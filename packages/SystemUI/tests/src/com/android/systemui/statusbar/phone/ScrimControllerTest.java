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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.ScrimController.VISIBILITY_FULLY_OPAQUE;
import static com.android.systemui.statusbar.phone.ScrimController.VISIBILITY_FULLY_TRANSPARENT;
import static com.android.systemui.statusbar.phone.ScrimController.VISIBILITY_SEMI_TRANSPARENT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.AlarmManager;
import android.graphics.Color;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.util.function.TriConsumer;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ScrimControllerTest extends SysuiTestCase {

    private SynchronousScrimController mScrimController;
    private ScrimView mScrimBehind;
    private ScrimView mScrimInFront;
    private ScrimState mScrimState;
    private float mScrimBehindAlpha;
    private GradientColors mScrimInFrontColor;
    private int mScrimVisibility;
    private DozeParameters mDozeParamenters;
    private WakeLock mWakeLock;
    private boolean mAlwaysOnEnabled;
    private AlarmManager mAlarmManager;
    private TestableLooper mLooper;


    @Before
    public void setup() {
        mScrimBehind = spy(new ScrimView(getContext()));
        mScrimInFront = new ScrimView(getContext());
        mWakeLock = mock(WakeLock.class);
        mAlarmManager = mock(AlarmManager.class);
        mAlwaysOnEnabled = true;
        mDozeParamenters = mock(DozeParameters.class);
        mLooper = TestableLooper.get(this);
        when(mDozeParamenters.getAlwaysOn()).thenAnswer(invocation -> mAlwaysOnEnabled);
        when(mDozeParamenters.getDisplayNeedsBlanking()).thenReturn(true);
        mScrimController = new SynchronousScrimController(mScrimBehind, mScrimInFront,
                (scrimState, scrimBehindAlpha, scrimInFrontColor) -> {
                    mScrimState = scrimState;
                    mScrimBehindAlpha = scrimBehindAlpha;
                    mScrimInFrontColor = scrimInFrontColor;
                },
                visible -> mScrimVisibility = visible, mDozeParamenters, mAlarmManager,
                mock(KeyguardMonitor.class));
        mScrimController.setHasBackdrop(false);
        mScrimController.setWallpaperSupportsAmbientMode(false);
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();
    }

    @After
    public void tearDown() {
        mScrimController.finishAnimationsImmediately();
    }

    @Test
    public void transitionToKeyguard() {
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);
        assertScrimTint(mScrimBehind, true /* tinted */);
    }

    @Test
    public void transitionToAod_withRegularWallpaper() {
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);
        assertScrimTint(mScrimInFront, true /* tinted */);
    }

    @Test
    public void transitionToAod_withAodWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);

        // Pulsing notification should conserve AOD wallpaper.
        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
    }

    @Test
    public void transitionToAod_withAodWallpaperAndLockScreenWallpaper() {
        mScrimController.setHasBackdrop(true);
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);
        assertScrimTint(mScrimInFront, true /* tinted */);
    }

    @Test
    public void setHasBackdrop_withAodWallpaperAndAlbumArt() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        mScrimController.setHasBackdrop(true);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible with tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);
        assertScrimTint(mScrimInFront, true /* tinted */);
    }

    @Test
    public void transitionToAod_withFrontAlphaUpdates() {
        // Assert that setting the AOD front scrim alpha doesn't take effect in a non-AOD state.
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.setAodFrontScrimAlpha(0.5f);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);

        // ... but that it does take effect once we enter the AOD state.
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be semi-transparent
        // Back scrim should be visible
        assertScrimVisibility(VISIBILITY_SEMI_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);

        // ... and that if we set it while we're in AOD, it does take immediate effect.
        mScrimController.setAodFrontScrimAlpha(1f);
        assertScrimVisibility(VISIBILITY_FULLY_OPAQUE, VISIBILITY_FULLY_OPAQUE);

        // ... and make sure we recall the previous front scrim alpha even if we transition away
        // for a bit.
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_OPAQUE, VISIBILITY_FULLY_OPAQUE);

        // ... and alpha updates should be completely ignored if always_on is off.
        // Passing it forward would mess up the wake-up transition.
        mAlwaysOnEnabled = false;
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        mScrimController.setAodFrontScrimAlpha(0.3f);
        Assert.assertEquals(ScrimState.AOD.getFrontAlpha(), mScrimInFront.getViewAlpha(), 0.001f);
        Assert.assertNotEquals(0.3f, mScrimInFront.getViewAlpha(), 0.001f);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToPulsing() {
        // Pre-condition
        // Need to go to AoD first because PULSING doesn't change
        // the back scrim opacity - otherwise it would hide AoD wallpapers.
        mScrimController.setWallpaperSupportsAmbientMode(false);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);

        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent, but tinted
        // Back scrim should be semi-transparent so the user can see the wallpaper
        // Pulse callback should have been invoked
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
        assertScrimTint(mScrimBehind, true /* tinted */);

        // ... and when ambient goes dark, front scrim should be semi-transparent
        mScrimController.setAodFrontScrimAlpha(0.5f);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be semi-transparent
        assertScrimVisibility(VISIBILITY_SEMI_TRANSPARENT /* front */,
                VISIBILITY_FULLY_OPAQUE /* back */);

        mScrimController.setWakeLockScreenSensorActive(true);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_SEMI_TRANSPARENT /* front */,
                VISIBILITY_SEMI_TRANSPARENT /* back */);

        // Reset value since enums are static.
        mScrimController.setAodFrontScrimAlpha(0f);
    }

    @Test
    public void transitionToKeyguardBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToBouncer() {
        mScrimController.transitionTo(ScrimState.BOUNCER_SCRIMMED);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be visible without tint
        assertScrimVisibility(VISIBILITY_SEMI_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void transitionToUnlocked() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
        assertScrimTint(mScrimBehind, false /* tinted */);
        assertScrimTint(mScrimInFront, false /* tinted */);

        // Back scrim should be visible after start dragging
        mScrimController.setPanelExpansion(0.5f);
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_SEMI_TRANSPARENT);
    }

    @Test
    public void transitionToBubbleExpanded() {
        mScrimController.transitionTo(ScrimState.BUBBLE_EXPANDED);
        mScrimController.finishAnimationsImmediately();

        // Front scrim should be transparent
        Assert.assertEquals(ScrimController.VISIBILITY_FULLY_TRANSPARENT,
                mScrimInFront.getViewAlpha(), 0.0f);
        // Back scrim should be visible
        Assert.assertEquals(ScrimController.GRADIENT_SCRIM_ALPHA_BUSY,
                mScrimBehind.getViewAlpha(), 0.0f);
    }

    @Test
    public void scrimStateCallback() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals(mScrimState, ScrimState.UNLOCKED);

        mScrimController.transitionTo(ScrimState.BOUNCER);
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals(mScrimState, ScrimState.BOUNCER);

        mScrimController.transitionTo(ScrimState.BOUNCER_SCRIMMED);
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals(mScrimState, ScrimState.BOUNCER_SCRIMMED);
    }

    @Test
    public void panelExpansion() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        reset(mScrimBehind);
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(1.0f);
        mScrimController.finishAnimationsImmediately();

        Assert.assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setPanelExpansion(0f);
        mScrimController.finishAnimationsImmediately();

        Assert.assertEquals("Scrim alpha should change after setPanelExpansion",
                mScrimBehindAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void panelExpansionAffectsAlpha() {
        mScrimController.setPanelExpansion(0f);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        final float scrimAlpha = mScrimBehind.getViewAlpha();
        reset(mScrimBehind);
        mScrimController.setExpansionAffectsAlpha(false);
        mScrimController.setPanelExpansion(0.8f);
        verifyZeroInteractions(mScrimBehind);
        Assert.assertEquals("Scrim opacity shouldn't change when setExpansionAffectsAlpha "
                + "is false", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);

        mScrimController.setExpansionAffectsAlpha(true);
        mScrimController.setPanelExpansion(0.1f);
        mScrimController.finishAnimationsImmediately();
        Assert.assertNotEquals("Scrim opacity should change when setExpansionAffectsAlpha "
                + "is true", scrimAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void transitionToUnlockedFromAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.setPanelExpansion(0f);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        // Immediately tinted after the transition starts
        assertScrimTint(mScrimInFront, true /* tinted */);
        assertScrimTint(mScrimBehind, true /* tinted */);
        mScrimController.finishAnimationsImmediately();
        // Front scrim should be transparent
        // Back scrim should be transparent
        // Neither scrims should be tinted anymore after the animation.
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);
        assertScrimTint(mScrimInFront, false /* tinted */);
        assertScrimTint(mScrimBehind, false /* tinted */);
    }

    @Test
    public void scrimBlanksBeforeLeavingAod() {
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        // Front scrim should be black in the middle of the transition
                        Assert.assertTrue("Scrim should be visible during transition. Alpha: "
                                + mScrimInFront.getViewAlpha(), mScrimInFront.getViewAlpha() > 0);
                        assertScrimTint(mScrimInFront, true /* tinted */);
                        Assert.assertSame("Scrim should be visible during transition.",
                                mScrimVisibility, VISIBILITY_FULLY_OPAQUE);
                    }
                });
        mScrimController.finishAnimationsImmediately();
    }

    @Test
    public void scrimBlanksWhenUnlockingFromPulse() {
        boolean[] blanked = {false};
        // Simulate unlock with fingerprint
        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED,
                new ScrimController.Callback() {
                    @Override
                    public void onDisplayBlanked() {
                        blanked[0] = true;
                    }
                });
        mScrimController.finishAnimationsImmediately();
        Assert.assertTrue("Scrim should blank when unlocking from pulse.", blanked[0]);
    }

    @Test
    public void testScrimCallback() {
        int[] callOrder = {0, 0, 0};
        int[] currentCall = {0};
        mScrimController.transitionTo(ScrimState.AOD, new ScrimController.Callback() {
            @Override
            public void onStart() {
                callOrder[0] = ++currentCall[0];
            }

            @Override
            public void onDisplayBlanked() {
                callOrder[1] = ++currentCall[0];
            }

            @Override
            public void onFinished() {
                callOrder[2] = ++currentCall[0];
            }
        });
        mScrimController.finishAnimationsImmediately();
        Assert.assertEquals("onStart called in wrong order", 1, callOrder[0]);
        Assert.assertEquals("onDisplayBlanked called in wrong order", 2, callOrder[1]);
        Assert.assertEquals("onFinished called in wrong order", 3, callOrder[2]);
    }

    @Test
    public void testScrimCallbacksWithoutAmbientDisplay() {
        mAlwaysOnEnabled = false;
        testScrimCallback();
    }

    @Test
    public void testScrimCallbackCancelled() {
        boolean[] cancelledCalled = {false};
        mScrimController.transitionTo(ScrimState.AOD, new ScrimController.Callback() {
            @Override
            public void onCancelled() {
                cancelledCalled[0] = true;
            }
        });
        mScrimController.transitionTo(ScrimState.PULSING);
        Assert.assertTrue("onCancelled should have been called", cancelledCalled[0]);
    }

    @Test
    public void testHoldsWakeLock_whenAOD() {
        mScrimController.transitionTo(ScrimState.AOD);
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        mScrimController.finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
    }

    @Test
    public void testDoesNotHoldWakeLock_whenUnlocking() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        verifyZeroInteractions(mWakeLock);
    }

    @Test
    public void testCallbackInvokedOnSameStateTransition() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();
        ScrimController.Callback callback = mock(ScrimController.Callback.class);
        mScrimController.transitionTo(ScrimState.UNLOCKED, callback);
        verify(callback).onFinished();
    }

    @Test
    public void testHoldsAodWallpaperAnimationLock() {
        // Pre-conditions
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        reset(mWakeLock);

        mScrimController.onHideWallpaperTimeout();
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        mScrimController.finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
    }

    @Test
    public void testHoldsPulsingWallpaperAnimationLock() {
        // Pre-conditions
        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        reset(mWakeLock);

        mScrimController.onHideWallpaperTimeout();
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());
        mScrimController.finishAnimationsImmediately();
        verify(mWakeLock).release(anyString());
    }

    @Test
    public void testWillHideAodWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        verify(mAlarmManager).setExact(anyInt(), anyLong(), any(), any(), any());
        mScrimController.transitionTo(ScrimState.KEYGUARD);
        verify(mAlarmManager).cancel(any(AlarmManager.OnAlarmListener.class));
    }

    @Test
    public void transitionToPulsing_withTimeoutWallpaperCallback_willHideWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);

        mScrimController.transitionTo(ScrimState.PULSING, new ScrimController.Callback() {
            @Override
            public boolean shouldTimeoutWallpaper() {
                return true;
            }
        });

        verify(mAlarmManager).setExact(anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    public void transitionToPulsing_withDefaultCallback_wontHideWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);

        mScrimController.transitionTo(ScrimState.PULSING, new ScrimController.Callback() {});

        verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    public void transitionToPulsing_withoutCallback_wontHideWallpaper() {
        mScrimController.setWallpaperSupportsAmbientMode(true);

        mScrimController.transitionTo(ScrimState.PULSING);

        verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    public void testConservesExpansionOpacityAfterTransition() {
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.setPanelExpansion(0.5f);
        mScrimController.finishAnimationsImmediately();

        final float expandedAlpha = mScrimBehind.getViewAlpha();

        mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        mScrimController.finishAnimationsImmediately();
        mScrimController.transitionTo(ScrimState.UNLOCKED);
        mScrimController.finishAnimationsImmediately();

        Assert.assertEquals("Scrim expansion opacity wasn't conserved when transitioning back",
                expandedAlpha, mScrimBehind.getViewAlpha(), 0.01f);
    }

    @Test
    public void cancelsOldAnimationBeforeBlanking() {
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        // Consume whatever value we had before
        mScrimController.wasAnimationJustCancelled();

        mScrimController.transitionTo(ScrimState.KEYGUARD);
        mScrimController.finishAnimationsImmediately();
        Assert.assertTrue(mScrimController.wasAnimationJustCancelled());
    }

    @Test
    public void testScrimFocus() {
        mScrimController.transitionTo(ScrimState.AOD);
        Assert.assertFalse("Should not be focusable on AOD", mScrimBehind.isFocusable());
        Assert.assertFalse("Should not be focusable on AOD", mScrimInFront.isFocusable());

        mScrimController.transitionTo(ScrimState.KEYGUARD);
        Assert.assertTrue("Should be focusable on keyguard", mScrimBehind.isFocusable());
        Assert.assertTrue("Should be focusable on keyguard", mScrimInFront.isFocusable());
    }

    @Test
    public void testHidesShowWhenLockedActivity() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.setKeyguardOccluded(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);

        mScrimController.transitionTo(ScrimState.PULSING);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
    }

    @Test
    public void testHidesShowWhenLockedActivity_whenAlreadyInAod() {
        mScrimController.setWallpaperSupportsAmbientMode(true);
        mScrimController.transitionTo(ScrimState.AOD);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_TRANSPARENT);

        mScrimController.setKeyguardOccluded(true);
        mScrimController.finishAnimationsImmediately();
        assertScrimVisibility(VISIBILITY_FULLY_TRANSPARENT, VISIBILITY_FULLY_OPAQUE);
    }

    @Test
    public void testEatsTouchEvent() {
        HashSet<ScrimState> eatsTouches =
                new HashSet<>(Collections.singletonList(ScrimState.AOD));
        for (ScrimState state : ScrimState.values()) {
            if (state == ScrimState.UNINITIALIZED) {
                continue;
            }
            mScrimController.transitionTo(state);
            mScrimController.finishAnimationsImmediately();
            Assert.assertEquals("Should be clickable unless AOD or PULSING, was: " + state,
                    mScrimBehind.getViewAlpha() != 0 && !eatsTouches.contains(state),
                    mScrimBehind.isClickable());
        }
    }

    @Test
    public void testAnimatesTransitionToAod() {
        when(mDozeParamenters.shouldControlScreenOff()).thenReturn(false);
        ScrimState.AOD.prepare(ScrimState.KEYGUARD);
        Assert.assertFalse("No animation when ColorFade kicks in",
                ScrimState.AOD.getAnimateChange());

        reset(mDozeParamenters);
        when(mDozeParamenters.shouldControlScreenOff()).thenReturn(true);
        ScrimState.AOD.prepare(ScrimState.KEYGUARD);
        Assert.assertTrue("Animate scrims when ColorFade won't be triggered",
                ScrimState.AOD.getAnimateChange());
    }

    @Test
    public void testViewsDontHaveFocusHighlight() {
        Assert.assertFalse("Scrim shouldn't have focus highlight",
                mScrimInFront.getDefaultFocusHighlightEnabled());
        Assert.assertFalse("Scrim shouldn't have focus highlight",
                mScrimBehind.getDefaultFocusHighlightEnabled());
    }

    private void assertScrimTint(ScrimView scrimView, boolean tinted) {
        final boolean viewIsTinted = scrimView.getTint() != Color.TRANSPARENT;
        final String name = scrimView == mScrimInFront ? "front" : "back";
        Assert.assertEquals("Tint test failed at state " + mScrimController.getState()
                +" with scrim: " + name + " and tint: " + Integer.toHexString(scrimView.getTint()),
                tinted, viewIsTinted);
    }

    private void assertScrimVisibility(int inFront, int behind) {
        boolean inFrontVisible = inFront != ScrimController.VISIBILITY_FULLY_TRANSPARENT;
        boolean behindVisible = behind != ScrimController.VISIBILITY_FULLY_TRANSPARENT;
        Assert.assertEquals("Unexpected front scrim visibility. Alpha is "
                + mScrimInFront.getViewAlpha(), inFrontVisible, mScrimInFront.getViewAlpha() > 0);
        Assert.assertEquals("Unexpected back scrim visibility. Alpha is "
                + mScrimBehind.getViewAlpha(), behindVisible, mScrimBehind.getViewAlpha() > 0);

        final int visibility;
        if (inFront == VISIBILITY_FULLY_OPAQUE || behind == VISIBILITY_FULLY_OPAQUE) {
            visibility = VISIBILITY_FULLY_OPAQUE;
        } else if (inFront > VISIBILITY_FULLY_TRANSPARENT || behind > VISIBILITY_FULLY_TRANSPARENT) {
            visibility = VISIBILITY_SEMI_TRANSPARENT;
        } else {
            visibility = VISIBILITY_FULLY_TRANSPARENT;
        }
        Assert.assertEquals("Invalid visibility.", visibility, mScrimVisibility);
    }

    /**
     * Special version of ScrimController where animations have 0 duration for test purposes.
     */
    private class SynchronousScrimController extends ScrimController {

        private boolean mAnimationCancelled;
        boolean mOnPreDrawCalled;

        SynchronousScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
                TriConsumer<ScrimState, Float, GradientColors> scrimStateListener,
                Consumer<Integer> scrimVisibleListener, DozeParameters dozeParameters,
                AlarmManager alarmManager, KeyguardMonitor keyguardMonitor) {
            super(scrimBehind, scrimInFront, scrimStateListener, scrimVisibleListener,
                    dozeParameters, alarmManager, keyguardMonitor);
        }

        @Override
        public boolean onPreDraw() {
            mOnPreDrawCalled = true;
            return super.onPreDraw();
        }

        void finishAnimationsImmediately() {
            boolean[] animationFinished = {false};
            setOnAnimationFinished(()-> animationFinished[0] = true);
            // Execute code that will trigger animations.
            onPreDraw();
            // Force finish all animations.
            mLooper.processAllMessages();
            endAnimation(mScrimBehind, TAG_KEY_ANIM);
            endAnimation(mScrimInFront, TAG_KEY_ANIM);

            if (!animationFinished[0]) {
                throw new IllegalStateException("Animation never finished");
            }
        }

        boolean wasAnimationJustCancelled() {
            final boolean wasCancelled = mAnimationCancelled;
            mAnimationCancelled = false;
            return wasCancelled;
        }

        private void endAnimation(View scrimView, int tag) {
            Animator animator = (Animator) scrimView.getTag(tag);
            if (animator != null) {
                animator.end();
            }
        }

        @Override
        protected void cancelAnimator(ValueAnimator previousAnimator) {
            super.cancelAnimator(previousAnimator);
            mAnimationCancelled = true;
        }

        @Override
        protected Handler getHandler() {
            return new FakeHandler(mLooper.getLooper());
        }

        @Override
        protected WakeLock createWakeLock() {
            return mWakeLock;
        }

        /**
         * Do not wait for a frame since we're in a test environment.
         * @param callback What to execute.
         */
        @Override
        protected void doOnTheNextFrame(Runnable callback) {
            callback.run();
        }
    }
}
