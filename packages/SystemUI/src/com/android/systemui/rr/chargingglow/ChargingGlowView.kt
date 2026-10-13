/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.chargingglow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import com.android.internal.graphics.ColorUtils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.BatteryController

/**
 * Lock screen glow that rises from the bottom while the phone charges. Its height follows the
 * battery level; on the always-on display it is a dim, still version.
 */
class ChargingGlowView(
    context: Context,
    private val batteryController: BatteryController,
    private val statusBarStateController: StatusBarStateController,
    private val broadcastDispatcher: BroadcastDispatcher,
) : View(context), BatteryController.BatteryStateChangeCallback,
    StatusBarStateController.StateListener {

    private var enabled = true
    private var colorMode = COLOR_ACCENT
    private var customColor = CRIMSON
    private var heightMode = HEIGHT_LEVEL
    private var brightness = DEFAULT_BRIGHTNESS / 100f
    private var rippleEnabled = true
    private var showOnAod = true

    private var level = -1
    private var pluggedIn = false
    private var charging = false
    private var dozing = false

    /** Battery level as drawn; follows [level] with an animation. */
    private var shownLevel = 0f
    /** 0 while hidden, 1 once fully risen. */
    private var rise = 0f
    private var breath = 1f
    private var rippleProgress = -1f
    private var aodShift = 0

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowMatrix = Matrix()
    private val crestMatrix = Matrix()
    private var shaderColor = 0

    private var riseAnimator: ValueAnimator? = null
    private var levelAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            readSettings()
            update(animate = false)
        }
    }

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A few pixels each minute on the always-on display, against burn-in.
            aodShift = (aodShift + 1) % AOD_SHIFT_STEPS
            if (dozing) invalidate()
        }
    }

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        for (key in KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, settingsObserver, UserHandle.USER_ALL
            )
        }
        broadcastDispatcher.registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        readSettings()
        dozing = statusBarStateController.isDozing
        statusBarStateController.addCallback(this)
        batteryController.addCallback(this)
        update(animate = false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.contentResolver.unregisterContentObserver(settingsObserver)
        broadcastDispatcher.unregisterReceiver(timeTickReceiver)
        statusBarStateController.removeCallback(this)
        batteryController.removeCallback(this)
        cancelAnimators()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The accent may have changed.
        shaderColor = 0
        invalidate()
    }

    override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
        val first = this.level < 0
        val pluggedNow = pluggedIn && !this.pluggedIn
        this.level = level
        this.pluggedIn = pluggedIn
        this.charging = charging
        update(animate = !first, plugged = pluggedNow && !first)
    }

    override fun onDozingChanged(isDozing: Boolean) {
        dozing = isDozing
        update(animate = false)
    }

    private fun readSettings() {
        val resolver = context.contentResolver
        fun int(key: String, def: Int) =
            Settings.System.getIntForUser(resolver, key, def, UserHandle.USER_CURRENT)
        enabled = int(KEY_ENABLED, 1) == 1
        colorMode = int(KEY_COLOR, COLOR_ACCENT)
        customColor = int(KEY_CUSTOM_COLOR, CRIMSON)
        heightMode = int(KEY_HEIGHT, HEIGHT_LEVEL)
        brightness = int(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS).coerceIn(10, 100) / 100f
        rippleEnabled = int(KEY_RIPPLE, 1) == 1
        showOnAod = int(KEY_AOD, 1) == 1
        shaderColor = 0
    }

    private fun update(animate: Boolean, plugged: Boolean = false) {
        val show = enabled && pluggedIn && level >= 0 && (!dozing || showOnAod)
        if (!show) {
            hide(animate)
            return
        }
        animate().cancel()
        alpha = 1f
        val hidden = visibility != VISIBLE || rise == 0f
        visibility = VISIBLE
        if (hidden || plugged) {
            levelAnimator?.cancel()
            shownLevel = level.toFloat()
            if (animate && !dozing) {
                animateRise()
            } else {
                riseAnimator?.cancel()
                rise = 1f
            }
            if (plugged && rippleEnabled && !dozing) animateRipple()
        } else if (shownLevel != level.toFloat()) {
            animateLevel(level.toFloat(), animate && !dozing)
        }
        updateBreathing()
        invalidate()
    }

    private fun hide(animate: Boolean) {
        cancelAnimators()
        if (visibility != VISIBLE) return
        val reset = {
            visibility = GONE
            alpha = 1f
            rise = 0f
            rippleProgress = -1f
        }
        if (animate && !dozing) {
            animate().alpha(0f).setDuration(FADE_MS).withEndAction { reset() }.start()
        } else {
            reset()
        }
    }

    private fun updateBreathing() {
        if (charging && level < 100 && !dozing) {
            if (breathAnimator != null) return
            breathAnimator = ValueAnimator.ofFloat(1f, BREATH_MIN).apply {
                duration = BREATH_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    breath = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            breathAnimator?.cancel()
            breathAnimator = null
            breath = 1f
        }
    }

    private fun animateRise() {
        riseAnimator?.cancel()
        rise = 0f
        riseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RISE_MS
            interpolator = EASE_OUT
            addUpdateListener {
                rise = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateLevel(to: Float, animate: Boolean) {
        levelAnimator?.cancel()
        if (!animate) {
            shownLevel = to
            return
        }
        levelAnimator = ValueAnimator.ofFloat(shownLevel, to).apply {
            duration = LEVEL_MS
            interpolator = EASE_OUT
            addUpdateListener {
                shownLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateRipple() {
        rippleAnimator?.cancel()
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RIPPLE_MS
            interpolator = EASE_OUT
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rippleProgress = -1f
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelAnimators() {
        animate().cancel()
        riseAnimator?.cancel()
        levelAnimator?.cancel()
        breathAnimator?.cancel()
        breathAnimator = null
        rippleAnimator?.cancel()
        breath = 1f
    }

    private fun glowColor(): Int = when (colorMode) {
        COLOR_CRIMSON -> CRIMSON
        COLOR_LEVEL -> when {
            level < 20 -> LEVEL_LOW
            level < 80 -> LEVEL_MID
            else -> LEVEL_HIGH
        }
        COLOR_CUSTOM -> customColor or BLACK
        else -> context.getColor(android.R.color.system_accent1_400)
    }

    private fun ensureShaders(color: Int) {
        if (color == shaderColor) return
        shaderColor = color
        // Unit gradients, stretched to size with local matrices in onDraw.
        glowPaint.shader = LinearGradient(
            0f, 1f, 0f, 0f,
            intArrayOf(
                withAlpha(color, 0.85f), withAlpha(color, 0.45f), withAlpha(color, 0.12f),
                withAlpha(color, 0f)
            ),
            floatArrayOf(0f, 0.4f, 0.85f, 1f),
            Shader.TileMode.CLAMP,
        )
        val crest = ColorUtils.blendARGB(color, Color.WHITE, 0.2f)
        crestPaint.shader = RadialGradient(
            0f, 0f, 1f, withAlpha(crest, 0.8f), withAlpha(crest, 0f), Shader.TileMode.CLAMP
        )
        ripplePaint.color = color
        ripplePaint.strokeWidth = dp(2f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || rise <= 0f) return
        ensureShaders(glowColor())

        val fraction = if (heightMode == HEIGHT_LEVEL) {
            MIN_FRACTION + (MAX_FRACTION - MIN_FRACTION) * shownLevel / 100f
        } else {
            STEADY_FRACTION
        }
        val glowHeight = h * fraction * rise * (if (dozing) AOD_HEIGHT else 1f)
        val shift = if (dozing) (aodShift - AOD_SHIFT_STEPS / 2) * dp(AOD_SHIFT_DP) else 0f
        val top = h - glowHeight + shift
        val strength = brightness * breath * (if (dozing) AOD_ALPHA else 1f)

        canvas.save()
        canvas.translate(0f, top)
        glowMatrix.setScale(1f, glowHeight)
        glowPaint.shader?.setLocalMatrix(glowMatrix)
        glowPaint.alpha = (255 * strength).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, maxOf(glowHeight, h - top), glowPaint)
        if (!dozing) {
            crestMatrix.setScale(w * 0.6f, dp(10f))
            crestMatrix.postTranslate(w / 2f, 0f)
            crestPaint.shader?.setLocalMatrix(crestMatrix)
            crestPaint.alpha = (230 * strength * rise).toInt().coerceIn(0, 255)
            canvas.drawRect(-w * 0.1f, -dp(12f), w * 1.1f, dp(12f), crestPaint)
        }
        canvas.restore()

        if (rippleProgress >= 0f) {
            ripplePaint.alpha = (230 * (1f - rippleProgress) * brightness).toInt().coerceIn(0, 255)
            canvas.drawCircle(w / 2f, h + dp(10f), rippleProgress * h * 0.6f, ripplePaint)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun withAlpha(color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (255 * alpha).toInt())

    companion object {
        const val KEY_ENABLED = "charging_glow"
        /** 0 accent, 1 RR crimson, 2 battery level, 3 custom. */
        const val KEY_COLOR = "charging_glow_color"
        const val KEY_CUSTOM_COLOR = "charging_glow_custom_color"
        /** 0 follows the battery level, 1 low and steady. */
        const val KEY_HEIGHT = "charging_glow_height"
        /** 10 to 100. */
        const val KEY_BRIGHTNESS = "charging_glow_brightness"
        const val KEY_RIPPLE = "charging_glow_ripple"
        const val KEY_AOD = "charging_glow_aod"
        private val KEYS = arrayOf(
            KEY_ENABLED, KEY_COLOR, KEY_CUSTOM_COLOR, KEY_HEIGHT, KEY_BRIGHTNESS, KEY_RIPPLE, KEY_AOD
        )

        private const val COLOR_ACCENT = 0
        private const val COLOR_CRIMSON = 1
        private const val COLOR_LEVEL = 2
        private const val COLOR_CUSTOM = 3
        private const val HEIGHT_LEVEL = 0
        private const val DEFAULT_BRIGHTNESS = 70

        private const val BLACK = 0xFF000000.toInt()
        private const val CRIMSON = 0xFFFF003F.toInt()
        private const val LEVEL_LOW = 0xFFFF3030.toInt()
        private const val LEVEL_MID = 0xFFFFB02E.toInt()
        private const val LEVEL_HIGH = 0xFF3DDC84.toInt()

        private const val MIN_FRACTION = 0.12f
        private const val MAX_FRACTION = 0.52f
        private const val STEADY_FRACTION = 0.22f
        private const val AOD_HEIGHT = 0.65f
        private const val AOD_ALPHA = 0.35f
        private const val AOD_SHIFT_STEPS = 8
        private const val AOD_SHIFT_DP = 3f
        private const val BREATH_MIN = 0.82f
        private const val BREATH_MS = 1700L
        private const val RISE_MS = 1100L
        private const val LEVEL_MS = 600L
        private const val RIPPLE_MS = 1600L
        private const val FADE_MS = 400L
        private val EASE_OUT = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)
    }
}
