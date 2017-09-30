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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.LockIcon;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

/**
 * Controls the indications and error messages shown on the Keyguard
 */
public class KeyguardIndicationController {

    private static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final int MSG_HIDE_TRANSIENT = 1;
    private static final int MSG_CLEAR_FP_MSG = 2;
    private static final long TRANSIENT_FP_ERROR_TIMEOUT = 1300;

    private final Context mContext;
    private final ViewGroup mIndicationArea;
    private final KeyguardIndicationTextView mTextView;
    private final KeyguardIndicationTextView mDisclosure;
    private final UserManager mUserManager;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;

    private final int mSlowThreshold;
    private final int mFastThreshold;
    private final LockIcon mLockIcon;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private String mRestingIndication;
    private String mTransientIndication;
    private int mTransientTextColor;
    private boolean mVisible;

    private boolean mPowerPluggedIn;
    private boolean mPowerCharged;
    private int mChargingSpeed;
    private int mChargingCurrent;
    private double mChargingVoltage;
    private int mChargingWattage;
    private int mTemperature;
    private String mMessageToShowOnScreenOn;

    private KeyguardUpdateMonitorCallback mUpdateMonitor;

    private final DevicePolicyManager mDevicePolicyManager;
    private boolean mDozing;

    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    public KeyguardIndicationController(Context context, ViewGroup indicationArea,
            LockIcon lockIcon) {
        this(context, indicationArea, lockIcon,
                WakeLock.createPartial(context, "Doze:KeyguardIndication"));

        registerCallbacks(KeyguardUpdateMonitor.getInstance(context));
    }

