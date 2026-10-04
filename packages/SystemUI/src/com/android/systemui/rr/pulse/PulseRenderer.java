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

package com.android.systemui.rr.pulse;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.systemui.navigationbar.views.NavigationBarView;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Navbar music bars for the default display. Drawn from {@link NavigationBarView#onDraw}
 * so the bars stay behind the buttons and never take touches.
 *
 * <p>Remixer writes the same Settings.System names: {@code pulse_navbar_enabled} and
 * {@code pulse_style} (0 solid lines, 1 fading blocks).
 */
public final class PulseRenderer {
    private static final String TAG = "RrPulse";

    /** Settings.System int. 1 draws bars while audio is playing. */
    public static final String KEY_ENABLED = "pulse_navbar_enabled";
    /** Settings.System int. 0 solid lines, 1 fading blocks. */
    public static final String KEY_STYLE = "pulse_style";

    private static final int BARS = 28;
    private static final int STYLE_FADING = 1;
    private static final int COLOR = 0xFFE02437;
    private static final int FRAME_MS = 25;
    private static final int TRAIL_FRAMES = 10;

    private static final WeakHashMap<NavigationBarView, Session> SESSIONS = new WeakHashMap<>();
    private static HandlerThread sThread;
    private static Handler sAudio;

    private PulseRenderer() {}

    public static void attach(NavigationBarView view) {
        if (view == null) {
            return;
        }
        Display display = view.getDisplay();
        if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
            return;
        }
        detach(view);
        Session session = new Session(view);
        SESSIONS.put(view, session);
        session.start();
    }

    public static void detach(NavigationBarView view) {
        if (view == null) {
            return;
        }
        Session session = SESSIONS.remove(view);
        if (session != null) {
            session.stop();
        }
    }

    /** Called from {@link NavigationBarView#onDraw} between the dead zone and the buttons. */
    public static void draw(NavigationBarView view, Canvas canvas) {
        Session session = SESSIONS.get(view);
        if (session == null) {
            return;
        }
        session.draw(view, canvas);
    }

    private static Handler audioThread() {
        synchronized (PulseRenderer.class) {
            if (sAudio == null) {
                sThread = new HandlerThread("rr-pulse");
                sThread.start();
                sAudio = new Handler(sThread.getLooper());
            }
            return sAudio;
        }
    }

    private static final class Session {
        private final WeakReference<NavigationBarView> mView;
        private final Context mContext;
        private final Object mLock = new Object();
        private final float[] mLevels = new float[BARS];
        private final float[] mTargets = new float[BARS];
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFadePaint = new Paint();
        private final Handler mMain = new Handler(Looper.getMainLooper());

        private ContentObserver mObserver;
        private boolean mReceiverRegistered;
        private boolean mReleased;
        private boolean mLinking;
        private boolean mDrawForced;
        private boolean mEnabled;
        private boolean mScreenOn = true;
        private boolean mPowerSave;
        private int mStyle;
        private int mTrail;
        private long mLastDraw;
        private long mLastFft;
        private Visualizer mVisualizer;
        private Bitmap mBitmap;
        private Canvas mBitmapCanvas;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mReleased || intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mScreenOn = false;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mScreenOn = true;
                } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                    PowerManager power = mContext.getSystemService(PowerManager.class);
                    mPowerSave = power != null && power.isPowerSaveMode();
                }
                syncLink();
            }
        };

        Session(NavigationBarView view) {
            mView = new WeakReference<>(view);
            mContext = view.getContext();
            mPaint.setColor(COLOR);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mFadePaint.setColor(0xE6FFFFFF);
            mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        }

        void start() {
            PowerManager power = mContext.getSystemService(PowerManager.class);
            mScreenOn = power == null || power.isInteractive();
            mPowerSave = power != null && power.isPowerSaveMode();
            mObserver = new ContentObserver(mMain) {
                @Override
                public void onChange(boolean selfChange) {
                    if (!mReleased) {
                        readSettings();
                        syncLink();
                    }
                }
            };
            try {
                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(KEY_ENABLED), false, mObserver,
                        UserHandle.USER_ALL);
                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(KEY_STYLE), false, mObserver,
                        UserHandle.USER_ALL);
            } catch (RuntimeException e) {
                Log.w(TAG, "Not watching pulse settings", e);
                try {
                    mContext.getContentResolver().unregisterContentObserver(mObserver);
                } catch (RuntimeException cleanup) {
                    Log.w(TAG, "Pulse observer cleanup failed", cleanup);
                }
                mObserver = null;
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            try {
                mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                mReceiverRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Not watching screen state", e);
            }
            readSettings();
            syncLink();
        }

        void stop() {
            mReleased = true;
            if (mObserver != null) {
                try {
                    mContext.getContentResolver().unregisterContentObserver(mObserver);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Pulse observer already gone", e);
                }
                mObserver = null;
            }
            if (mReceiverRegistered) {
                try {
                    mContext.unregisterReceiver(mReceiver);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Receiver already gone", e);
                }
                mReceiverRegistered = false;
            }
            synchronized (mLock) {
                Arrays.fill(mTargets, 0f);
                Arrays.fill(mLevels, 0f);
            }
            recycleBitmap();
            setDrawing(false);
            mView.clear();
            audioThread().post(this::releaseVisualizer);
        }

        private void readSettings() {
            int user = ActivityManager.getCurrentUser();
            mEnabled = Settings.System.getIntForUser(
                    mContext.getContentResolver(), KEY_ENABLED, 0, user) == 1;
            int style = Settings.System.getIntForUser(
                    mContext.getContentResolver(), KEY_STYLE, 0, user) == STYLE_FADING
                    ? STYLE_FADING : 0;
            if (style != mStyle) {
                mStyle = style;
                recycleBitmap();
                mTrail = 0;
            }
        }

        private void syncLink() {
            boolean want = mEnabled && mScreenOn && !mPowerSave && !mReleased;
            if (want) {
                // FrameLayout skips onDraw unless this is cleared, and only while pulse is on.
                setDrawing(true);
                audioThread().post(this::linkVisualizer);
            } else {
                audioThread().post(this::releaseVisualizer);
                synchronized (mLock) {
                    Arrays.fill(mTargets, 0f);
                }
                setDrawing(false);
            }
        }

        private void setDrawing(boolean draw) {
            NavigationBarView view = mView.get();
            if (view == null || draw == mDrawForced) {
                return;
            }
            mDrawForced = draw;
            if (draw) {
                view.setWillNotDraw(false);
            } else {
                view.setWillNotDraw(view.getBackground() == null);
            }
            view.invalidate();
        }

        private void linkVisualizer() {
            synchronized (mLock) {
                if (mReleased || mVisualizer != null || mLinking) {
                    return;
                }
                mLinking = true;
            }
            Visualizer created = null;
            try {
                created = new Visualizer(0);
                created.setEnabled(false);
                int[] range = Visualizer.getCaptureSizeRange();
                if (range == null || range.length < 2) {
                    throw new IllegalStateException("no capture size");
                }
                created.setCaptureSize(range[1]);
                int maxRate = Visualizer.getMaxCaptureRate();
                int rate = maxRate > 0 ? (int) (maxRate * 0.75f) : 10000;
                int status = created.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(
                            Visualizer visualizer, byte[] waveform, int samplingRate) {
                    }

                    @Override
                    public void onFftDataCapture(
                            Visualizer visualizer, byte[] fft, int samplingRate) {
                        onFft(fft);
                    }
                }, rate, false /* waveform */, true /* fft */);
                if (status != Visualizer.SUCCESS) {
                    throw new IllegalStateException("capture listener " + status);
                }
                created.setEnabled(true);
            } catch (RuntimeException e) {
                Log.w(TAG, "Visualizer unavailable", e);
                if (created != null) {
                    try {
                        created.release();
                    } catch (RuntimeException ignored) {
                    }
                    created = null;
                }
            }
            final Visualizer viz = created;
            synchronized (mLock) {
                mLinking = false;
                if (viz == null) {
                    return;
                }
                if (mReleased || mVisualizer != null) {
                    try {
                        viz.release();
                    } catch (RuntimeException ignored) {
                    }
                    return;
                }
                mVisualizer = viz;
            }
            NavigationBarView view = mView.get();
            if (view != null) {
                view.post(() -> view.invalidate());
            }
        }

        private void releaseVisualizer() {
            Visualizer viz;
            synchronized (mLock) {
                viz = mVisualizer;
                mVisualizer = null;
            }
            if (viz == null) {
                return;
            }
            try {
                viz.setDataCaptureListener(null, 0, false, false);
                viz.setEnabled(false);
                viz.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Visualizer release failed", e);
            }
        }

        private void onFft(byte[] fft) {
            float[] next = new float[BARS];
            decode(fft, next);
            boolean poke = false;
            synchronized (mLock) {
                if (mReleased) {
                    return;
                }
                mLastFft = SystemClock.uptimeMillis();
                System.arraycopy(next, 0, mTargets, 0, BARS);
                poke = audible(next) || audible(mLevels);
            }
            NavigationBarView view = mView.get();
            if (poke && view != null) {
                view.postInvalidateOnAnimation();
            }
        }

        void draw(NavigationBarView view, Canvas canvas) {
            if (mReleased || !view.isAttachedToWindow()) {
                return;
            }
            int width = view.getWidth();
            int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            int style;
            boolean pending;
            synchronized (mLock) {
                if (mLastFft != 0 && SystemClock.uptimeMillis() - mLastFft > 400) {
                    Arrays.fill(mTargets, 0f);
                }
                style = mStyle;
                pending = audible(mTargets) || audible(mLevels);
            }
            if (!pending && (style != STYLE_FADING || mTrail <= 0)) {
                recycleBitmap();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (mLastDraw != 0 && now - mLastDraw < FRAME_MS) {
                view.postInvalidateDelayed(FRAME_MS - (now - mLastDraw));
                return;
            }
            mLastDraw = now;
            float[] levels = new float[BARS];
            synchronized (mLock) {
                for (int i = 0; i < BARS; i++) {
                    mLevels[i] += (mTargets[i] - mLevels[i]) * 0.45f;
                    if (mLevels[i] < 0.012f && mTargets[i] < 0.012f) {
                        mLevels[i] = 0f;
                    }
                    levels[i] = mLevels[i];
                }
            }
            boolean moving = audible(levels);
            if (style == STYLE_FADING) {
                if (moving) {
                    mTrail = TRAIL_FRAMES;
                } else if (mTrail > 0) {
                    mTrail--;
                }
            }
            boolean fromLeft = growsFromLeft(view);
            try {
                if (style == STYLE_FADING) {
                    drawFading(canvas, levels, width, height, fromLeft);
                } else {
                    drawBars(canvas, levels, width, height, fromLeft);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Pulse draw failed", e);
                recycleBitmap();
                return;
            }
            view.postInvalidateDelayed(FRAME_MS);
        }

        private void drawFading(Canvas canvas, float[] levels, int width, int height,
                boolean fromLeft) {
            if (mBitmap == null
                    || mBitmap.getWidth() != width
                    || mBitmap.getHeight() != height) {
                recycleBitmap();
                mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                mBitmap.setDensity(Bitmap.DENSITY_NONE);
                mBitmapCanvas = new Canvas(mBitmap);
            }
            mBitmapCanvas.drawPaint(mFadePaint);
            drawBars(mBitmapCanvas, levels, width, height, fromLeft);
            canvas.drawBitmap(mBitmap, 0f, 0f, null);
        }

        private void drawBars(Canvas canvas, float[] levels, int width, int height,
                boolean fromLeft) {
            boolean vertical = height > width;
            float span = vertical ? height : width;
            float depth = vertical ? width : height;
            float gap = Math.max(1f, span / (BARS * 8f));
            float thick = (span - gap * (BARS + 1)) / BARS;
            if (thick < 1f) {
                thick = 1f;
            }
            mPaint.setStrokeWidth(Math.max(2f, thick * 0.72f));
            for (int i = 0; i < BARS; i++) {
                float amp = levels[i] * Math.max(0f, depth - 4f);
                if (amp < 1f) {
                    continue;
                }
                float across = gap + i * (thick + gap) + thick / 2f;
                if (vertical) {
                    if (fromLeft) {
                        canvas.drawLine(2f, across, 2f + amp, across, mPaint);
                    } else {
                        canvas.drawLine(width - 2f, across, width - 2f - amp, across, mPaint);
                    }
                } else {
                    canvas.drawLine(across, height - 2f, across, height - 2f - amp, mPaint);
                }
            }
        }

        private void recycleBitmap() {
            if (mBitmap != null) {
                mBitmap.recycle();
                mBitmap = null;
                mBitmapCanvas = null;
            }
        }

        private static boolean growsFromLeft(NavigationBarView view) {
            try {
                int[] xy = new int[2];
                view.getLocationOnScreen(xy);
                int screen = view.getResources().getDisplayMetrics().widthPixels;
                return xy[0] + view.getWidth() / 2 < screen / 2;
            } catch (RuntimeException e) {
                Log.w(TAG, "Nav bar location unavailable", e);
                return false;
            }
        }

        private static boolean audible(float[] values) {
            for (float value : values) {
                if (value > 0.02f) {
                    return true;
                }
            }
            return false;
        }

        /** Lower two thirds of the FFT, split evenly. Signed bytes are fine for hypot. */
        private static void decode(byte[] fft, float[] out) {
            Arrays.fill(out, 0f);
            if (fft == null || fft.length < 8) {
                return;
            }
            int complexBins = fft.length / 2 - 1;
            int used = Math.max(BARS, (complexBins * 2) / 3);
            if (used > complexBins) {
                used = complexBins;
            }
            int per = Math.max(1, used / out.length);
            for (int i = 0; i < out.length; i++) {
                int start = i * per;
                float sum = 0f;
                int count = 0;
                for (int bin = start; bin < start + per && bin < used; bin++) {
                    int idx = 2 + bin * 2;
                    if (idx + 1 >= fft.length) {
                        break;
                    }
                    sum += (float) Math.hypot(fft[idx], fft[idx + 1]);
                    count++;
                }
                if (count == 0) {
                    continue;
                }
                float norm = (float) Math.sqrt(Math.min(1d, (sum / count) / 140d));
                out[i] = norm < 0.08f ? 0f : norm;
            }
        }
    }
}
