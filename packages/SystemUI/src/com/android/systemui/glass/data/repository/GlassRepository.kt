/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.data.repository

import android.content.Context
import androidx.annotation.ColorInt
import com.android.internal.util.catalyxx.GlassLook
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Catalyxx's system glass settings ([GlassSettings]), kept current for the places that stay on
 * screen while they change: the shade and the lock screen. Places that open and close (the
 * volume dialog, power menu and dialogs) read [GlassSettings] each time they open instead.
 */
@SysUISingleton
class GlassRepository
@Inject
constructor(
    private val settings: SystemSettings,
    @Application applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
) {
    private val state: StateFlow<GlassState> =
        settings
            .observerFlow(*GlassSettings.KEYS)
            .emitOnStart()
            .map { readState() }
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, GlassState.OFF)

    /** Whether glass is on for [place], one of the [GlassSettings] place keys. */
    fun isOn(place: String): Flow<Boolean> = state.map { it.isOn(place) }.distinctUntilChanged()

    /** What glass multiplies [place]'s blur radius by: 1 while glass is off there. */
    fun blurScale(place: String): Flow<Float> =
        state.map { it.blurScale(place) }.distinctUntilChanged()

    /** [blurScale] right now, for code that applies blur every frame. */
    fun currentBlurScale(place: String): Float = state.value.blurScale(place)

    /** Glass's color and tint for [place], or null while glass is off there. */
    fun tint(place: String): Flow<GlassTint?> =
        state.map { it.tint(place) }.distinctUntilChanged()

    /** How glass's curved rim is drawn at [place], or null while glass is off there. */
    fun rim(place: String): Flow<GlassRim?> = state.map { it.rim(place) }.distinctUntilChanged()

    /** [rim] right now. */
    fun currentRim(place: String): GlassRim? = state.value.rim(place)

    private fun readState(): GlassState {
        // RR: the material says whether glass is on, its style and the color it starts with
        val material = settings.getInt(GlassSettings.MATERIAL, GlassSettings.DEFAULT_MATERIAL)
        val tint =
            GlassTint(
                color =
                    GlassSettings.color(
                        material,
                        settings.getInt(GlassSettings.COLOR, GlassSettings.COLOR_UNSET),
                    ),
                tint = settings.getInt(GlassSettings.TINT, GlassSettings.DEFAULT_TINT),
            )
        return GlassState(
            enabled = GlassSettings.isEnabled(material),
            strength =
                settings.getInt(
                    GlassSettings.BLUR_STRENGTH,
                    GlassSettings.DEFAULT_BLUR_STRENGTH,
                ),
            tint = tint,
            rim =
                GlassRim(
                    style =
                        GlassSettings.style(
                            material,
                            settings.getInt(GlassSettings.STYLE, GlassSettings.DEFAULT_STYLE),
                        ),
                    bend = settings.getInt(GlassSettings.BEND, GlassSettings.DEFAULT_BEND),
                    width =
                        settings.getInt(
                            GlassSettings.RIM_WIDTH,
                            GlassSettings.DEFAULT_RIM_WIDTH,
                        ),
                    light =
                        settings.getInt(
                            GlassSettings.RIM_LIGHT,
                            GlassSettings.DEFAULT_RIM_LIGHT,
                        ),
                    rainbow =
                        settings.getInt(GlassSettings.RAINBOW, GlassSettings.DEFAULT_RAINBOW),
                    shadow =
                        settings.getInt(GlassSettings.SHADOW, GlassSettings.DEFAULT_SHADOW),
                    tint = tint,
                    adaptive = settings.getInt(GlassSettings.ADAPTIVE, 1),
                    smoke = settings.getInt(GlassSettings.SMOKE, GlassSettings.DEFAULT_SMOKE),
                    smokeColor =
                        settings.getInt(GlassSettings.SMOKE_COLOR, GlassSettings.SMOKE_BLACK),
                    switchPoint =
                        settings.getInt(
                            GlassSettings.SWITCH_POINT,
                            GlassSettings.DEFAULT_SWITCH_POINT,
                        ),
                ),
            placesOff =
                GlassSettings.PLACES.filterTo(HashSet()) { settings.getInt(it, 1) == 0 },
        )
    }

    private data class GlassState(
        val enabled: Boolean,
        val strength: Int,
        val tint: GlassTint,
        val rim: GlassRim,
        val placesOff: Set<String>,
    ) {
        fun isOn(place: String) = enabled && place !in placesOff

        fun blurScale(place: String) = if (isOn(place)) GlassSettings.blurScale(strength) else 1f

        fun tint(place: String) = if (isOn(place)) tint else null

        fun rim(place: String) = if (isOn(place)) rim else null

        companion object {
            private val DEFAULT_TINT =
                GlassTint(GlassSettings.COLOR_FROSTED, GlassSettings.DEFAULT_TINT)

            /** Until the settings are read: glass off, as it is by default. */
            val OFF =
                GlassState(
                    enabled = false,
                    strength = GlassSettings.DEFAULT_BLUR_STRENGTH,
                    tint = DEFAULT_TINT,
                    rim =
                        GlassRim(
                            style = GlassSettings.DEFAULT_STYLE,
                            bend = GlassSettings.DEFAULT_BEND,
                            width = GlassSettings.DEFAULT_RIM_WIDTH,
                            light = GlassSettings.DEFAULT_RIM_LIGHT,
                            rainbow = GlassSettings.DEFAULT_RAINBOW,
                            shadow = GlassSettings.DEFAULT_SHADOW,
                            tint = DEFAULT_TINT,
                            adaptive = 1,
                            smoke = GlassSettings.DEFAULT_SMOKE,
                            smokeColor = GlassSettings.SMOKE_BLACK,
                            switchPoint = GlassSettings.DEFAULT_SWITCH_POINT,
                        ),
                    placesOff = emptySet(),
                )
        }
    }
}

/** Glass's [GlassSettings.COLOR] and [GlassSettings.TINT]. */
data class GlassTint(val color: Int, val tint: Int) {

    /** The color this glass tints with, over a place where Android tints with [androidColor]. */
    @ColorInt
    fun over(context: Context, @ColorInt androidColor: Int): Int =
        GlassSettings.tintColor(context, color, tint, androidColor)
}

/**
 * What glass with a curved rim is drawn from: [GlassSettings.STYLE], [GlassSettings.BEND],
 * [GlassSettings.RIM_WIDTH], [GlassSettings.RIM_LIGHT], [GlassSettings.RAINBOW],
 * [GlassSettings.SHADOW], its color ([tint]), [GlassSettings.ADAPTIVE], [GlassSettings.SMOKE],
 * [GlassSettings.SMOKE_COLOR] and [GlassSettings.SWITCH_POINT].
 */
data class GlassRim(
    val style: Int,
    val bend: Int,
    val width: Int,
    val light: Int,
    val rainbow: Int,
    val shadow: Int,
    val tint: GlassTint,
    val adaptive: Int,
    val smoke: Int,
    val smokeColor: Int,
    val switchPoint: Int,
) {

    /**
     * How this glass is drawn on [context]'s display, over a place where Android tints with
     * [androidColor], or null for the Flat style. The glass brings its own color, so while this
     * isn't null the place leaves its own tint out.
     */
    fun look(context: Context, @ColorInt androidColor: Int): GlassLook? =
        GlassSettings.look(
            context,
            style,
            bend,
            width,
            light,
            rainbow,
            shadow,
            tint.over(context, androidColor),
            smoke,
            smokeColor,
            adaptive,
            switchPoint,
        )
}
