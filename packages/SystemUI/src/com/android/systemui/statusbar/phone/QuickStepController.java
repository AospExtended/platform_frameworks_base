/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static com.android.systemui.Interpolators.ALPHA_IN;
import static com.android.systemui.Interpolators.ALPHA_OUT;
import static com.android.systemui.OverviewProxyService.DEBUG_OVERVIEW_PROXY;
import static com.android.systemui.OverviewProxyService.TAG_OPS;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_DEAD_ZONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_ROTATION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.RemoteException;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Slog;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.support.annotation.DimenRes;
import com.android.systemui.Dependency;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.system.NavigationBarCompat;
import com.android.internal.util.aospextended.AEXUtils;

/**
 * Class to detect gestures on the navigation bar and implement quick scrub.
 */
public class QuickStepController implements GestureHelper {

    private static final String TAG = "QuickStepController";
    private static final int ANIM_IN_DURATION_MS = 150;
    private static final int ANIM_OUT_DURATION_MS = 134;
    private static final float TRACK_SCALE = 0.95f;
    private static final float GRADIENT_WIDTH = .75f;

    private NavigationBarView mNavigationBarView;

    private boolean mQuickScrubActive;
    private boolean mAllowGestureDetection;
    private boolean mQuickStepStarted;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDragPositive;
    private boolean mIsVertical;
    private boolean mIsRTL;
    private float mTrackAlpha;
    private float mTrackScale = TRACK_SCALE;
    private float mDarkIntensity;
    private RadialGradient mHighlight;
    private float mHighlightCenter;
    private AnimatorSet mTrackAnimator;
    private ButtonDispatcher mHitTarget;
    private View mCurrentNavigationBarView;

    private boolean mBackActionScheduled;
    private boolean isDoubleTapPending;
    private boolean wasConsumed;
    private static final int sDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout() - 100;
    private static final int DOUBLETAPSLOP = ViewConfiguration.getDoubleTapSlop();
    private static final int sDoubleTapSquare = DOUBLETAPSLOP * DOUBLETAPSLOP;
    private int mPreviousUpEventX = 0;
    private int mPreviousUpEventY = 0;
    private static final int sLongPressTimeout = ViewConfiguration.getLongPressTimeout();
    private boolean mLongPressing;
    private boolean mLongPressWasTriggered;
    private boolean mIsKeyboardShowing;

    private final Handler mHandler = new Handler();
    private final Rect mTrackRect = new Rect();
    private final OverviewProxyService mOverviewEventSender;
    private final int mTrackThickness;
    private final int mTrackEndPadding;
    private final Context mContext;
    private final Matrix mTransformGlobalMatrix = new Matrix();
    private final Matrix mTransformLocalMatrix = new Matrix();
    private final Paint mTrackPaint = new Paint();

    private final FloatProperty<QuickStepController> mTrackAlphaProperty =
            new FloatProperty<QuickStepController>("TrackAlpha") {
        @Override
        public void setValue(QuickStepController controller, float alpha) {
            mTrackAlpha = alpha;
            mNavigationBarView.invalidate();
        }

        @Override
        public Float get(QuickStepController controller) {
            return mTrackAlpha;
        }
    };

    private final FloatProperty<QuickStepController> mTrackScaleProperty =
            new FloatProperty<QuickStepController>("TrackScale") {
        @Override
        public void setValue(QuickStepController controller, float scale) {
            mTrackScale = scale;
            mNavigationBarView.invalidate();
        }

        @Override
        public Float get(QuickStepController controller) {
            return mTrackScale;
        }
    };

    private final FloatProperty<QuickStepController> mNavBarAlphaProperty =
            new FloatProperty<QuickStepController>("NavBarAlpha") {
        @Override
        public void setValue(QuickStepController controller, float alpha) {
            if (mCurrentNavigationBarView != null) {
                mCurrentNavigationBarView.setAlpha(alpha);
            }
        }

        @Override
        public Float get(QuickStepController controller) {
            if (mCurrentNavigationBarView != null) {
                return mCurrentNavigationBarView.getAlpha();
            }
            return 1f;
        }
    };

