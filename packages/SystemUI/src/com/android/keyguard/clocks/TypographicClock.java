package com.android.keyguard.clocks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.res.Resources;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.Annotation;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.android.keyguard.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

public class TypographicClock extends TextView {

    private Resources mResources;
    private String[] mHours;
    private String[] mMinutes;
    private int mCurrentHour = -1;
    private int mCurrentMinute = -1;
    private String mDescFormat;
    private TimeZone mTimeZone;
    private int mAccentColor;
    private final Calendar mTime;
    private boolean mTransition = false;
    private final Animation fadeIn;
    private final Animation fadeOut;

    private final BroadcastReceiver mTimeZoneChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                onTimeZoneChanged(TimeZone.getTimeZone(tz));
                onTimeChanged();
            } else if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                mResources = context.getResources();
                mHours = mResources.getStringArray(R.array.text_clock_hours_array);
                mMinutes = mResources.getStringArray(R.array.text_clock_minutes_array);
                mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
                mTransition = true;
                onTimeChanged();
            }
        }
    };

    public TypographicClock(Context context) {
        this(context, null);
    }

    public TypographicClock(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public TypographicClock(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);

        mTime = Calendar.getInstance(TimeZone.getDefault());
        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
        mResources = context.getResources();
        mHours = mResources.getStringArray(R.array.text_clock_hours_array);
        mMinutes = mResources.getStringArray(R.array.text_clock_minutes_array);
        mAccentColor = mResources.getColor(R.color.custom_text_clock_top_color, null);

        fadeIn = new AlphaAnimation(0, 1);
        fadeIn.setInterpolator(new DecelerateInterpolator());
        fadeIn.setDuration(300);

        fadeOut = new AlphaAnimation(1, 0);
        fadeOut.setInterpolator(new AccelerateInterpolator());
        fadeOut.setStartOffset(300);
        fadeOut.setDuration(300);
    }

    public void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        setContentDescription(DateFormat.format(mDescFormat, mTime));
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int newHour = mTime.get(Calendar.HOUR_OF_DAY) % (h24 ? 24 : 12);
        int newMinute = mTime.get(Calendar.MINUTE) % 60;

        // 12 hours format correction
        if(newHour == 0 && !h24)
            newHour = 12;

        mTransition |= (mCurrentHour != newHour || mCurrentMinute != newMinute);
        mCurrentHour = newHour;
        mCurrentMinute = newMinute;

        String header = mResources.getQuantityString(R.plurals.text_clock_header, mCurrentHour);
        SpannedString rawFormat = (SpannedString) mResources.getText(R.string.text_clock_string_format);
        Annotation[] annotationArr = rawFormat.getSpans(0, rawFormat.length(), Annotation.class);
        SpannableString stringFormat = new SpannableString(rawFormat);

        // Set header color
        for (Annotation annotation : annotationArr) {
            if ("header_color".equals(annotation.getValue())) {
                stringFormat.setSpan(new ForegroundColorSpan(mAccentColor),
                        stringFormat.getSpanStart(annotation),
                        stringFormat.getSpanEnd(annotation),
                        Spanned.SPAN_POINT_POINT);
            }
        }

        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationEnd(Animation animation) {
                setText(TextUtils.expandTemplate(stringFormat, header, mHours[mCurrentHour], mMinutes[mCurrentMinute]));
                startAnimation(fadeIn);
            }

            @Override
            public void onAnimationStart(Animation animation) { }

            @Override
            public void onAnimationRepeat(Animation animation) { }
        });

        if (mTransition)
            startAnimation(fadeOut);

        mTransition = false;
    }

    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mTime.setTimeZone(timeZone);
    }

    public void updateHeaderColor() {
        mAccentColor = mResources.getColor(R.color.custom_text_clock_top_color, null);
        onTimeChanged();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TimeZone timeZone = mTimeZone == null ? TimeZone.getDefault() : mTimeZone;
        mTime.setTimeZone(timeZone);
        onTimeChanged();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        getContext().registerReceiver(mTimeZoneChangedReceiver, filter);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().unregisterReceiver(mTimeZoneChangedReceiver);
    }
}
