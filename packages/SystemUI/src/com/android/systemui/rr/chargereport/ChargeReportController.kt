/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.chargereport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
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
 * 30 seconds. On unplugging, a notification sums it up with the curve, and the session joins the
 * history in charge_report_history (charge_report_keep days), which Remixer lists.
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
    private var plugged = 0
    private var curve = JSONArray()
    /** Every stride-th sample goes in the curve; doubles when the curve fills up. */
    private var stride = 1
    private var tick = 0

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
            plugged = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            curve = JSONArray()
            stride = 1
            tick = 0
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
            if (tick++ % stride == 0) {
                curve.put(JSONArray().put(((System.currentTimeMillis() - startTime) / 1000))
                    .put(Math.round(watts * 10) / 10.0).put(level).put(Math.round(temp * 10) / 10.0))
                if (curve.length() >= MAX_POINTS) {
                    val kept = JSONArray()
                    for (i in 0 until curve.length() step 2) kept.put(curve.get(i))
                    curve = kept
                    stride *= 2
                }
            }
        }
        if (temp > 0) maxTemp = maxOf(maxTemp, temp)
    }

    private fun chargerLabel() = context.getString(when (plugged) {
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.charge_report_wireless
        BatteryManager.BATTERY_PLUGGED_USB -> R.string.charge_report_usb
        BatteryManager.BATTERY_PLUGGED_DOCK -> R.string.charge_report_dock
        else -> R.string.charge_report_wired
    })

    /** Watts as a filled curve in the accent, battery level as a line, for the expanded report. */
    private fun curveBitmap(): Bitmap? {
        val n = curve.length()
        if (n < 2) return null
        val w = 1024f
        val h = 512f
        val pad = 24f
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = context.getColor(android.R.color.system_accent1_400)
        val end = maxOf(1.0, curve.getJSONArray(n - 1).optDouble(0))
        var peak = 1.0
        for (i in 0 until n) peak = maxOf(peak, curve.getJSONArray(i).optDouble(1))
        fun x(i: Int) = (pad + (w - 2 * pad) * curve.getJSONArray(i).optDouble(0) / end).toFloat()
        fun y(value: Double, max: Double) = (h - pad - (h - 2 * pad) * value / max).toFloat()
        val area = Path().apply {
            moveTo(x(0), h - pad)
            for (i in 0 until n) lineTo(x(i), y(curve.getJSONArray(i).optDouble(1), peak))
            lineTo(x(n - 1), h - pad)
            close()
        }
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (accent and 0x00FFFFFF) or 0x40000000
        })
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeJoin = Paint.Join.ROUND
            color = accent
        }
        canvas.drawPath(Path().apply {
            for (i in 0 until n) {
                val py = y(curve.getJSONArray(i).optDouble(1), peak)
                if (i == 0) moveTo(x(i), py) else lineTo(x(i), py)
            }
        }, line)
        line.strokeWidth = 4f
        line.color = context.getColor(android.R.color.system_neutral1_400)
        canvas.drawPath(Path().apply {
            for (i in 0 until n) {
                val py = y(curve.getJSONArray(i).optDouble(2), 100.0)
                if (i == 0) moveTo(x(i), py) else lineTo(x(i), py)
            }
        }, line)
        return bitmap
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
        val details = context.getString(R.string.charge_report_details, chargerLabel(), startLevel,
            level, String.format("%.1f", average))
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.charge_report_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ACTION_REPORTS).setPackage(SETTINGS)
            .putExtra(EXTRA_START, startTime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val graph = curveBitmap()
        val style = if (graph != null) {
            Notification.BigPictureStyle().bigPicture(graph).setSummaryText("$summary\n$details")
        } else {
            Notification.BigTextStyle().bigText("$summary\n$details")
        }
        manager.notifyAsUser(TAG, NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.rr_nowbar_bolt)
            .setContentTitle(context.getString(R.string.charge_report_title))
            .setContentText(summary)
            .setStyle(style)
            .setContentIntent(PendingIntent.getActivityAsUser(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, null,
                UserHandle.CURRENT))
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
        val days = Settings.System.getIntForUser(resolver, KEY_KEEP, 7, UserHandle.USER_CURRENT)
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 30) * DAY_MS
        val history = JSONArray()
        history.put(JSONObject()
            .put("start", startTime).put("from", startLevel).put("to", level)
            .put("minutes", minutes).put("peak", Math.round(peakWatts * 10) / 10.0)
            .put("avg", Math.round(average * 10) / 10.0).put("temp", Math.round(maxTemp * 10) / 10.0)
            .put("plugged", plugged).put("curve", thinned(curve)))
        for (i in 0 until old.length()) {
            val entry = old.optJSONObject(i) ?: continue
            if (entry.optLong("start") < cutoff || history.length() >= MAX_SESSIONS) continue
            entry.optJSONArray("curve")?.let { entry.put("curve", thinned(it)) }
            history.put(entry)
        }
        // The settings provider throws past 32K chars a value and 40K bytes a package: the
        // history stays well under, oldest sessions out first.
        var json = history.toString()
        while (json.length > MAX_CHARS && history.length() > 1) {
            history.remove(history.length() - 1)
            json = history.toString()
        }
        try {
            Settings.System.putStringForUser(resolver, KEY_HISTORY, json, UserHandle.USER_CURRENT)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Charge history not saved, keeping this session only", e)
            runCatching {
                Settings.System.putStringForUser(resolver, KEY_HISTORY,
                    JSONArray().put(history.get(0)).toString(), UserHandle.USER_CURRENT)
            }
        }
    }

    /** At most [STORED_POINTS] of [points], spread evenly; the notification draws them all. */
    private fun thinned(points: JSONArray): JSONArray {
        val n = points.length()
        if (n <= STORED_POINTS) return points
        val out = JSONArray()
        for (i in 0 until STORED_POINTS) out.put(points.opt(i * (n - 1) / (STORED_POINTS - 1)))
        return out
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
        /** Days of history to keep. */
        const val KEY_KEEP = "charge_report_keep"
        private const val ACTION_REPORTS = "com.resurrection.settings.CHARGE_REPORTS"
        private const val SETTINGS = "com.android.settings"
        private const val EXTRA_START = "charge_report_start"
        private const val SAMPLE_MS = 30_000L
        private const val TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_SESSIONS = 10
        /** Points sampled while charging; the history keeps [STORED_POINTS] of them. */
        private const val MAX_POINTS = 160
        private const val STORED_POINTS = 40
        private const val MAX_CHARS = 12_000
    }
}
