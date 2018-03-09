/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.service.quicksettings.Tile;
import android.widget.Switch;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.graph.BatteryMeterDrawableBase;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class BatterySaverTile extends QSTileImpl<BooleanState> implements
        BatteryController.BatteryStateChangeCallback {

    private final BatteryController mBatteryController;

    private int mLevel;
    private boolean mPowerSave;
    private static boolean mCharging;
    private boolean mPluggedIn;

    private boolean mDashCharger;
    private boolean mHasDashCharger;

    private boolean mTurboCharger;
    private boolean mHasTurboCharger;

    public BatterySaverTile(QSHost host) {
        super(host);
        mBatteryController = Dependency.get(BatteryController.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_BATTERY_TILE;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            mBatteryController.addCallback(this);
        } else {
            mBatteryController.removeCallback(this);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
    }

    @Override
    protected void handleClick() {
        mBatteryController.setPowerSaveMode(!mPowerSave);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.battery_detail_switch_title);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        mHasDashCharger = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hasDashCharger);
        mDashCharger = mHasDashCharger && isDashCharger();

        mHasTurboCharger = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hasTurboPowerCharger);
        mTurboCharger = mHasTurboCharger && isTurboPower();

        state.state = mPluggedIn ? Tile.STATE_UNAVAILABLE
                : mPowerSave ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;

        BatterySaverIcon bsi = new BatterySaverIcon();
        bsi.mState = state.state;
        state.icon = bsi;

        if (mCharging) {
            state.label = mContext.getString(R.string.keyguard_plugged_in);
        }
        if (mDashCharger) {
            state.label = mContext.getString(R.string.keyguard_plugged_in_dash_charging);
        }
        if (mTurboCharger) {
            state.label = mContext.getString(R.string.keyguard_plugged_in_turbo_charging);
        }
        if (!mDashCharger && !mTurboCharger && !mCharging) {
            if (getBatteryLevel(mContext) == 100) {
                state.label = mContext.getString(R.string.battery_saver_qs_tile_fully_charged);
            } else {
                state.label = mLevel + "%";
            }
        }
        state.contentDescription = state.label;
        state.value = mPowerSave;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        mPluggedIn = pluggedIn;
        mCharging = charging;
        refreshState(level);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSave = isPowerSave;
        refreshState(null);
    }

    public static class BatterySaverIcon extends Icon {
        private int mState;

        @Override
        public Drawable getDrawable(Context context) {
            BatterySaverDrawable b = new BatterySaverDrawable(context, 0);
            b.mState = mState;
            final int pad = context.getResources()
                    .getDimensionPixelSize(R.dimen.qs_tile_divider_height);
            b.setPadding(pad, pad, pad, pad);
            return b;
        }
    }

    private static class BatterySaverDrawable extends BatteryMeterDrawableBase {
        private int mState;
        private static final int MAX_BATTERY = 100;

        BatterySaverDrawable(Context context, int frameColor) {
            super(context, frameColor);
            // Show as full so it's always uniform color
            super.setBatteryLevel(MAX_BATTERY);
            setPowerSave(true);

            if (mCharging) {
                setCharging(true);
            } else {
                setCharging(false);
            }
        }

        @Override
        protected int batteryColorForLevel(int level) {
            return QSTileImpl.getColorForState(mContext, mState);
        }

        @Override
        public void setBatteryLevel(int val) {
            // Don't change the actual level, otherwise this won't draw correctly
        }
    }

    // Check for dash charging -- OnePlus charging method
    private boolean isDashCharger() {
        try {
            FileReader file = new FileReader("/sys/class/power_supply/battery/fastchg_status");
            BufferedReader br = new BufferedReader(file);
            String state = br.readLine();
            br.close();
            file.close();
            return "1".equals(state);
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        }
        return false;
    }

    // Check for turbo charging -- Motorola charging method
    private boolean isTurboPower() {
        try {
            FileReader file = new FileReader("/sys/class/power_supply/battery/charge_rate");
            BufferedReader br = new BufferedReader(file);
            String state = br.readLine();
            br.close();
            file.close();
            return "Turbo".equals(state);
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        }
        return false;
    }

    private int getBatteryLevel(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent intent = context.registerReceiver(null, filter);
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        if (level < 0 || scale <= 0) {
            return 0;
        }

        return (100 * level / scale);
    }
}
