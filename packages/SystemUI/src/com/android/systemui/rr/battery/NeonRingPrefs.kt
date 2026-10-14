/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.battery

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lineageos.providers.LineageSettings

/** The neon ring battery's options, from Settings.System, shared by every battery view. */
object NeonRingPrefs {
    /** 0 by level (accent, red below 20%, amber in Battery Saver), 1 accent, 2 white. */
    const val KEY_COLOR = "status_bar_battery_ring_color"
    const val KEY_SPARK = "status_bar_battery_ring_spark"
    /** The Quick Settings battery tile shows the ring too, while the neon ring is the style. */
    const val KEY_TILE = "qs_battery_ring"
    const val COLOR_LEVEL = 0
    const val COLOR_ACCENT = 1
    const val COLOR_WHITE = 2
    private const val STYLE_NEON_RING = 4

    data class Prefs(val color: Int = COLOR_LEVEL, val spark: Boolean = true)

    @Volatile private var flow: MutableStateFlow<Prefs>? = null

    fun state(context: Context): StateFlow<Prefs> {
        flow?.let { return it }
        synchronized(this) {
            flow?.let { return it }
            val app = context.applicationContext
            val state = MutableStateFlow(read(app))
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        state.value = read(app)
                    }
                }
            for (key in arrayOf(KEY_COLOR, KEY_SPARK)) {
                app.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
            }
            flow = state
            return state
        }
    }

    private fun read(context: Context): Prefs {
        val resolver = context.contentResolver
        return Prefs(
            color = Settings.System.getIntForUser(resolver, KEY_COLOR, COLOR_LEVEL,
                UserHandle.USER_CURRENT),
            spark = Settings.System.getIntForUser(resolver, KEY_SPARK, 1,
                UserHandle.USER_CURRENT) == 1,
        )
    }

    /** Whether the battery tile draws the ring. */
    @JvmStatic
    fun tileRing(context: Context): Boolean {
        val resolver = context.contentResolver
        return Settings.System.getIntForUser(resolver, KEY_TILE, 1, UserHandle.USER_CURRENT) == 1 &&
            LineageSettings.System.getIntForUser(resolver,
                LineageSettings.System.STATUS_BAR_BATTERY_STYLE, 0, UserHandle.USER_CURRENT) ==
                STYLE_NEON_RING
    }
}
