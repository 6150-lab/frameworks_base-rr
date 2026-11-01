/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.annotation.MainThread

/**
 * Draws the rim of glass a view draws itself, such as a notification's background, from
 * [OwnGlass] for glass at [place] (one of GlassSettings' place keys), and keeps the light moving
 * while the view is on screen with a rim. The view calls the `on` methods from its own.
 */
@MainThread
class OwnGlassViewRim(private val view: View, private val place: String) {

    private val paint = Paint()
    private val radii = FloatArray(4)
    private var attached = false
    private var visible = false
    private var shown = false
    private val onChange = Runnable {
        updateShown()
        view.invalidate()
    }

    fun onAttachedToWindow() {
        OwnGlass.start(view.context)
        attached = true
        OwnGlass.addListener(onChange)
        updateShown()
    }

    fun onDetachedFromWindow() {
        attached = false
        OwnGlass.removeListener(onChange)
        updateShown()
    }

    /** From [View.onVisibilityAggregated]. */
    fun onVisibilityAggregated(isVisible: Boolean) {
        visible = isVisible
        updateShown()
    }

    /**
     * Draws the rim on glass inside [left], [top], [right], [bottom] at [alpha] (0 to 255), with
     * corner radii as GradientDrawable takes them: [cornerRadii] has x and y for the top left,
     * top right, bottom right and bottom left corners.
     */
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadii: FloatArray,
        alpha: Int,
    ) {
        if (!attached || right <= left || bottom <= top || alpha <= 0) {
            return
        }
        val rim = OwnGlass.rim(place, view.resources.displayMetrics.density) ?: return
        radii[0] = cornerRadii[0]
        radii[1] = cornerRadii[2]
        radii[2] = cornerRadii[4]
        radii[3] = cornerRadii[6]
        val shader = GlassRimShader.shared ?: return
        val ready =
            shader.set(
                left,
                top,
                right,
                bottom,
                radii,
                rim,
                OwnGlass.light,
                overLight = !view.resources.configuration.isNightModeActive,
            )
        if (!ready) {
            return
        }
        paint.shader = shader.shader
        paint.alpha = alpha
        canvas.drawRect(left, top, right, bottom, paint)
    }

    private fun updateShown() {
        val show =
            attached && visible &&
                OwnGlass.rim(place, view.resources.displayMetrics.density) != null
        if (show != shown) {
            shown = show
            if (show) OwnGlass.show(this) else OwnGlass.hide(this)
        }
    }
}
