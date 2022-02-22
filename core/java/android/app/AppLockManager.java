/*
 * Copyright (C) 2022 AOSP-Krypton Project
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

package android.app;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.annotation.RequiresPermission;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.RemoteException;

import java.util.List;

@SystemService(Context.APP_LOCK_SERVICE)
public final class AppLockManager {

    public static final long DEFAULT_TIMEOUT = 10 * 1000;
    public static final boolean DEFAULT_BIOMETRICS_ALLOWED = true;

    private final Context mContext;
    private final IAppLockManagerService mService;

    AppLockManager(Context context, IAppLockManagerService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Add an application to be protected. Package should be an user
     * installed application or a system app whitelisted in
     * {@link config_appLockAllowedSystemApps}.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the package name of the app to add.
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void addPackage(@NonNull String packageName) {
        try {
            mService.addPackage(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove an application from the protected packages list.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the package name of the app to remove.
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void removePackage(@NonNull String packageName) {
        try {
            mService.removePackage(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current auto lock timeout.
     *
     * @param userId the user id given by the caller.
     * @return the timeout in milliseconds if configuration for
     *     current user exists, -1 otherwise.
     */
    @UserHandleAware
    public long getTimeout() {
        try {
            return mService.getTimeout(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set auto lock timeout.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param timeout the timeout in milliseconds. Must be >= 5.
     * @param userId the user id given by the caller.
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setTimeout(long timeout) {
        try {
            mService.setTimeout(timeout, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of packages protected with app lock.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @return a list of package name of the protected apps.
     */
    @UserHandleAware
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public List<String> getPackages() {
        try {
            return mService.getPackages(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether notification content should be hidden for a package.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the package name.
     * @param secure true to hide notification content.
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setSecureNotification(@NonNull String packageName, boolean secure) {
        try {
            mService.setSecureNotification(packageName, secure, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of packages whose notifications contents are secure.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @return a list of package names with secure notifications.
     */
    @UserHandleAware
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public List<String> getPackagesWithSecureNotifications() {
        try {
            return mService.getPackagesWithSecureNotifications(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether to allow unlocking with biometrics.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param biometricsAllowed whether to use biometrics.
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setBiometricsAllowed(boolean biometricsAllowed) {
        try {
            mService.setBiometricsAllowed(biometricsAllowed, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether biometrics is allowed for unlocking.
     *
     * @return true if biometrics will be used for unlocking, false otheriwse.
     */
    @UserHandleAware
    public boolean isBiometricsAllowed() {
        try {
            return mService.isBiometricsAllowed(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}