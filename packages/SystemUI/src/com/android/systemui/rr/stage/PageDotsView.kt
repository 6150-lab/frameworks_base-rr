/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.stage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.settingslib.Utils

/** One dot per Lock Stage page, the current one in the accent; shown briefly after a swipe. */
class PageDotsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    var pages = 1
    var current = 0

    init {
        alpha = 0f
    }

    fun show() {
        animate().cancel()
        alpha = 1f
        invalidate()
        animate().alpha(0f).setStartDelay(1000L).setDuration(400L).start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((pages * 14 * density).toInt(), (10 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val accent = Utils.getColorAccentDefaultColor(context)
        for (i in 0 until pages) {
            paint.color = if (i == current) accent else ColorUtils.setAlphaComponent(Color.WHITE, 0x66)
            canvas.drawCircle((7 + i * 14) * density, height / 2f, 3f * density, paint)
        }
    }
}
