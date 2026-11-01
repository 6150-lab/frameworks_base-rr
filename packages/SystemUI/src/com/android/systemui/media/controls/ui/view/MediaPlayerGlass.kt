/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.controls.ui.view

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.internal.util.catalyxx.GlassLook
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.glass.ui.OwnGlass
import com.android.systemui.glass.ui.OwnGlassViewRim
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.google.ux.material.libmonet.hct.Hct
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Catalyxx glass on a media player's card, which its [albumView] draws, wherever notifications
 * have glass; GlassSettings.MUSIC_PLAYER picks how it looks. On the lock screen the card becomes
 * glass SurfaceFlinger draws over the wallpaper, in the album art's color or clear. In the shade
 * it's see-through over the shade's own blur, with the rim drawn here. With the album art kept,
 * the card stays as Android draws it and gets the rim on top.
 *
 * The player's text is light on every card, so the glass under it stays dark.
 */
@MainThread
class MediaPlayerGlass(private val albumView: ImageView) {

    private val cornerRadius =
        albumView.resources.getDimension(R.dimen.notification_corner_radius)
    private val rimRadii = FloatArray(8) { cornerRadius }
    private val rim = OwnGlassViewRim(albumView, GlassSettings.PLACE_NOTIFICATIONS)
    private val foreground = Foreground()

    // STYLE_STOCK, or the GlassSettings PLAYER_ value the card has now
    private var style = STYLE_STOCK
    private var glassVersion = -1
    private var attached = false
    private var artHidden = false
    private var hasCardColor = false
    // Whether the card's tint is glass's rather than Android's
    private var glassTint = false
    // Whether this draws the rim; it redraws the card whenever the light moves
    private var rimOn = false
    // The lock screen's media container, while the player is in it
    private var lockScreen: MediaContainerView? = null

    // Glass SurfaceFlinger draws for the card on the lock screen
    private var blur: BackgroundBlurDrawable? = null
    private var blurColor = Color.TRANSPARENT
    private var baseLook: GlassLook? = null
    private var blurObserver: ViewTreeObserver? = null
    private val visible = Rect()
    private val location = IntArray(2)
    private val containerLocation = IntArray(2)

    private val onGlassChange = Runnable {
        val version = OwnGlass.version
        if (version != glassVersion) {
            // Not when only the light moved
            glassVersion = version
            update()
        }
    }

    // SurfaceFlinger draws the glass at the card's own alpha and bounds, and never sees the
    // views above it fade or clip it, so before each frame it gets what's on screen.
    private val blurUpdater =
        ViewTreeObserver.OnPreDrawListener {
            updateBlur()
            true
        }

