/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.rr;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Haptic Remix: vibration patterns the user tapped out in Remixer, assigned to calls, a contact's
 * number, an app's notifications, the alarm, plugging in or a fingerprint match. Patterns live in
 * {@link #KEY_PATTERNS} as a JSON array of {name, timings, amplitudes}; assignments in
 * {@link #KEY_ASSIGN} as a JSON object from target to pattern name.
 */
public final class RemixHaptics {
    public static final String KEY_PATTERNS = "rr_haptic_patterns";
    public static final String KEY_ASSIGN = "rr_haptic_assign";

    public static final String TARGET_CALL = "call";
    public static final String TARGET_ALARM = "alarm";
    public static final String TARGET_PLUG = "plug";
    public static final String TARGET_FINGERPRINT = "fingerprint";
    public static final String PREFIX_APP = "app:";
    public static final String PREFIX_NUMBER = "number:";

    /** Longer than any ringtone's loop; patterns past this are cut. */
    private static final long MAX_TOTAL_MS = 30_000;

    private static final Object sLock = new Object();
    private static String sPatternsJson;
    private static String sAssignJson;
    private static final Map<String, long[]> sTimings = new HashMap<>();
    private static final Map<String, int[]> sAmplitudes = new HashMap<>();
    private static final Map<String, String> sAssign = new HashMap<>();

    private RemixHaptics() {}

    /** The effect assigned to [target], looping if [repeat], or null when there is none. */
    public static VibrationEffect effect(Context context, String target, boolean repeat) {
        synchronized (sLock) {
            load(context);
            String name = sAssign.get(target);
            if (name == null) return null;
            long[] timings = sTimings.get(name);
            int[] amplitudes = sAmplitudes.get(name);
            if (timings == null || amplitudes == null) return null;
            return VibrationEffect.createWaveform(timings, amplitudes, repeat ? 0 : -1);
        }
    }

    /** The effect for a call from [handle]: the number's own, else the one for every call. */
    public static VibrationEffect forCall(Context context, Uri handle) {
        synchronized (sLock) {
            load(context);
            String number = handle != null && "tel".equals(handle.getScheme())
                    ? handle.getSchemeSpecificPart() : null;
            if (!TextUtils.isEmpty(number)) {
                for (Map.Entry<String, String> entry : sAssign.entrySet()) {
                    if (!entry.getKey().startsWith(PREFIX_NUMBER)) continue;
                    String stored = entry.getKey().substring(PREFIX_NUMBER.length());
                    if (PhoneNumberUtils.compare(context, stored, number)) {
                        VibrationEffect effect = effect(context, entry.getKey(), true);
                        if (effect != null) return effect;
                    }
                }
            }
            return effect(context, TARGET_CALL, true);
        }
    }

    private static void load(Context context) {
        int user = ActivityManager.getCurrentUser();
        String patterns = Settings.System.getStringForUser(context.getContentResolver(),
                KEY_PATTERNS, user);
        String assign = Settings.System.getStringForUser(context.getContentResolver(),
                KEY_ASSIGN, user);
        if (!TextUtils.equals(patterns, sPatternsJson)) {
            sPatternsJson = patterns;
            sTimings.clear();
            sAmplitudes.clear();
            try {
                JSONArray array = new JSONArray(patterns != null ? patterns : "[]");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject pattern = array.getJSONObject(i);
                    JSONArray t = pattern.getJSONArray("timings");
                    JSONArray a = pattern.getJSONArray("amplitudes");
                    int n = Math.min(t.length(), a.length());
                    if (n == 0) continue;
                    long[] timings = new long[n];
                    int[] amplitudes = new int[n];
                    long total = 0;
                    boolean any = false;
                    for (int j = 0; j < n; j++) {
                        timings[j] = Math.max(0, Math.min(10_000, t.getLong(j)));
                        amplitudes[j] = Math.max(0, Math.min(255, a.getInt(j)));
                        total += timings[j];
                        any |= amplitudes[j] > 0 && timings[j] > 0;
                    }
                    if (!any || total > MAX_TOTAL_MS) continue;
                    String name = pattern.getString("name");
                    sTimings.put(name, timings);
                    sAmplitudes.put(name, amplitudes);
                }
            } catch (Exception e) {
                sTimings.clear();
                sAmplitudes.clear();
            }
        }
        if (!TextUtils.equals(assign, sAssignJson)) {
            sAssignJson = assign;
            sAssign.clear();
            try {
                JSONObject object = new JSONObject(assign != null ? assign : "{}");
                for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) {
                    String key = keys.next();
                    String value = object.optString(key);
                    if (!value.isEmpty() && !"contacts".equals(key)) sAssign.put(key, value);
                }
            } catch (Exception e) {
                sAssign.clear();
            }
        }
    }
}
