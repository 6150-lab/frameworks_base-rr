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

    /** RR volume panel looks, on rr_volume_style: 0 Android, 1 Neon, 2 Glass, 3 Pill, 4 Classic RR. */
    private fun applyRrStyle() {
        val style = android.provider.Settings.System.getIntForUser(
            context.contentResolver, "rr_volume_style", 0, android.os.UserHandle.USER_CURRENT
        )
        val bg = findViewById<View>(R.id.volume_dialog_background) ?: return
        if (style == 0) {
            bg.setBackgroundResource(R.drawable.volume_dialog_background)
            bg.elevation = 0f
            return
        }
        val density = context.resources.displayMetrics.density
        val accent = context.getColor(android.R.color.system_accent1_400)
        val shape = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 100 * density }
        bg.elevation = 0f
        when (style) {
            1 -> {
                shape.setColor(0xE6120A0C.toInt())
                shape.setStroke((1.5f * density).toInt(), accent)
                bg.outlineSpotShadowColor = accent
                bg.outlineAmbientShadowColor = accent
                bg.elevation = 10 * density
                bg.background = shape
            }
            2 -> {
                shape.setColor(0x33FFFFFF)
                shape.setStroke(density.toInt().coerceAtLeast(1), 0x44FFFFFF)
                bg.background = shape
                bg.post {
                    bg.viewRootImpl?.createBackgroundBlurDrawable()?.let { blur ->
                        blur.setBlurRadius((40 * density).toInt())
                        blur.setCornerRadius(100 * density)
                        blur.setColor(0x33FFFFFF)
                        bg.background = android.graphics.drawable.LayerDrawable(arrayOf(blur, shape))
                    }
                }
            }
            3 -> {
                shape.setColor(0xCC1A1416.toInt())
                bg.background = shape
            }
            4 -> {
                val card = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(0xF21D1517.toInt())
                }
                val bar = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(accent)
                }
                bg.background = android.graphics.drawable.LayerDrawable(arrayOf(bar, card)).apply {
                    setLayerInset(1, 0, (3 * density).toInt(), 0, 0)
                }
            }
        }
    }
}
