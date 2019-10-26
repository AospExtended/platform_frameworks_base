/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.framework.permission.tests;

import android.media.AudioAttributes;
import android.os.Binder;
import android.os.IVibratorService;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.VibrationEffect;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;


/**
 * Verify that Hardware apis cannot be called without required permissions.
 */
@SmallTest
public class VibratorServicePermissionTest extends TestCase {

    private IVibratorService mVibratorService;

    @Override
    protected void setUp() throws Exception {
        mVibratorService = IVibratorService.Stub.asInterface(
                ServiceManager.getService("vibrator"));
    }

    /**
     * Test that calling {@link android.os.IVibratorService#vibrate(long)} requires permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#VIBRATE}
     * @throws RemoteException
     */
    public void testVibrate() throws RemoteException {
        try {
            final VibrationEffect effect =
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE);
            final AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build();
            mVibratorService.vibrate(Process.myUid(), null, effect, attrs,
                    "testVibrate", new Binder());
            fail("vibrate did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Test that calling {@link android.os.IVibratorService#cancelVibrate()} requires permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#VIBRATE}
     * @throws RemoteException
     */
    public void testCancelVibrate() throws RemoteException {
        try {
            mVibratorService.cancelVibrate(new Binder());
            fail("cancelVibrate did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}
