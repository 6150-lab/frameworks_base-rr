/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.systemui.LauncherProxyService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.rr.slim.SlimRecents;
import com.android.systemui.shared.recents.ILauncherProxy;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/**
 * An implementation of the Recents interface which proxies to the LauncherProxyService.
 */
@SysUISingleton
public class OverviewProxyRecentsImpl implements RecentsImplementation {

    private final static String TAG = "OverviewProxyRecentsImpl";
    private Handler mHandler;
    private Context mContext;
    private final LauncherProxyService mLauncherProxyService;
    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguardStateController;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyRecentsImpl(
            LauncherProxyService launcherProxyService,
            ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController) {
        mLauncherProxyService = launcherProxyService;
        mActivityStarter = activityStarter;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    public void onStart(Context context) {
        mHandler = new Handler();
        mContext = context;
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        // Alt-tab and the legacy show path. Gesture overview does not come through here.
        if (slimEnabled() && !mKeyguardStateController.isShowing()
                && SlimRecents.show(mContext)) {
            return;
        }
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            try {
                launcherProxy.onOverviewShown(triggeredFromAltTab);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        }
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (slimEnabled()) {
            SlimRecents.hide();
        }
        // Home still has to close a launcher overview opened with a gesture.
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            try {
                launcherProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
        }
    }

    @Override
    public void toggleRecentApps() {
        if (slimEnabled()) {
            toggleSlim();
            return;
        }
        toggleLauncher();
    }

    private boolean slimEnabled() {
        return mContext != null && SlimRecents.enabled(mContext);
    }

    /** Recents button. Wait until the keyguard is gone so the list is not shown over the lock. */
    private void toggleSlim() {
        final Runnable toggleSlim = () -> {
            if (mContext == null || !SlimRecents.toggle(mContext)) {
                toggleLauncher();
            }
        };
        if (mKeyguardStateController.isShowing()) {
            mActivityStarter.executeRunnableDismissingKeyguard(
                    () -> mHandler.post(toggleSlim), null, true /* dismissShade */,
                    true /* afterKeyguardGone */,
                    true /* deferred */);
        } else {
            toggleSlim.run();
        }
    }

    private void toggleLauncher() {
        // If connected to launcher service, let it handle the toggle logic
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            final Runnable toggleRecents = () -> {
                try {
                    if (mLauncherProxyService.getProxy() != null) {
                        mLauncherProxyService.getProxy().onOverviewToggle();
                        mLauncherProxyService.notifyToggleRecentApps();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot send toggle recents through proxy service.", e);
                }
            };
            // Preload only if device for current user is unlocked
            if (mKeyguardStateController.isShowing()) {
                mActivityStarter.executeRunnableDismissingKeyguard(
                        () -> mHandler.post(toggleRecents), null, true /* dismissShade */,
                        false /* afterKeyguardGone */,
                        true /* deferred */);
            } else {
                toggleRecents.run();
            }
        }
    }
}
