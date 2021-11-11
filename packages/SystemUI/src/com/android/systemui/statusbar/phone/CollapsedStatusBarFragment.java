/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_CLOCK;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.StatusBarManager.DISABLE_ONGOING_CALL_CHIP;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_IN;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_OUT;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.IDLE;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.SHOWING_PERSISTENT_DOT;

import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Fragment;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.SparseArray;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallListener;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.EncryptionHelper;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks,
        StatusBarStateController.StateListener,
        SystemStatusAnimationCallback {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    public static final String STATUS_BAR_ICON_MANAGER_TAG = "status_bar_icon_manager";
    public static final int FADE_IN_DURATION = 320;
    public static final int FADE_IN_DELAY = 50;
    private PhoneStatusBarView mStatusBar;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final NetworkController mNetworkController;
    private LinearLayout mSystemIconArea;
    private View mClockView;
    private View mOngoingCallChip;

    private int mClockStyle;

    private View mNotificationIconAreaInner;
    private View mCenteredIconArea;
    private int mDisabled1;
    private int mDisabled2;
    private final StatusBar mStatusBarComponent;
    private DarkIconManager mDarkIconManager;
    private View mOperatorNameFrame;
    private final CommandQueue mCommandQueue;
    private final OngoingCallController mOngoingCallController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final StatusBarLocationPublisher mLocationPublisher;
    private final FeatureFlags mFeatureFlags;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final StatusBarIconController mStatusBarIconController;

    private List<String> mBlockedIcons = new ArrayList<>();

    private LinearLayout mCenterClockLayout;
    private View mRightClock;
    private boolean mShowClock = true;
    private final Handler mHandler = new Handler();

    private class SettingsObserver extends ContentObserver {
       SettingsObserver(Handler handler) {
           super(handler);
       }

       void observe() {
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_STYLE),
                    false, this, UserHandle.USER_ALL);
       }

       @Override
       public void onChange(boolean selfChange) {
           updateSettings(true);
       }
    }

    private SettingsObserver mSettingsObserver;
    private ContentResolver mContentResolver;

    private SignalCallback mSignalCallback = new SignalCallback() {
        @Override
        public void setIsAirplaneMode(NetworkController.IconState icon) {
            mCommandQueue.recomputeDisableFlags(getContext().getDisplayId(), true /* animate */);
        }
    };

    private final OngoingCallListener mOngoingCallListener = new OngoingCallListener() {
        @Override
        public void onOngoingCallStateChanged(boolean animate) {
            disable(getContext().getDisplayId(), mDisabled1, mDisabled2, animate);
        }
    };

    @Inject
    public CollapsedStatusBarFragment(
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            NotificationIconAreaController notificationIconAreaController,
            FeatureFlags featureFlags,
            StatusBarIconController statusBarIconController,
            KeyguardStateController keyguardStateController,
            NetworkController networkController,
            StatusBarStateController statusBarStateController,
            StatusBar statusBarComponent,
            CommandQueue commandQueue
    ) {
        mOngoingCallController = ongoingCallController;
        mAnimationScheduler = animationScheduler;
        mLocationPublisher = locationPublisher;
        mNotificationIconAreaController = notificationIconAreaController;
        mFeatureFlags = featureFlags;
        mStatusBarIconController = statusBarIconController;
        mKeyguardStateController = keyguardStateController;
        mNetworkController = networkController;
        mStatusBarStateController = statusBarStateController;
        mStatusBarComponent = statusBarComponent;
        mCommandQueue = commandQueue;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStatusBar = (PhoneStatusBarView) view;
        View contents = mStatusBar.findViewById(R.id.status_bar_contents);
        contents.addOnLayoutChangeListener(mStatusBarLayoutListener);
        updateStatusBarLocation(contents.getLeft(), contents.getRight());
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.restoreHierarchyState(
                    savedInstanceState.getSparseParcelableArray(EXTRA_PANEL_STATE));
        }
        mDarkIconManager = new DarkIconManager(view.findViewById(R.id.statusIcons), mFeatureFlags);
        mContentResolver = getContext().getContentResolver();
        mSettingsObserver = new SettingsObserver(mHandler);
        mDarkIconManager.setShouldLog(true);
        mBlockedIcons.add(getString(com.android.internal.R.string.status_bar_volume));
        mBlockedIcons.add(getString(com.android.internal.R.string.status_bar_alarm_clock));
        mBlockedIcons.add(getString(com.android.internal.R.string.status_bar_call_strength));
        mDarkIconManager.setBlockList(mBlockedIcons);
        mStatusBarIconController.addIconGroup(mDarkIconManager);
        mSystemIconArea = mStatusBar.findViewById(R.id.system_icon_area);
        mClockView = mStatusBar.findViewById(R.id.clock);
        mOngoingCallChip = mStatusBar.findViewById(R.id.ongoing_call_chip);
        mCenterClockLayout = (LinearLayout) mStatusBar.findViewById(R.id.center_clock_layout);
        mRightClock = mStatusBar.findViewById(R.id.right_clock);
        showSystemIconArea(false);
        initEmergencyCryptkeeperText();
        animateHide(mClockView, false, false);
        initOperatorName();
        initNotificationIconArea();
        mAnimationScheduler.addCallback(this);
        mSettingsObserver.observe();
        updateSettings(false);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SparseArray<Parcelable> states = new SparseArray<>();
        mStatusBar.saveHierarchyState(states);
        outState.putSparseParcelableArray(EXTRA_PANEL_STATE, states);
    }

    @Override
    public void onResume() {
        super.onResume();
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
        initOngoingCallChip();
    }

    @Override
    public void onPause() {
        super.onPause();
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
        mOngoingCallController.removeCallback(mOngoingCallListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mStatusBarIconController.removeIconGroup(mDarkIconManager);
        mAnimationScheduler.removeCallback(this);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            mNetworkController.removeCallback(mSignalCallback);
        }
    }

    /** Initializes views related to the notification icon area. */
    public void initNotificationIconArea() {
        ViewGroup notificationIconArea = mStatusBar.findViewById(R.id.notification_icon_area);
        mNotificationIconAreaInner =
                mNotificationIconAreaController.getNotificationInnerAreaView();
        if (mNotificationIconAreaInner.getParent() != null) {
            ((ViewGroup) mNotificationIconAreaInner.getParent())
                    .removeView(mNotificationIconAreaInner);
        }
        notificationIconArea.addView(mNotificationIconAreaInner);

        ViewGroup statusBarCenteredIconArea = mStatusBar.findViewById(R.id.centered_icon_area);
        mCenteredIconArea = mNotificationIconAreaController.getCenteredNotificationAreaView();
        if (mCenteredIconArea.getParent() != null) {
            ((ViewGroup) mCenteredIconArea.getParent())
                    .removeView(mCenteredIconArea);
        }
        statusBarCenteredIconArea.addView(mCenteredIconArea);

        // #disable should have already been called, so use the disable values to set visibility.
        updateNotificationIconAreaAndCallChip(mDisabled1, false);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        state1 = adjustDisableFlags(state1);
        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        final int old2 = mDisabled2;
        final int diff2 = state2 ^ old2;
        mDisabled1 = state1;
        mDisabled2 = state2;
        if ((diff1 & DISABLE_SYSTEM_INFO) != 0 || ((diff2 & DISABLE2_SYSTEM_ICONS) != 0)) {
            if ((state1 & DISABLE_SYSTEM_INFO) != 0 || ((state2 & DISABLE2_SYSTEM_ICONS) != 0)) {
                hideSystemIconArea(animate);
                hideOperatorName(animate);
            } else {
                showSystemIconArea(animate);
                showOperatorName(animate);
            }
        }

        // The ongoing call chip and notification icon visibilities are intertwined, so update both
        // if either change.
        if (((diff1 & DISABLE_ONGOING_CALL_CHIP) != 0)
                || ((diff1 & DISABLE_NOTIFICATION_ICONS) != 0)) {
            updateNotificationIconAreaAndCallChip(state1, animate);
        }
    }

    protected int adjustDisableFlags(int state) {
        boolean headsUpVisible = mStatusBarComponent.headsUpShouldBeVisible();
        if (headsUpVisible) {
            state |= DISABLE_CLOCK;
        }

        if (!mKeyguardStateController.isLaunchTransitionFadingAway()
                && !mKeyguardStateController.isKeyguardFadingAway()
                && shouldHideNotificationIcons()
                && !(mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        && headsUpVisible)) {
            state |= DISABLE_NOTIFICATION_ICONS;
            state |= DISABLE_SYSTEM_INFO;
            state |= DISABLE_CLOCK;
        }


        if (mNetworkController != null && EncryptionHelper.IS_DATA_ENCRYPTED) {
            if (mNetworkController.hasEmergencyCryptKeeperText()) {
                state |= DISABLE_NOTIFICATION_ICONS;
            }
            if (!mNetworkController.isRadioOn()) {
                state |= DISABLE_SYSTEM_INFO;
            }
        }

        // The shelf will be hidden when dozing with a custom clock, we must show notification
        // icons in this occasion.
        if (mStatusBarStateController.isDozing()
                && mStatusBarComponent.getPanelController().hasCustomClock()) {
            state |= DISABLE_CLOCK | DISABLE_SYSTEM_INFO;
        }

        if (mOngoingCallController.hasOngoingCall()) {
            state &= ~DISABLE_ONGOING_CALL_CHIP;
        } else {
            state |= DISABLE_ONGOING_CALL_CHIP;
        }

        return state;
    }

    /**
     * Updates the visibility of the notification icon area and ongoing call chip based on disabled1
     * state.
     */
    private void updateNotificationIconAreaAndCallChip(int state1, boolean animate) {
        boolean disableNotifications = (state1 & DISABLE_NOTIFICATION_ICONS) != 0;
        boolean hasOngoingCall = (state1 & DISABLE_ONGOING_CALL_CHIP) == 0;

        // Hide notifications if the disable flag is set or we have an ongoing call.
        if (disableNotifications || hasOngoingCall) {
            hideNotificationIconArea(animate);
            animateHide(mClockView, animate, false);
        } else {
            showNotificationIconArea(animate);
            updateClockStyle(animate);
        }

        // Show the ongoing call chip only if there is an ongoing call *and* notification icons
        // are allowed. (The ongoing call chip occupies the same area as the notification icons,
        // so if the icons are disabled then the call chip should be, too.)
        boolean showOngoingCallChip = hasOngoingCall && !disableNotifications;
        if (showOngoingCallChip) {
            showOngoingCallChip(animate);
        } else {
            hideOngoingCallChip(animate);
        }
        mOngoingCallController.notifyChipVisibilityChanged(showOngoingCallChip);
    }

    private boolean shouldHideNotificationIcons() {
        if (!mStatusBar.isClosed() && mStatusBarComponent.hideStatusBarIconsWhenExpanded()) {
            return true;
        }
        if (mStatusBarComponent.hideStatusBarIconsForBouncer()) {
            return true;
        }
        return false;
    }

    private void hideSystemIconArea(boolean animate) {
        animateHide(mCenterClockLayout, animate, true);
        if (mClockStyle == 2) {
            animateHide(mRightClock, animate, true);
        }
        animateHide(mSystemIconArea, animate, true);
    }

    private void showSystemIconArea(boolean animate) {
        // Only show the system icon area if we are not currently animating
        int state = mAnimationScheduler.getAnimationState();
        if (state == IDLE || state == SHOWING_PERSISTENT_DOT) {
            animateShow(mCenterClockLayout, animate);
            if (mClockStyle == 2) {
                animateShow(mRightClock, animate);
            }
            animateShow(mSystemIconArea, animate);
        }
    }


