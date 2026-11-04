/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOverlay
import android.view.animation.AccelerateInterpolator
import androidx.annotation.MainThread
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import kotlin.math.roundToInt

/**
 * Melt together (GlassSettings.MELT) for a panel that morphs: a bead of glass stays behind where
 * the panel comes out of, and melts into the panel's glass, so a neck joins them as the panel
 * grows away from it, stretches, and snaps. Going back, they reach for each other and join again.
 *
 * The bead lies where it can't be seen on its own, in the camera hole or just past the screen's
 * edge, so only what melts out of it shows, and it's there only while it melts. Its glass is the
 * panel's ([panelGlass]) and it's in the window's overlay, where it draws nothing of its own, so
 * SurfaceFlinger draws them as one. Main thread only.
 */
@MainThread
internal class OwnGlassNeck(
    private val view: View,
    private val panelGlass: OwnGlassMorph.MeltingGlass,
) {
    private val bounds = Rect()
    private var radius = 0f
    // How close the bead reaches for the panel at most; 0 while glass doesn't melt
    private var size = 0f
    // How much longer the neck takes to let go
    private var speed = 1f

    private var bead: BackgroundBlurDrawable? = null
    private var overlay: ViewOverlay? = null
    // How much of its reach the neck still has as it lets go
    private var holding = 1f
    private var release: ValueAnimator? = null
    private var isReleased = false
    private var lastOut = 0f
    private var wasGrowing = true

    /**
     * Sets the bead at [onScreen] with corners of [cornerRadius], for a morph that takes [speed]
     * times as long as usual. Nothing shows until a frame calls for it.
     */
    fun place(onScreen: RectF, cornerRadius: Float, speed: Float) {
        remove()
        OwnGlass.start(view.context)
        size = OwnGlass.meltSize(view.resources.displayMetrics.density)
        this.speed = speed
        radius = cornerRadius
        val location = IntArray(2)
        view.rootView.getLocationOnScreen(location)
        bounds.set(
            (onScreen.left - location[0]).roundToInt(),
            (onScreen.top - location[1]).roundToInt(),
            (onScreen.right - location[0]).roundToInt(),
            (onScreen.bottom - location[1]).roundToInt(),
        )
        holding = 1f
        isReleased = false
    }

    /**
     * A frame of the morph: the panel is [out] of the way from the spot to where it rests,
     * [isGrowing] or going back.
     */
    fun onFrame(out: Float, isGrowing: Boolean) {
        lastOut = out
        wasGrowing = isGrowing
        if (size <= 0f) {
            return
        }
        if (!isGrowing) {
            cancelRelease()
            isReleased = false
            holding = 1f
        } else if (isReleased) {
            return
        } else if (out >= RELEASE_FROM) {
            startRelease()
        }
        updateReach()
    }

    private fun updateReach() {
        val out = lastOut
        // They set out from the same spot, where melting at full reach would swell out of it
        var reach = size * smooth(0f, APPEAR_TO, out) * holding
        if (!wasGrowing) {
            // Coming back, it reaches out again as the panel nears
            reach *= 1f - smooth(REACH_FROM, 1f, out)
        }
        if (reach < MIN_REACH_PX) {
            hide()
            return
        }
        (bead ?: show())?.setGlassMelt(reach)
    }

    /** Takes the bead away; a later frame brings it back if the morph carries on. */
    fun remove() {
        cancelRelease()
        hide()
    }

    private fun show(): BackgroundBlurDrawable? {
        val source = panelGlass.now() ?: return null
        val viewRoot = view.viewRootImpl ?: return null
        val glass = viewRoot.createBackgroundBlurDrawable()
        if (!glass.setGlassLike(source)) {
            // Not glass: nothing to melt, and no use asking again this morph
            size = 0f
            glass.setVisible(false, false)
            return null
        }
        // It has nothing to show of its own, nor any hole to make through the window's content
        glass.setXfermode(null)
        glass.setCornerRadius(radius)
        glass.bounds = bounds
        val host = view.rootView.overlay
        host.add(glass)
        overlay = host
        bead = glass
        return glass
    }

    private fun hide() {
        val glass = bead ?: return
        bead = null
        glass.setGlassMelt(0f)
        glass.setVisible(false, false)
        overlay?.remove(glass)
        overlay = null
    }

    /** The panel is out: the neck holds a moment, then lets go. */
    private fun startRelease() {
        if (release != null) {
            return
        }
        release =
            ValueAnimator.ofFloat(1f, 0f).apply {
                startDelay = (HOLD_MS * speed).toLong()
                duration = (RELEASE_MS * speed).toLong()
                // It thins slowly at first, then all at once
                interpolator = AccelerateInterpolator()
                addUpdateListener {
                    holding = it.animatedValue as Float
                    updateReach()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var isCanceled = false

                        override fun onAnimationCancel(animation: Animator) {
                            isCanceled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            release = null
                            if (!isCanceled) {
                                isReleased = true
                                hide()
                            }
                        }
                    }
                )
                start()
            }
    }

    private fun cancelRelease() {
        release?.cancel()
        release = null
    }

    private companion object {
        // The neck comes to its full reach by this far out
        const val APPEAR_TO = 0.2f
        // How far out the panel lets go of it, and how long the neck holds then lets go, in ms
        const val RELEASE_FROM = 0.98f
        const val HOLD_MS = 60f
        const val RELEASE_MS = 240f
        // How far out the panel, going back, starts to reach for it again
        const val REACH_FROM = 0.7f
        // Less reach than this doesn't show
        const val MIN_REACH_PX = 2f

        fun smooth(from: Float, to: Float, x: Float): Float {
            val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
