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

import android.Manifest
import android.annotation.RequiresPermission
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.AlarmManager
import android.app.IActivityTaskManager
import android.app.IAppLockManagerService
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Environment
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserHandle
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import android.util.Slog

import com.android.internal.annotations.GuardedBy
import com.android.internal.R
import com.android.server.app.AppLockManagerServiceInternal.CancelCallback
import com.android.server.app.AppLockManagerServiceInternal.UnlockCallback
import com.android.server.LocalServices
import com.android.server.notification.NotificationManagerInternal
import com.android.server.SystemService
import com.android.server.wm.ActivityTaskManagerInternal

import kotlin.random.Random

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Service to manage per app lock.
 *
 * @hide
 */
class AppLockManagerService(private val context: Context) :
    IAppLockManagerService.Stub() {

    private val alsInternal = LocalService()
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private var currentUserId = UserHandle.USER_NULL
    private var isDeviceSecure = false

    private val mutex = Mutex()

    @GuardedBy("mutex")
    private val userConfigMap = ArrayMap<Int, AppLockConfig>()

    @GuardedBy("mutex")
    private val topPackages = ArraySet<String>()

    @GuardedBy("mutex")
    private val unlockedPackages = ArraySet<String>()

    private lateinit var biometricUnlocker: BiometricUnlocker
    private lateinit var activityTaskManager: IActivityTaskManager
    private lateinit var atmInternal: ActivityTaskManagerInternal
    private lateinit var notificationManagerInternal: NotificationManagerInternal
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var alarmManager: AlarmManager

    private val alarmsMutex = Mutex()

    @GuardedBy("alarmsMutex")
    private val scheduledAlarms = ArrayMap<String, Pair<Int, PendingIntent>>()

    // Using same request code for multiple pending intents will
    // cause in timeout alarms to report wrong package, so we use
    // randomized request codes and keep a set of used ones to
    // prevent collisions. This set is cleared one by one when each
    // alarm is received.
    @GuardedBy("alarmsMutex")
    private var usedAlarmRequestCodes = ArraySet<Int>()

    private val whiteListedSystemApps = mutableListOf<String>()

    // Sometimes onTaskStackChanged is called multiple times
    // during app switches and [unlockInternal] might be called
    // more than once for a locked package in [checkAndUnlockPackage].
    // Cache the queued package name to prevent duplicate prompts.
    @GuardedBy("mutex")
    private var unlockScheduledPackage: String? = null

    private lateinit var packageManager: PackageManager
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_REMOVED) return
            val userId = getSendingUserId()
            if (userId != currentUserId) {
                logD("Ignoring package removal broadcast from user $userId")
                return
            }
            val packageName = intent.data?.schemeSpecificPart ?: run {
                Slog.e(TAG, "Failed to get package name")
                return
            }
            serviceScope.launch {
                val config = mutex.withLock {
                    userConfigMap[userId] ?: run {
                        Slog.e(TAG, "Config unavailable for user $userId")
                        return@launch
                    }
                }
                mutex.withLock {
                    if (!config.appLockPackages.contains(packageName)) {
                        logD("Package $packageName not in the list, ignoring")
                        return@launch
                    }
                }
                logD("Package $packageName uninstalled, cleaning up")
                alarmsMutex.withLock {
                    scheduledAlarms.remove(packageName)?.let {
                        alarmManager.cancel(it.second)
                        usedAlarmRequestCodes.remove(it.first)
                    }
                }
                mutex.withLock {
                    unlockedPackages.remove(packageName)
                    if (config.removePackage(packageName)) {
                        config.write()
                    }
                }
            }
        }
    }

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            logD("onTaskStackChanged")
            serviceScope.launch {
                val currentTopPackages = ArraySet<String>()
                atmInternal.getTopVisibleActivities().forEach {
                    val packageName = atmInternal.getActivityName(
                        it.activityToken)?.packageName ?: return@forEach
                    currentTopPackages.add(packageName)
                }
                logD("topPackages = $topPackages")
                logD("currentTopPackages = $currentTopPackages")
                mutex.withLock {
                    topPackages.filter {
                        !currentTopPackages.contains(it) && unlockedPackages.contains(it)
                    }.forEach {
                        scheduleLockAlarm(it)
                    }
                    topPackages.clear()
                    topPackages.addAll(currentTopPackages)
                }
                alarmsMutex.withLock {
                    currentTopPackages.forEach { pkg ->
                        if (!scheduledAlarms.containsKey(pkg)) return@forEach
                        logD("Cancelling timeout alarm for $pkg")
                        scheduledAlarms.remove(pkg)?.let {
                            alarmManager.cancel(it.second)
                            usedAlarmRequestCodes.remove(it.first)
                        }
                    }
                }
                currentTopPackages.forEach {
                    checkAndUnlockPackage(it)
                }
            }
        }
    }

    private val lockAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            logD("Lock alarm received")
            if (intent?.action != ACTION_APP_LOCK_TIMEOUT) return
            val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            logD("$packageName timed out")
            serviceScope.launch {
                mutex.withLock {
                    if (topPackages.contains(packageName)) {
                        logD("$packageName is currently in foreground, skipping lock")
                        return@withLock
                    }
                    unlockedPackages.remove(packageName)
                    notificationManagerInternal.updateSecureNotifications(
                        packageName, true, ActivityManager.getCurrentUser())
                }
                alarmsMutex.withLock {
                    scheduledAlarms.remove(packageName)?.let {
                        usedAlarmRequestCodes.remove(it.first)
                    }
                }
            }
        }
    }

    private fun checkAndUnlockPackage(pkg: String) {
        if (!isDeviceSecure) return
        serviceScope.launch {
            mutex.withLock {
                if (unlockedPackages.contains(pkg)) return@launch
                val config = userConfigMap[currentUserId] ?: run {
                    Slog.e(TAG, "Config unavailable for user $currentUserId")
                    return@launch
                }
                if (!config.appLockPackages.contains(pkg)) return@launch
                if (unlockScheduledPackage == pkg) {
                    logD("Unlock already scheduled for $pkg, skipping")
                    return@launch
                }
                logD("$pkg is locked out, asking user to unlock")
                unlockScheduledPackage = pkg
            }
            unlockInternal(pkg, currentUserId,
                onSuccess = {
                    serviceScope.launch {
                        mutex.withLock {
                            unlockedPackages.add(pkg)
                            unlockScheduledPackage = null
                        }
                    }
                },
                onCancel = {
                    serviceScope.launch {
                        mutex.withLock {
                            unlockScheduledPackage = null
                        }
                    }
                    // Send user to home on cancel
                    context.mainExecutor.execute {
                        atmInternal.startHomeActivity(currentUserId,
                            "unlockInternal#onCancel")
                    }
                }
            )
        }
    }

    private fun scheduleLockAlarm(pkg: String) {
        logD("scheduleLockAlarm, package = $pkg")
        serviceScope.launch {
            alarmsMutex.withLock {
                if (scheduledAlarms.containsKey(pkg)) {
                    logD("Alarm already scheduled for package $pkg")
                    return@launch
                }
            }
            val timeout = mutex.withLock {
                userConfigMap[currentUserId]?.appLockTimeout
            } ?: run {
                Slog.e(TAG, "Failed to retrieve user config for $currentUserId")
                return@launch
            }
            val requestCode = alarmsMutex.withLock {
                getRequestCodeLocked()
            }
            logD("requestCode = $requestCode, usedCodes = $usedAlarmRequestCodes")
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(ACTION_APP_LOCK_TIMEOUT).apply {
                    putExtra(EXTRA_PACKAGE, pkg)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeout,
                pendingIntent
            )
            alarmsMutex.withLock {
                scheduledAlarms[pkg] = Pair(requestCode, pendingIntent)
            }
        }
    }

    private fun getRequestCodeLocked(): Int {
        var code = 0
        while (usedAlarmRequestCodes.contains(code)) {
            code = Random.nextInt(0, 1000)
        }
        usedAlarmRequestCodes.add(code)
        return code
    }

    private fun getActualUserId(userId: Int, tag: String): Int {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
            Binder.getCallingUid(), userId, false /* allowAll */,
            true /* requireFull */, tag, AppLockManagerService::class.qualifiedName)
    }

    private fun unlockInternal(
        pkg: String,
        userId: Int,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val ident = Binder.clearCallingIdentity()
        try {
            if (!biometricUnlocker.canUnlock()) {
                Slog.e(TAG, "Application cannot be unlocked with biometrics or device credentials")
                return
            }
            biometricUnlocker.unlock(getLabelForPackage(pkg, userId), onSuccess, onCancel)
        } finally {
            Binder.restoreCallingIdentity(ident)
        }
    }

    private fun getLabelForPackage(pkg: String, userId: Int): String? {
        return try {
            val aInfo = packageManager.getApplicationInfoAsUser(pkg,
                PackageManager.MATCH_ALL, userId)
            aInfo.loadLabel(packageManager).toString()
        } catch(e: PackageManager.NameNotFoundException) {
            Slog.e(TAG, "Package $pkg not found")
            null
        }
    }

    /**
     * Add an application to be protected.
     *
     * @param packageName the package name of the app to add.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     * @throws [IllegalArgumentException] if package is a system app that
     *     is not whitelisted in [R.array.config_appLockAllowedSystemApps],
     *     or if package is not installed.
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun addPackage(packageName: String, userId: Int) {
        logD("addPackage: packageName = $packageName, userId = $userId")
        enforceCallingPermission("addPackage")
        checkPackage(packageName, userId)
        val actualUserId = getActualUserId(userId, "addPackage")
        serviceScope.launch {
            mutex.withLock {
                userConfigMap[actualUserId]?.let {
                    if (it.addPackage(packageName)) {
                        // Collapse any active notifications or bubbles for the app.
                        if (!unlockedPackages.contains(packageName) &&
                                !topPackages.contains(packageName)) {
                            notificationManagerInternal.updateSecureNotifications(
                                packageName, true, actualUserId)
                        }
                        withContext(Dispatchers.IO) {
                            it.write()
                        }
                    }
                } ?: Slog.e(TAG, "addPackage requested by unknown user id $actualUserId")
            }
        }
    }

    private fun checkPackage(pkg: String, userId: Int) {
        try {
            val aInfo = packageManager.getApplicationInfoAsUser(
                pkg, PackageManager.MATCH_ALL, userId)
            if (!aInfo.isSystemApp()) return
            if (!whiteListedSystemApps.contains(pkg))
                throw IllegalArgumentException("System package $pkg is not whitelisted")
        } catch(e: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Package $pkg is not installed")
        }
    }

    /**
     * Remove an application from the protected packages list.
     *
     * @param packageName the package name of the app to remove.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun removePackage(packageName: String, userId: Int) {
        logD("removePackage: packageName = $packageName, userId = $userId")
        enforceCallingPermission("removePackage")
        val actualUserId = getActualUserId(userId, "removePackage")
        serviceScope.launch {
            mutex.withLock {
                userConfigMap[actualUserId]?.let {
                    if (it.removePackage(packageName)) {
                        // Let active notifications be expanded since the app
                        // is no longer protected.
                        notificationManagerInternal.updateSecureNotifications(
                            packageName, false, actualUserId)
                        withContext(Dispatchers.IO) {
                            it.write()
                        }
                    }
                } ?: Slog.e(TAG, "removePackage requested by unknown user id $actualUserId")
            }
        }
    }

    /**
     * Get the current auto lock timeout.
     *
     * @param userId the user id of the caller.
     * @return the timeout in milliseconds if configuration for
     *     current user exists, -1 otherwise.
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun getTimeout(userId: Int): Long {
        logD("getTimeout: userId = $userId")
        val actualUserId = getActualUserId(userId, "getTimeout")
        return runBlocking {
            mutex.withLock {
                userConfigMap[actualUserId]?.let { it.appLockTimeout } ?: run {
                    Slog.e(TAG, "getTimeout requested by unknown user id $actualUserId")
                    -1L
                }
            }
        }
    }

    /**
     * Set auto lock timeout.
     *
     * @param timeout the timeout in milliseconds. Must be >= 5.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun setTimeout(timeout: Long, userId: Int) {
        logD("setTimeout: timeout = $timeout, userId = $userId")
        if (timeout < 5L) {
            throw IllegalArgumentException("Timeout must be greater than or equal to 5")
        }
        enforceCallingPermission("setTimeout")
        val actualUserId = getActualUserId(userId, "setTimeout")
        serviceScope.launch {
            mutex.withLock {
                userConfigMap[actualUserId]?.let {
                    if (it.appLockTimeout != timeout) {
                        it.appLockTimeout = timeout
                        it.write()
                    }
                } ?: Slog.e(TAG, "setTimeout requested by unknown user id $actualUserId")
            }
        }
    }

    /**
     * Get the list of packages protected with app lock.
     *
     * @param userId the user id of the caller.
     * @return list of package name of the protected apps.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun getPackages(userId: Int): List<String> {
        logD("getPackages: userId = $userId")
        enforceCallingPermission("getPackages")
        val actualUserId = getActualUserId(userId, "getPackages")
        return runBlocking {
            mutex.withLock {
                userConfigMap[actualUserId]?.appLockPackages?.toList() ?: run {
                    Slog.e(TAG, "getPackages requested by unknown user id $actualUserId")
                    emptyList()
                }
            }
        }
    }

    /**
     * Set whether notification content should be hidden for a package.
     *
     * @param packageName the package name.
     * @param secure true to hide notification content.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    override fun setSecureNotification(
        packageName: String,
        secure: Boolean,
        userId: Int,
    ) {
        logD("setSecureNotification: packageName = $packageName, userId = $userId")
        enforceCallingPermission("setSecureNotification")
        val actualUserId = getActualUserId(userId, "setSecureNotification")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "setSecureNotification requested by unknown " +
                        "user id $actualUserId")
                    return@withLock
                }
                if (config.setSecureNotification(packageName, secure)) {
                    notificationManagerInternal.updateSecureNotifications(
                        packageName, secure, actualUserId)
                    withContext(Dispatchers.IO) {
                        config.write()
                    }
                }
            }
        }
    }

    /**
     * Get the list of packages whose notifications contents are secure.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param userId the user id of the caller.
     * @return a list of package names with secure notifications.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    override fun getPackagesWithSecureNotifications(userId: Int): List<String> {
        logD("getPackagesWithSecureNotifications: userId = $userId")
        enforceCallingPermission("getPackagesWithSecureNotifications")
        val actualUserId = getActualUserId(userId, "getPackagesWithSecureNotifications")
        return runBlocking {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "getPackagesWithSecureNotifications requested by " +
                        "unknown user id $actualUserId")
                    return@withLock emptyList()
                }
                config.packageNotificationMap.entries.filter {
                    it.value
                }.map {
                    it.key
                }.toList()
            }
        }
    }

    private fun enforceCallingPermission(msg: String) {
        context.enforceCallingPermission(Manifest.permission.MANAGE_APP_LOCK, msg)
    }

    private fun onStart() {
        LocalServices.addService(AppLockManagerServiceInternal::class.java, alsInternal)
    }

    private fun onBootCompleted() {
        Slog.i(TAG, "onBootCompleted")
        whiteListedSystemApps.addAll(context.resources
            .getStringArray(R.array.config_appLockAllowedSystemApps).toList())

        alarmManager = context.getSystemService(AlarmManager::class.java)
        context.registerReceiverAsUser(
            lockAlarmReceiver,
            UserHandle.SYSTEM,
            IntentFilter(ACTION_APP_LOCK_TIMEOUT),
            null /* broadcastPermission */,
            null /* scheduler */,
        )

        packageManager = context.packageManager
        context.registerReceiverForAllUsers(
            packageChangeReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_REMOVED),
            null /* broadcastPermission */,
            null /* scheduler */,
        )

        biometricUnlocker = BiometricUnlocker(context)

        keyguardManager = context.getSystemService(KeyguardManager::class.java)

        notificationManagerInternal = LocalServices.getService(NotificationManagerInternal::class.java)

        activityTaskManager = ActivityTaskManager.getService()
        atmInternal = LocalServices.getService(ActivityTaskManagerInternal::class.java)
        activityTaskManager.registerTaskStackListener(taskStackListener)
    }

    private fun onUserStarting(userId: Int) {
        Slog.i(TAG, "onUserStarting: userId = $userId")
        currentUserId = userId
        isDeviceSecure = keyguardManager.isDeviceSecure(userId)
        logD("isDeviceSecure = $isDeviceSecure")
        serviceScope.launch {
            mutex.withLock {
                if (!userConfigMap.containsKey(userId)) {
                    val config = AppLockConfig(Environment.getDataSystemDeDirectory(userId))
                    userConfigMap[userId] = config
                    withContext(Dispatchers.IO) {
                        config.read()
                    }
                }
            }
        }
    }

    private fun onUserStopping(userId: Int): Job {
        Slog.i(TAG, "onUserStopping: userId = $userId")
        return serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[userId] ?: return@withLock
                userConfigMap.remove(userId)
                unlockedPackages.clear()
                withContext(Dispatchers.IO) {
                    config.write()
                }
            }
        }
    }

    private fun onUserSwitching(oldUserId: Int, newUserId: Int) {
        Slog.i(TAG, "onUserSwitching: oldUserId = $oldUserId, newUserId = $newUserId")
        serviceScope.launch {
            if (oldUserId != UserHandle.USER_NULL) {
                onUserStopping(oldUserId).join()
            }
            onUserStarting(newUserId)
        }
    }

    private inner class LocalService : AppLockManagerServiceInternal() {
        override fun requireUnlock(packageName: String, userId: Int): Boolean {
            return requireUnlockInternal(packageName, userId, false)
        }

        private fun requireUnlockInternal(
            packageName: String,
            userId: Int,
            ignoreLockState: Boolean,
        ): Boolean {
            if (userId < 0) {
                Slog.w(TAG, "Ignoring requireUnlock call for special user $userId")
                return false
            }
            if (!isDeviceSecure) {
                logD("Device is not secure, app does not require unlock")
                return false
            }
            // If device is locked then there is no point in proceeding.
            if (!ignoreLockState && keyguardManager.isDeviceLocked()) {
                logD("Device is locked, app does not require unlock")
                return false
            }
            logD("requireUnlock: packageName = $packageName")
            val actualUserId = getActualUserId(userId, "requireUnlock")
            return runBlocking {
                mutex.withLock {
                    val config = userConfigMap[actualUserId] ?: run {
                        Slog.e(TAG, "requireUnlock queried by unknown user id $actualUserId")
                        return@withLock false
                    }
                    if (config.appLockPackages.contains(packageName)) {
                        val requireUnlock = !unlockedPackages.contains(packageName)
                        logD("requireUnlock = $requireUnlock")
                        requireUnlock
                    } else {
                        false
                    }
                }
            }
        }

        override fun unlock(
            packageName: String,
            unlockCallback: UnlockCallback?,
            cancelCallback: CancelCallback?,
            userId: Int
        ) {
            if (userId < 0) {
                Slog.w(TAG, "Ignoring unlock call for special user $userId")
                return
            }
            if (!isDeviceSecure) {
                Slog.w(TAG, "Device is not secure, should not be " +
                    "calling unlock()")
                return
            }
            logD("unlock: packageName = $packageName")
            val actualUserId = getActualUserId(userId, "unlock")
            serviceScope.launch {
                mutex.withLock {
                    val config = userConfigMap[actualUserId] ?: run {
                        Slog.e(TAG, "Unlock requested by unknown user id " +
                            "$actualUserId")
                        return@withLock
                    }
                    if (!config.appLockPackages.contains(packageName)) {
                        Slog.w(TAG, "Unlock requested for package $packageName " +
                            "that is not in list")
                        return@withLock
                    }
                    unlockInternal(packageName, actualUserId,
                        onSuccess = {
                            logD("Unlock successfull")
                            serviceScope.launch {
                                mutex.withLock {
                                    unlockedPackages.add(packageName)
                                }
                                unlockCallback?.onUnlocked(packageName)
                                notificationManagerInternal.updateSecureNotifications(
                                    packageName, false, actualUserId)
                            }
                        },
                        onCancel = {
                            logD("Unlock cancelled")
                            serviceScope.launch {
                                cancelCallback?.onCancelled(packageName)
                            }
                        }
                    )
                }
            }
        }

        override fun reportPasswordChanged(userId: Int) {
            if (userId < 0) {
                Slog.w(TAG, "Ignoring reportPasswordChanged call for special user $userId")
                return
            }
            logD("reportPasswordChanged: userId = $userId")
            if (userId != currentUserId) {
                logD("Ignoring password change event for user $userId")
                return
            }
            isDeviceSecure = keyguardManager.isDeviceSecure(userId)
            logD("isDeviceSecure = $isDeviceSecure")
        }

        override fun isNotificationSecured(
            packageName: String,
            userId: Int,
        ): Boolean {
            if (userId < 0) {
                Slog.w(TAG, "Ignoring isNotificationSecured call for special user $userId")
                return false
            }
            logD("isNotificationSecured: " +
                    "packageName = $packageName, " +
                    "userId = $userId")
            val actualUserId = getActualUserId(userId, "isNotificationSecured")
            if (!requireUnlockInternal(packageName, userId, true /* ignoreLockState */)) return false
            return runBlocking {
                mutex.withLock {
                    val config = userConfigMap[actualUserId] ?: run {
                        Slog.e(TAG, "isNotificationSecured queried by " +
                            "unknown user id $actualUserId")
                        return@withLock false
                    }
                    val secure = config.packageNotificationMap[packageName] == true
                    logD("Secure = $secure")
                    return@withLock secure
                }
            }
        }
    }

    class Lifecycle(context: Context): SystemService(context) {
        private lateinit var service: AppLockManagerService

        override fun onStart() {
            service = AppLockManagerService(context)
            publishBinderService(Context.APP_LOCK_SERVICE, service)
            service.onStart()
        }

        override fun onBootPhase(phase: Int) {
            if (phase == PHASE_ACTIVITY_MANAGER_READY) {
                service.onBootCompleted()
            }
        }

        override fun onUserStarting(user: TargetUser) {
            service.onUserStarting(user.userIdentifier)
        }

        override fun onUserStopping(user: TargetUser) {
            service.onUserStopping(user.userIdentifier)
        }

        override fun onUserSwitching(from: TargetUser?, to: TargetUser) {
            service.onUserSwitching(
                from?.userIdentifier ?: UserHandle.USER_NULL,
                to.userIdentifier
            )
        }
    }

    companion object {
        internal const val TAG = "AppLockManagerService"
        internal val DEBUG: Boolean
            get() = Log.isLoggable(TAG, Log.DEBUG)

        private const val ACTION_APP_LOCK_TIMEOUT = "com.android.server.app.AppLockManagerService.APP_LOCK_TIMEOUT"
        private const val EXTRA_PACKAGE = "com.android.server.app.AppLockManagerService.PACKAGE"

        internal fun logD(msg: String) {
            if (DEBUG) Slog.d(TAG, msg)
        }
    }
}