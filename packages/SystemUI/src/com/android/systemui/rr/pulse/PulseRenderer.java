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

package com.android.systemui.rr.pulse;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
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
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

/**
 * Music bars along the bottom of the default display while media plays. Drawn in an
 * untouchable overlay so it works with gesture nav and the launcher taskbar alike.
 *
 * <p>Remixer's Pulse page writes the {@code pulse_*} Settings.System keys below.
 */
@SysUISingleton
public final class PulseRenderer implements CoreStartable {
    private static final String TAG = "RrPulse";

    public static final String KEY_ENABLED = "pulse_navbar_enabled";
    /** 0 lines, 1 fading, 2 blocks, 3 mirror. */
    public static final String KEY_STYLE = "pulse_style";
    /** 0 custom color, 1 system accent, 2 gradient. */
    public static final String KEY_COLOR_MODE = "pulse_color_mode";
    public static final String KEY_COLOR = "pulse_color";
    public static final String KEY_BARS = "pulse_bar_count";
    /** dp. */
    public static final String KEY_HEIGHT = "pulse_height";
    /** Percent. */
    public static final String KEY_OPACITY = "pulse_opacity";
    public static final String KEY_LOCKSCREEN = "pulse_lockscreen";

    private static final String[] KEYS = {KEY_ENABLED, KEY_STYLE, KEY_COLOR_MODE, KEY_COLOR,
            KEY_BARS, KEY_HEIGHT, KEY_OPACITY, KEY_LOCKSCREEN};

    private static final int STYLE_LINES = 0;
    private static final int STYLE_FADING = 1;
    private static final int STYLE_BLOCKS = 2;
    private static final int STYLE_MIRROR = 3;
    private static final int COLOR_CUSTOM = 0;
    private static final int COLOR_ACCENT = 1;
    private static final int COLOR_GRADIENT = 2;

    private static final int DEFAULT_COLOR = 0xFFE02437;
    private static final int DEFAULT_BARS = 28;
    private static final int MIN_BARS = 12;
    private static final int MAX_BARS = 48;
    private static final int DEFAULT_HEIGHT_DP = 48;
    private static final int MIN_HEIGHT_DP = 24;
    private static final int MAX_HEIGHT_DP = 120;
    private static final int DEFAULT_OPACITY = 90;
    private static final int[] GRADIENT = {0xFFFF3B5C, 0xFFFF9F1C, 0xFFFFE14D, 0xFF3DDC97,
            0xFF2EC4F1, 0xFF7B61FF, 0xFFFF3B9A};

    private static final int FRAME_MS = 25;
    private static final int TRAIL_FRAMES = 10;
    private static final int SILENCE_MS = 400;

    private final Context mContext;
    private final KeyguardStateController mKeyguard;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();

    private Handler mAudio;
    private Context mWindowContext;
    private WindowManager mWindowManager;
    private PulseView mView;

    private boolean mEnabled;
    private boolean mLockscreen = true;
    private boolean mScreenOn = true;
    private boolean mPowerSave;
    private boolean mPlaying;
    private int mStyle;
    private int mColorMode;
    private int mColor = DEFAULT_COLOR;
    private int mHeightDp = DEFAULT_HEIGHT_DP;
    private int mOpacity = DEFAULT_OPACITY;

