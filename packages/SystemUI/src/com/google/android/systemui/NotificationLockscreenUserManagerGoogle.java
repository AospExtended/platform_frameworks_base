package com.google.android.systemui;

import android.app.admin.DevicePolicyManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.UserManager;
import android.os.Handler;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.google.android.systemui.smartspace.SmartSpaceController;

import javax.inject.Inject;

@SysUISingleton
public class NotificationLockscreenUserManagerGoogle extends NotificationLockscreenUserManagerImpl {
    @Inject
    public NotificationLockscreenUserManagerGoogle(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            UserManager userManager,
            NotificationClickNotifier clickNotifier,
            KeyguardManager keyguardManager,
            StatusBarStateController statusBarStateController,
            @Main Handler mainHandler,
            DeviceProvisionedController deviceProvisionedController,
            KeyguardStateController keyguardStateController) {
        super(
            context,
            broadcastDispatcher,
            devicePolicyManager,
            userManager,
            clickNotifier,
            keyguardManager,
            statusBarStateController,
            mainHandler,
            deviceProvisionedController,
            keyguardStateController
        );
    }

    @Override
    public void updateLockscreenNotificationSetting() {
        super.updateLockscreenNotificationSetting();
        updateAodVisibilitySettings();
    }

    public void updateAodVisibilitySettings() {
        SmartSpaceController.get(mContext).setHideSensitiveData(
            !userAllowsPrivateNotificationsInPublic(mCurrentUserId));
    }
}
