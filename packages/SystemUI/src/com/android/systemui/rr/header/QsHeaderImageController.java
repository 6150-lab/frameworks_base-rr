/*
 * Copyright (C) 2026 The Resurrection Remix Project
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

package com.android.systemui.rr.header;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.res.R;

/**
 * Shows the Remixer header image at the top of the expanded shade, fading in with the shade.
 */
public final class QsHeaderImageController
        implements StatusBarHeaderMachine.IStatusBarHeaderMachineObserver {
    private static final int HEIGHT_DEFAULT_DP = 142;

    private final Context mContext;
    private final QsHeaderImageView mView;
    private final StatusBarHeaderMachine mMachine;
    private final ContentObserver mSettings;

    private boolean mAttached;
    private boolean mEnabled;
    private int mHeightDp = HEIGHT_DEFAULT_DP;
    private int mShadow;
    private float mExpansion = -1f;
    private Drawable mCurrent;

    public QsHeaderImageController(View panel) {
        mContext = panel.getContext();
        mView = panel.requireViewById(R.id.qs_header_image);
        mMachine = new StatusBarHeaderMachine(mContext);
        mSettings = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                readSettings();
                applyLayout();
                update(mExpansion);
            }
        };
    }

    public void attach() {
        if (mAttached) {
            return;
        }
        mAttached = true;
        ContentResolver resolver = mContext.getContentResolver();
        for (String key : new String[] {
                Settings.System.STATUS_BAR_CUSTOM_HEADER,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW}) {
            resolver.registerContentObserver(Settings.System.getUriFor(key), false, mSettings,
                    UserHandle.USER_ALL);
        }
        readSettings();
        applyLayout();
        mMachine.addObserver(this);
        mMachine.updateEnablement();
    }

    public void detach() {
        if (!mAttached) {
            return;
        }
        mAttached = false;
        mContext.getContentResolver().unregisterContentObserver(mSettings);
        mMachine.removeObserver(this);
    }

    /** @param expansion shade expansion, 0 collapsed to 1 expanded */
    public void update(float expansion) {
        mExpansion = expansion;
        boolean landscape = mView.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        boolean show = mEnabled && expansion > 0f && !landscape && mCurrent != null;
        if (!show) {
            if (mView.getVisibility() != View.GONE) {
                mView.setVisibility(View.GONE);
                stopAnimation();
            }
            return;
        }
        if (mView.getVisibility() != View.VISIBLE) {
            mView.setVisibility(View.VISIBLE);
        }
        mView.setImageAlpha(Math.round(Math.min(1f, expansion) * (255 - mShadow)));
        startAnimation();
    }

    @Override
    public void updateHeader(Drawable headerImage, boolean force) {
        mView.post(() -> setImage(headerImage, force));
    }

    @Override
    public void disableHeader() {
        mView.post(() -> setImage(null, true));
    }

    @Override
    public void refreshHeader() {
        mView.post(() -> {
            readSettings();
            update(mExpansion);
        });
    }

    private void readSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mEnabled = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0, UserHandle.USER_CURRENT) == 1;
        mHeightDp = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT, HEIGHT_DEFAULT_DP,
                UserHandle.USER_CURRENT);
        mShadow = Math.max(0, Math.min(255, Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW, 0, UserHandle.USER_CURRENT)));
    }

    private void applyLayout() {
        int height = Math.round(mHeightDp * mView.getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = mView.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            mView.setLayoutParams(lp);
        }
        mView.setFadeSize(Math.round(height * 0.55f));
    }

    private void setImage(Drawable image, boolean force) {
        stopAnimation();
        Drawable current = mView.getDrawable();
        if (image != null && current != null && !force && !(image instanceof Animatable)
                && !(current instanceof Animatable)) {
            TransitionDrawable fade = new TransitionDrawable(new Drawable[] {current, image});
            fade.setCrossFadeEnabled(true);
            mView.setImageDrawable(fade);
            fade.startTransition(1000);
        } else {
            mView.setImageDrawable(image);
        }
        mCurrent = image;
        update(mExpansion);
    }

    private void startAnimation() {
        Drawable drawable = mView.getDrawable();
        if (drawable == null) {
            return;
        }
        drawable.setVisible(true, false);
        if (drawable instanceof Animatable anim && !anim.isRunning()) {
            anim.start();
        }
    }

    private void stopAnimation() {
        Drawable drawable = mView.getDrawable();
        if (drawable instanceof Animatable anim && anim.isRunning()) {
            anim.stop();
        }
        if (drawable != null) {
            drawable.setVisible(false, false);
        }
    }
}
