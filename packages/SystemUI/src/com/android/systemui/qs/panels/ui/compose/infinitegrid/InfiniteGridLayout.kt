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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.compose.modifiers.thenIf
import com.android.systemui.media.remedia.ui.compose.QsMediaTile
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.shared.model.LocalQsMediaTileSize
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.TileCells
import com.android.systemui.qs.panels.shared.model.TilePlacement
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.packTiles
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.LocalQsPreviewTiles
import com.android.systemui.qs.panels.ui.compose.LocalQsPreviewPage
import com.android.systemui.qs.panels.ui.compose.QsPlusTile
import com.android.systemui.qs.panels.ui.compose.QsPreviewTile
import com.android.systemui.qs.panels.ui.compose.animatePlacement
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
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        val tweaks = LocalQsTweaks.current
        val mediaTileSize = LocalQsMediaTileSize.current
        val look = tileLook(tileShapeStyle())
        val tileHeight = look.height
        val preview = LocalQsPreviewTiles.current
        val previewPage = LocalQsPreviewPage.current
        val shownTiles = remember(tiles, viewModel.pageKeys.toList()) {
            tiles.filter { viewModel.isInGrid(it.spec) }
        }
        val cells =
            remember(shownTiles, largeTiles, columns, viewModel.pageKeys.toList()) {
                shownTiles.map { viewModel.cellsOf(it.spec) }
            }
        // In edit mode the last page ends with the plus tile, when a cell is left for it.
        val plus = preview != null && previewPage?.last != false
        val packed =
            remember(cells, columns, plus) {
                packTiles(if (plus) cells + TileCells(1, 1) else cells, columns)
            }
        val plusPlace =
            packed.getOrNull(cells.size)?.takeIf {
                previewPage == null || it.row < previewPage.rows
            }
        val placements = if (plusPlace != null) packed else packed.take(cells.size)
        var gridWidth by remember { mutableIntStateOf(0) }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val bounceables =
            remember(shownTiles) { List(shownTiles.size) { BounceableTileViewModel() } }
        val columnSpacing =
            tweaks.spacing?.dp ?: dimensionResource(R.dimen.qs_tile_margin_horizontal)
        val rowSpacing = tweaks.spacing?.dp ?: dimensionResource(R.dimen.qs_tile_margin_vertical)
        // One cell with its gap, for the resize handle to snap to.
        val (cellWidth, rowHeight) =
            with(LocalDensity.current) {
                val gapX = columnSpacing.toPx()
                val cell = if (gridWidth > 0) (gridWidth - gapX * (columns - 1)) / columns else 0f
                (if (cell > 0f) cell + gapX else 0f) to tileHeight.toPx() + rowSpacing.toPx()
            }

        TilePlacementGrid(
            placements = placements,
            columns = columns,
            columnSpacing = columnSpacing,
            rowSpacing = rowSpacing,
            keys = { if (it < shownTiles.size) shownTiles[it].spec else PlusTileKey },
            raised = {
                preview != null && it < shownTiles.size && shownTiles[it].spec == preview.dragging
            },
            animate = preview?.settling == true,
            modifier =
                modifier.thenIf(preview != null) { Modifier.onSizeChanged { gridWidth = it.width } },
        ) { index ->
            if (index >= shownTiles.size) {
                if (preview != null) {
                    QsPlusTile(
                        preview = preview,
                        radius = look.inactiveRadius,
                        modifier = Modifier.fillMaxWidth().height(tileHeight),
                    )
                }
                return@TilePlacementGrid
            }
            val tile = shownTiles[index]
            val place = placements[index]
            val height = tileHeight * place.rows + rowSpacing * (place.rows - 1)
            val content: @Composable () -> Unit = {
                if (tile.spec.isMediaTile()) {
                    QsMediaTile(
                        viewModelFactory = mediaViewModelFactory,
                        size = mediaTileSize,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clipToBounds()
                                .verticalSquish { squishiness }
                                .height(height),
                    )
                } else {
                    PerTileLook(tile.spec) {
                        Tile(
                            tile = tile,
                            iconOnly = place.columns == 1,
                            squishiness = { squishiness },
                            tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                            coroutineScope = scope,
                            bounceableInfo =
                                bounceables.bounceableInfo(
                                    SizedTileImpl(tile, place.columns),
                                    index = index,
                                    column = place.column,
                                    columns = columns,
                                    isFirstInRow = place.column == 0,
                                    isLastInRow =
                                        placements.getOrNull(index + 1)?.row != place.row,
                                ),
                            detailsViewModel = detailsViewModel,
                            isVisible = listening,
                            requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                            enableRevealEffect = enableRevealEffect,
                            rows = place.rows,
                            height = height,
                        )
                    }
                }
            }
            if (preview != null) {
                QsPreviewTile(
                    preview = preview,
                    spec = tile.spec,
                    columns = place.columns,
                    rows = place.rows,
                    maxColumns = columns,
                    cellWidth = cellWidth,
                    rowHeight = rowHeight,
                    radius = look.activeRadius,
                    content = content,
                )
            } else {
                Element(tile.spec.toElementKey(), Modifier) { content() }
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
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()
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
        val tweaks = LocalQsTweaks.current
        val theme = LocalQsStyle.current
        val widths =
            currentTiles.associate {
                val spec = it.tileSpec
                val large = largeTiles.contains(spec)
                spec to tweaks.cellsOf(spec.spec, theme, large, largeTilesSpan, columns).columns
            }
        val listState =
            remember(columns, largeTilesSpan) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                    initialWidths = widths,
                )
            }
        LaunchedEffect(currentTiles, largeTiles, widths) {
            listState.updateTiles(currentTiles, largeTiles, widths)
        }

        DefaultEditTileGrid(
            listState = listState,
            allTiles = tiles,
            modifier = modifier,
            snapshotViewModel = snapshotViewModel,
            qsStyleRepository = viewModel.qsStyleRepository,
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
                is EditAction.SetTiles -> {
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}

private fun TileSpec.isMediaTile() = spec == QsMediaTileSize.TILE_SPEC

/**
 * A grid whose tiles can span rows as well as columns. One row tiles set the height of their row,
 * and a taller tile only stretches the rows it spans when it doesn't fit in them.
 */
@Composable
internal fun TilePlacementGrid(
    placements: List<TilePlacement>,
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    keys: (index: Int) -> Any,
    modifier: Modifier = Modifier,
    raised: (index: Int) -> Boolean = { false },
    animate: Boolean = false,
    content: @Composable (index: Int) -> Unit,
) {
    Layout(
        content = {
            placements.indices.forEach { index ->
                key(keys(index)) {
                    Box(
                        Modifier.thenIf(animate && !raised(index)) { Modifier.animatePlacement() }
                            .semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }
                    ) {
                        content(index)
                    }
                }
            }
        },
        modifier = modifier.semantics { collectionInfo = CollectionInfo(placements.size, 1) },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapX = columnSpacing.roundToPx()
        val gapY = rowSpacing.roundToPx()
        val cellWidth = (width - gapX * (columns - 1)) / columns.toFloat()
        val placeables =
            measurables.mapIndexed { index, measurable ->
                val cells = placements[index].columns
                measurable.measure(
                    Constraints.fixedWidth((cellWidth * cells + gapX * (cells - 1)).roundToInt())
                )
            }
        val rows = placements.maxOfOrNull { it.row + it.rows } ?: 0
        val heights = IntArray(rows)
        placements.forEachIndexed { index, place ->
            if (place.rows == 1) {
                heights[place.row] = maxOf(heights[place.row], placeables[index].height)
            }
        }
        placements.forEachIndexed { index, place ->
            if (place.rows > 1) {
                val spanned = place.row until place.row + place.rows
                val missing =
                    placeables[index].height - gapY * (place.rows - 1) -
                        spanned.sumOf { heights[it] }
                if (missing > 0) {
                    val empty = spanned.filter { heights[it] == 0 }
                    val growing = empty.ifEmpty { spanned.toList() }
                    growing.forEach { heights[it] += (missing + growing.size - 1) / growing.size }
                }
            }
        }
        val tops = IntArray(rows)
        for (row in 1 until rows) tops[row] = tops[row - 1] + heights[row - 1] + gapY
        val height = if (rows == 0) 0 else tops[rows - 1] + heights[rows - 1]
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val place = placements[index]
                placeable.placeRelative(
                    (place.column * (cellWidth + gapX)).roundToInt(),
                    tops[place.row],
                    zIndex = if (raised(index)) 1f else 0f,
                )
            }
        }
    }
}

private object PlusTileKey
