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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.ui.compose.QsMediaTile
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.shared.model.LocalQsMediaTileSize
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShadeFlag
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    override val viewModelFactory: InfiniteGridViewModel.Factory,
    private val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    private val mediaViewModelFactory: MediaViewModel.Factory,
) : PaginatableGridLayout {

    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }

        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }

        val columns = viewModel.columnsWithMediaViewModel.columns
        val largeTilesSpan = viewModel.columnsWithMediaViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        val style = LocalQsStyle.current
        val uniformTiles = style.uniformTiles
        val mediaTileSize = LocalQsMediaTileSize.current
        val tileHeight = tileLook(style).height
        val blockRows = if (tileHeight < 56.dp) 3 else 2
        // Tiles or largeTiles may be updated while this is composed, so listen to any changes
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan, uniformTiles, mediaTileSize, columns) {
                tiles.map {
                    val span =
                        when {
                            it.spec.isMediaTile() ->
                                if (mediaTileSize == QsMediaTileSize.SMALL) 1 else columns
                            !uniformTiles && largeTiles.contains(it.spec) -> largeTilesSpan
                            else -> 1
                        }
                    SizedTileImpl(it, span)
                }
            }
        val cells =
            remember(sizedTiles, columns, mediaTileSize, blockRows) {
                gridCells(sizedTiles, columns, mediaTileSize, blockRows)
            }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val spans by remember(cells) { derivedStateOf { cells.fastMap { it.span } } }
        val columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal)
        val rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical)

        val tile: @Composable (Int, Int, Boolean, Boolean) -> Unit =
            { index, column, isFirstInRow, isLastInRow ->
                val it = sizedTiles[index]
                Element(it.tile.spec.toElementKey(), Modifier) {
                    if (it.tile.spec.isMediaTile()) {
                        QsMediaTile(
                            viewModelFactory = mediaViewModelFactory,
                            size =
                                if (mediaTileSize == QsMediaTileSize.SMALL) QsMediaTileSize.SMALL
                                else QsMediaTileSize.WIDE,
                            modifier = Modifier.fillMaxWidth().height(tileHeight),
                        )
                    } else {
                        Tile(
                            tile = it.tile,
                            iconOnly = uniformTiles || iconTilesViewModel.isIconTile(it.tile.spec),
                            squishiness = { squishiness },
                            tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                            coroutineScope = scope,
                            bounceableInfo =
                                bounceables.bounceableInfo(
                                    it,
                                    index = index,
                                    column = column,
                                    columns = columns,
                                    isFirstInRow = isFirstInRow,
                                    isLastInRow = isLastInRow,
                                ),
                            detailsViewModel = detailsViewModel,
                            isVisible = listening,
                            requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                            enableRevealEffect = enableRevealEffect,
                        )
                    }
                }
            }

        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = columnSpacing,
            rowSpacing = rowSpacing,
            spans = spans,
            keys = { cells[it].key },
            modifier = modifier,
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            when (val cell = cells[spanIndex]) {
                is GridCell.Single -> tile(cell.index, column, isFirstInColumn, isLastInColumn)
                is GridCell.MediaBlock ->
                    MediaBlock(
                        cell = cell,
                        columns = columns,
                        columnSpacing = columnSpacing,
                        rowSpacing = rowSpacing,
                        minHeight = tileHeight * blockRows + rowSpacing * (blockRows - 1),
                        widthOf = { sizedTiles[it].width },
                        media = {
                            Element(sizedTiles[cell.index].tile.spec.toElementKey(), Modifier) {
                                QsMediaTile(
                                    viewModelFactory = mediaViewModelFactory,
                                    size = QsMediaTileSize.SQUARE,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                        sideTile = { index, sideColumn, isLast ->
                            tile(index, sideColumn, false, isLast)
                        },
                    )
            }
        }

        TileListener(tiles, listening)
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val snapshotViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.snapshotViewModelFactory.create()
            }
        val topBarActionsViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.editTopBarActionsViewModelFactory.create()
            }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()

                    // Automatically scroll to the top on reset
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                }
            }
        val showDualShadeSetting =
            DualShadeFlag.isEnabled &&
                LocalResources.current.getBoolean(
                    com.android.settingslib.R.bool.config_useDualShadeSetting
                )
        val actions =
            remember(topBarActionsViewModel, showDualShadeSetting) {
                topBarActionsViewModel.actions(showDualShadeSetting).toMutableStateList()
            }
        val columns = columnsViewModel.columns
        val largeTilesSpan = columnsViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState

        val currentTiles by rememberUpdatedState(tiles.filter { it.isCurrent })
        val listState =
            remember(columns, largeTilesSpan) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                )
            }
        LaunchedEffect(currentTiles, largeTiles) { listState.updateTiles(currentTiles, largeTiles) }

        DefaultEditTileGrid(
            listState = listState,
            allTiles = tiles,
            modifier = modifier,
            scrollState = scrollState,
            snapshotViewModel = snapshotViewModel,
            onStopEditing = onStopEditing,
            topBarActions = actions,
        ) { action ->
            // Opening the dialog doesn't require a snapshot
            if (action != EditAction.ResetGrid) {
                snapshotViewModel.takeSnapshot(currentTiles.map { it.tileSpec }, largeTiles)
            }

            when (action) {
                is EditAction.AddTile -> {
                    onAddTile(action.tileSpec, listState.tileSpecs().size)
                }
                is EditAction.InsertTile -> {
                    onAddTile(action.tileSpec, action.position)
                }
                is EditAction.RemoveTile -> {
                    onRemoveTile(action.tileSpec)
                }
                EditAction.ResetGrid -> {
                    dialogDelegate.showDialog()
                }
                is EditAction.ResizeTile -> {
                    iconTilesViewModel.resize(action.tileSpec, action.toIcon)
                }
                is EditAction.SetTiles -> {
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}

private sealed interface GridCell {
    val span: Int
    val key: Any

    data class Single(val index: Int, override val span: Int, override val key: Any) : GridCell

    data class MediaBlock(
        val index: Int,
        val mediaColumns: Int,
        val rows: List<List<Int>>,
        override val span: Int,
        override val key: Any,
    ) : GridCell
}

private fun TileSpec.isMediaTile() = spec == QsMediaTileSize.TILE_SPEC

private fun gridCells(
    tiles: List<SizedTile<TileViewModel>>,
    columns: Int,
    size: QsMediaTileSize,
    blockRows: Int,
): List<GridCell> {
    val media = tiles.indexOfFirst { it.tile.spec.isMediaTile() }
    if (media < 0 || size != QsMediaTileSize.SQUARE || columns < 3) {
        return tiles.mapIndexed { i, t -> GridCell.Single(i, t.width, t.tile.spec) }
    }
    val mediaColumns = if (columns >= 5) 3 else 2
    val sideColumns = columns - mediaColumns
    val rows = List(blockRows) { mutableListOf<Int>() }
    var row = 0
    var used = 0
    var next = media + 1
    while (next < tiles.size && row < blockRows) {
        val width = tiles[next].width
        if (width > sideColumns) break
        if (used + width > sideColumns) {
            row++
            used = 0
            continue
        }
        rows[row].add(next)
        used += width
        next++
    }
    return buildList {
        for (i in 0 until media) add(GridCell.Single(i, tiles[i].width, tiles[i].tile.spec))
        add(
            GridCell.MediaBlock(
                index = media,
                mediaColumns = mediaColumns,
                rows = rows.filter { it.isNotEmpty() },
                span = columns,
                key = tiles[media].tile.spec,
            )
        )
        for (i in next until tiles.size) add(GridCell.Single(i, tiles[i].width, tiles[i].tile.spec))
    }
}

@Composable
private fun MediaBlock(
    cell: GridCell.MediaBlock,
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    minHeight: Dp,
    widthOf: (Int) -> Int,
    media: @Composable () -> Unit,
    sideTile: @Composable (index: Int, column: Int, isLastInRow: Boolean) -> Unit,
) {
    val sideColumns = columns - cell.mediaColumns
    Layout(
        contents =
            listOf(
                media,
                {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(rowSpacing),
                    ) {
                        cell.rows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(columnSpacing),
                            ) {
                                var used = 0
                                row.forEach { index ->
                                    val width = widthOf(index)
                                    val column = cell.mediaColumns + used
                                    Box(Modifier.weight(width.toFloat())) {
                                        sideTile(index, column, used + width == sideColumns)
                                    }
                                    used += width
                                }
                                if (used < sideColumns) {
                                    Spacer(Modifier.weight((sideColumns - used).toFloat()))
                                }
                            }
                        }
                    }
                },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) { (mediaMeasurables, sideMeasurables), constraints ->
        val spacing = columnSpacing.roundToPx()
        val cellWidth = (constraints.maxWidth - spacing * (columns - 1)) / columns.toFloat()
        val mediaWidth = (cellWidth * cell.mediaColumns + spacing * (cell.mediaColumns - 1)).roundToInt()
        val sideWidth = (constraints.maxWidth - mediaWidth - spacing).coerceAtLeast(0)
        val side = sideMeasurables.first().measure(Constraints.fixedWidth(sideWidth))
        val height = maxOf(side.height, minHeight.roundToPx())
        val mediaPlaceable = mediaMeasurables.first().measure(Constraints.fixed(mediaWidth, height))
        layout(constraints.maxWidth, height) {
            mediaPlaceable.place(0, 0)
            side.place(mediaWidth + spacing, 0)
        }
    }
}
