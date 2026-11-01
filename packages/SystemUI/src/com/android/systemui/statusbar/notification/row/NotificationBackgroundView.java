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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.Flags.notificationRowTransparency;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.util.catalyxx.GlassLook;
import com.android.internal.util.catalyxx.GlassSettings;
import com.android.systemui.Dumpable;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.glass.ui.OwnGlass;
import com.android.systemui.glass.ui.OwnGlassViewRim;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.util.Assert;
import com.android.systemui.util.DrawableDumpKt;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View implements Dumpable,
        ExpandableNotificationRow.DismissButtonTargetVisibilityListener {

    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private int mClipTopAmount;
    private int mTopOverlap;
    private int mBottomOverlap;
    private int mClipBottomAmount;
    private int mTintColor;
    @Nullable private Integer mRippleColor;
    private final float[] mCornerRadii = new float[8];
    private final float[] mFocusOverlayCornerRadii = new float[8];
    private float mFocusOverlayStroke = 0;
    private boolean mBottomIsRounded;
    private boolean mBottomAmountClips = true;
    private int mActualHeight = -1;
    private int mActualWidth = -1;
    private boolean mExpandAnimationRunning;
    private int mExpandAnimationWidth = -1;
    private int mExpandAnimationHeight = -1;
    private int mDrawableAlpha = 255;
    private final ColorStateList mLightColoredStatefulColors;
    private final ColorStateList mDarkColoredStatefulColors;
    private int mNormalColor;
    private final int convexR = 9;
    private final int concaveR = 22;
    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    @VisibleForTesting
    protected View.OnAttachStateChangeListener mOnAttachStateChangeListener;

    // True only if the dismiss button is visible.
    private boolean mDrawDismissButtonCutout = false;
    private boolean mOnKeyguard = true;
    // Catalyxx glass: the rim on the notification's background catches the light
    private final OwnGlassViewRim mGlassRim;
    // Catalyxx glass over the lock screen or an app: the blurred background draws as glass
    // (updateGlass()). SurfaceFlinger draws it at the drawable's own alpha and never sees the views
    // above it fade, so before each frame it takes the alpha the background really has.
    private boolean mBlurIsGlass;
    private boolean mBlurAlphaUpdating;
    private final ViewTreeObserver.OnPreDrawListener mBlurAlphaUpdater = () -> {
        updateBlurAlpha();
        return true;
    };

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mGlassRim = new OwnGlassViewRim(this, GlassSettings.PLACE_NOTIFICATIONS);
        mDontModifyCorners = getResources().getBoolean(R.bool.config_clipNotificationsToOutline);
        mLightColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_light);
        mDarkColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_dark);
        if (notificationRowTransparency()) {
            mNormalColor = SurfaceEffectColors.surfaceEffect1(getContext());
        } else  {
            mNormalColor = mContext.getColor(
                    com.android.internal.R.color.materialColorSurfaceContainerHigh);
        }
        mFocusOverlayStroke = getResources().getDimension(R.dimen.notification_focus_stroke_width);
    }

    @Override
    public void onTargetVisibilityChanged(boolean targetVisible) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        if (mDrawDismissButtonCutout != targetVisible) {
            mDrawDismissButtonCutout = targetVisible;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float clipTop = Math.max(mClipTopAmount, mTopOverlap);
        int clipBottomAmount = Math.max(mClipBottomAmount, mBottomOverlap);
        if (clipTop + clipBottomAmount < getActualHeight() || mExpandAnimationRunning) {
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, clipTop, getWidth(),
                        getActualHeight() - clipBottomAmount);
            }

            if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
                // The blur only draws on the screen's own, hardware canvas
                if (mBackgroundBlurDrawable != null && canvas.isHardwareAccelerated()) {
                    drawBlur(canvas);
                } else {
                    draw(canvas, mBackground);
                }
                canvas.restore();
                return;
            }

            Rect backgroundBounds = null;
            if (mBackground != null || mBackgroundBlurDrawable != null
                    || mDrawDismissButtonCutout) {
                backgroundBounds = calculateBackgroundBounds();
            }

            if (mDrawDismissButtonCutout) {
                canvas.clipPath(calculateDismissButtonCutoutPath(backgroundBounds));
            }
            if (mBackgroundBlurDrawable != null) {
                mBackgroundBlurDrawable.setBounds(backgroundBounds);
                mBackgroundBlurDrawable.draw(canvas);
                drawGlassRim(canvas, backgroundBounds, mBackgroundBlurDrawable);
            } else if (mBackground != null) {
                mBackground.setBounds(backgroundBounds);
                mBackground.draw(canvas);
                drawGlassRim(canvas, backgroundBounds, mBackground);
            }

            canvas.restore();
        }
    }

    /** Draws glass's rim on the background {@code drawable}, just drawn inside {@code bounds}. */
    private void drawGlassRim(Canvas canvas, Rect bounds, Drawable drawable) {
        if (drawable == mBackgroundBlurDrawable) {
            // Blurred glass gets its rim from SurfaceFlinger
            return;
        }
        mGlassRim.draw(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom,
                mCornerRadii, drawable.getAlpha());
    }

    /**
     * Draws the blurred background. SurfaceFlinger blurs the drawable's whole bounds whatever the
     * canvas clips, so they stop where the background is clipped; glass treats a clipped side as
     * carrying on under what clips it: square, without a rim.
     */
    private void drawBlur(Canvas canvas) {
        final boolean alignedToRight = isAlignedToRight();
        final int width = getWidth();
        final int actualWidth = getActualWidth();
        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;
        int top = 0;
        int bottom = getActualHeight();
        int joinedSides = 0;
        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = left + actualWidth;
        } else {
            final int clipTop = Math.max(mClipTopAmount, mTopOverlap);
            final int clipBottom = Math.max(mClipBottomAmount, mBottomOverlap);
            if (clipTop > 0) {
                top = clipTop;
                joinedSides |= BackgroundBlurDrawable.GLASS_SIDE_TOP;
            }
            if (clipBottom > 0) {
                bottom -= clipBottom;
                // A rounded bottom that the clip moves up stays rounded
                if (!mBottomIsRounded || !mBottomAmountClips) {
                    joinedSides |= BackgroundBlurDrawable.GLASS_SIDE_BOTTOM;
                }
            }
        }
        if (bottom <= top) {
            return;
        }
        final float topRadius =
                (joinedSides & BackgroundBlurDrawable.GLASS_SIDE_TOP) != 0 ? 0 : mCornerRadii[0];
        final float bottomRadius =
                (joinedSides & BackgroundBlurDrawable.GLASS_SIDE_BOTTOM) != 0 ? 0 : mCornerRadii[4];
        mBackgroundBlurDrawable.setCornerRadius(topRadius, topRadius, topRadius, topRadius,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius);
        mBackgroundBlurDrawable.setGlassJoinedSides(joinedSides);
        mBackgroundBlurDrawable.setBounds(left, top, right, bottom);
        mBackgroundBlurDrawable.draw(canvas);
    }

    /**
     * Applies Catalyxx glass to the blurred background (OwnGlass): how it's drawn, how much it
     * blurs and its color. A no-op without the blurred background.
     */
    public void updateGlass() {
        if (mBackgroundBlurDrawable == null) {
            return;
        }
        final GlassLook look =
                OwnGlass.INSTANCE.look(GlassSettings.PLACE_NOTIFICATIONS, mNormalColor);
        mBlurIsGlass = look != null;
        mBackgroundBlurDrawable.setGlass(look);
        mBackgroundBlurDrawable.setBlurRadius(GlassSettings.scaleBlurRadius(
                getResources().getDimensionPixelSize(R.dimen.notification_background_blur_radius),
                OwnGlass.INSTANCE.blurScale(GlassSettings.PLACE_NOTIFICATIONS)));
        updateBlurColor();
        invalidate();
    }

    private void updateBlurColor() {
        if (mBlurIsGlass) {
            // The glass draws its own color, through anything drawn behind the notification; a
            // colorized notification keeps its color on it
            mBackgroundBlurDrawable.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            mBackgroundBlurDrawable.setColor(mTintColor != 0 && mTintColor != mNormalColor
                    ? mTintColor : Color.TRANSPARENT);
        } else {
            mBackgroundBlurDrawable.setXfermode(null);
            mBackgroundBlurDrawable.setColor(mTintColor != 0 ? mTintColor : mNormalColor);
        }
    }

    /**
     * The blurred background takes the alpha it's really drawn with: its own, times that of every
     * view above it, and none while one of them is hidden.
     */
    private void updateBlurAlpha() {
        if (mBackgroundBlurDrawable == null) {
            return;
        }
        float alpha = mDrawableAlpha / 255f;
        View view = this;
        while (view != null && alpha > 0f) {
            if (view.getVisibility() != VISIBLE) {
                alpha = 0f;
                break;
            }
            alpha *= view.getAlpha() * view.getTransitionAlpha();
            final ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        mBackgroundBlurDrawable.setAlpha(Math.round(alpha * 255));
    }

    /** Follows the alpha before each frame while there's a blurred background on screen. */
    private void updateBlurAlphaUpdater(boolean attached) {
        final boolean updating = attached && mBackgroundBlurDrawable != null;
        if (updating != mBlurAlphaUpdating) {
            mBlurAlphaUpdating = updating;
            if (updating) {
                getViewTreeObserver().addOnPreDrawListener(mBlurAlphaUpdater);
                updateBlurAlpha();
            } else {
                getViewTreeObserver().removeOnPreDrawListener(mBlurAlphaUpdater);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mGlassRim.onAttachedToWindow();
        updateBlurAlphaUpdater(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mGlassRim.onDetachedFromWindow();
        // The blurred background belongs to the window it was made for. The notification asks for
        // a new one when it's attached again (ActivatableNotificationView.onAttachedToWindow()).
        setBlurBackgroundEnabled(false);
        updateBlurAlphaUpdater(false);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mGlassRim.onVisibilityAggregated(isVisible);
    }

    private Path calculateDismissButtonCutoutPath(Rect backgroundBounds) {
        // TODO(b/365585705): Adapt to RTL after the UX design is finalized.

        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        Path path = new Path();

        final int left = backgroundBounds.left;
        final int right = backgroundBounds.right;
        final int top = backgroundBounds.top;
        final int bottom = backgroundBounds.bottom;

        // Generate the path clockwise from the left-top corner.
        path.moveTo(left, top);
        path.lineTo(right - 2 * convexR - concaveR, top);
        path.quadTo(right - convexR - concaveR, top, right - convexR - concaveR,
                top + convexR);
        path.quadTo(right - convexR - concaveR, top + convexR + concaveR, right - convexR,
                top + convexR + concaveR);
        path.quadTo(right, top + convexR + concaveR, right, top + 2 * convexR + concaveR);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, top);

        return path;
    }

    private Rect calculateBackgroundBounds() {
        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        int top = 0;
        int bottom = getActualHeight();
        if (mBottomIsRounded
                && mBottomAmountClips
                && !mExpandAnimationRunning) {
            bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
        }
        final boolean alignedToRight = isAlignedToRight();
        final int width = getWidth();
        final int actualWidth = getActualWidth();

        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;

        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = (int) (left + actualWidth);
        }

        return new Rect(left, top, right, bottom);
    }

    /**
     * @return Whether the background view should be right-aligned. This only matters if the
     * actualWidth is different than the full (measured) width. In other words, this is used to
     * define the short-shelf alignment.
     */
    protected boolean isAlignedToRight() {
        return isLayoutRtl();
    }

    private void draw(Canvas canvas, Drawable drawable) {
        NotificationAddXOnHoverToDismiss.assertInLegacyMode();

        if (drawable != null) {
            int top = 0;
            int bottom = getActualHeight();
            if (mBottomIsRounded
                    && mBottomAmountClips
                    && !mExpandAnimationRunning) {
                bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
            }

            final boolean alignedToRight = isAlignedToRight();
            final int width = getWidth();
            final int actualWidth = getActualWidth();

            int left = alignedToRight ? width - actualWidth : 0;
            int right = alignedToRight ? width : actualWidth;

            if (mExpandAnimationRunning) {
                // Horizontally center this background view inside of the container
                left = (int) ((width - actualWidth) / 2.0f);
                right = (int) (left + actualWidth);
            }
            drawable.setBounds(left, top, right, bottom);
            drawable.draw(canvas);
            drawGlassRim(canvas, drawable.getBounds(), drawable);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        // The blurred background redraws when it changes too: it draws nothing at alpha 0, so it
        // needs a new frame to show again once its alpha comes back (as the lock screen fades in)
        return super.verifyDrawable(who) || who == mBackground || who == mBackgroundBlurDrawable;
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    /**
     * Update the view on whether the device is currently on the keyguard.
     */
    public void setOnKeyguard(boolean onKeyguard) {
        if (onKeyguard == mOnKeyguard) {
            return;
        }

        // TODO: (b/445495701) - Determine why this value is opposite of expected, then use in #draw
        // to restrict the blurred background to the keyguard only.
        mOnKeyguard = onKeyguard;
    }

    /**
     * Stateful colors are colors that will overlay on the notification original color when one of
     * hover states, pressed states or other similar states is activated.
     */
    private void setStatefulColors() {
        Drawable statefulLayer = getStatefulBackgroundLayer();
        if (statefulLayer == null) {
            return;
        }
        if (mTintColor != mNormalColor) {
            ColorStateList newColor = ContrastColorUtil.isColorDark(mTintColor)
                    ? mDarkColoredStatefulColors : mLightColoredStatefulColors;
            ((GradientDrawable) statefulLayer.mutate()).setColor(newColor);
        }
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mRippleColor = null;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    /**
     * Update whether this view should allow a blurred background or not.
     *
     * @param enabled - If true, queues creation of a {BackgroundBlurDrawable}. If false, removes
     *     any reference to a blurred drawable.
     */
    @UiThread
    public void setBlurBackgroundEnabled(boolean enabled) {
        // Catalyxx glass uses this blur too, so the callers decide when it's on
        // (ActivatableNotificationView.usesBlurredBackground()). Rows may be inflated off the main
        // thread, where it's always off: nothing to do there.
        if (!enabled && mBackgroundBlurDrawable == null && mOnAttachStateChangeListener == null) {
            return;
        }
        Assert.isMainThread();

        if (enabled && mBackgroundBlurDrawable == null) {
            if (mOnAttachStateChangeListener != null) {
                removeOnAttachStateChangeListener(mOnAttachStateChangeListener);
            }
            mOnAttachStateChangeListener =
                    new OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View view) {
                            if (mBackgroundBlurDrawable == null) {
                                mBackgroundBlurDrawable =
                                        view.getViewRootImpl().createBackgroundBlurDrawable();
                                mBackgroundBlurDrawable.setBlurRadius(
                                        getResources().getDimensionPixelSize(
                                                R.dimen.notification_background_blur_radius));
                                mBackgroundBlurDrawable.setXfermode(null);
                                mBackgroundBlurDrawable.setCallback(
                                        NotificationBackgroundView.this);
                                mBackgroundBlurDrawable.setColor(mNormalColor);

                                updateBackgroundRadii();
                                updateGlass();
                                updateBlurAlphaUpdater(true);
                                invalidate();
                            }
                            NotificationBackgroundView.this.removeOnAttachStateChangeListener(this);
                            mOnAttachStateChangeListener = null;
                        }

                        @Override
                        public void onViewDetachedFromWindow(View view) {}
                    };
            addOnAttachStateChangeListener(mOnAttachStateChangeListener);

            if (isAttachedToWindow()) {
                mOnAttachStateChangeListener.onViewAttachedToWindow(this);
            }
        } else if (!enabled) {
            if (mOnAttachStateChangeListener != null) {
                removeOnAttachStateChangeListener(mOnAttachStateChangeListener);
                mOnAttachStateChangeListener = null;
            }
            if (mBackgroundBlurDrawable != null) {
                // The window's blur only lets go of a hidden drawable; until then it keeps it, and
                // Catalyxx glass's tilt keeps lighting it
                mBackgroundBlurDrawable.setVisible(false, false);
                mBackgroundBlurDrawable.setCallback(null);
            }
            mBackgroundBlurDrawable = null;
            mBlurIsGlass = false;
            updateBlurAlphaUpdater(false);
            invalidate();
        }
    }

    public Drawable getBaseBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(0);
    }

    private Drawable getStatefulBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(1);
    }

    public void setTint(int tintColor) {
        Drawable baseLayer = getBaseBackgroundLayer();
        if (notificationRowTransparency()) {
            ((GradientDrawable) baseLayer.mutate()).setColor(tintColor);
        } else {
            baseLayer.mutate().setTintMode(PorterDuff.Mode.SRC_ATOP);
            baseLayer.setTint(tintColor);
        }
        mTintColor = tintColor;
        if (mBackgroundBlurDrawable != null) {
            updateBlurColor();
        }
        setStatefulColors();
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    private int getActualHeight() {
        if (mExpandAnimationRunning && mExpandAnimationHeight > -1) {
            return mExpandAnimationHeight;
        } else if (mActualHeight > -1) {
            return mActualHeight;
        }
        return getHeight();
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
    }

    private int getActualWidth() {
        if (mExpandAnimationRunning && mExpandAnimationWidth > -1) {
            return mExpandAnimationWidth;
        } else if (mActualWidth > -1) {
            return mActualWidth;
        }
        return getWidth();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    /**
     * Sets the overlap on the top of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipTopAmount, however it is a separate way to clip which is usually
     * then combined with the clipTopAmount to take the maximum.
     */
    public void setTopOverlap(int topOverlap) {
        mTopOverlap = topOverlap;
        invalidate();
    }

    /**
     * Sets the overlap on the bottom of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipBottomAmount, however it is a separate way to clip which is usually
     * then combined with the clipBottomAmount to take the maximum.
     */
    public void setBottomOverlap(int bottomOverlap) {
        mBottomOverlap = bottomOverlap;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
            mRippleColor = color;
        } else {
            mRippleColor = null;
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
        updateBlurAlpha();
    }

    /**
     * Sets the current top and bottom radius for this background.
     */
    public void setRadius(float topRoundness, float bottomRoundness) {
        if (topRoundness == mCornerRadii[0] && bottomRoundness == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundness != 0.0f;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundness;
        mCornerRadii[5] = bottomRoundness;
        mCornerRadii[6] = bottomRoundness;
        mCornerRadii[7] = bottomRoundness;
        updateBackgroundRadii();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    @VisibleForTesting
    protected boolean isBlurEnabled() {
        return mBackgroundBlurDrawable != null;
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable layerDrawable) {
            int numberOfLayers = layerDrawable.getNumberOfLayers();
            for (int i = 0; i < numberOfLayers; i++) {
                GradientDrawable gradientDrawable = (GradientDrawable) layerDrawable.getDrawable(i);
                gradientDrawable.setCornerRadii(mCornerRadii);
            }
            updateFocusOverlayRadii(layerDrawable);
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setCornerRadius(mCornerRadii[0], mCornerRadii[1],
                    mCornerRadii[2], mCornerRadii[3], mCornerRadii[4], mCornerRadii[5],
                    mCornerRadii[6], mCornerRadii[7]);
        }
    }

    private void updateFocusOverlayRadii(LayerDrawable background) {
        GradientDrawable overlay =
                (GradientDrawable) background.findDrawableByLayerId(
                        R.id.notification_focus_overlay);
        if (overlay == null) {
            return;
        }
        for (int i = 0; i < mCornerRadii.length; i++) {
            // in theory subtracting mFocusOverlayStroke/2 should be enough but notification
            // background is still peeking a bit from below - probably due to antialiasing or
            // overlay uneven scaling. So let's subtract full mFocusOverlayStroke to make sure the
            // radius is a bit smaller and covers background corners fully
            mFocusOverlayCornerRadii[i] = Math.max(0, mCornerRadii[i] - mFocusOverlayStroke);
        }
        overlay.setCornerRadii(mFocusOverlayCornerRadii);
    }

    /** Set the current expand animation size. */
    public void setExpandAnimationSize(int width, int height) {
        mExpandAnimationHeight = height;
        mExpandAnimationWidth = width;
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("mDontModifyCorners: " + mDontModifyCorners);
        pw.println("mClipTopAmount: " + mClipTopAmount);
        pw.println("mClipBottomAmount: " + mClipBottomAmount);
        pw.println("mTopOverlap: " + mTopOverlap);
        pw.println("mBottomOverlap: " + mBottomOverlap);
        pw.println("mCornerRadii: " + Arrays.toString(mCornerRadii));
        pw.println("mBottomIsRounded: " + mBottomIsRounded);
        pw.println("mBottomAmountClips: " + mBottomAmountClips);
        pw.println("mActualWidth: " + mActualWidth);
        pw.println("mActualHeight: " + mActualHeight);
        pw.println("mTintColor: " + hexColorString(mTintColor));
        pw.println("mRippleColor: " + hexColorString(mRippleColor));
        pw.println("mBackground: " + DrawableDumpKt.dumpToString(mBackground));
        pw.println("mBackgroundBlurDrawable: "
                + DrawableDumpKt.dumpToString(mBackgroundBlurDrawable));
    }

    /** create a concise dump of this view's colors */
    public String toDumpString() {
        return "<NotificationBackgroundView"
                + " tintColor=" + hexColorString(mTintColor)
                + " rippleColor=" + hexColorString(mRippleColor)
                + " bgColor=" + DrawableDumpKt.getSolidColor(mBackground)
                + " blurEnabled=" + (mBackgroundBlurDrawable != null)
                + ">";

    }
}
