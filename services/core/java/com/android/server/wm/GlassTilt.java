/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;

import com.android.internal.os.BackgroundThread;

/**
 * Liquid Glass: moves the glass highlights with the phone's tilt. While active it reads the
 * accelerometer and sends SurfaceFlinger which way is up on screen, so the glass is lit from
 * above.
 */
final class GlassTilt implements SensorEventListener {
    private static final String TAG = "GlassTilt";
    // SurfaceFlinger backdoor code for the light direction (frameworks/native).
    private static final int SF_GLASS_LIGHT = 1100;
    private static final float DEFAULT_X = -0.5f;
    private static final float DEFAULT_Y = -0.866f;
    // Tilt in g below which the phone counts as flat and the default light takes over.
    private static final float FLAT = 0.12f;
    private static final float TILTED = 0.45f;
    private static final float SMOOTHING = 0.2f;
    // About a degree, so a phone at rest sends nothing.
    private static final float MIN_STEP = 0.02f;

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private SensorManager mSensorManager;
    private Display mDisplay;
    private IBinder mSurfaceFlinger;
    private boolean mListening;
    private float mX = DEFAULT_X;
    private float mY = DEFAULT_Y;
    private float mSentX = DEFAULT_X;
    private float mSentY = DEFAULT_Y;

    GlassTilt(Context context) {
        mContext = context;
    }

    /** Follows the tilt while {@code active}; otherwise puts the light back to its default. */
    void setActive(boolean active) {
        mHandler.post(() -> {
            if (active == mListening) {
                return;
            }
            if (active) {
                start();
            } else {
                stop();
            }
        });
    }

    private void start() {
        if (mSensorManager == null) {
            mSensorManager = mContext.getSystemService(SensorManager.class);
            mDisplay = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
        }
        final Sensor sensor = mSensorManager == null ? null
                : mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mListening = sensor != null && mSensorManager.registerListener(this, sensor,
                SensorManager.SENSOR_DELAY_UI, mHandler);
    }

    private void stop() {
        mSensorManager.unregisterListener(this);
        mListening = false;
        mX = DEFAULT_X;
        mY = DEFAULT_Y;
        send(0f, 0f);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Up in device axes (x right, y toward the top), in g.
        final float ux = event.values[0] / SensorManager.GRAVITY_EARTH;
        final float uy = event.values[1] / SensorManager.GRAVITY_EARTH;
        // The same vector in the axes of the current screen rotation.
        float dx = ux;
        float dy = uy;
        switch (mDisplay != null ? mDisplay.getRotation() : Surface.ROTATION_0) {
            case Surface.ROTATION_90:
                dx = -uy;
                dy = ux;
                break;
            case Surface.ROTATION_180:
                dx = -ux;
                dy = -uy;
                break;
            case Surface.ROTATION_270:
                dx = uy;
                dy = -ux;
                break;
            default:
                break;
        }
        // Screen space points y down.
        final float sx = dx;
        final float sy = -dy;
        final float tilt = (float) Math.hypot(sx, sy);
        float targetX = DEFAULT_X;
        float targetY = DEFAULT_Y;
        if (tilt > 0.001f) {
            final float weight = Math.max(0f, Math.min(1f, (tilt - FLAT) / (TILTED - FLAT)));
            targetX += (sx / tilt - DEFAULT_X) * weight;
            targetY += (sy / tilt - DEFAULT_Y) * weight;
        }
        mX += (targetX - mX) * SMOOTHING;
        mY += (targetY - mY) * SMOOTHING;
        final float length = (float) Math.hypot(mX, mY);
        if (length < 0.001f) {
            return;
        }
        final float x = mX / length;
        final float y = mY / length;
        if (Math.abs(x - mSentX) + Math.abs(y - mSentY) > MIN_STEP) {
            send(x, y);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void send(float x, float y) {
        if (mSurfaceFlinger == null) {
            mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
            if (mSurfaceFlinger == null) {
                return;
            }
        }
        final Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.ui.ISurfaceComposer");
            data.writeFloat(x);
            data.writeFloat(y);
            mSurfaceFlinger.transact(SF_GLASS_LIGHT, data, null, IBinder.FLAG_ONEWAY);
            final boolean reset = x == 0f && y == 0f;
            mSentX = reset ? DEFAULT_X : x;
            mSentY = reset ? DEFAULT_Y : y;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't send the glass light", e);
            mSurfaceFlinger = null;
        } finally {
            data.recycle();
        }
    }
}