    /** The card's color as Android picks it from the album art; glass changes it as it needs. */
    @ColorInt
    var cardColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            hasCardColor = true
            applyColors()
        }

    init {
        albumView.foreground = foreground
        albumView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = onAttached()

                override fun onViewDetachedFromWindow(v: View) = onDetached()
            }
        )
        if (albumView.isAttachedToWindow) {
            onAttached()
        }
    }

    private fun onAttached() {
        attached = true
        OwnGlass.start(albumView.context)
        OwnGlass.addListener(onGlassChange)
        // The player moves between the lock screen, the shade and its other places by being
        // attached to each
        lockScreen = findLockScreen()
        glassVersion = OwnGlass.version
        update()
    }

    private fun onDetached() {
        attached = false
        OwnGlass.removeListener(onGlassChange)
        lockScreen = null
        // The blur belongs to the window it was made for
        dropBlur()
        setRimOn(false)
    }

    /** Applies the glass settings to the card, where the player is now. */
    private fun update() {
        style =
            if (attached && OwnGlass.isOn(GlassSettings.PLACE_NOTIFICATIONS)) {
                when (val player = OwnGlass.musicPlayer) {
                    GlassSettings.PLAYER_CLEAR,
                    GlassSettings.PLAYER_ALBUM_ART -> player
                    else -> GlassSettings.PLAYER_TINTED
                }
            } else {
                STYLE_STOCK
            }
        val glassCard =
            style == GlassSettings.PLAYER_TINTED || style == GlassSettings.PLAYER_CLEAR
        // A glass card leaves out the album art and its scrim. That takes a color filter: the
        // player crossfades art in a TransitionDrawable, which sets its layers' alpha itself.
        if (glassCard != artHidden) {
            artHidden = glassCard
            if (glassCard) albumView.colorFilter = HIDE_ART else albumView.clearColorFilter()
        }
        if (glassCard && lockScreen != null) {
            baseLook = OwnGlass.look(GlassSettings.PLACE_NOTIFICATIONS, Color.TRANSPARENT)
            makeBlur()
            blur?.setBlurRadius(
                GlassSettings.scaleBlurRadius(
                    albumView.resources.getDimensionPixelSize(
                        R.dimen.notification_background_blur_radius
                    ),
                    OwnGlass.blurScale(GlassSettings.PLACE_NOTIFICATIONS),
                )
            )
        } else {
            baseLook = null
            dropBlur()
        }
        // SurfaceFlinger draws the rim of its own glass
        setRimOn(style != STYLE_STOCK && blur == null)
        applyColors()
        albumView.invalidate()
    }

    private fun setRimOn(on: Boolean) {
        if (on != rimOn) {
            rimOn = on
            if (on) {
                rim.onAttachedToWindow()
                rim.onVisibilityAggregated(foreground.isVisible && albumView.isShown)
            } else {
                rim.onDetachedFromWindow()
            }
        }
    }

    /** Colors the card for its style, and SurfaceFlinger's glass on the lock screen. */
    private fun applyColors() {
        val b = blur
        if (b != null) {
            // SurfaceFlinger draws the glass and its color, under the rim's light; Flat glass is
            // plain blur, so there the card draws its color over it
            val look = baseLook
            b.setGlass(
                if (look != null && style == GlassSettings.PLAYER_TINTED && hasCardColor) {
                    tinted(look)
                } else {
                    look
                }
            )
            val color = if (look != null) Color.TRANSPARENT else glassFill()
            if (color != blurColor) {
                blurColor = color
                b.setColor(color)
                albumView.invalidate()
            }
            setCardTint(Color.TRANSPARENT)
            return
        }
        when (style) {
            GlassSettings.PLAYER_TINTED,
            GlassSettings.PLAYER_CLEAR -> setCardTint(glassFill())
            else ->
                if (hasCardColor) {
                    setCardTint(cardColor, glass = false)
                } else if (glassTint) {
                    // Android hasn't colored the card yet
                    albumView.backgroundTintList = null
                    glassTint = false
                }
        }
    }

    /** The card's color as see-through glass: a deep shade of the album art, or plain smoke. */
    @ColorInt
    private fun glassFill(): Int =
        if (style == GlassSettings.PLAYER_TINTED && hasCardColor) {
            ColorUtils.setAlphaComponent(artShade(TONE_OVER_DARK), FILL_ALPHA_TINTED)
        } else {
            ColorUtils.setAlphaComponent(Color.BLACK, FILL_ALPHA_CLEAR)
        }

    /** [look] in shades of the album art's color, darker over light content. */
    private fun tinted(look: GlassLook): GlassLook =
        GlassLook(
            look.rimWidth,
            look.bend,
            look.frost,
            look.rimLight,
            look.dispersion,
            look.shadow,
            ColorUtils.setAlphaComponent(artShade(TONE_OVER_DARK), TINT_ALPHA_OVER_DARK),
            ColorUtils.setAlphaComponent(
                artShade(TONE_OVER_LIGHT),
                max(Color.alpha(look.tintOverLight), TINT_ALPHA_OVER_LIGHT),
            ),
            look.adaptPoint,
        )

    /**
     * The album art's main color as a deep shade at [tone]. Android's card color for the art is
     * nearly black and keeps only a little of the art's chroma; this brings it back.
     */
    @ColorInt
    private fun artShade(tone: Double): Int {
        val hct = Hct.fromInt(cardColor)
        return Hct.from(hct.hue, (hct.chroma * CHROMA_LIFT).coerceAtMost(MAX_CHROMA), tone)
            .toInt()
    }

    private fun setCardTint(@ColorInt color: Int, glass: Boolean = true) {
        glassTint = glass
        if (albumView.backgroundTintList?.defaultColor != color) {
            albumView.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private fun makeBlur() {
        if (blur != null) {
            return
        }
        val root = albumView.viewRootImpl ?: return
        val b = root.createBackgroundBlurDrawable()
        b.setVisible(foreground.isVisible, false)
        b.callback = foreground
        blur = b
        blurColor = Color.TRANSPARENT
        blurObserver = albumView.viewTreeObserver.also { it.addOnPreDrawListener(blurUpdater) }
        updateBlur()
    }

    private fun dropBlur() {
        val b = blur ?: return
        blur = null
        // The window's blur only lets go of a hidden drawable; until then it keeps it, and the
        // tilt keeps lighting it
        b.setVisible(false, false)
        b.callback = null
        blurObserver?.let {
            if (it.isAlive) {
                it.removeOnPreDrawListener(blurUpdater)
            }
        }
        blurObserver = null
        albumView.invalidate()
    }

    /** Gives SurfaceFlinger the part of the card on screen now, at the alpha it has there. */
    private fun updateBlur() {
        val b = blur ?: return
        var alpha = 1f
        var view: View? = albumView
        while (view != null && alpha > 0f) {
            if (view.visibility != View.VISIBLE) {
                alpha = 0f
                break
            }
            alpha *= view.alpha * view.transitionAlpha
            view = view.parent as? View
        }
        if (alpha > 0f && !findVisibleCard()) {
            alpha = 0f
        }
        b.alpha = (alpha * OPAQUE).roundToInt()
        if (b.alpha == 0) {
            return
        }
        // A side something cuts off carries on under it: square there, without a rim
        var joined = 0
        if (visible.left > 0) joined = joined or SIDE_LEFT
        if (visible.top > 0) joined = joined or SIDE_TOP
        if (visible.right < albumView.width) joined = joined or SIDE_RIGHT
        if (visible.bottom < albumView.height) joined = joined or SIDE_BOTTOM
        fun corner(sides: Int) = if ((joined and sides) != 0) 0f else cornerRadius
        b.setCornerRadius(
            corner(SIDE_LEFT or SIDE_TOP),
            corner(SIDE_RIGHT or SIDE_TOP),
            corner(SIDE_LEFT or SIDE_BOTTOM),
            corner(SIDE_RIGHT or SIDE_BOTTOM),
        )
        b.setGlassJoinedSides(joined)
        b.bounds = visible
    }

    /**
     * Puts the part of the card on screen now into [visible], in the card's coordinates: inside
     * what the views above it clip, and above the bottom the lock screen's media container cuts
     * off as it shrinks. False when none of it is on screen.
     */
    private fun findVisibleCard(): Boolean {
        if (!albumView.getLocalVisibleRect(visible)) {
            return false
        }
        val container = lockScreen
        if (container != null) {
            albumView.getLocationInWindow(location)
            container.getLocationInWindow(containerLocation)
            val bottom = containerLocation[1] + container.clipHeight - location[1]
            if (visible.bottom > bottom) {
                visible.bottom = bottom
            }
        }
        return !visible.isEmpty
    }

    private fun findLockScreen(): MediaContainerView? {
        var parent = albumView.parent
        while (parent != null) {
            if (parent is MediaContainerView) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }

    /**
     * The album view's foreground: SurfaceFlinger's glass on the lock screen, or the rim on top
     * of the card and its art.
     */
    private inner class Foreground : Drawable(), Drawable.Callback {

        override fun draw(canvas: Canvas) {
            val b = blur
            if (b != null) {
                b.draw(canvas)
            } else if (rimOn) {
                val r = bounds
                rim.draw(
                    canvas,
                    r.left.toFloat(),
                    r.top.toFloat(),
                    r.right.toFloat(),
                    r.bottom.toFloat(),
                    rimRadii,
                    OPAQUE,
                )
            }
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            if (rimOn) {
                rim.onVisibilityAggregated(visible)
            }
            blur?.setVisible(visible, restart)
            return changed
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun invalidateDrawable(who: Drawable) = invalidateSelf()

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) =
            scheduleSelf(what, `when`)

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    private companion object {
        // Not glass: the card as Android draws it
        const val STYLE_STOCK = -1
        const val OPAQUE = 255

        val HIDE_ART = PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC)

        // The art's color on glass: the card color's chroma times about what Android's neutral
        // palette takes from the art, as a deep shade
        const val CHROMA_LIFT = 8.0
        const val MAX_CHROMA = 40.0
        const val TONE_OVER_DARK = 30.0
        const val TONE_OVER_LIGHT = 20.0

        // How much of it SurfaceFlinger's glass takes; more over light content, so the light
        // text reads
        const val TINT_ALPHA_OVER_DARK = 128
        const val TINT_ALPHA_OVER_LIGHT = 184

        // See-through cards in the shade, over its blur
        const val FILL_ALPHA_TINTED = 158
        const val FILL_ALPHA_CLEAR = 115

        const val SIDE_LEFT = BackgroundBlurDrawable.GLASS_SIDE_LEFT
        const val SIDE_TOP = BackgroundBlurDrawable.GLASS_SIDE_TOP
        const val SIDE_RIGHT = BackgroundBlurDrawable.GLASS_SIDE_RIGHT
        const val SIDE_BOTTOM = BackgroundBlurDrawable.GLASS_SIDE_BOTTOM
    }
}
