/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.glass.ui

import android.graphics.RuntimeShader
import android.util.Log
import androidx.annotation.MainThread

/**
 * The light on the rim of glass SystemUI draws itself, such as Quick Settings tiles over the
 * shade's blur: the thin line along the edge with its turning rainbow, the soft glow inside the
 * lit part of the rim and the glint where it faces the light, as SurfaceFlinger draws them on its
 * own glass (frameworks/native BlurFilter). There's nothing sharp behind to bend here, so the rim
 * away from the light gets a little shade instead, where SurfaceFlinger's rim would bend in the
 * darker content past the edge. A finger on the glass lights it up around itself.
 *
 * It draws over the glass and what's on it, inside the outline only. One shader serves all glass
 * on the main thread: each drawing sets it up just before it draws, and the drawing keeps what it
 * was set to. Should the shader fail on a device, glass draws without its rim from then on, as
 * SurfaceFlinger's glass falls back to plain blur.
 */
@MainThread
class GlassRimShader private constructor() {

    val shader = RuntimeShader(SOURCE)

    /**
     * Sets up the next drawing: glass inside [left], [top], [right], [bottom] with corner radii
     * [radii] (top left, top right, bottom right, bottom left, in pixels), drawn with [rim] in
     * [light]. [overLight] is true where the glass sits on light content, where the bright line
     * would vanish. [glow] (0 to 1) lights the glass up around ([touchX], [touchY]). False if the
     * shader failed, so there's nothing to draw.
     */
    fun set(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: FloatArray,
        rim: OwnGlassRim,
        light: OwnGlassLight,
        overLight: Boolean,
        glow: Float = 0f,
        touchX: Float = 0f,
        touchY: Float = 0f,
    ): Boolean =
        try {
            setUniforms(left, top, right, bottom, radii, rim, light, overLight, glow, touchX, touchY)
            true
        } catch (e: IllegalArgumentException) {
            fail(e)
            false
        }

    private fun setUniforms(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: FloatArray,
        rim: OwnGlassRim,
        light: OwnGlassLight,
        overLight: Boolean,
        glow: Float,
        touchX: Float,
        touchY: Float,
    ) {
        val halfSize = minOf(right - left, bottom - top) / 2f
        shader.setFloatUniform("bounds", left, top, right, bottom)
        shader.setFloatUniform("radii", radii[0], radii[1], radii[2], radii[3])
        // A rim wider than half the glass would meet itself in the middle
        shader.setFloatUniform("rimWidth", rim.rimWidth.coerceIn(1f, maxOf(1f, halfSize * 0.9f)))
        shader.setFloatUniform("rimLight", rim.rimLight)
        shader.setFloatUniform("dispersion", rim.dispersion)
        shader.setFloatUniform("bevel", rim.bevel)
        shader.setFloatUniform("overLight", if (overLight) 1f else 0f)
        shader.setFloatUniform("lightDir", light.x, light.y)
        shader.setFloatUniform("lightTurn", light.turn)
        shader.setFloatUniform("touchGlow", glow)
        shader.setFloatUniform("touchPoint", touchX, touchY)
        shader.setFloatUniform("touchRadius", rim.glowRadius)
        shader.setFloatUniform(
            "touchColor",
            ((rim.glowColor shr 16) and 0xff) / 255f,
            ((rim.glowColor shr 8) and 0xff) / 255f,
            (rim.glowColor and 0xff) / 255f,
        )
        shader.setFloatUniform("touchRainbow", if (rim.glowRainbow) 1f else 0f)
    }

