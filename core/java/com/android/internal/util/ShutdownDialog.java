/*
 * Copyright (C) 2016 AllianceROM, ~Morningstar
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

package com.android.internal.util;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ProgressBar;

import com.android.internal.R;
import com.android.internal.util.AllianceUtils;

/**
 * @hide
 */
public class ShutdownDialog extends Dialog {

    private static final int ACTION_UPDATE = 0;
    private static final int ACTION_FACTORY_RESET = 1;
    private static final int ACTION_REBOOT = 2;
    private static final int ACTION_SHUTDOWN = 3;

    private final Context mContext;

    private TextView mPrimaryText;
    private ProgressBar mProgress;
    private ImageView mLogo;

    public static ShutdownDialog create(Context context, int action) {
        return create(context,  WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG, action);
    }

    public static ShutdownDialog create(Context context, int windowType, int action) {
        final int theme = com.android.internal.R.style.Theme_DeviceDefault_Light_NoActionBar_TranslucentDecor;
        return new ShutdownDialog(context, theme, windowType, action);
    }

    private ShutdownDialog(Context context, int themeResId, int windowType, int action) {
        super(context, themeResId);
        mContext = context;

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View rootView = inflater.inflate(com.android.internal.R.layout.shutdown_layout, null, false);
        mLogo = (ImageView) rootView.findViewById(R.id.shutdown_logo);
        mProgress = (ProgressBar) rootView.findViewById(R.id.shutdown_progress);
        mPrimaryText = (TextView) rootView.findViewById(R.id.shutdown_message);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(rootView);

        if (windowType != 0) {
            getWindow().setType(windowType);
        }
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().getDecorView();
        rootView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                              | View.SYSTEM_UI_FLAG_FULLSCREEN
                              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        // turn off button lights while shutdown/reboot screen is showing
        lp.buttonBrightness = 0;
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
        getWindow().setAttributes(lp);
        setCancelable(false);
        setMessage(action);
        show();

        rootView.post(new Runnable() {
            @Override public void run() {
                // start the marquee
                mPrimaryText.setSelected(true);
            }
        });
    }

    private void setMessage(int action) {
        String mMessage = "";

        switch (action) {
            case ACTION_UPDATE:
                mMessage = mContext.getResources().getString(com.android.internal.R.string.reboot_to_update_prepare);
                break;
            case ACTION_FACTORY_RESET:
                mMessage = mContext.getResources().getString(com.android.internal.R.string.reboot_system_message);
                break;
            case ACTION_REBOOT:
            default:
                mMessage = mContext.getResources().getString(com.android.internal.R.string.reboot_system_message);
                break;
            case ACTION_SHUTDOWN:
                mMessage = mContext.getResources().getString(com.android.internal.R.string.shutdown_progress);
                break;
        }
        mPrimaryText.setText(mMessage);
    }

    public void setMessage(final CharSequence msg) {
        mPrimaryText.setText(msg);
    }

    // This dialog will consume all events coming in to it
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return true;
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return true;
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return true;
    }
}