/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.quicklaunch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The shortcut fan above the fingerprint sensor, over a dimmed screen. */
class QuickLaunchView(context: Context) : View(context) {

    class Item(val label: CharSequence, val icon: Drawable)

    private var items: List<Item> = emptyList()
    private val angles = ArrayList<Float>()
    private val positions = ArrayList<PointF>()
    private var centerX = 0f
    private var centerY = 0f
    private var progress = 0f

    /** Index of the shortcut under the finger, or -1. */
    var selected = -1
        private set

    private val density = resources.displayMetrics.density
    private val radius = 118 * density
    private val itemRadius = 24 * density
    private val iconSize = 24 * density
    private val accent = context.getColor(android.R.color.system_accent1_400)

    private val scrimPaint = Paint().apply { color = Color.BLACK }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x40FFFFFF
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        maskFilter = BlurMaskFilter(14 * density, BlurMaskFilter.Blur.NORMAL)
    }
    // Inside apply, a bare `density` is TextPaint.density, which is 1.
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(6 * this@QuickLaunchView.density, 0f, 0f, 0x99000000.toInt())
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(items: List<Item>, x: Float, y: Float) {
        this.items = items
        centerX = x
        centerY = y
        angles.clear()
        positions.clear()
        val span = minOf(MAX_SPAN, SPACING * (items.size - 1))
        items.forEachIndexed { i, _ ->
            val angle = if (items.size == 1) 90f else 90f + span / 2f - span * i / (items.size - 1)
            angles += angle
            val rad = Math.toRadians(angle.toDouble())
            positions += PointF(x + radius * cos(rad).toFloat(), y - radius * sin(rad).toFloat())
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ENTER_MS
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Picks the shortcut toward ([x], [y]). Returns true when the choice changed. */
    fun select(x: Float, y: Float): Boolean {
        val dx = x - centerX
        val dy = y - centerY
        var best = -1
        if (hypot(dx, dy) > radius * PICK_DISTANCE) {
            val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
            var bestDiff = PICK_ANGLE
            angles.forEachIndexed { i, a ->
                val diff = abs(angle - a)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = i
                }
            }
        }
        if (best == selected) return false
        selected = best
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val shown = progress.coerceIn(0f, 1f)
        scrimPaint.alpha = (SCRIM_ALPHA * shown).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        var top = centerY
        items.forEachIndexed { i, item ->
            val p = positions[i]
            val x = centerX + (p.x - centerX) * progress
            val y = centerY + (p.y - centerY) * progress
            top = minOf(top, y)
            val on = i == selected
            val r = itemRadius * (if (on) 1.3f else 1f)
            if (on) {
                canvas.drawCircle(x, y, r, glowPaint)
                canvas.drawCircle(x, y, r, selectedPaint)
            } else {
                itemPaint.alpha = (0x33 * shown).toInt()
                ringPaint.alpha = (0x40 * shown).toInt()
                canvas.drawCircle(x, y, r, itemPaint)
                canvas.drawCircle(x, y, r, ringPaint)
            }
            val half = iconSize * (if (on) 1.3f else 1f) / 2f
            item.icon.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
            item.icon.alpha = (255 * shown).toInt()
            item.icon.draw(canvas)
        }
        if (selected >= 0) {
            canvas.drawText(items[selected].label.toString(), centerX,
                top - itemRadius * 1.3f - 18 * density, labelPaint)
        }
    }

    companion object {
        private const val ENTER_MS = 240L
        private const val SCRIM_ALPHA = 140
        private const val MAX_SPAN = 140f
        private const val SPACING = 35f
        private const val PICK_DISTANCE = 0.45f
        private const val PICK_ANGLE = 30f
    }
}
