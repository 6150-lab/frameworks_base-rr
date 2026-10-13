/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgelight

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.android.settingslib.Utils
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * Edge light moments: incoming calls, alarms and timers, plugging in, low battery and unlocking.
 * Each one plays in its own overlay window, so it shows over the lock screen, the always-on
 * display and full screen call and alarm screens.
 */
@SysUISingleton
class EdgeLightMomentsController
@Inject
constructor(
    private val context: Context,
    private val batteryController: BatteryController,
    private val keyguardStateController: KeyguardStateController,
    private val broadcastDispatcher: BroadcastDispatcher,
) : CoreStartable {

    private enum class Moment(
        val key: String,
        val kind: EdgeMomentView.Kind,
        val defaultOn: Boolean,
        /** 0 follows the accent. */
        val defaultColor: Int,
    ) {
        CALL("edge_light_moment_calls", EdgeMomentView.Kind.PULSE, true, 0),
        ALARM("edge_light_moment_alarms", EdgeMomentView.Kind.PULSE, true, AMBER),
        PLUGGED("edge_light_moment_plugged", EdgeMomentView.Kind.RUN_UP, true, GREEN),
        BATTERY_LOW("edge_light_moment_battery_low", EdgeMomentView.Kind.DOUBLE, false, RED),
        UNLOCK("edge_light_moment_unlock", EdgeMomentView.Kind.SWEEP, false, 0);

        val colorKey = key + "_color"
        val ringing get() = kind == EdgeMomentView.Kind.PULSE
    }

    private val handler = Handler(Looper.getMainLooper())
    private val resolver = context.contentResolver
    private lateinit var display: Display
    private lateinit var windowManager: WindowManager
    private lateinit var windowContext: Context
    private var view: EdgeMomentView? = null
    private var current: Moment? = null

    private var enabled = false
    private var strokeWidth = 8
    private val momentOn = HashMap<Moment, Boolean>()
    private val momentColor = HashMap<Moment, Int>()

    private var callRinging = false
    private var ringtonePlaying = false
    private var silenced = false
    private var alarmPlaying = false
    private var level = -1
    private var pluggedIn = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val ringing = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ==
                        TelephonyManager.EXTRA_STATE_RINGING
                    if (!ringing) silenced = false
                    callRinging = ringing
                }
                Intent.ACTION_USER_SWITCHED -> readSettings()
            }
            refresh()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            // A one-shot ends when the screen turns off; ringing ones wait for it to come on.
            if (!canShow() && current?.ringing == false) hide()
            refresh()
        }

        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val ringtone = configs.any { it.isActive && it.usage() == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
            // The ringtone stopping while the call still rings means it was silenced.
            if (ringtonePlaying && !ringtone && callRinging) silenced = true
            ringtonePlaying = ringtone
            alarmPlaying = configs.any { it.isActive && it.usage() == AudioAttributes.USAGE_ALARM }
            refresh()
        }
    }

    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            val previous = this@EdgeLightMomentsController.level
            val plugged = pluggedIn && !this@EdgeLightMomentsController.pluggedIn
            this@EdgeLightMomentsController.level = level
            this@EdgeLightMomentsController.pluggedIn = pluggedIn
            if (previous < 0) return
            if (plugged) {
                oneShot(Moment.PLUGGED)
            } else if (!pluggedIn && level < previous &&
                LOW_LEVELS.any { previous > it && level <= it }) {
                oneShot(Moment.BATTERY_LOW)
            }
        }
    }

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardGoingAwayChanged() {
            if (keyguardStateController.isKeyguardGoingAway) oneShot(Moment.UNLOCK)
        }
    }

    override fun start() {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        displayManager.registerDisplayListener(
            displayListener, handler, DisplayManager.EVENT_TYPE_DISPLAY_STATE
        )
        windowContext = context.createWindowContext(
            display, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null
        )
        windowManager = windowContext.getSystemService(WindowManager::class.java)

        readSettings()
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                readSettings()
                refresh()
            }
        }
        for (key in settingKeys()) {
            resolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                addAction(Intent.ACTION_USER_SWITCHED)
            },
        )
        context.getSystemService(AudioManager::class.java)
            ?.registerAudioPlaybackCallback(playbackCallback, handler)
        batteryController.addCallback(batteryCallback)
        keyguardStateController.addCallback(keyguardCallback)
    }

    private fun settingKeys() =
        listOf(KEY_ENABLED, KEY_STROKE_WIDTH) + Moment.values().flatMap { listOf(it.key, it.colorKey) }

    private fun readSettings() {
        fun int(key: String, def: Int) =
            Settings.System.getIntForUser(resolver, key, def, UserHandle.USER_CURRENT)
        enabled = int(KEY_ENABLED, 0) == 1
        strokeWidth = int(KEY_STROKE_WIDTH, 8)
        for (moment in Moment.values()) {
            momentOn[moment] = int(moment.key, if (moment.defaultOn) 1 else 0) == 1
            momentColor[moment] = int(moment.colorKey, 0)
        }
        view?.strokeWidthDp = strokeWidth
    }

    private fun isOn(moment: Moment) = enabled && momentOn[moment] == true

    /** Only while the screen is on or the always-on display can still draw. */
    private fun canShow() = display.state == Display.STATE_ON || display.state == Display.STATE_DOZE

    private fun callActive(): Boolean {
        if (!isOn(Moment.CALL) || silenced) return false
        val ringing = callRinging ||
            (ringtonePlaying && context.getSystemService(TelecomManager::class.java)?.isRinging == true)
        // In Do Not Disturb only calls that actually ring light up.
        return ringing && (ringtonePlaying || !interruptionsFiltered())
    }

    private fun refresh() {
        val ringing = when {
            !canShow() -> null
            callActive() -> Moment.CALL
            isOn(Moment.ALARM) && alarmPlaying -> Moment.ALARM
            else -> null
        }
        if (ringing != null) {
            if (current != ringing) show(ringing)
        } else if (current?.ringing == true) {
            hide()
        }
    }

    private fun oneShot(moment: Moment) {
        if (!isOn(moment) || !canShow() || current?.ringing == true) return
        if (Settings.Global.getInt(resolver, Settings.Global.ZEN_MODE, 0) != 0) return
        show(moment)
    }

    private fun interruptionsFiltered(): Boolean {
        val filter = context.getSystemService(NotificationManager::class.java)
            ?.currentInterruptionFilter ?: return false
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun colorFor(moment: Moment): Int {
        val color = momentColor[moment] ?: 0
        if (color != 0) return color
        return if (moment.defaultColor != 0) moment.defaultColor
        else Utils.getColorAccentDefaultColor(context)
    }

    private fun show(moment: Moment) {
        val view = view ?: addWindow() ?: return
        current = moment
        view.strokeWidthDp = strokeWidth
        view.play(moment.kind, colorFor(moment)) {
            if (current == moment) hide()
        }
    }

    private fun hide() {
        current = null
        val view = view ?: return
        view.stop()
        try {
            windowManager.removeView(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Moment window already gone", e)
        }
        this.view = null
    }

    private fun addWindow(): EdgeMomentView? {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.setTrustedOverlay()
        lp.title = "RREdgeMoments"
        val view = EdgeMomentView(windowContext)
        return try {
            windowManager.addView(view, lp)
            this.view = view
            view
        } catch (e: RuntimeException) {
            Log.w(TAG, "Moment window not added", e)
            null
        }
    }

    private fun AudioPlaybackConfiguration.usage() = audioAttributes.usage

    companion object {
        private const val TAG = "EdgeLightMoments"
        private const val KEY_ENABLED = Settings.System.EDGE_LIGHT_ENABLED
        private const val KEY_STROKE_WIDTH = Settings.System.EDGE_LIGHT_STROKE_WIDTH
        private const val AMBER = 0xFFFFB02E.toInt()
        private const val GREEN = 0xFF3DDC84.toInt()
        private const val RED = 0xFFFF3030.toInt()
        private val LOW_LEVELS = intArrayOf(15, 5)
    }
}
