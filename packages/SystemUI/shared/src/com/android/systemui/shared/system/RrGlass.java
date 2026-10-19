/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shared.system;

import static com.android.systemui.Flags.enableLockscreenBlur;

import android.app.AppGlobals;
import android.app.Application;
import android.os.UserHandle;
import android.provider.Settings;

/** Liquid Glass (Remixer): which SystemUI surfaces may blur. The material lives in system_server. */
public final class RrGlass {
    public static final String QS = "rr_glass_qs";
    public static final String LOCKSCREEN = "rr_glass_lockscreen";
    public static final String VOLUME = "rr_glass_volume";
    public static final String POWER = "rr_glass_power";
    public static final String MENUS = "rr_glass_menus";

    private RrGlass() {}

    /** Whether the user left glass on for this surface. */
    public static boolean isOn(String key) {
        final Application app = AppGlobals.getInitialApplication();
        return app == null || Settings.System.getIntForUser(app.getContentResolver(), key, 1,
                UserHandle.USER_CURRENT) != 0;
    }

    public static boolean isVolumeBlurEnabled() {
        return BlurUtils.isVolumeAndPowerBlurEnabled() && isOn(VOLUME);
    }

    public static boolean isPowerBlurEnabled() {
        return BlurUtils.isVolumeAndPowerBlurEnabled() && isOn(POWER);
    }

    /** Bottom sheet dialogs. */
    public static boolean isDialogBlurEnabled() {
        return BlurUtils.isVolumeAndPowerBlurEnabled() && isOn(MENUS);
    }

    public static boolean isLockscreenBlurEnabled() {
        return enableLockscreenBlur() && isOn(LOCKSCREEN);
    }
}
