/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.internal.util.android.OmniJawsClient
import com.android.systemui.res.R
import com.android.systemui.rr.nowbar.NowBarItem
import com.android.systemui.rr.nowbar.NowBarRepository
import com.android.systemui.rr.nowbar.NowBarType
import com.android.systemui.weather.WeatherViewController
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * RR's Flatline under the time: a faint flat line with a scan head drifting across now and
 * then, a steady heartbeat while a song plays, a fast double beat while a call rings, and one
 * bright spike from left to right when a notification lands. The always-on display gets one
 * frozen beat, no glow and no motion.
 */
class FlatlineClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), NowBarRepository.Listener {

    private val density = resources.displayMetrics.density
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f * density
        letterSpacing = 0.12f
    }
    private val path = Path()
    /** A faint monitor grid behind the line, and whether the readout line is drawn. */
    private val grid: Boolean
    private val showReadout: Boolean
    private var color = Color.WHITE
    private var dozing = false
    private var playing = false
    private var calling = false
    private var readout: String? = null
    /** City and condition for the idle readout, null while clock face weather is off. */
    private var weather: String? = null
    private val weatherObserver = object : OmniJawsClient.OmniJawsObserver {
        override fun weatherUpdated() = updateWeather()
        override fun weatherError(errorReason: Int) = updateWeather()
    }
    /** Uptime when the last notification spike began, 0 for none. */
    private var spikeAt = 0L
    private var nextScan = 0L

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.FlatlineClockView)
        grid = a.getBoolean(R.styleable.FlatlineClockView_flatlineGrid, false)
        showReadout = a.getBoolean(R.styleable.FlatlineClockView_flatlineReadout, true)
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached.add(this)
        NowBarRepository.instance?.addListener(this)
        if (showReadout) {
            OmniJawsClient.get().addObserver(context, weatherObserver)
            updateWeather()
        }
        nextScan = now() + SCAN_EVERY
        invalidate()
    }

    override fun onDetachedFromWindow() {
        NowBarRepository.instance?.removeListener(this)
        if (showReadout) OmniJawsClient.get().removeObserver(context, weatherObserver)
        attached.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onNowBarChanged(items: List<NowBarItem>) {
        val song = items.firstOrNull { it.type == NowBarType.MUSIC && it.playing }
        val call = items.firstOrNull { it.type == NowBarType.CALL }
        playing = song != null
        calling = call != null
        readout = when {
            call != null -> call.title.toString()
            song != null -> listOfNotNull(song.title.takeIf { it.isNotEmpty() }, song.text)
                .joinToString(" · ")
            else -> null
        }
        invalidate()
    }

    /** The lock screen clock color, white on the always-on display. */
    fun setColor(color: Int) {
        if (this.color == color) return
        this.color = color
        invalidate()
    }

    fun setDozing(dozing: Boolean) {
        if (this.dozing == dozing) return
        this.dozing = dozing
        // Picks up the weather switches when the screen wakes.
        if (!dozing && showReadout && isAttachedToWindow) updateWeather()
        invalidate()
    }

    private fun updateWeather() {
        weather = WeatherViewController.customClockDetail(context)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = h * 0.52f
        val now = now()
        // Detached previews show a beat, so the picker's thumbnail looks alive.
        val preview = !isAttachedToWindow
        val live = !dozing && !preview
        val spike = live && spikeAt != 0L && now - spikeAt < SPIKE_MS
        val scanning = live && !playing && !calling && !spike && now >= nextScan &&
            now < nextScan + SCAN_MS
        val beating = preview || playing || calling

        if (grid && !dozing) {
            trace.strokeWidth = 1f
            trace.color = ColorUtils.setAlphaComponent(color, 0x16)
            val step = 24f * density
            var gx = step
            while (gx < w) {
                canvas.drawLine(gx, 0f, gx, h, trace)
                gx += step
            }
            for (f in GRID_ROWS) canvas.drawLine(0f, h * f, w, h * f, trace)
        }

        // The resting line.
        trace.strokeWidth = 1.2f * density
        trace.color = ColorUtils.setAlphaComponent(color, if (dozing) 0x66 else 0x3A)
        canvas.drawLine(0f, base, w, base, trace)

        path.rewind()
        val amp = h * 0.42f
        val period = (if (calling) 110f else 180f) * density
        // Scroll the beat left while it plays; frozen while dozing.
        val shift = if (live) (now % 100_000L) / 1000f * period * (if (calling) 1.6f else 0.9f)
            else 0f
        val spikeHead = w * (now - spikeAt) / SPIKE_MS.toFloat()
        // The trace's height at [x]: the beat scrolling by, the spike around its head, or flat.
        val yAt = { x: Float ->
            when {
                beating -> base - amp * flatlineBeat(((x + shift) % period) / period, calling)
                spike -> {
                    val d = (x - spikeHead) / (46f * density)
                    if (d > 0.3f || d < -1.6f) base else base - amp * flatlineBeat(0.56f + d * 0.22f, false)
                }
                else -> base
            }
        }
        var x = 0f
        while (x <= w) {
            val y = yAt(x)
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2f * density
        }
        if (dozing) {
            // One beat in the middle, still and unlit.
            path.rewind()
            path.moveTo(0f, base)
            x = w * 0.42f
            path.lineTo(x, base)
            val span = 46f * density
            while (x <= w * 0.42f + span) {
                val t = (x - w * 0.42f) / span
                path.lineTo(x, base - amp * flatlineBeat(0.42f + t * 0.3f, false))
                x += 2f * density
            }
            path.lineTo(w, base)
            trace.strokeWidth = 1.6f * density
            trace.color = ColorUtils.setAlphaComponent(Color.WHITE, 0xB3)
            canvas.drawPath(path, trace)
            return
        }

        // Glow from three strokes, wide and faint to thin and bright.
        for ((width, alpha) in GLOW) {
            trace.strokeWidth = width * density
            trace.color = ColorUtils.setAlphaComponent(color, alpha)
            canvas.drawPath(path, trace)
        }

        // The scan head: a bright dot riding the trace.
        val headX = when {
            spike -> spikeHead
            scanning -> w * (now - nextScan) / SCAN_MS.toFloat()
            beating -> w * 0.7f
            else -> -1f
        }
        if (headX >= 0f) {
            val headY = yAt(headX)
            fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 0x55)
            canvas.drawCircle(headX, headY, 7f * density, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(headX, headY, 2.4f * density, fill)
        }

        if (showReadout) drawReadout(canvas, w, h, preview)

        // Next frame: smooth while something moves, else sleep until the next scan.
        when {
            beating || spike || scanning -> postInvalidateDelayed(FRAME_MS)
            live -> {
                if (now >= nextScan + SCAN_MS) nextScan = now + SCAN_EVERY
                postInvalidateDelayed(max(16L, nextScan - now))
            }
        }
    }

    /** The readout line under the trace: what the line is doing, and the song or caller. */
    private fun drawReadout(canvas: Canvas, w: Float, h: Float, preview: Boolean) {
        text.color = ColorUtils.setAlphaComponent(color, 0x99)
        val ty = h - 3f * density
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(
            when {
                calling -> context.getString(R.string.flatline_call)
                playing -> context.getString(R.string.flatline_playing)
                else -> context.getString(R.string.flatline_idle)
            }, 2f * density, ty, text,
        )
        // Idle, the right side shows the weather.
        val right = readout
            ?: if (preview) context.getString(R.string.flatline_preview) else weather ?: ""
        if (right.isNotEmpty()) {
            text.textAlign = Paint.Align.RIGHT
            val fitted = android.text.TextUtils.ellipsize(right.uppercase(), android.text.TextPaint(text),
                w * 0.55f, android.text.TextUtils.TruncateAt.END)
            canvas.drawText(fitted.toString(), w - 2f * density, ty, text)
        }
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    companion object {
        private const val FRAME_MS = 40L
        private const val SPIKE_MS = 1100L
        private const val SCAN_MS = 1400L
        private const val SCAN_EVERY = 5000L
        private val GLOW = listOf(9f to 0x1E, 4f to 0x50, 1.8f to 0xFF)
        private val GRID_ROWS = floatArrayOf(0.25f, 0.5f, 0.75f)
        private val attached: MutableSet<FlatlineClockView> =
            Collections.newSetFromMap(WeakHashMap())

        /** A notification just arrived: every Flatline on screen sends a spike across. */
        @JvmStatic
        fun pulse() {
            val now = android.os.SystemClock.uptimeMillis()
            for (view in attached) {
                view.spikeAt = now
                view.invalidate()
            }
        }
    }
}
