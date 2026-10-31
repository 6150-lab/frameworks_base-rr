/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.data.repository

import android.content.Context
import androidx.annotation.ColorInt
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.settings.GlobalSettings
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
    private val globalSettings: GlobalSettings,
    @Application applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
) {
    private val state: StateFlow<GlassState> =
        globalSettings
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

    private fun readState() =
        GlassState(
            enabled = globalSettings.getInt(GlassSettings.ENABLED, 0) == 1,
            strength =
                globalSettings.getInt(
                    GlassSettings.BLUR_STRENGTH,
                    GlassSettings.DEFAULT_BLUR_STRENGTH,
                ),
            tint =
                GlassTint(
                    color = globalSettings.getInt(GlassSettings.COLOR, GlassSettings.COLOR_FROSTED),
                    tint = globalSettings.getInt(GlassSettings.TINT, GlassSettings.DEFAULT_TINT),
                ),
            placesOff =
                GlassSettings.PLACES.filterTo(HashSet()) { globalSettings.getInt(it, 1) == 0 },
        )

    private data class GlassState(
        val enabled: Boolean,
        val strength: Int,
        val tint: GlassTint,
        val placesOff: Set<String>,
    ) {
        fun isOn(place: String) = enabled && place !in placesOff

        fun blurScale(place: String) = if (isOn(place)) GlassSettings.blurScale(strength) else 1f

        fun tint(place: String) = if (isOn(place)) tint else null

        companion object {
            /** Until the settings are read: glass off, as it is by default. */
            val OFF =
                GlassState(
                    enabled = false,
                    strength = GlassSettings.DEFAULT_BLUR_STRENGTH,
                    tint = GlassTint(GlassSettings.COLOR_FROSTED, GlassSettings.DEFAULT_TINT),
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
