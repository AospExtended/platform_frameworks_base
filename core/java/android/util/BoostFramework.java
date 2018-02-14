/*
 * Copyright (c) 2017-2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 *      copyright notice, this list of conditions and the following
 *      disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *    * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *      from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.util;

import android.util.Log;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.System;
import android.view.MotionEvent;
import android.util.DisplayMetrics;
import android.os.SystemProperties;
import android.content.Context;

/** @hide */
public class BoostFramework {

    private static final String TAG = "BoostFramework";
    private static final String PERFORMANCE_JAR = "/system/framework/QPerformance.jar";
    private static final String PERFORMANCE_CLASS = "com.qualcomm.qti.Performance";

/** @hide */
    private static boolean mIsLoaded = false;
    private static Class<?> mPerfClass = null;
    private static Method mAcquireFunc = null;
    private static Method mPerfHintFunc = null;
    private static Method mReleaseFunc = null;
    private static Method mReleaseHandlerFunc = null;
    private static Constructor<Class> mConstructor = null;

/** @hide */
    private Object mPerf = null;

    //perf hints
    public static final int VENDOR_HINT_SCROLL_BOOST = 0x00001080;
    public static final int VENDOR_HINT_FIRST_LAUNCH_BOOST = 0x00001081;
    public static final int VENDOR_HINT_SUBSEQ_LAUNCH_BOOST = 0x00001082;
    public static final int VENDOR_HINT_ANIM_BOOST = 0x00001083;
    public static final int VENDOR_HINT_ACTIVITY_BOOST = 0x00001084;
    public static final int VENDOR_HINT_TOUCH_BOOST = 0x00001085;
    public static final int VENDOR_HINT_MTP_BOOST = 0x00001086;
    public static final int VENDOR_HINT_DRAG_BOOST = 0x00001087;
    //perf events
    public static final int VENDOR_HINT_FIRST_DRAW = 0x00001042;
    public static final int VENDOR_HINT_TAP_EVENT = 0x00001043;

    public class Scroll {
        public static final int VERTICAL = 1;
        public static final int HORIZONTAL = 2;
        public static final int PANEL_VIEW = 3;
        public static final int PREFILING = 4;
    };

    public class Launch {
        public static final int BOOST_V1 = 1;
        public static final int BOOST_V2 = 2;
        public static final int BOOST_V3 = 3;
        public static final int TYPE_SERVICE_START = 100;
    };

    public class Draw {
        public static final int EVENT_TYPE_V1 = 1;
    };

/** @hide */
    public BoostFramework() {
        synchronized(BoostFramework.class) {
            if (mIsLoaded == false) {
                try {
                    mPerfClass = Class.forName(PERFORMANCE_CLASS);

                    Class[] argClasses = new Class[] {int.class, int[].class};
                    mAcquireFunc = mPerfClass.getMethod("perfLockAcquire", argClasses);

                    argClasses = new Class[] {int.class, String.class, int.class, int.class};
                    mPerfHintFunc = mPerfClass.getMethod("perfHint", argClasses);

                    argClasses = new Class[] {};
                    mReleaseFunc = mPerfClass.getMethod("perfLockRelease", argClasses);

                    argClasses = new Class[] {int.class};
                    mReleaseHandlerFunc = mPerfClass.getDeclaredMethod("perfLockReleaseHandler", argClasses);

                    mIsLoaded = true;
                }
                catch(Exception e) {
                    Log.e(TAG,"BoostFramework() : Exception_1 = " + e);
                }
            }
        }

        try {
            if (mPerfClass != null) {
                mPerf = mPerfClass.newInstance();
            }
        }
        catch(Exception e) {
            Log.e(TAG,"BoostFramework() : Exception_2 = " + e);
        }
    }

/** @hide */
    public int perfLockAcquire(int duration, int... list) {
        int ret = -1;
        try {
            Object retVal = mAcquireFunc.invoke(mPerf, duration, list);
            ret = (int)retVal;
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfLockRelease() {
        int ret = -1;
        try {
            Object retVal = mReleaseFunc.invoke(mPerf);
            ret = (int)retVal;
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfLockReleaseHandler(int handle) {
        int ret = -1;
        try {
            Object retVal = mReleaseHandlerFunc.invoke(mPerf, handle);
            ret = (int)retVal;
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfHint(int hint, String userDataStr) {
        return perfHint(hint, userDataStr, -1, -1);
    }

/** @hide */
    public int perfHint(int hint, String userDataStr, int userData) {
        return perfHint(hint, userDataStr, userData, -1);
    }

/** @hide */
    public int perfHint(int hint, String userDataStr, int userData1, int userData2) {
        int ret = -1;
        try {
            Object retVal = mPerfHintFunc.invoke(mPerf, hint, userDataStr, userData1, userData2);
            ret = (int)retVal;
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }
};
