/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.remedia.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.QsStyle

/** Media card look for the QS style: a plain card with the artwork as a thumbnail. */
internal data class MediaCardLook(
    val background: Color,
    val border: Color?,
    val radius: Dp,
    val text: Color,
    val accent: Color,
    val onAccent: Color,
    val playButton: Color,
    val playIcon: Color,
    val playBorder: Color?,
    val artShape: Shape,
    val translucent: Boolean = false,
)

/** True for the lockscreen card, where see-through styles get a dark base over the wallpaper. */
val LocalMediaOnLockscreen = staticCompositionLocalOf { false }

private val LockscreenBase = Color(0x9E0E0F11)

/** QS card layout from the canvas. The lockscreen keeps the stock rows. */
internal enum class QsMediaShape(val height: Dp) {
    /** Artwork, title and play button over a thin progress line. */
    Card(108.dp),
    /** One short row with round artwork. */
    Pill(60.dp),
    /** Artwork on top, title and play button below. Sits next to the Circles sliders. */
    Tall(172.dp),
}

/** True for the collapsed QS card, which stays short. */
internal val LocalMediaInQqs = staticCompositionLocalOf { false }

/** The QS layout for the current style, or null for the stock rows. */
@Composable
@ReadOnlyComposable
internal fun qsMediaShape(): QsMediaShape? {
    if (LocalMediaOnLockscreen.current) return null
    return when (LocalQsStyle.current) {
        QsStyle.STOCK -> null
        QsStyle.COMPACT -> QsMediaShape.Pill
        QsStyle.CIRCLES -> if (LocalMediaInQqs.current) QsMediaShape.Card else QsMediaShape.Tall
        else -> QsMediaShape.Card
    }
}

/** The look for the current QS style, or null for the stock album art card. */
@Composable
@ReadOnlyComposable
internal fun mediaCardLook(): MediaCardLook? {
    val scheme = MaterialTheme.colorScheme
    val surface = LocalAndroidColorScheme.current.surfaceEffect1
    val art = RoundedCornerShape(14.dp)
    val lock = LocalMediaOnLockscreen.current
    return when (LocalQsStyle.current) {
        QsStyle.STOCK -> null
        QsStyle.REMIX ->
            MediaCardLook(
                background = Color(0xFF1E2024),
                border = null,
                radius = if (lock) 28.dp else 22.dp,
                text = Color(0xFFEDEEF0),
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = colorResource(android.R.color.system_accent1_600),
                playIcon = Color.White,
                playBorder = null,
                artShape = art,
            )
        QsStyle.COMPACT ->
            MediaCardLook(
                background = Color.White.copy(alpha = .09f),
                border = null,
                radius = if (lock) 32.dp else 30.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = scheme.primary,
                playIcon = scheme.onPrimary,
                playBorder = null,
                artShape = CircleShape,
                translucent = true,
            )
        QsStyle.GLASS ->
            MediaCardLook(
                background = Color.White.copy(alpha = .035f),
                border = Color.White.copy(alpha = .13f),
                radius = if (lock) 24.dp else 22.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = scheme.primary.copy(alpha = .2f),
                playIcon = scheme.primary,
                playBorder = scheme.primary,
                artShape = art,
                translucent = true,
            )
        QsStyle.CLASSIC ->
            MediaCardLook(
                background = Color.White.copy(alpha = .06f),
                border = null,
                radius = 16.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = Color.Transparent,
                playIcon = scheme.primary,
                playBorder = scheme.primary,
                artShape = RoundedCornerShape(10.dp),
                translucent = true,
            )
        QsStyle.MONO ->
            MediaCardLook(
                background = Color(0xFF161616),
                border = null,
                radius = 18.dp,
                text = Color(0xFFEDEDED),
                accent = Color(0xFFF2F2F2),
                onAccent = Color(0xFF111111),
                playButton = Color(0xFFF2F2F2),
                playIcon = Color(0xFF111111),
                playBorder = null,
                artShape = RoundedCornerShape(12.dp),
            )
        // Circles and Cards.
        else ->
            MediaCardLook(
                background = surface,
                border = null,
                radius = 26.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = scheme.primary,
                playIcon = scheme.onPrimary,
                playBorder = null,
                artShape = art,
                translucent = true,
            )
    }
}

/** The card fill that replaces the album art background. */
@Composable
internal fun StyledCardBackground(look: MediaCardLook, modifier: Modifier = Modifier) {
    val shape = remember(look.radius) { RoundedCornerShape(look.radius) }
    val base =
        if (look.translucent && LocalMediaOnLockscreen.current) LockscreenBase else Color.Transparent
    Box(
        modifier =
            modifier.clip(shape).background(base).background(look.background).let {
                if (look.border != null) it.border(1.5.dp, look.border, shape) else it
            }
    )
}

/** The artwork, shown small next to the title. */
@Composable
internal fun ArtThumbnail(image: Icon?, shape: Shape, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(image) { (image as? Icon.Loaded)?.asImageBitmap() }
    Box(modifier = modifier.size(size).clip(shape).background(Color.White.copy(alpha = .1f))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
