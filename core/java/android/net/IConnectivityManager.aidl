/**
 * Copyright (c) 2008, The Android Open Source Project
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

package android.net;

import android.app.PendingIntent;
import android.net.ConnectionInfo;
import android.net.ConnectivityDiagnosticsManager;
import android.net.IConnectivityDiagnosticsCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.ISocketKeepaliveCallback;
import android.net.ProxyInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.ResultReceiver;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.net.VpnProfile;

/**
 * Interface that answers queries about, and allows changing, the
 * state of network connectivity.
 */
/** {@hide} */
interface IConnectivityManager
{
    Network getActiveNetwork();
    Network getActiveNetworkForUid(int uid, boolean ignoreBlocked);
    @UnsupportedAppUsage
    NetworkInfo getActiveNetworkInfo();
    NetworkInfo getActiveNetworkInfoForUid(int uid, boolean ignoreBlocked);
    @UnsupportedAppUsage(maxTargetSdk = 28)
    NetworkInfo getNetworkInfo(int networkType);
    NetworkInfo getNetworkInfoForUid(in Network network, int uid, boolean ignoreBlocked);
    @UnsupportedAppUsage
    NetworkInfo[] getAllNetworkInfo();
    Network getNetworkForType(int networkType);
    Network[] getAllNetworks();
    NetworkCapabilities[] getDefaultNetworkCapabilitiesForUser(
            int userId, String callingPackageName);

    boolean isNetworkSupported(int networkType);

    @UnsupportedAppUsage
    LinkProperties getActiveLinkProperties();
    LinkProperties getLinkPropertiesForType(int networkType);
    LinkProperties getLinkProperties(in Network network);

    NetworkCapabilities getNetworkCapabilities(in Network network, String callingPackageName);

    @UnsupportedAppUsage
    NetworkState[] getAllNetworkState();

    NetworkQuotaInfo getActiveNetworkQuotaInfo();
    boolean isActiveNetworkMetered();

    boolean requestRouteToHostAddress(int networkType, in byte[] hostAddress);

    @UnsupportedAppUsage(maxTargetSdk = 29,
            publicAlternatives = "Use {@code TetheringManager#getLastTetherError} as alternative")
    int getLastTetherError(String iface);

    @UnsupportedAppUsage(maxTargetSdk = 29,
            publicAlternatives = "Use {@code TetheringManager#getTetherableIfaces} as alternative")
    String[] getTetherableIfaces();

    @UnsupportedAppUsage(maxTargetSdk = 29,
            publicAlternatives = "Use {@code TetheringManager#getTetheredIfaces} as alternative")
    String[] getTetheredIfaces();

    @UnsupportedAppUsage(maxTargetSdk = 29,
            publicAlternatives = "Use {@code TetheringManager#getTetheringErroredIfaces} "
            + "as Alternative")
    String[] getTetheringErroredIfaces();

    @UnsupportedAppUsage(maxTargetSdk = 29,
            publicAlternatives = "Use {@code TetheringManager#getTetherableUsbRegexs} as alternative")
    String[] getTetherableUsbRegexs();

    @UnsupportedAppUsage(maxTargetSdk = 29,
            publicAlternatives = "Use {@code TetheringManager#getTetherableWifiRegexs} as alternative")
    String[] getTetherableWifiRegexs();

    @UnsupportedAppUsage(maxTargetSdk = 28)
    void reportInetCondition(int networkType, int percentage);

    void reportNetworkConnectivity(in Network network, boolean hasConnectivity);

    ProxyInfo getGlobalProxy();

    void setGlobalProxy(in ProxyInfo p);

    ProxyInfo getProxyForNetwork(in Network nework);

    boolean prepareVpn(String oldPackage, String newPackage, int userId);

    void setVpnPackageAuthorization(String packageName, int userId, int vpnType);

    ParcelFileDescriptor establishVpn(in VpnConfig config);

    boolean provisionVpnProfile(in VpnProfile profile, String packageName);

    void deleteVpnProfile(String packageName);

    void startVpnProfile(String packageName);

    void stopVpnProfile(String packageName);

    VpnConfig getVpnConfig(int userId);

    @UnsupportedAppUsage
    void startLegacyVpn(in VpnProfile profile);

