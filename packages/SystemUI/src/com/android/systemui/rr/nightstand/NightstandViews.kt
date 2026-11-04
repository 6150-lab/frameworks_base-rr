/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nightstand

import android.app.AlarmManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.NextAlarmController
import java.util.Calendar
import kotlin.math.min

/** Shared looks for the Nightstand complications: neon ring in the accent, a faint glass slab. */
internal object NightstandLook {
    fun accent(context: Context) = context.getColor(android.R.color.system_accent1_300)

    fun slab(context: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 20f * context.resources.displayMetrics.density
        setColor(0x1AFFFFFF)
        setStroke((context.resources.displayMetrics.density).toInt(), 0x2AFFFFFF)
    }
}

/** A neon ring that fills with the battery level, the percentage inside, a bolt while charging. */
class BatteryRingView(context: Context, private val batteryController: BatteryController) :
    View(context), BatteryController.BatteryStateChangeCallback {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val bolt = Path()
    private val bounds = RectF()
    private var level = 100
    private var charging = false
    var dozing = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        batteryController.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        batteryController.removeCallback(this)
        super.onDetachedFromWindow()
    }

    override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
        this.level = level
        this.charging = charging
        contentDescription = context.getString(R.string.stage_battery_description, level)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = (size * 0.075f).coerceIn(3f * density, 8f * density)
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - stroke
        bounds.set(cx - r, cy - r, cx + r, cy + r)
        val color = if (dozing) Color.WHITE else NightstandLook.accent(context)
        ring.strokeWidth = stroke
        ring.color = ColorUtils.setAlphaComponent(color, if (dozing) 0x40 else 0x2E)
        canvas.drawArc(bounds, 0f, 360f, false, ring)
        if (!dozing) {
            // The glow under the lit part.
            ring.strokeWidth = stroke * 2.2f
            ring.color = ColorUtils.setAlphaComponent(color, 0x22)
            canvas.drawArc(bounds, -90f, 360f * level / 100f, false, ring)
        }
        ring.strokeWidth = stroke
        ring.color = color
        canvas.drawArc(bounds, -90f, 360f * level / 100f, false, ring)
        text.color = Color.WHITE
        text.textSize = size * 0.22f
        val baseline = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText("$level%", cx, baseline, text)
        if (charging) {
            val s = size * 0.11f
            bolt.rewind()
            bolt.moveTo(cx + s * 0.1f, cy + r * 0.42f)
            bolt.lineTo(cx - s * 0.5f, cy + r * 0.42f + s * 1.4f)
            bolt.lineTo(cx, cy + r * 0.42f + s * 1.4f)
            bolt.lineTo(cx - s * 0.1f, cy + r * 0.42f + s * 2.6f)
            bolt.lineTo(cx + s * 0.5f, cy + r * 0.42f + s * 1.1f)
            bolt.lineTo(cx, cy + r * 0.42f + s * 1.1f)
            bolt.close()
            text.color = color
            canvas.drawPath(bolt, text)
        }
    }
}

/** The next alarm, as "Alarm 6:30" with its icon, or nothing when none is set. */
class NextAlarmView(context: Context, private val nextAlarmController: NextAlarmController) :
    LinearLayout(context), NextAlarmController.NextAlarmChangeCallback {
    private val icon = ImageView(context)
    private val label = android.widget.TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = NightstandLook.slab(context)
        val pad = (10 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        icon.setImageResource(R.drawable.ic_alarm)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(NightstandLook.accent(context))
        val iconSize = (18 * resources.displayMetrics.density).toInt()
        addView(icon, LayoutParams(iconSize, iconSize).apply { marginEnd = pad / 2 })
        label.setTextColor(Color.WHITE)
        label.textSize = 14f
        label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        label.isSingleLine = true
        addView(label)
    }

    var dozing = false
        set(value) {
            field = value
            background = if (value) null else NightstandLook.slab(context)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (value) Color.WHITE else NightstandLook.accent(context))
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        nextAlarmController.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        nextAlarmController.removeCallback(this)
        super.onDetachedFromWindow()
    }

    override fun onNextAlarmChanged(nextAlarm: AlarmManager.AlarmClockInfo?) {
        if (nextAlarm == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val calendar = Calendar.getInstance().apply { timeInMillis = nextAlarm.triggerTime }
        val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
        label.text = DateFormat.format(pattern, calendar)
        contentDescription = context.getString(R.string.stage_alarm_description, label.text)
    }
}
