/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui.compose

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.util.lerp
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.glass.ui.GlassRimShader
import com.android.systemui.glass.ui.OwnGlass
import com.android.systemui.glass.ui.OwnGlassLens
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

/**
 * The glass selector's lens on a slider: while the slider is dragged, where glass at [place] has
 * the selector, Android's knob turns into a lens that magnifies the track under it and stretches
 * as it moves. Made by [rememberOwnGlassSliderLens]; the slider takes [ownGlassSliderLens], its
 * knob [ownGlassLensKnob] and [knobSize], and its track [trackGap].
 */
@Stable
class OwnGlassSliderLens internal constructor(
    internal val place: String,
    private val vertical: Boolean,
) {
    // 0 while the slider has Android's knob, 1 once the lens has grown out of it
    internal val presence = Animatable(0f)
    // How long the lens is along the slider now, as a scale: 1 round
    private var stretch by mutableFloatStateOf(1f)
    // The knob's middle in the slider, as the frame loop last found it
    private var knob by mutableStateOf(Offset.Unspecified)
    // Android's knob, as the slider gives it
    private var knobRest = DpSize.Zero
    internal var trackThickness = 0f
    internal var density = 1f
    // How much the lens magnifies, and whether it stretches, as the settings were when it showed
    internal var magnify = 1f
    private var stretchy = true
    // Whether a finger holds the slider with the lens on it
    private var held = false
    private var slider: LayoutCoordinates? = null
    private var knobLayout: LayoutCoordinates? = null

    /** Android's knob of [size], thinning along the slider as the lens takes its place. */
    fun knobSize(size: DpSize): DpSize {
        knobRest = size
        val left = (1f - presence.value).coerceIn(0f, 1f)
        return if (vertical) {
            DpSize(size.width, size.height * left)
        } else {
            DpSize(size.width * left, size.height)
        }
    }

    /** The track's [gap] around the knob, closing as the lens takes the knob's place. */
    fun trackGap(gap: Dp): Dp = gap * (1f - presence.value).coerceIn(0f, 1f)

    internal fun onSliderPlaced(coordinates: LayoutCoordinates) {
        slider = coordinates
    }

    internal fun onKnobPlaced(coordinates: LayoutCoordinates) {
        knobLayout = coordinates
    }

    // Where the knob's middle is in the slider now. The slider moves the box the knob sits in, so
    // the knob itself is never placed again as it moves: this asks the layout each time. It maps
    // the knob's middle as a point: under the lens the knob is flat, and a flat knob's bounding box
    // comes back empty, at the slider's corner.
    private fun findKnob(): Offset {
        val s = slider ?: return Offset.Unspecified
        val k = knobLayout ?: return Offset.Unspecified
        if (!s.isAttached || !k.isAttached) {
            return Offset.Unspecified
        }
        return s.localPositionOf(k, Offset(k.size.width / 2f, k.size.height / 2f))
    }

    /** Shows the lens while [interactionSource] says the slider is held, until cancelled. */
    internal suspend fun follow(interactionSource: InteractionSource) = coroutineScope {
        val holds = mutableSetOf<Interaction>()
        var motion: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press,
                is DragInteraction.Start -> holds += interaction
                is PressInteraction.Release -> holds -= interaction.press
                is PressInteraction.Cancel -> holds -= interaction.press
                is DragInteraction.Stop -> holds -= interaction.start
                is DragInteraction.Cancel -> holds -= interaction.start
                else -> return@collect
            }
            if (holds.isNotEmpty() && !held) {
                if (!OwnGlass.hasSelector(place)) {
                    return@collect
                }
                held = true
                magnify = OwnGlassLens.magnify(OwnGlass.selectorMagnify)
                stretchy = OwnGlass.stretch
                launch { presence.animateTo(1f, grow) }
                if (motion?.isActive != true) {
                    motion = launch { move() }
                }
            } else if (holds.isEmpty() && held) {
                held = false
                launch { presence.animateTo(0f, grow) }
            }
        }
    }

    // Follows the knob each frame: the faster it goes, the longer the lens, on a wobbly spring. It
    // also keeps [knob] up to date, so the lens is drawn again as the knob moves.
    private suspend fun move() {
        var lastAt = 0L
        var lastPos = Float.NaN
        var speed = 0f
        var velocity = 0f
        val stiffness = OwnGlassLens.STRETCH_STIFFNESS
        val damping = 2f * OwnGlassLens.STRETCH_DAMPING_RATIO * sqrt(stiffness)
        while (true) {
            val now = withFrameNanos { it }
            val k = findKnob()
            if (k.isSpecified) {
                knob = k
            }
            val pos = if (k.isSpecified) (if (vertical) k.y else k.x) else lastPos
            if (lastAt != 0L && !pos.isNaN() && !lastPos.isNaN()) {
                val dt = ((now - lastAt) / NANOS_PER_SECOND).coerceIn(MIN_STEP_S, MAX_STEP_S)
                val moved = abs(pos - lastPos) / dt / density
                speed += (moved - speed) * min(1f, dt / OwnGlassLens.SPEED_SMOOTHING_S)
                val target = if (held && stretchy) OwnGlassLens.stretch(speed) else 1f
                velocity += (stiffness * (target - stretch) - damping * velocity) * dt
                stretch += velocity * dt
            }
            lastAt = now
            lastPos = pos
            if (!held && presence.value <= 0f && abs(stretch - 1f) < SETTLED) {
                stretch = 1f
                return
            }
        }
    }

    /** The lens's outline in a slider drawing at its size now; null while there's no knob. */
    internal fun DrawScope.outline(): RoundRect? {
        // Reading [knob] draws this again as the frame loop moves it; where the knob is right now
        // comes from the layout
        val last = knob
        val now = findKnob()
        val k = if (now.isSpecified) now else last
        if (!k.isSpecified) {
            return null
        }
        val p = presence.value.coerceAtLeast(0f)
        val restAlong = (if (vertical) knobRest.height else knobRest.width).value * density
        val restAcross = (if (vertical) knobRest.width else knobRest.height).value * density
        val cross = if (vertical) size.width else size.height
        val length = if (vertical) size.height else size.width
        val round = min(trackThickness + 2f * OwnGlassLens.OVERHANG_DP * density, cross)
        val s = stretch
        val along = lerp(restAlong, round, p) * s
        val across = min(lerp(restAcross, round, p) / sqrt(s), cross)
        // At the slider's ends it stays inside the slider, which may clip what's outside
        val half = along / 2f
        val middle =
            (if (vertical) k.y else k.x).coerceIn(
                min(half, length / 2f),
                max(length - half, length / 2f),
            )
        val side = if (vertical) k.x else k.y
        val halfAcross = across / 2f
        val rect =
            if (vertical) {
                Rect(side - halfAcross, middle - half, side + halfAcross, middle + half)
            } else {
                Rect(middle - half, side - halfAcross, middle + half, side + halfAcross)
            }
        val radius = min(along, across) / 2f
        return RoundRect(rect, radius, radius)
    }

    private companion object {
        val grow = spring<Float>(OwnGlassLens.GROW_DAMPING_RATIO, OwnGlassLens.GROW_STIFFNESS)
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_STEP_S = 0.001f
        const val MAX_STEP_S = 0.05f
        const val SETTLED = 0.002f
    }
}

