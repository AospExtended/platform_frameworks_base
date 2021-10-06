/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.View;
import android.widget.ImageSwitcher;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import com.android.systemui.statusbar.StatusBarIconView;

import java.util.ArrayList;

public abstract class Ticker implements DarkReceiver {

    private Context mContext;
    private Handler mHandler = new Handler();
    private ArrayList<Segment> mSegments = new ArrayList();
    private TextPaint mPaint;
    private ImageSwitcher mIconSwitcher;
    private TextSwitcher mTextSwitcher;
    private float mIconScale;
    private int mIconTint =  0xffffffff;
    private int mTextColor = 0xffffffff;
    private int mTickerSegmentDelay = 3000;

    private MediaMetadata mShowingMediaMetadata;
    private String mShowingNotificationText;

    private ContrastColorUtil mNotificationColorUtil;

    public static boolean isGraphicOrEmoji(char c) {
        int gc = Character.getType(c);
        return     gc != Character.CONTROL
                && gc != Character.FORMAT
                && gc != Character.UNASSIGNED
                && gc != Character.LINE_SEPARATOR
                && gc != Character.PARAGRAPH_SEPARATOR
                && gc != Character.SPACE_SEPARATOR;
    }

    private Animation mAnimationIn;
    private Animation mAnimationOut;

    private final class Segment {
        StatusBarNotification notification;
        Drawable icon;
        CharSequence text;
        int current;
        int next;
        boolean first;

        StaticLayout getLayout(CharSequence substr) {
            int w = mTextSwitcher.getWidth() - mTextSwitcher.getPaddingLeft()
                    - mTextSwitcher.getPaddingRight();
            if (w > 0) {
                return new StaticLayout(substr, mPaint, w, Alignment.ALIGN_NORMAL, 1, 0, true);
            }
            return null;
        }

        CharSequence rtrim(CharSequence substr, int start, int end) {
            while (end > start && !isGraphicOrEmoji(substr.charAt(end-1))) {
                end--;
            }
            if (end > start) {
                return substr.subSequence(start, end);
            }
            return null;
        }

        /** returns null if there is no more text */
        CharSequence getText() {
            if (this.current > this.text.length()) {
                return null;
            }
            CharSequence substr = this.text.subSequence(this.current, this.text.length());
            StaticLayout l = getLayout(substr);
            if (l == null) {
                return null;
            }
            int lineCount = l.getLineCount();
            if (lineCount > 0) {
                int start = l.getLineStart(0);
                int end = l.getLineEnd(0);
                this.next = this.current + end;
                return rtrim(substr, start, end);
            } else {
                throw new RuntimeException("lineCount=" + lineCount + " current=" + current +
                        " text=" + text);
            }
        }

        /** returns null if there is no more text */
        CharSequence advance() {
            this.first = false;
            int index = this.next;
            final int len = this.text.length();
            while (index < len && !isGraphicOrEmoji(this.text.charAt(index))) {
                index++;
            }
            if (index >= len) {
                return null;
            }

            CharSequence substr = this.text.subSequence(index, this.text.length());
            StaticLayout l = getLayout(substr);
            if (l == null) {
                return null;
            }
            final int lineCount = l.getLineCount();
            int i;
            for (i=0; i<lineCount; i++) {
                int start = l.getLineStart(i);
                int end = l.getLineEnd(i);
                if (i == lineCount-1) {
                    this.next = len;
                } else {
                    this.next = index + l.getLineStart(i+1);
                }
                CharSequence result = rtrim(substr, start, end);
                if (result != null) {
                    this.current = index + start;
                    return result;
                }
            }
            this.current = len;
            return null;
        }

        Segment(StatusBarNotification n, Drawable icon, CharSequence text) {
            this.notification = n;
            this.icon = icon;
            this.text = text;
            int index = 0;
            final int len = text.length();
            while (index < len && !isGraphicOrEmoji(text.charAt(index))) {
                index++;
            }
            this.current = index;
            this.next = index;
            this.first = true;
        }
    };

