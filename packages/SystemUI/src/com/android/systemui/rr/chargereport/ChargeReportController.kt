/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.chargereport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.settingslib.utils.StringUtil
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Charge report: while plugged in, the battery's current, voltage and temperature are read every
 * 30 seconds. On unplugging, a notification sums it up and the session joins a 7 day history in
 * charge_report_history, which Remixer lists.
 */
@SysUISingleton
class ChargeReportController
@Inject
constructor(
    private val context: Context,
    private val batteryController: BatteryController,
) : CoreStartable, BatteryController.BatteryStateChangeCallback {

    private val handler = Handler(Looper.getMainLooper())
    private var pluggedIn = false
    private var level = -1
    private var startLevel = 0
    private var startTime = 0L
    private var peakWatts = 0f
    private var wattSum = 0f
    private var samples = 0
    private var maxTemp = 0f
    private var curve = JSONArray()

    private val sampler = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun start() {
        batteryController.addCallback(this)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("ChargeReport: pluggedIn=$pluggedIn from=$startLevel samples=$samples peak=$peakWatts")
    }

    override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
        val first = this.level < 0
        this.level = level
        if (pluggedIn == this.pluggedIn) return
        this.pluggedIn = pluggedIn
        if (pluggedIn) {
            startLevel = level
            startTime = System.currentTimeMillis()
            peakWatts = 0f
            wattSum = 0f
            samples = 0
            maxTemp = 0f
            curve = JSONArray()
            handler.post(sampler)
        } else {
            handler.removeCallbacks(sampler)
            if (!first) finish()
        }
    }

    private fun sample() {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val millivolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val microamps = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        // Some drivers report mA, most µA.
        val amps = abs(microamps) / (if (abs(microamps) > 20_000) 1_000_000f else 1_000f)
        val watts = amps * millivolts / 1000f
        if (watts in 0.1f..300f) {
            peakWatts = maxOf(peakWatts, watts)
            wattSum += watts
            samples++
            if (curve.length() < MAX_POINTS) {
                curve.put(JSONArray().put(((System.currentTimeMillis() - startTime) / 1000))
                    .put(Math.round(watts * 10) / 10.0).put(level))
            }
        }
        if (temp > 0) maxTemp = maxOf(maxTemp, temp)
    }

    private fun finish() {
        val added = level - startLevel
        val minutes = (System.currentTimeMillis() - startTime) / 60000L
        val resolver = context.contentResolver
        if (Settings.System.getIntForUser(resolver, KEY_ENABLED, 1, UserHandle.USER_CURRENT) != 1) return
        val minimum = Settings.System.getIntForUser(resolver, KEY_MINIMUM, 10, UserHandle.USER_CURRENT)
        if (added < minimum || minutes < 1) return
        val average = if (samples > 0) wattSum / samples else 0f
        saveHistory(added, minutes, average)
        val duration = StringUtil.formatElapsedTime(context, minutes * 60_000.0, false, true)
        val summary = context.getString(R.string.charge_report_summary, added, duration,
            String.format("%.1f", peakWatts), String.format("%.0f", maxTemp))
        val details = context.getString(R.string.charge_report_details, startLevel, level,
            String.format("%.1f", average))
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.charge_report_channel), NotificationManager.IMPORTANCE_DEFAULT))
        manager.notifyAsUser(TAG, NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.rr_nowbar_bolt)
            .setContentTitle(context.getString(R.string.charge_report_title))
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText("$summary\n$details"))
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build(), UserHandle.CURRENT)
    }

    private fun saveHistory(added: Int, minutes: Long, average: Float) {
        val resolver = context.contentResolver
        val old = try {
            JSONArray(Settings.System.getStringForUser(resolver, KEY_HISTORY, UserHandle.USER_CURRENT) ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val history = JSONArray()
        history.put(JSONObject()
            .put("start", startTime).put("from", startLevel).put("to", level)
            .put("minutes", minutes).put("peak", Math.round(peakWatts * 10) / 10.0)
            .put("avg", Math.round(average * 10) / 10.0).put("temp", Math.round(maxTemp * 10) / 10.0)
            .put("curve", curve))
        for (i in 0 until old.length()) {
            val entry = old.optJSONObject(i) ?: continue
            if (entry.optLong("start") >= cutoff && history.length() < MAX_SESSIONS) history.put(entry)
        }
        Settings.System.putStringForUser(resolver, KEY_HISTORY, history.toString(), UserHandle.USER_CURRENT)
    }

    companion object {
        private const val TAG = "ChargeReport"
        private const val CHANNEL = "charge_report"
        private const val NOTIFICATION_ID = 0x5252
        const val KEY_ENABLED = "charge_report"
        /** Only report charges that added at least this many percent. */
        const val KEY_MINIMUM = "charge_report_min"
        /** JSON, newest first, for Remixer. */
        const val KEY_HISTORY = "charge_report_history"
        private const val SAMPLE_MS = 30_000L
        private const val TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_SESSIONS = 20
        private const val MAX_POINTS = 240
    }
}
