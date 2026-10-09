/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.graphics.BlurMaskFilter
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.text.TextUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState

/** Sizes of one tile in a [QsStyle]. */
internal data class TileLook(
    val height: Dp,
    val activeRadius: Dp,
    val inactiveRadius: Dp,
    val toggleSize: Dp,
    val largeIconSize: Dp,
    val smallIconSize: Dp,
    val labelStyle: TextStyle?,
    val secondaryLabelStyle: TextStyle?,
)

@Composable
@ReadOnlyComposable
internal fun tileLook(style: QsStyle): TileLook {
    val type = MaterialTheme.typography
    fun look(h: Int, r: Int, toggle: Int, large: Int, small: Int, label: TextStyle? = null,
        secondary: TextStyle? = null) =
        TileLook(h.dp, r.dp, r.dp, toggle.dp, large.dp, small.dp, label, secondary)
    return when (style) {
        QsStyle.STOCK ->
            TileLook(
                CommonTileDefaults.TileHeight,
                CommonTileDefaults.ActiveTileCornerRadius,
                CommonTileDefaults.InactiveTileCornerRadius,
                CommonTileDefaults.ToggleTargetSize,
                CommonTileDefaults.LargeTileIconSize,
                CommonTileDefaults.SmallTileIconSize,
                null,
                null,
            )
        QsStyle.REMIX -> look(60, 20, 40, 20, 24, type.titleSmall, type.labelMedium)
        QsStyle.COMPACT ->
            look(48, 24, 36, 18, 20, type.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                type.labelSmall)
        QsStyle.GLASS -> look(64, 22, 44, 22, 24, type.titleSmall, type.labelMedium)
        QsStyle.MONO -> look(64, 18, 40, 20, 24, type.titleSmall, type.labelMedium)
        QsStyle.CIRCLES -> look(80, 26, 48, 22, 24, type.titleSmall, type.labelMedium)
        QsStyle.CLASSIC -> look(78, 16, 0, 0, 26)
        QsStyle.CARDS -> look(104, 26, 38, 20, 20)
    }
}

private val RemixOff = Color(0xFF1E2024)
private val RemixOffChip = Color(0xFF2B2E33)
private val RemixOffText = Color(0xFFE9EAEC)
private val RemixOffMuted = Color(0xFF9DA1A7)
private val MonoOn = Color(0xFFF2F2F2)
private val MonoOnMuted = Color(0xFF4A4A4A)
private val MonoInk = Color(0xFF111111)
private val MonoOff = Color(0xFF161616)
private val MonoOffChip = Color(0xFF232323)
private val MonoOffText = Color(0xFFEDEDED)
private val MonoOffMuted = Color(0xFF8A8A8A)

