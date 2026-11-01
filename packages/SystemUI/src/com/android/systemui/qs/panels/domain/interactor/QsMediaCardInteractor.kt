/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.data.repository.QsStyleRepository
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The media tile is the only player in QS: without it there is none. It draws the full card only
 * when it was moved out of the tile grid.
 */
@SysUISingleton
class QsMediaCardInteractor
@Inject
constructor(
    @Background scope: CoroutineScope,
    currentTilesInteractor: CurrentTilesInteractor,
    qsStyleRepository: QsStyleRepository,
) {
    val cardShown: StateFlow<Boolean> =
        combine(
                currentTilesInteractor.currentTiles
                    .map { tiles -> tiles.any { it.spec.spec == QsMediaTileSize.TILE_SPEC } }
                    .distinctUntilChanged(),
                qsStyleRepository.style,
                qsStyleRepository.tweaks,
            ) { hasTile, style, tweaks ->
                hasTile && tweaks.mediaPlaceIn(style) != QsMediaPlace.GRID
            }
            .stateIn(scope, SharingStarted.Eagerly, false)
}
