/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.stage

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.NextAlarmController
import java.util.Calendar
import kotlin.math.min

/** Shared looks for the stage complications: neon ring in the accent, a faint glass slab. */
internal object StageLook {
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
        val color = if (dozing) Color.WHITE else StageLook.accent(context)
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
        background = StageLook.slab(context)
        val pad = (10 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        icon.setImageResource(R.drawable.ic_alarm)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(StageLook.accent(context))
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
            background = if (value) null else StageLook.slab(context)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (value) Color.WHITE else StageLook.accent(context))
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

/** A row of round neon buttons: flashlight, camera and the QR scanner. */
class TogglesView(
    context: Context,
    private val flashlightController: FlashlightController,
    private val activityStarter: ActivityStarter,
) : LinearLayout(context), FlashlightController.FlashlightListener {
    private val density = resources.displayMetrics.density
    private val flashlight = button(R.drawable.rr_ql_flashlight, R.string.stage_flashlight)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        flashlight.setOnClickListener {
            flashlightController.setFlashlight(!flashlightController.isEnabled)
        }
        addView(flashlight, buttonParams())
        val camera = button(R.drawable.rr_ql_camera, R.string.stage_camera)
        camera.setOnClickListener {
            activityStarter.postStartActivityDismissingKeyguard(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0)
        }
        addView(camera, buttonParams())
        val component = context.getString(
            com.android.internal.R.string.config_defaultQrCodeComponent)
        if (component.isNotEmpty()) {
            val qr = button(R.drawable.rr_ql_qr, R.string.stage_qr)
            qr.setOnClickListener {
                val intent = Intent().setComponent(ComponentName.unflattenFromString(component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activityStarter.postStartActivityDismissingKeyguard(intent, 0)
            }
            addView(qr, buttonParams())
        }
    }

    private fun button(icon: Int, description: Int): ImageView = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x1AFFFFFF)
            setStroke(density.toInt(), StageLook.accent(context))
        }
        contentDescription = context.getString(description)
        isClickable = true
        isFocusable = true
    }

    private fun buttonParams() = LayoutParams((48 * density).toInt(), (48 * density).toInt())
        .apply { marginStart = (8 * density).toInt(); marginEnd = (8 * density).toInt() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        flashlightController.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        flashlightController.removeCallback(this)
        super.onDetachedFromWindow()
    }

    override fun onFlashlightChanged(enabled: Boolean) {
        post {
            (flashlight.background as? GradientDrawable)
                ?.setColor(if (enabled) StageLook.accent(context) else 0x1AFFFFFF)
            flashlight.imageTintList = android.content.res.ColorStateList.valueOf(
                if (enabled) Color.BLACK else Color.WHITE)
        }
    }

    override fun onFlashlightError() {}

    override fun onFlashlightAvailabilityChanged(available: Boolean) {
        post { flashlight.visibility = if (available) VISIBLE else GONE }
    }
}
