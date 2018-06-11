/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;

import android.os.Handler;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Represents the dock regions for each orientation.
 */
class DockRegion {
    public static TaskStack.DockState[] PHONE_LANDSCAPE = {
            // We only allow docking to the left in landscape for now on small devices
            TaskStack.DockState.LEFT
    };
    public static TaskStack.DockState[] PHONE_PORTRAIT = {
            // We only allow docking to the top for now on small devices
            TaskStack.DockState.TOP
    };
    public static TaskStack.DockState[] TABLET_LANDSCAPE = {
            TaskStack.DockState.LEFT,
            TaskStack.DockState.RIGHT
    };
    public static TaskStack.DockState[] TABLET_PORTRAIT = PHONE_PORTRAIT;
}

/**
 * Application resources that can be retrieved from the application context and are not specifically
 * tied to the current activity.
 */
public class RecentsConfiguration implements NextAlarmChangeCallback {

    private static final int LARGE_SCREEN_MIN_DP = 600;
    private static final int XLARGE_SCREEN_MIN_DP = 720;

    private NextAlarmController mNextAlarmController;
    private String mAlarm = "";

    private int mMediaColor = -1;
    private boolean mMediaPlaying;
    private String mMediaPackageName = "";
    private MediaMetadata mMediaMetaData;
    private String mMediaText = null;
    private Drawable mArtWork;

    private ArrayList<MediaAndClockCallbacks> mCallbacks = new ArrayList<>();

    /** Levels of svelte in increasing severity/austerity. */
    // No svelting.
    public static final int SVELTE_NONE = 0;
    // Limit thumbnail cache to number of visible thumbnails when Recents was loaded, disable
    // caching thumbnails as you scroll.
    public static final int SVELTE_LIMIT_CACHE = 1;
    // Disable the thumbnail cache, load thumbnails asynchronously when the activity loads and
    // evict all thumbnails when hidden.
    public static final int SVELTE_DISABLE_CACHE = 2;
    // Disable all thumbnail loading.
    public static final int SVELTE_DISABLE_LOADING = 3;

    // Launch states
    public RecentsActivityLaunchState mLaunchState = new RecentsActivityLaunchState();

    // Since the positions in Recents has to be calculated globally (before the RecentsActivity
    // starts), we need to calculate some resource values ourselves, instead of relying on framework
    // resources.
    public final boolean isLargeScreen;
    public final boolean isXLargeScreen;
    public final int smallestWidth;

    /** Misc **/
    public boolean fakeShadows;
    public int svelteLevel;

    // Whether this product supports Grid-based Recents. If this is field is set to true, then
    // Recents will layout task views in a grid mode when there's enough space in the screen.
    public boolean isGridEnabledDefault;
    public boolean mIsGridEnabled;

    // Support for Android Recents for low ram devices. If this field is set to true, then Recents
    // will use the alternative layout.
    public boolean isLowRamDevice;
    public boolean isLowRamDeviceDefault;
    public boolean mIsGoLayoutEnabled;

    // Enable drag and drop split from Recents. Disabled for low ram devices.
    public boolean dragToSplitEnabled;

    private final Context mAppContext;

    public int fabEnterAnimDuration;
    public int fabEnterAnimDelay;
    public int fabExitAnimDuration;

