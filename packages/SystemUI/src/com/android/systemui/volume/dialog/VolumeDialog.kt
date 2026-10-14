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
     * RR volume panel looks, on rr_volume_style: 0 Android, 1 Neon, 2 Glass, 3 Pill, 4 Classic RR.
     * The ringer binder owns the background drawable (it reshapes it and swaps in the blur layers),
     * so a look only tints it and draws its outline or bar on top.
     */
    private fun applyRrStyle() {
        val style = android.provider.Settings.System.getIntForUser(
            context.contentResolver, "rr_volume_style", 0, android.os.UserHandle.USER_CURRENT
        )
        val bg = findViewById<View>(R.id.volume_dialog_background) ?: return
        bg.backgroundTintList = null
        bg.foreground = null
        bg.elevation = 0f
        bg.outlineSpotShadowColor = android.graphics.Color.BLACK
        bg.outlineAmbientShadowColor = android.graphics.Color.BLACK
        if (style == 0) return
        val density = context.resources.displayMetrics.density
        val accent = context.getColor(android.R.color.system_accent1_400)
        fun tint(color: Int) {
            bg.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            bg.backgroundTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        }
        when (style) {
            1 -> {
                tint(0xE6120A0C.toInt())
                bg.foreground = RrPanelForeground(bg, accent, 1.5f * density, bar = false)
                bg.outlineSpotShadowColor = accent
                bg.outlineAmbientShadowColor = accent
                bg.elevation = 10 * density
            }
            2 -> {
                tint(0x40FFFFFF)
                bg.foreground = RrPanelForeground(bg, 0x44FFFFFF, density, bar = false)
            }
            3 -> tint(0xCC1A1416.toInt())
            4 -> {
                tint(0xF21D1517.toInt())
                bg.foreground = RrPanelForeground(bg, accent, 3 * density, bar = true)
            }
        }
    }

    /** An outline, or a bar along the top, that follows the panel's current corners. */
    private class RrPanelForeground(
        private val view: View,
        color: Int,
        private val widthPx: Float,
        private val bar: Boolean,
    ) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (bar) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
            strokeWidth = widthPx
        }
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()

        override fun draw(canvas: android.graphics.Canvas) {
            val radii = radii() ?: return
            rect.set(bounds)
            if (!bar) rect.inset(widthPx / 2, widthPx / 2)
            path.reset()
            path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
            if (bar) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + widthPx, paint)
                canvas.restore()
            } else {
                canvas.drawPath(path, paint)
            }
        }

        private fun radii(): FloatArray? {
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
