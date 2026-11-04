/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.rr.looks.Looks;

import java.util.List;

import javax.inject.Inject;

/** Quick settings tile: tap for the next saved Look, hold for Remixer's Looks page. */
public class LookTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "rrlook";

    private Icon mIcon = null;
    private final ContentObserver mObserver;
    private boolean mListening;

    @Inject
    public LookTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Looks.KEY_CURRENT), false, mObserver,
                    UserHandle.USER_ALL);
            refreshState();
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        List<String> names = Looks.INSTANCE.names(mContext);
        if (names.isEmpty()) {
            handleLongClick(expandable);
            return;
        }
        int at = names.indexOf(Looks.INSTANCE.current(mContext));
        Looks.INSTANCE.apply(mContext, names.get((at + 1) % names.size()));
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName("com.android.settings",
                "com.resurrection.settings.LooksActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_look_label);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_look);
        }
        String current = Looks.INSTANCE.current(mContext);
        state.icon = mIcon;
        state.label = getTileLabel();
        state.secondaryLabel = current != null ? current
                : mContext.getString(R.string.qs_look_none);
        state.contentDescription = state.label + ", " + state.secondaryLabel;
        state.state = current != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_PANEL;
    }
}
