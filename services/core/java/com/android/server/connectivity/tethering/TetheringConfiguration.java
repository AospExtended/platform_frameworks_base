/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import static android.content.Context.TELEPHONY_SERVICE;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static com.android.internal.R.array.config_mobile_hotspot_provision_app;
import static com.android.internal.R.array.config_tether_bluetooth_regexs;
import static com.android.internal.R.array.config_tether_dhcp_range;
import static com.android.internal.R.array.config_tether_usb_regexs;
import static com.android.internal.R.array.config_tether_upstream_types;
import static com.android.internal.R.array.config_tether_wifi_regexs;
import static com.android.internal.R.string.config_mobile_hotspot_provision_app_no_ui;

import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.util.SharedLog;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.R;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;


/**
 * A utility class to encapsulate the various tethering configuration elements.
 *
 * This configuration data includes elements describing upstream properties
 * (preferred and required types of upstream connectivity as well as default
 * DNS servers to use if none are available) and downstream properties (such
 * as regular expressions use to match suitable downstream interfaces and the
 * DHCPv4 ranges to use).
 *
 * @hide
 */
public class TetheringConfiguration {
    private static final String TAG = TetheringConfiguration.class.getSimpleName();

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    @VisibleForTesting
    public static final int DUN_NOT_REQUIRED = 0;
    public static final int DUN_REQUIRED = 1;
    public static final int DUN_UNSPECIFIED = 2;

    // USB is  192.168.42.1 and 255.255.255.0
    // Wifi is 192.168.43.1 and 255.255.255.0
    // BT is limited to max default of 5 connections. 192.168.44.1 to 192.168.48.1
    // with 255.255.255.0
    // P2P is 192.168.49.1 and 255.255.255.0
    private static final String[] DHCP_DEFAULT_RANGE = {
        "192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254",
        "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254",
        "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254",
        "192.168.48.2", "192.168.48.254", "192.168.49.2", "192.168.49.254",
    };

    private final String[] DEFAULT_IPV4_DNS = {"1.0.0.1", "1.1.1.1"};

    public final String[] tetherableUsbRegexs;
    public final String[] tetherableWifiRegexs;
    public final String[] tetherableBluetoothRegexs;
    public final int dunCheck;
    public final boolean isDunRequired;
    public final Collection<Integer> preferredUpstreamIfaceTypes;
    public final String[] dhcpRanges;
    public final String[] defaultIPv4DNS;

    public final String[] provisioningApp;
    public final String provisioningAppNoUi;

    public TetheringConfiguration(Context ctx, SharedLog log) {
        final SharedLog configLog = log.forSubComponent("config");

        tetherableUsbRegexs = getResourceStringArray(ctx, config_tether_usb_regexs);
        // TODO: Evaluate deleting this altogether now that Wi-Fi always passes
        // us an interface name. Careful consideration needs to be given to
        // implications for Settings and for provisioning checks.
        tetherableWifiRegexs = getResourceStringArray(ctx, config_tether_wifi_regexs);
        tetherableBluetoothRegexs = getResourceStringArray(ctx, config_tether_bluetooth_regexs);

        dunCheck = checkDunRequired(ctx);
        configLog.log("DUN check returned: " + dunCheckString(dunCheck));

        preferredUpstreamIfaceTypes = getUpstreamIfaceTypes(ctx, dunCheck);
        isDunRequired = preferredUpstreamIfaceTypes.contains(TYPE_MOBILE_DUN);

        dhcpRanges = getDhcpRanges(ctx);
        defaultIPv4DNS = copy(DEFAULT_IPV4_DNS);

        provisioningApp = getResourceStringArray(ctx, config_mobile_hotspot_provision_app);
        provisioningAppNoUi = getProvisioningAppNoUi(ctx);

        configLog.log(toString());
    }

    public boolean isUsb(String iface) {
        return matchesDownstreamRegexs(iface, tetherableUsbRegexs);
    }

