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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
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
import com.android.systemui.media.remedia.ui.compose.QsMediaTile
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.shared.model.LocalQsMediaTileSize
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.TilePlacement
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.packTiles
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
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        val tweaks = LocalQsTweaks.current
        val mediaTileSize = LocalQsMediaTileSize.current
        val tileHeight = tileLook(tileShapeStyle()).height
        val shownTiles = remember(tiles, viewModel.pageKeys.toList()) {
            tiles.filter { viewModel.isInGrid(it.spec) }
        }
        val cells =
            remember(shownTiles, largeTiles, columns, viewModel.pageKeys.toList()) {
                shownTiles.map { viewModel.cellsOf(it.spec) }
            }
        val placements = remember(cells, columns) { packTiles(cells, columns) }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val bounceables =
            remember(shownTiles) { List(shownTiles.size) { BounceableTileViewModel() } }
        val columnSpacing =
            tweaks.spacing?.dp ?: dimensionResource(R.dimen.qs_tile_margin_horizontal)
        val rowSpacing = tweaks.spacing?.dp ?: dimensionResource(R.dimen.qs_tile_margin_vertical)

        TilePlacementGrid(
            placements = placements,
            columns = columns,
            columnSpacing = columnSpacing,
            rowSpacing = rowSpacing,
            keys = { shownTiles[it].spec },
            modifier = modifier,
        ) { index ->
            val tile = shownTiles[index]
            val place = placements[index]
            val height = tileHeight * place.rows + rowSpacing * (place.rows - 1)
            Element(tile.spec.toElementKey(), Modifier) {
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
                                isLastInRow = placements.getOrNull(index + 1)?.row != place.row,
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
            scrollState = scrollState,
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
                is EditAction.ResizeTile -> {
                    iconTilesViewModel.resize(action.tileSpec, action.toIcon)
                    viewModel.qsStyleRepository.editTweaks { current ->
                        current.afterResize(
                            action.tileSpec.spec,
                            theme,
                            action.toIcon,
                            largeTilesSpan,
                            columns,
                        )
                    }
                }
                is EditAction.SetTiles -> {
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}

private fun TileSpec.isMediaTile() = spec == QsMediaTileSize.TILE_SPEC

/** The resize handle replaces any size picked for the tile, or the theme's when it differs. */
private fun QsTweaks.afterResize(
    spec: String,
    theme: QsStyle,
    toIcon: Boolean,
    largeSpan: Int,
    columns: Int,
): QsTweaks {
    if (spec == QsMediaTileSize.TILE_SPEC) {
        return copy(mediaSize = if (toIcon) QsMediaTileSize.SMALL else null)
    }
    val wanted = if (toIcon) 1 else largeSpan.coerceAtMost(columns)
    val plain = withTile(spec) { it.copy(columns = null, rows = null) }
    val auto = plain.cellsOf(spec, theme, !toIcon, largeSpan, columns).columns
    return if (auto == wanted) plain
    else plain.withTile(spec) { it.copy(columns = wanted) }
}

/**
 * A grid whose tiles can span rows as well as columns. One row tiles set the height of their row,
 * and a taller tile only stretches the rows it spans when it doesn't fit in them.
 */
@Composable
private fun TilePlacementGrid(
    placements: List<TilePlacement>,
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    keys: (index: Int) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int) -> Unit,
) {
    Layout(
        content = {
            placements.indices.forEach { index ->
                key(keys(index)) {
                    Box(
                        Modifier.semantics {
                            collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                        }
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
                )
            }
        }
    }
}
