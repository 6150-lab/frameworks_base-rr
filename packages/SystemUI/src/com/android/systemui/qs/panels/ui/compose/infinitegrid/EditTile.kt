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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.ui.icons.Undo
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.data.repository.QsStyleRepository
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.resized
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.LocalQsEditPreview
import com.android.systemui.qs.panels.ui.compose.LocalQsPreviewTiles
import com.android.systemui.qs.panels.ui.compose.QsFitToHeight
import com.android.systemui.qs.panels.ui.compose.QsPreviewHost
import com.android.systemui.qs.panels.ui.compose.QsPreviewTiles
import com.android.systemui.qs.panels.ui.compose.qsPreviewDrag
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTopBarActionViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridSnapshotViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface EditAction {
    data class AddTile(val tileSpec: TileSpec) : EditAction

    data class InsertTile(val tileSpec: TileSpec, val position: Int) : EditAction

    data class RemoveTile(val tileSpec: TileSpec) : EditAction

    data class SetTiles(val tileSpecs: List<TileSpec>) : EditAction

    data object ResetGrid : EditAction
}

/**
 * Edit mode is the open panel itself: a tap picks a tile, a hold moves it, its corner resizes it
 * and the plus tile adds one, with the customizer docked underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultEditTileGrid(
    listState: EditTileListState,
    allTiles: List<EditTileViewModel>,
    snapshotViewModel: InfiniteGridSnapshotViewModel,
    topBarActions: SnapshotStateList<EditTopBarActionViewModel>,
    qsStyleRepository: QsStyleRepository,
    modifier: Modifier = Modifier,
    onStopEditing: () -> Unit = {},
    onEditAction: (EditAction) -> Unit = {},
) {
    val currentListState by rememberUpdatedState(listState)
    val currentAction by rememberUpdatedState(onEditAction)
    val theme by rememberUpdatedState(LocalQsStyle.current)
    val scope = rememberCoroutineScope()
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val edge = with(LocalDensity.current) { EdgeFlipWidth.toPx() }
    var topBarBottom by remember { mutableFloatStateOf(0f) }
    var tapped by remember { mutableStateOf<TileSpec?>(null) }
    var tab by remember { mutableStateOf<CustomizerTab?>(null) }

    val preview = remember {
        QsPreviewTiles().also { p ->
            var edgeJob: Job? = null
            var edgeDirection = 0
            var lastPointer = Offset.Unspecified
            var settleJob: Job? = null
            fun settle() {
                settleJob?.cancel()
                p.settling = true
                settleJob =
                    scope.launch {
                        delay(SettleMillis)
                        if (p.dragging == null) p.settling = false
                    }
            }
            fun retarget() {
                val moving = p.dragging ?: return
                val (target, after) = p.targetFor(moving, lastPointer) ?: return
                val order = p.order.toMutableList()
                if (!order.remove(moving)) return
                val at = order.indexOf(target) + (if (after) 1 else 0)
                order.add(at.coerceIn(0, order.size), moving)
                if (order != p.order) p.order = order
            }
            p.host =
                object : QsPreviewHost {
                    override fun onTap(spec: TileSpec) {
                        tapped = spec
                    }

                    override fun onAdd() {
                        tapped = null
                        tab = CustomizerTab.Add
                    }

                    override fun onDragStart(spec: TileSpec) {
                        p.order = currentListState.tileSpecs()
                        p.dragging = spec
                        p.removeArmed = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        settle()
                    }

                    override fun onDragTo(pointer: Offset) {
                        val moving = p.dragging ?: return
                        lastPointer = pointer
                        p.dragCorner = pointer - p.grab
                        p.removeArmed = pointer.y < topBarBottom
                        val pager = p.pager
                        val direction =
                            when {
                                p.removeArmed -> 0
                                pointer.x < pager.left + edge && p.canFlip(-1) -> -1
                                pointer.x > pager.right - edge && p.canFlip(1) -> 1
                                else -> 0
                            }
                        if (direction != edgeDirection) {
                            edgeJob?.cancel()
                            edgeDirection = direction
                            edgeJob =
                                if (direction == 0) {
                                    null
                                } else {
                                    scope.launch {
                                        while (p.canFlip(direction)) {
                                            delay(EdgeFlipDelayMillis)
                                            p.flips.trySend(direction)
                                            delay(EdgeFlipPauseMillis)
                                            retarget()
                                        }
                                    }
                                }
                        }
                        val onPage = p.bounds[moving]?.let { pager.contains(it.center) } == true
                        if ((direction != 0 && onPage) || p.removeArmed) return
                        retarget()
                    }

                    override fun onDragEnd(cancelled: Boolean) {
                        val moving = p.dragging ?: return
                        edgeJob?.cancel()
                        edgeJob = null
                        edgeDirection = 0
                        val remove = p.removeArmed && !cancelled
                        val order = p.order
                        p.dragging = null
                        p.dragCorner = Offset.Unspecified
                        p.removeArmed = false
                        settle()
                        when {
                            remove -> currentAction(EditAction.RemoveTile(moving))
                            cancelled -> p.order = currentListState.tileSpecs()
                            order != currentListState.tileSpecs() ->
                                currentAction(EditAction.SetTiles(order))
                        }
                        if (remove) {
                            if (tapped == moving) tapped = null
                        } else if (tapped != null) {
                            tapped = moving
                        }
                    }

                    override fun onResize(spec: TileSpec, columns: Int, rows: Int) {
                        settle()
                        qsStyleRepository.editTweaks {
                            it.resized(spec.spec, theme, columns, rows, currentListState.columns)
                        }
                    }
                }
        }
    }

    val specs = listState.tileSpecs()
    val tappedTile = allTiles.find { it.tileSpec == tapped }?.takeIf { it.tileSpec in specs }
    SideEffect { preview.selected = tappedTile?.tileSpec }
    LaunchedEffect(specs) { if (preview.dragging == null) preview.order = specs }

    val undoAction: @Composable RowScope.() -> Unit = {
        AnimatedVisibility(snapshotViewModel.canUndo, enter = fadeIn(), exit = fadeOut()) {
            IconButton(
                enabled = snapshotViewModel.canUndo,
                onClick = snapshotViewModel::undo,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = LocalAndroidColorScheme.current.surfaceEffect1,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Icon(
                    Undo,
                    contentDescription = stringResource(id = com.android.internal.R.string.undo),
                )
            }
        }
    }
    val resetAction: @Composable RowScope.() -> Unit = {
        IconButton(
            onClick = { onEditAction(EditAction.ResetGrid) },
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.qs_custom_reset_tiles),
            )
        }
    }

    // The bar stays on its compact row, so the panel keeps the room.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    LaunchedEffect(scrollBehavior) {
        snapshotFlow { scrollBehavior.state.heightOffsetLimit }
            .collect { scrollBehavior.state.heightOffset = it }
    }
    Scaffold(
        modifier =
            modifier
                .consumeWindowInsets(WindowInsets.displayCutout)
                .sysuiResTag(EDIT_MODE_ROOT_TEST_TAG),
        containerColor = Color.Transparent,
        topBar = {
            EditModeExpandableTopBar(
                onStopEditing = onStopEditing,
                subtitle = {
                    Text(
                        text = stringResource(R.string.qs_custom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier =
                    Modifier.statusBarsPadding().onGloballyPositioned {
                        topBarBottom = it.boundsInRoot().bottom
                    },
                scrollBehavior = scrollBehavior,
                collapsibleActions = topBarActions,
                dragging = preview.dragging != null,
                removeArmed = preview.removeArmed,
                actions = {
                    undoAction()
                    resetAction()
                },
            )
        },
        bottomBar = {
            QsCustomizerDock(
                repository = qsStyleRepository,
                tile = tappedTile,
                allTiles = allTiles,
                columns = listState.columns,
                tab = tab,
                onTabChange = { tab = it },
                onRemoveTile = { tile ->
                    tapped = null
                    onEditAction(EditAction.RemoveTile(tile.tileSpec))
                },
                onCloseTile = { tapped = null },
                onAddTile = { spec ->
                    onEditAction(EditAction.AddTile(spec))
                    preview.reveal = spec
                },
            )
        },
    ) { innerPadding ->
        val panel = LocalQsEditPreview.current ?: return@Scaffold
        // A theme change relays the panel out at once; it fades in instead of tumbling.
        val style = LocalQsStyle.current
        val fade = remember { Animatable(1f) }
        var shownStyle by remember { mutableStateOf(style) }
        LaunchedEffect(style) {
            if (style != shownStyle) {
                shownStyle = style
                fade.snapTo(0.3f)
                fade.animateTo(1f, tween(ThemeFadeMillis))
            }
        }
        var area by remember { mutableStateOf<LayoutCoordinates?>(null) }
        CompositionLocalProvider(LocalQsPreviewTiles provides preview) {
            QsFitToHeight(
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .graphicsLayer { alpha = fade.value }
                    .onGloballyPositioned { area = it }
                    .qsPreviewDrag(preview) { area },
                panel,
            )
        }
    }
}

/**
 * The tile under [pointer] and whether [moving] goes after it. In free space that is the last
 * tile in order that sits before the pointer, or the first tile of the page to go before.
 */
private fun QsPreviewTiles.targetFor(moving: TileSpec, pointer: Offset): Pair<TileSpec, Boolean>? {
    val onPage =
        bounds.entries.filter { (spec, rect) -> spec != moving && pager.contains(rect.center) }
    onPage
        .firstOrNull { it.value.contains(pointer) }
        ?.let {
            return it.key to (pointer.x > it.value.center.x)
        }
    onPage
        .filter { (_, rect) ->
            rect.bottom <= pointer.y || (rect.top <= pointer.y && rect.right <= pointer.x)
        }
        .maxByOrNull { order.indexOf(it.key) }
        ?.let {
            return it.key to true
        }
    return onPage.minByOrNull { order.indexOf(it.key) }?.let { it.key to false }
}

private val EdgeFlipWidth = 24.dp
private const val EdgeFlipDelayMillis = 450L
private const val EdgeFlipPauseMillis = 500L
/** How long the tiles keep gliding after a drop or a resize. */
private const val SettleMillis = 500L
private const val ThemeFadeMillis = 240
private const val EDIT_MODE_ROOT_TEST_TAG = "EditModeRoot"