    companion object {
        private const val TAG = "GlassRimShader"

        private var made: GlassRimShader? = null
        private var failed = false

        /**
         * The one shader all of SystemUI's own glass draws with, or null if it failed: glass then
         * draws without its rim. Main thread only.
         */
        val shared: GlassRimShader?
            get() {
                if (made == null && !failed) {
                    try {
                        made = GlassRimShader()
                    } catch (e: IllegalArgumentException) {
                        fail(e)
                    }
                }
                return made
            }

        private fun fail(e: IllegalArgumentException) {
            Log.e(TAG, "Glass rim shader failed, glass draws without its rim", e)
            failed = true
            made = null
        }

        private const val SOURCE =
            """
            // The glass's outline in local coordinates and its corner radii: top left, top
            // right, bottom right, bottom left.
            uniform float4 bounds;
            uniform float4 radii;
            // Width of the curved rim, the strength of its light (0 to 1), how much it splits
            // light into colors (0 to 1), and how much shade it has away from the light (0 to 1).
            uniform float rimWidth;
            uniform float rimLight;
            uniform float dispersion;
            uniform float bevel;
            // 1 where the glass sits on light content: the bright line gives way to a thin dark
            // one.
            uniform float overLight;
            // Unit vector toward the light, y pointing down, and how far it has turned from its
            // usual place at the top left, in radians.
            uniform float2 lightDir;
            uniform float lightTurn;
            // A finger on the glass: how strongly it lights it (0 to 1), where, how far the
            // light reaches, its color, and 1 for light that turns through the rainbow instead.
            uniform float touchGlow;
            uniform float2 touchPoint;
            uniform float touchRadius;
            uniform float3 touchColor;
            uniform float touchRainbow;

            // Signed distance to the outline (negative inside) and the outward normal.
            float outline(float2 p, out float2 normal) {
                float2 center = (bounds.xy + bounds.zw) * 0.5;
                float2 halfSize = (bounds.zw - bounds.xy) * 0.5;
                float2 q = p - center;
                float2 side = float2(q.x < 0.0 ? -1.0 : 1.0, q.y < 0.0 ? -1.0 : 1.0);
                float radius = q.y < 0.0 ? (q.x < 0.0 ? radii.x : radii.y)
                                         : (q.x < 0.0 ? radii.w : radii.z);
                radius = min(radius, min(halfSize.x, halfSize.y));
                float2 d = abs(q) - halfSize + radius;
                if (d.x > 0.0 && d.y > 0.0) {
                    normal = side * normalize(d);
                } else if (d.x > d.y) {
                    normal = float2(side.x, 0.0);
                } else {
                    normal = float2(0.0, side.y);
                }
                return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - radius;
            }

            half4 main(float2 p) {
                float2 normal;
                float toEdge = outline(p, normal);
                // Inside the outline only, with a soft pixel at the edge
                float inside = 1.0 - smoothstep(-0.5, 0.5, toEdge);
                if (inside <= 0.0) {
                    return half4(0.0);
                }
                float depth = max(-toEdge, 0.0);
                // 1 on the very edge, 0 where the rim meets the flat middle.
                float edge = 1.0 - clamp(depth / rimWidth, 0.0, 1.0);

                // As SurfaceFlinger's glass: a thin line all along the edge, brightest facing
                // the light and brighter on the far side than in between, tinted with a rainbow
                // that turns around the outline with the light, over a soft glow inside the lit
                // part of the rim, and a wet glint where it turns straight toward the light.
                float facing = dot(normal, lightDir);
                float catchLight = 0.3 + 0.7 * pow(max(facing, 0.0), 2.0) +
                                   0.4 * pow(max(-facing, 0.0), 2.0);
                float lineWidth = max(1.5, rimWidth * 0.06);
                float line = 1.0 - smoothstep(0.0, lineWidth, depth);
                float angle = atan(normal.y, normal.x);
                float3 rainbow =
                        0.5 + 0.5 * cos(5.76 + lightTurn - angle + float3(0.0, 2.094, 4.189));
                float3 lineColor = mix(float3(1.0), rainbow, 0.8 * dispersion);
                float glint = pow(max(facing, 0.0), 12.0) * smoothstep(0.35, 1.0, edge);
                float3 light = rimLight * (1.0 - 0.8 * overLight) *
                               (line * catchLight * lineColor +
                                0.25 * edge * edge * edge * max(facing, 0.0) + 0.6 * glint);

                if (touchGlow > 0.0) {
                    float2 fromTouch = p - touchPoint;
                    float reach = length(fromTouch) / touchRadius;
                    // The rainbow turns around the finger, white where it touches so the colors
                    // don't meet in a point
                    float3 glowColor = touchRainbow > 0.5
                            ? mix(float3(1.0),
                                  0.5 + 0.5 * cos(atan(fromTouch.y, fromTouch.x) +
                                                  float3(0.0, 2.094, 4.189)),
                                  0.6 * smoothstep(0.0, 0.8, reach))
                            : touchColor;
                    // A soft pool of light under the finger, and the rim near it lit up
                    light += touchGlow * glowColor *
                             (0.35 * exp(-2.0 * reach * reach) +
                              0.8 * line * exp(-0.5 * reach * reach));
                }
                light = min(light, float3(1.0));
                float lit = max(light.r, max(light.g, light.b));

                // Shade in the rim away from the light, and over light content the thin dark
                // line
                float shade = bevel * 0.45 * edge * edge * (1.0 - max(facing, 0.0)) +
                              0.35 * rimLight * overLight * line;
                // Light added like light on a screen, over the shade: premultiplied, so this
                // draws as the glass plus the light, darkened by the shade.
                float alpha = 1.0 - (1.0 - lit) * (1.0 - min(shade, 1.0));
                return half4(half3(light * inside), half(alpha * inside));
            }
            """
    }
}