    // Guarded by mLock; written by the audio thread, read while drawing.
    private float[] mTargets = new float[DEFAULT_BARS];
    private long mLastFft;
    // Audio thread only.
    private Visualizer mVisualizer;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOn = true;
            } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                PowerManager power = mContext.getSystemService(PowerManager.class);
                mPowerSave = power != null && power.isPowerSaveMode();
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                readSettings();
            }
            update();
        }
    };

    @Inject
    public PulseRenderer(Context context, KeyguardStateController keyguard) {
        mContext = context;
        mKeyguard = keyguard;
    }

    @Override
    public void start() {
        Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        mWindowContext = mContext.createWindowContext(display,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null);
        mWindowManager = mWindowContext.getSystemService(WindowManager.class);
        HandlerThread thread = new HandlerThread("rr-pulse");
        thread.start();
        mAudio = new Handler(thread.getLooper());

        PowerManager power = mContext.getSystemService(PowerManager.class);
        mScreenOn = power == null || power.isInteractive();
        mPowerSave = power != null && power.isPowerSaveMode();

        ContentObserver observer = new ContentObserver(mMain) {
            @Override
            public void onChange(boolean selfChange) {
                readSettings();
                update();
            }
        };
        ContentResolver resolver = mContext.getContentResolver();
        for (String key : KEYS) {
            resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer,
                    UserHandle.USER_ALL);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mReceiver, filter, null, mMain, Context.RECEIVER_NOT_EXPORTED);

        AudioManager audio = mContext.getSystemService(AudioManager.class);
        audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                boolean playing = isPlaying(configs);
                if (playing != mPlaying) {
                    mPlaying = playing;
                    update();
                }
            }
        }, mMain);
        mPlaying = isPlaying(audio.getActivePlaybackConfigurations());

        mKeyguard.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardShowingChanged() {
                update();
            }
        });

        readSettings();
        update();
    }

    private void readSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        int user = ActivityManager.getCurrentUser();
        mEnabled = Settings.System.getIntForUser(resolver, KEY_ENABLED, 0, user) == 1;
        mLockscreen = Settings.System.getIntForUser(resolver, KEY_LOCKSCREEN, 1, user) == 1;
        mStyle = clamp(Settings.System.getIntForUser(resolver, KEY_STYLE, STYLE_LINES, user),
                STYLE_LINES, STYLE_MIRROR);
        mColorMode = clamp(Settings.System.getIntForUser(resolver, KEY_COLOR_MODE,
                COLOR_CUSTOM, user), COLOR_CUSTOM, COLOR_GRADIENT);
        mColor = Settings.System.getIntForUser(resolver, KEY_COLOR, DEFAULT_COLOR, user)
                | 0xFF000000;
        mHeightDp = clamp(Settings.System.getIntForUser(resolver, KEY_HEIGHT,
                DEFAULT_HEIGHT_DP, user), MIN_HEIGHT_DP, MAX_HEIGHT_DP);
        mOpacity = clamp(Settings.System.getIntForUser(resolver, KEY_OPACITY,
                DEFAULT_OPACITY, user), 10, 100);
        int bars = clamp(Settings.System.getIntForUser(resolver, KEY_BARS, DEFAULT_BARS, user),
                MIN_BARS, MAX_BARS);
        synchronized (mLock) {
            if (mTargets.length != bars) {
                mTargets = new float[bars];
            }
        }
        if (mView != null) {
            mView.applySettings();
        }
    }

    private void update() {
        boolean show = mEnabled && mScreenOn && !mPowerSave && mPlaying
                && (mLockscreen || !mKeyguard.isShowing());
        if (show) {
            showWindow();
            mAudio.post(this::linkVisualizer);
        } else {
            mAudio.post(this::releaseVisualizer);
            synchronized (mLock) {
                Arrays.fill(mTargets, 0f);
            }
            hideWindow();
        }
    }

    private void showWindow() {
        int height = Math.round(mHeightDp * mWindowContext.getResources()
                .getDisplayMetrics().density);
        if (mView == null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, height,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM;
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTrustedOverlay();
            lp.setTitle("RRPulse");
            mView = new PulseView(mWindowContext);
            try {
                mWindowManager.addView(mView, lp);
            } catch (RuntimeException e) {
                Log.w(TAG, "Pulse window not added", e);
                mView = null;
            }
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mView.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            mWindowManager.updateViewLayout(mView, lp);
        }
        mView.poke();
    }

    private void hideWindow() {
        if (mView == null) {
            return;
        }
        try {
            mWindowManager.removeView(mView);
        } catch (RuntimeException e) {
            Log.w(TAG, "Pulse window already gone", e);
        }
        mView = null;
    }

    private void linkVisualizer() {
        if (mVisualizer != null) {
            return;
        }
        Visualizer created = null;
        try {
            created = new Visualizer(0);
            created.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            created.setCaptureSize(range[1]);
            int maxRate = Visualizer.getMaxCaptureRate();
            int rate = maxRate > 0 ? (int) (maxRate * 0.75f) : 10000;
            int status = created.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) {
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    onFft(fft);
                }
            }, rate, false /* waveform */, true /* fft */);
            if (status != Visualizer.SUCCESS) {
                throw new IllegalStateException("capture listener " + status);
            }
            created.setEnabled(true);
            mVisualizer = created;
        } catch (RuntimeException e) {
            Log.w(TAG, "Visualizer unavailable", e);
            if (created != null) {
                created.release();
            }
        }
    }

    private void releaseVisualizer() {
        Visualizer viz = mVisualizer;
        mVisualizer = null;
        if (viz == null) {
            return;
        }
        try {
            viz.setEnabled(false);
            viz.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "Visualizer release failed", e);
        }
    }

    private void onFft(byte[] fft) {
        int bars;
        synchronized (mLock) {
            bars = mTargets.length;
        }
        float[] next = new float[bars];
        decode(fft, next);
        synchronized (mLock) {
            if (next.length != mTargets.length) {
                return;
            }
            System.arraycopy(next, 0, mTargets, 0, next.length);
            mLastFft = SystemClock.uptimeMillis();
        }
        mMain.post(() -> {
            if (mView != null) {
                mView.poke();
            }
        });
    }

    private static boolean isPlaying(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) {
            return false;
        }
        for (AudioPlaybackConfiguration config : configs) {
            int usage = config.getAudioAttributes().getUsage();
            if (config.isActive() && (usage == AudioAttributes.USAGE_MEDIA
                    || usage == AudioAttributes.USAGE_GAME
                    || usage == AudioAttributes.USAGE_UNKNOWN)) {
                return true;
            }
        }
        return false;
    }

    /** Log-spaced bands over the lower two thirds of the FFT, tilted up for the treble. */
    private static void decode(byte[] fft, float[] out) {
        Arrays.fill(out, 0f);
        if (fft == null || fft.length < 8) {
            return;
        }
        int bins = fft.length / 2 - 1;
        int used = Math.min(bins, Math.max(out.length, bins * 2 / 3));
        int n = out.length;
        int start = 0;
        for (int i = 0; i < n; i++) {
            int end = (int) Math.round(Math.pow(used, (i + 1) / (double) n));
            end = Math.min(used, Math.max(end, start + 1));
            float sum = 0f;
            int count = 0;
            for (int bin = start; bin < end; bin++) {
                int idx = 2 + bin * 2;
                if (idx + 1 >= fft.length) {
                    break;
                }
                sum += (float) Math.hypot(fft[idx], fft[idx + 1]);
                count++;
            }
            start = end;
            if (count == 0) {
                continue;
            }
            float tilt = 1f + 1.5f * i / n;
            float norm = (float) Math.sqrt(Math.min(1d, (sum / count) * tilt / 140d));
            out[i] = norm < 0.08f ? 0f : norm;
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PulseView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFade = new Paint();
        private final RectF mRect = new RectF();
        private float[] mLevels = new float[0];
        private Bitmap mBitmap;
        private Canvas mBitmapCanvas;
        private LinearGradient mShader;
        private int mShaderWidth;
        private int mAccent;
        private int mDrawnStyle = -1;
        private int mTrail;
        private boolean mScheduled;
        private boolean mNavVisible = true;

        PulseView(Context context) {
            super(context);
            mFade.setColor(0xE6FFFFFF);
            mFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
            mAccent = context.getColor(android.R.color.system_accent1_300);
        }

        void applySettings() {
            if (mDrawnStyle != mStyle) {
                recycleBitmap();
                mTrail = 0;
            }
            poke();
        }

        void poke() {
            if (!mScheduled) {
                mScheduled = true;
                postInvalidateOnAnimation();
            }
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            // Fullscreen apps hide the nav bar; stay out of their way.
            boolean visible = insets.isVisible(WindowInsets.Type.navigationBars());
            if (visible != mNavVisible) {
                mNavVisible = visible;
                poke();
            }
            return super.onApplyWindowInsets(insets);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            mAccent = getContext().getColor(android.R.color.system_accent1_300);
            poke();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            recycleBitmap();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            mScheduled = false;
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float[] levels;
            synchronized (mLock) {
                if (mLastFft != 0 && SystemClock.uptimeMillis() - mLastFft > SILENCE_MS) {
                    Arrays.fill(mTargets, 0f);
                }
                if (mLevels.length != mTargets.length) {
                    mLevels = new float[mTargets.length];
                }
                for (int i = 0; i < mLevels.length; i++) {
                    mLevels[i] += (mTargets[i] - mLevels[i]) * 0.45f;
                    if (mLevels[i] < 0.012f && mTargets[i] < 0.012f) {
                        mLevels[i] = 0f;
                    }
                }
                levels = mLevels.clone();
            }
            int style = mStyle;
            mDrawnStyle = style;
            boolean moving = audible(levels);
            if (style == STYLE_FADING) {
                mTrail = moving ? TRAIL_FRAMES : Math.max(0, mTrail - 1);
            } else {
                mTrail = 0;
            }
            if (!mNavVisible || (!moving && mTrail == 0)) {
                recycleBitmap();
                return;
            }
            preparePaint(width);
            if (style == STYLE_FADING) {
                if (mBitmap == null || mBitmap.getWidth() != width
                        || mBitmap.getHeight() != height) {
                    recycleBitmap();
                    mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    mBitmapCanvas = new Canvas(mBitmap);
                }
                mBitmapCanvas.drawPaint(mFade);
                drawBars(mBitmapCanvas, levels, width, height, STYLE_LINES);
                canvas.drawBitmap(mBitmap, 0f, 0f, null);
            } else {
                drawBars(canvas, levels, width, height, style);
            }
            mScheduled = true;
            postInvalidateDelayed(FRAME_MS);
        }

        private void preparePaint(int width) {
            if (mColorMode == COLOR_GRADIENT) {
                if (mShader == null || mShaderWidth != width) {
                    mShader = new LinearGradient(0f, 0f, width, 0f, GRADIENT, null,
                            Shader.TileMode.CLAMP);
                    mShaderWidth = width;
                }
                mPaint.setShader(mShader);
                mPaint.setColor(Color.WHITE);
            } else {
                mPaint.setShader(null);
                mPaint.setColor(mColorMode == COLOR_ACCENT ? mAccent : mColor);
            }
            mPaint.setAlpha(Math.round(255 * mOpacity / 100f));
        }

        private void drawBars(Canvas canvas, float[] levels, int width, int height, int style) {
            int n = levels.length;
            float gap = Math.max(2f, width / (n * 6f));
            float thick = Math.max(2f, (width - gap * (n + 1)) / n);
            float radius = thick / 2f;
            float bottom = height - 2f;
            for (int i = 0; i < n; i++) {
                float amp = levels[i] * (height - 4f);
                if (amp < 1f) {
                    continue;
                }
                float left = gap + i * (thick + gap);
                if (style == STYLE_BLOCKS) {
                    float block = Math.max(3f, Math.min(thick, height / 8f));
                    float step = block + Math.max(2f, block * 0.35f);
                    int count = Math.max(1, Math.round(amp / step));
                    for (int k = 0; k < count; k++) {
                        float top = bottom - k * step - block;
                        mRect.set(left, top, left + thick, top + block);
                        canvas.drawRoundRect(mRect, block * 0.3f, block * 0.3f, mPaint);
                    }
                } else if (style == STYLE_MIRROR) {
                    float center = height / 2f;
                    mRect.set(left, center - amp / 2f, left + thick, center + amp / 2f);
                    canvas.drawRoundRect(mRect, radius, radius, mPaint);
                } else {
                    mRect.set(left, bottom - amp, left + thick, bottom);
                    canvas.drawRoundRect(mRect, radius, radius, mPaint);
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
    }
}
