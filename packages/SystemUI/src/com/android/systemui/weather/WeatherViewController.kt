/*
 * Copyright (C) 2025 the AxionAOSP Project
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
package com.android.systemui.weather

import android.content.Context
import android.database.ContentObserver
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.android.internal.util.android.OmniJawsClient

import com.android.systemui.Dependency
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class WeatherViewController(
    private val context: Context,
    private val weatherIcon: ImageView,
    private val weatherTemp: TextView,
    private val weatherInfoView: View,
    private val isCustomClock: Boolean = false,
    /** Which part of the weather the text shows, one of the PART_ values. */
    private val part: Int = PART_FULL,
) : OmniJawsClient.OmniJawsObserver {

    private var weatherInfo: OmniJawsClient.WeatherInfo? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var mDozing = false
    private val statusBarStateController: StatusBarStateController = Dependency.get(StatusBarStateController::class.java)

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}

        override fun onDozingChanged(dozing: Boolean) {
            mDozing = dozing
            updateIconTint()
        }
    }

    private val weatherSettingsFlow = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(getWeatherSettings())
            }
        }
        listOf(LOCKSCREEN_WEATHER_ENABLED, LOCKSCREEN_WEATHER_LOCATION, LOCKSCREEN_WEATHER_TEXT,
            LOCKSCREEN_WEATHER_WIND_INFO, LOCKSCREEN_WEATHER_HUMIDITY_INFO)
            .map(Settings.System::getUriFor)
            .plus(Settings.Secure.getUriFor(CUSTOM_CLOCK_WEATHER))
            .forEach { resolver.registerContentObserver(it, false, observer, UserHandle.USER_ALL) }
        trySend(getWeatherSettings())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.stateIn(scope, SharingStarted.Eagerly, getWeatherSettings())

    fun init() {
        scope.launch {
            weatherSettingsFlow.collectLatest { applyWeatherSettings(it) }
        }
        statusBarStateController.addCallback(statusBarStateListener)
        statusBarStateListener.onDozingChanged(statusBarStateController.isDozing())
    }

    private fun getWeatherSettings() = WeatherSettings(
        weatherEnabled = getSystemSetting(LOCKSCREEN_WEATHER_ENABLED),
        showWeatherLocation = getSystemSetting(LOCKSCREEN_WEATHER_LOCATION),
        showWeatherText = getSystemSetting(LOCKSCREEN_WEATHER_TEXT, defaultValue = 1),
        showWindInfo = getSystemSetting(LOCKSCREEN_WEATHER_WIND_INFO),
        showHumidityInfo = getSystemSetting(LOCKSCREEN_WEATHER_HUMIDITY_INFO),
        customClockWeather = getSecureSetting(CUSTOM_CLOCK_WEATHER, defaultValue = 1),
    )

    private fun getSystemSetting(setting: String, defaultValue: Int = 0) =
        Settings.System.getIntForUser(context.contentResolver, setting, defaultValue, UserHandle.USER_CURRENT) != 0

    private fun getSecureSetting(setting: String, defaultValue: Int = 0) =
        Settings.Secure.getIntForUser(context.contentResolver, setting, defaultValue, UserHandle.USER_CURRENT) != 0

    private fun applyWeatherSettings(settings: WeatherSettings) {
        if (!settings.weatherEnabled) {
            hideAllViews()
            OmniJawsClient.get().removeObserver(context, this@WeatherViewController)
        } else {
            OmniJawsClient.get().addObserver(context, this@WeatherViewController)
            updateWeather()
            showAllViews()
        }
    }

    override fun weatherUpdated() = updateWeather()

    private fun updateIconTint() {
        if (mDozing) {
            val matrix = ColorMatrix()
            matrix.setSaturation(0f)
            weatherIcon.colorFilter = ColorMatrixColorFilter(matrix)
        } else {
            weatherIcon.colorFilter = null
        }
    }

    private fun updateWeather() {
        val settings = weatherSettingsFlow.value

        if (!settings.weatherEnabled) {
            hideAllViews()
            return
        }

        val shouldShow = if (isCustomClock) settings.customClockWeather
                         else !settings.customClockWeather
        if (!shouldShow) {
            hideAllViews()
            return
        }

        try {
            OmniJawsClient.get().queryWeather(context)
            weatherInfo = OmniJawsClient.get().weatherInfo
            weatherInfo?.let { info ->
                weatherIcon.setImageDrawable(
                    OmniJawsClient.get().getWeatherConditionImage(context, 
                    info.conditionCode))
                updateIconTint()
                weatherTemp.text = buildWeatherText(info)
                weatherTemp.isSelected = true
            }
        } catch (e: Exception) {}

        applyElementVisibility(settings)
    }

    private fun hideAllViews() {
        listOf(weatherInfoView, weatherIcon, weatherTemp).forEach {
            it.visibility = View.GONE
        }
    }

    private fun showAllViews() {
        scope.launch {
            listOf(weatherInfoView, weatherIcon, weatherTemp).forEach {
                it.visibility = View.VISIBLE
            }
            applyElementVisibility(weatherSettingsFlow.value)
        }
    }

    private fun applyElementVisibility(settings: WeatherSettings) {
        val show = settings.weatherEnabled && (
            if (isCustomClock) settings.customClockWeather
            else !settings.customClockWeather
        )
        weatherIcon.visibility = if (show) View.VISIBLE else View.GONE
        // A part can come out empty, like city and condition with both switched off.
        weatherTemp.visibility =
            if (show && !weatherTemp.text.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildWeatherText(info: OmniJawsClient.WeatherInfo): String {
        val settings = weatherSettingsFlow.value
        val conditionText = conditionText(context, info.condition)
        val temp = "${info.temp}${info.tempUnits}"
        val condition = conditionText.takeIf { settings.showWeatherText && it.isNotEmpty() }

        when (part) {
            PART_TEMP -> return temp
            PART_DEGREES -> return "${info.temp}°"
            PART_TEMP_CONDITION -> return listOfNotNull(temp, condition).joinToString(" · ")
            PART_DEGREES_CONDITION -> return listOfNotNull("${info.temp}°", condition).joinToString(" ")
            PART_DETAIL -> return detailText(info, conditionText, settings)
            PART_WORDS -> return context.getString(
                R.string.weather_temp_in_words, info.temp.toIntOrNull()?.let(::inWords) ?: info.temp,
            )
        }

        val locationText = if (settings.showWeatherLocation) " • ${info.city}" else ""
        val conditionDisplay = if (settings.showWeatherText) " • $conditionText" else ""
        val windDisplay = if (settings.showWindInfo) " • ${info.windSpeed} ${info.windUnits} ${info.pinWheel}" else ""
        val humidityDisplay = if (settings.showHumidityInfo) " • ${info.humidity}" else ""

        return "$temp$locationText$conditionDisplay$windDisplay$humidityDisplay"
    }

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            weatherInfo = null
            weatherIcon.setImageDrawable(null)
            weatherTemp.text = ""
            hideAllViews()
        }
    }

    /** Fills the views in once, for a detached preview. */
    fun preview() = updateWeather()

    fun removeObserver() {
        scope.cancel()
        OmniJawsClient.get().removeObserver(context, this)
        statusBarStateController.removeCallback(statusBarStateListener)
    }

    data class WeatherSettings(
        val weatherEnabled: Boolean,
        val showWeatherLocation: Boolean,
        val showWeatherText: Boolean,
        val showWindInfo: Boolean,
        val showHumidityInfo: Boolean,
        val customClockWeather: Boolean,
    )

    companion object {
        const val PART_FULL = 0
        const val PART_TEMP = 1
        const val PART_DEGREES = 2
        const val PART_TEMP_CONDITION = 3
        const val PART_DEGREES_CONDITION = 4
        const val PART_DETAIL = 5
        const val PART_WORDS = 6

        private val ONES = arrayOf(
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen",
        )
        private val TENS = arrayOf(
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety",
        )

        /** A temperature in words, the way the Text face spells the time. */
        fun inWords(n: Int): String = when {
            n < 0 -> "Minus " + inWords(-n)
            n < 20 -> ONES[n]
            n < 100 -> TENS[n / 10] + if (n % 10 != 0) "-" + ONES[n % 10] else ""
            else -> ONES[n / 100] + " Hundred" + if (n % 100 != 0) " " + inWords(n % 100) else ""
        }

        fun conditionText(context: Context, condition: String?): String {
            val lower = condition?.lowercase() ?: return ""
            val locale = context.resources.configuration.locales[0]
            if (!locale.language.startsWith("en", ignoreCase = true)) {
                for ((key, value) in WEATHER_CONDITIONS) {
                    if (lower.contains(key)) return context.resources.getString(value)
                }
            }
            return lower.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        }

        /** City and condition, each only when its switch is on. */
        private fun detailText(
            info: OmniJawsClient.WeatherInfo, conditionText: String, settings: WeatherSettings,
        ): String = listOfNotNull(
            info.city?.takeIf { settings.showWeatherLocation && it.isNotEmpty() },
            conditionText.takeIf { settings.showWeatherText && it.isNotEmpty() },
        ).joinToString(" · ")

        /**
         * City and condition for a face that draws its own weather, like Flatline's readout, or
         * null while clock face weather is off.
         */
        fun customClockDetail(context: Context): String? {
            fun system(key: String, def: Int = 0) = Settings.System.getIntForUser(
                context.contentResolver, key, def, UserHandle.USER_CURRENT) != 0
            val customClockWeather = Settings.Secure.getIntForUser(context.contentResolver,
                CUSTOM_CLOCK_WEATHER, 1, UserHandle.USER_CURRENT) != 0
            if (!system(LOCKSCREEN_WEATHER_ENABLED) || !customClockWeather) return null
            val client = OmniJawsClient.get()
            val info = try {
                client.queryWeather(context)
                client.weatherInfo
            } catch (e: Exception) {
                null
            } ?: return null
            val settings = WeatherSettings(
                weatherEnabled = true,
                showWeatherLocation = system(LOCKSCREEN_WEATHER_LOCATION),
                showWeatherText = system(LOCKSCREEN_WEATHER_TEXT, 1),
                showWindInfo = false,
                showHumidityInfo = false,
                customClockWeather = true,
            )
            return detailText(info, conditionText(context, info.condition), settings)
                .takeIf { it.isNotEmpty() }
        }

        private const val LOCKSCREEN_WEATHER_ENABLED = "lockscreen_weather_enabled"
        private const val LOCKSCREEN_WEATHER_LOCATION = "lockscreen_weather_location"
        private const val LOCKSCREEN_WEATHER_TEXT = "lockscreen_weather_text"
        private const val LOCKSCREEN_WEATHER_WIND_INFO = "lockscreen_weather_wind_info"
        private const val LOCKSCREEN_WEATHER_HUMIDITY_INFO = "lockscreen_weather_humidity_info"
        const val CUSTOM_CLOCK_WEATHER = "custom_clock_weather"

        private val WEATHER_CONDITIONS = mapOf(
            "clouds" to R.string.weather_condition_clouds,
            "rain" to R.string.weather_condition_rain,
            "clear" to R.string.weather_condition_clear,
            "storm" to R.string.weather_condition_storm,
            "snow" to R.string.weather_condition_snow,
            "wind" to R.string.weather_condition_wind,
            "mist" to R.string.weather_condition_mist
        )
    }
}
