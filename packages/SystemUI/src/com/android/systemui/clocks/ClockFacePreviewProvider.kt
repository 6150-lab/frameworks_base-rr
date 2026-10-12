/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.systemui.res.R
import com.android.systemui.weather.WeatherImageView
import com.android.systemui.weather.WeatherTextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Draws lock screen clock faces for Remixer's picker, so Settings doesn't carry the faces.
 *
 * call("render", null, extras) takes [EXTRA_STYLE], [EXTRA_WIDTH] (pixels of the result) and
 * [EXTRA_HEIGHT_FRACTION] (how much of the screen, from the top), plus any [ClockStyle] setting
 * name to override the current look. It returns [EXTRA_PNG]: the face on a transparent
 * background, placed where the lock screen puts it.
 */
class ClockFacePreviewProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_RENDER || extras == null) return null
        val task = FutureTask { render(extras) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            Handler(Looper.getMainLooper()).post(task)
        }
        val png = try {
            task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't draw face ${extras.getInt(EXTRA_STYLE)}", e)
            null
        } ?: return null
        return Bundle().apply { putByteArray(EXTRA_PNG, png) }
    }

    private fun render(extras: Bundle): ByteArray? {
        val context = ContextThemeWrapper(requireContext(), R.style.Theme_SystemUI)
        val bounds = context.getSystemService(WindowManager::class.java)!!
            .maximumWindowMetrics.bounds
        val width = bounds.width()
        val fraction = extras.getFloat(EXTRA_HEIGHT_FRACTION, 1f).coerceIn(0.1f, 1f)
        val height = (bounds.height() * fraction).toInt()
        val outWidth = extras.getInt(EXTRA_WIDTH, width).coerceIn(16, width)

        val root = FrameLayout(context)
        val clock = LayoutInflater.from(context)
            .inflate(R.layout.keyguard_clock_style, root, false) as ClockStyle
        clock.isPreview = true
        // Same spot as KeyguardClockStyleSection.
        val top = (context.resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f)
            .toInt()
        root.addView(clock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top })

        val resolver = context.contentResolver
        for (key in ClockStyle.TUNABLE_KEYS) {
            val value = if (key == ClockStyle.CLOCK_STYLE_KEY) {
                extras.getInt(EXTRA_STYLE).toString()
            } else {
                extras.getString(key)
                    ?: Settings.Secure.getStringForUser(resolver, key, UserHandle.USER_CURRENT)
            }
            clock.onTuningChanged(key, value)
        }

        val live = ArrayList<View>()
        collectLiveViews(clock, live)
        try {
            for (view in live) {
                when (view) {
                    is WeatherTextView -> view.preview()
                    is WeatherImageView -> view.preview()
                }
            }
            // Twice: the clock applies its size once it has been laid out.
            repeat(2) {
                root.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, width, height)
            }
            val scale = outWidth.toFloat() / width
            val bitmap = Bitmap.createBitmap(
                outWidth, (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            root.draw(canvas)
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        } finally {
            // Detached views never get onDetachedFromWindow, so stop what they started.
            for (view in live) {
                when (view) {
                    is WeatherTextView -> view.release()
                    is WeatherImageView -> view.release()
                    is AnalogClockView -> view.release()
                    is WordClockView -> view.release()
                }
            }
        }
    }

    private fun collectLiveViews(view: View, out: MutableList<View>) {
        if (view is WeatherTextView || view is WeatherImageView ||
                view is AnalogClockView || view is WordClockView) {
            out.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectLiveViews(view.getChildAt(i), out)
        }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?,
    ) = 0

    companion object {
        private const val TAG = "ClockFacePreview"
        private const val TIMEOUT_MS = 3000L

        const val METHOD_RENDER = "render"
        const val EXTRA_STYLE = "style"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT_FRACTION = "height_fraction"
        const val EXTRA_PNG = "png"
    }
}
