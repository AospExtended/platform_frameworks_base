package com.android.systemui.power;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.android.systemui.power.Estimate;

public class EnhancedEstimatesTurboImpl implements EnhancedEstimates {

    private Context mContext;

    public EnhancedEstimatesTurboImpl(Context context) {
        mContext = context;
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        try {
            boolean turboEnabled = mContext.getPackageManager().getPackageInfo("com.google.android.apps.turbo",
                                    PackageManager.MATCH_DISABLED_COMPONENTS).applicationInfo.enabled;
            return turboEnabled;
        }
        catch (PackageManager.NameNotFoundException nameNotFoundException) {
            return false;
        }
    }

    private Uri getEnhancedBatteryPredictionUri() {
        return new Uri.Builder().scheme("content")
                                .authority("com.google.android.apps.turbo.estimated_time_remaining")
                                .appendPath("time_remaining").build();
    }

    @Override
    public Estimate getEstimate() {
        long dischargeTime = -1L;
        boolean basedOnUsage = false;
        Uri uri = this.getEnhancedBatteryPredictionUri();
        Cursor cursor = mContext.getContentResolver().query(uri, null, null, null, null);

        // Return null if cursor is null or empty
        if (cursor == null || !cursor.moveToFirst()) {
            try {
                cursor.close();
            }
            catch (NullPointerException nullPointerException) {
                 // cursor might be null
            }
            return null;
        }

        // Check if estimate is usage based
        int colIndex = cursor.getColumnIndex("is_based_on_usage");
        if (colIndex != -1)
            basedOnUsage = cursor.getInt(colIndex) == 1;

        // Create estimate from data
        colIndex = cursor.getColumnIndex("battery_estimate");
        Estimate enhancedEstimate = new Estimate(cursor.getLong(colIndex), basedOnUsage);

        // Cleanup
        try {
            cursor.close();
        }
        catch (NullPointerException nullPointerException) {
            // We already checked if cursor is null, so it shouldn't be dereferenced yet.
        }

        return enhancedEstimate;
    }

    @Override
    public long getLowWarningThreshold() {
        return 0;
    }

    @Override
    public long getSevereWarningThreshold() {
        return 0;
    }
}
