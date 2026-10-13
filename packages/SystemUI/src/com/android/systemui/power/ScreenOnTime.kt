/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.power

import android.content.Context
import android.os.BatteryConsumer
import android.os.BatteryStatsManager
import android.os.BatteryUsageStats
import android.os.BatteryUsageStatsQuery
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.settingslib.utils.StringUtil
import com.android.systemui.res.R
import java.util.concurrent.Executors

/**
 * Screen-on time since the battery stats last reset, which happens after a full charge.
 * Reads are cached; a stale read returns the last value and refreshes it in the background.
 */
object ScreenOnTime {
    private const val TAG = "ScreenOnTime"
    /** Settings.System switch for showing it on the lock screen and the battery tile. */
    const val SETTING = "screen_on_time_info"
    private const val MAX_AGE_MS = 60_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cachedMs = -1L
    @Volatile private var fetchedAt = 0L
    @Volatile private var fetching = false

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        Settings.System.getIntForUser(
            context.contentResolver, SETTING, 1, UserHandle.USER_CURRENT
        ) == 1

    /**
     * "SOT 5 hr 12 min", or null before the first read. [onUpdate] runs on the main thread when
     * a background refresh brings a new value.
     */
    @JvmStatic
    @JvmOverloads
    fun label(context: Context, onUpdate: Runnable? = null): String? {
        val now = SystemClock.elapsedRealtime()
        if (!fetching && (cachedMs < 0 || now - fetchedAt > MAX_AGE_MS)) {
            fetching = true
            val app = context.applicationContext
            executor.execute {
                val ms = read(app)
                fetchedAt = SystemClock.elapsedRealtime()
                fetching = false
                if (ms >= 0 && ms != cachedMs) {
                    cachedMs = ms
                    onUpdate?.let { mainHandler.post(it) }
                }
            }
        }
        val ms = cachedMs
        if (ms < 0) return null
        val duration = StringUtil.formatElapsedTime(context, ms.toDouble(), false, true)
        return context.getString(R.string.screen_on_time_label, duration)
    }

    private fun read(context: Context): Long {
        val manager = context.getSystemService(BatteryStatsManager::class.java) ?: return -1
        return try {
            val query = BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(MAX_AGE_MS).build()
            manager.getBatteryUsageStats(query).use { stats ->
                stats.getAggregateBatteryConsumer(
                    BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE
                ).getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't read battery stats", e)
            -1
        }
    }
}
