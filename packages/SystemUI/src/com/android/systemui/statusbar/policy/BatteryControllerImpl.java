/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BatteryStatsHelper;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.Dependency;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.power.Estimate;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Default implementation of a {@link BatteryController}. This controller monitors for battery
 * level change events that are broadcasted by the system.
 */
public class BatteryControllerImpl extends BroadcastReceiver implements BatteryController {
    private static final String TAG = "BatteryController";

    public static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<BatteryController.BatteryStateChangeCallback> mChangeCallbacks = new ArrayList<>();
    private final PowerManager mPowerManager;
    private final Handler mHandler;
    private final Handler mBgHandler;
    private final Context mContext;

    protected int mLevel;
    protected boolean mPluggedIn;
    protected boolean mCharging;
    protected boolean mCharged;
    protected boolean mPowerSave;
    protected boolean mAodPowerSave;
    private boolean mTestmode = false;
    private boolean mHasReceivedBattery = false;

    private Estimate mEstimate = null;
    private final EnhancedEstimates mEstimates;
    private final ArrayList<EstimateFetchCompletion> mFetchCallbacks = new ArrayList<>();
    private boolean mFetchingEstimate = false;
    private long mLastEstimateTimestamp = -1;

    public BatteryControllerImpl(Context context, EnhancedEstimates enhancedEstimates) {
        this(context, enhancedEstimates, context.getSystemService(PowerManager.class));
    }