    private Handler mHandler = new Handler();
    private SettingsObserver mSettingsObserver;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mAppContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENTS_LAYOUT_STYLE),
                    false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            mIsGridEnabled = Settings.System.getIntForUser(mAppContext.getContentResolver(),
                    Settings.System.RECENTS_LAYOUT_STYLE, isGridEnabledDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) == 1;
            mIsGoLayoutEnabled = Settings.System.getIntForUser(mAppContext.getContentResolver(),
                    Settings.System.RECENTS_LAYOUT_STYLE, isLowRamDeviceDefault ? 2 : 0,
                    UserHandle.USER_CURRENT) == 2;
        }
    }

    public RecentsConfiguration(Context context) {
        // Load only resources that can not change after the first load either through developer
        // settings or via multi window
        SystemServicesProxy ssp = Recents.getSystemServices();
        mAppContext = context.getApplicationContext();
        Resources res = mAppContext.getResources();
        fakeShadows = res.getBoolean(R.bool.config_recents_fake_shadows);
        svelteLevel = res.getInteger(R.integer.recents_svelte_level);

        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        isGridEnabledDefault = SystemProperties.getBoolean("ro.recents.grid", false);
        isLowRamDeviceDefault = ActivityManager.isLowRamDeviceStatic();
        isLowRamDevice = mIsGoLayoutEnabled;
        dragToSplitEnabled = mIsGoLayoutEnabled? true : !isLowRamDeviceDefault;

        float screenDensity = context.getResources().getDisplayMetrics().density;
        smallestWidth = ssp.getDeviceSmallestWidth();
        isLargeScreen = smallestWidth >= (int) (screenDensity * LARGE_SCREEN_MIN_DP);
        isXLargeScreen = smallestWidth >= (int) (screenDensity * XLARGE_SCREEN_MIN_DP);

        fabEnterAnimDuration =
                res.getInteger(R.integer.recents_animate_fab_enter_duration);
        fabEnterAnimDelay =
                res.getInteger(R.integer.recents_animate_fab_enter_delay);
        fabExitAnimDuration =
                res.getInteger(R.integer.recents_animate_fab_exit_duration);

        mNextAlarmController = Dependency.get(NextAlarmController.class);
        mNextAlarmController.addCallback(this);
    }

    /**
     * Returns the activity launch state.
     * TODO: This will be refactored out of RecentsConfiguration.
     */
    public RecentsActivityLaunchState getLaunchState() {
        return mLaunchState;
    }

    /**
     * Returns the preferred dock states for the current orientation.
     * @return a list of dock states for device and its orientation
     */
    public TaskStack.DockState[] getDockStatesForCurrentOrientation() {
        boolean isLandscape = mAppContext.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        RecentsConfiguration config = Recents.getConfiguration();
        if (config.isLargeScreen) {
            return isLandscape ? DockRegion.TABLET_LANDSCAPE : DockRegion.TABLET_PORTRAIT;
        } else {
            return isLandscape ? DockRegion.PHONE_LANDSCAPE : DockRegion.PHONE_PORTRAIT;
        }
    }

    public boolean isGridEnabled() {
        return mIsGridEnabled;
    }

    public void setMediaPlaying(boolean playing, String packageName) {
        mMediaPlaying = playing;
        mMediaPackageName = packageName;
    }

    public void setMedia(int color, Drawable artwork, MediaMetadata mediaMetaData, String title, String text) {
        mMediaMetaData = mediaMetaData;
        String notificationText = null;
        if (title != null && text != null) {
            notificationText = title + " - " + text;
        }
        mMediaText = notificationText;
        mMediaColor = color;
        mArtWork = artwork;

        int callbackCount = mCallbacks.size();
        for (int i = 0; i < callbackCount; i++) {
            mCallbacks.get(i).onPlayingStateChanged();
        }
    }

    public int getMediaColor() {
        return mMediaColor;
    }

    public boolean isMediaPlaying() {
        return mMediaPlaying;
    }

    public String getMediaPackage() {
        return mMediaPackageName;
    }

    public String getTrackInfo() {
        CharSequence charSequence = null;
        CharSequence lenghtInfo = null;
        if (mMediaMetaData != null) {
            CharSequence artist = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_ARTIST);
            CharSequence album = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_ALBUM);
            CharSequence title = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_TITLE);
            long duration = mMediaMetaData.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (artist != null && album != null && title != null) {
                charSequence = artist.toString() /*+ " - " + album.toString()*/ + " - " + title.toString();
                if (duration != 0) {
                    lenghtInfo = String.format("%02d:%02d",
                            TimeUnit.MILLISECONDS.toMinutes(duration),
                            TimeUnit.MILLISECONDS.toSeconds(duration) -
                            TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration))
                    );
                }
            }
        }
        if (charSequence != null && lenghtInfo != null) {
            return (lenghtInfo + " | " + charSequence).toString();
        } else if (charSequence != null) {
            return charSequence.toString();
        }
        return mMediaText;
    }

    public Drawable getArtwork() {
        return mArtWork;
    }

    // To refresh the app card when the panel is already showing and the players skips a track
    public interface MediaAndClockCallbacks {
        public void onPlayingStateChanged();

        public void onAlarmChanged();
    }

    public void addCallback(MediaAndClockCallbacks cb) {
        if (!mCallbacks.contains(cb)) {
            mCallbacks.add(cb);
        }
    }

    public void removeCallback(MediaAndClockCallbacks cb) {
        mCallbacks.remove(cb);
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            mAlarm = KeyguardStatusView.formatNextAlarm(mAppContext, nextAlarm);
        } else {
            mAlarm = "";
        }
        int callbackCount = mCallbacks.size();
        for (int i = 0; i < callbackCount; i++) {
            mCallbacks.get(i).onAlarmChanged();
        }
    }

    public String getClockWithAlarmTitle(String appName) {
        final String icon = "\u23F2\uFE0E";
        return appName + " " + "(" + icon + " " + mAlarm + ")";
    }

    public boolean isAlarmActive() {
        return !mAlarm.isEmpty();
    }
}