    LegacyVpnInfo getLegacyVpnInfo(int userId);

    boolean updateLockdownVpn();
    boolean isAlwaysOnVpnPackageSupported(int userId, String packageName);
    boolean setAlwaysOnVpnPackage(int userId, String packageName, boolean lockdown,
            in List<String> lockdownWhitelist);
    String getAlwaysOnVpnPackage(int userId);
    boolean isVpnLockdownEnabled(int userId);
    List<String> getVpnLockdownWhitelist(int userId);

    int checkMobileProvisioning(int suggestedTimeOutMs);

    String getMobileProvisioningUrl();

    void setProvisioningNotificationVisible(boolean visible, int networkType, in String action);

    void setAirplaneMode(boolean enable);

    boolean requestBandwidthUpdate(in Network network);

    int registerNetworkFactory(in Messenger messenger, in String name);
    void unregisterNetworkFactory(in Messenger messenger);

    int registerNetworkProvider(in Messenger messenger, in String name);
    void unregisterNetworkProvider(in Messenger messenger);

    void declareNetworkRequestUnfulfillable(in NetworkRequest request);

    Network registerNetworkAgent(in Messenger messenger, in NetworkInfo ni, in LinkProperties lp,
            in NetworkCapabilities nc, int score, in NetworkAgentConfig config,
            in int factorySerialNumber);

    NetworkRequest requestNetwork(in NetworkCapabilities networkCapabilities,
            in Messenger messenger, int timeoutSec, in IBinder binder, int legacy,
            String callingPackageName);

    NetworkRequest pendingRequestForNetwork(in NetworkCapabilities networkCapabilities,
            in PendingIntent operation, String callingPackageName);

    void releasePendingNetworkRequest(in PendingIntent operation);

    NetworkRequest listenForNetwork(in NetworkCapabilities networkCapabilities,
            in Messenger messenger, in IBinder binder, String callingPackageName);

    void pendingListenForNetwork(in NetworkCapabilities networkCapabilities,
            in PendingIntent operation, String callingPackageName);

    void releaseNetworkRequest(in NetworkRequest networkRequest);

    void setAcceptUnvalidated(in Network network, boolean accept, boolean always);
    void setAcceptPartialConnectivity(in Network network, boolean accept, boolean always);
    void setAvoidUnvalidated(in Network network);
    void startCaptivePortalApp(in Network network);
    void startCaptivePortalAppInternal(in Network network, in Bundle appExtras);

    boolean shouldAvoidBadWifi();
    int getMultipathPreference(in Network Network);

    NetworkRequest getDefaultRequest();

    int getRestoreDefaultNetworkDelay(int networkType);

    boolean addVpnAddress(String address, int prefixLength);
    boolean removeVpnAddress(String address, int prefixLength);
    boolean setUnderlyingNetworksForVpn(in Network[] networks);

    void factoryReset();

    void startNattKeepalive(in Network network, int intervalSeconds,
            in ISocketKeepaliveCallback cb, String srcAddr, int srcPort, String dstAddr);

    void startNattKeepaliveWithFd(in Network network, in FileDescriptor fd, int resourceId,
            int intervalSeconds, in ISocketKeepaliveCallback cb, String srcAddr,
            String dstAddr);

    void startTcpKeepalive(in Network network, in FileDescriptor fd, int intervalSeconds,
            in ISocketKeepaliveCallback cb);

    void stopKeepalive(in Network network, int slot);

    String getCaptivePortalServerUrl();

    byte[] getNetworkWatchlistConfigHash();

    int getConnectionOwnerUid(in ConnectionInfo connectionInfo);
    boolean isCallerCurrentAlwaysOnVpnApp();
    boolean isCallerCurrentAlwaysOnVpnLockdownApp();

    void registerConnectivityDiagnosticsCallback(in IConnectivityDiagnosticsCallback callback,
            in NetworkRequest request, String callingPackageName);
    void unregisterConnectivityDiagnosticsCallback(in IConnectivityDiagnosticsCallback callback);

    IBinder startOrGetTestNetworkService();

    void simulateDataStall(int detectionMethod, long timestampMillis, in Network network,
                in PersistableBundle extras);

    // Lineage custom API
    VpnProfile[] getAllLegacyVpns();
}
