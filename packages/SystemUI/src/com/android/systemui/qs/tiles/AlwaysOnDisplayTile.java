/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2018 Android Ice Cold Project
 * Copyright (C) 2018 crDroid Android Project
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.quicksettings.Tile;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.qs.tileimpl.QSTileImpl;

public class AlwaysOnDisplayTile extends QSTileImpl<BooleanState> {

    private final SecureSetting mSetting;

    public AlwaysOnDisplayTile(QSHost host) {
        super(host);

        mSetting = new SecureSetting(mContext, mHandler, Secure.DOZE_ALWAYS_ON) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        mSetting.setValue(mState.value ? 0 : 1);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.custom.ambient.display", "com.custom.ambient.display.DozeService"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_always_on_display_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean enable = value != 0;
        state.value = enable;
        state.label = mContext.getString(R.string.quick_settings_always_on_display_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_alwaysondisplay);
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
