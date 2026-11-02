/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The glass selector's lens on a picker a view group draws, such as the volume dialog's ringer,
 * for glass at [place] (one of GlassSettings' place keys): it sits on the chosen option, one of
 * [group]'s children, and slides to a newly chosen one, stretching on the way while the stretch
 * setting is on. [group] draws through [draw] from its dispatchDraw and passes on its `on` calls.
 */
@MainThread
class OwnGlassViewLens(private val group: ViewGroup, private val place: String) {

    private val rim = OwnGlassViewRim(group, place)
    private val density = group.resources.displayMetrics.density
    private val path = Path()
    private val from = RectF()
    private val to = RectF()
    private val lens = RectF()
    private val cornerRadii = FloatArray(8)
    private val shadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter =
                BlurMaskFilter(OwnGlassLens.SHADOW_RADIUS_DP * density, BlurMaskFilter.Blur.NORMAL)
        }
    private var chosen: View? = null
    private var previous: View? = null
    // How far it has slid from the previous option to the chosen one: past 1 as it overshoots
    private var progress = 1f
    private var progressVelocity = 0f
    // How long it is along the way it slides, as a scale: 1 round
    private var stretch = 1f
    private var stretchVelocity = 0f
    private var speed = 0f
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastFrame = 0L
    private var moving = false
    private val frame = Choreographer.FrameCallback { step(it) }

    fun onAttachedToWindow() {
        rim.onAttachedToWindow()
    }

    fun onDetachedFromWindow() {
        rim.onDetachedFromWindow()
        stop()
    }

    /** From [View.onVisibilityAggregated]. */
    fun onVisibilityAggregated(isVisible: Boolean) {
        rim.onVisibilityAggregated(isVisible)
    }

    /** [option] is the chosen one, with the lens on it at once. */
    fun choose(option: View?) {
        if (option === chosen) {
            return
        }
        stop()
        chosen = option
        group.invalidate()
    }

    /** The choice moves to [option], so the lens slides there from the option it's on. */
    fun slideTo(option: View) {
        if (option === chosen) {
            return
        }
        previous = chosen
        chosen = option
        progress = if (previous == null) 1f else 0f
        progressVelocity = 0f
        if (!moving) {
            moving = true
            lastFrame = 0L
            lastX = Float.NaN
            Choreographer.getInstance().postFrameCallback(frame)
        }
        group.invalidate()
    }

    /**
     * Draws [group] with the lens on the chosen option: [around] draws all of it, here with the
     * lens cut out, then [under] draws the options again inside the lens, magnified as the
     * selector magnify setting says, and the lens gets its shadow and rim. False, having drawn
     * nothing, while there's no lens to draw.
     */
    fun draw(canvas: Canvas, around: (Canvas) -> Unit, under: (Canvas) -> Unit): Boolean {
        val option = chosen
        if (option == null || !OwnGlass.hasSelector(place) || !findLens()) {
            return false
        }
        val alpha = option.alpha.coerceIn(0f, 1f)
        if (alpha <= 0f) {
            return false
        }
        val radius = min(lens.width(), lens.height()) / 2f
        path.rewind()
        path.addRoundRect(lens, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipOutPath(path)
        around(canvas)
        val drop = OwnGlassLens.SHADOW_DROP_DP * density
        shadowPaint.alpha = (OwnGlassLens.SHADOW_ALPHA * alpha * OPAQUE).roundToInt()
        canvas.drawRoundRect(
            lens.left,
            lens.top + drop,
            lens.right,
            lens.bottom + drop,
            radius,
            radius,
            shadowPaint,
        )
        canvas.restore()

        canvas.save()
        canvas.clipPath(path)
        val magnify = OwnGlassLens.magnify(OwnGlass.selectorMagnify)
        canvas.scale(magnify, magnify, lens.centerX(), lens.centerY())
        under(canvas)
        canvas.restore()

        cornerRadii.fill(radius)
        rim.draw(
            canvas,
            lens.left,
            lens.top,
            lens.right,
            lens.bottom,
            cornerRadii,
            (alpha * OPAQUE).roundToInt(),
        )
        return true
    }

    // The lens now into [lens]: on the chosen option, or on its way there from the previous one.
    // False while the chosen option isn't laid out in the group.
    private fun findLens(): Boolean {
        val option = chosen ?: return false
        if (!bounds(option, to)) {
            return false
        }
        var x = to.centerX()
        var y = to.centerY()
        var width = to.width()
        var height = to.height()
        var acrossX = false
        val last = previous
        if (last != null && bounds(last, from)) {
            val p = progress
            x = from.centerX() + (x - from.centerX()) * p
            y = from.centerY() + (y - from.centerY()) * p
            width = from.width() + (width - from.width()) * p
            height = from.height() + (height - from.height()) * p
            acrossX = abs(to.centerX() - from.centerX()) > abs(to.centerY() - from.centerY())
        }
        val inset = 2f * LENS_INSET_DP * density
        width -= inset
        height -= inset
        val s = stretch
        if (acrossX) {
            width *= s
            height /= sqrt(s)
        } else {
            height *= s
            width /= sqrt(s)
        }
        lens.set(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
        return true
    }

    // Where [view] is in the group now, moved and scaled as it is, into [out]
    private fun bounds(view: View, out: RectF): Boolean {
        if (view.parent !== group || view.visibility != View.VISIBLE || view.width == 0) {
            return false
        }
        out.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(out)
        out.offset(view.left.toFloat(), view.top.toFloat())
        return true
    }

    // A frame of the slide: the lens springs toward the chosen option, stretching with its speed
    private fun step(now: Long) {
        val dt =
            if (lastFrame == 0L) 0f
            else ((now - lastFrame) / NANOS_PER_SECOND).coerceIn(MIN_STEP_S, MAX_STEP_S)
        lastFrame = now
        if (dt > 0f) {
            progressVelocity +=
                (SLIDE_STIFFNESS * (1f - progress) - SLIDE_DAMPING * progressVelocity) * dt
            progress += progressVelocity * dt
            if (findLens()) {
                val x = lens.centerX()
                val y = lens.centerY()
                if (!lastX.isNaN()) {
                    // A spring's path is smooth, and a slide is over too soon to wait for a
                    // smoothed speed
                    speed = hypot(x - lastX, y - lastY) / dt / density
                }
                lastX = x
                lastY = y
            }
            val target = if (OwnGlass.stretch) OwnGlassLens.stretch(speed) else 1f
            stretchVelocity +=
                (OwnGlassLens.STRETCH_STIFFNESS * (target - stretch) -
                    STRETCH_DAMPING * stretchVelocity) * dt
            stretch += stretchVelocity * dt
        }
        group.invalidate()
        val settled =
            abs(1f - progress) < SETTLED &&
                abs(progressVelocity) < SETTLED &&
                abs(stretch - 1f) < SETTLED &&
                abs(stretchVelocity) < SETTLED
        if (settled) {
            stop()
        } else {
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun stop() {
        if (moving) {
            Choreographer.getInstance().removeFrameCallback(frame)
            moving = false
        }
        previous = null
        progress = 1f
        progressVelocity = 0f
        stretch = 1f
        stretchVelocity = 0f
        speed = 0f
        group.invalidate()
    }

    private companion object {
        // How far inside the option's bounds the lens sits, on each side, in dp
        const val LENS_INSET_DP = 2f
        // The slide's spring: a little underdamped, so the lens overshoots and settles back, and soft
        // enough to see it travel (it settles in about 0.3 s)
        const val SLIDE_STIFFNESS = 320f
        val SLIDE_DAMPING = 2f * 0.7f * sqrt(SLIDE_STIFFNESS)
        val STRETCH_DAMPING =
            2f * OwnGlassLens.STRETCH_DAMPING_RATIO * sqrt(OwnGlassLens.STRETCH_STIFFNESS)
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_STEP_S = 0.001f
        const val MAX_STEP_S = 0.05f
        const val SETTLED = 0.002f
        const val OPAQUE = 255
    }
}
