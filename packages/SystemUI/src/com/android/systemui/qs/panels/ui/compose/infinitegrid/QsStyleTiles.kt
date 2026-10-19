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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
        QsStyle.NEON -> look(64, 20, 40, 22, 22, type.titleSmall, type.labelMedium)
        QsStyle.BENTO ->
            look(80, 26, 46, 24, 22, type.titleSmall.copy(fontWeight = FontWeight.Bold),
                type.labelMedium)
        QsStyle.SHAPES -> look(88, 20, 0, 0, 24)
        QsStyle.BLUEPRINT -> look(56, 10, 36, 20, 20, BlueprintLabel, BlueprintSecondary)
        // The toggle is Duo's whole left segment, as tall as the tile.
        QsStyle.DUO -> look(60, 20, 60, 22, 22, type.titleSmall, type.labelMedium)
        QsStyle.SOFT -> look(64, 22, 40, 22, 22, type.titleSmall, type.labelMedium)
    }
}

private val BlueprintLabel =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = .8.sp,
    )
private val BlueprintSecondary = BlueprintLabel.copy(fontWeight = FontWeight.Normal, fontSize = 10.5.sp)

/** Soft: tiles share the panel's color and stand out by their shadows. */
internal val SoftSurface = Color(0xFF1C1716)
private val SoftDark = Color(0xFF0B0908)
private val SoftLight = Color(0xFF2E2725)
private val DuoSegment = Color.White.copy(alpha = .07f)

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
    val vivid = colorResource(android.R.color.system_primary_dark)
    val vividInk = colorResource(android.R.color.system_on_primary_dark)
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
            QsStyle.NEON ->
                if (active) {
                    TileColors(
                        background = vivid.copy(alpha = .1f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = vivid,
                        icon = vivid,
                    )
                } else {
                    TileColors(
                        background = Color.White.copy(alpha = .03f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.onSurfaceVariant,
                        icon = scheme.onSurface.copy(alpha = .7f),
                    )
                }
            QsStyle.BENTO ->
                if (active) {
                    TileColors(vivid, vividInk.copy(alpha = .14f), vividInk, vividInk.copy(alpha = .8f),
                        vividInk)
                } else {
                    TileColors(surface, Color.White.copy(alpha = .08f), scheme.onSurface,
                        scheme.onSurfaceVariant, scheme.onSurface)
                }
            QsStyle.SHAPES ->
                TileColors(
                    background = Color.Transparent,
                    iconBackground = if (active) vivid else surface,
                    label = scheme.onSurface,
                    secondaryLabel = scheme.onSurfaceVariant,
                    icon = if (active) vividInk else scheme.onSurface,
                )
            QsStyle.BLUEPRINT ->
                TileColors(
                    background = if (active) vivid.copy(alpha = .08f) else Color.Transparent,
                    iconBackground = Color.Transparent,
                    label = scheme.onSurface,
                    secondaryLabel = if (active) vivid else scheme.onSurfaceVariant,
                    icon = if (active) vivid else scheme.onSurface.copy(alpha = .7f),
                )
            QsStyle.DUO -> {
                val segment = if (active) vivid else Color.White.copy(alpha = .1f)
                TileColors(
                    // A large tile is two segments over nothing; a small one is one segment.
                    background = if (iconOnly) segment else Color.Transparent,
                    iconBackground = if (iconOnly) Color.Transparent else segment,
                    label = scheme.onSurface,
                    secondaryLabel = if (active) vivid else scheme.onSurfaceVariant,
                    icon = if (active) vividInk else scheme.onSurface,
                )
            }
            QsStyle.SOFT ->
                TileColors(
                    background = SoftSurface,
                    iconBackground = Color.Transparent,
                    label = scheme.onSurface,
                    secondaryLabel = if (active) vivid else scheme.onSurfaceVariant,
                    icon = if (active) vivid else scheme.onSurface.copy(alpha = .75f),
                )
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

/** Liquid Glass: lets the blurred shade show through the tile. */
internal fun TileColors.glassy(glass: QsGlass, active: Boolean): TileColors {
    val alpha =
        when (glass) {
            QsGlass.SOLID -> return this
            QsGlass.FROSTED -> if (active) .86f else .55f
            QsGlass.LIQUID -> if (active) .72f else .24f
        }
    return copy(
        background = background.copy(alpha = background.alpha * alpha),
        iconBackground = iconBackground.copy(alpha = iconBackground.alpha * alpha),
    )
}

/** Liquid Glass: a lit rim and a soft sheen, where light catches the glass. */
internal fun Modifier.liquidRim(shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val rim =
        Brush.linearGradient(
            0f to Color.White.copy(alpha = .6f),
            .45f to Color.White.copy(alpha = .06f),
            1f to Color.White.copy(alpha = .24f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    val sheen =
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = .14f), Color.Transparent),
            center = Offset(size.width * .2f, 0f),
            radius = maxOf(size.width, size.height) * .8f,
        )
    // The tile clips to its shape, so half the stroke shows.
    val stroke = Stroke(width = 2.dp.toPx())
    onDrawWithContent {
        drawOutline(outline, sheen)
        drawContent()
        drawOutline(outline, rim, style = stroke)
    }
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

/** Neon: a tight accent glow around the tile while on. */
@Composable
internal fun Modifier.neonGlow(active: Boolean, radius: Dp): Modifier {
    val glow by animateFloatAsState(if (active) 1f else 0f, label = "QSTileNeonGlow")
    val glowColor = colorResource(android.R.color.system_primary_dark)
    return drawBehind {
        if (glow > 0f) {
            drawIntoCanvas { canvas ->
                val paint =
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = glowColor.copy(alpha = .55f * glow).toArgb()
                        maskFilter = BlurMaskFilter(10.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                    }
                val r = radius.toPx()
                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            }
        }
    }
}

/** Neon and Blueprint: an outline, accent while on. */
@Composable
internal fun Modifier.styleOutline(active: Boolean, shape: Shape, off: Color, width: Dp): Modifier {
    val color by
        animateColorAsState(
            if (active) colorResource(android.R.color.system_primary_dark) else off,
            label = "QSTileStyleOutline",
        )
    return border(width, color, shape)
}

/** Soft: the tile casts a dark shadow down and a light one up while raised (off). */
@Composable
internal fun Modifier.softRaise(pressed: Boolean, shape: Shape): Modifier {
    val raise by animateFloatAsState(if (pressed) 0f else 1f, label = "QSTileSoftRaise")
    return drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            .asAndroidPath()
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(7.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
        val offset = 4.dp.toPx()
        onDrawBehind {
            if (raise <= 0f) return@onDrawBehind
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                paint.color = SoftDark.copy(alpha = .9f * raise).toArgb()
                native.save()
                native.translate(offset, offset)
                native.drawPath(path, paint)
                native.restore()
                paint.color = SoftLight.copy(alpha = raise).toArgb()
                native.save()
                native.translate(-offset * .8f, -offset * .8f)
                native.drawPath(path, paint)
                native.restore()
            }
        }
    }
}

/** Soft: shadows inside the tile's edges while pressed (on). Draws under the content. */
@Composable
internal fun Modifier.softInset(pressed: Boolean, shape: Shape): Modifier {
    val press by animateFloatAsState(if (pressed) 1f else 0f, label = "QSTileSoftInset")
    return drawWithCache {
        val tile = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            .asAndroidPath()
        // Everything around the tile; blurred and shifted, its edge shades the inside.
        val frame =
            android.graphics.Path().apply {
                val grow = 24.dp.toPx()
                addRect(-grow, -grow, size.width + grow, size.height + grow,
                    android.graphics.Path.Direction.CW)
                op(tile, android.graphics.Path.Op.DIFFERENCE)
            }
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(6.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
        val offset = 4.dp.toPx()
        onDrawWithContent {
            if (press > 0f) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.clipPath(tile)
                    paint.color = SoftDark.copy(alpha = press).toArgb()
                    native.save()
                    native.translate(offset, offset)
                    native.drawPath(frame, paint)
                    native.restore()
                    paint.color = SoftLight.copy(alpha = .8f * press).toArgb()
                    native.translate(-offset * .8f, -offset * .8f)
                    native.drawPath(frame, paint)
                    native.restore()
                }
            }
            drawContent()
        }
    }
}

