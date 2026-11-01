/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.android.systemui.qs.pipeline.shared.TileSpec

/** The open panel, for the customizer in edit mode to show its changes on. */
val LocalQsEditPreview = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** The tiles inside that preview, where tapping one picks it instead of using it. */
class QsPreviewTiles(val selected: TileSpec?, val onTap: (TileSpec) -> Unit)

val LocalQsPreviewTiles = compositionLocalOf<QsPreviewTiles?> { null }

/** Covers [content], so a tap does [onTap] and not what it does in the panel. */
@Composable
fun QsPreviewCover(
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box {
        content()
        Box(
            Modifier.matchParentSize()
                .then(modifier)
                .clickable(interactionSource = null, indication = null, onClick = onTap)
        )
    }
}

/** Shows [content] at its real size, shrunk only as far as it takes to fit the height. */
@Composable
fun QsFitToHeight(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val placeable =
            measurables
                .single()
                .measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scale = if (placeable.height > height) height / placeable.height.toFloat() else 1f
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(.5f, 0f)
            }
        }
    }
}
