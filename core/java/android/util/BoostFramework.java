/*
 * Copyright (c) 2017-2018, The Linux Foundation. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 */

package android.util;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

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
    private static final int DEFAULT_INTERACTION_MS = 1000;
    private static final int CONFIG_TIMEOUT = -1;

    private static volatile Handler sHandler;
    private static BoostFramework sInstance;
    private static volatile long sBoostedUntil;

    private final Object mPerf;
    private Method mHint;
    private Method mRelease;
    private int mLaunchHandle = -1;

    private BoostFramework() {
        Object perf = null;
        Method hint = null;
        Method release = null;
        try {
            PathClassLoader loader = new PathClassLoader("/system/framework/QPerformance.jar",
                    ClassLoader.getSystemClassLoader());
            Class<?> perfClass = loader.loadClass("com.qualcomm.qti.Performance");
            perf = perfClass.getDeclaredConstructor().newInstance();
            hint = perfClass.getMethod("perfHint", int.class, String.class, int.class, int.class);
            release = perfClass.getMethod("perfLockReleaseHandler", int.class);
        } catch (Throwable t) {
            Log.w(TAG, "QPerformance unavailable", t);
        }
        mPerf = perf;
        mHint = hint;
        mRelease = release;
    }

    /** Touch, fling, and window animation boost. */
    public static void onInteraction(int durationMs) {
        final int duration = durationMs > 0 ? durationMs : DEFAULT_INTERACTION_MS;
        // Touches poke every 100 ms; the running hint is renewed once half of it is left.
        final long now = SystemClock.uptimeMillis();
        if (sBoostedUntil - now > duration / 2) {
            return;
        }
        sBoostedUntil = now + duration;
        post(() -> instance().hint(VENDOR_HINT_SCROLL_BOOST, duration, SCROLL_TYPE));
    }

    /** App launch boost. Pass enabled false when the launch mode ends. */
    public static void onLaunch(boolean enabled) {
        post(() -> instance().launch(enabled));
    }

    private void launch(boolean enabled) {
        if (mLaunchHandle > 0) {
            release(mLaunchHandle);
            mLaunchHandle = -1;
        }
        if (enabled) {
            mLaunchHandle = hint(VENDOR_HINT_FIRST_LAUNCH_BOOST, CONFIG_TIMEOUT, LAUNCH_TYPE);
        }
    }

    private int hint(int hintId, int durationMs, int type) {
        if (mHint == null) {
            return -1;
        }
        try {
            Object handle = mHint.invoke(mPerf, hintId, null, durationMs, type);
            return handle instanceof Integer ? (Integer) handle : -1;
        } catch (Throwable t) {
            Log.w(TAG, "perfHint failed", t);
            mHint = null;
            return -1;
        }
    }

    private void release(int handle) {
        if (mRelease == null) {
            return;
        }
        try {
            mRelease.invoke(mPerf, handle);
        } catch (Throwable t) {
            Log.w(TAG, "perfLockReleaseHandler failed", t);
            mRelease = null;
        }
    }

    private static void post(Runnable r) {
        Handler handler = sHandler;
        if (handler == null) {
            synchronized (BoostFramework.class) {
                handler = sHandler;
                if (handler == null) {
                    HandlerThread thread =
                            new HandlerThread(TAG, Process.THREAD_PRIORITY_FOREGROUND);
                    thread.start();
                    handler = new Handler(thread.getLooper());
                    sHandler = handler;
                }
            }
        }
        handler.post(r);
    }

    private static BoostFramework instance() {
        if (sInstance == null) {
            sInstance = new BoostFramework();
        }
        return sInstance;
    }
}
