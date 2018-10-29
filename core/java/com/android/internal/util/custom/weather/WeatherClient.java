/*
 * Copyright (C) 2018 The OmniROM Project
 *                    The PixelExperience Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.internal.util.custom.weather;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.R;

public class WeatherClient {

    public static final String SERVICE_PACKAGE = "org.pixelexperience.weather.client";
    public static final Uri WEATHER_URI = Uri.parse("content://org.pixelexperience.weather.client.provider/weather");
    private static final String TAG = "WeatherClient";
    private static final boolean DEBUG = true;

    public static final int WEATHER_UPDATE_SUCCESS = 0; // Success
    public static final int WEATHER_UPDATE_RUNNING = 1; // Update running
    public static final int WEATHER_UPDATE_NO_DATA = 2; // On boot event
    public static final int WEATHER_UPDATE_ERROR = 3; // Error

    private static final String COLUMN_STATUS = "status";
    private static final String COLUMN_CONDITIONS = "conditions";
    private static final String COLUMN_TEMPERATURE_METRIC = "temperatureMetric";
    private static final String COLUMN_TEMPERATURE_IMPERIAL = "temperatureImperial";
    private static final String[] PROJECTION_DEFAULT_WEATHER = new String[]{
            COLUMN_STATUS,
            COLUMN_CONDITIONS,
            COLUMN_TEMPERATURE_METRIC,
            COLUMN_TEMPERATURE_IMPERIAL
    };

    private Context mContext;
    private List<WeatherObserver> mObserver;

    public WeatherClient(Context context) {
        mContext = context;
        mObserver = new ArrayList<>();
        new WeatherContentObserver(new Handler()).observe();
    }

    public static boolean isAvailable(Context context) {
        final PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(SERVICE_PACKAGE, PackageManager.GET_ACTIVITIES);
            int enabled = pm.getApplicationEnabledSetting(SERVICE_PACKAGE);
            return enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                    enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public WeatherInfo getWeatherData() {
        if (!isAvailable(mContext)) {
            return null;
        }
        WeatherInfo weatherInfo = new WeatherInfo();
        Cursor c = mContext.getContentResolver().query(WEATHER_URI, PROJECTION_DEFAULT_WEATHER,
                null, null, null);
        if (c != null) {
            try {
                int count = c.getCount();
                if (count > 0) {
                    for (int i = 0; i < count; i++) {
                        c.moveToPosition(i);
                        if (i == 0) {
                            weatherInfo.status = c.getInt(0);
                            weatherInfo.conditions = c.getString(1);
                            weatherInfo.temperatureMetric = c.getInt(2);
                            weatherInfo.temperatureImperial = c.getInt(3);
                        }
                    }
                }
            } finally {
                c.close();
            }
        }
        if (DEBUG) Log.d(TAG, weatherInfo.toString());
        return weatherInfo;
    }

    public void addObserver(WeatherObserver observer) {
        mObserver.add(observer);
    }

    public void removeObserver(WeatherObserver observer) {
        mObserver.remove(observer);
    }

    public interface WeatherObserver {
        void onWeatherUpdated(WeatherInfo info);
    }

    private class WeatherContentObserver extends ContentObserver {
        WeatherContentObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(WEATHER_URI, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            WeatherInfo info = getWeatherData();
            for (WeatherObserver observer : mObserver) {
                observer.onWeatherUpdated(info);
            }
        }
    }

    public class WeatherInfo {

        int status = WEATHER_UPDATE_ERROR;
        String conditions = "";
        int temperatureMetric = 0;
        int temperatureImperial = 0;

        public WeatherInfo() {
        }

        public int getTemperature(boolean metric) {
            return metric ? this.temperatureMetric : this.temperatureImperial;
        }

        public int getStatus() {
            return this.status;
        }

        public String getConditions() {
            return this.conditions;
        }

        public int getWeatherConditionImage(){
            boolean isDay = getConditions().contains("d");
            if (getConditions().contains(WeatherClient.Conditions.CONDITION_STORMY)){
                return R.drawable.weather_11;
            }else if (getConditions().contains(WeatherClient.Conditions.CONDITION_SNOWY) && conditions.contains(WeatherClient.Conditions.CONDITION_ICY)){
                return R.drawable.weather_13;
            }else if (getConditions().contains(WeatherClient.Conditions.CONDITION_RAINY)){
                return R.drawable.weather_09;
            }else if (getConditions().contains(WeatherClient.Conditions.CONDITION_FOGGY) && conditions.contains(WeatherClient.Conditions.CONDITION_HAZY)){
                return R.drawable.weather_50;
            }else if (getConditions().contains(WeatherClient.Conditions.CONDITION_CLOUDY) && conditions.contains(WeatherClient.Conditions.CONDITION_CLEAR)){
                return isDay ? R.drawable.weather_02 : R.drawable.weather_02n;
            }else if (getConditions().contains(WeatherClient.Conditions.CONDITION_CLOUDY)){
                return isDay ? R.drawable.weather_03 : R.drawable.weather_03n;
            }else if (getConditions().contains(WeatherClient.Conditions.CONDITION_CLEAR)){
                return isDay ? R.drawable.weather_01 : R.drawable.weather_01n;
            }else{
                return isDay ? R.drawable.weather_04 : R.drawable.weather_04n; // Default
            }
        }

        @Override
        public String toString() {
            return "WeatherInfo: " +
                    "status=" + getStatus() + "," +
                    "conditions=" + getConditions() + "," +
                    "temperatureMetric=" + getTemperature(true) + "," +
                    "temperatureImperial=" + getTemperature(false);
        }
    }

    public class Conditions {
        public static final String CONDITION_UNKNOWN = "0";
        public static final String CONDITION_CLEAR = "1";
        public static final String CONDITION_CLOUDY = "2";
        public static final String CONDITION_FOGGY = "3";
        public static final String CONDITION_HAZY = "4";
        public static final String CONDITION_ICY = "5";
        public static final String CONDITION_RAINY = "6";
        public static final String CONDITION_SNOWY = "7";
        public static final String CONDITION_STORMY = "8";
        public static final String CONDITION_WINDY = "9";
    }
}
