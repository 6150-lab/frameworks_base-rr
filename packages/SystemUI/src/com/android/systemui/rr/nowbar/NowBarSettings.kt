/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Now Bar switches, kept in Settings.System and set from Remixer › Lock screen › Now Bar. */
class NowBarSettings(private val context: Context) {
    var enabled = true
        private set
    var wave = true
        private set
    var aod = true
        private set
    var chip = true
        private set
    /** Where live scores come from: [NowBarScores.SOURCE_OFF], At a Glance or Smartspacer. */
    var scores = NowBarScores.SOURCE_GLANCE
        private set
    private val shown = HashMap<NowBarType, Boolean>()

    init {
        read()
    }

    fun read() {
        enabled = get(KEY_ENABLED, true)
        wave = get(KEY_WAVE, true)
        aod = get(KEY_AOD, true)
        chip = get(KEY_CHIP, true)
        scores = Settings.System.getIntForUser(context.contentResolver, KEY_SCORES,
            NowBarScores.SOURCE_GLANCE, UserHandle.USER_CURRENT)
        shown[NowBarType.SPORTS] = scores != NowBarScores.SOURCE_OFF
        shown[NowBarType.MUSIC] = get(KEY_MUSIC, true)
        shown[NowBarType.TIMER] = get(KEY_TIMERS, true)
        shown[NowBarType.CALL] = get(KEY_CALLS, true)
        val live = get(KEY_LIVE, true)
        shown[NowBarType.NAVIGATION] = live
        shown[NowBarType.LIVE] = live
        shown[NowBarType.RECORDING] = get(KEY_RECORDING, true)
        shown[NowBarType.CHARGING] = get(KEY_CHARGING, false)
        shown[NowBarType.FLASHLIGHT] = get(KEY_FLASHLIGHT, false)
    }

    fun shows(type: NowBarType) = enabled && shown[type] == true

    private fun get(key: String, def: Boolean) = get(context, key, def)

    companion object {
        const val KEY_ENABLED = "now_bar"
        const val KEY_MUSIC = "now_bar_music"
        const val KEY_TIMERS = "now_bar_timers"
        const val KEY_CALLS = "now_bar_calls"
        const val KEY_LIVE = "now_bar_live_updates"
        const val KEY_RECORDING = "now_bar_recording"
        const val KEY_CHARGING = "now_bar_charging"
        const val KEY_FLASHLIGHT = "now_bar_flashlight"
        const val KEY_WAVE = "now_bar_wave"
        const val KEY_AOD = "now_bar_aod"
        const val KEY_CHIP = "now_bar_chip"
        const val KEY_SCORES = "now_bar_scores"
        val KEYS = arrayOf(
            KEY_ENABLED, KEY_MUSIC, KEY_TIMERS, KEY_CALLS, KEY_LIVE, KEY_RECORDING, KEY_CHARGING,
            KEY_FLASHLIGHT, KEY_WAVE, KEY_AOD, KEY_CHIP, KEY_SCORES,
        )

        private fun get(context: Context, key: String, def: Boolean) =
            Settings.System.getIntForUser(
                context.contentResolver, key, if (def) 1 else 0, UserHandle.USER_CURRENT
            ) == 1

        /** With music in the Now Bar, the lock screen player steps aside. */
        @JvmStatic
        fun hidesLockscreenMedia(context: Context) =
            get(context, KEY_ENABLED, true) && get(context, KEY_MUSIC, true)

        /** Fires when [hidesLockscreenMedia] may have changed. */
        @JvmStatic
        fun lockscreenMediaChanges(context: Context): Flow<Unit> = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            for (key in arrayOf(KEY_ENABLED, KEY_MUSIC)) {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
                )
            }
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
}
