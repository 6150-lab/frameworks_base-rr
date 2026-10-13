/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.policy

import android.content.Context
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.internal.util.android.OmniJawsClient
import com.android.systemui.Dependency
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R

/**
 * OmniJaws temperature and condition icon in the status bar. The layout has one next to the
 * clock (tag "start") and one before the system icons (tag "end"); the position setting picks
 * which one shows.
 */
class StatusBarWeatherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), OmniJawsClient.OmniJawsObserver,
    DarkIconDispatcher.DarkReceiver {

    private lateinit var icon: ImageView
    private lateinit var temp: TextView
    private var mode = MODE_OFF
    private var position = POSITION_START
    private var observing = false

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            readSettings()
            update()
        }
    }

    private val myPosition: Int
        get() = if (tag == "end") POSITION_END else POSITION_START

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = requireViewById(R.id.status_bar_weather_icon)
        temp = requireViewById(R.id.status_bar_weather_temp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        for (key in arrayOf(KEY_MODE, KEY_POSITION)) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, settingsObserver, UserHandle.USER_ALL
            )
        }
        Dependency.get(DarkIconDispatcher::class.java).addDarkReceiver(this)
        readSettings()
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.contentResolver.unregisterContentObserver(settingsObserver)
        Dependency.get(DarkIconDispatcher::class.java).removeDarkReceiver(this)
        setObserving(false)
    }

    private fun readSettings() {
        val resolver = context.contentResolver
        mode = Settings.System.getIntForUser(resolver, KEY_MODE, MODE_OFF, UserHandle.USER_CURRENT)
        position = Settings.System.getIntForUser(
            resolver, KEY_POSITION, POSITION_START, UserHandle.USER_CURRENT
        )
    }

    private fun setObserving(observe: Boolean) {
        if (observe == observing) return
        observing = observe
        if (observe) {
            OmniJawsClient.get().addObserver(context, this)
        } else {
            OmniJawsClient.get().removeObserver(context, this)
        }
    }

    private fun update() {
        val active = mode != MODE_OFF && position == myPosition
        setObserving(active)
        if (!active) {
            visibility = GONE
            return
        }
        val client = OmniJawsClient.get()
        val info = try {
            client.queryWeather(context)
            client.weatherInfo
        } catch (e: Exception) {
            null
        }
        if (info == null) {
            visibility = GONE
            return
        }
        temp.text = "${info.temp}${info.tempUnits}"
        temp.visibility = if (mode == MODE_ICON) GONE else VISIBLE
        icon.setImageDrawable(client.getWeatherConditionImage(context, info.conditionCode))
        icon.visibility = if (mode == MODE_TEMP) GONE else VISIBLE
        visibility = VISIBLE
    }

    override fun weatherUpdated() = update()

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            visibility = GONE
        }
    }

    override fun onDarkChanged(areas: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        temp.setTextColor(DarkIconDispatcher.getTint(areas, this, tint))
    }

    companion object {
        /** 0 off, 1 icon and temperature, 2 temperature only, 3 icon only. */
        const val KEY_MODE = "status_bar_weather"
        /** 0 next to the clock, 1 before the system icons. */
        const val KEY_POSITION = "status_bar_weather_position"

        private const val MODE_OFF = 0
        private const val MODE_TEMP = 2
        private const val MODE_ICON = 3
        private const val POSITION_START = 0
        private const val POSITION_END = 1
    }
}
