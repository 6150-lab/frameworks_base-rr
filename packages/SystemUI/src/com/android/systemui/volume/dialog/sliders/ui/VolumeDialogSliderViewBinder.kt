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

package com.android.systemui.volume.dialog.sliders.ui

import android.graphics.BlurMaskFilter
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.domain.interactor.ExpandedAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

@VolumeDialogSliderScope
class VolumeDialogSliderViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSliderViewModel,
    private val overscrollViewModel: VolumeDialogOverscrollViewModel,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val expandedAudioTileDetailsFeatureInteractor: ExpandedAudioTileDetailsFeatureInteractor,
) {
    fun bind(view: View) {
        // Use horizontal volume dialog if the audio tile details view is enabled
        val isVolumeDialogVertical = !expandedAudioTileDetailsFeatureInteractor.isDialogHorizontal()
        val resolver = view.context.contentResolver
        val rrStyle = Settings.System.getIntForUser(resolver, "rr_volume_style", 0,
            UserHandle.USER_CURRENT)
        val ticks = Settings.System.getIntForUser(resolver, "rr_volume_tick", 1,
            UserHandle.USER_CURRENT) == 1
        val percent = Settings.System.getIntForUser(resolver, "rr_volume_percent", 1,
            UserHandle.USER_CURRENT) == 1
        val sliderComposeViewId =
            if (isVolumeDialogVertical) {
                R.id.volume_dialog_slider
            } else {
                R.id.volume_dialog_slider_horizontal
            }
        val sliderComposeView: ComposeView = view.requireViewById(sliderComposeViewId)
        sliderComposeView.setContent {
            PlatformTheme {
                VolumeDialogSlider(
                    viewModel = viewModel,
                    overscrollViewModel = overscrollViewModel,
                    hapticsViewModelFactory = hapticsViewModelFactory,
                    isVolumeDialogVertical = isVolumeDialogVertical,
                    rrStyle = rrStyle,
                    ticks = ticks,
                    showPercent = percent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeDialogSlider(
    viewModel: VolumeDialogSliderViewModel,
    overscrollViewModel: VolumeDialogOverscrollViewModel,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    isVolumeDialogVertical: Boolean,
    modifier: Modifier = Modifier,
    rrStyle: Int = RR_ANDROID,
    ticks: Boolean = true,
    showPercent: Boolean = true,
    dimensions: VolumeSliderDimensions =
        when {
            rrStyle == RR_PILL && isVolumeDialogVertical -> VolumeSliderDimensions.RrPill
            isVolumeDialogVertical -> VolumeSliderDimensions.Vertical
            else -> VolumeSliderDimensions.Horizontal
        },
) {
    val accent = Color(LocalContext.current.getColor(android.R.color.system_accent1_400))
    val density = LocalDensity.current
    // Neon glows around the track itself; an elevation shadow showed through its clear part.
    val neonGlow = remember(accent, density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = with(density) { 4.dp.toPx() }
            color = accent.copy(alpha = 0.7f).toArgb()
            maskFilter = BlurMaskFilter(with(density) { 6.dp.toPx() }, BlurMaskFilter.Blur.NORMAL)
        }
    }
    val colors =
        when (rrStyle) {
            // RR looks: an outlined, glowing track; frosted; one wide pill; classic accent.
            RR_NEON ->
                SliderDefaults.colors(
                    activeTrackColor = accent,
                    thumbColor = accent,
                    activeTickColor = Color(0x33FFFFFF),
                    inactiveTrackColor = Color(0x1AFFFFFF),
                    disabledActiveTickColor = Color(0x1AFFFFFF),
                    disabledInactiveTrackColor = Color(0x1AFFFFFF),
                )
            RR_GLASS ->
                SliderDefaults.colors(
                    activeTrackColor = Color(0xD9FFFFFF),
                    thumbColor = Color.White,
                    activeTickColor = Color(0x40FFFFFF),
                    inactiveTrackColor = Color(0x33FFFFFF),
                    disabledActiveTickColor = Color(0x33FFFFFF),
                    disabledInactiveTrackColor = Color(0x33FFFFFF),
                )
            RR_PILL, RR_CLASSIC ->
                SliderDefaults.colors(
                    activeTrackColor = accent,
                    thumbColor = accent,
                    activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            else -> null
        } ?: if (isVolumeDialogVertical) {
            SliderDefaults.colors(
                activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        } else {
            SliderDefaults.colors(
                activeTickColor = MaterialTheme.colorScheme.onPrimary,
                inactiveTickColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    val collectedSliderStateModel by viewModel.state.collectAsStateWithLifecycle(null)
    val sliderStateModel = collectedSliderStateModel ?: return
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> viewModel.onSliderDragStarted()
                is DragInteraction.Cancel -> viewModel.onSliderDragFinished()
                is DragInteraction.Stop -> viewModel.onSliderDragFinished()
            }
        }
    }

    val percent =
        with(sliderStateModel) {
            val span = valueRange.endInclusive - valueRange.start
            if (span > 0f) ((value - valueRange.start) / span * 100f).roundToInt() else 0
        }
    val percentColor =
        when (rrStyle) {
            RR_NEON -> accent
            RR_GLASS -> Color.White
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    PercentFrame(
        show = showPercent,
        isVertical = isVolumeDialogVertical,
        text = "$percent%",
        color = percentColor,
        modifier = modifier,
    ) { sliderModifier ->
        Slider(
            value = sliderStateModel.value,
            valueRange = sliderStateModel.valueRange,
            onValueChanged = { value ->
                overscrollViewModel.setSlider(
                    value = value,
                    min = sliderStateModel.valueRange.start,
                    max = sliderStateModel.valueRange.endInclusive,
                )
                viewModel.setStreamVolume(value, true)
            },
            onValueChangeFinished = { viewModel.onSliderChangeFinished(it) },
            isEnabled = !sliderStateModel.isDisabled,
            isReverseDirection = true,
            isVertical = isVolumeDialogVertical,
            colors = colors,
            interactionSource = interactionSource,
            haptics =
                if (!ticks) {
                    Haptics.Disabled
                } else {
                    Haptics.Enabled(
                        hapticsViewModelFactory = hapticsViewModelFactory,
                        hapticConfigs =
                            VolumeHapticsConfigsProvider.continuousConfigs(
                                SliderHapticFeedbackFilter()
                            ),
                        orientation =
                            if (isVolumeDialogVertical) {
                                Orientation.Vertical
                            } else {
                                Orientation.Horizontal
                            },
                    )
                },
            stepDistance = 1f,
            track = { sliderState ->
                Box(
                    if (rrStyle == RR_NEON) {
                        Modifier.drawBehind {
                                val r = 12.dp.toPx()
                                drawIntoCanvas {
                                    it.nativeCanvas.drawRoundRect(
                                        0f, 0f, size.width, size.height, r, r, neonGlow)
                                }
                            }
                            .border(1.5.dp, accent, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    }
                ) {
                SliderTrack(
                    sliderState,
                    colors = colors,
                    isEnabled = !sliderStateModel.isDisabled,
                    isVertical = isVolumeDialogVertical,
                    activeTrackEndIcon = { iconsState ->
                        SliderIcon(
                            icon = {
                                Icon(
                                    icon = sliderStateModel.icon,
                                    tint = null,
                                    modifier = Modifier.size(dimensions.iconSize),
                                )
                            },
                            isVisible = !iconsState.isInactiveTrackEndIconVisible,
                        )
                    },
                    inactiveTrackEndIcon = { iconsState ->
                        SliderIcon(
                            icon = {
                                Icon(
                                    icon = sliderStateModel.icon,
                                    tint = null,
                                    modifier = Modifier.size(dimensions.iconSize),
                                )
                            },
                            isVisible = iconsState.isInactiveTrackEndIconVisible,
                        )
                    },
                    trackSize = dimensions.trackSize,
                )
                }
            },
            thumb = { sliderState, interactions ->
                SliderDefaults.Thumb(
                    sliderState = sliderState,
                    interactionSource = interactions,
                    enabled = !sliderStateModel.isDisabled,
                    colors = colors,
                    thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                )
            },
            accessibilityParams = AccessibilityParams(contentDescription = sliderStateModel.label),
            modifier =
                sliderModifier.pointerInput(Unit) {
                    coroutineScope {
                        val currentContext = currentCoroutineContext()
                        awaitPointerEventScope {
                            while (currentContext.isActive) {
                                viewModel.onTouchEvent(awaitPointerEvent())
                            }
                        }
                    }
                },
        )
    }
}

/** The slider with its level in percent above it, or after it when horizontal. */
@Composable
private fun PercentFrame(
    show: Boolean,
    isVertical: Boolean,
    text: String,
    color: Color,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (!show) {
        content(modifier)
        return
    }
    val label: @Composable () -> Unit = {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
            // The slider already reads out its level.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
    if (isVertical) {
        Column(
            modifier = modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            label()
            content(Modifier.weight(1f))
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content(Modifier.weight(1f))
            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
                label()
            }
        }
    }
}

private const val RR_ANDROID = 0
private const val RR_NEON = 1
private const val RR_GLASS = 2
private const val RR_PILL = 3
private const val RR_CLASSIC = 4

data class VolumeSliderDimensions(
    val iconSize: Dp,
    val thumbHeight: Dp,
    val thumbWidth: Dp,
    val trackSize: Dp,
) {
    companion object {
        val Vertical =
            VolumeSliderDimensions(
                iconSize = 20.dp,
                thumbWidth = 52.dp,
                thumbHeight = 4.dp,
                trackSize = 40.dp,
            )

        val Horizontal =
            VolumeSliderDimensions(
                iconSize = 24.dp,
                thumbHeight = 40.dp,
                thumbWidth = 3.dp,
                trackSize = 32.dp,
            )

        /** RR Pill: one wide pill you drag, no handle, the icon inside. */
        val RrPill =
            VolumeSliderDimensions(
                iconSize = 24.dp,
                thumbWidth = 0.dp,
                thumbHeight = 0.dp,
                // The whole width of the slider's 52dp column.
                trackSize = 52.dp,
            )
    }
}
