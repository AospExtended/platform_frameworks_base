/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.navigationbar;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.car.hvac.HvacController;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.phone.StatusBarIconController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CarNavigationBarControllerTest extends SysuiTestCase {

    private static final String TOP_NOTIFICATION_PANEL =
            "com.android.systemui.car.notification.TopNotificationPanelViewMediator";
    private static final String BOTTOM_NOTIFICATION_PANEL =
            "com.android.systemui.car.notification.BottomNotificationPanelViewMediator";
    private CarNavigationBarController mCarNavigationBar;
    private NavigationBarViewFactory mNavigationBarViewFactory;
    private TestableResources mTestableResources;

    @Mock
    private ButtonSelectionStateController mButtonSelectionStateController;
    @Mock
    private ButtonRoleHolderController mButtonRoleHolderController;
    @Mock
    private HvacController mHvacController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mNavigationBarViewFactory = new NavigationBarViewFactory(mContext);
        mTestableResources = mContext.getOrCreateTestableResources();

        // Needed to inflate top navigation bar.
        mDependency.injectMockDependency(DarkIconDispatcher.class);
        mDependency.injectMockDependency(StatusBarIconController.class);
    }

    private CarNavigationBarController createNavigationBarController() {
        return new CarNavigationBarController(mContext, mNavigationBarViewFactory,
                mButtonSelectionStateController, () -> mHvacController,
                mButtonRoleHolderController,
                new SystemBarConfigs(mTestableResources.getResources()));
    }

    @Test
    public void testConnectToHvac_callsConnect() {
        mCarNavigationBar = createNavigationBarController();

        mCarNavigationBar.connectToHvac();

        verify(mHvacController).connectToCarService();
    }

    @Test
    public void testRemoveAll_callsHvacControllerRemoveAllComponents() {
        mCarNavigationBar = createNavigationBarController();

        mCarNavigationBar.removeAll();

        verify(mHvacController).removeAllComponents();
    }


    @Test
    public void testRemoveAll_callsButtonRoleHolderControllerRemoveAll() {
        mCarNavigationBar = createNavigationBarController();

        mCarNavigationBar.removeAll();

        verify(mButtonRoleHolderController).removeAll();
    }

    @Test
    public void testRemoveAll_callsButtonSelectionStateControllerRemoveAll() {
        mCarNavigationBar = createNavigationBarController();

        mCarNavigationBar.removeAll();

        verify(mButtonSelectionStateController).removeAll();
    }

    @Test
    public void testGetTopWindow_topDisabled_returnsNull() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, false);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        // If Top Notification Panel is used but top navigation bar is not enabled, SystemUI is
        // expected to crash.
        mTestableResources.addOverride(R.string.config_notificationPanelViewMediator,
                BOTTOM_NOTIFICATION_PANEL);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getTopWindow();

        assertThat(window).isNull();
    }

    @Test
    public void testGetTopWindow_topEnabled_returnsWindow() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getTopWindow();

        assertThat(window).isNotNull();
    }

    @Test
    public void testGetTopWindow_topEnabled_calledTwice_returnsSameWindow() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window1 = mCarNavigationBar.getTopWindow();
        ViewGroup window2 = mCarNavigationBar.getTopWindow();

        assertThat(window1).isEqualTo(window2);
    }

    @Test
    public void testGetBottomWindow_bottomDisabled_returnsNull() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, false);
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        // If Bottom Notification Panel is used but bottom navigation bar is not enabled,
        // SystemUI is expected to crash.
        mTestableResources.addOverride(R.string.config_notificationPanelViewMediator,
                TOP_NOTIFICATION_PANEL);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getBottomWindow();

        assertThat(window).isNull();
    }

    @Test
    public void testGetBottomWindow_bottomEnabled_returnsWindow() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getBottomWindow();

        assertThat(window).isNotNull();
    }

    @Test
    public void testGetBottomWindow_bottomEnabled_calledTwice_returnsSameWindow() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window1 = mCarNavigationBar.getBottomWindow();
        ViewGroup window2 = mCarNavigationBar.getBottomWindow();

        assertThat(window1).isEqualTo(window2);
    }

    @Test
    public void testGetLeftWindow_leftDisabled_returnsNull() {
        mTestableResources.addOverride(R.bool.config_enableLeftNavigationBar, false);
        mCarNavigationBar = createNavigationBarController();
        ViewGroup window = mCarNavigationBar.getLeftWindow();
        assertThat(window).isNull();
    }

    @Test
    public void testGetLeftWindow_leftEnabled_returnsWindow() {
        mTestableResources.addOverride(R.bool.config_enableLeftNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getLeftWindow();

        assertThat(window).isNotNull();
    }

    @Test
    public void testGetLeftWindow_leftEnabled_calledTwice_returnsSameWindow() {
        mTestableResources.addOverride(R.bool.config_enableLeftNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window1 = mCarNavigationBar.getLeftWindow();
        ViewGroup window2 = mCarNavigationBar.getLeftWindow();

        assertThat(window1).isEqualTo(window2);
    }

    @Test
    public void testGetRightWindow_rightDisabled_returnsNull() {
        mTestableResources.addOverride(R.bool.config_enableRightNavigationBar, false);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getRightWindow();

        assertThat(window).isNull();
    }

    @Test
    public void testGetRightWindow_rightEnabled_returnsWindow() {
        mTestableResources.addOverride(R.bool.config_enableRightNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getRightWindow();

        assertThat(window).isNotNull();
    }

    @Test
    public void testGetRightWindow_rightEnabled_calledTwice_returnsSameWindow() {
        mTestableResources.addOverride(R.bool.config_enableRightNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window1 = mCarNavigationBar.getRightWindow();
        ViewGroup window2 = mCarNavigationBar.getRightWindow();

        assertThat(window1).isEqualTo(window2);
    }

    @Test
    public void testSetTopWindowVisibility_setTrue_isVisible() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getTopWindow();
        mCarNavigationBar.setTopWindowVisibility(View.VISIBLE);

        assertThat(window.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testSetTopWindowVisibility_setFalse_isGone() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getTopWindow();
        mCarNavigationBar.setTopWindowVisibility(View.GONE);

        assertThat(window.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void testSetBottomWindowVisibility_setTrue_isVisible() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getBottomWindow();
        mCarNavigationBar.setBottomWindowVisibility(View.VISIBLE);

        assertThat(window.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testSetBottomWindowVisibility_setFalse_isGone() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getBottomWindow();
        mCarNavigationBar.setBottomWindowVisibility(View.GONE);

        assertThat(window.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void testSetLeftWindowVisibility_setTrue_isVisible() {
        mTestableResources.addOverride(R.bool.config_enableLeftNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getLeftWindow();
        mCarNavigationBar.setLeftWindowVisibility(View.VISIBLE);

        assertThat(window.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testSetLeftWindowVisibility_setFalse_isGone() {
        mTestableResources.addOverride(R.bool.config_enableLeftNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getLeftWindow();
        mCarNavigationBar.setLeftWindowVisibility(View.GONE);

        assertThat(window.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void testSetRightWindowVisibility_setTrue_isVisible() {
        mTestableResources.addOverride(R.bool.config_enableRightNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getRightWindow();
        mCarNavigationBar.setRightWindowVisibility(View.VISIBLE);

        assertThat(window.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testSetRightWindowVisibility_setFalse_isGone() {
        mTestableResources.addOverride(R.bool.config_enableRightNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        ViewGroup window = mCarNavigationBar.getRightWindow();
        mCarNavigationBar.setRightWindowVisibility(View.GONE);

        assertThat(window.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void testRegisterBottomBarTouchListener_createViewFirst_registrationSuccessful() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        View.OnTouchListener controller = bottomBar.getStatusBarWindowTouchListener();
        assertThat(controller).isNull();
        mCarNavigationBar.registerBottomBarTouchListener(mock(View.OnTouchListener.class));
        controller = bottomBar.getStatusBarWindowTouchListener();

        assertThat(controller).isNotNull();
    }

    @Test
    public void testRegisterBottomBarTouchListener_registerFirst_registrationSuccessful() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        mCarNavigationBar.registerBottomBarTouchListener(mock(View.OnTouchListener.class));
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        View.OnTouchListener controller = bottomBar.getStatusBarWindowTouchListener();

        assertThat(controller).isNotNull();
    }

    @Test
    public void testRegisterNotificationController_createViewFirst_registrationSuccessful() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        CarNavigationBarController.NotificationsShadeController controller =
                bottomBar.getNotificationsPanelController();
        assertThat(controller).isNull();
        mCarNavigationBar.registerNotificationController(
                mock(CarNavigationBarController.NotificationsShadeController.class));
        controller = bottomBar.getNotificationsPanelController();

        assertThat(controller).isNotNull();
    }

    @Test
    public void testRegisterNotificationController_registerFirst_registrationSuccessful() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();

        mCarNavigationBar.registerNotificationController(
                mock(CarNavigationBarController.NotificationsShadeController.class));
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        CarNavigationBarController.NotificationsShadeController controller =
                bottomBar.getNotificationsPanelController();

        assertThat(controller).isNotNull();
    }

    @Test
    public void testShowAllKeyguardButtons_bottomEnabled_bottomKeyguardButtonsVisible() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        View bottomKeyguardButtons = bottomBar.findViewById(R.id.lock_screen_nav_buttons);

        mCarNavigationBar.showAllKeyguardButtons(/* isSetUp= */ true);

        assertThat(bottomKeyguardButtons.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testShowAllKeyguardButtons_bottomEnabled_bottomNavButtonsGone() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        View bottomButtons = bottomBar.findViewById(R.id.nav_buttons);

        mCarNavigationBar.showAllKeyguardButtons(/* isSetUp= */ true);

        assertThat(bottomButtons.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void testHideAllKeyguardButtons_bottomEnabled_bottomKeyguardButtonsGone() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        View bottomKeyguardButtons = bottomBar.findViewById(R.id.lock_screen_nav_buttons);

        mCarNavigationBar.showAllKeyguardButtons(/* isSetUp= */ true);
        assertThat(bottomKeyguardButtons.getVisibility()).isEqualTo(View.VISIBLE);
        mCarNavigationBar.hideAllKeyguardButtons(/* isSetUp= */ true);

        assertThat(bottomKeyguardButtons.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void testHideAllKeyguardButtons_bottomEnabled_bottomNavButtonsVisible() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        View bottomButtons = bottomBar.findViewById(R.id.nav_buttons);

        mCarNavigationBar.showAllKeyguardButtons(/* isSetUp= */ true);
        assertThat(bottomButtons.getVisibility()).isEqualTo(View.GONE);
        mCarNavigationBar.hideAllKeyguardButtons(/* isSetUp= */ true);

        assertThat(bottomButtons.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testToggleAllNotificationsUnseenIndicator_bottomEnabled_hasUnseen_setCorrectly() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        CarNavigationButton notifications = bottomBar.findViewById(R.id.notifications);

        boolean hasUnseen = true;
        mCarNavigationBar.toggleAllNotificationsUnseenIndicator(/* isSetUp= */ true,
                hasUnseen);

        assertThat(notifications.getUnseen()).isTrue();
    }

    @Test
    public void testToggleAllNotificationsUnseenIndicator_bottomEnabled_noUnseen_setCorrectly() {
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        mCarNavigationBar = createNavigationBarController();
        CarNavigationBarView bottomBar = mCarNavigationBar.getBottomBar(/* isSetUp= */ true);
        CarNavigationButton notifications = bottomBar.findViewById(R.id.notifications);

        boolean hasUnseen = false;
        mCarNavigationBar.toggleAllNotificationsUnseenIndicator(/* isSetUp= */ true,
                hasUnseen);

        assertThat(notifications.getUnseen()).isFalse();
    }
}
