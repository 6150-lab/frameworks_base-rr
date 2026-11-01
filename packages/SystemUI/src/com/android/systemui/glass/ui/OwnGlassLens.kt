/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import com.android.internal.util.catalyxx.GlassSettings
import kotlin.math.min

/**
 * The glass selector's lens, wherever it shows: on a slider's knob while it's dragged, and on a
 * picker's chosen option. It magnifies what's under it as the selector magnify setting says, and
 * stretches like jelly along the way it moves while the stretch setting is on.
 */
object OwnGlassLens {

    /** How much the lens magnifies at [level], one of GlassSettings' `MAGNIFY_` values. */
    fun magnify(level: Int): Float =
        when (level) {
            GlassSettings.MAGNIFY_SUBTLE -> 1.15f
            GlassSettings.MAGNIFY_STRONG -> 1.35f
            else -> 1f
        }

    /**
     * How long the lens gets along its way at [speed] (dp a second), as a scale: 1 standing
     * still. Its width shrinks by the square root, so it keeps about the same area.
     */
    fun stretch(speed: Float): Float = 1f + min(MAX_STRETCH, speed * STRETCH_PER_SPEED)

    /** How far a slider's lens reaches past its track on each side, in dp. */
    const val OVERHANG_DP = 6f

    /** How quickly the measured speed follows the lens, in seconds. */
    const val SPEED_SMOOTHING_S = 0.06f

    // The spring the stretch follows: soft and underdamped, so it wobbles as it settles
    const val STRETCH_STIFFNESS = 380f
    const val STRETCH_DAMPING_RATIO = 0.3f

    // How the lens grows out of the knob and shrinks back into it
    const val GROW_STIFFNESS = 800f
    const val GROW_DAMPING_RATIO = 0.8f

    // The shadow it casts around itself, darkest at its edge, in dp
    const val SHADOW_RADIUS_DP = 4f
    const val SHADOW_DROP_DP = 1f
    const val SHADOW_ALPHA = 0.22f

    // The lens is at most this much longer than round, at about 830 dp a second
    private const val MAX_STRETCH = 0.5f
    private const val STRETCH_PER_SPEED = 0.0006f
}
