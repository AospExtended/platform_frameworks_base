/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Plugin for a custom Typographic clock face that displays the time in words.
 */
public class TypeClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    private final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    private final LayoutInflater mLayoutInflater;

    /**
     * Extracts accent color from wallpaper.
     */
    private final SysuiColorExtractor mColorExtractor;

    /**
     * Computes preferred position of clock.
     */
    private float mDarkAmount;
    private final int mStatusBarHeight;
    private final int mKeyguardLockPadding;
    private final int mKeyguardLockHeight;
    private final int mBurnInOffsetY;

    /**
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Custom clock shown on AOD screen and behind stack scroller on lock.
     */
    private View mView;
    private TypographicClock mTypeClock;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private TypographicClock mLockClock;

    /**
     * Controller for transition into dark state.
     */
    private CrossFadeDarkController mDarkController;

    /**
     * Create a TypeClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    TypeClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
        mStatusBarHeight = res.getDimensionPixelSize(R.dimen.status_bar_height);
        mKeyguardLockPadding = res.getDimensionPixelSize(R.dimen.keyguard_lock_padding);
        mKeyguardLockHeight = res.getDimensionPixelSize(R.dimen.keyguard_lock_height);
        mBurnInOffsetY = res.getDimensionPixelSize(R.dimen.burn_in_prevention_offset_y);
    }

    private void createViews() {
        mView = mLayoutInflater.inflate(R.layout.type_aod_clock, null);
        mTypeClock = mView.findViewById(R.id.type_clock);

        // For now, this view is used to hide the default digital clock.
        // Need better transition to lock screen.
        mLockClock = (TypographicClock) mLayoutInflater.inflate(R.layout.typographic_clock, null);
        mLockClock.setVisibility(View.GONE);

        mDarkController = new CrossFadeDarkController(mView, mLockClock);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTypeClock = null;
        mLockClock = null;
        mDarkController = null;
    }

    @Override
    public String getName() {
        return "type";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_type);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.type_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {

        // Use the big clock view for the preview
        View view = getBigClockView();

        // Initialize state of plugin before generating preview.
        setDarkAmount(1f);
        setTextColor(Color.WHITE);
        ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK);
        setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
        onTimeTick();

        return mRenderer.createPreview(view, width, height);
    }

    @Override
    public View getView() {
        if (mLockClock == null) {
            createViews();
        }
        return mLockClock;
    }

    @Override
    public View getBigClockView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        // On AOD, clock needs to appear below the status bar with enough room for pixel shifting
        int aodY = mStatusBarHeight + mKeyguardLockHeight + 2 * mKeyguardLockPadding
                + mBurnInOffsetY + mTypeClock.getHeight() + (mTypeClock.getHeight() / 2);
        // On lock screen, clock needs to appear below the lock icon
        int lockY =  mStatusBarHeight + mKeyguardLockHeight + 2 * mKeyguardLockPadding + (mTypeClock.getHeight() / 2);
        return (int) MathUtils.lerp(lockY, aodY, mDarkAmount);
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
        mTypeClock.setTextColor(color);
        mLockClock.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        if (colorPalette == null || colorPalette.length == 0) {
            return;
        }
        final int color = colorPalette[Math.max(0, colorPalette.length - 5)];
        mTypeClock.setClockColor(color);
        mLockClock.setClockColor(color);
    }

    @Override
    public void onTimeTick() {
        mTypeClock.onTimeChanged();
        mLockClock.onTimeChanged();
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        if (mDarkController != null) {
            mDarkController.setDarkAmount(darkAmount);
        }
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTypeClock.onTimeZoneChanged(timeZone);
        mLockClock.onTimeZoneChanged(timeZone);
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
