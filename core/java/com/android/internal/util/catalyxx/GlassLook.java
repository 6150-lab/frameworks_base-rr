/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

/**
 * How a piece of Catalyxx glass is drawn. SurfaceFlinger draws a blur region with a look as a
 * slab of glass: its curved rim bends in the content just past the edge and catches the light,
 * and its flat middle shows the content behind it, clear or frosted. Made by
 * {@link GlassSettings#look}.
 */
public final class GlassLook {

    /** Width of the curved rim, in pixels. */
    public final float rimWidth;

    /** How far past the edge the rim reaches to bend in what's there, in rim widths. */
    public final float bend;

    /** How blurred the flat middle is: 0 is clear, 1 fully frosted. */
    public final float frost;

    /** Strength of the light on the rim, 0 to 1. */
    public final float rimLight;

    /** How much the rim splits light into colors, 0 to 1. */
    public final float dispersion;

    /** Darkness of the glass's shadow, 0 to 1. */
    public final float shadow;

    public GlassLook(float rimWidth, float bend, float frost, float rimLight, float dispersion,
            float shadow) {
        this.rimWidth = rimWidth;
        this.bend = bend;
        this.frost = frost;
        this.rimLight = rimLight;
        this.dispersion = dispersion;
        this.shadow = shadow;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GlassLook)) {
            return false;
        }
        final GlassLook other = (GlassLook) o;
        return rimWidth == other.rimWidth && bend == other.bend && frost == other.frost
                && rimLight == other.rimLight && dispersion == other.dispersion
                && shadow == other.shadow;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(rimWidth);
        result = 31 * result + Float.hashCode(bend);
        result = 31 * result + Float.hashCode(frost);
        result = 31 * result + Float.hashCode(rimLight);
        result = 31 * result + Float.hashCode(dispersion);
        result = 31 * result + Float.hashCode(shadow);
        return result;
    }

    @Override
    public String toString() {
        return "GlassLook{rimWidth=" + rimWidth + ", bend=" + bend + ", frost=" + frost
                + ", rimLight=" + rimLight + ", dispersion=" + dispersion + ", shadow=" + shadow
                + "}";
    }
}
