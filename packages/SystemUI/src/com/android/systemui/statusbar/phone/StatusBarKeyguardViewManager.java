/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.sNewInsetsMode;
import static android.view.WindowInsets.Type.navigationBars;

import static com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_FADING;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.systemui.DejankUtils;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.phone.KeyguardBouncer.BouncerExpansionCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewBase}.
 */
@Singleton
public class StatusBarKeyguardViewManager implements RemoteInputController.Callback,
        StatusBarStateController.StateListener, ConfigurationController.ConfigurationListener,
        PanelExpansionListener, NavigationModeController.ModeChangedListener,
        KeyguardViewController {

    // When hiding the Keyguard with timing supplied from WindowManager, better be early than late.
    private static final long HIDE_TIMING_CORRECTION_MS = - 16 * 3;

    // Delay for showing the navigation bar when the bouncer appears. This should be kept in sync
    // with the appear animations of the PIN/pattern/password views.
    private static final long NAV_BAR_SHOW_DELAY_BOUNCER = 320;

    private static final long WAKE_AND_UNLOCK_SCRIM_FADEOUT_DURATION_MS = 200;

    // Duration of the Keyguard dismissal animation in case the user is currently locked. This is to
    // make everything a bit slower to bridge a gap until the user is unlocked and home screen has
    // dranw its first frame.
    private static final long KEYGUARD_DISMISS_DURATION_LOCKED = 2000;

    private static String TAG = "StatusBarKeyguardViewManager";

    protected final Context mContext;
    private final ConfigurationController mConfigurationController;
    private final NavigationModeController mNavigationModeController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final BouncerExpansionCallback mExpansionCallback = new BouncerExpansionCallback() {
        @Override
        public void onFullyShown() {
            updateStates();
            updateLockIcon();
            onKeyguardBouncerFullyShownChanged(true);
        }

        @Override
        public void onStartingToHide() {
            updateStates();
        }

        @Override
        public void onStartingToShow() {
            updateStates();
            updateLockIcon();
        }

        @Override
        public void onFullyHidden() {
            updateStates();
            updateLockIcon();
            onKeyguardBouncerFullyShownChanged(false);
        }
    };
    private final DockManager.DockEventListener mDockEventListener =
            new DockManager.DockEventListener() {
                @Override
                public void onEvent(int event) {
                    boolean isDocked = mDockManager.isDocked();
            if (isDocked == mIsDocked) {
                return;
            }
            mIsDocked = isDocked;
            updateStates();
        }
    };

    protected LockPatternUtils mLockPatternUtils;
    protected ViewMediatorCallback mViewMediatorCallback;
    protected StatusBar mStatusBar;
    private NotificationPanelViewController mNotificationPanelViewController;
    private BiometricUnlockController mBiometricUnlockController;

    private ViewGroup mContainer;
    private ViewGroup mLockIconContainer;
    private View mNotificationContainer;

    protected KeyguardBouncer mBouncer;
    protected boolean mShowing;
    protected boolean mOccluded;
    protected boolean mRemoteInputActive;
    private boolean mGlobalActionsVisible = false;
    private boolean mLastGlobalActionsVisible = false;
    private boolean mDozing;
    private boolean mPulsing;
    private boolean mGesturalNav;
    private boolean mIsDocked;

    protected boolean mFirstUpdate = true;
    protected boolean mLastShowing;
    protected boolean mLastOccluded;
    private boolean mLastBouncerShowing;
    private boolean mLastBouncerInTransit;
    private boolean mLastBouncerDismissible;
    protected boolean mLastRemoteInputActive;
    private boolean mLastDozing;
    private boolean mLastGesturalNav;
    private boolean mLastIsDocked;
    private boolean mLastPulsing;
    private int mLastBiometricMode;
    private boolean mLastLockVisible;

    private OnDismissAction mAfterKeyguardGoneAction;
    private Runnable mKeyguardGoneCancelAction;
    private final ArrayList<Runnable> mAfterKeyguardGoneRunnables = new ArrayList<>();

    // Dismiss action to be launched when we stop dozing or the keyguard is gone.
    private DismissWithActionRequest mPendingWakeupAction;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationMediaManager mMediaManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final DockManager mDockManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateManager;
    private KeyguardBypassController mBypassController;

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onEmergencyCallAction() {

            // Since we won't get a setOccluded call we have to reset the view manually such that
            // the bouncer goes away.
            if (mOccluded) {
                reset(true /* hideBouncerWhenShowing */);
            }
        }
    };

    @Inject
    public StatusBarKeyguardViewManager(
            Context context,
            ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils,
            SysuiStatusBarStateController sysuiStatusBarStateController,
            ConfigurationController configurationController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            NavigationModeController navigationModeController,
            DockManager dockManager,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            NotificationMediaManager notificationMediaManager) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mConfigurationController = configurationController;
        mNavigationModeController = navigationModeController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mKeyguardStateController = keyguardStateController;
        mMediaManager = notificationMediaManager;
        mKeyguardUpdateManager = keyguardUpdateMonitor;
        mStatusBarStateController = sysuiStatusBarStateController;
        mDockManager = dockManager;
    }

    @Override
    public void registerStatusBar(StatusBar statusBar,
            ViewGroup container,
            NotificationPanelViewController notificationPanelViewController,
            BiometricUnlockController biometricUnlockController,
            DismissCallbackRegistry dismissCallbackRegistry,
            ViewGroup lockIconContainer, View notificationContainer,
            KeyguardBypassController bypassController, FalsingManager falsingManager) {
        mStatusBar = statusBar;
        mContainer = container;
        mLockIconContainer = lockIconContainer;
        if (mLockIconContainer != null) {
            mLastLockVisible = mLockIconContainer.getVisibility() == View.VISIBLE;
        }
        mBiometricUnlockController = biometricUnlockController;
        mBouncer = SystemUIFactory.getInstance().createKeyguardBouncer(mContext,
                mViewMediatorCallback, mLockPatternUtils, container, dismissCallbackRegistry,
                mExpansionCallback, mKeyguardStateController, falsingManager, bypassController);
        mNotificationPanelViewController = notificationPanelViewController;
        notificationPanelViewController.addExpansionListener(this);
        mBypassController = bypassController;
        mNotificationContainer = notificationContainer;

        registerListeners();
    }

    private void registerListeners() {
        mKeyguardUpdateManager.registerCallback(mUpdateMonitorCallback);
        mStatusBarStateController.addCallback(this);
        mConfigurationController.addCallback(this);
        mGesturalNav = QuickStepContract.isGesturalMode(
                mNavigationModeController.addListener(this));
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
            mIsDocked = mDockManager.isDocked();
        }
    }

    @Override
    public void onPanelExpansionChanged(float expansion, boolean tracking) {
        // We don't want to translate the bounce when:
        // • Keyguard is occluded, because we're in a FLAG_SHOW_WHEN_LOCKED activity and need to
        //   conserve the original animation.
        // • The user quickly taps on the display and we show "swipe up to unlock."
        // • Keyguard will be dismissed by an action. a.k.a: FLAG_DISMISS_KEYGUARD_ACTIVITY
        // • Full-screen user switcher is displayed.
        if (mNotificationPanelViewController.isUnlockHintRunning()) {
            mBouncer.setExpansion(KeyguardBouncer.EXPANSION_HIDDEN);
        } else if (bouncerNeedsScrimming()) {
            mBouncer.setExpansion(KeyguardBouncer.EXPANSION_VISIBLE);
        } else if (mShowing) {
            if (!isWakeAndUnlocking() && !mStatusBar.isInLaunchTransition()) {
                mBouncer.setExpansion(expansion);
            }
            if (expansion != KeyguardBouncer.EXPANSION_HIDDEN && tracking
                    && !mKeyguardStateController.canDismissLockScreen()
                    && !mBouncer.isShowing() && !mBouncer.isAnimatingAway()) {
                mBouncer.show(false /* resetSecuritySelection */, false /* scrimmed */);
            }
        } else if (mPulsing && expansion == KeyguardBouncer.EXPANSION_VISIBLE) {
            // Panel expanded while pulsing but didn't translate the bouncer (because we are
            // unlocked.) Let's simply wake-up to dismiss the lock screen.
            mStatusBar.wakeUpIfDozing(SystemClock.uptimeMillis(), mContainer, "BOUNCER_VISIBLE");
        }
    }

    @Override
    public void onQsExpansionChanged(float expansion) {
        updateLockIcon();
    }

    /**
     * Update the global actions visibility state in order to show the navBar when active.
     */
    public void setGlobalActionsVisible(boolean isVisible) {
        mGlobalActionsVisible = isVisible;
        updateStates();
    }

    private void updateLockIcon() {
        // Not all form factors have a lock icon
        if (mLockIconContainer == null) {
            return;
        }
        boolean keyguardWithoutQs = mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                && !mNotificationPanelViewController.isQsExpanded();
        boolean lockVisible = (mBouncer.isShowing() || keyguardWithoutQs)
                && !mBouncer.isAnimatingAway() && !mKeyguardStateController.isKeyguardFadingAway();

        if (mLastLockVisible != lockVisible) {
            mLastLockVisible = lockVisible;
            if (lockVisible) {
                CrossFadeHelper.fadeIn(mLockIconContainer,
                        AppearAnimationUtils.DEFAULT_APPEAR_DURATION /* duration */,
                        0 /* delay */);
            } else {
                final long duration;
                final int delay;
                if (needsBypassFading()) {
                    duration = KeyguardBypassController.BYPASS_PANEL_FADE_DURATION;
                    delay = 0;
                } else {
                    duration = AppearAnimationUtils.DEFAULT_APPEAR_DURATION / 2;
                    delay = 120;
                }
                CrossFadeHelper.fadeOut(mLockIconContainer, duration, delay, null /* runnable */);
            }
        }
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    @Override
    public void show(Bundle options) {
        mShowing = true;
        mNotificationShadeWindowController.setKeyguardShowing(true);
        mKeyguardStateController.notifyKeyguardState(mShowing,
                mKeyguardStateController.isOccluded());
        reset(true /* hideBouncerWhenShowing */);
        SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link KeyguardBouncer#needsFullscreenBouncer()}.
     */
    protected void showBouncerOrKeyguard(boolean hideBouncerWhenShowing) {
        if (mBouncer.needsFullscreenBouncer() && !mDozing) {
            // The keyguard might be showing (already). So we need to hide it.
            mStatusBar.hideKeyguard();
            mBouncer.show(true /* resetSecuritySelection */);
        } else {
            mStatusBar.showKeyguard();
            if (hideBouncerWhenShowing) {
                hideBouncer(shouldDestroyViewOnReset() /* destroyView */);
                mBouncer.prepare();
            }
        }
        updateStates();
    }

    protected boolean shouldDestroyViewOnReset() {
        return false;
    }

    @VisibleForTesting
    void hideBouncer(boolean destroyView) {
        if (mBouncer == null) {
            return;
        }
        if (mShowing) {
            // If we were showing the bouncer and then aborting, we need to also clear out any
            // potential actions unless we actually unlocked.
            mAfterKeyguardGoneAction = null;
            if (mKeyguardGoneCancelAction != null) {
                mKeyguardGoneCancelAction.run();
                mKeyguardGoneCancelAction = null;
            }
        }
        mBouncer.hide(destroyView);
        cancelPendingWakeupAction();
    }

    /**
     * Shows the keyguard bouncer - the password challenge on the lock screen
     *
     * @param scrimmed true when the bouncer should show scrimmed, false when the user will be
     * dragging it and translation should be deferred {@see KeyguardBouncer#show(boolean, boolean)}
     */
    public void showBouncer(boolean scrimmed) {
        if (mShowing && !mBouncer.isShowing()) {
            mBouncer.show(false /* resetSecuritySelection */, scrimmed);
        }
        updateStates();
    }

    public void dismissWithAction(OnDismissAction r, Runnable cancelAction,
            boolean afterKeyguardGone) {
        dismissWithAction(r, cancelAction, afterKeyguardGone, null /* message */);
    }

    public void dismissWithAction(OnDismissAction r, Runnable cancelAction,
            boolean afterKeyguardGone, String message) {
        if (mShowing) {
            cancelPendingWakeupAction();
            // If we're dozing, this needs to be delayed until after we wake up - unless we're
            // wake-and-unlocking, because there dozing will last until the end of the transition.
            if (mDozing && !isWakeAndUnlocking()) {
                mPendingWakeupAction = new DismissWithActionRequest(
                        r, cancelAction, afterKeyguardGone, message);
                return;
            }

            if (!afterKeyguardGone) {
                mBouncer.showWithDismissAction(r, cancelAction);
            } else {
                mAfterKeyguardGoneAction = r;
                mKeyguardGoneCancelAction = cancelAction;
                mBouncer.show(false /* resetSecuritySelection */);
            }
        }
        updateStates();
    }

    private boolean isWakeAndUnlocking() {
        int mode = mBiometricUnlockController.getMode();
        return mode == MODE_WAKE_AND_UNLOCK || mode == MODE_WAKE_AND_UNLOCK_PULSING;
    }

    /**
     * Adds a {@param runnable} to be executed after Keyguard is gone.
     */
    public void addAfterKeyguardGoneRunnable(Runnable runnable) {
        mAfterKeyguardGoneRunnables.add(runnable);
    }

    @Override
    public void reset(boolean hideBouncerWhenShowing) {
        if (mShowing) {
            if (mOccluded && !mDozing) {
                mStatusBar.hideKeyguard();
                if (hideBouncerWhenShowing || mBouncer.needsFullscreenBouncer()) {
                    hideBouncer(false /* destroyView */);
                }
            } else {
                showBouncerOrKeyguard(hideBouncerWhenShowing);
            }
            mKeyguardUpdateManager.sendKeyguardReset();
            updateStates();
        }
    }

    @Override
    public void onStartedWakingUp() {
        mStatusBar.getNotificationShadeWindowView().getWindowInsetsController()
                .setAnimationsDisabled(false);
    }

    @Override
    public void onStartedGoingToSleep() {
        mStatusBar.getNotificationShadeWindowView().getWindowInsetsController()
                .setAnimationsDisabled(true);
    }

    @Override
    public void onFinishedGoingToSleep() {
        mBouncer.onScreenTurnedOff();
    }

    @Override
    public void onRemoteInputActive(boolean active) {
        mRemoteInputActive = active;
        updateStates();
    }

    private void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            mDozing = dozing;
            if (dozing || mBouncer.needsFullscreenBouncer() || mOccluded) {
                reset(dozing /* hideBouncerWhenShowing */);
            }
            updateStates();

            if (!dozing) {
                launchPendingWakeupAction();
            }
        }
    }

    /**
     * If {@link StatusBar} is pulsing.
     */
    public void setPulsing(boolean pulsing) {
        if (mPulsing != pulsing) {
            mPulsing = pulsing;
            updateStates();
        }
    }

    @Override
    public void setNeedsInput(boolean needsInput) {
        mNotificationShadeWindowController.setKeyguardNeedsInput(needsInput);
    }

    @Override
    public boolean isUnlockWithWallpaper() {
        return mNotificationShadeWindowController.isShowingWallpaper();
    }

    @Override
    public void setOccluded(boolean occluded, boolean animate) {
        mStatusBar.setOccluded(occluded);
        if (occluded && !mOccluded && mShowing) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__OCCLUDED);
            if (mStatusBar.isInLaunchTransition()) {
                mOccluded = true;
                mStatusBar.fadeKeyguardAfterLaunchTransition(null /* beforeFading */,
                        new Runnable() {
                            @Override
                            public void run() {
                                mNotificationShadeWindowController.setKeyguardOccluded(mOccluded);
                                reset(true /* hideBouncerWhenShowing */);
                            }
                        });
                return;
            }
        } else if (!occluded && mOccluded && mShowing) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
        }
        boolean isOccluding = !mOccluded && occluded;
        mOccluded = occluded;
        if (mShowing) {
            mMediaManager.updateMediaMetaData(false, animate && !occluded);
        }
        mNotificationShadeWindowController.setKeyguardOccluded(occluded);
        mStatusBar.getVisualizer().setOccluded(occluded);

        // setDozing(false) will call reset once we stop dozing.
        if (!mDozing) {
            // If Keyguard is reshown, don't hide the bouncer as it might just have been requested
            // by a FLAG_DISMISS_KEYGUARD_ACTIVITY.
            reset(isOccluding /* hideBouncerWhenShowing*/);
        }
        if (animate && !occluded && mShowing && !mBouncer.isShowing()) {
            mStatusBar.animateKeyguardUnoccluding();
        }
    }

    public boolean isOccluded() {
        return mOccluded;
    }

    @Override
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer.isShowing()) {
            mBouncer.startPreHideAnimation(finishRunnable);
            mStatusBar.onBouncerPreHideAnimation();
        } else if (finishRunnable != null) {
            finishRunnable.run();
        }
        mNotificationPanelViewController.blockExpansionForCurrentTouch();
        updateLockIcon();
    }

    @Override
    public void hide(long startTime, long fadeoutDuration) {
        mShowing = false;
        mKeyguardStateController.notifyKeyguardState(mShowing,
                mKeyguardStateController.isOccluded());
        launchPendingWakeupAction();

        if (mKeyguardUpdateManager.needsSlowUnlockTransition()) {
            fadeoutDuration = KEYGUARD_DISMISS_DURATION_LOCKED;
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        long delay = Math.max(0, startTime + HIDE_TIMING_CORRECTION_MS - uptimeMillis);

        if (mStatusBar.isInLaunchTransition() ) {
            mStatusBar.fadeKeyguardAfterLaunchTransition(new Runnable() {
                @Override
                public void run() {
                    mNotificationShadeWindowController.setKeyguardShowing(false);
                    mNotificationShadeWindowController.setKeyguardFadingAway(true);
                    hideBouncer(true /* destroyView */);
                    updateStates();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    mStatusBar.hideKeyguard();
                    mNotificationShadeWindowController.setKeyguardFadingAway(false);
                    mViewMediatorCallback.keyguardGone();
                    executeAfterKeyguardGoneAction();
                }
            });
        } else {
            executeAfterKeyguardGoneAction();
            boolean wakeUnlockPulsing =
                    mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING;
            boolean needsFading = needsBypassFading();
            if (needsFading) {
                delay = 0;
                fadeoutDuration = KeyguardBypassController.BYPASS_PANEL_FADE_DURATION;
            } else if (wakeUnlockPulsing) {
                delay = 0;
                fadeoutDuration = 240;
            }
            mStatusBar.setKeyguardFadingAway(startTime, delay, fadeoutDuration, needsFading);
            mBiometricUnlockController.startKeyguardFadingAway();
            hideBouncer(true /* destroyView */);
            if (wakeUnlockPulsing) {
                if (needsFading) {
                    ViewGroupFadeHelper.fadeOutAllChildrenExcept(
                            mNotificationPanelViewController.getView(),
                            mNotificationContainer,
                            fadeoutDuration,
                                    () -> {
                        mStatusBar.hideKeyguard();
                        onKeyguardFadedAway();
                    });
                } else {
                    mStatusBar.fadeKeyguardWhilePulsing();
                }
                wakeAndUnlockDejank();
            } else {
                boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
                if (!staying) {
                    mNotificationShadeWindowController.setKeyguardFadingAway(true);
                    if (needsFading) {
                        ViewGroupFadeHelper.fadeOutAllChildrenExcept(
                                mNotificationPanelViewController.getView(),
                                mNotificationContainer,
                                fadeoutDuration,
                                () -> {
                                    mStatusBar.hideKeyguard();
                                });
                    } else {
                        mStatusBar.hideKeyguard();
                    }
                    // hide() will happen asynchronously and might arrive after the scrims
                    // were already hidden, this means that the transition callback won't
                    // be triggered anymore and StatusBarWindowController will be forever in
                    // the fadingAway state.
                    mStatusBar.updateScrimController();
                    wakeAndUnlockDejank();
                } else {
                    mStatusBar.hideKeyguard();
                    mStatusBar.finishKeyguardFadingAway();
                    mBiometricUnlockController.finishKeyguardFadingAway();
                }
            }
            updateLockIcon();
            updateStates();
            mNotificationShadeWindowController.setKeyguardShowing(false);
            mViewMediatorCallback.keyguardGone();
        }
        SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__HIDDEN);
    }

    private boolean needsBypassFading() {
        return (mBiometricUnlockController.getMode() == MODE_UNLOCK_FADING
                || mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING
                || mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK)
                && mBypassController.getBypassEnabled();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        hideBouncer(true /* destroyView */);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        boolean gesturalNav = QuickStepContract.isGesturalMode(mode);
        if (gesturalNav != mGesturalNav) {
            mGesturalNav = gesturalNav;
            updateStates();
        }
    }

    public void onThemeChanged() {
        hideBouncer(true /* destroyView */);
        mBouncer.prepare();
    }

    public void onKeyguardFadedAway() {
        mContainer.postDelayed(() -> mNotificationShadeWindowController
                        .setKeyguardFadingAway(false), 100);
        ViewGroupFadeHelper.reset(mNotificationPanelViewController.getView());
        mStatusBar.finishKeyguardFadingAway();
        mBiometricUnlockController.finishKeyguardFadingAway();
        WindowManagerGlobal.getInstance().trimMemory(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);

    }

    private void wakeAndUnlockDejank() {
        if (mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK
                && LatencyTracker.isEnabled(mContext)) {
            DejankUtils.postAfterTraversal(() ->
                    LatencyTracker.getInstance(mContext).onActionEnd(
                            LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK));
        }
    }

    private void executeAfterKeyguardGoneAction() {
        if (mAfterKeyguardGoneAction != null) {
            mAfterKeyguardGoneAction.onDismiss();
            mAfterKeyguardGoneAction = null;
        }
        mKeyguardGoneCancelAction = null;
        for (int i = 0; i < mAfterKeyguardGoneRunnables.size(); i++) {
            mAfterKeyguardGoneRunnables.get(i).run();
        }
        mAfterKeyguardGoneRunnables.clear();
    }

    @Override
    public void dismissAndCollapse() {
        mStatusBar.executeRunnableDismissingKeyguard(null, null, true, false, true);
    }

    /**
     * WARNING: This method might cause Binder calls.
     */
    public boolean isSecure() {
        return mBouncer.isSecure();
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Notifies this manager that the back button has been pressed.
     *
     * @param hideImmediately Hide bouncer when {@code true}, keep it around otherwise.
     *                        Non-scrimmed bouncers have a special animation tied to the expansion
     *                        of the notification panel.
     * @return whether the back press has been handled
     */
    public boolean onBackPressed(boolean hideImmediately) {
        if (mBouncer.isShowing()) {
            mStatusBar.endAffordanceLaunch();
            // The second condition is for SIM card locked bouncer
            if (mBouncer.isScrimmed() && !mBouncer.needsFullscreenBouncer()) {
                hideBouncer(false);
                updateStates();
            } else {
                reset(hideImmediately);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isBouncerShowing() {
        return mBouncer.isShowing();
    }

    @Override
    public boolean bouncerIsOrWillBeShowing() {
        return mBouncer.isShowing() || mBouncer.inTransit();
    }

    public boolean isFullscreenBouncer() {
        return mBouncer.isFullscreenBouncer();
    }

    private long getNavBarShowDelay() {
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            return mKeyguardStateController.getKeyguardFadingAwayDelay();
        } else if (mBouncer.isShowing()) {
            return NAV_BAR_SHOW_DELAY_BOUNCER;
        } else {
            // No longer dozing, or remote input is active. No delay.
            return 0;
        }
    }

    private Runnable mMakeNavigationBarVisibleRunnable = new Runnable() {
        @Override
        public void run() {
            if (ViewRootImpl.sNewInsetsMode == NEW_INSETS_MODE_FULL) {
                mStatusBar.getNotificationShadeWindowView().getWindowInsetsController()
                        .show(navigationBars());
            } else {
                mStatusBar.getNavigationBarView().getRootView().setVisibility(View.VISIBLE);
            }
        }
    };

    private void onKeyguardBouncerFullyShownChanged(boolean fullyShown){
        mKeyguardUpdateManager.onKeyguardBouncerFullyShown(fullyShown);
    }

    protected void updateStates() {
        int vis = mContainer.getSystemUiVisibility();
        boolean showing = mShowing;
        boolean occluded = mOccluded;
        boolean bouncerShowing = mBouncer.isShowing();
        boolean bouncerInTransit = mBouncer.inTransit();
        boolean bouncerDismissible = !mBouncer.isFullscreenBouncer();
        boolean remoteInputActive = mRemoteInputActive;

        if ((bouncerDismissible || !showing || remoteInputActive) !=
                (mLastBouncerDismissible || !mLastShowing || mLastRemoteInputActive)
                || mFirstUpdate) {
            if (bouncerDismissible || !showing || remoteInputActive) {
                mContainer.setSystemUiVisibility(vis & ~View.STATUS_BAR_DISABLE_BACK);
            } else {
                mContainer.setSystemUiVisibility(vis | View.STATUS_BAR_DISABLE_BACK);
            }
        }

        boolean navBarVisible = isNavBarVisible();
        boolean lastNavBarVisible = getLastNavBarVisible();
        if (navBarVisible != lastNavBarVisible || mFirstUpdate) {
            updateNavigationBarVisibility(navBarVisible);
        }

        mLockIconContainer.setVisibility((mLastLockVisible && mDozing)
                 ? View.GONE : View.VISIBLE);

        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            mNotificationShadeWindowController.setBouncerShowing(bouncerShowing);
            mStatusBar.setBouncerShowing(bouncerShowing);
            updateLockIcon();
        }

        if ((showing && !occluded) != (mLastShowing && !mLastOccluded) || mFirstUpdate) {
            mKeyguardUpdateManager.onKeyguardVisibilityChanged(showing && !occluded);
        }

        boolean bouncerVisible = bouncerShowing || bouncerInTransit;
        boolean lastBouncerVisible = mLastBouncerShowing || mLastBouncerInTransit;
        if (bouncerVisible != lastBouncerVisible || mFirstUpdate) {
            mKeyguardUpdateManager.sendKeyguardBouncerChanged(bouncerVisible);
        }

        mFirstUpdate = false;
        mLastShowing = showing;
        mLastGlobalActionsVisible = mGlobalActionsVisible;
        mLastOccluded = occluded;
        mLastBouncerShowing = bouncerShowing;
        mLastBouncerInTransit = bouncerInTransit;
        mLastBouncerDismissible = bouncerDismissible;
        mLastRemoteInputActive = remoteInputActive;
        mLastDozing = mDozing;
        mLastPulsing = mPulsing;
        mLastBiometricMode = mBiometricUnlockController.getMode();
        mLastGesturalNav = mGesturalNav;
        mLastIsDocked = mIsDocked;
        mStatusBar.onKeyguardViewManagerStatesUpdated();
    }

    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if (mStatusBar.getNavigationBarView() != null) {
            if (navBarVisible) {
                long delay = getNavBarShowDelay();
                if (delay == 0) {
                    mMakeNavigationBarVisibleRunnable.run();
                } else {
                    mContainer.postOnAnimationDelayed(mMakeNavigationBarVisibleRunnable,
                            delay);
                }
            } else {
                mContainer.removeCallbacks(mMakeNavigationBarVisibleRunnable);
                if (sNewInsetsMode == NEW_INSETS_MODE_FULL) {
                    mStatusBar.getNotificationShadeWindowView().getWindowInsetsController()
                            .hide(navigationBars());
                } else {
                    mStatusBar.getNavigationBarView().getRootView().setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * @return Whether the navigation bar should be made visible based on the current state.
     */
    protected boolean isNavBarVisible() {
        int biometricMode = mBiometricUnlockController.getMode();
        boolean keyguardShowing = mShowing && !mOccluded;
        boolean hideWhileDozing = mDozing && biometricMode != MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardWithGestureNav = (keyguardShowing && !mDozing || mPulsing && !mIsDocked)
                && mGesturalNav;
        return (!keyguardShowing && !hideWhileDozing || mBouncer.isShowing()
                || mRemoteInputActive || keyguardWithGestureNav
                || mGlobalActionsVisible);
    }

    /**
     * @return Whether the navigation bar was made visible based on the last known state.
     */
    protected boolean getLastNavBarVisible() {
        boolean keyguardShowing = mLastShowing && !mLastOccluded;
        boolean hideWhileDozing = mLastDozing && mLastBiometricMode != MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardWithGestureNav = (keyguardShowing && !mLastDozing
                || mLastPulsing && !mLastIsDocked) && mLastGesturalNav;
        return (!keyguardShowing && !hideWhileDozing || mLastBouncerShowing
                || mLastRemoteInputActive || keyguardWithGestureNav
                || mLastGlobalActionsVisible);
    }

    public boolean shouldDismissOnMenuPressed() {
        return mBouncer.shouldDismissOnMenuPressed();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mBouncer.interceptMediaKey(event);
    }

    public void readyForKeyguardDone() {
        mViewMediatorCallback.readyForKeyguardDone();
    }

    @Override
    public boolean shouldDisableWindowAnimationsForUnlock() {
        return mStatusBar.isInLaunchTransition();
    }

    @Override
    public boolean shouldSubtleWindowAnimationsForUnlock() {
        return needsBypassFading();
    }

    @Override
    public boolean isGoingToNotificationShade() {
        return mStatusBarStateController.leaveOpenOnKeyguardHide();
    }

    public boolean isSecure(int userId) {
        return mBouncer.isSecure() || mLockPatternUtils.isSecure(userId);
    }

    @Override
    public void keyguardGoingAway() {
        mStatusBar.keyguardGoingAway();
    }

    @Override
    public void setKeyguardGoingAwayState(boolean isKeyguardGoingAway) {
        mNotificationShadeWindowController.setKeyguardGoingAway(isKeyguardGoingAway);
    }

    @Override
    public void onCancelClicked() {
        // No-op
    }

    /**
     * Notifies that the user has authenticated by other means than using the bouncer, for example,
     * fingerprint.
     */
    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        mBouncer.notifyKeyguardAuthenticated(strongAuth);
    }

    public void showBouncerMessage(String message, ColorStateList colorState) {
        mBouncer.showMessage(message, colorState);
    }

    @Override
    public ViewRootImpl getViewRootImpl() {
        return mStatusBar.getStatusBarView().getViewRootImpl();
    }

    public void launchPendingWakeupAction() {
        DismissWithActionRequest request = mPendingWakeupAction;
        mPendingWakeupAction = null;
        if (request != null) {
            if (mShowing) {
                dismissWithAction(request.dismissAction, request.cancelAction,
                        request.afterKeyguardGone, request.message);
            } else if (request.dismissAction != null) {
                request.dismissAction.onDismiss();
            }
        }
    }

    public void cancelPendingWakeupAction() {
        DismissWithActionRequest request = mPendingWakeupAction;
        mPendingWakeupAction = null;
        if (request != null && request.cancelAction != null) {
            request.cancelAction.run();
        }
    }

    public boolean bouncerNeedsScrimming() {
        return mOccluded || mBouncer.willDismissWithAction()
                || mStatusBar.isFullScreenUserSwitcherState()
                || (mBouncer.isShowing() && mBouncer.isScrimmed())
                || mBouncer.isFullscreenBouncer();
    }

    public void dump(PrintWriter pw) {
        pw.println("StatusBarKeyguardViewManager:");
        pw.println("  mShowing: " + mShowing);
        pw.println("  mOccluded: " + mOccluded);
        pw.println("  mRemoteInputActive: " + mRemoteInputActive);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mAfterKeyguardGoneAction: " + mAfterKeyguardGoneAction);
        pw.println("  mAfterKeyguardGoneRunnables: " + mAfterKeyguardGoneRunnables);
        pw.println("  mPendingWakeupAction: " + mPendingWakeupAction);

        if (mBouncer != null) {
            mBouncer.dump(pw);
        }
    }

    @Override
    public void onStateChanged(int newState) {
        updateLockIcon();
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    public KeyguardBouncer getBouncer() {
        return mBouncer;
    }

    private static class DismissWithActionRequest {
        final OnDismissAction dismissAction;
        final Runnable cancelAction;
        final boolean afterKeyguardGone;
        final String message;

        DismissWithActionRequest(OnDismissAction dismissAction, Runnable cancelAction,
                boolean afterKeyguardGone, String message) {
            this.dismissAction = dismissAction;
            this.cancelAction = cancelAction;
            this.afterKeyguardGone = afterKeyguardGone;
            this.message = message;
        }
    }
}
