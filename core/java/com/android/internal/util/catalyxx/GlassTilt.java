/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Moves the light on Catalyxx glass's rim as the phone tilts, as if it hung above: turn the phone
 * and the shine stays toward the ceiling ({@link GlassSettings#TILT}). SurfaceFlinger draws all
 * glass in one light, which this sends it.
 *
 * <p>It wakes when glass with a curved rim shows in this process and listens to the gravity
 * sensor only while SurfaceFlinger says glass is on a screen that's on, so it costs nothing the
 * rest of the time. Only processes that may use SurfaceFlinger, such as SystemUI, can send the
 * light; anywhere else it stays asleep.
 */
public final class GlassTilt {

    private static final String TAG = "GlassTilt";

    // SurfaceFlinger's glass light transaction (frameworks/native SurfaceFlinger.cpp): a unit
    // vector toward the light in the screen's axes as two floats, (0, 0) for the usual light.
    // The reply says whether glass is on a screen that's on.
    private static final String SURFACE_FLINGER = "SurfaceFlinger";
    private static final String SURFACE_COMPOSER = "android.ui.ISurfaceComposer";
    private static final int GLASS_LIGHT_TRANSACTION = 1990;

    // The usual light, up and a little to the left (SurfaceFlinger's BlurFilter), as an angle in
    // the screen's axes (x right, y down)
    private static final double USUAL_ANGLE = Math.atan2(-0.894427, -0.447214);
    // How upright the phone is, as the share of gravity along the screen. Lying flat there's no
    // up on the screen, so below FLAT the light eases back to the usual one; above UPRIGHT it
    // follows the tilt fully.
    private static final double FLAT = 0.25;
    private static final double UPRIGHT = 0.6;
    // How quickly the light catches up with the phone, in seconds
    private static final double FOLLOW_TIME = 0.08;
    // The smallest turn of the light worth drawing again, in radians (about 0.3 degrees)
    private static final double MIN_TURN = 0.005;
    // While awake it sends the light at least this often (ms), even when it's still, so it hears
    // soon when the glass is gone...
    private static final long CHECK_INTERVAL = 500;
    // ...and it goes back to sleep once SurfaceFlinger has had no glass on screen this long (ms)
    private static final long GONE_DELAY = 1000;

    private static final int MSG_WAKE = 1;
    private static final int MSG_CHECK = 2;

    // Glass with a curved rim showing in this process. Glass whose window went away without
    // hiding it stays until it's garbage, which costs at most a short wake when the screen turns
    // on.
    private static final Set<Object> sShownGlass =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static GlassTilt sInstance;

    private final Context mContext;
    private final Handler mHandler;

    // All below on mHandler's thread
    private boolean mSetUp;
    private IBinder mFlinger;
    private SensorManager mSensorManager;
    private Sensor mGravity;
    private Display mDisplay;
    // Read on any thread to skip waking twice
    private volatile boolean mAwake;
    private long mWokeAt;
    // Since when SurfaceFlinger has said there's no glass on screen, or -1
    private long mGoneSince;
    // How far the light follows the phone, see GlassSettings.tiltAmount()
    private float mAmount;
    // The light's angle as it catches up with the phone, and the last one sent
    private boolean mHasAngle;
    private double mAngle;
    private long mLastEventNanos;
    private boolean mHasSent;
    private double mSentAngle;
    private long mSentAt;

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onGravity(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        return;
                    }
                    if (mDisplay.getState() != Display.STATE_ON) {
                        sleep();
                    } else if (!sShownGlass.isEmpty()) {
                        wake();
                    }
                }
            };

    /** {@code glass}, glass with a curved rim, started showing in this process. */
    public static void onGlassShown(Context context, Object glass) {
        sShownGlass.add(glass);
        get(context).requestWake();
    }

    /** {@code glass} stopped showing. */
    public static void onGlassHidden(Object glass) {
        sShownGlass.remove(glass);
    }

    private static synchronized GlassTilt get(Context context) {
        if (sInstance == null) {
            final Context app = context.getApplicationContext();
            sInstance = new GlassTilt(app != null ? app : context);
        }
        return sInstance;
    }

    private GlassTilt(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        mHandler = new Handler(thread.getLooper(), this::handleMessage);
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_WAKE:
                wake();
                return true;
            case MSG_CHECK:
                check();
                return true;
            default:
                return false;
        }
    }

    private void requestWake() {
        if (!mAwake && !mHandler.hasMessages(MSG_WAKE)) {
            mHandler.sendEmptyMessage(MSG_WAKE);
        }
    }

    /** Looks up what it needs the first time; false if this process can't move the light. */
    private boolean setUp() {
        if (mSetUp) {
            return mFlinger != null;
        }
        mSetUp = true;
        if (mContext.checkSelfPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        mSensorManager = mContext.getSystemService(SensorManager.class);
        mGravity = mSensorManager != null
                ? mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        mDisplay = displayManager != null
                ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (mGravity == null || mDisplay == null) {
            Log.w(TAG, "No gravity sensor or display, the glass keeps its usual light");
            return false;
        }
        mFlinger = ServiceManager.getService(SURFACE_FLINGER);
        if (mFlinger == null) {
            return false;
        }
        displayManager.registerDisplayListener(mDisplayListener, mHandler,
                DisplayManager.EVENT_TYPE_DISPLAY_CHANGED | DisplayManager.EVENT_TYPE_DISPLAY_STATE);
        return true;
    }

    private void wake() {
        if (mAwake || !setUp()) {
            return;
        }
        final float amount = tiltAmount();
        if (amount == 0f) {
            // The glass keeps the usual light, also after tilt shine was just turned off
            sendLight(0f, 0f);
            return;
        }
        if (mDisplay.getState() != Display.STATE_ON) {
            return;
        }
        mAwake = true;
        mAmount = amount;
        mWokeAt = SystemClock.uptimeMillis();
        mGoneSince = -1;
        mHasAngle = false;
        mSensorManager.registerListener(mSensorListener, mGravity,
                SensorManager.SENSOR_DELAY_GAME, mHandler);
        mHandler.sendEmptyMessageDelayed(MSG_CHECK, CHECK_INTERVAL);
    }

    private void sleep() {
        if (!mAwake) {
            return;
        }
        mAwake = false;
        mSensorManager.unregisterListener(mSensorListener);
        mHandler.removeMessages(MSG_CHECK);
    }

    private void check() {
        if (!mAwake) {
            return;
        }
        final float amount = tiltAmount();
        if (amount == 0f) {
            sleep();
            sendLight(0f, 0f);
            return;
        }
        if (mDisplay.getState() != Display.STATE_ON) {
            sleep();
            return;
        }
        mAmount = amount;
        if (SystemClock.uptimeMillis() - mSentAt >= CHECK_INTERVAL) {
            // A still phone gives no new light to send: ask whether the glass is still there
            send(mHasAngle ? mAngle : USUAL_ANGLE);
        }
        if (mAwake) {
            mHandler.sendEmptyMessageDelayed(MSG_CHECK, CHECK_INTERVAL);
        }
    }

    /**
     * How far the light follows the phone, see {@link GlassSettings#tiltAmount}, and 0 too while
     * animations are off (Remove animations).
     */
    private float tiltAmount() {
        final ContentResolver resolver = mContext.getContentResolver();
        if (Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                == 0f) {
            return 0f;
        }
        return GlassSettings.tiltAmount(resolver);
    }

    private void onGravity(SensorEvent event) {
        if (!mAwake) {
            return;
        }
        final double target = lightAngle(event.values[0], event.values[1], event.values[2]);
        if (Double.isNaN(target)) {
            return;
        }
        if (mHasAngle) {
            final double seconds =
                    Math.min(Math.max((event.timestamp - mLastEventNanos) * 1e-9, 0), 0.2);
            mAngle += (1 - Math.exp(-seconds / FOLLOW_TIME)) * wrap(target - mAngle);
        } else {
            mAngle = target;
            mHasAngle = true;
        }
        mLastEventNanos = event.timestamp;
        if (!mHasSent || Math.abs(wrap(mAngle - mSentAngle)) >= MIN_TURN) {
            send(mAngle);
        }
    }

    /**
     * Where the light should be, as an angle in the screen's axes, for the gravity sensor's
     * reading: it points up in the phone's axes (x to its right, y to its top, z out of the
     * screen). NaN when there's no reading.
     */
    private double lightAngle(float gx, float gy, float gz) {
        final double g = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (g < 1) {
            return Double.NaN;
        }
        // Up along the screen, in its axes for its rotation
        final double upX;
        final double upY;
        switch (mDisplay.getRotation()) {
            case Surface.ROTATION_90:
                upX = -gy;
                upY = -gx;
                break;
            case Surface.ROTATION_180:
                upX = -gx;
                upY = gy;
                break;
            case Surface.ROTATION_270:
                upX = gy;
                upY = gx;
                break;
            default:
                upX = gx;
                upY = -gy;
                break;
        }
        // How far the phone is turned from upright, where up is straight up the screen. The light
        // turns back by as much, so it stays where it was in the room.
        final double turn = wrap(Math.atan2(upY, upX) + Math.PI / 2);
        final double t = Math.min(Math.max((Math.hypot(upX, upY) / g - FLAT) / (UPRIGHT - FLAT),
                0), 1);
        final double upright = t * t * (3 - 2 * t);
        return USUAL_ANGLE + mAmount * upright * turn;
    }

    private void send(double angle) {
        final boolean glassOnScreen = sendLight((float) Math.cos(angle), (float) Math.sin(angle));
        mHasSent = true;
        mSentAngle = angle;
        mSentAt = SystemClock.uptimeMillis();
        if (glassOnScreen) {
            mGoneSince = -1;
            return;
        }
        if (mGoneSince < 0) {
            mGoneSince = mSentAt;
        }
        // Glass that just showed may not have reached SurfaceFlinger yet
        if (mSentAt - mGoneSince >= GONE_DELAY && mSentAt - mWokeAt >= GONE_DELAY) {
            sleep();
        }
    }

    /** Sends SurfaceFlinger the light; returns whether glass is on a screen that's on. */
    private boolean sendLight(float x, float y) {
        if (mFlinger == null) {
            return false;
        }
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SURFACE_COMPOSER);
            data.writeFloat(x);
            data.writeFloat(y);
            mFlinger.transact(GLASS_LIGHT_TRANSACTION, data, reply, 0);
            return reply.readBoolean();
        } catch (RemoteException | RuntimeException e) {
            // Such as a SurfaceFlinger without the transaction, which denies it
            Log.w(TAG, "SurfaceFlinger took no glass light, the glass keeps its usual light", e);
            sleep();
            mFlinger = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** {@code angle} turned into -pi to pi. */
    private static double wrap(double angle) {
        return angle - 2 * Math.PI * Math.floor((angle + Math.PI) / (2 * Math.PI));
    }
}