    public boolean isWifi(String iface) {
        return matchesDownstreamRegexs(iface, tetherableWifiRegexs);
    }

    public boolean isBluetooth(String iface) {
        return matchesDownstreamRegexs(iface, tetherableBluetoothRegexs);
    }

    public boolean hasMobileHotspotProvisionApp() {
        return !TextUtils.isEmpty(provisioningAppNoUi);
    }

    public void dump(PrintWriter pw) {
        dumpStringArray(pw, "tetherableUsbRegexs", tetherableUsbRegexs);
        dumpStringArray(pw, "tetherableWifiRegexs", tetherableWifiRegexs);
        dumpStringArray(pw, "tetherableBluetoothRegexs", tetherableBluetoothRegexs);

        pw.print("isDunRequired: ");
        pw.println(isDunRequired);

        dumpStringArray(pw, "preferredUpstreamIfaceTypes",
                preferredUpstreamNames(preferredUpstreamIfaceTypes));

        dumpStringArray(pw, "dhcpRanges", dhcpRanges);
        dumpStringArray(pw, "defaultIPv4DNS", defaultIPv4DNS);

        dumpStringArray(pw, "provisioningApp", provisioningApp);
        pw.print("provisioningAppNoUi: ");
        pw.println(provisioningAppNoUi);
    }

    public String toString() {
        final StringJoiner sj = new StringJoiner(" ");
        sj.add(String.format("tetherableUsbRegexs:%s", makeString(tetherableUsbRegexs)));
        sj.add(String.format("tetherableWifiRegexs:%s", makeString(tetherableWifiRegexs)));
        sj.add(String.format("tetherableBluetoothRegexs:%s",
                makeString(tetherableBluetoothRegexs)));
        sj.add(String.format("isDunRequired:%s", isDunRequired));
        sj.add(String.format("preferredUpstreamIfaceTypes:%s",
                makeString(preferredUpstreamNames(preferredUpstreamIfaceTypes))));
        sj.add(String.format("provisioningApp:%s", makeString(provisioningApp)));
        sj.add(String.format("provisioningAppNoUi:%s", provisioningAppNoUi));
        return String.format("TetheringConfiguration{%s}", sj.toString());
    }

    private static void dumpStringArray(PrintWriter pw, String label, String[] values) {
        pw.print(label);
        pw.print(": ");

        if (values != null) {
            final StringJoiner sj = new StringJoiner(", ", "[", "]");
            for (String value : values) { sj.add(value); }
            pw.print(sj.toString());
        } else {
            pw.print("null");
        }

        pw.println();
    }

    private static String makeString(String[] strings) {
        if (strings == null) return "null";
        final StringJoiner sj = new StringJoiner(",", "[", "]");
        for (String s : strings) sj.add(s);
        return sj.toString();
    }

    private static String[] preferredUpstreamNames(Collection<Integer> upstreamTypes) {
        String[] upstreamNames = null;

        if (upstreamTypes != null) {
            upstreamNames = new String[upstreamTypes.size()];
            int i = 0;
            for (Integer netType : upstreamTypes) {
                upstreamNames[i] = ConnectivityManager.getNetworkTypeName(netType);
                i++;
            }
        }

        return upstreamNames;
    }

    public static int checkDunRequired(Context ctx) {
        final TelephonyManager tm = (TelephonyManager) ctx.getSystemService(TELEPHONY_SERVICE);
        return (tm != null) ? tm.getTetherApnRequired() : DUN_UNSPECIFIED;
    }

    private static String dunCheckString(int dunCheck) {
        switch (dunCheck) {
            case DUN_NOT_REQUIRED: return "DUN_NOT_REQUIRED";
            case DUN_REQUIRED:     return "DUN_REQUIRED";
            case DUN_UNSPECIFIED:  return "DUN_UNSPECIFIED";
            default:
                return String.format("UNKNOWN (%s)", dunCheck);
        }
    }

