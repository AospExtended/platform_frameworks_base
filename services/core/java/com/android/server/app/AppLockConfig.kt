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

import android.app.AppLockManager
import android.os.FileUtils
import android.os.FileUtils.S_IRWXU
import android.os.FileUtils.S_IRWXG
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Slog

import java.io.File
import java.io.IOException

import org.json.JSONException
import org.json.JSONObject

/**
 * Container for app lock configuration. Also handles logic of reading
 * and writing configuration to disk, serialized as a JSON file.
 * All operations must be synchronized with an external lock.
 *
 * @hide
 */
internal class AppLockConfig(dataDir: File) {

    private val appLockDir = File(dataDir, APP_LOCK_DIR_NAME)
    private val appLockConfigFile = File(appLockDir, APP_LOCK_CONFIG_FILE)

    private val _appLockPackages = ArraySet<String>()
    val appLockPackages: Set<String>
        get() = _appLockPackages.toSet()

    var appLockTimeout: Long = AppLockManager.DEFAULT_TIMEOUT

    private val _packageNotificationMap = ArrayMap<String, Boolean>()
    val packageNotificationMap: Map<String, Boolean>
        get() = _packageNotificationMap.toMap()

    var biometricsAllowed = AppLockManager.DEFAULT_BIOMETRICS_ALLOWED

    init {
        appLockDir.mkdirs()
        FileUtils.setPermissions(appLockDir, S_IRWXU or S_IRWXG, -1, -1)
    }

    /**
     * Add an application to [appLockPackages].
     *
     * @param packageName the package name of the application.
     * @return true if package was added, false if already exists.
     */
    fun addPackage(packageName: String): Boolean {
        return _appLockPackages.add(packageName)
    }

    /**
     * Remove an application from [appLockPackages].
     *
     * @param packageName the package name of the application.
     * @return true if package was removed, false otherwise.
     */
    fun removePackage(packageName: String): Boolean {
        _packageNotificationMap.remove(packageName)
        return _appLockPackages.remove(packageName)
    }

    /**
     * Set notifications as protected or not for an application
     * in [appLockPackages].
     *
     * @param packageName the package name of the application.
     * @return true if config was changed, false otherwise.
     */
    fun setSecureNotification(packageName: String, secure: Boolean): Boolean {
        if (!_appLockPackages.contains(packageName)) {
            Slog.e(AppLockManagerService.TAG, "Attempt to set secure " +
                "notification field for package that is not in list")
            return false
        }
        if (_packageNotificationMap[packageName] == secure) return false
        _packageNotificationMap[packageName] = secure
        return true
    }

    /**
     * Parse contents from [appLockConfigFile].
     */
    fun read() {
        reset()
        if (!appLockConfigFile.isFile) {
            Slog.i(AppLockManagerService.TAG, "No configuration saved")
            return
        }
        try {
            appLockConfigFile.inputStream().bufferedReader().use {
                val rootObject = JSONObject(it.readText())
                appLockTimeout = rootObject.optLong(KEY_TIMEOUT, AppLockManager.DEFAULT_TIMEOUT)
                biometricsAllowed = rootObject.optBoolean(KEY_BIOMETRICS_ALLOWED,
                    AppLockManager.DEFAULT_BIOMETRICS_ALLOWED)
                val packageObject = rootObject.optJSONObject(KEY_PACKAGES) ?: return@use
                packageObject.keys().forEach { pkg ->
                    _appLockPackages.add(pkg)
                    _packageNotificationMap[pkg] = packageObject.optJSONObject(pkg)
                        ?.optBoolean(KEY_SECURE_NOTIFICATION, false) == true
                }
            }
        } catch(e: IOException) {
            Slog.wtf(AppLockManagerService.TAG, "Failed to read config file", e)
        } catch(e: JSONException) {
            Slog.wtf(AppLockManagerService.TAG, "Failed to parse config file", e)
        }
        AppLockManagerService.logD(
            "readConfig: packages = $appLockPackages",
            "readConfig: packageNotificationMap = $packageNotificationMap",
            "readConfig: timeout = $appLockTimeout",
        )
    }

    private fun reset() {
        _appLockPackages.clear()
        appLockTimeout = AppLockManager.DEFAULT_TIMEOUT
        _packageNotificationMap.clear()
        biometricsAllowed = AppLockManager.DEFAULT_BIOMETRICS_ALLOWED
    }

    /**
     * Write contents to [appLockConfigFile].
     */
    fun write() {
        val rootObject = JSONObject()
        try {
            rootObject.put(KEY_TIMEOUT, appLockTimeout)
            rootObject.put(KEY_BIOMETRICS_ALLOWED, biometricsAllowed)
            val packageObject = JSONObject()
            appLockPackages.forEach {
                val packageConfigObject = JSONObject().apply {
                    put(KEY_SECURE_NOTIFICATION, _packageNotificationMap[it] == true)
                }
                packageObject.put(it, packageConfigObject)
            }
            rootObject.put(KEY_PACKAGES, packageObject)
        } catch(e: JSONException) {
            Slog.wtf(AppLockManagerService.TAG, "Failed to create json configuration", e)
            return
        }
        try {
            appLockConfigFile.outputStream().bufferedWriter().use {
                val flattenedString = rootObject.toString(4)
                it.write(flattenedString, 0, flattenedString.length)
                it.flush()
            }
        } catch(e: IOException) {
            Slog.wtf(AppLockManagerService.TAG, "Failed to write config to file", e)
        }
    }

    companion object {
        private const val APP_LOCK_DIR_NAME = "app_lock"
        private const val APP_LOCK_CONFIG_FILE = "app_lock_config.json"

        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_SECURE_NOTIFICATION = "secure_notification"
        private const val KEY_BIOMETRICS_ALLOWED = "biometrics_allowed"
    }
}