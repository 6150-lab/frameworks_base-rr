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

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.data.repository.QsStyleRepository
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.TileCells
import com.android.systemui.qs.panels.shared.model.TilePlacement
import com.android.systemui.qs.panels.shared.model.packTiles
import com.android.systemui.qs.panels.shared.model.paginateTiles
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickQuickSettingsViewModel
@AssistedInject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    qsColumnsViewModelFactory: QSColumnsViewModel.Factory,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    val squishinessViewModel: TileSquishinessViewModel,
    iconTilesViewModel: IconTilesViewModel,
    val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    qsStyleRepository: QsStyleRepository,
    val mediaViewModelFactory: MediaViewModel.Factory,
) : HydratedActivatable() {

    private val style by qsStyleRepository.style.hydratedStateOf()

    private val tweaks by qsStyleRepository.tweaks.hydratedStateOf()

    private val qsColumnsViewModel = qsColumnsViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)
    private val mediaInRowViewModel =
        mediaInRowInLandscapeViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)

    val columns: Int
        get() = qsColumnsViewModel.columns

    private val largeTiles by iconTilesViewModel.largeTiles.hydratedStateOf()

    private val rows: Int
        get() =
            if (mediaInRowViewModel.shouldMediaShowInRow) {
                rowsWithoutMedia * 2
            } else {
                rowsWithoutMedia
            }

    private val rowsWithoutMedia by
        quickQuickSettingsRowInteractor.rows.hydratedStateOf(
            initialValue = quickQuickSettingsRowInteractor.defaultRows
        )

    private val largeTilesSpan: Int
        get() = qsColumnsViewModel.largeSpan

    private val currentTiles by tilesInteractor.currentTiles.hydratedStateOf()

    val tileViewModels by derivedStateOf {
        placedTiles(Int.MAX_VALUE).map { SizedTileImpl(it.tile, it.place.columns) }
    }

    /** A tile of the collapsed panel, and whether it lost rows to fit there. */
    data class PlacedTile(val tile: TileViewModel, val place: TilePlacement, val cut: Boolean)

    /**
     * The tiles in the first rows of the first QS page of [pageRows] rows, where QS puts them, so
     * they stay in place while the panel expands.
     */
    fun placedTiles(pageRows: Int): List<PlacedTile> {
        val mediaInGrid = tweaks.mediaPlaceIn(style) == QsMediaPlace.GRID
        val shown =
            currentTiles.filter { mediaInGrid || it.spec.spec != QsMediaTileSize.TILE_SPEC }
        val cells = shown.map { it.spec.cells() }
        val page = paginateTiles(cells, columns, pageRows).firstOrNull() ?: return emptyList()
        val fitted = page.map { cells[it].copy(rows = cells[it].rows.coerceAtMost(rows)) }
        return packTiles(fitted, columns).mapIndexedNotNull { index, place ->
            if (place.row + place.rows > rows) return@mapIndexedNotNull null
            val tile = shown[page[index]]
            PlacedTile(
                TileViewModel(tile.tile, tile.spec, tile.expandable),
                place,
                cut = place.rows < cells[page[index]].rows,
            )
        }
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch { qsColumnsViewModel.activate() }
            launch { mediaInRowViewModel.activate() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickQuickSettingsViewModel
    }

    private fun TileSpec.cells(): TileCells =
        tweaks.cellsOf(spec, style, largeTiles.contains(this), largeTilesSpan, columns)

    companion object {
        /** Behavior of the media carousel in quick quick settings */
        @VisibleForTesting
        val mediaUiBehavior: MediaUiBehavior
            get() =
                MediaUiBehavior(
                    isCarouselDismissible = true,
                    carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                )
    }
}
