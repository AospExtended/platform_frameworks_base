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

package android.net.wifi.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pConfig}
 */
@SmallTest
public class WifiP2pConfigTest {

    private static final String DEVICE_ADDRESS = "aa:bb:cc:dd:ee:ff";
    /**
     * Check network name setter
     */
    @Test
    public void testBuilderInvalidNetworkName() throws Exception {
        WifiP2pConfig.Builder b = new WifiP2pConfig.Builder();

        // sunny case
        try {
            b.setNetworkName("DIRECT-ab-Hello");
        } catch (IllegalArgumentException e) {
            fail("Unexpected IllegalArgumentException");
        }

        // sunny case, no trailing string
        try {
            b.setNetworkName("DIRECT-WR");
        } catch (IllegalArgumentException e) {
            fail("Unexpected IllegalArgumentException");
        }

        // sunny case with maximum bytes for the network name
        try {
            b.setNetworkName("DIRECT-abcdefghijklmnopqrstuvwxy");
        } catch (IllegalArgumentException e) {
            fail("Unexpected IllegalArgumentException");
        }

        // less than 9 characters.
        try {
            b.setNetworkName("DIRECT-z");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // not starts with DIRECT-xy.
        try {
            b.setNetworkName("ABCDEFGHIJK");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // not starts with uppercase DIRECT-xy
        try {
            b.setNetworkName("direct-ab");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // x and y are not selected from upper case letters, lower case letters or
        // numbers.
        try {
            b.setNetworkName("direct-a?");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // over maximum bytes
        try {
            b.setNetworkName("DIRECT-abcdefghijklmnopqrstuvwxyz");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }
    }

    /**
     * Check passphrase setter
     */
    @Test
    public void testBuilderInvalidPassphrase() throws Exception {
        WifiP2pConfig.Builder b = new WifiP2pConfig.Builder();

        // sunny case
        try {
            b.setPassphrase("abcd1234");
        } catch (IllegalArgumentException e) {
            fail("Unexpected IllegalArgumentException");
        }

        // null string.
        try {
            b.setPassphrase(null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected exception.
        }

        // less than 8 characters.
        try {
            b.setPassphrase("12abcde");
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected exception.
        }

        // more than 63 characters.
        try {
            b.setPassphrase(
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890+/");
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected exception.
        }
    }

    @Test
    /*
     * Verify WifiP2pConfig basic operations
     */
    public void testWifiP2pConfig() throws Exception {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = DEVICE_ADDRESS;

        WifiP2pConfig copiedConfig = new WifiP2pConfig(config);
        // no equals operator, use toString for comparison.
        assertEquals(config.toString(), copiedConfig.toString());

        Parcel parcelW = Parcel.obtain();
        config.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiP2pConfig configFromParcel = WifiP2pConfig.CREATOR.createFromParcel(parcelR);

        // no equals operator, use toString for comparison.
        assertEquals(config.toString(), configFromParcel.toString());

    }

    @Test
    /*
     * Verify WifiP2pConfig invalidate API
     */
    public void testInvalidate() throws Exception {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = DEVICE_ADDRESS;
        config.invalidate();
        assertEquals("", config.deviceAddress);
    }

}
