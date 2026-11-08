/*
 * Copyright (C) 2023-2024 risingOS Android Project
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
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.android.systemui.res.R

class WeatherTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
    isCustomClock: Boolean = true,
) : TextView(context, attrs, defStyle) {

    private val mWeatherViewController: WeatherViewController

    /** A view that shows and hides with the weather, like a separator before it. */
    private val linkedViewId: Int
    private var linkedView: View? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.WeatherTextView)
        val part = a.getInt(R.styleable.WeatherTextView_weatherText, WeatherViewController.PART_FULL)
        linkedViewId = a.getResourceId(R.styleable.WeatherTextView_weatherLinkedView, View.NO_ID)
        a.recycle()

        visibility = View.GONE

        val stubIcon = android.widget.ImageView(context)

        mWeatherViewController = WeatherViewController(
            context = context,
            weatherIcon = stubIcon,
            weatherTemp = this,
            weatherInfoView = this,
            isCustomClock = isCustomClock,
            part = part,
        )
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        linked()?.visibility = visibility
    }

    /** The linked view, looked up in the face; the picker's previews are never attached. */
    private fun linked(): View? {
        // TextView sets its visibility before this class has read its attributes.
        if (linkedViewId <= 0) return null
        linkedView?.let { return it }
        var root: View = this
        while (true) root = root.parent as? View ?: break
        return root.findViewById<View?>(linkedViewId)?.also { linkedView = it }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        linked()?.visibility = visibility
        mWeatherViewController.init()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mWeatherViewController.removeObserver()
    }

    /** Shows the current weather in a detached preview; [release] it when done. */
    fun preview() = mWeatherViewController.preview()

    fun release() = mWeatherViewController.removeObserver()
}
