/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.usb

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.shared.system.RrGlass.isDialogBlurEnabled
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlinx.coroutines.delay

data class UsbModeItem(
    val function: Long,
    @StringRes val labelResId: Int,
    @StringRes val shortLabelResId: Int,
    val icon: ImageVector,
    val selected: Boolean,
)

/** The USB island: the current mode and a button per mode; it hides itself after a while. */
@Composable
fun UsbModePickerContent(
    dialog: SystemUIDialog,
    modes: List<UsbModeItem>,
    onModeSelected: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val radius = dimensionResource(R.dimen.usb_island_corner_radius)
    val blurRadius = dimensionResource(R.dimen.bottomsheet_blur_radius)
    val isBlurSupported by
        dialog.blurInteractor!!.isBlurCurrentlySupported.collectAsStateWithLifecycle()
    // Liquid Glass: blur the card's own region, so the glass lens lights the card.
    val glass = isDialogBlurEnabled() && isBlurSupported
    val density = LocalDensity.current
    val blur = remember {
        dialog.window!!.decorView.getViewRootImpl().createBackgroundBlurDrawable()
    }
    // Before the draw pass: the window copies its blur regions before drawing, and the island
    // draws a single frame, so radii set while drawing would never reach SurfaceFlinger.
    SideEffect {
        if (glass) {
            blur.setBlurRadius(with(density) { blurRadius.roundToPx() })
            blur.setCornerRadius(with(density) { radius.toPx() })
        } else {
            blur.setBlurRadius(0)
        }
    }
    LaunchedEffect(Unit) {
        delay(AUTO_HIDE_MS)
        onDismiss()
    }
    val current = modes.firstOrNull { it.selected } ?: modes.last()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glass) {
                    Modifier.drawBehind {
                        drawIntoCanvas { canvas ->
                            blur.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            blur.draw(canvas.nativeCanvas)
                        }
                    }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(radius))
            .background(
                if (glass) {
                    Color(
                        ColorUtils.compositeColors(
                            LocalAndroidColorScheme.current.surfaceEffect0.toArgb(),
                            LocalAndroidColorScheme.current.surfaceEffect2.toArgb(),
                        )
                    )
                } else {
                    colors.surfaceContainer
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Usb,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = colors.onPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.usb_island_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(current.labelResId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.usb_island_settings),
                    tint = colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.usb_island_dismiss),
                    tint = colors.onSurfaceVariant,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            modes.forEach { mode ->
                UsbModeButton(mode, Modifier.weight(1f)) { onModeSelected(mode.function) }
            }
        }
    }
}

@Composable
private fun UsbModeButton(mode: UsbModeItem, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (mode.selected) colors.primary else colors.onSurface.copy(alpha = 0.1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "usbModeContainer",
    )
    val content by animateColorAsState(
        targetValue = if (mode.selected) colors.onPrimary else colors.onSurface,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "usbModeContent",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = mode.selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = content,
            )
        }
        Text(
            text = stringResource(mode.shortLabelResId),
            style = MaterialTheme.typography.labelSmall,
            color = if (mode.selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private const val AUTO_HIDE_MS = 8000L
