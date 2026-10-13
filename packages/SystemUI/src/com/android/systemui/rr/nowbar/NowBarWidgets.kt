/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/** A small moving wave, drawn along the bottom of the capsule while music plays. */
class NowBarWaveView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    var running = false
        set(value) {
            field = value
            updateAnimator()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimator()
    }

    private fun updateAnimator() {
        if (running && isAttachedToWindow && isShown) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        wave(path, 0f, width.toFloat(), height / 2f, height / 2.5f, WAVELENGTH * density, phase)
        canvas.drawPath(path, paint)
    }

    companion object {
        const val WAVELENGTH = 14f

        /** A sine wave from [start] to [end] around [mid], shifted by [phase] of a wavelength. */
        fun wave(path: Path, start: Float, end: Float, mid: Float, amplitude: Float,
                wavelength: Float, phase: Float) {
            path.reset()
            var x = start
            path.moveTo(x, mid)
            while (x <= end) {
                val y = mid + amplitude * sin(((x - start) / wavelength - phase) * 2f * PI.toFloat())
                path.lineTo(x, y)
                x += 2f
            }
        }
    }
}

/** Page dots under the capsule: the current page is a short bar. */
class NowBarDotsView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var count = 0
    private var current = 0

    fun set(count: Int, current: Int) {
        this.count = count
        this.current = current
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (count < 2) return
        val dot = 4f * density
        val bar = 9f * density
        val gap = 3f * density
        val total = bar + (count - 1) * dot + (count - 1) * gap
        var x = (width - total) / 2f
        val y = height / 2f
        for (i in 0 until count) {
            val w = if (i == current) bar else dot
            paint.color = if (i == current) 0xFFFFFFFF.toInt() else 0x55FFFFFF
            canvas.drawRoundRect(x, y - dot / 2f, x + w, y + dot / 2f, dot / 2f, dot / 2f, paint)
            x += w + gap
        }
    }
}

/** A seek bar whose played part is a moving wave. */
class NowBarSeekView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = 0x40FFFFFF
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()
    private var phase = 0f
    private var dragging = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** 0 to 1. */
    var progress = 0f
        set(value) {
            if (!dragging) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        }

    var color: Int
        get() = wavePaint.color
        set(value) {
            wavePaint.color = value
            invalidate()
        }

    var waving = true
        set(value) {
            field = value
            if (value && isAttachedToWindow) animator.start() else animator.cancel()
            invalidate()
        }

    /** Called with the new position, 0 to 1, when the user lets go. */
    var onSeek: ((Float) -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (waving) animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 8f * density
        val mid = height / 2f
        val end = pad + (width - 2 * pad) * progress
        if (waving) {
            NowBarWaveView.wave(path, pad, end, mid, 3f * density, 16f * density, phase)
            canvas.drawPath(path, wavePaint)
        } else {
            canvas.drawLine(pad, mid, end, mid, wavePaint)
        }
        canvas.drawLine(end, mid, width - pad, mid, restPaint)
        canvas.drawCircle(end, mid, (if (dragging) 8f else 6f) * density, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onSeek == null) return false
        val pad = 8f * density
        val value = ((event.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                dragging = true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                progress = value
                onSeek?.invoke(value)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        if (dragging) {
            // Draws the thumb where the finger is while dragging.
            dragging = false
            progress = value
            dragging = true
        }
        return true
    }
}
