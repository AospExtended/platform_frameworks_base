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
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.telephony.TelephonyManager;
import android.content.ComponentName;
import com.android.internal.telephony.DcParamObject;
import com.android.internal.telephony.Phone;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

/**
 * Lazy Lte Tile
 * Created by Adnan on 1/21/15.
 */
public class LteTile extends QSTileImpl<BooleanState> {

    private static final Intent MOBILE_NETWORK_SETTINGS = new Intent(Intent.ACTION_MAIN)
            .setComponent(new ComponentName("com.android.phone",
                    "com.android.phone.MobileNetworkSettings"));

    public LteTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return MOBILE_NETWORK_SETTINGS;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_lte_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.LIQUID;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    protected void handleClick() {
        toggleLteState();
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {

        switch (getCurrentPreferredNetworkMode()) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_TDSCDMA_GSM_WCDMA:
            case Phone.NT_MODE_LTE_TDSCDMA_WCDMA:
                state.icon= ResourceIcon.get(R.drawable.ic_qs_lte_on);
                state.label = mContext.getString(R.string.lte_on);
                state.state = Tile.STATE_ACTIVE;
                break;
            default:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_lte_off);
                state.label = mContext.getString(R.string.lte_off);
                state.state = Tile.STATE_INACTIVE;
                break;
        }
    }

    private void toggleLteState() {
        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        tm.toggleLTE(true);
    }

    private int getCurrentPreferredNetworkMode() {
        int subid = 1;
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.PREFERRED_NETWORK_MODE + subid, -1);
    }
}
