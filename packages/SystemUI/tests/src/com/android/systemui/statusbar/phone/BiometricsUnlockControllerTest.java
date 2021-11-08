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

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class BiometricsUnlockControllerTest extends SysuiTestCase {

    @Mock
    private DumpManager mDumpManager;
    @Mock
    private NotificationMediaManager mMediaManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock
    private DozeScrimController mDozeScrimController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private ScrimController mScrimController;
    @Mock
    private BiometricUnlockController.BiometricModeListener mBiometricModeListener;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private Handler mHandler;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private NotificationMediaManager mNotificationMediaManager;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    private BiometricUnlockController mBiometricUnlockController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        TestableResources res = getContext().getOrCreateTestableResources();
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mKeyguardStateController.isFaceAuthEnabled()).thenReturn(true);
        when(mKeyguardBypassController.onBiometricAuthenticated(any(), anyBoolean()))
                .thenReturn(true);
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        when(mKeyguardBypassController.canPlaySubtleWindowAnimations()).thenReturn(true);
        mContext.addMockSystemService(PowerManager.class, mPowerManager);
        mDependency.injectTestDependency(NotificationMediaManager.class, mMediaManager);
        res.addOverride(com.android.internal.R.integer.config_wakeUpDelayDoze, 0);
        mBiometricUnlockController = new BiometricUnlockController(mContext, mDozeScrimController,
                mKeyguardViewMediator, mScrimController, mShadeController,
                mNotificationShadeWindowController, mKeyguardStateController, mHandler,
                mUpdateMonitor, res.getResources(), mKeyguardBypassController, mDozeParameters,
                mMetricsLogger, mDumpManager, mPowerManager,
                mNotificationMediaManager, mWakefulnessLifecycle, mScreenLifecycle,
                mAuthController);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.setBiometricModeListener(mBiometricModeListener);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintAndBiometricsDisallowed_showBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */))
                .thenReturn(false);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);
        verify(mStatusBarKeyguardViewManager).showBouncer(eq(false));
        verify(mStatusBarKeyguardViewManager, never()).notifyKeyguardAuthenticated(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprint_nonStrongBioDisallowed_showBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(false /* isStrongBiometric */))
                .thenReturn(false);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, false /* isStrongBiometric */);
        verify(mStatusBarKeyguardViewManager).showBouncer(eq(false));
        verify(mShadeController).animateCollapsePanels(anyInt(), anyBoolean(), anyBoolean(),
                anyFloat());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FINGERPRINT);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintAndNotInteractive_wakeAndUnlock() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mKeyguardViewMediator).onWakeAndUnlocking();
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprint_notifyKeyguardAuthenticated() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).showBouncer(anyBoolean());
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_dontDismissKeyguard() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        verify(mStatusBarKeyguardViewManager, never()).notifyKeyguardAuthenticated(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_NONE);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andBypass_dismissKeyguard() {
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_FADING);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andNonBypassAndUdfps_dismissKeyguard() {
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(false);
        when(mAuthController.isUdfpsFingerDown()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
            .isEqualTo(BiometricUnlockController.MODE_UNLOCK_FADING);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andBypass_encrypted_showBouncer() {
        reset(mUpdateMonitor);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        // Wake up before showing the bouncer
        verify(mStatusBarKeyguardViewManager, never()).showBouncer(eq(false));
        mBiometricUnlockController.mWakefulnessObserver.onFinishedWakingUp();

        verify(mStatusBarKeyguardViewManager).showBouncer(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_noBypass_encrypted_doNothing() {
        reset(mUpdateMonitor);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).showBouncer(anyBoolean());
        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_NONE);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FACE);
    }

    @Test
    public void onBiometricAuthenticated_whenBypassOnBouncer_dismissBouncer() {
        reset(mKeyguardBypassController);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        when(mKeyguardBypassController.onBiometricAuthenticated(any(), anyBoolean()))
                .thenReturn(true);
        when(mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenBypassOnBouncer_respectsCanPlaySubtleAnim() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_FADING);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceAndPulsing_dontDismissKeyguard() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_ONLY_WAKE);
    }

    @Test
    public void onFinishedGoingToSleep_authenticatesWhenPending() {
        when(mUpdateMonitor.isGoingToSleep()).thenReturn(true);
        mBiometricUnlockController.onFinishedGoingToSleep(-1);
        verify(mHandler, never()).post(any());

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(1 /* userId */,
                BiometricSourceType.FACE, true /* isStrongBiometric */);
        mBiometricUnlockController.onFinishedGoingToSleep(-1);
        verify(mHandler).post(captor.capture());
        captor.getValue().run();
    }
}
