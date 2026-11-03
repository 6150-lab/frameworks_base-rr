/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.graphics.Rect
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.res.R
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Grows a panel out of the spot on the screen's edge that opened it, such as the volume keys,
 * and shrinks it back there, for Catalyxx glass's morph (GlassSettings.MORPH).
 *
 * [view] holds the whole panel and is scaled about that spot on two springs, one along the edge
 * and one out of it. The panel's main glass ([glass], in the view's own pixels) starts as a drop
 * [DROP_OUT_DP] thick and [DROP_ALONG_DP] long against the edge. Liquid lets the spring along the
 * edge lead, so the drop first stretches along the edge, then the panel swells a little past its
 * size on each axis in turn and settles; Smooth grows it cleanly. The view's content fades in
 * once the drop has grown and fades out early as it shrinks back. SurfaceFlinger's glass follows
 * the view's scaled bounds every frame by itself, whatever the content's alpha, and keeps its
 * corner radius, which Skia fits to the shrunk bounds: the drop is a capsule of glass.
 *
 * [progress] hears how far out the panel is, from 0 to 1, every frame, for what goes with it,
 * such as the dim behind. Main thread only.
 */
@MainThread
class OwnGlassMorph(
    private val view: View,
    private val origin: Origin,
    private val glass: Glass,
    private val progress: Progress? = null,
) {

    /** Where the panel grows out of, asked once it's laid out. */
    fun interface Origin {
        fun now(): OwnGlassMorphOrigin
    }

    /** The panel's main glass in the view's own pixels, unscaled; empty when it has none. */
    fun interface Glass {
        fun now(): Rect
    }

    /** How far out the panel is: 0 is the drop, 1 its full size. */
    fun interface Progress {
        fun onProgress(progress: Float)
    }

    // How far each spring has taken the panel from the drop (0) to its full size (1)
    private val along = FloatValueHolder(0f)
    private val out = FloatValueHolder(0f)
    private val alongSpring = newSpring(along)
    private val outSpring = newSpring(out)

    // The panel's scale at the drop along the edge and out of it, set once it's laid out
    private var dropAlong = 1f
    private var dropOut = 1f
    private var alongIsY = true
    private var isPrepared = false
    private var isWaitingForLayout = false
    private var isGrowing = false
    private var onEnd: Runnable? = null

    /** Whether the panel is still moving. */
    val isRunning: Boolean
        get() = alongSpring.isRunning || outSpring.isRunning

    /** Grows the panel out of its spot, then runs [then]; turns back if it was shrinking. */
    fun grow(then: Runnable? = null) {
        isGrowing = true
        start(then)
    }

    /**
     * Shrinks the panel back into its spot until it's gone, then runs [then]; turns back if it
     * was growing. Runs [then] at once if it never grew.
     */
    fun shrink(then: Runnable? = null) {
        isGrowing = false
        if (!isPrepared && !isWaitingForLayout) {
            replaceOnEnd(then)
            finish()
            return
        }
        start(then)
    }

    /** [grow], and waits until the panel has settled. */
    suspend fun growAndWait() = suspendCancellableCoroutine { continuation ->
        grow { if (continuation.isActive) continuation.resume(Unit) }
        continuation.invokeOnCancellation { cancel() }
    }

    /** [shrink], and waits until the panel is gone. */
    suspend fun shrinkAndWait() = suspendCancellableCoroutine { continuation ->
        shrink { if (continuation.isActive) continuation.resume(Unit) }
        continuation.invokeOnCancellation { cancel() }
    }

    /** Stops where it is, without running what was to come after. */
    fun cancel() {
        onEnd = null
        alongSpring.cancel()
        outSpring.cancel()
    }

    /** Also tells [listener] of every frame, as Android's own animations do for jank tracking. */
    fun addUpdateListener(listener: DynamicAnimation.OnAnimationUpdateListener) {
        outSpring.addUpdateListener(listener)
    }

    fun removeUpdateListener(listener: DynamicAnimation.OnAnimationUpdateListener) {
        outSpring.removeUpdateListener(listener)
    }

    private fun start(then: Runnable?) {
        replaceOnEnd(then)
        if (isPrepared || view.isLaidOut) {
            run()
            return
        }
        if (isWaitingForLayout) {
            // run() reads where it's going when the panel is laid out
            return
        }
        // Set up before the first frame is drawn, so the panel never shows at its full size first
        isWaitingForLayout = true
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    isWaitingForLayout = false
                    run()
                    return true
                }
            }
        )
    }

    private fun run() {
        if (!isPrepared && !prepare()) {
            // Nothing to grow out of: the panel just shows, or goes
            view.alpha = if (isGrowing) 1f else 0f
            progress?.onProgress(if (isGrowing) 1f else 0f)
            finish()
            return
        }
        OwnGlass.start(view.context)
        val style =
            if (OwnGlass.morphStyle == GlassSettings.MORPH_SMOOTH) {
                if (isGrowing) SMOOTH_GROW else SMOOTH_SHRINK
            } else {
                if (isGrowing) LIQUID_GROW else LIQUID_SHRINK
            }
        // A spring takes as much longer as one over the square root of its stiffness. Android's
        // animation speed comes on top, in the springs themselves.
        val speed = SPEED[OwnGlass.morphSpeed.coerceIn(0, SPEED.size - 1)]
        val stiffnessScale = 1f / (speed * speed)
        alongSpring.spring
            .setStiffness(style.alongStiffness * stiffnessScale)
            .setDampingRatio(style.alongDamping)
        outSpring.spring
            .setStiffness(style.outStiffness * stiffnessScale)
            .setDampingRatio(style.outDamping)
        alongSpring.animateToFinalPosition(if (isGrowing) 1f else vanished(dropAlong))
        outSpring.animateToFinalPosition(if (isGrowing) 1f else vanished(dropOut))
    }

    /**
     * Finds the spot to grow out of and the drop's size, and puts the panel at the drop. False
     * when the panel has no glass to grow.
     */
    private fun prepare(): Boolean {
        val shape = glass.now()
        if (shape.width() <= 0 || shape.height() <= 0) {
            return false
        }
        val spot = origin.now()
        view.scaleX = 1f
        view.scaleY = 1f
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val screen =
            view.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
                ?: return false
        val middleX = location[0] + shape.exactCenterX()
        val middleY = location[1] + shape.exactCenterY()
        val x: Float
        val y: Float
        when (spot.edge) {
            OwnGlassMorphOrigin.LEFT -> {
                x = screen.left.toFloat()
                y = spot.along ?: middleY
            }
            OwnGlassMorphOrigin.RIGHT -> {
                x = screen.right.toFloat()
                y = spot.along ?: middleY
            }
            OwnGlassMorphOrigin.TOP -> {
                x = spot.along ?: middleX
                y = screen.top.toFloat()
            }
            else -> {
                x = spot.along ?: middleX
                y = screen.bottom.toFloat()
            }
        }
        view.pivotX = x - location[0]
        view.pivotY = y - location[1]
        alongIsY = spot.edge == OwnGlassMorphOrigin.LEFT || spot.edge == OwnGlassMorphOrigin.RIGHT
        val density = view.resources.displayMetrics.density
        val alongSize = if (alongIsY) shape.height() else shape.width()
        val outSize = if (alongIsY) shape.width() else shape.height()
        dropAlong = min(1f, DROP_ALONG_DP * density / alongSize)
        dropOut = min(1f, DROP_OUT_DP * density / outSize)
        along.value = 0f
        out.value = 0f
        isPrepared = true
        applyFrame()
        return true
    }

    /** Where a spring takes an axis whose drop is at [drop] for the panel to be gone. */
    private fun vanished(drop: Float): Float =
        if (drop < 1f) (VANISHED_SCALE - drop) / (1f - drop) else 0f

    private fun applyFrame() {
        val a = along.value
        val o = out.value
        val alongScale = max(VANISHED_SCALE, dropAlong + (1f - dropAlong) * a)
        val outScale = max(VANISHED_SCALE, dropOut + (1f - dropOut) * o)
        if (alongIsY) {
            view.scaleX = outScale
            view.scaleY = alongScale
        } else {
            view.scaleX = alongScale
            view.scaleY = outScale
        }
        // The content shows once the drop has grown, and is the first to go
        val grown = min(a, o)
        view.alpha =
            if (isGrowing) smooth(FADE_IN_FROM, FADE_IN_TO, grown)
            else smooth(FADE_OUT_TO, FADE_OUT_FROM, grown)
        progress?.onProgress(o.coerceIn(0f, 1f))
    }

    private fun replaceOnEnd(then: Runnable?) {
        // Whoever waited for the last move hears it's over, so nothing waits forever
        val last = onEnd
        onEnd = then
        last?.run()
    }

    private fun finish() {
        val then = onEnd
        onEnd = null
        then?.run()
    }

    private fun newSpring(holder: FloatValueHolder): SpringAnimation =
        SpringAnimation(holder).apply {
            spring = SpringForce()
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, _, _ -> applyFrame() }
            addEndListener { _, canceled, _, _ ->
                if (!canceled && !this@OwnGlassMorph.isRunning) {
                    finish()
                }
            }
        }

    /** Springs along the edge and out of it: stiffness and damping ratio. */
    private class Style(
        val alongStiffness: Float,
        val alongDamping: Float,
        val outStiffness: Float,
        val outDamping: Float,
    )

    private companion object {
        // The drop the panel starts as and ends as, before it's gone
        const val DROP_OUT_DP = 8f
        const val DROP_ALONG_DP = 40f
        // A panel shrunk this far is gone; never 0, which no view can be scaled to safely
        const val VANISHED_SCALE = 0.02f

        // The content fades in while the drop grows from FADE_IN_FROM to FADE_IN_TO of the way
        // to its full size, and out while it shrinks from FADE_OUT_FROM to FADE_OUT_TO
        const val FADE_IN_FROM = 0.35f
        const val FADE_IN_TO = 0.8f
        const val FADE_OUT_FROM = 0.9f
        const val FADE_OUT_TO = 0.55f

        // Liquid: along the edge first, past full size by about 6%, then out of it, past by
        // about 5%: a thin drop that swells and wobbles once. Going back, out of the edge first
        // (pulled back in), then along it, with no bounce, so it never turns inside out.
        val LIQUID_GROW = Style(600f, 0.66f, 380f, 0.7f)
        val LIQUID_SHRINK = Style(450f, 1f, 700f, 1f)
        val SMOOTH_GROW = Style(450f, 1f, 450f, 1f)
        val SMOOTH_SHRINK = Style(700f, 1f, 700f, 1f)

        // How much longer a morph takes at GlassSettings.MORPH_SLOW, _NORMAL and _FAST
        val SPEED = floatArrayOf(1.5f, 1f, 0.7f)

        fun smooth(from: Float, to: Float, x: Float): Float {
            val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/**
 * A spot on the screen's edge that a panel grows out of: the [edge] ([LEFT], [TOP], [RIGHT] or
 * [BOTTOM] of the screen as it's turned now) and how far [along] it, in pixels: x on the top and
 * bottom edges, y on the sides. A null [along] stands for the panel's own middle.
 */
data class OwnGlassMorphOrigin(val edge: Int, val along: Float?) {

    companion object {
        const val LEFT = 0
        const val TOP = 1
        const val RIGHT = 2
        const val BOTTOM = 3

        // The sides in catalyxx_config.xml, with the phone held upright
        private const val SIDE_LEFT = 1
        private const val SIDE_RIGHT = 2

        /** The middle of the power key, for the screen [view] is on as it's turned now. */
        @JvmStatic
        fun powerKey(view: View): OwnGlassMorphOrigin {
            val res = view.resources
            var y =
                override(view, GlassSettings.MORPH_POWER_KEY_Y)
                    ?: res.getDimension(R.dimen.catalyxx_morph_power_key_y)
            if (y < 0f) {
                y =
                    res.getDimensionPixelSize(R.dimen.physical_power_button_center_screen_location_y)
                        .toFloat()
            }
            val side = res.getInteger(R.integer.catalyxx_morph_power_key_side)
            return ofKey(view, side == SIDE_LEFT, y)
        }

        /**
         * The middle of the volume keys, for the screen [view] is on as it's turned now. They're
         * on the side the volume panel shows on with the phone upright ([panelOnLeft]) unless
         * the phone says which side they're on.
         */
        @JvmStatic
        fun volumeKeys(view: View, panelOnLeft: Boolean): OwnGlassMorphOrigin {
            val res = view.resources
            val side = res.getInteger(R.integer.catalyxx_morph_volume_keys_side)
            val onLeft = if (side == SIDE_LEFT || side == SIDE_RIGHT) side == SIDE_LEFT else panelOnLeft
            val y =
                override(view, GlassSettings.MORPH_VOLUME_KEYS_Y)
                    ?: res.getDimension(R.dimen.catalyxx_morph_volume_keys_y)
            return ofKey(view, onLeft, if (y >= 0f) y else null)
        }

        private fun override(view: View, key: String): Float? {
            val y = Settings.Global.getInt(view.context.contentResolver, key, -1)
            return if (y >= 0) y.toFloat() else null
        }

        /**
         * A key on the phone's left side ([onLeft]) or right side, [fromTop] pixels down from the
         * top of the screen held upright (null: not known), on the screen as it's turned now.
         */
        private fun ofKey(view: View, onLeft: Boolean, fromTop: Float?): OwnGlassMorphOrigin {
            val screen =
                view.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            val rotation = view.display?.rotation ?: Surface.ROTATION_0
            // The screen's height held upright
            val uprightHeight =
                when {
                    screen == null -> 0
                    rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270 ->
                        screen.width()
                    else -> screen.height()
                }
            return when (rotation) {
                // Turned left: the phone's right side is on top
                Surface.ROTATION_90 -> OwnGlassMorphOrigin(if (onLeft) BOTTOM else TOP, fromTop)
                Surface.ROTATION_180 ->
                    OwnGlassMorphOrigin(if (onLeft) RIGHT else LEFT, fromTop?.let { uprightHeight - it })
                // Turned right: the phone's right side is at the bottom
                Surface.ROTATION_270 ->
                    OwnGlassMorphOrigin(if (onLeft) TOP else BOTTOM, fromTop?.let { uprightHeight - it })
                else -> OwnGlassMorphOrigin(if (onLeft) LEFT else RIGHT, fromTop)
            }
        }
    }
}
