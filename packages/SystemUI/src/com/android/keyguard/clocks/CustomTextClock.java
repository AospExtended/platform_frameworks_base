package com.android.keyguard.clocks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.text.format.DateUtils;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.TextView;
import android.provider.Settings;

import java.util.TimeZone;

import com.android.systemui.R;

public class CustomTextClock extends TextView {

    private final String[] mTensStrings = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty" };
    private final String[] mUnitsStrings = { "Clock", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
            "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen",
            "Nineteen" };
    private final String[] mTensStringsHour = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty" };
    private final String[] mUnitsStringsHour = { "Twelve", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
            "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen",
            "Nineteen" };

    private Time mCalendar;

    private boolean mAttached;

    private int mHandType;

    private boolean m24hrClock;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = new Time(TimeZone.getTimeZone(tz).getID());
            }

            onTimeChanged();

            invalidate();
        }
    };

    public CustomTextClock(Context context) {
        this(context, null);
    }

    public CustomTextClock(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CustomTextClock);
        mHandType = a.getInteger(R.styleable.CustomTextClock_HandType, 2);
        a.recycle();
        mCalendar = new Time();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

            // OK, this is gross but needed. This class is supported by the
            // remote views machanism and as a part of that the remote views
            // can be inflated by a context for another user without the app
            // having interact users permission - just for loading resources.
            // For example, when adding widgets from a user profile to the
            // home screen. Therefore, we register the receiver as the current
            // user not the one the context is for.
            getContext().registerReceiverAsUser(mIntentReceiver, android.os.Process.myUserHandle(), filter, null,
                    getHandler());
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver
        // always runs
        // in the main thread, therefore the receiver can't run before this method
        // returns.

        // The time zone may have changed while the receiver wasn't registered, so
        // update the Time
        mCalendar = new Time();

        // Make sure we update to the current time
        onTimeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private void onTimeChanged() {
        mCalendar.setToNow();
        m24hrClock = DateFormat.is24HourFormat(getContext());

        int hour = mCalendar.hour;
        int minute = mCalendar.minute;

        if (!m24hrClock) {
            if (hour > 12) {
                hour = hour - 12;
            }
        }

        switch (mHandType) {
        case 0:
            setText(getIntStringHour(hour));
            break;
        case 1:
            setText(getIntStringMin(minute));
            break;
        default:
            break;
        }

        updateContentDescription(mCalendar, getContext());
    }

    private void updateContentDescription(Time time, Context mContext) {
        final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR;
        String contentDescription = DateUtils.formatDateTime(mContext, time.toMillis(false), flags);
        setContentDescription(contentDescription);
    }

    private String getIntStringHour(int num) {
        int tens, units;
        String NumString = "";
        if (num >= 20) {
            units = num % 10;
            tens = num / 10;
            if (units == 0) {
                NumString = mTensStringsHour[tens];
            } else {
                NumString = mTensStringsHour[tens] + " " + mUnitsStringsHour[units];
            }
        } else if (num < 20) {
            NumString = mUnitsStringsHour[num];
        }

        return NumString;
    }

    private String getIntStringMin(int num) {
        int tens, units;
        String NumString = "";
        if (num >= 20) {
            units = num % 10;
            tens = num / 10;
            if (units == 0) {
                NumString = mTensStrings[tens];
            } else {
                NumString = mTensStrings[tens] + " " + mUnitsStrings[units];
            }
        } else if (num < 10) {
            NumString = "O\'" + mUnitsStrings[num];
        } else if (num >= 10 && num < 20) {
            NumString = mUnitsStrings[num];
        }

        return NumString;
    }

}
