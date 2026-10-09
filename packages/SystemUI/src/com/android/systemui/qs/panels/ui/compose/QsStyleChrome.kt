/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.res.R

/** Brightness slider colors for [style], or null for the stock ones. */
@Composable
fun qsStyleSliderColors(style: QsStyle): SliderColors? {
    val scheme = MaterialTheme.colorScheme
    return when (style) {
        QsStyle.REMIX ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = .16f),
                    activeTickColor = colorResource(android.R.color.system_accent1_800),
                    inactiveTickColor = Color.White,
                )
        QsStyle.GLASS ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary.copy(alpha = .55f),
                    inactiveTrackColor = Color.White.copy(alpha = .05f),
                    activeTickColor = Color.White,
                    inactiveTickColor = scheme.onSurface,
                )
        QsStyle.CLASSIC ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = .18f),
                )
        QsStyle.MONO ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = Color(0xFFF2F2F2),
                    activeTrackColor = Color(0xFFF2F2F2),
                    inactiveTrackColor = Color(0xFF1C1C1C),
                    activeTickColor = Color(0xFF111111),
                    inactiveTickColor = Color(0xFFEDEDED),
                )
        else -> null
    }
}

fun qsStyleSliderDimensions(
    style: QsStyle,
    default: BrightnessSliderDimensions,
): BrightnessSliderDimensions =
    when (style) {
        QsStyle.COMPACT ->
            default.copy(
                iconSize = DpSize(20.dp, 20.dp),
                thumbHeight = 36.dp,
                trackHeight = 28.dp,
                verticalPadding = 2.dp,
            )
        QsStyle.CLASSIC ->
            default.copy(
                iconSize = DpSize(0.dp, 0.dp),
                thumbHeight = 20.dp,
                thumbWidth = 20.dp,
                trackHeight = 4.dp,
                verticalPadding = 10.dp,
            )
        QsStyle.GLASS -> default.copy(trackHeight = 36.dp, thumbHeight = 46.dp)
        QsStyle.REMIX -> default.copy(trackHeight = 44.dp, thumbHeight = 52.dp, verticalPadding = 0.dp)
        else -> default
    }

/** What sits around the brightness slider: Remix puts it in an accent card, Classic adds a sun. */
@Composable
fun QsStyleSliderFrame(style: QsStyle, content: @Composable () -> Unit) {
    when (style) {
        QsStyle.REMIX -> {
            val top = colorResource(android.R.color.system_accent1_600)
            val bottom = colorResource(android.R.color.system_accent1_900)
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(top, bottom),
                                start = Offset.Zero,
                                end = Offset(900f, 600f),
                            ),
                            RoundedCornerShape(28.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                content()
            }
        }
        QsStyle.CLASSIC ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_brightness_full),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(22.dp),
                )
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        else -> content()
    }
}
