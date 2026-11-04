/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.animation

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewRootImpl
import android.view.WindowManager
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.internal.util.catalyxx.GlassLook
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.animation.TransitionAnimator.SpringParams
import kotlin.math.roundToInt

/**
 * Catalyxx glass on a pop-up that grows out of what opened it, such as a Quick Settings tile's
 * (GlassSettings.PLACE_DIALOGS). At rest, the pop-up's background is glass. While it grows or
 * shrinks, one glass shape follows the expanding outline in the pop-up's window, and the pop-up
 * and what it grows out of cross-fade over it instead of swapping halfway under an opaque layer,
 * as they do without glass. The pop-up's own background is cleared: glass with a rim brings its
 * own color, and Flat glass gets the glass's tint instead.
 *
 * [view] is the pop-up with its background (DialogTransitionAnimator's
 * dialogContentWithBackground) in [decorView], its window. Main thread only.
 */
internal class DialogGlass
private constructor(
    private val decorView: ViewGroup,
    private val view: View,
    private val plainBackground: Drawable,
    private val look: GlassLook?,
    private val blurRadius: Int,
    private val cornerRadius: Float,
) {

    // The glass that follows the outline while the pop-up grows or shrinks, in the window's
    // overlay, and the view it's the background of
    private var morphHost: View? = null
    private var morphGlass: BackgroundBlurDrawable? = null

    // While the pop-up grows, its glass waits for the transition's first step
    private var isWaitingToShow = false

    // The root of the window the pop-up grows out of, while it does
    private var sourceView: View? = null

    // The glass under the pop-up's background at rest
    private var restGlass: BackgroundBlurDrawable? = null

    private val location = IntArray(2)
    private val padding = Rect()

    /**
     * Before the pop-up starts to grow or shrink: its own glass comes off, so the pop-up's
     * background is plain when Android's transition wraps it, which would draw the glass twice.
     */
    fun beforeTransition() {
        removeRestGlass()
    }

    /**
     * The pop-up starts to grow ([isLaunching]) or shrink from [state]. Shrinking, the glass shape
     * is there at once, in place of the pop-up's own glass. Growing, it shows on the frame after
     * the transition's first step: what the pop-up grows out of starts to draw in the pop-up's
     * window only then, as a Quick Settings tile does (Compose draws a change on the frame after
     * it), and glass there before it would blur the tile where it still is. That frame of the
     * window it leaves, [sourceView]'s, and the pop-up's then show together: one shown before the
     * other would leave the tile out of both, or in both, for a frame.
     */
    fun onTransitionStart(
        state: TransitionAnimator.State,
        isLaunching: Boolean,
        sourceView: View?,
    ) {
        if (morphHost == null) {
            val viewRoot = decorView.viewRootImpl ?: return
            val glass =
                newGlass(viewRoot).apply {
                    // Over the ghosts already in the overlay: no hole through them
                    setXfermode(null)
                }
            val host = View(decorView.context).apply { background = glass }
            decorView.overlay.add(host)
            morphGlass = glass
            morphHost = host
        }
        morphGlass?.alpha = if (isLaunching) 0 else 255
        isWaitingToShow = isLaunching
        this.sourceView = if (isLaunching) sourceView else null
        place(state)
    }

    /** A step of the transition: the glass shape goes where the outline is now. */
    fun onTransitionProgress(state: TransitionAnimator.State) {
        val host = morphHost ?: return
        place(state)
        if (isWaitingToShow) {
            isWaitingToShow = false
            val glass = morphGlass
            // Steps come on a frame's animation callbacks, or off a frame for animations that
            // step as they start, so this is the next frame's
            host.postOnAnimation {
                if (morphGlass === glass) {
                    glass?.alpha = 255
                    sourceView?.let { ViewRootSync.synchronizeNextDraw(it, decorView) {} }
                }
            }
        }
    }

    private fun place(state: TransitionAnimator.State) {
        val host = morphHost ?: return
        decorView.getLocationOnScreen(location)
        host.setLeftTopRightBottom(
            state.left - location[0],
            state.top - location[1],
            state.right - location[0],
            state.bottom - location[1],
        )
        morphGlass?.setCornerRadius(
            state.topCornerRadius,
            state.topCornerRadius,
            state.bottomCornerRadius,
            state.bottomCornerRadius,
        )
    }

    /** The pop-up has grown ([isLaunching]) or shrunk: the shape gives way to its own glass. */
    fun onTransitionEnd(isLaunching: Boolean) {
        removeMorphGlass()
        if (isLaunching) {
            addRestGlass()
        }
    }

    /** The pop-up is gone. */
    fun dispose() {
        removeMorphGlass()
        removeRestGlass()
    }

    private fun newGlass(viewRoot: ViewRootImpl): BackgroundBlurDrawable =
        viewRoot.createBackgroundBlurDrawable().apply {
            setBlurRadius(blurRadius)
            setCornerRadius(cornerRadius)
            setGlass(look)
            setColor(Color.TRANSPARENT)
        }

    private fun removeMorphGlass() {
        val host = morphHost ?: return
        morphGlass?.setVisible(false, false)
        decorView.overlay.remove(host)
        morphHost = null
        morphGlass = null
        sourceView = null
    }

    private fun addRestGlass() {
        if (restGlass != null) {
            return
        }
        val viewRoot = view.viewRootImpl ?: return
        val glass = newGlass(viewRoot)
        // Under the background's own shape, which an inset background draws inside its insets
        plainBackground.getPadding(padding)
        view.background =
            LayerDrawable(arrayOf(glass, plainBackground)).apply {
                setLayerInset(0, padding.left, padding.top, padding.right, padding.bottom)
                setPadding(padding.left, padding.top, padding.right, padding.bottom)
            }
        restGlass = glass
    }

    private fun removeRestGlass() {
        val glass = restGlass ?: return
        // Hidden first: the window only forgets blur drawables that are hidden
        glass.setVisible(false, false)
        view.background = plainBackground
        restGlass = null
    }

    companion object {
        // The pop-up's blur at the default glass strength, as bottom sheets have it
        private const val BLUR_RADIUS_DP = 30f

        // On glass the pop-up fades out over this part of its spring's way while it shrinks, and
        // in over the second while it grows, with what it grows out of doing the opposite
        const val FADE_OUT_FROM = 0.08f
        const val FADE_OUT_TO = 0.45f
        const val FADE_IN_FROM = 0.3f
        const val FADE_IN_TO = 0.75f

        // How tile pop-ups grow and shrink with Catalyxx glass's morph: Liquid swells about 5%
        // past full size and settles with a wobble, and lands back in its tile with a little
        // bounce; Smooth grows as Android does and goes back without a bounce
        private val LIQUID_LAUNCH = SpringParams(600f, 0.8f, 600f, 0.8f, 500f, 0.68f)
        private val LIQUID_COLLAPSE = SpringParams(450f, 0.85f, 450f, 0.85f, 450f, 0.8f)
        private val SMOOTH_COLLAPSE = SpringParams(380f, 1f, 380f, 1f, 380f, 1f)

        // How much longer the morph takes at GlassSettings.MORPH_SLOW, _NORMAL and _FAST
        private val SPEED = floatArrayOf(1.5f, 1f, 0.7f)

        /**
         * Glass for the pop-up [view] of [dialog], which shows in [decorView]; null where glass
         * is off for pop-ups, the system's blur is off, or the pop-up's background isn't a plain
         * shape it can clear.
         */
        fun create(dialog: Dialog, decorView: ViewGroup, view: View): DialogGlass? {
            val context = dialog.context
            val resolver = context.contentResolver
            if (!GlassSettings.isOn(resolver, GlassSettings.PLACE_DIALOGS)) {
                return null
            }
            val windowManager = context.getSystemService(WindowManager::class.java)
            if (windowManager == null || !windowManager.isCrossWindowBlurEnabled) {
                return null
            }
            // Its own copy: the shape is shared with every pop-up drawn from the same theme
            val background = view.background?.mutate() ?: return null
            val shape =
                GhostedViewTransitionAnimatorController.findGradientDrawable(background)
                    ?: return null
            val androidColor = shape.color?.defaultColor ?: return null
            val look = GlassSettings.look(context, GlassSettings.PLACE_DIALOGS, androidColor)
            shape.setColor(
                if (look != null) {
                    Color.TRANSPARENT
                } else {
                    GlassSettings.tintColor(context, GlassSettings.PLACE_DIALOGS, androidColor)
                }
            )
            val blurRadius =
                GlassSettings.scaleBlurRadius(
                    (BLUR_RADIUS_DP * context.resources.displayMetrics.density).roundToInt(),
                    GlassSettings.blurScale(resolver, GlassSettings.PLACE_DIALOGS),
                )
            return DialogGlass(decorView, view, background, look, blurRadius, shape.cornerRadius)
        }

        /**
         * The springs a tile pop-up grows ([isLaunching]) or shrinks on with Catalyxx glass's
         * morph, which [stock] are without it.
         */
        fun springParams(context: Context, isLaunching: Boolean, stock: SpringParams): SpringParams {
            val resolver = context.contentResolver
            if (!GlassSettings.morphs(resolver, GlassSettings.MORPH_TILES)) {
                return stock
            }
            val isSmooth =
                Settings.Global.getInt(
                    resolver,
                    GlassSettings.MORPH_STYLE,
                    GlassSettings.DEFAULT_MORPH_STYLE,
                ) == GlassSettings.MORPH_SMOOTH
            val springs =
                when {
                    isSmooth && isLaunching -> stock
                    isSmooth -> SMOOTH_COLLAPSE
                    isLaunching -> LIQUID_LAUNCH
                    else -> LIQUID_COLLAPSE
                }
            // A spring takes as much longer as one over the square root of its stiffness
            val speed =
                SPEED[
                    Settings.Global.getInt(
                            resolver,
                            GlassSettings.MORPH_SPEED,
                            GlassSettings.DEFAULT_MORPH_SPEED,
                        )
                        .coerceIn(0, SPEED.size - 1)]
            val scale = 1f / (speed * speed)
            return springs.copy(
                centerXStiffness = springs.centerXStiffness * scale,
                centerYStiffness = springs.centerYStiffness * scale,
                scaleStiffness = springs.scaleStiffness * scale,
            )
        }

        /** 0 below [from], 1 past [to], smoothly in between. */
        fun fade(from: Float, to: Float, x: Float): Float {
            val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