/**
 * The lens a slider gets at [place] (one of GlassSettings' place keys), showing while
 * [interactionSource], the slider's own, says it's held. [trackThickness] is how thick the track
 * is across the slider; [vertical] for a slider that runs up and down.
 */
@Composable
fun rememberOwnGlassSliderLens(
    place: String,
    vertical: Boolean,
    trackThickness: Dp,
    interactionSource: InteractionSource,
): OwnGlassSliderLens {
    val context = LocalContext.current
    remember(context) { OwnGlass.start(context) }
    val density = LocalDensity.current
    val lens = remember(place, vertical) { OwnGlassSliderLens(place, vertical) }
    lens.density = density.density
    lens.trackThickness = with(density) { trackThickness.toPx() }
    // The light on its rim follows the phone while it shows
    OwnGlassShown(place) { lens.presence.value > 0f }
    LaunchedEffect(lens, interactionSource) { lens.follow(interactionSource) }
    return lens
}

/**
 * Draws [lens] over this slider while it shows: what's under it inside it, magnified, then its
 * shadow and rim. Put it on the slider itself, after anything that pads or moves it.
 */
fun Modifier.ownGlassSliderLens(lens: OwnGlassSliderLens): Modifier = composed {
    val overLight = !isSystemInDarkTheme()
    val density = LocalDensity.current.density
    val path = remember { Path() }
    val radii = remember { FloatArray(4) }
    val shadowPaint =
        remember(density) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter =
                    BlurMaskFilter(
                        OwnGlassLens.SHADOW_RADIUS_DP * density,
                        BlurMaskFilter.Blur.NORMAL,
                    )
            }
        }
    onPlaced { lens.onSliderPlaced(it) }
        .drawWithContent {
            val p = lens.presence.value
            val shape = if (p > 0f) with(lens) { outline() } else null
            if (shape == null) {
                drawContent()
                return@drawWithContent
            }
            val shown = p.coerceIn(0f, 1f)
            val bounds = shape.boundingRect
            val radius = shape.topLeftCornerRadius.x
            path.reset()
            path.addRoundRect(shape)
            // Around the lens: the slider as it is, and the lens's shadow on it
            clipPath(path, ClipOp.Difference) {
                this@drawWithContent.drawContent()
                val drop = OwnGlassLens.SHADOW_DROP_DP * density
                shadowPaint.alpha = (OwnGlassLens.SHADOW_ALPHA * shown * OPAQUE).roundToInt()
                drawIntoCanvas {
                    it.nativeCanvas.drawRoundRect(
                        bounds.left,
                        bounds.top + drop,
                        bounds.right,
                        bounds.bottom + drop,
                        radius,
                        radius,
                        shadowPaint,
                    )
                }
            }
            // Inside it: what it covers, magnified around its middle
            val magnify = 1f + (lens.magnify - 1f) * shown
            clipPath(path) {
                scale(magnify, magnify, bounds.center) { this@drawWithContent.drawContent() }
            }
            val rim = OwnGlass.rim(lens.place, density) ?: return@drawWithContent
            val shader = GlassRimShader.shared ?: return@drawWithContent
            radii.fill(radius)
            val ready =
                shader.set(
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    radii,
                    rim,
                    OwnGlass.light,
                    overLight,
                )
            if (ready) {
                drawRect(ShaderBrush(shader.shader), bounds.topLeft, bounds.size, alpha = shown)
            }
        }
}

/**
 * Android's knob on a slider with [lens]: tells the lens where it is, and fades as the lens takes
 * its place.
 */
fun Modifier.ownGlassLensKnob(lens: OwnGlassSliderLens): Modifier =
    onPlaced { lens.onKnobPlaced(it) }
        .graphicsLayer { alpha = 1f - lens.presence.value.coerceIn(0f, 1f) }

private const val OPAQUE = 255
