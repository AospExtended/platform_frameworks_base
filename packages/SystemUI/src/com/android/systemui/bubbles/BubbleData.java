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
package com.android.systemui.bubbles;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_DATA;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.util.Log;
import android.util.Pair;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController.DismissReason;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of active bubbles.
 */
@Singleton
public class BubbleData {

    private BubbleLogger mLogger = new BubbleLoggerImpl();

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleData" : TAG_BUBBLES;

    private static final Comparator<Bubble> BUBBLES_BY_SORT_KEY_DESCENDING =
            Comparator.comparing(BubbleData::sortKey).reversed();

    /** Contains information about changes that have been made to the state of bubbles. */
    static final class Update {
        boolean expandedChanged;
        boolean selectionChanged;
        boolean orderChanged;
        boolean expanded;
        @Nullable Bubble selectedBubble;
        @Nullable Bubble addedBubble;
        @Nullable Bubble updatedBubble;
        // Pair with Bubble and @DismissReason Integer
        final List<Pair<Bubble, Integer>> removedBubbles = new ArrayList<>();

        // A read-only view of the bubbles list, changes there will be reflected here.
        final List<Bubble> bubbles;
        final List<Bubble> overflowBubbles;

        private Update(List<Bubble> row, List<Bubble> overflow) {
            bubbles = Collections.unmodifiableList(row);
            overflowBubbles = Collections.unmodifiableList(overflow);
        }

        boolean anythingChanged() {
            return expandedChanged
                    || selectionChanged
                    || addedBubble != null
                    || updatedBubble != null
                    || !removedBubbles.isEmpty()
                    || orderChanged;
        }

        void bubbleRemoved(Bubble bubbleToRemove, @DismissReason  int reason) {
            removedBubbles.add(new Pair<>(bubbleToRemove, reason));
        }
    }

    /**
     * This interface reports changes to the state and appearance of bubbles which should be applied
     * as necessary to the UI.
     */
    interface Listener {
        /** Reports changes have have occurred as a result of the most recent operation. */
        void applyUpdate(Update update);
    }

    interface TimeSource {
        long currentTimeMillis();
    }

    private final Context mContext;
    /** Bubbles that are actively in the stack. */
    private final List<Bubble> mBubbles;
    /** Bubbles that aged out to overflow. */
    private final List<Bubble> mOverflowBubbles;
    /** Bubbles that are being loaded but haven't been added to the stack just yet. */
    private final HashMap<String, Bubble> mPendingBubbles;
    private Bubble mSelectedBubble;
    private boolean mShowingOverflow;
    private boolean mExpanded;
    private final int mMaxBubbles;
    private int mMaxOverflowBubbles;

    // State tracked during an operation -- keeps track of what listener events to dispatch.
    private Update mStateChange;

    private TimeSource mTimeSource = System::currentTimeMillis;

    @Nullable
    private Listener mListener;

    @Nullable
    private BubbleController.NotificationSuppressionChangedListener mSuppressionListener;
    private BubbleController.PendingIntentCanceledListener mCancelledListener;

    /**
     * We track groups with summaries that aren't visibly displayed but still kept around because
     * the bubble(s) associated with the summary still exist.
     *
     * The summary must be kept around so that developers can cancel it (and hence the bubbles
     * associated with it). This list is used to check if the summary should be hidden from the
     * shade.
     *
     * Key: group key of the NotificationEntry
     * Value: key of the NotificationEntry
     */
    private HashMap<String, String> mSuppressedGroupKeys = new HashMap<>();

    @Inject
    public BubbleData(Context context) {
        mContext = context;
        mBubbles = new ArrayList<>();
        mOverflowBubbles = new ArrayList<>();
        mPendingBubbles = new HashMap<>();
        mStateChange = new Update(mBubbles, mOverflowBubbles);
        mMaxBubbles = mContext.getResources().getInteger(R.integer.bubbles_max_rendered);
        mMaxOverflowBubbles = mContext.getResources().getInteger(R.integer.bubbles_max_overflow);
    }

