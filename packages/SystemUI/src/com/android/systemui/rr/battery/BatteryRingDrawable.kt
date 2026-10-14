/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.battery

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The neon ring as a Quick Settings tile icon: the level as an arc over a faint track, and a dot at
 * its end while charging. One color, so the tile's tint applies.
 */
class BatteryRingDrawable(
    private val level: Int,
    private val charging: Boolean,
    private val sizePx: Int,
) : Drawable() {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = TRACK_ALPHA
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val oval = RectF()
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = min(b.width(), b.height()).toFloat()
        val stroke = size * 0.12f
        val radius = size / 2f - stroke
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, track)
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val sweep = 3.6f * level.coerceIn(0, 100)
        if (sweep > 0) canvas.drawArc(oval, -90f, sweep, false, arc)
        if (charging) {
            val a = Math.toRadians((sweep - 90f).toDouble())
            canvas.drawCircle(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat(),
                stroke * 0.9f, dot)
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        arc.alpha = alpha
        dot.alpha = alpha
        track.alpha = TRACK_ALPHA * alpha / 255
        invalidateSelf()
    }

    override fun getAlpha() = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        track.colorFilter = colorFilter
        arc.colorFilter = colorFilter
        dot.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        val color = tint?.getColorForState(state, tint.defaultColor) ?: Color.WHITE
        arc.color = color
        dot.color = color
        track.color = color
        setAlpha(alphaValue)
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = sizePx

    override fun getIntrinsicHeight() = sizePx

    override fun getConstantState(): ConstantState =
        object : ConstantState() {
            override fun newDrawable() = BatteryRingDrawable(level, charging, sizePx)

            override fun getChangingConfigurations() = 0
        }

    private companion object {
        const val TRACK_ALPHA = 0x4D
    }
}
