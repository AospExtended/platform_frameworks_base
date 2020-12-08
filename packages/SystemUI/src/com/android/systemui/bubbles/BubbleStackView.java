/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.bubbles;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.systemui.Prefs.Key.HAS_SEEN_BUBBLES_EDUCATION;
import static com.android.systemui.Prefs.Key.HAS_SEEN_BUBBLES_MANAGE_EDUCATION;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_STACK_VIEW;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_USER_EDUCATION;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityView;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Interpolators;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.bubbles.animation.AnimatableScaleMatrix;
import com.android.systemui.bubbles.animation.ExpandedAnimationController;
import com.android.systemui.bubbles.animation.PhysicsAnimationLayout;
import com.android.systemui.bubbles.animation.StackAnimationController;
import com.android.systemui.model.SysUiState;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.util.DismissCircleView;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.systemui.util.RelativeTouchListener;
import com.android.systemui.util.animation.PhysicsAnimator;
import com.android.systemui.util.magnetictarget.MagnetizedObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders bubbles in a stack and handles animating expanded and collapsed states.
 */
public class BubbleStackView extends FrameLayout
        implements ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleStackView" : TAG_BUBBLES;

    /** Animation durations for bubble stack user education views. **/
    private static final int ANIMATE_STACK_USER_EDUCATION_DURATION = 200;
    private static final int ANIMATE_STACK_USER_EDUCATION_DURATION_SHORT = 40;

    /** How far the flyout needs to be dragged before it's dismissed regardless of velocity. */
    static final float FLYOUT_DRAG_PERCENT_DISMISS = 0.25f;

    /** Velocity required to dismiss the flyout via drag. */
    private static final float FLYOUT_DISMISS_VELOCITY = 2000f;

    /**
     * Factor for attenuating translation when the flyout is overscrolled (8f = flyout moves 1 pixel
     * for every 8 pixels overscrolled).
     */
    private static final float FLYOUT_OVERSCROLL_ATTENUATION_FACTOR = 8f;

    /** Duration of the flyout alpha animations. */
    private static final int FLYOUT_ALPHA_ANIMATION_DURATION = 100;

    /** Percent to darken the bubbles when they're in the dismiss target. */
    private static final float DARKEN_PERCENT = 0.3f;

    /** Duration of the dismiss scrim fading in/out. */
    private static final int DISMISS_TRANSITION_DURATION_MS = 200;

    /** How long to wait, in milliseconds, before hiding the flyout. */
    @VisibleForTesting
    static final int FLYOUT_HIDE_AFTER = 5000;

    /**
     * How long to wait to animate the stack temporarily invisible after a drag/flyout hide
     * animation ends, if we are in fact temporarily invisible.
     */
    private static final int ANIMATE_TEMPORARILY_INVISIBLE_DELAY = 1000;

    private static final PhysicsAnimator.SpringConfig FLYOUT_IME_ANIMATION_SPRING_CONFIG =
            new PhysicsAnimator.SpringConfig(
                    StackAnimationController.IME_ANIMATION_STIFFNESS,
                    StackAnimationController.DEFAULT_BOUNCINESS);

    private final PhysicsAnimator.SpringConfig mScaleInSpringConfig =
            new PhysicsAnimator.SpringConfig(300f, 0.9f);

    private final PhysicsAnimator.SpringConfig mScaleOutSpringConfig =
            new PhysicsAnimator.SpringConfig(900f, 1f);

    private final PhysicsAnimator.SpringConfig mTranslateSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_NO_BOUNCY);

    /**
     * Handler to use for all delayed animations - this way, we can easily cancel them before
     * starting a new animation.
     */
    private final Handler mDelayedAnimationHandler = new Handler();

    /**
     * Interface to synchronize {@link View} state and the screen.
     *
     * {@hide}
     */
    interface SurfaceSynchronizer {
        /**
         * Wait until requested change on a {@link View} is reflected on the screen.
         *
         * @param callback callback to run after the change is reflected on the screen.
         */
        void syncSurfaceAndRun(Runnable callback);
    }

    private static final SurfaceSynchronizer DEFAULT_SURFACE_SYNCHRONIZER =
            new SurfaceSynchronizer() {
        @Override
        public void syncSurfaceAndRun(Runnable callback) {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                // Just wait 2 frames. There is no guarantee, but this is usually enough time that
                // the requested change is reflected on the screen.
                // TODO: Once SurfaceFlinger provide APIs to sync the state of {@code View} and
                // surfaces, rewrite this logic with them.
                private int mFrameWait = 2;

                @Override
                public void doFrame(long frameTimeNanos) {
                    if (--mFrameWait > 0) {
                        Choreographer.getInstance().postFrameCallback(this);
                    } else {
                        callback.run();
                    }
                }
            });
        }
    };

    private Point mDisplaySize;

    private final BubbleData mBubbleData;

    private final ValueAnimator mDesaturateAndDarkenAnimator;
    private final Paint mDesaturateAndDarkenPaint = new Paint();

    private PhysicsAnimationLayout mBubbleContainer;
    private StackAnimationController mStackAnimationController;
    private ExpandedAnimationController mExpandedAnimationController;

    private FrameLayout mExpandedViewContainer;

    /** Matrix used to scale the expanded view container with a given pivot point. */
    private final AnimatableScaleMatrix mExpandedViewContainerMatrix = new AnimatableScaleMatrix();

    /**
     * SurfaceView that we draw screenshots of animating-out bubbles into. This allows us to animate
     * between bubble activities without needing both to be alive at the same time.
     */
    private SurfaceView mAnimatingOutSurfaceView;

    /** Container for the animating-out SurfaceView. */
    private FrameLayout mAnimatingOutSurfaceContainer;

    /**
     * Buffer containing a screenshot of the animating-out bubble. This is drawn into the
     * SurfaceView during animations.
     */
    private SurfaceControl.ScreenshotGraphicBuffer mAnimatingOutBubbleBuffer;

    private BubbleFlyoutView mFlyout;
    /** Runnable that fades out the flyout and then sets it to GONE. */
    private Runnable mHideFlyout = () -> animateFlyoutCollapsed(true, 0 /* velX */);
    /**
     * Callback to run after the flyout hides. Also called if a new flyout is shown before the
     * previous one animates out.
     */
    private Runnable mAfterFlyoutHidden;
    /**
     * Set when the flyout is tapped, so that we can expand the bubble associated with the flyout
     * once it collapses.
     */
    @Nullable
    private Bubble mBubbleToExpandAfterFlyoutCollapse = null;

    /** Layout change listener that moves the stack to the nearest valid position on rotation. */
    private OnLayoutChangeListener mOrientationChangedListener;

    @Nullable private RelativeStackPosition mRelativeStackPositionBeforeRotation;

    private int mMaxBubbles;
    private int mBubbleSize;
    private int mBubbleElevation;
    private int mBubblePaddingTop;
    private int mBubbleTouchPadding;
    private int mExpandedViewPadding;
    private int mCornerRadius;
    private int mStatusBarHeight;
    private int mImeOffset;
    @Nullable private BubbleViewProvider mExpandedBubble;
    private boolean mIsExpanded;

    /** Whether the stack is currently on the left side of the screen, or animating there. */
    private boolean mStackOnLeftOrWillBe = true;

    /** Whether a touch gesture, such as a stack/bubble drag or flyout drag, is in progress. */
    private boolean mIsGestureInProgress = false;

    /** Whether or not the stack is temporarily invisible off the side of the screen. */
    private boolean mTemporarilyInvisible = false;

    /** Whether we're in the middle of dragging the stack around by touch. */
    private boolean mIsDraggingStack = false;

    /**
     * The pointer index of the ACTION_DOWN event we received prior to an ACTION_UP. We'll ignore
     * touches from other pointer indices.
     */
    private int mPointerIndexDown = -1;

    /** Description of current animation controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Stack view state:");
        pw.print("  gestureInProgress:       "); pw.println(mIsGestureInProgress);
        pw.print("  showingDismiss:          "); pw.println(mShowingDismiss);
        pw.print("  isExpansionAnimating:    "); pw.println(mIsExpansionAnimating);
        pw.print("  expandedContainerVis:    "); pw.println(mExpandedViewContainer.getVisibility());
        pw.print("  expandedContainerAlpha:  "); pw.println(mExpandedViewContainer.getAlpha());
        pw.print("  expandedContainerMatrix: ");
        pw.println(mExpandedViewContainer.getAnimationMatrix());

        mStackAnimationController.dump(fd, pw, args);
        mExpandedAnimationController.dump(fd, pw, args);

        if (mExpandedBubble != null) {
            pw.println("Expanded bubble state:");
            pw.println("  expandedBubbleKey: " + mExpandedBubble.getKey());

            final BubbleExpandedView expandedView = mExpandedBubble.getExpandedView();

            if (expandedView != null) {
                pw.println("  expandedViewVis:    " + expandedView.getVisibility());
                pw.println("  expandedViewAlpha:  " + expandedView.getAlpha());
                pw.println("  expandedViewTaskId: " + expandedView.getTaskId());

                final ActivityView av = expandedView.getActivityView();

                if (av != null) {
                    pw.println("  activityViewVis:    " + av.getVisibility());
                    pw.println("  activityViewAlpha:  " + av.getAlpha());
                } else {
                    pw.println("  activityView is null");
                }
            } else {
                pw.println("Expanded bubble view state: expanded bubble view is null");
            }
        } else {
            pw.println("Expanded bubble state: expanded bubble is null");
        }
    }

    private BubbleController.BubbleExpandListener mExpandListener;

    /** Callback to run when we want to unbubble the given notification's conversation. */
    private Consumer<String> mUnbubbleConversationCallback;

    private SysUiState mSysUiState;

    private boolean mViewUpdatedRequested = false;
    private boolean mIsExpansionAnimating = false;
    private boolean mIsBubbleSwitchAnimating = false;
    private boolean mShowingDismiss = false;

    /** The view to desaturate/darken when magneted to the dismiss target. */
    @Nullable private View mDesaturateAndDarkenTargetView;

    private LayoutInflater mInflater;

    private Rect mTempRect = new Rect();

    private final List<Rect> mSystemGestureExclusionRects = Collections.singletonList(new Rect());

    private ViewTreeObserver.OnPreDrawListener mViewUpdater =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
                    updateExpandedView();
                    mViewUpdatedRequested = false;
                    return true;
                }
            };

    private ViewTreeObserver.OnDrawListener mSystemGestureExcludeUpdater =
            this::updateSystemGestureExcludeRects;

    /** Float property that 'drags' the flyout. */
    private final FloatPropertyCompat mFlyoutCollapseProperty =
            new FloatPropertyCompat("FlyoutCollapseSpring") {
                @Override
                public float getValue(Object o) {
                    return mFlyoutDragDeltaX;
                }

                @Override
                public void setValue(Object o, float v) {
                    setFlyoutStateForDragLength(v);
                }
            };

    /** SpringAnimation that springs the flyout collapsed via onFlyoutDragged. */
    private final SpringAnimation mFlyoutTransitionSpring =
            new SpringAnimation(this, mFlyoutCollapseProperty);

    /** Distance the flyout has been dragged in the X axis. */
    private float mFlyoutDragDeltaX = 0f;

    /**
     * Runnable that animates in the flyout. This reference is needed to cancel delayed postings.
     */
    private Runnable mAnimateInFlyout;

    /**
     * End listener for the flyout spring that either posts a runnable to hide the flyout, or hides
     * it immediately.
     */
    private final DynamicAnimation.OnAnimationEndListener mAfterFlyoutTransitionSpring =
            (dynamicAnimation, b, v, v1) -> {
                if (mFlyoutDragDeltaX == 0) {
                    mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
                } else {
                    mFlyout.hideFlyout();
                }
            };

    @NonNull
    private final SurfaceSynchronizer mSurfaceSynchronizer;

    /**
     * Callback to run when the IME visibility changes - BubbleController uses this to update the
     * Bubbles window focusability flags with the WindowManager.
     */
    public final Consumer<Boolean> mOnImeVisibilityChanged;

    /**
     * Callback to run to ask BubbleController to hide the current IME.
     */
    private final Runnable mHideCurrentInputMethodCallback;

    /**
     * The currently magnetized object, which is being dragged and will be attracted to the magnetic
     * dismiss target.
     *
     * This is either the stack itself, or an individual bubble.
     */
    private MagnetizedObject<?> mMagnetizedObject;

    /**
     * The MagneticTarget instance for our circular dismiss view. This is added to the
     * MagnetizedObject instances for the stack and any dragged-out bubbles.
     */
    private MagnetizedObject.MagneticTarget mMagneticTarget;

    /** Magnet listener that handles animating and dismissing individual dragged-out bubbles. */
    private final MagnetizedObject.MagnetListener mIndividualBubbleMagnetListener =
            new MagnetizedObject.MagnetListener() {
                @Override
                public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    if (mExpandedAnimationController.getDraggedOutBubble() == null) {
                        return;
                    }

                    animateDesaturateAndDarken(
                            mExpandedAnimationController.getDraggedOutBubble(), true);
                }

                @Override
                public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                        float velX, float velY, boolean wasFlungOut) {
                    if (mExpandedAnimationController.getDraggedOutBubble() == null) {
                        return;
                    }

                    animateDesaturateAndDarken(
                            mExpandedAnimationController.getDraggedOutBubble(), false);

                    if (wasFlungOut) {
                        mExpandedAnimationController.snapBubbleBack(
                                mExpandedAnimationController.getDraggedOutBubble(), velX, velY);
                        hideDismissTarget();
                    } else {
                        mExpandedAnimationController.onUnstuckFromTarget();
                    }
                }

                @Override
                public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    if (mExpandedAnimationController.getDraggedOutBubble() == null) {
                        return;
                    }

                    mExpandedAnimationController.dismissDraggedOutBubble(
                            mExpandedAnimationController.getDraggedOutBubble() /* bubble */,
                            mDismissTargetContainer.getHeight() /* translationYBy */,
                            BubbleStackView.this::dismissMagnetizedObject /* after */);
                    hideDismissTarget();
                }
            };

    /** Magnet listener that handles animating and dismissing the entire stack. */
    private final MagnetizedObject.MagnetListener mStackMagnetListener =
            new MagnetizedObject.MagnetListener() {
                @Override
                public void onStuckToTarget(
                        @NonNull MagnetizedObject.MagneticTarget target) {
                    animateDesaturateAndDarken(mBubbleContainer, true);
                }

                @Override
                public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                        float velX, float velY, boolean wasFlungOut) {
                    animateDesaturateAndDarken(mBubbleContainer, false);

                    if (wasFlungOut) {
                        mStackAnimationController.flingStackThenSpringToEdge(
                                mStackAnimationController.getStackPosition().x, velX, velY);
                        hideDismissTarget();
                    } else {
                        mStackAnimationController.onUnstuckFromTarget();
                    }
                }

                @Override
                public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    mStackAnimationController.animateStackDismissal(
                            mDismissTargetContainer.getHeight() /* translationYBy */,
                            () -> {
                                resetDesaturationAndDarken();
                                dismissMagnetizedObject();
                            }
                    );

                    hideDismissTarget();
                }
            };

    /**
     * Click listener set on each bubble view. When collapsed, clicking a bubble expands the stack.
     * When expanded, clicking a bubble either expands that bubble, or collapses the stack.
     */
    private OnClickListener mBubbleClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            mIsDraggingStack = false; // If the touch ended in a click, we're no longer dragging.

            // Bubble clicks either trigger expansion/collapse or a bubble switch, both of which we
            // shouldn't interrupt. These are quick transitions, so it's not worth trying to adjust
            // the animations inflight.
            if (mIsExpansionAnimating || mIsBubbleSwitchAnimating) {
                return;
            }

            final Bubble clickedBubble = mBubbleData.getBubbleWithView(view);

            // If the bubble has since left us, ignore the click.
            if (clickedBubble == null) {
                return;
            }

            final boolean clickedBubbleIsCurrentlyExpandedBubble =
                    clickedBubble.getKey().equals(mExpandedBubble.getKey());

            if (isExpanded()) {
                mExpandedAnimationController.onGestureFinished();
            }

            if (isExpanded() && !clickedBubbleIsCurrentlyExpandedBubble) {
                if (clickedBubble != mBubbleData.getSelectedBubble()) {
                    // Select the clicked bubble.
                    mBubbleData.setSelectedBubble(clickedBubble);
                } else {
                    // If the clicked bubble is the selected bubble (but not the expanded bubble),
                    // that means overflow was previously expanded. Set the selected bubble
                    // internally without going through BubbleData (which would ignore it since it's
                    // already selected).
                    setSelectedBubble(clickedBubble);
                }
            } else {
                // Otherwise, we either tapped the stack (which means we're collapsed
                // and should expand) or the currently selected bubble (we're expanded
                // and should collapse).
                if (!maybeShowStackUserEducation()) {
                    mBubbleData.setExpanded(!mBubbleData.isExpanded());
                }
            }
        }
    };

    /**
     * Touch listener set on each bubble view. This enables dragging and dismissing the stack (when
     * collapsed), or individual bubbles (when expanded).
     */
    private RelativeTouchListener mBubbleTouchListener = new RelativeTouchListener() {

        @Override
        public boolean onDown(@NonNull View v, @NonNull MotionEvent ev) {
            // If we're expanding or collapsing, consume but ignore all touch events.
            if (mIsExpansionAnimating) {
                return true;
            }

            // If the manage menu is visible, just hide it.
            if (mShowingManage) {
                showManageMenu(false /* show */);
            }

            if (mBubbleData.isExpanded()) {
                maybeShowManageEducation(false /* show */);

                // If we're expanded, tell the animation controller to prepare to drag this bubble,
                // dispatching to the individual bubble magnet listener.
                mExpandedAnimationController.prepareForBubbleDrag(
                        v /* bubble */,
                        mMagneticTarget,
                        mIndividualBubbleMagnetListener);

                hideCurrentInputMethod();

                // Save the magnetized individual bubble so we can dispatch touch events to it.
                mMagnetizedObject = mExpandedAnimationController.getMagnetizedBubbleDraggingOut();
            } else {
                // If we're collapsed, prepare to drag the stack. Cancel active animations, set the
                // animation controller, and hide the flyout.
                mStackAnimationController.cancelStackPositionAnimations();
                mBubbleContainer.setActiveController(mStackAnimationController);
                hideFlyoutImmediate();

                // Also, save the magnetized stack so we can dispatch touch events to it.
                mMagnetizedObject = mStackAnimationController.getMagnetizedStack(mMagneticTarget);
                mMagnetizedObject.setMagnetListener(mStackMagnetListener);

                mIsDraggingStack = true;

                // Cancel animations to make the stack temporarily invisible, since we're now
                // dragging it.
                updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
            }

            passEventToMagnetizedObject(ev);

            // Bubbles are always interested in all touch events!
            return true;
        }

        @Override
        public void onMove(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy) {
            // If we're expanding or collapsing, ignore all touch events.
            if (mIsExpansionAnimating) {
                return;
            }

            // Show the dismiss target, if we haven't already.
            springInDismissTargetMaybe();

            // First, see if the magnetized object consumes the event - if so, we shouldn't move the
            // bubble since it's stuck to the target.
            if (!passEventToMagnetizedObject(ev)) {
                if (mBubbleData.isExpanded()) {
                    mExpandedAnimationController.dragBubbleOut(
                            v, viewInitialX + dx, viewInitialY + dy);
                } else {
                    hideStackUserEducation(false /* fromExpansion */);
                    mStackAnimationController.moveStackFromTouch(
                            viewInitialX + dx, viewInitialY + dy);
                }
            }
        }

        @Override
        public void onUp(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy, float velX, float velY) {
            // If we're expanding or collapsing, ignore all touch events.
            if (mIsExpansionAnimating) {
                return;
            }

            // First, see if the magnetized object consumes the event - if so, the bubble was
            // released in the target or flung out of it, and we should ignore the event.
            if (!passEventToMagnetizedObject(ev)) {
                if (mBubbleData.isExpanded()) {
                    mExpandedAnimationController.snapBubbleBack(v, velX, velY);
                } else {
                    // Fling the stack to the edge, and save whether or not it's going to end up on
                    // the left side of the screen.
                    mStackOnLeftOrWillBe =
                            mStackAnimationController.flingStackThenSpringToEdge(
                                    viewInitialX + dx, velX, velY) <= 0;

                    updateBubbleZOrdersAndDotPosition(true /* animate */);

                    logBubbleEvent(null /* no bubble associated with bubble stack move */,
                            SysUiStatsLog.BUBBLE_UICHANGED__ACTION__STACK_MOVED);
                }

                hideDismissTarget();
            }

            mIsDraggingStack = false;

            // Hide the stack after a delay, if needed.
            updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
        }
    };

    /** Click listener set on the flyout, which expands the stack when the flyout is tapped. */
    private OnClickListener mFlyoutClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            if (maybeShowStackUserEducation()) {
                // If we're showing user education, don't open the bubble show the education first
                mBubbleToExpandAfterFlyoutCollapse = null;
            } else {
                mBubbleToExpandAfterFlyoutCollapse = mBubbleData.getSelectedBubble();
            }

            mFlyout.removeCallbacks(mHideFlyout);
            mHideFlyout.run();
        }
    };

    /** Touch listener for the flyout. This enables the drag-to-dismiss gesture on the flyout. */
    private RelativeTouchListener mFlyoutTouchListener = new RelativeTouchListener() {

        @Override
        public boolean onDown(@NonNull View v, @NonNull MotionEvent ev) {
            mFlyout.removeCallbacks(mHideFlyout);
            return true;
        }

        @Override
        public void onMove(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy) {
            setFlyoutStateForDragLength(dx);
        }

        @Override
        public void onUp(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy, float velX, float velY) {
            final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
            final boolean metRequiredVelocity =
                    onLeft ? velX < -FLYOUT_DISMISS_VELOCITY : velX > FLYOUT_DISMISS_VELOCITY;
            final boolean metRequiredDeltaX =
                    onLeft
                            ? dx < -mFlyout.getWidth() * FLYOUT_DRAG_PERCENT_DISMISS
                            : dx > mFlyout.getWidth() * FLYOUT_DRAG_PERCENT_DISMISS;
            final boolean isCancelFling = onLeft ? velX > 0 : velX < 0;
            final boolean shouldDismiss = metRequiredVelocity
                    || (metRequiredDeltaX && !isCancelFling);

            mFlyout.removeCallbacks(mHideFlyout);
            animateFlyoutCollapsed(shouldDismiss, velX);

            maybeShowStackUserEducation();
        }
    };

    private View mDismissTargetCircle;
    private ViewGroup mDismissTargetContainer;
    private PhysicsAnimator<View> mDismissTargetAnimator;
    private PhysicsAnimator.SpringConfig mDismissTargetSpring = new PhysicsAnimator.SpringConfig(
            SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    @Nullable
    private BubbleOverflow mBubbleOverflow;

    private boolean mShouldShowUserEducation;
    private boolean mAnimatingEducationAway;
    private View mUserEducationView;

    private boolean mShouldShowManageEducation;
    private BubbleManageEducationView mManageEducationView;
    private boolean mAnimatingManageEducationAway;

    private ViewGroup mManageMenu;
    private ImageView mManageSettingsIcon;
    private TextView mManageSettingsText;
    private boolean mShowingManage = false;
    private PhysicsAnimator.SpringConfig mManageSpringConfig = new PhysicsAnimator.SpringConfig(
            SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY);
    @SuppressLint("ClickableViewAccessibility")
    public BubbleStackView(Context context, BubbleData data,
            @Nullable SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator,
            SysUiState sysUiState,
            Runnable allBubblesAnimatedOutAction,
            Consumer<Boolean> onImeVisibilityChanged,
            Runnable hideCurrentInputMethodCallback) {
        super(context);

        mBubbleData = data;
        mInflater = LayoutInflater.from(context);

        mSysUiState = sysUiState;

        Resources res = getResources();
        mMaxBubbles = res.getInteger(R.integer.bubbles_max_rendered);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubbleElevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleTouchPadding = res.getDimensionPixelSize(R.dimen.bubble_touch_padding);

        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);

        mDisplaySize = new Point();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        // We use the real size & subtract screen decorations / window insets ourselves when needed
        wm.getDefaultDisplay().getRealSize(mDisplaySize);

        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        int elevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);

        final TypedArray ta = mContext.obtainStyledAttributes(
                new int[] {android.R.attr.dialogCornerRadius});
        mCornerRadius = ta.getDimensionPixelSize(0, 0);
        ta.recycle();

        final Runnable onBubbleAnimatedOut = () -> {
            if (getBubbleCount() == 0) {
                allBubblesAnimatedOutAction.run();
            }
        };

        mStackAnimationController = new StackAnimationController(
                floatingContentCoordinator, this::getBubbleCount, onBubbleAnimatedOut);

        mExpandedAnimationController = new ExpandedAnimationController(
                mDisplaySize, mExpandedViewPadding, res.getConfiguration().orientation,
                onBubbleAnimatedOut);
        mSurfaceSynchronizer = synchronizer != null ? synchronizer : DEFAULT_SURFACE_SYNCHRONIZER;

        setUpUserEducation();

        // Force LTR by default since most of the Bubbles UI is positioned manually by the user, or
        // is centered. It greatly simplifies translation positioning/animations. Views that will
        // actually lay out differently in RTL, such as the flyout and expanded view, will set their
        // layout direction to LOCALE.
        setLayoutDirection(LAYOUT_DIRECTION_LTR);

        mBubbleContainer = new PhysicsAnimationLayout(context);
        mBubbleContainer.setActiveController(mStackAnimationController);
        mBubbleContainer.setElevation(elevation);
        mBubbleContainer.setClipChildren(false);
        addView(mBubbleContainer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        mExpandedViewContainer = new FrameLayout(context);
        mExpandedViewContainer.setElevation(elevation);
        mExpandedViewContainer.setClipChildren(false);
        addView(mExpandedViewContainer);

        mAnimatingOutSurfaceContainer = new FrameLayout(getContext());
        mAnimatingOutSurfaceContainer.setLayoutParams(
                new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(mAnimatingOutSurfaceContainer);

        mAnimatingOutSurfaceView = new SurfaceView(getContext());
        mAnimatingOutSurfaceView.setUseAlpha();
        mAnimatingOutSurfaceView.setZOrderOnTop(true);
        mAnimatingOutSurfaceView.setCornerRadius(mCornerRadius);
        mAnimatingOutSurfaceView.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        mAnimatingOutSurfaceContainer.addView(mAnimatingOutSurfaceView);

        mAnimatingOutSurfaceContainer.setPadding(
                mExpandedViewPadding,
                mExpandedViewPadding,
                mExpandedViewPadding,
                mExpandedViewPadding);

        setUpManageMenu();

        setUpFlyout();
        mFlyoutTransitionSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_LOW)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        mFlyoutTransitionSpring.addEndListener(mAfterFlyoutTransitionSpring);

        final int targetSize = res.getDimensionPixelSize(R.dimen.dismiss_circle_size);
        mDismissTargetCircle = new DismissCircleView(context);
        final FrameLayout.LayoutParams newParams =
                new FrameLayout.LayoutParams(targetSize, targetSize);
        newParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDismissTargetCircle.setLayoutParams(newParams);
        mDismissTargetAnimator = PhysicsAnimator.getInstance(mDismissTargetCircle);

        mDismissTargetContainer = new FrameLayout(context);
        mDismissTargetContainer.setLayoutParams(new FrameLayout.LayoutParams(
                MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height),
                Gravity.BOTTOM));

        final int bottomMargin =
                getResources().getDimensionPixelSize(R.dimen.floating_dismiss_bottom_margin);
        mDismissTargetContainer.setPadding(0, 0, 0, bottomMargin);
        mDismissTargetContainer.setClipToPadding(false);
        mDismissTargetContainer.setClipChildren(false);
        mDismissTargetContainer.addView(mDismissTargetCircle);
        mDismissTargetContainer.setVisibility(View.INVISIBLE);
        mDismissTargetContainer.setBackgroundResource(
                R.drawable.floating_dismiss_gradient_transition);
        addView(mDismissTargetContainer);

        // Start translated down so the target springs up.
        mDismissTargetCircle.setTranslationY(
                getResources().getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height));

        final ContentResolver contentResolver = getContext().getContentResolver();
        final int dismissRadius = Settings.Secure.getInt(
                contentResolver, "bubble_dismiss_radius", mBubbleSize * 2 /* default */);

        // Save the MagneticTarget instance for the newly set up view - we'll add this to the
        // MagnetizedObjects.
        mMagneticTarget = new MagnetizedObject.MagneticTarget(mDismissTargetCircle, dismissRadius);

        setClipChildren(false);
        setFocusable(true);
        mBubbleContainer.bringToFront();

        setUpOverflow();

        mOnImeVisibilityChanged = onImeVisibilityChanged;
        mHideCurrentInputMethodCallback = hideCurrentInputMethodCallback;

        setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            onImeVisibilityChanged.accept(insets.getInsets(WindowInsets.Type.ime()).bottom > 0);

            if (!mIsExpanded || mIsExpansionAnimating) {
                return view.onApplyWindowInsets(insets);
            }
            mExpandedAnimationController.updateYPosition(
                    // Update the insets after we're done translating otherwise position
                    // calculation for them won't be correct.
                    () -> {
                        if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
                            mExpandedBubble.getExpandedView().updateInsets(insets);
                        }
                    });
            return view.onApplyWindowInsets(insets);
        });

        mOrientationChangedListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    mExpandedAnimationController.updateResources(mOrientation, mDisplaySize);
                    mStackAnimationController.updateResources(mOrientation);
                    mBubbleOverflow.updateDimensions();

                    // Need to update the padding around the view
                    WindowInsets insets = getRootWindowInsets();
                    int leftPadding = mExpandedViewPadding;
                    int rightPadding = mExpandedViewPadding;
                    if (insets != null) {
                        // Can't have the expanded view overlaying notches
                        int cutoutLeft = 0;
                        int cutoutRight = 0;
                        DisplayCutout cutout = insets.getDisplayCutout();
                        if (cutout != null) {
                            cutoutLeft = cutout.getSafeInsetLeft();
                            cutoutRight = cutout.getSafeInsetRight();
                        }
                        // Or overlaying nav or status bar
                        leftPadding += Math.max(cutoutLeft, insets.getStableInsetLeft());
                        rightPadding += Math.max(cutoutRight, insets.getStableInsetRight());
                    }
                    mExpandedViewContainer.setPadding(leftPadding, mExpandedViewPadding,
                            rightPadding, mExpandedViewPadding);

                    if (mIsExpanded) {
                        // Re-draw bubble row and pointer for new orientation.
                        beforeExpandedViewAnimation();
                        updateOverflowVisibility();
                        updatePointerPosition();
                        mExpandedAnimationController.expandFromStack(() -> {
                            afterExpandedViewAnimation();
                        } /* after */);
                        mExpandedViewContainer.setTranslationX(0);
                        mExpandedViewContainer.setTranslationY(getExpandedViewY());
                        mExpandedViewContainer.setAlpha(1f);
                    }
                    if (mRelativeStackPositionBeforeRotation != null) {
                        mStackAnimationController.setStackPosition(
                                mRelativeStackPositionBeforeRotation);
                        mRelativeStackPositionBeforeRotation = null;
                    }
                    removeOnLayoutChangeListener(mOrientationChangedListener);
                };

        // This must be a separate OnDrawListener since it should be called for every draw.
        getViewTreeObserver().addOnDrawListener(mSystemGestureExcludeUpdater);

        final ColorMatrix animatedMatrix = new ColorMatrix();
        final ColorMatrix darkenMatrix = new ColorMatrix();

        mDesaturateAndDarkenAnimator = ValueAnimator.ofFloat(1f, 0f);
        mDesaturateAndDarkenAnimator.addUpdateListener(animation -> {
            final float animatedValue = (float) animation.getAnimatedValue();
            animatedMatrix.setSaturation(animatedValue);

            final float animatedDarkenValue = (1f - animatedValue) * DARKEN_PERCENT;
            darkenMatrix.setScale(
                    1f - animatedDarkenValue /* red */,
                    1f - animatedDarkenValue /* green */,
                    1f - animatedDarkenValue /* blue */,
                    1f /* alpha */);

            // Concat the matrices so that the animatedMatrix both desaturates and darkens.
            animatedMatrix.postConcat(darkenMatrix);

            // Update the paint and apply it to the bubble container.
            mDesaturateAndDarkenPaint.setColorFilter(new ColorMatrixColorFilter(animatedMatrix));

            if (mDesaturateAndDarkenTargetView != null) {
                mDesaturateAndDarkenTargetView.setLayerPaint(mDesaturateAndDarkenPaint);
            }
        });

        // If the stack itself is touched, it means none of its touchable views (bubbles, flyouts,
        // ActivityViews, etc.) were touched. Collapse the stack if it's expanded.
        setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                if (mShowingManage) {
                    showManageMenu(false /* show */);
                } else if (mBubbleData.isExpanded()) {
                    mBubbleData.setExpanded(false);
                }
            }

            return true;
        });

        animate()
                .setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED)
                .setDuration(CollapsedStatusBarFragment.FADE_IN_DURATION);
    }

    /**
     * Sets whether or not the stack should become temporarily invisible by moving off the side of
     * the screen.
     *
     * If a flyout comes in while it's invisible, it will animate back in while the flyout is
     * showing but disappear again when the flyout is gone.
     */
    public void setTemporarilyInvisible(boolean invisible) {
        mTemporarilyInvisible = invisible;

        // If we are animating out, hide immediately if possible so we animate out with the status
        // bar.
        updateTemporarilyInvisibleAnimation(invisible /* hideImmediately */);
    }

    /**
     * Animates the stack to be temporarily invisible, if needed.
     *
     * If we're currently dragging the stack, or a flyout is visible, the stack will remain visible.
     * regardless of the value of {@link #mTemporarilyInvisible}. This method is called on ACTION_UP
     * as well as whenever a flyout hides, so we will animate invisible at that point if needed.
     */
    private void updateTemporarilyInvisibleAnimation(boolean hideImmediately) {
        removeCallbacks(mAnimateTemporarilyInvisibleImmediate);

        if (mIsDraggingStack) {
            // If we're dragging the stack, don't animate it invisible.
            return;
        }

        final boolean shouldHide =
                mTemporarilyInvisible && mFlyout.getVisibility() != View.VISIBLE;

        postDelayed(mAnimateTemporarilyInvisibleImmediate,
                shouldHide && !hideImmediately ? ANIMATE_TEMPORARILY_INVISIBLE_DELAY : 0);
    }

    private final Runnable mAnimateTemporarilyInvisibleImmediate = () -> {
        if (mTemporarilyInvisible && mFlyout.getVisibility() != View.VISIBLE) {
            if (mStackAnimationController.isStackOnLeftSide()) {
                animate().translationX(-mBubbleSize).start();
            } else {
                animate().translationX(mBubbleSize).start();
            }
        } else {
            animate().translationX(0).start();
        }
    };

    private void setUpManageMenu() {
        if (mManageMenu != null) {
            removeView(mManageMenu);
        }

        mManageMenu = (ViewGroup) LayoutInflater.from(getContext()).inflate(
                R.layout.bubble_manage_menu, this, false);
        mManageMenu.setVisibility(View.INVISIBLE);

        PhysicsAnimator.getInstance(mManageMenu).setDefaultSpringConfig(mManageSpringConfig);

        mManageMenu.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        mManageMenu.setClipToOutline(true);

        mManageMenu.findViewById(R.id.bubble_manage_menu_dismiss_container).setOnClickListener(
                view -> {
                    showManageMenu(false /* show */);
                    dismissBubbleIfExists(mBubbleData.getSelectedBubble());
                });

        mManageMenu.findViewById(R.id.bubble_manage_menu_dont_bubble_container).setOnClickListener(
                view -> {
                    showManageMenu(false /* show */);
                    mUnbubbleConversationCallback.accept(mBubbleData.getSelectedBubble().getKey());
                });

        mManageMenu.findViewById(R.id.bubble_manage_menu_settings_container).setOnClickListener(
                view -> {
                    showManageMenu(false /* show */);
                    final Bubble bubble = mBubbleData.getSelectedBubble();
                    if (bubble != null && mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                        final Intent intent = bubble.getSettingsIntent(mContext);
                        collapseStack(() -> {
                            mContext.startActivityAsUser(intent, bubble.getUser());
                            logBubbleEvent(bubble,
                                    SysUiStatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_SETTINGS);
                        });
                    }
                });

        mManageSettingsIcon = mManageMenu.findViewById(R.id.bubble_manage_menu_settings_icon);
        mManageSettingsText = mManageMenu.findViewById(R.id.bubble_manage_menu_settings_name);

        // The menu itself should respect locale direction so the icons are on the correct side.
        mManageMenu.setLayoutDirection(LAYOUT_DIRECTION_LOCALE);
        addView(mManageMenu);
    }

    private void setUpUserEducation() {
        if (mUserEducationView != null) {
            removeView(mUserEducationView);
        }
        mShouldShowUserEducation = shouldShowBubblesEducation();
        if (DEBUG_USER_EDUCATION) {
            Log.d(TAG, "shouldShowUserEducation: " + mShouldShowUserEducation);
        }
        if (mShouldShowUserEducation) {
            mUserEducationView = mInflater.inflate(R.layout.bubble_stack_user_education, this,
                    false /* attachToRoot */);
            mUserEducationView.setVisibility(GONE);

            final TypedArray ta = mContext.obtainStyledAttributes(
                    new int[] {android.R.attr.colorAccent,
                            android.R.attr.textColorPrimaryInverse});
            final int bgColor = ta.getColor(0, Color.BLACK);
            int textColor = ta.getColor(1, Color.WHITE);
            ta.recycle();
            textColor = ContrastColorUtil.ensureTextContrast(textColor, bgColor, true);

            TextView title = mUserEducationView.findViewById(R.id.user_education_title);
            TextView description = mUserEducationView.findViewById(R.id.user_education_description);
            title.setTextColor(textColor);
            description.setTextColor(textColor);

            updateUserEducationForLayoutDirection();
            addView(mUserEducationView);
        }

        if (mManageEducationView != null) {
            removeView(mManageEducationView);
        }
        mShouldShowManageEducation = shouldShowManageEducation();
        if (DEBUG_USER_EDUCATION) {
            Log.d(TAG, "shouldShowManageEducation: " + mShouldShowManageEducation);
        }
        if (mShouldShowManageEducation) {
            mManageEducationView = (BubbleManageEducationView)
                    mInflater.inflate(R.layout.bubbles_manage_button_education, this,
                            false /* attachToRoot */);
            mManageEducationView.setVisibility(GONE);
            mManageEducationView.setElevation(mBubbleElevation);
            mManageEducationView.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
            addView(mManageEducationView);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setUpFlyout() {
        if (mFlyout != null) {
            removeView(mFlyout);
        }
        mFlyout = new BubbleFlyoutView(getContext());
        mFlyout.setVisibility(GONE);
        mFlyout.animate()
                .setDuration(FLYOUT_ALPHA_ANIMATION_DURATION)
                .setInterpolator(new AccelerateDecelerateInterpolator());
        mFlyout.setOnClickListener(mFlyoutClickListener);
        mFlyout.setOnTouchListener(mFlyoutTouchListener);
        addView(mFlyout, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }

    private void setUpOverflow() {
        int overflowBtnIndex = 0;
        if (mBubbleOverflow == null) {
            mBubbleOverflow = new BubbleOverflow(getContext());
            mBubbleOverflow.setUpOverflow(mBubbleContainer, this);
        } else {
            mBubbleContainer.removeView(mBubbleOverflow.getIconView());
            mBubbleOverflow.setUpOverflow(mBubbleContainer, this);
            overflowBtnIndex = mBubbleContainer.getChildCount();
        }
        mBubbleContainer.addView(mBubbleOverflow.getIconView(), overflowBtnIndex,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        mBubbleOverflow.getIconView().setOnClickListener(v -> {
            setSelectedBubble(mBubbleOverflow);
            showManageMenu(false);
        });
        updateOverflowVisibility();
    }
    /**
     * Handle theme changes.
     */
    public void onThemeChanged() {
        setUpFlyout();
        setUpOverflow();
        setUpUserEducation();
        setUpManageMenu();
        updateExpandedViewTheme();
    }

    /** Respond to the phone being rotated by repositioning the stack and hiding any flyouts. */
    public void onOrientationChanged(int orientation) {
        mOrientation = orientation;

        // Display size is based on the rotation device was in when requested, we should update it
        // We use the real size & subtract screen decorations / window insets ourselves when needed
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealSize(mDisplaySize);

        // Some resources change depending on orientation
        Resources res = getContext().getResources();
        mStatusBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);

        mRelativeStackPositionBeforeRotation = mStackAnimationController.getRelativeStackPosition();
        addOnLayoutChangeListener(mOrientationChangedListener);
        hideFlyoutImmediate();

        mManageMenu.setVisibility(View.INVISIBLE);
        mShowingManage = false;
    }

    /** Tells the views with locale-dependent layout direction to resolve the new direction. */
    public void onLayoutDirectionChanged(int direction) {
        mManageMenu.setLayoutDirection(direction);
        mFlyout.setLayoutDirection(direction);
        if (mUserEducationView != null) {
            mUserEducationView.setLayoutDirection(direction);
            updateUserEducationForLayoutDirection();
        }
        if (mManageEducationView != null) {
            mManageEducationView.setLayoutDirection(direction);
        }
        updateExpandedViewDirection(direction);
    }

    /** Respond to the display size change by recalculating view size and location. */
    public void onDisplaySizeChanged() {
        setUpOverflow();

        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealSize(mDisplaySize);
        Resources res = getContext().getResources();
        mStatusBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleSize = getResources().getDimensionPixelSize(R.dimen.individual_bubble_size);
        for (Bubble b : mBubbleData.getBubbles()) {
            if (b.getIconView() == null) {
                Log.d(TAG, "Display size changed. Icon null: " + b);
                continue;
            }
            b.getIconView().setLayoutParams(new LayoutParams(mBubbleSize, mBubbleSize));
        }
        mExpandedAnimationController.updateResources(mOrientation, mDisplaySize);
        mStackAnimationController.updateResources(mOrientation);

        final int targetSize = res.getDimensionPixelSize(R.dimen.dismiss_circle_size);
        mDismissTargetCircle.getLayoutParams().width = targetSize;
        mDismissTargetCircle.getLayoutParams().height = targetSize;
        mDismissTargetCircle.requestLayout();

        mMagneticTarget.setMagneticFieldRadiusPx(mBubbleSize * 2);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);

        mTempRect.setEmpty();
        getTouchableRegion(mTempRect);
        inoutInfo.touchableRegion.set(mTempRect);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        if (mBubbleOverflow != null && mBubbleOverflow.getExpandedView() != null) {
            mBubbleOverflow.getExpandedView().cleanUpExpandedState();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        setupLocalMenu(info);
    }

    void updateExpandedViewTheme() {
        final List<Bubble> bubbles = mBubbleData.getBubbles();
        if (bubbles.isEmpty()) {
            return;
        }
        bubbles.forEach(bubble -> {
            if (bubble.getExpandedView() != null) {
                bubble.getExpandedView().applyThemeAttrs();
            }
        });
    }

    void updateExpandedViewDirection(int direction) {
        final List<Bubble> bubbles = mBubbleData.getBubbles();
        if (bubbles.isEmpty()) {
            return;
        }
        bubbles.forEach(bubble -> {
            if (bubble.getExpandedView() != null) {
                bubble.getExpandedView().setLayoutDirection(direction);
            }
        });
    }

    void setupLocalMenu(AccessibilityNodeInfo info) {
        Resources res = mContext.getResources();

        // Custom local actions.
        AccessibilityAction moveTopLeft = new AccessibilityAction(R.id.action_move_top_left,
                res.getString(R.string.bubble_accessibility_action_move_top_left));
        info.addAction(moveTopLeft);

        AccessibilityAction moveTopRight = new AccessibilityAction(R.id.action_move_top_right,
                res.getString(R.string.bubble_accessibility_action_move_top_right));
        info.addAction(moveTopRight);

        AccessibilityAction moveBottomLeft = new AccessibilityAction(R.id.action_move_bottom_left,
                res.getString(R.string.bubble_accessibility_action_move_bottom_left));
        info.addAction(moveBottomLeft);

        AccessibilityAction moveBottomRight = new AccessibilityAction(R.id.action_move_bottom_right,
                res.getString(R.string.bubble_accessibility_action_move_bottom_right));
        info.addAction(moveBottomRight);

        // Default actions.
        info.addAction(AccessibilityAction.ACTION_DISMISS);
        if (mIsExpanded) {
            info.addAction(AccessibilityAction.ACTION_COLLAPSE);
        } else {
            info.addAction(AccessibilityAction.ACTION_EXPAND);
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        final RectF stackBounds = mStackAnimationController.getAllowableStackPositionRegion();

        // R constants are not final so we cannot use switch-case here.
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            mBubbleData.dismissAll(BubbleController.DISMISS_ACCESSIBILITY_ACTION);
            announceForAccessibility(
                    getResources().getString(R.string.accessibility_bubble_dismissed));
            return true;
        } else if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            mBubbleData.setExpanded(false);
            return true;
        } else if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            mBubbleData.setExpanded(true);
            return true;
        } else if (action == R.id.action_move_top_left) {
            mStackAnimationController.springStackAfterFling(stackBounds.left, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_top_right) {
            mStackAnimationController.springStackAfterFling(stackBounds.right, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_bottom_left) {
            mStackAnimationController.springStackAfterFling(stackBounds.left, stackBounds.bottom);
            return true;
        } else if (action == R.id.action_move_bottom_right) {
            mStackAnimationController.springStackAfterFling(stackBounds.right, stackBounds.bottom);
            return true;
        }
        return false;
    }

    /**
     * Update content description for a11y TalkBack.
     */
    public void updateContentDescription() {
        if (mBubbleData.getBubbles().isEmpty()) {
            return;
        }

        for (int i = 0; i < mBubbleData.getBubbles().size(); i++) {
            final Bubble bubble = mBubbleData.getBubbles().get(i);
            final String appName = bubble.getAppName();

            String titleStr = bubble.getTitle();
            if (titleStr == null) {
                titleStr = getResources().getString(R.string.notification_bubble_title);
            }

            if (bubble.getIconView() != null) {
                if (mIsExpanded || i > 0) {
                    bubble.getIconView().setContentDescription(getResources().getString(
                            R.string.bubble_content_description_single, titleStr, appName));
                } else {
                    final int moreCount = mBubbleContainer.getChildCount() - 1;
                    bubble.getIconView().setContentDescription(getResources().getString(
                            R.string.bubble_content_description_stack,
                            titleStr, appName, moreCount));
                }
            }
        }
    }

    private void updateSystemGestureExcludeRects() {
        // Exclude the region occupied by the first BubbleView in the stack
        Rect excludeZone = mSystemGestureExclusionRects.get(0);
        if (getBubbleCount() > 0) {
            View firstBubble = mBubbleContainer.getChildAt(0);
            excludeZone.set(firstBubble.getLeft(), firstBubble.getTop(), firstBubble.getRight(),
                    firstBubble.getBottom());
            excludeZone.offset((int) (firstBubble.getTranslationX() + 0.5f),
                    (int) (firstBubble.getTranslationY() + 0.5f));
            mBubbleContainer.setSystemGestureExclusionRects(mSystemGestureExclusionRects);
        } else {
            excludeZone.setEmpty();
            mBubbleContainer.setSystemGestureExclusionRects(Collections.emptyList());
        }
    }

    /**
     * Sets the listener to notify when the bubble stack is expanded.
     */
    public void setExpandListener(BubbleController.BubbleExpandListener listener) {
        mExpandListener = listener;
    }

    /** Sets the function to call to un-bubble the given conversation. */
    public void setUnbubbleConversationCallback(
            Consumer<String> unbubbleConversationCallback) {
        mUnbubbleConversationCallback = unbubbleConversationCallback;
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    /**
     * Whether the stack of bubbles is animating to or from expansion.
     */
    public boolean isExpansionAnimating() {
        return mIsExpansionAnimating;
    }

    /**
     * The {@link BadgedImageView} that is expanded, null if one does not exist.
     */
    View getExpandedBubbleView() {
        return mExpandedBubble != null ? mExpandedBubble.getIconView() : null;
    }

    /**
     * The {@link Bubble} that is expanded, null if one does not exist.
     */
    @Nullable
    BubbleViewProvider getExpandedBubble() {
        return mExpandedBubble;
    }

    // via BubbleData.Listener
    @SuppressLint("ClickableViewAccessibility")
    void addBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "addBubble: " + bubble);
        }

        if (getBubbleCount() == 0 && mShouldShowUserEducation) {
            // Override the default stack position if we're showing user education.
            mStackAnimationController.setStackPosition(
                    mStackAnimationController.getStartPosition());
        }

        if (getBubbleCount() == 0) {
            mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
        }

        if (bubble.getIconView() == null) {
            return;
        }

        // Set the dot position to the opposite of the side the stack is resting on, since the stack
        // resting slightly off-screen would result in the dot also being off-screen.
        bubble.getIconView().setDotPositionOnLeft(
                !mStackOnLeftOrWillBe /* onLeft */, false /* animate */);

        bubble.getIconView().setOnClickListener(mBubbleClickListener);
        bubble.getIconView().setOnTouchListener(mBubbleTouchListener);

        mBubbleContainer.addView(bubble.getIconView(), 0,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__POSTED);
    }

    // via BubbleData.Listener
    void removeBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "removeBubble: " + bubble);
        }
        // Remove it from the views
        for (int i = 0; i < getBubbleCount(); i++) {
            View v = mBubbleContainer.getChildAt(i);
            if (v instanceof BadgedImageView
                    && ((BadgedImageView) v).getKey().equals(bubble.getKey())) {
                mBubbleContainer.removeViewAt(i);
                bubble.cleanupViews();
                updatePointerPosition();
                logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__DISMISSED);
                return;
            }
        }
        Log.d(TAG, "was asked to remove Bubble, but didn't find the view! " + bubble);
    }

    private void updateOverflowVisibility() {
        if (mBubbleOverflow == null) {
            return;
        }
        mBubbleOverflow.setVisible(mIsExpanded ? VISIBLE : GONE);
    }

    // via BubbleData.Listener
    void updateBubble(Bubble bubble) {
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__UPDATED);
    }

    public void updateBubbleOrder(List<Bubble> bubbles) {
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble bubble = bubbles.get(i);
            mBubbleContainer.reorderView(bubble.getIconView(), i);
        }
        updateBubbleZOrdersAndDotPosition(false /* animate */);
        updatePointerPosition();
    }

    /**
     * Changes the currently selected bubble. If the stack is already expanded, the newly selected
     * bubble will be shown immediately. This does not change the expanded state or change the
     * position of any bubble.
     */
    // via BubbleData.Listener
    public void setSelectedBubble(@Nullable BubbleViewProvider bubbleToSelect) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setSelectedBubble: " + bubbleToSelect);
        }

        if (bubbleToSelect == null) {
            mBubbleData.setShowingOverflow(false);
            return;
        }

        // Ignore this new bubble only if it is the exact same bubble object. Otherwise, we'll want
        // to re-render it even if it has the same key (equals() returns true). If the currently
        // expanded bubble is removed and instantly re-added, we'll get back a new Bubble instance
        // with the same key (with newly inflated expanded views), and we need to render those new
        // views.
        if (mExpandedBubble == bubbleToSelect) {
            return;
        }

        if (bubbleToSelect.getKey() == BubbleOverflow.KEY) {
            mBubbleData.setShowingOverflow(true);
        } else {
            mBubbleData.setShowingOverflow(false);
        }

        if (mIsExpanded && mIsExpansionAnimating) {
            // If the bubble selection changed during the expansion animation, the expanding bubble
            // probably crashed or immediately removed itself (or, we just got unlucky with a new
            // auto-expanding bubble showing up at just the right time). Cancel the animations so we
            // can start fresh.
            cancelAllExpandCollapseSwitchAnimations();
        }

        // If we're expanded, screenshot the currently expanded bubble (before expanding the newly
        // selected bubble) so we can animate it out.
        if (mIsExpanded && mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
            if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
                // Before screenshotting, have the real ActivityView show on top of other surfaces
                // so that the screenshot doesn't flicker on top of it.
                mExpandedBubble.getExpandedView().setSurfaceZOrderedOnTop(true);
            }

            try {
                screenshotAnimatingOutBubbleIntoSurface((success) -> {
                    mAnimatingOutSurfaceContainer.setVisibility(
                            success ? View.VISIBLE : View.INVISIBLE);
                    showNewlySelectedBubble(bubbleToSelect);
                });
            } catch (Exception e) {
                showNewlySelectedBubble(bubbleToSelect);
                e.printStackTrace();
            }
        } else {
            showNewlySelectedBubble(bubbleToSelect);
        }
    }

    private void showNewlySelectedBubble(BubbleViewProvider bubbleToSelect) {
        final BubbleViewProvider previouslySelected = mExpandedBubble;
        mExpandedBubble = bubbleToSelect;
        updatePointerPosition();

        if (mIsExpanded) {
            hideCurrentInputMethod();

            // Make the container of the expanded view transparent before removing the expanded view
            // from it. Otherwise a punch hole created by {@link android.view.SurfaceView} in the
            // expanded view becomes visible on the screen. See b/126856255
            mExpandedViewContainer.setAlpha(0.0f);
            mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                if (previouslySelected != null) {
                    previouslySelected.setContentVisibility(false);
                }

                updateExpandedBubble();
                requestUpdate();

                logBubbleEvent(previouslySelected,
                        SysUiStatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
                logBubbleEvent(bubbleToSelect, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
                notifyExpansionChanged(previouslySelected, false /* expanded */);
                notifyExpansionChanged(bubbleToSelect, true /* expanded */);
            });
        }
    }

    /**
     * Changes the expanded state of the stack.
     *
     * @param shouldExpand whether the bubble stack should appear expanded
     */
    // via BubbleData.Listener
    public void setExpanded(boolean shouldExpand) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setExpanded: " + shouldExpand);
        }

        if (!shouldExpand) {
            // If we're collapsing, release the animating-out surface immediately since we have no
            // need for it, and this ensures it cannot remain visible as we collapse.
            releaseAnimatingOutBubbleBuffer();
        }

        if (shouldExpand == mIsExpanded) {
            return;
        }

        hideCurrentInputMethod();

        mSysUiState
                .setFlag(QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED, shouldExpand)
                .commitUpdate(mContext.getDisplayId());

        if (mIsExpanded) {
            animateCollapse();
            logBubbleEvent(mExpandedBubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
        } else {
            animateExpansion();
            // TODO: move next line to BubbleData
            logBubbleEvent(mExpandedBubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
            logBubbleEvent(mExpandedBubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__STACK_EXPANDED);
        }
        notifyExpansionChanged(mExpandedBubble, mIsExpanded);
    }

    /**
     * If necessary, shows the user education view for the bubble stack. This appears the first
     * time a user taps on a bubble.
     *
     * @return true if user education was shown, false otherwise.
     */
    private boolean maybeShowStackUserEducation() {
        if (mShouldShowUserEducation && mUserEducationView.getVisibility() != VISIBLE) {
            mUserEducationView.setAlpha(0);
            mUserEducationView.setVisibility(VISIBLE);
            updateUserEducationForLayoutDirection();

            // Post so we have height of mUserEducationView
            mUserEducationView.post(() -> {
                final int viewHeight = mUserEducationView.getHeight();
                PointF stackPosition = mStackAnimationController.getStartPosition();
                final float translationY = stackPosition.y + (mBubbleSize / 2) - (viewHeight / 2);
                mUserEducationView.setTranslationY(translationY);
                mUserEducationView.animate()
                        .setDuration(ANIMATE_STACK_USER_EDUCATION_DURATION)
                        .setInterpolator(FAST_OUT_SLOW_IN)
                        .alpha(1);
            });
            Prefs.putBoolean(getContext(), HAS_SEEN_BUBBLES_EDUCATION, true);
            return true;
        }
        return false;
    }

    private void updateUserEducationForLayoutDirection() {
        if (mUserEducationView == null) {
            return;
        }
        LinearLayout textLayout =  mUserEducationView.findViewById(R.id.user_education_view);
        TextView title = mUserEducationView.findViewById(R.id.user_education_title);
        TextView description = mUserEducationView.findViewById(R.id.user_education_description);
        boolean isLtr =
                getResources().getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_LTR;
        if (isLtr) {
            mUserEducationView.setLayoutDirection(LAYOUT_DIRECTION_LTR);
            textLayout.setBackgroundResource(R.drawable.bubble_stack_user_education_bg);
            title.setGravity(Gravity.LEFT);
            description.setGravity(Gravity.LEFT);
        } else {
            mUserEducationView.setLayoutDirection(LAYOUT_DIRECTION_RTL);
            textLayout.setBackgroundResource(R.drawable.bubble_stack_user_education_bg_rtl);
            title.setGravity(Gravity.RIGHT);
            description.setGravity(Gravity.RIGHT);
        }
    }

    /**
     * If necessary, hides the user education view for the bubble stack.
     *
     * @param fromExpansion if true this indicates the hide is happening due to the bubble being
     *                      expanded, false if due to a touch outside of the bubble stack.
     */
    void hideStackUserEducation(boolean fromExpansion) {
        if (mShouldShowUserEducation
                && mUserEducationView.getVisibility() == VISIBLE
                && !mAnimatingEducationAway) {
            mAnimatingEducationAway = true;
            mUserEducationView.animate()
                    .alpha(0)
                    .setDuration(fromExpansion
                            ? ANIMATE_STACK_USER_EDUCATION_DURATION_SHORT
                            : ANIMATE_STACK_USER_EDUCATION_DURATION)
                    .withEndAction(() -> {
                        mAnimatingEducationAway = false;
                        mShouldShowUserEducation = shouldShowBubblesEducation();
                        mUserEducationView.setVisibility(GONE);
                    });
        }
    }

    /**
     * If necessary, toggles the user education view for the manage button. This is shown when the
     * bubble stack is expanded for the first time.
     *
     * @param show whether the user education view should show or not.
     */
    void maybeShowManageEducation(boolean show) {
        if (mManageEducationView == null) {
            return;
        }
        if (show
                && mShouldShowManageEducation
                && mManageEducationView.getVisibility() != VISIBLE
                && mIsExpanded
                && mExpandedBubble.getExpandedView() != null) {
            mManageEducationView.setAlpha(0);
            mManageEducationView.setVisibility(VISIBLE);
            mManageEducationView.post(() -> {
                mExpandedBubble.getExpandedView().getManageButtonBoundsOnScreen(mTempRect);
                final int viewHeight = mManageEducationView.getManageViewHeight();
                final int inset = getResources().getDimensionPixelSize(
                        R.dimen.bubbles_manage_education_top_inset);
                mManageEducationView.bringToFront();
                mManageEducationView.setManageViewPosition(0, mTempRect.top - viewHeight + inset);
                mManageEducationView.animate()
                        .setDuration(ANIMATE_STACK_USER_EDUCATION_DURATION)
                        .setInterpolator(FAST_OUT_SLOW_IN).alpha(1);
                mManageEducationView.findViewById(R.id.manage).setOnClickListener(view -> {
                            mExpandedBubble.getExpandedView().findViewById(R.id.settings_button)
                                    .performClick();
                            maybeShowManageEducation(false);
                        });
                mManageEducationView.findViewById(R.id.got_it).setOnClickListener(view ->
                        maybeShowManageEducation(false));
                mManageEducationView.setOnClickListener(view ->
                        maybeShowManageEducation(false));
            });
            Prefs.putBoolean(getContext(), HAS_SEEN_BUBBLES_MANAGE_EDUCATION, true);
        } else if (!show
                && mManageEducationView.getVisibility() == VISIBLE
                && !mAnimatingManageEducationAway) {
            mManageEducationView.animate()
                    .alpha(0)
                    .setDuration(mIsExpansionAnimating
                            ? ANIMATE_STACK_USER_EDUCATION_DURATION_SHORT
                            : ANIMATE_STACK_USER_EDUCATION_DURATION)
                    .withEndAction(() -> {
                        mAnimatingManageEducationAway = false;
                        mShouldShowManageEducation = shouldShowManageEducation();
                        mManageEducationView.setVisibility(GONE);
                    });
        }
    }

    /**
     * Dismiss the stack of bubbles.
     *
     * @deprecated
     */
    @Deprecated
    void stackDismissed(int reason) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "stackDismissed: reason=" + reason);
        }
        mBubbleData.dismissAll(reason);
        logBubbleEvent(null /* no bubble associated with bubble stack dismiss */,
                SysUiStatsLog.BUBBLE_UICHANGED__ACTION__STACK_DISMISSED);
    }

    /**
     * @deprecated use {@link #setExpanded(boolean)} and
     * {@link BubbleData#setSelectedBubble(Bubble)}
     */
    @Deprecated
    @MainThread
    void collapseStack(Runnable endRunnable) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "collapseStack(endRunnable)");
        }
        mBubbleData.setExpanded(false);
        // TODO - use the runnable at end of animation
        endRunnable.run();
    }

    void showExpandedViewContents(int displayId) {
        if (mExpandedBubble != null
                && mExpandedBubble.getExpandedView() != null
                && mExpandedBubble.getExpandedView().getVirtualDisplayId() == displayId) {
            mExpandedBubble.setContentVisibility(true);
        }
    }

    /**
     * Asks the BubbleController to hide the IME from anywhere, whether it's focused on Bubbles or
     * not.
     */
    void hideCurrentInputMethod() {
        mHideCurrentInputMethodCallback.run();
    }

    private void beforeExpandedViewAnimation() {
        mIsExpansionAnimating = true;
        hideFlyoutImmediate();
        updateExpandedBubble();
        updateExpandedView();
    }

    private void afterExpandedViewAnimation() {
        mIsExpansionAnimating = false;
        updateExpandedView();
        requestUpdate();
    }

    private void animateExpansion() {
        cancelDelayedExpandCollapseSwitchAnimations();

        mIsExpanded = true;
        hideStackUserEducation(true /* fromExpansion */);
        beforeExpandedViewAnimation();

        mBubbleContainer.setActiveController(mExpandedAnimationController);
        updateOverflowVisibility();
        updatePointerPosition();
        mExpandedAnimationController.expandFromStack(() -> {
            afterExpandedViewAnimation();
            maybeShowManageEducation(true);
        } /* after */);

        mExpandedViewContainer.setTranslationX(0);
        mExpandedViewContainer.setTranslationY(getExpandedViewY());
        mExpandedViewContainer.setAlpha(1f);

        // X-value of the bubble we're expanding, once it's settled in its row.
        final float bubbleWillBeAtX =
                mExpandedAnimationController.getBubbleLeft(
                        mBubbleData.getBubbles().indexOf(mExpandedBubble));

        // How far horizontally the bubble will be animating. We'll wait a bit longer for bubbles
        // that are animating farther, so that the expanded view doesn't move as much.
        final float horizontalDistanceAnimated =
                Math.abs(bubbleWillBeAtX
                        - mStackAnimationController.getStackPosition().x);

        // Wait for the path animation target to reach its end, and add a small amount of extra time
        // if the bubble is moving a lot horizontally.
        long startDelay = 0L;

        // Should not happen since we lay out before expanding, but just in case...
        if (getWidth() > 0) {
            startDelay = (long)
                    (ExpandedAnimationController.EXPAND_COLLAPSE_TARGET_ANIM_DURATION
                            + (horizontalDistanceAnimated / getWidth()) * 30);
        }

        // Set the pivot point for the scale, so the expanded view animates out from the bubble.
        mExpandedViewContainerMatrix.setScale(
                0f, 0f,
                bubbleWillBeAtX + mBubbleSize / 2f, getExpandedViewY());
        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);

        if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
            mExpandedBubble.getExpandedView().setSurfaceZOrderedOnTop(false);
        }

        mDelayedAnimationHandler.postDelayed(() -> {
            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                    .spring(AnimatableScaleMatrix.SCALE_X,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .spring(AnimatableScaleMatrix.SCALE_Y,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .addUpdateListener((target, values) -> {
                        if (mExpandedBubble == null || mExpandedBubble.getIconView() == null) {
                            return;
                        }
                        mExpandedViewContainerMatrix.postTranslate(
                                mExpandedBubble.getIconView().getTranslationX()
                                        - bubbleWillBeAtX,
                                0);
                        mExpandedViewContainer.setAnimationMatrix(
                                mExpandedViewContainerMatrix);
                    })
                    .withEndActions(() -> {
                        if (mExpandedBubble != null
                                && mExpandedBubble.getExpandedView() != null) {
                            mExpandedBubble.getExpandedView()
                                    .setContentVisibility(true);
                            mExpandedBubble.getExpandedView()
                                    .setSurfaceZOrderedOnTop(false);
                        }
                    })
                    .start();
        }, startDelay);
    }

    private void animateCollapse() {
        cancelDelayedExpandCollapseSwitchAnimations();

        // Hide the menu if it's visible.
        showManageMenu(false);

        mIsExpanded = false;
        mIsExpansionAnimating = true;

        mBubbleContainer.cancelAllAnimations();

        // If we were in the middle of swapping, the animating-out surface would have been scaling
        // to zero - finish it off.
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer).cancel();
        mAnimatingOutSurfaceContainer.setScaleX(0f);
        mAnimatingOutSurfaceContainer.setScaleY(0f);

        // Let the expanded animation controller know that it shouldn't animate child adds/reorders
        // since we're about to animate collapsed.
        mExpandedAnimationController.notifyPreparingToCollapse();

        final long startDelay =
                (long) (ExpandedAnimationController.EXPAND_COLLAPSE_TARGET_ANIM_DURATION * 0.6f);
        mDelayedAnimationHandler.postDelayed(() -> mExpandedAnimationController.collapseBackToStack(
                mStackAnimationController.getStackPositionAlongNearestHorizontalEdge()
                /* collapseTo */,
                () -> mBubbleContainer.setActiveController(mStackAnimationController)), startDelay);

        // We want to visually collapse into this bubble during the animation.
        final View expandingFromBubble = mExpandedBubble.getIconView();

        // X-value the bubble is animating from (back into the stack).
        final float expandingFromBubbleAtX =
                mExpandedAnimationController.getBubbleLeft(
                        mBubbleData.getBubbles().indexOf(mExpandedBubble));

        // Set the pivot point.
        mExpandedViewContainerMatrix.setScale(
                1f, 1f,
                expandingFromBubbleAtX + mBubbleSize / 2f,
                getExpandedViewY());

        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                .spring(AnimatableScaleMatrix.SCALE_X, 0f, mScaleOutSpringConfig)
                .spring(AnimatableScaleMatrix.SCALE_Y, 0f, mScaleOutSpringConfig)
                .addUpdateListener((target, values) -> {
                    if (expandingFromBubble != null) {
                        // Follow the bubble as it translates!
                        mExpandedViewContainerMatrix.postTranslate(
                                expandingFromBubble.getTranslationX()
                                        - expandingFromBubbleAtX, 0f);
                    }

                    mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);

                    // Hide early so we don't have a tiny little expanded view still visible at the
                    // end of the scale animation.
                    if (mExpandedViewContainerMatrix.getScaleX() < 0.05f) {
                        mExpandedViewContainer.setVisibility(View.INVISIBLE);
                    }
                })
                .withEndActions(() -> {
                    final BubbleViewProvider previouslySelected = mExpandedBubble;
                    beforeExpandedViewAnimation();
                    maybeShowManageEducation(false);

                    if (DEBUG_BUBBLE_STACK_VIEW) {
                        Log.d(TAG, "animateCollapse");
                        Log.d(TAG, BubbleDebugConfig.formatBubblesString(getBubblesOnScreen(),
                                mExpandedBubble));
                    }
                    updateOverflowVisibility();

                    afterExpandedViewAnimation();
                    if (previouslySelected != null) {
                        previouslySelected.setContentVisibility(false);
                    }
                })
                .start();
    }

    private void animateSwitchBubbles() {
        // If we're no longer expanded, this is meaningless.
        if (!mIsExpanded) {
            return;
        }

        mIsBubbleSwitchAnimating = true;

        // The surface contains a screenshot of the animating out bubble, so we just need to animate
        // it out (and then release the GraphicBuffer).
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer).cancel();
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer)
                .spring(DynamicAnimation.SCALE_X, 0f, mScaleOutSpringConfig)
                .spring(DynamicAnimation.SCALE_Y, 0f, mScaleOutSpringConfig)
                .spring(DynamicAnimation.TRANSLATION_Y,
                        mAnimatingOutSurfaceContainer.getTranslationY() - mBubbleSize * 2,
                        mTranslateSpringConfig)
                .withEndActions(this::releaseAnimatingOutBubbleBuffer)
                .start();

        boolean isOverflow = mExpandedBubble != null
                && mExpandedBubble.getKey().equals(BubbleOverflow.KEY);
        float expandingFromBubbleDestinationX =
                mExpandedAnimationController.getBubbleLeft(isOverflow ? getBubbleCount()
                        : mBubbleData.getBubbles().indexOf(mExpandedBubble));

        mExpandedViewContainer.setAlpha(1f);
        mExpandedViewContainer.setVisibility(View.VISIBLE);

        mExpandedViewContainerMatrix.setScale(
                0f, 0f, expandingFromBubbleDestinationX + mBubbleSize / 2f, getExpandedViewY());
        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);

        mDelayedAnimationHandler.postDelayed(() -> {
            if (!mIsExpanded) {
                mIsBubbleSwitchAnimating = false;
                return;
            }

            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                    .spring(AnimatableScaleMatrix.SCALE_X,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .spring(AnimatableScaleMatrix.SCALE_Y,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .addUpdateListener((target, values) -> {
                        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);
                    })
                    .withEndActions(() -> {
                        if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
                            mExpandedBubble.getExpandedView().setContentVisibility(true);
                            mExpandedBubble.getExpandedView().setSurfaceZOrderedOnTop(false);
                        }

                        mIsBubbleSwitchAnimating = false;
                    })
                    .start();
        }, 25);
    }

    /**
     * Cancels any delayed steps for expand/collapse and bubble switch animations, and resets the is
     * animating flags for those animations.
     */
    private void cancelDelayedExpandCollapseSwitchAnimations() {
        mDelayedAnimationHandler.removeCallbacksAndMessages(null);

        mIsExpansionAnimating = false;
        mIsBubbleSwitchAnimating = false;
    }

    private void cancelAllExpandCollapseSwitchAnimations() {
        cancelDelayedExpandCollapseSwitchAnimations();

        PhysicsAnimator.getInstance(mAnimatingOutSurfaceView).cancel();
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();

        mExpandedViewContainer.setAnimationMatrix(null);
    }

    private void notifyExpansionChanged(BubbleViewProvider bubble, boolean expanded) {
        if (mExpandListener != null && bubble != null) {
            mExpandListener.onBubbleExpandChanged(expanded, bubble.getKey());
        }
    }

    /** Return the BubbleView at the given index from the bubble container. */
    public BadgedImageView getBubbleAt(int i) {
        return getBubbleCount() > i
                ? (BadgedImageView) mBubbleContainer.getChildAt(i)
                : null;
    }

    /** Moves the bubbles out of the way if they're going to be over the keyboard. */
    public void onImeVisibilityChanged(boolean visible, int height) {
        mStackAnimationController.setImeHeight(visible ? height + mImeOffset : 0);

        if (!mIsExpanded && getBubbleCount() > 0) {
            final float stackDestinationY =
                    mStackAnimationController.animateForImeVisibility(visible);

            // How far the stack is animating due to IME, we'll just animate the flyout by that
            // much too.
            final float stackDy =
                    stackDestinationY - mStackAnimationController.getStackPosition().y;

            // If the flyout is visible, translate it along with the bubble stack.
            if (mFlyout.getVisibility() == VISIBLE) {
                PhysicsAnimator.getInstance(mFlyout)
                        .spring(DynamicAnimation.TRANSLATION_Y,
                                mFlyout.getTranslationY() + stackDy,
                                FLYOUT_IME_ANIMATION_SPRING_CONFIG)
                        .start();
            }
        }
    }

    /**
     * This method is called by {@link android.app.ActivityView} because the BubbleStackView has a
     * higher Z-index than the ActivityView (so that dragged-out bubbles are visible over the AV).
     * ActivityView is asking BubbleStackView to subtract the stack's bounds from the provided
     * touchable region, so that the ActivityView doesn't consume events meant for the stack. Due to
     * the special nature of ActivityView, it does not respect the standard
     * {@link #dispatchTouchEvent} and {@link #onInterceptTouchEvent} methods typically used for
     * this purpose.
     *
     * BubbleStackView is MATCH_PARENT, so that bubbles can be positioned via their translation
     * properties for performance reasons. This means that the default implementation of this method
     * subtracts the entirety of the screen from the ActivityView's touchable region, resulting in
     * it not receiving any touch events. This was previously addressed by returning false in the
     * stack's {@link View#canReceivePointerEvents()} method, but this precluded the use of any
     * touch handlers in the stack or its child views.
     *
     * To support touch handlers, we're overriding this method to leave the ActivityView's touchable
     * region alone. The only touchable part of the stack that can ever overlap the AV is a
     * dragged-out bubble that is animating back into the row of bubbles. It's not worth continually
     * updating the touchable region to allow users to grab a bubble while it completes its ~50ms
     * animation back to the bubble row.
     *
     * NOTE: Any future additions to the stack that obscure the ActivityView region will need their
     * bounds subtracted here in order to receive touch events.
     */
    @Override
    public void subtractObscuredTouchableRegion(Region touchableRegion, View view) {
        // If the notification shade is expanded, or the manage menu is open, or we are showing
        // manage bubbles user education, we shouldn't let the ActivityView steal any touch events
        // from any location.
        if (!mIsExpanded
                || mShowingManage
                || (mManageEducationView != null
                    && mManageEducationView.getVisibility() == VISIBLE)) {
            touchableRegion.setEmpty();
        }
    }

    /**
     * If you're here because you're not receiving touch events on a view that is a descendant of
     * BubbleStackView, and you think BSV is intercepting them - it's not! You need to subtract the
     * bounds of the view in question in {@link #subtractObscuredTouchableRegion}. The ActivityView
     * consumes all touch events within its bounds, even for views like the BubbleStackView that are
     * above it. It ignores typical view touch handling methods like this one and
     * dispatchTouchEvent.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN && ev.getActionIndex() != mPointerIndexDown) {
            // Ignore touches from additional pointer indices.
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mPointerIndexDown = ev.getActionIndex();
        } else if (ev.getAction() == MotionEvent.ACTION_UP
                || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            mPointerIndexDown = -1;
        }

        boolean dispatched = super.dispatchTouchEvent(ev);

        // If a new bubble arrives while the collapsed stack is being dragged, it will be positioned
        // at the front of the stack (under the touch position). Subsequent ACTION_MOVE events will
        // then be passed to the new bubble, which will not consume them since it hasn't received an
        // ACTION_DOWN yet. Work around this by passing MotionEvents directly to the touch handler
        // until the current gesture ends with an ACTION_UP event.
        if (!dispatched && !mIsExpanded && mIsGestureInProgress) {
            dispatched = mBubbleTouchListener.onTouch(this /* view */, ev);
        }

        mIsGestureInProgress =
                ev.getAction() != MotionEvent.ACTION_UP
                        && ev.getAction() != MotionEvent.ACTION_CANCEL;

        return dispatched;
    }

    void setFlyoutStateForDragLength(float deltaX) {
        // This shouldn't happen, but if it does, just wait until the flyout lays out. This method
        // is continually called.
        if (mFlyout.getWidth() <= 0) {
            return;
        }

        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        mFlyoutDragDeltaX = deltaX;

        final float collapsePercent =
                onLeft ? -deltaX / mFlyout.getWidth() : deltaX / mFlyout.getWidth();
        mFlyout.setCollapsePercent(Math.min(1f, Math.max(0f, collapsePercent)));

        // Calculate how to translate the flyout if it has been dragged too far in either direction.
        float overscrollTranslation = 0f;
        if (collapsePercent < 0f || collapsePercent > 1f) {
            // Whether we are more than 100% transitioned to the dot.
            final boolean overscrollingPastDot = collapsePercent > 1f;

            // Whether we are overscrolling physically to the left - this can either be pulling the
            // flyout away from the stack (if the stack is on the right) or pushing it to the left
            // after it has already become the dot.
            final boolean overscrollingLeft =
                    (onLeft && collapsePercent > 1f) || (!onLeft && collapsePercent < 0f);
            overscrollTranslation =
                    (overscrollingPastDot ? collapsePercent - 1f : collapsePercent * -1)
                            * (overscrollingLeft ? -1 : 1)
                            * (mFlyout.getWidth() / (FLYOUT_OVERSCROLL_ATTENUATION_FACTOR
                            // Attenuate the smaller dot less than the larger flyout.
                            / (overscrollingPastDot ? 2 : 1)));
        }

        mFlyout.setTranslationX(mFlyout.getRestingTranslationX() + overscrollTranslation);
    }

    /** Passes the MotionEvent to the magnetized object and returns true if it was consumed. */
    private boolean passEventToMagnetizedObject(MotionEvent event) {
        return mMagnetizedObject != null && mMagnetizedObject.maybeConsumeMotionEvent(event);
    }

    /**
     * Dismisses the magnetized object - either an individual bubble, if we're expanded, or the
     * stack, if we're collapsed.
     */
    private void dismissMagnetizedObject() {
        if (mIsExpanded) {
            final View draggedOutBubbleView = (View) mMagnetizedObject.getUnderlyingObject();
            dismissBubbleIfExists(mBubbleData.getBubbleWithView(draggedOutBubbleView));
        } else {
            mBubbleData.dismissAll(BubbleController.DISMISS_USER_GESTURE);
        }
    }

    private void dismissBubbleIfExists(@Nullable Bubble bubble) {
        if (bubble != null && mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
            mBubbleData.dismissBubbleWithKey(
                    bubble.getKey(), BubbleController.DISMISS_USER_GESTURE);
        }
    }

    /** Prepares and starts the desaturate/darken animation on the bubble stack. */
    private void animateDesaturateAndDarken(View targetView, boolean desaturateAndDarken) {
        mDesaturateAndDarkenTargetView = targetView;

        if (mDesaturateAndDarkenTargetView == null) {
            return;
        }

        if (desaturateAndDarken) {
            // Use the animated paint for the bubbles.
            mDesaturateAndDarkenTargetView.setLayerType(
                    View.LAYER_TYPE_HARDWARE, mDesaturateAndDarkenPaint);
            mDesaturateAndDarkenAnimator.removeAllListeners();
            mDesaturateAndDarkenAnimator.start();
        } else {
            mDesaturateAndDarkenAnimator.removeAllListeners();
            mDesaturateAndDarkenAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    // Stop using the animated paint.
                    resetDesaturationAndDarken();
                }
            });
            mDesaturateAndDarkenAnimator.reverse();
        }
    }

    private void resetDesaturationAndDarken() {

        mDesaturateAndDarkenAnimator.removeAllListeners();
        mDesaturateAndDarkenAnimator.cancel();

        if (mDesaturateAndDarkenTargetView != null) {
            mDesaturateAndDarkenTargetView.setLayerType(View.LAYER_TYPE_NONE, null);
            mDesaturateAndDarkenTargetView = null;
        }
    }

    /** Animates in the dismiss target. */
    private void springInDismissTargetMaybe() {
        if (mShowingDismiss) {
            return;
        }

        mShowingDismiss = true;

        mDismissTargetContainer.bringToFront();
        mDismissTargetContainer.setZ(Short.MAX_VALUE - 1);
        mDismissTargetContainer.setVisibility(VISIBLE);

        ((TransitionDrawable) mDismissTargetContainer.getBackground()).startTransition(
                DISMISS_TRANSITION_DURATION_MS);

        mDismissTargetAnimator.cancel();
        mDismissTargetAnimator
                .spring(DynamicAnimation.TRANSLATION_Y, 0f, mDismissTargetSpring)
                .start();
    }

    /**
     * Animates the dismiss target out, as well as the circle that encircles the bubbles, if they
     * were dragged into the target and encircled.
     */
    private void hideDismissTarget() {
        if (!mShowingDismiss) {
            return;
        }

        mShowingDismiss = false;

        ((TransitionDrawable) mDismissTargetContainer.getBackground()).reverseTransition(
                DISMISS_TRANSITION_DURATION_MS);

        mDismissTargetAnimator
                .spring(DynamicAnimation.TRANSLATION_Y, mDismissTargetContainer.getHeight(),
                        mDismissTargetSpring)
                .withEndActions(() -> mDismissTargetContainer.setVisibility(View.INVISIBLE))
                .start();
    }

    /** Animates the flyout collapsed (to dot), or the reverse, starting with the given velocity. */
    private void animateFlyoutCollapsed(boolean collapsed, float velX) {
        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        // If the flyout was tapped, we want a higher stiffness for the collapse animation so it's
        // faster.
        mFlyoutTransitionSpring.getSpring().setStiffness(
                (mBubbleToExpandAfterFlyoutCollapse != null)
                        ? SpringForce.STIFFNESS_MEDIUM
                        : SpringForce.STIFFNESS_LOW);
        mFlyoutTransitionSpring
                .setStartValue(mFlyoutDragDeltaX)
                .setStartVelocity(velX)
                .animateToFinalPosition(collapsed
                        ? (onLeft ? -mFlyout.getWidth() : mFlyout.getWidth())
                        : 0f);
    }

    /**
     * Calculates the y position of the expanded view when it is expanded.
     */
    float getExpandedViewY() {
        return getStatusBarHeight() + mBubbleSize + mBubblePaddingTop;
    }

    /**
     * Animates in the flyout for the given bubble, if available, and then hides it after some time.
     */
    @VisibleForTesting
    void animateInFlyoutForBubble(Bubble bubble) {
        Bubble.FlyoutMessage flyoutMessage = bubble.getFlyoutMessage();
        final BadgedImageView bubbleView = bubble.getIconView();
        if (flyoutMessage == null
                || flyoutMessage.message == null
                || !bubble.showFlyout()
                || (mUserEducationView != null && mUserEducationView.getVisibility() == VISIBLE)
                || isExpanded()
                || mIsExpansionAnimating
                || mIsGestureInProgress
                || mBubbleToExpandAfterFlyoutCollapse != null
                || bubbleView == null) {
            if (bubbleView != null) {
                bubbleView.removeDotSuppressionFlag(BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);
            }
            // Skip the message if none exists, we're expanded or animating expansion, or we're
            // about to expand a bubble from the previous tapped flyout, or if bubble view is null.
            return;
        }

        mFlyoutDragDeltaX = 0f;
        clearFlyoutOnHide();
        mAfterFlyoutHidden = () -> {
            // Null it out to ensure it runs once.
            mAfterFlyoutHidden = null;

            if (mBubbleToExpandAfterFlyoutCollapse != null) {
                // User tapped on the flyout and we should expand
                mBubbleData.setSelectedBubble(mBubbleToExpandAfterFlyoutCollapse);
                mBubbleData.setExpanded(true);
                mBubbleToExpandAfterFlyoutCollapse = null;
            }

            // Stop suppressing the dot now that the flyout has morphed into the dot.
            bubbleView.removeDotSuppressionFlag(
                    BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);

            mFlyout.setVisibility(INVISIBLE);

            // Hide the stack after a delay, if needed.
            updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
        };
        mFlyout.setVisibility(INVISIBLE);

        // Suppress the dot when we are animating the flyout.
        bubbleView.addDotSuppressionFlag(
                BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);

        // Start flyout expansion. Post in case layout isn't complete and getWidth returns 0.
        post(() -> {
            // An auto-expanding bubble could have been posted during the time it takes to
            // layout.
            if (isExpanded()) {
                return;
            }
            final Runnable expandFlyoutAfterDelay = () -> {
                mAnimateInFlyout = () -> {
                    mFlyout.setVisibility(VISIBLE);
                    updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
                    mFlyoutDragDeltaX =
                            mStackAnimationController.isStackOnLeftSide()
                                    ? -mFlyout.getWidth()
                                    : mFlyout.getWidth();
                    animateFlyoutCollapsed(false /* collapsed */, 0 /* velX */);
                    mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
                };
                mFlyout.postDelayed(mAnimateInFlyout, 200);
            };

            if (bubble.getIconView() == null) {
                return;
            }

            mFlyout.setupFlyoutStartingAsDot(flyoutMessage,
                    mStackAnimationController.getStackPosition(), getWidth(),
                    mStackAnimationController.isStackOnLeftSide(),
                    bubble.getIconView().getDotColor() /* dotColor */,
                    expandFlyoutAfterDelay /* onLayoutComplete */,
                    mAfterFlyoutHidden,
                    bubble.getIconView().getDotCenter(),
                    !bubble.showDot());
            mFlyout.bringToFront();
        });
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
        logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__FLYOUT);
    }

    /** Hide the flyout immediately and cancel any pending hide runnables. */
    private void hideFlyoutImmediate() {
        clearFlyoutOnHide();
        mFlyout.removeCallbacks(mAnimateInFlyout);
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.hideFlyout();
    }

    private void clearFlyoutOnHide() {
        mFlyout.removeCallbacks(mAnimateInFlyout);
        if (mAfterFlyoutHidden == null) {
            return;
        }
        mAfterFlyoutHidden.run();
        mAfterFlyoutHidden = null;
    }

    /**
     * Fills the Rect with the touchable region of the bubbles. This will be used by WindowManager
     * to decide which touch events go to Bubbles.
     *
     * Bubbles is below the status bar/notification shade but above application windows. If you're
     * trying to get touch events from the status bar or another higher-level window layer, you'll
     * need to re-order TYPE_BUBBLES in WindowManagerPolicy so that we have the opportunity to steal
     * them.
     */
    public void getTouchableRegion(Rect outRect) {
        if (mUserEducationView != null && mUserEducationView.getVisibility() == VISIBLE) {
            // When user education shows then capture all touches
            outRect.set(0, 0, getWidth(), getHeight());
            return;
        }

        if (!mIsExpanded) {
            if (getBubbleCount() > 0) {
                mBubbleContainer.getChildAt(0).getBoundsOnScreen(outRect);
                // Increase the touch target size of the bubble
                outRect.top -= mBubbleTouchPadding;
                outRect.left -= mBubbleTouchPadding;
                outRect.right += mBubbleTouchPadding;
                outRect.bottom += mBubbleTouchPadding;
            }
        } else {
            mBubbleContainer.getBoundsOnScreen(outRect);
        }

        if (mFlyout.getVisibility() == View.VISIBLE) {
            final Rect flyoutBounds = new Rect();
            mFlyout.getBoundsOnScreen(flyoutBounds);
            outRect.union(flyoutBounds);
        }
    }

    private int getStatusBarHeight() {
        if (getRootWindowInsets() != null) {
            WindowInsets insets = getRootWindowInsets();
            return Math.max(
                    mStatusBarHeight,
                    insets.getDisplayCutout() != null
                            ? insets.getDisplayCutout().getSafeInsetTop()
                            : 0);
        }

        return 0;
    }

    private void requestUpdate() {
        if (mViewUpdatedRequested || mIsExpansionAnimating) {
            return;
        }
        mViewUpdatedRequested = true;
        getViewTreeObserver().addOnPreDrawListener(mViewUpdater);
        invalidate();
    }

    private void showManageMenu(boolean show) {
        mShowingManage = show;

        // This should not happen, since the manage menu is only visible when there's an expanded
        // bubble. If we end up in this state, just hide the menu immediately.
        if (mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            mManageMenu.setVisibility(View.INVISIBLE);
            return;
        }

        // If available, update the manage menu's settings option with the expanded bubble's app
        // name and icon.
        if (show && mBubbleData.hasBubbleInStackWithKey(mExpandedBubble.getKey())) {
            final Bubble bubble = mBubbleData.getBubbleInStackWithKey(mExpandedBubble.getKey());
            mManageSettingsIcon.setImageDrawable(bubble.getBadgedAppIcon());
            mManageSettingsText.setText(getResources().getString(
                    R.string.bubbles_app_settings, bubble.getAppName()));
        }

        mExpandedBubble.getExpandedView().getManageButtonBoundsOnScreen(mTempRect);

        final boolean isLtr =
                getResources().getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_LTR;

        // When the menu is open, it should be at these coordinates. The menu pops out to the right
        // in LTR and to the left in RTL.
        final float targetX = isLtr ? mTempRect.left : mTempRect.right - mManageMenu.getWidth();
        final float targetY = mTempRect.bottom - mManageMenu.getHeight();

        final float xOffsetForAnimation = (isLtr ? 1 : -1) * mManageMenu.getWidth() / 4f;

        if (show) {
            mManageMenu.setScaleX(0.5f);
            mManageMenu.setScaleY(0.5f);
            mManageMenu.setTranslationX(targetX - xOffsetForAnimation);
            mManageMenu.setTranslationY(targetY + mManageMenu.getHeight() / 4f);
            mManageMenu.setAlpha(0f);

            PhysicsAnimator.getInstance(mManageMenu)
                    .spring(DynamicAnimation.ALPHA, 1f)
                    .spring(DynamicAnimation.SCALE_X, 1f)
                    .spring(DynamicAnimation.SCALE_Y, 1f)
                    .spring(DynamicAnimation.TRANSLATION_X, targetX)
                    .spring(DynamicAnimation.TRANSLATION_Y, targetY)
                    .withEndActions(() -> {
                        View child = mManageMenu.getChildAt(0);
                        child.requestAccessibilityFocus();
                    })
                    .start();

            mManageMenu.setVisibility(View.VISIBLE);
        } else {
            PhysicsAnimator.getInstance(mManageMenu)
                    .spring(DynamicAnimation.ALPHA, 0f)
                    .spring(DynamicAnimation.SCALE_X, 0.5f)
                    .spring(DynamicAnimation.SCALE_Y, 0.5f)
                    .spring(DynamicAnimation.TRANSLATION_X, targetX - xOffsetForAnimation)
                    .spring(DynamicAnimation.TRANSLATION_Y, targetY + mManageMenu.getHeight() / 4f)
                    .withEndActions(() -> mManageMenu.setVisibility(View.INVISIBLE))
                    .start();
        }

        // Update the AV's obscured touchable region for the new menu visibility state.
        mExpandedBubble.getExpandedView().updateObscuredTouchableRegion();
    }

    private void updateExpandedBubble() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedBubble()");
        }

        mExpandedViewContainer.removeAllViews();
        if (mIsExpanded && mExpandedBubble != null
                && mExpandedBubble.getExpandedView() != null) {
            BubbleExpandedView bev = mExpandedBubble.getExpandedView();
            bev.setContentVisibility(false);
            mExpandedViewContainerMatrix.setScaleX(0f);
            mExpandedViewContainerMatrix.setScaleY(0f);
            mExpandedViewContainerMatrix.setTranslate(0f, 0f);
            mExpandedViewContainer.setVisibility(View.INVISIBLE);
            mExpandedViewContainer.setAlpha(0f);
            mExpandedViewContainer.addView(bev);
            bev.setManageClickListener((view) -> showManageMenu(!mShowingManage));
            bev.populateExpandedView();

            if (!mIsExpansionAnimating) {
                mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                    post(this::animateSwitchBubbles);
                });
            }
        }
    }

    /**
     * Requests a snapshot from the currently expanded bubble's ActivityView and displays it in a
     * SurfaceView. This allows us to load a newly expanded bubble's Activity into the ActivityView,
     * while animating the (screenshot of the) previously selected bubble's content away.
     *
     * @param onComplete Callback to run once we're done here - called with 'false' if something
     *                   went wrong, or 'true' if the SurfaceView is now showing a screenshot of the
     *                   expanded bubble.
     */
    private void screenshotAnimatingOutBubbleIntoSurface(Consumer<Boolean> onComplete) {
        if (!mIsExpanded || mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            // You can't animate null.
            onComplete.accept(false);
            return;
        }

        final BubbleExpandedView animatingOutExpandedView = mExpandedBubble.getExpandedView();

        // Release the previous screenshot if it hasn't been released already.
        if (mAnimatingOutBubbleBuffer != null) {
            releaseAnimatingOutBubbleBuffer();
        }

        try {
            mAnimatingOutBubbleBuffer = animatingOutExpandedView.snapshotActivitySurface();
        } catch (Exception e) {
            // If we fail for any reason, print the stack trace and then notify the callback of our
            // failure. This is not expected to occur, but it's not worth crashing over.
            Log.wtf(TAG, e);
            onComplete.accept(false);
        }

        if (mAnimatingOutBubbleBuffer == null
                || mAnimatingOutBubbleBuffer.getGraphicBuffer() == null) {
            // While no exception was thrown, we were unable to get a snapshot.
            onComplete.accept(false);
            return;
        }

        // Make sure the surface container's properties have been reset.
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer).cancel();
        mAnimatingOutSurfaceContainer.setScaleX(1f);
        mAnimatingOutSurfaceContainer.setScaleY(1f);
        mAnimatingOutSurfaceContainer.setTranslationX(0);
        mAnimatingOutSurfaceContainer.setTranslationY(0);

        final int[] activityViewLocation =
                mExpandedBubble.getExpandedView().getActivityViewLocationOnScreen();
        final int[] surfaceViewLocation = mAnimatingOutSurfaceView.getLocationOnScreen();

        // Translate the surface to overlap the real ActivityView.
        mAnimatingOutSurfaceContainer.setTranslationY(
                activityViewLocation[1] - surfaceViewLocation[1]);

        // Set the width/height of the SurfaceView to match the snapshot.
        mAnimatingOutSurfaceView.getLayoutParams().width =
                mAnimatingOutBubbleBuffer.getGraphicBuffer().getWidth();
        mAnimatingOutSurfaceView.getLayoutParams().height =
                mAnimatingOutBubbleBuffer.getGraphicBuffer().getHeight();
        mAnimatingOutSurfaceView.requestLayout();

        // Post to wait for layout.
        post(() -> {
            // The buffer might have been destroyed if the user is mashing on bubbles, that's okay.
            if (mAnimatingOutBubbleBuffer.getGraphicBuffer().isDestroyed()) {
                onComplete.accept(false);
                return;
            }

            if (!mIsExpanded) {
                onComplete.accept(false);
                return;
            }

            // Attach the buffer! We're now displaying the snapshot.
            mAnimatingOutSurfaceView.getHolder().getSurface().attachAndQueueBufferWithColorSpace(
                    mAnimatingOutBubbleBuffer.getGraphicBuffer(),
                    mAnimatingOutBubbleBuffer.getColorSpace());

            mSurfaceSynchronizer.syncSurfaceAndRun(() -> post(() -> onComplete.accept(true)));
        });
    }

    /**
     * Releases the buffer containing the screenshot of the animating-out bubble, if it exists and
     * isn't yet destroyed.
     */
    private void releaseAnimatingOutBubbleBuffer() {
        if (mAnimatingOutBubbleBuffer != null
                && !mAnimatingOutBubbleBuffer.getGraphicBuffer().isDestroyed()) {
            mAnimatingOutBubbleBuffer.getGraphicBuffer().destroy();
        }
    }

    private void updateExpandedView() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedView: mIsExpanded=" + mIsExpanded);
        }

        mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
            mExpandedViewContainer.setTranslationY(getExpandedViewY());
            mExpandedBubble.getExpandedView().updateView(
                    mExpandedViewContainer.getLocationOnScreen());
        }

        mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
        updateBubbleZOrdersAndDotPosition(false);
    }

    /** Sets the appropriate Z-order and dot position for each bubble in the stack. */
    private void updateBubbleZOrdersAndDotPosition(boolean animate) {
        int bubbleCount = getBubbleCount();
        for (int i = 0; i < bubbleCount; i++) {
            BadgedImageView bv = (BadgedImageView) mBubbleContainer.getChildAt(i);
            bv.setZ((mMaxBubbles * mBubbleElevation) - i);

            // If the dot is on the left, and so is the stack, we need to change the dot position.
            if (bv.getDotPositionOnLeft() == mStackOnLeftOrWillBe) {
                bv.setDotPositionOnLeft(!mStackOnLeftOrWillBe, animate);
            }

            if (!mIsExpanded && i > 0) {
                // If we're collapsed and this bubble is behind other bubbles, suppress its dot.
                bv.addDotSuppressionFlag(
                        BadgedImageView.SuppressionFlag.BEHIND_STACK);
            } else {
                bv.removeDotSuppressionFlag(
                        BadgedImageView.SuppressionFlag.BEHIND_STACK);
            }
        }
    }

    private void updatePointerPosition() {
        if (mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            return;
        }
        int index = getBubbleIndex(mExpandedBubble);
        if (index == -1) {
            return;
        }
        float bubbleLeftFromScreenLeft = mExpandedAnimationController.getBubbleLeft(index);
        float halfBubble = mBubbleSize / 2f;
        float bubbleCenter = bubbleLeftFromScreenLeft + halfBubble;
        // Padding might be adjusted for insets, so get it directly from the view
        bubbleCenter -= mExpandedViewContainer.getPaddingLeft();
        mExpandedBubble.getExpandedView().setPointerPosition(bubbleCenter);
    }

    /**
     * @return the number of bubbles in the stack view.
     */
    public int getBubbleCount() {
        // Subtract 1 for the overflow button that is always in the bubble container.
        return mBubbleContainer.getChildCount() - 1;
    }

    /**
     * Finds the bubble index within the stack.
     *
     * @param provider the bubble view provider with the bubble to look up.
     * @return the index of the bubble view within the bubble stack. The range of the position
     * is between 0 and the bubble count minus 1.
     */
    int getBubbleIndex(@Nullable BubbleViewProvider provider) {
        if (provider == null) {
            return 0;
        }
        return mBubbleContainer.indexOfChild(provider.getIconView());
    }

    /**
     * @return the normalized x-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedXPosition() {
        return new BigDecimal(getStackPosition().x / mDisplaySize.x)
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    /**
     * @return the normalized y-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedYPosition() {
        return new BigDecimal(getStackPosition().y / mDisplaySize.y)
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    public void setStackStartPosition(RelativeStackPosition position) {
        mStackAnimationController.setStackStartPosition(position);
    }

    public PointF getStackPosition() {
        return mStackAnimationController.getStackPosition();
    }

    public RelativeStackPosition getRelativeStackPosition() {
        return mStackAnimationController.getRelativeStackPosition();
    }

    /**
     * Logs the bubble UI event.
     *
     * @param provider the bubble view provider that is being interacted on. Null value indicates
     *               that the user interaction is not specific to one bubble.
     * @param action the user interaction enum.
     */
    private void logBubbleEvent(@Nullable BubbleViewProvider provider, int action) {
        if (provider == null || provider.getKey().equals(BubbleOverflow.KEY)) {
            SysUiStatsLog.write(SysUiStatsLog.BUBBLE_UI_CHANGED,
                    mContext.getApplicationInfo().packageName,
                    provider == null ? null : BubbleOverflow.KEY /* notification channel */,
                    0 /* notification ID */,
                    0 /* bubble position */,
                    getBubbleCount(),
                    action,
                    getNormalizedXPosition(),
                    getNormalizedYPosition(),
                    false /* unread bubble */,
                    false /* on-going bubble */,
                    false /* isAppForeground (unused) */);
            return;
        }
        provider.logUIEvent(getBubbleCount(), action, getNormalizedXPosition(),
                getNormalizedYPosition(), getBubbleIndex(provider));
    }

    /**
     * Called when a back gesture should be directed to the Bubbles stack. When expanded,
     * a back key down/up event pair is forwarded to the bubble Activity.
     */
    boolean performBackPressIfNeeded() {
        if (!isExpanded() || mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            return false;
        }
        return mExpandedBubble.getExpandedView().performBackPressIfNeeded();
    }

    /** Whether the educational view should appear for bubbles. **/
    private boolean shouldShowBubblesEducation() {
        return BubbleDebugConfig.forceShowUserEducation(getContext())
                || !Prefs.getBoolean(getContext(), HAS_SEEN_BUBBLES_EDUCATION, false);
    }

    /** Whether the educational view should appear for the expanded view "manage" button. **/
    private boolean shouldShowManageEducation() {
        return BubbleDebugConfig.forceShowUserEducation(getContext())
                || !Prefs.getBoolean(getContext(), HAS_SEEN_BUBBLES_MANAGE_EDUCATION, false);
    }

    /** For debugging only */
    List<Bubble> getBubblesOnScreen() {
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < getBubbleCount(); i++) {
            View child = mBubbleContainer.getChildAt(i);
            if (child instanceof BadgedImageView) {
                String key = ((BadgedImageView) child).getKey();
                Bubble bubble = mBubbleData.getBubbleInStackWithKey(key);
                bubbles.add(bubble);
            }
        }
        return bubbles;
    }

    /**
     * Representation of stack position that uses relative properties rather than absolute
     * coordinates. This is used to maintain similar stack positions across configuration changes.
     */
    public static class RelativeStackPosition {
        /** Whether to place the stack at the leftmost allowed position. */
        private boolean mOnLeft;

        /**
         * How far down the vertically allowed region to place the stack. For example, if the stack
         * allowed region is between y = 100 and y = 1100 and this is 0.2f, we'll place the stack at
         * 100 + (0.2f * 1000) = 300.
         */
        private float mVerticalOffsetPercent;

        public RelativeStackPosition(boolean onLeft, float verticalOffsetPercent) {
            mOnLeft = onLeft;
            mVerticalOffsetPercent = clampVerticalOffsetPercent(verticalOffsetPercent);
        }

        /** Constructs a relative position given a region and a point in that region. */
        public RelativeStackPosition(PointF position, RectF region) {
            mOnLeft = position.x < region.width() / 2;
            mVerticalOffsetPercent =
                    clampVerticalOffsetPercent((position.y - region.top) / region.height());
        }

        /** Ensures that the offset percent is between 0f and 1f. */
        private float clampVerticalOffsetPercent(float offsetPercent) {
            return Math.max(0f, Math.min(1f, offsetPercent));
        }

        /**
         * Given an allowable stack position region, returns the point within that region
         * represented by this relative position.
         */
        public PointF getAbsolutePositionInRegion(RectF region) {
            return new PointF(
                    mOnLeft ? region.left : region.right,
                    region.top + mVerticalOffsetPercent * region.height());
        }
    }
}
