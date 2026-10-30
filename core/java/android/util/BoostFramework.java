/*
 * Copyright (c) 2017-2018, The Linux Foundation. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 */

package android.util;

import dalvik.system.PathClassLoader;

import java.lang.reflect.Method;

/**
 * Client for the stock Qualcomm perf HAL. Loads QPerformance.jar and forwards
 * the same interaction and launch events PowerManager already sends to
 * libperfmgr. The sun perfboostsconfig enables scroll type 5 and launch type 10.
 *
 * @hide
 */
public final class BoostFramework {
    private static final String TAG = "BoostFramework";
    private static final int VENDOR_HINT_SCROLL_BOOST = 0x00001080;
    private static final int VENDOR_HINT_FIRST_LAUNCH_BOOST = 0x00001081;
    /** Enabled sun config: silky scroll at 120 Hz and above. */
    private static final int SCROLL_TYPE = 5;
    /** Enabled sun config: activity launch. Types 1, 2, 3, and 6 are off. */
    private static final int LAUNCH_TYPE = 10;

    private static BoostFramework sInstance;

    private final Object mPerf;
    private Method mHint;

    private BoostFramework() {
        Object perf = null;
        Method hint = null;
        try {
            PathClassLoader loader = new PathClassLoader("/system/framework/QPerformance.jar",
                    ClassLoader.getSystemClassLoader());
            Class<?> perfClass = loader.loadClass("com.qualcomm.qti.Performance");
            perf = perfClass.getDeclaredConstructor().newInstance();
            hint = perfClass.getMethod("perfHint", int.class, String.class, int.class, int.class);
        } catch (Throwable t) {
            Log.w(TAG, "QPerformance unavailable", t);
        }
        mPerf = perf;
        mHint = hint;
    }

    /** Touch, fling, and window animation boost. */
    public static void onInteraction(int durationMs) {
        hint(VENDOR_HINT_SCROLL_BOOST, durationMs, SCROLL_TYPE);
    }

    /** App launch boost. Pass enabled false when the launch mode ends. */
    public static void onLaunch(boolean enabled) {
        hint(VENDOR_HINT_FIRST_LAUNCH_BOOST, enabled ? 0 : -1, LAUNCH_TYPE);
    }

    private static void hint(int hintId, int durationMs, int type) {
        BoostFramework inst = instance();
        if (inst.mHint == null) {
            return;
        }
        try {
            inst.mHint.invoke(inst.mPerf, hintId, null, durationMs, type);
        } catch (Throwable t) {
            Log.w(TAG, "perfHint failed", t);
            inst.mHint = null;
        }
    }

    private static BoostFramework instance() {
        BoostFramework inst = sInstance;
        if (inst == null) {
            synchronized (BoostFramework.class) {
                inst = sInstance;
                if (inst == null) {
                    inst = new BoostFramework();
                    sInstance = inst;
                }
            }
        }
        return inst;
    }
}
