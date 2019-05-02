package com.android.keyguard.clocks;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.support.v7.graphics.Palette;
import android.util.AttributeSet;

import com.android.systemui.R;

import java.lang.IllegalStateException;
import java.lang.NullPointerException;

public class CustomTextClockHeader extends CustomTextClock {

    private Context mContext;

    public CustomTextClockHeader(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
    }

    @Override
    protected String getTimeString(int hour, int minute) {
        if(hour < 2)
            return getResources().getString(R.string.text_clock_header_singular);
        else
            return getResources().getString(R.string.text_clock_header_plural);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        setTextColorFromWallpaper();
    }

    private void setTextColorFromWallpaper() {
        Bitmap mBitmap;
        //Get wallpaper as bitmap
        WallpaperManager manager = WallpaperManager.getInstance(mContext);
        ParcelFileDescriptor pfd = manager.getWallpaperFile(WallpaperManager.FLAG_LOCK);

        //Sometimes lock wallpaper maybe null as getWallpaperFile doesnt return builtin wallpaper
        if (pfd == null)
            pfd = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);

        try {
            if (pfd != null) {
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

    private Bitmap drawEmpty() {
        Bitmap convertedBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(convertedBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.YELLOW);
        canvas.drawPaint(paint);
        return convertedBitmap;
    }
}
