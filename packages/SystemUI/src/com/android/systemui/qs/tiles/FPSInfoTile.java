/*
 * Copyright (C) 2019 The OmniROM Project
 * Copyright (C) 2020 crDroid Android Project
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

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;

import javax.inject.Inject;

public class FPSInfoTile extends QSTileImpl<BooleanState> {
    private boolean mListening;
    private FPSInfoObserver mObserver;

    @Inject
    public FPSInfoTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mObserver = new FPSInfoObserver(mHandler);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        toggleState();
        refreshState();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public void handleLongClick(@Nullable View view) {
    }

    protected void toggleState() {
        Intent service = (new Intent())
                .setClassName("com.android.systemui",
                "com.android.systemui.FPSInfoService");
        if (FPSInfoEnabled()) {
            Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.SHOW_FPS_OVERLAY, 0);
            mContext.stopService(service);
        } else {
            Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.SHOW_FPS_OVERLAY, 1);
            mContext.startService(service);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_fpsinfo_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_fpsinfo_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_fps_info);
	    if (FPSInfoEnabled()) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_fpsinfo_on);
            state.state = Tile.STATE_ACTIVE;
	    } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_fpsinfo_off);
            state.state = Tile.STATE_INACTIVE;
	    }
    }

    private boolean FPSInfoEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.SHOW_FPS_OVERLAY, 0) == 1;
    }

    @Override
    public boolean isAvailable() {
        final String fpsInfoSysNode = mContext.getResources().getString(
                R.string.config_fpsInfoSysNode);
        boolean fpsInfoSupported = !TextUtils.isEmpty(fpsInfoSysNode);
        return fpsInfoSupported;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
    }

    private class FPSInfoObserver extends ContentObserver {
        public FPSInfoObserver(Handler handler) {
            super(handler);
        }
    }
}
