/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nightstand

import android.app.AutomaticZenRule
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Nightstand: on the charger at night the always-on display shows the Nightstand stage, dim.
 * Starts on a charger inside the night window, on a schedule, or while a Bedtime Mode is on;
 * ends when the charger goes, the alarm rings, or the window closes. While on it writes
 * [KEY_ACTIVE], which the Lock Stage section and the doze brightness read, turns the always-on
 * display on if it was off, and swaps in the Nightstand clock face when both faces are custom.
 */
@SysUISingleton
class NightstandController
@Inject
constructor(
    private val context: Context,
    private val batteryController: BatteryController,
    private val nextAlarmController: NextAlarmController,
    private val zenModeInteractor: ZenModeInteractor,
    @Application private val scope: CoroutineScope,
) : CoreStartable {
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = context.contentResolver

    private var mode = OFF
    private var from = 22 * 60
    private var to = 7 * 60
    private var face = -1
    private var pluggedIn = false
    private var bedtime = false
    private var active = false
    private var nextAlarm = 0L

    private val tick = Runnable { evaluate() }

    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            if (this@NightstandController.pluggedIn == pluggedIn) return
            this@NightstandController.pluggedIn = pluggedIn
            evaluate()
        }
    }

    private val alarmCallback = NextAlarmController.NextAlarmChangeCallback { alarm ->
        val trigger = alarm?.triggerTime ?: 0L
        // The alarm that was set rang (or was dismissed): the night is over.
        if (active && nextAlarm != 0L && trigger != nextAlarm && System.currentTimeMillis() >= nextAlarm - 60_000) {
            Log.i(TAG, "Alarm went; Nightstand ends")
            set(false)
        }
        nextAlarm = trigger
    }

    override fun start() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                read()
                evaluate()
            }
        }
        for (key in arrayOf(KEY_MODE, KEY_FROM, KEY_TO, KEY_FACE)) {
            resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
        }
        read()
        // A reboot while active leaves the flag set; clear it and start over.
        if (Settings.System.getIntForUser(resolver, KEY_ACTIVE, 0, UserHandle.USER_CURRENT) == 1) restore()
        batteryController.addCallback(batteryCallback)
        nextAlarmController.addCallback(alarmCallback)
        scope.launch {
            zenModeInteractor.modes
                .map { modes -> modes.any { it.isActive && it.type == AutomaticZenRule.TYPE_BEDTIME } }
                .distinctUntilChanged()
                .collect { on ->
                    handler.post {
                        bedtime = on
                        evaluate()
                    }
                }
        }
        evaluate()
    }

    private fun read() {
        mode = Settings.System.getIntForUser(resolver, KEY_MODE, OFF, UserHandle.USER_CURRENT)
        from = Settings.System.getIntForUser(resolver, KEY_FROM, 22 * 60, UserHandle.USER_CURRENT)
        to = Settings.System.getIntForUser(resolver, KEY_TO, 7 * 60, UserHandle.USER_CURRENT)
        face = Settings.System.getIntForUser(resolver, KEY_FACE, -1, UserHandle.USER_CURRENT)
    }

    /** True inside the window, which may cross midnight. */
    private fun inWindow(): Boolean {
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        return if (from <= to) now in from until to else now >= from || now < to
    }

    private fun evaluate() {
        handler.removeCallbacks(tick)
        val wanted = when (mode) {
            CHARGER_AT_NIGHT -> pluggedIn && inWindow()
            SCHEDULE -> inWindow()
            BEDTIME -> bedtime
            else -> false
        }
        if (wanted != active) set(wanted)
        if (mode == CHARGER_AT_NIGHT || mode == SCHEDULE) {
            // Wake at the next window edge.
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val edge = listOf(from, to).map { if (it > minute) it - minute else it + 24 * 60 - minute }.min()
            handler.postDelayed(tick, edge * 60_000L - now.get(Calendar.SECOND) * 1000L + 1000L)
        }
    }

    private fun set(on: Boolean) {
        active = on
        Log.i(TAG, "Nightstand ${if (on) "on" else "off"}")
        if (on) {
            val aod = Settings.Secure.getIntForUser(resolver, Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver, KEY_PREV_AOD, aod, UserHandle.USER_CURRENT)
            if (aod == 0) Settings.Secure.putIntForUser(resolver, Settings.Secure.DOZE_ALWAYS_ON, 1, UserHandle.USER_CURRENT)
            val current = Settings.Secure.getIntForUser(resolver, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, 0, UserHandle.USER_CURRENT)
            // Only custom-to-custom switches are live; the system clock stays.
            if (face > 0 && current > 0 && face != current) {
                Settings.System.putIntForUser(resolver, KEY_PREV_FACE, current, UserHandle.USER_CURRENT)
                Settings.Secure.putIntForUser(resolver, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, face, UserHandle.USER_CURRENT)
            } else {
                Settings.System.putIntForUser(resolver, KEY_PREV_FACE, -1, UserHandle.USER_CURRENT)
            }
            Settings.System.putIntForUser(resolver, KEY_ACTIVE, 1, UserHandle.USER_CURRENT)
        } else {
            restore()
        }
    }

    /** Puts the always-on display and the clock face back the way they were. */
    private fun restore() {
        active = false
        if (Settings.System.getIntForUser(resolver, KEY_PREV_AOD, 1, UserHandle.USER_CURRENT) == 0) {
            Settings.Secure.putIntForUser(resolver, Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT)
        }
        val prevFace = Settings.System.getIntForUser(resolver, KEY_PREV_FACE, -1, UserHandle.USER_CURRENT)
        if (prevFace > 0) {
            Settings.Secure.putIntForUser(resolver, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, prevFace, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver, KEY_PREV_FACE, -1, UserHandle.USER_CURRENT)
        }
        Settings.System.putIntForUser(resolver, KEY_PREV_AOD, 1, UserHandle.USER_CURRENT)
        Settings.System.putIntForUser(resolver, KEY_ACTIVE, 0, UserHandle.USER_CURRENT)
    }

    companion object {
        private const val TAG = "Nightstand"
        const val KEY_MODE = "rr_nightstand"
        const val KEY_FROM = "rr_nightstand_from"
        const val KEY_TO = "rr_nightstand_to"
        const val KEY_FACE = "rr_nightstand_face"
        /** The Nightstand stage spec, the same JSON as rr_lock_stage. */
        const val KEY_STAGE = "rr_lock_stage_night"
        /** 1 while Nightstand is on; read by the Lock Stage section and doze brightness. */
        const val KEY_ACTIVE = "rr_nightstand_active"
        /** The brightness cap on the always-on display while on, 1 to 255. */
        const val KEY_BRIGHTNESS = "rr_nightstand_brightness"
        private const val KEY_PREV_AOD = "rr_nightstand_prev_aod"
        private const val KEY_PREV_FACE = "rr_nightstand_prev_face"
        const val OFF = 0
        const val CHARGER_AT_NIGHT = 1
        const val SCHEDULE = 2
        const val BEDTIME = 3
    }
}
