/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.ArraySet
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.internal.util.catalyxx.GlassSettings
import com.android.internal.util.catalyxx.GlassTilt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/**
 * Glass SystemUI draws itself over the shade's blur, such as Quick Settings tiles and
 * notifications: the light on its rim ([GlassRimShader]) comes from the same settings and moves
 * in the same light ([GlassTilt]) as SurfaceFlinger's glass. This keeps both current for the
 * whole process. Compose reads them while drawing, so glass draws again when they change; views
 * listen with [addListener].
 *
 * Main thread only.
 */
@MainThread
object OwnGlass {

    // Size of the touch glow at GlassSettings.TOUCH_GLOW_SIZE 100, in dp
    private const val GLOW_RADIUS_DP = 48f

    private var started = false
    private lateinit var context: Context
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val listeners = ArraySet<Runnable>()

    // Compose state, so drawing that reads it is done again when it changes
    private var settings by mutableStateOf(Values.OFF)
    private var blurOn by mutableStateOf(false)
    private var lightNow by mutableStateOf(OwnGlassLight.USUAL)

    // The light GlassTilt last gave, waiting for the main thread
    private val pendingLight = AtomicLong()
    private val lightPosted = AtomicBoolean()

    /** Starts keeping the settings and the light current; once per process. */
    fun start(context: Context) {
        if (started) {
            return
        }
        started = true
        this.context = context.applicationContext ?: context
        val resolver = this.context.contentResolver
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    settings = readSettings()
                    changed()
                }
            }
        GlassSettings.KEYS.forEach {
            resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
        }
        settings = readSettings()

        // Glass is solid wherever the system's blur is off, such as in Battery Saver. The window
        // manager comes from the caller's context, a visual one.
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (windowManager != null) {
            blurOn = windowManager.isCrossWindowBlurEnabled
            windowManager.addCrossWindowBlurEnabledListener(this.context.mainExecutor) {
                if (it != blurOn) {
                    blurOn = it
                    changed()
                }
            }
        }

        val light = FloatArray(2)
        GlassTilt.getLight(light)
        lightNow = OwnGlassLight.of(light[0], light[1])
        GlassTilt.addLightListener { x, y ->
            pendingLight.set((x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and MASK))
            if (!lightPosted.getAndSet(true)) {
                handler.post {
                    lightPosted.set(false)
                    val packed = pendingLight.get()
                    lightNow =
                        OwnGlassLight.of(
                            Float.fromBits((packed ushr 32).toInt()),
                            Float.fromBits(packed.toInt()),
                        )
                    changed()
                }
            }
        }
    }

    /** The light now. */
    val light: OwnGlassLight
        get() = lightNow

    /**
     * How glass at [place], one of [GlassSettings]' place keys, draws its rim now, with sizes for
     * a display of [density]; null where it draws none: glass is off there or Flat, or the
     * system's blur is off.
     */
    fun rim(place: String, density: Float): OwnGlassRim? {
        val s = settings
        if (!blurOn || !s.hasRim(place)) {
            return null
        }
        val glowColor =
            when (s.glowColor) {
                GlassSettings.GLOW_ACCENT ->
                    context.getColor(android.R.color.system_accent1_200)
                else -> Color.WHITE
            }
        return OwnGlassRim(
            rimWidth =
                s.rimWidth.coerceIn(GlassSettings.MIN_RIM_WIDTH, GlassSettings.MAX_RIM_WIDTH) *
                    density,
            rimLight = fraction(s.rimLight),
            dispersion = fraction(s.rainbow),
            bevel = fraction(s.bend),
            touchGlow = s.touchGlow,
            glowColor = glowColor,
            glowRainbow = s.glowColor == GlassSettings.GLOW_RAINBOW,
            glowRadius =
                GLOW_RADIUS_DP *
                    s.glowSize.coerceIn(
                        GlassSettings.MIN_TOUCH_GLOW_SIZE,
                        GlassSettings.MAX_TOUCH_GLOW_SIZE,
                    ) / 100f * density,
        )
    }

    /**
     * The color glass at [place] fills itself with, where Android fills it with [androidColor]:
     * [androidColor] itself while glass is off there or the system's blur is off.
     */
    @ColorInt
    fun fill(place: String, @ColorInt androidColor: Int): Int {
        val s = settings
        if (!blurOn || !s.isOn(place)) {
            return androidColor
        }
        return GlassSettings.tintColor(context, s.color, s.tint, androidColor)
    }

    /** [listener] runs on the main thread whenever the settings, blur or light change. */
    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    /** Glass with a rim, [token], is on screen: the light follows the phone until [hide]. */
    fun show(token: Any) {
        GlassTilt.onOwnGlassShown(context, token)
    }

    fun hide(token: Any) {
        GlassTilt.onOwnGlassHidden(token)
    }

    private fun changed() {
        for (i in listeners.size - 1 downTo 0) {
            listeners.valueAt(i).run()
        }
    }

    private fun readSettings(): Values {
        val resolver = context.contentResolver
        fun get(key: String, default: Int) = Settings.Global.getInt(resolver, key, default)
        return Values(
            enabled = get(GlassSettings.ENABLED, 0) == 1,
            placesOff = GlassSettings.PLACES.filterTo(HashSet()) { get(it, 1) == 0 },
            style = get(GlassSettings.STYLE, GlassSettings.DEFAULT_STYLE),
            bend = get(GlassSettings.BEND, GlassSettings.DEFAULT_BEND),
            rimWidth = get(GlassSettings.RIM_WIDTH, GlassSettings.DEFAULT_RIM_WIDTH),
            rimLight = get(GlassSettings.RIM_LIGHT, GlassSettings.DEFAULT_RIM_LIGHT),
            rainbow = get(GlassSettings.RAINBOW, GlassSettings.DEFAULT_RAINBOW),
            color = get(GlassSettings.COLOR, GlassSettings.COLOR_FROSTED),
            tint = get(GlassSettings.TINT, GlassSettings.DEFAULT_TINT),
            touchGlow = get(GlassSettings.TOUCH_GLOW, 1) == 1,
            glowColor = get(GlassSettings.TOUCH_GLOW_COLOR, GlassSettings.GLOW_WHITE),
            glowSize = get(GlassSettings.TOUCH_GLOW_SIZE, GlassSettings.DEFAULT_TOUCH_GLOW_SIZE),
        )
    }

    private fun fraction(value: Int) =
        value.coerceIn(GlassSettings.MIN_PERCENT, GlassSettings.MAX_PERCENT) /
            GlassSettings.MAX_PERCENT.toFloat()

    private const val MASK = 0xffffffffL

    /** The glass settings SystemUI's own glass is drawn from, read together. */
    private data class Values(
        val enabled: Boolean,
        val placesOff: Set<String>,
        val style: Int,
        val bend: Int,
        val rimWidth: Int,
        val rimLight: Int,
        val rainbow: Int,
        val color: Int,
        val tint: Int,
        val touchGlow: Boolean,
        val glowColor: Int,
        val glowSize: Int,
    ) {
        fun isOn(place: String) = enabled && place !in placesOff

        fun hasRim(place: String) = isOn(place) && style != GlassSettings.STYLE_FLAT

        companion object {
            /** Until the settings are read: glass off, as it is by default. */
            val OFF =
                Values(
                    enabled = false,
                    placesOff = emptySet(),
                    style = GlassSettings.DEFAULT_STYLE,
                    bend = GlassSettings.DEFAULT_BEND,
                    rimWidth = GlassSettings.DEFAULT_RIM_WIDTH,
                    rimLight = GlassSettings.DEFAULT_RIM_LIGHT,
                    rainbow = GlassSettings.DEFAULT_RAINBOW,
                    color = GlassSettings.COLOR_FROSTED,
                    tint = GlassSettings.DEFAULT_TINT,
                    touchGlow = true,
                    glowColor = GlassSettings.GLOW_WHITE,
                    glowSize = GlassSettings.DEFAULT_TOUCH_GLOW_SIZE,
                )
        }
    }
}

