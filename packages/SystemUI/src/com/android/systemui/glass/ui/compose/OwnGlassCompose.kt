/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui.compose

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.glass.ui.GlassRimShader
import com.android.systemui.glass.ui.OwnGlass
import com.android.systemui.glass.ui.OwnGlassSquish
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

// How fast the touch glow comes up under a finger and fades once it lifts, in ms
private const val GLOW_IN_MS = 120
private const val GLOW_OUT_MS = 450

/** A finger on a piece of glass: where it touches, and how much the glass lights up for it. */
class OwnGlassTouch internal constructor() {
    internal var point by mutableStateOf(Offset.Zero)
    internal val glow = Animatable(0f)
}

/** The finger on a piece of glass, shared by its [ownGlassRim] and [ownGlassTouchPool]. */
@Composable fun rememberOwnGlassTouch(): OwnGlassTouch = remember { OwnGlassTouch() }

/**
 * Draws the rim of glass at [place] (one of GlassSettings' place keys) over this element and what's
 * on it, inside [shape], from [OwnGlass]. With [touch], it follows a finger on it, which lights up
 * the rim near it while the touch glow setting is on; [ownGlassTouchPool] draws the light under the
 * finger. Put it inside any clip or squish of the glass so the rim follows them.
 */
fun Modifier.ownGlassRim(place: String, shape: Shape, touch: OwnGlassTouch? = null): Modifier =
    composed {
        val context = LocalContext.current
        remember(context) { OwnGlass.start(context) }
        val density = LocalDensity.current.density
        val layoutDirection = LocalLayoutDirection.current
        val overLight = !isSystemInDarkTheme()
        val radii = remember { FloatArray(4) }
        val scope = rememberCoroutineScope()

        val touchModifier =
            if (touch != null) {
                // Only watches the finger, so the glass's own clicks and drags work as before
                Modifier.pointerInput(touch) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        touch.point = down.position
                        scope.launch { touch.glow.animateTo(1f, tween(GLOW_IN_MS)) }
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull { it.id == down.id }?.let {
                                touch.point = it.position
                            }
                        } while (event.changes.any { it.pressed })
                        scope.launch { touch.glow.animateTo(0f, tween(GLOW_OUT_MS)) }
                    }
                }
            } else {
                Modifier
            }

        touchModifier.drawWithContent {
            drawContent()
            val rim = OwnGlass.rim(place, density) ?: return@drawWithContent
            outlineRadii(shape, size, layoutDirection, radii)
            val shader = GlassRimShader.shared ?: return@drawWithContent
            val ready =
                shader.set(
                    0f,
                    0f,
                    size.width,
                    size.height,
                    radii,
                    rim,
                    OwnGlass.light,
                    overLight,
                    glow = if (touch != null && rim.touchGlow) touch.glow.value else 0f,
                    touchX = touch?.point?.x ?: 0f,
                    touchY = touch?.point?.y ?: 0f,
                )
            if (ready) {
                drawRect(ShaderBrush(shader.shader))
            }
        }
    }

/**
 * Squishes glass at [place] a little while [interactionSource] says it's pressed and springs it
 * back, as the squish setting says. Put it outside the glass's clip and rim, so they squish too.
 */
fun Modifier.ownGlassSquish(place: String, interactionSource: InteractionSource): Modifier =
    composed {
        val context = LocalContext.current
        remember(context) { OwnGlass.start(context) }
        val density = LocalDensity.current.density
        val depth = remember { Animatable(0f) }
        // How the glass squishes for the press now, kept until it has sprung back
        var squish by remember { mutableStateOf<OwnGlassSquish?>(null) }
        LaunchedEffect(interactionSource) {
            val presses = mutableSetOf<PressInteraction.Press>()
            var pressedAt = 0L
            var release: Job? = null
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses += interaction
                        if (presses.size == 1) {
                            release?.cancel()
                            val now = OwnGlassSquish.now(place) ?: return@collect
                            squish = now
                            pressedAt = SystemClock.uptimeMillis()
                            launch {
                                depth.animateTo(1f, spring(now.pressDamping, now.pressStiffness))
                            }
                        }
                    }
                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> {
                        presses -=
                            if (interaction is PressInteraction.Release) interaction.press
                            else (interaction as PressInteraction.Cancel).press
                        val s = squish
                        if (presses.isEmpty() && s != null) {
                            // A quick tap lifts before its squish shows, so it stays pressed a
                            // moment longer
                            val left =
                                pressedAt + OwnGlassSquish.MIN_PRESS_MS - SystemClock.uptimeMillis()
                            release = launch {
                                if (left > 0) {
                                    delay(left)
                                }
                                depth.animateTo(0f, spring(s.releaseDamping, s.releaseStiffness))
                            }
                        }
                    }
                }
            }
        }
        graphicsLayer {
            val s = squish ?: return@graphicsLayer
            val scale = s.scale(depth.value, size.width, size.height, density)
            scaleX = scale
            scaleY = scale
        }
    }

/**
 * Draws the pool of light under a finger on glass at [place], from the [touch] its [ownGlassRim]
 * follows, inside [shape]. Put it behind what's on the glass, so the light doesn't wash it out.
 */
fun Modifier.ownGlassTouchPool(place: String, shape: Shape, touch: OwnGlassTouch): Modifier =
    composed {
        val density = LocalDensity.current.density
        val layoutDirection = LocalLayoutDirection.current
        val radii = remember { FloatArray(4) }
        drawBehind {
            val glow = touch.glow.value
            if (glow <= 0f) {
                return@drawBehind
            }
            val rim = OwnGlass.rim(place, density)
            if (rim == null || !rim.touchGlow) {
                return@drawBehind
            }
            outlineRadii(shape, size, layoutDirection, radii)
            val shader = GlassRimShader.shared ?: return@drawBehind
            val ready =
                shader.set(
                    0f,
                    0f,
                    size.width,
                    size.height,
                    radii,
                    rim,
                    OwnGlass.light,
                    overLight = false,
                    glow = glow,
                    touchX = touch.point.x,
                    touchY = touch.point.y,
                    pool = true,
                )
            if (ready) {
                drawRect(ShaderBrush(shader.shader))
            }
        }
    }

/** [shape]'s corner radii at [size] into [radii]: top left, top right, bottom right, bottom left. */
private fun DrawScope.outlineRadii(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    radii: FloatArray,
) {
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> {
            val rect = outline.roundRect
            radii[0] = rect.topLeftCornerRadius.x
            radii[1] = rect.topRightCornerRadius.x
            radii[2] = rect.bottomRightCornerRadius.x
            radii[3] = rect.bottomLeftCornerRadius.x
        }
        else -> radii.fill(0f)
    }
}

/**
 * Keeps the light on glass at [place] following the phone while [visible] says this glass is on
 * screen and it has a rim.
 */
@Composable
fun OwnGlassShown(place: String, visible: () -> Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    LaunchedEffect(place, context) {
        OwnGlass.start(context)
        val token = Any()
        try {
            snapshotFlow { visible() && OwnGlass.rim(place, density) != null }
                .collect { if (it) OwnGlass.show(token) else OwnGlass.hide(token) }
        } finally {
            OwnGlass.hide(token)
        }
    }
}

/**
 * The color glass at [place] fills an element with, where Android fills it with [androidColor]:
 * [androidColor] itself while glass is off there.
 */
@Composable
fun ownGlassFill(place: String, androidColor: Color): Color {
    val context = LocalContext.current
    remember(context) { OwnGlass.start(context) }
    return Color(OwnGlass.fill(place, androidColor.toArgb()))
}
