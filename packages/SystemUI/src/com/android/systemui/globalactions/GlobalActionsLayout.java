/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static com.android.systemui.shared.system.BlurUtils.isVolumeAndPowerBlurEnabled;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.catalyxx.GlassLook;
import com.android.internal.util.catalyxx.GlassSettings;
import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.MultiListLayout;
import com.android.systemui.res.R;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Locale;

/**
 * Grid-based implementation of the button layout created by the global actions dialog.
 */
public abstract class GlobalActionsLayout extends MultiListLayout {
    boolean mBackgroundsSet;

    private Boolean mIsBlurSupported = null;
    private final boolean mTranslucentPowerMenu;

    public GlobalActionsLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTranslucentPowerMenu =
                context.getResources().getBoolean(R.bool.config_translucentStandalonePowerMenu);
    }

    public void setIsBlurSupported(boolean isBlurSupported) {
        if (mIsBlurSupported != null && isBlurSupported == mIsBlurSupported) {
            return;
        }

        mIsBlurSupported = isBlurSupported;
        updateIsBlurSupported();
    }

    private void setBackgrounds() {
        ViewGroup listView = getListView();

        int listBgColor = getResources().getColor(
                R.color.global_actions_grid_background, null);
        Drawable listBackground = getBackgroundDrawable(listBgColor);

        if (listBackground != null) {
            listView.setBackground(listBackground);
        }

        ViewGroup separatedView = getSeparatedView();

        if (separatedView != null) {
            int separatedBgColor = getResources().getColor(
                    R.color.global_actions_separated_background, null);
            HardwareBgDrawable separatedBackground = getBackgroundDrawable(separatedBgColor);
            if (separatedBackground != null) {
                getSeparatedView().setBackground(separatedBackground);
            }
        }
    }

    private void updateIsBlurSupported() {
        if (isVolumeAndPowerBlurEnabled(mContext, GlassSettings.PLACE_POWER_MENU)
                && mBackgroundsSet && mIsBlurSupported != null) {
            final boolean isBlurShown = mIsBlurSupported && mTranslucentPowerMenu;
            final int androidColor = mContext.getColor(R.color.global_actions_grid_background_blur);
            // Catalyxx glass's curved rim, if it's on here. It brings its own color.
            final GlassLook glassLook = isBlurShown
                    ? GlassSettings.look(mContext, GlassSettings.PLACE_POWER_MENU, androidColor)
                    : null;
            final boolean hasGlass = updateGlass(glassLook);
            updateBackground(hasGlass
                    ? Color.TRANSPARENT
                    : isBlurShown
                    // Catalyxx glass's tint, if it's on here
                    ? GlassSettings.tintColor(mContext, GlassSettings.PLACE_POWER_MENU,
                            androidColor)
                    : mContext.getColor(R.color.global_actions_grid_background_blur_fallback));
        }
    }

    /**
     * Puts Catalyxx glass with {@code look} under the panel's own background, or takes it away
     * when {@code look} is null. Returns whether the panel has glass now.
     */
    private boolean updateGlass(@Nullable GlassLook look) {
        final ViewGroup listView = getListView();
        final Drawable background = listView.getBackground();
        BackgroundBlurDrawable glass = null;
        if (background instanceof LayerDrawable layers
                && layers.getDrawable(0) instanceof BackgroundBlurDrawable existing) {
            glass = existing;
        }
        if (look == null) {
            if (glass != null) {
                glass.setGlass(null);
                glass.setBlurRadius(0);
            }
            return false;
        }
        if (glass == null) {
            final ViewRootImpl viewRoot = listView.getViewRootImpl();
            if (viewRoot == null || background == null) {
                return false;
            }
            glass = viewRoot.createBackgroundBlurDrawable();
            glass.setCornerRadius(getResources().getDimension(R.dimen.global_actions_corner_radius));
            listView.setBackground(new LayerDrawable(new Drawable[] {glass, background}));
        }
        glass.setBlurRadius(GlassSettings.scaleBlurRadius(
                getResources().getDimensionPixelSize(R.dimen.global_actions_blur_radius),
                GlassSettings.blurScale(mContext.getContentResolver(),
                        GlassSettings.PLACE_POWER_MENU)));
        glass.setGlass(look);
        return true;
    }

    private void updateBackground(int color) {
        Drawable drawable = getListView().getBackground();
        if (drawable instanceof LayerDrawable layers) {
            // The panel's own background, over its glass
            drawable = layers.getDrawable(1);
        }
        if (drawable instanceof GradientDrawable gradientDrawable) {
            gradientDrawable.setColor(color);
        } else {
            drawable.setTint(color);
        }
    }

    protected HardwareBgDrawable getBackgroundDrawable(int backgroundColor) {
        HardwareBgDrawable background = new HardwareBgDrawable(true, true, getContext());
        background.setTint(backgroundColor);
        return background;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // backgrounds set only once, the first time onMeasure is called after inflation
        if (getListView() != null && !mBackgroundsSet) {
            setBackgrounds();
            mBackgroundsSet = true;
            updateIsBlurSupported();
        }
    }

    protected void addToListView(View v, boolean reverse) {
        if (reverse) {
            getListView().addView(v, 0);
        } else {
            getListView().addView(v);
        }
    }

    protected void addToSeparatedView(View v, boolean reverse) {
        ViewGroup separated = getSeparatedView();
        if (separated != null) {
            if (reverse) {
                separated.addView(v, 0);
            } else {
                separated.addView(v);
            }
        } else {
            // if no separated view exists, just use the list view
            addToListView(v, reverse);
        }
    }

    @VisibleForTesting
    protected int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }

    @VisibleForTesting
    protected int getCurrentRotation() {
        return RotationUtils.getRotation(mContext);
    }

    /**
     * Determines whether the ListGridLayout should reverse the ordering of items within sublists.
     * Used for RTL languages to ensure that items appear in the same positions, without having to
     * override layoutDirection, which breaks Talkback ordering.
     */
    protected abstract boolean shouldReverseListItems();

    @Override
    public void onUpdateList() {
        super.onUpdateList();

        ViewGroup separatedView = getSeparatedView();
        ViewGroup listView = getListView();

        for (int i = 0; i < mAdapter.getCount(); i++) {
            // generate the view item
            View v;
            boolean separated = mAdapter.shouldBeSeparated(i);
            if (separated) {
                v = mAdapter.getView(i, null, separatedView);
            } else {
                v = mAdapter.getView(i, null, listView);
            }
            if (separated) {
                addToSeparatedView(v, false);
            } else {
                addToListView(v, shouldReverseListItems());
            }
        }
    }

    @Override
    protected ViewGroup getSeparatedView() {
        return findViewById(R.id.separated_button);
    }

    @Override
    protected ViewGroup getListView() {
        return findViewById(android.R.id.list);
    }

    /**
     * The panel's glass, in this layout's own pixels: where the actions sit. Empty until it's
     * laid out. For Catalyxx glass's morph.
     */
    Rect getGlassBounds() {
        final Rect bounds = new Rect();
        final ViewGroup listView = getListView();
        if (listView != null && listView.isLaidOut()) {
            bounds.set(0, 0, listView.getWidth(), listView.getHeight());
            offsetDescendantRectToMyCoords(listView, bounds);
        }
        return bounds;
    }

    protected View getWrapper() {
        return getChildAt(0);
    }

    /**
     * Not used in this implementation of the Global Actions Menu, but necessary for some others.
     */
    @Override
    public void setDivisionView(View v) {
        // do nothing
    }
}
