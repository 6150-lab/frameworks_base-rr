/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.os.SystemClock
import android.view.View
import androidx.annotation.MainThread
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.catalyxx.GlassSettings
import kotlin.math.max

/**
 * How glass squishes under a finger at one of GlassSettings' `SQUISH_` levels: pressed in until
 * the ends of its longest side have each moved [edgeDp], then sprung back, past rest as far as
 * [releaseDamping] lets it. The springs run on a depth from 0 at rest to 1 pressed all the way.
 */
class OwnGlassSquish
private constructor(
    val edgeDp: Float,
    val pressDamping: Float,
    val pressStiffness: Float,
    val releaseDamping: Float,
    val releaseStiffness: Float,
) {

    /** The scale for glass [width] by [height] pixels at [depth], on a display of [density]. */
    fun scale(depth: Float, width: Float, height: Float, density: Float): Float {
        val longest = max(width, height)
        return if (longest > 0f) 1f - depth * 2f * edgeDp * density / longest else 1f
    }

    companion object {
        /** A quick tap stays pressed at least this long, so its squish shows. */
        const val MIN_PRESS_MS = 80L

        private val SUBTLE = OwnGlassSquish(1.5f, 1f, 1500f, 1f, 700f)
        private val NORMAL = OwnGlassSquish(3f, 1f, 1500f, 0.5f, 500f)
        private val BOUNCY = OwnGlassSquish(5f, 0.8f, 1200f, 0.3f, 400f)

        /** How glass at [place] squishes now; null while it doesn't. */
        fun now(place: String): OwnGlassSquish? {
            if (!OwnGlass.isOn(place)) {
                return null
            }
            return when (OwnGlass.squish) {
                GlassSettings.SQUISH_SUBTLE -> SUBTLE
                GlassSettings.SQUISH_NORMAL -> NORMAL
                GlassSettings.SQUISH_BOUNCY -> BOUNCY
                else -> null
            }
        }
    }
}

/**
 * Squishes glass a view draws a little while it's pressed and springs it back, for glass at
 * [place]. The view passes its pressed state to [setPressed] and draws at [scale]; [onChange]
 * runs whenever that changes.
 */
@MainThread
class OwnGlassViewSquish(
    private val view: View,
    private val place: String,
    private val onChange: Runnable,
) {

    private val depth = FloatValueHolder(0f)
    private val spring =
        SpringAnimation(depth).apply {
            setSpring(SpringForce(0f))
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, _, _ -> onChange.run() }
        }
    // How the glass squishes for the press now, kept until it has sprung back
    private var squish: OwnGlassSquish? = null
    private var pressed = false
    private var pressedAt = 0L
    private val release = Runnable { springTo(0f) }

    /** The scale to draw glass [width] by [height] pixels at now. */
    fun scale(width: Int, height: Int): Float {
        val s = squish ?: return 1f
        return s.scale(
            depth.value,
            width.toFloat(),
            height.toFloat(),
            view.resources.displayMetrics.density,
        )
    }

    fun setPressed(isPressed: Boolean) {
        if (isPressed == pressed) {
            return
        }
        pressed = isPressed
        view.removeCallbacks(release)
        if (isPressed) {
            squish = OwnGlassSquish.now(place) ?: return
            pressedAt = SystemClock.uptimeMillis()
            springTo(1f)
        } else {
            // A quick tap lifts before its squish shows, so it stays pressed a moment longer
            val left = pressedAt + OwnGlassSquish.MIN_PRESS_MS - SystemClock.uptimeMillis()
            if (left > 0) view.postDelayed(release, left) else springTo(0f)
        }
    }

    private fun springTo(target: Float) {
        val s = squish ?: return
        if (target > 0f) {
            spring.spring.setDampingRatio(s.pressDamping).setStiffness(s.pressStiffness)
        } else {
            spring.spring.setDampingRatio(s.releaseDamping).setStiffness(s.releaseStiffness)
        }
        spring.animateToFinalPosition(target)
    }
}
