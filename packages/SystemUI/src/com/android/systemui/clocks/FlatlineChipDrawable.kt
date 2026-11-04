/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.TextView
import com.android.systemui.rr.nowbar.NowBarItem
import com.android.systemui.rr.nowbar.NowBarRepository
import com.android.systemui.rr.nowbar.NowBarType
import java.util.Collections
import java.util.WeakHashMap

/**
 * The Flatline beside the status bar time, inside its chip: flat when idle, a heartbeat while
 * a song plays, a fast one while a call rings, one spike when a notification lands. Drawn in
 * the clock's own color, so it follows the status bar tint.
 */
class FlatlineChipDrawable(private val host: TextView) : Drawable(), NowBarRepository.Listener {
    private val density = host.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var playing = false
    private var calling = false
    private var spikeAt = 0L
    private val frame = Runnable { invalidateSelf() }

    fun attach() {
        attached.add(this)
        NowBarRepository.instance?.let {
            it.addListener(this)
            onNowBarChanged(it.items)
        }
    }

    fun detach() {
        attached.remove(this)
        NowBarRepository.instance?.removeListener(this)
        unscheduleSelf(frame)
    }

    override fun onNowBarChanged(items: List<NowBarItem>) {
        playing = items.any { it.type == NowBarType.MUSIC && it.playing }
        calling = items.any { it.type == NowBarType.CALL }
        invalidateSelf()
    }

    override fun getIntrinsicWidth() = (28 * density).toInt()

    override fun getIntrinsicHeight() = (14 * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val now = SystemClock.uptimeMillis()
        val spike = spikeAt != 0L && now - spikeAt < SPIKE_MS
        val base = b.exactCenterY()
        val amp = b.height() * 0.46f
        val period = (if (calling) 34f else 56f) * density
        // Half the clock face's pace: the chip is only half a beat wide, so it read as a race.
        val shift = (now % 100_000L) / 1000f * period * (if (calling) 0.8f else 0.45f)
        val head = b.left + b.width() * (now - spikeAt) / SPIKE_MS.toFloat()
        paint.color = host.currentTextColor
        path.rewind()
        val left = b.left.toFloat()
        var x = left
        while (x <= b.right) {
            val y = when {
                playing || calling ->
                    base - amp * flatlineBeat(((x - left + shift) % period) / period, calling)
                spike -> {
                    val d = (x - head) / (10f * density)
                    if (d > 0.3f || d < -1.6f) base
                    else base - amp * flatlineBeat(0.56f + d * 0.22f, false)
                }
                else -> base
            }
            if (x == left) path.moveTo(x, y) else path.lineTo(x, y)
            x += 1.5f * density
        }
        canvas.drawPath(path, paint)
        if (playing || calling || spike) scheduleSelf(frame, now + FRAME_MS)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val FRAME_MS = 50L
        private const val SPIKE_MS = 1100L
        private val attached: MutableSet<FlatlineChipDrawable> =
            Collections.newSetFromMap(WeakHashMap())

        /** A notification just arrived: every chip on screen sends a spike across. */
        @JvmStatic
        fun pulse() {
            val now = SystemClock.uptimeMillis()
            for (drawable in attached) {
                drawable.spikeAt = now
                drawable.invalidateSelf()
            }
        }
    }
}
