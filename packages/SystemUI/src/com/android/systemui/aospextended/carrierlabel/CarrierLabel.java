/*
 * Copyright (C) 2014-2015 The MoKee OpenSource Project
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

package com.android.systemui.aospextended.carrierlabel;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.util.aospextended.AEXUtils;
import com.android.internal.telephony.TelephonyIntents;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import com.android.systemui.R;

public class CarrierLabel extends TextView implements DarkReceiver {

    private Context mContext;
    private boolean mAttached;

    private String mNetworkName = "";

    public CarrierLabel(Context context) {
        this(context, null);
    }

    public CarrierLabel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarrierLabel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        /* Force carrier label to the lockscreen. This helps us avoid
        the carrier label on the statusbar if for whatever reason
        the user changes notch overlays */
        if (AEXUtils.hasNotch(mContext)) {
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_SHOW_CARRIER, 1);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        setTextColor(DarkIconDispatcher.getTint(area, this, tint));
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED.equals(action)) {
                updateNetworkName(mNetworkName);
            }
        }
    };

    public void updateNetworkName(String name) {
        String customCarrierLabel = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.CUSTOM_CARRIER_LABEL, UserHandle.USER_CURRENT);
        if (!TextUtils.isEmpty(customCarrierLabel)) {
            setText(customCarrierLabel);
        } else {
            setText(name);
        }
    }

    public void setNetworkName(String networkName) {
        mNetworkName = networkName;
        updateNetworkName(networkName);
    }
}
