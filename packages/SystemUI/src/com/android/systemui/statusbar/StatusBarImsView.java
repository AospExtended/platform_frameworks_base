/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController.ImsIconState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

public class StatusBarImsView extends FrameLayout implements
        StatusIconDisplayable {
    private static final String TAG = "StatusBarImsView";

    private ImsIconState mState;
    private LinearLayout mImsGroup;
    private ImageView mVowifiIcon;
    private ImageView mVolteIcon;
    private String mSlot;
    private int mVisibleState = -1;

    public StatusBarImsView(Context context) {
        super(context);
    }

    public StatusBarImsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StatusBarImsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StatusBarImsView(Context context, AttributeSet attrs, int defStyleAttr,
                            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public static StatusBarImsView fromContext(Context context, String slot) {
        LayoutInflater inflater = LayoutInflater.from(context);
        StatusBarImsView v = (StatusBarImsView) inflater.inflate(R.layout.status_bar_ims_group, null);
        v.setSlot(slot);
        v.init();
        v.setVisibleState(STATE_ICON);
        return v;
    }

    public void init() {
        mImsGroup = findViewById(R.id.ims_group);
        mVowifiIcon = findViewById(R.id.vowifi_icon);
        mVolteIcon = findViewById(R.id.volte_icon);
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                setVisibility(View.VISIBLE);
                break;
            case STATE_DOT:
                setVisibility(View.GONE);
                break;
            case STATE_HIDDEN:
            default:
                setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public boolean isIconVisible() {
        return mState != null && mState.visible;
    }

    public void applyImsState(ImsIconState state) {
        mState = state;
        if (state == null) {
            setVisibility(View.INVISIBLE);
        } else {
            initViewState(state);
        }
    }

    private void initViewState(ImsIconState state) {
        setContentDescription(state.contentDescription);
        if (state.vowifiVisible) {
            mVolteIcon.setVisibility(View.GONE);
            mVowifiIcon.setImageDrawable(mContext.getDrawable(state.vowifiIcon));
            mVowifiIcon.setVisibility(View.VISIBLE);
        } else if (state.volteVisible) {
            mVowifiIcon.setVisibility(View.GONE);
            mVolteIcon.setImageDrawable(mContext.getDrawable(state.volteIcon));
            mVolteIcon.setVisibility(View.VISIBLE);
        } else {
            mVolteIcon.setVisibility(View.GONE);
            mVowifiIcon.setVisibility(View.GONE);
        }
        setVisibility(state.visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void setDecorColor(int color) {
    }

    @Override
    public void setStaticDrawableColor(int color) {
        ColorStateList list = ColorStateList.valueOf(color);
        mVolteIcon.setImageTintList(list);
        mVowifiIcon.setImageTintList(list);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        ColorStateList color = ColorStateList.valueOf(getTint(area, this, tint));
        mVolteIcon.setImageTintList(color);
        mVowifiIcon.setImageTintList(color);
    }
}
