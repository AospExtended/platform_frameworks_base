/*
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2021 Project 404
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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

import static com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm.CLOCK_USE_DEFAULT_Y;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class IDEClockController implements ClockPlugin {

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
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Helper to extract colors from wallpaper palette for clock face.
     */
    private final ClockPalette mPalette = new ClockPalette();

    /**
     * Root view of clock.
     */
    private ClockLayout mView;

    /**
     * Text clock for time, date, day and month
     */
    private TextClock mTime;
    private TextClock mDate;
    private TextClock mDay;
    private TextClock mMonth;
    private TextView mtextInclude;
    private TextView mtextStd;
    private TextView mtextUsingNamespace;
    private TextView mtextIntMain;
    private TextView mtextTimeDateDayMonth;

    /**
     * Create a DefaultClockController instance.
     *
     * @param res            Resources contains title and thumbnail.
     * @param inflater       Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public IDEClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
    }

    private void createViews() {
        mView = (ClockLayout) mLayoutInflater
                .inflate(R.layout.p404_ide_clock, null);
        mTime = mView.findViewById(R.id.clockTime);
        mDate = mView.findViewById(R.id.clockDate);
        mDay = mView.findViewById(R.id.clockDay);
        mMonth = mView.findViewById(R.id.clockMonth);
        mtextInclude = mView.findViewById(R.id.textInclude);
        mtextStd = mView.findViewById(R.id.textStd);
        mtextUsingNamespace = mView.findViewById(R.id.textUsingNamespace);
        mtextIntMain = mView.findViewById(R.id.textIntMain);
        mtextTimeDateDayMonth = mView.findViewById(R.id.textTimeDateDayMonth);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTime = null;
        mDate = null;
        mDay = null;
        mMonth = null;
    }

    @Override
    public String getName() {
        return "ide";
    }

    @Override
    public String getTitle() {
        return "IDE";
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.ide_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {

        View previewView = mLayoutInflater.inflate(R.layout.p404_ide_clock_preview, null);
        TextClock previewTime = previewView.findViewById(R.id.clockTime);
        TextClock previewDate = previewView.findViewById(R.id.clockDate);
        TextClock previewDay = previewView.findViewById(R.id.clockDay);
        TextClock previewMonth = previewView.findViewById(R.id.clockMonth);

        // Initialize state of plugin before generating preview.
        previewTime.setTextColor(Color.WHITE);
        previewDate.setTextColor(Color.WHITE);
        previewDay.setTextColor(Color.WHITE);
        previewMonth.setTextColor(Color.WHITE);
        ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK);
        setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
        onTimeTick();

        return mRenderer.createPreview(previewView, width, height);
    }

    @Override
    public View getView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public View getBigClockView() {
        return null;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return CLOCK_USE_DEFAULT_Y;
    }

    @Override
    public void setStyle(Style style) {
    }

    @Override
    public void setTextColor(int color) {
        mTime.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        mPalette.setColorPalette(supportsDarkText, colorPalette);
        updateColor();
    }

    private void updateColor() {
        final int primary = mPalette.getPrimaryColor();
        final int secondary = mPalette.getSecondaryColor();
        mDate.setTextColor(secondary);
        mtextInclude.setTextColor(secondary);
        mtextUsingNamespace.setTextColor(secondary);
        mtextIntMain.setTextColor(secondary);
        mMonth.setTextColor(primary);
        mtextStd.setTextColor(primary);
        mtextTimeDateDayMonth.setTextColor(primary);
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mPalette.setDarkAmount(darkAmount);
        mView.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeTick() {
        if (mView != null)
            mView.onTimeChanged();
        if (mTime != null)
            mTime.refreshTime();
        if (mDate != null)
            mDate.refreshTime();
        if (mDay != null)
            mDay.refreshTime();
        if (mMonth != null)
            mMonth.refreshTime();
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
