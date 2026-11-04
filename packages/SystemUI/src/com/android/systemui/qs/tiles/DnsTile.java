/*
 * Copyright (C) 2024 Yet Another AOSP Project
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

package com.android.systemui.qs.tiles;

import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;

import android.content.Intent;
import android.net.ConnectivitySettingsManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.res.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SettingObserver;

import javax.inject.Inject;

/** Quick settings tile: DNS Tile **/
public class DnsTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "dns";

    @Nullable
    private Icon mIcon = null;

    private final SettingObserver mSetting;
    private boolean mListening;

    @Inject
    public DnsTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            GlobalSettings globalSettings
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mSetting = new SettingObserver(globalSettings, mHandler, Settings.Global.PRIVATE_DNS_MODE) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            refreshState();
        }
        mSetting.setListening(listening);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_PRIVATE_DNS_SETTING);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        // Off, automatic, then the provider picked in Settings when there is one.
        final int mode = ConnectivitySettingsManager.getPrivateDnsMode(mContext);
        final int next;
        if (mode == PRIVATE_DNS_MODE_OFF) {
            next = PRIVATE_DNS_MODE_OPPORTUNISTIC;
        } else if (mode == PRIVATE_DNS_MODE_OPPORTUNISTIC && !TextUtils.isEmpty(
                ConnectivitySettingsManager.getPrivateDnsHostname(mContext))) {
            next = PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
        } else {
            next = PRIVATE_DNS_MODE_OFF;
        }
        ConnectivitySettingsManager.setPrivateDnsMode(mContext, next);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(
                com.android.settingslib.R.string.select_private_dns_configuration_title);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(
                com.android.settingslib.R.string.select_private_dns_configuration_title);
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(
                    R.drawable.ic_settings_dns);
        }
        state.icon = mIcon;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;

        final int mode = ConnectivitySettingsManager.getPrivateDnsMode(mContext);
        final boolean isTileActive = mode != PRIVATE_DNS_MODE_OFF;
        state.value = isTileActive;
        state.state = isTileActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.secondaryLabel = getSecondaryLabel(mode);
        state.stateDescription = state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_PANEL;
    }

    private String getSecondaryLabel(int mode) {
        if (mode == PRIVATE_DNS_MODE_OFF) {
            return mContext.getString(
                    com.android.settingslib.R.string.private_dns_mode_off);
        } else if (mode == PRIVATE_DNS_MODE_OPPORTUNISTIC) {
            return mContext.getString(
                    com.android.settingslib.R.string.private_dns_mode_opportunistic);
        }
        // PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
        return ConnectivitySettingsManager.getPrivateDnsHostname(mContext);
    }
}
