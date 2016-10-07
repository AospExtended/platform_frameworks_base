package me.piebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import dalvik.system.DexClassLoader;

/**
 * Created by thom on 15/10/27.
 */
public class PreventRunning implements PreventRunningHook {

    private static final String TAG = "Prevent";

    private static final int VERSION = 20160406;

    private PreventRunningHook mPreventRunning;

    private static String[] APKS = {
            "/data/app/me.piebridge.forcestopgb-1/base.apk",
            "/data/app/me.piebridge.forcestopgb-2/base.apk",
            "/data/app/me.piebridge.forcestopgb-3/base.apk",
            "/data/app/me.piebridge.forcestopgb-1.apk",
            "/data/app/me.piebridge.forcestopgb-2.apk",
            "/data/app/me.piebridge.forcestopgb-3.apk",
    };

    public PreventRunning() {
        for (String apk : APKS) {
            File file = new File(apk);
            if (file.exists() && initPreventRunning(file)) {
                break;
            }
        }
    }

    private boolean initPreventRunning(File apk) {
        try {
            ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader classLoader = new DexClassLoader(apk.getAbsolutePath(), "/cache", null, currentClassLoader);
            Log.d(TAG, "loading PreventRunning(" + VERSION + ") from " + apk);
            mPreventRunning = (PreventRunningHook) classLoader.loadClass("me.piebridge.prevent.framework.PreventRunning").newInstance();
            setVersion();
            setMethod();
            return true;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "cannot find class", e);
        } catch (InstantiationException e) {
            Log.d(TAG, "cannot instance class", e);
        } catch (IllegalAccessException e) {
            Log.d(TAG, "cannot access class", e);
        } catch (Throwable t) { // NOSONAR
            Log.d(TAG, "cannot load PreventRunning from " + apk, t);
        }
        return false;
    }

    @Override
    public void setSender(String sender) {
        if (mPreventRunning != null) {
            mPreventRunning.setSender(sender);
        }
    }

    @Override
    public void onBroadcastIntent(Intent intent) {
        if (mPreventRunning != null) {
            mPreventRunning.onBroadcastIntent(intent);
        }
    }

    @Override
    public void onCleanUpRemovedTask(String packageName) {
        if (mPreventRunning != null) {
            mPreventRunning.onCleanUpRemovedTask(packageName);
        }
    }

    @Override
    public void onStartHomeActivity(String packageName) {
        if (mPreventRunning != null) {
            mPreventRunning.onStartHomeActivity(packageName);
        }
    }

    @Override
    public void onMoveActivityTaskToBack(String packageName) {
        if (mPreventRunning != null) {
            mPreventRunning.onMoveActivityTaskToBack(packageName);
        }
    }

    @Override
    public void onAppDied(Object processRecord) {
        if (mPreventRunning != null) {
            mPreventRunning.onAppDied(processRecord);
        }
    }

    @Override
    public void onLaunchActivity(Object activityRecord) {
        if (mPreventRunning != null) {
            mPreventRunning.onLaunchActivity(activityRecord);
        }
    }

    @Override
    public void onResumeActivity(Object activityRecord) {
        if (mPreventRunning != null) {
            mPreventRunning.onResumeActivity(activityRecord);
        }
    }

    @Override
    public void onUserLeavingActivity(Object activityRecord) {
        if (mPreventRunning != null) {
            mPreventRunning.onUserLeavingActivity(activityRecord);
        }
    }

    @Override
    public void onDestroyActivity(Object activityRecord) {
        if (mPreventRunning != null) {
            mPreventRunning.onDestroyActivity(activityRecord);
        }
    }

    @Override
    public boolean isExcludingStopped(String action) {
        return mPreventRunning == null || mPreventRunning.isExcludingStopped(action);
    }


    @Override
    public boolean hookStartProcessLocked(Context context, ApplicationInfo info, String hostingType, ComponentName hostingName) {
        return mPreventRunning == null || mPreventRunning.hookStartProcessLocked(context, info, hostingType, hostingName);
    }

    @Override
    public int match(int match, Object filter, String action, String type, String scheme, Uri data, Set<String> categories) {
        if (mPreventRunning != null) {
            return mPreventRunning.match(match, filter, action, type, scheme, data, categories);
        } else {
            return match;
        }
    }

    private void setVersion() {
        try {
            Method method = mPreventRunning.getClass().getMethod("setVersion", int.class);
            method.invoke(mPreventRunning, VERSION);
        } catch (NoSuchMethodException e) {
            Log.d(TAG, "cannot find method", e);
        } catch (InvocationTargetException e) {
            Log.d(TAG, "cannot invoke target", e);
        } catch (IllegalAccessException e) {
            Log.d(TAG, "illegal access", e);
        }
    }

    private void setMethod() {
        try {
            Method method = mPreventRunning.getClass().getMethod("setMethod", String.class);
            method.invoke(mPreventRunning, "native");
        } catch (NoSuchMethodException e) {
            Log.d(TAG, "cannot find method", e);
        } catch (InvocationTargetException e) {
            Log.d(TAG, "cannot invoke target", e);
        } catch (IllegalAccessException e) {
            Log.d(TAG, "illegal access", e);
        }
    }
}
