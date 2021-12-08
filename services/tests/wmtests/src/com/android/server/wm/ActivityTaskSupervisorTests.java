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

package com.android.server.wm;

import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;

import android.app.WaitResult;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.ConditionVariable;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.Display;

import androidx.test.filters.MediumTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link ActivityTaskSupervisor} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityTaskSupervisorTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityTaskSupervisorTests extends WindowTestsBase {
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    /**
     * Ensures that an activity is removed from the stopping activities list once it is resumed.
     */
    @Test
    public void testStoppingActivityRemovedWhenResumed() {
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        mSupervisor.mStoppingActivities.add(firstActivity);

        firstActivity.completeResumeLocked();

        assertFalse(mSupervisor.mStoppingActivities.contains(firstActivity));
    }

    /**
     * Assume an activity has been started with result code START_SUCCESS. And before it is drawn,
     * it launches another existing activity. This test ensures that waiting results are notified
     * or updated while the result code of next launch is TASK_TO_FRONT or DELIVERED_TO_TOP.
     */
    @Test
    public void testReportWaitingActivityLaunched() {
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        final ConditionVariable condition = new ConditionVariable();
        final WaitResult taskToFrontWait = new WaitResult();
        final ComponentName[] launchedComponent = { null };
        // Create a new thread so the waiting method in test can be notified.
        new Thread(() -> {
            synchronized (mAtm.mGlobalLock) {
                // Note that TASK_TO_FRONT doesn't unblock the waiting thread.
                mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity,
                        START_TASK_TO_FRONT);
                launchedComponent[0] = taskToFrontWait.who;
                // Assume that another task is brought to front because first activity launches it.
                mSupervisor.reportActivityLaunched(false /* timeout */, secondActivity,
                        100 /* totalTime */, WaitResult.LAUNCH_STATE_HOT);
            }
            condition.open();
        }).start();
        final ActivityMetricsLogger.LaunchingState launchingState =
                new ActivityMetricsLogger.LaunchingState();
        spyOn(launchingState);
        doReturn(true).when(launchingState).contains(eq(secondActivity));
        // The test case already runs inside global lock, so above thread can only execute after
        // this waiting method that releases the lock.
        mSupervisor.waitActivityVisibleOrLaunched(taskToFrontWait, firstActivity, launchingState);

        // Assert that the thread is finished.
        assertTrue(condition.block(TIMEOUT_MS));
        assertEquals(taskToFrontWait.result, START_TASK_TO_FRONT);
        assertEquals(taskToFrontWait.who, secondActivity.mActivityComponent);
        assertEquals(taskToFrontWait.launchState, WaitResult.LAUNCH_STATE_HOT);
        // START_TASK_TO_FRONT means that another component will be visible, so the component
        // should not be assigned as the first activity.
        assertNull(launchedComponent[0]);

        condition.close();
        final WaitResult deliverToTopWait = new WaitResult();
        new Thread(() -> {
            synchronized (mAtm.mGlobalLock) {
                // Put a noise which isn't tracked by the current wait result. The waiting procedure
                // should ignore it and keep waiting for the target activity.
                mSupervisor.reportActivityLaunched(false /* timeout */, mock(ActivityRecord.class),
                        1000 /* totalTime */, WaitResult.LAUNCH_STATE_COLD);
                // Assume that the first activity launches an existing top activity, so the waiting
                // thread should be unblocked.
                mSupervisor.reportWaitingActivityLaunchedIfNeeded(secondActivity,
                        START_DELIVERED_TO_TOP);
            }
            condition.open();
        }).start();
        mSupervisor.waitActivityVisibleOrLaunched(deliverToTopWait, firstActivity, launchingState);

        assertTrue(condition.block(TIMEOUT_MS));
        assertEquals(deliverToTopWait.result, START_DELIVERED_TO_TOP);
        assertEquals(deliverToTopWait.who, secondActivity.mActivityComponent);
        // The result state must be unknown because DELIVERED_TO_TOP means that the target activity
        // is already visible so there is no valid launch time.
        assertEquals(deliverToTopWait.launchState, WaitResult.LAUNCH_STATE_UNKNOWN);
    }

    /**
     * Ensures that {@link TaskChangeNotificationController} notifies only when an activity is
     * forced to resize on secondary display.
     */
    @Test
    public void testHandleNonResizableTaskOnSecondaryDisplay() {
        // Create an unresizable task on secondary display.
        final DisplayContent newDisplay = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        final Task stack = new TaskBuilder(mSupervisor)
                .setDisplay(newDisplay).setCreateActivity(true).build();
        final ActivityRecord unresizableActivity = stack.getTopNonFinishingActivity();
        final Task task = unresizableActivity.getTask();
        unresizableActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
        task.setResizeMode(unresizableActivity.info.resizeMode);

        final TaskChangeNotificationController taskChangeNotifier =
                mAtm.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        mSupervisor.handleNonResizableTaskIfNeeded(task, newDisplay.getWindowingMode(),
                newDisplay.getDefaultTaskDisplayArea(), stack);
        // The top activity is unresizable, so it should notify the activity is forced resizing.
        verify(taskChangeNotifier).notifyActivityForcedResizable(eq(task.mTaskId),
                eq(FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY),
                eq(unresizableActivity.packageName));
        reset(taskChangeNotifier);

        // Put a resizable activity on top of the unresizable task.
        final ActivityRecord resizableActivity = new ActivityBuilder(mAtm)
                .setTask(task).build();
        resizableActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;

        mSupervisor.handleNonResizableTaskIfNeeded(task, newDisplay.getWindowingMode(),
                newDisplay.getDefaultTaskDisplayArea(), stack);
        // For the resizable activity, it is no need to force resizing or dismiss the docked stack.
        verify(taskChangeNotifier, never()).notifyActivityForcedResizable(anyInt() /* taskId */,
                anyInt() /* reason */, anyString() /* packageName */);
        verify(taskChangeNotifier, never()).notifyActivityDismissingDockedRootTask();
    }

    /** Ensures that the calling package name passed to client complies with package visibility. */
    @Test
    public void testFilteredReferred() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setLaunchedFromPackage("other.package").setCreateTask(true).build();
        assertNotNull(activity.launchedFromPackage);
        try {
            mSupervisor.realStartActivityLocked(activity, activity.app, false /* andResume */,
                    false /* checkConfig */);
        } catch (RemoteException ignored) {
        }
        verify(activity).getFilteredReferrer(eq(activity.launchedFromPackage));

        activity.deliverNewIntentLocked(ActivityBuilder.DEFAULT_FAKE_UID,
                new Intent(), null /* intentGrants */, "other.package2");
        verify(activity).getFilteredReferrer(eq("other.package2"));
    }

    /**
     * Ensures that notify focus task changes.
     */
    @Test
    public void testNotifyTaskFocusChanged() {
        final ActivityRecord fullScreenActivityA = new ActivityBuilder(mAtm).setCreateTask(true)
                .build();
        final Task taskA = fullScreenActivityA.getTask();

        final TaskChangeNotificationController taskChangeNotifier =
                mAtm.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        mAtm.setResumedActivityUncheckLocked(fullScreenActivityA, "resumeA");
        verify(taskChangeNotifier).notifyTaskFocusChanged(eq(taskA.mTaskId) /* taskId */,
                eq(true) /* focused */);
        reset(taskChangeNotifier);

        final ActivityRecord fullScreenActivityB = new ActivityBuilder(mAtm).setCreateTask(true)
                .build();
        final Task taskB = fullScreenActivityB.getTask();

        mAtm.setResumedActivityUncheckLocked(fullScreenActivityB, "resumeB");
        verify(taskChangeNotifier).notifyTaskFocusChanged(eq(taskA.mTaskId) /* taskId */,
                eq(false) /* focused */);
        verify(taskChangeNotifier).notifyTaskFocusChanged(eq(taskB.mTaskId) /* taskId */,
                eq(true) /* focused */);
    }

    /**
     * Ensures that a trusted display can launch arbitrary activity and an untrusted display can't.
     */
    @Test
    public void testDisplayCanLaunchActivities() {
        final Display display = mDisplayContent.mDisplay;
        // An empty info without FLAG_ALLOW_EMBEDDED.
        final ActivityInfo activityInfo = new ActivityInfo();
        final int callingPid = 12345;
        final int callingUid = 12345;
        spyOn(display);

        doReturn(true).when(display).isTrusted();
        final boolean allowedOnTrusted = mSupervisor.isCallerAllowedToLaunchOnDisplay(callingPid,
                callingUid, display.getDisplayId(), activityInfo);

        assertThat(allowedOnTrusted).isTrue();

        doReturn(false).when(display).isTrusted();
        final boolean allowedOnUntrusted = mSupervisor.isCallerAllowedToLaunchOnDisplay(callingPid,
                callingUid, display.getDisplayId(), activityInfo);

        assertThat(allowedOnUntrusted).isFalse();
    }

    /**
     * We need to launch home again after user unlocked for those displays that do not have
     * encryption aware home app.
     */
    @Test
    public void testStartHomeAfterUserUnlocked() {
        mSupervisor.onUserUnlocked(0);
        waitHandlerIdle(mAtm.mH);
        verify(mRootWindowContainer, timeout(TIMEOUT_MS)).startHomeOnEmptyDisplays("userUnlocked");
    }
}
