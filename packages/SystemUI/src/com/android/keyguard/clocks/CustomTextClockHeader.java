package com.android.keyguard.clocks;

import android.content.Context;
import android.util.AttributeSet;

import com.android.systemui.R;

public class CustomTextClockHeader extends CustomTextClock {

    public CustomTextClockHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected String getTimeString(int hour, int minute) {
        if(hour < 2)
            return getResources().getString(R.string.text_clock_header_singular);
        else
            return getResources().getString(R.string.text_clock_header_plural);
    }
}
