/*
 * Copyright (C) 2017 The Nitrogen Project
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
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.R.drawable;

/** Quick settings tile: HWKeys Actions **/
public class HWKeysTile extends QSTileImpl<BooleanState> {

    // Masks for checking presence of hardware keys.
    // Must match values in frameworks/base/core/res/res/values/config.xml
    public static final int KEY_MASK_HOME = 0x01;
    public static final int KEY_MASK_BACK = 0x02;
    public static final int KEY_MASK_MENU = 0x04;
    public static final int KEY_MASK_APP_SWITCH = 0x10;

    private final Icon mIcon = ResourceIcon.get(drawable.ic_qs_hwkeys);

    public HWKeysTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_hwkeys_label);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$ButtonSettingsActivity"));
    }

    private void setEnabled(boolean enabled) {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.HARDWARE_KEYS_DISABLE,
                enabled ? 0 : 1);
    }

   private boolean isHWKeysEnabled() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.HARDWARE_KEYS_DISABLE, 0) == 0;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.value = isHWKeysEnabled();
        state.icon = mIcon;
        state.slash.isSlashed = !state.value;
        if (isHWKeysEnabled()) {
            state.label =  mContext.getString(
                    R.string.accessibility_quick_settings_hwkeys_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.label =  mContext.getString(
                    R.string.accessibility_quick_settings_hwkeys_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public boolean isAvailable() {
        final int deviceKeys = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_deviceHardwareKeys);

        // read bits for present hardware keys
        final boolean hasHomeKey = (deviceKeys & KEY_MASK_HOME) != 0;
        final boolean hasBackKey = (deviceKeys & KEY_MASK_BACK) != 0;
        final boolean hasMenuKey = (deviceKeys & KEY_MASK_MENU) != 0;
        final boolean hasAppSwitchKey = (deviceKeys & KEY_MASK_APP_SWITCH) != 0;

        return (hasHomeKey || hasBackKey || hasMenuKey || hasAppSwitchKey);
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_hwkeys_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_hwkeys_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
