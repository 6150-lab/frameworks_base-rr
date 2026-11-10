/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

/**
 * How a piece of Catalyxx glass is drawn. SurfaceFlinger draws a blur region with a look as a
 * slab of glass: its curved rim bends in the content just past the edge and catches the light,
 * and its flat middle shows the content behind it, clear or frosted, under the glass's own color,
 * which can turn smoky where what's behind is light. Made by {@link GlassSettings#look}.
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

    /**
     * The glass's own color over dark content, ARGB; its alpha is how much of the glass it
     * covers.
     */
    public final int tintOverDark;

    /** The glass's own color over light content, ARGB. */
    public final int tintOverLight;

    /**
     * How bright what's behind is, 0 to 1, where the glass turns from {@link #tintOverDark} to
     * {@link #tintOverLight}; 0 keeps {@link #tintOverDark} everywhere.
     */
    public final float adaptPoint;

    /**
     * RR's lens edge ({@link GlassSettings#EDGE_RR}): a frosted middle keeps a clear rim, which
     * bends what's under the glass out to its edge, and what shows through is a little richer in
     * color.
     */
    public final boolean lensEdge;

    public GlassLook(float rimWidth, float bend, float frost, float rimLight, float dispersion,
            float shadow, int tintOverDark, int tintOverLight, float adaptPoint,
            boolean lensEdge) {
        this.rimWidth = rimWidth;
        this.bend = bend;
        this.frost = frost;
        this.rimLight = rimLight;
        this.dispersion = dispersion;
        this.shadow = shadow;
        this.tintOverDark = tintOverDark;
        this.tintOverLight = tintOverLight;
        this.adaptPoint = adaptPoint;
        this.lensEdge = lensEdge;
    }

    /** This look with no shadow. */
    public GlassLook withoutShadow() {
        return new GlassLook(rimWidth, bend, frost, rimLight, dispersion, /* shadow= */ 0f,
                tintOverDark, tintOverLight, adaptPoint, lensEdge);
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
                && shadow == other.shadow && tintOverDark == other.tintOverDark
                && tintOverLight == other.tintOverLight && adaptPoint == other.adaptPoint
                && lensEdge == other.lensEdge;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(rimWidth);
        result = 31 * result + Float.hashCode(bend);
        result = 31 * result + Float.hashCode(frost);
        result = 31 * result + Float.hashCode(rimLight);
        result = 31 * result + Float.hashCode(dispersion);
        result = 31 * result + Float.hashCode(shadow);
        result = 31 * result + tintOverDark;
        result = 31 * result + tintOverLight;
        result = 31 * result + Float.hashCode(adaptPoint);
        result = 31 * result + Boolean.hashCode(lensEdge);
        return result;
    }

    @Override
    public String toString() {
        return "GlassLook{rimWidth=" + rimWidth + ", bend=" + bend + ", frost=" + frost
                + ", rimLight=" + rimLight + ", dispersion=" + dispersion + ", shadow=" + shadow
                + ", tintOverDark=#" + Integer.toHexString(tintOverDark)
                + ", tintOverLight=#" + Integer.toHexString(tintOverLight)
                + ", adaptPoint=" + adaptPoint + ", lensEdge=" + lensEdge + "}";
    }
}
