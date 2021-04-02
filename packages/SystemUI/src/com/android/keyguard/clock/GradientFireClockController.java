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

import static com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm.CLOCK_USE_DEFAULT_Y;

import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff.Mode;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.TextClock;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Controller for Stretch clock that can appear on lock screen and AOD.
 */
public class GradientFireClockController implements ClockPlugin {

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
    private final SmallClockPosition mClockPosition;

    /**
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Custom clock shown on AOD screen and behind stack scroller on lock.
     */
    private ClockLayout mBackgroundView;
    private ImageView mGradientBackground;
    private TextView mDateText;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private View mView;
    private TextClock mLockClock;

    /**
     * Helper to extract colors from wallpaper palette for clock face.
     */
    private final ClockPalette mPalette = new ClockPalette();

    /**
     * Time and calendars to check the date
     */
    private final Calendar mTime = Calendar.getInstance(TimeZone.getDefault());
    private TimeZone mTimeZone;

    /**
     * Create a BubbleClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public GradientFireClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
        mClockPosition = new SmallClockPosition(res);
    }

    private void createViews() {
        mView = mLayoutInflater.inflate(R.layout.clock_gradient_fire_clock, null);
        mBackgroundView = (ClockLayout) mLayoutInflater.inflate(R.layout.clock_gradient_fire, null);
        mGradientBackground = mBackgroundView.findViewById(R.id.bottomGradient);
        mDateText = mBackgroundView.findViewById(R.id.bottomDate);
        mLockClock = mView.findViewById(R.id.smallClock);
    }

    @Override
    public void onDestroyView() {
        mBackgroundView = null;
        mGradientBackground = null;
        mDateText = null;
        mView = null;
        mLockClock = null;
    }

    @Override
    public String getName() {
        return "gradient_fire";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.gradient_fire_clock_title);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.gradient_fire_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {

        // Use the big clock view for the preview
        View view = getView();

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
        if (mBackgroundView == null) {
            createViews();
        }
        return mBackgroundView;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return CLOCK_USE_DEFAULT_Y;
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
    	if (mView == null) createViews();
        updateColor();
        mLockClock.setTextColor(color);
        mDateText.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        mPalette.setColorPalette(supportsDarkText, colorPalette);
        updateColor();
    }

    private void updateColor() {
        if (mView == null) createViews(); 
        final int primary = mPalette.getPrimaryColor();
        mGradientBackground.setColorFilter(primary, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    @Override
    public void onTimeTick() {
        DateFormat dateFormat = DateFormat.getInstanceForSkeleton("EEEEd", Locale.getDefault());
        dateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        mDateText.setText(String.format(mResources.getString(
            R.string.gradient_fire_bottom_text), dateFormat.format(mTime.getInstance().getTimeInMillis())));
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mPalette.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mTime.setTimeZone(timeZone);
        onTimeTick();
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
