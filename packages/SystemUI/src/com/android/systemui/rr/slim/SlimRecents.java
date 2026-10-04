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

package com.android.systemui.rr.slim;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Side list of recent tasks for the 3-button recents key. Gesture overview stays on the launcher.
 * Remixer writes the same Settings.System name, {@code slim_recents_enabled}.
 */
public final class SlimRecents {
    private static final String TAG = "RrSlimRecents";

    /** Settings.System int. 1 shows this panel instead of the launcher overview. */
    public static final String KEY = "slim_recents_enabled";

    private static final int MAX_TASKS = 20;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rr-slim");
        thread.setDaemon(true);
        return thread;
    });

    private static int sUser = UserHandle.USER_NULL;
    private static int sEnabled = -1;
    private static boolean sWatching;
    private static boolean sWatchFailed;
    private static Context sWatchContext;
    private static int sGeneration;
    private static Panel sPanel;
    private static WindowManager sWindowManager;

    private SlimRecents() {}

    public static boolean enabled(Context context) {
        if (context == null) {
            return false;
        }
        watch(context);
        int user = ActivityManager.getCurrentUser();
        if (!sWatching || sEnabled < 0 || user != sUser) {
            sUser = user;
            sEnabled = Settings.System.getIntForUser(
                    context.getContentResolver(), KEY, 0, user);
        }
        return sEnabled == 1;
    }

    /**
     * @return false when the panel could not be shown, so the caller can open the launcher
     */
    public static boolean toggle(Context context) {
        if (sPanel != null) {
            hide();
            return true;
        }
        return show(context);
    }

    /**
     * @return false when the panel could not be shown, so the caller can open the launcher
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
            if (sPanel == null) {
                WindowManager wm = context.getSystemService(WindowManager.class);
                if (wm == null) {
                    return false;
                }
                Panel panel = new Panel(context);
                wm.addView(panel, panelWindow());
                sWindowManager = wm;
                sPanel = panel;
                panel.requestFocus();
            }
            sPanel.reload();
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to show slim recents", e);
            hide();
            return false;
        }
    }

    public static void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(SlimRecents::hide);
            return;
        }
        sGeneration++;
        Panel panel = sPanel;
        sPanel = null;
        WindowManager wm = sWindowManager;
        sWindowManager = null;
        if (panel == null || wm == null) {
            return;
        }
        try {
            wm.removeView(panel);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Slim recents was already removed", e);
        }
    }

    private static void watch(Context context) {
        if (sWatching || sWatchFailed) {
            return;
        }
        Context app = context.getApplicationContext();
        sWatchContext = app != null ? app : context;
        ContentObserver observer = new ContentObserver(MAIN) {
            @Override
            public void onChange(boolean selfChange) {
                sEnabled = -1;
                if (sWatchContext != null && !enabled(sWatchContext) && sPanel != null) {
                    hide();
                }
            }
        };
        try {
            sWatchContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY), false, observer, UserHandle.USER_ALL);
            sWatching = true;
        } catch (RuntimeException e) {
            sWatchFailed = true;
            Log.w(TAG, "Not watching slim recents setting", e);
        }
    }

    private static WindowManager.LayoutParams panelWindow() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Default fit insets keep this window out of the status and nav bars, so the
        // recents key can still toggle the panel closed.
        lp.setTrustedOverlay();
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        lp.token = new Binder();
        lp.setTitle("RRSlimRecents");
        lp.windowAnimations = 0;
        return lp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Task {
        final int id;
        final String label;
        final Drawable icon;

        Task(int id, String label, Drawable icon) {
            this.id = id;
            this.label = label;
            this.icon = icon;
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
            component = info.topActivity;
        }
        if (component == null) {
            return null;
        }
        PackageManager pm = managerFor(context, managers, info.userId);
        String label = component.getPackageName();
        Drawable icon = pm.getDefaultActivityIcon();
        try {
            ActivityInfo activity = pm.getActivityInfo(component, 0);
            CharSequence loaded = activity.loadLabel(pm);
            if (loaded != null && loaded.length() > 0) {
                label = loaded.toString();
            }
            Drawable activityIcon = pm.getActivityIcon(component);
            if (activityIcon != null && activityIcon.getConstantState() != null) {
                icon = activityIcon.getConstantState().newDrawable().mutate();
            } else if (activityIcon != null) {
                icon = activityIcon;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Missing activity " + component, e);
        }
        return new Task(info.taskId, label, icon);
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

    private static void launch(Task task) {
        try {
            int result = ActivityTaskManager.getService().startActivityFromRecents(
                    task.id, ActivityOptions.makeBasic().toBundle());
            if (result >= 0) {
                hide();
            } else {
                Log.w(TAG, "startActivityFromRecents returned " + result);
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not start task " + task.id, e);
        }
    }

    private static void dismiss(Task task) {
        EXEC.execute(() -> {
            try {
                ActivityTaskManager.getService().removeTask(task.id);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Could not remove task " + task.id, e);
            }
            MAIN.post(() -> {
                if (sPanel != null) {
                    sPanel.reload();
                }
            });
        });
    }

    private static void clearAll(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        List<Task> copy = new ArrayList<>(tasks);
        EXEC.execute(() -> {
            for (Task task : copy) {
                try {
                    ActivityTaskManager.getService().removeTask(task.id);
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "Could not remove task " + task.id, e);
                }
            }
            MAIN.post(SlimRecents::hide);
        });
    }

    /**
     * Full-screen touch shield with the list on the end side. A partial window would pass
     * outside taps through to the app underneath.
     */
    private static final class Panel extends FrameLayout {
        private final LinearLayout mRows;
        private final TextView mEmpty;
        private List<Task> mTasks = Collections.emptyList();

        Panel(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setBackgroundColor(0x80000000);
            setOnClickListener(v -> SlimRecents.hide());

            int width = Math.min(dp(context, 320),
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.86f));
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xE6121212);
            card.setClickable(true);
            LayoutParams cardLp = new LayoutParams(width, LayoutParams.MATCH_PARENT, Gravity.END);
            addView(card, cardLp);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(context, 20), dp(context, 16), dp(context, 8), dp(context, 8));
            TextView title = new TextView(context);
            title.setText("Recents");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            header.addView(title, titleLp);
            TextView clear = new TextView(context);
            clear.setText("Clear all");
            clear.setTextColor(0xFFE02437);
            clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            clear.setOnClickListener(v -> SlimRecents.clearAll(mTasks));
            header.addView(clear);
            card.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            mRows = new LinearLayout(context);
            mRows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(mRows, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            mEmpty = new TextView(context);
            mEmpty.setText("No recent apps");
            mEmpty.setTextColor(0xB3FFFFFF);
            mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            mEmpty.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12));
            mRows.addView(mEmpty);
        }

        void reload() {
            final int generation = ++sGeneration;
            final Context context = getContext();
            EXEC.execute(() -> {
                List<Task> tasks = loadTasks(context);
                MAIN.post(() -> {
                    if (generation != sGeneration || sPanel != this) {
                        return;
                    }
                    showTasks(tasks);
                });
            });
        }

        private void showTasks(List<Task> tasks) {
            mTasks = tasks;
            mRows.removeAllViews();
            if (tasks.isEmpty()) {
                mRows.addView(mEmpty);
                return;
            }
            Context context = getContext();
            for (Task task : tasks) {
                mRows.addView(row(context, task));
            }
        }

        private View row(Context context, Task task) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 12), dp(context, 6), dp(context, 4), dp(context, 6));
            row.setMinimumHeight(dp(context, 56));
            row.setOnClickListener(v -> SlimRecents.launch(task));

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(task.icon);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int iconSize = dp(context, 40);
            row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

            TextView label = new TextView(context);
            label.setText(task.label);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(context, 12), 0, dp(context, 8), 0);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = new TextView(context);
            close.setText("\u00D7");
            close.setTextColor(0xFFFFFFFF);
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            close.setGravity(Gravity.CENTER);
            close.setMinimumWidth(dp(context, 48));
            close.setMinimumHeight(dp(context, 48));
            close.setContentDescription("Dismiss " + task.label);
            close.setOnClickListener(v -> SlimRecents.dismiss(task));
            row.addView(close);
            return row;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                SlimRecents.hide();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                SlimRecents.hide();
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
    }
}
