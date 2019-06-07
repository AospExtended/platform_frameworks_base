/*
 * Copyright (C) 2019 FireHound
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

import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.res.Resources;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.provider.Settings;
import android.provider.Settings.System;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.systemui.R;
import com.android.systemui.SysUIToast;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SystemSetting;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.NotificationChannels;

import java.util.ArrayList;
import java.util.List;

/** Quick settings tile: Gaming Mode tile **/
public class GamingModeTile extends QSTileImpl<BooleanState> {

    private static final int GAMING_NOTIFICATION_ID = 420; // sesh with me

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_gaming_mode);
    private final SystemSetting mSetting;
    private final AudioManager mAudioManager;
    private final NotificationManager mNotificationManager;

    private ArrayList<String> mGameApp = new ArrayList<String>();

    public GamingModeTile(QSHost host) {
        super(host);

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mSetting = new SystemSetting(mContext, mHandler, System.ENABLE_GAMING_MODE) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        boolean gamingOn = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.ENABLE_GAMING_MODE, 1) == 1;
        if (!gamingOn) {
            SystemUIDialog dialog = new SystemUIDialog(mContext);
            dialog.setTitle(R.string.gaming_mode_dialog_title);
            dialog.setIcon(R.drawable.ic_fh_gaming);
            dialog.setMessage(R.string.gaming_mode_dialog_message);
            dialog.setPositiveButton(com.android.internal.R.string.ok,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            enableStaticGamingMode();
                            addNotification();
                        }
                    });
            dialog.setShowForAllUsers(true);
            dialog.show();
        } else {
            enableStaticGamingMode();
            mNotificationManager.cancel(GAMING_NOTIFICATION_ID);
        }
    }

    public void enableStaticGamingMode() {
        handleState(!mState.value);
        refreshState();
    }

    private void addNotification() {
        final Resources r = mContext.getResources();
        // Display a notification
        Notification.Builder builder = new Notification.Builder(mContext, NotificationChannels.GAMING)
            .setTicker(r.getString(R.string.gaming_notif_ticker))
            .setContentTitle(r.getString(R.string.gaming_notif_title))
            .setSmallIcon(R.drawable.ic_gaming_notif)
            .setWhen(java.lang.System.currentTimeMillis()) // no shit
            .setOngoing(true);

        Notification notif = builder.build();
        mNotificationManager.notify(GAMING_NOTIFICATION_ID, notif);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$GamingSettingsActivity"));
    }

    private void handleState(boolean enabled) {
        // Lock brightness
        boolean enableManualBrightness = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_MANUAL_BRIGHTNESS_TOGGLE, 1) == 1;
        if (enabled && enableManualBrightness) {
            final boolean isAdaptiveEnabledByUser = Settings.System.getInt(mContext.getContentResolver(),
                              Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.GAMING_SCREEN_BRIGHTNESS_MODE, isAdaptiveEnabledByUser ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, SCREEN_BRIGHTNESS_MODE_MANUAL);
        } else if (!enabled) {
            final boolean wasAdaptiveEnabledByUser = Settings.System.getInt(mContext.getContentResolver(),
                              Settings.System.GAMING_SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, wasAdaptiveEnabledByUser ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }
        // Heads up
        boolean enableHeadsUp = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_HEADSUP_TOGGLE, 0) == 1;
        if (enabled && enableHeadsUp) {
            final boolean isHeadsUpEnabledByUser = Settings.Global.getInt(mContext.getContentResolver(),
                              Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1;
                Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.GAMING_HEADS_UP_NOTIFICATIONS_ENABLED, isHeadsUpEnabledByUser ? 1 : 0);
                Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 0);
        } else if (!enabled) {
            final boolean wasHeadsUpEnabledByUser = Settings.Global.getInt(mContext.getContentResolver(),
                              Settings.Global.GAMING_HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1;
                Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, wasHeadsUpEnabledByUser ? 1 : 0);
        }
        // Capacitive keys
        boolean disableHwKeys = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_HW_KEYS_TOGGLE, 1) == 1;
        if (enabled && disableHwKeys) {
            final boolean isHwKeysEnabledByUser = Settings.Secure.getInt(mContext.getContentResolver(),
                              Settings.Secure.HARDWARE_KEYS_DISABLE, 0) == 0;
                Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.GAMING_HARDWARE_KEYS_DISABLE, isHwKeysEnabledByUser ? 0 : 1);
                Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.HARDWARE_KEYS_DISABLE, 1);
        } else if (!enabled) {
            final boolean wasHwKeyEnabledByUser = Settings.Secure.getInt(mContext.getContentResolver(),
                              Settings.Secure.GAMING_HARDWARE_KEYS_DISABLE, 0) == 0;
                Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.HARDWARE_KEYS_DISABLE, wasHwKeyEnabledByUser ? 0 : 1);
        }

        // Ringer mode (0: OFF, 1: Vibrate, 2:DND: 3:Silent
        if (enabled) {
            int ringerMode = Settings.System.getInt(mContext.getContentResolver(),
                 Settings.System.GAMING_MODE_RINGER_MODE, 0);
            int userState = mAudioManager.getRingerModeInternal();
                 Settings.System.putInt(mContext.getContentResolver(),
                     Settings.System.GAMING_RINGER_STATE, userState);
            int userZenState = mNotificationManager.getZenMode();
                 Settings.System.getInt(mContext.getContentResolver(),
                     Settings.System.GAMING_MODE_ZEN_STATE, userZenState);
            if (ringerMode != 0 || ringerMode != userState) {
                if (ringerMode == 1) {
                    mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                    mNotificationManager.setZenMode(ZEN_MODE_OFF, null, TAG);
                }
            else if (ringerMode == 2) {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                mNotificationManager.setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, TAG);
                }
            else if (ringerMode == 3) {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
                mNotificationManager.setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, TAG);
                }
            }
        } else if (!enabled) {
            final int ringerState = Settings.System.getInt(mContext.getContentResolver(),
                  Settings.System.GAMING_RINGER_STATE, AudioManager.RINGER_MODE_NORMAL);
            final int zenState = Settings.System.getInt(mContext.getContentResolver(),
                  Settings.System.GAMING_MODE_ZEN_STATE, ZEN_MODE_OFF);
            if (ringerState != mAudioManager.getRingerModeInternal())
                  mAudioManager.setRingerModeInternal(ringerState);
            if (zenState != mNotificationManager.getZenMode())
                  mNotificationManager.setZenMode(zenState, null, TAG);
        }
        // Media volume increased to the fullest
        boolean maximizeMediaVolume = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_MAXIMIZE_MEDIA_TOGGLE, 1) == 1;
        if (enabled && !mAudioManager.isWiredHeadsetOn() && maximizeMediaVolume) {
            final int userMedia = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MEDIA_VOLUME, userMedia);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 60, 0); // 60 in the case "Music Volume Steps" have been set to 60 by user.
        } else if (!enabled) {
            final int userMediaVolume = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MEDIA_VOLUME, 1);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, userMediaVolume, 0);
        }
        // Show a toast
        if (enabled) {
            SysUIToast.makeText(mContext, mContext.getString(
                R.string.gaming_mode_tile_toast),
                Toast.LENGTH_SHORT).show();
        } else if (!enabled) {
            SysUIToast.makeText(mContext, mContext.getString(
                R.string.gaming_mode_tile_toast_disabled),
                Toast.LENGTH_SHORT).show();
        }

        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.ENABLE_GAMING_MODE,
                enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean enable = value != 0;
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.value = enable;
        state.slash.isSlashed = !state.value;
        state.label = mContext.getString(R.string.gaming_mode_tile_title);
        if (enable) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_gaming_mode_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_gaming_mode_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.gaming_mode_tile_title);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_gaming_mode_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_gaming_mode_off);
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
        // no-op
    }
}
