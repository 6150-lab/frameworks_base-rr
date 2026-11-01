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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.content.res.Resources
import android.os.Trace
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.internal.util.catalyxx.GlassSettings
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.mechanics.effects.VerticalTactileSurfaceRevealEffect
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.glass.ui.OwnGlass
import com.android.systemui.glass.ui.compose.OwnGlassShown
import com.android.systemui.glass.ui.compose.ownGlassRim
import com.android.systemui.glass.ui.compose.ownGlassSquish
import com.android.systemui.glass.ui.compose.ownGlassTouchPool
import com.android.systemui.glass.ui.compose.rememberOwnGlassTouch
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.shared.model.LocalQsGlass
import com.android.systemui.qs.panels.shared.model.LocalQsGlassTune
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsOffFill
import com.android.systemui.qs.panels.shared.model.QsOnFill
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTileDepth
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.Tooltip
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveIconCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveIconCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelMoreDetails
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.RrVolumeTile
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

private val TileViewModel.traceName
    get() = spec.toString().takeLast(Trace.MAX_SECTION_NAME_LEN)

/**
 * This composable function is responsible for rendering a tile based on the provided
 * [TileViewModel]. It handles different states of the tile (e.g., available, unavailable),
 * interactions (click, long click), and visual styles (icon only or large tile).
 *
 * @param tile The [TileViewModel] containing the data and logic for the tile.
 * @param iconOnly A boolean indicating whether to display only the icon of the tile or the full
 *   tile content (false for large tiles).
 * @param squishiness The float value representing the current squishiness factor of the tile, used
 *   for animations.
 * @param coroutineScope The [CoroutineScope] to launch coroutines for animations.
 * @param tileHapticsViewModelFactory A factory for creating a [TileHapticsViewModel] instance, used
 *   for haptic feedback.
 * @param modifier An optional [Modifier] to be applied to the root composable of the tile.
 * @param isVisible Whether the tile is currently visible. Defaults to true.
 * @param requestToggleTextFeedback A lambda function that is invoked when a toggleable icon only
 *   tile is clicked, used to request the feedback text.
 * @param detailsViewModel An optional [DetailsViewModel] used to handle navigation to a detailed
 *   view when a tile is clicked, if applicable.
 * @param enableRevealEffect If `true`, the tiles will animate using the reveal animation.
 * @param rows How many grid rows the tile spans.
 * @param height The tile's height when it spans more than one row.
 */
