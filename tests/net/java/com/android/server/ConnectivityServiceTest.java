/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.net.ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_FOTA;
import static android.net.ConnectivityManager.TYPE_MOBILE_MMS;
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.TYPE_VPN;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_DNS;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_FALLBACK;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTP;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTPS;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_PARTIAL;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_VALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_EIMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOTA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_RCS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_SUPL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P;
import static android.net.NetworkCapabilities.NET_CAPABILITY_XCAP;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;
import static android.net.NetworkPolicyManager.RULE_ALLOW_METERED;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.RULE_REJECT_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.RouteInfo.RTN_UNREACHABLE;

import static com.android.internal.util.TestUtils.waitForIdleHandler;
import static com.android.internal.util.TestUtils.waitForIdleLooper;
import static com.android.internal.util.TestUtils.waitForIdleSerialExecutor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityManager.PacketKeepalive;
import android.net.ConnectivityManager.PacketKeepaliveCallback;
import android.net.ConnectivityManager.TooManyRequestsException;
import android.net.ConnectivityThread;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.IpSecManager;
import android.net.IpSecManager.UdpEncapsulationSocket;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStackClient;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.ResolverParamsParcel;
import android.net.RouteInfo;
import android.net.SocketKeepalive;
import android.net.UidRange;
import android.net.metrics.IpConnectivityLog;
import android.net.shared.NetworkMonitorUtils;
import android.net.shared.PrivateDnsConfig;
import android.net.util.MultinetworkPolicyTracker;
import android.os.Binder;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.system.Os;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.WakeupMessage;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.connectivity.ConnectivityConstants;
import com.android.server.connectivity.DefaultNetworkMetrics;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.net.NetworkPinner;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkStatsFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Tests for {@link ConnectivityService}.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.ConnectivityServiceTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConnectivityServiceTest {
    private static final String TAG = "ConnectivityServiceTest";

    private static final int TIMEOUT_MS = 500;
    private static final int TEST_LINGER_DELAY_MS = 250;
    // Chosen to be less than the linger timeout. This ensures that we can distinguish between a
    // LOST callback that arrives immediately and a LOST callback that arrives after the linger
    // timeout. For this, our assertions should run fast enough to leave less than
    // (mService.mLingerDelayMs - TEST_CALLBACK_TIMEOUT_MS) between the time callbacks are
    // supposedly fired, and the time we call expectCallback.
    private final static int TEST_CALLBACK_TIMEOUT_MS = 200;
    // Chosen to be less than TEST_CALLBACK_TIMEOUT_MS. This ensures that requests have time to
    // complete before callbacks are verified.
    private final static int TEST_REQUEST_TIMEOUT_MS = 150;

    private static final String CLAT_PREFIX = "v4-";
    private static final String MOBILE_IFNAME = "test_rmnet_data0";
    private static final String WIFI_IFNAME = "test_wlan0";
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private MockContext mServiceContext;
    private WrappedConnectivityService mService;
    private WrappedConnectivityManager mCm;
    private MockNetworkAgent mWiFiNetworkAgent;
    private MockNetworkAgent mCellNetworkAgent;
    private MockNetworkAgent mEthernetNetworkAgent;
    private MockVpn mMockVpn;
    private Context mContext;
    private INetworkPolicyListener mPolicyListener;

    @Mock IpConnectivityMetrics.Logger mMetricsService;
    @Mock DefaultNetworkMetrics mDefaultNetworkMetrics;
    @Mock INetworkManagementService mNetworkManagementService;
    @Mock INetworkStatsService mStatsService;
    @Mock INetworkPolicyManager mNpm;
    @Mock IDnsResolver mMockDnsResolver;
    @Mock INetd mMockNetd;
    @Mock NetworkStackClient mNetworkStack;
    @Mock PackageManager mPackageManager;
    @Mock UserManager mUserManager;
    @Mock NotificationManager mNotificationManager;

    private ArgumentCaptor<ResolverParamsParcel> mResolverParamsParcelCaptor =
            ArgumentCaptor.forClass(ResolverParamsParcel.class);

    // This class exists to test bindProcessToNetwork and getBoundNetworkForProcess. These methods
    // do not go through ConnectivityService but talk to netd directly, so they don't automatically
    // reflect the state of our test ConnectivityService.
    private class WrappedConnectivityManager extends ConnectivityManager {
        private Network mFakeBoundNetwork;

        public synchronized boolean bindProcessToNetwork(Network network) {
            mFakeBoundNetwork = network;
            return true;
        }

        public synchronized Network getBoundNetworkForProcess() {
            return mFakeBoundNetwork;
        }

        public WrappedConnectivityManager(Context context, ConnectivityService service) {
            super(context, service);
        }
    }

    private class MockContext extends BroadcastInterceptingContext {
        private final MockContentResolver mContentResolver;

        @Spy private Resources mResources;
        private final LinkedBlockingQueue<Intent> mStartedActivities = new LinkedBlockingQueue<>();

        MockContext(Context base, ContentProvider settingsProvider) {
            super(base);

            mResources = spy(base.getResources());
            when(mResources.getStringArray(com.android.internal.R.array.networkAttributes)).
                    thenReturn(new String[] {
                            "wifi,1,1,1,-1,true",
                            "mobile,0,0,0,-1,true",
                            "mobile_mms,2,0,2,60000,true",
                    });

            mContentResolver = new MockContentResolver();
            mContentResolver.addProvider(Settings.AUTHORITY, settingsProvider);
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle handle) {
            mStartedActivities.offer(intent);
        }

        public Intent expectStartActivityIntent(int timeoutMs) {
            Intent intent = null;
            try {
                intent = mStartedActivities.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
            assertNotNull("Did not receive sign-in intent after " + timeoutMs + "ms", intent);
            return intent;
        }

        public void expectNoStartActivityIntent(int timeoutMs) {
            try {
                assertNull("Received unexpected Intent to start activity",
                        mStartedActivities.poll(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {}
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return mCm;
            if (Context.NOTIFICATION_SERVICE.equals(name)) return mNotificationManager;
            if (Context.NETWORK_STACK_SERVICE.equals(name)) return mNetworkStack;
            if (Context.USER_SERVICE.equals(name)) return mUserManager;
            return super.getSystemService(name);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            // The mainline permission can only be held if signed with the network stack certificate
            // Skip testing for this permission.
            if (NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK.equals(permission)) return;
            // All other permissions should be held by the test or unnecessary: check as normal to
            // make sure the code does not rely on unexpected permissions.
            super.enforceCallingOrSelfPermission(permission, message);
        }
    }

    public void waitForIdle(int timeoutMsAsInt) {
        long timeoutMs = timeoutMsAsInt;
        waitForIdleHandler(mService.mHandlerThread, timeoutMs);
        waitForIdle(mCellNetworkAgent, timeoutMs);
        waitForIdle(mWiFiNetworkAgent, timeoutMs);
        waitForIdle(mEthernetNetworkAgent, timeoutMs);
        waitForIdleHandler(mService.mHandlerThread, timeoutMs);
        waitForIdleLooper(ConnectivityThread.getInstanceLooper(), timeoutMs);
    }

    public void waitForIdle(MockNetworkAgent agent, long timeoutMs) {
        if (agent == null) {
            return;
        }
        waitForIdleHandler(agent.mHandlerThread, timeoutMs);
    }

    private void waitForIdle() {
        waitForIdle(TIMEOUT_MS);
    }

    @Test
    public void testWaitForIdle() {
        final int attempts = 50;  // Causes the test to take about 200ms on bullhead-eng.

        // Tests that waitForIdle returns immediately if the service is already idle.
        for (int i = 0; i < attempts; i++) {
            waitForIdle();
        }

        // Bring up a network that we can use to send messages to ConnectivityService.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        Network n = mWiFiNetworkAgent.getNetwork();
        assertNotNull(n);

        // Tests that calling waitForIdle waits for messages to be processed.
        for (int i = 0; i < attempts; i++) {
            mWiFiNetworkAgent.setSignalStrength(i);
            waitForIdle();
            assertEquals(i, mCm.getNetworkCapabilities(n).getSignalStrength());
        }
    }

    // This test has an inherent race condition in it, and cannot be enabled for continuous testing
    // or presubmit tests. It is kept for manual runs and documentation purposes.
    @Ignore
    public void verifyThatNotWaitingForIdleCausesRaceConditions() {
        // Bring up a network that we can use to send messages to ConnectivityService.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        Network n = mWiFiNetworkAgent.getNetwork();
        assertNotNull(n);

        // Ensure that not calling waitForIdle causes a race condition.
        final int attempts = 50;  // Causes the test to take about 200ms on bullhead-eng.
        for (int i = 0; i < attempts; i++) {
            mWiFiNetworkAgent.setSignalStrength(i);
            if (i != mCm.getNetworkCapabilities(n).getSignalStrength()) {
                // We hit a race condition, as expected. Pass the test.
                return;
            }
        }

        // No race? There is a bug in this test.
        fail("expected race condition at least once in " + attempts + " attempts");
    }

    private class MockNetworkAgent {
        private static final int VALIDATION_RESULT_BASE = NETWORK_VALIDATION_PROBE_DNS
                | NETWORK_VALIDATION_PROBE_HTTP
                | NETWORK_VALIDATION_PROBE_HTTPS;
        private static final int VALIDATION_RESULT_VALID = VALIDATION_RESULT_BASE
                | NETWORK_VALIDATION_RESULT_VALID;
        private static final int VALIDATION_RESULT_PARTIAL = VALIDATION_RESULT_BASE
                | NETWORK_VALIDATION_PROBE_FALLBACK
                | NETWORK_VALIDATION_RESULT_PARTIAL;
        private static final int VALIDATION_RESULT_INVALID = 0;

        private final INetworkMonitor mNetworkMonitor;
        private final NetworkInfo mNetworkInfo;
        private final NetworkCapabilities mNetworkCapabilities;
        private final HandlerThread mHandlerThread;
        private final ConditionVariable mDisconnected = new ConditionVariable();
        private final ConditionVariable mNetworkStatusReceived = new ConditionVariable();
        private final ConditionVariable mPreventReconnectReceived = new ConditionVariable();
        private int mScore;
        private NetworkAgent mNetworkAgent;
        private int mStartKeepaliveError = SocketKeepalive.ERROR_UNSUPPORTED;
        private int mStopKeepaliveError = SocketKeepalive.NO_KEEPALIVE;
        private Integer mExpectedKeepaliveSlot = null;
        // Contains the redirectUrl from networkStatus(). Before reading, wait for
        // mNetworkStatusReceived.
        private String mRedirectUrl;

        private INetworkMonitorCallbacks mNmCallbacks;
        private int mNmValidationResult = VALIDATION_RESULT_BASE;
        private String mNmValidationRedirectUrl = null;
        private boolean mNmProvNotificationRequested = false;

        void setNetworkValid() {
            mNmValidationResult = VALIDATION_RESULT_VALID;
            mNmValidationRedirectUrl = null;
        }

        void setNetworkInvalid() {
            mNmValidationResult = VALIDATION_RESULT_INVALID;
            mNmValidationRedirectUrl = null;
        }

        void setNetworkPortal(String redirectUrl) {
            setNetworkInvalid();
            mNmValidationRedirectUrl = redirectUrl;
        }

        void setNetworkPartial() {
            mNmValidationResult = VALIDATION_RESULT_PARTIAL;
            mNmValidationRedirectUrl = null;
        }

        void setNetworkPartialValid() {
            mNmValidationResult = VALIDATION_RESULT_PARTIAL | VALIDATION_RESULT_VALID;
            mNmValidationRedirectUrl = null;
        }

        MockNetworkAgent(int transport) {
            this(transport, new LinkProperties());
        }

        MockNetworkAgent(int transport, LinkProperties linkProperties) {
            final int type = transportToLegacyType(transport);
            final String typeName = ConnectivityManager.getNetworkTypeName(type);
            mNetworkInfo = new NetworkInfo(type, 0, typeName, "Mock");
            mNetworkCapabilities = new NetworkCapabilities();
            mNetworkCapabilities.addTransportType(transport);
            switch (transport) {
                case TRANSPORT_ETHERNET:
                    mScore = 70;
                    break;
                case TRANSPORT_WIFI:
                    mScore = 60;
                    break;
                case TRANSPORT_CELLULAR:
                    mScore = 50;
                    break;
                case TRANSPORT_WIFI_AWARE:
                    mScore = 20;
                    break;
                case TRANSPORT_VPN:
                    mNetworkCapabilities.removeCapability(NET_CAPABILITY_NOT_VPN);
                    mScore = ConnectivityConstants.VPN_DEFAULT_SCORE;
                    break;
                default:
                    throw new UnsupportedOperationException("unimplemented network type");
            }
            mHandlerThread = new HandlerThread("Mock-" + typeName);
            mHandlerThread.start();

            mNetworkMonitor = mock(INetworkMonitor.class);
            final Answer validateAnswer = inv -> {
                new Thread(this::onValidationRequested).start();
                return null;
            };

            try {
                doAnswer(validateAnswer).when(mNetworkMonitor).notifyNetworkConnected(any(), any());
                doAnswer(validateAnswer).when(mNetworkMonitor).forceReevaluation(anyInt());
            } catch (RemoteException e) {
                fail(e.getMessage());
            }

            final ArgumentCaptor<Network> nmNetworkCaptor = ArgumentCaptor.forClass(Network.class);
            final ArgumentCaptor<INetworkMonitorCallbacks> nmCbCaptor =
                    ArgumentCaptor.forClass(INetworkMonitorCallbacks.class);
            doNothing().when(mNetworkStack).makeNetworkMonitor(
                    nmNetworkCaptor.capture(),
                    any() /* name */,
                    nmCbCaptor.capture());

            mNetworkAgent = new NetworkAgent(mHandlerThread.getLooper(), mServiceContext,
                    "Mock-" + typeName, mNetworkInfo, mNetworkCapabilities,
                    linkProperties, mScore, new NetworkMisc(), NetworkFactory.SerialNumber.NONE) {
                @Override
                public void unwanted() { mDisconnected.open(); }

                @Override
                public void startSocketKeepalive(Message msg) {
                    int slot = msg.arg1;
                    if (mExpectedKeepaliveSlot != null) {
                        assertEquals((int) mExpectedKeepaliveSlot, slot);
                    }
                    onSocketKeepaliveEvent(slot, mStartKeepaliveError);
                }

                @Override
                public void stopSocketKeepalive(Message msg) {
                    onSocketKeepaliveEvent(msg.arg1, mStopKeepaliveError);
                }

                @Override
                public void networkStatus(int status, String redirectUrl) {
                    mRedirectUrl = redirectUrl;
                    mNetworkStatusReceived.open();
                }

                @Override
                protected void preventAutomaticReconnect() {
                    mPreventReconnectReceived.open();
                }

                @Override
                protected void addKeepalivePacketFilter(Message msg) {
                    Log.i(TAG, "Add keepalive packet filter.");
                }

                @Override
                protected void removeKeepalivePacketFilter(Message msg) {
                    Log.i(TAG, "Remove keepalive packet filter.");
                }
            };

            assertEquals(mNetworkAgent.netId, nmNetworkCaptor.getValue().netId);
            mNmCallbacks = nmCbCaptor.getValue();

            try {
                mNmCallbacks.onNetworkMonitorCreated(mNetworkMonitor);
            } catch (RemoteException e) {
                fail(e.getMessage());
            }

            // Waits for the NetworkAgent to be registered, which includes the creation of the
            // NetworkMonitor.
            waitForIdle();
        }

        private void onValidationRequested() {
            try {
                if (mNmProvNotificationRequested
                        && ((mNmValidationResult & NETWORK_VALIDATION_RESULT_VALID) != 0)) {
                    mNmCallbacks.hideProvisioningNotification();
                    mNmProvNotificationRequested = false;
                }

                mNmCallbacks.notifyNetworkTested(
                        mNmValidationResult, mNmValidationRedirectUrl);

                if (mNmValidationRedirectUrl != null) {
                    mNmCallbacks.showProvisioningNotification(
                            "test_provisioning_notif_action", "com.android.test.package");
                    mNmProvNotificationRequested = true;
                }
            } catch (RemoteException e) {
                fail(e.getMessage());
            }
        }

        public void adjustScore(int change) {
            mScore += change;
            mNetworkAgent.sendNetworkScore(mScore);
        }

        public int getScore() {
            return mScore;
        }

        public void explicitlySelected(boolean acceptUnvalidated) {
            mNetworkAgent.explicitlySelected(acceptUnvalidated);
        }

        public void addCapability(int capability) {
            mNetworkCapabilities.addCapability(capability);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void removeCapability(int capability) {
            mNetworkCapabilities.removeCapability(capability);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void setUids(Set<UidRange> uids) {
            mNetworkCapabilities.setUids(uids);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void setSignalStrength(int signalStrength) {
            mNetworkCapabilities.setSignalStrength(signalStrength);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void setNetworkSpecifier(NetworkSpecifier networkSpecifier) {
            mNetworkCapabilities.setNetworkSpecifier(networkSpecifier);
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }

        public void setNetworkCapabilities(NetworkCapabilities nc,
                boolean sendToConnectivityService) {
            mNetworkCapabilities.set(nc);
            if (sendToConnectivityService) {
                mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
            }
        }

        public void connectWithoutInternet() {
            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        /**
         * Transition this NetworkAgent to CONNECTED state with NET_CAPABILITY_INTERNET.
         * @param validated Indicate if network should pretend to be validated.
         */
        public void connect(boolean validated) {
            connect(validated, true);
        }

        /**
         * Transition this NetworkAgent to CONNECTED state.
         * @param validated Indicate if network should pretend to be validated.
         * @param hasInternet Indicate if network should pretend to have NET_CAPABILITY_INTERNET.
         */
        public void connect(boolean validated, boolean hasInternet) {
            assertEquals("MockNetworkAgents can only be connected once",
                    mNetworkInfo.getDetailedState(), DetailedState.IDLE);
            assertFalse(mNetworkCapabilities.hasCapability(NET_CAPABILITY_INTERNET));

            NetworkCallback callback = null;
            final ConditionVariable validatedCv = new ConditionVariable();
            if (validated) {
                setNetworkValid();
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(mNetworkCapabilities.getTransportTypes()[0])
                        .clearCapabilities()
                        .build();
                callback = new NetworkCallback() {
                    public void onCapabilitiesChanged(Network network,
                            NetworkCapabilities networkCapabilities) {
                        if (network.equals(getNetwork()) &&
                            networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                            validatedCv.open();
                        }
                    }
                };
                mCm.registerNetworkCallback(request, callback);
            }
            if (hasInternet) {
                addCapability(NET_CAPABILITY_INTERNET);
            }

            connectWithoutInternet();

            if (validated) {
                // Wait for network to validate.
                waitFor(validatedCv);
                setNetworkInvalid();
            }

            if (callback != null) mCm.unregisterNetworkCallback(callback);
        }

        public void connectWithCaptivePortal(String redirectUrl) {
            setNetworkPortal(redirectUrl);
            connect(false);
        }

        public void connectWithPartialConnectivity() {
            setNetworkPartial();
            connect(false);
        }

        public void suspend() {
            mNetworkInfo.setDetailedState(DetailedState.SUSPENDED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        public void resume() {
            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        public void disconnect() {
            mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        public Network getNetwork() {
            return new Network(mNetworkAgent.netId);
        }

        public ConditionVariable getPreventReconnectReceived() {
            return mPreventReconnectReceived;
        }

        public ConditionVariable getDisconnectedCV() {
            return mDisconnected;
        }

        public void sendLinkProperties(LinkProperties lp) {
            mNetworkAgent.sendLinkProperties(lp);
        }

        public void setStartKeepaliveError(int error) {
            mStartKeepaliveError = error;
        }

        public void setStopKeepaliveError(int error) {
            mStopKeepaliveError = error;
        }

        public void setExpectedKeepaliveSlot(Integer slot) {
            mExpectedKeepaliveSlot = slot;
        }

        public String waitForRedirectUrl() {
            assertTrue(mNetworkStatusReceived.block(TIMEOUT_MS));
            return mRedirectUrl;
        }

        public NetworkAgent getNetworkAgent() {
            return mNetworkAgent;
        }

        public NetworkCapabilities getNetworkCapabilities() {
            return mNetworkCapabilities;
        }
    }

    /**
     * A NetworkFactory that allows tests to wait until any in-flight NetworkRequest add or remove
     * operations have been processed. Before ConnectivityService can add or remove any requests,
     * the factory must be told to expect those operations by calling expectAddRequestsWithScores or
     * expectRemoveRequests.
     */
    private static class MockNetworkFactory extends NetworkFactory {
        private final ConditionVariable mNetworkStartedCV = new ConditionVariable();
        private final ConditionVariable mNetworkStoppedCV = new ConditionVariable();
        private final AtomicBoolean mNetworkStarted = new AtomicBoolean(false);

        // Used to expect that requests be removed or added on a separate thread, without sleeping.
        // Callers can call either expectAddRequestsWithScores() or expectRemoveRequests() exactly
        // once, then cause some other thread to add or remove requests, then call
        // waitForRequests().
        // It is not possible to wait for both add and remove requests. When adding, the queue
        // contains the expected score. When removing, the value is unused, all matters is the
        // number of objects in the queue.
        private final LinkedBlockingQueue<Integer> mExpectations;

        // Whether we are currently expecting requests to be added or removed. Valid only if
        // mExpectations is non-empty.
        private boolean mExpectingAdditions;

        // Used to collect the networks requests managed by this factory. This is a duplicate of
        // the internal information stored in the NetworkFactory (which is private).
        private SparseArray<NetworkRequest> mNetworkRequests = new SparseArray<>();

        public MockNetworkFactory(Looper looper, Context context, String logTag,
                NetworkCapabilities filter) {
            super(looper, context, logTag, filter);
            mExpectations = new LinkedBlockingQueue<>();
        }

        public int getMyRequestCount() {
            return getRequestCount();
        }

        protected void startNetwork() {
            mNetworkStarted.set(true);
            mNetworkStartedCV.open();
        }

        protected void stopNetwork() {
            mNetworkStarted.set(false);
            mNetworkStoppedCV.open();
        }

        public boolean getMyStartRequested() {
            return mNetworkStarted.get();
        }

        public ConditionVariable getNetworkStartedCV() {
            mNetworkStartedCV.close();
            return mNetworkStartedCV;
        }

        public ConditionVariable getNetworkStoppedCV() {
            mNetworkStoppedCV.close();
            return mNetworkStoppedCV;
        }

        @Override
        protected void handleAddRequest(NetworkRequest request, int score,
                int factorySerialNumber) {
            synchronized (mExpectations) {
                final Integer expectedScore = mExpectations.poll(); // null if the queue is empty

                assertNotNull("Added more requests than expected (" + request + " score : "
                        + score + ")", expectedScore);
                // If we're expecting anything, we must be expecting additions.
                if (!mExpectingAdditions) {
                    fail("Can't add requests while expecting requests to be removed");
                }
                if (expectedScore != score) {
                    fail("Expected score was " + expectedScore + " but actual was " + score
                            + " in added request");
                }

                // Add the request.
                mNetworkRequests.put(request.requestId, request);
                super.handleAddRequest(request, score, factorySerialNumber);
                mExpectations.notify();
            }
        }

        @Override
        protected void handleRemoveRequest(NetworkRequest request) {
            synchronized (mExpectations) {
                final Integer expectedScore = mExpectations.poll(); // null if the queue is empty

                assertTrue("Removed more requests than expected", expectedScore != null);
                // If we're expecting anything, we must be expecting removals.
                if (mExpectingAdditions) {
                    fail("Can't remove requests while expecting requests to be added");
                }

                // Remove the request.
                mNetworkRequests.remove(request.requestId);
                super.handleRemoveRequest(request);
                mExpectations.notify();
            }
        }

        // Trigger releasing the request as unfulfillable
        public void triggerUnfulfillable(NetworkRequest r) {
            super.releaseRequestAsUnfulfillableByAnyFactory(r);
        }

        private void assertNoExpectations() {
            if (mExpectations.size() != 0) {
                fail("Can't add expectation, " + mExpectations.size() + " already pending");
            }
        }

        // Expects that requests with the specified scores will be added.
        public void expectAddRequestsWithScores(final int... scores) {
            assertNoExpectations();
            mExpectingAdditions = true;
            for (int score : scores) {
                mExpectations.add(score);
            }
        }

        // Expects that count requests will be removed.
        public void expectRemoveRequests(final int count) {
            assertNoExpectations();
            mExpectingAdditions = false;
            for (int i = 0; i < count; ++i) {
                mExpectations.add(0); // For removals the score is ignored so any value will do.
            }
        }

        // Waits for the expected request additions or removals to happen within a timeout.
        public void waitForRequests() throws InterruptedException {
            final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            synchronized (mExpectations) {
                while (mExpectations.size() > 0 && SystemClock.elapsedRealtime() < deadline) {
                    mExpectations.wait(deadline - SystemClock.elapsedRealtime());
                }
            }
            final long count = mExpectations.size();
            final String msg = count + " requests still not " +
                    (mExpectingAdditions ? "added" : "removed") +
                    " after " + TIMEOUT_MS + " ms";
            assertEquals(msg, 0, count);
        }

        public SparseArray<NetworkRequest> waitForNetworkRequests(final int count)
                throws InterruptedException {
            waitForRequests();
            assertEquals(count, getMyRequestCount());
            return mNetworkRequests;
        }
    }

    private static Looper startHandlerThreadAndReturnLooper() {
        final HandlerThread handlerThread = new HandlerThread("MockVpnThread");
        handlerThread.start();
        return handlerThread.getLooper();
    }

    private class MockVpn extends Vpn {
        // TODO : the interactions between this mock and the mock network agent are too
        // hard to get right at this moment, because it's unclear in which case which
        // target needs to get a method call or both, and in what order. It's because
        // MockNetworkAgent wants to manage its own NetworkCapabilities, but the Vpn
        // parent class of MockVpn agent wants that responsibility.
        // That being said inside the test it should be possible to make the interactions
        // harder to get wrong with precise speccing, judicious comments, helper methods
        // and a few sprinkled assertions.

        private boolean mConnected = false;
        // Careful ! This is different from mNetworkAgent, because MockNetworkAgent does
        // not inherit from NetworkAgent.
        private MockNetworkAgent mMockNetworkAgent;

        public MockVpn(int userId) {
            super(startHandlerThreadAndReturnLooper(), mServiceContext, mNetworkManagementService,
                    userId);
        }

        public void setNetworkAgent(MockNetworkAgent agent) {
            waitForIdle(agent, TIMEOUT_MS);
            mMockNetworkAgent = agent;
            mNetworkAgent = agent.getNetworkAgent();
            mNetworkCapabilities.set(agent.getNetworkCapabilities());
        }

        public void setUids(Set<UidRange> uids) {
            mNetworkCapabilities.setUids(uids);
            updateCapabilities(null /* defaultNetwork */);
        }

        @Override
        public int getNetId() {
            if (mMockNetworkAgent == null) {
                return NETID_UNSET;
            }
            return mMockNetworkAgent.getNetwork().netId;
        }

        @Override
        public boolean appliesToUid(int uid) {
            return mConnected;  // Trickery to simplify testing.
        }

        @Override
        protected boolean isCallerEstablishedOwnerLocked() {
            return mConnected;  // Similar trickery
        }

        private void connect(boolean isAlwaysMetered) {
            mNetworkCapabilities.set(mMockNetworkAgent.getNetworkCapabilities());
            mConnected = true;
            mConfig = new VpnConfig();
            mConfig.isMetered = isAlwaysMetered;
        }

        public void connectAsAlwaysMetered() {
            connect(true /* isAlwaysMetered */);
        }

        public void connect() {
            connect(false /* isAlwaysMetered */);
        }

        @Override
        public NetworkCapabilities updateCapabilities(Network defaultNetwork) {
            if (!mConnected) return null;
            super.updateCapabilities(defaultNetwork);
            // Because super.updateCapabilities will update the capabilities of the agent but
            // not the mock agent, the mock agent needs to know about them.
            copyCapabilitiesToNetworkAgent();
            return new NetworkCapabilities(mNetworkCapabilities);
        }

        private void copyCapabilitiesToNetworkAgent() {
            if (null != mMockNetworkAgent) {
                mMockNetworkAgent.setNetworkCapabilities(mNetworkCapabilities,
                        false /* sendToConnectivityService */);
            }
        }

        public void disconnect() {
            mConnected = false;
            mConfig = null;
        }
    }

    private class FakeWakeupMessage extends WakeupMessage {
        private static final int UNREASONABLY_LONG_WAIT = 1000;

        public FakeWakeupMessage(Context context, Handler handler, String cmdName, int cmd) {
            super(context, handler, cmdName, cmd);
        }

        public FakeWakeupMessage(Context context, Handler handler, String cmdName, int cmd,
                int arg1, int arg2, Object obj) {
            super(context, handler, cmdName, cmd, arg1, arg2, obj);
        }

        @Override
        public void schedule(long when) {
            long delayMs = when - SystemClock.elapsedRealtime();
            if (delayMs < 0) delayMs = 0;
            if (delayMs > UNREASONABLY_LONG_WAIT) {
                fail("Attempting to send msg more than " + UNREASONABLY_LONG_WAIT +
                        "ms into the future: " + delayMs);
            }
            Message msg = mHandler.obtainMessage(mCmd, mArg1, mArg2, mObj);
            mHandler.sendMessageDelayed(msg, delayMs);
        }

        @Override
        public void cancel() {
            mHandler.removeMessages(mCmd, mObj);
        }

        @Override
        public void onAlarm() {
            throw new AssertionError("Should never happen. Update this fake.");
        }
    }

    private class WrappedMultinetworkPolicyTracker extends MultinetworkPolicyTracker {
        public volatile boolean configRestrictsAvoidBadWifi;
        public volatile int configMeteredMultipathPreference;

        public WrappedMultinetworkPolicyTracker(Context c, Handler h, Runnable r) {
            super(c, h, r);
        }

        @Override
        public boolean configRestrictsAvoidBadWifi() {
            return configRestrictsAvoidBadWifi;
        }

        @Override
        public int configMeteredMultipathPreference() {
            return configMeteredMultipathPreference;
        }
    }

    private class WrappedConnectivityService extends ConnectivityService {
        public WrappedMultinetworkPolicyTracker wrappedMultinetworkPolicyTracker;
        private MockableSystemProperties mSystemProperties;

        public WrappedConnectivityService(Context context, INetworkManagementService netManager,
                INetworkStatsService statsService, INetworkPolicyManager policyManager,
                IpConnectivityLog log, INetd netd, IDnsResolver dnsResolver) {
            super(context, netManager, statsService, policyManager, dnsResolver, log, netd);
            mNetd = netd;
            mLingerDelayMs = TEST_LINGER_DELAY_MS;
        }

        @Override
        protected MockableSystemProperties getSystemProperties() {
            // Minimal approach to overriding system properties: let most calls fall through to real
            // device values, and only override ones values that are important to this test.
            mSystemProperties = spy(new MockableSystemProperties());
            when(mSystemProperties.getInt("net.tcp.default_init_rwnd", 0)).thenReturn(0);
            when(mSystemProperties.getBoolean("ro.radio.noril", false)).thenReturn(false);
            return mSystemProperties;
        }

        @Override
        protected Tethering makeTethering() {
            return mock(Tethering.class);
        }

        @Override
        protected ProxyTracker makeProxyTracker() {
            return mock(ProxyTracker.class);
        }

        @Override
        protected int reserveNetId() {
            while (true) {
                final int netId = super.reserveNetId();

                // Don't overlap test NetIDs with real NetIDs as binding sockets to real networks
                // can have odd side-effects, like network validations succeeding.
                Context context = InstrumentationRegistry.getContext();
                final Network[] networks = ConnectivityManager.from(context).getAllNetworks();
                boolean overlaps = false;
                for (Network network : networks) {
                    if (netId == network.netId) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                return netId;
            }
        }

        @Override
        protected boolean queryUserAccess(int uid, int netId) {
            return true;
        }

        public Nat464Xlat getNat464Xlat(MockNetworkAgent mna) {
            return getNetworkAgentInfoForNetwork(mna.getNetwork()).clatd;
        }

        @Override
        public MultinetworkPolicyTracker createMultinetworkPolicyTracker(
                Context c, Handler h, Runnable r) {
            final WrappedMultinetworkPolicyTracker tracker = new WrappedMultinetworkPolicyTracker(c, h, r);
            return tracker;
        }

        public WrappedMultinetworkPolicyTracker getMultinetworkPolicyTracker() {
            return (WrappedMultinetworkPolicyTracker) mMultinetworkPolicyTracker;
        }

        @Override
        protected NetworkStackClient getNetworkStack() {
            return mNetworkStack;
        }

        @Override
        public WakeupMessage makeWakeupMessage(
                Context context, Handler handler, String cmdName, int cmd, Object obj) {
            return new FakeWakeupMessage(context, handler, cmdName, cmd, 0, 0, obj);
        }

        @Override
        public boolean hasService(String name) {
            // Currenty, the only relevant service that ConnectivityService checks for is
            // ETHERNET_SERVICE.
            return Context.ETHERNET_SERVICE.equals(name);
        }

        @Override
        protected IpConnectivityMetrics.Logger metricsLogger() {
            return mMetricsService;
        }

        @Override
        protected void registerNetdEventCallback() {
        }

        public void mockVpn(int uid) {
            synchronized (mVpns) {
                int userId = UserHandle.getUserId(uid);
                mMockVpn = new MockVpn(userId);
                // This has no effect unless the VPN is actually connected, because things like
                // getActiveNetworkForUidInternal call getNetworkAgentInfoForNetId on the VPN
                // netId, and check if that network is actually connected.
                mVpns.put(userId, mMockVpn);
            }
        }

        public void waitForIdle(int timeoutMs) {
            waitForIdleHandler(mHandlerThread, timeoutMs);
        }

        public void waitForIdle() {
            waitForIdle(TIMEOUT_MS);
        }

        public void setUidRulesChanged(int uidRules) {
            try {
                mPolicyListener.onUidRulesChanged(Process.myUid(), uidRules);
            } catch (RemoteException ignored) {
            }
        }

        public void setRestrictBackgroundChanged(boolean restrictBackground) {
            try {
                mPolicyListener.onRestrictBackgroundChanged(restrictBackground);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Wait up to TIMEOUT_MS for {@code conditionVariable} to open.
     * Fails if TIMEOUT_MS goes by before {@code conditionVariable} opens.
     */
    static private void waitFor(ConditionVariable conditionVariable) {
        if (conditionVariable.block(TIMEOUT_MS)) {
            return;
        }
        fail("ConditionVariable was blocked for more than " + TIMEOUT_MS + "ms");
    }

    private static final int VPN_USER = 0;
    private static final int APP1_UID = UserHandle.getUid(VPN_USER, 10100);
    private static final int APP2_UID = UserHandle.getUid(VPN_USER, 10101);
    private static final int VPN_UID = UserHandle.getUid(VPN_USER, 10043);

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();

        MockitoAnnotations.initMocks(this);
        when(mMetricsService.defaultNetworkMetrics()).thenReturn(mDefaultNetworkMetrics);

        when(mUserManager.getUsers(eq(true))).thenReturn(
                Arrays.asList(new UserInfo[] {
                        new UserInfo(VPN_USER, "", 0),
                }));

        // InstrumentationTestRunner prepares a looper, but AndroidJUnitRunner does not.
        // http://b/25897652 .
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mockDefaultPackages();

        FakeSettingsProvider.clearSettingsProvider();
        mServiceContext = new MockContext(InstrumentationRegistry.getContext(),
                new FakeSettingsProvider());
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.addService(
                NetworkPolicyManagerInternal.class, mock(NetworkPolicyManagerInternal.class));

        mService = new WrappedConnectivityService(mServiceContext,
                mNetworkManagementService,
                mStatsService,
                mNpm,
                mock(IpConnectivityLog.class),
                mMockNetd,
                mMockDnsResolver);

        final ArgumentCaptor<INetworkPolicyListener> policyListenerCaptor =
                ArgumentCaptor.forClass(INetworkPolicyListener.class);
        verify(mNpm).registerListener(policyListenerCaptor.capture());
        mPolicyListener = policyListenerCaptor.getValue();

        // Create local CM before sending system ready so that we can answer
        // getSystemService() correctly.
        mCm = new WrappedConnectivityManager(InstrumentationRegistry.getContext(), mService);
        mService.systemReady();
        mService.mockVpn(Process.myUid());
        mCm.bindProcessToNetwork(null);

        // Ensure that the default setting for Captive Portals is used for most tests
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
        setAlwaysOnNetworks(false);
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OFF, "ignored.example.com");
    }

    @After
    public void tearDown() throws Exception {
        setAlwaysOnNetworks(false);
        if (mCellNetworkAgent != null) {
            mCellNetworkAgent.disconnect();
            mCellNetworkAgent = null;
        }
        if (mWiFiNetworkAgent != null) {
            mWiFiNetworkAgent.disconnect();
            mWiFiNetworkAgent = null;
        }
        if (mEthernetNetworkAgent != null) {
            mEthernetNetworkAgent.disconnect();
            mEthernetNetworkAgent = null;
        }
        FakeSettingsProvider.clearSettingsProvider();
    }

    private void mockDefaultPackages() throws Exception {
        final String testPackageName = mContext.getPackageName();
        final PackageInfo testPackageInfo = mContext.getPackageManager().getPackageInfo(
                testPackageName, PackageManager.GET_PERMISSIONS);
        when(mPackageManager.getPackagesForUid(Binder.getCallingUid())).thenReturn(
                new String[] {testPackageName});
        when(mPackageManager.getPackageInfoAsUser(eq(testPackageName), anyInt(),
                eq(UserHandle.getCallingUserId()))).thenReturn(testPackageInfo);

        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                Arrays.asList(new PackageInfo[] {
                        buildPackageInfo(/* SYSTEM */ false, APP1_UID),
                        buildPackageInfo(/* SYSTEM */ false, APP2_UID),
                        buildPackageInfo(/* SYSTEM */ false, VPN_UID)
                }));
    }

   private static int transportToLegacyType(int transport) {
        switch (transport) {
            case TRANSPORT_ETHERNET:
                return TYPE_ETHERNET;
            case TRANSPORT_WIFI:
                return TYPE_WIFI;
            case TRANSPORT_CELLULAR:
                return TYPE_MOBILE;
            case TRANSPORT_VPN:
                return TYPE_VPN;
            default:
                return TYPE_NONE;
        }
    }

    private void verifyActiveNetwork(int transport) {
        // Test getActiveNetworkInfo()
        assertNotNull(mCm.getActiveNetworkInfo());
        assertEquals(transportToLegacyType(transport), mCm.getActiveNetworkInfo().getType());
        // Test getActiveNetwork()
        assertNotNull(mCm.getActiveNetwork());
        assertEquals(mCm.getActiveNetwork(), mCm.getActiveNetworkForUid(Process.myUid()));
        if (!NetworkCapabilities.isValidTransport(transport)) {
            throw new IllegalStateException("Unknown transport " + transport);
        }
        switch (transport) {
            case TRANSPORT_WIFI:
                assertEquals(mCm.getActiveNetwork(), mWiFiNetworkAgent.getNetwork());
                break;
            case TRANSPORT_CELLULAR:
                assertEquals(mCm.getActiveNetwork(), mCellNetworkAgent.getNetwork());
                break;
            default:
                break;
        }
        // Test getNetworkInfo(Network)
        assertNotNull(mCm.getNetworkInfo(mCm.getActiveNetwork()));
        assertEquals(transportToLegacyType(transport),
                mCm.getNetworkInfo(mCm.getActiveNetwork()).getType());
        // Test getNetworkCapabilities(Network)
        assertNotNull(mCm.getNetworkCapabilities(mCm.getActiveNetwork()));
        assertTrue(mCm.getNetworkCapabilities(mCm.getActiveNetwork()).hasTransport(transport));
    }

    private void verifyNoNetwork() {
        waitForIdle();
        // Test getActiveNetworkInfo()
        assertNull(mCm.getActiveNetworkInfo());
        // Test getActiveNetwork()
        assertNull(mCm.getActiveNetwork());
        assertNull(mCm.getActiveNetworkForUid(Process.myUid()));
        // Test getAllNetworks()
        assertEmpty(mCm.getAllNetworks());
    }

    /**
     * Return a ConditionVariable that opens when {@code count} numbers of CONNECTIVITY_ACTION
     * broadcasts are received.
     */
    private ConditionVariable waitForConnectivityBroadcasts(final int count) {
        final ConditionVariable cv = new ConditionVariable();
        mServiceContext.registerReceiver(new BroadcastReceiver() {
                    private int remaining = count;
                    public void onReceive(Context context, Intent intent) {
                        if (--remaining == 0) {
                            cv.open();
                            mServiceContext.unregisterReceiver(this);
                        }
                    }
                }, new IntentFilter(CONNECTIVITY_ACTION));
        return cv;
    }

    @Test
    public void testNetworkTypes() {
        // Ensure that our mocks for the networkAttributes config variable work as expected. If they
        // don't, then tests that depend on CONNECTIVITY_ACTION broadcasts for these network types
        // will fail. Failing here is much easier to debug.
        assertTrue(mCm.isNetworkSupported(TYPE_WIFI));
        assertTrue(mCm.isNetworkSupported(TYPE_MOBILE));
        assertTrue(mCm.isNetworkSupported(TYPE_MOBILE_MMS));
        assertFalse(mCm.isNetworkSupported(TYPE_MOBILE_FOTA));

        // Check that TYPE_ETHERNET is supported. Unlike the asserts above, which only validate our
        // mocks, this assert exercises the ConnectivityService code path that ensures that
        // TYPE_ETHERNET is supported if the ethernet service is running.
        assertTrue(mCm.isNetworkSupported(TYPE_ETHERNET));
    }

    @Test
    public void testLingering() throws Exception {
        verifyNoNetwork();
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // Test bringing up validated cellular.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertLength(2, mCm.getAllNetworks());
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mWiFiNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mWiFiNetworkAgent.getNetwork()));
        // Test bringing up validated WiFi.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertLength(2, mCm.getAllNetworks());
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mCellNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCellNetworkAgent.getNetwork()));
        // Test cellular linger timeout.
        waitFor(mCellNetworkAgent.getDisconnectedCV());
        waitForIdle();
        assertLength(1, mCm.getAllNetworks());
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertLength(1, mCm.getAllNetworks());
        assertEquals(mCm.getAllNetworks()[0], mCm.getActiveNetwork());
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @Test
    public void testValidatedCellularOutscoresUnvalidatedWiFi() throws Exception {
        // Test bringing up unvalidated WiFi
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test cellular disconnect.
        mCellNetworkAgent.disconnect();
        waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @Test
    public void testUnvalidatedWifiOutscoresUnvalidatedCellular() throws Exception {
        // Test bringing up unvalidated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @Test
    public void testUnlingeringDoesNotValidate() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Unlingering a network should not cause it to be marked as validated.
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
    }

    @Test
    public void testCellularOutscoresWeakWifi() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi getting really weak.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.adjustScore(-11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test WiFi restoring signal strength.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.adjustScore(11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testReapingNetwork() throws Exception {
        // Test bringing up WiFi without NET_CAPABILITY_INTERNET.
        // Expect it to be torn down immediately because it satisfies no requests.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = mWiFiNetworkAgent.getDisconnectedCV();
        mWiFiNetworkAgent.connectWithoutInternet();
        waitFor(cv);
        // Test bringing up cellular without NET_CAPABILITY_INTERNET.
        // Expect it to be torn down immediately because it satisfies no requests.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = mCellNetworkAgent.getDisconnectedCV();
        mCellNetworkAgent.connectWithoutInternet();
        waitFor(cv);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular.
        // Expect it to be torn down because it could never be the highest scoring network
        // satisfying the default request even if it validated.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = mCellNetworkAgent.getDisconnectedCV();
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        cv = mWiFiNetworkAgent.getDisconnectedCV();
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
    }

    @Test
    public void testCellularFallback() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Reevaluate WiFi (it'll instantly fail DNS).
        cv = waitForConnectivityBroadcasts(2);
        assertTrue(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mWiFiNetworkAgent.getNetwork());
        // Should quickly fall back to Cellular.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        cv = waitForConnectivityBroadcasts(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testWiFiFallback() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        cv = waitForConnectivityBroadcasts(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testRequiresValidation() {
        assertTrue(NetworkMonitorUtils.isValidationRequired(
                mCm.getDefaultRequest().networkCapabilities));
    }

    enum CallbackState {
        NONE,
        AVAILABLE,
        NETWORK_CAPABILITIES,
        LINK_PROPERTIES,
        SUSPENDED,
        RESUMED,
        LOSING,
        LOST,
        UNAVAILABLE,
        BLOCKED_STATUS
    }

    private static class CallbackInfo {
        public final CallbackState state;
        public final Network network;
        public final Object arg;
        public CallbackInfo(CallbackState s, Network n, Object o) {
            state = s; network = n; arg = o;
        }
        public String toString() {
            return String.format("%s (%s) (%s)", state, network, arg);
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CallbackInfo)) return false;
            // Ignore timeMs, since it's unpredictable.
            CallbackInfo other = (CallbackInfo) o;
            return (state == other.state) && Objects.equals(network, other.network);
        }
        @Override
        public int hashCode() {
            return Objects.hash(state, network);
        }
    }

    /**
     * Utility NetworkCallback for testing. The caller must explicitly test for all the callbacks
     * this class receives, by calling expectCallback() exactly once each time a callback is
     * received. assertNoCallback may be called at any time.
     */
    private class TestNetworkCallback extends NetworkCallback {
        private final LinkedBlockingQueue<CallbackInfo> mCallbacks = new LinkedBlockingQueue<>();
        private Network mLastAvailableNetwork;

        protected void setLastCallback(CallbackState state, Network network, Object o) {
            mCallbacks.offer(new CallbackInfo(state, network, o));
        }

        @Override
        public void onAvailable(Network network) {
            mLastAvailableNetwork = network;
            setLastCallback(CallbackState.AVAILABLE, network, null);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities netCap) {
            setLastCallback(CallbackState.NETWORK_CAPABILITIES, network, netCap);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProp) {
            setLastCallback(CallbackState.LINK_PROPERTIES, network, linkProp);
        }

        @Override
        public void onUnavailable() {
            setLastCallback(CallbackState.UNAVAILABLE, null, null);
        }

        @Override
        public void onNetworkSuspended(Network network) {
            setLastCallback(CallbackState.SUSPENDED, network, null);
        }

        @Override
        public void onNetworkResumed(Network network) {
            setLastCallback(CallbackState.RESUMED, network, null);
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            setLastCallback(CallbackState.LOSING, network, maxMsToLive /* autoboxed int */);
        }

        @Override
        public void onLost(Network network) {
            mLastAvailableNetwork = null;
            setLastCallback(CallbackState.LOST, network, null);
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean blocked) {
            setLastCallback(CallbackState.BLOCKED_STATUS, network, blocked);
        }

        public Network getLastAvailableNetwork() {
            return mLastAvailableNetwork;
        }

        CallbackInfo nextCallback(int timeoutMs) {
            CallbackInfo cb = null;
            try {
                cb = mCallbacks.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }
            if (cb == null) {
                // LinkedBlockingQueue.poll() returns null if it timeouts.
                fail("Did not receive callback after " + timeoutMs + "ms");
            }
            return cb;
        }

        CallbackInfo expectCallback(CallbackState state, MockNetworkAgent agent, int timeoutMs) {
            final Network expectedNetwork = (agent != null) ? agent.getNetwork() : null;
            CallbackInfo expected = new CallbackInfo(state, expectedNetwork, 0);
            CallbackInfo actual = nextCallback(timeoutMs);
            assertEquals("Unexpected callback:", expected, actual);

            if (state == CallbackState.LOSING) {
                String msg = String.format(
                        "Invalid linger time value %d, must be between %d and %d",
                        actual.arg, 0, mService.mLingerDelayMs);
                int maxMsToLive = (Integer) actual.arg;
                assertTrue(msg, 0 <= maxMsToLive && maxMsToLive <= mService.mLingerDelayMs);
            }

            return actual;
        }

        CallbackInfo expectCallback(CallbackState state, MockNetworkAgent agent) {
            return expectCallback(state, agent, TEST_CALLBACK_TIMEOUT_MS);
        }

        CallbackInfo expectCallbackLike(Predicate<CallbackInfo> fn) {
            return expectCallbackLike(fn, TEST_CALLBACK_TIMEOUT_MS);
        }

        CallbackInfo expectCallbackLike(Predicate<CallbackInfo> fn, int timeoutMs) {
            int timeLeft = timeoutMs;
            while (timeLeft > 0) {
                long start = SystemClock.elapsedRealtime();
                CallbackInfo info = nextCallback(timeLeft);
                if (fn.test(info)) {
                    return info;
                }
                timeLeft -= (SystemClock.elapsedRealtime() - start);
            }
            fail("Did not receive expected callback after " + timeoutMs + "ms");
            return null;
        }

        // Expects onAvailable and the callbacks that follow it. These are:
        // - onSuspended, iff the network was suspended when the callbacks fire.
        // - onCapabilitiesChanged.
        // - onLinkPropertiesChanged.
        // - onBlockedStatusChanged.
        //
        // @param agent the network to expect the callbacks on.
        // @param expectSuspended whether to expect a SUSPENDED callback.
        // @param expectValidated the expected value of the VALIDATED capability in the
        //        onCapabilitiesChanged callback.
        // @param timeoutMs how long to wait for the callbacks.
        void expectAvailableCallbacks(MockNetworkAgent agent, boolean expectSuspended,
                boolean expectValidated, boolean expectBlocked, int timeoutMs) {
            expectCallback(CallbackState.AVAILABLE, agent, timeoutMs);
            if (expectSuspended) {
                expectCallback(CallbackState.SUSPENDED, agent, timeoutMs);
            }
            if (expectValidated) {
                expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, agent, timeoutMs);
            } else {
                expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, agent, timeoutMs);
            }
            expectCallback(CallbackState.LINK_PROPERTIES, agent, timeoutMs);
            expectBlockedStatusCallback(expectBlocked, agent);
        }

        // Expects the available callbacks (validated), plus onSuspended.
        void expectAvailableAndSuspendedCallbacks(MockNetworkAgent agent, boolean expectValidated) {
            expectAvailableCallbacks(agent, true, expectValidated, false, TEST_CALLBACK_TIMEOUT_MS);
        }

        void expectAvailableCallbacksValidated(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, false, true, false, TEST_CALLBACK_TIMEOUT_MS);
        }

        void expectAvailableCallbacksValidatedAndBlocked(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, false, true, true, TEST_CALLBACK_TIMEOUT_MS);
        }

        void expectAvailableCallbacksUnvalidated(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, false, false, false, TEST_CALLBACK_TIMEOUT_MS);
        }

        void expectAvailableCallbacksUnvalidatedAndBlocked(MockNetworkAgent agent) {
            expectAvailableCallbacks(agent, false, false, true, TEST_CALLBACK_TIMEOUT_MS);
        }

        // Expects the available callbacks (where the onCapabilitiesChanged must contain the
        // VALIDATED capability), plus another onCapabilitiesChanged which is identical to the
        // one we just sent.
        // TODO: this is likely a bug. Fix it and remove this method.
        void expectAvailableDoubleValidatedCallbacks(MockNetworkAgent agent) {
            expectCallback(CallbackState.AVAILABLE, agent, TEST_CALLBACK_TIMEOUT_MS);
            NetworkCapabilities nc1 = expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, agent);
            expectCallback(CallbackState.LINK_PROPERTIES, agent, TEST_CALLBACK_TIMEOUT_MS);
            // Implicitly check the network is allowed to use.
            // TODO: should we need to consider if network is in blocked status in this case?
            expectBlockedStatusCallback(false, agent);
            NetworkCapabilities nc2 = expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, agent);
            assertEquals(nc1, nc2);
        }

        // Expects the available callbacks where the onCapabilitiesChanged must not have validated,
        // then expects another onCapabilitiesChanged that has the validated bit set. This is used
        // when a network connects and satisfies a callback, and then immediately validates.
        void expectAvailableThenValidatedCallbacks(MockNetworkAgent agent) {
            expectAvailableCallbacksUnvalidated(agent);
            expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, agent);
        }

        NetworkCapabilities expectCapabilitiesWith(int capability, MockNetworkAgent agent) {
            return expectCapabilitiesWith(capability, agent, TEST_CALLBACK_TIMEOUT_MS);
        }

        NetworkCapabilities expectCapabilitiesWith(int capability, MockNetworkAgent agent,
                int timeoutMs) {
            CallbackInfo cbi = expectCallback(CallbackState.NETWORK_CAPABILITIES, agent, timeoutMs);
            NetworkCapabilities nc = (NetworkCapabilities) cbi.arg;
            assertTrue(nc.hasCapability(capability));
            return nc;
        }

        NetworkCapabilities expectCapabilitiesWithout(int capability, MockNetworkAgent agent) {
            return expectCapabilitiesWithout(capability, agent, TEST_CALLBACK_TIMEOUT_MS);
        }

        NetworkCapabilities expectCapabilitiesWithout(int capability, MockNetworkAgent agent,
                int timeoutMs) {
            CallbackInfo cbi = expectCallback(CallbackState.NETWORK_CAPABILITIES, agent, timeoutMs);
            NetworkCapabilities nc = (NetworkCapabilities) cbi.arg;
            assertFalse(nc.hasCapability(capability));
            return nc;
        }

        void expectCapabilitiesLike(Predicate<NetworkCapabilities> fn, MockNetworkAgent agent) {
            CallbackInfo cbi = expectCallback(CallbackState.NETWORK_CAPABILITIES, agent);
            assertTrue("Received capabilities don't match expectations : " + cbi.arg,
                    fn.test((NetworkCapabilities) cbi.arg));
        }

        void expectLinkPropertiesLike(Predicate<LinkProperties> fn, MockNetworkAgent agent) {
            CallbackInfo cbi = expectCallback(CallbackState.LINK_PROPERTIES, agent);
            assertTrue("Received LinkProperties don't match expectations : " + cbi.arg,
                    fn.test((LinkProperties) cbi.arg));
        }

        void expectBlockedStatusCallback(boolean expectBlocked, MockNetworkAgent agent) {
            CallbackInfo cbi = expectCallback(CallbackState.BLOCKED_STATUS, agent);
            boolean actualBlocked = (boolean) cbi.arg;
            assertEquals(expectBlocked, actualBlocked);
        }

        void assertNoCallback() {
            waitForIdle();
            CallbackInfo c = mCallbacks.peek();
            assertNull("Unexpected callback: " + c, c);
        }
    }

    // Can't be part of TestNetworkCallback because "cannot be declared static; static methods can
    // only be declared in a static or top level type".
    static void assertNoCallbacks(TestNetworkCallback ... callbacks) {
        for (TestNetworkCallback c : callbacks) {
            c.assertNoCallback();
        }
    }

    @Test
    public void testStateChangeNetworkCallbacks() throws Exception {
        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        // Test unvalidated networks
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // Test validated networks
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        genericNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        genericNetworkCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        genericNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
    }

    @Test
    public void testMultipleLingering() {
        // This test would be flaky with the default 120ms timer: that is short enough that
        // lingered networks are torn down before assertions can be run. We don't want to mock the
        // lingering timer to keep the WakeupMessage logic realistic: this has already proven useful
        // in detecting races.
        mService.mLingerDelayMs = 300;

        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_NOT_METERED)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mEthernetNetworkAgent = new MockNetworkAgent(TRANSPORT_ETHERNET);

        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mEthernetNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);

        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.connect(true);
        // We get AVAILABLE on wifi when wifi connects and satisfies our unmetered request.
        // We then get LOSING when wifi validates and cell is outscored.
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mEthernetNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mEthernetNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(mEthernetNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mEthernetNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        for (int i = 0; i < 4; i++) {
            MockNetworkAgent oldNetwork, newNetwork;
            if (i % 2 == 0) {
                mWiFiNetworkAgent.adjustScore(-15);
                oldNetwork = mWiFiNetworkAgent;
                newNetwork = mCellNetworkAgent;
            } else {
                mWiFiNetworkAgent.adjustScore(15);
                oldNetwork = mCellNetworkAgent;
                newNetwork = mWiFiNetworkAgent;

            }
            callback.expectCallback(CallbackState.LOSING, oldNetwork);
            // TODO: should we send an AVAILABLE callback to newNetwork, to indicate that it is no
            // longer lingering?
            defaultCallback.expectAvailableCallbacksValidated(newNetwork);
            assertEquals(newNetwork.getNetwork(), mCm.getActiveNetwork());
        }
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Verify that if a network no longer satisfies a request, we send LOST and not LOSING, even
        // if the network is still up.
        mWiFiNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        // We expect a notification about the capabilities change, and nothing else.
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_METERED, mWiFiNetworkAgent);
        defaultCallback.assertNoCallback();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Wifi no longer satisfies our listen, which is for an unmetered network.
        // But because its score is 55, it's still up (and the default network).
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect our test networks.
        mWiFiNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        mCm.unregisterNetworkCallback(callback);
        waitForIdle();

        // Check that a network is only lingered or torn down if it would not satisfy a request even
        // if it validated.
        request = new NetworkRequest.Builder().clearCapabilities().build();
        callback = new TestNetworkCallback();

        mCm.registerNetworkCallback(request, callback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);   // Score: 10
        callback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi with a score of 20.
        // Cell stays up because it would satisfy the default request if it validated.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);   // Score: 20
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi with a score of 70.
        // Cell is lingered because it would not satisfy any request, even if it validated.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.adjustScore(50);
        mWiFiNetworkAgent.connect(false);   // Score: 70
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Tear down wifi.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi, then validate it. Previous versions would immediately tear down cell, but
        // it's arguably correct to linger it, since it was the default network before it validated.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        // If a network is lingering, and we add and remove a request from it, resume lingering.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        NetworkCallback noopCallback = new NetworkCallback();
        mCm.requestNetwork(cellRequest, noopCallback);
        // TODO: should this cause an AVAILABLE callback, to indicate that the network is no longer
        // lingering?
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);

        // Similar to the above: lingering can start even after the lingered request is removed.
        // Disconnect wifi and switch to cell.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Cell is now the default network. Pin it with a cell-specific request.
        noopCallback = new NetworkCallback();  // Can't reuse NetworkCallbacks. http://b/20701525
        mCm.requestNetwork(cellRequest, noopCallback);

        // Now connect wifi, and expect it to become the default network.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        // The default request is lingering on cell, but nothing happens to cell, and we send no
        // callbacks for it, because it's kept up by cellRequest.
        callback.assertNoCallback();
        // Now unregister cellRequest and expect cell to start lingering.
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);

        // Let linger run its course.
        callback.assertNoCallback();
        final int lingerTimeoutMs = mService.mLingerDelayMs + mService.mLingerDelayMs / 4;
        callback.expectCallback(CallbackState.LOST, mCellNetworkAgent, lingerTimeoutMs);

        // Register a TRACK_DEFAULT request and check that it does not affect lingering.
        TestNetworkCallback trackDefaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(trackDefaultCallback);
        trackDefaultCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        mEthernetNetworkAgent = new MockNetworkAgent(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mEthernetNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mEthernetNetworkAgent);
        trackDefaultCallback.expectAvailableDoubleValidatedCallbacks(mEthernetNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Let linger run its course.
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent, lingerTimeoutMs);

        // Clean up.
        mEthernetNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
        trackDefaultCallback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(defaultCallback);
        mCm.unregisterNetworkCallback(trackDefaultCallback);
    }

    @Test
    public void testNetworkGoesIntoBackgroundAfterLinger() {
        setAlwaysOnNetworks(true);
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities()
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);

        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        // Wifi comes up and cell lingers.
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);

        // File a request for cellular, then release it.
        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        NetworkCallback noopCallback = new NetworkCallback();
        mCm.requestNetwork(cellRequest, noopCallback);
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);

        // Let linger run its course.
        callback.assertNoCallback();
        final int lingerTimeoutMs = TEST_LINGER_DELAY_MS + TEST_LINGER_DELAY_MS / 4;
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent,
                lingerTimeoutMs);

        // Clean up.
        mCm.unregisterNetworkCallback(defaultCallback);
        mCm.unregisterNetworkCallback(callback);
    }

    @Test
    public void testExplicitlySelected() {
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up validated cell.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        // Bring up unvalidated wifi with explicitlySelected=true.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(false);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // Cell Remains the default.
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Lower wifi's score to below than cell, and check that it doesn't disconnect because
        // it's explicitly selected.
        mWiFiNetworkAgent.adjustScore(-40);
        mWiFiNetworkAgent.adjustScore(40);
        callback.assertNoCallback();

        // If the user chooses yes on the "No Internet access, stay connected?" dialog, we switch to
        // wifi even though it's unvalidated.
        mCm.setAcceptUnvalidated(mWiFiNetworkAgent.getNetwork(), true, false);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect wifi, and then reconnect, again with explicitlySelected=true.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(false);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // If the user chooses no on the "No Internet access, stay connected?" dialog, we ask the
        // network to disconnect.
        mCm.setAcceptUnvalidated(mWiFiNetworkAgent.getNetwork(), false, false);
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Reconnect, again with explicitlySelected=true, but this time validate.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(false);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // BUG: the network will no longer linger, even though it's validated and outscored.
        // TODO: fix this.
        mEthernetNetworkAgent = new MockNetworkAgent(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(mEthernetNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        callback.assertNoCallback();

        // Clean up.
        mWiFiNetworkAgent.disconnect();
        mCellNetworkAgent.disconnect();
        mEthernetNetworkAgent.disconnect();

        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        callback.expectCallback(CallbackState.LOST, mEthernetNetworkAgent);
    }

    private int[] makeIntArray(final int size, final int value) {
        final int[] array = new int[size];
        Arrays.fill(array, value);
        return array;
    }

    private void tryNetworkFactoryRequests(int capability) throws Exception {
        // Verify NOT_RESTRICTED is set appropriately
        final NetworkCapabilities nc = new NetworkRequest.Builder().addCapability(capability)
                .build().networkCapabilities;
        if (capability == NET_CAPABILITY_CBS || capability == NET_CAPABILITY_DUN ||
                capability == NET_CAPABILITY_EIMS || capability == NET_CAPABILITY_FOTA ||
                capability == NET_CAPABILITY_IA || capability == NET_CAPABILITY_IMS ||
                capability == NET_CAPABILITY_RCS || capability == NET_CAPABILITY_XCAP) {
            assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        } else {
            assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        }

        NetworkCapabilities filter = new NetworkCapabilities();
        filter.addCapability(capability);
        final HandlerThread handlerThread = new HandlerThread("testNetworkFactoryRequests");
        handlerThread.start();
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);
        ConditionVariable cv = testFactory.getNetworkStartedCV();
        testFactory.expectAddRequestsWithScores(0);
        testFactory.register();
        testFactory.waitForNetworkRequests(1);
        int expectedRequestCount = 1;
        NetworkCallback networkCallback = null;
        // For non-INTERNET capabilities we cannot rely on the default request being present, so
        // add one.
        if (capability != NET_CAPABILITY_INTERNET) {
            assertFalse(testFactory.getMyStartRequested());
            NetworkRequest request = new NetworkRequest.Builder().addCapability(capability).build();
            networkCallback = new NetworkCallback();
            testFactory.expectAddRequestsWithScores(0);  // New request
            mCm.requestNetwork(request, networkCallback);
            expectedRequestCount++;
            testFactory.waitForNetworkRequests(expectedRequestCount);
        }
        waitFor(cv);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        assertTrue(testFactory.getMyStartRequested());

        // Now bring in a higher scored network.
        MockNetworkAgent testAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        // Rather than create a validated network which complicates things by registering it's
        // own NetworkRequest during startup, just bump up the score to cancel out the
        // unvalidated penalty.
        testAgent.adjustScore(40);
        cv = testFactory.getNetworkStoppedCV();

        // When testAgent connects, ConnectivityService will re-send us all current requests with
        // the new score. There are expectedRequestCount such requests, and we must wait for all of
        // them.
        testFactory.expectAddRequestsWithScores(makeIntArray(expectedRequestCount, 50));
        testAgent.connect(false);
        testAgent.addCapability(capability);
        waitFor(cv);
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Bring in a bunch of requests.
        testFactory.expectAddRequestsWithScores(makeIntArray(10, 50));
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        ConnectivityManager.NetworkCallback[] networkCallbacks =
                new ConnectivityManager.NetworkCallback[10];
        for (int i = 0; i< networkCallbacks.length; i++) {
            networkCallbacks[i] = new ConnectivityManager.NetworkCallback();
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(capability);
            mCm.requestNetwork(builder.build(), networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(10 + expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Remove the requests.
        testFactory.expectRemoveRequests(10);
        for (int i = 0; i < networkCallbacks.length; i++) {
            mCm.unregisterNetworkCallback(networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Drop the higher scored network.
        cv = testFactory.getNetworkStartedCV();
        // With the default network disconnecting, the requests are sent with score 0 to factories.
        testFactory.expectAddRequestsWithScores(makeIntArray(expectedRequestCount, 0));
        testAgent.disconnect();
        waitFor(cv);
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        assertTrue(testFactory.getMyStartRequested());

        testFactory.unregister();
        if (networkCallback != null) mCm.unregisterNetworkCallback(networkCallback);
        handlerThread.quit();
    }

    @Test
    public void testNetworkFactoryRequests() throws Exception {
        tryNetworkFactoryRequests(NET_CAPABILITY_MMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_SUPL);
        tryNetworkFactoryRequests(NET_CAPABILITY_DUN);
        tryNetworkFactoryRequests(NET_CAPABILITY_FOTA);
        tryNetworkFactoryRequests(NET_CAPABILITY_IMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_CBS);
        tryNetworkFactoryRequests(NET_CAPABILITY_WIFI_P2P);
        tryNetworkFactoryRequests(NET_CAPABILITY_IA);
        tryNetworkFactoryRequests(NET_CAPABILITY_RCS);
        tryNetworkFactoryRequests(NET_CAPABILITY_XCAP);
        tryNetworkFactoryRequests(NET_CAPABILITY_EIMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_METERED);
        tryNetworkFactoryRequests(NET_CAPABILITY_INTERNET);
        tryNetworkFactoryRequests(NET_CAPABILITY_TRUSTED);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_VPN);
        // Skipping VALIDATED and CAPTIVE_PORTAL as they're disallowed.
    }

    @Test
    public void testNoMutableNetworkRequests() throws Exception {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("a"), 0);
        NetworkRequest request1 = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED)
                .build();
        NetworkRequest request2 = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL)
                .build();

        Class<IllegalArgumentException> expected = IllegalArgumentException.class;
        assertException(() -> { mCm.requestNetwork(request1, new NetworkCallback()); }, expected);
        assertException(() -> { mCm.requestNetwork(request1, pendingIntent); }, expected);
        assertException(() -> { mCm.requestNetwork(request2, new NetworkCallback()); }, expected);
        assertException(() -> { mCm.requestNetwork(request2, pendingIntent); }, expected);
    }

    @Test
    public void testMMSonWiFi() throws Exception {
        // Test bringing up cellular without MMS NetworkRequest gets reaped
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        ConditionVariable cv = mCellNetworkAgent.getDisconnectedCV();
        mCellNetworkAgent.connectWithoutInternet();
        waitFor(cv);
        waitForIdle();
        assertEmpty(mCm.getAllNetworks());
        verifyNoNetwork();

        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);

        // Register MMS NetworkRequest
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(builder.build(), networkCallback);

        // Test bringing up unvalidated cellular with MMS
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mCellNetworkAgent.connectWithoutInternet();
        networkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        verifyActiveNetwork(TRANSPORT_WIFI);

        // Test releasing NetworkRequest disconnects cellular with MMS
        cv = mCellNetworkAgent.getDisconnectedCV();
        mCm.unregisterNetworkCallback(networkCallback);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testMMSonCell() throws Exception {
        // Test bringing up cellular without MMS
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);

        // Register MMS NetworkRequest
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(builder.build(), networkCallback);

        // Test bringing up MMS cellular network
        MockNetworkAgent mmsNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mmsNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mmsNetworkAgent.connectWithoutInternet();
        networkCallback.expectAvailableCallbacksUnvalidated(mmsNetworkAgent);
        verifyActiveNetwork(TRANSPORT_CELLULAR);

        // Test releasing MMS NetworkRequest does not disconnect main cellular NetworkAgent
        cv = mmsNetworkAgent.getDisconnectedCV();
        mCm.unregisterNetworkCallback(networkCallback);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
    }

    @Test
    public void testPartialConnectivity() {
        // Register network callback.
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up validated mobile data.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        // Bring up wifi with partial connectivity.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithPartialConnectivity();
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY, mWiFiNetworkAgent);

        // Mobile data should be the default network.
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        callback.assertNoCallback();

        // With HTTPS probe disabled, NetworkMonitor should pass the network validation with http
        // probe.
        mWiFiNetworkAgent.setNetworkPartialValid();
        // If the user chooses yes to use this partial connectivity wifi, switch the default
        // network to wifi and check if wifi becomes valid or not.
        mCm.setAcceptPartialConnectivity(mWiFiNetworkAgent.getNetwork(), true /* accept */,
                false /* always */);
        // If user accepts partial connectivity network,
        // NetworkMonitor#setAcceptPartialConnectivity() should be called too.
        waitForIdle();
        try {
            verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
        // Need a trigger point to let NetworkMonitor tell ConnectivityService that network is
        // validated.
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        NetworkCapabilities nc = callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED,
                mWiFiNetworkAgent);
        assertTrue(nc.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect and reconnect wifi with partial connectivity again.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithPartialConnectivity();
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY, mWiFiNetworkAgent);

        // Mobile data should be the default network.
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // If the user chooses no, disconnect wifi immediately.
        mCm.setAcceptPartialConnectivity(mWiFiNetworkAgent.getNetwork(), false/* accept */,
                false /* always */);
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // If user accepted partial connectivity before, and device reconnects to that network
        // again, but now the network has full connectivity. The network shouldn't contain
        // NET_CAPABILITY_PARTIAL_CONNECTIVITY.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        // acceptUnvalidated is also used as setting for accepting partial networks.
        mWiFiNetworkAgent.explicitlySelected(true /* acceptUnvalidated */);
        mWiFiNetworkAgent.connect(true);
        // If user accepted partial connectivity network before,
        // NetworkMonitor#setAcceptPartialConnectivity() will be called in
        // ConnectivityService#updateNetworkInfo().
        waitForIdle();
        try {
            verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        nc = callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertFalse(nc.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));
        // Wifi should be the default network.
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // If user accepted partial connectivity before, and now the device reconnects to the
        // partial connectivity network. The network should be valid and contain
        // NET_CAPABILITY_PARTIAL_CONNECTIVITY.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(true /* acceptUnvalidated */);
        // Current design cannot send multi-testResult from NetworkMonitor to ConnectivityService.
        // So, if user accepts partial connectivity, NetworkMonitor will send PARTIAL_CONNECTIVITY
        // to ConnectivityService first then send VALID. Once NetworkMonitor support
        // multi-testResult, this test case also need to be changed to meet the new design.
        mWiFiNetworkAgent.connectWithPartialConnectivity();
        // If user accepted partial connectivity network before,
        // NetworkMonitor#setAcceptPartialConnectivity() will be called in
        // ConnectivityService#updateNetworkInfo().
        waitForIdle();
        try {
            verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        // TODO: If the user accepted partial connectivity, we shouldn't switch to wifi until
        // NetworkMonitor detects partial connectivity
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        callback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY, mWiFiNetworkAgent);
        mWiFiNetworkAgent.setNetworkValid();
        // Need a trigger point to let NetworkMonitor tell ConnectivityService that network is
        // validated.
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
    }

    @Test
    public void testCaptivePortalOnPartialConnectivity() throws RemoteException {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String redirectUrl = "http://android.com/path";
        mWiFiNetworkAgent.connectWithCaptivePortal(redirectUrl);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), redirectUrl);

        // Check that startCaptivePortalApp sends the expected command to NetworkMonitor.
        mCm.startCaptivePortalApp(mWiFiNetworkAgent.getNetwork());
        verify(mWiFiNetworkAgent.mNetworkMonitor, timeout(TIMEOUT_MS).times(1))
                .launchCaptivePortalApp();

        // Report that the captive portal is dismissed with partial connectivity, and check that
        // callbacks are fired.
        mWiFiNetworkAgent.setNetworkPartial();
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        waitForIdle();
        captivePortalCallback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY,
                mWiFiNetworkAgent);

        // Report partial connectivity is accepted.
        mWiFiNetworkAgent.setNetworkPartialValid();
        mCm.setAcceptPartialConnectivity(mWiFiNetworkAgent.getNetwork(), true /* accept */,
                false /* always */);
        waitForIdle();
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        validatedCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        NetworkCapabilities nc =
                validatedCallback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY,
                mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(captivePortalCallback);
        mCm.unregisterNetworkCallback(validatedCallback);
    }

    @Test
    public void testCaptivePortal() {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String firstRedirectUrl = "http://example.com/firstPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(firstRedirectUrl);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), firstRedirectUrl);

        // Take down network.
        // Expect onLost callback.
        mWiFiNetworkAgent.disconnect();
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String secondRedirectUrl = "http://example.com/secondPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(secondRedirectUrl);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), secondRedirectUrl);

        // Make captive portal disappear then revalidate.
        // Expect onLost callback because network no longer provides NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent.setNetworkValid();
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Expect NET_CAPABILITY_VALIDATED onAvailable callback.
        validatedCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        // Expect no notification to be shown when captive portal disappears by itself
        verify(mNotificationManager, never()).notifyAsUser(
                anyString(), eq(NotificationType.LOGGED_IN.eventId), any(), any());

        // Break network connectivity.
        // Expect NET_CAPABILITY_VALIDATED onLost callback.
        mWiFiNetworkAgent.setNetworkInvalid();
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), false);
        validatedCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
    }

    @Test
    public void testCaptivePortalApp() throws RemoteException {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up wifi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        validatedCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        Network wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Check that calling startCaptivePortalApp does nothing.
        final int fastTimeoutMs = 100;
        mCm.startCaptivePortalApp(wifiNetwork);
        waitForIdle();
        verify(mWiFiNetworkAgent.mNetworkMonitor, never()).launchCaptivePortalApp();
        mServiceContext.expectNoStartActivityIntent(fastTimeoutMs);

        // Turn into a captive portal.
        mWiFiNetworkAgent.setNetworkPortal("http://example.com");
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        validatedCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Check that startCaptivePortalApp sends the expected command to NetworkMonitor.
        mCm.startCaptivePortalApp(wifiNetwork);
        waitForIdle();
        verify(mWiFiNetworkAgent.mNetworkMonitor).launchCaptivePortalApp();

        // NetworkMonitor uses startCaptivePortal(Network, Bundle) (startCaptivePortalAppInternal)
        final Bundle testBundle = new Bundle();
        final String testKey = "testkey";
        final String testValue = "testvalue";
        testBundle.putString(testKey, testValue);
        mCm.startCaptivePortalApp(wifiNetwork, testBundle);
        final Intent signInIntent = mServiceContext.expectStartActivityIntent(TIMEOUT_MS);
        assertEquals(ACTION_CAPTIVE_PORTAL_SIGN_IN, signInIntent.getAction());
        assertEquals(testValue, signInIntent.getStringExtra(testKey));

        // Report that the captive portal is dismissed, and check that callbacks are fired
        mWiFiNetworkAgent.setNetworkValid();
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        validatedCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        captivePortalCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        verify(mNotificationManager, times(1)).notifyAsUser(anyString(),
                eq(NotificationType.LOGGED_IN.eventId), any(), eq(UserHandle.ALL));

        mCm.unregisterNetworkCallback(validatedCallback);
        mCm.unregisterNetworkCallback(captivePortalCallback);
    }

    @Test
    public void testAvoidOrIgnoreCaptivePortals() {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_AVOID);
        // Bring up a network with a captive portal.
        // Expect it to fail to connect and not result in any callbacks.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        String firstRedirectUrl = "http://example.com/firstPath";

        ConditionVariable disconnectCv = mWiFiNetworkAgent.getDisconnectedCV();
        ConditionVariable avoidCv = mWiFiNetworkAgent.getPreventReconnectReceived();
        mWiFiNetworkAgent.connectWithCaptivePortal(firstRedirectUrl);
        waitFor(disconnectCv);
        waitFor(avoidCv);

        assertNoCallbacks(captivePortalCallback, validatedCallback);
    }

    private NetworkRequest.Builder newWifiRequestBuilder() {
        return new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI);
    }

    /**
     * Verify request matching behavior with network specifiers.
     *
     * Note: this test is somewhat problematic since it involves removing capabilities from
     * agents - i.e. agents rejecting requests which they previously accepted. This is flagged
     * as a WTF bug in
     * {@link ConnectivityService#mixInCapabilities(NetworkAgentInfo, NetworkCapabilities)} but
     * does work.
     */
    @Test
    public void testNetworkSpecifier() {
        // A NetworkSpecifier subclass that matches all networks but must not be visible to apps.
        class ConfidentialMatchAllNetworkSpecifier extends NetworkSpecifier implements
                Parcelable {
            @Override
            public boolean satisfiedBy(NetworkSpecifier other) {
                return true;
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {}

            @Override
            public NetworkSpecifier redact() {
                return null;
            }
        }

        // A network specifier that matches either another LocalNetworkSpecifier with the same
        // string or a ConfidentialMatchAllNetworkSpecifier, and can be passed to apps as is.
        class LocalStringNetworkSpecifier extends NetworkSpecifier implements Parcelable {
            private String mString;

            LocalStringNetworkSpecifier(String string) {
                mString = string;
            }

            @Override
            public boolean satisfiedBy(NetworkSpecifier other) {
                if (other instanceof LocalStringNetworkSpecifier) {
                    return TextUtils.equals(mString,
                            ((LocalStringNetworkSpecifier) other).mString);
                }
                if (other instanceof ConfidentialMatchAllNetworkSpecifier) return true;
                return false;
            }

            @Override
            public int describeContents() {
                return 0;
            }
            @Override
            public void writeToParcel(Parcel dest, int flags) {}
        }


        NetworkRequest rEmpty1 = newWifiRequestBuilder().build();
        NetworkRequest rEmpty2 = newWifiRequestBuilder().setNetworkSpecifier((String) null).build();
        NetworkRequest rEmpty3 = newWifiRequestBuilder().setNetworkSpecifier("").build();
        NetworkRequest rEmpty4 = newWifiRequestBuilder().setNetworkSpecifier(
            (NetworkSpecifier) null).build();
        NetworkRequest rFoo = newWifiRequestBuilder().setNetworkSpecifier(
                new LocalStringNetworkSpecifier("foo")).build();
        NetworkRequest rBar = newWifiRequestBuilder().setNetworkSpecifier(
                new LocalStringNetworkSpecifier("bar")).build();

        TestNetworkCallback cEmpty1 = new TestNetworkCallback();
        TestNetworkCallback cEmpty2 = new TestNetworkCallback();
        TestNetworkCallback cEmpty3 = new TestNetworkCallback();
        TestNetworkCallback cEmpty4 = new TestNetworkCallback();
        TestNetworkCallback cFoo = new TestNetworkCallback();
        TestNetworkCallback cBar = new TestNetworkCallback();
        TestNetworkCallback[] emptyCallbacks = new TestNetworkCallback[] {
                cEmpty1, cEmpty2, cEmpty3, cEmpty4 };

        mCm.registerNetworkCallback(rEmpty1, cEmpty1);
        mCm.registerNetworkCallback(rEmpty2, cEmpty2);
        mCm.registerNetworkCallback(rEmpty3, cEmpty3);
        mCm.registerNetworkCallback(rEmpty4, cEmpty4);
        mCm.registerNetworkCallback(rFoo, cFoo);
        mCm.registerNetworkCallback(rBar, cBar);

        LocalStringNetworkSpecifier nsFoo = new LocalStringNetworkSpecifier("foo");
        LocalStringNetworkSpecifier nsBar = new LocalStringNetworkSpecifier("bar");

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        cEmpty1.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        cEmpty2.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        cEmpty3.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        cEmpty4.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertNoCallbacks(cFoo, cBar);

        mWiFiNetworkAgent.setNetworkSpecifier(nsFoo);
        cFoo.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier().equals(nsFoo),
                    mWiFiNetworkAgent);
        }
        cFoo.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier().equals(nsFoo),
                mWiFiNetworkAgent);
        assertEquals(nsFoo,
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        cFoo.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(nsBar);
        cFoo.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        cBar.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier().equals(nsBar),
                    mWiFiNetworkAgent);
        }
        cBar.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier().equals(nsBar),
                mWiFiNetworkAgent);
        assertEquals(nsBar,
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        cBar.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(new ConfidentialMatchAllNetworkSpecifier());
        cFoo.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        for (TestNetworkCallback c : emptyCallbacks) {
            c.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier() == null,
                    mWiFiNetworkAgent);
        }
        cFoo.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier() == null,
                mWiFiNetworkAgent);
        cBar.expectCapabilitiesLike((caps) -> caps.getNetworkSpecifier() == null,
                mWiFiNetworkAgent);
        assertNull(
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        cFoo.assertNoCallback();
        cBar.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(null);
        cFoo.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        cBar.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCallback(CallbackState.NETWORK_CAPABILITIES, mWiFiNetworkAgent);
        }

        assertNoCallbacks(cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo, cBar);
    }

    @Test
    public void testInvalidNetworkSpecifier() {
        try {
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.setNetworkSpecifier(new MatchAllNetworkSpecifier());
            fail("NetworkRequest builder with MatchAllNetworkSpecifier");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            NetworkCapabilities networkCapabilities = new NetworkCapabilities();
            networkCapabilities.addTransportType(TRANSPORT_WIFI)
                    .setNetworkSpecifier(new MatchAllNetworkSpecifier());
            mService.requestNetwork(networkCapabilities, null, 0, null,
                    ConnectivityManager.TYPE_WIFI);
            fail("ConnectivityService requestNetwork with MatchAllNetworkSpecifier");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        class NonParcelableSpecifier extends NetworkSpecifier {
            public boolean satisfiedBy(NetworkSpecifier other) { return false; }
        };
        class ParcelableSpecifier extends NonParcelableSpecifier implements Parcelable {
            @Override public int describeContents() { return 0; }
            @Override public void writeToParcel(Parcel p, int flags) {}
        }
        NetworkRequest.Builder builder;

        builder = new NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET);
        try {
            builder.setNetworkSpecifier(new NonParcelableSpecifier());
            Parcel parcelW = Parcel.obtain();
            builder.build().writeToParcel(parcelW, 0);
            fail("Parceling a non-parcelable specifier did not throw an exception");
        } catch (Exception e) {
            // expected
        }

        builder = new NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET);
        builder.setNetworkSpecifier(new ParcelableSpecifier());
        NetworkRequest nr = builder.build();
        assertNotNull(nr);

        try {
            Parcel parcelW = Parcel.obtain();
            nr.writeToParcel(parcelW, 0);
            byte[] bytes = parcelW.marshall();
            parcelW.recycle();

            Parcel parcelR = Parcel.obtain();
            parcelR.unmarshall(bytes, 0, bytes.length);
            parcelR.setDataPosition(0);
            NetworkRequest rereadNr = NetworkRequest.CREATOR.createFromParcel(parcelR);
            fail("Unparceling a non-framework NetworkSpecifier did not throw an exception");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    public void testNetworkSpecifierUidSpoofSecurityException() {
        class UidAwareNetworkSpecifier extends NetworkSpecifier implements Parcelable {
            @Override
            public boolean satisfiedBy(NetworkSpecifier other) {
                return true;
            }

            @Override
            public void assertValidFromUid(int requestorUid) {
                throw new SecurityException("failure");
            }

            @Override
            public int describeContents() { return 0; }
            @Override
            public void writeToParcel(Parcel dest, int flags) {}
        }

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        UidAwareNetworkSpecifier networkSpecifier = new UidAwareNetworkSpecifier();
        NetworkRequest networkRequest = newWifiRequestBuilder().setNetworkSpecifier(
                networkSpecifier).build();
        TestNetworkCallback networkCallback = new TestNetworkCallback();
        try {
            mCm.requestNetwork(networkRequest, networkCallback);
            fail("Network request with spoofed UID did not throw a SecurityException");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testInvalidSignalStrength() {
        NetworkRequest r = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_WIFI)
                .setSignalStrength(-75)
                .build();
        // Registering a NetworkCallback with signal strength but w/o NETWORK_SIGNAL_STRENGTH_WAKEUP
        // permission should get SecurityException.
        try {
            mCm.registerNetworkCallback(r, new NetworkCallback());
            fail("Expected SecurityException filing a callback with signal strength");
        } catch (SecurityException expected) {
            // expected
        }

        try {
            mCm.registerNetworkCallback(r, PendingIntent.getService(
                    mServiceContext, 0, new Intent(), 0));
            fail("Expected SecurityException filing a callback with signal strength");
        } catch (SecurityException expected) {
            // expected
        }

        // Requesting a Network with signal strength should get IllegalArgumentException.
        try {
            mCm.requestNetwork(r, new NetworkCallback());
            fail("Expected IllegalArgumentException filing a request with signal strength");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            mCm.requestNetwork(r, PendingIntent.getService(
                    mServiceContext, 0, new Intent(), 0));
            fail("Expected IllegalArgumentException filing a request with signal strength");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testRegisterDefaultNetworkCallback() throws Exception {
        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

        // Create a TRANSPORT_CELLULAR request to keep the mobile interface up
        // whenever Wi-Fi is up. Without this, the mobile network agent is
        // reaped before any other activity can take place.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);
        cellNetworkCallback.assertNoCallback();

        // Bring up cell and expect CALLBACK_AVAILABLE.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi and expect CALLBACK_AVAILABLE.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring down cell. Expect no default network callback, since it wasn't the default.
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up cell. Expect no default network callback, since it won't be the default.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring down wifi. Expect the default network callback to notified of LOST wifi
        // followed by AVAILABLE cell.
        mWiFiNetworkAgent.disconnect();
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultNetworkCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        final int uid = Process.myUid();
        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        vpnNetworkAgent.disconnect();
        defaultNetworkCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());
    }

    @Test
    public void testAdditionalStateCallbacks() throws Exception {
        // File a network request for mobile.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        // Bring up the mobile network.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        // We should get onAvailable(), onCapabilitiesChanged(), and
        // onLinkPropertiesChanged() in rapid succession. Additionally, we
        // should get onCapabilitiesChanged() when the mobile network validates.
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Update LinkProperties.
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("foonet_data0");
        mCellNetworkAgent.sendLinkProperties(lp);
        // We should get onLinkPropertiesChanged().
        cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Suspend the network.
        mCellNetworkAgent.suspend();
        cellNetworkCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_SUSPENDED,
                mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.SUSPENDED, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Register a garden variety default network request.
        TestNetworkCallback dfltNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(dfltNetworkCallback);
        // We should get onAvailable(), onCapabilitiesChanged(), onLinkPropertiesChanged(),
        // as well as onNetworkSuspended() in rapid succession.
        dfltNetworkCallback.expectAvailableAndSuspendedCallbacks(mCellNetworkAgent, true);
        dfltNetworkCallback.assertNoCallback();
        mCm.unregisterNetworkCallback(dfltNetworkCallback);

        mCellNetworkAgent.resume();
        cellNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_SUSPENDED,
                mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.RESUMED, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        dfltNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(dfltNetworkCallback);
        // This time onNetworkSuspended should not be called.
        dfltNetworkCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        dfltNetworkCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(dfltNetworkCallback);
        mCm.unregisterNetworkCallback(cellNetworkCallback);
    }

    private void setCaptivePortalMode(int mode) {
        ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.CAPTIVE_PORTAL_MODE, mode);
    }

    private void setAlwaysOnNetworks(boolean enable) {
        ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.MOBILE_DATA_ALWAYS_ON, enable ? 1 : 0);
        mService.updateAlwaysOnNetworks();
        waitForIdle();
    }

    private void setPrivateDnsSettings(String mode, String specifier) {
        final ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putString(cr, Settings.Global.PRIVATE_DNS_MODE, mode);
        Settings.Global.putString(cr, Settings.Global.PRIVATE_DNS_SPECIFIER, specifier);
        mService.updatePrivateDnsSettings();
        waitForIdle();
    }

    private boolean isForegroundNetwork(MockNetworkAgent network) {
        NetworkCapabilities nc = mCm.getNetworkCapabilities(network.getNetwork());
        assertNotNull(nc);
        return nc.hasCapability(NET_CAPABILITY_FOREGROUND);
    }

    @Test
    public void testBackgroundNetworks() throws Exception {
        // Create a background request. We can't do this ourselves because ConnectivityService
        // doesn't have an API for it. So just turn on mobile data always on.
        setAlwaysOnNetworks(true);
        final NetworkRequest request = new NetworkRequest.Builder().build();
        final NetworkRequest fgRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_FOREGROUND).build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        final TestNetworkCallback fgCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);
        mCm.registerNetworkCallback(fgRequest, fgCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        fgCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        // When wifi connects, cell lingers.
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        fgCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        fgCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        fgCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // When lingering is complete, cell is still there but is now in the background.
        waitForIdle();
        int timeoutMs = TEST_LINGER_DELAY_MS + TEST_LINGER_DELAY_MS / 4;
        fgCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent, timeoutMs);
        // Expect a network capabilities update sans FOREGROUND.
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertFalse(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // File a cell request and check that cell comes into the foreground.
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        final TestNetworkCallback cellCallback = new TestNetworkCallback();
        mCm.requestNetwork(cellRequest, cellCallback);
        // NOTE: This request causes the network's capabilities to change. This
        // is currently delivered before the onAvailable() callbacks.
        // TODO: Fix this.
        cellCallback.expectCapabilitiesWith(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        cellCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        fgCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        // Expect a network capabilities update with FOREGROUND, because the most recent
        // request causes its state to change.
        callback.expectCapabilitiesWith(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // Release the request. The network immediately goes into the background, since it was not
        // lingering.
        mCm.unregisterNetworkCallback(cellCallback);
        fgCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        // Expect a network capabilities update sans FOREGROUND.
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertFalse(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // Disconnect wifi and check that cell is foreground again.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        fgCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        fgCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(fgCallback);
    }

    @Ignore // This test has instrinsic chances of spurious failures: ignore for continuous testing.
    public void benchmarkRequestRegistrationAndCallbackDispatch() throws Exception {
        // TODO: turn this unit test into a real benchmarking test.
        // Benchmarks connecting and switching performance in the presence of a large number of
        // NetworkRequests.
        // 1. File NUM_REQUESTS requests.
        // 2. Have a network connect. Wait for NUM_REQUESTS onAvailable callbacks to fire.
        // 3. Have a new network connect and outscore the previous. Wait for NUM_REQUESTS onLosing
        //    and NUM_REQUESTS onAvailable callbacks to fire.
        // See how long it took.
        final int NUM_REQUESTS = 90;
        final int REGISTER_TIME_LIMIT_MS = 200;
        final int CONNECT_TIME_LIMIT_MS = 60;
        final int SWITCH_TIME_LIMIT_MS = 60;
        final int UNREGISTER_TIME_LIMIT_MS = 20;

        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        final NetworkCallback[] callbacks = new NetworkCallback[NUM_REQUESTS];
        final CountDownLatch availableLatch = new CountDownLatch(NUM_REQUESTS);
        final CountDownLatch losingLatch = new CountDownLatch(NUM_REQUESTS);

        for (int i = 0; i < NUM_REQUESTS; i++) {
            callbacks[i] = new NetworkCallback() {
                @Override public void onAvailable(Network n) { availableLatch.countDown(); }
                @Override public void onLosing(Network n, int t) { losingLatch.countDown(); }
            };
        }

        assertTimeLimit("Registering callbacks", REGISTER_TIME_LIMIT_MS, () -> {
            for (NetworkCallback cb : callbacks) {
                mCm.registerNetworkCallback(request, cb);
            }
        });

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        // Don't request that the network validate, because otherwise connect() will block until
        // the network gets NET_CAPABILITY_VALIDATED, after all the callbacks below have fired,
        // and we won't actually measure anything.
        mCellNetworkAgent.connect(false);

        long onAvailableDispatchingDuration = durationOf(() -> {
            awaitLatch(availableLatch, 10 * CONNECT_TIME_LIMIT_MS);
        });
        Log.d(TAG, String.format("Dispatched %d of %d onAvailable callbacks in %dms",
                NUM_REQUESTS - availableLatch.getCount(), NUM_REQUESTS,
                onAvailableDispatchingDuration));
        assertTrue(String.format("Dispatching %d onAvailable callbacks in %dms, expected %dms",
                NUM_REQUESTS, onAvailableDispatchingDuration, CONNECT_TIME_LIMIT_MS),
                onAvailableDispatchingDuration <= CONNECT_TIME_LIMIT_MS);

        // Give wifi a high enough score that we'll linger cell when wifi comes up.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.adjustScore(40);
        mWiFiNetworkAgent.connect(false);

        long onLostDispatchingDuration = durationOf(() -> {
            awaitLatch(losingLatch, 10 * SWITCH_TIME_LIMIT_MS);
        });
        Log.d(TAG, String.format("Dispatched %d of %d onLosing callbacks in %dms",
                NUM_REQUESTS - losingLatch.getCount(), NUM_REQUESTS, onLostDispatchingDuration));
        assertTrue(String.format("Dispatching %d onLosing callbacks in %dms, expected %dms",
                NUM_REQUESTS, onLostDispatchingDuration, SWITCH_TIME_LIMIT_MS),
                onLostDispatchingDuration <= SWITCH_TIME_LIMIT_MS);

        assertTimeLimit("Unregistering callbacks", UNREGISTER_TIME_LIMIT_MS, () -> {
            for (NetworkCallback cb : callbacks) {
                mCm.unregisterNetworkCallback(cb);
            }
        });
    }

    private long durationOf(Runnable fn) {
        long startTime = SystemClock.elapsedRealtime();
        fn.run();
        return SystemClock.elapsedRealtime() - startTime;
    }

    private void assertTimeLimit(String descr, long timeLimit, Runnable fn) {
        long timeTaken = durationOf(fn);
        String msg = String.format("%s: took %dms, limit was %dms", descr, timeTaken, timeLimit);
        Log.d(TAG, msg);
        assertTrue(msg, timeTaken <= timeLimit);
    }

    private boolean awaitLatch(CountDownLatch l, long timeoutMs) {
        try {
            return l.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {}
        return false;
    }

    @Test
    public void testMobileDataAlwaysOn() throws Exception {
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        final HandlerThread handlerThread = new HandlerThread("MobileDataAlwaysOnFactory");
        handlerThread.start();
        NetworkCapabilities filter = new NetworkCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET);
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to start looking for a network.
        testFactory.expectAddRequestsWithScores(0);  // Score 0 as the request is not served yet.
        testFactory.register();
        testFactory.waitForNetworkRequests(1);
        assertTrue(testFactory.getMyStartRequested());

        // Bring up wifi. The factory stops looking for a network.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        // Score 60 - 40 penalty for not validated yet, then 60 when it validates
        testFactory.expectAddRequestsWithScores(20, 60);
        mWiFiNetworkAgent.connect(true);
        testFactory.waitForRequests();
        assertFalse(testFactory.getMyStartRequested());

        ContentResolver cr = mServiceContext.getContentResolver();

        // Turn on mobile data always on. The factory starts looking again.
        testFactory.expectAddRequestsWithScores(0);  // Always on requests comes up with score 0
        setAlwaysOnNetworks(true);
        testFactory.waitForNetworkRequests(2);
        assertTrue(testFactory.getMyStartRequested());

        // Bring up cell data and check that the factory stops looking.
        assertLength(1, mCm.getAllNetworks());
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        testFactory.expectAddRequestsWithScores(10, 50);  // Unvalidated, then validated
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        testFactory.waitForNetworkRequests(2);
        assertFalse(testFactory.getMyStartRequested());  // Because the cell network outscores us.

        // Check that cell data stays up.
        waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertLength(2, mCm.getAllNetworks());

        // Turn off mobile data always on and expect the request to disappear...
        testFactory.expectRemoveRequests(1);
        setAlwaysOnNetworks(false);
        testFactory.waitForNetworkRequests(1);

        // ...  and cell data to be torn down.
        cellNetworkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        assertLength(1, mCm.getAllNetworks());

        testFactory.unregister();
        mCm.unregisterNetworkCallback(cellNetworkCallback);
        handlerThread.quit();
    }

    @Test
    public void testAvoidBadWifiSetting() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final WrappedMultinetworkPolicyTracker tracker = mService.getMultinetworkPolicyTracker();
        final String settingName = Settings.Global.NETWORK_AVOID_BAD_WIFI;

        tracker.configRestrictsAvoidBadWifi = false;
        String[] values = new String[] {null, "0", "1"};
        for (int i = 0; i < values.length; i++) {
            Settings.Global.putInt(cr, settingName, 1);
            tracker.reevaluate();
            waitForIdle();
            String msg = String.format("config=false, setting=%s", values[i]);
            assertTrue(mService.avoidBadWifi());
            assertFalse(msg, tracker.shouldNotifyWifiUnvalidated());
        }

        tracker.configRestrictsAvoidBadWifi = true;

        Settings.Global.putInt(cr, settingName, 0);
        tracker.reevaluate();
        waitForIdle();
        assertFalse(mService.avoidBadWifi());
        assertFalse(tracker.shouldNotifyWifiUnvalidated());

        Settings.Global.putInt(cr, settingName, 1);
        tracker.reevaluate();
        waitForIdle();
        assertTrue(mService.avoidBadWifi());
        assertFalse(tracker.shouldNotifyWifiUnvalidated());

        Settings.Global.putString(cr, settingName, null);
        tracker.reevaluate();
        waitForIdle();
        assertFalse(mService.avoidBadWifi());
        assertTrue(tracker.shouldNotifyWifiUnvalidated());
    }

    @Test
    public void testAvoidBadWifi() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final WrappedMultinetworkPolicyTracker tracker = mService.getMultinetworkPolicyTracker();

        // Pretend we're on a carrier that restricts switching away from bad wifi.
        tracker.configRestrictsAvoidBadWifi = true;

        // File a request for cell to ensure it doesn't go down.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        NetworkRequest validatedWifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_VALIDATED)
                .build();
        TestNetworkCallback validatedWifiCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(validatedWifiRequest, validatedWifiCallback);

        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 0);
        tracker.reevaluate();

        // Bring up validated cell.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        Network cellNetwork = mCellNetworkAgent.getNetwork();

        // Bring up validated wifi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        Network wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Fail validation on wifi.
        mWiFiNetworkAgent.setNetworkInvalid();
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        validatedWifiCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Because avoid bad wifi is off, we don't switch to cellular.
        defaultCallback.assertNoCallback();
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);

        // Simulate switching to a carrier that does not restrict avoiding bad wifi, and expect
        // that we switch back to cell.
        tracker.configRestrictsAvoidBadWifi = false;
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Switch back to a restrictive carrier.
        tracker.configRestrictsAvoidBadWifi = true;
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);

        // Simulate the user selecting "switch" on the dialog, and check that we switch to cell.
        mCm.setAvoidUnvalidated(wifiNetwork);
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Disconnect and reconnect wifi to clear the one-time switch above.
        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Fail validation on wifi and expect the dialog to appear.
        mWiFiNetworkAgent.setNetworkInvalid();
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        validatedWifiCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Simulate the user selecting "switch" and checking the don't ask again checkbox.
        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 1);
        tracker.reevaluate();

        // We now switch to cell.
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Simulate the user turning the cellular fallback setting off and then on.
        // We switch to wifi and then to cell.
        Settings.Global.putString(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, null);
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);
        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 1);
        tracker.reevaluate();
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // If cell goes down, we switch to wifi.
        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        validatedWifiCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(cellNetworkCallback);
        mCm.unregisterNetworkCallback(validatedWifiCallback);
        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testMeteredMultipathPreferenceSetting() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final WrappedMultinetworkPolicyTracker tracker = mService.getMultinetworkPolicyTracker();
        final String settingName = Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE;

        for (int config : Arrays.asList(0, 3, 2)) {
            for (String setting: Arrays.asList(null, "0", "2", "1")) {
                tracker.configMeteredMultipathPreference = config;
                Settings.Global.putString(cr, settingName, setting);
                tracker.reevaluate();
                waitForIdle();

                final int expected = (setting != null) ? Integer.parseInt(setting) : config;
                String msg = String.format("config=%d, setting=%s", config, setting);
                assertEquals(msg, expected, mCm.getMultipathPreference(null));
            }
        }
    }

    /**
     * Validate that a satisfied network request does not trigger onUnavailable() once the
     * time-out period expires.
     */
    @Test
    public void testSatisfiedNetworkRequestDoesNotTriggerOnUnavailable() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(nr, networkCallback, TEST_REQUEST_TIMEOUT_MS);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacks(mWiFiNetworkAgent, false, false, false,
                TEST_CALLBACK_TIMEOUT_MS);

        // pass timeout and validate that UNAVAILABLE is not called
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that a satisfied network request followed by a disconnected (lost) network does
     * not trigger onUnavailable() once the time-out period expires.
     */
    @Test
    public void testSatisfiedThenLostNetworkRequestDoesNotTriggerOnUnavailable() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(nr, networkCallback, TEST_REQUEST_TIMEOUT_MS);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacks(mWiFiNetworkAgent, false, false, false,
                TEST_CALLBACK_TIMEOUT_MS);
        mWiFiNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);

        // Validate that UNAVAILABLE is not called
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that when a time-out is specified for a network request the onUnavailable()
     * callback is called when time-out expires. Then validate that if network request is
     * (somehow) satisfied - the callback isn't called later.
     */
    @Test
    public void testTimedoutNetworkRequest() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 10;
        mCm.requestNetwork(nr, networkCallback, timeoutMs);

        // pass timeout and validate that UNAVAILABLE is called
        networkCallback.expectCallback(CallbackState.UNAVAILABLE, null);

        // create a network satisfying request - validate that request not triggered
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that when a network request is unregistered (cancelled), no posterior event can
     * trigger the callback.
     */
    @Test
    public void testNoCallbackAfterUnregisteredNetworkRequest() {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 10;

        mCm.requestNetwork(nr, networkCallback, timeoutMs);
        mCm.unregisterNetworkCallback(networkCallback);
        // Regardless of the timeout, unregistering the callback in ConnectivityManager ensures
        // that this callback will not be called.
        networkCallback.assertNoCallback();

        // create a network satisfying request - validate that request not triggered
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.assertNoCallback();
    }

    @Test
    public void testUnfulfillableNetworkRequest() throws Exception {
        runUnfulfillableNetworkRequest(false);
    }

    @Test
    public void testUnfulfillableNetworkRequestAfterUnregister() throws Exception {
        runUnfulfillableNetworkRequest(true);
    }

    /**
     * Validate the callback flow for a factory releasing a request as unfulfillable.
     */
    private void runUnfulfillableNetworkRequest(boolean preUnregister) throws Exception {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();

        final HandlerThread handlerThread = new HandlerThread("testUnfulfillableNetworkRequest");
        handlerThread.start();
        NetworkCapabilities filter = new NetworkCapabilities()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET);
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to receive the default request.
        testFactory.expectAddRequestsWithScores(0);
        testFactory.register();
        SparseArray<NetworkRequest> requests = testFactory.waitForNetworkRequests(1);

        assertEquals(1, requests.size()); // have 1 request at this point
        int origRequestId = requests.valueAt(0).requestId;

        // Now file the test request and expect it.
        testFactory.expectAddRequestsWithScores(0);
        mCm.requestNetwork(nr, networkCallback);
        requests = testFactory.waitForNetworkRequests(2); // have 2 requests at this point

        int newRequestId = 0;
        for (int i = 0; i < requests.size(); ++i) {
            if (requests.valueAt(i).requestId != origRequestId) {
                newRequestId = requests.valueAt(i).requestId;
                break;
            }
        }

        testFactory.expectRemoveRequests(1);
        if (preUnregister) {
            mCm.unregisterNetworkCallback(networkCallback);

            // Simulate the factory releasing the request as unfulfillable: no-op since
            // the callback has already been unregistered (but a test that no exceptions are
            // thrown).
            testFactory.triggerUnfulfillable(requests.get(newRequestId));
        } else {
            // Simulate the factory releasing the request as unfulfillable and expect onUnavailable!
            testFactory.triggerUnfulfillable(requests.get(newRequestId));

            networkCallback.expectCallback(CallbackState.UNAVAILABLE, null);
            testFactory.waitForRequests();

            // unregister network callback - a no-op (since already freed by the
            // on-unavailable), but should not fail or throw exceptions.
            mCm.unregisterNetworkCallback(networkCallback);
        }

        testFactory.unregister();
        handlerThread.quit();
    }

    private static class TestKeepaliveCallback extends PacketKeepaliveCallback {

        public static enum CallbackType { ON_STARTED, ON_STOPPED, ON_ERROR };

        private class CallbackValue {
            public CallbackType callbackType;
            public int error;

            public CallbackValue(CallbackType type) {
                this.callbackType = type;
                this.error = PacketKeepalive.SUCCESS;
                assertTrue("onError callback must have error", type != CallbackType.ON_ERROR);
            }

            public CallbackValue(CallbackType type, int error) {
                this.callbackType = type;
                this.error = error;
                assertEquals("error can only be set for onError", type, CallbackType.ON_ERROR);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof CallbackValue &&
                        this.callbackType == ((CallbackValue) o).callbackType &&
                        this.error == ((CallbackValue) o).error;
            }

            @Override
            public String toString() {
                return String.format("%s(%s, %d)", getClass().getSimpleName(), callbackType, error);
            }
        }

        private final LinkedBlockingQueue<CallbackValue> mCallbacks = new LinkedBlockingQueue<>();

        @Override
        public void onStarted() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STARTED));
        }

        @Override
        public void onStopped() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STOPPED));
        }

        @Override
        public void onError(int error) {
            mCallbacks.add(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        private void expectCallback(CallbackValue callbackValue) {
            try {
                assertEquals(
                        callbackValue,
                        mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail(callbackValue.callbackType + " callback not seen after " + TIMEOUT_MS + " ms");
            }
        }

        public void expectStarted() {
            expectCallback(new CallbackValue(CallbackType.ON_STARTED));
        }

        public void expectStopped() {
            expectCallback(new CallbackValue(CallbackType.ON_STOPPED));
        }

        public void expectError(int error) {
            expectCallback(new CallbackValue(CallbackType.ON_ERROR, error));
        }
    }

    private static class TestSocketKeepaliveCallback extends SocketKeepalive.Callback {

        public enum CallbackType { ON_STARTED, ON_STOPPED, ON_ERROR };

        private class CallbackValue {
            public CallbackType callbackType;
            public int error;

            CallbackValue(CallbackType type) {
                this.callbackType = type;
                this.error = SocketKeepalive.SUCCESS;
                assertTrue("onError callback must have error", type != CallbackType.ON_ERROR);
            }

            CallbackValue(CallbackType type, int error) {
                this.callbackType = type;
                this.error = error;
                assertEquals("error can only be set for onError", type, CallbackType.ON_ERROR);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof CallbackValue
                        && this.callbackType == ((CallbackValue) o).callbackType
                        && this.error == ((CallbackValue) o).error;
            }

            @Override
            public String toString() {
                return String.format("%s(%s, %d)", getClass().getSimpleName(), callbackType,
                        error);
            }
        }

        private LinkedBlockingQueue<CallbackValue> mCallbacks = new LinkedBlockingQueue<>();
        private final Executor mExecutor;

        TestSocketKeepaliveCallback(@NonNull Executor executor) {
            mExecutor = executor;
        }

        @Override
        public void onStarted() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STARTED));
        }

        @Override
        public void onStopped() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STOPPED));
        }

        @Override
        public void onError(int error) {
            mCallbacks.add(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        private void expectCallback(CallbackValue callbackValue) {
            try {
                assertEquals(
                        callbackValue,
                        mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail(callbackValue.callbackType + " callback not seen after " + TIMEOUT_MS + " ms");
            }
        }

        public void expectStarted() {
            expectCallback(new CallbackValue(CallbackType.ON_STARTED));
        }

        public void expectStopped() {
            expectCallback(new CallbackValue(CallbackType.ON_STOPPED));
        }

        public void expectError(int error) {
            expectCallback(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        public void assertNoCallback() {
            waitForIdleSerialExecutor(mExecutor, TIMEOUT_MS);
            CallbackValue cv = mCallbacks.peek();
            assertNull("Unexpected callback: " + cv, cv);
        }
    }

    private Network connectKeepaliveNetwork(LinkProperties lp) {
        // Ensure the network is disconnected before we do anything.
        if (mWiFiNetworkAgent != null) {
            assertNull(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()));
        }

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        return mWiFiNetworkAgent.getNetwork();
    }

    @Test
    public void testPacketKeepalives() throws Exception {
        InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        InetAddress notMyIPv4 = InetAddress.getByName("192.0.2.35");
        InetAddress myIPv6 = InetAddress.getByName("2001:db8::1");
        InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        InetAddress dstIPv6 = InetAddress.getByName("2001:4860:4860::8888");

        final int validKaInterval = 15;
        final int invalidKaInterval = 9;

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));

        Network notMyNet = new Network(61234);
        Network myNet = connectKeepaliveNetwork(lp);

        TestKeepaliveCallback callback = new TestKeepaliveCallback();
        PacketKeepalive ka;

        // Attempt to start keepalives with invalid parameters and check for errors.
        ka = mCm.startNattKeepalive(notMyNet, validKaInterval, callback, myIPv4, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_NETWORK);

        ka = mCm.startNattKeepalive(myNet, invalidKaInterval, callback, myIPv4, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_INTERVAL);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 1234, dstIPv6);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv6, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        // NAT-T is only supported for IPv4.
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv6, 1234, dstIPv6);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 123456, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_PORT);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 123456, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_PORT);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);

        // Check that a started keepalive can be stopped.
        mWiFiNetworkAgent.setStartKeepaliveError(PacketKeepalive.SUCCESS);
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        mWiFiNetworkAgent.setStopKeepaliveError(PacketKeepalive.SUCCESS);
        ka.stop();
        callback.expectStopped();

        // Check that deleting the IP address stops the keepalive.
        LinkProperties bogusLp = new LinkProperties(lp);
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        bogusLp.removeLinkAddress(new LinkAddress(myIPv4, 25));
        bogusLp.addLinkAddress(new LinkAddress(notMyIPv4, 25));
        mWiFiNetworkAgent.sendLinkProperties(bogusLp);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);
        mWiFiNetworkAgent.sendLinkProperties(lp);

        // Check that a started keepalive is stopped correctly when the network disconnects.
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        mWiFiNetworkAgent.disconnect();
        waitFor(mWiFiNetworkAgent.getDisconnectedCV());
        callback.expectError(PacketKeepalive.ERROR_INVALID_NETWORK);

        // ... and that stopping it after that has no adverse effects.
        waitForIdle();
        final Network myNetAlias = myNet;
        assertNull(mCm.getNetworkCapabilities(myNetAlias));
        ka.stop();

        // Reconnect.
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveError(PacketKeepalive.SUCCESS);

        // Check that keepalive slots start from 1 and increment. The first one gets slot 1.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();

        // The second one gets slot 2.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(2);
        TestKeepaliveCallback callback2 = new TestKeepaliveCallback();
        PacketKeepalive ka2 = mCm.startNattKeepalive(
                myNet, validKaInterval, callback2, myIPv4, 6789, dstIPv4);
        callback2.expectStarted();

        // Now stop the first one and create a third. This also gets slot 1.
        ka.stop();
        callback.expectStopped();

        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        TestKeepaliveCallback callback3 = new TestKeepaliveCallback();
        PacketKeepalive ka3 = mCm.startNattKeepalive(
                myNet, validKaInterval, callback3, myIPv4, 9876, dstIPv4);
        callback3.expectStarted();

        ka2.stop();
        callback2.expectStopped();

        ka3.stop();
        callback3.expectStopped();
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    // Helper method to prepare the executor and run test
    private void runTestWithSerialExecutors(ThrowingConsumer<Executor> functor) throws Exception {
        final ExecutorService executorSingleThread = Executors.newSingleThreadExecutor();
        final Executor executorInline = (Runnable r) -> r.run();
        functor.accept(executorSingleThread);
        executorSingleThread.shutdown();
        functor.accept(executorInline);
    }

    @Test
    public void testNattSocketKeepalives() throws Exception {
        runTestWithSerialExecutors(executor -> doTestNattSocketKeepalivesWithExecutor(executor));
        runTestWithSerialExecutors(executor -> doTestNattSocketKeepalivesFdWithExecutor(executor));
    }

    private void doTestNattSocketKeepalivesWithExecutor(Executor executor) throws Exception {
        // TODO: 1. Move this outside of ConnectivityServiceTest.
        //       2. Make test to verify that Nat-T keepalive socket is created by IpSecService.
        //       3. Mock ipsec service.
        final InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        final InetAddress notMyIPv4 = InetAddress.getByName("192.0.2.35");
        final InetAddress myIPv6 = InetAddress.getByName("2001:db8::1");
        final InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        final InetAddress dstIPv6 = InetAddress.getByName("2001:4860:4860::8888");

        final int validKaInterval = 15;
        final int invalidKaInterval = 9;

        final IpSecManager mIpSec = (IpSecManager) mContext.getSystemService(Context.IPSEC_SERVICE);
        final UdpEncapsulationSocket testSocket = mIpSec.openUdpEncapsulationSocket();
        final int srcPort = testSocket.getPort();

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));

        Network notMyNet = new Network(61234);
        Network myNet = connectKeepaliveNetwork(lp);

        TestSocketKeepaliveCallback callback = new TestSocketKeepaliveCallback(executor);

        // Attempt to start keepalives with invalid parameters and check for errors.
        // Invalid network.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                notMyNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_NETWORK);
        }

        // Invalid interval.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(invalidKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_INTERVAL);
        }

        // Invalid destination.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }

        // Invalid source;
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv6, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }

        // NAT-T is only supported for IPv4.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv6, dstIPv6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }

        // Sanity check before testing started keepalive.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_UNSUPPORTED);
        }

        // Check that a started keepalive can be stopped.
        mWiFiNetworkAgent.setStartKeepaliveError(SocketKeepalive.SUCCESS);
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            mWiFiNetworkAgent.setStopKeepaliveError(SocketKeepalive.SUCCESS);
            ka.stop();
            callback.expectStopped();

            // Check that keepalive could be restarted.
            ka.start(validKaInterval);
            callback.expectStarted();
            ka.stop();
            callback.expectStopped();

            // Check that keepalive can be restarted without waiting for callback.
            ka.start(validKaInterval);
            callback.expectStarted();
            ka.stop();
            ka.start(validKaInterval);
            callback.expectStopped();
            callback.expectStarted();
            ka.stop();
            callback.expectStopped();
        }

        // Check that deleting the IP address stops the keepalive.
        LinkProperties bogusLp = new LinkProperties(lp);
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            bogusLp.removeLinkAddress(new LinkAddress(myIPv4, 25));
            bogusLp.addLinkAddress(new LinkAddress(notMyIPv4, 25));
            mWiFiNetworkAgent.sendLinkProperties(bogusLp);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
            mWiFiNetworkAgent.sendLinkProperties(lp);
        }

        // Check that a started keepalive is stopped correctly when the network disconnects.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            mWiFiNetworkAgent.disconnect();
            waitFor(mWiFiNetworkAgent.getDisconnectedCV());
            callback.expectError(SocketKeepalive.ERROR_INVALID_NETWORK);

            // ... and that stopping it after that has no adverse effects.
            waitForIdle();
            final Network myNetAlias = myNet;
            assertNull(mCm.getNetworkCapabilities(myNetAlias));
            ka.stop();
            callback.assertNoCallback();
        }

        // Reconnect.
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveError(SocketKeepalive.SUCCESS);

        // Check that keepalive slots start from 1 and increment. The first one gets slot 1.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        int srcPort2 = 0;
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();

            // The second one gets slot 2.
            mWiFiNetworkAgent.setExpectedKeepaliveSlot(2);
            final UdpEncapsulationSocket testSocket2 = mIpSec.openUdpEncapsulationSocket();
            srcPort2 = testSocket2.getPort();
            TestSocketKeepaliveCallback callback2 = new TestSocketKeepaliveCallback(executor);
            try (SocketKeepalive ka2 = mCm.createSocketKeepalive(
                    myNet, testSocket2, myIPv4, dstIPv4, executor, callback2)) {
                ka2.start(validKaInterval);
                callback2.expectStarted();

                ka.stop();
                callback.expectStopped();

                ka2.stop();
                callback2.expectStopped();

                testSocket.close();
                testSocket2.close();
            }
        }

        // Check that there is no port leaked after all keepalives and sockets are closed.
        // TODO: enable this check after ensuring a valid free port. See b/129512753#comment7.
        // assertFalse(isUdpPortInUse(srcPort));
        // assertFalse(isUdpPortInUse(srcPort2));

        mWiFiNetworkAgent.disconnect();
        waitFor(mWiFiNetworkAgent.getDisconnectedCV());
        mWiFiNetworkAgent = null;
    }

    @Test
    public void testTcpSocketKeepalives() throws Exception {
        runTestWithSerialExecutors(executor -> doTestTcpSocketKeepalivesWithExecutor(executor));
    }

    private void doTestTcpSocketKeepalivesWithExecutor(Executor executor) throws Exception {
        final int srcPortV4 = 12345;
        final int srcPortV6 = 23456;
        final InetAddress myIPv4 = InetAddress.getByName("127.0.0.1");
        final InetAddress myIPv6 = InetAddress.getByName("::1");

        final int validKaInterval = 15;

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("127.0.0.254")));

        final Network notMyNet = new Network(61234);
        final Network myNet = connectKeepaliveNetwork(lp);

        final Socket testSocketV4 = new Socket();
        final Socket testSocketV6 = new Socket();

        TestSocketKeepaliveCallback callback = new TestSocketKeepaliveCallback(executor);

        // Attempt to start Tcp keepalives with invalid parameters and check for errors.
        // Invalid network.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            notMyNet, testSocketV4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_NETWORK);
        }

        // Invalid Socket (socket is not bound with IPv4 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        // Invalid Socket (socket is not bound with IPv6 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        // Bind the socket address
        testSocketV4.bind(new InetSocketAddress(myIPv4, srcPortV4));
        testSocketV6.bind(new InetSocketAddress(myIPv6, srcPortV6));

        // Invalid Socket (socket is bound with IPv4 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        // Invalid Socket (socket is bound with IPv6 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        testSocketV4.close();
        testSocketV6.close();

        mWiFiNetworkAgent.disconnect();
        waitFor(mWiFiNetworkAgent.getDisconnectedCV());
        mWiFiNetworkAgent = null;
    }

    private void doTestNattSocketKeepalivesFdWithExecutor(Executor executor) throws Exception {
        final InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        final InetAddress anyIPv4 = InetAddress.getByName("0.0.0.0");
        final InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        final int validKaInterval = 15;

        // Prepare the target network.
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));
        Network myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveError(SocketKeepalive.SUCCESS);
        mWiFiNetworkAgent.setStopKeepaliveError(SocketKeepalive.SUCCESS);

        TestSocketKeepaliveCallback callback = new TestSocketKeepaliveCallback(executor);

        // Prepare the target file descriptor, keep only one instance.
        final IpSecManager mIpSec = (IpSecManager) mContext.getSystemService(Context.IPSEC_SERVICE);
        final UdpEncapsulationSocket testSocket = mIpSec.openUdpEncapsulationSocket();
        final int srcPort = testSocket.getPort();
        final ParcelFileDescriptor testPfd =
                ParcelFileDescriptor.dup(testSocket.getFileDescriptor());
        testSocket.close();
        assertTrue(isUdpPortInUse(srcPort));

        // Start keepalive and explicit make the variable goes out of scope with try-with-resources
        // block.
        try (SocketKeepalive ka = mCm.createNattKeepalive(
                myNet, testPfd, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            ka.stop();
            callback.expectStopped();
        }

        // Check that the ParcelFileDescriptor is still valid after keepalive stopped,
        // ErrnoException with EBADF will be thrown if the socket is closed when checking local
        // address.
        assertTrue(isUdpPortInUse(srcPort));
        final InetSocketAddress sa =
                (InetSocketAddress) Os.getsockname(testPfd.getFileDescriptor());
        assertEquals(anyIPv4, sa.getAddress());

        testPfd.close();
        // TODO: enable this check after ensuring a valid free port. See b/129512753#comment7.
        // assertFalse(isUdpPortInUse(srcPort));

        mWiFiNetworkAgent.disconnect();
        waitFor(mWiFiNetworkAgent.getDisconnectedCV());
        mWiFiNetworkAgent = null;
    }

    private static boolean isUdpPortInUse(int port) {
        try (DatagramSocket ignored = new DatagramSocket(port)) {
            return false;
        } catch (IOException ignored) {
            return true;
        }
    }

    @Test
    public void testGetCaptivePortalServerUrl() throws Exception {
        String url = mCm.getCaptivePortalServerUrl();
        assertEquals("http://connectivitycheck.gstatic.com/generate_204", url);
    }

    private static class TestNetworkPinner extends NetworkPinner {
        public static boolean awaitPin(int timeoutMs) {
            synchronized(sLock) {
                if (sNetwork == null) {
                    try {
                        sLock.wait(timeoutMs);
                    } catch (InterruptedException e) {}
                }
                return sNetwork != null;
            }
        }

        public static boolean awaitUnpin(int timeoutMs) {
            synchronized(sLock) {
                if (sNetwork != null) {
                    try {
                        sLock.wait(timeoutMs);
                    } catch (InterruptedException e) {}
                }
                return sNetwork == null;
            }
        }
    }

    private void assertPinnedToWifiWithCellDefault() {
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getBoundNetworkForProcess());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    private void assertPinnedToWifiWithWifiDefault() {
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getBoundNetworkForProcess());
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    private void assertNotPinnedToWifi() {
        assertNull(mCm.getBoundNetworkForProcess());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    @Test
    public void testNetworkPinner() {
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .build();
        assertNull(mCm.getBoundNetworkForProcess());

        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        assertNull(mCm.getBoundNetworkForProcess());

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        // When wi-fi connects, expect to be pinned.
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithCellDefault();

        // Disconnect and expect the pin to drop.
        mWiFiNetworkAgent.disconnect();
        assertTrue(TestNetworkPinner.awaitUnpin(100));
        assertNotPinnedToWifi();

        // Reconnecting does not cause the pin to come back.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertFalse(TestNetworkPinner.awaitPin(100));
        assertNotPinnedToWifi();

        // Pinning while connected causes the pin to take effect immediately.
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithCellDefault();

        // Explicitly unpin and expect to use the default network again.
        TestNetworkPinner.unpin();
        assertNotPinnedToWifi();

        // Disconnect cell and wifi.
        ConditionVariable cv = waitForConnectivityBroadcasts(3);  // cell down, wifi up, wifi down.
        mCellNetworkAgent.disconnect();
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);

        // Pinning takes effect even if the pinned network is the default when the pin is set...
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithWifiDefault();

        // ... and is maintained even when that network is no longer the default.
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        assertPinnedToWifiWithCellDefault();
    }

    @Test
    public void testNetworkCallbackMaximum() {
        // We can only have 99 callbacks, because MultipathPolicyTracker is
        // already one of them.
        final int MAX_REQUESTS = 99;
        final int CALLBACKS = 89;
        final int INTENTS = 10;
        assertEquals(MAX_REQUESTS, CALLBACKS + INTENTS);

        NetworkRequest networkRequest = new NetworkRequest.Builder().build();
        ArrayList<Object> registered = new ArrayList<>();

        int j = 0;
        while (j++ < CALLBACKS / 2) {
            NetworkCallback cb = new NetworkCallback();
            mCm.requestNetwork(networkRequest, cb);
            registered.add(cb);
        }
        while (j++ < CALLBACKS) {
            NetworkCallback cb = new NetworkCallback();
            mCm.registerNetworkCallback(networkRequest, cb);
            registered.add(cb);
        }
        j = 0;
        while (j++ < INTENTS / 2) {
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, new Intent("a" + j), 0);
            mCm.requestNetwork(networkRequest, pi);
            registered.add(pi);
        }
        while (j++ < INTENTS) {
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, new Intent("b" + j), 0);
            mCm.registerNetworkCallback(networkRequest, pi);
            registered.add(pi);
        }

        // Test that the limit is enforced when MAX_REQUESTS simultaneous requests are added.
        try {
            mCm.requestNetwork(networkRequest, new NetworkCallback());
            fail("Registering " + MAX_REQUESTS + " network requests did not throw exception");
        } catch (TooManyRequestsException expected) {}
        try {
            mCm.registerNetworkCallback(networkRequest, new NetworkCallback());
            fail("Registering " + MAX_REQUESTS + " network callbacks did not throw exception");
        } catch (TooManyRequestsException expected) {}
        try {
            mCm.requestNetwork(networkRequest,
                PendingIntent.getBroadcast(mContext, 0, new Intent("c"), 0));
            fail("Registering " + MAX_REQUESTS + " PendingIntent requests did not throw exception");
        } catch (TooManyRequestsException expected) {}
        try {
            mCm.registerNetworkCallback(networkRequest,
                PendingIntent.getBroadcast(mContext, 0, new Intent("d"), 0));
            fail("Registering " + MAX_REQUESTS
                    + " PendingIntent callbacks did not throw exception");
        } catch (TooManyRequestsException expected) {}

        for (Object o : registered) {
            if (o instanceof NetworkCallback) {
                mCm.unregisterNetworkCallback((NetworkCallback)o);
            }
            if (o instanceof PendingIntent) {
                mCm.unregisterNetworkCallback((PendingIntent)o);
            }
        }
        waitForIdle();

        // Test that the limit is not hit when MAX_REQUESTS requests are added and removed.
        for (int i = 0; i < MAX_REQUESTS; i++) {
            NetworkCallback networkCallback = new NetworkCallback();
            mCm.requestNetwork(networkRequest, networkCallback);
            mCm.unregisterNetworkCallback(networkCallback);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            NetworkCallback networkCallback = new NetworkCallback();
            mCm.registerNetworkCallback(networkRequest, networkCallback);
            mCm.unregisterNetworkCallback(networkCallback);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, new Intent("e" + i), 0);
            mCm.requestNetwork(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, new Intent("f" + i), 0);
            mCm.registerNetworkCallback(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
    }

    @Test
    public void testNetworkInfoOfTypeNone() {
        ConditionVariable broadcastCV = waitForConnectivityBroadcasts(1);

        verifyNoNetwork();
        MockNetworkAgent wifiAware = new MockNetworkAgent(TRANSPORT_WIFI_AWARE);
        assertNull(mCm.getActiveNetworkInfo());

        Network[] allNetworks = mCm.getAllNetworks();
        assertLength(1, allNetworks);
        Network network = allNetworks[0];
        NetworkCapabilities capabilities = mCm.getNetworkCapabilities(network);
        assertTrue(capabilities.hasTransport(TRANSPORT_WIFI_AWARE));

        final NetworkRequest request =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI_AWARE).build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up wifi aware network.
        wifiAware.connect(false, false);
        callback.expectAvailableCallbacksUnvalidated(wifiAware);

        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // TODO: getAllNetworkInfo is dirty and returns a non-empty array right from the start
        // of this test. Fix it and uncomment the assert below.
        //assertEmpty(mCm.getAllNetworkInfo());

        // Disconnect wifi aware network.
        wifiAware.disconnect();
        callback.expectCallbackLike((info) -> info.state == CallbackState.LOST, TIMEOUT_MS);
        mCm.unregisterNetworkCallback(callback);

        verifyNoNetwork();
        if (broadcastCV.block(10)) {
            fail("expected no broadcast, but got CONNECTIVITY_ACTION broadcast");
        }
    }

    @Test
    public void testDeprecatedAndUnsupportedOperations() throws Exception {
        final int TYPE_NONE = ConnectivityManager.TYPE_NONE;
        assertNull(mCm.getNetworkInfo(TYPE_NONE));
        assertNull(mCm.getNetworkForType(TYPE_NONE));
        assertNull(mCm.getLinkProperties(TYPE_NONE));
        assertFalse(mCm.isNetworkSupported(TYPE_NONE));

        assertException(() -> { mCm.networkCapabilitiesForType(TYPE_NONE); },
                IllegalArgumentException.class);

        Class<UnsupportedOperationException> unsupported = UnsupportedOperationException.class;
        assertException(() -> { mCm.startUsingNetworkFeature(TYPE_WIFI, ""); }, unsupported);
        assertException(() -> { mCm.stopUsingNetworkFeature(TYPE_WIFI, ""); }, unsupported);
        // TODO: let test context have configuration application target sdk version
        // and test that pre-M requesting for TYPE_NONE sends back APN_REQUEST_FAILED
        assertException(() -> { mCm.startUsingNetworkFeature(TYPE_NONE, ""); }, unsupported);
        assertException(() -> { mCm.stopUsingNetworkFeature(TYPE_NONE, ""); }, unsupported);
        assertException(() -> { mCm.requestRouteToHostAddress(TYPE_NONE, null); }, unsupported);
    }

    @Test
    public void testLinkPropertiesEnsuresDirectlyConnectedRoutes() {
        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(WIFI_IFNAME);
        LinkAddress myIpv4Address = new LinkAddress("192.168.12.3/24");
        RouteInfo myIpv4DefaultRoute = new RouteInfo((IpPrefix) null,
                NetworkUtils.numericToInetAddress("192.168.12.1"), lp.getInterfaceName());
        lp.addLinkAddress(myIpv4Address);
        lp.addRoute(myIpv4DefaultRoute);

        // Verify direct routes are added when network agent is first registered in
        // ConnectivityService.
        MockNetworkAgent networkAgent = new MockNetworkAgent(TRANSPORT_WIFI, lp);
        networkAgent.connect(true);
        networkCallback.expectCallback(CallbackState.AVAILABLE, networkAgent);
        networkCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES, networkAgent);
        CallbackInfo cbi = networkCallback.expectCallback(CallbackState.LINK_PROPERTIES,
                networkAgent);
        networkCallback.expectCallback(CallbackState.BLOCKED_STATUS, networkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, networkAgent);
        networkCallback.assertNoCallback();
        checkDirectlyConnectedRoutes(cbi.arg, Arrays.asList(myIpv4Address),
                Arrays.asList(myIpv4DefaultRoute));
        checkDirectlyConnectedRoutes(mCm.getLinkProperties(networkAgent.getNetwork()),
                Arrays.asList(myIpv4Address), Arrays.asList(myIpv4DefaultRoute));

        // Verify direct routes are added during subsequent link properties updates.
        LinkProperties newLp = new LinkProperties(lp);
        LinkAddress myIpv6Address1 = new LinkAddress("fe80::cafe/64");
        LinkAddress myIpv6Address2 = new LinkAddress("2001:db8::2/64");
        newLp.addLinkAddress(myIpv6Address1);
        newLp.addLinkAddress(myIpv6Address2);
        networkAgent.sendLinkProperties(newLp);
        cbi = networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, networkAgent);
        networkCallback.assertNoCallback();
        checkDirectlyConnectedRoutes(cbi.arg,
                Arrays.asList(myIpv4Address, myIpv6Address1, myIpv6Address2),
                Arrays.asList(myIpv4DefaultRoute));
        mCm.unregisterNetworkCallback(networkCallback);
    }

    @Test
    public void testStatsIfacesChanged() throws Exception {
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);

        Network[] onlyCell = new Network[] {mCellNetworkAgent.getNetwork()};
        Network[] onlyWifi = new Network[] {mWiFiNetworkAgent.getNetwork()};

        LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_IFNAME);

        // Simple connection should have updated ifaces
        mCellNetworkAgent.connect(false);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(
                        eq(onlyCell),
                        any(NetworkState[].class),
                        eq(MOBILE_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);

        // Default network switch should update ifaces.
        mWiFiNetworkAgent.connect(false);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        waitForIdle();
        assertEquals(wifiLp, mService.getActiveLinkProperties());
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(
                        eq(onlyWifi),
                        any(NetworkState[].class),
                        eq(WIFI_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);

        // Disconnect should update ifaces.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(
                        eq(onlyCell),
                        any(NetworkState[].class),
                        eq(MOBILE_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);

        // Metered change should update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(
                        eq(onlyCell),
                        any(NetworkState[].class),
                        eq(MOBILE_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);

        mCellNetworkAgent.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(
                        eq(onlyCell),
                        any(NetworkState[].class),
                        eq(MOBILE_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);

        // Captive portal change shouldn't update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        waitForIdle();
        verify(mStatsService, never())
                .forceUpdateIfaces(
                        eq(onlyCell),
                        any(NetworkState[].class),
                        eq(MOBILE_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);

        // Roaming change should update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(
                        eq(onlyCell),
                        any(NetworkState[].class),
                        eq(MOBILE_IFNAME));
        assertEquals(new VpnInfo[0], NetworkStatsFactory.getVpnInfos());
        reset(mStatsService);
    }

    @Test
    public void testBasicDnsConfigurationPushed() throws Exception {
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");

        // Clear any interactions that occur as a result of CS starting up.
        reset(mMockDnsResolver);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        waitForIdle();
        verify(mMockDnsResolver, never()).setResolverConfiguration(any());
        verifyNoMoreInteractions(mMockDnsResolver);

        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        // Add IPv4 and IPv6 default routes, because DNS-over-TLS code does
        // "is-reachable" testing in order to not program netd with unreachable
        // nameservers that it might try repeated to validate.
        cellLp.addLinkAddress(new LinkAddress("192.0.2.4/24"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("192.0.2.4"),
                MOBILE_IFNAME));
        cellLp.addLinkAddress(new LinkAddress("2001:db8:1::1/64"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("2001:db8:1::1"),
                MOBILE_IFNAME));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        mCellNetworkAgent.connect(false);
        waitForIdle();

        verify(mMockDnsResolver, times(1)).createNetworkCache(
                eq(mCellNetworkAgent.getNetwork().netId));
        // CS tells dnsresolver about the empty DNS config for this network.
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(any());
        reset(mMockDnsResolver);

        cellLp.addDnsServer(InetAddress.getByName("2001:db8::1"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        ResolverParamsParcel resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(1, resolvrParams.servers.length);
        assertTrue(ArrayUtils.contains(resolvrParams.servers, "2001:db8::1"));
        // Opportunistic mode.
        assertTrue(ArrayUtils.contains(resolvrParams.tlsServers, "2001:db8::1"));
        reset(mMockDnsResolver);

        cellLp.addDnsServer(InetAddress.getByName("192.0.2.1"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        // Opportunistic mode.
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);

        final String TLS_SPECIFIER = "tls.example.com";
        final String TLS_SERVER6 = "2001:db8:53::53";
        final InetAddress[] TLS_IPS = new InetAddress[]{ InetAddress.getByName(TLS_SERVER6) };
        final String[] TLS_SERVERS = new String[]{ TLS_SERVER6 };
        mCellNetworkAgent.mNmCallbacks.notifyPrivateDnsConfigResolved(
                new PrivateDnsConfig(TLS_SPECIFIER, TLS_IPS).toParcel());

        waitForIdle();
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);
    }

    @Test
    public void testPrivateDnsSettingsChange() throws Exception {
        // Clear any interactions that occur as a result of CS starting up.
        reset(mMockDnsResolver);

        // The default on Android is opportunistic mode ("Automatic").
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");

        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        waitForIdle();
        // CS tells netd about the empty DNS config for this network.
        verify(mMockDnsResolver, never()).setResolverConfiguration(any());
        verifyNoMoreInteractions(mMockDnsResolver);

        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        // Add IPv4 and IPv6 default routes, because DNS-over-TLS code does
        // "is-reachable" testing in order to not program netd with unreachable
        // nameservers that it might try repeated to validate.
        cellLp.addLinkAddress(new LinkAddress("192.0.2.4/24"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("192.0.2.4"),
                MOBILE_IFNAME));
        cellLp.addLinkAddress(new LinkAddress("2001:db8:1::1/64"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("2001:db8:1::1"),
                MOBILE_IFNAME));
        cellLp.addDnsServer(InetAddress.getByName("2001:db8::1"));
        cellLp.addDnsServer(InetAddress.getByName("192.0.2.1"));

        mCellNetworkAgent.sendLinkProperties(cellLp);
        mCellNetworkAgent.connect(false);
        waitForIdle();
        verify(mMockDnsResolver, times(1)).createNetworkCache(
                eq(mCellNetworkAgent.getNetwork().netId));
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        ResolverParamsParcel resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        // Opportunistic mode.
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);
        cellNetworkCallback.expectCallback(CallbackState.AVAILABLE, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES,
                mCellNetworkAgent);
        CallbackInfo cbi = cellNetworkCallback.expectCallback(
                CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.BLOCKED_STATUS, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertNull(((LinkProperties)cbi.arg).getPrivateDnsServerName());

        setPrivateDnsSettings(PRIVATE_DNS_MODE_OFF, "ignored.example.com");
        verify(mMockDnsResolver, times(1)).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);
        cellNetworkCallback.assertNoCallback();

        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);
        cellNetworkCallback.assertNoCallback();

        setPrivateDnsSettings(PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, "strict.example.com");
        // Can't test dns configuration for strict mode without properly mocking
        // out the DNS lookups, but can test that LinkProperties is updated.
        cbi = cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertTrue(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertEquals("strict.example.com", ((LinkProperties)cbi.arg).getPrivateDnsServerName());
    }

    @Test
    public void testLinkPropertiesWithPrivateDnsValidationEvents() throws Exception {
        // The default on Android is opportunistic mode ("Automatic").
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");

        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        waitForIdle();
        LinkProperties lp = new LinkProperties();
        mCellNetworkAgent.sendLinkProperties(lp);
        mCellNetworkAgent.connect(false);
        waitForIdle();
        cellNetworkCallback.expectCallback(CallbackState.AVAILABLE, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES,
                mCellNetworkAgent);
        CallbackInfo cbi = cellNetworkCallback.expectCallback(
                CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackState.BLOCKED_STATUS, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertNull(((LinkProperties)cbi.arg).getPrivateDnsServerName());
        Set<InetAddress> dnsServers = new HashSet<>();
        checkDnsServers(cbi.arg, dnsServers);

        // Send a validation event for a server that is not part of the current
        // resolver config. The validation event should be ignored.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "", "145.100.185.18", true);
        cellNetworkCallback.assertNoCallback();

        // Add a dns server to the LinkProperties.
        LinkProperties lp2 = new LinkProperties(lp);
        lp2.addDnsServer(InetAddress.getByName("145.100.185.16"));
        mCellNetworkAgent.sendLinkProperties(lp2);
        cbi = cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertNull(((LinkProperties)cbi.arg).getPrivateDnsServerName());
        dnsServers.add(InetAddress.getByName("145.100.185.16"));
        checkDnsServers(cbi.arg, dnsServers);

        // Send a validation event containing a hostname that is not part of
        // the current resolver config. The validation event should be ignored.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "145.100.185.16", "hostname", true);
        cellNetworkCallback.assertNoCallback();

        // Send a validation event where validation failed.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "145.100.185.16", "", false);
        cellNetworkCallback.assertNoCallback();

        // Send a validation event where validation succeeded for a server in
        // the current resolver config. A LinkProperties callback with updated
        // private dns fields should be sent.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "145.100.185.16", "", true);
        cbi = cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertTrue(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertNull(((LinkProperties)cbi.arg).getPrivateDnsServerName());
        checkDnsServers(cbi.arg, dnsServers);

        // The private dns fields in LinkProperties should be preserved when
        // the network agent sends unrelated changes.
        LinkProperties lp3 = new LinkProperties(lp2);
        lp3.setMtu(1300);
        mCellNetworkAgent.sendLinkProperties(lp3);
        cbi = cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertTrue(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertNull(((LinkProperties)cbi.arg).getPrivateDnsServerName());
        checkDnsServers(cbi.arg, dnsServers);
        assertEquals(1300, ((LinkProperties)cbi.arg).getMtu());

        // Removing the only validated server should affect the private dns
        // fields in LinkProperties.
        LinkProperties lp4 = new LinkProperties(lp3);
        lp4.removeDnsServer(InetAddress.getByName("145.100.185.16"));
        mCellNetworkAgent.sendLinkProperties(lp4);
        cbi = cellNetworkCallback.expectCallback(CallbackState.LINK_PROPERTIES,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(((LinkProperties)cbi.arg).isPrivateDnsActive());
        assertNull(((LinkProperties)cbi.arg).getPrivateDnsServerName());
        dnsServers.remove(InetAddress.getByName("145.100.185.16"));
        checkDnsServers(cbi.arg, dnsServers);
        assertEquals(1300, ((LinkProperties)cbi.arg).getMtu());
    }

    private void checkDirectlyConnectedRoutes(Object callbackObj,
            Collection<LinkAddress> linkAddresses, Collection<RouteInfo> otherRoutes) {
        assertTrue(callbackObj instanceof LinkProperties);
        LinkProperties lp = (LinkProperties) callbackObj;

        Set<RouteInfo> expectedRoutes = new ArraySet<>();
        expectedRoutes.addAll(otherRoutes);
        for (LinkAddress address : linkAddresses) {
            RouteInfo localRoute = new RouteInfo(address, null, lp.getInterfaceName());
            // Duplicates in linkAddresses are considered failures
            assertTrue(expectedRoutes.add(localRoute));
        }
        List<RouteInfo> observedRoutes = lp.getRoutes();
        assertEquals(expectedRoutes.size(), observedRoutes.size());
        assertTrue(observedRoutes.containsAll(expectedRoutes));
    }

    private static void checkDnsServers(Object callbackObj, Set<InetAddress> dnsServers) {
        assertTrue(callbackObj instanceof LinkProperties);
        LinkProperties lp = (LinkProperties) callbackObj;
        assertEquals(dnsServers.size(), lp.getDnsServers().size());
        assertTrue(lp.getDnsServers().containsAll(dnsServers));
    }

    private static <T> void assertEmpty(T[] ts) {
        int length = ts.length;
        assertEquals("expected empty array, but length was " + length, 0, length);
    }

    private static <T> void assertLength(int expected, T[] got) {
        int length = got.length;
        assertEquals(String.format("expected array of length %s, but length was %s for %s",
                expected, length, Arrays.toString(got)), expected, length);
    }

    private static <T> void assertException(Runnable block, Class<T> expected) {
        try {
            block.run();
            fail("Expected exception of type " + expected);
        } catch (Exception got) {
            if (!got.getClass().equals(expected)) {
                fail("Expected exception of type " + expected + " but got " + got);
            }
            return;
        }
    }

    @Test
    public void testVpnNetworkActive() {
        final int uid = Process.myUid();

        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback genericNotVpnNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        final NetworkRequest genericNotVpnRequest = new NetworkRequest.Builder().build();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN).build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(genericNotVpnRequest, genericNotVpnNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        mCm.registerDefaultNetworkCallback(defaultCallback);
        defaultCallback.assertNoCallback();

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        genericNotVpnNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        vpnNetworkCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        // VPN networks do not satisfy the default request and are automatically validated
        // by NetworkMonitor
        assertFalse(NetworkMonitorUtils.isValidationRequired(vpnNetworkAgent.mNetworkCapabilities));
        vpnNetworkAgent.setNetworkValid();

        vpnNetworkAgent.connect(false);
        mMockVpn.connect();
        mMockVpn.setUnderlyingNetworks(new Network[0]);

        genericNetworkCallback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        genericNetworkCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES, vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCapabilitiesLike(nc -> null == nc.getUids(), vpnNetworkAgent);
        defaultCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES, vpnNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        ranges.clear();
        vpnNetworkAgent.setUids(ranges);

        genericNetworkCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);

        // TODO : The default network callback should actually get a LOST call here (also see the
        // comment below for AVAILABLE). This is because ConnectivityService does not look at UID
        // ranges at all when determining whether a network should be rematched. In practice, VPNs
        // can't currently update their UIDs without disconnecting, so this does not matter too
        // much, but that is the reason the test here has to check for an update to the
        // capabilities instead of the expected LOST then AVAILABLE.
        defaultCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES, vpnNetworkAgent);

        ranges.add(new UidRange(uid, uid));
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.setUids(ranges);

        genericNetworkCallback.expectAvailableCallbacksValidated(vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectAvailableCallbacksValidated(vpnNetworkAgent);
        // TODO : Here like above, AVAILABLE would be correct, but because this can't actually
        // happen outside of the test, ConnectivityService does not rematch callbacks.
        defaultCallback.expectCallback(CallbackState.NETWORK_CAPABILITIES, vpnNetworkAgent);

        mWiFiNetworkAgent.disconnect();

        genericNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        genericNotVpnNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        vpnNetworkCallback.assertNoCallback();
        defaultCallback.assertNoCallback();

        vpnNetworkAgent.disconnect();

        genericNetworkCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        defaultCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        assertEquals(null, mCm.getActiveNetwork());

        mCm.unregisterNetworkCallback(genericNetworkCallback);
        mCm.unregisterNetworkCallback(wifiNetworkCallback);
        mCm.unregisterNetworkCallback(vpnNetworkCallback);
        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testVpnWithoutInternet() {
        final int uid = Process.myUid();

        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        defaultCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, false /* hasInternet */);
        mMockVpn.connect();

        defaultCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        vpnNetworkAgent.disconnect();
        defaultCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testVpnWithInternet() {
        final int uid = Process.myUid();

        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        defaultCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, true /* hasInternet */);
        mMockVpn.connect();

        defaultCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        vpnNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testVpnUnvalidated() throws Exception {
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(callback);

        // Bring up Ethernet.
        mEthernetNetworkAgent = new MockNetworkAgent(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mEthernetNetworkAgent);
        callback.assertNoCallback();

        // Bring up a VPN that has the INTERNET capability, initially unvalidated.
        final int uid = Process.myUid();
        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(false /* validated */, true /* hasInternet */);
        mMockVpn.connect();

        // Even though the VPN is unvalidated, it becomes the default network for our app.
        callback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        // TODO: this looks like a spurious callback.
        callback.expectCallback(CallbackState.NETWORK_CAPABILITIES, vpnNetworkAgent);
        callback.assertNoCallback();

        assertTrue(vpnNetworkAgent.getScore() > mEthernetNetworkAgent.getScore());
        assertEquals(ConnectivityConstants.VPN_DEFAULT_SCORE, vpnNetworkAgent.getScore());
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        NetworkCapabilities nc = mCm.getNetworkCapabilities(vpnNetworkAgent.getNetwork());
        assertFalse(nc.hasCapability(NET_CAPABILITY_VALIDATED));
        assertTrue(nc.hasCapability(NET_CAPABILITY_INTERNET));

        assertFalse(NetworkMonitorUtils.isValidationRequired(vpnNetworkAgent.mNetworkCapabilities));
        assertTrue(NetworkMonitorUtils.isPrivateDnsValidationRequired(
                vpnNetworkAgent.mNetworkCapabilities));

        // Pretend that the VPN network validates.
        vpnNetworkAgent.setNetworkValid();
        vpnNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        // Expect to see the validated capability, but no other changes, because the VPN is already
        // the default network for the app.
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, vpnNetworkAgent);
        callback.assertNoCallback();

        vpnNetworkAgent.disconnect();
        callback.expectCallback(CallbackState.LOST, vpnNetworkAgent);
        callback.expectAvailableCallbacksValidated(mEthernetNetworkAgent);
    }

    @Test
    public void testVpnSetUnderlyingNetworks() {
        final int uid = Process.myUid();

        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN)
                .build();
        NetworkCapabilities nc;
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        vpnNetworkCallback.assertNoCallback();

        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.connect();
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, false /* hasInternet */);

        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        nc = mCm.getNetworkCapabilities(vpnNetworkAgent.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertFalse(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        // For safety reasons a VPN without underlying networks is considered metered.
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Connect cell and use it as an underlying network.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);

        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Don't disconnect, but note the VPN is not using wifi any more.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Use Wifi but not cell. Note the VPN is now unmetered.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Use both again.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Disconnect cell. Receive update without even removing the dead network from the
        // underlying networks – it's dead anyway. Not metered any more.
        mCellNetworkAgent.disconnect();
        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Disconnect wifi too. No underlying networks means this is now metered.
        mWiFiNetworkAgent.disconnect();
        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        mMockVpn.disconnect();
    }

    @Test
    public void testNullUnderlyingNetworks() {
        final int uid = Process.myUid();

        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN)
                .build();
        NetworkCapabilities nc;
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        vpnNetworkCallback.assertNoCallback();

        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.connect();
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, false /* hasInternet */);

        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        nc = mCm.getNetworkCapabilities(vpnNetworkAgent.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertFalse(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        // By default, VPN is set to track default network (i.e. its underlying networks is null).
        // In case of no default network, VPN is considered metered.
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Connect to Cell; Cell is the default network.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Connect to WiFi; WiFi is the new default.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        // Disconnect Cell. The default network did not change, so there shouldn't be any changes in
        // the capabilities.
        mCellNetworkAgent.disconnect();

        // Disconnect wifi too. Now we have no default network.
        mWiFiNetworkAgent.disconnect();

        vpnNetworkCallback.expectCapabilitiesLike((caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                vpnNetworkAgent);

        mMockVpn.disconnect();
    }

    @Test
    public void testIsActiveNetworkMeteredOverWifi() {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();

        assertFalse(mCm.isActiveNetworkMetered());
    }

    @Test
    public void testIsActiveNetworkMeteredOverCell() {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        mCellNetworkAgent.connect(true);
        waitForIdle();

        assertTrue(mCm.isActiveNetworkMetered());
    }

    @Test
    public void testIsActiveNetworkMeteredOverVpnTrackingPlatformDefault() {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        assertTrue(mCm.isActiveNetworkMetered());

        // Connect VPN network. By default it is using current default network (Cell).
        MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        final int uid = Process.myUid();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        waitForIdle();
        // Ensure VPN is now the active network.
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // Connect WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        // VPN should still be the active network.
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Expect VPN to be unmetered as it should now be using WiFi (new default).
        assertFalse(mCm.isActiveNetworkMetered());

        // Disconnecting Cell should not affect VPN's meteredness.
        mCellNetworkAgent.disconnect();
        waitForIdle();

        assertFalse(mCm.isActiveNetworkMetered());

        // Disconnect WiFi; Now there is no platform default network.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();

        // VPN without any underlying networks is treated as metered.
        assertTrue(mCm.isActiveNetworkMetered());

        vpnNetworkAgent.disconnect();
        mMockVpn.disconnect();
    }

   @Test
   public void testIsActiveNetworkMeteredOverVpnSpecifyingUnderlyingNetworks() {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        assertTrue(mCm.isActiveNetworkMetered());

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertFalse(mCm.isActiveNetworkMetered());

        // Connect VPN network.
        MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        final int uid = Process.myUid();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        waitForIdle();
        // Ensure VPN is now the active network.
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        // VPN is using Cell
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is now using WiFi
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be unmetered
        assertFalse(mCm.isActiveNetworkMetered());

        // VPN is using Cell | WiFi.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is using WiFi | Cell.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork(), mCellNetworkAgent.getNetwork() });
        waitForIdle();

        // Order should not matter and VPN should still be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is not using any underlying networks.
        mService.setUnderlyingNetworksForVpn(new Network[0]);
        waitForIdle();

        // VPN without underlying networks is treated as metered.
        assertTrue(mCm.isActiveNetworkMetered());

        vpnNetworkAgent.disconnect();
        mMockVpn.disconnect();
    }

    @Test
    public void testIsActiveNetworkMeteredOverAlwaysMeteredVpn() {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertFalse(mCm.isActiveNetworkMetered());

        // Connect VPN network.
        MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        final int uid = Process.myUid();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connectAsAlwaysMetered();
        waitForIdle();
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // VPN is tracking current platform default (WiFi).
        mService.setUnderlyingNetworksForVpn(null);
        waitForIdle();

        // Despite VPN using WiFi (which is unmetered), VPN itself is marked as always metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN explicitly declares WiFi as its underlying network.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Doesn't really matter whether VPN declares its underlying networks explicitly.
        assertTrue(mCm.isActiveNetworkMetered());

        // With WiFi lost, VPN is basically without any underlying networks. And in that case it is
        // anyways suppose to be metered.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();

        assertTrue(mCm.isActiveNetworkMetered());

        vpnNetworkAgent.disconnect();
    }

    @Test
    public void testNetworkBlockedStatus() {
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .build();
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        mService.setUidRulesChanged(RULE_REJECT_ALL);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);

        // ConnectivityService should cache it not to invoke the callback again.
        mService.setUidRulesChanged(RULE_REJECT_METERED);
        cellNetworkCallback.assertNoCallback();

        mService.setUidRulesChanged(RULE_NONE);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);

        mService.setUidRulesChanged(RULE_REJECT_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);

        // Restrict the network based on UID rule and NOT_METERED capability change.
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        cellNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_METERED, mCellNetworkAgent);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        cellNetworkCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_METERED,
                mCellNetworkAgent);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        mService.setUidRulesChanged(RULE_ALLOW_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);

        mService.setUidRulesChanged(RULE_NONE);
        cellNetworkCallback.assertNoCallback();

        // Restrict the network based on BackgroundRestricted.
        mService.setRestrictBackgroundChanged(true);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        mService.setRestrictBackgroundChanged(true);
        cellNetworkCallback.assertNoCallback();
        mService.setRestrictBackgroundChanged(false);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(cellNetworkCallback);
    }

    @Test
    public void testNetworkBlockedStatusBeforeAndAfterConnect() {
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        // No Networkcallbacks invoked before any network is active.
        mService.setUidRulesChanged(RULE_REJECT_ALL);
        mService.setUidRulesChanged(RULE_NONE);
        mService.setUidRulesChanged(RULE_REJECT_METERED);
        defaultCallback.assertNoCallback();

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        defaultCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mCellNetworkAgent);

        // Allow to use the network after switching to NOT_METERED network.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);

        // Switch to METERED network. Restrict the use of the network.
        mWiFiNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidatedAndBlocked(mCellNetworkAgent);

        // Network becomes NOT_METERED.
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        defaultCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_METERED, mCellNetworkAgent);
        defaultCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);

        // Verify there's no Networkcallbacks invoked after data saver on/off.
        mService.setRestrictBackgroundChanged(true);
        mService.setRestrictBackgroundChanged(false);
        defaultCallback.assertNoCallback();

        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        defaultCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(defaultCallback);
    }

    /**
     * Make simulated InterfaceConfig for Nat464Xlat to query clat lower layer info.
     */
    private InterfaceConfiguration getClatInterfaceConfig(LinkAddress la) {
        InterfaceConfiguration cfg = new InterfaceConfiguration();
        cfg.setHardwareAddress("11:22:33:44:55:66");
        cfg.setLinkAddress(la);
        return cfg;
    }

    /**
     * Make expected stack link properties, copied from Nat464Xlat.
     */
    private LinkProperties makeClatLinkProperties(LinkAddress la) {
        LinkAddress clatAddress = la;
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName(CLAT_PREFIX + MOBILE_IFNAME);
        RouteInfo ipv4Default = new RouteInfo(
                new LinkAddress(Inet4Address.ANY, 0),
                clatAddress.getAddress(), CLAT_PREFIX + MOBILE_IFNAME);
        stacked.addRoute(ipv4Default);
        stacked.addLinkAddress(clatAddress);
        return stacked;
    }

    @Test
    public void testStackedLinkProperties() throws UnknownHostException, RemoteException {
        final LinkAddress myIpv4 = new LinkAddress("1.2.3.4/24");
        final LinkAddress myIpv6 = new LinkAddress("2001:db8:1::1/64");
        final String kNat64PrefixString = "2001:db8:64:64:64:64::";
        final IpPrefix kNat64Prefix = new IpPrefix(InetAddress.getByName(kNat64PrefixString), 96);

        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        // Prepare ipv6 only link properties.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        final int cellNetId = mCellNetworkAgent.getNetwork().netId;
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        cellLp.addLinkAddress(myIpv6);
        cellLp.addRoute(new RouteInfo((IpPrefix) null, myIpv6.getAddress(), MOBILE_IFNAME));
        cellLp.addRoute(new RouteInfo(myIpv6, null, MOBILE_IFNAME));
        reset(mNetworkManagementService);
        reset(mMockDnsResolver);
        reset(mMockNetd);
        when(mNetworkManagementService.getInterfaceConfig(CLAT_PREFIX + MOBILE_IFNAME))
                .thenReturn(getClatInterfaceConfig(myIpv4));

        // Connect with ipv6 link properties. Expect prefix discovery to be started.
        mCellNetworkAgent.sendLinkProperties(cellLp);
        mCellNetworkAgent.connect(true);

        verify(mMockNetd, times(1)).networkCreatePhysical(eq(cellNetId), anyInt());
        verify(mMockDnsResolver, times(1)).createNetworkCache(eq(cellNetId));

        networkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);

        // Switching default network updates TCP buffer sizes.
        verifyTcpBufferSizeChange(ConnectivityService.DEFAULT_TCP_BUFFER_SIZES);

        // Add an IPv4 address. Expect prefix discovery to be stopped. Netd doesn't tell us that
        // the NAT64 prefix was removed because one was never discovered.
        cellLp.addLinkAddress(myIpv4);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).stopPrefix64Discovery(cellNetId);
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(any());

        verifyNoMoreInteractions(mMockNetd);
        verifyNoMoreInteractions(mMockDnsResolver);
        reset(mMockNetd);
        reset(mMockDnsResolver);

        // Remove IPv4 address. Expect prefix discovery to be started again.
        cellLp.removeLinkAddress(myIpv4);
        cellLp.removeRoute(new RouteInfo(myIpv4, null, MOBILE_IFNAME));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);

        // When NAT64 prefix discovery succeeds, LinkProperties are updated and clatd is started.
        Nat464Xlat clat = mService.getNat464Xlat(mCellNetworkAgent);
        assertNull(mCm.getLinkProperties(mCellNetworkAgent.getNetwork()).getNat64Prefix());
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, true /* added */,
                kNat64PrefixString, 96);
        LinkProperties lpBeforeClat = (LinkProperties) networkCallback.expectCallback(
                CallbackState.LINK_PROPERTIES, mCellNetworkAgent).arg;
        assertEquals(0, lpBeforeClat.getStackedLinks().size());
        assertEquals(kNat64Prefix, lpBeforeClat.getNat64Prefix());
        verify(mMockNetd, times(1)).clatdStart(MOBILE_IFNAME, kNat64Prefix.toString());

        // Clat iface comes up. Expect stacked link to be added.
        clat.interfaceLinkStateChanged(CLAT_PREFIX + MOBILE_IFNAME, true);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        List<LinkProperties> stackedLps = mCm.getLinkProperties(mCellNetworkAgent.getNetwork())
                .getStackedLinks();
        assertEquals(makeClatLinkProperties(myIpv4), stackedLps.get(0));

        // Change trivial linkproperties and see if stacked link is preserved.
        cellLp.addDnsServer(InetAddress.getByName("8.8.8.8"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);

        List<LinkProperties> stackedLpsAfterChange =
                mCm.getLinkProperties(mCellNetworkAgent.getNetwork()).getStackedLinks();
        assertNotEquals(stackedLpsAfterChange, Collections.EMPTY_LIST);
        assertEquals(makeClatLinkProperties(myIpv4), stackedLpsAfterChange.get(0));

        verify(mMockDnsResolver, times(1)).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        ResolverParamsParcel resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(1, resolvrParams.servers.length);
        assertTrue(ArrayUtils.contains(resolvrParams.servers, "8.8.8.8"));

        // Add ipv4 address, expect that clatd and prefix discovery are stopped and stacked
        // linkproperties are cleaned up.
        cellLp.addLinkAddress(myIpv4);
        cellLp.addRoute(new RouteInfo(myIpv4, null, MOBILE_IFNAME));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        verify(mMockNetd, times(1)).clatdStop(MOBILE_IFNAME);
        verify(mMockDnsResolver, times(1)).stopPrefix64Discovery(cellNetId);

        // As soon as stop is called, the linkproperties lose the stacked interface.
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        LinkProperties actualLpAfterIpv4 = mCm.getLinkProperties(mCellNetworkAgent.getNetwork());
        LinkProperties expected = new LinkProperties(cellLp);
        expected.setNat64Prefix(kNat64Prefix);
        assertEquals(expected, actualLpAfterIpv4);
        assertEquals(0, actualLpAfterIpv4.getStackedLinks().size());

        // The interface removed callback happens but has no effect after stop is called.
        clat.interfaceRemoved(CLAT_PREFIX + MOBILE_IFNAME);
        networkCallback.assertNoCallback();

        verifyNoMoreInteractions(mMockNetd);
        verifyNoMoreInteractions(mMockDnsResolver);
        reset(mMockNetd);
        reset(mMockDnsResolver);

        // Stopping prefix discovery causes netd to tell us that the NAT64 prefix is gone.
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, false /* added */,
                kNat64PrefixString, 96);
        networkCallback.expectLinkPropertiesLike((lp) -> lp.getNat64Prefix() == null,
                mCellNetworkAgent);

        // Remove IPv4 address and expect prefix discovery and clatd to be started again.
        cellLp.removeLinkAddress(myIpv4);
        cellLp.removeRoute(new RouteInfo(myIpv4, null, MOBILE_IFNAME));
        cellLp.removeDnsServer(InetAddress.getByName("8.8.8.8"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, true /* added */,
                kNat64PrefixString, 96);
        networkCallback.expectCallback(CallbackState.LINK_PROPERTIES, mCellNetworkAgent);
        verify(mMockNetd, times(1)).clatdStart(MOBILE_IFNAME, kNat64Prefix.toString());


        // Clat iface comes up. Expect stacked link to be added.
        clat.interfaceLinkStateChanged(CLAT_PREFIX + MOBILE_IFNAME, true);
        networkCallback.expectLinkPropertiesLike(
                (lp) -> lp.getStackedLinks().size() == 1 && lp.getNat64Prefix() != null,
                mCellNetworkAgent);

        // NAT64 prefix is removed. Expect that clat is stopped.
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, false /* added */,
                kNat64PrefixString, 96);
        networkCallback.expectLinkPropertiesLike(
                (lp) -> lp.getStackedLinks().size() == 0 && lp.getNat64Prefix() == null,
                mCellNetworkAgent);
        verify(mMockNetd, times(1)).clatdStop(MOBILE_IFNAME);
        networkCallback.expectLinkPropertiesLike((lp) -> lp.getStackedLinks().size() == 0,
                mCellNetworkAgent);

        // Clean up.
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        networkCallback.assertNoCallback();
        mCm.unregisterNetworkCallback(networkCallback);
    }

    @Test
    public void testDataActivityTracking() throws RemoteException {
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        reset(mNetworkManagementService);
        mCellNetworkAgent.connect(true);
        networkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mNetworkManagementService, times(1)).addIdleTimer(eq(MOBILE_IFNAME), anyInt(),
                eq(ConnectivityManager.TYPE_MOBILE));

        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        final LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_IFNAME);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);

        // Network switch
        reset(mNetworkManagementService);
        mWiFiNetworkAgent.connect(true);
        networkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        networkCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        verify(mNetworkManagementService, times(1)).addIdleTimer(eq(WIFI_IFNAME), anyInt(),
                eq(ConnectivityManager.TYPE_WIFI));
        verify(mNetworkManagementService, times(1)).removeIdleTimer(eq(MOBILE_IFNAME));

        // Disconnect wifi and switch back to cell
        reset(mNetworkManagementService);
        mWiFiNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackState.LOST, mWiFiNetworkAgent);
        assertNoCallbacks(networkCallback);
        verify(mNetworkManagementService, times(1)).removeIdleTimer(eq(WIFI_IFNAME));
        verify(mNetworkManagementService, times(1)).addIdleTimer(eq(MOBILE_IFNAME), anyInt(),
                eq(ConnectivityManager.TYPE_MOBILE));

        // reconnect wifi
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        wifiLp.setInterfaceName(WIFI_IFNAME);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        mWiFiNetworkAgent.connect(true);
        networkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        networkCallback.expectCallback(CallbackState.LOSING, mCellNetworkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);

        // Disconnect cell
        reset(mNetworkManagementService);
        reset(mMockNetd);
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackState.LOST, mCellNetworkAgent);
        // LOST callback is triggered earlier than removing idle timer. Broadcast should also be
        // sent as network being switched. Ensure rule removal for cell will not be triggered
        // unexpectedly before network being removed.
        waitForIdle();
        verify(mNetworkManagementService, times(0)).removeIdleTimer(eq(MOBILE_IFNAME));
        verify(mMockNetd, times(1)).networkDestroy(eq(mCellNetworkAgent.getNetwork().netId));
        verify(mMockDnsResolver, times(1))
                .destroyNetworkCache(eq(mCellNetworkAgent.getNetwork().netId));

        // Disconnect wifi
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        reset(mNetworkManagementService);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verify(mNetworkManagementService, times(1)).removeIdleTimer(eq(WIFI_IFNAME));

        // Clean up
        mCm.unregisterNetworkCallback(networkCallback);
    }

    private void verifyTcpBufferSizeChange(String tcpBufferSizes) {
        String[] values = tcpBufferSizes.split(",");
        String rmemValues = String.join(" ", values[0], values[1], values[2]);
        String wmemValues = String.join(" ", values[3], values[4], values[5]);
        waitForIdle();
        try {
            verify(mMockNetd, atLeastOnce()).setTcpRWmemorySize(rmemValues, wmemValues);
        } catch (RemoteException e) {
            fail("mMockNetd should never throw RemoteException");
        }
        reset(mMockNetd);
    }

    @Test
    public void testTcpBufferReset() {
        final String testTcpBufferSizes = "1,2,3,4,5,6";

        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        reset(mMockNetd);
        // Switching default network updates TCP buffer sizes.
        mCellNetworkAgent.connect(false);
        verifyTcpBufferSizeChange(ConnectivityService.DEFAULT_TCP_BUFFER_SIZES);

        // Change link Properties should have updated tcp buffer size.
        LinkProperties lp = new LinkProperties();
        lp.setTcpBufferSizes(testTcpBufferSizes);
        mCellNetworkAgent.sendLinkProperties(lp);
        verifyTcpBufferSizeChange(testTcpBufferSizes);
    }

    @Test
    public void testGetGlobalProxyForNetwork() {
        final ProxyInfo testProxyInfo = ProxyInfo.buildDirectProxy("test", 8888);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        final Network wifiNetwork = mWiFiNetworkAgent.getNetwork();
        when(mService.mProxyTracker.getGlobalProxy()).thenReturn(testProxyInfo);
        assertEquals(testProxyInfo, mService.getProxyForNetwork(wifiNetwork));
    }

    @Test
    public void testGetProxyForActiveNetwork() {
        final ProxyInfo testProxyInfo = ProxyInfo.buildDirectProxy("test", 8888);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        final LinkProperties testLinkProperties = new LinkProperties();
        testLinkProperties.setHttpProxy(testProxyInfo);

        mWiFiNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();

        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
    }

    @Test
    public void testGetProxyForVPN() {
        final ProxyInfo testProxyInfo = ProxyInfo.buildDirectProxy("test", 8888);

        // Set up a WiFi network with no proxy
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        // Set up a VPN network with a proxy
        final int uid = Process.myUid();
        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setUids(ranges);
        LinkProperties testLinkProperties = new LinkProperties();
        testLinkProperties.setHttpProxy(testProxyInfo);
        vpnNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();

        // Connect to VPN with proxy
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        waitForIdle();

        // Test that the VPN network returns a proxy, and the WiFi does not.
        assertEquals(testProxyInfo, mService.getProxyForNetwork(vpnNetworkAgent.getNetwork()));
        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
        assertNull(mService.getProxyForNetwork(mWiFiNetworkAgent.getNetwork()));

        // Test that the VPN network returns no proxy when it is set to null.
        testLinkProperties.setHttpProxy(null);
        vpnNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(vpnNetworkAgent.getNetwork()));
        assertNull(mService.getProxyForNetwork(null));

        // Set WiFi proxy and check that the vpn proxy is still null.
        testLinkProperties.setHttpProxy(testProxyInfo);
        mWiFiNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        // Disconnect from VPN and check that the active network, which is now the WiFi, has the
        // correct proxy setting.
        vpnNetworkAgent.disconnect();
        waitForIdle();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(testProxyInfo, mService.getProxyForNetwork(mWiFiNetworkAgent.getNetwork()));
        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
    }

    @Test
    public void testFullyRoutedVpnResultsInInterfaceFilteringRules() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final MockNetworkAgent vpnNetworkAgent = establishVpn(lp, VPN_UID, vpnRange);

        // Connected VPN should have interface rules set up. There are two expected invocations,
        // one during VPN uid update, one during VPN LinkProperties update
        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mMockNetd, times(2)).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getAllValues().get(0), APP1_UID, APP2_UID);
        assertContainsExactly(uidCaptor.getAllValues().get(1), APP1_UID, APP2_UID);
        assertTrue(mService.mPermissionMonitor.getVpnUidRanges("tun0").equals(vpnRange));

        vpnNetworkAgent.disconnect();
        waitForIdle();

        // Disconnected VPN should have interface rules removed
        verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
        assertNull(mService.mPermissionMonitor.getVpnUidRanges("tun0"));
    }

    @Test
    public void testLegacyVpnDoesNotResultInInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final MockNetworkAgent vpnNetworkAgent = establishVpn(lp, Process.SYSTEM_UID, vpnRange);

        // Legacy VPN should not have interface rules set up
        verify(mMockNetd, never()).firewallAddUidInterfaceRules(any(), any());
    }

    @Test
    public void testLocalIpv4OnlyVpnDoesNotResultInInterfaceFilteringRule()
            throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix("192.0.2.0/24"), null, "tun0"));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), RTN_UNREACHABLE));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final MockNetworkAgent vpnNetworkAgent = establishVpn(lp, Process.SYSTEM_UID, vpnRange);

        // IPv6 unreachable route should not be misinterpreted as a default route
        verify(mMockNetd, never()).firewallAddUidInterfaceRules(any(), any());
    }

    @Test
    public void testVpnHandoverChangesInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final MockNetworkAgent vpnNetworkAgent = establishVpn(lp, VPN_UID, vpnRange);

        // Connected VPN should have interface rules set up. There are two expected invocations,
        // one during VPN uid update, one during VPN LinkProperties update
        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mMockNetd, times(2)).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getAllValues().get(0), APP1_UID, APP2_UID);
        assertContainsExactly(uidCaptor.getAllValues().get(1), APP1_UID, APP2_UID);

        reset(mMockNetd);
        InOrder inOrder = inOrder(mMockNetd);
        lp.setInterfaceName("tun1");
        vpnNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        // VPN handover (switch to a new interface) should result in rules being updated (old rules
        // removed first, then new rules added)
        inOrder.verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
        inOrder.verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun1"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);

        reset(mMockNetd);
        lp = new LinkProperties();
        lp.setInterfaceName("tun1");
        lp.addRoute(new RouteInfo(new IpPrefix("192.0.2.0/24"), null, "tun1"));
        vpnNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        // VPN not routing everything should no longer have interface filtering rules
        verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);

        reset(mMockNetd);
        lp = new LinkProperties();
        lp.setInterfaceName("tun1");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        vpnNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        // Back to routing all IPv6 traffic should have filtering rules
        verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun1"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
    }

    @Test
    public void testUidUpdateChangesInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final UidRange vpnRange = UidRange.createForUser(VPN_USER);
        final MockNetworkAgent vpnNetworkAgent = establishVpn(lp, VPN_UID,
                Collections.singleton(vpnRange));

        reset(mMockNetd);
        InOrder inOrder = inOrder(mMockNetd);

        // Update to new range which is old range minus APP1, i.e. only APP2
        final Set<UidRange> newRanges = new HashSet<>(Arrays.asList(
                new UidRange(vpnRange.start, APP1_UID - 1),
                new UidRange(APP1_UID + 1, vpnRange.stop)));
        vpnNetworkAgent.setUids(newRanges);
        waitForIdle();

        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        // Verify old rules are removed before new rules are added
        inOrder.verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
        inOrder.verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP2_UID);
    }


    private MockNetworkAgent establishVpn(LinkProperties lp, int establishingUid,
            Set<UidRange> vpnRange) {
        final MockNetworkAgent vpnNetworkAgent = new MockNetworkAgent(TRANSPORT_VPN, lp);
        vpnNetworkAgent.getNetworkCapabilities().setEstablishingVpnAppUid(establishingUid);
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.connect();
        mMockVpn.setUids(vpnRange);
        vpnNetworkAgent.connect(true);
        waitForIdle();
        return vpnNetworkAgent;
    }

    private void assertContainsExactly(int[] actual, int... expected) {
        int[] sortedActual = Arrays.copyOf(actual, actual.length);
        int[] sortedExpected = Arrays.copyOf(expected, expected.length);
        Arrays.sort(sortedActual);
        Arrays.sort(sortedExpected);
        assertArrayEquals(sortedExpected, sortedActual);
    }

    private static PackageInfo buildPackageInfo(boolean hasSystemPermission, int uid) {
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.requestedPermissions = new String[0];
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.privateFlags = 0;
        packageInfo.applicationInfo.uid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                UserHandle.getAppId(uid));
        return packageInfo;
    }
}
