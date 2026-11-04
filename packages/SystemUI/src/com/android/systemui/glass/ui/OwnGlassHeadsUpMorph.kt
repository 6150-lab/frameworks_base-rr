/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.row.NotificationBackgroundView
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Catalyxx glass's morph for heads-up notifications (GlassSettings.MORPH_HEADS_UP, off by
 * default): one that pops up drips out of the camera hole at the top of the screen and grows into
 * its card where it shows, in place of sliding down from above, and goes back into the hole as it
 * goes, in place of sliding back up. Android's own appear and disappear animations still run on
 * it, for all that hangs on them, while the drop is what shows.
 *
 * The card's glass starts as a drop inside the hole, where the screen shows nothing, so it comes
 * out of it, and grows on [OwnGlassMorph]'s springs. The row is moved with its animation matrix,
 * which the notification stack never sets, and its content, not its glass, fades in once the drop
 * has grown and out early as it goes back. Main thread only.
 */
@MainThread
object OwnGlassHeadsUpMorph {

    // How big the drop is next to the hole: inside it, so it comes out of it
    private const val DROP_SCALE = 0.6f

    // The smallest a side of the card is drawn, in pixels: a view is never scaled to 0
    private const val MIN_SIZE_PX = 1.5f

    // The rows morphing now, or kept gone by a morph until Android's animation ends
    private val morphs = WeakHashMap<View, OwnGlassMorph>()
    // The rows a morph has moved or faded, until they're put back
    private val touched = WeakHashMap<View, HeadsUpTarget>()

    // While Android's own animations start on a row for its morph, they leave the morph alone
    private var isStartingStock = false

    /**
     * The drop heads-up [view] drips out of now, on the screen: inside the camera hole at the top
     * of the screen. Null where the morph is off, there's no hole at the top, or someone is
     * sliding the notification aside.
     */
    @JvmStatic
    fun dropFor(view: ExpandableView): RectF? {
        OwnGlass.start(view.context)
        if (!OwnGlass.morphsHeadsUp || view.translation != 0f) {
            return null
        }
        // The display's own cutout, in the screen's pixels. Not the window's, which comes with
        // where the window is, and the shade's window has only just come back as a heads-up
        // pops up: the hole came out over 100 px above where it is
        val cutout = view.display?.cutout ?: return null
        val top = cutout.boundingRectTop
        if (top.isEmpty) {
            return null
        }
        // The hole itself, from the cutout's outline; the area it takes up where that's missing
        val hole = RectF()
        cutout.cutoutPath?.computeBounds(hole, true)
        if (hole.isEmpty || !RectF(top).contains(hole.centerX(), hole.centerY())) {
            hole.set(top)
        }
        val half = min(hole.width(), hole.height()) * DROP_SCALE / 2f
        return RectF(
            hole.centerX() - half,
            hole.centerY() - half,
            hole.centerX() + half,
            hole.centerY() + half,
        )
    }

    /** Android's own animations start on a row for its morph, and leave the morph alone. */
    @JvmStatic
    fun beginStockAnimations() {
        isStartingStock = true
    }

    @JvmStatic
    fun endStockAnimations() {
        isStartingStock = false
    }

    /**
     * Grows heads-up [view] out of [drop], once Android's own appear animation has started on it
     * to run at once.
     */
    @JvmStatic
    fun appear(view: ExpandableView, drop: RectF) {
        morphs.remove(view)?.cancel()
        val target = HeadsUpTarget()
        val morph = newMorph(view, drop, target)
        morphs[view] = morph
        touched[view] = target
        morph.grow {
            // Over unless it turned back, which carries on from here
            if (!morph.isRunning && morphs[view] === morph) {
                morphs.remove(view)
                reset(view)
            }
        }
    }

    /**
     * Shrinks heads-up [view] back into [drop], once Android's own disappear animation has
     * started on it; one still growing turns back. Once gone it stays gone until [cancel], when
     * Android's animation ends.
     */
    @JvmStatic
    fun disappear(view: ExpandableView, drop: RectF) {
        val running = morphs[view]
        if (running != null && running.isRunning) {
            running.shrink()
            return
        }
        running?.cancel()
        val target = HeadsUpTarget()
        val morph = newMorph(view, drop, target)
        morphs[view] = morph
        touched[view] = target
        morph.shrinkFromFull()
    }

    /** Puts [view] back as it was, unmorphed, stopping any morph on it. */
    @JvmStatic
    fun cancel(view: View) {
        morphs.remove(view)?.cancel()
        reset(view)
    }

    /**
     * Android starts an appear or disappear animation on [view]: unless it's for the morph, the
     * morph stops.
     */
    @JvmStatic
    fun onStockAnimationStarting(view: View) {
        if (!isStartingStock) {
            cancel(view)
        }
    }

    private fun newMorph(view: ExpandableView, drop: RectF, target: HeadsUpTarget) =
        OwnGlassMorph(
            view,
            // It drips down out of the hole, then spreads into the card
            { OwnGlassMorphOrigin(OwnGlassMorphOrigin.TOP, drop.centerX(), drop, outLeads = true) },
            { Rect(0, 0, view.width, view.actualHeight) },
            null,
            target,
        )

    private fun reset(view: View) {
        touched.remove(view)?.reset(view)
    }

    /**
     * The morph on a row's animation matrix and its content's transition alpha, from wherever the
     * stack has the row: it can still be moving it, such as sliding one that times out up off the
     * screen, and where a row is as its heads-up appears isn't always where it's drawn next.
     */
    private class HeadsUpTarget : OwnGlassMorph.Target {
        private val matrix = Matrix()
        private val location = IntArray(2)

        override val followsView = true

        override fun prepare(view: View, shape: Rect) {
            view.animationMatrix = null
        }

        override fun apply(view: View, shape: Rect, full: RectF, now: RectF, alpha: Float) {
            // Where the view's own top left is on the screen now, its animation matrix aside
            view.getLocationOnScreen(location)
            matrix.setTranslate(-shape.left.toFloat(), -shape.top.toFloat())
            matrix.postScale(
                max(MIN_SIZE_PX, now.width()) / full.width(),
                max(MIN_SIZE_PX, now.height()) / full.height(),
            )
            matrix.postTranslate(now.left - location[0], now.top - location[1])
            view.animationMatrix = matrix
            setContentAlpha(view, alpha)
        }

        override fun jump(view: View, shown: Boolean) {
            // Nothing to grow out of: Android's own animation shows it as it does
            reset(view)
        }

        fun reset(view: View) {
            view.animationMatrix = null
            setContentAlpha(view, 1f)
        }

        private fun setContentAlpha(view: View, alpha: Float) {
            if (view !is ViewGroup) {
                return
            }
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                // The background is the glass: it shows all along
                if (child !is NotificationBackgroundView) {
                    child.transitionAlpha = alpha
                }
            }
        }
    }
}
