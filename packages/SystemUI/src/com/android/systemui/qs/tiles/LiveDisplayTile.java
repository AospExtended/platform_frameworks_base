/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2018-2019 The LineageOS Project
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

import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_AUTO;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_DAY;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_OFF;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_OUTDOOR;

import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.provider.Settings;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.internal.app.ColorDisplayController;
import com.android.internal.util.ArrayUtils;
import com.android.systemui.plugins.qs.QSTile.LiveDisplayState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import com.android.internal.R;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.custom.hardware.LiveDisplayManager;

/** Quick settings tile: LiveDisplay mode switcher **/
public class LiveDisplayTile extends QSTileImpl<LiveDisplayState> {

    private static final Intent LIVEDISPLAY_SETTINGS =
            new Intent("com.android.settings.LIVEDISPLAY_SETTINGS");

    private final LiveDisplayObserver mObserver;
    private String mTitle;
    private String[] mEntries;
    private String[] mDescriptionEntries;
    private String[] mAnnouncementEntries;
    private String[] mValues;
    private final int[] mEntryIconRes;

    private boolean mListening;

    private int mDayTemperature;

    private final boolean mOutdoorModeAvailable;
    private final boolean mOutdoorOverlayCapable;
    private final LiveDisplayManager mLiveDisplay;

    private static final int OFF_TEMPERATURE = 6500;

    public LiveDisplayTile(QSHost host) {
        super(host);

        Resources res = mContext.getResources();
        TypedArray typedArray = res.obtainTypedArray(R.array.live_display_drawables);
        mEntryIconRes = new int[typedArray.length()];
        for (int i = 0; i < mEntryIconRes.length; i++) {
            mEntryIconRes[i] = typedArray.getResourceId(i, 0);
        }
        typedArray.recycle();

        updateEntries();

        mOutdoorOverlayCapable = res.getBoolean(R.bool.config_outdoorCapable);

        mLiveDisplay = LiveDisplayManager.getInstance(mContext);
        if (mLiveDisplay.getConfig() != null) {
            mOutdoorModeAvailable = mLiveDisplay.getConfig().hasFeature(MODE_OUTDOOR);
            mDayTemperature = mLiveDisplay.getDayColorTemperature();
        } else {
            mOutdoorModeAvailable = false;
            mDayTemperature = -1;
        }

        mObserver = new LiveDisplayObserver(mHandler);
        mObserver.startObserving();
    }

    private void updateEntries() {
        Resources res = mContext.getResources();
        mTitle = res.getString(R.string.live_display_title);
        mEntries = res.getStringArray(R.array.live_display_entries);
        mDescriptionEntries = res.getStringArray(R.array.live_display_description);
        mAnnouncementEntries = res.getStringArray(R.array.live_display_announcement);
        mValues = res.getStringArray(R.array.live_display_values);
    }

    @Override
    public boolean isAvailable() {
        return !ColorDisplayController.isAvailable(mContext);
    }

    @Override
    public LiveDisplayState newTileState() {
        return new LiveDisplayState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening)
            return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
        } else {
            mObserver.endObserving();
        }
    }

    @Override
    protected void handleClick() {
        changeToNextMode();
    }

    @Override
    protected void handleUpdateState(LiveDisplayState state, Object arg) {
        updateEntries();
        state.mode = arg == null ? getCurrentModeIndex() : (Integer) arg;
        state.label = mTitle;
        state.secondaryLabel = mEntries[state.mode];
        state.icon = ResourceIcon.get(mEntryIconRes[state.mode]);
        state.contentDescription = mDescriptionEntries[state.mode];
        state.state = mLiveDisplay.getMode() != MODE_OFF ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.live_display_title);
    }

    @Override
    public Intent getLongClickIntent() {
        return LIVEDISPLAY_SETTINGS;
    }

    @Override
    protected String composeChangeAnnouncement() {
        return mAnnouncementEntries[getCurrentModeIndex()];
    }

    private int getCurrentModeIndex() {
        String currentLiveDisplayMode = null;
        try {
            currentLiveDisplayMode = String.valueOf(mLiveDisplay.getMode());
        } catch (NullPointerException e) {
            currentLiveDisplayMode = String.valueOf(MODE_AUTO);
        } finally {
            return ArrayUtils.indexOf(mValues, currentLiveDisplayMode);
        }
    }

    private void changeToNextMode() {
        int next = getCurrentModeIndex() + 1;

        if (next >= mValues.length) {
            next = 0;
        }

        int nextMode = 0;

        while (true) {
            nextMode = Integer.valueOf(mValues[next]);
            // Skip outdoor mode if it's unsupported, and skip the day setting
            // if it's the same as the off setting
            if ((!mOutdoorModeAvailable && !mOutdoorOverlayCapable && nextMode == MODE_OUTDOOR) ||
                    (mDayTemperature == OFF_TEMPERATURE && nextMode == MODE_DAY)) {
                next++;
                if (next >= mValues.length) {
                    next = 0;
                }
            } else {
                break;
            }
        }

        Settings.System.putIntForUser(mContext.getContentResolver(),
            Settings.System.DISPLAY_TEMPERATURE_MODE, nextMode,
            UserHandle.USER_CURRENT);
    }

    private class LiveDisplayObserver extends ContentObserver {
        public LiveDisplayObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            mDayTemperature = mLiveDisplay.getDayColorTemperature();
            refreshState(getCurrentModeIndex());
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.DISPLAY_TEMPERATURE_MODE),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.DISPLAY_TEMPERATURE_DAY),
                    false, this, UserHandle.USER_ALL);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
