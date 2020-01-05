/**
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
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

package com.android.internal.notification;

import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;

import android.app.Notification;
import android.content.Context;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.notification.LedValues;
import com.android.internal.notification.LightsCapabilities;
import com.android.internal.util.benzo.ColorUtils;

import java.util.Map;

public final class NotificationLights {
    private static final String TAG = "NotificationLights";
    private static final boolean DEBUG = false;

    // Light capabilities
    private boolean mAdjustableNotificationLedBrightness;
    private boolean mMultiColorNotificationLed;

    // Light config
    private boolean mAutoGenerateNotificationColor;
    private boolean mScreenOnEnabled;
    private int mDefaultNotificationColor;
    private int mDefaultNotificationLedOn;
    private int mDefaultNotificationLedOff;

    private ArrayMap<String, LedValues> mNotificationPulseCustomLedValues;
    private Map<String, String> mPackageNameMappings;
    private final ArrayMap<String, Integer> mGeneratedPackageLedColors =
            new ArrayMap<String, Integer>();

    private final SettingsObserver mSettingsObserver;

    private final Context mContext;

    public interface LedUpdater {
        public void update();
    }
    private final LedUpdater mLedUpdater;

    public NotificationLights(Context context, LedUpdater ledUpdater) {
        mContext = context;
        mLedUpdater = ledUpdater;

        final Resources res = mContext.getResources();

        mAdjustableNotificationLedBrightness = LightsCapabilities.supports(
                mContext, LightsCapabilities.LIGHTS_ADJUSTABLE_NOTIFICATION_LED_BRIGHTNESS);

        mDefaultNotificationColor = res.getColor(
                com.android.internal.R.color.config_defaultNotificationColor);
        mDefaultNotificationLedOn = res.getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        mDefaultNotificationLedOff = res.getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);

        mMultiColorNotificationLed = LightsCapabilities.supports(
                mContext, LightsCapabilities.LIGHTS_RGB_NOTIFICATION_LED);

        mNotificationPulseCustomLedValues = new ArrayMap<String, LedValues>();

        mPackageNameMappings = new ArrayMap<String, String>();
        final String[] defaultMapping = res.getStringArray(
                com.android.internal.R.array.notification_light_package_mapping);
        for (String mapping : defaultMapping) {
            String[] map = mapping.split("\\|");
            mPackageNameMappings.put(map[0], map[1]);
        }

        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();
    }

    // Whether we should show lights if the screen is on.
    public boolean showLightsScreenOn() {
        return mScreenOnEnabled;
    }

    private void parseNotificationPulseCustomValuesString(String customLedValuesString) {
        if (TextUtils.isEmpty(customLedValuesString)) {
            return;
        }

        for (String packageValuesString : customLedValuesString.split("\\|")) {
            String[] packageValues = packageValuesString.split("=");
            if (packageValues.length != 2) {
                Slog.e(TAG, "Error parsing custom led values for unknown package");
                continue;
            }
            String packageName = packageValues[0];
            String[] values = packageValues[1].split(";");
            if (values.length != 3) {
                Slog.e(TAG, "Error parsing custom led values '"
                        + packageValues[1] + "' for " + packageName);
                continue;
            }
            LedValues ledValues;
            try {
                // color, onMs, offMs
                ledValues = new LedValues(Integer.parseInt(values[0]),
                        Integer.parseInt(values[1]), Integer.parseInt(values[2]));
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Error parsing custom led values '"
                        + packageValues[1] + "' for " + packageName);
                continue;
            }
            mNotificationPulseCustomLedValues.put(packageName, ledValues);
        }
    }

    private LedValues getLedValuesForPackageName(String packageName) {
        return mNotificationPulseCustomLedValues.get(mapPackage(packageName));
    }

    private int generateLedColorForPackageName(String packageName) {
        if (!mAutoGenerateNotificationColor) {
            return mDefaultNotificationColor;
        }
        if (!mMultiColorNotificationLed) {
            return mDefaultNotificationColor;
        }
        final String mapping = mapPackage(packageName);
        int color = mDefaultNotificationColor;

        if (mGeneratedPackageLedColors.containsKey(mapping)) {
            return mGeneratedPackageLedColors.get(mapping);
        }

        PackageManager pm = mContext.getPackageManager();
        Drawable icon;
        try {
            icon = pm.getApplicationIcon(mapping);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, e.getMessage(), e);
            return color;
        }

        color = ColorUtils.generateAlertColorFromDrawable(icon);
        mGeneratedPackageLedColors.put(mapping, color);

        return color;
    }

    private String mapPackage(String pkg) {
        if (!mPackageNameMappings.containsKey(pkg)) {
            return pkg;
        }
        return mPackageNameMappings.get(pkg);
    }

    public boolean isForcedOn(Notification n) {
        if (n.extras != null) {
            return n.extras.getBoolean(Notification.EXTRA_FORCE_SHOW_LIGHTS, false);
        }
        return false;
    }

    // Called by NotificationManagerService updateLightsLocked().
    // Takes the lights values as requested by a notification and
    // updates them according to the active Lineage feature settings.
    public void calcLights(LedValues ledValues, String packageName, Notification n,
            boolean screenOn, boolean inCall, boolean isDefaultLights, int suppressedEffects) {
        final boolean forcedOn = isForcedOn(n);
        final boolean suppressScreenOff =
                (suppressedEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0;
        final boolean suppressScreenOn =
                (suppressedEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0;
        final boolean screenActive = screenOn || inCall;
        final boolean enableLed;

        if (DEBUG) {
            Slog.i(TAG, "calcLights input: "
                    + " ledValues={ " + ledValues + " }"
                    + " packageName=" + packageName
                    + " forcedOn=" + forcedOn
                    + " screenOn=" + screenOn
                    + " inCall=" + inCall
                    + " isDefaultLights=" + isDefaultLights
                    + " suppressedEffects=" + suppressedEffects);
        }

        if (forcedOn) {
            // Forced on always enables
            enableLed = true;
        } else if (screenActive && (!mScreenOnEnabled || suppressScreenOn)) {
            // Screen on cases where we disable
            enableLed = false;
        } else if (!screenActive && suppressScreenOff) {
            // Screen off cases where we disable
            enableLed = false;
        } else {
            // Enable by default fallthrough
            enableLed = true;
        }
        if (!enableLed) {
            ledValues.setEnabled(false);
            return;
        }

        final LedValues ledValuesPkg = getLedValuesForPackageName(packageName);

        // Use package specific values that the user has chosen.
        if (ledValuesPkg != null) {
            ledValues.setColor(ledValuesPkg.getColor() != 0 ?
                    ledValuesPkg.getColor() : generateLedColorForPackageName(packageName));
            ledValues.setOnMs(ledValuesPkg.getOnMs() >= 0 ?
                    ledValuesPkg.getOnMs() : mDefaultNotificationLedOn);
            ledValues.setOffMs(ledValuesPkg.getOffMs() >= 0 ?
                    ledValuesPkg.getOffMs() : mDefaultNotificationLedOff);
        } else if (isDefaultLights) {
            ledValues.setColor(generateLedColorForPackageName(packageName));
            ledValues.setOnMs(mDefaultNotificationLedOn);
            ledValues.setOffMs(mDefaultNotificationLedOff);
        }
        if (DEBUG) {
            Slog.i(TAG, "calcLights output: ledValues={ " + ledValues + " }");
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_ON),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_OFF),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_VALUES),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_SCREEN_ON),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_LIGHT_COLOR_AUTO), false,
                    this, UserHandle.USER_ALL);
            if (mAdjustableNotificationLedBrightness) {
                resolver.registerContentObserver(Settings.System.getUriFor(
                        Settings.System.NOTIFICATION_LIGHT_BRIGHTNESS_LEVEL),
                        false, this, UserHandle.USER_ALL);
            }

            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        private void update() {
            ContentResolver resolver = mContext.getContentResolver();
            Resources res = mContext.getResources();

            // Automatically pick a color for LED if not set
            mAutoGenerateNotificationColor = Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_COLOR_AUTO,
                    0, UserHandle.USER_CURRENT) != 0;

            // LED default color
            mDefaultNotificationColor = Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_COLOR,
                    mDefaultNotificationColor, UserHandle.USER_CURRENT);

            // LED default on MS
            mDefaultNotificationLedOn = Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_ON,
                    mDefaultNotificationLedOn, UserHandle.USER_CURRENT);

            // LED default off MS
            mDefaultNotificationLedOff = Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_OFF,
                    mDefaultNotificationLedOff, UserHandle.USER_CURRENT);

            // LED generated notification colors
            mGeneratedPackageLedColors.clear();

            // LED custom notification colors
            mNotificationPulseCustomLedValues.clear();
            if (Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE, 0,
                    UserHandle.USER_CURRENT) != 0) {
                parseNotificationPulseCustomValuesString(Settings.System.getStringForUser(
                        resolver, Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_VALUES,
                        UserHandle.USER_CURRENT));
            }

            // Notification lights with screen on
            mScreenOnEnabled = (Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_SCREEN_ON, 0,
                    UserHandle.USER_CURRENT) != 0);

            mLedUpdater.update();
        }
    }
}
