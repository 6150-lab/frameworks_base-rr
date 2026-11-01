/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.qs.panels.data.repository.QsStyleRepository
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.TileCells
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.paginateTiles
import com.android.systemui.qs.panels.ui.dialog.QSResetDialogDelegate
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class InfiniteGridViewModel
@AssistedInject
constructor(
    dynamicIconTilesViewModelFactory: DynamicIconTilesViewModel.Factory,
    val columnsWithMediaViewModelFactory: QSColumnsViewModel.Factory,
    val squishinessViewModel: TileSquishinessViewModel,
    val snapshotViewModelFactory: InfiniteGridSnapshotViewModel.Factory,
    val resetDialogDelegateFactory: QSResetDialogDelegate.Factory,
    val editTopBarActionsViewModelFactory: EditTopBarActionsViewModel.Factory,
    val qsStyleRepository: QsStyleRepository,
) : ExclusiveActivatable(), PaginatableViewModel {
    private val hydrator = Hydrator("InfiniteGridViewModel.hydrator")

    private val style by
        hydrator.hydratedStateOf(traceName = "style", source = qsStyleRepository.style)
    private val tweaks by
        hydrator.hydratedStateOf(traceName = "tweaks", source = qsStyleRepository.tweaks)

    val iconTilesViewModel = dynamicIconTilesViewModelFactory.create()
    val columnsWithMediaViewModel =
        columnsWithMediaViewModelFactory.create(
            LOCATION_QS,
            QuickSettingsContainerViewModel.mediaUiBehavior,
        )

    override val pageKeys: Array<Any>
        get() =
            arrayOf(
                columnsWithMediaViewModel.columns,
                columnsWithMediaViewModel.largeSpan,
                iconTilesViewModel.largeTilesState.value,
                style,
                tweaks,
            )

    override fun splitIntoPages(tiles: List<TileViewModel>, rows: Int): List<List<TileViewModel>> {
        val shown = tiles.filter { isInGrid(it.spec) }
        return paginateTiles(
                shown.map { cellsOf(it.spec) },
                columnsWithMediaViewModel.columns,
                rows,
            )
            .map { page -> page.map { shown[it] } }
    }

    /** The player leaves the grid when it was moved above or below the tiles. */
    fun isInGrid(spec: TileSpec): Boolean =
        spec.spec != QsMediaTileSize.TILE_SPEC || tweaks.mediaPlaceIn(style) == QsMediaPlace.GRID

    fun cellsOf(spec: TileSpec): TileCells =
        tweaks.cellsOf(
            spec = spec.spec,
            theme = style,
            isLarge = iconTilesViewModel.largeTilesState.value.contains(spec),
            largeSpan = columnsWithMediaViewModel.largeSpan,
            columns = columnsWithMediaViewModel.columns,
        )

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { iconTilesViewModel.activate() }
            launch { columnsWithMediaViewModel.activate() }
            launch { hydrator.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory : PaginatableViewModel.Factory {
        override fun create(): InfiniteGridViewModel
    }
}
