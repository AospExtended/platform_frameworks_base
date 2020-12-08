/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_CONFIGS;
import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_WINDOW_CONFIGS;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.TaskDescription;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.ITaskOrganizerController;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Stores the TaskOrganizers associated with a given windowing mode and
 * their associated state.
 */
class TaskOrganizerController extends ITaskOrganizerController.Stub {
    private static final String TAG = "TaskOrganizerController";
    private static final LinkedList<IBinder> EMPTY_LIST = new LinkedList<>();

    /**
     * Masks specifying which configurations are important to report back to an organizer when
     * changed.
     */
    private static final int REPORT_CONFIGS = CONTROLLABLE_CONFIGS;
    private static final int REPORT_WINDOW_CONFIGS = CONTROLLABLE_WINDOW_CONFIGS;

    private final WindowManagerGlobalLock mGlobalLock;

    private class DeathRecipient implements IBinder.DeathRecipient {
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer) {
            mTaskOrganizer = organizer;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.remove(
                        mTaskOrganizer.asBinder());
                if (state != null) {
                    state.dispose();
                }
            }
        }
    }

    /**
     * A wrapper class around ITaskOrganizer to ensure that the calls are made in the right
     * lifecycle order since we may be updating the visibility of task surface controls in a pending
     * transaction before they are presented to the task org.
     */
    private class TaskOrganizerCallbacks {
        final WindowManagerService mService;
        final ITaskOrganizer mTaskOrganizer;
        final Consumer<Runnable> mDeferTaskOrgCallbacksConsumer;

        private final SurfaceControl.Transaction mTransaction;

        TaskOrganizerCallbacks(WindowManagerService wm, ITaskOrganizer taskOrg,
                Consumer<Runnable> deferTaskOrgCallbacksConsumer) {
            mService = wm;
            mDeferTaskOrgCallbacksConsumer = deferTaskOrgCallbacksConsumer;
            mTaskOrganizer = taskOrg;
            mTransaction = wm.mTransactionFactory.get();
        }

        IBinder getBinder() {
            return mTaskOrganizer.asBinder();
        }

        void onTaskAppeared(Task task) {
            final boolean visible = task.isVisible();
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                try {
                    SurfaceControl outSurfaceControl = new SurfaceControl(task.getSurfaceControl(),
                            "TaskOrganizerController.onTaskAppeared");
                    if (!task.mCreatedByOrganizer && !visible) {
                        // To prevent flashes, we hide the task prior to sending the leash to the
                        // task org if the task has previously hidden (ie. when entering PIP)
                        mTransaction.hide(outSurfaceControl);
                        mTransaction.apply();
                    }
                    mTaskOrganizer.onTaskAppeared(taskInfo, outSurfaceControl);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception sending onTaskAppeared callback", e);
                }
            });
        }


        void onTaskVanished(Task task) {
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                try {
                    mTaskOrganizer.onTaskVanished(taskInfo);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception sending onTaskVanished callback", e);
                }
            });
        }

        void onTaskInfoChanged(Task task, ActivityManager.RunningTaskInfo taskInfo) {
            if (!task.mCreatedByOrganizer && !task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared(), except for tasks created
                // by the organizer that don't receive that signal
                return;
            }
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                if (!task.isOrganized()) {
                    // This is safe to ignore if the task is no longer organized
                    return;
                }
                try {
                    mTaskOrganizer.onTaskInfoChanged(taskInfo);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception sending onTaskInfoChanged callback", e);
                }
            });
        }

        void onBackPressedOnTaskRoot(Task task) {
            if (!task.mCreatedByOrganizer && !task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared(), except for tasks created
                // by the organizer that don't receive that signal
                return;
            }
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                if (!task.isOrganized()) {
                    // This is safe to ignore if the task is no longer organized
                    return;
                }
                try {
                   mTaskOrganizer.onBackPressedOnTaskRoot(task.getTaskInfo());
                } catch (Exception e) {
                    Slog.e(TAG, "Exception sending onBackPressedOnTaskRoot callback", e);
                }
            });
        }
    }

    private class TaskOrganizerState {
        private final TaskOrganizerCallbacks mOrganizer;
        private final DeathRecipient mDeathRecipient;
        private final ArrayList<Task> mOrganizedTasks = new ArrayList<>();
        private final int mUid;
        private boolean mInterceptBackPressedOnTaskRoot;

        TaskOrganizerState(ITaskOrganizer organizer, int uid) {
            final Consumer<Runnable> deferTaskOrgCallbacksConsumer =
                    mDeferTaskOrgCallbacksConsumer != null
                            ? mDeferTaskOrgCallbacksConsumer
                            : mService.mWindowManager.mAnimator::addAfterPrepareSurfacesRunnable;
            mOrganizer = new TaskOrganizerCallbacks(mService.mWindowManager, organizer,
                    deferTaskOrgCallbacksConsumer);
            mDeathRecipient = new DeathRecipient(organizer);
            try {
                organizer.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskOrganizer failed to register death recipient");
            }
            mUid = uid;
        }

        void setInterceptBackPressedOnTaskRoot(boolean interceptBackPressed) {
            mInterceptBackPressedOnTaskRoot = interceptBackPressed;
        }

        void addTask(Task t) {
            if (t.mTaskAppearedSent) return;

            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }
            if (t.taskAppearedReady()) {
                t.mTaskAppearedSent = true;
                mOrganizer.onTaskAppeared(t);
            }
        }

        void removeTask(Task t) {
            if (t.mTaskAppearedSent) {
                t.migrateToNewSurfaceControl();
                t.mTaskAppearedSent = false;
                mOrganizer.onTaskVanished(t);
            }
            mOrganizedTasks.remove(t);
        }

        void dispose() {
            // Move organizer from managing specific windowing modes
            for (int i = mTaskOrganizersForWindowingMode.size() - 1; i >= 0; --i) {
                mTaskOrganizersForWindowingMode.valueAt(i).remove(mOrganizer.getBinder());
            }

            // Update tasks currently managed by this organizer to the next one available if
            // possible.
            while (!mOrganizedTasks.isEmpty()) {
                final Task t = mOrganizedTasks.get(0);
                t.updateTaskOrganizerState(true /* forceUpdate */);
                if (mOrganizedTasks.contains(t)) {
                    removeTask(t);
                }
            }

            // Remove organizer state after removing tasks so we get a chance to send
            // onTaskVanished.
            mTaskOrganizerStates.remove(asBinder());
        }

        void unlinkDeath() {
            mOrganizer.getBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    }

    private final SparseArray<LinkedList<IBinder>> mTaskOrganizersForWindowingMode =
            new SparseArray<>();
    private final HashMap<IBinder, TaskOrganizerState> mTaskOrganizerStates = new HashMap<>();
    private final WeakHashMap<Task, RunningTaskInfo> mLastSentTaskInfos = new WeakHashMap<>();
    private final ArrayList<Task> mPendingTaskInfoChanges = new ArrayList<>();

    private final ActivityTaskManagerService mService;

    private RunningTaskInfo mTmpTaskInfo;
    private Consumer<Runnable> mDeferTaskOrgCallbacksConsumer;

    TaskOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    private void enforceStackPermission(String func) {
        mService.mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, func);
    }

    /**
     * Specifies the consumer to run to defer the task org callbacks. Can be overridden while
     * testing to allow the callbacks to be sent synchronously.
     */
    @VisibleForTesting
    public void setDeferTaskOrgCallbacksConsumer(Consumer<Runnable> consumer) {
        mDeferTaskOrgCallbacksConsumer = consumer;
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
     * If there was already a TaskOrganizer for this windowing mode it will be evicted
     * but will continue to organize it's existing tasks.
     */
    @Override
    public void registerTaskOrganizer(ITaskOrganizer organizer, int windowingMode) {
        if (windowingMode == WINDOWING_MODE_PINNED) {
            if (!mService.mSupportsPictureInPicture) {
                throw new UnsupportedOperationException("Picture in picture is not supported on "
                        + "this device");
            }
        } else if (WindowConfiguration.isSplitScreenWindowingMode(windowingMode)) {
            if (!mService.mSupportsSplitScreenMultiWindow) {
                throw new UnsupportedOperationException("Split-screen is not supported on this "
                        + "device");
            }
        } else if (windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            if (!mService.mSupportsMultiWindow) {
                throw new UnsupportedOperationException("Multi-window is not supported on this "
                        + "device");
            }
        } else {
            throw new UnsupportedOperationException("As of now only Pinned/Split/Multiwindow"
                    + " windowing modes are supported for registerTaskOrganizer");
        }
        enforceStackPermission("registerTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (getTaskOrganizer(windowingMode) != null) {
                    Slog.w(TAG, "Task organizer already exists for windowing mode: "
                            + windowingMode);
                }

                LinkedList<IBinder> orgs = mTaskOrganizersForWindowingMode.get(windowingMode);
                if (orgs == null) {
                    orgs = new LinkedList<>();
                    mTaskOrganizersForWindowingMode.put(windowingMode, orgs);
                }
                orgs.add(organizer.asBinder());
                if (!mTaskOrganizerStates.containsKey(organizer.asBinder())) {
                    mTaskOrganizerStates.put(organizer.asBinder(),
                            new TaskOrganizerState(organizer, uid));
                }

                mService.mRootWindowContainer.forAllTasks((task) -> {
                    if (task.getWindowingMode() == windowingMode) {
                        task.updateTaskOrganizerState(true /* forceUpdate */);
                    }
                });
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterTaskOrganizer(ITaskOrganizer organizer) {
        enforceStackPermission("unregisterTaskOrganizer()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                if (state == null) {
                    return;
                }
                state.unlinkDeath();
                state.dispose();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    ITaskOrganizer getTaskOrganizer(int windowingMode) {
        final IBinder organizer =
                mTaskOrganizersForWindowingMode.get(windowingMode, EMPTY_LIST).peekLast();
        if (organizer == null) {
            return null;
        }
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer);
        if (state == null) {
            return null;
        }
        return state.mOrganizer.mTaskOrganizer;
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        state.addTask(task);
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        if (state != null) {
            state.removeTask(task);
        }
    }

    @Override
    public RunningTaskInfo createRootTask(int displayId, int windowingMode) {
        enforceStackPermission("createRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    return null;
                }

                final Task task = display.getDefaultTaskDisplayArea().createStack(windowingMode,
                        ACTIVITY_TYPE_UNDEFINED, false /* onTop */, null /* info */, new Intent(),
                        true /* createdByOrganizer */);
                RunningTaskInfo out = task.getTaskInfo();
                mLastSentTaskInfos.put(task, out);
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean deleteRootTask(WindowContainerToken token) {
        enforceStackPermission("deleteRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = WindowContainer.fromBinder(token.asBinder()).asTask();
                if (task == null) return false;
                if (!task.mCreatedByOrganizer) {
                    throw new IllegalArgumentException(
                            "Attempt to delete task not created by organizer task=" + task);
                }
                task.removeImmediately();
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void dispatchPendingTaskInfoChanges() {
        if (mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            return;
        }
        for (int i = 0, n = mPendingTaskInfoChanges.size(); i < n; ++i) {
            dispatchTaskInfoChanged(mPendingTaskInfoChanges.get(i), false /* force */);
        }
        mPendingTaskInfoChanges.clear();
    }

    void dispatchTaskInfoChanged(Task task, boolean force) {
        if (!force && mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            // Defer task info reporting while layout is deferred. This is because layout defer
            // blocks tend to do lots of re-ordering which can mess up animations in receivers.
            mPendingTaskInfoChanges.remove(task);
            mPendingTaskInfoChanges.add(task);
            return;
        }
        RunningTaskInfo lastInfo = mLastSentTaskInfos.get(task);
        if (mTmpTaskInfo == null) {
            mTmpTaskInfo = new RunningTaskInfo();
        }
        mTmpTaskInfo.configuration.unset();
        task.fillTaskInfo(mTmpTaskInfo);
        boolean changed = lastInfo == null
                || mTmpTaskInfo.topActivityType != lastInfo.topActivityType
                || mTmpTaskInfo.isResizeable != lastInfo.isResizeable
                || mTmpTaskInfo.pictureInPictureParams != lastInfo.pictureInPictureParams
                || !TaskDescription.equals(mTmpTaskInfo.taskDescription, lastInfo.taskDescription)
                || mTmpTaskInfo.requestedOrientation != lastInfo.requestedOrientation;
        if (!changed) {
            int cfgChanges = mTmpTaskInfo.configuration.diff(lastInfo.configuration);
            final int winCfgChanges = (cfgChanges & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                    ? (int) mTmpTaskInfo.configuration.windowConfiguration.diff(
                            lastInfo.configuration.windowConfiguration,
                            true /* compareUndefined */) : 0;
            if ((winCfgChanges & REPORT_WINDOW_CONFIGS) == 0) {
                cfgChanges &= ~ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
            }
            changed = (cfgChanges & REPORT_CONFIGS) != 0;
        }
        if (!(changed || force)) {
            return;
        }
        final RunningTaskInfo newInfo = mTmpTaskInfo;
        mLastSentTaskInfos.put(task, mTmpTaskInfo);
        // Since we've stored this, clean up the reference so a new one will be created next time.
        // Transferring it this way means we only have to construct new RunningTaskInfos when they
        // change.
        mTmpTaskInfo = null;

        if (task.isOrganized()) {
            // Because we defer sending taskAppeared() until the app has drawn, we may receive a
            // configuration change before the state actually has the task registered. As such we
            // should ignore these change events to the organizer until taskAppeared(). If the task
            // was created by the organizer, then we always send the info change.
            final TaskOrganizerState state = mTaskOrganizerStates.get(
                    task.mTaskOrganizer.asBinder());
            if (state != null) {
                state.mOrganizer.onTaskInfoChanged(task, newInfo);
            }
        }
    }

    @Override
    public WindowContainerToken getImeTarget(int displayId) {
        enforceStackPermission("getImeTarget()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent dc = mService.mWindowManager.mRoot
                        .getDisplayContent(displayId);
                if (dc == null || dc.mInputMethodTarget == null) {
                    return null;
                }
                // Avoid WindowState#getRootTask() so we don't attribute system windows to a task.
                final Task task = dc.mInputMethodTarget.getTask();
                if (task == null) {
                    return null;
                }
                return task.getRootTask().mRemoteToken.toWindowContainerToken();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setLaunchRoot(int displayId, @Nullable WindowContainerToken token) {
        enforceStackPermission("setLaunchRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                TaskDisplayArea defaultTaskDisplayArea = mService.mRootWindowContainer
                        .getDisplayContent(displayId).getDefaultTaskDisplayArea();
                if (defaultTaskDisplayArea == null) {
                    return;
                }
                Task task = token == null
                        ? null : WindowContainer.fromBinder(token.asBinder()).asTask();
                if (task == null) {
                    defaultTaskDisplayArea.mLaunchRootTask = null;
                    return;
                }
                if (!task.mCreatedByOrganizer) {
                    throw new IllegalArgumentException("Attempt to set task not created by "
                            + "organizer as launch root task=" + task);
                }
                if (task.getDisplayArea() == null
                        || task.getDisplayArea().getDisplayId() != displayId) {
                    throw new RuntimeException("Can't set launch root for display " + displayId
                            + " to task on display " + task.getDisplayContent().getDisplayId());
                }
                task.getDisplayArea().mLaunchRootTask = task;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<RunningTaskInfo> getChildTasks(WindowContainerToken parent,
            @Nullable int[] activityTypes) {
        enforceStackPermission("getChildTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (parent == null) {
                    throw new IllegalArgumentException("Can't get children of null parent");
                }
                final WindowContainer container = WindowContainer.fromBinder(parent.asBinder());
                if (container == null) {
                    Slog.e(TAG, "Can't get children of " + parent + " because it is not valid.");
                    return null;
                }
                final Task task = container.asTask();
                if (task == null) {
                    Slog.e(TAG, container + " is not a task...");
                    return null;
                }
                // For now, only support returning children of tasks created by the organizer.
                if (!task.mCreatedByOrganizer) {
                    Slog.w(TAG, "Can only get children of root tasks created via createRootTask");
                    return null;
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                for (int i = task.getChildCount() - 1; i >= 0; --i) {
                    final Task child = task.getChildAt(i).asTask();
                    if (child == null) continue;
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, child.getActivityType())) {
                        continue;
                    }
                    out.add(child.getTaskInfo());
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<RunningTaskInfo> getRootTasks(int displayId, @Nullable int[] activityTypes) {
        enforceStackPermission("getRootTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc =
                        mService.mRootWindowContainer.getDisplayContent(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Display " + displayId + " doesn't exist");
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                for (int tdaNdx = dc.getTaskDisplayAreaCount() - 1; tdaNdx >= 0; --tdaNdx) {
                    final TaskDisplayArea taskDisplayArea = dc.getTaskDisplayAreaAt(tdaNdx);
                    for (int sNdx = taskDisplayArea.getStackCount() - 1; sNdx >= 0; --sNdx) {
                        final Task task = taskDisplayArea.getStackAt(sNdx);
                        if (activityTypes != null
                                && !ArrayUtils.contains(activityTypes, task.getActivityType())) {
                            continue;
                        }
                        out.add(task.getTaskInfo());
                    }
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setInterceptBackPressedOnTaskRoot(ITaskOrganizer organizer,
            boolean interceptBackPressed) {
        enforceStackPermission("setInterceptBackPressedOnTaskRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                if (state != null) {
                    state.setInterceptBackPressedOnTaskRoot(interceptBackPressed);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean handleInterceptBackPressedOnTaskRoot(Task task) {
        if (task == null || !task.isOrganized()) {
            return false;
        }

        final TaskOrganizerState state = mTaskOrganizerStates.get(task.mTaskOrganizer.asBinder());
        if (!state.mInterceptBackPressedOnTaskRoot) {
            return false;
        }

        state.mOrganizer.onBackPressedOnTaskRoot(task);
        return true;
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println("TaskOrganizerController:");
        pw.print(innerPrefix); pw.println("Per windowing mode:");
        for (int i = 0; i < mTaskOrganizersForWindowingMode.size(); i++) {
            final int windowingMode = mTaskOrganizersForWindowingMode.keyAt(i);
            final List<IBinder> taskOrgs = mTaskOrganizersForWindowingMode.valueAt(i);
            pw.println(innerPrefix + "  "
                    + WindowConfiguration.windowingModeToString(windowingMode) + ":");
            for (int j = 0; j < taskOrgs.size(); j++) {
                final TaskOrganizerState state =  mTaskOrganizerStates.get(taskOrgs.get(j));
                final ArrayList<Task> tasks = state.mOrganizedTasks;
                pw.print(innerPrefix + "    ");
                pw.println(state.mOrganizer.mTaskOrganizer + " uid=" + state.mUid + ":");
                for (int k = 0; k < tasks.size(); k++) {
                    pw.println(innerPrefix + "      " + tasks.get(k));
                }
            }

        }
        pw.println();
    }
}