    private AnimatorListenerAdapter mQuickScrubEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            resetQuickScrub();
        }
    };

    public QuickStepController(Context context) {
        final Resources res = context.getResources();
        mContext = context;
        mOverviewEventSender = Dependency.get(OverviewProxyService.class);
        mTrackThickness = res.getDimensionPixelSize(R.dimen.nav_quick_scrub_track_thickness);
        mTrackEndPadding = res.getDimensionPixelSize(R.dimen.nav_quick_scrub_track_edge_padding);
        mTrackPaint.setAntiAlias(true);
        mTrackPaint.setDither(true);
    }

    public void setComponents(NavigationBarView navigationBarView) {
        mNavigationBarView = navigationBarView;
    }

    /**
     * @return true if we want to intercept touch events for quick scrub and prevent proxying the
     *         event to the overview service.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return handleTouchEvent(event);
    }

    /**
     * @return true if we want to handle touch events for quick scrub or if down event (that will
     *         get consumed and ignored). No events will be proxied to the overview service.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The same down event was just sent on intercept and therefore can be ignored here
        final boolean ignoreProxyDownEvent = event.getAction() == MotionEvent.ACTION_DOWN
                && mOverviewEventSender.getProxy() != null;
        return ignoreProxyDownEvent || handleTouchEvent(event);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        final boolean deadZoneConsumed =
                mNavigationBarView.getDownHitTarget() == HIT_TARGET_DEAD_ZONE;
        if (mOverviewEventSender.getProxy() == null || (!mNavigationBarView.isQuickScrubEnabled()
                && !mNavigationBarView.isQuickStepSwipeUpEnabled())) {
            return deadZoneConsumed;
        }
        mNavigationBarView.requestUnbufferedDispatch(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int x = (int) event.getX();
                int y = (int) event.getY();

                // End any existing quickscrub animations before starting the new transition
                if (mTrackAnimator != null) {
                    mTrackAnimator.end();
                    mTrackAnimator = null;
                }

                mCurrentNavigationBarView = mNavigationBarView.getCurrentView();
                mHitTarget = mNavigationBarView.getButtonAtPosition(x, y);
                if (mHitTarget != null) {
                    // Pre-emptively delay the touch feedback for the button that we just touched
                    mHitTarget.setDelayTouchFeedback(true);
                }
                mTouchDownX = x;
                mTouchDownY = y;
                mTransformGlobalMatrix.set(Matrix.IDENTITY_MATRIX);
                mTransformLocalMatrix.set(Matrix.IDENTITY_MATRIX);
                mNavigationBarView.transformMatrixToGlobal(mTransformGlobalMatrix);
                mNavigationBarView.transformMatrixToLocal(mTransformLocalMatrix);
                mQuickStepStarted = false;
                mBackActionScheduled = false;
                mAllowGestureDetection = true;

                // don't check double tap or navbar home action or keyboard cursors action
                // if full gesture mode or dt2s are disabled  or if we tap on the home or rotation button
                if (!mNavigationBarView.isFullGestureMode()
                        || mNavigationBarView.getDownHitTarget() == HIT_TARGET_HOME
                        || mNavigationBarView.getDownHitTarget() == HIT_TARGET_ROTATION) {
                        wasConsumed = true;
                        break;
                }

                mLongPressWasTriggered = false;
                if (mIsKeyboardShowing && !isDoubleTapPending) {
                    boolean isRightAreaTouch = mIsVertical ? (mTouchDownY < mTrackRect.height() / 2)
                            : (mTouchDownX > mTrackRect.width() / 2);
                    mLongPressAction.setIsRight(isRightAreaTouch);
                    mHandler.postDelayed(mLongPressAction, sLongPressTimeout);
                    mLongPressing = true;
                }

                if (mNavigationBarView.isDt2s() && isDoubleTapPending) {
                    // this is the 2nd tap, so let's trigger the double tap action
                    isDoubleTapPending = false;
                    mLongPressing = false;
                    wasConsumed = true;
                    mHandler.removeCallbacksAndMessages(null);
                    int deltaX = (int) mPreviousUpEventX - (int) event.getX();
                    int deltaY = (int) mPreviousUpEventY - (int) event.getY();
                    boolean isDoubleTapReally = deltaX * deltaX + deltaY * deltaY < sDoubleTapSquare;
                    if (isDoubleTapReally) {
                        mNavigationBarView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        AEXUtils.switchScreenOff(mContext);
                    }
                } else {
                    // this is the first tap, let's go further and schedule a
                    // mDoubleTapCancelTimeout call in the action up event so after the set time
                    // if we don't tap again the double tap check will be removed
                    wasConsumed = false;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mQuickStepStarted || !mAllowGestureDetection){
                    break;
                }
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);

                boolean exceededScrubTouchSlop, exceededSwipeUpTouchSlop;
                int pos, touchDown, offset, trackSize;

                if (mIsVertical) {
                    exceededScrubTouchSlop =
                            yDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && yDiff > xDiff;
                    exceededSwipeUpTouchSlop =
                            xDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && xDiff > yDiff;
                    pos = y;
                    touchDown = mTouchDownY;
                    offset = pos - mTrackRect.top;
                    trackSize = mTrackRect.height();
                } else {
                    exceededScrubTouchSlop =
                            xDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && xDiff > yDiff;
                    exceededSwipeUpTouchSlop =
                            yDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && yDiff > xDiff;
                    pos = x;
                    touchDown = mTouchDownX;
                    offset = pos - mTrackRect.left;
                    trackSize = mTrackRect.width();
                }

                if (exceededScrubTouchSlop || exceededSwipeUpTouchSlop) {
                    // consider this a move, not a tap, no more need to check double tap later
                    wasConsumed = true;
                    isDoubleTapPending = false;
                    mHandler.removeCallbacks(mDoubleTapCancelTimeout);
                    mHandler.removeCallbacks(mLongPressAction);
                    mLongPressing = false;
                    mLongPressWasTriggered = false;
                }

                // Decide to start quickstep if dragging away from the navigation bar, otherwise in
                // the parallel direction, decide to start quickscrub. Only one may run.
                if (!mQuickScrubActive && exceededSwipeUpTouchSlop) {
                    if (mNavigationBarView.isQuickStepSwipeUpEnabled()) {
                        startQuickStep(event);
                    }
                    break;
                }

                if (!mDragPositive) {
                    offset -= mIsVertical ? mTrackRect.height() : mTrackRect.width();
                }

                final boolean allowBackAction = !mNavigationBarView.isFullGestureMode() ? false
                        : (!mDragPositive ? offset < 0 && pos > touchDown
                        : offset >= 0 && pos < touchDown);
                // if quickscrub is active, don't trigger the back action but allow quickscrub drag
                // action so the user can still switch apps
                if (!mQuickScrubActive && exceededScrubTouchSlop && allowBackAction) {
                    // schedule a back button action and skip quickscrub
                     mBackActionScheduled = true;
                    break;
                }

                // Do not handle quick scrub if disabled
                if (!mNavigationBarView.isQuickScrubEnabled()) {
                    break;
                }
                final boolean allowDrag = !mDragPositive
                        ? offset < 0 && pos < touchDown : offset >= 0 && pos > touchDown;
                float scrubFraction = Utilities.clamp(Math.abs(offset) * 1f / trackSize, 0, 1);
                if (allowDrag) {
                    // Passing the drag slop then touch slop will start quick step
                    if (!mQuickScrubActive && exceededScrubTouchSlop) {
                        startQuickScrub();
                    }
                }

                if (mQuickScrubActive && (mDragPositive && offset >= 0
                        || !mDragPositive && offset <= 0)) {
                    try {
                        mOverviewEventSender.getProxy().onQuickScrubProgress(scrubFraction);
                        if (DEBUG_OVERVIEW_PROXY) {
                            Log.d(TAG_OPS, "Quick Scrub Progress:" + scrubFraction);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to send progress of quick scrub.", e);
                    }
                    mHighlightCenter = x;
                    mNavigationBarView.invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                wasConsumed = true;
                isDoubleTapPending = false;
                mHandler.removeCallbacks(mDoubleTapCancelTimeout);
                mHandler.removeCallbacks(mLongPressAction);
                mLongPressWasTriggered = false;
                mLongPressing = false;
                endQuickScrub(true /* animate */);
                break;
            case MotionEvent.ACTION_UP:
               if (mNavigationBarView.isFullGestureMode()) {
                    mHandler.removeCallbacks(mLongPressAction);
                    mLongPressing = false;
                    if (wasConsumed) {
                        wasConsumed = false;
                    } else if (!mLongPressWasTriggered) {
                        isDoubleTapPending = true;
                        mHandler.postDelayed(mDoubleTapCancelTimeout,
                        /* if dt2s is disabled we don'need to wait a 2nd tap to call the home action */
                        mNavigationBarView.isDt2s() ? sDoubleTapTimeout : 0);
                        mPreviousUpEventX = (int)event.getX();
                        mPreviousUpEventY = (int)event.getY();
	            }
                    if (mBackActionScheduled) {
                        endQuickScrub(true /* animate */);
                        mNavigationBarView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        AEXUtils.sendKeycode(KeyEvent.KEYCODE_BACK, mHandler);
                    } else {
                        endQuickScrub(true /* animate */);
                    }
                    break;
               }

                endQuickScrub(true /* animate */);
                break;
        }

        // Proxy motion events to launcher if not handled by quick scrub
        // Proxy motion events up/cancel that would be sent after long press on any nav button
        if (!mQuickScrubActive && (mAllowGestureDetection || mBackActionScheduled
                || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP)) {
            proxyMotionEvents(event);
        }
        return mQuickScrubActive || mQuickStepStarted || deadZoneConsumed || mBackActionScheduled;
    }

    private Runnable mDoubleTapCancelTimeout = new Runnable() {
        @Override
        public void run() {
            wasConsumed = false;
            isDoubleTapPending = false;
            // it was a single tap, let's trigger the home button action
            mHandler.removeCallbacksAndMessages(null);
            mNavigationBarView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            AEXUtils.sendKeycode(KeyEvent.KEYCODE_HOME, mHandler);
        }
    };

    private LongPressRunnable mLongPressAction = new LongPressRunnable();

    private class LongPressRunnable implements Runnable {
        private boolean isRight;

        public void setIsRight(boolean right) {
            isRight = right;
        }

        @Override
        public void run() {
            mNavigationBarView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            moveKbCursor(isRight, true);
        }
    }

    private void moveKbCursor(boolean right, boolean firstTrigger) {
        if (!mIsKeyboardShowing || !mLongPressing) return;

        mHandler.removeCallbacksAndMessages(null);
        mLongPressWasTriggered = true;
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                moveKbCursor(right, false);
            }
        };
        AEXUtils.moveKbCursor(KeyEvent.ACTION_UP, right);
        AEXUtils.moveKbCursor(KeyEvent.ACTION_DOWN, right);
        mHandler.postDelayed(r, firstTrigger ? 500 : 250);
    }

    public void setKeyboardShowing(boolean showing) {
        mIsKeyboardShowing = showing;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (!mNavigationBarView.isQuickScrubEnabled()) {
            return;
        }
        mTrackPaint.setAlpha(Math.round(255f * mTrackAlpha));

        // Scale the track, but apply the inverse scale from the nav bar
        final float radius = mTrackRect.height() / 2;
        canvas.save();
        float translate = Utilities.clamp(mHighlightCenter, mTrackRect.left, mTrackRect.right);
        canvas.translate(translate, 0);
        canvas.scale(mTrackScale / mNavigationBarView.getScaleX(),
                1f / mNavigationBarView.getScaleY(),
                mTrackRect.centerX(), mTrackRect.centerY());
        canvas.drawRoundRect(mTrackRect.left - translate, mTrackRect.top,
                mTrackRect.right - translate, mTrackRect.bottom, radius, radius, mTrackPaint);
        canvas.restore();
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int paddingLeft = mNavigationBarView.getPaddingLeft();
        final int paddingTop = mNavigationBarView.getPaddingTop();
        final int paddingRight = mNavigationBarView.getPaddingRight();
        final int paddingBottom = mNavigationBarView.getPaddingBottom();
        final int width = (right - left) - paddingRight - paddingLeft;
        final int height = (bottom - top) - paddingBottom - paddingTop;
        final int x1, x2, y1, y2;
        if (mIsVertical) {
            x1 = (width - mTrackThickness) / 2 + paddingLeft;
            x2 = x1 + mTrackThickness;
            y1 = paddingTop + mTrackEndPadding;
            y2 = y1 + height - 2 * mTrackEndPadding;
        } else {
            y1 = (height - mTrackThickness) / 2 + paddingTop;
            y2 = y1 + mTrackThickness;
            x1 = mNavigationBarView.getPaddingStart() + mTrackEndPadding;
            x2 = x1 + width - 2 * mTrackEndPadding;
        }
        mTrackRect.set(x1, y1, x2, y2);
        updateHighlight();
    }

    @Override
    public void onDarkIntensityChange(float intensity) {
        final float oldIntensity = mDarkIntensity;
        mDarkIntensity = intensity;

        // When in quick scrub, invalidate gradient if changing intensity from black to white and
        // vice-versa
        if (mNavigationBarView.isQuickScrubEnabled()
                && Math.round(intensity) != Math.round(oldIntensity)) {
            updateHighlight();
        }
        mNavigationBarView.invalidate();
    }

    @Override
    public void setBarState(boolean isVertical, boolean isRTL) {
        final boolean changed = (mIsVertical != isVertical) || (mIsRTL != isRTL);
        if (changed) {
            // End quickscrub if the state changes mid-transition
            endQuickScrub(false /* animate */);
        }
        mIsVertical = isVertical;
        mIsRTL = isRTL;
        try {
            int navbarPos = WindowManagerGlobal.getWindowManagerService().getNavBarPosition();
            mDragPositive = navbarPos == NAV_BAR_LEFT || navbarPos == NAV_BAR_BOTTOM;
            if (isRTL) {
                mDragPositive = !mDragPositive;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get nav bar position.", e);
        }
    }

    @Override
    public void onNavigationButtonLongPress(View v) {
        mAllowGestureDetection = false;
        mHandler.removeCallbacksAndMessages(null);
    }

    private void startQuickStep(MotionEvent event) {
        mQuickStepStarted = true;
        event.transform(mTransformGlobalMatrix);
        try {
            mOverviewEventSender.getProxy().onQuickStep(event);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Quick Step Start");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send quick step started.", e);
        } finally {
            event.transform(mTransformLocalMatrix);
        }
        mOverviewEventSender.notifyQuickStepStarted();
        mHandler.removeCallbacksAndMessages(null);

        if (mHitTarget != null) {
            mHitTarget.abortCurrentGesture();
        }

        if (mQuickScrubActive) {
            animateEnd();
        }
    }

    private void startQuickScrub() {
        if (!mQuickScrubActive) {
            updateHighlight();
            mQuickScrubActive = true;
            ObjectAnimator trackAnimator = ObjectAnimator.ofPropertyValuesHolder(this,
                    PropertyValuesHolder.ofFloat(mTrackAlphaProperty, 1f),
                    PropertyValuesHolder.ofFloat(mTrackScaleProperty, 1f));
            trackAnimator.setInterpolator(ALPHA_IN);
            trackAnimator.setDuration(ANIM_IN_DURATION_MS);
            ObjectAnimator navBarAnimator = ObjectAnimator.ofFloat(this, mNavBarAlphaProperty, 0f);
            navBarAnimator.setInterpolator(ALPHA_OUT);
            navBarAnimator.setDuration(ANIM_OUT_DURATION_MS);
            mTrackAnimator = new AnimatorSet();
            mTrackAnimator.playTogether(trackAnimator, navBarAnimator);
            mTrackAnimator.start();

            // Disable slippery for quick scrub to not cancel outside the nav bar
            mNavigationBarView.updateSlippery();

            try {
                mOverviewEventSender.getProxy().onQuickScrubStart();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub Start");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send start of quick scrub.", e);
            }
            mOverviewEventSender.notifyQuickScrubStarted();

            if (mHitTarget != null) {
                mHitTarget.abortCurrentGesture();
            }
        }
    }

    private void endQuickScrub(boolean animate) {
        if (mQuickScrubActive) {
            animateEnd();
            try {
                mOverviewEventSender.getProxy().onQuickScrubEnd();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub End");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send end of quick scrub.", e);
            }
        }
        if (!animate) {
            if (mTrackAnimator != null) {
                mTrackAnimator.end();
                mTrackAnimator = null;
            }
        }
    }

    private void animateEnd() {
        if (mTrackAnimator != null) {
            mTrackAnimator.cancel();
        }

        ObjectAnimator trackAnimator = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(mTrackAlphaProperty, 0f),
                PropertyValuesHolder.ofFloat(mTrackScaleProperty, TRACK_SCALE));
        trackAnimator.setInterpolator(ALPHA_OUT);
        trackAnimator.setDuration(ANIM_OUT_DURATION_MS);
        ObjectAnimator navBarAnimator = ObjectAnimator.ofFloat(this, mNavBarAlphaProperty, 1f);
        navBarAnimator.setInterpolator(ALPHA_IN);
        navBarAnimator.setDuration(ANIM_IN_DURATION_MS);
        mTrackAnimator = new AnimatorSet();
        mTrackAnimator.playTogether(trackAnimator, navBarAnimator);
        mTrackAnimator.addListener(mQuickScrubEndListener);
        mTrackAnimator.start();
    }

    private void resetQuickScrub() {
        mQuickScrubActive = false;
        mAllowGestureDetection = false;
        mCurrentNavigationBarView = null;
        updateHighlight();
    }

    private void updateHighlight() {
        if (mTrackRect.isEmpty()) {
            return;
        }
        int colorBase, colorGrad;
        if (mDarkIntensity > 0.5f) {
            colorBase = mContext.getColor(R.color.quick_step_track_background_background_dark);
            colorGrad = mContext.getColor(R.color.quick_step_track_background_foreground_dark);
        } else {
            colorBase = mContext.getColor(R.color.quick_step_track_background_background_light);
            colorGrad = mContext.getColor(R.color.quick_step_track_background_foreground_light);
        }
        mHighlight = new RadialGradient(0, mTrackRect.height() / 2,
                mTrackRect.width() * GRADIENT_WIDTH, colorGrad, colorBase,
                Shader.TileMode.CLAMP);
        mTrackPaint.setShader(mHighlight);
    }

    private boolean proxyMotionEvents(MotionEvent event) {
        final IOverviewProxy overviewProxy = mOverviewEventSender.getProxy();
        event.transform(mTransformGlobalMatrix);
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                overviewProxy.onPreMotionEvent(mNavigationBarView.getDownHitTarget());
            }
            overviewProxy.onMotionEvent(event);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Send MotionEvent: " + event.toString());
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Callback failed", e);
        } finally {
            event.transform(mTransformLocalMatrix);
        }
        return false;
    }
}