/** Tile colors for [style], or null to use the stock ones. */
@Composable
@ReadOnlyComposable
internal fun styledTileColors(style: QsStyle, uiState: TileUiState, iconOnly: Boolean): TileColors? {
    if (style == QsStyle.STOCK) return null
    val scheme = MaterialTheme.colorScheme
    val surface = LocalAndroidColorScheme.current.surfaceEffect1
    val active = uiState.visualState == STATE_ACTIVE
    val dualLarge = uiState.handlesToggleClick && !iconOnly
    val colors =
        when (style) {
            QsStyle.REMIX ->
                if (active) {
                    TileColors(
                        background = colorResource(android.R.color.system_accent1_600),
                        iconBackground = Color.White.copy(alpha = .22f),
                        label = Color.White,
                        secondaryLabel = Color.White.copy(alpha = .8f),
                        icon = Color.White,
                    )
                } else {
                    TileColors(RemixOff, RemixOffChip, RemixOffText, RemixOffMuted, RemixOffText)
                }
            QsStyle.GLASS ->
                if (active) {
                    TileColors(
                        background = scheme.primary.copy(alpha = .2f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.primary,
                        icon = scheme.primary,
                    )
                } else {
                    TileColors(
                        background = Color.White.copy(alpha = .035f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.onSurfaceVariant,
                        icon = scheme.onSurface,
                    )
                }
            QsStyle.MONO ->
                if (active) {
                    TileColors(MonoOn, MonoInk, MonoInk, MonoOnMuted, if (dualLarge) MonoOn else MonoInk)
                } else {
                    TileColors(MonoOff, MonoOffChip, MonoOffText, MonoOffMuted, MonoOffText)
                }
            QsStyle.CLASSIC ->
                TileColors(
                    background = Color.Transparent,
                    iconBackground = Color.Transparent,
                    label = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                    secondaryLabel = scheme.onSurfaceVariant,
                    icon = if (active) scheme.primary else scheme.onSurface.copy(alpha = .55f),
                )
            QsStyle.CIRCLES ->
                if (iconOnly) {
                    TileColors(
                        background = Color.Transparent,
                        iconBackground = if (active) scheme.primary else surface,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.onSurfaceVariant,
                        icon = if (active) scheme.onPrimary else scheme.onSurface,
                    )
                } else {
                    filled(active, surface)
                }
            // Compact and Cards: stock colors, but dual target tiles fill too.
            else -> filled(active, surface)
        }
    return when (uiState.visualState) {
        STATE_ACTIVE,
        STATE_INACTIVE -> colors
        else ->
            colors.copy(
                background =
                    if (colors.background.alpha == 0f) colors.background
                    else colors.background.copy(alpha = colors.background.alpha * .5f),
                label = colors.label.copy(alpha = .38f),
                secondaryLabel = colors.secondaryLabel.copy(alpha = .38f),
                icon = colors.icon.copy(alpha = .38f),
            )
    }
}

@Composable
@ReadOnlyComposable
private fun filled(active: Boolean, surface: Color): TileColors {
    val scheme = MaterialTheme.colorScheme
    return if (active) {
        TileColors(
            background = scheme.primary,
            iconBackground = scheme.onPrimary.copy(alpha = .14f),
            label = scheme.onPrimary,
            secondaryLabel = scheme.onPrimary.copy(alpha = .8f),
            icon = scheme.onPrimary,
        )
    } else {
        TileColors(
            background = surface,
            iconBackground = Color.White.copy(alpha = .1f),
            label = scheme.onSurface,
            secondaryLabel = scheme.onSurfaceVariant,
            icon = scheme.onSurface,
        )
    }
}

/** Glass: a soft accent glow around the tile while on. */
@Composable
internal fun Modifier.glassGlow(active: Boolean, radius: Dp): Modifier {
    val glow by animateFloatAsState(if (active) 1f else 0f, label = "QSTileGlassGlow")
    val glowColor = colorResource(android.R.color.system_accent1_500)
    return drawBehind {
        if (glow > 0f) {
            drawIntoCanvas { canvas ->
                val paint =
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = glowColor.copy(alpha = .45f * glow).toArgb()
                        maskFilter = BlurMaskFilter(14.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                    }
                val r = radius.toPx()
                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            }
        }
    }
}

/** Glass: the tile outline, accent while on. */
@Composable
internal fun Modifier.glassBorder(active: Boolean, shape: Shape): Modifier {
    val color by
        animateColorAsState(
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = .95f)
            else Color.White.copy(alpha = .13f),
            label = "QSTileGlassBorder",
        )
    return border(1.5.dp, color, shape)
}

/** Remix: on tiles fill with an accent gradient. */
@Composable
internal fun Modifier.remixFill(active: Boolean): Modifier {
    val fill by animateFloatAsState(if (active) 1f else 0f, label = "QSTileRemixFill")
    val start = colorResource(android.R.color.system_accent1_500)
    val end = colorResource(android.R.color.system_accent1_700)
    return drawBehind {
        if (fill > 0f) {
            drawRect(
                Brush.linearGradient(listOf(start, end), Offset.Zero, Offset(size.width, size.height)),
                alpha = fill,
            )
        }
    }
}

@Composable
private fun StyledLabel(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val animated by animateColorAsState(color, label = "QSTileStyledLabelColor")
    BasicText(
        text = text,
        style = style,
        color = { animated },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** Classic: a bare icon over its label. */
@Composable
internal fun ClassicTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize().padding(horizontal = 2.dp),
    ) {
        SmallTileContent(
            iconProvider = iconProvider,
            color = colors.icon,
            size = { iconSize },
            modifier = iconModifier,
        )
        StyledLabel(
            text = label,
            color = colors.label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** Circles: a round toggle with its label under it. */
@Composable
internal fun CircleTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val circle by animateColorAsState(colors.iconBackground, label = "QSTileCircleColor")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = iconModifier.size(56.dp).clip(CircleShape).drawBehind { drawRect(circle) },
        ) {
            SmallTileContent(iconProvider = iconProvider, color = colors.icon, size = { iconSize })
        }
        StyledLabel(
            text = label,
            color = colors.label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** Cards: an icon chip on top, the label and state at the bottom. */
@Composable
internal fun CardTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    look: TileLook,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val chip by animateColorAsState(colors.iconBackground, label = "QSTileCardChipColor")
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxSize().padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                iconModifier.size(look.toggleSize).clip(CircleShape).drawBehind { drawRect(chip) },
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { look.largeIconSize },
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            StyledLabel(
                text = label,
                color = colors.label,
                style = MaterialTheme.typography.titleSmall,
            )
            if (!TextUtils.isEmpty(secondaryLabel)) {
                StyledLabel(
                    text = secondaryLabel ?: "",
                    color = colors.secondaryLabel,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
