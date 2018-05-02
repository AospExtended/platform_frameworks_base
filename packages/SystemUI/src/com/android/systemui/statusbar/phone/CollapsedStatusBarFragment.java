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

import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import static com.android.systemui.statusbar.phone.StatusBar.reinflateSignalCluster;

import android.annotation.Nullable;
import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.utils.du.UserContentObserver;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.phone.TickerView;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.EncryptionHelper;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    private PhoneStatusBarView mStatusBar;
    private KeyguardMonitor mKeyguardMonitor;
    private NetworkController mNetworkController;
    private LinearLayout mSystemIconArea;
    private View mNotificationIconAreaInner;
    private int mDisabled1;
    private StatusBar mStatusBarComponent;
    private DarkIconManager mDarkIconManager;
    private SignalClusterView mSignalClusterView;

    private View mClock;
    private View mLeftClock;
    private LinearLayout mCenterClockLayout;
    private View mCenterClock;

    // Statusbar Weather Image
    private View mWeatherImageView;
    private View mWeatherTextView;
    private int mShowWeather;

    private int mTickerEnabled;
    private TickerObserver mTickerObserver;
    private ContentResolver mContentResolver;
    private View mTickerViewFromStub;

    // Custom Carrier
    private View mCustomCarrierLabel;
    private int mShowCarrierLabel;

    // AEX Status Logo
    private ImageView mAEXLogo;
    private boolean mShowLogo;
    private View mBatteryBar;

    private SignalCallback mSignalCallback = new SignalCallback() {
        @Override
        public void setIsAirplaneMode(NetworkController.IconState icon) {
            mStatusBarComponent.recomputeDisableFlags(true /* animate */);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContentResolver = getContext().getContentResolver();
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mStatusBarComponent = SysUiServiceProvider.getComponent(getContext(), StatusBar.class);
        mTickerObserver = new TickerObserver(new Handler());
    }

    class TickerObserver extends UserContentObserver {

        TickerObserver(Handler handler) {
            super(handler);
        }

        protected void unobserve() {
            super.unobserve();
            getContext().getContentResolver().unregisterContentObserver(this);
        }

        protected void observe() {
            super.observe();
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_TICKER),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CARRIER),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_STYLE),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK_SECONDS),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_AM_PM_STYLE),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_DISPLAY),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_STYLE),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_FORMAT),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_POSITION),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.STATUS_BAR_BATTERY_STYLE),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SHOW_BATTERY_PERCENT),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        protected void update() {
            mTickerEnabled = Settings.System.getIntForUser(mContentResolver,
                    Settings.System.STATUS_BAR_SHOW_TICKER, 0,
                    UserHandle.USER_CURRENT);
            initTickerView();
            updateSettings(true);
            ((Clock)mClock).updateSettings();
            ((Clock)mLeftClock).updateSettings();
            ((Clock)mCenterClock).updateSettings();
            mStatusBarComponent.updateQsbhClock();

            mStatusBarComponent.updateBatterySettings();
        }
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
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.go(savedInstanceState.getInt(EXTRA_PANEL_STATE));
        }
        mDarkIconManager = new DarkIconManager(view.findViewById(R.id.statusIcons));
        Dependency.get(StatusBarIconController.class).addIconGroup(mDarkIconManager);
        mSystemIconArea = mStatusBar.findViewById(R.id.system_icon_area);
        mBatteryBar = mStatusBar.findViewById(R.id.battery_bar);
        mSignalClusterView = mStatusBar.findViewById(R.id.signal_cluster);
        mClock = mStatusBar.findViewById(R.id.clock);
        mLeftClock = mStatusBar.findViewById(R.id.left_clock);
        mCenterClockLayout = (LinearLayout) mStatusBar.findViewById(R.id.center_clock_layout);
        mCenterClock = mStatusBar.findViewById(R.id.center_clock);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mSignalClusterView);
        mCustomCarrierLabel = mStatusBar.findViewById(R.id.statusbar_carrier_text);
        mAEXLogo = (ImageView) mStatusBar.findViewById(R.id.status_bar_logo);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mAEXLogo);
        mWeatherTextView = mStatusBar.findViewById(R.id.weather_temp);
        mWeatherImageView = mStatusBar.findViewById(R.id.weather_image);
        updateSettings(false);
        // Default to showing until we know otherwise.
        showSystemIconArea(false);
        initEmergencyCryptkeeperText();

        mTickerObserver.observe();
        mTickerObserver.update();

    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_PANEL_STATE, mStatusBar.getState());
    }

    @Override
    public void onResume() {
        super.onResume();
        SysUiServiceProvider.getComponent(getContext(), CommandQueue.class).addCallbacks(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        SysUiServiceProvider.getComponent(getContext(), CommandQueue.class).removeCallbacks(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mSignalClusterView);
        Dependency.get(StatusBarIconController.class).removeIconGroup(mDarkIconManager);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mAEXLogo);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            mNetworkController.removeCallback(mSignalCallback);
        }
        mTickerObserver.unobserve();
    }

    public void initNotificationIconArea(NotificationIconAreaController
            notificationIconAreaController) {
        ViewGroup notificationIconArea = mStatusBar.findViewById(R.id.notification_icon_area);
        mNotificationIconAreaInner =
                notificationIconAreaController.getNotificationInnerAreaView();
        if (mNotificationIconAreaInner.getParent() != null) {
            ((ViewGroup) mNotificationIconAreaInner.getParent())
                    .removeView(mNotificationIconAreaInner);
        }
        notificationIconArea.addView(mNotificationIconAreaInner);
        // Default to showing until we know otherwise.
        showNotificationIconArea(false);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        state1 = adjustDisableFlags(state1);
        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        mDisabled1 = state1;
        if ((diff1 & DISABLE_SYSTEM_INFO) != 0) {
            if ((state1 & DISABLE_SYSTEM_INFO) != 0) {
                hideSystemIconArea(animate);
            } else {
                showSystemIconArea(animate);
            }
        }
        if ((diff1 & DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state1 & DISABLE_NOTIFICATION_ICONS) != 0) {
                hideNotificationIconArea(animate);
                hideCarrierName(animate);
            } else {
                showNotificationIconArea(animate);
                showCarrierName(animate);
            }
        }
    }

    protected int adjustDisableFlags(int state) {
        if (!mStatusBarComponent.isLaunchTransitionFadingAway()
                && !mKeyguardMonitor.isKeyguardFadingAway()
                && shouldHideNotificationIcons()) {
            state |= DISABLE_NOTIFICATION_ICONS;
            state |= DISABLE_SYSTEM_INFO;
        }
        if (mNetworkController != null && EncryptionHelper.IS_DATA_ENCRYPTED) {
            if (mNetworkController.hasEmergencyCryptKeeperText()) {
                state |= DISABLE_NOTIFICATION_ICONS;
            }
            if (!mNetworkController.isRadioOn()) {
                state |= DISABLE_SYSTEM_INFO;
            }
        }
        return state;
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

    public void hideSystemIconArea(boolean animate) {
        animateHide(mBatteryBar, animate, false);
        if (((Clock)mCenterClock).isEnabled()) {
        animateHide(mCenterClockLayout, animate,false);
        }
        animateHide(mSystemIconArea, animate, true);
    }

    public void showSystemIconArea(boolean animate) {
        animateShow(mBatteryBar, animate);
        if (((Clock)mCenterClock).isEnabled()) {
        animateShow(mCenterClockLayout, animate);
        }
        animateShow(mSystemIconArea, animate);
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate, true);
        if (mShowLogo) {
            animateHide(mAEXLogo, animate, true);
        }
        if (((Clock)mCenterClock).isEnabled()) {
        animateHide(mCenterClockLayout, animate,false);
        }
        if (((Clock)mLeftClock).isEnabled()) {
            animateHide(mLeftClock, animate, true);
        }
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        if (mShowLogo) {
            animateShow(mAEXLogo, animate);
        }
        if (((Clock)mCenterClock).isEnabled()) {
        animateShow(mCenterClockLayout, animate);
        }
        if (((Clock)mLeftClock).isEnabled()) {
            animateShow(mLeftClock, animate);
        }
    }

    public void hideCarrierName(boolean animate) {
        if (mCustomCarrierLabel != null) {
            animateHide(mCustomCarrierLabel, animate, false);
        }
    }

    public void showCarrierName(boolean animate) {
        if (mCustomCarrierLabel != null) {
            setCarrierLabel(animate);
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
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(50)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardMonitor.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardMonitor.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardMonitor.getKeyguardFadingAwayDelay())
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

    private void initTickerView() {
        if (mTickerEnabled != 0) {
            View tickerStub = mStatusBar.findViewById(R.id.ticker_stub);
            if (mTickerViewFromStub == null && tickerStub != null) {
                mTickerViewFromStub = ((ViewStub) tickerStub).inflate();
            }
            TickerView tickerView = (TickerView) mStatusBar.findViewById(R.id.tickerText);
            ImageSwitcher tickerIcon = (ImageSwitcher) mStatusBar.findViewById(R.id.tickerIcon);
            mStatusBarComponent.createTicker(
                    mTickerEnabled, getContext(), mStatusBar, tickerView, tickerIcon, mTickerViewFromStub);
        } else {
            mStatusBarComponent.disableTicker();
            }
        }

    public void updateSettings(boolean animate) {
        mShowCarrierLabel = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_CARRIER, 1,
                UserHandle.USER_CURRENT);
        setCarrierLabel(animate);
        mShowLogo = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_LOGO, 0,
                UserHandle.USER_CURRENT) == 1;
        mShowWeather = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT);
        if (mNotificationIconAreaInner != null) {
            if (mShowLogo) {
                if (mNotificationIconAreaInner.getVisibility() == View.VISIBLE) {
                    animateShow(mAEXLogo, animate);
                }
            } else {
                animateHide(mAEXLogo, animate, false);
            }
        }
    }

    private void setCarrierLabel(boolean animate) {
        if (mShowCarrierLabel == 2 || mShowCarrierLabel == 3) {
            animateShow(mCustomCarrierLabel, animate);
        } else {
            animateHide(mCustomCarrierLabel, animate, false);
        }
    }
}
