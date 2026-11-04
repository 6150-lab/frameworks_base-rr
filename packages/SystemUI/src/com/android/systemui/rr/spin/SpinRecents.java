/*
 * Copyright (C) 2026 The Resurrected Remix Project
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

package com.android.systemui.rr.spin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.ScreenCaptureInternal;
import android.window.ScreenCaptureInternal.ScreenshotHardwareBuffer;
import android.window.ScreenCaptureInternal.SynchronousScreenCaptureListener;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.android.systemui.res.R;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spin recents: a turn wheel of recent tasks for the recents key and the Launcher3 swipe-up
 * overview, picked with {@link com.android.systemui.rr.RecentsStyle#SPIN}.
 */
public final class SpinRecents {
    private static final String TAG = "RrSpinRecents";

    private static final int MAX_TASKS = 20;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rr-spin");
        thread.setDaemon(true);
        return thread;
    });

    private static final TimeInterpolator LINEAR = new LinearInterpolator();
    private static final TimeInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final TimeInterpolator DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final TimeInterpolator ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    private static final TimeInterpolator POP = new OvershootInterpolator(1.3f);
    private static final TimeInterpolator DETENT = new OvershootInterpolator(1.6f);
    private static final TimeInterpolator SPIN_DOWN = new DecelerateInterpolator(1.5f);

    private static int sGeneration;
    private static Wheel sWheel;

    private SpinRecents() {}

    /**
     * @return false when the wheel could not be shown, so the caller can open the launcher
     */
    public static boolean toggle(Context context) {
        if (sWheel != null) {
            hide();
            return true;
        }
        return show(context);
    }

    /**
     * @return false when the wheel could not be shown, so the caller can open the launcher
     */
    public static boolean show(Context context) {
        if (context == null) {
            return false;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> show(context));
            return true;
        }
        try {
            if (sWheel == null) {
                WindowManager wm = context.getSystemService(WindowManager.class);
                if (wm == null) {
                    return false;
                }
                Wheel wheel = new Wheel(context, wm);
                // Asked for before the window exists, so the wheel is not in it.
                SynchronousScreenCaptureListener screen = captureScreen();
                wm.addView(wheel, wheelWindow());
                sWheel = wheel;
                wheel.requestFocus();
                if (screen != null) {
                    EXEC.execute(() -> {
                        Bitmap backdrop;
                        try {
                            backdrop = blurredScreen(screen);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "No backdrop", e);
                            return;
                        }
                        if (backdrop != null) {
                            MAIN.post(() -> {
                                if (sWheel == wheel) {
                                    wheel.setBackdrop(backdrop);
                                }
                            });
                        }
                    });
                }
            }
            sWheel.reload();
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to show spin recents", e);
            hide();
            return false;
        }
    }

    public static void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(SpinRecents::hide);
            return;
        }
        sGeneration++;
        Wheel wheel = sWheel;
        sWheel = null;
        if (wheel != null) {
            wheel.exit(false);
        }
    }

    /** The backdrop is blurred at 1/12 size, then drawn back up smooth. */
    private static final int BACKDROP_SCALE = 12;
    private static final int BACKDROP_RADIUS = 4;

    private static WindowManager.LayoutParams wheelWindow() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTrustedOverlay();
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        lp.token = new Binder();
        lp.setTitle("RRSpinRecents");
        lp.windowAnimations = 0;
        return lp;
    }

    /** Asks for the screen as it is now; {@link #blurredScreen} collects it. */
    private static SynchronousScreenCaptureListener captureScreen() {
        SynchronousScreenCaptureListener listener =
                ScreenCaptureInternal.createSyncCaptureListener();
        try {
            WindowManagerGlobal.getWindowManagerService().captureDisplay(
                    Display.DEFAULT_DISPLAY, null, listener);
        } catch (RemoteException | SecurityException e) {
            return null;
        }
        return listener;
    }

    /**
     * The screen as it was before the wheel opened, shrunk and blurred into a soft wash so the
     * app behind doesn't show through. Cross-window blur is off on this device, so it is done
     * here, once per opening, off the main thread: the copy and scale are the slow part.
     */
    private static Bitmap blurredScreen(SynchronousScreenCaptureListener listener) {
        ScreenshotHardwareBuffer buffer = listener.getBuffer();
        Bitmap hardware = buffer != null ? buffer.asBitmap() : null;
        if (hardware == null) {
            return null;
        }
        Bitmap screen = hardware.copy(Bitmap.Config.ARGB_8888, false);
        hardware.recycle();
        if (screen == null) {
            return null;
        }
        int w = Math.max(1, screen.getWidth() / BACKDROP_SCALE);
        int h = Math.max(1, screen.getHeight() / BACKDROP_SCALE);
        Bitmap small = Bitmap.createScaledBitmap(screen, w, h, true);
        if (small != screen) {
            screen.recycle();
        }
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        // Three box passes each way come close to a gaussian.
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, w, h, BACKDROP_RADIUS, true);
            boxBlur(px, w, h, BACKDROP_RADIUS, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** One box-blur pass along rows or columns, clamped at the edges. */
    private static void boxBlur(int[] px, int w, int h, int radius, boolean rows) {
        int lines = rows ? h : w;
        int length = rows ? w : h;
        int span = 2 * radius + 1;
        int[] line = new int[length];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < length; i++) {
                line[i] = px[rows ? l * w + i : i * w + l];
            }
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = line[Math.max(0, Math.min(length - 1, i))];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                px[rows ? l * w + i : i * w + l] =
                        0xFF000000 | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int out = line[Math.max(0, i - radius)];
                int in = line[Math.min(length - 1, i + radius + 1)];
                r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static int alpha(int base, float fraction) {
        return Math.round(base * clamp01(fraction));
    }

    private static final class Task {
        final int id;
        final String label;
        final Drawable icon;
        final int color;
        final boolean visible;
        Bitmap thumb;
        String shown;
        float shownWidth;

        Task(int id, String label, Drawable icon, int color, boolean visible) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.visible = visible;
        }
    }

    private static List<Task> loadTasks(Context context) {
        try {
            ParceledListSlice<ActivityManager.RecentTaskInfo> slice =
                    ActivityTaskManager.getService().getRecentTasks(
                            MAX_TASKS,
                            ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                            ActivityManager.getCurrentUser());
            if (slice == null || slice.getList() == null) {
                return Collections.emptyList();
            }
            Map<Integer, PackageManager> managers = new HashMap<>();
            List<Task> tasks = new ArrayList<>();
            for (ActivityManager.RecentTaskInfo info : slice.getList()) {
                try {
                    Task task = toTask(context, managers, info);
                    if (task != null) {
                        tasks.add(task);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "Skipping a recent task", e);
                }
            }
            return tasks;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not read recent tasks", e);
            return Collections.emptyList();
        }
    }

    private static Task toTask(Context context, Map<Integer, PackageManager> managers,
            ActivityManager.RecentTaskInfo info) {
        if (info == null || info.taskId < 0) {
            return null;
        }
        if (info.topActivityType == WindowConfiguration.ACTIVITY_TYPE_HOME
                || info.topActivityType == WindowConfiguration.ACTIVITY_TYPE_DREAM) {
            return null;
        }
        Intent base = info.baseIntent;
        if (base != null && base.hasCategory(Intent.CATEGORY_HOME)) {
            return null;
        }
        ComponentName component = info.baseActivity;
        if (component == null && base != null) {
            component = base.getComponent();
        }
        if (component == null) {
            component = info.realActivity;
        }
        if (component == null) {
            component = info.origActivity;
        }
        if (component == null) {
            component = info.topActivity;
        }
        if (component == null) {
            return null;
        }
        PackageManager pm = managerFor(context, managers, info.userId);
        String label = component.getPackageName();
        int color = 0;
        if (info.taskDescription != null) {
            if (info.taskDescription.getLabel() != null
                    && info.taskDescription.getLabel().length() > 0) {
                label = info.taskDescription.getLabel();
            }
            color = info.taskDescription.getBackgroundColor();
            if (Color.alpha(color) == 0) {
                color = info.taskDescription.getPrimaryColor();
            }
        }
        if (Color.alpha(color) == 0) {
            color = 0xFF2B2B30;
        }
        Drawable icon = null;
        try {
            ActivityInfo activity = pm.getActivityInfo(component, 0);
            CharSequence loaded = activity.loadLabel(pm);
            if (loaded != null && loaded.length() > 0) {
                label = loaded.toString();
            }
            icon = pm.getActivityIcon(component);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Missing activity " + component, e);
        }
        if (icon == null) {
            icon = pm.getDefaultActivityIcon();
        }
        icon = pm.getUserBadgedIcon(icon.mutate(), UserHandle.of(info.userId)).mutate();
        return new Task(info.taskId, label, icon, color | 0xFF000000, info.isVisible);
    }

    private static PackageManager managerFor(Context context, Map<Integer, PackageManager> managers,
            int userId) {
        PackageManager existing = managers.get(userId);
        if (existing != null) {
            return existing;
        }
        PackageManager pm;
        try {
            pm = context.createContextAsUser(UserHandle.of(userId), 0).getPackageManager();
        } catch (RuntimeException e) {
            Log.w(TAG, "Package manager for user " + userId, e);
            pm = context.getPackageManager();
        }
        managers.put(userId, pm);
        return pm;
    }

    private static Bitmap loadThumb(Task task) {
        try {
            ActivityManagerWrapper am = ActivityManagerWrapper.getInstance();
            ThumbnailData data = task.visible
                    ? am.takeTaskThumbnail(task.id)
                    : am.getTaskThumbnail(task.id, true /* isLowResolution */);
            return data.getThumbnail();
        } catch (RuntimeException e) {
            Log.w(TAG, "No thumbnail for task " + task.id, e);
            return null;
        }
    }

    private static void removeTask(int taskId) {
        try {
            ActivityTaskManager.getService().removeTask(taskId);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not remove task " + taskId, e);
        }
    }

    /**
     * Full-screen window: the selected task's preview card, the wheel, and a scrim. A partial
     * window would pass outside taps through to the app underneath.
     */
    private static final class Wheel extends View {
        private static final float SELECTED = 1.3f;
        private static final float ICON_MIN_DP = 30f;
        private static final float ICON_MAX_DP = 56f;
        private static final float CARD_SLIDE = 0.42f;
        /** Ring geometry scale. Touch margins and the hub label keep their full size. */
        private static final float RING_SCALE = 0.5f;
        private static final int SCRIM_ALPHA = 0xC4;
        private static final String EMPTY = "No recent apps";

        private static final int HIT_SCRIM = -1;
        private static final int HIT_CARD = -2;
        private static final int HIT_HUB = -3;
        private static final int HIT_RING = -4;

        private static final int DRAG_NONE = 0;
        private static final int DRAG_IGNORE = 1;
        private static final int DRAG_TURN = 2;
        private static final int DRAG_SWIPE = 3;
        private static final int DRAG_DISMISS = 4;

        private final WindowManager mWindowManager;
        private final float mDensity;
        private final int mSlop;
        private final int mFlingVelocity;
        private final int mIconPx;
        private final String mClearLabel;
        private final List<Task> mTasks = new ArrayList<>();
        private int[] mOrder = new int[0];
        private float[] mOffsets = new float[0];

        private final Paint mScrim = new Paint();
        private final Paint mBackdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect mBackdropDst = new Rect();
        private Bitmap mBackdrop;
        private final Paint mBand = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLensEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHubFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint mLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mHubText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mBox = new RectF();
        private final RectF mRect = new RectF();
        private final Rect mSafe = new Rect();
        private Insets mNavCut = Insets.NONE;
        private String mClearShown;

        private float mCx;
        private float mCy;
        private float mRadius;
        private float mHub;
        private float mSelAngle;
        private int mDir = 1;

        private float mPos;
        private int mTick;
        private float mGap;
        private int mGapIndex;
        private Task mGone;
        private float mShow;
        private float mIn;
        private float mClear;
        private float mCardDrag;
        private boolean mLoaded;
        private boolean mExiting;
        private boolean mRemoving;
        private boolean mHubPressed;

        private ValueAnimator mShowAnim;
        private ValueAnimator mInAnim;
        private ValueAnimator mTurn;
        private ValueAnimator mCardAnim;
        private ValueAnimator mGapAnim;
        private ValueAnimator mClearAnim;

        private VelocityTracker mVelocity;
        private int mDown = HIT_SCRIM;
        private int mDrag = DRAG_NONE;
        private float mDownX;
        private float mDownY;
        private float mLastX;
        private float mLastAngle;

        private BroadcastReceiver mCloser;
        private final OnBackInvokedCallback mBack = this::close;
        private final ViewTreeObserver.OnComputeInternalInsetsListener mTouchable =
                this::computeTouchable;
        private final Access mAccess;

        Wheel(Context context, WindowManager wm) {
            super(context);
            mWindowManager = wm;
            mDensity = context.getResources().getDisplayMetrics().density;
            ViewConfiguration config = ViewConfiguration.get(context);
            mSlop = config.getScaledTouchSlop();
            mFlingVelocity = config.getScaledMinimumFlingVelocity() * 4;
            mIconPx = Math.round(dp(ICON_MAX_DP) * SELECTED);
            mClearLabel = context.getString(R.string.clear_all_notifications_text);
            mClearShown = mClearLabel;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setAccessibilityPaneTitle(context.getString(R.string.accessibility_recent));

            int accent = context.getColor(android.R.color.system_accent1_200);
            mScrim.setColor(0xFF08080C);
            mBand.setStyle(Paint.Style.STROKE);
            mBand.setColor(Color.WHITE);
            mDot.setColor(Color.WHITE);
            mAccent.setColor(accent);
            mLensEdge.setStyle(Paint.Style.STROKE);
            mLensEdge.setStrokeWidth(dp(2));
            mLensEdge.setColor(accent);
            mHubFill.setColor(Color.WHITE);
            mEdge.setStyle(Paint.Style.STROKE);
            mEdge.setStrokeWidth(dp(1));
            mEdge.setColor(Color.WHITE);
            Typeface medium = Typeface.create(Typeface.DEFAULT, 500, false);
            mLabelPaint.setColor(Color.WHITE);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTextSize(sp(18));
            mLabelPaint.setTypeface(medium);
            mHubText.setColor(Color.WHITE);
            mHubText.setTextAlign(Paint.Align.CENTER);
            mHubText.setTextSize(sp(13));
            mHubText.setTypeface(medium);

            mAccess = new Access();
            ViewCompat.setAccessibilityDelegate(this, mAccess);
        }

        void reload() {
            final int generation = ++sGeneration;
            final Context context = getContext();
            EXEC.execute(() -> {
                List<Task> tasks = loadTasks(context);
                MAIN.post(() -> {
                    if (generation != sGeneration || sWheel != this) {
                        return;
                    }
                    setTasks(tasks);
                    loadThumbs(generation, new ArrayList<>(mTasks));
                });
            });
        }

        private void setTasks(List<Task> tasks) {
            int keep = mTasks.isEmpty() ? -1 : mTasks.get(selected()).id;
            stopTurn();
            if (mGapAnim != null) {
                mGapAnim.end();
            }
            mTasks.clear();
            mTasks.addAll(tasks);
            int n = mTasks.size();
            if (mOrder.length < n) {
                mOrder = new int[n];
                mOffsets = new float[n];
            }
            int selected = n > 1 && mTasks.get(0).visible ? 1 : 0;
            for (int i = 0; i < n; i++) {
                if (mTasks.get(i).id == keep) {
                    selected = i;
                    break;
                }
            }
            mPos = selected;
            mTick = selected;
            mCardDrag = 0f;
            boolean first = !mLoaded;
            mLoaded = true;
            if (first && !mExiting) {
                startIn();
            }
            mAccess.invalidateRoot();
            invalidate();
        }

        /** Nearest to the selection point first, so the visible card fills in before the rest. */
        private void loadThumbs(int generation, List<Task> tasks) {
            int n = tasks.size();
            if (n == 0) {
                return;
            }
            int start = selected();
            List<Task> order = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                int offset = (k + 1) / 2 * (k % 2 == 1 ? 1 : -1);
                order.add(tasks.get(Math.floorMod(start + offset, n)));
            }
            EXEC.execute(() -> {
                for (Task task : order) {
                    if (generation != sGeneration) {
                        return;
                    }
                    Bitmap thumb = loadThumb(task);
                    if (thumb == null) {
                        continue;
                    }
                    MAIN.post(() -> {
                        if (generation == sGeneration && sWheel == this) {
                            task.thumb = thumb;
                            invalidate();
                        }
                    });
                }
            });
        }

        private void startIn() {
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(420);
            anim.setInterpolator(LINEAR);
            anim.addUpdateListener(a -> {
                mIn = (float) a.getAnimatedValue();
                invalidate();
            });
            mInAnim = anim;
            anim.start();
        }

        void exit(boolean launching) {
            if (mExiting) {
                return;
            }
            mExiting = true;
            unlisten();
            stopTurn();
            try {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
                if (lp != null) {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    mWindowManager.updateViewLayout(this, lp);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not release spin recents input", e);
            }
            if (mShowAnim != null) {
                mShowAnim.cancel();
            }
            ValueAnimator anim = ValueAnimator.ofFloat(mShow, 0f);
            anim.setDuration(launching ? 140 : 200);
            anim.setInterpolator(ACCELERATE);
            anim.addUpdateListener(a -> {
                mShow = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        mWindowManager.removeView(Wheel.this);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Spin recents was already removed", e);
                    }
                }
            });
            mShowAnim = anim;
            anim.start();
        }

        private void close() {
            if (sWheel == this) {
                hide();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnComputeInternalInsetsListener(mTouchable);
            if (mExiting) {
                return;
            }
            listen();
            OnBackInvokedDispatcher dispatcher = findOnBackInvokedDispatcher();
            if (dispatcher != null) {
                dispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBack);
            }
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(220);
            anim.setInterpolator(LINEAR);
            anim.addUpdateListener(a -> {
                mShow = (float) a.getAnimatedValue();
                invalidate();
            });
            mShowAnim = anim;
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (sWheel == this) {
                sGeneration++;
                sWheel = null;
            }
            OnBackInvokedDispatcher dispatcher = findOnBackInvokedDispatcher();
            if (dispatcher != null) {
                dispatcher.unregisterOnBackInvokedCallback(mBack);
            }
            getViewTreeObserver().removeOnComputeInternalInsetsListener(mTouchable);
            unlisten();
            stopTurn();
            for (ValueAnimator anim : new ValueAnimator[] {
                    mShowAnim, mInAnim, mCardAnim, mGapAnim, mClearAnim}) {
                if (anim != null) {
                    anim.removeAllListeners();
                    anim.cancel();
                }
            }
            if (mVelocity != null) {
                mVelocity.recycle();
                mVelocity = null;
            }
            super.onDetachedFromWindow();
        }

        private void listen() {
            if (mCloser != null) {
                return;
            }
            BroadcastReceiver closer = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    close();
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            try {
                getContext().registerReceiver(closer, filter, null, MAIN,
                        Context.RECEIVER_EXPORTED);
                mCloser = closer;
            } catch (RuntimeException e) {
                Log.w(TAG, "Not listening for close requests", e);
            }
        }

        private void unlisten() {
            BroadcastReceiver closer = mCloser;
            mCloser = null;
            if (closer == null) {
                return;
            }
            try {
                getContext().unregisterReceiver(closer);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Close receiver was already gone", e);
            }
        }

        private void computeTouchable(ViewTreeObserver.InternalInsetsInfo info) {
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(mNavCut.left, 0, getWidth() - mNavCut.right,
                    getHeight() - mNavCut.bottom);
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            mSafe.set(safe.left, safe.top, safe.right, safe.bottom);
            Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets tappable = insets.getInsets(WindowInsets.Type.tappableElement());
            mNavCut = Insets.of(Math.min(nav.left, tappable.left), 0,
                    Math.min(nav.right, tappable.right), Math.min(nav.bottom, tappable.bottom));
            layoutWheel();
            return insets;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            layoutWheel();
        }

        private void layoutWheel() {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            float left = mSafe.left;
            float top = mSafe.top;
            float right = w - mSafe.right;
            float bottom = h - mSafe.bottom;
            float width = right - left;
            float height = bottom - top;
            float margin = dp(20);
            float label = dp(60);
            boolean portrait = height >= width;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            mDir = rtl ? -1 : 1;
            mRadius = RING_SCALE * (portrait
                    ? Math.min(width * 0.34f, height * 0.2f)
                    : Math.min(height * 0.33f, width * 0.2f));
            float lens = rdp(ICON_MAX_DP) * SELECTED / 2f;
            float outer = mRadius + lens + rdp(8);
            // Wide enough for the "Clear all" label at its normal size.
            mHub = clamp(mRadius - lens - rdp(14), dp(28), dp(38));
            if (portrait) {
                mCx = left + width / 2f;
                mCy = bottom - outer - margin;
                mSelAngle = (float) (-Math.PI / 2);
                mBox.set(left + margin * 2, top + margin, right - margin * 2,
                        mCy - outer - label);
            } else {
                mCy = top + height / 2f;
                mCx = rtl ? left + outer + margin : right - outer - margin;
                mSelAngle = rtl ? 0f : (float) Math.PI;
                mBox.set(rtl ? mCx + outer + margin * 2 : left + margin * 2, top + margin,
                        rtl ? right - margin * 2 : mCx - outer - margin * 2, bottom - label);
            }
            mClearShown = TextUtils.ellipsize(mClearLabel, mHubText, mHub * 1.7f,
                    TextUtils.TruncateAt.END).toString();
            for (Task task : mTasks) {
                task.shown = null;
            }
            mAccess.invalidateRoot();
            invalidate();
        }

        private int selected() {
            int n = mTasks.size();
            return n == 0 ? -1 : Math.floorMod(Math.round(mPos), n);
        }

        private float step() {
            return (float) (2 * Math.PI / Math.max(1f, mTasks.size() + mGap));
        }

        /** Offset in slots of a ring position from the selection point, on the near side. */
        private float wrap(float offset) {
            float slots = Math.max(1f, mTasks.size() + mGap);
            return offset - slots * (float) Math.floor(offset / slots + 0.5f);
        }

        private float offset(int index) {
            return wrap(index - mPos + (index >= mGapIndex ? mGap : 0f));
        }

        private float iconSize() {
            float arc = mRadius * step();
            return clamp(arc * 0.7f, rdp(ICON_MIN_DP), rdp(ICON_MAX_DP));
        }

        private float spin() {
            return mDir * ((1f - DECELERATE.getInterpolation(mIn)) * 0.9f + mClear * 1.6f);
        }

        private float grow() {
            float grow = 0.4f + 0.6f * POP.getInterpolation(mIn);
            if (mExiting) {
                grow *= 0.85f + 0.15f * mShow;
            }
            return grow * (1f - mClear);
        }

        private float fade() {
            return clamp01(mIn * 1.4f) * (mExiting ? mShow : 1f) * (1f - mClear);
        }

        /** Places the icon {@code offset} slots from the selection point; returns 0 front, 1 back. */
        private float place(float offset, float r, float icon, RectF out) {
            float step = step();
            double angle = mSelAngle + mDir * offset * step + spin();
            float depth = Math.min(1f, Math.abs(offset * step) / (float) Math.PI);
            float near = Math.max(0f, 1f - Math.abs(offset));
            float half = icon * (1f + (SELECTED - 1f) * near) * (1f - 0.24f * depth) / 2f;
            float x = mCx + r * (float) Math.cos(angle);
            float y = mCy + r * (float) Math.sin(angle);
            out.set(x - half, y - half, x + half, y + half);
            return depth;
        }

        /** Card for a task, fitted to its snapshot and resting on the bottom of the card area. */
        private void cardRect(Task task, RectF out) {
            float aspect = task.thumb != null && task.thumb.getHeight() > 0
                    ? (float) task.thumb.getWidth() / task.thumb.getHeight()
                    : (float) getWidth() / Math.max(1, getHeight());
            float width = Math.max(1f, mBox.width());
            float height = width / aspect;
            if (height > mBox.height()) {
                height = Math.max(1f, mBox.height());
                width = height * aspect;
            }
            out.set(mBox.centerX() - width / 2f, mBox.bottom - height,
                    mBox.centerX() + width / 2f, mBox.bottom);
        }

        void setBackdrop(Bitmap backdrop) {
            mBackdrop = backdrop;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (mBackdrop != null) {
                mBackdropPaint.setAlpha(alpha(255, mShow * 1.4f));
                mBackdropDst.set(0, 0, w, h);
                canvas.save();
                canvas.clipRect(mNavCut.left, 0, w - mNavCut.right, h - mNavCut.bottom);
                canvas.drawBitmap(mBackdrop, null, mBackdropDst, mBackdropPaint);
                canvas.restore();
            }
            mScrim.setAlpha(alpha(SCRIM_ALPHA, mShow * 1.4f));
            canvas.drawRect(mNavCut.left, 0, w - mNavCut.right, h - mNavCut.bottom, mScrim);
            if (mTasks.isEmpty()) {
                if (mLoaded) {
                    mLabelPaint.setAlpha(alpha(0xB3, mShow));
                    canvas.drawText(EMPTY, w / 2f, h / 2f, mLabelPaint);
                }
                return;
            }
            drawCards(canvas);
            drawWheel(canvas);
        }

        private void drawCards(Canvas canvas) {
            int n = mTasks.size();
            float drag = clamp01(-mCardDrag / Math.max(1f, mBox.height()));
            float fade = fade() * (1f - mGap) * (1f - drag);
            float lift = (1f - DECELERATE.getInterpolation(mIn)) * dp(32) + mCardDrag;
            float base = (float) Math.floor(mPos);
            float frac = mPos - base;
            int a = Math.floorMod((int) base, n);
            if (n == 1 || frac < 0.001f) {
                drawCard(canvas, mTasks.get(a), 0f, lift, fade, 1f);
                return;
            }
            int b = (a + 1) % n;
            float slide = mBox.width() * CARD_SLIDE * mDir;
            drawCard(canvas, mTasks.get(a), -frac * slide, lift, fade * (1f - frac),
                    1f - 0.08f * frac);
            drawCard(canvas, mTasks.get(b), (1f - frac) * slide, lift, fade * frac,
                    0.92f + 0.08f * frac);
        }

        private void drawCard(Canvas canvas, Task task, float dx, float dy, float fade,
                float scale) {
            if (fade <= 0.004f) {
                return;
            }
            cardRect(task, mRect);
            float cx = mRect.centerX() + dx;
            float cy = mRect.centerY() + dy;
            float hw = mRect.width() * scale / 2f;
            float hh = mRect.height() * scale / 2f;
            mRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float corner = dp(20) * scale;
            int a = alpha(255, fade);
            mPath.reset();
            mPath.addRoundRect(mRect, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mPath);
            if (task.thumb != null) {
                mThumbPaint.setAlpha(a);
                canvas.drawBitmap(task.thumb, null, mRect, mThumbPaint);
            } else {
                mFill.setColor(task.color);
                mFill.setAlpha(a);
                canvas.drawRect(mRect, mFill);
                float size = Math.min(dp(72), mRect.width() * 0.45f);
                drawIcon(canvas, task.icon, mRect.centerX(), mRect.centerY(), size, a);
            }
            canvas.restore();
            mEdge.setAlpha(alpha(0x29, fade));
            canvas.drawRoundRect(mRect, corner, corner, mEdge);
            mLabelPaint.setAlpha(a);
            canvas.drawText(label(task), cx, mRect.bottom + dp(34), mLabelPaint);
        }

        private String label(Task task) {
            float width = Math.max(dp(80), mBox.width());
            if (task.shown == null || task.shownWidth != width) {
                task.shown = TextUtils.ellipsize(task.label, mLabelPaint, width,
                        TextUtils.TruncateAt.END).toString();
                task.shownWidth = width;
            }
            return task.shown;
        }

        private void drawWheel(Canvas canvas) {
            int n = mTasks.size();
            float fade = fade();
            if (fade <= 0.004f) {
                return;
            }
            float r = mRadius * grow();
            float icon = iconSize();
            float step = step();
            float spin = spin();

            mBand.setStrokeWidth(icon + rdp(24));
            mBand.setAlpha(alpha(0x17, fade));
            canvas.drawCircle(mCx, mCy, r, mBand);
            if (n > 1) {
                mDot.setAlpha(alpha(0x52, fade));
                for (int i = 0; i < n; i++) {
                    double angle = mSelAngle + mDir * (offset(i) + 0.5f) * step + spin;
                    canvas.drawCircle(mCx + r * (float) Math.cos(angle),
                            mCy + r * (float) Math.sin(angle), dp(1.75f), mDot);
                }
            }

            float lens = icon * SELECTED / 2f + rdp(8);
            float cos = (float) Math.cos(mSelAngle);
            float sin = (float) Math.sin(mSelAngle);
            mAccent.setAlpha(alpha(0x3D, fade));
            canvas.drawCircle(mCx + r * cos, mCy + r * sin, lens, mAccent);
            mLensEdge.setAlpha(alpha(0xE6, fade));
            canvas.drawCircle(mCx + r * cos, mCy + r * sin, lens, mLensEdge);
            float px = mCx + (r + lens + rdp(9)) * cos;
            float py = mCy + (r + lens + rdp(9)) * sin;
            mPath.reset();
            mPath.moveTo(px + cos * rdp(6), py + sin * rdp(6));
            mPath.lineTo(px - sin * rdp(7), py + cos * rdp(7));
            mPath.lineTo(px + sin * rdp(7), py - cos * rdp(7));
            mPath.close();
            mAccent.setAlpha(alpha(0xE6, fade));
            canvas.drawPath(mPath, mAccent);

            if (mGone != null && mGap > 0f) {
                drawRingIcon(canvas, mGone, wrap(mGapIndex - mPos + (mGap - 1f) / 2f), r,
                        icon * mGap, fade * mGap);
            }
            for (int i = 0; i < n; i++) {
                mOrder[i] = i;
                mOffsets[i] = offset(i);
            }
            for (int i = 1; i < n; i++) {
                int index = mOrder[i];
                float key = Math.abs(mOffsets[index]);
                int j = i - 1;
                while (j >= 0 && Math.abs(mOffsets[mOrder[j]]) < key) {
                    mOrder[j + 1] = mOrder[j];
                    j--;
                }
                mOrder[j + 1] = index;
            }
            for (int k = 0; k < n; k++) {
                int index = mOrder[k];
                drawRingIcon(canvas, mTasks.get(index), mOffsets[index], r, icon, fade);
            }

            float hub = mHub * grow();
            mHubFill.setAlpha(alpha(mHubPressed ? 0x3D : 0x1F, fade));
            canvas.drawCircle(mCx, mCy, hub, mHubFill);
            mHubText.setAlpha(alpha(0xE6, fade));
            canvas.drawText(mClearShown, mCx,
                    mCy - (mHubText.descent() + mHubText.ascent()) / 2f, mHubText);
        }

        private void drawRingIcon(Canvas canvas, Task task, float offset, float r, float icon,
                float fade) {
            float depth = place(offset, r, icon, mRect);
            int a = alpha(255, fade * (1f - 0.5f * depth));
            float x = mRect.centerX();
            float y = mRect.centerY();
            float size = mRect.width();
            drawIcon(canvas, task.icon, x, y, size, a);
            if (task.visible) {
                float dx = x - mCx;
                float dy = y - mCy;
                float length = Math.max(1f, (float) Math.hypot(dx, dy));
                float out = size / 2f + rdp(6);
                mAccent.setAlpha(a);
                canvas.drawCircle(x + dx / length * out, y + dy / length * out, dp(2.5f),
                        mAccent);
            }
        }

        /** Fixed integer bounds and a canvas transform, so turning icons do not snap to pixels. */
        private void drawIcon(Canvas canvas, Drawable icon, float x, float y, float size,
                int alpha) {
            if (size <= 0f || alpha <= 0) {
                return;
            }
            icon.setBounds(0, 0, mIconPx, mIconPx);
            icon.setAlpha(alpha);
            float scale = size / mIconPx;
            canvas.save();
            canvas.translate(x - size / 2f, y - size / 2f);
            canvas.scale(scale, scale);
            icon.draw(canvas);
            canvas.restore();
        }

        private int hit(float x, float y) {
            int n = mTasks.size();
            if (n == 0) {
                return HIT_SCRIM;
            }
            cardRect(mTasks.get(selected()), mRect);
            mRect.bottom += dp(52);
            if (mRect.contains(x, y)) {
                return HIT_CARD;
            }
            float distance = (float) Math.hypot(x - mCx, y - mCy);
            if (distance <= mHub) {
                return HIT_HUB;
            }
            float r = mRadius * grow();
            float icon = iconSize();
            if (distance > r + icon * SELECTED / 2f + dp(16)) {
                return HIT_SCRIM;
            }
            int best = HIT_RING;
            float bestDistance = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                place(offset(i), r, icon, mRect);
                float d = (float) Math.hypot(x - mRect.centerX(), y - mRect.centerY());
                if (d < mRect.width() / 2f + dp(10) && d < bestDistance) {
                    best = i;
                    bestDistance = d;
                }
            }
            return best;
        }

        private float angleAt(float x, float y) {
            return (float) Math.atan2(y - mCy, x - mCx);
        }

        private float swipeUnit() {
            return Math.max(dp(96), mBox.width() * CARD_SLIDE);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mExiting || mRemoving || mClearAnim != null) {
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopTurn();
                    if (mVelocity != null) {
                        mVelocity.recycle();
                    }
                    mVelocity = VelocityTracker.obtain();
                    mVelocity.addMovement(event);
                    mDownX = x;
                    mDownY = y;
                    mLastX = x;
                    mLastAngle = angleAt(x, y);
                    mDrag = DRAG_NONE;
                    mDown = hit(x, y);
                    mHubPressed = mDown == HIT_HUB;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mVelocity == null) {
                        return true;
                    }
                    mVelocity.addMovement(event);
                    if (mDrag == DRAG_NONE
                            && Math.hypot(x - mDownX, y - mDownY) > mSlop) {
                        mDrag = dragMode(x - mDownX, y - mDownY);
                        mHubPressed = false;
                        mLastX = x;
                        mLastAngle = angleAt(x, y);
                        invalidate();
                    }
                    drag(x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mVelocity == null) {
                        return true;
                    }
                    mVelocity.addMovement(event);
                    mVelocity.computeCurrentVelocity(1000);
                    release(x, y, mVelocity.getXVelocity(), mVelocity.getYVelocity());
                    endTouch();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (mDrag == DRAG_DISMISS) {
                        animateCard(0f, 220, EMPHASIZED, null);
                    } else if (mDrag == DRAG_TURN || mDrag == DRAG_SWIPE) {
                        settle();
                    }
                    endTouch();
                    return true;
                default:
                    return true;
            }
        }

        private void endTouch() {
            if (mVelocity != null) {
                mVelocity.recycle();
                mVelocity = null;
            }
            mDrag = DRAG_NONE;
            mHubPressed = false;
            if (mTurn == null && !mExiting && mPos != Math.round(mPos)) {
                settle();
            }
            invalidate();
        }

        private int dragMode(float dx, float dy) {
            boolean several = mTasks.size() > 1;
            boolean vertical = Math.abs(dy) > Math.abs(dx);
            switch (mDown) {
                case HIT_CARD:
                    if (vertical) {
                        return dy < 0 ? DRAG_DISMISS : DRAG_IGNORE;
                    }
                    return several ? DRAG_SWIPE : DRAG_IGNORE;
                case HIT_SCRIM:
                    return several && !vertical ? DRAG_SWIPE : DRAG_IGNORE;
                default:
                    return several ? DRAG_TURN : DRAG_IGNORE;
            }
        }

        private void drag(float x, float y) {
            switch (mDrag) {
                case DRAG_TURN: {
                    float angle = angleAt(x, y);
                    float delta = angle - mLastAngle;
                    if (delta > Math.PI) {
                        delta -= (float) (2 * Math.PI);
                    } else if (delta < -Math.PI) {
                        delta += (float) (2 * Math.PI);
                    }
                    mLastAngle = angle;
                    setPos(mPos - mDir * delta / step());
                    break;
                }
                case DRAG_SWIPE:
                    setPos(mPos - mDir * (x - mLastX) / swipeUnit());
                    mLastX = x;
                    break;
                case DRAG_DISMISS: {
                    float dy = y - mDownY;
                    mCardDrag = dy < 0 ? dy : dy * 0.2f;
                    invalidate();
                    break;
                }
                default:
                    break;
            }
        }

        private void release(float x, float y, float vx, float vy) {
            switch (mDrag) {
                case DRAG_NONE:
                    tap();
                    break;
                case DRAG_TURN: {
                    float rx = x - mCx;
                    float ry = y - mCy;
                    float r2 = Math.max(rx * rx + ry * ry, dp(48) * dp(48));
                    float omega = (rx * vy - ry * vx) / r2;
                    fling(-mDir * omega / step());
                    break;
                }
                case DRAG_SWIPE:
                    fling(-mDir * vx / swipeUnit());
                    break;
                case DRAG_DISMISS:
                    if (mCardDrag < -mBox.height() * 0.25f || vy < -mFlingVelocity) {
                        dismissSelected();
                    } else {
                        animateCard(0f, 220, EMPHASIZED, null);
                    }
                    break;
                default:
                    break;
            }
        }

        private void tap() {
            switch (mDown) {
                case HIT_SCRIM:
                    close();
                    break;
                case HIT_CARD:
                    launch(selected());
                    break;
                case HIT_HUB:
                    clearAll();
                    break;
                case HIT_RING:
                    break;
                default: {
                    int index = mDown;
                    if (index == selected()) {
                        launch(index);
                    } else {
                        turnToIndex(index, () -> launch(index));
                    }
                    break;
                }
            }
        }

        private void setPos(float pos) {
            mPos = pos;
            int slot = Math.round(pos);
            if (slot != mTick) {
                mTick = slot;
                performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
            }
            invalidate();
        }

        private void normalize() {
            int n = mTasks.size();
            if (n > 0) {
                mPos = Math.floorMod(Math.round(mPos), n);
                mTick = Math.round(mPos);
            }
            mAccess.invalidateRoot();
            invalidate();
        }

        private void stopTurn() {
            ValueAnimator turn = mTurn;
            mTurn = null;
            if (turn != null) {
                turn.cancel();
            }
        }

        private void turnTo(float target, long duration, TimeInterpolator interpolator,
                Runnable after) {
            stopTurn();
            if (Math.abs(target - mPos) < 0.001f) {
                setPos(target);
                normalize();
                if (after != null) {
                    after.run();
                }
                return;
            }
            ValueAnimator anim = ValueAnimator.ofFloat(mPos, target);
            anim.setDuration(duration);
            anim.setInterpolator(interpolator);
            anim.addUpdateListener(a -> setPos((float) a.getAnimatedValue()));
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean mCanceled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCanceled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mTurn == animation) {
                        mTurn = null;
                    }
                    if (mCanceled) {
                        return;
                    }
                    normalize();
                    if (after != null) {
                        after.run();
                    }
                }
            });
            mTurn = anim;
            anim.start();
        }

        private void settle() {
            turnTo(Math.round(mPos), 200, DETENT, null);
        }

        /** @param velocity slots per second */
        private void fling(float velocity) {
            if (Math.abs(velocity) < 1.2f) {
                settle();
                return;
            }
            int n = mTasks.size();
            float travel = clamp(velocity * 0.3f, -n, n);
            float target = Math.round(mPos + travel);
            float distance = Math.abs(target - mPos);
            long duration = (long) clamp(3000f * distance / Math.abs(velocity), 220f, 900f);
            turnTo(target, duration, SPIN_DOWN, null);
        }

        private void nudge(int slots) {
            if (mTasks.size() < 2) {
                return;
            }
            turnTo(Math.round(mPos) + slots, 180, EMPHASIZED, null);
        }

        private void turnToIndex(int index, Runnable after) {
            float distance = wrap(index - mPos);
            long duration = (long) Math.min(420f, 160f + 55f * Math.abs(distance));
            turnTo(Math.round(mPos + distance), duration, EMPHASIZED, after);
        }

        private void launch(int index) {
            if (mExiting || index < 0 || index >= mTasks.size()) {
                return;
            }
            Task task = mTasks.get(index);
            cardRect(task, mRect);
            Bundle options = ActivityOptions.makeScaleUpAnimation(this,
                    Math.round(mRect.left), Math.round(mRect.top),
                    Math.round(mRect.width()), Math.round(mRect.height())).toBundle();
            if (sWheel == this) {
                sGeneration++;
                sWheel = null;
            }
            exit(true);
            int taskId = task.id;
            boolean inFront = task.visible;
            EXEC.execute(() -> {
                try {
                    // Relaunching the task already in front races the launcher's own
                    // swipe-up finish and can leave input stuck; closing the wheel is enough.
                    ActivityManager.RunningTaskInfo top =
                            ActivityManagerWrapper.getInstance().getRunningTask();
                    if (inFront && top != null && top.taskId == taskId) {
                        return;
                    }
                    int result = ActivityTaskManager.getService().startActivityFromRecents(
                            taskId, options);
                    if (result < 0) {
                        Log.w(TAG, "startActivityFromRecents returned " + result);
                    }
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "Could not start task " + taskId, e);
                }
            });
        }

        private void animateCard(float to, long duration, TimeInterpolator interpolator,
                Runnable end) {
            if (mCardAnim != null) {
                mCardAnim.cancel();
            }
            ValueAnimator anim = ValueAnimator.ofFloat(mCardDrag, to);
            anim.setDuration(duration);
            anim.setInterpolator(interpolator);
            anim.addUpdateListener(a -> {
                mCardDrag = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean mCanceled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCanceled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mCardAnim == animation) {
                        mCardAnim = null;
                    }
                    if (!mCanceled && end != null) {
                        end.run();
                    }
                }
            });
            mCardAnim = anim;
            anim.start();
        }

        private void dismissSelected() {
            int index = selected();
            if (index < 0 || mExiting || mRemoving) {
                return;
            }
            stopTurn();
            mRemoving = true;
            animateCard(-(mBox.bottom + dp(64)), 180, ACCELERATE, () -> removeAt(index));
        }

        /** The ring closes over the removed slot while the next task slides under the lens. */
        private void removeAt(int index) {
            mRemoving = false;
            if (index < 0 || index >= mTasks.size()) {
                return;
            }
            stopTurn();
            if (mGapAnim != null) {
                mGapAnim.end();
            }
            Task gone = mTasks.remove(index);
            int taskId = gone.id;
            EXEC.execute(() -> removeTask(taskId));
            mCardDrag = 0f;
            if (mTasks.isEmpty()) {
                close();
                return;
            }
            mGone = gone;
            mGapIndex = index;
            mGap = 1f;
            mPos = index;
            mTick = index;
            ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
            anim.setDuration(280);
            anim.setInterpolator(EMPHASIZED);
            anim.addUpdateListener(a -> {
                mGap = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mGapAnim == animation) {
                        mGapAnim = null;
                    }
                    mGap = 0f;
                    mGone = null;
                    normalize();
                }
            });
            mGapAnim = anim;
            anim.start();
            mAccess.invalidateRoot();
        }

        private void clearAll() {
            if (mTasks.isEmpty() || mExiting || mClearAnim != null) {
                return;
            }
            stopTurn();
            sGeneration++;
            List<Task> tasks = new ArrayList<>(mTasks);
            EXEC.execute(() -> {
                for (Task task : tasks) {
                    removeTask(task.id);
                }
            });
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(320);
            anim.setInterpolator(ACCELERATE);
            anim.addUpdateListener(a -> {
                mClear = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    close();
                }
            });
            mClearAnim = anim;
            anim.start();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int code = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
                close();
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || mExiting) {
                return super.dispatchKeyEvent(event);
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    nudge(mDir);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_UP:
                    nudge(-mDir);
                    return true;
                case KeyEvent.KEYCODE_TAB:
                    nudge(event.isShiftPressed() ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_SPACE:
                    launch(selected());
                    return true;
                case KeyEvent.KEYCODE_DEL:
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    dismissSelected();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        @Override
        protected boolean dispatchHoverEvent(MotionEvent event) {
            return mAccess.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
        }

        private float dp(float value) {
            return value * mDensity;
        }

        /** dp for the ring itself, see {@link #RING_SCALE}. */
        private float rdp(float value) {
            return dp(value) * RING_SCALE;
        }

        private float sp(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                    getResources().getDisplayMetrics());
        }

        /** Card, each icon on the ring, and the hub, for touch exploration. */
        private final class Access extends ExploreByTouchHelper {
            private static final int CARD = 1000;
            private static final int HUB = 1001;

            Access() {
                super(Wheel.this);
            }

            @Override
            protected int getVirtualViewAt(float x, float y) {
                int hit = hit(x, y);
                if (hit >= 0) {
                    return hit;
                }
                return hit == HIT_CARD ? CARD : hit == HIT_HUB ? HUB : INVALID_ID;
            }

            @Override
            protected void getVisibleVirtualViews(List<Integer> ids) {
                int n = mTasks.size();
                if (n == 0 || mExiting) {
                    return;
                }
                ids.add(CARD);
                int selected = selected();
                for (int k = 0; k < n; k++) {
                    ids.add(Math.floorMod(selected + k, n));
                }
                ids.add(HUB);
            }

            @Override
            protected void onPopulateNodeForVirtualView(int id,
                    AccessibilityNodeInfoCompat node) {
                Rect bounds = new Rect();
                int n = mTasks.size();
                if (id == HUB && n > 0) {
                    node.setContentDescription(mClearLabel);
                    bounds.set(Math.round(mCx - mHub), Math.round(mCy - mHub),
                            Math.round(mCx + mHub), Math.round(mCy + mHub));
                    node.addAction(AccessibilityActionCompat.ACTION_CLICK);
                } else if (n > 0 && (id == CARD || (id >= 0 && id < n))) {
                    int index = id == CARD ? selected() : id;
                    Task task = mTasks.get(index);
                    node.setContentDescription(task.label);
                    if (id == CARD) {
                        cardRect(task, mRect);
                    } else {
                        place(offset(index), mRadius * grow(), iconSize(), mRect);
                    }
                    mRect.roundOut(bounds);
                    node.addAction(AccessibilityActionCompat.ACTION_CLICK);
                    node.addAction(AccessibilityActionCompat.ACTION_DISMISS);
                } else {
                    node.setContentDescription("");
                }
                if (bounds.isEmpty()) {
                    bounds.set(0, 0, 1, 1);
                }
                node.setBoundsInParent(bounds);
            }

            @Override
            protected boolean onPerformActionForVirtualView(int id, int action,
                    Bundle arguments) {
                int n = mTasks.size();
                boolean task = n > 0 && (id == CARD || (id >= 0 && id < n));
                if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                    if (id == HUB) {
                        clearAll();
                        return true;
                    }
                    if (task) {
                        launch(id == CARD ? selected() : id);
                        return true;
                    }
                } else if (action == AccessibilityNodeInfoCompat.ACTION_DISMISS && task) {
                    removeAt(id == CARD ? selected() : id);
                    return true;
                }
                return false;
            }
        }
    }
}