/** How SystemUI's own glass draws its rim and touch glow, see [GlassRimShader]; in pixels. */
data class OwnGlassRim(
    val rimWidth: Float,
    val rimLight: Float,
    val dispersion: Float,
    val bevel: Float,
    val touchGlow: Boolean,
    @ColorInt val glowColor: Int,
    val glowRainbow: Boolean,
    val glowRadius: Float,
)

/** The light on glass: a unit vector toward it, ([x], [y]) in the screen's axes, y down. */
data class OwnGlassLight(val x: Float, val y: Float) {

    /** How far the light has turned from its usual place at the top left, in radians. */
    val turn: Float = wrap(atan2(y, x) - USUAL_ANGLE)

    companion object {
        /** The usual light, up and a little to the left, as SurfaceFlinger's glass has it. */
        val USUAL = OwnGlassLight(-0.447214f, -0.894427f)

        private val USUAL_ANGLE = atan2(-0.894427f, -0.447214f)

        /** The light [GlassTilt] gives: (0, 0) stands for the usual light. */
        fun of(x: Float, y: Float): OwnGlassLight =
            if (x == 0f && y == 0f) USUAL else OwnGlassLight(x, y)

        private fun wrap(angle: Float): Float =
            (angle - 2 * PI * floor((angle + PI) / (2 * PI))).toFloat()
    }
}
