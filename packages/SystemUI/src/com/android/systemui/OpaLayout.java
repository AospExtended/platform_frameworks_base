/*
 * Copyright (C) 2017-2018 Google Inc.
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
 * limitations under the License
 */

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;

import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.opa.DiamondAnimationRunnable;
import com.android.systemui.opa.OpaUtils;
import com.android.systemui.plugins.statusbar.phone.NavBarButtonProvider.ButtonInterface;
import com.android.systemui.R;
import com.android.systemui.shared.system.NavigationBarCompat;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.util.ArrayList;

public class OpaLayout extends FrameLayout implements ButtonInterface {

    private final Interpolator HOME_DISAPPEAR_INTERPOLATOR;
    private final ArrayList<View> mAnimatedViews;
    private int mAnimationState;
    private View mBlue;
    private View mBottom;
    private final ArraySet<Animator> mCurrentAnimators;
    private boolean mDelayTouchFeedback;
    private final Runnable mDiamondAnimation;
    private boolean mDiamondAnimationDelayed;
    private final Interpolator mDiamondInterpolator;
    private long mGestureAnimationSetDuration;
    private AnimatorSet mGestureAnimatorSet;
    private AnimatorSet mGestureLineSet;
    private int mGestureState;
    private View mGreen;
    private ImageView mHalo;
    private int mHaloDiameter;
    public KeyButtonView mHome;
    private boolean mIsPressed;
    private boolean mIsVertical;
    private View mLeft;
    private boolean mLongClicked;
    private boolean mOpaEnabled;
    private final OverviewProxyListener mOverviewProxyListener;
    private OverviewProxyService mOverviewProxyService;
    private View mRed;
    private Resources mResources;
    private final Runnable mRetract;
    private View mRight;
    private int mScrollTouchSlop;
    private long mStartTime;
    private View mTop;
    private int mTouchDownX;
    private int mTouchDownY;
    private ImageView mWhite;
    private ImageView mWhiteCutout;
    private boolean mWindowVisible;
    private View mYellow;
    private SettingsObserver mSettingsObserver;

