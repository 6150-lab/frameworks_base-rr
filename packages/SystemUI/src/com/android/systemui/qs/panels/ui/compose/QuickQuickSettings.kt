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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.media.remedia.ui.compose.QsMediaTile
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.shared.model.LocalQsMediaTileSize
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.isBeside
import com.android.systemui.qs.panels.ui.compose.infinitegrid.PerTileLook
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TilePlacementGrid
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileLook
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileShapeStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.verticalSquish
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R

@Composable
fun ContentScope.QuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier = Modifier,
    listening: () -> Boolean,
) {
    val columns = viewModel.columns
    val tweaks = LocalQsTweaks.current
    val pageRows =
        if (tweaks.scroll) {
            Int.MAX_VALUE
        } else {
            tweaks.rowsPerPage ?: integerResource(R.integer.quick_settings_paginated_grid_num_rows)
        }
    val placedTiles by
        remember(viewModel, pageRows) { derivedStateOf { viewModel.placedTiles(pageRows) } }
    val tiles = placedTiles.fastMap { it.tile }
    val placements = remember(placedTiles) { placedTiles.fastMap { it.place } }
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tileHeight = tileLook(tileShapeStyle()).height
    val columnSpacing = tweaks.spacing?.dp ?: dimensionResource(R.dimen.qs_tile_margin_horizontal)
    val rowSpacing = tweaks.spacing?.dp ?: dimensionResource(R.dimen.qs_tile_margin_vertical)
    val mediaTileSize = LocalQsMediaTileSize.current

    Box(modifier = modifier) {
        GridAnchor()

        val bounceables =
            remember(placedTiles) { List(placedTiles.size) { BounceableTileViewModel() } }
        TilePlacementGrid(
            placements = placements,
            columns = columns,
            columnSpacing = columnSpacing,
            rowSpacing = rowSpacing,
            keys = { placedTiles[it].tile.spec },
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
        ) { index ->
            val placed = placedTiles[index]
            val place = placed.place
            val height = tileHeight * place.rows + rowSpacing * (place.rows - 1)
            Element(placed.tile.spec.toElementKey(), Modifier) {
                if (placed.tile.spec.spec == QsMediaTileSize.TILE_SPEC) {
                    QsMediaTile(
                        viewModelFactory = viewModel.mediaViewModelFactory,
                        size =
                            when {
                                !placed.cut -> mediaTileSize
                                place.rows > 1 && place.columns >= columns -> QsMediaTileSize.FULL
                                place.rows > 1 -> QsMediaTileSize.SQUARE
                                place.columns > 1 -> QsMediaTileSize.WIDE
                                else -> QsMediaTileSize.SMALL
                            },
                        modifier =
                            Modifier.fillMaxWidth()
                                .clipToBounds()
                                .verticalSquish { squishiness }
                                .height(height),
                    )
                } else {
                    PerTileLook(placed.tile.spec) {
                        Tile(
                            tile = placed.tile,
                            iconOnly = place.columns == 1,
                            squishiness = { squishiness },
                            coroutineScope = scope,
                            bounceableInfo =
                                bounceables.bounceableInfo(
                                    SizedTileImpl(placed.tile, place.columns),
                                    index = index,
                                    column = place.column,
                                    columns = columns,
                                    isFirstInRow = !placements.isBeside(index, index - 1),
                                    isLastInRow = !placements.isBeside(index, index + 1),
                                ),
                            tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                            // There should be no QuickQuickSettings when the details view is
                            // enabled.
                            detailsViewModel = null,
                            isVisible = listening,
                            rows = place.rows,
                            height = height,
                        )
                    }
                }
            }
        }
    }

    TileListener(tiles, listening)
}
