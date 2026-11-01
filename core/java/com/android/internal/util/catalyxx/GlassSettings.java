/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

import android.annotation.Nullable;
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

    // How glass with a curved rim is drawn, see look(). So far SurfaceFlinger draws the rim on
    // the volume dialog, the lock screen's buttons, the power menu, SystemUI's bottom sheets and
    // notifications over the lock screen or an app; Quick Settings tiles and notifications in the
    // shade draw its light themselves, over the shade's blur (SystemUI's GlassRimShader); other
    // places are plain blur.

    /**
     * {@link #STYLE_CLEAR} (the default), {@link #STYLE_FROSTED} or {@link #STYLE_FLAT}.
     */
    public static final String STYLE = "catalyxx_glass_style";

    /** Plain blur without a rim. */
    public static final int STYLE_FLAT = 0;
    /**
     * A clear middle, with a curved rim that bends in what's past the edge. Only for buttons and
     * sliders: places with text on their glass get a frosted middle, so the text reads.
     */
    public static final int STYLE_CLEAR = 1;
    /** The same rim around a frosted middle. */
    public static final int STYLE_FROSTED = 2;

    /** How far the rim reaches past the edge to bend in what's there, from 0 (flat) to 100. */
    public static final String BEND = "catalyxx_glass_bend";

    /** Width of the curved rim, in dp. */
    public static final String RIM_WIDTH = "catalyxx_glass_rim_width";

    /** Light on the rim, from 0 (none) to 100. */
    public static final String RIM_LIGHT = "catalyxx_glass_rim_light";

    /** How much the rim splits light into rainbow colors, from 0 (none) to 100. */
    public static final String RAINBOW = "catalyxx_glass_rainbow";

    /** The soft shadow around the glass, from 0 (none) to 100. */
    public static final String SHADOW = "catalyxx_glass_shadow";

    /**
     * 1 (the default) moves the light on the rim as the phone tilts, as if it hung above, see
     * {@link GlassTilt}; 0 keeps it at the top left.
     */
    public static final String TILT = "catalyxx_glass_tilt";

    /**
     * How far the light follows the phone's tilt, from 25 to 200 (percent). 100, the default,
     * keeps it where a light above would be.
     */
    public static final String TILT_AMOUNT = "catalyxx_glass_tilt_amount";

    /**
     * 1 (the default) lets glass with a curved rim turn smoky where what's behind it is light, so
     * what sits on it stays readable; 0 keeps its color everywhere.
     */
    public static final String ADAPTIVE = "catalyxx_glass_adaptive";

    /** How smoky glass with a curved rim turns over light content, from 0 (not at all) to 100. */
    public static final String SMOKE = "catalyxx_glass_smoke";

    /** {@link #SMOKE_BLACK} (the default) or {@link #SMOKE_THEME}. */
    public static final String SMOKE_COLOR = "catalyxx_glass_smoke_color";

    /** Smoke of plain black. */
    public static final int SMOKE_BLACK = 0;
    /** Smoke in a dark shade of the theme's accent color. */
    public static final int SMOKE_THEME = 1;

    /** How light what's behind has to be before glass turns smoky, from 10 (soon) to 90 (late). */
    public static final String SWITCH_POINT = "catalyxx_glass_switch_point";

    /**
     * 1 (the default) lights glass up under a finger touching it, so far on Quick Settings
     * tiles; 0 turns that off.
     */
    public static final String TOUCH_GLOW = "catalyxx_glass_touch_glow";

    /** {@link #GLOW_WHITE} (the default), {@link #GLOW_ACCENT} or {@link #GLOW_RAINBOW}. */
    public static final String TOUCH_GLOW_COLOR = "catalyxx_glass_touch_glow_color";

    /** White light under the finger. */
    public static final int GLOW_WHITE = 0;
    /** Light in the theme's accent color. */
    public static final int GLOW_ACCENT = 1;
    /** Light that turns through the rainbow around the finger. */
    public static final int GLOW_RAINBOW = 2;

    /** Size of the touch glow, from 50 to 200 (percent of the usual). */
    public static final String TOUCH_GLOW_SIZE = "catalyxx_glass_touch_glow_size";

    /**
     * How glass buttons, cards and the music player squish while pressed and spring back:
     * {@link #SQUISH_OFF}, {@link #SQUISH_SUBTLE}, {@link #SQUISH_NORMAL} (the default) or
     * {@link #SQUISH_BOUNCY}.
     */
    public static final String SQUISH = "catalyxx_glass_squish";

    /** Glass stays still under a finger. */
    public static final int SQUISH_OFF = 0;
    /** A slight squish that settles back without a bounce. */
    public static final int SQUISH_SUBTLE = 1;
    /** A squish that springs back with a little bounce. */
    public static final int SQUISH_NORMAL = 2;
    /** A deeper squish that wobbles as it springs back. */
    public static final int SQUISH_BOUNCY = 3;

    /**
     * A buzz when you tap glass where Android has none of its own: {@link #TAP_BUZZ_OFF} (the
     * default), {@link #TAP_BUZZ_LIGHT} or {@link #TAP_BUZZ_MEDIUM}. It follows the system's
     * touch vibration setting.
     */
    public static final String TAP_BUZZ = "catalyxx_glass_tap_buzz";

    public static final int TAP_BUZZ_OFF = 0;
    /** Android's light tick. */
    public static final int TAP_BUZZ_LIGHT = 1;
    /** Android's click, as for a key. */
    public static final int TAP_BUZZ_MEDIUM = 2;

    /**
     * 1 (the default) fades notifications softly as they scroll up under the shade's header,
     * where notifications have glass; 0 cuts them off there, as Android does.
     */
    public static final String SCROLL_FADE = "catalyxx_glass_scroll_fade";

    /**
     * 1 (the default) turns glass back into plain panels in Battery Saver, like all other blur.
     * 0 keeps blur on in Battery Saver while glass is on; Android has one blur switch for the
     * whole system there, so apps keep their blur too.
     */
    public static final String SOLID_IN_BATTERY_SAVER = "catalyxx_glass_solid_in_battery_saver";

    /**
     * How the music player looks where notifications have glass: {@link #PLAYER_TINTED} (the
     * default), {@link #PLAYER_CLEAR} or {@link #PLAYER_ALBUM_ART}.
     */
    public static final String MUSIC_PLAYER = "catalyxx_glass_music_player";

    /** Glass tinted with the album art's color. */
    public static final int PLAYER_TINTED = 0;
    /** Clear glass, as notifications have it. */
    public static final int PLAYER_CLEAR = 1;
    /** The album art, as Android shows it, with the glass rim on it. */
    public static final int PLAYER_ALBUM_ART = 2;

    // Places glass can show. Each is on by default once glass is on; 0 turns it off there.

    /** Quick Settings and the notification shade. */
    public static final String PLACE_SHADE = "catalyxx_glass_shade";

    /**
     * Notifications and the music player: in the shade, on the lock screen and as heads-up.
     */
    public static final String PLACE_NOTIFICATIONS = "catalyxx_glass_notifications";

    /** The lock screen's shortcut buttons and fingerprint button. */
    public static final String PLACE_LOCKSCREEN = "catalyxx_glass_lockscreen";

    /** The volume dialog. */
    public static final String PLACE_VOLUME = "catalyxx_glass_volume";

    /** The power menu. */
    public static final String PLACE_POWER_MENU = "catalyxx_glass_power_menu";

    /**
     * 1 blurs the whole screen behind the power menu when its panel is glass with a curved rim.
     * 0 (the default) only dims it, so the rim has something sharp to bend.
     */
    public static final String POWER_MENU_BLUR_BEHIND = "catalyxx_glass_power_menu_blur_behind";

    /** SystemUI menus and dialogs, such as the Internet, Bluetooth and volume panel sheets. */
    public static final String PLACE_DIALOGS = "catalyxx_glass_dialogs";

    /** Every place key. */
    public static final String[] PLACES = {
        PLACE_SHADE, PLACE_NOTIFICATIONS, PLACE_LOCKSCREEN, PLACE_VOLUME, PLACE_POWER_MENU,
        PLACE_DIALOGS,
    };

    /** Every glass key, for watching all of them at once. */
    public static final String[] KEYS = {
        ENABLED, BLUR_STRENGTH, COLOR, TINT, STYLE, BEND, RIM_WIDTH, RIM_LIGHT, RAINBOW, SHADOW,
        TILT, TILT_AMOUNT, ADAPTIVE, SMOKE, SMOKE_COLOR, SWITCH_POINT, TOUCH_GLOW,
        TOUCH_GLOW_COLOR, TOUCH_GLOW_SIZE, SQUISH, TAP_BUZZ, SCROLL_FADE, SOLID_IN_BATTERY_SAVER,
        MUSIC_PLAYER, PLACE_SHADE, PLACE_NOTIFICATIONS, PLACE_LOCKSCREEN, PLACE_VOLUME,
        PLACE_POWER_MENU, PLACE_DIALOGS, POWER_MENU_BLUR_BEHIND,
    };

    public static final int MIN_BLUR_STRENGTH = 0;
    public static final int MAX_BLUR_STRENGTH = 100;
    /** Keeps each place's own blur radius. */
    public static final int DEFAULT_BLUR_STRENGTH = 50;

    public static final int MIN_TINT = 0;
    public static final int MAX_TINT = 100;
    /** Light enough that what's behind shows clearly, enough to tell glass from a hole. */
    public static final int DEFAULT_TINT = 20;

    public static final int DEFAULT_STYLE = STYLE_CLEAR;

    // BEND, RIM_LIGHT, RAINBOW and SHADOW all run from 0 to 100
    public static final int MIN_PERCENT = 0;
    public static final int MAX_PERCENT = 100;

    public static final int DEFAULT_BEND = 50;
    public static final int DEFAULT_RIM_LIGHT = 70;
    public static final int DEFAULT_RAINBOW = 50;
    public static final int DEFAULT_SHADOW = 35;

    public static final int MIN_RIM_WIDTH = 4;
    public static final int MAX_RIM_WIDTH = 32;
    public static final int DEFAULT_RIM_WIDTH = 16;

    public static final int MIN_TILT_AMOUNT = 25;
    public static final int MAX_TILT_AMOUNT = 200;
    public static final int DEFAULT_TILT_AMOUNT = 100;

    /** Dark enough that white icons and text on glass over a white page stay readable. */
    public static final int DEFAULT_SMOKE = 45;

    public static final int MIN_SWITCH_POINT = 10;
    public static final int MAX_SWITCH_POINT = 90;
    /** A little past mid-grey, so glass over everyday dark content keeps its own color. */
    public static final int DEFAULT_SWITCH_POINT = 55;

    public static final int MIN_TOUCH_GLOW_SIZE = 50;
    public static final int MAX_TOUCH_GLOW_SIZE = 200;
    public static final int DEFAULT_TOUCH_GLOW_SIZE = 100;

    public static final int DEFAULT_SQUISH = SQUISH_NORMAL;

    // How far the rim reaches at BEND 100, in rim widths, and the shadow's darkness at SHADOW 100
    private static final float MAX_BEND = 2.4f;
    private static final float MAX_SHADOW = 0.6f;

    // How much more smoke glass with text on it gets over light content: the default 45 becomes
    // 61, enough for white text on smoke over a white page to read (about 5.7:1)
    private static final float TEXT_SMOKE_SCALE = 1.35f;

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

    /** Whether glass at {@code place} has a curved rim: it's on there and not Flat. */
    public static boolean hasRim(ContentResolver resolver, String place) {
        return isOn(resolver, place)
                && Settings.Global.getInt(resolver, STYLE, DEFAULT_STYLE) != STYLE_FLAT;
    }

    /**
     * Whether the power menu blurs the whole screen behind it: always as Android does, except
     * that glass with a curved rim only dims it unless {@link #POWER_MENU_BLUR_BEHIND} is on.
     */
    public static boolean blursBehindPowerMenu(ContentResolver resolver) {
        return !hasRim(resolver, PLACE_POWER_MENU)
                || Settings.Global.getInt(resolver, POWER_MENU_BLUR_BEHIND, 0) == 1;
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

    /**
     * How glass with a curved rim is drawn at {@code place} now, with sizes in pixels for
     * {@code context}'s display; null where it's plain blur, because glass is off there or set
     * to {@link #STYLE_FLAT}. Its color over dark content is {@link #tintColor}'s, with
     * {@code androidColor} as Android's own tint there; the glass draws it itself, so the place
     * leaves its own tint out.
     */
    @Nullable
    public static GlassLook look(Context context, String place, int androidColor) {
        final ContentResolver resolver = context.getContentResolver();
        if (!isOn(resolver, place)) {
            return null;
        }
        final int style = Settings.Global.getInt(resolver, STYLE, DEFAULT_STYLE);
        final int smoke = Settings.Global.getInt(resolver, SMOKE, DEFAULT_SMOKE);
        // Glass with text on it gets a frosted middle whatever the style, and more smoke over
        // light content, so the text on it reads
        final boolean carriesText = PLACE_DIALOGS.equals(place)
                || PLACE_POWER_MENU.equals(place) || PLACE_SHADE.equals(place)
                || PLACE_NOTIFICATIONS.equals(place);
        return look(context,
                carriesText && style == STYLE_CLEAR ? STYLE_FROSTED : style,
                Settings.Global.getInt(resolver, BEND, DEFAULT_BEND),
                Settings.Global.getInt(resolver, RIM_WIDTH, DEFAULT_RIM_WIDTH),
                Settings.Global.getInt(resolver, RIM_LIGHT, DEFAULT_RIM_LIGHT),
                Settings.Global.getInt(resolver, RAINBOW, DEFAULT_RAINBOW),
                Settings.Global.getInt(resolver, SHADOW, DEFAULT_SHADOW),
                tintColor(context, place, androidColor),
                carriesText ? Math.min(MAX_PERCENT, Math.round(smoke * TEXT_SMOKE_SCALE)) : smoke,
                Settings.Global.getInt(resolver, SMOKE_COLOR, SMOKE_BLACK),
                Settings.Global.getInt(resolver, ADAPTIVE, 1),
                Settings.Global.getInt(resolver, SWITCH_POINT, DEFAULT_SWITCH_POINT));
    }

    /**
     * The look of glass with these {@link #STYLE}, {@link #BEND}, {@link #RIM_WIDTH},
     * {@link #RIM_LIGHT}, {@link #RAINBOW}, {@link #SHADOW}, {@link #SMOKE},
     * {@link #SMOKE_COLOR}, {@link #ADAPTIVE} and {@link #SWITCH_POINT} settings, and
     * {@code tint} as its ARGB color over dark content (see {@link #tintColor}); sizes in pixels
     * for {@code context}'s display, null for {@link #STYLE_FLAT}.
     */
    @Nullable
    public static GlassLook look(Context context, int style, int bend, int rimWidth,
            int rimLight, int rainbow, int shadow, int tint, int smoke, int smokeColor,
            int adaptive, int switchPoint) {
        if (style == STYLE_FLAT) {
            return null;
        }
        final int width = Math.max(MIN_RIM_WIDTH, Math.min(MAX_RIM_WIDTH, rimWidth));
        final int smokeRgb = smokeColor == SMOKE_THEME
                ? context.getColor(android.R.color.system_accent1_900)
                : Color.BLACK;
        final int point = Math.max(MIN_SWITCH_POINT, Math.min(MAX_SWITCH_POINT, switchPoint));
        return new GlassLook(
                width * context.getResources().getDisplayMetrics().density,
                MAX_BEND * fraction(bend),
                style == STYLE_FROSTED ? 1f : 0f,
                fraction(rimLight),
                fraction(rainbow),
                MAX_SHADOW * fraction(shadow),
                tint,
                (Math.round(fraction(smoke) * 255) << 24) | (smokeRgb & 0xffffff),
                adaptive == 1 ? point / 100f : 0f);
    }

    /**
     * How far the light on glass follows the phone's tilt: 1 keeps it where a light above would
     * be, 0 means {@link #TILT} is off.
     */
    public static float tiltAmount(ContentResolver resolver) {
        if (Settings.Global.getInt(resolver, TILT, 1) != 1) {
            return 0f;
        }
        final int amount = Settings.Global.getInt(resolver, TILT_AMOUNT, DEFAULT_TILT_AMOUNT);
        return Math.max(MIN_TILT_AMOUNT, Math.min(MAX_TILT_AMOUNT, amount)) / 100f;
    }

    /** A 0 to 100 setting as 0 to 1. */
    private static float fraction(int value) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, value)) / (float) MAX_PERCENT;
    }

    /** Whether glass keeps the system's blur on while Battery Saver is on. */
    public static boolean keepsBlurInBatterySaver(ContentResolver resolver) {
        return isEnabled(resolver)
                && Settings.Global.getInt(resolver, SOLID_IN_BATTERY_SAVER, 1) == 0;
    }
}
