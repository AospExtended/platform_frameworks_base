package com.google.android.systemui.power;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Log;
import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.power.EnhancedEstimates;
import java.time.Duration;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class EnhancedEstimatesGoogleImpl implements EnhancedEstimates {

    private Context mContext;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    @Inject
    public EnhancedEstimatesGoogleImpl(Context context) {
        mContext = context;
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        try {
            if (!mContext.getPackageManager().getPackageInfo("com.google.android.apps.turbo", PackageManager.MATCH_DISABLED_COMPONENTS).applicationInfo.enabled) {
                return false;
            }
            updateFlags();
            return mParser.getBoolean("hybrid_enabled", true);
        } catch (PackageManager.NameNotFoundException unused) {
            return false;
        }
    }

    @Override
    public Estimate getEstimate() {
        try {
            Cursor query = mContext.getContentResolver().query(new Uri.Builder().scheme("content").authority("com.google.android.apps.turbo.estimated_time_remaining").appendPath("time_remaining").build(), null, null, null, null);
            if (query != null) {
                if (query.moveToFirst()) {
                    boolean z = true;
                    if (query.getColumnIndex("is_based_on_usage") != -1) {
                        if (query.getInt(query.getColumnIndex("is_based_on_usage")) == 0) {
                            z = false;
                        }
                    }
                    int columnIndex = query.getColumnIndex("average_battery_life");
                    long j = -1;
                    if (columnIndex != -1) {
                        long j2 = query.getLong(columnIndex);
                        if (j2 != -1) {
                            long millis = Duration.ofMinutes(15).toMillis();
                            if (Duration.ofMillis(j2).compareTo(Duration.ofDays(1)) >= 0) {
                                millis = Duration.ofHours(1).toMillis();
                            }
                            j = PowerUtil.roundTimeToNearestThreshold(j2, millis);
                        }
                    }
                    Estimate estimate = new Estimate(query.getLong(query.getColumnIndex("battery_estimate")), z, j);
                    if (query != null) {
                        query.close();
                    }
                    return estimate;
                }
            }
            if (query != null) {
                query.close();
            }
        } catch (Exception e) {
            Log.d("EnhancedEstimates", "Something went wrong when getting an estimate from Turbo", e);
        }
        return new Estimate(-1, false, -1);
    }

    @Override
    public long getLowWarningThreshold() {
        updateFlags();
        return mParser.getLong("low_threshold", Duration.ofHours(3).toMillis());
    }

    @Override
    public long getSevereWarningThreshold() {
        updateFlags();
        return mParser.getLong("severe_threshold", Duration.ofHours(1).toMillis());
    }

    @Override
    public boolean getLowWarningEnabled() {
        updateFlags();
        return mParser.getBoolean("low_warning_enabled", false);
    }

    private void updateFlags() {
        try {
            mParser.setString(Settings.Global.getString(mContext.getContentResolver(), "hybrid_sysui_battery_warning_flags"));
        } catch (IllegalArgumentException unused) {
            Log.e("EnhancedEstimates", "Bad hybrid sysui warning flags");
        }
    }
}
