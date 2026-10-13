/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.gesture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.gesture.Gesture
import android.gesture.GestureOverlayView
import android.gesture.GesturePoint
import android.gesture.GestureStore
import android.gesture.GestureStroke
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray

/**
 * Gesture anywhere: swipe in from a strip on part of one edge, draw a shape, and it opens what you
 * set for it. C, M, an arrow down and O come built in; Remixer records your own.
 */
@SysUISingleton
class GestureAnywhereController
@Inject
constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val flashlightController: FlashlightController,
    private val activityStarter: ActivityStarter,
) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private var monitor: InputMonitorCompat? = null
    private var receiver: InputChannelCompat.InputEventReceiver? = null
    private var overlay: FrameLayout? = null
    private var store = GestureStore()
    private val actions = HashMap<String, String>()

    private var enabled = false
    private var rightSide = false
    private var position = 50
    private var length = 30
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val closeIdle = Runnable { close() }

    override fun start() {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = context.createWindowContext(
            display, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null
        )
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = readSettings()
        }
        for (key in KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        readSettings()
    }

    private fun string(key: String): String? =
        Settings.System.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT)

    private fun int(key: String, def: Int) =
        Settings.System.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    private fun readSettings() {
        enabled = int(KEY_ENABLED, 0) == 1
        rightSide = int(KEY_SIDE, 0) == 1
        position = int(KEY_POSITION, 50).coerceIn(0, 100)
        length = int(KEY_LENGTH, 30).coerceIn(10, 80)
        buildStore()
        if (enabled && monitor == null) {
            monitor = InputMonitorCompat(TAG, Display.DEFAULT_DISPLAY).also {
                receiver = it.getInputReceiver(Looper.getMainLooper(), Choreographer.getInstance()) {
                    event -> onInputEvent(event)
                }
            }
        } else if (!enabled && monitor != null) {
            receiver?.dispose()
            monitor?.dispose()
            receiver = null
            monitor = null
            close()
        }
    }

    /** Built-in shapes plus the ones recorded in Remixer. */
    private fun buildStore() {
        store = GestureStore().apply {
            orientationStyle = GestureStore.ORIENTATION_SENSITIVE
            sequenceType = GestureStore.SEQUENCE_SENSITIVE
        }
        actions.clear()
        fun builtIn(name: String, key: String, def: String, points: List<Pair<Float, Float>>) {
            store.addGesture(name, gesture(points))
            actions[name] = string(key) ?: def
        }
        builtIn("c", KEY_C, "camera", arc(0.5f, 0.5f, 0.4f, 300f, 60f))
        builtIn("o", KEY_O, "screenshot", arc(0.5f, 0.5f, 0.4f, 270f, 270f - 359f))
        builtIn("m", KEY_M, "music",
            listOf(0.1f to 0.9f, 0.3f to 0.1f, 0.5f to 0.6f, 0.7f to 0.1f, 0.9f to 0.9f))
        builtIn("down", KEY_DOWN, "flashlight", listOf(0.5f to 0.1f, 0.5f to 0.9f))
        try {
            val custom = JSONArray(string(KEY_CUSTOM) ?: "[]")
            for (i in 0 until custom.length()) {
                val entry = custom.getJSONObject(i)
                val name = "custom$i"
                // Each drawing from Remixer is one more sample of the same gesture.
                val samples = entry.optJSONArray("samples")
                    ?: JSONArray().put(entry.getJSONArray("strokes"))
                for (n in 0 until samples.length()) {
                    store.addGesture(name, parse(samples.getJSONArray(n)))
                }
                actions[name] = entry.getString("action")
            }
        } catch (e: Exception) {
            // Keep the built-in ones.
        }
    }

    private fun parse(strokes: JSONArray): Gesture {
        val g = Gesture()
        for (s in 0 until strokes.length()) {
            val flat = strokes.getJSONArray(s)
            val points = ArrayList<GesturePoint>()
            var p = 0
            while (p + 2 < flat.length()) {
                points += GesturePoint(flat.getDouble(p).toFloat(),
                    flat.getDouble(p + 1).toFloat(), flat.getLong(p + 2))
                p += 3
            }
            if (points.size > 1) g.addStroke(GestureStroke(points))
        }
        return g
    }

    private fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float): List<Pair<Float, Float>> {
        val steps = 24
        return (0..steps).map {
            val a = Math.toRadians((from + (to - from) * it / steps).toDouble())
            (cx + r * cos(a).toFloat()) to (cy - r * sin(a).toFloat())
        }
    }

    private fun gesture(points: List<Pair<Float, Float>>): Gesture {
        val size = 300f
        var t = 0L
        val stroke = GestureStroke(ArrayList(points.map { (x, y) -> GesturePoint(x * size, y * size, t++ * 16) }))
        return Gesture().apply { addStroke(stroke) }
    }

    private fun inStrip(x: Float, y: Float): Boolean {
        val metrics = context.resources.displayMetrics
        val edge = STRIP_DP * metrics.density
        val onEdge = if (rightSide) x >= metrics.widthPixels - edge else x <= edge
        if (!onEdge) return false
        val h = metrics.heightPixels
        val span = h * length / 100f
        val center = h * (0.1f + 0.8f * position / 100f)
        return y in center - span / 2..center + span / 2
    }

    private fun onInputEvent(event: InputEvent) {
        if (event !is MotionEvent || overlay != null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = !keyguardStateController.isShowing && inStrip(event.rawX, event.rawY)
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return
                val dx = (event.rawX - downX) * (if (rightSide) -1 else 1)
                if (dx > slop * 2 && abs(event.rawY - downY) < dx &&
                    event.eventTime - downTime < SWIPE_MS) {
                    tracking = false
                    monitor?.pilferPointers()
                    openCanvas()
                } else if (event.eventTime - downTime > SWIPE_MS) {
                    tracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }

    private fun openCanvas() {
        val accent = context.getColor(android.R.color.system_accent1_400)
        val root = FrameLayout(windowContext)
        root.setBackgroundColor(0xB8000000.toInt())
        val hint = TextView(windowContext).apply {
            setText(R.string.gesture_anywhere_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = (context.resources.displayMetrics.heightPixels * 0.16f).toInt()
        })
        val canvas = GestureOverlayView(windowContext).apply {
            gestureColor = accent
            uncertainGestureColor = accent
            gestureStrokeWidth = 10f * context.resources.displayMetrics.density
            gestureStrokeType = GestureOverlayView.GESTURE_STROKE_TYPE_MULTIPLE
            fadeOffset = 500L
            isEventsInterceptionEnabled = true
            addOnGesturePerformedListener { _, gesture -> recognize(gesture, hint) }
            addOnGestureListener(object : GestureOverlayView.OnGestureListener {
                override fun onGestureStarted(overlay: GestureOverlayView, event: MotionEvent) {
                    handler.removeCallbacks(closeIdle)
                }

                override fun onGesture(overlay: GestureOverlayView, event: MotionEvent) {}

                override fun onGestureEnded(overlay: GestureOverlayView, event: MotionEvent) {}

                override fun onGestureCancelled(overlay: GestureOverlayView, event: MotionEvent) {}
            })
        }
        root.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.title = "RRGestureAnywhere"
        try {
            windowManager.addView(root, lp)
        } catch (e: RuntimeException) {
            return
        }
        overlay = root
        handler.postDelayed(closeIdle, IDLE_MS)
    }

    private fun recognize(gesture: Gesture, hint: TextView) {
        val best = store.recognize(gesture).firstOrNull { it.score >= MIN_SCORE }
        val action = best?.let { actions[it.name] }
        if (action == null) {
            hint.setText(R.string.gesture_anywhere_unknown)
            handler.removeCallbacks(closeIdle)
            handler.postDelayed(closeIdle, IDLE_MS)
            return
        }
        close()
        run(action)
    }

    private fun close() {
        handler.removeCallbacks(closeIdle)
        val root = overlay ?: return
        overlay = null
        try {
            windowManager.removeView(root)
        } catch (e: RuntimeException) {
            // Already gone.
        }
    }

    private fun run(action: String) {
        when {
            action == "camera" -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            action == "music" -> start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            action == "flashlight" -> flashlightController.setFlashlight(!flashlightController.isEnabled)
            action == "screenshot" -> key(KeyEvent.KEYCODE_SYSRQ)
            action == "dnd" -> context.getSystemService(NotificationManager::class.java)?.let {
                it.setInterruptionFilter(
                    if (it.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
            }
            action.startsWith("app:") -> ComponentName.unflattenFromString(action.substring(4))?.let {
                start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(it))
            }
        }
    }

    private fun start(intent: Intent) {
        activityStarter.postStartActivityDismissingKeyguard(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0)
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            InputManagerGlobal.getInstance().injectInputEvent(
                KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD),
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,
            )
        }
    }

    companion object {
        private const val TAG = "RRGestureAnywhere"
        const val KEY_ENABLED = "gesture_anywhere"
        /** 0 left, 1 right. */
        const val KEY_SIDE = "gesture_anywhere_side"
        /** 0 to 100, where the strip's middle sits on the edge. */
        const val KEY_POSITION = "gesture_anywhere_position"
        /** 10 to 80, percent of the screen height. */
        const val KEY_LENGTH = "gesture_anywhere_length"
        const val KEY_C = "gesture_anywhere_c"
        const val KEY_M = "gesture_anywhere_m"
        const val KEY_DOWN = "gesture_anywhere_down"
        const val KEY_O = "gesture_anywhere_o"
        /** JSON from Remixer: [{action, strokes: [[x, y, t, ...]]}]. */
        const val KEY_CUSTOM = "gesture_anywhere_custom"
        private val KEYS = arrayOf(KEY_ENABLED, KEY_SIDE, KEY_POSITION, KEY_LENGTH, KEY_C, KEY_M,
            KEY_DOWN, KEY_O, KEY_CUSTOM)
        private const val STRIP_DP = 14f
        private const val SWIPE_MS = 400L
        private const val IDLE_MS = 4000L
        private const val MIN_SCORE = 2.0
    }
}
