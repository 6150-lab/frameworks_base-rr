/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.content.Context
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.statusbar.notification.stack.AnimationProperties

/**
 * Catalyxx glass's morph for notifications that open or close (GlassSettings.MORPH_NOTIFICATIONS):
 * the spring the shade moves them on while they do, the cards around them included, so they all
 * move as one. Liquid stretches a little past the new size as a notification opens and lands with
 * a small bounce as it closes; Smooth gets there without overshooting.
 */
object OwnGlassNotificationMorph {

    // Android moves notifications on a spring of 380 at a damping ratio of 0.68, about 5% past
    // where they're going. Liquid opens about 9% past and closes about 2% past, Smooth not at all
    private const val LIQUID_OPEN_STIFFNESS = 320f
    private const val LIQUID_OPEN_DAMPING = 0.6f
    private const val LIQUID_CLOSE_STIFFNESS = 400f
    private const val LIQUID_CLOSE_DAMPING = 0.78f
    private const val SMOOTH_STIFFNESS = 380f
    private const val SMOOTH_DAMPING = 1f

    // How much longer the morph takes at GlassSettings.MORPH_SLOW, _NORMAL and _FAST
    private val SPEED = floatArrayOf(1.5f, 1f, 0.7f)

    /**
     * Puts the morph's spring on [properties] while notifications open or close and the list
     * grows ([isGrowing]) or shrinks; leaves Android's while the morph is off for notifications.
     */
    @JvmStatic
    fun applySpring(properties: AnimationProperties, context: Context, isGrowing: Boolean) {
        OwnGlass.start(context)
        if (!OwnGlass.morphs(GlassSettings.MORPH_NOTIFICATIONS)) {
            return
        }
        val stiffness: Float
        val damping: Float
        when {
            OwnGlass.morphStyle == GlassSettings.MORPH_SMOOTH -> {
                stiffness = SMOOTH_STIFFNESS
                damping = SMOOTH_DAMPING
            }
            isGrowing -> {
                stiffness = LIQUID_OPEN_STIFFNESS
                damping = LIQUID_OPEN_DAMPING
            }
            else -> {
                stiffness = LIQUID_CLOSE_STIFFNESS
                damping = LIQUID_CLOSE_DAMPING
            }
        }
        // A spring takes as much longer as one over the square root of its stiffness
        val speed = SPEED[OwnGlass.morphSpeed.coerceIn(0, SPEED.size - 1)]
        properties.setSpring(stiffness / (speed * speed), damping)
    }
}
