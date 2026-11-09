/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
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
 * The panel's main glass ([glass], in [view]'s own pixels) starts as a drop [DROP_OUT_DP] thick
 * and [DROP_ALONG_DP] long against the edge, its middle at the spot, or as the spot's own drop,
 * and moves and grows to its own place and size on two springs, one along the edge and one out of
 * it; [view], which holds the whole panel, is scaled and moved to match. Liquid lets the spring
 * along the edge lead, so the drop first stretches along the edge, then the panel swells a little
 * past its size on each axis in turn and settles; Smooth grows it cleanly. The view's content
 * fades in once the drop has grown and fades out early as it shrinks back. SurfaceFlinger's glass
 * follows the view's scaled bounds every frame by itself, whatever the content's alpha, and keeps
 * its corner radius, which Skia fits to the shrunk bounds: the drop is a capsule of glass.
 *
 * [progress] hears how far out the panel is, from 0 to 1, every frame, for what goes with it,
 * such as the dim behind. [target] is how the morph shows on the view: by default on its own
 * scale, offset and alpha. With [meltingGlass], the panel's glass stays joined to the spot by a
 * neck that stretches and snaps ([OwnGlassNeck]) while Melt together is on. Main thread only.
 */
@MainThread
class OwnGlassMorph
@JvmOverloads
constructor(
    private val view: View,
    private val origin: Origin,
    private val glass: Glass,
    private val progress: Progress? = null,
    private val target: Target = ViewTarget(),
    meltingGlass: MeltingGlass? = null,
) {

    /** Where the panel grows out of, asked once it's laid out. */
    fun interface Origin {
        fun now(): OwnGlassMorphOrigin
    }

    /** The panel's main glass in the view's own pixels, unscaled; empty when it has none. */
    fun interface Glass {
        fun now(): Rect
    }

    /** The panel's glass drawable, to melt into; null when it has none. */
    fun interface MeltingGlass {
        fun now(): BackgroundBlurDrawable?
    }

    /** How far out the panel is: 0 is the drop, 1 its full size. */
    fun interface Progress {
        fun onProgress(progress: Float)
    }

    /** How the morph shows on the view. */
    interface Target {
        /** Before the morph sets out: puts [view], whose glass is [shape], back at rest. */
        fun prepare(view: View, shape: Rect)

        /**
         * A frame: [view]'s glass, [shape] in its own pixels and [full] on the screen at its full
         * size, is drawn at [now] on the screen, and its content at [alpha].
         */
        fun apply(view: View, shape: Rect, full: RectF, now: RectF, alpha: Float)

        /** There's nothing to grow out of: [view] just shows ([shown]) or goes. */
        fun jump(view: View, shown: Boolean)

        /**
         * Whether something else moves [view] while it morphs, such as a notification the stack
         * places. Then every frame is set again just before it's drawn, from where the view is
         * then, the drop staying where it is on the screen: the glass grows into where the view
         * ends up, and shrinks from where it was as it set out; gone, it's held in the spot
         * until the morph is cancelled. [apply] places it from where the view is itself.
         */
        val followsView: Boolean
            get() = false
    }

    /**
     * The morph on the view's own scale, offset and alpha, for a view that nothing else moves
     * while it morphs. It's scaled about its glass's top left corner, then moved to put the glass
     * where it is now.
     */
    class ViewTarget : Target {
        // The view's own offset at rest, which the morph moves it from
        private var restX = 0f
        private var restY = 0f

        override fun prepare(view: View, shape: Rect) {
            view.scaleX = 1f
            view.scaleY = 1f
            restX = view.translationX
            restY = view.translationY
            view.pivotX = shape.left.toFloat()
            view.pivotY = shape.top.toFloat()
        }

        override fun apply(view: View, shape: Rect, full: RectF, now: RectF, alpha: Float) {
            view.scaleX = max(GONE_PX, now.width()) / full.width()
            view.scaleY = max(GONE_PX, now.height()) / full.height()
            view.translationX = restX + now.left - full.left
            view.translationY = restY + now.top - full.top
            view.alpha = alpha
        }

        override fun jump(view: View, shown: Boolean) {
            view.alpha = if (shown) 1f else 0f
        }
    }

    // How far each spring has taken the panel from the drop (0) to its full size (1)
    private val along = FloatValueHolder(0f)
    private val out = FloatValueHolder(0f)
    private val alongSpring = newSpring(along)
    private val outSpring = newSpring(out)

    // On the screen, set once the panel is laid out: its glass at full size, the drop against the
    // edge it grows out of, and where the glass is now
    private val full = RectF()
    private val drop = RectF()
    private val now = RectF()
    // The panel's glass in the view's own pixels when it was laid out
    private val shape = Rect()
    private var alongIsY = true
    // Whether the edge is the screen's left or top, where the glass starts out of it
    private var edgeIsLow = true
    private var isPrepared = false
    private var isWaitingForLayout = false
    private var isGrowing = false
    // Whether the next prepare() puts the panel at its full size rather than at the drop
    private var startsFull = false
    // Whether the spring out of the edge leads, from the spot: a drop that drips
    private var outLeads = false
    private var onEnd: Runnable? = null
    // Both springs draw each frame; what goes with the panel only hears when it has moved
    private var lastProgress = Float.NaN

    // For a target that follows the view: where the view is on the screen, the frame set again
    // just before it's drawn while the morph runs, the window that does it, and whether [full]
    // stays where it is now, shrinking
    private val location = IntArray(2)
    private val follower =
        ViewTreeObserver.OnPreDrawListener {
            if (isPrepared) {
                applyFrame(isBeforeDraw = true)
            }
            true
        }
    private var followedTree: ViewTreeObserver? = null
    private var isFullKept = false

    private val neck = meltingGlass?.let { OwnGlassNeck(view, it) }

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

    /**
     * Shrinks the panel back into its spot from its full size, where it is now, until it's gone,
     * then runs [then]: for a panel that never grew out of it, or did long ago.
     */
    fun shrinkFromFull(then: Runnable? = null) {
        alongSpring.cancel()
        outSpring.cancel()
        isGrowing = false
        isPrepared = false
        startsFull = true
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
        unfollow()
        neck?.remove()
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
            target.jump(view, isGrowing)
            progress?.onProgress(if (isGrowing) 1f else 0f)
            finish()
            return
        }
        if (target.followsView) {
            // Growing, the glass goes where the view is every frame; shrinking, it sets out from
            // where the view is on the next one, and stays there
            isFullKept = false
            follow()
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
        val speed = currentSpeed()
        val stiffnessScale = 1f / (speed * speed)
        // A drop that drips out of its spot moves out of it first, then along the edge
        val alongStiffness = if (outLeads) style.outStiffness else style.alongStiffness
        val alongDamping = if (outLeads) style.outDamping else style.alongDamping
        val outStiffness = if (outLeads) style.alongStiffness else style.outStiffness
        val outDamping = if (outLeads) style.alongDamping else style.outDamping
        alongSpring.spring
            .setStiffness(alongStiffness * stiffnessScale)
            .setDampingRatio(alongDamping)
        outSpring.spring.setStiffness(outStiffness * stiffnessScale).setDampingRatio(outDamping)
        alongSpring.animateToFinalPosition(if (isGrowing) 1f else GONE_AIM)
        outSpring.animateToFinalPosition(if (isGrowing) 1f else GONE_AIM)
    }

    /**
     * Finds the panel's glass on the screen and the drop it grows out of, and puts the panel at
     * the drop. False when the panel has no glass to grow.
     */
    private fun prepare(): Boolean {
        val glassNow = glass.now()
        if (glassNow.width() <= 0 || glassNow.height() <= 0) {
            return false
        }
        val screen =
            view.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
                ?: return false
        val spot = origin.now()
        shape.set(glassNow)
        target.prepare(view, shape)
        view.getLocationOnScreen(location)
        full.set(shape)
        full.offset(location[0].toFloat(), location[1].toFloat())
        alongIsY = spot.edge == OwnGlassMorphOrigin.LEFT || spot.edge == OwnGlassMorphOrigin.RIGHT
        edgeIsLow = spot.edge == OwnGlassMorphOrigin.LEFT || spot.edge == OwnGlassMorphOrigin.TOP
        outLeads = spot.outLeads
        val spotDrop = spot.drop
        // Where a bead of glass can stay out of sight at the spot, to melt into the panel
        var bead: RectF? = null
        var beadRadius = 0f
        if (spotDrop != null) {
            // The spot's own drop, such as one inside the camera hole
            drop.set(spotDrop)
            bead = spot.bead
            beadRadius = bead?.let { min(it.width(), it.height()) / 2f } ?: 0f
        } else {
            // The drop lies against the edge with its middle at the spot; never bigger than the
            // panel
            val density = view.resources.displayMetrics.density
            val outSize = min(DROP_OUT_DP * density, if (alongIsY) full.width() else full.height())
            val alongSize =
                min(DROP_ALONG_DP * density, if (alongIsY) full.height() else full.width())
            if (alongIsY) {
                val middle = spot.along ?: full.centerY()
                val x =
                    if (spot.edge == OwnGlassMorphOrigin.LEFT) screen.left.toFloat()
                    else screen.right - outSize
                drop.set(x, middle - alongSize / 2f, x + outSize, middle + alongSize / 2f)
            } else {
                val middle = spot.along ?: full.centerX()
                val y =
                    if (spot.edge == OwnGlassMorphOrigin.TOP) screen.top.toFloat()
                    else screen.bottom - outSize
                drop.set(middle - alongSize / 2f, y, middle + alongSize / 2f, y + outSize)
            }
            // The panel can only draw inside its window: where the window doesn't reach the
            // spot, such as a narrow volume dialog with the keys across the screen, the drop
            // starts at the window's nearest place to it. A spot's own drop is where it is
            val root = view.rootView
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val windowLeft = rootLocation[0].toFloat()
            val windowTop = rootLocation[1].toFloat()
            val moveX = fit(drop.left, drop.right, windowLeft, windowLeft + root.width)
            val moveY = fit(drop.top, drop.bottom, windowTop, windowTop + root.height)
            drop.offset(moveX, moveY)
            if (moveX == 0f && moveY == 0f) {
                // Just past the screen's edge, as long as the drop. Out of the window's reach,
                // there's no neck to show
                val behind = BEAD_BEHIND_DP * density
                bead =
                    when (spot.edge) {
                        OwnGlassMorphOrigin.LEFT ->
                            RectF(screen.left - behind, drop.top, screen.left.toFloat(), drop.bottom)
                        OwnGlassMorphOrigin.RIGHT ->
                            RectF(screen.right.toFloat(), drop.top, screen.right + behind, drop.bottom)
                        OwnGlassMorphOrigin.TOP ->
                            RectF(drop.left, screen.top - behind, drop.right, screen.top.toFloat())
                        else ->
                            RectF(drop.left, screen.bottom.toFloat(), drop.right, screen.bottom + behind)
                    }
                beadRadius = BEAD_RADIUS_DP * density
            }
        }
        if (bead != null) {
            neck?.place(bead, beadRadius, currentSpeed())
        }
        // It sets out from the drop, or from its full size for shrinkFromFull()
        val startAt = if (startsFull) 1f else 0f
        startsFull = false
        along.value = startAt
        out.value = startAt
        isPrepared = true
        applyFrame()
        return true
    }

    /**
     * Sets a frame from where the springs are; [isBeforeDraw], just before it's drawn, for a
     * target that follows the view, from where the view is then.
     */
    private fun applyFrame(isBeforeDraw: Boolean = false) {
        if (isBeforeDraw && !isFullKept) {
            view.getLocationOnScreen(location)
            full.set(shape)
            full.offset(location[0].toFloat(), location[1].toFloat())
            isFullKept = !isGrowing
        }
        val a = along.value
        val o = out.value
        if (alongIsY) {
            side(drop.left, drop.right, full.left, full.right, o, isAlong = false)
            now.left = sideStart
            now.right = sideEnd
            side(drop.top, drop.bottom, full.top, full.bottom, a, isAlong = true)
            now.top = sideStart
            now.bottom = sideEnd
        } else {
            side(drop.left, drop.right, full.left, full.right, a, isAlong = true)
            now.left = sideStart
            now.right = sideEnd
            side(drop.top, drop.bottom, full.top, full.bottom, o, isAlong = false)
            now.top = sideStart
            now.bottom = sideEnd
        }
        neck?.onFrame(o, isGrowing)
        if (!isGrowing && (now.width() <= GONE_PX || now.height() <= GONE_PX)) {
            if (target.followsView) {
                // Held gone in the spot, wherever the view goes, until the morph is cancelled
                target.apply(view, shape, full, now, 0f)
            }
            if (isRunning) {
                // Gone: done, rather than waiting for the springs to settle past nothing
                alongSpring.cancel()
                outSpring.cancel()
                finish()
            }
            return
        }
        // The content shows once the drop has grown, and is the first to go
        val grown = min(a, o)
        val alpha =
            if (isGrowing) smooth(FADE_IN_FROM, FADE_IN_TO, grown)
            else smooth(FADE_OUT_TO, FADE_OUT_FROM, grown)
        target.apply(view, shape, full, now, alpha)
        val out = o.coerceIn(0f, 1f)
        if (out != lastProgress) {
            lastProgress = out
            progress?.onProgress(out)
        }
    }

    /**
     * How far to move the span [start] to [end] to put it inside [low] to [high]; its start
     * stays inside when it's the longer.
     */
    private fun fit(start: Float, end: Float, low: Float, high: Float): Float =
        when {
            start < low -> low - start
            end > high -> max(low - start, high - end)
            else -> 0f
        }

    // The span side() last found
    private var sideStart = 0f
    private var sideEnd = 0f

    /**
     * Where the glass spans on one axis at progress [p] of its spring: from the drop's span
     * ([dropStart] to [dropEnd]) at 0 to its full one at 1. Below 0 the drop itself shrinks, to
     * nothing at -1: along the edge about its middle, at the spot; out of it into the edge.
     */
    private fun side(
        dropStart: Float,
        dropEnd: Float,
        fullStart: Float,
        fullEnd: Float,
        p: Float,
        isAlong: Boolean,
    ) {
        if (p >= 0f) {
            sideStart = lerp(dropStart, fullStart, p)
            sideEnd = lerp(dropEnd, fullEnd, p)
            return
        }
        val size = (dropEnd - dropStart) * max(0f, 1f + p)
        when {
            isAlong -> {
                val middle = (dropStart + dropEnd) / 2f
                sideStart = middle - size / 2f
                sideEnd = middle + size / 2f
            }
            edgeIsLow -> {
                sideStart = dropStart
                sideEnd = dropStart + size
            }
            else -> {
                sideStart = dropEnd - size
                sideEnd = dropEnd
            }
        }
    }

    private fun replaceOnEnd(then: Runnable?) {
        // Whoever waited for the last move hears it's over, so nothing waits forever
        val last = onEnd
        onEnd = then
        last?.run()
    }

    private fun finish() {
        // Gone, a view something else moves stays held in the spot until the morph is cancelled
        if (isGrowing || !target.followsView) {
            unfollow()
        }
        // Grown, the neck lets go in its own time
        if (!isGrowing) {
            neck?.remove()
        }
        val then = onEnd
        onEnd = null
        then?.run()
    }

    private fun follow() {
        if (followedTree != null) {
            return
        }
        val tree = view.viewTreeObserver
        tree.addOnPreDrawListener(follower)
        followedTree = tree
    }

    private fun unfollow() {
        val tree = followedTree ?: return
        followedTree = null
        // The window it was added to, or the one the view is in now if that one has gone
        if (tree.isAlive) {
            tree.removeOnPreDrawListener(follower)
        } else {
            view.viewTreeObserver.removeOnPreDrawListener(follower)
        }
    }

    private fun currentSpeed(): Float = SPEED[OwnGlass.morphSpeed.coerceIn(0, SPEED.size - 1)]

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
        // The bead of glass past the screen's edge that a neck melts out of ([OwnGlassNeck]): how
        // far past the edge it reaches, and its corners
        const val BEAD_BEHIND_DP = 40f
        const val BEAD_RADIUS_DP = 12f
        // Going, the springs aim past where the drop is gone (-1), so they're still moving when it
        // is, and the panel is gone once a side is this many pixels long; a view is never scaled
        // to 0, which isn't safe
        const val GONE_AIM = -1.3f
        const val GONE_PX = 1.5f

        // The content fades in while the drop grows from FADE_IN_FROM to FADE_IN_TO of the way
        // to its full size, and out while it shrinks from FADE_OUT_FROM to FADE_OUT_TO
        const val FADE_IN_FROM = 0.35f
        const val FADE_IN_TO = 0.8f
        const val FADE_OUT_FROM = 0.9f
        const val FADE_OUT_TO = 0.55f

        // Liquid: along the edge first, past full size by about 6%, then out of it, past by
        // about 5%: a thin drop that swells and wobbles once. Going back, out of the edge a
        // little ahead (pulled back in) to the drop, which then shrinks into the spot, in about
        // 0.2 s; no bounce, so it never turns inside out.
        val LIQUID_GROW = Style(600f, 0.66f, 380f, 0.7f)
        val LIQUID_SHRINK = Style(200f, 1f, 290f, 1f)
        val SMOOTH_GROW = Style(450f, 1f, 450f, 1f)
        val SMOOTH_SHRINK = Style(250f, 1f, 250f, 1f)

        // How much longer a morph takes at GlassSettings.MORPH_SLOW, _NORMAL and _FAST
        val SPEED = floatArrayOf(1.5f, 1f, 0.7f)

        fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

        fun smooth(from: Float, to: Float, x: Float): Float {
            val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/**
 * A spot on the screen's edge that a panel grows out of: the [edge] ([LEFT], [TOP], [RIGHT] or
 * [BOTTOM] of the screen as it's turned now) and how far [along] it, in pixels: x on the top and
 * bottom edges, y on the sides. A null [along] stands for the panel's own middle. A [drop], on
 * the screen, is the drop the panel grows out of, such as one inside the camera hole, in place of
 * one against the edge; the edge still says which way is out of it. With [outLeads], the drop
 * drips: Liquid moves it out of the edge first, and along it second, the other way round from a
 * drop against the edge. With a [drop], [bead], on the screen, is where a bead of glass stays out
 * of sight to melt into the panel.
 */
data class OwnGlassMorphOrigin
@JvmOverloads
constructor(
    val edge: Int,
    val along: Float?,
    val drop: RectF? = null,
    val outLeads: Boolean = false,
    val bead: RectF? = null,
) {

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
            val y = GlassSettings.getInt(view.context.contentResolver, key, -1)
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
