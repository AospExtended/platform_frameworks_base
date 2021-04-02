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
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

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
public class TypeAltClockController implements ClockPlugin {

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
     * Root view of preview.
     */
    private View mView;

    /**
     * Text clock and date in formal.
     */
    private TextView mTextFormalTime;
    private TextView mTextFormalDate;

    /**
     * Time and calendars to check the date
     */
    private final Calendar mTime = Calendar.getInstance(TimeZone.getDefault());
    private String mDescFormat;
    private TimeZone mTimeZone;

    /**
     * Create a TypeAltClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public TypeAltClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
    }

    private void createViews() {
        mView = mLayoutInflater.inflate(R.layout.clock_type_text_alt, null);
        mTextFormalTime = mView.findViewById(R.id.formalTime);
        mTextFormalDate = mView.findViewById(R.id.formalDate);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTextFormalTime = null;
        mTextFormalDate = null;
    }

    @Override
    public String getName() {
        return "default";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_type) + " (alt)";
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.type_alt_thumbnail);
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
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
        mTextFormalTime.setTextColor(color);
        mTextFormalDate.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {}

    @Override
    public void onTimeTick() {
        mTime.setTimeInMillis(System.currentTimeMillis());

        // Let's see what string we need according to the time
        int saluteResId = R.string.salute_fallback;

        if (mTime.get(Calendar.HOUR_OF_DAY) > 4) {
            saluteResId = R.string.salute_morning;
        } else if (mTime.get(Calendar.HOUR_OF_DAY) > 12) {
            saluteResId = R.string.salute_evening;
        } else if (mTime.get(Calendar.HOUR_OF_DAY) > 19 || mTime.get(Calendar.HOUR_OF_DAY) < 5) {
            saluteResId = R.string.salute_night;
        }

        int hour = mTime.get(Calendar.HOUR) % 12;
        // lazy and ugly workaround for the it's string
        String typeHeader = mResources.getQuantityText(
                R.plurals.type_clock_header, hour).toString();
        typeHeader = typeHeader.replaceAll("\\n", "");
        SimpleDateFormat timeformat = new SimpleDateFormat("HH:mm");

        // Final string parsing
        mTextFormalTime.setText(mResources.getString(saluteResId) + ", " + typeHeader.substring(
            0, typeHeader.indexOf("^")).toLowerCase() + " " + timeformat.format(mTime.getInstance().getTimeInMillis()));

        DateFormat dateFormat = DateFormat.getInstanceForSkeleton("EEEEMMMMdyyyy", Locale.getDefault());
        dateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        mTextFormalDate.setText(String.format(mResources.getString(
            R.string.date_long_title_today), dateFormat.format(mTime.getInstance().getTimeInMillis())));
    }

    @Override
    public void setDarkAmount(float darkAmount) {}

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
