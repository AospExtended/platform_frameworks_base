package com.google.android.systemui;

import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;

import dagger.Binds;
import dagger.Module;

/**
 * A dagger module for injecting default implementations of components of System UI that may be
 * overridden by the System UI implementation.
 */
@Module
public abstract class SystemUIGoogleModule {

    @Binds
    abstract NotificationLockscreenUserManager bindNotificationLockscreenUserManager(
        NotificationLockscreenUserManagerGoogle notificationLockscreenUserManager);
}