    protected class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
           ContentResolver resolver = mContext.getContentResolver();
           resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.PIXEL_NAV_ANIMATION),
                  false, this, UserHandle.USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
           super.onChange(selfChange, uri);
           setOpaEnabled();
        }
    }

    private final Runnable mCheckLongPress;

    class RetractRunnable implements Runnable {
        RetractRunnable() {
        }

        public void run() {
            OpaLayout.this.cancelCurrentAnimation();
            OpaLayout.this.startRetractAnimation();
        }
    }

    class DiamondListener extends AnimatorListenerAdapter {
        DiamondListener() {
        }

        public void onAnimationCancel(Animator animation) {
            OpaLayout.this.mCurrentAnimators.clear();
        }

        public void onAnimationEnd(Animator animation) {
            OpaLayout.this.startLineAnimation();
        }
    }

    class RetractListener extends AnimatorListenerAdapter {
        RetractListener() {
        }

        public void onAnimationEnd(Animator animation) {
            OpaLayout.this.mCurrentAnimators.clear();
            OpaLayout.this.skipToStartingValue();
        }
    }

    class CollapseListener extends AnimatorListenerAdapter {
        CollapseListener() {
        }

        public void onAnimationEnd(Animator animation) {
            OpaLayout.this.mCurrentAnimators.clear();
            OpaLayout.this.skipToStartingValue();
        }
    }

    class LineListener extends AnimatorListenerAdapter {
        LineListener() {
        }

        public void onAnimationEnd(Animator animation) {
            OpaLayout.this.startCollapseAnimation();
        }

        public void onAnimationCancel(Animator animation) {
            OpaLayout.this.mCurrentAnimators.clear();
        }
    }

    class GestureListenerY extends AnimatorListenerAdapter {
        GestureListenerY() {
        }

        public void onAnimationEnd(Animator animation) {
            OpaLayout.this.startCollapseAnimation();
        }
    }

    class GestureListenerX extends AnimatorListenerAdapter {
        GestureListenerX() {
        }

        public void onAnimationEnd(Animator animation) {
            OpaLayout.this.startCollapseAnimation();
        }
    }

    class OverviewListener implements OverviewProxyListener {
        OverviewListener() {
        }

        public void onConnectionChanged(boolean isConnected) {
            OpaLayout.this.updateOpaLayout();
        }

        public void onInteractionFlagsChanged(int flags) {
            OpaLayout.this.updateOpaLayout();
        }
    }

    public OpaLayout(Context context) {
        this(context, null);
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        HOME_DISAPPEAR_INTERPOLATOR = new PathInterpolator(0.65f, 0.0f, 1.0f, 1.0f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0.0f, 0.2f, 1.0f);
        mCheckLongPress = new Runnable() {
            @Override
            public void run() {
                if (mIsPressed) {
                    mLongClicked = true;
                }
            }
        };
        mCurrentAnimators = new ArraySet();
        mAnimatedViews = new ArrayList();
        mAnimationState = 0;
        mGestureState = 0;
        mRetract = new RetractRunnable();
        mOverviewProxyListener = new OverviewListener();
        mDiamondAnimation = new DiamondAnimationRunnable(this);
        mScrollTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    protected void onFinishInflate() {
        super.onFinishInflate();
        mResources = getResources();
        mBlue = findViewById(R.id.blue);
        mRed = findViewById(R.id.red);
        mYellow = findViewById(R.id.yellow);
        mGreen = findViewById(R.id.green);
        mWhite = (ImageView) findViewById(R.id.white);
        mWhiteCutout = (ImageView) findViewById(R.id.white_cutout);
        mHalo = (ImageView) findViewById(R.id.halo);
        mHome = (KeyButtonView) findViewById(R.id.home_button);
        Context lightContext = new ContextThemeWrapper(getContext(), R.style.DualToneLightTheme);
        mHalo.setImageDrawable(KeyButtonDrawable.create(lightContext, lightContext.getDrawable(R.drawable.halo), new ContextThemeWrapper(getContext(), R.style.DualToneDarkTheme).getDrawable(R.drawable.halo), false));
        mHaloDiameter = mResources.getDimensionPixelSize(R.dimen.halo_diameter);
        Paint cutoutPaint = new Paint();
        cutoutPaint.setXfermode(new PorterDuffXfermode(Mode.DST_OUT));
        mWhiteCutout.setLayerType(2, cutoutPaint);
        mAnimatedViews.add(mBlue);
        mAnimatedViews.add(mRed);
        mAnimatedViews.add(mYellow);
        mAnimatedViews.add(mGreen);
        mAnimatedViews.add(mWhite);
        mAnimatedViews.add(mWhiteCutout);
        mAnimatedViews.add(mHalo);
        mOverviewProxyService = (OverviewProxyService) Dependency.get(OverviewProxyService.class);
    }

    public void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisible = visibility == 0;
        if (visibility == 0) {
            updateOpaLayout();
            return;
        }
        cancelCurrentAnimation();
        skipToStartingValue();
    }

    public static void startDiamondAnimation(OpaLayout opaLayout) {
        if (opaLayout.mCurrentAnimators.isEmpty()) {
            opaLayout.startDiamondAnimation();
        }
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!getOpaEnabled() || !ValueAnimator.areAnimatorsEnabled() || mGestureState != 0) {
            return false;
        }
        boolean exceededTouchSlopY = true;
        boolean isRetracting;
        switch (ev.getAction()) {
            case 0:
                mTouchDownX = (int) ev.getRawX();
                mTouchDownY = (int) ev.getRawY();
                isRetracting = false;
                if (!mCurrentAnimators.isEmpty()) {
                    if (mAnimationState != 2) {
                        return false;
                    }
                    endCurrentAnimation();
                    isRetracting = true;
                }
                mStartTime = SystemClock.elapsedRealtime();
                mLongClicked = false;
                mIsPressed = true;
                removeCallbacks(mDiamondAnimation);
                removeCallbacks(mRetract);
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, (long) ViewConfiguration.getLongPressTimeout());
                if (mDelayTouchFeedback && !isRetracting) {
                    mDiamondAnimationDelayed = true;
                    postDelayed(mDiamondAnimation, (long) ViewConfiguration.getTapTimeout());
                    break;
                }
                mDiamondAnimationDelayed = false;
                startDiamondAnimation();
                break;
            case 1:
            case 3:
                if (mDiamondAnimationDelayed) {
                    if (mIsPressed && !mLongClicked) {
                        postDelayed(mRetract, 200);
                    }
                } else if (mAnimationState == 1) {
                    long targetTime = 100 - (SystemClock.elapsedRealtime() - mStartTime);
                    removeCallbacks(mRetract);
                    postDelayed(mRetract, targetTime);
                    removeCallbacks(mDiamondAnimation);
                    removeCallbacks(mCheckLongPress);
                    return false;
                } else {
                    if (!mIsPressed || mLongClicked) {
                        exceededTouchSlopY = false;
                    }
                    if (exceededTouchSlopY) {
                        mRetract.run();
                    }
                }
                mIsPressed = false;
                break;
            case 2:
                int quickStepTouchSlopPx;
                int quickScrubTouchSlopPx;
                int abs = Math.abs(((int) ev.getRawX()) - mTouchDownX);
                if (mIsVertical) {
                    quickStepTouchSlopPx = NavigationBarCompat.getQuickStepTouchSlopPx();
                } else {
                    quickStepTouchSlopPx = NavigationBarCompat.getQuickScrubTouchSlopPx();
                }
                isRetracting = abs > quickStepTouchSlopPx;
                quickStepTouchSlopPx = Math.abs(((int) ev.getRawY()) - mTouchDownY);
                if (mIsVertical) {
                    quickScrubTouchSlopPx = NavigationBarCompat.getQuickScrubTouchSlopPx();
                } else {
                    quickScrubTouchSlopPx = NavigationBarCompat.getQuickStepTouchSlopPx();
                }
                if (quickStepTouchSlopPx <= quickScrubTouchSlopPx) {
                    exceededTouchSlopY = false;
                }
                if (isRetracting || exceededTouchSlopY) {
                    abortCurrentGesture();
                    break;
                }
        }
        return false;
    }

    public void setAccessibilityDelegate(AccessibilityDelegate delegate) {
        super.setAccessibilityDelegate(delegate);
        mHome.setAccessibilityDelegate(delegate);
    }

    public void setImageDrawable(Drawable drawable) {
        mWhite.setImageDrawable(drawable);
        mWhiteCutout.setImageDrawable(drawable);
    }

    public void abortCurrentGesture() {
        mHome.abortCurrentGesture();
        mIsPressed = false;
        mLongClicked = false;
        mDiamondAnimationDelayed = false;
        removeCallbacks(mDiamondAnimation);
        removeCallbacks(mCheckLongPress);
        if (mAnimationState == 3 || mAnimationState == 1) {
            mRetract.run();
        }
    }

    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateOpaLayout();
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mOverviewProxyService.addCallback(mOverviewProxyListener);
        updateOpaLayout();
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
    }

    private void startDiamondAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            setDotsVisible();
            mCurrentAnimators.addAll(getDiamondAnimatorSet());
            mAnimationState = 1;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startRetractAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll(getRetractAnimatorSet());
            mAnimationState = 2;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startLineAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll(getLineAnimatorSet());
            mAnimationState = 3;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startCollapseAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll(getCollapseAnimatorSet());
            mAnimationState = 3;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startAll(ArraySet<Animator> animators) {
        for (int i = animators.size() - 1; i >= 0; i--) {
            ((Animator) animators.valueAt(i)).start();
        }
    }

    private boolean allowAnimations() {
        return isAttachedToWindow() && mWindowVisible;
    }

    private ArraySet<Animator> getDiamondAnimatorSet() {
        ArraySet<Animator> animators = new ArraySet();
        animators.add(OpaUtils.getDeltaAnimatorY(mTop, mDiamondInterpolator, -OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation), 200));
        animators.add(OpaUtils.getScaleAnimatorX(mTop, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mTop, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getDeltaAnimatorY(mBottom, mDiamondInterpolator, OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation), 200));
        animators.add(OpaUtils.getScaleAnimatorX(mBottom, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mBottom, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getDeltaAnimatorX(mLeft, mDiamondInterpolator, -OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation), 200));
        animators.add(OpaUtils.getScaleAnimatorX(mLeft, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mLeft, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getDeltaAnimatorX(mRight, mDiamondInterpolator, OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation), 200));
        animators.add(OpaUtils.getScaleAnimatorX(mRight, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mRight, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorX(mWhite, 0.625f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mWhite, 0.625f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorX(mWhiteCutout, 0.625f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mWhiteCutout, 0.625f, 200, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorX(mHalo, 0.47619048f, 100, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mHalo, 0.47619048f, 100, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getAlphaAnimator(mHalo, 0.0f, 100, Interpolators.FAST_OUT_SLOW_IN));
        getLongestAnim(animators).addListener(new DiamondListener());
        return animators;
    }

    private ArraySet<Animator> getRetractAnimatorSet() {
        ArraySet<Animator> animators = new ArraySet();
        animators.add(OpaUtils.getTranslationAnimatorX(mRed, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getTranslationAnimatorY(mRed, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getScaleAnimatorX(mRed, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mRed, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getTranslationAnimatorX(mBlue, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getTranslationAnimatorY(mBlue, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getScaleAnimatorX(mBlue, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mBlue, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getTranslationAnimatorX(mGreen, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getTranslationAnimatorY(mGreen, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getScaleAnimatorX(mGreen, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mGreen, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getTranslationAnimatorX(mYellow, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getTranslationAnimatorY(mYellow, OpaUtils.INTERPOLATOR_40_OUT, 190));
        animators.add(OpaUtils.getScaleAnimatorX(mYellow, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mYellow, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorX(mWhite, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mWhite, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorX(mWhiteCutout, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mWhiteCutout, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorX(mHalo, 1.0f, 190, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getScaleAnimatorY(mHalo, 1.0f, 190, Interpolators.FAST_OUT_SLOW_IN));
        animators.add(OpaUtils.getAlphaAnimator(mHalo, 1.0f, 190, Interpolators.FAST_OUT_SLOW_IN));
        getLongestAnim(animators).addListener(new RetractListener());
        return animators;
    }

    private ArraySet<Animator> getCollapseAnimatorSet() {
        Animator translationAnimatorY;
        ArraySet<Animator> animators = new ArraySet();
        if (mIsVertical) {
            translationAnimatorY = OpaUtils.getTranslationAnimatorY(mRed, OpaUtils.INTERPOLATOR_40_OUT, 133);
        } else {
            translationAnimatorY = OpaUtils.getTranslationAnimatorX(mRed, OpaUtils.INTERPOLATOR_40_OUT, 133);
        }
        animators.add(translationAnimatorY);
        animators.add(OpaUtils.getScaleAnimatorX(mRed, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mRed, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        if (mIsVertical) {
            translationAnimatorY = OpaUtils.getTranslationAnimatorY(mBlue, OpaUtils.INTERPOLATOR_40_OUT, 150);
        } else {
            translationAnimatorY = OpaUtils.getTranslationAnimatorX(mBlue, OpaUtils.INTERPOLATOR_40_OUT, 150);
        }
        animators.add(translationAnimatorY);
        animators.add(OpaUtils.getScaleAnimatorX(mBlue, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mBlue, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        if (mIsVertical) {
            translationAnimatorY = OpaUtils.getTranslationAnimatorY(mYellow, OpaUtils.INTERPOLATOR_40_OUT, 133);
        } else {
            translationAnimatorY = OpaUtils.getTranslationAnimatorX(mYellow, OpaUtils.INTERPOLATOR_40_OUT, 133);
        }
        animators.add(translationAnimatorY);
        animators.add(OpaUtils.getScaleAnimatorX(mYellow, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mYellow, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        if (mIsVertical) {
            translationAnimatorY = OpaUtils.getTranslationAnimatorY(mGreen, OpaUtils.INTERPOLATOR_40_OUT, 150);
        } else {
            translationAnimatorY = OpaUtils.getTranslationAnimatorX(mGreen, OpaUtils.INTERPOLATOR_40_OUT, 150);
        }
        animators.add(translationAnimatorY);
        animators.add(OpaUtils.getScaleAnimatorX(mGreen, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        animators.add(OpaUtils.getScaleAnimatorY(mGreen, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        Animator homeScaleX = OpaUtils.getScaleAnimatorX(mWhite, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator homeScaleY = OpaUtils.getScaleAnimatorY(mWhite, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator homeCutoutScaleX = OpaUtils.getScaleAnimatorX(mWhiteCutout, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator homeCutoutScaleY = OpaUtils.getScaleAnimatorY(mWhiteCutout, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator haloScaleX = OpaUtils.getScaleAnimatorX(mHalo, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator haloScaleY = OpaUtils.getScaleAnimatorY(mHalo, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator haloAlpha = OpaUtils.getAlphaAnimator(mHalo, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        homeScaleX.setStartDelay(33);
        homeScaleY.setStartDelay(33);
        homeCutoutScaleX.setStartDelay(33);
        homeCutoutScaleY.setStartDelay(33);
        haloScaleX.setStartDelay(33);
        haloScaleY.setStartDelay(33);
        haloAlpha.setStartDelay(33);
        animators.add(homeScaleX);
        animators.add(homeScaleY);
        animators.add(homeCutoutScaleX);
        animators.add(homeCutoutScaleY);
        animators.add(haloScaleX);
        animators.add(haloScaleY);
        animators.add(haloAlpha);
        getLongestAnim(animators).addListener(new CollapseListener());
        return animators;
    }

    private ArraySet<Animator> getLineAnimatorSet() {
        ArraySet<Animator> animators = new ArraySet();
        if (mIsVertical) {
            animators.add(OpaUtils.getDeltaAnimatorY(mRed, Interpolators.FAST_OUT_SLOW_IN, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), 225));
            animators.add(OpaUtils.getDeltaAnimatorX(mRed, Interpolators.FAST_OUT_SLOW_IN, OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation), 133));
            animators.add(OpaUtils.getDeltaAnimatorY(mBlue, Interpolators.FAST_OUT_SLOW_IN, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), 225));
            animators.add(OpaUtils.getDeltaAnimatorY(mYellow, Interpolators.FAST_OUT_SLOW_IN, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), 225));
            animators.add(OpaUtils.getDeltaAnimatorX(mYellow, Interpolators.FAST_OUT_SLOW_IN, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation), 133));
            animators.add(OpaUtils.getDeltaAnimatorY(mGreen, Interpolators.FAST_OUT_SLOW_IN, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), 225));
        } else {
            animators.add(OpaUtils.getDeltaAnimatorX(mRed, Interpolators.FAST_OUT_SLOW_IN, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), 225));
            animators.add(OpaUtils.getDeltaAnimatorY(mRed, Interpolators.FAST_OUT_SLOW_IN, OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation), 133));
            animators.add(OpaUtils.getDeltaAnimatorX(mBlue, Interpolators.FAST_OUT_SLOW_IN, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), 225));
            animators.add(OpaUtils.getDeltaAnimatorX(mYellow, Interpolators.FAST_OUT_SLOW_IN, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), 225));
            animators.add(OpaUtils.getDeltaAnimatorY(mYellow, Interpolators.FAST_OUT_SLOW_IN, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation), 133));
            animators.add(OpaUtils.getDeltaAnimatorX(mGreen, Interpolators.FAST_OUT_SLOW_IN, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), 225));
        }
        animators.add(OpaUtils.getScaleAnimatorX(mWhite, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        animators.add(OpaUtils.getScaleAnimatorY(mWhite, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        animators.add(OpaUtils.getScaleAnimatorX(mWhiteCutout, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        animators.add(OpaUtils.getScaleAnimatorY(mWhiteCutout, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        animators.add(OpaUtils.getScaleAnimatorX(mHalo, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        animators.add(OpaUtils.getScaleAnimatorY(mHalo, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        getLongestAnim(animators).addListener(new LineListener());
        return animators;
    }

    public boolean getOpaEnabled() {
	final boolean opaToggle = Settings.System.getIntForUser(this.getContext().getContentResolver(),
            Settings.System.PIXEL_NAV_ANIMATION, 1, UserHandle.USER_CURRENT) == 1;
	if (opaToggle) return true;
	return false;
    }

    public void setOpaEnabled() {
	mOpaEnabled = getOpaEnabled();
        updateOpaLayout();
    }

    public void updateOpaLayout() {
        boolean showQuickStepIcons = mOverviewProxyService.shouldShowSwipeUpUI();
        int visibility = 0;
        boolean haloShown = getOpaEnabled() && !showQuickStepIcons;
        ImageView imageView = mHalo;
        if (!haloShown) {
            visibility = 4;
        }
        imageView.setVisibility(visibility);
        LayoutParams lp = (LayoutParams) mWhite.getLayoutParams();
        int haloDiameter = -1;
        lp.width = showQuickStepIcons ? -1 : mHaloDiameter;
        if (!showQuickStepIcons) {
            haloDiameter = mHaloDiameter;
        }
        lp.height = haloDiameter;
        mWhite.setLayoutParams(lp);
        mWhiteCutout.setLayoutParams(lp);
    }

    private void cancelCurrentAnimation() {
        if (!mCurrentAnimators.isEmpty()) {
            for (int i = mCurrentAnimators.size() - 1; i >= 0; i--) {
                Animator a = (Animator) mCurrentAnimators.valueAt(i);
                a.removeAllListeners();
                a.cancel();
            }
            mCurrentAnimators.clear();
            mAnimationState = 0;
        }
        if (mGestureAnimatorSet != null) {
            mGestureAnimatorSet.cancel();
            mGestureState = 0;
        }
    }

    private void endCurrentAnimation() {
        if (!mCurrentAnimators.isEmpty()) {
            for (int i = mCurrentAnimators.size() - 1; i >= 0; i--) {
                Animator a = (Animator) mCurrentAnimators.valueAt(i);
                a.removeAllListeners();
                a.end();
            }
            mCurrentAnimators.clear();
        }
        mAnimationState = 0;
    }

    private Animator getLongestAnim(ArraySet<Animator> animators) {
        long longestDuration = Long.MIN_VALUE;
        Animator longestAnim = null;
        for (int i = animators.size() - 1; i >= 0; i--) {
            Animator a = (Animator) animators.valueAt(i);
            if (a.getTotalDuration() > longestDuration) {
                longestAnim = a;
                longestDuration = a.getTotalDuration();
            }
        }
        return longestAnim;
    }

    private void setDotsVisible() {
        int size = mAnimatedViews.size();
        for (int i = 0; i < size; i++) {
            ((View) mAnimatedViews.get(i)).setAlpha(1.0f);
        }
    }

    private void skipToStartingValue() {
        int size = mAnimatedViews.size();
        for (int i = 0; i < size; i++) {
            View v = (View) mAnimatedViews.get(i);
            v.setScaleY(1.0f);
            v.setScaleX(1.0f);
            v.setTranslationY(0.0f);
            v.setTranslationX(0.0f);
            v.setAlpha(0.0f);
        }
        mHalo.setAlpha(1.0f);
        mWhite.setAlpha(1.0f);
        mWhiteCutout.setAlpha(1.0f);
        mAnimationState = 0;
        mGestureState = 0;
    }

    public void setVertical(boolean vertical) {
        if (!(mIsVertical == vertical || mGestureAnimatorSet == null)) {
            mGestureAnimatorSet.cancel();
            mGestureAnimatorSet = null;
            skipToStartingValue();
        }
        mIsVertical = vertical;
        mHome.setVertical(vertical);
        if (mIsVertical) {
            mTop = mGreen;
            mBottom = mBlue;
            mRight = mYellow;
            mLeft = mRed;
            return;
        }
        mTop = mRed;
        mBottom = mYellow;
        mLeft = mBlue;
        mRight = mGreen;
    }

    public void setOnLongClickListener(View.OnLongClickListener l) {
        mHome.setOnLongClickListener(l);
    }

    public void setDarkIntensity(float intensity) {
        if (mWhite.getDrawable() instanceof KeyButtonDrawable) {
            ((KeyButtonDrawable) mWhite.getDrawable()).setDarkIntensity(intensity);
        }
        ((KeyButtonDrawable) mHalo.getDrawable()).setDarkIntensity(intensity);
        mWhite.invalidate();
        mHalo.invalidate();
        mHome.setDarkIntensity(intensity);
    }

    public void setDelayTouchFeedback(boolean shouldDelay) {
        mHome.setDelayTouchFeedback(shouldDelay);
        mDelayTouchFeedback = shouldDelay;
    }

    public void onRelease() {
        if (mAnimationState == 0 && mGestureState == 1) {
            if (mGestureAnimatorSet != null) {
                mGestureAnimatorSet.cancel();
            }
            mGestureState = 0;
            startRetractAnimation();
        }
    }

    public void onProgress(float progress, int stage) {
        if (mGestureState != 2 && allowAnimations()) {
            if (mAnimationState == 2) {
                endCurrentAnimation();
            }
            if (mAnimationState == 0) {
                if (mGestureAnimatorSet == null) {
                    mGestureAnimatorSet = getGestureAnimatorSet();
                    mGestureAnimationSetDuration = mGestureAnimatorSet.getTotalDuration();
                }
                mGestureAnimatorSet.setCurrentPlayTime((long) (((float) (mGestureAnimationSetDuration - 1)) * progress));
                if (progress == 0.0f) {
                    mGestureState = 0;
                } else {
                    mGestureState = 1;
                }
            }
        }
    }

    private AnimatorSet getGestureAnimatorSet() {
        if (mGestureLineSet != null) {
            mGestureLineSet.removeAllListeners();
            mGestureLineSet.cancel();
            return mGestureLineSet;
        }
        mGestureLineSet = new AnimatorSet();
        ObjectAnimator homeAnimator = OpaUtils.getScaleObjectAnimator(mWhite, 0.0f, 100, OpaUtils.INTERPOLATOR_40_OUT);
        ObjectAnimator homeCutoutAnimator = OpaUtils.getScaleObjectAnimator(mWhiteCutout, 0.0f, 100, OpaUtils.INTERPOLATOR_40_OUT);
        ObjectAnimator haloAnimator = OpaUtils.getScaleObjectAnimator(mHalo, 0.0f, 100, OpaUtils.INTERPOLATOR_40_OUT);
        homeAnimator.setStartDelay(50);
        homeCutoutAnimator.setStartDelay(50);
        mGestureLineSet.play(homeAnimator).with(homeCutoutAnimator).with(haloAnimator);
        mGestureLineSet.play(OpaUtils.getScaleObjectAnimator(mTop, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN)).with(homeAnimator).with(OpaUtils.getAlphaObjectAnimator(mRed, 1.0f, 50, 130, Interpolators.LINEAR)).with(OpaUtils.getAlphaObjectAnimator(mYellow, 1.0f, 50, 130, Interpolators.LINEAR)).with(OpaUtils.getAlphaObjectAnimator(mBlue, 1.0f, 50, 113, Interpolators.LINEAR)).with(OpaUtils.getAlphaObjectAnimator(mGreen, 1.0f, 50, 113, Interpolators.LINEAR)).with(OpaUtils.getScaleObjectAnimator(mBottom, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN)).with(OpaUtils.getScaleObjectAnimator(mLeft, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN)).with(OpaUtils.getScaleObjectAnimator(mRight, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        Animator redAnimator;
        if (mIsVertical) {
            redAnimator = OpaUtils.getTranslationObjectAnimatorY(mRed, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mRed.getY() + OpaUtils.getDeltaDiamondPositionLeftY(), 350);
            redAnimator.addListener(new GestureListenerY());
            mGestureLineSet.play(redAnimator).with(haloAnimator).with(OpaUtils.getTranslationObjectAnimatorY(mBlue, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mBlue.getY() + OpaUtils.getDeltaDiamondPositionBottomY(mResources), 350)).with(OpaUtils.getTranslationObjectAnimatorY(mYellow, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mYellow.getY() + OpaUtils.getDeltaDiamondPositionRightY(), 350)).with(OpaUtils.getTranslationObjectAnimatorY(mGreen, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mGreen.getY() + OpaUtils.getDeltaDiamondPositionTopY(mResources), 350));
        } else {
            redAnimator = OpaUtils.getTranslationObjectAnimatorX(mRed, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mRed.getX() + OpaUtils.getDeltaDiamondPositionTopX(), 350);
            redAnimator.addListener(new GestureListenerX());
            mGestureLineSet.play(redAnimator).with(homeAnimator).with(OpaUtils.getTranslationObjectAnimatorX(mBlue, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mBlue.getX() + OpaUtils.getDeltaDiamondPositionLeftX(mResources), 350)).with(OpaUtils.getTranslationObjectAnimatorX(mYellow, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mYellow.getX() + OpaUtils.getDeltaDiamondPositionBottomX(), 350)).with(OpaUtils.getTranslationObjectAnimatorX(mGreen, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mGreen.getX() + OpaUtils.getDeltaDiamondPositionRightX(mResources), 350));
        }
        return mGestureLineSet;
    }
}
