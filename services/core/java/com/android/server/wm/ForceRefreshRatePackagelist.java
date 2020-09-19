/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved.
 *
 * Not a contribution.
*/

/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.os.BackgroundThread;
import com.android.server.wm.utils.DeviceConfigInterface;


/**
 * A list for packages that should force the display out of high refresh rate.
 */
class ForceRefreshRatePackageList {

    private static final String TAG = ForceRefreshRatePackageList.class.getSimpleName();
    private static final String KEY_FORCE_REFRESH_RATE_LIST = "force_refresh_rate_list";
    private static final float REFRESH_RATE_EPSILON  = 0.01f;

    private final ArrayMap<String, Float> mForcedPackageList = new ArrayMap<>();
    private final Object mLock = new Object();
    private DisplayInfo mDisplayInfo;
    private DeviceConfigInterface mDeviceConfig;
    private OnPropertiesChangedListener mListener = new OnPropertiesChangedListener();

    private static volatile ForceRefreshRatePackageList mInstance;

    static ForceRefreshRatePackageList getInstance(WindowManagerService wmService) {
        if (mInstance == null) {
            synchronized (ForceRefreshRatePackageList.class) {
                if (mInstance == null) {
                    mInstance = new ForceRefreshRatePackageList(wmService);
                }
            }
        }
        return mInstance;
    }

    private ForceRefreshRatePackageList(WindowManagerService wmService) {
        mDisplayInfo = wmService.getDefaultDisplayContentLocked().getDisplayInfo();
        mDeviceConfig = DeviceConfigInterface.REAL;
        mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                BackgroundThread.getExecutor(), mListener);
        final String property = mDeviceConfig.getProperty(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                KEY_FORCE_REFRESH_RATE_LIST);
        updateForcedPackagelist(property);
    }

    private void updateForcedPackagelist(@Nullable String property) {
        synchronized (mLock) {
            mForcedPackageList.clear();
            if (!TextUtils.isEmpty(property)) {
                String[] pairs = property.split(";");
                for (String pair : pairs) {
                    String[] keyValue = pair.split(",");
                    if (keyValue != null && keyValue.length == 2) {
                        if (!TextUtils.isEmpty(keyValue[0].trim())
                                && !TextUtils.isEmpty(keyValue[1].trim())) {
                            try {
                                String packageName = keyValue[0].trim();
                                Float refreshRate = new Float(keyValue[1].trim());
                                mForcedPackageList.put(packageName, refreshRate);
                            } catch (NumberFormatException e) {
                                Log.e(TAG, "Invalid refresh rate input! input: " + keyValue);
                            }
                        }
                    }
                }
            }
        }
    }

    int getForceRefreshRateId(String packageName) {
        synchronized (mLock) {
            if(mForcedPackageList.containsKey(packageName)) {
                float refreshRate = mForcedPackageList.get(packageName).floatValue();
                return findModeByRefreshRate(refreshRate);
            }else {
                return 0;
            }
        }
    }

    int findModeByRefreshRate(float refreshRate) {
        Display.Mode[] modes = mDisplayInfo.supportedModes;
        for (int i = 0; i < modes.length; i++) {
            if (Math.abs(modes[i].getRefreshRate() - refreshRate) < REFRESH_RATE_EPSILON) {
                return modes[i].getModeId();
            }
        }
        return 0;
    }

    private class OnPropertiesChangedListener implements DeviceConfig.OnPropertiesChangedListener {
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            if (properties.getKeyset().contains(KEY_FORCE_REFRESH_RATE_LIST)) {
                updateForcedPackagelist(
                        properties.getString(KEY_FORCE_REFRESH_RATE_LIST, null /*default*/));
            }
        }
    }
}

