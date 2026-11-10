/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.graphics.drawable;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.Xfermode;
import android.graphics.drawable.Drawable;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Objects;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.catalyxx.GlassLook;
import com.android.internal.util.catalyxx.GlassTilt;

/**
 * A drawable that keeps track of a blur region, pokes a hole under it, and propagates its state
 * to SurfaceFlinger.
 */
public final class BackgroundBlurDrawable extends Drawable {

    private static final String TAG = BackgroundBlurDrawable.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Sides of the drawable, for setGlassJoinedSides(). They match BlurRegion::kGlassSide* in
    // SurfaceFlinger.
    public static final int GLASS_SIDE_LEFT = 1;
    public static final int GLASS_SIDE_TOP = 1 << 1;
    public static final int GLASS_SIDE_RIGHT = 1 << 2;
    public static final int GLASS_SIDE_BOTTOM = 1 << 3;
    // Sent with the joined sides for setGlassFixedLight(): BlurRegion::kGlassFixedLight
    private static final int GLASS_FIXED_LIGHT = 1 << 4;
    // Sent with the joined sides for a look with RR's lens edge: BlurRegion::kGlassLensEdge
    private static final int GLASS_LENS_EDGE = 1 << 5;

    private final Aggregator mAggregator;
    private final RenderNode mRenderNode;
    private final Paint mPaint = new Paint();
    private final Path mRectPath = new Path();
    private final float[] mTmpRadii = new float[8];

    private boolean mVisible = true;

    // Confined to UiThread. The values are copied into a BlurRegion, which lives on
    // RenderThread to avoid interference with UiThread updates.
    private int mBlurRadius;
    private float mCornerRadiusTLX;

    private float mCornerRadiusTLY;
    private float mCornerRadiusTRX;
    private float mCornerRadiusTRY;
    private float mCornerRadiusBLX;
    private float mCornerRadiusBLY;
    private float mCornerRadiusBRX;
    private float mCornerRadiusBRY;
    private int mAlpha = 255;
    private GlassLook mGlassLook;
    private int mGlassJoinedSides;
    private boolean mGlassFixedLight;
    private float mGlassMelt;
    // Whether GlassTilt was told this glass shows
    private boolean mShowsGlass;

    // Do not update from UiThread. This holds the latest position for this drawable. It is used
    // by the Aggregator from RenderThread to get the final position of the blur region sent to SF
    private final Rect mRect = new Rect();
    // This is called from a thread pool. The callbacks might come out of order w.r.t. the frame
    // number, so we send a Runnable holding the actual update to the Aggregator. The Aggregator
    // can apply the update on RenderThread when processing that same frame.
    @VisibleForTesting
    public final RenderNode.PositionUpdateListener mPositionUpdateListener =
            new RenderNode.PositionUpdateListener() {
            @Override
            public void positionChanged(long frameNumber, int left, int top, int right,
                    int bottom) {
                mAggregator.onRenderNodePositionChanged(frameNumber, () -> {
                    mRect.set(left, top, right, bottom);
                });
            }

            @Override
            public void positionLost(long frameNumber) {
                mAggregator.onRenderNodePositionChanged(frameNumber, () -> {
                    mRect.setEmpty();
                });
            }
        };

    private BackgroundBlurDrawable(Aggregator aggregator) {
        mAggregator = aggregator;
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        mPaint.setColor(Color.TRANSPARENT);
        mPaint.setAntiAlias(true);
        mRenderNode = new RenderNode("BackgroundBlurDrawable");
        mRenderNode.addPositionUpdateListener(mPositionUpdateListener);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mRectPath.isEmpty() || !isVisible() || getAlpha() == 0) {
            return;
        }

