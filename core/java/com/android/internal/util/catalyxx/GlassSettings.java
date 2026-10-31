/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.provider.Settings;

/**
 * Catalyxx's system glass: blurred, glass-like SystemUI panels, set up on the Lab's glass page.
 *
 * <p>All keys are {@link Settings.Global} ints. Glass is off by default, and wherever it is off
 * a place looks the way it does without it. Glass never forces blur on: blur still follows
 * {@link Settings.Global#DISABLE_WINDOW_BLURS}, the thermal limit and (unless
 * {@link #SOLID_IN_BATTERY_SAVER} is off) Battery Saver.
 */
public final class GlassSettings {

    /** The main switch: 1 is on. Off by default. */
    public static final String ENABLED = "catalyxx_glass_enabled";

    /** How much glass blurs what's behind it, from 0 (light haze) to 100 (fully frosted). */
    public static final String BLUR_STRENGTH = "catalyxx_glass_blur_strength";

    /**
     * The color glass tints what it blurs with: {@link #COLOR_FROSTED} (the default),
     * {@link #COLOR_THEME} or {@link #COLOR_ANDROID}.
     */
    public static final String COLOR = "catalyxx_glass_color";

    /** Frosted white glass. */
    public static final int COLOR_FROSTED = 0;
    /** Glass in a light shade of the theme's accent color. */
    public static final int COLOR_THEME = 1;
    /** Android's own tint for blurred panels; {@link #TINT} doesn't change it. */
    public static final int COLOR_ANDROID = 2;

    /** How much the glass color covers what's behind, from 0 (clear) to 100 (solid). */
    public static final String TINT = "catalyxx_glass_tint";

    /**
     * 1 (the default) turns glass back into plain panels in Battery Saver, like all other blur.
     * 0 keeps blur on in Battery Saver while glass is on; Android has one blur switch for the
     * whole system there, so apps keep their blur too.
     */
    public static final String SOLID_IN_BATTERY_SAVER = "catalyxx_glass_solid_in_battery_saver";

    // Places glass can show. Each is on by default once glass is on; 0 turns it off there.

    /** Quick Settings and the notification shade. */
    public static final String PLACE_SHADE = "catalyxx_glass_shade";

    /** The lock screen's shortcut buttons and fingerprint button. */
    public static final String PLACE_LOCKSCREEN = "catalyxx_glass_lockscreen";

    /** The volume dialog. */
    public static final String PLACE_VOLUME = "catalyxx_glass_volume";

    /** The power menu. */
    public static final String PLACE_POWER_MENU = "catalyxx_glass_power_menu";

    /** SystemUI menus and dialogs, such as the Internet, Bluetooth and volume panel sheets. */
    public static final String PLACE_DIALOGS = "catalyxx_glass_dialogs";

    /** Every place key. */
    public static final String[] PLACES = {
        PLACE_SHADE, PLACE_LOCKSCREEN, PLACE_VOLUME, PLACE_POWER_MENU, PLACE_DIALOGS,
    };

    /** Every glass key, for watching all of them at once. */
    public static final String[] KEYS = {
        ENABLED, BLUR_STRENGTH, COLOR, TINT, SOLID_IN_BATTERY_SAVER,
        PLACE_SHADE, PLACE_LOCKSCREEN, PLACE_VOLUME, PLACE_POWER_MENU, PLACE_DIALOGS,
    };

    public static final int MIN_BLUR_STRENGTH = 0;
    public static final int MAX_BLUR_STRENGTH = 100;
    /** Keeps each place's own blur radius. */
    public static final int DEFAULT_BLUR_STRENGTH = 50;

    public static final int MIN_TINT = 0;
    public static final int MAX_TINT = 100;
    /** Light enough that what's behind shows clearly, enough to tell glass from a hole. */
    public static final int DEFAULT_TINT = 20;

    // What a place's blur radius is multiplied by at the lowest and highest strength
    private static final float MIN_BLUR_SCALE = 0.2f;
    private static final float MAX_BLUR_SCALE = 2f;

    private GlassSettings() {}

    /** Whether the main switch is on. */
    public static boolean isEnabled(ContentResolver resolver) {
        return Settings.Global.getInt(resolver, ENABLED, 0) == 1;
    }

    /** Whether glass is on for {@code place}, one of the {@code PLACE_} keys. */
    public static boolean isOn(ContentResolver resolver, String place) {
        return isEnabled(resolver) && Settings.Global.getInt(resolver, place, 1) == 1;
    }

    /**
     * What glass multiplies a blur radius by at {@code strength}: 1 at the default 50, falling
     * to 0.2 at 0 and rising to 2 at 100.
     */
    public static float blurScale(int strength) {
        final int s = Math.max(MIN_BLUR_STRENGTH, Math.min(MAX_BLUR_STRENGTH, strength));
        if (s <= DEFAULT_BLUR_STRENGTH) {
            return MIN_BLUR_SCALE + (1f - MIN_BLUR_SCALE) * s / DEFAULT_BLUR_STRENGTH;
        }
        return 1f + (MAX_BLUR_SCALE - 1f) * (s - DEFAULT_BLUR_STRENGTH)
                / (MAX_BLUR_STRENGTH - DEFAULT_BLUR_STRENGTH);
    }

    /** What glass multiplies {@code place}'s blur radius by now: 1 while glass is off there. */
    public static float blurScale(ContentResolver resolver, String place) {
        if (!isOn(resolver, place)) {
            return 1f;
        }
        return blurScale(Settings.Global.getInt(resolver, BLUR_STRENGTH, DEFAULT_BLUR_STRENGTH));
    }

    /** {@code radius} multiplied by {@code scale}; a radius above 0 stays above 0. */
    public static int scaleBlurRadius(int radius, float scale) {
        if (radius <= 0) {
            return radius;
        }
        return Math.max(1, Math.round(radius * scale));
    }

    /**
     * The color glass tints {@code place} with over its blur: {@code androidColor}, Android's
     * own tint there, while glass is off there or set to {@link #COLOR_ANDROID}.
     */
    public static int tintColor(Context context, String place, int androidColor) {
        final ContentResolver resolver = context.getContentResolver();
        if (!isOn(resolver, place)) {
            return androidColor;
        }
        return tintColor(context, Settings.Global.getInt(resolver, COLOR, COLOR_FROSTED),
                Settings.Global.getInt(resolver, TINT, DEFAULT_TINT), androidColor);
    }

    /**
     * The tint of glass color {@code color} at {@code tint}, one of the {@code COLOR_} values
     * and 0 to 100; {@code androidColor} for {@link #COLOR_ANDROID}.
     */
    public static int tintColor(Context context, int color, int tint, int androidColor) {
        final int rgb;
        if (color == COLOR_ANDROID) {
            return androidColor;
        } else if (color == COLOR_THEME) {
            // A light accent shade, so it frosts like the white instead of darkening
            rgb = context.getColor(context.getResources().getConfiguration().isNightModeActive()
                    ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_100);
        } else {
            rgb = Color.WHITE;
        }
        final int t = Math.max(MIN_TINT, Math.min(MAX_TINT, tint));
        return (Math.round(t * 255f / MAX_TINT) << 24) | (rgb & 0xffffff);
    }

    /** Whether glass keeps the system's blur on while Battery Saver is on. */
    public static boolean keepsBlurInBatterySaver(ContentResolver resolver) {
        return isEnabled(resolver)
                && Settings.Global.getInt(resolver, SOLID_IN_BATTERY_SAVER, 1) == 0;
    }
}
