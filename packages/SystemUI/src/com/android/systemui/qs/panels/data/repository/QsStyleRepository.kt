/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class QsStyleRepository
@Inject
constructor(@Background scope: CoroutineScope, systemSettings: SystemSettingsRepository) {
    val style: StateFlow<QsStyle> =
        systemSettings
            .intSetting(QsStyle.SETTING, QsStyle.STOCK.value)
            .map { QsStyle.of(it) }
            .stateIn(scope, SharingStarted.Eagerly, QsStyle.STOCK)
}
