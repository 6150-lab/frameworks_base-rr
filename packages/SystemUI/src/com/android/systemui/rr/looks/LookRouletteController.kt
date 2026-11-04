/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.looks

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.time.LocalDate
import javax.inject.Inject

/**
 * Look roulette: a different saved Look on every unlock, or one a day. It never picks a Look
 * that moves between the system clock and a custom face: Remixer restarts SystemUI for that,
 * the keyguard comes back, the next unlock spins again, and so on.
 */
@SysUISingleton
class LookRouletteController
@Inject
constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private var mode = OFF
    private var wasShowing = false
    private var startedAt = 0L
    private var lastSpin = 0L

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            val showing = keyguardStateController.isShowing
            if (wasShowing && !showing) spin()
            wasShowing = showing
        }
    }

    override fun start() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = read()
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(KEY_MODE), false, observer, UserHandle.USER_ALL
        )
        read()
        startedAt = SystemClock.elapsedRealtime()
        wasShowing = keyguardStateController.isShowing
        keyguardStateController.addCallback(keyguardCallback)
    }

    private fun read() {
        mode = Settings.System.getIntForUser(context.contentResolver, KEY_MODE, OFF, UserHandle.USER_CURRENT)
    }

    private fun spin() {
        if (mode == OFF) return
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt < StartGraceMillis || now - lastSpin < CooldownMillis) return
        if (mode == DAILY) {
            val today = LocalDate.now().toString()
            val last = Settings.System.getStringForUser(context.contentResolver, KEY_DAY, UserHandle.USER_CURRENT)
            if (today == last) return
            Settings.System.putStringForUser(context.contentResolver, KEY_DAY, today, UserHandle.USER_CURRENT)
        }
        val current = Looks.current(context)
        val systemClock = Settings.Secure.getIntForUser(context.contentResolver,
            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, 0, UserHandle.USER_CURRENT) == 0
        val choices = Looks.looks(context).filter { look ->
            look.optString("name") != current &&
                (!look.has("clock_face") || (look.optInt("clock_face") == 0) == systemClock)
        }
        if (choices.isEmpty()) return
        val pick = choices.random().optString("name")
        lastSpin = now
        Log.i(TAG, "Roulette picks $pick")
        Looks.apply(context, pick)
    }

    companion object {
        private const val TAG = "LookRoulette"
        const val KEY_MODE = "rr_look_roulette"
        private const val KEY_DAY = "rr_look_roulette_day"
        const val OFF = 0
        const val EVERY_UNLOCK = 1
        const val DAILY = 2
        /** No spin right after SystemUI starts, nor two within a minute. */
        private const val StartGraceMillis = 20_000L
        private const val CooldownMillis = 60_000L
    }
}