    public Ticker(Context context, View tickerLayout, int animationMode, int tickDuration) {
        mContext = context;
        final Resources res = context.getResources();
        final int outerBounds = res.getDimensionPixelSize(R.dimen.status_bar_icon_size);
        final int imageBounds = res.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size);
        mIconScale = (float)imageBounds / (float)outerBounds;

        updateAnimation(animationMode);
        updateTickDuration(tickDuration);

        mNotificationColorUtil = ContrastColorUtil.getInstance(mContext);

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    public void updateAnimation(int animationMode) {
        if (animationMode == 1) {
            mAnimationIn = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.push_up_in);
            mAnimationOut = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.push_up_out);
        } else {
            mAnimationIn = new AlphaAnimation(0.0f, 1.0f);
            Interpolator interpolatorIn = AnimationUtils.loadInterpolator(mContext,
                    android.R.interpolator.decelerate_quad);
            mAnimationIn.setInterpolator(interpolatorIn);
            mAnimationIn.setDuration(350);

            mAnimationOut = new AlphaAnimation(1.0f, 0.0f);
            Interpolator interpolatorOut = AnimationUtils.loadInterpolator(mContext,
                    android.R.interpolator.accelerate_quad);
            mAnimationOut.setInterpolator(interpolatorOut);
            mAnimationOut.setDuration(350);
        }
        if (mTextSwitcher != null && mIconSwitcher != null) {
            setViewAnimations();
        }
    }

    public void updateTickDuration(int duration) {
        mTickerSegmentDelay = duration;
    }


    public void addEntry(StatusBarNotification n, boolean isMusic, MediaMetadata mediaMetaData, String notificationText) {
        int initialCount = mSegments.size();
        ContentResolver resolver = mContext.getContentResolver();

        if (isMusic) {
            CharSequence artist = mediaMetaData.getText(MediaMetadata.METADATA_KEY_ARTIST);
            CharSequence album = mediaMetaData.getText(MediaMetadata.METADATA_KEY_ALBUM);
            CharSequence title = mediaMetaData.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (artist != null && album != null && title != null) {
                if (mShowingMediaMetadata != null &&
                        artist.equals(mShowingMediaMetadata.getText(
                                MediaMetadata.METADATA_KEY_ARTIST)) &&
                        album.equals(mShowingMediaMetadata.getText(
                                MediaMetadata.METADATA_KEY_ALBUM)) &&
                        title.equals(mShowingMediaMetadata.getText(
                                MediaMetadata.METADATA_KEY_TITLE))) {
                    // Already shown
                    return;
                }
                mShowingMediaMetadata = mediaMetaData;
                n.getNotification().tickerText = artist.toString() + " - " + album.toString() + " - " + title.toString();
            } else if (notificationText != null) {
                if (mShowingNotificationText != null && notificationText.equals(mShowingNotificationText)) {
                    // Already shown
                    return;
                }
                mShowingNotificationText = notificationText;
                n.getNotification().tickerText = notificationText;
            } else {
                return;
            }
        }

        // If what's being displayed has the same text and icon, just drop it
        // (which will let the current one finish, this happens when apps do
        // a notification storm).
        if (initialCount > 0) {
            final Segment seg = mSegments.get(0);
            if (n.getPackageName().equals(seg.notification.getPackageName())
                    && n.getNotification().icon == seg.notification.getNotification().icon
                    && n.getNotification().iconLevel == seg.notification.getNotification().iconLevel
                    && charSequencesEqual(seg.notification.getNotification().tickerText,
                    n.getNotification().tickerText)) {
                return;
            }
        }

        final Drawable icon = StatusBarIconView.getIcon(mContext,
                new StatusBarIcon(n.getPackageName(), n.getUser(), n.getNotification().icon, n.getNotification().iconLevel, 0,
                        n.getNotification().tickerText));
        final CharSequence text = n.getNotification().tickerText;
        final Segment newSegment = new Segment(n, icon, text);

        // If there's already a notification schedule for this package and id, remove it.
        for (int i=0; i<mSegments.size(); i++) {
            Segment seg = mSegments.get(i);
            if (n.getId() == seg.notification.getId() && n.getPackageName().equals(seg.notification.getPackageName())) {
                // just update that one to use this new data instead
                mSegments.remove(i--); // restart iteration here
            }
        }

        mSegments.add(newSegment);

        if (initialCount == 0 && mSegments.size() > 0) {
            Segment seg = mSegments.get(0);
            seg.first = false;

            mIconSwitcher.setAnimateFirstView(false);
            mIconSwitcher.reset();
            setAppIconColor(seg.icon);

            mTextSwitcher.setAnimateFirstView(false);
            mTextSwitcher.reset();
            mTextSwitcher.setText(seg.getText());
            mTextSwitcher.setTextColor(mTextColor);

            tickerStarting();
            scheduleAdvance();
        }
    }

    private static boolean charSequencesEqual(CharSequence a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }

        int length = a.length();
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public void removeEntry(StatusBarNotification n) {
        for (int i=mSegments.size()-1; i>=0; i--) {
            Segment seg = mSegments.get(i);
            if (n.getId() == seg.notification.getId() && n.getPackageName().equals(seg.notification.getPackageName())) {
                mSegments.remove(i);
            }
        }
    }

    public void halt() {
        mHandler.removeCallbacks(mAdvanceTicker);
        mSegments.clear();
        tickerHalting();
    }

    public void resetShownMediaMetadata() {
        mShowingMediaMetadata = null;
        mShowingNotificationText = null;
    }

    public void setViews(TextSwitcher ts, ImageSwitcher is) {
        mTextSwitcher = ts;
        // Copy the paint style of one of the TextSwitchers children to use later for measuring
        TextView text = (TextView) mTextSwitcher.getChildAt(0);
        mPaint = text.getPaint();

        mIconSwitcher = is;
        mIconSwitcher.setScaleX(mIconScale);
        mIconSwitcher.setScaleY(mIconScale);

        setViewAnimations();
    }

    private void setViewAnimations() {
        mTextSwitcher.setInAnimation(mAnimationIn);
        mTextSwitcher.setOutAnimation(mAnimationOut);
        mIconSwitcher.setInAnimation(mAnimationIn);
        mIconSwitcher.setOutAnimation(mAnimationOut);
    }

    public void reflowText() {
        if (mSegments.size() > 0) {
            Segment seg = mSegments.get(0);
            CharSequence text = seg.getText();
            mTextSwitcher.setCurrentText(text);
            mTextSwitcher.setTextColor(mTextColor);
        }
    }

    private Runnable mAdvanceTicker = new Runnable() {
        public void run() {
            while (mSegments.size() > 0) {
                Segment seg = mSegments.get(0);

                if (seg.first) {
                    // this makes the icon slide in for the first one for a given
                    // notification even if there are two notifications with the
                    // same icon in a row
                    setAppIconColor(seg.icon);
                }
                CharSequence text = seg.advance();
                if (text == null) {
                    mSegments.remove(0);
                    continue;
                }
                mTextSwitcher.setText(text);
                mTextSwitcher.setTextColor(mTextColor);

                scheduleAdvance();
                break;
            }
            if (mSegments.size() == 0) {
                tickerDone();
            }
        }
    };

    private void scheduleAdvance() {
        mHandler.postDelayed(mAdvanceTicker, mTickerSegmentDelay);
    }

    public abstract void tickerStarting();
    public abstract void tickerDone();
    public abstract void tickerHalting();

    public void setTextColor(int color) {
        mTextColor = color;
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {}

    public void applyDarkIntensity(Rect area, View v, int tint) {
        mTextColor = DarkIconDispatcher.getTint(area, v, tint);
        mIconTint = mTextColor;
        if (mSegments.size() > 0) {
            Segment seg = mSegments.get(0);
            mTextSwitcher.setTextColor(mTextColor);
            mIconSwitcher.reset();
            setAppIconColor(seg.icon);
        }
    }

    private void setAppIconColor(Drawable icon) {
        boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(icon);
        mIconSwitcher.setImageDrawableTint(icon, mIconTint, isGrayscale);
    }
}
