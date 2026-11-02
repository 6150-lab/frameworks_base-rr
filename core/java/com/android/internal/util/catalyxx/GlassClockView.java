/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The time in glass digits, for the lock screen's Glass clock face: the wallpaper shows through
 * them, bent at their rounded edges, their edges catch the light, and the light follows the
 * phone's tilt ({@link GlassTilt}). It draws the wallpaper behind it itself, from the wallpaper
 * image, so it can't see a live wallpaper: there the digits are milky glass instead. On the
 * always-on display ({@link #setDozing}) only their edges light up.
 *
 * <p>It looks as GlassSettings' {@code CLOCK_} keys say, and follows them as they change. Where
 * it is decides where the wallpaper is, by the view's {@code android:tag}:
 * <ul>
 * <li>none: on the lock screen, over the lock screen wallpaper filling the screen, under the
 *     lock screen's scrim ({@link #setBackdropDim});
 * <li>{@code "picker"}: in a picker that shows the home wallpaper filling its window,
 *     center-cropped, under a 30% black veil, as the lock screen customizer does;
 * <li>{@code "card"}: a preview that draws the lock screen wallpaper behind itself too, as it
 *     shows behind the lock screen's clock.
 * </ul>
 *
 * <p>Main thread only. The digits and the wallpaper are prepared on a background thread, so
 * nothing shows until both are ready.
 */
public class GlassClockView extends View {

    private static final String TAG = "GlassClockView";

    private static final int PLACE_LOCKSCREEN = 0;
    private static final int PLACE_PICKER = 1;
    private static final int PLACE_CARD = 2;

    // The shader's modes: the wallpaper is known, it isn't (live), or the always-on display
    private static final int MODE_BACKDROP = 0;
    private static final int MODE_MILKY = 1;
    private static final int MODE_AOD = 2;

    // Google Sans Flex (Lineage's external/google-fonts), a variable font with weight, width and
    // roundness; anywhere it's missing the system font stands in, without the roundness
    private static final String FONT_FAMILY = "google-sans-flex";
    // Every digit, for the digits' height, and the widest times, so the clock's size doesn't
    // change as the time does (the digits are tabular)
    private static final String DIGITS = "0123456789";
    private static final String WIDEST_ONE_LINE = "00:00";
    private static final String WIDEST_STACKED = "00";

    // Sizes, as shares of the text size unless said otherwise
    private static final float MAX_SIZE_ONE_LINE_DP = 130f;
    private static final float MAX_SIZE_STACKED_DP = 170f;
    private static final float PAD_V = 0.12f; // room above and below for the light and shadow
    private static final float PAD_H = 0.03f;
    private static final float LINE_GAP = 0.06f;
    // Sigma of the blur that rounds the digits into glass, for each CLOCK_DEPTH
    private static final float[] DEPTH_SIGMA = {0.008f, 0.025f, 0.045f};
    private static final float SHADOW_SIGMA = 0.035f;
    private static final float SHADOW_DROP = 0.022f;
    // How far the edge reaches past itself at CLOCK_BEND 100
    private static final float MAX_BEND = 0.12f;
    private static final float MAX_SHADOW = 0.6f;
    // How much of the accent color Tinted glass is
    private static final float TINT_COVER = 0.3f;
    // How much lighter thick glass looks than what's behind it
    private static final float CLEAR_LIFT = 0.06f;
    private static final float FROSTED_LIFT = 0.1f;
    // The bright line along the edges, in dp: wider and brighter on the always-on display,
    // where it's all there is
    private static final float LINE_WIDTH_DP = 0.75f;
    private static final float AOD_LINE_WIDTH_DP = 1.3f;
    private static final float AOD_MIN_LIGHT = 0.9f;

    // The lock screen lays its scrim over the wallpaper (SystemUI's ScrimController keeps 20%
    // on the lock screen); the lock screen customizer lays 30% black over its wallpaper
    private static final float LOCK_SCREEN_DIM = 0.2f;
    private static final float PICKER_DIM = 0.3f;
    // A card shows the wallpaper as it is behind the lock screen's clock, near the top, with
    // rounded corners
    private static final float CARD_TOP = 0.1f;
    private static final float CARD_RADIUS_DP = 24f;
    // The blurred wallpaper is this many times smaller
    private static final int SOFT_SCALE = 8;
    // A wallpaper that couldn't be read is tried again after this long (ms)
    private static final long RETRY_DELAY = 10_000;
    // Digits are prepared again for a clock drawn this much bigger or smaller than they were
    // made for, once it has stopped changing size this long (ms)
    private static final float RESCALE_SHARE = 0.2f;
    private static final long RESCALE_DELAY = 300;
    private static final float MAX_MASK_SCALE = 2.5f;

    // The usual light, up and a little to the left, as SurfaceFlinger's glass has it
    private static final float USUAL_LIGHT_X = -0.447214f;
    private static final float USUAL_LIGHT_Y = -0.894427f;
    private static final double USUAL_LIGHT_ANGLE = Math.atan2(USUAL_LIGHT_Y, USUAL_LIGHT_X);

    // One thread prepares the digits and the wallpaper for every clock in the process
    private static final Executor sWorker = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "GlassClock");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final int mPlace;
    private final float mDensity;
    private final Typeface mTypeface;

    private Look mLook;
    private String[] mLines = {""};

    // The text's layout, from onMeasure
    private final Paint mMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mBounds = new Rect();
    private float mTextSize;
    private float mDigitTop;
    private float mDigitHeight;
    private int mLaidOutWidth = -1;

    // The digits, ready to draw, and the newest asked for
    private Masks mMasks;
    private int mMaskGeneration;
    private float mMaskScale = 1f;
    private float mWantedMaskScale = 1f;

    // The wallpaper behind the clock, ready to draw, and the newest asked for
    private Backdrop mBackdrop;
    private int mBackdropGeneration;
    private boolean mBackdropLoading;
    private int mLoadingTop;
    private int mLoadingBottom;
    private int mLoadingWidth;
    private int mLoadingHeight;

    // Where the clock is over the wallpaper: from the view to the wallpaper's place
    private final Matrix mViewToBackdrop = new Matrix();
    private final Matrix mNewViewToBackdrop = new Matrix();
    private final Matrix mScratch = new Matrix();
    private final RectF mScratchRect = new RectF();
    private final float[] mScratchValues = new float[9];
    private final int[] mScratchLocation = new int[2];
    private int mBackdropWidth;
    private int mBackdropHeight;
    private boolean mPlaced;

    @ColorInt private int mDimColor = Color.BLACK;
    private float mDimAmount;
    private boolean mDozing;
    private boolean mPowerSave;
    private boolean mVisible;
    // Whether the clock shows at all through its own and its parents' alpha, as of the last
    // frame: the lock screen fades its clock out without hiding it
    private boolean mSeen = true;
    private boolean mTiltShown;
    private boolean mAttached;

    // The light, from GlassTilt's thread
    private float mLightX = USUAL_LIGHT_X;
    private float mLightY = USUAL_LIGHT_Y;
    private final AtomicLong mPendingLight = new AtomicLong();
    private final AtomicBoolean mLightPosted = new AtomicBoolean();

    // Drawing
    private RuntimeShader mShader;
    private boolean mShaderFailed;
    private boolean mInputsSet;
    private final Paint mPaint = new Paint();
    private final Paint mPlainPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mCardPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private BitmapShader mEmptyShader;

    private final Runnable mApplyLight = this::applyLight;
    private final Runnable mRescale = this::rescale;

    private final GlassTilt.LightListener mLightListener = (x, y) -> {
        mPendingLight.set(((long) Float.floatToRawIntBits(x) << 32)
                | (Float.floatToRawIntBits(y) & 0xffffffffL));
        if (!mLightPosted.getAndSet(true)) {
            mMainHandler.post(mApplyLight);
        }
    };

    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        onPreDraw();
        return true;
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                readPowerSave();
                updateTilt();
            } else {
                refreshTime();
            }
        }
    };

    private final ContentObserver mLookObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onLookChanged();
        }
    };

    private final ContentObserver mTimeFormatObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            refreshTime();
        }
    };

    private final WallpaperManager.OnColorsChangedListener mWallpaperListener =
            new WallpaperManager.OnColorsChangedListener() {
                @Override
                public void onColorsChanged(WallpaperColors colors, int which) {
                    // A new wallpaper, or a new dim on it
                    dropBackdrop();
                    invalidate();
                }
            };

    public GlassClockView(Context context) {
        this(context, null);
    }

    public GlassClockView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassClockView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public GlassClockView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        final Object tag = getTag();
        mPlace = "picker".equals(tag) ? PLACE_PICKER
                : "card".equals(tag) ? PLACE_CARD : PLACE_LOCKSCREEN;
        mDensity = getResources().getDisplayMetrics().density;
        mTypeface = Typeface.create(FONT_FAMILY, Typeface.NORMAL);
        mDimAmount = mPlace == PLACE_PICKER ? PICKER_DIM : LOCK_SCREEN_DIM;
        mLook = Look.read(context.getContentResolver());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (mPlace == PLACE_CARD) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            CARD_RADIUS_DP * mDensity);
                }
            });
            setClipToOutline(true);
        }
        updateText();
    }

    /**
     * The always-on display: no wallpaper behind, so only the light on the digits' edges shows.
     */
    public void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            mDozing = dozing;
            // The always-on display draws without the wallpaper; it's given back after
            mInputsSet = false;
            updateTilt();
            invalidate();
        }
    }

    /**
     * What the screen lays over the wallpaper behind the clock: {@code color} covering
     * {@code amount} (0 to 1) of it, so the glass shows the wallpaper as it looks there.
     */
    public void setBackdropDim(@ColorInt int color, float amount) {
        final float a = Math.max(0f, Math.min(1f, amount));
        if (mDimColor != color || mDimAmount != a) {
            mDimColor = color;
            mDimAmount = a;
            invalidate();
        }
    }

    /** Reads the time again, such as after the screen turns on. */
    public void refreshTime() {
        if (updateText()) {
            requestMasks();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        for (String key : GlassSettings.CLOCK_KEYS) {
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false,
                    mLookObserver);
        }
        final Uri timeFormat = Settings.System.getUriFor(Settings.System.TIME_12_24);
        try {
            // SystemUI shows whichever user is current
            resolver.registerContentObserver(timeFormat, false, mTimeFormatObserver,
                    UserHandle.USER_ALL);
        } catch (SecurityException e) {
            // An app can only watch its own user's
            resolver.registerContentObserver(timeFormat, false, mTimeFormatObserver);
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        context.registerReceiver(mReceiver, filter, null, mMainHandler,
                Context.RECEIVER_NOT_EXPORTED);
        final WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
        if (wallpaperManager != null) {
            try {
                wallpaperManager.addOnColorsChangedListener(mWallpaperListener, mMainHandler,
                        ActivityManager.getCurrentUser());
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't follow wallpaper changes", e);
            }
        }
        GlassTilt.addLightListener(mLightListener);
        applyLight(readTiltLight());
        getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        readPowerSave();
        onLookChanged();
        refreshTime();
        updateTilt();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAttached = false;
        final Context context = getContext();
        context.getContentResolver().unregisterContentObserver(mLookObserver);
        context.getContentResolver().unregisterContentObserver(mTimeFormatObserver);
        try {
            context.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            // Never registered
        }
        final WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
        if (wallpaperManager != null) {
            try {
                wallpaperManager.removeOnColorsChangedListener(mWallpaperListener,
                        ActivityManager.getCurrentUser());
            } catch (RuntimeException e) {
                // Never registered
            }
        }
        GlassTilt.removeLightListener(mLightListener);
        getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
        mMainHandler.removeCallbacks(mRescale);
        updateTilt();
        // The wallpaper is the big part; it's read again if the clock comes back
        dropBackdrop();
        mPlaced = false;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mVisible = isVisible;
        updateTilt();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        layOutText(width);
        final int lines = mLines.length;
        final int height = Math.round(2 * PAD_V * mTextSize + lines * mDigitHeight
                + (lines - 1) * LINE_GAP * mTextSize);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        requestMasks();
        // The wallpaper behind is read again for the new size at the next frame
        mPlaced = false;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final Masks masks = mMasks;
        if (masks == null || masks.width != getWidth() || masks.height != getHeight()) {
            return;
        }
        final Backdrop backdrop = mBackdrop;
        if (mPlace == PLACE_CARD) {
            drawCardBackdrop(canvas, backdrop);
        }
        if (!canvas.isHardwareAccelerated() || !ensureShader()) {
            drawPlain(canvas, masks);
            return;
        }
        final int mode;
        if (mDozing && mPlace == PLACE_LOCKSCREEN) {
            mode = MODE_AOD;
        } else if (backdrop == null) {
            // Still reading the wallpaper
            return;
        } else {
            mode = backdrop.sharp == null ? MODE_MILKY : MODE_BACKDROP;
        }
        if (!mInputsSet) {
            setInputs(masks, backdrop);
        }
        setUniforms(mode);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
    }

    /** Lays the text out for {@code width}: its size, and the digits' height. */
    private void layOutText(int width) {
        final Look look = mLook;
        final boolean stacked = look.layout == GlassSettings.CLOCK_STACKED;
        final String widest = stacked ? WIDEST_STACKED : WIDEST_ONE_LINE;
        final float available = width * (1f - 2 * PAD_H);
        final float maxSize = (stacked ? MAX_SIZE_STACKED_DP : MAX_SIZE_ONE_LINE_DP) * mDensity;
        // The font's optical size follows the text size, which changes its widths a little, so
        // the size is measured twice
        float size = 100f * mDensity;
        for (int i = 0; i < 2; i++) {
            look.configure(mMeasurePaint, mTypeface, size, mDensity);
            final float widestWidth = mMeasurePaint.measureText(widest);
            if (widestWidth > 0) {
                size = Math.min(maxSize, size * available / widestWidth);
            }
        }
        look.configure(mMeasurePaint, mTypeface, size, mDensity);
        mMeasurePaint.getTextBounds(DIGITS, 0, DIGITS.length(), mBounds);
        final boolean changed = size != mTextSize || width != mLaidOutWidth;
        mTextSize = size;
        mDigitTop = mBounds.top;
        mDigitHeight = Math.max(1, mBounds.height());
        mLaidOutWidth = width;
        if (changed) {
            requestMasks();
        }
    }

    /** Reads the time into the lines; true if they changed. */
    private boolean updateText() {
        final Context context = getContext();
        final boolean is24 = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser());
        final Calendar now = Calendar.getInstance();
        final String[] lines;
        if (mLook.layout == GlassSettings.CLOCK_STACKED) {
            lines = new String[] {
                DateFormat.format(is24 ? "HH" : "h", now).toString(),
                DateFormat.format("mm", now).toString(),
            };
        } else {
            lines = new String[] {DateFormat.format(is24 ? "HH:mm" : "h:mm", now).toString()};
        }
        setContentDescription(DateFormat.getTimeFormat(context).format(now.getTime()));
        if (Arrays.equals(lines, mLines)) {
            return false;
        }
        final boolean newLineCount = lines.length != mLines.length;
        mLines = lines;
        if (newLineCount) {
            requestLayout();
        }
        return true;
    }

    private void onLookChanged() {
        final Look look = Look.read(getContext().getContentResolver());
        final Look old = mLook;
        mLook = look;
        if (look.layout != old.layout) {
            updateText();
        }
        if (!look.sameShape(old)) {
            // A new font look or depth: new digits, and maybe a new size
            mLaidOutWidth = -1;
            requestLayout();
            requestMasks();
        }
        invalidate();
    }

    private void readPowerSave() {
        final PowerManager powerManager = getContext().getSystemService(PowerManager.class);
        mPowerSave = powerManager != null && powerManager.isPowerSaveMode();
    }

    /**
     * Keeps the light following the phone while the clock shows its glass; not on the always-on
     * display, nor in Battery Saver, where the sensor isn't worth it.
     */
    private void updateTilt() {
        final boolean show = mAttached && mVisible && mSeen && !mDozing && !mPowerSave;
        if (show != mTiltShown) {
            mTiltShown = show;
            if (show) {
                GlassTilt.onOwnGlassShown(getContext(), this);
            } else {
                GlassTilt.onOwnGlassHidden(this);
            }
        }
    }

    private void applyLight() {
        mLightPosted.set(false);
        final long packed = mPendingLight.get();
        applyLight(packed);
    }

    private void applyLight(long packed) {
        float x = Float.intBitsToFloat((int) (packed >>> 32));
        float y = Float.intBitsToFloat((int) packed);
        // (0, 0) stands for the usual light
        if (x == 0f && y == 0f) {
            x = USUAL_LIGHT_X;
            y = USUAL_LIGHT_Y;
        }
        if (x != mLightX || y != mLightY) {
            mLightX = x;
            mLightY = y;
            invalidate();
        }
    }

    /**
     * Each frame: follows the clock as it moves, keeps its digits sharp at its size, and keeps
     * the light following the phone only while the clock can be seen.
     */
    private void onPreDraw() {
        final boolean seen = isSeen();
        if (seen != mSeen) {
            mSeen = seen;
            updateTilt();
        }
        if (getWidth() == 0 || getHeight() == 0 || !place(mNewViewToBackdrop)) {
            return;
        }
        if (!mPlaced || !mNewViewToBackdrop.equals(mViewToBackdrop)) {
            mViewToBackdrop.set(mNewViewToBackdrop);
            mPlaced = true;
            mInputsSet = false;
            invalidate();
        }
        if (needsBackdrop()) {
            requestBackdrop();
        }
        // Digits made for the size the clock is drawn at, once it stops changing size
        mViewToBackdrop.getValues(mScratchValues);
        final float scale = (float) Math.sqrt(Math.abs(mScratchValues[Matrix.MSCALE_X]
                * mScratchValues[Matrix.MSCALE_Y]
                - mScratchValues[Matrix.MSKEW_X] * mScratchValues[Matrix.MSKEW_Y]));
        final float wanted = Math.max(1f, Math.min(MAX_MASK_SCALE,
                Math.round(scale * 4f) / 4f));
        if (Math.abs(wanted - mMaskScale) <= RESCALE_SHARE * mMaskScale) {
            // Close enough to the size the digits were made for
            if (mWantedMaskScale != mMaskScale) {
                mWantedMaskScale = mMaskScale;
                mMainHandler.removeCallbacks(mRescale);
            }
        } else if (wanted != mWantedMaskScale) {
            mWantedMaskScale = wanted;
            mMainHandler.removeCallbacks(mRescale);
            mMainHandler.postDelayed(mRescale, RESCALE_DELAY);
        }
    }

    /** Whether the clock shows through its own and all its parents' visibility and alpha. */
    private boolean isSeen() {
        if (getVisibility() != VISIBLE) {
            return false;
        }
        float alpha = getAlpha() * getTransitionAlpha();
        for (Object parent = getParent(); parent instanceof View;
                parent = ((View) parent).getParent()) {
            final View view = (View) parent;
            if (view.getVisibility() != VISIBLE) {
                return false;
            }
            alpha *= view.getAlpha() * view.getTransitionAlpha();
        }
        return alpha > 0.01f;
    }

    private void rescale() {
        if (mWantedMaskScale != mMaskScale) {
            mMaskScale = mWantedMaskScale;
            requestMasks();
        }
    }

    /**
     * Where the view is over the wallpaper now, into {@code viewToBackdrop}; false if it can't
     * tell yet.
     */
    private boolean place(Matrix viewToBackdrop) {
        if (mPlace == PLACE_CARD) {
            final WindowManager windowManager =
                    getContext().getSystemService(WindowManager.class);
            if (windowManager == null) {
                return false;
            }
            final Rect display = windowManager.getMaximumWindowMetrics().getBounds();
            mBackdropWidth = display.width();
            mBackdropHeight = display.height();
            viewToBackdrop.setTranslate((mBackdropWidth - getWidth()) / 2f,
                    Math.round(CARD_TOP * mBackdropHeight));
            return true;
        }
        // The wallpaper fills the window: on the lock screen the whole screen, in a picker its
        // window
        final View root = getRootView();
        if (root == null || root.getWidth() == 0 || root.getHeight() == 0) {
            return false;
        }
        mBackdropWidth = root.getWidth();
        mBackdropHeight = root.getHeight();
        viewToBackdrop.reset();
        transformMatrixToGlobal(viewToBackdrop);
        root.getLocationOnScreen(mScratchLocation);
        viewToBackdrop.postTranslate(-mScratchLocation[0], -mScratchLocation[1]);
        return true;
    }

    /** Whether the wallpaper behind the clock has to be read (again). */
    private boolean needsBackdrop() {
        if (mDozing && mPlace == PLACE_LOCKSCREEN) {
            return false;
        }
        if (mBackdropLoading) {
            // One read at a time: whether it still covers the clock is looked at once it's in,
            // unless the wallpaper's place has changed size meanwhile
            return mLoadingWidth != mBackdropWidth || mLoadingHeight != mBackdropHeight;
        }
        mScratchRect.set(0, 0, getWidth(), getHeight());
        mViewToBackdrop.mapRect(mScratchRect);
        final Backdrop backdrop = mBackdrop;
        if (backdrop == null) {
            return true;
        }
        if (backdrop.screenWidth != mBackdropWidth || backdrop.screenHeight != mBackdropHeight) {
            return true;
        }
        if (backdrop.failed && SystemClock.uptimeMillis() - backdrop.madeAt > RETRY_DELAY) {
            // The wallpaper couldn't be read last time; meanwhile the digits are milky
            return true;
        }
        // A live wallpaper has no rows to cover
        return backdrop.sharp != null && (mScratchRect.top < backdrop.top
                || mScratchRect.bottom > backdrop.top + backdrop.sharp.getHeight());
    }

    /** Reads the wallpaper rows behind the clock, with room around it, on the worker. */
    private void requestBackdrop() {
        if (!mAttached || mBackdropWidth <= 0 || mBackdropHeight <= 0) {
            return;
        }
        mScratchRect.set(0, 0, getWidth(), getHeight());
        mViewToBackdrop.mapRect(mScratchRect);
        final int margin = mPlace == PLACE_CARD ? 0 : Math.round(mScratchRect.height() * 0.5f);
        final int top = Math.max(0, (int) Math.floor(mScratchRect.top) - margin);
        final int bottom = Math.min(mBackdropHeight,
                (int) Math.ceil(mScratchRect.bottom) + margin);
        if (bottom <= top) {
            return;
        }
        final int generation = ++mBackdropGeneration;
        mBackdropLoading = true;
        mLoadingTop = top;
        mLoadingBottom = bottom;
        mLoadingWidth = mBackdropWidth;
        mLoadingHeight = mBackdropHeight;
        final Context context = getContext();
        final int place = mPlace;
        final int user = ActivityManager.getCurrentUser();
        final int width = mBackdropWidth;
        final int height = mBackdropHeight;
        sWorker.execute(() -> {
            Backdrop read;
            try {
                read = Backdrop.load(context, place, user, width, height, top, bottom);
            } catch (RuntimeException | OutOfMemoryError e) {
                // Nothing may escape the worker: it would take the whole process down
                Log.w(TAG, "Can't read the wallpaper", e);
                read = Backdrop.none(width, height, top, true);
            }
            final Backdrop backdrop = read;
            mMainHandler.post(() -> {
                if (generation != mBackdropGeneration || !mAttached) {
                    backdrop.recycle();
                    return;
                }
                mBackdropLoading = false;
                if (mBackdrop != null) {
                    mBackdrop.recycle();
                }
                mBackdrop = backdrop;
                mInputsSet = false;
                invalidate();
            });
        });
    }

    private void dropBackdrop() {
        mBackdropGeneration++;
        mBackdropLoading = false;
        if (mBackdrop != null) {
            mBackdrop.recycle();
            mBackdrop = null;
        }
        mInputsSet = false;
    }

    /** Prepares the digits for the current time, size and look, on the worker. */
    private void requestMasks() {
        final int width = getWidth();
        final int height = getHeight();
        if (width == 0 || height == 0 || mTextSize <= 0 || mLaidOutWidth != width) {
            return;
        }
        final MaskSpec spec = new MaskSpec(mLines.clone(), mTypeface,
                mLook.variation(mTextSize, mDensity), mTextSize, width, height, mMaskScale,
                PAD_V * mTextSize - mDigitTop, mDigitHeight + LINE_GAP * mTextSize,
                DEPTH_SIGMA[mLook.depth] * mTextSize, SHADOW_SIGMA * mTextSize);
        final int generation = ++mMaskGeneration;
        sWorker.execute(() -> {
            final Masks masks;
            try {
                masks = Masks.render(spec);
            } catch (RuntimeException | OutOfMemoryError e) {
                // Nothing may escape the worker: it would take the whole process down. The
                // clock keeps the digits it has, if any, until the next minute.
                Log.w(TAG, "Can't prepare the digits", e);
                return;
            }
            mMainHandler.post(() -> {
                if (generation != mMaskGeneration) {
                    masks.recycle();
                    return;
                }
                if (mMasks != null) {
                    mMasks.recycle();
                }
                mMasks = masks;
                mInputsSet = false;
                invalidate();
            });
        });
    }

    private boolean ensureShader() {
        if (mShader == null && !mShaderFailed) {
            try {
                mShader = new RuntimeShader(SHADER);
                mPaint.setShader(mShader);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Glass clock shader failed, the clock draws plain digits", e);
                mShaderFailed = true;
            }
        }
        return mShader != null;
    }

    /** Gives the shader the digits and the wallpaper, placed where the clock is. */
    private void setInputs(Masks masks, @Nullable Backdrop backdrop) {
        final RuntimeShader shader = mShader;
        shader.setInputShader("glyphs", masks.glyphs);
        shader.setInputShader("bevelMap", masks.bevel);
        shader.setInputShader("shadowMap", masks.shadow);
        // Wallpaper rows to the view: the rows start at backdrop.top. A clock scaled to nothing
        // shows nothing anyway.
        if (backdrop != null && backdrop.sharp != null && mViewToBackdrop.invert(mScratch)) {
            mScratch.preTranslate(0, backdrop.top);
            backdrop.sharpShader.setLocalMatrix(mScratch);
            shader.setInputShader("backdrop", backdrop.sharpShader);
            mScratch.preScale(backdrop.softScaleX, backdrop.softScaleY);
            backdrop.softShader.setLocalMatrix(mScratch);
            shader.setInputShader("backdropSoft", backdrop.softShader);
        } else {
            final BitmapShader empty = emptyShader();
            shader.setInputShader("backdrop", empty);
            shader.setInputShader("backdropSoft", empty);
        }
        mInputsSet = true;
    }

    private void setUniforms(int mode) {
        final RuntimeShader shader = mShader;
        final Look look = mLook;
        final float size = mTextSize;
        final float sigma = DEPTH_SIGMA[look.depth] * size;
        final boolean aod = mode == MODE_AOD;
        shader.setFloatUniform("bevel", sigma);
        shader.setFloatUniform("slopeStep", Math.max(1f / mMasks.scale, sigma / 6f));
        shader.setFloatUniform("bend", MAX_BEND * size * fraction(look.bend));
        shader.setFloatUniform("frost", look.style == GlassSettings.CLOCK_FROSTED ? 1f : 0f);
        if (look.style == GlassSettings.CLOCK_TINTED) {
            final int accent = getContext().getColor(android.R.color.system_accent1_300);
            shader.setFloatUniform("tint", Color.red(accent) / 255f, Color.green(accent) / 255f,
                    Color.blue(accent) / 255f, TINT_COVER);
        } else {
            shader.setFloatUniform("tint", 0f, 0f, 0f, 0f);
        }
        shader.setFloatUniform("lift",
                look.style == GlassSettings.CLOCK_CLEAR ? CLEAR_LIFT : FROSTED_LIFT);
        final float light = fraction(look.light);
        shader.setFloatUniform("rimLight", aod ? Math.max(light, AOD_MIN_LIGHT) : light);
        shader.setFloatUniform("dispersion", fraction(look.rainbow));
        shader.setFloatUniform("lineWidth",
                (aod ? AOD_LINE_WIDTH_DP : LINE_WIDTH_DP) * mDensity);
        shader.setFloatUniform("shadow", MAX_SHADOW * fraction(look.shadow));
        // The shadow falls away from the light, mostly downward
        final float drop = SHADOW_DROP * size;
        shader.setFloatUniform("shadowDrop", -mLightX * drop * 0.5f, -mLightY * drop);
        shader.setFloatUniform("lightDir", mLightX, mLightY);
        double turn = Math.atan2(mLightY, mLightX) - USUAL_LIGHT_ANGLE;
        turn -= 2 * Math.PI * Math.floor((turn + Math.PI) / (2 * Math.PI));
        shader.setFloatUniform("lightTurn", (float) turn);
        shader.setFloatUniform("dimColor", Color.red(mDimColor) / 255f,
                Color.green(mDimColor) / 255f, Color.blue(mDimColor) / 255f);
        shader.setFloatUniform("dimAmount", mDimAmount);
        shader.setFloatUniform("mode", mode);
    }

    /** A card draws the wallpaper behind itself, as the lock screen shows it there. */
    private void drawCardBackdrop(Canvas canvas, @Nullable Backdrop backdrop) {
        if (backdrop != null && backdrop.sharp != null && mViewToBackdrop.invert(mScratch)) {
            mScratch.preTranslate(0, backdrop.top);
            canvas.save();
            canvas.concat(mScratch);
            canvas.drawBitmap(backdrop.sharp, 0, 0, mCardPaint);
            canvas.restore();
        } else {
            // A live wallpaper can't be shown: a plain dark backdrop instead
            canvas.drawColor(Color.rgb(48, 48, 56));
        }
        canvas.drawColor(Color.argb(mDimAmount, Color.red(mDimColor) / 255f,
                Color.green(mDimColor) / 255f, Color.blue(mDimColor) / 255f));
    }

    /** Without the shader: plain white digits. */
    private void drawPlain(Canvas canvas, Masks masks) {
        mPlainPaint.setColor(Color.WHITE);
        mPlainPaint.setAlpha(230);
        canvas.save();
        canvas.scale(1f / masks.scale, 1f / masks.scale);
        canvas.drawBitmap(masks.glyphsBitmap, 0, 0, mPlainPaint);
        canvas.restore();
    }

    private BitmapShader emptyShader() {
        if (mEmptyShader == null) {
            final Bitmap empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mEmptyShader = new BitmapShader(empty, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        return mEmptyShader;
    }

    private static float fraction(int percent) {
        return Math.max(GlassSettings.MIN_PERCENT, Math.min(GlassSettings.MAX_PERCENT, percent))
                / (float) GlassSettings.MAX_PERCENT;
    }

    /** The light GlassTilt gives now, packed as its listener's two floats. */
    private static long readTiltLight() {
        final float[] light = new float[2];
        GlassTilt.getLight(light);
        return ((long) Float.floatToRawIntBits(light[0]) << 32)
                | (Float.floatToRawIntBits(light[1]) & 0xffffffffL);
    }

    /** The Glass clock settings, read together. */
    private static final class Look {
        final int style;
        final int depth;
        final int bend;
        final int light;
        final int rainbow;
        final int shadow;
        final int weight;
        final int roundness;
        final int width;
        final int layout;

        private Look(ContentResolver resolver) {
            style = clamp(get(resolver, GlassSettings.CLOCK_STYLE,
                    GlassSettings.DEFAULT_CLOCK_STYLE), GlassSettings.CLOCK_CLEAR,
                    GlassSettings.CLOCK_TINTED);
            depth = clamp(get(resolver, GlassSettings.CLOCK_DEPTH,
                    GlassSettings.DEFAULT_CLOCK_DEPTH), GlassSettings.DEPTH_FLAT,
                    GlassSettings.DEPTH_DEEP);
            bend = get(resolver, GlassSettings.CLOCK_BEND, GlassSettings.DEFAULT_CLOCK_BEND);
            light = get(resolver, GlassSettings.CLOCK_LIGHT, GlassSettings.DEFAULT_CLOCK_LIGHT);
            rainbow = get(resolver, GlassSettings.CLOCK_RAINBOW,
                    GlassSettings.DEFAULT_CLOCK_RAINBOW);
            shadow = get(resolver, GlassSettings.CLOCK_SHADOW,
                    GlassSettings.DEFAULT_CLOCK_SHADOW);
            weight = clamp(get(resolver, GlassSettings.CLOCK_WEIGHT,
                    GlassSettings.DEFAULT_CLOCK_WEIGHT), GlassSettings.MIN_CLOCK_WEIGHT,
                    GlassSettings.MAX_CLOCK_WEIGHT);
            roundness = clamp(get(resolver, GlassSettings.CLOCK_ROUNDNESS,
                    GlassSettings.DEFAULT_CLOCK_ROUNDNESS), GlassSettings.MIN_CLOCK_ROUNDNESS,
                    GlassSettings.MAX_CLOCK_ROUNDNESS);
            width = clamp(get(resolver, GlassSettings.CLOCK_WIDTH,
                    GlassSettings.DEFAULT_CLOCK_WIDTH), GlassSettings.MIN_CLOCK_WIDTH,
                    GlassSettings.MAX_CLOCK_WIDTH);
            layout = get(resolver, GlassSettings.CLOCK_LAYOUT,
                    GlassSettings.DEFAULT_CLOCK_LAYOUT) == GlassSettings.CLOCK_STACKED
                            ? GlassSettings.CLOCK_STACKED : GlassSettings.CLOCK_ONE_LINE;
        }

        static Look read(ContentResolver resolver) {
            return new Look(resolver);
        }

        /** Whether the digits' shapes are the same, so they needn't be prepared again. */
        boolean sameShape(Look other) {
            return depth == other.depth && weight == other.weight
                    && roundness == other.roundness && width == other.width
                    && layout == other.layout;
        }

        /** The font's variation settings for this look at {@code size}. */
        String variation(float size, float density) {
            final float opticalSize = Math.max(6f, Math.min(144f, size / density));
            return String.format(Locale.ROOT, "'wght' %d, 'wdth' %d, 'ROND' %d, 'opsz' %.1f",
                    weight, width, roundness, opticalSize);
        }

        /** Sets {@code paint} up to draw the digits in this look at {@code size}. */
        void configure(Paint paint, Typeface typeface, float size, float density) {
            paint.setTypeface(typeface);
            paint.setTextSize(size);
            // Tabular digits, so the time keeps its width
            paint.setFontFeatureSettings("'tnum'");
            paint.setFontVariationSettings(variation(size, density));
        }

        private static int get(ContentResolver resolver, String key, int def) {
            return Settings.Global.getInt(resolver, key, def);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** What the digits are made from, taken on the main thread. */
    private static final class MaskSpec {
        final String[] lines;
        final Typeface typeface;
        final String variation;
        final float textSize;
        final int width;
        final int height;
        final float scale;
        // The first line's baseline, and from one baseline to the next
        final float baseline;
        final float lineAdvance;
        final float bevelSigma;
        final float shadowSigma;

        MaskSpec(String[] lines, Typeface typeface, String variation, float textSize, int width,
                int height, float scale, float baseline, float lineAdvance, float bevelSigma,
                float shadowSigma) {
            this.lines = lines;
            this.typeface = typeface;
            this.variation = variation;
            this.textSize = textSize;
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.baseline = baseline;
            this.lineAdvance = lineAdvance;
            this.bevelSigma = bevelSigma;
            this.shadowSigma = shadowSigma;
        }
    }

    /**
     * The digits as the shader reads them, at {@link #scale} pixels per view pixel: their
     * coverage, the same blurred into the glass's rounded height, and blurred wide for the
     * shadow.
     */
    private static final class Masks {
        final int width;
        final int height;
        final float scale;
        final Bitmap glyphsBitmap;
        final Bitmap bevelBitmap;
        final Bitmap shadowBitmap;
        final BitmapShader glyphs;
        final BitmapShader bevel;
        final BitmapShader shadow;

        private Masks(MaskSpec spec, Bitmap glyphs, Bitmap bevel, Bitmap shadow) {
            width = spec.width;
            height = spec.height;
            scale = spec.scale;
            glyphsBitmap = glyphs;
            bevelBitmap = bevel;
            shadowBitmap = shadow;
            final Matrix toView = new Matrix();
            toView.setScale(1f / scale, 1f / scale);
            this.glyphs = shader(glyphs, toView);
            this.bevel = shader(bevel, toView);
            this.shadow = shader(shadow, toView);
        }

        static Masks render(MaskSpec spec) {
            final int w = Math.max(1, (int) Math.ceil(spec.width * spec.scale));
            final int h = Math.max(1, (int) Math.ceil(spec.height * spec.scale));
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(spec.typeface);
            paint.setTextSize(spec.textSize);
            paint.setFontFeatureSettings("'tnum'");
            paint.setFontVariationSettings(spec.variation);
            return new Masks(spec, draw(spec, paint, w, h, 0f),
                    draw(spec, paint, w, h, spec.bevelSigma),
                    draw(spec, paint, w, h, spec.shadowSigma));
        }

        /** The lines centered, blurred by {@code sigma} (view pixels; 0 for sharp). */
        private static Bitmap draw(MaskSpec spec, Paint paint, int w, int h, float sigma) {
            final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            final Canvas canvas = new Canvas(bitmap);
            canvas.scale(spec.scale, spec.scale);
            // BlurMaskFilter's radius becomes sigma = 0.57735 * radius + 0.5, scaled with the
            // canvas
            final float radius = (sigma - 0.5f) / 0.57735f;
            paint.setMaskFilter(radius > 0f
                    ? new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) : null);
            for (int i = 0; i < spec.lines.length; i++) {
                final String line = spec.lines[i];
                final float x = (spec.width - paint.measureText(line)) / 2f;
                canvas.drawText(line, x, spec.baseline + i * spec.lineAdvance, paint);
            }
            paint.setMaskFilter(null);
            return bitmap;
        }

        private static BitmapShader shader(Bitmap bitmap, Matrix toView) {
            final BitmapShader shader =
                    new BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL);
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setLocalMatrix(toView);
            return shader;
        }

        void recycle() {
            glyphsBitmap.recycle();
            bevelBitmap.recycle();
            shadowBitmap.recycle();
        }
    }

    /**
     * The wallpaper rows behind the clock, as the screen shows them: from {@link #top} down,
     * across a wallpaper place of {@link #screenWidth} by {@link #screenHeight}. No rows (null
     * {@link #sharp}) when the wallpaper is live, or when it couldn't be read ({@link #failed}),
     * which is tried again a little later.
     */
    private static final class Backdrop {
        final int screenWidth;
        final int screenHeight;
        final int top;
        final boolean failed;
        final long madeAt = SystemClock.uptimeMillis();
        @Nullable final Bitmap sharp;
        @Nullable final Bitmap soft;
        @Nullable final BitmapShader sharpShader;
        @Nullable final BitmapShader softShader;
        // From the blurred rows' pixels to the sharp rows' pixels
        final float softScaleX;
        final float softScaleY;

        private Backdrop(int screenWidth, int screenHeight, int top, @Nullable Bitmap sharp,
                @Nullable Bitmap soft, boolean failed) {
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.top = top;
            this.sharp = sharp;
            this.soft = soft;
            this.failed = failed;
            if (sharp != null && soft != null) {
                sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                sharpShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                softShader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                softShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                softScaleX = sharp.getWidth() / (float) soft.getWidth();
                softScaleY = sharp.getHeight() / (float) soft.getHeight();
            } else {
                sharpShader = null;
                softShader = null;
                softScaleX = 1f;
                softScaleY = 1f;
            }
        }

        /** No rows: for a live wallpaper, or one that couldn't be read ({@code failed}). */
        static Backdrop none(int width, int height, int top, boolean failed) {
            return new Backdrop(width, height, top, null, null, failed);
        }

        /**
         * Reads the wallpaper rows {@code top} to {@code bottom} of a {@code width} by
         * {@code height} place it fills, center-cropped as the lock screen and the picker both
         * show it. Off the main thread.
         */
        static Backdrop load(Context context, int place, int user, int width, int height,
                int top, int bottom) {
            final WallpaperManager wallpaperManager =
                    context.getSystemService(WallpaperManager.class);
            if (wallpaperManager == null) {
                return none(width, height, top, true);
            }
            final Bitmap source;
            float wallpaperDim = 0f;
            if (place == PLACE_PICKER) {
                // The picker shows the home wallpaper, or the default one for a live one
                source = wallpaperManager.getBitmapAsUser(user, false,
                        WallpaperManager.FLAG_SYSTEM, true);
            } else {
                // The lock screen shows its own wallpaper if it has one, else the home one
                final boolean ownLock = wallpaperManager.getWallpaperIdForUser(
                        WallpaperManager.FLAG_LOCK, user) >= 0;
                final int which = ownLock
                        ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
                if (wallpaperManager.getWallpaperInfo(which, user) != null) {
                    // Live: there's nothing to read
                    return none(width, height, top, false);
                }
                source = wallpaperManager.getBitmapAsUser(user, false, which, !ownLock);
                try {
                    // Dimming set on the wallpaper (its surface's alpha over black)
                    wallpaperDim = wallpaperManager.getWallpaperDimAmount();
                } catch (SecurityException e) {
                    // Only SystemUI may know; a card shows it undimmed
                }
            }
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                // Such as before the first unlock, while the wallpaper waits for it
                return none(width, height, top, true);
            }
            final Bitmap sharp;
            try {
                sharp = Bitmap.createBitmap(width, bottom - top, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(sharp);
                final float scale = Math.max(width / (float) source.getWidth(),
                        height / (float) source.getHeight());
                final Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                matrix.postTranslate((width - source.getWidth() * scale) / 2f,
                        (height - source.getHeight() * scale) / 2f - top);
                // The wallpaper's bitmap may be recycled meanwhile by whoever else holds it:
                // drawing it then throws, and the caller tries again later
                canvas.drawBitmap(source, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
                if (wallpaperDim > 0f) {
                    canvas.drawColor(Color.argb(Math.min(1f, wallpaperDim), 0f, 0f, 0f));
                }
            } finally {
                if (place != PLACE_PICKER) {
                    // The whole wallpaper is big; only the rows are kept
                    wallpaperManager.forgetLoadedWallpaper();
                }
            }
            final Bitmap soft = Bitmap.createScaledBitmap(sharp,
                    Math.max(1, width / SOFT_SCALE), Math.max(1, (bottom - top) / SOFT_SCALE),
                    true);
            blur(soft, 2);
            blur(soft, 2);
            return new Backdrop(width, height, top, sharp, soft, false);
        }

        /** A box blur of {@code radius} pixels, along the rows then the columns. */
        private static void blur(Bitmap bitmap, int radius) {
            final int w = bitmap.getWidth();
            final int h = bitmap.getHeight();
            final int[] pixels = new int[w * h];
            final int[] line = new int[Math.max(w, h)];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int y = 0; y < h; y++) {
                blurLine(pixels, y * w, 1, w, radius, line);
            }
            for (int x = 0; x < w; x++) {
                blurLine(pixels, x, w, h, radius, line);
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        }

        private static void blurLine(int[] pixels, int start, int stride, int count, int radius,
                int[] line) {
            for (int i = 0; i < count; i++) {
                line[i] = pixels[start + i * stride];
            }
            final int size = 2 * radius + 1;
            for (int i = 0; i < count; i++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    final int c = line[Math.max(0, Math.min(count - 1, i + k))];
                    a += c >>> 24;
                    r += (c >> 16) & 0xff;
                    g += (c >> 8) & 0xff;
                    b += c & 0xff;
                }
                pixels[start + i * stride] =
                        (a / size) << 24 | (r / size) << 16 | (g / size) << 8 | (b / size);
            }
        }

        void recycle() {
            if (sharp != null) {
                sharp.recycle();
            }
            if (soft != null) {
                soft.recycle();
            }
        }
    }

    // The digits as glass: the wallpaper seen through them, bent at their rounded edges, lit
    // from the light's side, with a soft shadow the edge bends in too. Everything is in the
    // view's pixels. The bench in catalyxx-drafts/glass-lab/clock-proto renders this same text.
    private static final String SHADER = """
            // What's behind the clock, as it shows on screen there, sharp and blurred.
            uniform shader backdrop;
            uniform shader backdropSoft;
            // The digits: their coverage, the same blurred by the bevel's width (the glass's
            // height), and blurred wide for the shadow.
            uniform shader glyphs;
            uniform shader bevelMap;
            uniform shader shadowMap;

            // Sigma of bevelMap's blur, and the step its slope is read over.
            uniform float bevel;
            uniform float slopeStep;
            // How far the bevel pulls in what's just past the edge, at its steepest.
            uniform float bend;
            // 0 clear .. 1 frosted middle.
            uniform float frost;
            // Tinted glass: the color (straight) and how much of it covers what's seen through.
            uniform float4 tint;
            // How much lighter thick glass is than what's behind it, 0 to 1.
            uniform float lift;
            // Strength of the light on the edge, 0 to 1, and how much it splits into colors.
            uniform float rimLight;
            uniform float dispersion;
            // Width of the bright line along the edge.
            uniform float lineWidth;
            // Darkness of the shadow under the digits, 0 to 1, and how far it falls.
            uniform float shadow;
            uniform float2 shadowDrop;
            // Unit vector toward the light, y pointing down, and how far it has turned from its
            // usual place at the top left, in radians.
            uniform float2 lightDir;
            uniform float lightTurn;
            // What the screen lays over the wallpaper there (the lock screen's scrim): its color
            // and how much of it covers.
            uniform float3 dimColor;
            uniform float dimAmount;
            // 0: the backdrop is known (an image wallpaper). 1: it isn't (a live wallpaper), so
            // the glass is milky and lets the real one show through. 2: always-on display: no
            // backdrop, only the light on the glass's edges.
            uniform float mode;

            float3 seen(float2 q) {
                return mix(backdrop.eval(q).rgb, dimColor, dimAmount);
            }

            float shadowAt(float2 q) {
                if (shadow <= 0.0) {
                    return 0.0;
                }
                return shadow * shadowMap.eval(q - shadowDrop).a * (1.0 - glyphs.eval(q).a);
            }

            half4 main(float2 p) {
                float cover = glyphs.eval(p).a;
                if (cover <= 0.0) {
                    // Outside the digits: only their shadow, on what's behind.
                    return half4(0.0, 0.0, 0.0, half(mode > 1.5 ? 0.0 : shadowAt(p)));
                }

                // The glass's slope, from the blurred digits: 1 where a straight edge is
                // steepest.
                float2 g = float2(bevelMap.eval(p + float2(slopeStep, 0.0)).a -
                                  bevelMap.eval(p - float2(slopeStep, 0.0)).a,
                                  bevelMap.eval(p + float2(0.0, slopeStep)).a -
                                  bevelMap.eval(p - float2(0.0, slopeStep)).a) / (2.0 * slopeStep);
                float2 slope = g * bevel * 2.5066;
                float steep = min(length(slope), 1.0);
                float2 outward = length(slope) > 0.0001 ? -normalize(slope) : float2(0.0);
                // How far inside the edge this is: a blurred straight edge is half covered on
                // the edge and rises 0.4 per sigma there.
                float inside = max(0.0, (bevelMap.eval(p).a - 0.5) * 2.5066 * bevel);

                // Light: the surface tilts outward where it curves down to the edge.
                float3 normal = normalize(float3(-slope * 0.9, 1.0));
                float3 toLight = normalize(float3(lightDir, 0.75));
                float facing = dot(outward, lightDir);
                float shading = dot(normal, toLight) - toLight.z;
                float glint = pow(max(dot(normal, normalize(toLight + float3(0.0, 0.0, 1.0))),
                                      0.0), 40.0);
                float catchLight = 0.3 + 0.7 * pow(max(facing, 0.0), 2.0) +
                                   0.4 * pow(max(-facing, 0.0), 2.0);
                float line = 1.0 - smoothstep(0.0, lineWidth, inside);
                // The colors turn around the edge with the light, as on SurfaceFlinger's
                // glass. The flat middle has no edge direction (atan(0, 0) isn't a number).
                float angle = steep > 0.0001 ? atan(outward.y, outward.x) : 0.0;
                float3 rainbow = 0.5 + 0.5 * cos(5.76 + lightTurn - angle +
                                                 float3(0.0, 2.094, 4.189));
                float3 lineColor = mix(float3(1.0), rainbow, 0.8 * dispersion);
                float3 light = rimLight * (line * catchLight * lineColor +
                                           0.22 * steep * steep * steep * max(facing, 0.0) +
                                           0.8 * glint);

                if (mode > 0.5) {
                    // Nothing known behind: milky glass on a live wallpaper, nearly clear on
                    // black.
                    float milk = mode > 1.5 ? 0.08 : 0.26 + 0.14 * frost + 0.12 * steep;
                    float3 body = float3(milk) * (1.0 + 1.2 * shading);
                    float lit = max(light.r, max(light.g, light.b));
                    float alpha = 1.0 - (1.0 - milk) * (1.0 - min(lit, 1.0));
                    float3 color = body + light * (1.0 - milk);
                    return half4(half3(min(color, float3(alpha)) * cover), half(alpha * cover));
                }

                // The bevel pulls in what's past the edge, its steep outer part the most; blue
                // bends further than red.
                float2 offset = outward * bend * steep * steep;
                float3 color = float3(seen(p + offset * (1.0 - 0.5 * dispersion)).r,
                                      seen(p + offset).g,
                                      seen(p + offset * (1.0 + 0.5 * dispersion)).b);
                float3 soft = mix(backdropSoft.eval(p + offset).rgb, dimColor, dimAmount);
                // The middle is as frosted as asked; the steep edge stays clear so its bend
                // shows.
                color = mix(color, soft, frost * (1.0 - smoothstep(0.0, 0.8, steep)));
                // The shadow the digits cast, bent in by the edge.
                color *= 1.0 - shadowAt(p + offset);
                color = color + (1.0 - color) * lift;
                color = mix(color, tint.rgb, tint.a);
                color *= 1.0 + 0.6 * shading;

                // Over a light wallpaper a bright line can't be seen: the glass smokes a
                // little, more at its edges, and the line gives way to a thin dark one, so the
                // time stays readable.
                float backdropLight = dot(mix(backdropSoft.eval(p).rgb, dimColor, dimAmount),
                                          float3(0.2126, 0.7152, 0.0722));
                float overLight = smoothstep(0.5, 0.8, backdropLight);
                color *= 1.0 - overLight * (0.12 + 0.28 * steep);
                light *= 1.0 - 0.7 * overLight;
                color = 1.0 - (1.0 - color) * (1.0 - min(light, 1.0));
                color *= 1.0 - 0.45 * rimLight * overLight * line;

                float s = shadowAt(p);
                return half4(half3(clamp(color, 0.0, 1.0) * cover),
                             half(cover + s * (1.0 - cover)));
            }
            """;
}
