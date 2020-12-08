/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_CLOSE;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_FULL;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_NONE;

import android.annotation.SuppressLint;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.model.SysUiState;
import com.android.systemui.pip.PipAnimationController;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.pip.PipUiEventLogger;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.DismissCircleView;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.systemui.util.animation.PhysicsAnimator;
import com.android.systemui.util.magnetictarget.MagnetizedObject;

import java.io.PrintWriter;

import kotlin.Unit;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler {
    private static final String TAG = "PipTouchHandler";

    /** Duration of the dismiss scrim fading in/out. */
    private static final int DISMISS_TRANSITION_DURATION_MS = 200;

    /* The multiplier to apply scale the target size by when applying the magnetic field radius */
    private static final float MAGNETIC_FIELD_RADIUS_MULTIPLIER = 1.25f;

    // Allow dragging the PIP to a location to close it
    private final boolean mEnableDismissDragToEdge;
    // Allow PIP to resize to a slightly bigger state upon touch
    private final boolean mEnableResize;
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IActivityManager mActivityManager;
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipUiEventLogger mPipUiEventLogger;

    private PipResizeGestureHandler mPipResizeGestureHandler;
    private IPinnedStackController mPinnedStackController;

    private final PipMenuActivityController mMenuController;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final AccessibilityManager mAccessibilityManager;
    private boolean mShowPipMenuOnAnimationEnd = false;

    /**
     * MagnetizedObject wrapper for PIP. This allows the magnetic target library to locate and move
     * PIP.
     */
    private MagnetizedObject<Rect> mMagnetizedPip;

    /**
     * Container for the dismiss circle, so that it can be animated within the container via
     * translation rather than within the WindowManager via slow layout animations.
     */
    private ViewGroup mTargetViewContainer;

    /** Circle view used to render the dismiss target. */
    private DismissCircleView mTargetView;

    /**
     * MagneticTarget instance wrapping the target view and allowing us to set its magnetic radius.
     */
    private MagnetizedObject.MagneticTarget mMagneticTarget;

    /** PhysicsAnimator instance for animating the dismiss target in/out. */
    private PhysicsAnimator<View> mMagneticTargetAnimator;

    /** Default configuration to use for springing the dismiss target in/out. */
    private final PhysicsAnimator.SpringConfig mTargetSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    // The current movement bounds
    private Rect mMovementBounds = new Rect();

    // The reference inset bounds, used to determine the dismiss fraction
    private Rect mInsetBounds = new Rect();
    // The reference bounds used to calculate the normal/expanded target bounds
    private Rect mNormalBounds = new Rect();
    @VisibleForTesting Rect mNormalMovementBounds = new Rect();
    private Rect mExpandedBounds = new Rect();
    @VisibleForTesting Rect mExpandedMovementBounds = new Rect();
    private int mExpandedShortestEdgeSize;

    // Used to workaround an issue where the WM rotation happens before we are notified, allowing
    // us to send stale bounds
    private int mDeferResizeToNormalBoundsUntilRotation = -1;
    private int mDisplayRotation;

    /**
     * Runnable that can be posted delayed to show the target. This needs to be saved as a member
     * variable so we can pass it to removeCallbacks.
     */
    private Runnable mShowTargetAction = this::showDismissTargetMaybe;

    private Handler mHandler = new Handler();

    // Behaviour states
    private int mMenuState = MENU_STATE_NONE;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private int mImeOffset;
    private int mDismissAreaHeight;
    private boolean mIsShelfShowing;
    private int mShelfHeight;
    private int mMovementBoundsExtraOffsets;
    private int mBottomOffsetBufferPx;
    private float mSavedSnapFraction = -1f;
    private boolean mSendingHoverAccessibilityEvents;
    private boolean mMovementWithinDismiss;
    private PipAccessibilityInteractionConnection mConnection;

    // Touch state
    private final PipTouchState mTouchState;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private PipMotionHelper mMotionHelper;
    private PipTouchGesture mGesture;

    // Temp vars
    private final Rect mTmpBounds = new Rect();

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PipMenuActivityController.Listener {
        @Override
        public void onPipMenuStateChanged(int menuState, boolean resize, Runnable callback) {
            setMenuState(menuState, resize, callback);
        }

        @Override
        public void onPipExpand() {
            mMotionHelper.expandPipToFullscreen();
        }

        @Override
        public void onPipDismiss() {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_TAP_TO_REMOVE);
            mTouchState.removeDoubleTapTimeoutCallback();
            mMotionHelper.dismissPip();
        }

        @Override
        public void onPipShowMenu() {
            mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                    true /* allowMenuTimeout */, willResizeMenu(), shouldShowResizeHandle());
        }
    }

    @SuppressLint("InflateParams")
    public PipTouchHandler(Context context, IActivityManager activityManager,
            PipMenuActivityController menuController,
            InputConsumerController inputConsumerController,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            DeviceConfigProxy deviceConfig,
            PipSnapAlgorithm pipSnapAlgorithm,
            SysUiState sysUiState,
            PipUiEventLogger pipUiEventLogger) {
        // Initialize the Pip input consumer
        mContext = context;
        mActivityManager = activityManager;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mMenuController = menuController;
        mMenuController.addListener(new PipMenuListener());
        mSnapAlgorithm = pipSnapAlgorithm;
        mGesture = new DefaultPipTouchGesture();
        mMotionHelper = new PipMotionHelper(mContext, pipTaskOrganizer, mMenuController,
                mSnapAlgorithm, floatingContentCoordinator);
        mPipResizeGestureHandler =
                new PipResizeGestureHandler(context, pipBoundsHandler, mMotionHelper,
                        deviceConfig, pipTaskOrganizer, menuController, this::getMovementBounds,
                        this::updateMovementBounds, sysUiState, pipUiEventLogger);
        mTouchState = new PipTouchState(ViewConfiguration.get(context), mHandler,
                () -> mMenuController.showMenuWithDelay(MENU_STATE_FULL, mMotionHelper.getBounds(),
                        true /* allowMenuTimeout */, willResizeMenu(), shouldShowResizeHandle()),
                        menuController::hideMenu);

        Resources res = context.getResources();
        mEnableDismissDragToEdge = res.getBoolean(R.bool.config_pipEnableDismissDragToEdge);
        mEnableResize = res.getBoolean(R.bool.config_pipEnableResizeForMenu);
        reloadResources();

        // Register the listener for input consumer touch events
        inputConsumerController.setInputListener(this::handleTouchEvent);
        inputConsumerController.setRegistrationListener(this::onRegistrationChanged);

        mPipBoundsHandler = pipBoundsHandler;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mConnection = new PipAccessibilityInteractionConnection(mContext, mMotionHelper,
                pipTaskOrganizer, pipSnapAlgorithm, this::onAccessibilityShowMenu,
                this::updateMovementBounds, mHandler);

        mPipUiEventLogger = pipUiEventLogger;

        mTargetView = new DismissCircleView(context);
        mTargetViewContainer = new FrameLayout(context);
        mTargetViewContainer.setBackgroundDrawable(
                context.getDrawable(R.drawable.floating_dismiss_gradient_transition));
        mTargetViewContainer.setClipChildren(false);
        mTargetViewContainer.addView(mTargetView);

        mMagnetizedPip = mMotionHelper.getMagnetizedPip();
        mMagneticTarget = mMagnetizedPip.addTarget(mTargetView, 0);
        updateMagneticTargetSize();

        mMagnetizedPip.setAnimateStuckToTarget(
                (target, velX, velY, flung, after) -> {
                    mMotionHelper.animateIntoDismissTarget(target, velX, velY, flung, after);
                    return Unit.INSTANCE;
                });
        mMagnetizedPip.setMagnetListener(new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                // Show the dismiss target, in case the initial touch event occurred within the
                // magnetic field radius.
                showDismissTargetMaybe();
            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    float velX, float velY, boolean wasFlungOut) {
                if (wasFlungOut) {
                    mMotionHelper.flingToSnapTarget(velX, velY, null, null);
                    hideDismissTarget();
                } else {
                    mMotionHelper.setSpringingToTouch(true);
                }
            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                mMotionHelper.notifyDismissalPending();

                mHandler.post(() -> {
                    mMotionHelper.animateDismiss();
                    hideDismissTarget();
                });

                mPipUiEventLogger.log(
                        PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_DRAG_TO_REMOVE);
            }
        });

        mMagneticTargetAnimator = PhysicsAnimator.getInstance(mTargetView);
    }

    private void reloadResources() {
        final Resources res = mContext.getResources();
        mBottomOffsetBufferPx = res.getDimensionPixelSize(R.dimen.pip_bottom_offset_buffer);
        mExpandedShortestEdgeSize = res.getDimensionPixelSize(
                R.dimen.pip_expanded_shortest_edge_size);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);
        mDismissAreaHeight = res.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height);
        updateMagneticTargetSize();
    }

    private void updateMagneticTargetSize() {
        if (mTargetView == null) {
            return;
        }

        final Resources res = mContext.getResources();
        final int targetSize = res.getDimensionPixelSize(R.dimen.dismiss_circle_size);
        final FrameLayout.LayoutParams newParams =
                new FrameLayout.LayoutParams(targetSize, targetSize);
        newParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        newParams.bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.floating_dismiss_bottom_margin);
        mTargetView.setLayoutParams(newParams);

        // Set the magnetic field radius equal to the target size from the center of the target
        mMagneticTarget.setMagneticFieldRadiusPx(
                (int) (targetSize * MAGNETIC_FIELD_RADIUS_MULTIPLIER));
    }

    private boolean shouldShowResizeHandle() {
            return !mPipBoundsHandler.hasSaveReentryBounds();
    }

    public void setTouchGesture(PipTouchGesture gesture) {
        mGesture = gesture;
    }

    public void setTouchEnabled(boolean enabled) {
        mTouchState.setAllowTouches(enabled);
    }

    public void showPictureInPictureMenu() {
        // Only show the menu if the user isn't currently interacting with the PiP
        if (!mTouchState.isUserInteracting()) {
            mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                    false /* allowMenuTimeout */, willResizeMenu(),
                    shouldShowResizeHandle());
        }
    }

    public void onActivityPinned() {
        createOrUpdateDismissTarget();

        mShowPipMenuOnAnimationEnd = true;
        mPipResizeGestureHandler.onActivityPinned();
        mFloatingContentCoordinator.onContentAdded(mMotionHelper);
    }

    public void onActivityUnpinned(ComponentName topPipActivity) {
        if (topPipActivity == null) {
            // Clean up state after the last PiP activity is removed
            cleanUpDismissTarget();

            mFloatingContentCoordinator.onContentRemoved(mMotionHelper);
        }
        mPipResizeGestureHandler.onActivityUnpinned();
    }

    public void onPinnedStackAnimationEnded(
            @PipAnimationController.TransitionDirection int direction) {
        // Always synchronize the motion helper bounds once PiP animations finish
        mMotionHelper.synchronizePinnedStackBounds();
        updateMovementBounds();
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            // Set the initial bounds as the user resize bounds.
            mPipResizeGestureHandler.setUserResizeBounds(mMotionHelper.getBounds());
        }

        if (mShowPipMenuOnAnimationEnd) {
            mMenuController.showMenu(MENU_STATE_CLOSE, mMotionHelper.getBounds(),
                    true /* allowMenuTimeout */, false /* willResizeMenu */,
                    shouldShowResizeHandle());
            mShowPipMenuOnAnimationEnd = false;
        }
    }

    public void onConfigurationChanged() {
        mPipResizeGestureHandler.onConfigurationChanged();
        mMotionHelper.synchronizePinnedStackBounds();
        reloadResources();

        // Recreate the dismiss target for the new orientation.
        createOrUpdateDismissTarget();
    }

    public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        mIsImeShowing = imeVisible;
        mImeHeight = imeHeight;
    }

    public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
        mIsShelfShowing = shelfVisible;
        mShelfHeight = shelfHeight;
    }

    public void adjustBoundsForRotation(Rect outBounds, Rect curBounds, Rect insetBounds) {
        final Rect toMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(outBounds, insetBounds, toMovementBounds, 0);
        final int prevBottom = mMovementBounds.bottom - mMovementBoundsExtraOffsets;
        if ((prevBottom - mBottomOffsetBufferPx) <= curBounds.top) {
            outBounds.offsetTo(outBounds.left, toMovementBounds.bottom);
        }
    }

    /**
     * Responds to IPinnedStackListener on resetting aspect ratio for the pinned window.
     */
    public void onAspectRatioChanged() {
        mPipResizeGestureHandler.invalidateUserResizeBounds();
    }

    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds, Rect curBounds,
            boolean fromImeAdjustment, boolean fromShelfAdjustment, int displayRotation) {
        // Set the user resized bounds equal to the new normal bounds in case they were
        // invalidated (e.g. by an aspect ratio change).
        if (mPipResizeGestureHandler.getUserResizeBounds().isEmpty()) {
            mPipResizeGestureHandler.setUserResizeBounds(normalBounds);
        }

        final int bottomOffset = mIsImeShowing ? mImeHeight : 0;
        final boolean fromDisplayRotationChanged = (mDisplayRotation != displayRotation);
        if (fromDisplayRotationChanged) {
            mTouchState.reset();
        }

        // Re-calculate the expanded bounds
        mNormalBounds.set(normalBounds);
        Rect normalMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mNormalBounds, insetBounds, normalMovementBounds,
                bottomOffset);

        if (mMovementBounds.isEmpty()) {
            // mMovementBounds is not initialized yet and a clean movement bounds without
            // bottom offset shall be used later in this function.
            mSnapAlgorithm.getMovementBounds(curBounds, insetBounds, mMovementBounds,
                    0 /* bottomOffset */);
        }

        // Calculate the expanded size
        float aspectRatio = (float) normalBounds.width() / normalBounds.height();
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size expandedSize = mSnapAlgorithm.getSizeForAspectRatio(aspectRatio,
                mExpandedShortestEdgeSize, displaySize.x, displaySize.y);
        mExpandedBounds.set(0, 0, expandedSize.getWidth(), expandedSize.getHeight());
        Rect expandedMovementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(mExpandedBounds, insetBounds, expandedMovementBounds,
                bottomOffset);

        mPipResizeGestureHandler.updateMinSize(mNormalBounds.width(), mNormalBounds.height());
        mPipResizeGestureHandler.updateMaxSize(mExpandedBounds.width(), mExpandedBounds.height());

        // The extra offset does not really affect the movement bounds, but are applied based on the
        // current state (ime showing, or shelf offset) when we need to actually shift
        int extraOffset = Math.max(
                mIsImeShowing ? mImeOffset : 0,
                !mIsImeShowing && mIsShelfShowing ? mShelfHeight : 0);

        // If this is from an IME or shelf adjustment, then we should move the PiP so that it is not
        // occluded by the IME or shelf.
        if (fromImeAdjustment || fromShelfAdjustment) {
            if (mTouchState.isUserInteracting()) {
                // Defer the update of the current movement bounds until after the user finishes
                // touching the screen
            } else {
                final boolean isExpanded = mMenuState == MENU_STATE_FULL && willResizeMenu();
                final Rect toMovementBounds = new Rect();
                mSnapAlgorithm.getMovementBounds(curBounds, insetBounds,
                        toMovementBounds, mIsImeShowing ? mImeHeight : 0);
                final int prevBottom = mMovementBounds.bottom - mMovementBoundsExtraOffsets;
                // This is to handle landscape fullscreen IMEs, don't apply the extra offset in this
                // case
                final int toBottom = toMovementBounds.bottom < toMovementBounds.top
                        ? toMovementBounds.bottom
                        : toMovementBounds.bottom - extraOffset;

                if (isExpanded) {
                    curBounds.set(mExpandedBounds);
                    mSnapAlgorithm.applySnapFraction(curBounds, toMovementBounds,
                            mSavedSnapFraction);
                }

                if (prevBottom < toBottom) {
                    // The movement bounds are expanding
                    if (curBounds.top > prevBottom - mBottomOffsetBufferPx) {
                        mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                    }
                } else if (prevBottom > toBottom) {
                    // The movement bounds are shrinking
                    if (curBounds.top > toBottom - mBottomOffsetBufferPx) {
                        mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                    }
                }
            }
        }

        // Update the movement bounds after doing the calculations based on the old movement bounds
        // above
        mNormalMovementBounds.set(normalMovementBounds);
        mExpandedMovementBounds.set(expandedMovementBounds);
        mDisplayRotation = displayRotation;
        mInsetBounds.set(insetBounds);
        updateMovementBounds();
        mMovementBoundsExtraOffsets = extraOffset;
        mConnection.onMovementBoundsChanged(mNormalBounds, mExpandedBounds, mNormalMovementBounds,
                mExpandedMovementBounds);

        // If we have a deferred resize, apply it now
        if (mDeferResizeToNormalBoundsUntilRotation == displayRotation) {
            mMotionHelper.animateToUnexpandedState(normalBounds, mSavedSnapFraction,
                    mNormalMovementBounds, mMovementBounds, true /* immediate */);
            mSavedSnapFraction = -1f;
            mDeferResizeToNormalBoundsUntilRotation = -1;
        }
    }

    /** Adds the magnetic target view to the WindowManager so it's ready to be animated in. */
    private void createOrUpdateDismissTarget() {
        if (!mTargetViewContainer.isAttachedToWindow()) {
            mHandler.removeCallbacks(mShowTargetAction);
            mMagneticTargetAnimator.cancel();

            mTargetViewContainer.setVisibility(View.INVISIBLE);

            try {
                mWindowManager.addView(mTargetViewContainer, getDismissTargetLayoutParams());
            } catch (IllegalStateException e) {
                // This shouldn't happen, but if the target is already added, just update its layout
                // params.
                mWindowManager.updateViewLayout(
                        mTargetViewContainer, getDismissTargetLayoutParams());
            }
        } else {
            mWindowManager.updateViewLayout(mTargetViewContainer, getDismissTargetLayoutParams());
        }
    }

    /** Returns layout params for the dismiss target, using the latest display metrics. */
    private WindowManager.LayoutParams getDismissTargetLayoutParams() {
        final Point windowSize = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(windowSize);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                mDismissAreaHeight,
                0, windowSize.y - mDismissAreaHeight,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        lp.setTitle("pip-dismiss-overlay");
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0 /* types */);

        return lp;
    }

    /** Makes the dismiss target visible and animates it in, if it isn't already visible. */
    private void showDismissTargetMaybe() {
        createOrUpdateDismissTarget();

        if (mTargetViewContainer.getVisibility() != View.VISIBLE) {

            mTargetView.setTranslationY(mTargetViewContainer.getHeight());
            mTargetViewContainer.setVisibility(View.VISIBLE);

            // Cancel in case we were in the middle of animating it out.
            mMagneticTargetAnimator.cancel();
            mMagneticTargetAnimator
                    .spring(DynamicAnimation.TRANSLATION_Y, 0f, mTargetSpringConfig)
                    .start();

            ((TransitionDrawable) mTargetViewContainer.getBackground()).startTransition(
                    DISMISS_TRANSITION_DURATION_MS);
        }
    }

    /** Animates the magnetic dismiss target out and then sets it to GONE. */
    private void hideDismissTarget() {
        mHandler.removeCallbacks(mShowTargetAction);
        mMagneticTargetAnimator
                .spring(DynamicAnimation.TRANSLATION_Y,
                        mTargetViewContainer.getHeight(),
                        mTargetSpringConfig)
                .withEndActions(() ->  mTargetViewContainer.setVisibility(View.GONE))
                .start();

        ((TransitionDrawable) mTargetViewContainer.getBackground()).reverseTransition(
                DISMISS_TRANSITION_DURATION_MS);
    }

    /**
     * Removes the dismiss target and cancels any pending callbacks to show it.
     */
    private void cleanUpDismissTarget() {
        mHandler.removeCallbacks(mShowTargetAction);

        if (mTargetViewContainer.isAttachedToWindow()) {
            mWindowManager.removeViewImmediate(mTargetViewContainer);
        }
    }

    private void onRegistrationChanged(boolean isRegistered) {
        mAccessibilityManager.setPictureInPictureActionReplacingConnection(isRegistered
                ? mConnection : null);
        if (!isRegistered && mTouchState.isUserInteracting()) {
            // If the input consumer is unregistered while the user is interacting, then we may not
            // get the final TOUCH_UP event, so clean up the dismiss target as well
            cleanUpDismissTarget();
        }
    }

    private void onAccessibilityShowMenu() {
        mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                true /* allowMenuTimeout */, willResizeMenu(),
                shouldShowResizeHandle());
    }

    private boolean handleTouchEvent(InputEvent inputEvent) {
        // Skip any non motion events
        if (!(inputEvent instanceof MotionEvent)) {
            return true;
        }
        // Skip touch handling until we are bound to the controller
        if (mPinnedStackController == null) {
            return true;
        }

        MotionEvent ev = (MotionEvent) inputEvent;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                && mPipResizeGestureHandler.willStartResizeGesture(ev)) {
            // Initialize the touch state for the gesture, but immediately reset to invalidate the
            // gesture
            mTouchState.onTouchEvent(ev);
            mTouchState.reset();
            return true;
        }

        if ((ev.getAction() == MotionEvent.ACTION_DOWN || mTouchState.isUserInteracting())
                && mMagnetizedPip.maybeConsumeMotionEvent(ev)) {
            // If the first touch event occurs within the magnetic field, pass the ACTION_DOWN event
            // to the touch state. Touch state needs a DOWN event in order to later process MOVE
            // events it'll receive if the object is dragged out of the magnetic field.
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                mTouchState.onTouchEvent(ev);
            }

            // Continue tracking velocity when the object is in the magnetic field, since we want to
            // respect touch input velocity if the object is dragged out and then flung.
            mTouchState.addMovementToVelocityTracker(ev);

            return true;
        }

        // Update the touch state
        mTouchState.onTouchEvent(ev);

        boolean shouldDeliverToMenu = mMenuState != MENU_STATE_NONE;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                mGesture.onDown(mTouchState);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mGesture.onMove(mTouchState)) {
                    break;
                }

                shouldDeliverToMenu = !mTouchState.isDragging();
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the movement bounds again if the state has changed since the user started
                // dragging (ie. when the IME shows)
                updateMovementBounds();

                if (mGesture.onUp(mTouchState)) {
                    break;
                }

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                shouldDeliverToMenu = !mTouchState.startedDragging() && !mTouchState.isDragging();
                mTouchState.reset();
                break;
            }
            case MotionEvent.ACTION_HOVER_ENTER:
                // If Touch Exploration is enabled, some a11y services (e.g. Talkback) is probably
                // on and changing MotionEvents into HoverEvents.
                // Let's not enable menu show/hide for a11y services.
                if (!mAccessibilityManager.isTouchExplorationEnabled()) {
                    mTouchState.removeHoverExitTimeoutCallback();
                    mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                            false /* allowMenuTimeout */, false /* willResizeMenu */,
                            shouldShowResizeHandle());
                }
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (!shouldDeliverToMenu && !mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                    mSendingHoverAccessibilityEvents = true;
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                // If Touch Exploration is enabled, some a11y services (e.g. Talkback) is probably
                // on and changing MotionEvents into HoverEvents.
                // Let's not enable menu show/hide for a11y services.
                if (!mAccessibilityManager.isTouchExplorationEnabled()) {
                    mTouchState.scheduleHoverExitTimeoutCallback();
                }
                if (!shouldDeliverToMenu && mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    mSendingHoverAccessibilityEvents = false;
                }
                break;
            }
        }

        // Deliver the event to PipMenuActivity to handle button click if the menu has shown.
        if (shouldDeliverToMenu) {
            final MotionEvent cloneEvent = MotionEvent.obtain(ev);
            // Send the cancel event and cancel menu timeout if it starts to drag.
            if (mTouchState.startedDragging()) {
                cloneEvent.setAction(MotionEvent.ACTION_CANCEL);
                mMenuController.pokeMenu();
            }

            mMenuController.handlePointerEvent(cloneEvent);
        }

        return true;
    }

    private void sendAccessibilityHoverEvent(int type) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setImportantForAccessibility(true);
        event.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID);
        event.setWindowId(
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Updates the appearance of the menu and scrim on top of the PiP while dismissing.
     */
    private void updateDismissFraction() {
        // Skip updating the dismiss fraction when the IME is showing. This is to work around an
        // issue where starting the menu activity for the dismiss overlay will steal the window
        // focus, which closes the IME.
        if (mMenuController != null && !mIsImeShowing) {
            Rect bounds = mMotionHelper.getBounds();
            final float target = mInsetBounds.bottom;
            float fraction = 0f;
            if (bounds.bottom > target) {
                final float distance = bounds.bottom - target;
                fraction = Math.min(distance / bounds.height(), 1f);
            }
            if (Float.compare(fraction, 0f) != 0 || mMenuController.isMenuActivityVisible()) {
                // Update if the fraction > 0, or if fraction == 0 and the menu was already visible
                mMenuController.setDismissFraction(fraction);
            }
        }
    }

    /**
     * Sets the controller to update the system of changes from user interaction.
     */
    void setPinnedStackController(IPinnedStackController controller) {
        mPinnedStackController = controller;
    }

    /**
     * Sets the menu visibility.
     */
    private void setMenuState(int menuState, boolean resize, Runnable callback) {
        if (mMenuState == menuState && !resize) {
            return;
        }

        if (menuState == MENU_STATE_FULL && mMenuState != MENU_STATE_FULL) {
            // Save the current snap fraction and if we do not drag or move the PiP, then
            // we store back to this snap fraction.  Otherwise, we'll reset the snap
            // fraction and snap to the closest edge.
            if (resize) {
                Rect expandedBounds = new Rect(mExpandedBounds);
                mSavedSnapFraction = mMotionHelper.animateToExpandedState(expandedBounds,
                        mMovementBounds, mExpandedMovementBounds, callback);
            }
        } else if (menuState == MENU_STATE_NONE && mMenuState == MENU_STATE_FULL) {
            // Try and restore the PiP to the closest edge, using the saved snap fraction
            // if possible
            if (resize) {
                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    // This is a very special case: when the menu is expanded and visible,
                    // navigating to another activity can trigger auto-enter PiP, and if the
                    // revealed activity has a forced rotation set, then the controller will get
                    // updated with the new rotation of the display. However, at the same time,
                    // SystemUI will try to hide the menu by creating an animation to the normal
                    // bounds which are now stale.  In such a case we defer the animation to the
                    // normal bounds until after the next onMovementBoundsChanged() call to get the
                    // bounds in the new orientation
                    try {
                        int displayRotation = mPinnedStackController.getDisplayRotation();
                        if (mDisplayRotation != displayRotation) {
                            mDeferResizeToNormalBoundsUntilRotation = displayRotation;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not get display rotation from controller");
                    }
                }

                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    Rect restoreBounds = new Rect(getUserResizeBounds());
                    Rect restoredMovementBounds = new Rect();
                    mSnapAlgorithm.getMovementBounds(restoreBounds, mInsetBounds,
                            restoredMovementBounds, mIsImeShowing ? mImeHeight : 0);
                    mMotionHelper.animateToUnexpandedState(restoreBounds, mSavedSnapFraction,
                            restoredMovementBounds, mMovementBounds, false /* immediate */);
                    mSavedSnapFraction = -1f;
                }
            } else {
                mSavedSnapFraction = -1f;
            }
        }
        mMenuState = menuState;
        updateMovementBounds();
        // If pip menu has dismissed, we should register the A11y ActionReplacingConnection for pip
        // as well, or it can't handle a11y focus and pip menu can't perform any action.
        onRegistrationChanged(menuState == MENU_STATE_NONE);
        if (menuState == MENU_STATE_NONE) {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_HIDE_MENU);
        } else if (menuState == MENU_STATE_FULL) {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_SHOW_MENU);
        }
    }

    /**
     * @return the motion helper.
     */
    public PipMotionHelper getMotionHelper() {
        return mMotionHelper;
    }

    @VisibleForTesting
    PipResizeGestureHandler getPipResizeGestureHandler() {
        return mPipResizeGestureHandler;
    }

    @VisibleForTesting
    void setPipResizeGestureHandler(PipResizeGestureHandler pipResizeGestureHandler) {
        mPipResizeGestureHandler = pipResizeGestureHandler;
    }

    @VisibleForTesting
    void setPipMotionHelper(PipMotionHelper pipMotionHelper) {
        mMotionHelper = pipMotionHelper;
    }

    /**
     * @return the unexpanded bounds.
     */
    public Rect getNormalBounds() {
        return mNormalBounds;
    }

    Rect getUserResizeBounds() {
        return mPipResizeGestureHandler.getUserResizeBounds();
    }

    /**
     * Gesture controlling normal movement of the PIP.
     */
    private class DefaultPipTouchGesture extends PipTouchGesture {
        private final Point mStartPosition = new Point();
        private final PointF mDelta = new PointF();
        private boolean mShouldHideMenuAfterFling;

        @Override
        public void onDown(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return;
            }

            Rect bounds = mMotionHelper.getPossiblyAnimatingBounds();
            mDelta.set(0f, 0f);
            mStartPosition.set(bounds.left, bounds.top);
            mMovementWithinDismiss = touchState.getDownTouchPosition().y >= mMovementBounds.bottom;
            mMotionHelper.setSpringingToTouch(false);

            // If the menu is still visible then just poke the menu
            // so that it will timeout after the user stops touching it
            if (mMenuState != MENU_STATE_NONE) {
                mMenuController.pokeMenu();
            }
        }

        @Override
        public boolean onMove(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return false;
            }

            if (touchState.startedDragging()) {
                mSavedSnapFraction = -1f;

                if (mEnableDismissDragToEdge) {
                    if (mTargetViewContainer.getVisibility() != View.VISIBLE) {
                        mHandler.removeCallbacks(mShowTargetAction);
                        showDismissTargetMaybe();
                    }
                }
            }

            if (touchState.isDragging()) {
                // Move the pinned stack freely
                final PointF lastDelta = touchState.getLastTouchDelta();
                float lastX = mStartPosition.x + mDelta.x;
                float lastY = mStartPosition.y + mDelta.y;
                float left = lastX + lastDelta.x;
                float top = lastY + lastDelta.y;

                // Add to the cumulative delta after bounding the position
                mDelta.x += left - lastX;
                mDelta.y += top - lastY;

                mTmpBounds.set(mMotionHelper.getPossiblyAnimatingBounds());
                mTmpBounds.offsetTo((int) left, (int) top);
                mMotionHelper.movePip(mTmpBounds, true /* isDragging */);

                final PointF curPos = touchState.getLastTouchPosition();
                if (mMovementWithinDismiss) {
                    // Track if movement remains near the bottom edge to identify swipe to dismiss
                    mMovementWithinDismiss = curPos.y >= mMovementBounds.bottom;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (mEnableDismissDragToEdge) {
                hideDismissTarget();
            }

            if (!touchState.isUserInteracting()) {
                return false;
            }

            final PointF vel = touchState.getVelocity();

            if (touchState.isDragging()) {
                if (mMenuState != MENU_STATE_NONE) {
                    // If the menu is still visible, then just poke the menu so that
                    // it will timeout after the user stops touching it
                    mMenuController.showMenu(mMenuState, mMotionHelper.getBounds(),
                            true /* allowMenuTimeout */, willResizeMenu(),
                            shouldShowResizeHandle());
                }
                mShouldHideMenuAfterFling = mMenuState == MENU_STATE_NONE;

                // Reset the touch state on up before the fling settles
                mTouchState.reset();
                mMotionHelper.flingToSnapTarget(vel.x, vel.y,
                        PipTouchHandler.this::updateDismissFraction /* updateAction */,
                        this::flingEndAction /* endAction */);
            } else if (mTouchState.isDoubleTap()) {
                // Expand to fullscreen if this is a double tap
                // the PiP should be frozen until the transition ends
                setTouchEnabled(false);
                mMotionHelper.expandPipToFullscreen();
            } else if (mMenuState != MENU_STATE_FULL) {
                if (!mTouchState.isWaitingForDoubleTap()) {
                    // User has stalled long enough for this not to be a drag or a double tap, just
                    // expand the menu
                    mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                            true /* allowMenuTimeout */, willResizeMenu(),
                            shouldShowResizeHandle());
                } else {
                    // Next touch event _may_ be the second tap for the double-tap, schedule a
                    // fallback runnable to trigger the menu if no touch event occurs before the
                    // next tap
                    mTouchState.scheduleDoubleTapTimeoutCallback();
                }
            }
            return true;
        }

        private void flingEndAction() {
            if (mShouldHideMenuAfterFling) {
                // If the menu is not visible, then we can still be showing the activity for the
                // dismiss overlay, so just finish it after the animation completes
                mMenuController.hideMenu();
            }
        }
    };

    /**
     * Updates the current movement bounds based on whether the menu is currently visible and
     * resized.
     */
    private void updateMovementBounds() {
        mSnapAlgorithm.getMovementBounds(mMotionHelper.getBounds(), mInsetBounds,
                mMovementBounds, mIsImeShowing ? mImeHeight : 0);
        mMotionHelper.setCurrentMovementBounds(mMovementBounds);

        boolean isMenuExpanded = mMenuState == MENU_STATE_FULL;
        mPipBoundsHandler.setMinEdgeSize(
                isMenuExpanded  && willResizeMenu() ? mExpandedShortestEdgeSize : 0);
    }

    private Rect getMovementBounds(Rect curBounds) {
        Rect movementBounds = new Rect();
        mSnapAlgorithm.getMovementBounds(curBounds, mInsetBounds,
                movementBounds, mIsImeShowing ? mImeHeight : 0);
        return movementBounds;
    }

    /**
     * @return whether the menu will resize as a part of showing the full menu.
     */
    private boolean willResizeMenu() {
        if (!mEnableResize) {
            return false;
        }
        return mExpandedBounds.width() != mNormalBounds.width()
                || mExpandedBounds.height() != mNormalBounds.height();
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMovementBounds=" + mMovementBounds);
        pw.println(innerPrefix + "mNormalBounds=" + mNormalBounds);
        pw.println(innerPrefix + "mNormalMovementBounds=" + mNormalMovementBounds);
        pw.println(innerPrefix + "mExpandedBounds=" + mExpandedBounds);
        pw.println(innerPrefix + "mExpandedMovementBounds=" + mExpandedMovementBounds);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
        pw.println(innerPrefix + "mSavedSnapFraction=" + mSavedSnapFraction);
        pw.println(innerPrefix + "mEnableDragToEdgeDismiss=" + mEnableDismissDragToEdge);
        pw.println(innerPrefix + "mMovementBoundsExtraOffsets=" + mMovementBoundsExtraOffsets);
        mTouchState.dump(pw, innerPrefix);
        mMotionHelper.dump(pw, innerPrefix);
        if (mPipResizeGestureHandler != null) {
            mPipResizeGestureHandler.dump(pw, innerPrefix);
        }
    }

}