    /**
     * Creates a new KeyguardIndicationController for testing. Does *not* register callbacks.
     */
    @VisibleForTesting
    KeyguardIndicationController(Context context, ViewGroup indicationArea, LockIcon lockIcon,
                WakeLock wakeLock) {
        mContext = context;
        mIndicationArea = indicationArea;
        mTextView = (KeyguardIndicationTextView) indicationArea.findViewById(
                R.id.keyguard_indication_text);
        mDisclosure = (KeyguardIndicationTextView) indicationArea.findViewById(
                R.id.keyguard_indication_enterprise_disclosure);
        mLockIcon = lockIcon;
        mWakeLock = new SettableWakeLock(wakeLock);

        Resources res = context.getResources();
        mSlowThreshold = res.getInteger(R.integer.config_chargingSlowlyThreshold);
        mFastThreshold = res.getInteger(R.integer.config_chargingFastThreshold);

        mUserManager = context.getSystemService(UserManager.class);
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));

        mDevicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        updateDisclosure();
    }

    private void registerCallbacks(KeyguardUpdateMonitor monitor) {
        monitor.registerCallback(getKeyguardCallback());

        mContext.registerReceiverAsUser(mTickReceiver, UserHandle.SYSTEM,
                new IntentFilter(Intent.ACTION_TIME_TICK), null,
                Dependency.get(Dependency.TIME_TICK_HANDLER));
    }

    /**
     * Gets the {@link KeyguardUpdateMonitorCallback} instance associated with this
     * {@link KeyguardIndicationController}.
     *
     * <p>Subclasses may override this method to extend or change the callback behavior by extending
     * the {@link BaseKeyguardCallback}.
     *
     * @return A KeyguardUpdateMonitorCallback. Multiple calls to this method <b>must</b> return the
     * same instance.
     */
    protected KeyguardUpdateMonitorCallback getKeyguardCallback() {
        if (mUpdateMonitor == null) {
            mUpdateMonitor = new BaseKeyguardCallback();
        }
        return mUpdateMonitor;
    }

    private void updateDisclosure() {
        if (mDevicePolicyManager == null) {
            return;
        }

        if (!mDozing && mDevicePolicyManager.isDeviceManaged()) {
            final CharSequence organizationName =
                    mDevicePolicyManager.getDeviceOwnerOrganizationName();
            if (organizationName != null) {
                mDisclosure.switchIndication(mContext.getResources().getString(
                        R.string.do_disclosure_with_name, organizationName));
            } else {
                mDisclosure.switchIndication(R.string.do_disclosure_generic);
            }
            mDisclosure.setVisibility(View.VISIBLE);
        } else {
            mDisclosure.setVisibility(View.GONE);
        }
    }

    public void setVisible(boolean visible) {
        mVisible = visible;
        mIndicationArea.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            hideTransientIndication();
            updateIndication();
        }
    }

    /**
     * Sets the indication that is shown if nothing else is showing.
     */
    public void setRestingIndication(String restingIndication) {
        mRestingIndication = restingIndication;
        updateIndication();
    }

    /**
     * Sets the active controller managing changes and callbacks to user information.
     */
    public void setUserInfoController(UserInfoController userInfoController) {
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_HIDE_TRANSIENT), delayMs);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(String transientIndication) {
        showTransientIndication(transientIndication, Color.WHITE);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(String transientIndication, int textColor) {
        mTransientIndication = transientIndication;
        mTransientTextColor = textColor;
        mHandler.removeMessages(MSG_HIDE_TRANSIENT);
        if (mDozing && !TextUtils.isEmpty(mTransientIndication)) {
            // Make sure this doesn't get stuck and burns in. Acquire wakelock until its cleared.
            mWakeLock.setAcquired(true);
            hideTransientIndicationDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);
        }
        updateIndication();
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHandler.removeMessages(MSG_HIDE_TRANSIENT);
            updateIndication();
        }
    }

    private void updateIndication() {
        if (TextUtils.isEmpty(mTransientIndication)) {
            mWakeLock.setAcquired(false);
        }

        if (mVisible) {
            // Walk down a precedence-ordered list of what should indication
            // should be shown based on user or device state
            if (mDozing) {
                // If we're dozing, never show a persistent indication.
                if (!TextUtils.isEmpty(mTransientIndication)) {
                    mTextView.switchIndication(mTransientIndication);
                    mTextView.setTextColor(mTransientTextColor);

                } else {
                    mTextView.switchIndication(null);
                }
                return;
            }

            if (!mUserManager.isUserUnlocked(KeyguardUpdateMonitor.getCurrentUser())) {
                mTextView.switchIndication(com.android.internal.R.string.lockscreen_storage_locked);
                mTextView.setTextColor(Color.WHITE);

            } else if (!TextUtils.isEmpty(mTransientIndication)) {
                mTextView.switchIndication(mTransientIndication);
                mTextView.setTextColor(mTransientTextColor);

            } else if (mPowerPluggedIn) {
                String indication = computePowerIndication();
                if (DEBUG_CHARGING_SPEED) {
                    indication += ",  " + (mChargingWattage / 1000) + " mW";
                }
                mTextView.switchIndication(indication);
                mTextView.setTextColor(Color.WHITE);

            } else {
                mTextView.switchIndication(mRestingIndication);
                mTextView.setTextColor(Color.WHITE);
            }
        }
    }

    private String computePowerIndication() {
        if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        // Try fetching charging time from battery stats.
        long chargingTimeRemaining = 0;
        try {
            chargingTimeRemaining = mBatteryInfo.computeChargeTimeRemaining();

        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IBatteryStats: ", e);
        }
        final boolean hasChargingTime = chargingTimeRemaining > 0;

        int chargingId;
        switch (mChargingSpeed) {
            case KeyguardUpdateMonitor.BatteryStatus.CHARGING_FAST:
                chargingId = hasChargingTime
                        ? R.string.keyguard_indication_charging_time_fast
                        : R.string.keyguard_plugged_in_charging_fast;
                break;
            case KeyguardUpdateMonitor.BatteryStatus.CHARGING_SLOWLY:
                chargingId = hasChargingTime
                        ? R.string.keyguard_indication_charging_time_slowly
                        : R.string.keyguard_plugged_in_charging_slowly;
                break;
            default:
                chargingId = hasChargingTime
                        ? R.string.keyguard_indication_charging_time
                        : R.string.keyguard_plugged_in;
                break;
        }

        String batteryInfo = "";
        boolean showbatteryInfo = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.LOCKSCREEN_BATTERY_INFO, 1, UserHandle.USER_CURRENT) == 1;

        if (showbatteryInfo) {
            if (mChargingCurrent > 0) {
                batteryInfo = batteryInfo + (mChargingCurrent / 1000) + "mA";
            }
            if (mChargingVoltage > 0) {
                batteryInfo = (batteryInfo == "" ? "" : batteryInfo + " · ") +
                        String.format("%.1f", (mChargingVoltage / 1000 / 1000)) + "V";
            }
            if (mTemperature > 0) {
                batteryInfo = (batteryInfo == "" ? "" : batteryInfo + " · ") +
                        mTemperature / 10 + "°C";
            }
            if (batteryInfo != "") {
                batteryInfo = "\n" + batteryInfo;
            }
        }

        if (hasChargingTime) {
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, chargingTimeRemaining);
            String chargingText = mContext.getResources().getString(chargingId, chargingTimeFormatted);
            return chargingText + batteryInfo;
        } else {
            String chargingText = mContext.getResources().getString(chargingId);
            return chargingText + batteryInfo;
        }
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    private final BroadcastReceiver mTickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.post(() -> {
                if (mVisible) {
                    updateIndication();
                }
            });
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_HIDE_TRANSIENT) {
                hideTransientIndication();
            } else if (msg.what == MSG_CLEAR_FP_MSG) {
                mLockIcon.setTransientFpError(false);
                hideTransientIndication();
            }
        }
    };

    public void setDozing(boolean dozing) {
        if (mDozing == dozing) {
            return;
        }
        mDozing = dozing;
        updateIndication();
        updateDisclosure();
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        public static final int HIDE_DELAY_MS = 5000;
        private int mLastSuccessiveErrorMessage = -1;

        @Override
        public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.status == BatteryManager.BATTERY_STATUS_FULL;
            boolean wasPluggedIn = mPowerPluggedIn;
            mPowerPluggedIn = status.isPluggedIn() && isChargingOrFull;
            mPowerCharged = status.isCharged();
            mChargingCurrent = status.maxChargingCurrent;
            mChargingVoltage = status.maxChargingVoltage;
            mChargingWattage = status.maxChargingWattage;
            mTemperature = status.temperature;
            mChargingSpeed = status.getChargingSpeed(mSlowThreshold, mFastThreshold);
            updateIndication();
            if (mDozing) {
                if (!wasPluggedIn && mPowerPluggedIn) {
                    showTransientIndication(computePowerIndication());
                    hideTransientIndicationDelayed(HIDE_DELAY_MS);
                } else if (wasPluggedIn && !mPowerPluggedIn) {
                    hideTransientIndication();
                }
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                updateDisclosure();
            }
        }

        @Override
        public void onFingerprintHelp(int msgId, String helpString) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (!updateMonitor.isUnlockingWithFingerprintAllowed()) {
                return;
            }
            int errorColor = Utils.getColorError(mContext);
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(helpString, errorColor);
            } else if (updateMonitor.isDeviceInteractive()
                    || mDozing && updateMonitor.isScreenOn()) {
                mLockIcon.setTransientFpError(true);
                showTransientIndication(helpString, errorColor);
                mHandler.removeMessages(MSG_CLEAR_FP_MSG);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEAR_FP_MSG),
                        TRANSIENT_FP_ERROR_TIMEOUT);
            }
            // Help messages indicate that there was actually a try since the last error, so those
            // are not two successive error messages anymore.
            mLastSuccessiveErrorMessage = -1;
        }

        @Override
        public void onFingerprintError(int msgId, String errString) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (!updateMonitor.isUnlockingWithFingerprintAllowed()
                    || msgId == FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
                return;
            }
            int errorColor = Utils.getColorError(mContext);
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                // When swiping up right after receiving a fingerprint error, the bouncer calls
                // authenticate leading to the same message being shown again on the bouncer.
                // We want to avoid this, as it may confuse the user when the message is too
                // generic.
                if (mLastSuccessiveErrorMessage != msgId) {
                    mStatusBarKeyguardViewManager.showBouncerMessage(errString, errorColor);
                }
            } else if (updateMonitor.isDeviceInteractive()) {
                showTransientIndication(errString, errorColor);
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
            } else {
                mMessageToShowOnScreenOn = errString;
            }
            mLastSuccessiveErrorMessage = msgId;
        }

        @Override
        public void onScreenTurnedOn() {
            if (mMessageToShowOnScreenOn != null) {
                int errorColor = Utils.getColorError(mContext);
                showTransientIndication(mMessageToShowOnScreenOn, errorColor);
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onFingerprintRunningStateChanged(boolean running) {
            if (running) {
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onFingerprintAuthenticated(int userId) {
            super.onFingerprintAuthenticated(userId);
            mLastSuccessiveErrorMessage = -1;
        }

        @Override
        public void onFingerprintAuthFailed() {
            super.onFingerprintAuthFailed();
            mLastSuccessiveErrorMessage = -1;
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateIndication();
            }
        }
    };
}
