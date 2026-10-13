/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgelight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.android.internal.graphics.ColorUtils

/** Draws one edge light moment: a pulse, a run up both sides, a double flash or a sweep. */
class EdgeMomentView(context: Context) : View(context) {

    enum class Kind { PULSE, RUN_UP, DOUBLE, SWEEP }

    var strokeWidthDp = 8
        set(value) {
            field = value.coerceIn(2, 32)
            invalidate()
        }

    private var kind = Kind.PULSE
    private var color = Color.WHITE
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val cornerRadius = try {
        resources.getDimension(
            resources.getIdentifier("rounded_corner_radius", "dimen", "android")
        )
    } catch (e: Exception) {
        32f * density
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val isPlaying: Boolean
        get() = animator?.isRunning == true

    /** Plays [kind] in [color]; a pulse repeats until [stop], the others end on their own. */
    fun play(kind: Kind, color: Int, onDone: () -> Unit) {
        animator?.cancel()
        this.kind = kind
        this.color = color or 0xFF000000.toInt()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (kind) {
                Kind.PULSE -> PULSE_MS
                Kind.RUN_UP -> RUN_UP_MS
                Kind.DOUBLE -> DOUBLE_MS
                Kind.SWEEP -> SWEEP_MS
            }
            interpolator = LinearInterpolator()
            if (kind == Kind.PULSE) repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (!cancelled) onDone()
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        if (animator == null) return
        val stroke = strokeWidthDp * density
        if (strokePaint.strokeWidth != stroke) {
            strokePaint.strokeWidth = stroke
            glowPaint.strokeWidth = stroke * 2f
            glowPaint.maskFilter = BlurMaskFilter(stroke * 2.5f, BlurMaskFilter.Blur.NORMAL)
        }
        val half = stroke / 2f
        rect.set(half, half, width - half, height - half)
        path.reset()
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        when (kind) {
            Kind.PULSE -> drawOutline(canvas, PULSE_MIN + (1f - PULSE_MIN) * wave(progress))
            Kind.DOUBLE -> drawOutline(canvas, doubleFlash(progress))
            Kind.SWEEP -> drawSweep(canvas)
            Kind.RUN_UP -> drawRunUp(canvas, half)
        }
    }

    private fun drawOutline(canvas: Canvas, alpha: Float) {
        strokePaint.shader = null
        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha = (GLOW_ALPHA * alpha).toInt()
        strokePaint.color = color
        strokePaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, strokePaint)
    }

    private fun drawSweep(canvas: Canvas) {
        // One turn starting at the bottom, then a quick fade.
        val turn = (progress / SWEEP_TURN).coerceAtMost(1f)
        val fade = if (progress <= SWEEP_TURN) 1f else 1f - (progress - SWEEP_TURN) / (1f - SWEEP_TURN)
        val shader = SweepGradient(
            width / 2f, height / 2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, color, Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 0.92f, 1f),
        )
        matrix.setRotate(90f + 360f * EASE.getInterpolation(turn), width / 2f, height / 2f)
        shader.setLocalMatrix(matrix)
        strokePaint.shader = shader
        glowPaint.shader = shader
        strokePaint.alpha = (255 * fade).toInt()
        glowPaint.alpha = (GLOW_ALPHA * fade).toInt()
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, strokePaint)
    }

    private fun drawRunUp(canvas: Canvas, half: Float) {
        val grow = EASE.getInterpolation((progress / RUN_UP_GROW).coerceAtMost(1f))
        val fade = if (progress <= RUN_UP_GROW) 1f else 1f - (progress - RUN_UP_GROW) / (1f - RUN_UP_GROW)
        val bottom = height.toFloat()
        val tip = bottom - bottom * RUN_UP_LENGTH * grow
        if (tip >= bottom) return
        val shader = LinearGradient(
            0f, bottom, 0f, tip,
            ColorUtils.setAlphaComponent(color, 50), color, Shader.TileMode.CLAMP,
        )
        strokePaint.shader = shader
        glowPaint.shader = shader
        strokePaint.alpha = (255 * fade).toInt()
        glowPaint.alpha = (GLOW_ALPHA * fade).toInt()
        for (x in floatArrayOf(half, width - half)) {
            canvas.drawLine(x, bottom, x, tip, glowPaint)
            canvas.drawLine(x, bottom, x, tip, strokePaint)
        }
    }

    /** 0 to 1 and back once per cycle. */
    private fun wave(t: Float) = (1f - kotlin.math.cos(t * 2f * Math.PI.toFloat())) / 2f

    /** Two flashes in the first 60% of the run. */
    private fun doubleFlash(t: Float): Float {
        fun flash(start: Float): Float {
            val u = (t - start) / 0.2f
            return if (u < 0f || u > 1f) 0f else if (u < 0.5f) u * 2f else (1f - u) * 2f
        }
        return maxOf(flash(0f), flash(0.3f))
    }

    companion object {
        private const val PULSE_MS = 1200L
        private const val PULSE_MIN = 0.25f
        private const val DOUBLE_MS = 2400L
        private const val SWEEP_MS = 1600L
        private const val SWEEP_TURN = 0.8f
        private const val RUN_UP_MS = 2400L
        private const val RUN_UP_GROW = 0.7f
        private const val RUN_UP_LENGTH = 0.92f
        private const val GLOW_ALPHA = 170
        private val EASE = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)
    }
}
