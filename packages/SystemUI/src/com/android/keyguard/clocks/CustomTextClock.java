package com.android.keyguard.clocks;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.support.v7.graphics.Palette;
import android.text.format.DateUtils;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.TextView;

import java.util.TimeZone;

import com.android.systemui.R;

import java.lang.IllegalStateException;
import java.lang.NullPointerException;

public class CustomTextClock extends TextView {

    private final String[] TensString = {"", "", "Twenty","Thirty","Forty", "Fifty", "Sixty"};
    private final String[] UnitsString = {"Clock", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen" };
    private final String[] TensStringH = {"", "", "Twenty","Thirty","Forty", "Fifty", "Sixty"};
    private final String[] UnitsStringH = {"Twelve", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen" };

    private Time mCalendar;

    private boolean mAttached;

    private int handType;
    private Context mContext;

    private boolean h24;

    public CustomTextClock(Context context) {
        this(context, null);
    }

    public CustomTextClock(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.CustomTextClock);

        handType = a.getInteger(R.styleable.CustomTextClock_HandType, 2);

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
            // For exmaple, when adding widgets from a user profile to the
            // home screen. Therefore, we register the receiver as the current
            // user not the one the context is for.
            getContext().registerReceiverAsUser(mIntentReceiver,
                    android.os.Process.myUserHandle(), filter, null, getHandler());
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (handType == 2) {
            Bitmap mBitmap;
            //Get wallpaper as bitmap
            WallpaperManager manager = WallpaperManager.getInstance(mContext);
            ParcelFileDescriptor pfd = manager.getWallpaperFile(WallpaperManager.FLAG_LOCK);

            //Sometimes lock wallpaper maybe null as getWallpaperFile doesnt return builtin wallpaper
            if (pfd == null)
                pfd = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            try {
                if (pfd != null)
                {
                    mBitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                } else {
                    //Incase both cases return null wallpaper, generate a yellow bitmap
                    mBitmap = drawEmpty();
                }
   		Palette palette = Palette.generate(mBitmap);

                //For monochrome and single color bitmaps, the value returned is 0
                if (Color.valueOf(palette.getLightVibrantColor(0x000000)).toArgb() == 0) {
                    //So get bodycolor on dominant color instead as a hacky workaround
                    setTextColor(palette.getDominantSwatch().getBodyTextColor());
                //On Black Wallpapers set color to White
                } else if(String.format("#%06X", (0xFFFFFF & (palette.getLightVibrantColor(0x000000)))) == "#000000") {
                    setTextColor(Color.WHITE);
                } else {
                    setTextColor((Color.valueOf(palette.getLightVibrantColor(0xff000000))).toArgb());
                }

              //Just a fallback, although I doubt this case will ever come
            } catch (NullPointerException e) {
                setTextColor(Color.WHITE);
            }
        }
    }

    private void onTimeChanged() {
        mCalendar.setToNow();
        h24 = DateFormat.is24HourFormat(getContext());

        int hour = mCalendar.hour;
        int minute = mCalendar.minute;

        Log.d("CustomTextClock", ""+h24);

        if (!h24) {
            if (hour > 12) {
                hour = hour - 12;
            }
        }

        switch(handType){
            case 0:
                if (hour == 12 && minute == 0) {
                setText("High");
                } else {
                setText(getIntStringHour(hour));
                }
                break;
            case 1:
                if (hour == 12 && minute == 0) {
                setText("Noon");
                } else {
                setText(getIntStringMin(minute));
                }
                break;
            default:
                break;
        }

        updateContentDescription(mCalendar, getContext());
    }

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

    private void updateContentDescription(Time time, Context mContext) {
        final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR;
        String contentDescription = DateUtils.formatDateTime(mContext,
                time.toMillis(false), flags);
        setContentDescription(contentDescription);
    }

    private String getIntStringHour (int num) {
        int tens, units;
        String NumString = "";
        if(num >= 20) {
            units = num % 10 ;
            tens =  num / 10;
            if ( units == 0 ) {
                NumString = TensStringH[tens];
            } else {
                NumString = TensStringH[tens]+" "+UnitsStringH[units];
            }
        } else if (num < 20 ) {
            NumString = UnitsStringH[num];
        }

        return NumString;
    }

    private String getIntStringMin (int num) {
        int tens, units;
        String NumString = "";
        if(num >= 20) {
            units = num % 10 ;
            tens =  num / 10;
            if ( units == 0 ) {
                NumString = TensString[tens];
            } else {
                NumString = TensString[tens]+" "+UnitsString[units];
            }
        } else if (num < 10 ) {
            NumString = "O\'"+UnitsString[num];
        } else if (num >= 10 && num < 20) {
            NumString = UnitsString[num];
        }

        return NumString;
    }


    private Bitmap drawEmpty() {
        Bitmap convertedBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(convertedBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.YELLOW);
        canvas.drawPaint(paint);
        return convertedBitmap;
    }

}

