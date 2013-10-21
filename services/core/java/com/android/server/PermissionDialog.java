/*
 * Copyright (C) 2015-2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
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

package com.android.server;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

public class PermissionDialog extends BasePermissionDialog {
    private final static String TAG = "PermissionDialog";

    private final AppOpsService mService;
    private final String mPackageName;
    private final int mCode;
    private View  mView;
    private CheckBox mChoice;
    private int mUid;
    final CharSequence[] mOpLabels;
    private Context mContext;

    // Event 'what' codes
    static final int ACTION_ALLOWED = 0x2;
    static final int ACTION_IGNORED = 0x4;
    static final int ACTION_IGNORED_TIMEOUT = 0x8;

    // 15s timeout, then we automatically dismiss the permission
    // dialog. Otherwise, it may cause watchdog timeout sometimes.
    static final long DISMISS_TIMEOUT = 1000 * 15 * 1;

    public PermissionDialog(Context context, AppOpsService service,
            int code, int uid, String packageName) {
        super(context);

        mContext = context;
        Resources res = context.getResources();

        mService = service;
        mCode = code;
        mPackageName = packageName;
        mUid = uid;
        mOpLabels = res.getTextArray(
            com.android.internal.R.array.app_ops_labels);

        setCancelable(false);

        setButton(DialogInterface.BUTTON_POSITIVE,
                  res.getString(com.android.internal.R.string.allow), mHandler.obtainMessage(ACTION_ALLOWED));

        setButton(DialogInterface.BUTTON_NEGATIVE,
                    res.getString(com.android.internal.R.string.deny), mHandler.obtainMessage(ACTION_IGNORED));

        setTitle(res.getString(com.android.internal.R.string.privacy_guard_dialog_title));
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Permission info: " + getAppName(mPackageName));
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);

        mView = getLayoutInflater().inflate(
             com.android.internal.R.layout.permission_confirmation_dialog,
             null);
        TextView tv = (TextView) mView.findViewById(
            com.android.internal.R.id.permission_text);
        mChoice = (CheckBox) mView.findViewById(
            com.android.internal.R.id.permission_remember_choice_checkbox);
        String name = getAppName(mPackageName);
        if(name == null)
            name = mPackageName;
        tv.setText(mContext.getString(com.android.internal.R.string.privacy_guard_dialog_summary,
                name, mOpLabels[mCode]));
        setView(mView);

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(ACTION_IGNORED_TIMEOUT), DISMISS_TIMEOUT);
    }

    public void ignore() {
        mHandler.sendMessage(mHandler.obtainMessage(ACTION_IGNORED_TIMEOUT));
    }

    private String getAppName(String packageName) {
        ApplicationInfo appInfo = null;
        PackageManager pm = mContext.getPackageManager();
        try {
            appInfo = pm.getApplicationInfo(packageName,
                      PackageManager.GET_DISABLED_COMPONENTS
                      | PackageManager.GET_UNINSTALLED_PACKAGES);
        } catch (final NameNotFoundException e) {
            return null;
        }
        if(appInfo != null) {
            return  (String)pm.getApplicationLabel(appInfo);
        }
        return null;
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            int mode;
            boolean remember = mChoice.isChecked();
            switch(msg.what) {
                case ACTION_ALLOWED:
                    mode = AppOpsManager.MODE_ALLOWED;
                    break;
                case ACTION_IGNORED:
                    mode = AppOpsManager.MODE_IGNORED;
                    break;
                default:
                    mode = AppOpsManager.MODE_IGNORED;
                    remember = false;
            }
            mService.notifyOperation(mCode, mUid, mPackageName, mode,
                remember);
            dismiss();
        }
    };
}
