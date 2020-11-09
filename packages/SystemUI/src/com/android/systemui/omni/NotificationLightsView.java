/*
* Copyright (C) 2019 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.omni;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.WallpaperInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.palette.graphics.Palette;

import com.android.settingslib.Utils;
import com.android.systemui.R;

public class NotificationLightsView extends RelativeLayout {
    private static final boolean DEBUG = false;
    private static final String TAG = "NotificationLightsView";
    private static final String CANCEL_NOTIFICATION_PULSE_ACTION = "cancel_notification_pulse";
    private ValueAnimator mLightAnimator;

    public NotificationLightsView(Context context) {
        this(context, null);
    }

    public NotificationLightsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationLightsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationLightsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void stopAnimateNotification() {
        if (mLightAnimator != null) {
            mLightAnimator.end();
            mLightAnimator = null;
        }
    }

    public void animateNotification() {
        animateNotificationWithColor(getNotificationLightsColor());
    }

    public int getNotificationLightsColor() {
        int colorMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NOTIFICATION_PULSE_COLOR_MODE,
                0, UserHandle.USER_CURRENT);
        int color = getDefaultNotificationLightsColor(); // custom color (fallback)
        if (colorMode == 0) { // accent
            color = Utils.getColorAccentDefaultColor(getContext());
        } else if (colorMode == 1) { // wallpapper
            try {
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
                WallpaperInfo wallpaperInfo = wallpaperManager.getWallpaperInfo();
                if (wallpaperInfo == null) { // if not a live wallpaper
                    Drawable wallpaperDrawable = wallpaperManager.getDrawable();
                    Bitmap bitmap = ((BitmapDrawable)wallpaperDrawable).getBitmap();
                    if (bitmap != null) { // if wallpaper is not blank
                        Palette p = Palette.from(bitmap).generate();
                        int wallColor = p.getDominantColor(color);
                        if (color != wallColor)
                            color = wallColor;
                    }
                }
            } catch (Exception e) { /* nothing to do, will use fallback */ }
        }
        return color;
    }

    public int getDefaultNotificationLightsColor() {
        int defaultColor = Utils.getColorAccentDefaultColor(getContext());
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.NOTIFICATION_PULSE_COLOR, defaultColor,
                    UserHandle.USER_CURRENT);
    }

    public void animateNotificationWithColor(int color) {
        ContentResolver resolver = mContext.getContentResolver();
        int duration = Settings.System.getIntForUser(resolver,
                Settings.System.NOTIFICATION_PULSE_DURATION, 2,
                UserHandle.USER_CURRENT) * 1000; // seconds to ms
        int repeats = Settings.System.getIntForUser(resolver,
                Settings.System.NOTIFICATION_PULSE_REPEATS, 0,
                UserHandle.USER_CURRENT);

        ImageView leftView = (ImageView) findViewById(R.id.notification_animation_left);
        ImageView rightView = (ImageView) findViewById(R.id.notification_animation_right);
        leftView.setColorFilter(color);
        rightView.setColorFilter(color);
        mLightAnimator = ValueAnimator.ofFloat(new float[]{0.0f, 2.0f});
        mLightAnimator.setDuration(duration);
        mLightAnimator.setRepeatCount(repeats == 0 ?
                ValueAnimator.INFINITE : repeats);
        mLightAnimator.setRepeatMode(ValueAnimator.RESTART);
        if (repeats != 0) {
            mLightAnimator.addListener(new AnimatorListener() {
                @Override
                public void onAnimationCancel(Animator animation) { /* do nothing */ }
                @Override
                public void onAnimationRepeat(Animator animation) { /* do nothing */ }
                @Override
                public void onAnimationStart(Animator animation) { /* do nothing */ }
                @Override
                public void onAnimationEnd(Animator animation) {
                    Settings.System.putIntForUser(resolver,
                            Settings.System.AOD_NOTIFICATION_PULSE_ACTIVATED, 0,
                            UserHandle.USER_CURRENT);
                    Settings.System.putIntForUser(resolver,
                            Settings.System.AOD_NOTIFICATION_PULSE_TRIGGER, 0,
                            UserHandle.USER_CURRENT);
                }
            });
        }
        mLightAnimator.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                if (DEBUG) Log.d(TAG, "onAnimationUpdate");
                float progress = ((Float) animation.getAnimatedValue()).floatValue();
                leftView.setScaleY(progress);
                rightView.setScaleY(progress);
                float alpha = 1.0f;
                if (progress <= 0.3f) {
                    alpha = progress / 0.3f;
                } else if (progress >= 1.0f) {
                    alpha = 2.0f - progress;
                }
                leftView.setAlpha(alpha);
                rightView.setAlpha(alpha);
            }
        });
        if (DEBUG) Log.d(TAG, "start");
        mLightAnimator.start();
    }
}
