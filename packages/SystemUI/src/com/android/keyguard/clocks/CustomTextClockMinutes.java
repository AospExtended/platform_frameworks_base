package com.android.keyguard.clocks;

import android.content.Context;
import android.util.AttributeSet;

import com.android.systemui.R;

public class CustomTextClockMinutes extends CustomTextClock {

    public CustomTextClockMinutes(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected String getTimeString(int hour, int minute) {
        return getResources().getStringArray(R.array.text_clock_minutes_array)[minute];
    }
}
