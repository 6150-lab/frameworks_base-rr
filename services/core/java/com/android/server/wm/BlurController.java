/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.os.PowerManager.THERMAL_STATUS_SEVERE;
import static android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.ICrossWindowBlurEnabledListener;
import android.view.TunnelModeEnabledListener;

/**
 * Keeps track of the different factors that determine whether cross-window blur is enabled
 * or disabled. Also keeps a list of all interested listeners and notifies them when the
 * blur enabled state changes.
 */
final class BlurController {
    private static final String TAG = "BlurController";

    // Liquid Glass (Remixer). SurfaceFlinger reads the persist.sys.rr.glass.* mirrors.
    private static final String GLASS_MATERIAL = "rr_glass_material";
    private static final String GLASS_BATTERY_SAVER = "rr_glass_battery_saver";
    private static final String GLASS_TILT = "rr_glass_tilt";
    private static final int GLASS_SOLID = 0;
    private static final int GLASS_FROSTED = 1;
    private static final int GLASS_LIQUID = 2;
    private static final String[] GLASS_KEYS = {GLASS_MATERIAL, "rr_glass_blur",
            "rr_glass_tint", "rr_glass_refraction", "rr_glass_accent_tint",
            "rr_glass_accent_color"};
    private static final String[] GLASS_PROPS = {"persist.sys.rr.glass.material",
            "persist.sys.rr.glass.blur", "persist.sys.rr.glass.tint",
            "persist.sys.rr.glass.refraction", "persist.sys.rr.glass.accent_tint",
            "persist.sys.rr.glass.accent_color"};
    private static final int[] GLASS_DEFAULTS = {GLASS_FROSTED, 50, 0, 60, 0, 0xE02437};

    private final Context mContext;
    private final RemoteCallbackList<ICrossWindowBlurEnabledListener>
            mBlurEnabledListeners = new RemoteCallbackList<>();
    // We don't use the WM global lock, because the BlurController is not involved in window
    // drawing and only receives binder calls that don't need synchronization with the rest of WM
    private final Object mLock = new Object();
    private volatile boolean mBlurEnabled;
    private boolean mInPowerSaveMode;
    private boolean mDisabledByThermal;
    private boolean mBlurDisabledSetting;
    private boolean mTunnelModeEnabled = false;
    private int mGlassMaterial = GLASS_FROSTED;
    private boolean mSolidInPowerSave = true;
    private boolean mGlassTilt = true;
    private boolean mInteractive;
    private final GlassTilt mTilt;

    private TunnelModeEnabledListener mTunnelModeListener =
            new TunnelModeEnabledListener(Runnable::run) {
        @Override
        public void onTunnelModeEnabledChanged(boolean tunnelModeEnabled) {
            mTunnelModeEnabled = tunnelModeEnabled;
            updateBlurEnabled();
        }
    };

    BlurController(Context context, PowerManager powerManager) {
        mContext = context;
        mTilt = new GlassTilt(context);
        mInteractive = powerManager.isInteractive();

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    readGlassSettings();
                    updateBlurEnabled();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        || Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    mInteractive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                    updateBlurEnabled();
                } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(
                        intent.getAction())) {
                    // onReceive always gets called on the same thread, so there is no
                    // multi-threaded execution here. Thus, we don't have to hold mLock here.
                    mInPowerSaveMode = powerManager.isPowerSaveMode();
                    updateBlurEnabled();
                }
            }
        }, filter, null, null);
        mInPowerSaveMode = powerManager.isPowerSaveMode();

        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DISABLE_WINDOW_BLURS), false,
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        super.onChange(selfChange);
                        // onChange always gets called on the same thread, so there is no
                        // multi-threaded execution here. Thus, we don't have to hold mLock here.
                        mBlurDisabledSetting = getBlurDisabledSetting();
                        updateBlurEnabled();
                    }
                });
        mBlurDisabledSetting = getBlurDisabledSetting();

        final ContentObserver glassObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                readGlassSettings();
                updateBlurEnabled();
            }
        };
        for (String key : GLASS_KEYS) {
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, glassObserver, UserHandle.USER_ALL);
        }
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(GLASS_BATTERY_SAVER), false, glassObserver,
                UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(GLASS_TILT), false, glassObserver,
                UserHandle.USER_ALL);
        readGlassSettings();

        powerManager.addThermalStatusListener((status) -> {
            mDisabledByThermal = status >= THERMAL_STATUS_SEVERE;
            updateBlurEnabled();
        });
        mDisabledByThermal = powerManager.getCurrentThermalStatus() >= THERMAL_STATUS_SEVERE;

        TunnelModeEnabledListener.register(mTunnelModeListener);

        updateBlurEnabled();
    }

    boolean registerCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener) {
        if (listener == null) return false;
        mBlurEnabledListeners.register(listener);
        return getBlurEnabled();
    }

    void unregisterCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener) {
        if (listener == null) return;
        mBlurEnabledListeners.unregister(listener);
    }

    boolean getBlurEnabled() {
        return mBlurEnabled;
    }

    private void updateBlurEnabled() {
        synchronized (mLock) {
            final boolean newEnabled = CROSS_WINDOW_BLUR_SUPPORTED && !mBlurDisabledSetting
                    && mGlassMaterial != GLASS_SOLID && !(mInPowerSaveMode && mSolidInPowerSave)
                    && !mTunnelModeEnabled && !mDisabledByThermal;
            if (mBlurEnabled != newEnabled) {
                mBlurEnabled = newEnabled;
                notifyBlurEnabledChangedLocked(newEnabled);
            }
            mTilt.setActive(newEnabled && mGlassMaterial == GLASS_LIQUID && mGlassTilt
                    && mInteractive);
        }
    }

    private void notifyBlurEnabledChangedLocked(boolean enabled) {
        int i = mBlurEnabledListeners.beginBroadcast();
        while (i > 0) {
            i--;
            ICrossWindowBlurEnabledListener listener =
                    mBlurEnabledListeners.getBroadcastItem(i);
            try {
                listener.onCrossWindowBlurEnabledChanged(enabled);
            } catch (RemoteException e) {
            }
        }
        mBlurEnabledListeners.finishBroadcast();
    }

    private void readGlassSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        mSolidInPowerSave = Settings.System.getIntForUser(resolver, GLASS_BATTERY_SAVER, 1,
                UserHandle.USER_CURRENT) != 0;
        mGlassTilt = Settings.System.getIntForUser(resolver, GLASS_TILT, 1,
                UserHandle.USER_CURRENT) != 0;
        for (int i = 0; i < GLASS_KEYS.length; i++) {
            final int value = Settings.System.getIntForUser(resolver, GLASS_KEYS[i],
                    GLASS_DEFAULTS[i], UserHandle.USER_CURRENT);
            if (i == 0) {
                mGlassMaterial = value;
            }
            final String prop = Integer.toString(value);
            if (prop.equals(SystemProperties.get(GLASS_PROPS[i]))) {
                continue;
            }
            try {
                SystemProperties.set(GLASS_PROPS[i], prop);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Couldn't set " + GLASS_PROPS[i], e);
            }
        }
    }

    private boolean getBlurDisabledSetting() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DISABLE_WINDOW_BLURS, 0) == 1;
    }
}