        canvas.drawPath(mRectPath, mPaint);
        canvas.drawRenderNode(mRenderNode);
    }

    /**
     * Color that will be alpha blended on top of the blur.
     */
    public void setColor(@ColorInt int color) {
        mPaint.setColor(color);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (changed) {
            mVisible = visible;
            mAggregator.onBlurDrawableUpdated(this);
        }
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    /**
     * Set the {@link Xfermode} for the drawable.
     */
    public void setXfermode(@Nullable Xfermode xfermode) {
        mPaint.setXfermode(xfermode);
    }

    /**
     * Blur radius in pixels.
     */
    public void setBlurRadius(int blurRadius) {
        if (mBlurRadius != blurRadius) {
            mBlurRadius = blurRadius;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Sets the corner radius, in degrees.
     */
    public void setCornerRadius(float cornerRadius) {
        setCornerRadius(cornerRadius, cornerRadius, cornerRadius, cornerRadius);
    }

    /**
     * Sets the corner radius in degrees.
     * @param cornerRadiusTL top left radius.
     * @param cornerRadiusTR top right radius.
     * @param cornerRadiusBL bottom left radius.
     * @param cornerRadiusBR bottom right radius.
     */
    public void setCornerRadius(float cornerRadiusTL, float cornerRadiusTR, float cornerRadiusBL,
            float cornerRadiusBR) {
        setCornerRadius(cornerRadiusTL, cornerRadiusTL, cornerRadiusTR, cornerRadiusTR,
                cornerRadiusBL, cornerRadiusBL, cornerRadiusBR, cornerRadiusBR);
    }

    /**
     * Sets the corner radius in degrees.
     * @param cornerRadiusTLX top left radius X.
     * @param cornerRadiusTLY top left radius Y.
     * @param cornerRadiusTRX top right radius X.
     * @param cornerRadiusTRY top right radius Y.
     * @param cornerRadiusBLX bottom left radius X.
     * @param cornerRadiusBLY bottom left radius Y.
     * @param cornerRadiusBRX bottom right radius X.
     * @param cornerRadiusBRY bottom right radius Y.
     */
    public void setCornerRadius(float cornerRadiusTLX, float cornerRadiusTLY,
            float cornerRadiusTRX, float cornerRadiusTRY, float cornerRadiusBLX,
            float cornerRadiusBLY, float cornerRadiusBRX, float cornerRadiusBRY) {
        if (mCornerRadiusTLX != cornerRadiusTLX
                || mCornerRadiusTLY != cornerRadiusTLY
                || mCornerRadiusTRX != cornerRadiusTRX
                || mCornerRadiusTRY != cornerRadiusTRY
                || mCornerRadiusBLX != cornerRadiusBLX
                || mCornerRadiusBLY != cornerRadiusBLY
                || mCornerRadiusBRX != cornerRadiusBRX
                || mCornerRadiusBRY != cornerRadiusBRY) {
            mCornerRadiusTLX = cornerRadiusTLX;
            mCornerRadiusTLY = cornerRadiusTLY;
            mCornerRadiusTRX = cornerRadiusTRX;
            mCornerRadiusTRY = cornerRadiusTRY;
            mCornerRadiusBLX = cornerRadiusBLX;
            mCornerRadiusBLY = cornerRadiusBLY;
            mCornerRadiusBRX = cornerRadiusBRX;
            mCornerRadiusBRY = cornerRadiusBRY;
            updatePath();
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Draws the blur as Catalyxx glass with {@code look}, or as plain blur when it's null.
     */
    public void setGlass(@Nullable GlassLook look) {
        if (!Objects.equals(mGlassLook, look)) {
            mGlassLook = look;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Sets the sides ({@code GLASS_SIDE_} bits) where this drawable's glass carries on into
     * another blur drawable of the same window. Those sides get no rim and no shadow, so the
     * pieces read as one.
     */
    public void setGlassJoinedSides(int sides) {
        if (mGlassJoinedSides != sides) {
            mGlassJoinedSides = sides;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Keeps this drawable's glass in the usual light whatever the phone's tilt. For glass that
     * shows nearly all the time, such as the navigation bar's: while only such glass shows, the
     * tilt sensor stays off and SurfaceFlinger doesn't draw the screen again as the light moves.
     */
    public void setGlassFixedLight(boolean fixed) {
        if (mGlassFixedLight != fixed) {
            mGlassFixedLight = fixed;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Melts this drawable's glass into the other glass of the same window, in pixels: pieces start
     * to reach for each other when they're this close, and a neck joins them from a third of it.
     * The other pieces needn't melt themselves. 0, as it starts, keeps it to itself.
     */
    public void setGlassMelt(float size) {
        if (mGlassMelt != size) {
            mGlassMelt = size;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Makes this drawable's glass, with no shadow of its own, what {@code other}'s is, with the
     * same blur: for a bead of glass that only shows where it melts into {@code other}'s, which
     * is what it looks like there. Returns false if {@code other} isn't glass.
     */
    public boolean setGlassLike(@NonNull BackgroundBlurDrawable other) {
        if (other.mGlassLook == null) {
            return false;
        }
        setGlassFixedLight(other.mGlassFixedLight);
        setGlass(other.mGlassLook.withoutShadow());
        setBlurRadius(other.mBlurRadius);
        return true;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        mRenderNode.setPosition(left, top, right, bottom);
        updatePath();
    }

    private void updatePath() {
        mTmpRadii[0] = mCornerRadiusTLX;
        mTmpRadii[1] = mCornerRadiusTLY;
        mTmpRadii[2] = mCornerRadiusTRX;
        mTmpRadii[3] = mCornerRadiusTRY;
        mTmpRadii[4] = mCornerRadiusBLX;
        mTmpRadii[5] = mCornerRadiusBLY;
        mTmpRadii[6] = mCornerRadiusBRX;
        mTmpRadii[7] = mCornerRadiusBRY;
        mRectPath.reset();
        // Kept whatever the alpha and visibility: draw() already skips a hidden or see-through
        // drawable, and neither setAlpha() nor setVisible() rebuilds the path. A drawable laid out
        // while hidden or at alpha 0 (as on the lock screen, which fades in) used to keep an empty
        // path, so it never drew, never got a position and never reached SurfaceFlinger.
        Rect bounds = getBounds();
        mRectPath.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, mTmpRadii,
                Path.Direction.CW);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public String toString() {
        return "BackgroundBlurDrawable{"
            + "blurRadius=" + mBlurRadius
            + ", corners={" + mCornerRadiusTLX
            + "," + mCornerRadiusTLY
            + "," + mCornerRadiusTRX
            + "," + mCornerRadiusTRY
            + "," + mCornerRadiusBLX
            + "," + mCornerRadiusBLY
            + "," + mCornerRadiusBRX
            + "," + mCornerRadiusBRY
            + "}, alpha=" + mAlpha
            + ", visible=" + mVisible
            + ", glass=" + mGlassLook
            + ", glassJoinedSides=" + mGlassJoinedSides
            + ", glassFixedLight=" + mGlassFixedLight
            + ", glassMelt=" + mGlassMelt
            + "}";
    }

    /**
     * Responsible for keeping track of all blur regions of a {@link ViewRootImpl} and posting a
     * message when it's time to propagate them.
     */
    public static final class Aggregator {
        private final Object mRtLock = new Object();
        // Maintains a list of all *visible* blur drawables. Confined to  UI thread
        private final ArraySet<BackgroundBlurDrawable> mDrawables = new ArraySet();
        @GuardedBy("mRtLock")
        private final LongSparseArray<ArraySet<Runnable>> mFrameRtUpdates = new LongSparseArray();
        private long mLastFrameNumber = 0;
        private BlurRegion[] mLastFrameBlurRegions = null;
        private final ViewRootImpl mViewRoot;
        private BlurRegion[] mTmpBlurRegionsForFrame = new BlurRegion[0];
        private boolean mHasUiUpdates;
        private ViewTreeObserver.OnPreDrawListener mOnPreDrawListener;
        // The context the drawables were made with
        private Context mContext;

        public Aggregator(ViewRootImpl viewRoot) {
            mViewRoot = viewRoot;
        }

        /**
         * Creates a blur region with default radius.
         */
        public BackgroundBlurDrawable createBackgroundBlurDrawable(Context context) {
            mContext = context;
            BackgroundBlurDrawable drawable = new BackgroundBlurDrawable(this);
            drawable.setBlurRadius(context.getResources().getDimensionPixelSize(
                    R.dimen.default_background_blur_radius));
            return drawable;
        }

        /**
         * Called when a BackgroundBlurDrawable has been updated
         */
        @UiThread
        void onBlurDrawableUpdated(BackgroundBlurDrawable drawable) {
            final boolean shouldBeDrawn =
                    drawable.mAlpha > 0 && drawable.mBlurRadius > 0 && drawable.mVisible;
            // Catalyxx glass: the light on its rim follows the phone's tilt while it shows, but
            // for glass that keeps a fixed light
            final boolean showsGlass = shouldBeDrawn && drawable.mGlassLook != null
                    && !drawable.mGlassFixedLight;
            if (showsGlass != drawable.mShowsGlass && mContext != null) {
                drawable.mShowsGlass = showsGlass;
                if (showsGlass) {
                    GlassTilt.onGlassShown(mContext, drawable);
                } else {
                    GlassTilt.onGlassHidden(drawable);
                }
            }
            final boolean isDrawn = mDrawables.contains(drawable);
            if (shouldBeDrawn) {
                mHasUiUpdates = true;
                if (!isDrawn) {
                    mDrawables.add(drawable);
                    if (DEBUG) {
                        Log.d(TAG, "Add " + drawable);
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Update " + drawable);
                    }
                }
            } else if (!shouldBeDrawn && isDrawn) {
                mHasUiUpdates = true;
                mDrawables.remove(drawable);
                if (DEBUG) {
                    Log.d(TAG, "Remove " + drawable);
                }
            }

            if (mOnPreDrawListener == null && mViewRoot.getView() != null
                    && hasRegions()) {
                registerPreDrawListener();

                if (mHasUiUpdates) {
                    // Catalyxx: posted for the next frame. A drawable shown while a frame is
                    // drawn, by a pre-draw listener or as it's drawn, comes too late for the
                    // listener just registered, and invalidating then asks for no new frame: on
                    // a still screen nothing sent SurfaceFlinger its blur until something else
                    // drew the window again
                    mViewRoot.getView().postInvalidateOnAnimation();
                }
            }
        }

        private void registerPreDrawListener() {
            mOnPreDrawListener = () -> {
                final boolean hasUiUpdates = hasUpdates();

                if (hasUiUpdates || hasRegions()) {
                    final BlurRegion[] blurRegionsForNextFrame = getBlurRegionsCopyForRT();

                    mViewRoot.registerRtFrameCallback(frame -> {
                        synchronized (mRtLock) {
                            mLastFrameNumber = frame;
                            mLastFrameBlurRegions = blurRegionsForNextFrame;
                            handleDispatchBlurTransactionLocked(
                                    frame, blurRegionsForNextFrame, hasUiUpdates);
                        }
                    });
                }
                if (!hasRegions() && mViewRoot.getView() != null) {
                    mViewRoot.getView().getViewTreeObserver()
                            .removeOnPreDrawListener(mOnPreDrawListener);
                    mOnPreDrawListener = null;
                }
                return true;
            };

            mViewRoot.getView().getViewTreeObserver().addOnPreDrawListener(mOnPreDrawListener);
        }

        // Called from a thread pool
        void onRenderNodePositionChanged(long frameNumber, Runnable update) {
            // One of the blur region's position has changed, so we have to send an updated list
            // of blur regions to SurfaceFlinger for this frame.
            synchronized (mRtLock) {
                ArraySet<Runnable> frameRtUpdates = mFrameRtUpdates.get(frameNumber);
                if (frameRtUpdates == null) {
                    frameRtUpdates = new ArraySet<>();
                    mFrameRtUpdates.put(frameNumber, frameRtUpdates);
                }
                frameRtUpdates.add(update);

                if (mLastFrameNumber == frameNumber) {
                    // The transaction for this frame has already been sent, so we have to manually
                    // trigger sending a transaction here in order to apply this position update
                    handleDispatchBlurTransactionLocked(frameNumber, mLastFrameBlurRegions, true);
                }
            }

        }

        /**
         * @return true if there are any updates that need to be sent to SF
         */
        @UiThread
        public boolean hasUpdates() {
            return mHasUiUpdates;
        }

        /**
         * @return true if there are any visible blur regions
         */
        @UiThread
        public boolean hasRegions() {
            return mDrawables.size() > 0;
        }

        /**
         * @return an array of BlurRegions, which are holding a copy of the information in
         *         all the currently visible BackgroundBlurDrawables
         */
        @UiThread
        public BlurRegion[] getBlurRegionsCopyForRT() {
            if (mHasUiUpdates) {
                mTmpBlurRegionsForFrame = new BlurRegion[mDrawables.size()];
                for (int i = 0; i < mDrawables.size(); i++) {
                    mTmpBlurRegionsForFrame[i] = new BlurRegion(mDrawables.valueAt(i));
                }
                mHasUiUpdates = false;
            }

            return mTmpBlurRegionsForFrame;
        }

        /**
         * Called on RenderThread.
         *
         * @return true if it is necessary to send an update to Sf this frame
         */
        @GuardedBy("mRtLock")
        @VisibleForTesting
        public float[][] getBlurRegionsForFrameLocked(long frameNumber,
                BlurRegion[] blurRegionsForFrame, boolean forceUpdate) {
            if (!forceUpdate && (mFrameRtUpdates.size() == 0
                        || mFrameRtUpdates.keyAt(0) > frameNumber)) {
                return null;
            }

            // mFrameRtUpdates holds position updates coming from a thread pool span from
            // RenderThread. At this point, all position updates for frame frameNumber should
            // have been added to mFrameRtUpdates.
            // Here, we apply all updates for frames <= frameNumber in case some previous update
            // has been missed. This also protects mFrameRtUpdates from memory leaks.
            while (mFrameRtUpdates.size() != 0 && mFrameRtUpdates.keyAt(0) <= frameNumber) {
                final ArraySet<Runnable> frameUpdates = mFrameRtUpdates.valueAt(0);
                mFrameRtUpdates.removeAt(0);
                for (int i = 0; i < frameUpdates.size(); i++) {
                    frameUpdates.valueAt(i).run();
                }
            }

            if (DEBUG) {
                Log.d(TAG, "Might dispatch " + blurRegionsForFrame.length + " blur regions:");
            }
            ArrayList<BlurRegion> filteredBlurRegionsForFrame = new ArrayList<>();
            for (BlurRegion blurRegion : blurRegionsForFrame) {
                if (!blurRegion.rect.isEmpty()) {
                    filteredBlurRegionsForFrame.add(blurRegion);
                }
            }
            if (DEBUG) {
                Log.d(TAG, "Dispatching " + blurRegionsForFrame.length + " blur regions:");
            }

            final float[][] blurRegionsArray = new float[filteredBlurRegionsForFrame.size()][];
            for (int i = 0; i < blurRegionsArray.length; i++) {
                blurRegionsArray[i] = filteredBlurRegionsForFrame.get(i).toFloatArray();
                if (DEBUG) {
                    Log.d(TAG, "Region: " + filteredBlurRegionsForFrame.get(i).toString());
                }
            }
            return blurRegionsArray;
        }

        /**
         * Dispatch all blur regions if there are any ui or position updates for that frame.
         */
        @GuardedBy("mRtLock")
        private void handleDispatchBlurTransactionLocked(long frameNumber, BlurRegion[] blurRegions,
                boolean forceUpdate) {
            float[][] blurRegionsArray =
                    getBlurRegionsForFrameLocked(frameNumber, blurRegions, forceUpdate);
            if (blurRegionsArray != null) {
                mViewRoot.dispatchBlurRegions(blurRegionsArray, frameNumber);
            }
        }
    }

    /**
     * Wrapper for sending blur data to SurfaceFlinger
     * Confined to RenderThread.
     */
    public static final class BlurRegion {
        public final int blurRadius;
        public final float cornerRadiusTLX;
        public final float cornerRadiusTRX;
        public final float cornerRadiusTRY;
        public final float cornerRadiusTLY;
        public final float cornerRadiusBLX;
        public final float cornerRadiusBLY;
        public final float cornerRadiusBRX;
        public final float cornerRadiusBRY;
        public final float alpha;
        public final Rect rect;
        public final GlassLook glassLook;
        public final int glassJoinedSides;
        public final float glassMelt;

        BlurRegion(BackgroundBlurDrawable drawable) {
            alpha = drawable.mAlpha / 255f;
            blurRadius = drawable.mBlurRadius;
            cornerRadiusTLX = drawable.mCornerRadiusTLX;
            cornerRadiusTLY = drawable.mCornerRadiusTLY;
            cornerRadiusTRX = drawable.mCornerRadiusTRX;
            cornerRadiusTRY = drawable.mCornerRadiusTRY;
            cornerRadiusBLX = drawable.mCornerRadiusBLX;
            cornerRadiusBLY = drawable.mCornerRadiusBLY;
            cornerRadiusBRX = drawable.mCornerRadiusBRX;
            cornerRadiusBRY = drawable.mCornerRadiusBRY;
            rect = drawable.mRect;
            glassLook = drawable.mGlassLook;
            // SurfaceFlinger takes the fixed light and the lens edge with the joined sides
            glassJoinedSides = drawable.mGlassJoinedSides
                    | (drawable.mGlassFixedLight ? GLASS_FIXED_LIGHT : 0)
                    | (glassLook != null && glassLook.lensEdge ? GLASS_LENS_EDGE : 0);
            glassMelt = drawable.mGlassMelt;
        }

        /**
         * Serializes this class into a float array that's more JNI friendly.
         */
        float[] toFloatArray() {
            final float[] floatArray = new float[glassLook != null ? 31 : 14];
            floatArray[0] = blurRadius;
            floatArray[1] = alpha;
            floatArray[2] = rect.left;
            floatArray[3] = rect.top;
            floatArray[4] = rect.right;
            floatArray[5] = rect.bottom;
            floatArray[6] = cornerRadiusTLX;
            floatArray[7] = cornerRadiusTLY;
            floatArray[8] = cornerRadiusTRX;
            floatArray[9] = cornerRadiusTRY;
            floatArray[10] = cornerRadiusBLX;
            floatArray[11] = cornerRadiusBLY;
            floatArray[12] = cornerRadiusBRX;
            floatArray[13] = cornerRadiusBRY;
            if (glassLook != null) {
                // Catalyxx glass, which nativeSetBlurRegions reads when it's there
                floatArray[14] = glassLook.rimWidth;
                floatArray[15] = glassLook.bend;
                floatArray[16] = glassLook.frost;
                floatArray[17] = glassLook.rimLight;
                floatArray[18] = glassLook.dispersion;
                floatArray[19] = glassLook.shadow;
                floatArray[20] = glassJoinedSides;
                putColor(floatArray, 21, glassLook.tintOverDark);
                putColor(floatArray, 25, glassLook.tintOverLight);
                floatArray[29] = glassLook.adaptPoint;
                floatArray[30] = glassMelt;
            }
            return floatArray;
        }

        /** Puts ARGB {@code color} at {@code index} as red, green, blue and alpha from 0 to 1. */
        private static void putColor(float[] floatArray, int index, int color) {
            floatArray[index] = Color.red(color) / 255f;
            floatArray[index + 1] = Color.green(color) / 255f;
            floatArray[index + 2] = Color.blue(color) / 255f;
            floatArray[index + 3] = Color.alpha(color) / 255f;
        }

        @Override
        public String toString() {
            return "BlurRegion{"
                    + "blurRadius=" + blurRadius
                    + ", corners={" + cornerRadiusTLX
                    + "," + cornerRadiusTLY
                    + "," + cornerRadiusTRX
                    + "," + cornerRadiusTRY
                    + "," + cornerRadiusBLX
                    + "," + cornerRadiusBLY
                    + "," + cornerRadiusBRX
                    + "," + cornerRadiusBRY
                    + "}, alpha=" + alpha
                    + ", rect=" + rect
                    + ", glass=" + glassLook
                    + ", glassJoinedSides=" + glassJoinedSides
                    + ", glassMelt=" + glassMelt
                    + "}";
        }
    }
}
