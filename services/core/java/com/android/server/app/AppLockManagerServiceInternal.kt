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

package com.android.server.app

/**
 * Internal class for system server to manage app lock.
 *
 * @hide
 */
abstract class AppLockManagerServiceInternal {

    /**
     * Whether user has to unlock this application in order to
     * open it.
     *
     * @param packageName the package name of the app to check.
     * @param userId the user id given by the caller.
     * @return true if user has to unlock, false otherwise.
     */
    abstract fun requireUnlock(packageName: String, userId: Int): Boolean

    /**
     * Unlock the application.
     *
     * @param packageName the package name of the app to unlock.
     * @param unlockCallback the callback to listen for when user unlocks.
     * @param cancelCallback the callback to listen for when user cancels unlock.
     * @param userId the user id given by the caller.
     */
    abstract fun unlock(
        packageName: String,
        unlockCallback: UnlockCallback?,
        cancelCallback: CancelCallback?,
        userId: Int,
    )

    /**
     * Report that password for user has changed.
     *
     * @param userId the user for which password has changed.
     */
    abstract fun reportPasswordChanged(userId: Int)

    /**
     * Check whether notification content should be hidden for a package.
     *
     * @param packageName the package to check for.
     * @param userId the user id given by the caller.
     * @return true if notification should be hidden, false otherwise.
     */
    abstract fun isNotificationSecured(packageName: String, userId: Int): Boolean

    @FunctionalInterface
    interface UnlockCallback {
        /**
         * Callback fired when user successfully unlocks the security prompt.
         *
         * @param packageName the name of the package that was unlocked.
         */
        fun onUnlocked(packageName: String)
    }

    @FunctionalInterface
    interface CancelCallback {
        /**
         * Callback fired when user cancells security prompt.
         *
         * @param packageName the name of the package for which the
         *    unlock was cancelled.
         */
        fun onCancelled(packageName: String)
    }
}