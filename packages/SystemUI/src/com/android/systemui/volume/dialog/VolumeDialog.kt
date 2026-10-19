/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.dialog

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.factory.VolumeDialogComponentFactory
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import lineageos.providers.LineageSettings

class VolumeDialog
@AssistedInject
constructor(
    @Application context: Context,
    private val componentFactory: VolumeDialogComponentFactory,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
    @Assisted private val isVolumeDialogVertical: Boolean,
) : ComponentDialog(context, R.style.Theme_SystemUI_Dialog_Volume) {

    @AssistedFactory
    interface Factory {
        fun create(isVolumeDialogVertical: Boolean): VolumeDialog
    }

    private var volumePanelOnLeft: Boolean = false

    private val volumePanelOnLeftObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val onLeft =
                    LineageSettings.Secure.getIntForUser(
                        context.contentResolver,
                        LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
                        0,
                        UserHandle.USER_CURRENT
                    ) != 0

                if (volumePanelOnLeft != onLeft) {
                    volumePanelOnLeft = onLeft
                    applyLayoutAndGravity()
                }
            }
        }

    private fun applyLayoutAndGravity() {
        val win = window ?: return
        val side = if (volumePanelOnLeft) Gravity.LEFT else Gravity.RIGHT
        val dialogView = win.decorView

        dialogView.layoutDirection =
            if (volumePanelOnLeft)
                View.LAYOUT_DIRECTION_RTL
            else
                View.LAYOUT_DIRECTION_LTR

        if (isVolumeDialogVertical) {
            win.setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            win.setGravity(side)
        } else {
            win.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            win.setGravity(Gravity.TOP or side)
        }
    }

    init {
        with(window!!) {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)

            attributes =
                attributes.apply {
                    title = "VolumeDialog" // Not the same as Window#setTitle
                }
        }
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isVolumeDialogVertical) {
            setContentView(R.layout.volume_dialog)
        } else {
            setContentView(R.layout.volume_dialog_horizontal)
        }
        requireViewById<View>(R.id.volume_dialog).repeatWhenAttached {
            coroutineScopeTraced("[Volume]dialog") {
                val component = componentFactory.create(this)
                with(component.volumeDialogViewBinder()) {
                    bind(this@VolumeDialog, isVolumeDialogVertical)
                }

                awaitCancellation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyRrStyle()
        context.contentResolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.VOLUME_PANEL_ON_LEFT),
            false,
            volumePanelOnLeftObserver,
            UserHandle.USER_ALL
        )
        volumePanelOnLeft = LineageSettings.Secure.getIntForUser(
            context.contentResolver, LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
            0, UserHandle.USER_CURRENT
        ) != 0
        applyLayoutAndGravity()
    }

    override fun onStop() {
        super.onStop()
        context.contentResolver.unregisterContentObserver(volumePanelOnLeftObserver)
    }

    /**
     * NOTE: This will be called with ACTION_OUTSIDE MotionEvents for touches that occur outside of
     * the touchable region of the volume dialog (as returned by [.onComputeInternalInsets]) even if
     * those touches occurred within the bounds of the volume dialog.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isShowing) {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                visibilityInteractor.dismissDialog(Events.DISMISS_REASON_TOUCH_OUTSIDE)
                return true
            }
        }
        return false
    }

    /**
     * RR volume panel looks, on rr_volume_style: 0 Android, 1 Neon, 2 Glass, 3 Pill, 4 Classic RR,
     * and the QS themes 5 Bento, 6 Shapes, 7 Blueprint, 8 Duo, 9 Soft.
     * The ringer binder owns the background drawables (it reshapes them and swaps in the blur
     * layers), so a look only tints them and draws its outline or bar on top.
     */
    private fun applyRrStyle() {
        val style = android.provider.Settings.System.getIntForUser(
            context.contentResolver, "rr_volume_style", 0, android.os.UserHandle.USER_CURRENT
        )
        val root = findViewById<View>(R.id.volume_dialog) ?: return
        val bg = findViewById<View>(R.id.volume_dialog_background) ?: return
        val ringer = findViewById<View>(R.id.ringer_buttons_background)
        panelOutline?.let {
            root.overlay.remove(it)
            root.viewTreeObserver.removeOnPreDrawListener(it)
        }
        panelOutline = null
        bg.backgroundTintList = null
        ringer?.backgroundTintList = null
        bg.foreground = null
        if (style == 0) return
        val density = context.resources.displayMetrics.density
        val accent = context.getColor(android.R.color.system_accent1_400)
        fun tint(color: Int, ringerColor: Int = color) {
            for (view in listOfNotNull(bg, ringer)) {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (view === ringer) ringerColor else color
                )
                view.backgroundTintMode = android.graphics.PorterDuff.Mode.SRC_IN
            }
        }
        fun outline(color: Int, widthPx: Float, glow: Boolean) {
            panelOutline = RrPanelOutline(root, bg, ringer, color, widthPx, withGlow = glow).also {
                root.overlay.add(it)
                root.viewTreeObserver.addOnPreDrawListener(it)
            }
        }
        when (style) {
            1 -> {
                tint(0xE6120A0C.toInt())
                outline(accent, 1.5f * density, glow = true)
            }
            2 -> {
                tint(0x40FFFFFF)
                outline(0x44FFFFFF, density, glow = false)
            }
            3 -> tint(0xCC1A1416.toInt())
            4 -> {
                tint(0xF21D1517.toInt())
                bg.foreground = RrPanelBar(bg, accent, 3 * density)
            }
            5 -> tint(0xF2181211.toInt())
            6 -> tint(0xF21E1715.toInt())
            7 -> {
                tint(0xF20C0B0B.toInt())
                outline(0x2EFFFFFF, density, glow = false)
            }
            // Duo: the ringer and the sliders are two segments in two shades.
            8 -> tint(0xF2181211.toInt(), ringerColor = 0xF22A2120.toInt())
            9 -> {
                tint(0xFF1C1716.toInt())
                outline(0x14FFFFFF, density, glow = false)
            }
        }
    }

    private var panelOutline: RrPanelOutline? = null

    /**
     * An outline around the panel and the ringer above it, drawn over the dialog so nothing in it
     * hides the line. Neon's glow is a blurred stroke; an elevation shadow would lift the panel
     * over the sliders.
     */
    private class RrPanelOutline(
        private val root: View,
        private val panel: View,
        private val ringer: View?,
        color: Int,
        widthPx: Float,
        withGlow: Boolean,
    ) : android.graphics.drawable.Drawable(), android.view.ViewTreeObserver.OnPreDrawListener {
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = widthPx
        }
        private val glow = if (!withGlow) null else android.graphics.Paint(line).apply {
            strokeWidth = widthPx * 3
            alpha = 150
            maskFilter = android.graphics.BlurMaskFilter(
                widthPx * 5, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        private val path = android.graphics.Path()
        private val part = android.graphics.Path()
        private val rect = android.graphics.RectF()
        private val origin = IntArray(2)
        private val at = IntArray(2)
        private var shape = FloatArray(0)

        // The ringer drawer animates both shapes, so rebuild whenever either changes.
        override fun onPreDraw(): Boolean {
            val next = FloatArray(SHAPE_SIZE * 2)
            val hasPanel = measure(panel, next, 0)
            if (ringer != null && ringer.isShown) measure(ringer, next, SHAPE_SIZE)
            if (!next.contentEquals(shape)) {
                shape = next
                path.reset()
                if (hasPanel) {
                    addShape(path, 0)
                    if (next[SHAPE_SIZE + 2] > 0f) {
                        part.reset()
                        addShape(part, SHAPE_SIZE)
                        path.op(part, android.graphics.Path.Op.UNION)
                    }
                }
                setBounds(0, 0, root.width, root.height)
                invalidateSelf()
            }
            return true
        }

        /** Writes [view]'s bounds in the dialog and its corner radii into [out] at [offset]. */
        private fun measure(view: View, out: FloatArray, offset: Int): Boolean {
            if (view.width == 0 || view.height == 0) return false
            root.getLocationInWindow(origin)
            view.getLocationInWindow(at)
            out[offset] = (at[0] - origin[0]).toFloat()
            out[offset + 1] = (at[1] - origin[1]).toFloat()
            out[offset + 2] = view.width.toFloat()
            out[offset + 3] = view.height.toFloat()
            radii(view)?.copyInto(out, offset + 4)
            return true
        }

        private fun addShape(target: android.graphics.Path, offset: Int) {
            val left = shape[offset]
            val top = shape[offset + 1]
            rect.set(left, top, left + shape[offset + 2], top + shape[offset + 3])
            target.addRoundRect(
                rect, shape.copyOfRange(offset + 4, offset + SHAPE_SIZE),
                android.graphics.Path.Direction.CW
            )
        }

        override fun draw(canvas: android.graphics.Canvas) {
            glow?.let {
                // Only outward, so it doesn't tint the sliders.
                canvas.save()
                canvas.clipOutPath(path)
                canvas.drawPath(path, it)
                canvas.restore()
            }
            canvas.drawPath(path, line)
        }

        override fun setAlpha(alpha: Int) {
            line.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            line.colorFilter = colorFilter
            glow?.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

        private companion object {
            // Left, top, width, height, then eight corner radii.
            const val SHAPE_SIZE = 12
        }
    }

    /** A bar along the top that follows the panel's current corners. */
    private class RrPanelBar(
        private val view: View,
        color: Int,
        private val heightPx: Float,
    ) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()

        override fun draw(canvas: android.graphics.Canvas) {
            val radii = radii(view) ?: return
            rect.set(bounds)
            path.reset()
            path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + heightPx, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/** The corner radii of [view]'s rounded background, the one the ringer binder reshapes. */
private fun radii(view: View): FloatArray? {
    val shape = when (val background = view.background) {
        is android.graphics.drawable.GradientDrawable -> background
        is android.graphics.drawable.LayerDrawable ->
            (0 until background.numberOfLayers)
                .map { background.getDrawable(it) }
                .filterIsInstance<android.graphics.drawable.GradientDrawable>()
                .lastOrNull()
        else -> null
    } ?: return null
    return shape.cornerRadii ?: FloatArray(8) { shape.cornerRadius }
}