@Composable
fun ContentScope.Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    requestToggleTextFeedback: (TileSpec) -> Unit = {},
    detailsViewModel: DetailsViewModel?,
    enableRevealEffect: Boolean = false,
    rows: Int = 1,
    height: Dp? = null,
) {
    trace(tile.traceName) {
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val resources = resources()

        /*
         * Use produce state because [QSTile.State] doesn't have well defined equals (due to
         * inheritance). This way, even if tile.state changes, uiState may not change and lead to
         * recomposition.
         */
        val uiState by
            produceState(tile.currentState.toUiState(resources), tile, resources) {
                tile.state.collect { value = it.toUiState(resources) }
            }
        val isClickable = uiState.handlesMainClick

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        val tweaks = LocalQsTweaks.current
        val tileTweak = tweaks.tiles[tile.spec.spec]
        val volume = if (tile.spec.spec == RrVolumeTile.TILE_SPEC) rememberVolumeTile(tweaks) else null
        val paintState = if (volume != null) uiState.copy(visualState = STATE_INACTIVE) else uiState
        val shape = tileShapeStyle()
        val paint = tilePaintStyle()
        val glass = LocalQsGlass.current
        val tune = LocalQsGlassTune.current
        val look = tileLook(shape)
        val active = paintState.visualState == STATE_ACTIVE
        val ownColor = tileTweak?.color?.let { Color(it) }
        val tone = ownColor ?: tweaks.accent?.let { Color(it) }
        val layout = tileLayout(shape, tweaks.label, tileTweak?.hideLabel == true, iconOnly, rows)
        val chipFills =
            layout == TileLayout.Under && (shape == QsStyle.CIRCLES || shape == QsStyle.SHAPES)
        val chipShape =
            tweaks.iconBacking.chipShape(uiState.label)
                ?: CircleShape.takeIf {
                    active &&
                        !chipFills &&
                        tweaks.iconBacking == null &&
                        tweaks.onFill == QsOnFill.ICON
                }
        val colors =
            TileDefaults.getColorForState(paintState, iconOnly, paint, tone)
                .withFills(tweaks, paintState.visualState, tone, ownColor != null, chipFills)
                .withBacking(tweaks.iconBacking, active, tone)
                .glassy(glass, active, tune)
        val hapticsViewModel: TileHapticsViewModel =
            rememberViewModel(traceName = "TileHapticsViewModel") {
                tileHapticsViewModelFactory.create(tile)
            }

        // TODO(b/361789146): Draw the shapes instead of clipping
        val tileShape by TileDefaults.animateTileShapeAsState(paintState, look)
        // A picked fill replaces the theme's outline and glow.
        val repainted =
            if (active) tweaks.onFill != null || ownColor != null else tweaks.offFill != null
        val styleModifier =
            when {
                tweaks.glow != null ->
                    Modifier.tweakGlow(active, look.activeRadius, tweaks.glow, tone)
                repainted -> Modifier
                paint == QsStyle.GLASS -> Modifier.glassGlow(active, look.activeRadius, tone)
                paint == QsStyle.NEON -> Modifier.neonGlow(active, look.activeRadius, tone)
                else -> Modifier
            }.then(
                when {
                    tweaks.depth == QsTileDepth.FLAT -> Modifier
                    tweaks.depth == QsTileDepth.RAISED -> Modifier.softRaise(active, tileShape)
                    repainted -> Modifier
                    paint == QsStyle.SOFT -> Modifier.softRaise(active, tileShape)
                    else -> Modifier
                }
            )
        val see = glassAlphas(glass, tune)?.first ?: 1f
        val fillModifier =
            when {
                active && tweaks.onFill == QsOnFill.GRADIENT && !chipFills ->
                    Modifier.remixFill(true, see, tone)
                active && tweaks.onFill == QsOnFill.OUTLINE ->
                    Modifier.styleOutline(true, tileShape, Color.Transparent, 1.5.dp, tone)
                !active && tweaks.offFill == QsOffFill.OUTLINE ->
                    Modifier.styleOutline(false, tileShape, Color.White.copy(alpha = .18f), 1.dp)
                repainted -> Modifier
                paint == QsStyle.REMIX -> Modifier.remixFill(active, see, tone)
                // Liquid Glass draws its own rim.
                paint == QsStyle.GLASS && glass != QsGlass.LIQUID ->
                    Modifier.glassBorder(active, tileShape, tone)
                paint == QsStyle.NEON ->
                    Modifier.styleOutline(active, tileShape, Color.White.copy(alpha = .12f),
                        if (active) 1.5.dp else 1.dp, tone)
                paint == QsStyle.BLUEPRINT ->
                    Modifier.styleOutline(active, tileShape, Color.White.copy(alpha = .18f), 1.dp,
                        tone)
                paint == QsStyle.SOFT && tweaks.depth != QsTileDepth.FLAT ->
                    Modifier.softInset(active, tileShape)
                else -> Modifier
            }.then(
                if (glass == QsGlass.LIQUID) Modifier.liquidRim(tileShape, tune.rimStrength)
                else Modifier
            )
        val levelModifier =
            if (volume != null) volumeLevelModifier(volume, tweaks, iconOnly, rows) else Modifier
        val animatedColor by animateColorAsState(colors.background, label = "QSTileBackgroundColor")
        // Catalyxx glass: the tile's rim catches the light, and a finger lights it up
        OwnGlassShown(GlassSettings.PLACE_SHADE, isVisible)
        val glassTouch = rememberOwnGlassTouch()
        val isDualTarget = uiState.handlesToggleClick
        // A tile that only shows its icon toggles on tap, whatever its size.
        val tapToggles = iconOnly || layout == TileLayout.Icon
        val interactionSource = remember { MutableInteractionSource() }
        // Bounce the tile's container if it is toggleable and is not a large
        // dual target tile. These don't toggle on main click.
        val bounceContainer = uiState.isToggleable && (tapToggles || !isDualTarget)
        // Catalyxx glass: the tile squishes while it, or its toggle, is pressed. A tile that bounces
        // its container shares the bounce's presses; the others get their own, so they still don't
        // bounce.
        val glassPress = remember { MutableInteractionSource() }
        val pressSource =
            if (bounceContainer && volume == null) interactionSource else glassPress
        val view = LocalView.current

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.VerticalPadding.toPx() }

            val animatedCornerRadius by animateDpAsState(TileDefaults.tileRadius(uiState, look))

            val inactiveCornerRadius = InactiveIconCornerRadius
            surfaceRevealModifier =
                Modifier.verticalTactileSurfaceReveal(
                    deltaY = marginBottom,
                    effectSpec =
                        remember(inactiveCornerRadius) {
                            VerticalTactileSurfaceRevealEffect(
                                maxCornerSize = { animatedCornerRadius },
                                phase1MarginX = inactiveCornerRadius,
                            )
                        },
                    label = tile.traceName,
                )

            contentRevealModifier =
                Modifier.verticalFadeContentReveal(deltaY = marginBottom, label = tile.traceName)
        } else {
            surfaceRevealModifier = Modifier
            contentRevealModifier = Modifier
        }

        val expandable =
            if (dynamicTargetResolutionEnabled()) tile.expandable
            else remember { Expandable(mutableSetOf()) }
        Tooltip(
            text = uiState.label,
            modifier = modifier,
            enabled = Flags.enableQsTileTooltips(),
        ) { modifier ->
            TileExpandable(
                expandable = expandable,
                color = { animatedColor },
                shape = tileShape,
                squishiness = squishiness,
                hapticsViewModel = hapticsViewModel,
                glassRim = Modifier.ownGlassRim(GlassSettings.PLACE_SHADE, tileShape, glassTouch),
                modifier =
                    modifier
                        .then(surfaceRevealModifier)
                        .then(styleModifier)
                        .borderOnFocus(
                            color = MaterialTheme.colorScheme.secondary,
                            tileShape.topEnd,
                        )
                        .sysuiResTag("tile_expandable")
                        .fillMaxWidth()
                        .bounceable(
                            currentBounceableInfo.bounceable,
                            currentBounceableInfo.previousTile,
                            currentBounceableInfo.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo.bounceEnd,
                            interactionSource = interactionSource,
                        )
                        .ownGlassSquish(GlassSettings.PLACE_SHADE, pressSource),
            ) { expandable ->
                // Use main click on long press for small, available dual target tiles.
                // Open settings otherwise.
                val useLongClickToSettings = !(tapToggles && isDualTarget && isClickable)
                val longClick: (() -> Unit)? =
                    {
                            hapticsViewModel.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                            )

                            if (useLongClickToSettings) {
                                tile.settingsClick(expandable)
                            } else {
                                val hasDetails =
                                    QsDetailedView.isEnabled &&
                                        detailsViewModel?.onTileClicked(tile.spec) == true
                                if (!hasDetails) {
                                    tile.mainClick(expandable)
                                }
                            }
                        }
                        .takeIf { !useLongClickToSettings || uiState.handlesSettingsClick }

                TileContainer(
                    interactionSource = pressSource.takeIf { volume == null },
                    onClick = onClick@{
                            if (volume != null) {
                                volume.onIcon()
                                hapticsViewModel.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                                return@onClick
                            }
                            if (!isClickable) return@onClick
                            // Catalyxx glass: Android buzzes for a toggle itself
                            if (!bounceContainer) {
                                OwnGlass.tapBuzz(view, GlassSettings.PLACE_SHADE)
                            }

                            if (tapToggles && isDualTarget) {
                                tile.toggleClick()
                            } else {
                                val hasDetails =
                                    QsDetailedView.isEnabled &&
                                        detailsViewModel?.onTileClicked(tile.spec) == true
                                if (hasDetails) return@onClick

                                // For those tile's who doesn't have a detailed view, process with
                                // their `onClick` behavior.
                                tile.mainClick(expandable)
                            }

                            // Side effects of the click
                            hapticsViewModel.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.CLICKED
                            )

                            coroutineScope.launch {
                                // Bounce the content of the tile if we're not animating the
                                // container.
                                if (!bounceContainer) {
                                    currentBounceableInfo.bounceable.animateContentBounce(tapToggles)
                                }
                            }
                            if (uiState.isToggleable && tapToggles) {
                                // And show footer text feedback for icons
                                requestToggleTextFeedback(tile.spec)
                            }
                        },
                    onLongClick = longClick,
                    accessibilityUiState = uiState.accessibilityUiState,
                    iconOnly = tapToggles,
                    isDualTarget = isDualTarget,
                    height = height ?: look.height,
                    // The light under a finger goes behind the icon and label
                    modifier =
                        fillModifier
                            .then(contentRevealModifier)
                            .then(levelModifier)
                            .ownGlassTouchPool(GlassSettings.PLACE_SHADE, tileShape, glassTouch),
                ) {
                    val iconProvider: Context.() -> Icon = {
                        tileTweak?.icon?.let { name ->
                            QsTileIcons[name]?.let { Icon.Resource(it, null) }
                        } ?: getTileIcon(icon = icon)
                    }
                    val iconBounce =
                        Modifier.bounceScale { currentBounceableInfo.bounceable.iconBounceScale }
                    val label = if (look.caps) uiState.label.uppercase() else uiState.label
                    val secondaryLabel =
                        when {
                            !tweaks.secondLine -> ""
                            look.caps -> uiState.secondaryLabel.uppercase()
                            else -> uiState.secondaryLabel
                        }
                    val secondaryClick: (() -> Unit)? =
                        {
                                hapticsViewModel.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                                tile.toggleClick()
                            }
                            .takeIf { isDualTarget && !tapToggles }
                    if (volume != null) {
                        VolumeTileContent(volume, colors, look, paint, iconOnly, rows, longClick)
                    } else when (layout) {
                        TileLayout.Under ->
                            when {
                                chipShape != null ->
                                    BackedUnderTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        shape = chipShape,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                                shape == QsStyle.SHAPES && tweaks.iconBacking == null ->
                                    ShapeTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        active = active,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                                shape == QsStyle.CIRCLES && tweaks.iconBacking == null ->
                                    CircleTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                                else ->
                                    ClassicTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                            }
                        TileLayout.Stack ->
                            CardTileContent(
                                label = label,
                                secondaryLabel = secondaryLabel,
                                iconProvider = iconProvider,
                                colors = colors,
                                look = look,
                                iconModifier = iconBounce,
                                chipShape = chipShape ?: CircleShape,
                                toggleClick = secondaryClick,
                                onLongClick = longClick,
                            )
                        TileLayout.Duo ->
                            DuoTileContent(
                                label = label,
                                secondaryLabel = secondaryLabel,
                                iconProvider = iconProvider,
                                colors = colors,
                                look = look,
                                accessibilityUiState = uiState.accessibilityUiState,
                                toggleClick = secondaryClick,
                                onLongClick = longClick,
                                iconModifier = iconBounce,
                            )
                        TileLayout.Icon ->
                            if (chipShape != null) {
                                BackedIcon(
                                    iconProvider = iconProvider,
                                    colors = colors,
                                    shape = chipShape,
                                    chipSize = minOf(look.smallIconSize + 22.dp, look.height - 8.dp),
                                    iconSize = look.smallIconSize,
                                    modifier = Modifier.align(Alignment.Center).then(iconBounce),
                                )
                            } else {
                                SmallTileContent(
                                    iconProvider = iconProvider,
                                    color = colors.icon,
                                    size = { look.smallIconSize },
                                    modifier = Modifier.align(Alignment.Center).then(iconBounce),
                                )
                            }
                        TileLayout.Row -> {
                            val stockIconShape by TileDefaults.animateIconShapeAsState(uiState)
                            val iconShape =
                                if (shape == QsStyle.STOCK) stockIconShape else CircleShape
                            LargeTileContent(
                                label = label,
                                secondaryLabel = secondaryLabel,
                                iconProvider = iconProvider,
                                sideDrawable = uiState.sideDrawable,
                                colors = colors,
                                iconShape = iconShape,
                                iconClip = chipShape ?: iconShape,
                                alwaysBacked = chipShape != null,
                                toggleClick = secondaryClick,
                                toggleInteractionSource = glassPress,
                                onLongClick = longClick,
                                accessibilityUiState = uiState.accessibilityUiState,
                                squishiness = squishiness,
                                isVisible = isVisible,
                                textScale = { currentBounceableInfo.bounceable.textBounceScale },
                                toggleSize = look.toggleSize,
                                iconSize = look.largeIconSize,
                                labelStyle = look.labelStyle,
                                secondaryLabelStyle = look.secondaryLabelStyle,
                                showChevron = shape == QsStyle.CIRCLES && isDualTarget,
                                modifier =
                                    Modifier.largeTilePadding(
                                        isDualTarget = uiState.handlesSettingsClick
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileExpandable(
    expandable: Expandable,
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    modifier: Modifier = Modifier,
    glassRim: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        expandable = expandable,
        controller = rememberExpandableController(color = color, shape = shape),
        modifier =
            modifier
                .clip(shape)
                .motionTestValues { squishiness() exportAs TileMotionTestKeys.Squishness }
                .verticalSquish(squishiness)
                .then(glassRim),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

@Composable
fun TileContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    iconOnly: Boolean,
    isDualTarget: Boolean,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    height: Dp = TileHeight,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(height)
                .fillMaxWidth()
                .tileCombinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    accessibilityUiState = accessibilityUiState,
                    iconOnly = iconOnly,
                    isDualTarget = isDualTarget,
                    interactionSource = interactionSource,
                )
                .tileTestTag(iconOnly),
        content = content,
    )
}

@Composable
fun SmallStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = TileDefaults.getColorForState(uiState = uiState, iconOnly = true)

    Box(
        modifier
            .clip(TileDefaults.animateTileShapeAsState(uiState).value)
            .background(colors.background)
            .size(TileHeight)
            .clickable(onClick = onClick)
    ) {
        SmallTileContent(
            iconProvider = { getTileIcon(icon = iconProvider) },
            color = colors.icon,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
fun LargeStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = TileDefaults.getColorForState(uiState = uiState, iconOnly = false)

    Box(
        modifier
            .clip(TileDefaults.animateTileShapeAsState(uiState).value)
            .background(colors.background)
            .height(TileHeight)
            .clickable(onClick = onClick)
            .largeTilePadding()
    ) {
        LargeTileContent(
            label = uiState.label,
            secondaryLabel = "",
            iconProvider = { getTileIcon(icon = iconProvider) },
            sideDrawable = null,
            colors = colors,
            squishiness = { 1f },
        )
    }
}

private fun Context.getTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = CommonTileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

@Composable
fun Modifier.tileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean,
    isDualTarget: Boolean,
): Modifier {
    val longPressLabel =
        if (iconOnly && isDualTarget) longPressLabelMoreDetails() else longPressLabelSettings()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = false, // Haptics handled separately
            interactionSource = interactionSource,
        )
        .semantics {
            val accessibilityRole =
                if (iconOnly && isDualTarget) {
                    Role.Switch
                } else {
                    accessibilityUiState.accessibilityRole
                }
            if (accessibilityRole == Role.Switch) {
                accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            role = accessibilityRole
            stateDescription = accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics { contentDescription = accessibilityUiState.contentDescription }
        }
}

data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

@VisibleForTesting
object TileMotionTestKeys {
    val Squishness = MotionTestValueKey<Float>("tile_squishiness")
}

private object TileDefaults {
    /** An active tile uses the active color as background */
    @Composable
    @ReadOnlyComposable
    fun activeTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    /** An active tile with dual target only show the active color on the icon */
    @Composable
    @ReadOnlyComposable
    fun activeDualTargetTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .18f)
        val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
        return TileColors(
            background = surfaceColor,
            iconBackground = surfaceColor,
            label = onSurfaceVariantColor,
            secondaryLabel = onSurfaceVariantColor,
            icon = onSurfaceVariantColor,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun getColorForState(
        uiState: TileUiState,
        iconOnly: Boolean,
        style: QsStyle = QsStyle.STOCK,
        tone: Color? = null,
    ): TileColors {
        styledTileColors(style, uiState.visualState, iconOnly, tone)?.let {
            return it
        }
        return when (uiState.visualState) {
            STATE_ACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    activeDualTargetTileColors()
                } else {
                    activeTileColors()
                }
            }

            STATE_INACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    inactiveDualTargetTileColors()
                } else {
                    inactiveTileColors()
                }
            }

            else -> unavailableTileColors()
        }
    }

    @Composable
    fun iconRadius(uiState: TileUiState): Dp {
        return when (uiState.visualState) {
            STATE_ACTIVE -> ActiveIconCornerRadius
            STATE_INACTIVE -> InactiveIconCornerRadius
            else -> InactiveIconCornerRadius
        }
    }

    @Composable
    fun tileRadius(uiState: TileUiState, look: TileLook? = null): Dp {
        return when (uiState.visualState) {
            STATE_ACTIVE -> look?.activeRadius ?: ActiveTileCornerRadius
            STATE_INACTIVE -> look?.inactiveRadius ?: InactiveTileCornerRadius
            else -> look?.inactiveRadius ?: InactiveTileCornerRadius
        }
    }

    @Composable
    fun animateIconShapeAsState(uiState: TileUiState): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = iconRadius(uiState),
            label = "QSTileIconCornerRadius",
        )
    }

    @Composable
    fun animateTileShapeAsState(
        uiState: TileUiState,
        look: TileLook? = null,
    ): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = tileRadius(uiState, look),
            label = "QSTileCornerRadius",
        )
    }

    @Composable
    fun animateShapeAsState(targetValue: Dp, label: String): State<RoundedCornerShape> {
        val animatedCornerRadius by animateDpAsState(targetValue = targetValue, label = label)

        return remember {
            val corner =
                object : CornerSize {
                    override fun toPx(shapeSize: Size, density: Density): Float {
                        return with(density) { animatedCornerRadius.toPx() }
                    }
                }
            mutableStateOf(RoundedCornerShape(corner))
        }
    }
}

/**
 * A composable function that returns the [Resources]. It will be recomposed when [Configuration]
 * gets updated.
 */
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}
