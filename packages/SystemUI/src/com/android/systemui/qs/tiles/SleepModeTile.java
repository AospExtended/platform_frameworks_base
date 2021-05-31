/*
 * Copyright (C) 2021 Havoc-OS
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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.text.format.DateFormat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import java.time.format.DateTimeFormatter;
import java.time.LocalTime;

import javax.inject.Inject;

public class SleepModeTile extends QSTileImpl<QSTile.BooleanState> {

    private static final ComponentName SLEEP_MODE_SETTING_COMPONENT = new ComponentName(
            "com.android.settings", "com.android.settings.Settings$SleepModeActivity");

    private static final Intent SLEEP_MODE_SETTINGS =
            new Intent().setComponent(SLEEP_MODE_SETTING_COMPONENT);

    private final Icon mIcon = ResourceIcon.get(com.android.internal.R.drawable.ic_sleep);

    private final SecureSetting mSetting;

    private boolean mIsTurningOn = false;

    @Inject
    public SleepModeTile(QSHost host) {
        super(host);

        mSetting = new SecureSetting(mContext, mHandler, Settings.Secure.SLEEP_MODE_ENABLED) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mIsTurningOn) {
            return;
        }
        mIsTurningOn = true;
        setEnabled(!mState.value);
        refreshState();
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mIsTurningOn = false;
            }
        }, 1500);
    }

    private void setEnabled(boolean enabled) {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_ENABLED, enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean sleep = value != 0;
        final int mode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AUTO_MODE, 0, UserHandle.USER_CURRENT);
        final boolean sleepModeOn = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

        String timeValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AUTO_TIME, UserHandle.USER_CURRENT);
        if (timeValue == null || timeValue.equals("")) timeValue = "20:00,07:00";
        String[] time = timeValue.split(",", 0);
        String outputFormat = DateFormat.is24HourFormat(mContext) ? "HH:mm" : "h:mm a";
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern(outputFormat);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        LocalTime sinceValue = LocalTime.parse(time[0], formatter);
        LocalTime tillValue = LocalTime.parse(time[1], formatter);

        state.value = sleep;
        state.label = mContext.getString(R.string.quick_settings_sleep_mode_label);
        state.icon = mIcon;
        state.contentDescription = TextUtils.isEmpty(state.secondaryLabel)
                ? state.label
                : TextUtils.concat(state.label, ", ", state.secondaryLabel);
        switch (mode) {
            default:
            case 0:
                state.secondaryLabel = null;
                break;
            case 1:
                state.secondaryLabel = mContext.getResources().getString(sleepModeOn
                    ? R.string.quick_settings_night_secondary_label_until_sunrise
                    : R.string.quick_settings_night_secondary_label_on_at_sunset);
                break;
            case 2:
                if (sleepModeOn) {
                    state.secondaryLabel = mContext.getResources().getString(
                            R.string.quick_settings_secondary_label_until, tillValue.format(outputFormatter));
                } else {
                    state.secondaryLabel = mContext.getResources().getString(
                            R.string.quick_settings_night_secondary_label_on_at, sinceValue.format(outputFormatter));
                }
                break;
            case 3:
                if (sleepModeOn) {
                    state.secondaryLabel = mContext.getResources().getString(
                            R.string.quick_settings_secondary_label_until, tillValue.format(outputFormatter));
                } else {
                    state.secondaryLabel = mContext.getResources().getString(
                            R.string.quick_settings_night_secondary_label_on_at_sunset);
                }
                break;
            case 4:
                if (sleepModeOn) {
                    state.secondaryLabel = mContext.getResources().getString(
                            R.string.quick_settings_night_secondary_label_until_sunrise);
                } else {
                    state.secondaryLabel = mContext.getResources().getString(
                            R.string.quick_settings_night_secondary_label_on_at, sinceValue.format(outputFormatter));
                }
                break;
        }
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public Intent getLongClickIntent() {
        return SLEEP_MODE_SETTINGS;
    }

    @Override
    protected void handleSetListening(boolean listening) {
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SLEEP_MODE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SLEEP_MODE_AUTO_MODE), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }
    }
}
