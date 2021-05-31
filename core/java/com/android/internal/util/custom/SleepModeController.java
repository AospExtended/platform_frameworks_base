/**
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

package com.android.internal.util.custom;

import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;

public class SleepModeController {
    private final Resources mResources;
    private final Context mUiContext;

    private Context mContext;
    private AudioManager mAudioManager;
    private NotificationManager mNotificationManager;
    private WifiManager mWifiManager;
    private LocationManager mLocationManager;
    private SensorPrivacyManager mSensorPrivacyManager;
    private BluetoothAdapter mBluetoothAdapter;
    private int mSubscriptionId;
    private Toast mToast;

    private boolean mSleepModeEnabled;

    private static boolean mWifiState;
    private static boolean mLocationState;
    private static boolean mCellularState;
    private static boolean mBluetoothState;
    private static boolean mSensorState;
    private static int mAODState;
    private static int mIdleState;
    private static int mStandbyState;
    private static int mRingerState;
    private static int mZenState;

    private static final String TAG = "SleepModeController";
    private static final int SLEEP_NOTIFICATION_ID = 727;
    public static final String SLEEP_MODE_TURN_OFF = "android.intent.action.SLEEP_MODE_TURN_OFF";

    public SleepModeController(Context context) {
        mContext = context;
        mUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mResources = mContext.getResources();

        mSleepModeEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

        SettingsObserver observer = new SettingsObserver(new Handler(Looper.getMainLooper()));
        observer.observe();
        observer.update();
    }

    private TelephonyManager getTelephonyManager() {
        int subscriptionId = mSubscriptionId;

        // If mSubscriptionId is invalid, get default data sub.
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId();
        }

        // If data sub is also invalid, get any active sub.
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            int[] activeSubIds = SubscriptionManager.from(mContext).getActiveSubscriptionIdList();
            if (!ArrayUtils.isEmpty(activeSubIds)) {
                subscriptionId = activeSubIds[0];
            }
        }

        return mContext.getSystemService(
                TelephonyManager.class).createForSubscriptionId(subscriptionId);
    }

    private boolean isWifiEnabled() {
        if (mWifiManager == null) {
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        }
        try {
            return mWifiManager.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void setWifiEnabled(boolean enable) {
        if (mWifiManager == null) {
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        }
        try {
            mWifiManager.setWifiEnabled(enable);
        } catch (Exception e) {
        }
    }

    private boolean isLocationEnabled() {
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        }
        try {
            return mLocationManager.isLocationEnabledForUser(UserHandle.of(ActivityManager.getCurrentUser()));
        } catch (Exception e) {
            return false;
        }
    }

    private void setLocationEnabled(boolean enable) {
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        }
        try {
            mLocationManager.setLocationEnabledForUser(enable, UserHandle.of(ActivityManager.getCurrentUser()));
        } catch (Exception e) {
        }
    }

    private boolean isBluetoothEnabled() {
        if (mBluetoothAdapter == null) {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        try {
            return mBluetoothAdapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void setBluetoothEnabled(boolean enable) {
        if (mBluetoothAdapter == null) {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        try {
            if (enable) mBluetoothAdapter.enable();
            else mBluetoothAdapter.disable();
        } catch (Exception e) {
        }
    }

    private boolean isSensorEnabled() {
        if (mSensorPrivacyManager == null) {
            mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
        }
        try {
            return !mSensorPrivacyManager.isSensorPrivacyEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void setSensorEnabled(boolean enable) {
        if (mSensorPrivacyManager == null) {
            mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
        }
        try {
            mSensorPrivacyManager.setSensorPrivacy(!enable);
        } catch (Exception e) {
        }
    }

    private int getZenMode() {
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        try {
            return mNotificationManager.getZenMode();
        } catch (Exception e) {
            return -1;
        }
    }

    private void setZenMode(int mode) {
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        try {
            mNotificationManager.setZenMode(mode, null, TAG);
        } catch (Exception e) {
        }
    }

    private int getRingerModeInternal() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        try {
            return mAudioManager.getRingerModeInternal();
        } catch (Exception e) {
            return -1;
        }
    }

    private void setRingerModeInternal(int mode) {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        try {
            mAudioManager.setRingerModeInternal(mode);
        } catch (Exception e) {
        }
    }

    private void enable() {
        if (!ActivityManager.isSystemReady()) return;

        // Disable Wi-Fi
        boolean disableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_WIFI_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableWifi) {
            mWifiState = isWifiEnabled();
            setWifiEnabled(false);
        }

        // Disable Bluetooth
        boolean disableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_BLUETOOTH_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableBluetooth) {
            mBluetoothState = isBluetoothEnabled();
            setBluetoothEnabled(false);
        }

        // Disable Mobile Data
        boolean disableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_CELLULAR_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableData) {
            mCellularState = getTelephonyManager().isDataEnabled();
            getTelephonyManager().setDataEnabled(false);
        }

        // Disable Location
        boolean disableLocation = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_LOCATION_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableLocation) {
            mLocationState = isLocationEnabled();
            setLocationEnabled(false);
        }

        // Disable Sensors
        boolean disableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_SENSORS_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableSensors) {
            mSensorState = isSensorEnabled();
            setSensorEnabled(false);
        }

        // Disable AOD
        boolean disableAOD = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AOD_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableAOD) {
            mAODState = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT);
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT);
        }

        // Enable Aggressive battery
        boolean enableAggressive = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AGGRESSIVE_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (enableAggressive) {
            mIdleState = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.AGGRESSIVE_IDLE_ENABLED, 0);
            mStandbyState = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.AGGRESSIVE_STANDBY_ENABLED, 0);
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGGRESSIVE_IDLE_ENABLED, 1);
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGGRESSIVE_STANDBY_ENABLED, 1);
        }

        // Set Ringer mode (0: Off, 1: Vibrate, 2:DND: 3:Silent)
        int ringerMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_RINGER_MODE, 0, UserHandle.USER_CURRENT);
        if (ringerMode != 0) {
            mRingerState = getRingerModeInternal();
            mZenState = getZenMode();
            if (ringerMode == 1) {
                setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                setZenMode(ZEN_MODE_OFF);
            } else if (ringerMode == 2) {
                setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
            } else if (ringerMode == 3) {
                setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
                setZenMode(ZEN_MODE_OFF);
            }
        }

        showToast(mResources.getString(R.string.sleep_mode_enabled_toast), Toast.LENGTH_LONG);
        addNotification();
    }

    private void disable() {
        if (!ActivityManager.isSystemReady()) return;

        // Enable Wi-Fi
        boolean disableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_WIFI_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableWifi && mWifiState != isWifiEnabled()) {
            setWifiEnabled(mWifiState);
        }

        // Enable Bluetooth
        boolean disableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_BLUETOOTH_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableBluetooth && mBluetoothState != isBluetoothEnabled()) {
            setBluetoothEnabled(mBluetoothState);
        }

        // Enable Mobile Data
        boolean disableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_CELLULAR_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableData && mCellularState != getTelephonyManager().isDataEnabled()) {
            getTelephonyManager().setDataEnabled(mCellularState);
        }

        // Enable Location
        boolean disableLocation = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_LOCATION_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableLocation && mLocationState != isLocationEnabled()) {
            setLocationEnabled(mLocationState);
        }

        // Enable Sensors
        boolean disableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_SENSORS_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableSensors && mSensorState != isSensorEnabled()) {
            setSensorEnabled(mSensorState);
        }

        // Enable AOD
        boolean disableAOD = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_AOD_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (disableAOD) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ALWAYS_ON, mAODState, UserHandle.USER_CURRENT);
        }

        // Disable Aggressive battery
        boolean enableAggressive = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_AGGRESSIVE_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
        if (enableAggressive) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGGRESSIVE_IDLE_ENABLED, mIdleState);
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGGRESSIVE_STANDBY_ENABLED, mStandbyState);
        }

        // Set Ringer mode (0: Off, 1: Vibrate, 2:DND: 3:Silent)
        int ringerMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SLEEP_MODE_RINGER_MODE, 0, UserHandle.USER_CURRENT);
        if (ringerMode != 0 && (mRingerState != getRingerModeInternal() ||
                mZenState != getZenMode())) {
            setRingerModeInternal(mRingerState);
            setZenMode(mZenState);
        }

        showToast(mResources.getString(R.string.sleep_mode_disabled_toast), Toast.LENGTH_LONG);
        mNotificationManager.cancel(SLEEP_NOTIFICATION_ID);
    }

    private void addNotification() {
        Intent intent = new Intent(SLEEP_MODE_TURN_OFF);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        // Display a notification
        Notification.Builder builder = new Notification.Builder(mContext, SystemNotificationChannels.SLEEP)
            .setTicker(mResources.getString(R.string.sleep_mode_notification_title))
            .setContentTitle(mResources.getString(R.string.sleep_mode_notification_title))
            .setContentText(mResources.getString(R.string.sleep_mode_notification_content))
            .setSmallIcon(R.drawable.ic_sleep)
            .setWhen(java.lang.System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false);

        Notification notification = builder.build();
        mNotificationManager.notify(SLEEP_NOTIFICATION_ID, notification);
    }

    private void showToast(String msg, int duration) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mToast != null) mToast.cancel();
                    mToast = Toast.makeText(mUiContext, msg, duration);
                    mToast.show();
                } catch (Exception e) {
                }
            }
        });
    }

    private void setSleepMode(boolean enabled) {
        if (mSleepModeEnabled == enabled) {
            return;
        }

        mSleepModeEnabled = enabled;

        if (mSleepModeEnabled) {
            enable();
        } else {
            disable();
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SLEEP_MODE_ENABLED), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update();
        }

        void update() {
            boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
            setSleepMode(enabled);
        }
    }
}