    @VisibleForTesting
    BatteryControllerImpl(Context context, EnhancedEstimates enhancedEstimates, PowerManager powerManager) {
        mContext = context;
        mHandler = new Handler();
        mBgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
        mPowerManager = powerManager;
        mEstimates = enhancedEstimates;

        registerReceiver();
        updatePowerSave();
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGING);
        filter.addAction(ACTION_LEVEL_TEST);
        mContext.registerReceiver(this, filter);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BatteryController state:");
        pw.print("  mLevel="); pw.println(mLevel);
        pw.print("  mPluggedIn="); pw.println(mPluggedIn);
        pw.print("  mCharging="); pw.println(mCharging);
        pw.print("  mCharged="); pw.println(mCharged);
        pw.print("  mPowerSave="); pw.println(mPowerSave);
    }

    @Override
    public void setPowerSaveMode(boolean powerSave) {
        BatterySaverUtils.setPowerSaveMode(mContext, powerSave, /*needFirstTimeWarning*/ true);
    }

    @Override
    public void addCallback(BatteryController.BatteryStateChangeCallback cb) {
        synchronized (mChangeCallbacks) {
            mChangeCallbacks.add(cb);
        }
        if (!mHasReceivedBattery) return;
        cb.onBatteryLevelChanged(mLevel, mPluggedIn, mCharging);
        cb.onPowerSaveChanged(mPowerSave);
    }

    @Override
    public void removeCallback(BatteryController.BatteryStateChangeCallback cb) {
        synchronized (mChangeCallbacks) {
            mChangeCallbacks.remove(cb);
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
            if (mTestmode && !intent.getBooleanExtra("testmode", false)) return;
            mHasReceivedBattery = true;
            mLevel = (int)(100f
                    * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            mPluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            mCharged = status == BatteryManager.BATTERY_STATUS_FULL;
            mCharging = mCharged || status == BatteryManager.BATTERY_STATUS_CHARGING;

            fireBatteryLevelChanged();
        } else if (action.equals(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) {
            updatePowerSave();
        } else if (action.equals(PowerManager.ACTION_POWER_SAVE_MODE_CHANGING)) {
            setPowerSave(intent.getBooleanExtra(PowerManager.EXTRA_POWER_SAVE_MODE, false));
        } else if (action.equals(ACTION_LEVEL_TEST)) {
            mTestmode = true;
            mHandler.post(new Runnable() {
                int curLevel = 0;
                int incr = 1;
                int saveLevel = mLevel;
                boolean savePlugged = mPluggedIn;
                Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);
                @Override
                public void run() {
                    if (curLevel < 0) {
                        mTestmode = false;
                        dummy.putExtra("level", saveLevel);
                        dummy.putExtra("plugged", savePlugged);
                        dummy.putExtra("testmode", false);
                    } else {
                        dummy.putExtra("level", curLevel);
                        dummy.putExtra("plugged", incr > 0 ? BatteryManager.BATTERY_PLUGGED_AC
                                : 0);
                        dummy.putExtra("testmode", true);
                    }
                    context.sendBroadcast(dummy);

                    if (!mTestmode) return;

                    curLevel += incr;
                    if (curLevel == 100) {
                        incr *= -1;
                    }
                    mHandler.postDelayed(this, 200);
                }
            });
        }
    }

    @Override
    public boolean isPowerSave() {
        return mPowerSave;
    }

    @Override
    public boolean isAodPowerSave() {
        return mAodPowerSave;
    }

    private void updatePowerSave() {
        setPowerSave(mPowerManager.isPowerSaveMode());
    }

    private void setPowerSave(boolean powerSave) {
        if (powerSave == mPowerSave) return;
        mPowerSave = powerSave;

        // AOD power saving setting might be different from PowerManager power saving mode.
        PowerSaveState state = mPowerManager.getPowerSaveState(PowerManager.ServiceType.AOD);
        mAodPowerSave = state.batterySaverEnabled;

        if (DEBUG) Log.d(TAG, "Power save is " + (mPowerSave ? "on" : "off"));
        firePowerSaveChanged();
    }

    protected void fireBatteryLevelChanged() {
        synchronized (mChangeCallbacks) {
            final int N = mChangeCallbacks.size();
            for (int i = 0; i < N; i++) {
                mChangeCallbacks.get(i).onBatteryLevelChanged(mLevel, mPluggedIn, mCharging);
            }
        }
    }

    private void firePowerSaveChanged() {
        synchronized (mChangeCallbacks) {
            final int N = mChangeCallbacks.size();
            for (int i = 0; i < N; i++) {
                mChangeCallbacks.get(i).onPowerSaveChanged(mPowerSave);
            }
        }
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
            mContext.unregisterReceiver(this);
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            registerReceiver();
            updatePowerSave();
        } else if (mDemoMode && command.equals(COMMAND_BATTERY)) {
            String level = args.getString("level");
            String plugged = args.getString("plugged");
            String powerSave = args.getString("powersave");
            if (level != null) {
                mLevel = Math.min(Math.max(Integer.parseInt(level), 0), 100);
            }
            if (plugged != null) {
                mPluggedIn = Boolean.parseBoolean(plugged);
            }
            if (powerSave != null) {
                mPowerSave = powerSave.equals("true");
                firePowerSaveChanged();
            }
            fireBatteryLevelChanged();
        }
    }

    public void getEstimatedTimeRemainingString(EstimateFetchCompletion callback) {
        if (mEstimate == null || mLastEstimateTimestamp <= System.currentTimeMillis() - 60000) {
            synchronized (mFetchCallbacks) {
                mFetchCallbacks.add(callback);
            }
            updateEstimateInBackground();
            return;
        }
        callback.onBatteryRemainingEstimateRetrieved(generateTimeRemainingString());
    }
    
    private void updateEstimateInBackground() {
        if (!mFetchingEstimate) {
            mFetchingEstimate = true;
            mBgHandler.post(this::updateEstimate);
        }
    }

    private void updateEstimate() {
        mEstimate = mEstimates.isHybridNotificationEnabled() ? mEstimates.getEstimate() : null;
        if (mEstimate == null) {
            mEstimate = computeEstimate();
        }
        mLastEstimateTimestamp = System.currentTimeMillis();
        mFetchingEstimate = false;
        mHandler.post(this::notifyEstimateFetchCallbacks);
    }

    private Estimate computeEstimate() {
        BatteryStatsHelper statsHelper = new BatteryStatsHelper(mContext, true);
        statsHelper.create((Bundle) null);
        BatteryStats stats = statsHelper.getStats();
        if (stats == null) return null;
        final long elapsedRealtimeUs =
                        PowerUtil.convertMsToUs(SystemClock.elapsedRealtime());
        long prediction = stats.computeBatteryTimeRemaining(elapsedRealtimeUs);
        if (prediction == -1) return null;
        return new Estimate(PowerUtil.convertUsToMs(prediction), false);
    }

    private void notifyEstimateFetchCallbacks() {
        String remaining = generateTimeRemainingString();
        synchronized (mFetchCallbacks) {
            for (EstimateFetchCompletion callback : mFetchCallbacks) {
                callback.onBatteryRemainingEstimateRetrieved(remaining);
            }
            mFetchCallbacks.clear();
        }
    }

    private String generateTimeRemainingString() {
        if (mEstimate == null) {
            return null;
        }
        return PowerUtil.getBatteryRemainingShortStringFormatted(mContext, mEstimate.estimateMillis);
    }
}
