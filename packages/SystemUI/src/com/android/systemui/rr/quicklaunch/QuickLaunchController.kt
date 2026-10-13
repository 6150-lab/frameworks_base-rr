/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.quicklaunch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricSourceType
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.UdfpsController
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController
import com.android.systemui.res.R
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.hypot

/**
 * Fingerprint quick launch: unlock with the in-display sensor and keep the finger down, and a fan
 * of shortcuts opens above it. Slide to one and lift to open it; lift without sliding to just
 * unlock.
 */
@SysUISingleton
class QuickLaunchController
@Inject
constructor(
    private val context: Context,
    private val authController: AuthController,
    private val udfpsControllerProvider: Provider<UdfpsController>,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardStateController: KeyguardStateController,
    private val flashlightController: FlashlightController,
    private val qrCodeScannerController: QRCodeScannerController,
    private val activityStarter: ActivityStarter,
) : CoreStartable {

    private class Entry(val token: String, val item: QuickLaunchView.Item)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private var udfpsController: UdfpsController? = null

    private var enabled = false
    private var holdMs = DEFAULT_HOLD_MS
    private var haptics = true
    private var tokens = DEFAULT_SHORTCUTS

    private var monitor: InputMonitorCompat? = null
    private var receiver: InputChannelCompat.InputEventReceiver? = null
    private var tracking = false
    private var pointerDown = false
    private var authenticated = false
    private var sensorX = 0f
    private var sensorY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var view: QuickLaunchView? = null
    private var entries: List<Entry> = emptyList()

    private val showFan = Runnable { showFan() }
    private val giveUp = Runnable { if (!authenticated) stopTracking() }

    private val authCallback = object : AuthController.Callback {
        override fun onAllAuthenticatorsRegistered(modality: Int) = updateUdfps()

        override fun onUdfpsLocationChanged(udfpsOverlayParams: UdfpsOverlayParams) = updateUdfps()
    }

    private val udfpsCallback = object : UdfpsController.Callback {
        override fun onFingerDown() {
            if (enabled && !tracking && keyguardStateController.isShowing) startTracking()
        }

        override fun onFingerUp() {
            // Once authenticated, the input monitor tracks the finger instead.
            if (tracking && !authenticated) stopTracking()
        }
    }

    private val updateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onBiometricAuthenticated(
            userId: Int,
            biometricSourceType: BiometricSourceType,
            isStrongBiometric: Boolean,
        ) {
            if (biometricSourceType != BiometricSourceType.FINGERPRINT || !tracking ||
                !pointerDown || authenticated) return
            authenticated = true
            handler.removeCallbacks(giveUp)
            handler.postDelayed(showFan, holdMs)
        }
    }

    override fun start() {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = context.createWindowContext(
            display, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null
        )
        windowManager = windowContext.getSystemService(WindowManager::class.java)

        readSettings()
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = readSettings()
        }
        for (key in arrayOf(KEY_ENABLED, KEY_SHORTCUTS, KEY_HOLD, KEY_HAPTICS)) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        qrCodeScannerController.registerQRCodeScannerChangeObservers(
            QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE
        )
        authController.addCallback(authCallback)
        updateUdfps()
        keyguardUpdateMonitor.registerCallback(updateMonitorCallback)
    }

    private fun readSettings() {
        val resolver = context.contentResolver
        enabled = Settings.System.getIntForUser(resolver, KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        holdMs = Settings.System.getIntForUser(
            resolver, KEY_HOLD, DEFAULT_HOLD_MS.toInt(), UserHandle.USER_CURRENT
        ).coerceIn(200, 1500).toLong()
        haptics = Settings.System.getIntForUser(resolver, KEY_HAPTICS, 1, UserHandle.USER_CURRENT) == 1
        tokens = Settings.System.getStringForUser(resolver, KEY_SHORTCUTS, UserHandle.USER_CURRENT)
            ?: DEFAULT_SHORTCUTS
        if (!enabled && tracking) stopTracking()
    }

    private fun updateUdfps() {
        if (udfpsController != null || authController.udfpsProps.isNullOrEmpty()) return
        udfpsController = udfpsControllerProvider.get().also { it.addCallback(udfpsCallback) }
    }

    private fun startTracking() {
        val location = authController.udfpsLocation ?: return
        sensorX = location.x.toFloat()
        sensorY = location.y.toFloat()
        lastX = sensorX
        lastY = sensorY
        tracking = true
        pointerDown = true
        authenticated = false
        try {
            monitor = InputMonitorCompat(TAG, Display.DEFAULT_DISPLAY).also {
                receiver = it.getInputReceiver(
                    Looper.getMainLooper(), Choreographer.getInstance()
                ) { event -> onInputEvent(event) }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No input monitor", e)
            stopTracking()
            return
        }
        handler.postDelayed(giveUp, AUTH_TIMEOUT_MS)
    }

    private fun stopTracking() {
        handler.removeCallbacks(showFan)
        handler.removeCallbacks(giveUp)
        receiver?.dispose()
        receiver = null
        monitor?.dispose()
        monitor = null
        tracking = false
        pointerDown = false
        authenticated = false
        hideFan()
    }

    private fun onInputEvent(event: InputEvent) {
        if (event !is MotionEvent || !tracking) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = event.rawX
                lastY = event.rawY
                val fan = view
                if (fan != null) {
                    if (fan.select(lastX, lastY) && fan.selected >= 0 && haptics) {
                        fan.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                    }
                } else if (authenticated && movedAway()) {
                    // A swipe, not a hold.
                    stopTracking()
                }
            }
            MotionEvent.ACTION_UP -> finish(launch = true)
            MotionEvent.ACTION_CANCEL -> finish(launch = false)
        }
    }

    private fun movedAway() =
        hypot(lastX - sensorX, lastY - sensorY) > maxOf(authController.udfpsRadius, 1f) * 3f

    private fun finish(launch: Boolean) {
        val choice = view?.selected ?: -1
        val entry = if (launch && choice in entries.indices) entries[choice] else null
        stopTracking()
        entry?.let { launch(it.token) }
    }

    private fun showFan() {
        if (!tracking || !pointerDown || movedAway()) {
            stopTracking()
            return
        }
        entries = buildEntries()
        if (entries.isEmpty()) {
            stopTracking()
            return
        }
        // The rest of this touch is ours; the keyguard gets a cancel.
        monitor?.pilferPointers()
        val fan = QuickLaunchView(windowContext)
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
        lp.title = "RRQuickLaunch"
        try {
            windowManager.addView(fan, lp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Quick launch window not added", e)
            stopTracking()
            return
        }
        view = fan
        fan.show(entries.map { it.item }, sensorX, sensorY)
        if (haptics) {
            fan.post { fan.performHapticFeedback(HapticFeedbackConstants.GESTURE_START) }
        }
    }

    private fun hideFan() {
        val fan = view ?: return
        view = null
        try {
            windowManager.removeView(fan)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Quick launch window already gone", e)
        }
    }

    private fun buildEntries(): List<Entry> {
        val pm = context.packageManager
        fun builtIn(token: String, label: Int, icon: Int) =
            Entry(token, QuickLaunchView.Item(context.getString(label), context.getDrawable(icon)!!.mutate()))
        return tokens.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { token ->
            when {
                token == CAMERA -> builtIn(token, R.string.quick_launch_camera, R.drawable.rr_ql_camera)
                token == VIDEO -> builtIn(token, R.string.quick_launch_video, R.drawable.rr_ql_video)
                token == FLASHLIGHT -> if (flashlightController.hasFlashlight()) {
                    builtIn(token, R.string.quick_launch_flashlight, R.drawable.rr_ql_flashlight)
                } else null
                token == QR -> if (qrCodeScannerController.isAbleToLaunchScannerActivity) {
                    builtIn(token, R.string.quick_launch_qr, R.drawable.rr_ql_qr)
                } else null
                token == TIMER -> builtIn(token, R.string.quick_launch_timer, R.drawable.rr_ql_timer)
                token.startsWith(APP) -> {
                    val component = ComponentName.unflattenFromString(token.removePrefix(APP))
                    try {
                        val info = pm.getActivityInfo(component ?: return@mapNotNull null, 0)
                        Entry(token, QuickLaunchView.Item(info.loadLabel(pm), info.loadIcon(pm)))
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                else -> null
            }
        }.take(MAX_SHORTCUTS)
    }

    private fun launch(token: String) {
        when {
            token == FLASHLIGHT -> flashlightController.setFlashlight(!flashlightController.isEnabled)
            token == QR -> qrCodeScannerController.intent?.let { start(Intent(it)) }
            token == CAMERA -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            token == VIDEO -> start(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
            token == TIMER -> start(Intent(AlarmClock.ACTION_SET_TIMER))
            token.startsWith(APP) ->
                ComponentName.unflattenFromString(token.removePrefix(APP))?.let {
                    start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(it))
                }
        }
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activityStarter.postStartActivityDismissingKeyguard(intent, 0)
    }

    companion object {
        private const val TAG = "RRQuickLaunch"
        const val KEY_ENABLED = "fp_quick_launch"
        /** Comma-separated: camera, video, flashlight, qr, timer or app:<component>. */
        const val KEY_SHORTCUTS = "fp_quick_launch_shortcuts"
        const val KEY_HOLD = "fp_quick_launch_hold"
        const val KEY_HAPTICS = "fp_quick_launch_haptics"

        private const val CAMERA = "camera"
        private const val VIDEO = "video"
        private const val FLASHLIGHT = "flashlight"
        private const val QR = "qr"
        private const val TIMER = "timer"
        private const val APP = "app:"
        private const val DEFAULT_SHORTCUTS = "camera,flashlight,qr,timer"
        private const val DEFAULT_HOLD_MS = 500L
        private const val AUTH_TIMEOUT_MS = 3000L
        private const val MAX_SHORTCUTS = 5
    }
}