/*    public void hideClock(boolean animate) {
        animateHide(mClockView, animate, false);
    }

    private void showClock(boolean animate) {
        animateShow(mClockView, animate);
    }
*/

    /** Hides the ongoing call chip. */
    public void hideOngoingCallChip(boolean animate) {
        animateHide(mOngoingCallChip, animate, false);
    }

    /** Displays the ongoing call chip. */
    public void showOngoingCallChip(boolean animate) {
        animateShow(mOngoingCallChip, animate);
    }

    /**
     * If panel is expanded/expanding it usually means QS shade is opening, so
     * don't set the clock GONE otherwise it'll mess up the animation.
    private int clockHiddenMode() {
        if (!mStatusBar.isClosed() && !mKeyguardStateController.isShowing()
                && !mStatusBarStateController.isDozing()) {
            return View.INVISIBLE;
        }
        return View.GONE;
    }*/

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate, true);
        animateHide(mCenteredIconArea, animate, true);
	animateHide(mCenterClockLayout, animate, true);
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        animateShow(mCenteredIconArea, animate);
        animateShow(mCenterClockLayout, animate);
    }

    public void hideOperatorName(boolean animate) {
        if (mOperatorNameFrame != null) {
            animateHide(mOperatorNameFrame, animate, true);
        }
    }

    public void showOperatorName(boolean animate) {
        if (mOperatorNameFrame != null) {
            animateShow(mOperatorNameFrame, animate);
        }
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate, final boolean invisible) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(invisible ? View.INVISIBLE : View.GONE);
            return;
        }

        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(() -> v.setVisibility(invisible ? View.INVISIBLE : View.GONE));
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        if (v instanceof Clock && !((Clock)v).isClockVisible()) {
            return;
        }
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(FADE_IN_DELAY)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardStateController.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    private void initEmergencyCryptkeeperText() {
        View emergencyViewStub = mStatusBar.findViewById(R.id.emergency_cryptkeeper_text);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            if (emergencyViewStub != null) {
                ((ViewStub) emergencyViewStub).inflate();
            }
            mNetworkController.addCallback(mSignalCallback);
        } else if (emergencyViewStub != null) {
            ViewGroup parent = (ViewGroup) emergencyViewStub.getParent();
            parent.removeView(emergencyViewStub);
        }
    }

    private void initOperatorName() {
        if (getResources().getBoolean(R.bool.config_showOperatorNameInStatusBar)) {
            ViewStub stub = mStatusBar.findViewById(R.id.operator_name);
            mOperatorNameFrame = stub.inflate();
        }
    }

    private void initOngoingCallChip() {
        mOngoingCallController.addCallback(mOngoingCallListener);
        mOngoingCallController.setChipView(mOngoingCallChip);
    }

    @Override
    public void onStateChanged(int newState) { }

    @Override
    public void onDozingChanged(boolean isDozing) {
        disable(getContext().getDisplayId(), mDisabled1, mDisabled2, false /* animate */);
    }

    @Override
    public void onSystemChromeAnimationStart() {
        if (mAnimationScheduler.getAnimationState() == ANIMATING_OUT
                && !isSystemIconAreaDisabled()) {
            mSystemIconArea.setVisibility(View.VISIBLE);
            mSystemIconArea.setAlpha(0f);
        }
    }

    @Override
    public void onSystemChromeAnimationEnd() {
        // Make sure the system icons are out of the way
        if (mAnimationScheduler.getAnimationState() == ANIMATING_IN) {
            mSystemIconArea.setVisibility(View.INVISIBLE);
            mSystemIconArea.setAlpha(0f);
        } else {
            if (isSystemIconAreaDisabled()) {
                // don't unhide
                return;
            }

            mSystemIconArea.setAlpha(1f);
            mSystemIconArea.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onSystemChromeAnimationUpdate(@NotNull ValueAnimator animator) {
        mSystemIconArea.setAlpha((float) animator.getAnimatedValue());
    }

    private boolean isSystemIconAreaDisabled() {
        return (mDisabled1 & DISABLE_SYSTEM_INFO) != 0 || (mDisabled2 & DISABLE2_SYSTEM_ICONS) != 0;
    }

    private void updateStatusBarLocation(int left, int right) {
        int leftMargin = left - mStatusBar.getLeft();
        int rightMargin = mStatusBar.getRight() - right;

        mLocationPublisher.updateStatusBarMargin(leftMargin, rightMargin);
    }

    // Listen for view end changes of PhoneStatusBarView and publish that to the privacy dot
    private View.OnLayoutChangeListener mStatusBarLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || right != oldRight) {
                    updateStatusBarLocation(left, right);
                }
            };

    public void updateSettings(boolean animate) {
        mShowClock = Settings.System.getIntForUser(mContentResolver,
                Settings.System.STATUSBAR_CLOCK, 1,
                UserHandle.USER_CURRENT) == 1;
        if (!mShowClock) {
            mClockStyle = 1; // internally switch to centered clock layout because
                             // left & right will show up again after QS pulldown
        } else {
            mClockStyle = Settings.System.getIntForUser(mContentResolver,
                    Settings.System.STATUSBAR_CLOCK_STYLE, 0,
                    UserHandle.USER_CURRENT);
        }
        updateClockStyle(animate);
    }

    private void updateClockStyle(boolean animate) {
        if (mClockStyle == 1 || mClockStyle == 2) {
            animateHide(mClockView, animate, false);
        } else {
            if (((Clock)mClockView).isClockVisible()) {
                 animateShow(mClockView, animate);
            }
        }
    }
}
