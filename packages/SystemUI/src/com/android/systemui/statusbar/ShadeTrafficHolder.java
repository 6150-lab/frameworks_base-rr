/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import org.lineageos.internal.statusbar.LineageStatusBarItem;

import java.util.ArrayList;

public class ShadeTrafficHolder extends FrameLayout implements LineageStatusBarItem.Manager {

    private final ArrayList<LineageStatusBarItem.DarkReceiver> mDarkReceivers =
            new ArrayList<>();
    private final ArrayList<LineageStatusBarItem.VisibilityReceiver> mVisibilityReceivers =
            new ArrayList<>();
    private final ArrayList<Rect> mAreas = new ArrayList<>();

    private int mTint = Color.WHITE;
    private boolean mVisible;

    public ShadeTrafficHolder(Context context) {
        this(context, null);
    }

    public ShadeTrafficHolder(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setTint(int tint) {
        mTint = tint;
        for (LineageStatusBarItem.DarkReceiver r : mDarkReceivers) {
            r.onDarkChanged(mAreas, darkIntensity(), mTint);
        }
    }

    private float darkIntensity() {
        return Color.luminance(mTint) < 0.5f ? 1f : 0f;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible == mVisible) {
            return;
        }
        mVisible = isVisible;
        for (LineageStatusBarItem.VisibilityReceiver r : mVisibilityReceivers) {
            r.onVisibilityChanged(mVisible);
        }
    }

    @Override
    public void addDarkReceiver(LineageStatusBarItem.DarkReceiver darkReceiver) {
        mDarkReceivers.add(darkReceiver);
        darkReceiver.onDarkChanged(mAreas, darkIntensity(), mTint);
    }

    @Override
    public void removeDarkReceiver(LineageStatusBarItem.DarkReceiver darkReceiver) {
        mDarkReceivers.remove(darkReceiver);
    }

    @Override
    public void addVisibilityReceiver(LineageStatusBarItem.VisibilityReceiver receiver) {
        mVisibilityReceivers.add(receiver);
        receiver.onVisibilityChanged(mVisible);
    }

    @Override
    public void removeVisibilityReceiver(LineageStatusBarItem.VisibilityReceiver receiver) {
        mVisibilityReceivers.remove(receiver);
    }
}