/** Lobes and depth of the shape an on tile takes in Shapes, picked per tile. */
private val ShapeLobes = listOf(8 to .07f, 4 to .13f, 6 to .09f, 12 to .045f, 5 to .1f)

/** A circle whose edge bulges into [lobes] soft lobes, [depth] of its radius deep. */
internal fun blobPath(width: Float, height: Float, lobes: Int, depth: Float, path: Path) {
    path.reset()
    val cx = width / 2
    val cy = height / 2
    val base = minOf(cx, cy) / (1 + depth)
    val steps = 96
    for (i in 0 until steps) {
        val angle = i * 2 * PI / steps
        val r = base * (1 + depth * cos(lobes * angle)).toFloat()
        val x = cx + r * sin(angle).toFloat()
        val y = cy - r * cos(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** [blobPath] as a shape, for clipping. */
internal class BlobShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Generic(Path().also { blobPath(size.width, size.height, lobes, depth, it) })
}

/** Shapes: a circle that grows lobes while on, with its label under it. */
@Composable
internal fun ShapeTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    active: Boolean,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val (lobes, depth) = ShapeLobes[Math.floorMod(label.hashCode(), ShapeLobes.size)]
    val morph by animateFloatAsState(if (active) 1f else 0f, label = "QSTileShapeMorph")
    val fill by animateColorAsState(colors.iconBackground, label = "QSTileShapeColor")
    val path = remember { Path() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                iconModifier.size(58.dp).drawBehind {
                    blobPath(size.width, size.height, lobes, depth * morph, path)
                    drawPath(path, fill)
                },
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

/**
 * Duo: a large tile split in two, the icon segment on the left (the toggle on dual target tiles)
 * and the labels on the right.
 */
@Composable
internal fun DuoTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    look: TileLook,
    accessibilityUiState: AccessibilityUiState?,
    toggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val segment by animateColorAsState(colors.iconBackground, label = "QSTileDuoSegment")
    val r = look.activeRadius
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.fillMaxHeight()
                    .width(look.toggleSize)
                    .clip(RoundedCornerShape(r, 6.dp, 6.dp, r))
                    .drawBehind { drawRect(segment) }
                    .then(
                        if (toggleClick == null) {
                            Modifier
                        } else {
                            Modifier.combinedClickable(
                                    onClick = toggleClick,
                                    onLongClick = onLongClick,
                                    hapticFeedbackEnabled = false,
                                )
                                .semantics {
                                    accessibilityUiState?.let {
                                        contentDescription = it.contentDescription
                                        stateDescription = it.stateDescription
                                        it.toggleableState?.let { state -> toggleableState = state }
                                    }
                                    role = Role.Switch
                                }
                        }
                    ),
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { look.largeIconSize },
                modifier = iconModifier,
            )
        }
        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
            labelStyle = look.labelStyle,
            secondaryLabelStyle = look.secondaryLabelStyle,
            modifier =
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(6.dp, r, r, 6.dp))
                    .background(DuoSegment)
                    .padding(horizontal = 12.dp),
        )
    }
}
