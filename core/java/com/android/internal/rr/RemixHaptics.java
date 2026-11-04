/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.rr;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private static final List<Contact> sContacts = new ArrayList<>();

    /** A contact row from Remixer: the number its pattern hangs on, its name, an edge color. */
    private static final class Contact {
        final String name;
        final String number;
        final int color;

        Contact(String name, String number, int color) {
            this.name = name;
            this.number = number;
            this.color = color;
        }
    }

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

    /**
     * The effect for a notification from [pkg]: the sender's own when it is a message from a
     * contact with a pattern, else the app's.
     */
    public static VibrationEffect forNotification(Context context, String pkg, Notification n,
            boolean repeat) {
        synchronized (sLock) {
            load(context);
            String[] who = sender(n);
            String key = contactKey(context, who[0], who[1]);
            if (key != null) {
                VibrationEffect effect = effect(context, key, repeat);
                if (effect != null) return effect;
            }
            return effect(context, PREFIX_APP + pkg, repeat);
        }
    }

    /** The edge light color the user gave this message's sender, or 0 for none. */
    public static int senderColor(Context context, Notification n) {
        synchronized (sLock) {
            load(context);
            String[] who = sender(n);
            Contact contact = contactFor(context, who[0], who[1]);
            return contact != null ? contact.color : 0;
        }
    }

    /** The sender of a message notification as {number, name}; either may be null. */
    private static String[] sender(Notification n) {
        String[] who = new String[2];
        Bundle extras = n != null ? n.extras : null;
        if (extras == null) return who;
        Person person = null;
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            List<Notification.MessagingStyle.Message> list =
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
            for (int i = list.size() - 1; i >= 0 && person == null; i--) {
                person = list.get(i).getSenderPerson();
            }
        }
        if (person == null) {
            ArrayList<Person> people = extras.getParcelableArrayList(
                    Notification.EXTRA_PEOPLE_LIST, Person.class);
            if (people != null && !people.isEmpty()) person = people.get(0);
        }
        if (person != null) {
            String uri = person.getUri();
            if (uri != null && uri.startsWith("tel:")) {
                who[0] = Uri.parse(uri).getSchemeSpecificPart();
            }
            if (person.getName() != null) who[1] = person.getName().toString();
        }
        if (who[1] == null && Notification.CATEGORY_MESSAGE.equals(n.category)) {
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null) who[1] = title.toString();
        }
        return who;
    }

    private static Contact contactFor(Context context, String number, String name) {
        for (Contact contact : sContacts) {
            if (!TextUtils.isEmpty(number)
                    && PhoneNumberUtils.compare(context, contact.number, number)) {
                return contact;
            }
        }
        for (Contact contact : sContacts) {
            if (!TextUtils.isEmpty(name) && name.equalsIgnoreCase(contact.name)) return contact;
        }
        return null;
    }

    /** The "number:" key for a sender, by number first, then by the contact's name. */
    private static String contactKey(Context context, String number, String name) {
        if (!TextUtils.isEmpty(number)) {
            for (String key : sAssign.keySet()) {
                if (key.startsWith(PREFIX_NUMBER) && PhoneNumberUtils.compare(context,
                        key.substring(PREFIX_NUMBER.length()), number)) {
                    return key;
                }
            }
        }
        Contact contact = contactFor(context, number, name);
        return contact != null ? PREFIX_NUMBER + contact.number : null;
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
            sContacts.clear();
            try {
                JSONObject object = new JSONObject(assign != null ? assign : "{}");
                for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) {
                    String key = keys.next();
                    String value = object.optString(key);
                    if (!value.isEmpty() && !"contacts".equals(key)) sAssign.put(key, value);
                }
                JSONArray contacts = object.optJSONArray("contacts");
                for (int i = 0; contacts != null && i < contacts.length(); i++) {
                    JSONObject contact = contacts.getJSONObject(i);
                    String number = contact.optString("number");
                    if (number.isEmpty()) continue;
                    String color = contact.optString("color");
                    int argb = 0;
                    if (color.startsWith("#")) {
                        try {
                            argb = Color.parseColor(color) | 0xFF000000;
                        } catch (IllegalArgumentException e) {
                            // Not a color; the accent then.
                        }
                    }
                    sContacts.add(new Contact(contact.optString("name"), number, argb));
                }
            } catch (Exception e) {
                sAssign.clear();
                sContacts.clear();
            }
        }
    }
}
