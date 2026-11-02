/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val STREAM = AudioManager.STREAM_MUSIC
private const val FILL_ALPHA = 0.22f

/**
 * Shows the media volume as a fill across the tile, under its icon and text, and changes it on a
 * sideways drag. [stateKey] changes with the tile's state, which follows the system volume.
 */
@Composable
internal fun volumeLevelModifier(stateKey: String, color: Color): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(audio) { audio.getStreamMaxVolume(STREAM).coerceAtLeast(1) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(STREAM)) }
    var dragging by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(level.toFloat() / max, label = "QSTileVolumeLevel")
    val fill = color.copy(alpha = FILL_ALPHA)

    LaunchedEffect(stateKey, dragging) {
        if (!dragging) level = audio.getStreamVolume(STREAM)
    }

    fun change(next: Int) {
        if (next == level) return
        level = next
        audio.setStreamVolume(STREAM, next, 0)
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    return Modifier.semantics {
            progressBarRangeInfo =
                ProgressBarRangeInfo(level.toFloat(), 0f..max.toFloat(), (max - 1).coerceAtLeast(0))
            setProgress { value ->
                change(value.roundToInt().coerceIn(0, max))
                true
            }
        }
        .drawBehind {
            val width = size.width * fraction
            drawRect(
                color = fill,
                topLeft = Offset(if (rtl) size.width - width else 0f, 0f),
                size = Size(width, size.height),
            )
        }
        .pointerInput(audio, max, rtl) {
            var exact = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    exact = level.toFloat()
                    dragging = true
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { input, dx ->
                input.consume()
                // A small tile still gets a usable throw.
                val span = maxOf(size.width.toFloat(), 180.dp.toPx())
                exact = (exact + (if (rtl) -dx else dx) / span * max).coerceIn(0f, max.toFloat())
                change(exact.roundToInt())
            }
        }
}