    private static Collection<Integer> getUpstreamIfaceTypes(Context ctx, int dunCheck) {
        final int ifaceTypes[] = ctx.getResources().getIntArray(config_tether_upstream_types);
        final ArrayList<Integer> upstreamIfaceTypes = new ArrayList<>(ifaceTypes.length);
        for (int i : ifaceTypes) {
            switch (i) {
                case TYPE_MOBILE:
                case TYPE_MOBILE_HIPRI:
                    if (dunCheck == DUN_REQUIRED) continue;
                    break;
                case TYPE_MOBILE_DUN:
                    if (dunCheck == DUN_NOT_REQUIRED) continue;
                    break;
            }
            upstreamIfaceTypes.add(i);
        }

        // Fix up upstream interface types for DUN or mobile. NOTE: independent
        // of the value of |dunCheck|, cell data of one form or another is
        // *always* an upstream, regardless of the upstream interface types
        // specified by configuration resources.
        if (dunCheck == DUN_REQUIRED) {
            appendIfNotPresent(upstreamIfaceTypes, TYPE_MOBILE_DUN);
        } else if (dunCheck == DUN_NOT_REQUIRED) {
            appendIfNotPresent(upstreamIfaceTypes, TYPE_MOBILE);
            appendIfNotPresent(upstreamIfaceTypes, TYPE_MOBILE_HIPRI);
        } else {
            // Fix upstream interface types for case DUN_UNSPECIFIED.
            // Do not modify if a cellular interface type is already present in the
            // upstream interface types. Add TYPE_MOBILE and TYPE_MOBILE_HIPRI if no
            // cellular interface types are found in the upstream interface types.
            if (!(containsOneOf(upstreamIfaceTypes,
                    TYPE_MOBILE_DUN, TYPE_MOBILE, TYPE_MOBILE_HIPRI))) {
                upstreamIfaceTypes.add(TYPE_MOBILE);
                upstreamIfaceTypes.add(TYPE_MOBILE_HIPRI);
            }
        }

        // Always make sure our good friend Ethernet is present.
        // TODO: consider unilaterally forcing this at the front.
        prependIfNotPresent(upstreamIfaceTypes, TYPE_ETHERNET);

        return upstreamIfaceTypes;
    }

    private static boolean matchesDownstreamRegexs(String iface, String[] regexs) {
        for (String regex : regexs) {
            if (iface.matches(regex)) return true;
        }
        return false;
    }

    private static String[] getDhcpRanges(Context ctx) {
        final String[] fromResource = getResourceStringArray(ctx, config_tether_dhcp_range);
        if ((fromResource.length > 0) && (fromResource.length % 2 == 0)) {
            return fromResource;
        }
        return copy(DHCP_DEFAULT_RANGE);
    }

    private static String getProvisioningAppNoUi(Context ctx) {
        try {
            return ctx.getResources().getString(config_mobile_hotspot_provision_app_no_ui);
        } catch (Resources.NotFoundException e) {
            return "";
        }
    }

    private static String[] getResourceStringArray(Context ctx, int resId) {
        try {
            final String[] strArray = ctx.getResources().getStringArray(resId);
            return (strArray != null) ? strArray : EMPTY_STRING_ARRAY;
        } catch (Resources.NotFoundException e404) {
            return EMPTY_STRING_ARRAY;
        }
    }

    private static String[] copy(String[] strarray) {
        return Arrays.copyOf(strarray, strarray.length);
    }

    private static void prependIfNotPresent(ArrayList<Integer> list, int value) {
        if (list.contains(value)) return;
        list.add(0, value);
    }

    private static void appendIfNotPresent(ArrayList<Integer> list, int value) {
        if (list.contains(value)) return;
        list.add(value);
    }

    private static boolean containsOneOf(ArrayList<Integer> list, Integer... values) {
        for (Integer value : values) {
            if (list.contains(value)) return true;
        }
        return false;
    }
}
