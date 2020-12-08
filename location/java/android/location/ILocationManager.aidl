/*
 * Copyright (C) 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

import android.app.PendingIntent;
import android.location.Address;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.GnssMeasurementCorrections;
import android.location.GnssRequest;
import android.location.IBatchedLocationCallback;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssStatusListener;
import android.location.IGnssNavigationMessageListener;
import android.location.ILocationListener;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.Bundle;
import android.os.ICancellationSignal;

import com.android.internal.location.ProviderProperties;

/**
 * System private API for talking with the location service.
 *
 * @hide
 */
interface ILocationManager
{
    Location getLastLocation(in LocationRequest request, String packageName, String featureId);
    @nullable ICancellationSignal getCurrentLocation(in LocationRequest request,
            in ILocationListener listener, String packageName, String featureId,
            String listenerId);

    void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            in PendingIntent intent, String packageName, String featureId, String listenerId);
    void removeUpdates(in ILocationListener listener, in PendingIntent intent);

    void requestGeofence(in LocationRequest request, in Geofence geofence,
            in PendingIntent intent, String packageName, String featureId);
    void removeGeofence(in Geofence fence, in PendingIntent intent, String packageName);

    boolean registerGnssStatusCallback(IGnssStatusListener callback, String packageName,
            String featureId);
    void unregisterGnssStatusCallback(IGnssStatusListener callback);

    boolean geocoderIsPresent();
    String getFromLocation(double latitude, double longitude, int maxResults,
        in GeocoderParams params, out List<Address> addrs);
    String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        in GeocoderParams params, out List<Address> addrs);

    boolean addGnssMeasurementsListener(in GnssRequest request,
            in IGnssMeasurementsListener listener,
            String packageName, String featureId);
    void injectGnssMeasurementCorrections(in GnssMeasurementCorrections corrections,
            in String packageName);
    long getGnssCapabilities();
    void removeGnssMeasurementsListener(in IGnssMeasurementsListener listener);

    boolean addGnssAntennaInfoListener(in IGnssAntennaInfoListener listener,
             String packageName, String featureId);
    void removeGnssAntennaInfoListener(in IGnssAntennaInfoListener listener);

    boolean addGnssNavigationMessageListener(in IGnssNavigationMessageListener listener,
             String packageName, String featureId);
    void removeGnssNavigationMessageListener(in IGnssNavigationMessageListener listener);

    int getGnssYearOfHardware();
    String getGnssHardwareModelName();

    int getGnssBatchSize(String packageName);
    boolean addGnssBatchingCallback(in IBatchedLocationCallback callback, String packageName, String featureId);
    void removeGnssBatchingCallback();
    boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName, String featureId);
    void flushGnssBatch(String packageName);
    boolean stopGnssBatch();
    void injectLocation(in Location location);

    List<String> getAllProviders();
    List<String> getProviders(in Criteria criteria, boolean enabledOnly);
    String getBestProvider(in Criteria criteria, boolean enabledOnly);
    ProviderProperties getProviderProperties(String provider);
    boolean isProviderPackage(String packageName);
    List<String> getProviderPackages(String provider);

    void setExtraLocationControllerPackage(String packageName);
    String getExtraLocationControllerPackage();
    void setExtraLocationControllerPackageEnabled(boolean enabled);
    boolean isExtraLocationControllerPackageEnabled();

    boolean isProviderEnabledForUser(String provider, int userId);
    boolean isLocationEnabledForUser(int userId);
    void setLocationEnabledForUser(boolean enabled, int userId);
    void addTestProvider(String name, in ProviderProperties properties, String packageName, String featureId);
    void removeTestProvider(String provider, String packageName, String featureId);
    void setTestProviderLocation(String provider, in Location location, String packageName, String featureId);
    void setTestProviderEnabled(String provider, boolean enabled, String packageName, String featureId);
    List<LocationRequest> getTestProviderCurrentRequests(String provider);
    LocationTime getGnssTimeMillis();

    boolean sendExtraCommand(String provider, String command, inout Bundle extras);

    // --- internal ---

    // for reporting callback completion
    void locationCallbackFinished(ILocationListener listener);

    // used by gts tests to verify whitelists
    String[] getBackgroundThrottlingWhitelist();
    String[] getIgnoreSettingsWhitelist();
}
