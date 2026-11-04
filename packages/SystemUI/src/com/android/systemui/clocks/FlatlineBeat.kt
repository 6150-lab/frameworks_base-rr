/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import kotlin.math.PI
import kotlin.math.sin

/**
 * One heartbeat over [t] in 0..1: a small P bump, the sharp QRS spike with its dip, and a
 * rounder T bump, flat the rest of the way. Calls use a tighter, taller shape. Shared by the
 * Flatline face and the status bar chip.
 */
internal fun flatlineBeat(t: Float, call: Boolean): Float {
    val p = bump(t, 0.32f, 0.05f) * 0.12f
    val q = -bump(t, 0.47f, 0.015f) * 0.14f
    val r = bump(t, 0.52f, 0.022f) * (if (call) 1f else 0.85f)
    val s = -bump(t, 0.575f, 0.018f) * 0.32f
    val tw = bump(t, 0.70f, 0.045f) * 0.2f
    return p + q + r + s + tw
}

private fun bump(t: Float, at: Float, width: Float): Float {
    val d = (t - at) / width
    if (d < -1f || d > 1f) return 0f
    return (0.5f + 0.5f * sin((d * PI / 2f + PI / 2f).toFloat())).let { it * it }
}