    public void setSuppressionChangedListener(
            BubbleController.NotificationSuppressionChangedListener listener) {
        mSuppressionListener = listener;
    }

    public void setPendingIntentCancelledListener(
            BubbleController.PendingIntentCanceledListener listener) {
        mCancelledListener = listener;
    }

    public boolean hasBubbles() {
        return !mBubbles.isEmpty();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public boolean hasAnyBubbleWithKey(String key) {
        return hasBubbleInStackWithKey(key) || hasOverflowBubbleWithKey(key);
    }

    public boolean hasBubbleInStackWithKey(String key) {
        return getBubbleInStackWithKey(key) != null;
    }

    public boolean hasOverflowBubbleWithKey(String key) {
        return getOverflowBubbleWithKey(key) != null;
    }

    @Nullable
    public Bubble getSelectedBubble() {
        return mSelectedBubble;
    }

    public void setExpanded(boolean expanded) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setExpanded: " + expanded);
        }
        setExpandedInternal(expanded);
        dispatchPendingChanges();
    }

    public void setSelectedBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubble: " + bubble);
        }
        setSelectedBubbleInternal(bubble);
        dispatchPendingChanges();
    }

    void setShowingOverflow(boolean showingOverflow) {
        mShowingOverflow = showingOverflow;
    }

    /**
     * Constructs a new bubble or returns an existing one. Does not add new bubbles to
     * bubble data, must go through {@link #notificationEntryUpdated(Bubble, boolean, boolean)}
     * for that.
     *
     * @param entry The notification entry to use, only null if it's a bubble being promoted from
     *              the overflow that was persisted over reboot.
     * @param persistedBubble The bubble to use, only non-null if it's a bubble being promoted from
     *              the overflow that was persisted over reboot.
     */
    Bubble getOrCreateBubble(NotificationEntry entry, Bubble persistedBubble) {
        String key = entry != null ? entry.getKey() : persistedBubble.getKey();
        Bubble bubbleToReturn = getBubbleInStackWithKey(key);

        if (bubbleToReturn == null) {
            bubbleToReturn = getOverflowBubbleWithKey(key);
            if (bubbleToReturn != null) {
                // Promoting from overflow
                mOverflowBubbles.remove(bubbleToReturn);
            } else if (mPendingBubbles.containsKey(key)) {
                // Update while it was pending
                bubbleToReturn = mPendingBubbles.get(key);
            } else if (entry != null) {
                // New bubble
                bubbleToReturn = new Bubble(entry, mSuppressionListener, mCancelledListener);
            } else {
                // Persisted bubble being promoted
                bubbleToReturn = persistedBubble;
            }
        }

        if (entry != null) {
            bubbleToReturn.setEntry(entry);
        }
        mPendingBubbles.put(key, bubbleToReturn);
        return bubbleToReturn;
    }

    /**
     * When this method is called it is expected that all info in the bubble has completed loading.
     * @see Bubble#inflate(BubbleViewInfoTask.Callback, Context,
     * BubbleStackView, BubbleIconFactory).
     */
    void notificationEntryUpdated(Bubble bubble, boolean suppressFlyout, boolean showInShade) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "notificationEntryUpdated: " + bubble);
        }
        mPendingBubbles.remove(bubble.getKey()); // No longer pending once we're here
        Bubble prevBubble = getBubbleInStackWithKey(bubble.getKey());
        suppressFlyout |= !bubble.isVisuallyInterruptive();

        if (prevBubble == null) {
            // Create a new bubble
            bubble.setSuppressFlyout(suppressFlyout);
            doAdd(bubble);
            trim();
        } else {
            // Updates an existing bubble
            bubble.setSuppressFlyout(suppressFlyout);
            // If there is no flyout, we probably shouldn't show the bubble at the top
            doUpdate(bubble, !suppressFlyout /* reorder */);
        }

        if (bubble.shouldAutoExpand()) {
            bubble.setShouldAutoExpand(false);
            setSelectedBubbleInternal(bubble);
            if (!mExpanded) {
                setExpandedInternal(true);
            }
        }

        boolean isBubbleExpandedAndSelected = mExpanded && mSelectedBubble == bubble;
        boolean suppress = isBubbleExpandedAndSelected || !showInShade || !bubble.showInShade();
        bubble.setSuppressNotification(suppress);
        bubble.setShowDot(!isBubbleExpandedAndSelected /* show */);

        dispatchPendingChanges();
    }

    /**
     * Dismisses the bubble with the matching key, if it exists.
     */
    public void dismissBubbleWithKey(String key, @DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "notificationEntryRemoved: key=" + key + " reason=" + reason);
        }
        doRemove(key, reason);
        dispatchPendingChanges();
    }

    /**
     * Adds a group key indicating that the summary for this group should be suppressed.
     *
     * @param groupKey the group key of the group whose summary should be suppressed.
     * @param notifKey the notification entry key of that summary.
     */
    void addSummaryToSuppress(String groupKey, String notifKey) {
        mSuppressedGroupKeys.put(groupKey, notifKey);
    }

    /**
     * Retrieves the notif entry key of the summary associated with the provided group key.
     *
     * @param groupKey the group to look up
     * @return the key for the {@link NotificationEntry} that is the summary of this group.
     */
    String getSummaryKey(String groupKey) {
        return mSuppressedGroupKeys.get(groupKey);
    }

    /**
     * Removes a group key indicating that summary for this group should no longer be suppressed.
     */
    void removeSuppressedSummary(String groupKey) {
        mSuppressedGroupKeys.remove(groupKey);
    }

    /**
     * Whether the summary for the provided group key is suppressed.
     */
    boolean isSummarySuppressed(String groupKey) {
        return mSuppressedGroupKeys.containsKey(groupKey);
    }

    /**
     * Retrieves any bubbles that are part of the notification group represented by the provided
     * group key.
     */
    ArrayList<Bubble> getBubblesInGroup(@Nullable String groupKey, @NonNull
            NotificationEntryManager nem) {
        ArrayList<Bubble> bubbleChildren = new ArrayList<>();
        if (groupKey == null) {
            return bubbleChildren;
        }
        for (Bubble b : mBubbles) {
            final NotificationEntry entry = nem.getPendingOrActiveNotif(b.getKey());
            if (entry != null && groupKey.equals(entry.getSbn().getGroupKey())) {
                bubbleChildren.add(b);
            }
        }
        return bubbleChildren;
    }

    /**
     * Removes bubbles from the given package whose shortcut are not in the provided list of valid
     * shortcuts.
     */
    public void removeBubblesWithInvalidShortcuts(
            String packageName, List<ShortcutInfo> validShortcuts, int reason) {

        final Set<String> validShortcutIds = new HashSet<String>();
        for (ShortcutInfo info : validShortcuts) {
            validShortcutIds.add(info.getId());
        }

        final Predicate<Bubble> invalidBubblesFromPackage = bubble -> {
            final boolean bubbleIsFromPackage = packageName.equals(bubble.getPackageName());
            final boolean isShortcutBubble = bubble.hasMetadataShortcutId();
            if (!bubbleIsFromPackage || !isShortcutBubble) {
                return false;
            }
            final boolean hasShortcutIdAndValidShortcut =
                    bubble.hasMetadataShortcutId()
                            && bubble.getShortcutInfo() != null
                            && bubble.getShortcutInfo().isEnabled()
                            && validShortcutIds.contains(bubble.getShortcutInfo().getId());
            return bubbleIsFromPackage && !hasShortcutIdAndValidShortcut;
        };

        final Consumer<Bubble> removeBubble = bubble ->
                dismissBubbleWithKey(bubble.getKey(), reason);

        performActionOnBubblesMatching(getBubbles(), invalidBubblesFromPackage, removeBubble);
        performActionOnBubblesMatching(
                getOverflowBubbles(), invalidBubblesFromPackage, removeBubble);
    }

    /** Dismisses all bubbles from the given package. */
    public void removeBubblesWithPackageName(String packageName, int reason) {
        final Predicate<Bubble> bubbleMatchesPackage = bubble ->
                bubble.getPackageName().equals(packageName);

        final Consumer<Bubble> removeBubble = bubble ->
                dismissBubbleWithKey(bubble.getKey(), reason);

        performActionOnBubblesMatching(getBubbles(), bubbleMatchesPackage, removeBubble);
        performActionOnBubblesMatching(getOverflowBubbles(), bubbleMatchesPackage, removeBubble);
    }

    private void doAdd(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doAdd: " + bubble);
        }
        mBubbles.add(0, bubble);
        mStateChange.addedBubble = bubble;
        // Adding the first bubble doesn't change the order
        mStateChange.orderChanged = mBubbles.size() > 1;
        if (!isExpanded()) {
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    private void trim() {
        if (mBubbles.size() > mMaxBubbles) {
            mBubbles.stream()
                    // sort oldest first (ascending lastActivity)
                    .sorted(Comparator.comparingLong(Bubble::getLastActivity))
                    // skip the selected bubble
                    .filter((b) -> !b.equals(mSelectedBubble))
                    .findFirst()
                    .ifPresent((b) -> doRemove(b.getKey(), BubbleController.DISMISS_AGED));
        }
    }

    private void doUpdate(Bubble bubble, boolean reorder) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doUpdate: " + bubble);
        }
        mStateChange.updatedBubble = bubble;
        if (!isExpanded() && reorder) {
            int prevPos = mBubbles.indexOf(bubble);
            mBubbles.remove(bubble);
            mBubbles.add(0, bubble);
            mStateChange.orderChanged = prevPos != 0;
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    /** Runs the given action on Bubbles that match the given predicate. */
    private void performActionOnBubblesMatching(
            List<Bubble> bubbles, Predicate<Bubble> predicate, Consumer<Bubble> action) {
        final List<Bubble> matchingBubbles = new ArrayList<>();
        for (Bubble bubble : bubbles) {
            if (predicate.test(bubble)) {
                matchingBubbles.add(bubble);
            }
        }

        for (Bubble matchingBubble : matchingBubbles) {
            action.accept(matchingBubble);
        }
    }

    private void doRemove(String key, @DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doRemove: " + key);
        }
        //  If it was pending remove it
        if (mPendingBubbles.containsKey(key)) {
            mPendingBubbles.remove(key);
        }
        int indexToRemove = indexForKey(key);
        if (indexToRemove == -1) {
            if (hasOverflowBubbleWithKey(key)
                && (reason == BubbleController.DISMISS_NOTIF_CANCEL
                    || reason == BubbleController.DISMISS_GROUP_CANCELLED
                    || reason == BubbleController.DISMISS_NO_LONGER_BUBBLE
                    || reason == BubbleController.DISMISS_BLOCKED
                    || reason == BubbleController.DISMISS_SHORTCUT_REMOVED
                    || reason == BubbleController.DISMISS_PACKAGE_REMOVED)) {

                Bubble b = getOverflowBubbleWithKey(key);
                if (DEBUG_BUBBLE_DATA) {
                    Log.d(TAG, "Cancel overflow bubble: " + b);
                }
                if (b != null) {
                    b.stopInflation();
                }
                mLogger.logOverflowRemove(b, reason);
                mStateChange.bubbleRemoved(b, reason);
                mOverflowBubbles.remove(b);
            }
            return;
        }
        Bubble bubbleToRemove = mBubbles.get(indexToRemove);
        bubbleToRemove.stopInflation();
        if (mBubbles.size() == 1) {
            // Going to become empty, handle specially.
            setExpandedInternal(false);
            // Don't use setSelectedBubbleInternal because we don't want to trigger an applyUpdate
            mSelectedBubble = null;
        }
        if (indexToRemove < mBubbles.size() - 1) {
            // Removing anything but the last bubble means positions will change.
            mStateChange.orderChanged = true;
        }
        mBubbles.remove(indexToRemove);
        mStateChange.bubbleRemoved(bubbleToRemove, reason);
        if (!isExpanded()) {
            mStateChange.orderChanged |= repackAll();
        }

        overflowBubble(reason, bubbleToRemove);

        // Note: If mBubbles.isEmpty(), then mSelectedBubble is now null.
        if (Objects.equals(mSelectedBubble, bubbleToRemove)) {
            // Move selection to the new bubble at the same position.
            int newIndex = Math.min(indexToRemove, mBubbles.size() - 1);
            Bubble newSelected = mBubbles.get(newIndex);
            setSelectedBubbleInternal(newSelected);
        }
        maybeSendDeleteIntent(reason, bubbleToRemove);
    }

    void overflowBubble(@DismissReason int reason, Bubble bubble) {
        if (bubble.getPendingIntentCanceled()
                || !(reason == BubbleController.DISMISS_AGED
                || reason == BubbleController.DISMISS_USER_GESTURE)) {
            return;
        }
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "Overflowing: " + bubble);
        }
        mLogger.logOverflowAdd(bubble, reason);
        mOverflowBubbles.add(0, bubble);
        bubble.stopInflation();
        if (mOverflowBubbles.size() == mMaxOverflowBubbles + 1) {
            // Remove oldest bubble.
            Bubble oldest = mOverflowBubbles.get(mOverflowBubbles.size() - 1);
            if (DEBUG_BUBBLE_DATA) {
                Log.d(TAG, "Overflow full. Remove: " + oldest);
            }
            mStateChange.bubbleRemoved(oldest, BubbleController.DISMISS_OVERFLOW_MAX_REACHED);
            mLogger.log(bubble, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_MAX_REACHED);
            mOverflowBubbles.remove(oldest);
        }
    }

    public void dismissAll(@DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "dismissAll: reason=" + reason);
        }
        if (mBubbles.isEmpty()) {
            return;
        }
        setExpandedInternal(false);
        setSelectedBubbleInternal(null);
        while (!mBubbles.isEmpty()) {
            doRemove(mBubbles.get(0).getKey(), reason);
        }
        dispatchPendingChanges();
    }

    /**
     * Indicates that the provided display is no longer in use and should be cleaned up.
     *
     * @param displayId the id of the display to clean up.
     */
    void notifyDisplayEmpty(int displayId) {
        for (Bubble b : mBubbles) {
            if (b.getDisplayId() == displayId) {
                if (b.getExpandedView() != null) {
                    b.getExpandedView().notifyDisplayEmpty();
                }
                return;
            }
        }
    }

    private void dispatchPendingChanges() {
        if (mListener != null && mStateChange.anythingChanged()) {
            mListener.applyUpdate(mStateChange);
        }
        mStateChange = new Update(mBubbles, mOverflowBubbles);
    }

    /**
     * Requests a change to the selected bubble.
     *
     * @param bubble the new selected bubble
     */
    private void setSelectedBubbleInternal(@Nullable Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubbleInternal: " + bubble);
        }
        if (!mShowingOverflow && Objects.equals(bubble, mSelectedBubble)) {
            return;
        }
        // Otherwise, if we are showing the overflow menu, return to the previously selected bubble.

        if (bubble != null && !mBubbles.contains(bubble) && !mOverflowBubbles.contains(bubble)) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return;
        }
        if (mExpanded && bubble != null) {
            bubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
        }
        mSelectedBubble = bubble;
        mStateChange.selectedBubble = bubble;
        mStateChange.selectionChanged = true;
    }

    /**
     * Requests a change to the expanded state.
     *
     * @param shouldExpand the new requested state
     */
    private void setExpandedInternal(boolean shouldExpand) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setExpandedInternal: shouldExpand=" + shouldExpand);
        }
        if (mExpanded == shouldExpand) {
            return;
        }
        if (shouldExpand) {
            if (mBubbles.isEmpty()) {
                Log.e(TAG, "Attempt to expand stack when empty!");
                return;
            }
            if (mSelectedBubble == null) {
                Log.e(TAG, "Attempt to expand stack without selected bubble!");
                return;
            }
            mSelectedBubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
            mStateChange.orderChanged |= repackAll();
        } else if (!mBubbles.isEmpty()) {
            // Apply ordering and grouping rules from expanded -> collapsed, then save
            // the result.
            mStateChange.orderChanged |= repackAll();
            // Save the state which should be returned to when expanded (with no other changes)

            if (mShowingOverflow) {
                // Show previously selected bubble instead of overflow menu on next expansion.
                setSelectedBubbleInternal(mSelectedBubble);
            }
            if (mBubbles.indexOf(mSelectedBubble) > 0) {
                // Move the selected bubble to the top while collapsed.
                int index = mBubbles.indexOf(mSelectedBubble);
                if (index != 0) {
                    mBubbles.remove(mSelectedBubble);
                    mBubbles.add(0, mSelectedBubble);
                    mStateChange.orderChanged = true;
                }
            }
        }
        mExpanded = shouldExpand;
        mStateChange.expanded = shouldExpand;
        mStateChange.expandedChanged = true;
    }

    private static long sortKey(Bubble bubble) {
        return bubble.getLastActivity();
    }

    /**
     * This applies a full sort and group pass to all existing bubbles.
     * Bubbles are sorted by lastUpdated descending.
     *
     * @return true if the position of any bubbles changed as a result
     */
    private boolean repackAll() {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "repackAll()");
        }
        if (mBubbles.isEmpty()) {
            return false;
        }
        List<Bubble> repacked = new ArrayList<>(mBubbles.size());
        // Add bubbles, freshest to oldest
        mBubbles.stream()
                .sorted(BUBBLES_BY_SORT_KEY_DESCENDING)
                .forEachOrdered(repacked::add);
        if (repacked.equals(mBubbles)) {
            return false;
        }
        mBubbles.clear();
        mBubbles.addAll(repacked);
        return true;
    }

    private void maybeSendDeleteIntent(@DismissReason int reason, @NonNull final Bubble bubble) {
        if (reason != BubbleController.DISMISS_USER_GESTURE) return;
        PendingIntent deleteIntent = bubble.getDeleteIntent();
        if (deleteIntent == null) return;
        try {
            deleteIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "Failed to send delete intent for bubble with key: " + bubble.getKey());
        }
    }

    private int indexForKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The set of bubbles in row.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public List<Bubble> getBubbles() {
        return Collections.unmodifiableList(mBubbles);
    }

    /**
     * The set of bubbles in overflow.
     */
    @VisibleForTesting(visibility = PRIVATE)
    List<Bubble> getOverflowBubbles() {
        return Collections.unmodifiableList(mOverflowBubbles);
    }

    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    Bubble getAnyBubbleWithkey(String key) {
        Bubble b = getBubbleInStackWithKey(key);
        if (b == null) {
            b = getOverflowBubbleWithKey(key);
        }
        return b;
    }

    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    Bubble getBubbleInStackWithKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    @Nullable
    Bubble getBubbleWithView(View view) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getIconView() != null && bubble.getIconView().equals(view)) {
                return bubble;
            }
        }
        return null;
    }

    @VisibleForTesting(visibility = PRIVATE)
    Bubble getOverflowBubbleWithKey(String key) {
        for (int i = 0; i < mOverflowBubbles.size(); i++) {
            Bubble bubble = mOverflowBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    @VisibleForTesting(visibility = PRIVATE)
    void setTimeSource(TimeSource timeSource) {
        mTimeSource = timeSource;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Set maximum number of bubbles allowed in overflow.
     * This method should only be used in tests, not in production.
     */
    @VisibleForTesting
    void setMaxOverflowBubbles(int maxOverflowBubbles) {
        mMaxOverflowBubbles = maxOverflowBubbles;
    }

    /**
     * Description of current bubble data state.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("selected: ");
        pw.println(mSelectedBubble != null
                ? mSelectedBubble.getKey()
                : "null");
        pw.print("expanded: ");
        pw.println(mExpanded);
        pw.print("count:    ");
        pw.println(mBubbles.size());
        for (Bubble bubble : mBubbles) {
            bubble.dump(fd, pw, args);
        }
        pw.print("summaryKeys: ");
        pw.println(mSuppressedGroupKeys.size());
        for (String key : mSuppressedGroupKeys.keySet()) {
            pw.println("   suppressing: " + key);
        }
    }
}
