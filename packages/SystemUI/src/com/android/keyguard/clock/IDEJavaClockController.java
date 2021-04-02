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
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.os.SystemProperties;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;


import static com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm.CLOCK_USE_DEFAULT_Y;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class IDEJavaClockController implements ClockPlugin {

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
    private TextView mDate;
    private TextClock mMonth;
    private TextClock mYear;
    private TextView mtextPackage;
    private TextView mtextPackageSubclass;
    private TextView mtextClassAccessMod;
    private TextView mtextClassObject;
    private TextView mtextStringAccessMod;
    private TextView mtextStringClass;
    private TextView mtextIntAccessMod;
    private TextView mtextIntClass;
    private TextView mtextTimeVar;
    private TextView mtextDateVar;
    private TextView mtextMonthVar;
    private TextView mtextYearVar;

    /**
     * Time and calendars to check the date
     */
    private final Calendar mTimeCal = Calendar.getInstance(TimeZone.getDefault());
    private TimeZone mTimeZone;

    /**
     * Create a DefaultClockController instance.
     *
     * @param res            Resources contains title and thumbnail.
     * @param inflater       Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public IDEJavaClockController(Resources res, LayoutInflater inflater,
                              SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
    }

    private void createViews() {
        mView = (ClockLayout) mLayoutInflater
                .inflate(R.layout.clock_ide_java_mix, null);
        mTime = mView.findViewById(R.id.clockTimeView);
        mDate = mView.findViewById(R.id.clockDateView);
        mMonth = mView.findViewById(R.id.clockMonthView);
        mYear = mView.findViewById(R.id.clockYearView);
        mtextPackage = mView.findViewById(R.id.textPackage);
        mtextPackageSubclass = mView.findViewById(R.id.textPackageName);
        mtextClassAccessMod = mView.findViewById(R.id.textDeclareClass);
        mtextClassObject = mView.findViewById(R.id.textDeclareClassType);
        mtextStringAccessMod = mView.findViewById(R.id.textStringDeclare);
        mtextStringClass = mView.findViewById(R.id.textStringDeclareType);
        mtextIntAccessMod = mView.findViewById(R.id.textIntDeclare);
        mtextIntClass = mView.findViewById(R.id.textIntDeclareType);
        mtextTimeVar = mView.findViewById(R.id.clockTime);
        mtextDateVar = mView.findViewById(R.id.clockDate);
        mtextMonthVar = mView.findViewById(R.id.clockMonth);
        mtextYearVar = mView.findViewById(R.id.clockYear);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTime = null;
        mDate = null;
        mMonth = null;
        mYear = null;
        mtextPackage = null;
        mtextPackageSubclass = null;
        mtextClassAccessMod = null;
        mtextClassObject = null;
        mtextStringAccessMod = null;
        mtextStringClass = null;
        mtextIntAccessMod = null;
        mtextIntClass = null;
        mtextTimeVar = null;
        mtextDateVar = null;
        mtextMonthVar = null;
        mtextYearVar = null;
    }

    @Override
    public String getName() {
        return "ide-java";
    }

    @Override
    public String getTitle() {
        return "IDE (Java Mix)";
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.ide_java_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {
        setTextColor(Color.WHITE);
        View previewView = getView();

        // Initialize state of plugin before generating preview.
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
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        mPalette.setColorPalette(supportsDarkText, colorPalette);
        updateColor();
    }

    private void updateColor() {
        final int primary = mPalette.getPrimaryColor();

        if (mView == null) createViews();

        TextView[] primaryViews = { mtextPackage, mtextClassAccessMod, mtextStringAccessMod, mtextIntAccessMod };
        TextView[] secondaryViews = { mtextClassObject, mtextStringClass, mtextIntClass };
        TextView[] tertiaryViews = { mtextYearVar, mtextDateVar, mtextTimeVar, mtextMonthVar };

        for (TextView pView : primaryViews) {
            pView.setTextColor(primary);
        }
        for (TextView sView : secondaryViews) {
            sView.setTextColor(generateColorDesat(primary, 0.6f));
        }
        for (TextView tView : tertiaryViews) {
            tView.setTextColor(generateColorDesat(primary, 0.4f));
        }
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mPalette.setDarkAmount(darkAmount);
        mView.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeTick() {
        String buildType = SystemProperties.get("ro.bootleggers.releasetype", "Lost");
        mtextPackageSubclass.setText(buildType.toLowerCase());
        DateFormat dateFormat = DateFormat.getInstanceForSkeleton("EEEEd", Locale.getDefault());
        dateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        mDate.setText(dateFormat.format(mTimeCal.getInstance().getTimeInMillis()));
        mView.onTimeChanged();
        mTime.refreshTime();
        mMonth.refreshTime();
        mYear.refreshTime();
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        onTimeTick();
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }

    private int generateColorDesat(int color, float satValue) {
        float[] hslParams = new float[3];
        ColorUtils.colorToHSL(color, hslParams);
        // Conversion to desature the color?
        hslParams[1] = hslParams[1]*satValue;
        return ColorUtils.HSLToColor(hslParams);
    }
}
