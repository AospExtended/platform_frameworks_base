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
package com.android.server.power;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.util.custom.SleepModeController;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.lang.IllegalArgumentException;
import java.util.Calendar;

public class SleepModeService extends SystemService {

    private static final String TAG = "SleepModeService";
    private static final int WAKELOCK_TIMEOUT_MS = 3000;

    /**
     * Disabled state (default)
     */
    private static final int MODE_DISABLED = 0;
    /**
     * Active from sunset to sunrise
     */
    private static final int MODE_NIGHT = 1;
    /**
     * Active at a user set time
     */
    private static final int MODE_TIME = 2;
    /**
     * Active from sunset till a time
     */
    private static final int MODE_MIXED_SUNSET = 3;
    /**
     * Active from a time till sunrise
     */
    private static final int MODE_MIXED_SUNRISE = 4;

    private final SleepModeController mSleepModeController;
    private final AlarmManager mAlarmManager;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TwilightManager mTwilightManager;
    private TwilightState mTwilightState;

    /**
     * Current operation mode
     * Can either be {@link #MODE_DISABLED}, {@link #MODE_NIGHT} or {@link #MODE_TIME}
     */
    private int mMode = MODE_DISABLED;
    /**
     * Whether Sleep Mode is currently activated by the service
     */
    private boolean mActive = false;
    /**
     * Whether next alarm should enable or disable Sleep Mode
     */
    private boolean mIsNextActivate = false;

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            if (mMode != MODE_NIGHT && mMode < MODE_MIXED_SUNSET) {
                setTwilightListener(false);
                return;
            }
            Slog.v(TAG, "onTwilightStateChanged state: " + state);
            if (state == null) return;
            mTwilightState = state;
            if (mMode < MODE_MIXED_SUNSET) mHandler.post(() -> maybeActivateSleepMode());
            else mHandler.post(() -> maybeActivateTime());
        }
    };

    private final BroadcastReceiver mTimeChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mMode != MODE_TIME && mMode < MODE_MIXED_SUNSET) {
                setTimeReciever(false);
                return;
            }
            Slog.v(TAG, "mTimeChangedReceiver onReceive");
            mHandler.post(() -> maybeActivateTime());
        }
    };

    /**
     * A class to manage and handle alarms
     */
    private class Alarm implements AlarmManager.OnAlarmListener {
        @Override
        public void onAlarm() {
            Slog.v(TAG, "onAlarm");
            mHandler.post(() -> setAutoSleepModeActive(mIsNextActivate));
            if (mMode == MODE_TIME || mMode >= MODE_MIXED_SUNSET)
                mHandler.post(() -> maybeActivateTime(false));
            else
                maybeActivateNight(false);
        }

        /**
         * Set a new alarm using a Calendar
         * @param time time as Calendar
         */
        public void set(Calendar time) {
            set(time.getTimeInMillis());
        }

        /**
         * Set a new alarm using ms since epoch
         * @param time time as ms since epoch
         */
        public void set(long time) {
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                    time, TAG, this, mHandler);
            Slog.v(TAG, "new alarm set to " + time
                    + " mIsNextActivate=" + mIsNextActivate);
        }

        public void cancel() {
            mAlarmManager.cancel(this);
            Slog.v(TAG, "alarm cancelled");
        }
    }

    private final Alarm mAlarm = new Alarm();

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SLEEP_MODE_AUTO_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SLEEP_MODE_AUTO_TIME),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mHandler.post(() -> initState());
        }
    }

    private final SettingsObserver mSettingsObserver;

    public SleepModeService(Context context) {
        super(context);
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mSettingsObserver = new SettingsObserver(mHandler);
        mSleepModeController = new SleepModeController(mContext);
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting " + TAG);
        publishLocalService(SleepModeService.class, this);
        mSettingsObserver.observe();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Slog.v(TAG, "onBootPhase PHASE_SYSTEM_SERVICES_READY");
            mTwilightManager = getLocalService(TwilightManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Slog.v(TAG, "onBootPhase PHASE_BOOT_COMPLETED");
            mHandler.post(() -> initState());
        }
    }

    /**
     * Registers or unregisters {@link #mTimeChangedReceiver}
     * @param register Register when true, unregister when false
     */
    private void setTimeReciever(boolean register) {
        if (register) {
            Slog.v(TAG, "Registering mTimeChangedReceiver");
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mContext.registerReceiver(mTimeChangedReceiver, intentFilter);
            return;
        }
        try {
            mContext.unregisterReceiver(mTimeChangedReceiver);
            Slog.v(TAG, "Unregistered mTimeChangedReceiver");
        } catch (IllegalArgumentException e) {
            // Nothing to do. Already unregistered
        }
    }

    /**
     * Registers or unregisters {@link #mTwilightListener}
     * @param register Register when true, unregister when false
     */
    private void setTwilightListener(boolean register) {
        if (register) {
            try {
                Slog.v(TAG, "Registering mTwilightListener");
                mTwilightManager.registerListener(mTwilightListener, mHandler);
                mTwilightState = mTwilightManager.getLastTwilightState();
            } catch (Exception e) {
                Slog.v(TAG, "Failed to register mTwilightListener");
            }
            return;
        }
        try {
            mTwilightManager.unregisterListener(mTwilightListener);
            Slog.v(TAG, "Unregistered mTwilightListener");
        } catch (Exception e) {
            // Nothing to do. Already unregistered
        }
    }

    /**
     * Initiates the state according to user settings
     * Registers or unregisters listeners and calls {@link #maybeActivateSleepMode()}
     */
    private void initState() {
        int pMode = mMode;
        mMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AUTO_MODE, MODE_DISABLED,
                UserHandle.USER_CURRENT);
        mAlarm.cancel();
        switch (mMode) {
            default:
            case MODE_DISABLED:
                if (pMode != MODE_DISABLED) setAutoSleepModeActive(false);
                return;
            case MODE_TIME:
                if (pMode == MODE_TIME) break;
                setTimeReciever(true);
                break;
            case MODE_NIGHT:
                if (pMode == MODE_NIGHT) break;
                setTwilightListener(true);
                break;
            case MODE_MIXED_SUNSET:
            case MODE_MIXED_SUNRISE:
                if (pMode >= MODE_MIXED_SUNSET) break;
                if (pMode != MODE_NIGHT) setTwilightListener(true);
                if (pMode != MODE_TIME) setTimeReciever(true);
                break;
        }
        switch (pMode) {
            case MODE_TIME:
                if (mMode == MODE_TIME || mMode >= MODE_MIXED_SUNSET) break;
                setTimeReciever(false);
                break;
            case MODE_NIGHT:
            case MODE_MIXED_SUNSET:
            case MODE_MIXED_SUNRISE:
                if (mMode == MODE_NIGHT || mMode >= MODE_MIXED_SUNSET) break;
                setTwilightListener(false);
                if (pMode < MODE_MIXED_SUNSET) break;
                setTimeReciever(false);
                break;
        }
        maybeActivateSleepMode();
    }

    /**
     * Calls the correct function to set the next alarm according to {@link #mMode}
     */
    private void maybeActivateSleepMode() {
        switch (mMode) {
            default:
            case MODE_DISABLED:
                break;
            case MODE_NIGHT:
                maybeActivateNight();
                break;
            case MODE_TIME:
            case MODE_MIXED_SUNSET:
            case MODE_MIXED_SUNRISE:
                maybeActivateTime();
                break;
        }
    }

    /**
     * See {@link #maybeActivateNight(boolean)}
     */
    private void maybeActivateNight() {
        maybeActivateNight(true);
    }

    /**
     * Sets the next alarm for {@link #MODE_NIGHT}
     * @param setActive Whether to set activation state.
     *                  When false only updates the alarm
     */
    private void maybeActivateNight(boolean setActive) {
        if (mTwilightState == null) {
            Slog.e(TAG, "aborting maybeActivateNight(). mTwilightState is null");
            return;
        }
        mIsNextActivate = !mTwilightState.isNight();
        mAlarm.set(mIsNextActivate ? mTwilightState.sunsetTimeMillis()
                : mTwilightState.sunriseTimeMillis());
        if (setActive) mHandler.post(() -> setAutoSleepModeActive(!mIsNextActivate));
    }

    /**
     * See {@link #maybeActivateTime(boolean)}
     */
    private void maybeActivateTime() {
        maybeActivateTime(true);
    }

    /**
     * Sets the next alarm for {@link #MODE_TIME}, {@link #MODE_MIXED_SUNSET} and
     *                         {@link #MODE_MIXED_SUNRISE}
     * @param setActive Whether to set activation state
     *                  When false only updates the alarm
     */
    private void maybeActivateTime(boolean setActive) {
        Calendar currentTime = Calendar.getInstance();
        Calendar since = Calendar.getInstance();
        Calendar till = Calendar.getInstance();
        String value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AUTO_TIME, UserHandle.USER_CURRENT);
        if (value == null || value.equals("")) value = "20:00,07:00";
        String[] times = value.split(",", 0);
        String[] sinceValues = times[0].split(":", 0);
        String[] tillValues = times[1].split(":", 0);
        since.set(Calendar.HOUR_OF_DAY, Integer.parseInt(sinceValues[0]));
        since.set(Calendar.MINUTE, Integer.parseInt(sinceValues[1]));
        since.set(Calendar.SECOND, 0);
        till.set(Calendar.HOUR_OF_DAY, Integer.parseInt(tillValues[0]));
        till.set(Calendar.MINUTE, Integer.parseInt(tillValues[1]));
        till.set(Calendar.SECOND, 0);

        // Handle mixed modes
        if (mMode >= MODE_MIXED_SUNSET) {
            if (mTwilightState == null) {
                Slog.e(TAG, "aborting maybeActivateTime(). mTwilightState is null");
                return;
            }
            if (mMode == MODE_MIXED_SUNSET) {
                since.setTimeInMillis(mTwilightState.sunsetTimeMillis());
            } else { // MODE_MIXED_SUNRISE
                till.setTimeInMillis(mTwilightState.sunriseTimeMillis());
                if (!mTwilightState.isNight()) till.roll(Calendar.DATE, true);
            }
        }

        // Roll to the next day if needed be
        if (since.after(till)) till.roll(Calendar.DATE, true);
        if (currentTime.after(since) && currentTime.compareTo(till) >= 0) {
            since.roll(Calendar.DATE, true);
            till.roll(Calendar.DATE, true);
        }
        // Abort if the user was dumb enough to set the same time
        if (since.compareTo(till) == 0) {
            Slog.e(TAG, "Aborting maybeActivateTime(). Time diff is 0");
            return;
        }

        // Update the next alarm
        mIsNextActivate = currentTime.before(since);
        mAlarm.set(mIsNextActivate ? since : till);

        // Activate or disable according to current time
        if (setActive) setAutoSleepModeActive(currentTime.compareTo(since) >= 0
                && currentTime.before(till));
    }

    /**
     * Activates or deactivates Sleep Mode
     * @param active Whether to enable or disable Sleep Mode
     */
    private void setAutoSleepModeActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        Slog.v(TAG, "setAutoSleepModeActive: active=" + active);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_ENABLED, active ? 1 : 0, UserHandle.USER_CURRENT);
    }
}
