/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.dialog.ringer.ui.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.glass.ui.OwnGlassViewLens
import com.android.systemui.res.R

/**
 * The volume dialog's ringer drawer, with the glass selector's lens on the chosen ringer mode
 * where the volume dialog has glass. The lens magnifies the ringer buttons under it, not the
 * drawer's background: SurfaceFlinger draws that one's glass from where it's drawn.
 */
class VolumeRingerDrawerLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    MotionLayout(context, attrs, defStyleAttr) {

    /** The lens on the chosen ringer mode, which the drawer's binder moves. */
    val glassLens = OwnGlassViewLens(this, GlassSettings.PLACE_VOLUME)

    override fun dispatchDraw(canvas: Canvas) {
        val drawn =
            glassLens.draw(canvas, around = { super.dispatchDraw(it) }) { lensCanvas ->
                val time = drawingTime
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    if (child.id != R.id.ringer_buttons_background &&
                            child.visibility == View.VISIBLE) {
                        drawChild(lensCanvas, child, time)
                    }
                }
            }
        if (!drawn) {
            super.dispatchDraw(canvas)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glassLens.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glassLens.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        glassLens.onVisibilityAggregated(isVisible)
    }
}
