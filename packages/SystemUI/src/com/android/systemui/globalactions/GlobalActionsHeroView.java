/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.globalactions;

import android.content.Context;
import android.graphics.Typeface;
import android.icu.text.MeasureFormat;
import android.icu.text.NumberFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.FontStyles;
import com.android.systemui.globalactions.GlobalActionsDialogLite.Action;
import com.android.systemui.res.R;

import java.util.Locale;

/**
 * Accent card on top of the power menu. Holds Power off and Restart, and turns into the header
 * of the restart options.
 */
public class GlobalActionsHeroView extends LinearLayout {

    /** Routes the card's buttons through the dialog's own click handling. */
    public interface Listener {
        void onClick(Action action);

        boolean onLongClick(Action action);
    }

    private View mMark;
    private View mBack;
    private TextView mTitle;
    private TextView mSubtitle;
    private View mButtons;
    private View mGap;
    private View mPower;
    private View mRestart;
    private View mRestartMore;
    private boolean mHasActions;
    private CharSequence mStatus;

    public GlobalActionsHeroView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMark = findViewById(R.id.rr_power_mark);
        mBack = findViewById(R.id.rr_power_back);
        mTitle = findViewById(R.id.rr_power_title);
        mSubtitle = findViewById(R.id.rr_power_subtitle);
        mButtons = findViewById(R.id.rr_power_buttons);
        mGap = findViewById(R.id.rr_power_buttons_gap);
        mPower = findViewById(R.id.rr_power_off);
        mRestart = findViewById(R.id.rr_power_restart);
        mRestartMore = findViewById(R.id.rr_power_restart_more);

        mTitle.setTypeface(Typeface.create(FontStyles.GSF_TITLE_LARGE_EMPHASIZED,
                Typeface.NORMAL));
        Typeface label = Typeface.create(FontStyles.GSF_LABEL_LARGE_EMPHASIZED, Typeface.NORMAL);
        mSubtitle.setTypeface(Typeface.create(FontStyles.GSF_BODY_MEDIUM, Typeface.NORMAL));
        ((TextView) findViewById(R.id.rr_power_off_label)).setTypeface(label);
        ((TextView) findViewById(R.id.rr_power_restart_label)).setTypeface(label);
    }

    /** Binds the buttons; a null action hides its button. */
    public void bind(@Nullable Action power, @Nullable Action restart, boolean restartOpensMenu,
            Listener listener) {
        bindButton(mPower, R.id.rr_power_off_icon, power, listener);
        bindButton(mRestart, R.id.rr_power_restart_icon, restart, listener);
        mGap.setVisibility(power != null && restart != null ? VISIBLE : GONE);
        mRestartMore.setVisibility(restartOpensMenu ? VISIBLE : GONE);
        mHasActions = power != null || restart != null;
        mStatus = status();
    }

    public void setOnBackListener(OnClickListener listener) {
        mBack.setOnClickListener(listener);
    }

    /** Shows the restart options header instead of the power buttons. */
    public void setRestartMode(boolean restart) {
        mMark.setVisibility(restart ? GONE : VISIBLE);
        mBack.setVisibility(restart ? VISIBLE : GONE);
        mButtons.setVisibility(!restart && mHasActions ? VISIBLE : GONE);
        mTitle.setText(restart ? R.string.global_action_restart_title : R.string.rr_power_title);
        mSubtitle.setText(restart
                ? getContext().getString(R.string.global_action_restart_subtitle) : mStatus);
    }

    private void bindButton(View button, int iconId, @Nullable Action action, Listener listener) {
        if (action == null) {
            button.setVisibility(GONE);
            return;
        }
        button.setVisibility(VISIBLE);
        ((ImageView) button.findViewById(iconId)).setImageDrawable(action.getIcon(getContext()));
        button.setOnClickListener(v -> listener.onClick(action));
        button.setOnLongClickListener(v -> listener.onLongClick(action));
    }

    /** "Up 2d 4h · 84% battery". */
    private CharSequence status() {
        long minutes = SystemClock.elapsedRealtime() / 60_000L;
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        MeasureFormat format = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.NARROW);
        String uptime;
        if (days > 0) {
            uptime = format.formatMeasures(new Measure(days, MeasureUnit.DAY),
                    new Measure(hours, MeasureUnit.HOUR));
        } else if (hours > 0) {
            uptime = format.formatMeasures(new Measure(hours, MeasureUnit.HOUR),
                    new Measure(minutes % 60, MeasureUnit.MINUTE));
        } else {
            uptime = format.formatMeasures(new Measure(Math.max(1, minutes), MeasureUnit.MINUTE));
        }

        BatteryManager battery = getContext().getSystemService(BatteryManager.class);
        int level = battery != null
                ? battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        if (level <= 0) {
            return getContext().getString(R.string.rr_power_status_uptime, uptime);
        }
        return getContext().getString(R.string.rr_power_status, uptime,
                NumberFormat.getPercentInstance().format(level / 100d));
    }
}
