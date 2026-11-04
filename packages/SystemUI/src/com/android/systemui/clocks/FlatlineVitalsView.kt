/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.AttributeSet
import android.widget.TextView
import com.android.systemui.res.R
import java.util.Date
import kotlin.math.max

/** The Flatline Vitals readout: battery, a bolt while charging, and the next alarm. */
class FlatlineVitalsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    private var battery: Intent? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery = intent
            update()
        }
    }

    init {
        // Detached previews in the face picker show a sample.
        text = context.getString(R.string.flatline_vitals_preview).uppercase()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        battery = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        update()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(receiver)
        super.onDetachedFromWindow()
    }

    private fun update() {
        val parts = ArrayList<String>(2)
        battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                max(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0) parts += if (plugged) "$level%↯" else "$level%"
        }
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let {
            parts += context.getString(
                R.string.flatline_alarm,
                DateFormat.getTimeFormat(context).format(Date(it.triggerTime)),
            )
        }
        text = parts.joinToString(" · ").uppercase()
    }
}
