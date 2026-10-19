/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class QsStyleRepository
@Inject
constructor(
    @Background scope: CoroutineScope,
    systemSettings: SystemSettingsRepository,
    blurInteractor: WindowRootViewBlurInteractor,
) {
    val style: StateFlow<QsStyle> =
        systemSettings
            .intSetting(QsStyle.SETTING, QsStyle.STOCK.value)
            .map { QsStyle.of(it) }
            .stateIn(scope, SharingStarted.Eagerly, QsStyle.STOCK)

    /** Lockscreen media card look; follows [style] unless one is picked. */
    val lockscreenMediaStyle: StateFlow<QsStyle> =
        combine(style, systemSettings.intSetting(QsStyle.LOCKSCREEN_MEDIA_SETTING, -1)) { qs, media ->
                if (media < 0) qs else QsStyle.of(media)
            }
            .stateIn(scope, SharingStarted.Eagerly, QsStyle.STOCK)

    /** Liquid Glass on the tiles; opaque while blur is off (Solid, Battery Saver, heat). */
    val glass: StateFlow<QsGlass> =
        combine(
                systemSettings.intSetting(QsGlass.SETTING_QS, 1),
                systemSettings.intSetting(QsGlass.SETTING_MATERIAL, 1),
                blurInteractor.isBlurCurrentlySupported,
            ) { qs, material, blur ->
                when {
                    qs == 0 || !blur -> QsGlass.SOLID
                    material == 2 -> QsGlass.LIQUID
                    material == 1 -> QsGlass.FROSTED
                    else -> QsGlass.SOLID
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, QsGlass.SOLID)
}
