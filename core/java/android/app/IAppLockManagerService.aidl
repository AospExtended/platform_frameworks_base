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

/**
 * Interface for managing app lock.
 * @hide
 */
interface IAppLockManagerService {

    void addPackage(in String packageName, in int userId);

    void removePackage(in String packageName, in int userId);

    long getTimeout(in int userId);

    void setTimeout(in long timeout, in int userId);

    List<String> getPackages(in int userId);

    void setSecureNotification(in String packageName, in boolean secure, in int userId);

    List<String> getPackagesWithSecureNotifications(in int userId);
}