/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsGlassTune
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsMyTheme
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.QsTweaksCodec
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SysUISingleton
class QsStyleRepository
@Inject
constructor(
    @Background private val scope: CoroutineScope,
    private val systemSettings: SystemSettingsRepository,
    blurInteractor: WindowRootViewBlurInteractor,
) {
    val style: StateFlow<QsStyle> =
        systemSettings
            .intSetting(QsStyle.SETTING, QsStyle.STOCK.value)
            .map { QsStyle.of(it) }
            .stateIn(scope, SharingStarted.Eagerly, QsStyle.STOCK)

    private val tweakStore = MutableStateFlow<Map<Int, QsTweaks>>(emptyMap())
    private var saveTweaksJob: Job? = null

    /** The changes made to the current theme. */
    val tweaks: StateFlow<QsTweaks> =
        combine(style, tweakStore) { theme, store -> store[theme.value] ?: QsTweaks() }
            .stateIn(scope, SharingStarted.Eagerly, QsTweaks())

    val myThemes: StateFlow<List<QsMyTheme>> =
        systemSettings
            .stringSetting(QsTweaksCodec.MY_THEMES_SETTING)
            .map { QsTweaksCodec.parseMyThemes(it) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val qsMediaStyle: StateFlow<QsStyle> =
        combine(style, tweaks) { theme, tweaks -> tweaks.mediaFrom ?: theme }
            .stateIn(scope, SharingStarted.Eagerly, QsStyle.STOCK)

    val mediaTileSize: StateFlow<QsMediaTileSize> =
        tweaks
            .map { it.mediaSize ?: QsMediaTileSize.SQUARE }
            .stateIn(scope, SharingStarted.Eagerly, QsMediaTileSize.SQUARE)

    /** Lockscreen media card look; follows [qsMediaStyle] unless one is picked. */
    val lockscreenMediaStyle: StateFlow<QsStyle> =
        combine(qsMediaStyle, systemSettings.intSetting(QsStyle.LOCKSCREEN_MEDIA_SETTING, -1)) {
                qs,
                media ->
                if (media < 0) qs else QsStyle.of(media)
            }
            .stateIn(scope, SharingStarted.Eagerly, QsStyle.STOCK)

    /** Liquid Glass on the tiles; opaque while blur is off (Solid, Battery Saver, heat). */
    val glass: StateFlow<QsGlass> =
        combine(
                systemSettings.intSetting(GlassSettings.PLACE_SHADE, 1),
                systemSettings.intSetting(GlassSettings.MATERIAL, GlassSettings.DEFAULT_MATERIAL),
                blurInteractor.isBlurCurrentlySupported,
            ) { qs, material, blur ->
                when {
                    qs == 0 || !blur -> QsGlass.SOLID
                    material == GlassSettings.MATERIAL_LIQUID -> QsGlass.LIQUID
                    material == GlassSettings.MATERIAL_FROSTED -> QsGlass.FROSTED
                    else -> QsGlass.SOLID
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, QsGlass.SOLID)

    val glassTune: StateFlow<QsGlassTune> =
        systemSettings
            .intSetting(GlassSettings.TINT, GlassSettings.DEFAULT_TINT)
            .map { QsGlassTune(it.coerceIn(0, 100) / 100f) }
            .stateIn(scope, SharingStarted.Eagerly, QsGlassTune())

    init {
        scope.launch {
            systemSettings.stringSetting(QsTweaksCodec.SETTING).collect { saved ->
                // An unsaved edit is newer than what the setting holds.
                if (saveTweaksJob?.isActive != true) {
                    tweakStore.value = QsTweaksCodec.parseStore(saved)
                }
            }
        }
    }

    fun setStyle(style: QsStyle) {
        scope.launch { systemSettings.setInt(QsStyle.SETTING, style.value) }
    }

    /** Changes the current theme. Quick edits, like a slider drag, are saved together. */
    fun editTweaks(edit: (QsTweaks) -> QsTweaks) {
        val theme = style.value.value
        tweakStore.update { store ->
            val tweaks = edit(store[theme] ?: QsTweaks())
            if (tweaks == QsTweaks()) store - theme else store + (theme to tweaks)
        }
        saveTweaks()
    }

    fun resetTweaks() = editTweaks { QsTweaks() }

    private fun saveTweaks() {
        saveTweaksJob?.cancel()
        saveTweaksJob =
            scope.launch {
                delay(SAVE_TWEAKS_DELAY_MS)
                systemSettings.setString(
                    QsTweaksCodec.SETTING,
                    QsTweaksCodec.writeStore(tweakStore.value),
                )
            }
    }

    fun currentAsMyTheme(name: String): QsMyTheme = QsMyTheme(name, style.value, tweaks.value)

    fun saveMyTheme(name: String) {
        writeMyThemes(myThemes.value + currentAsMyTheme(name))
    }

    fun deleteMyTheme(theme: QsMyTheme) {
        writeMyThemes(myThemes.value - theme)
    }

    /** Switches to the theme of [myTheme] and gives it those changes. */
    fun applyMyTheme(myTheme: QsMyTheme) {
        tweakStore.update { store ->
            if (myTheme.tweaks == QsTweaks()) store - myTheme.style.value
            else store + (myTheme.style.value to myTheme.tweaks)
        }
        saveTweaks()
        setStyle(myTheme.style)
    }

    private fun writeMyThemes(themes: List<QsMyTheme>) {
        scope.launch {
            systemSettings.setString(
                QsTweaksCodec.MY_THEMES_SETTING,
                QsTweaksCodec.writeMyThemes(themes),
            )
        }
    }

    private companion object {
        const val SAVE_TWEAKS_DELAY_MS = 300L
    }
}
