package com.android.server.am;

import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.ArraySet;

import java.util.Set;

import me.piebridge.PreventRunning;

public class PreventRunningUtils {

    private static ActivityManagerService mAm;

    private static PreventRunning mPreventRunning = new PreventRunning();

    private PreventRunningUtils() {
    }

    public static void init(ActivityManagerService am) {
        mAm = am;
    }

    public static void onUserLeavingActivity(ActivityRecord ar, boolean userLeaving) {
        if (userLeaving) {
            mPreventRunning.onUserLeavingActivity(ar);
        }
    }

    public static void onResumeActivity(ActivityRecord ar) {
        mPreventRunning.onResumeActivity(ar);
    }

    public static void onDestroyActivity(ActivityRecord ar) {
        mPreventRunning.onDestroyActivity(ar);
    }

    public static void onLaunchActivity(ActivityRecord ar) {
        mPreventRunning.onLaunchActivity(ar);
    }

    private static void setSender(IApplicationThread caller) {
        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        mPreventRunning.setSender(callerApp != null ? callerApp.info.packageName : String.valueOf(Binder.getCallingUid()));
    }

    public static void setSenderInStartService(IApplicationThread caller) {
        setSender(caller);
    }

    public static ComponentName clearSenderInStartService(ComponentName res) {
        mPreventRunning.setSender(null);
        if (res != null && res.getPackageName().startsWith("!")) {
            return null;
        }
        return res;
    }

    public static void setSenderInBindService(IApplicationThread caller) {
        setSender(caller);
    }

    public static int clearSenderInBindService(int res) {
        mPreventRunning.setSender(null);
        return res;
    }

    public static void setSenderInBroadcastIntent(IApplicationThread caller) {
        setSender(caller);
    }

    public static int onBroadcastIntent(int res, Intent intent) {
        if (res >= 0) {
            mPreventRunning.onBroadcastIntent(intent);
        }
        mPreventRunning.setSender(null);
        return res;
    }

    public static void onAppDied(ProcessRecord app) {
        mPreventRunning.onAppDied(app);
    }

    public static void onCleanUpRemovedTask(ComponentName component) {
        mPreventRunning.onCleanUpRemovedTask(component.getPackageName());
    }

    public static boolean onMoveActivityTaskToBack(boolean res, IBinder token) {
        if (res) {
            ActivityRecord ar = forToken(token);
            mPreventRunning.onMoveActivityTaskToBack(ar != null ? ar.packageName : null);
        }
        return res;
    }

    private static ActivityRecord forToken(IBinder token) {
        return ActivityRecord.forTokenLocked(token);
    }

    public static boolean isExcludingStopped(Intent intent) {
        String action = intent.getAction();
        return intent.isExcludingStopped() && action != null && mPreventRunning.isExcludingStopped(action);
    }

    public static int match(IntentFilter filter, String action, String type, String scheme, Uri data, Set<String> categories, String tag) {
        int match = filter.match(action, type, scheme, data, categories, tag);
        if (match >= 0) {
            return mPreventRunning.match(match, filter, action, type, scheme, data, categories);
        } else {
            return match;
        }
    }

    public static int onStartActivity(int res, IApplicationThread caller, Intent intent) {
        if (res >= 0 && intent != null && (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LAUNCHER))) {
            ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
            if (callerApp != null) {
                mPreventRunning.onStartHomeActivity(callerApp.info.packageName);
            }
        }
        return res;
    }

    public static boolean hookStartProcessLocked(ApplicationInfo info, boolean knownToBeDead, int intentFlags, String hostingType, ComponentName hostingName) {
        return mPreventRunning.hookStartProcessLocked(mAm.mContext, info, hostingType, hostingName);
    }

    public static boolean isDep(ArraySet<String> pkgDeps) {
        return false;
    }
}
