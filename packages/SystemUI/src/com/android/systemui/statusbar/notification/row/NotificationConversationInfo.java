/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.provider.Settings.Global.NOTIFICATION_BUBBLES;

import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.notification.ConversationIconFactory;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.NotificationChannelHelper;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

import java.lang.annotation.Retention;

import javax.inject.Provider;

/**
 * The guts of a conversation notification revealed when performing a long press.
 */
public class NotificationConversationInfo extends LinearLayout implements
        NotificationGuts.GutsContent {
    private static final String TAG = "ConversationGuts";


    private INotificationManager mINotificationManager;
    private ShortcutManager mShortcutManager;
    private PackageManager mPm;
    private ConversationIconFactory mIconFactory;
    private VisualStabilityManager mVisualStabilityManager;
    private Handler mMainHandler;
    private Handler mBgHandler;
    private BubbleController mBubbleController;
    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    private NotificationChannel mNotificationChannel;
    private ShortcutInfo mShortcutInfo;
    private NotificationEntry mEntry;
    private StatusBarNotification mSbn;
    @Nullable private Notification.BubbleMetadata mBubbleMetadata;
    private Context mUserContext;
    private Provider<PriorityOnboardingDialogController.Builder> mBuilderProvider;
    private boolean mIsDeviceProvisioned;
    private int mAppBubble;

    private TextView mPriorityDescriptionView;
    private TextView mDefaultDescriptionView;
    private TextView mSilentDescriptionView;

    private @Action int mSelectedAction = -1;
    private boolean mPressedApply;

    private OnSnoozeClickListener mOnSnoozeClickListener;
    private OnSettingsClickListener mOnSettingsClickListener;
    private NotificationGuts mGutsContainer;
    private OnConversationSettingsClickListener mOnConversationSettingsClickListener;

    @VisibleForTesting
    boolean mSkipPost = false;
    private int mActualHeight;

    @Retention(SOURCE)
    @IntDef({ACTION_DEFAULT, ACTION_HOME, ACTION_FAVORITE, ACTION_SNOOZE, ACTION_MUTE,
            ACTION_SETTINGS})
    private @interface Action {}
    static final int ACTION_DEFAULT = 0;
    static final int ACTION_HOME = 1;
    static final int ACTION_FAVORITE = 2;
    static final int ACTION_SNOOZE = 3;
    static final int ACTION_MUTE = 4;
    static final int ACTION_SETTINGS = 5;

    // TODO: b/152050825
    /*
    private OnClickListener mOnHomeClick = v -> {
        mSelectedAction = ACTION_HOME;
        mShortcutManager.requestPinShortcut(mShortcutInfo, null);
        mShadeController.animateCollapsePanels();
        mGutsContainer.closeControls(v, true);
    };

    private OnClickListener mOnSnoozeClick = v -> {
        mSelectedAction = ACTION_SNOOZE;
        mOnSnoozeClickListener.onClick(v, 1);
        mGutsContainer.closeControls(v, true);
    };
    */

    private OnClickListener mOnFavoriteClick = v -> {
        setSelectedAction(ACTION_FAVORITE);
        updateToggleActions(mSelectedAction, true);
    };

    private OnClickListener mOnDefaultClick = v -> {
        setSelectedAction(ACTION_DEFAULT);
        updateToggleActions(mSelectedAction, true);
    };

    private OnClickListener mOnMuteClick = v -> {
        setSelectedAction(ACTION_MUTE);
        updateToggleActions(mSelectedAction, true);
    };

    private OnClickListener mOnDone = v -> {
        mPressedApply = true;
        // If the user selected Priority, maybe show the priority onboarding
        if (mSelectedAction == ACTION_FAVORITE && shouldShowPriorityOnboarding()) {
            showPriorityOnboarding();
        }
        mGutsContainer.closeControls(v, true);
    };

    public NotificationConversationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public interface OnConversationSettingsClickListener {
        void onClick();
    }

    public interface OnAppSettingsClickListener {
        void onClick(View v, Intent intent);
    }

    public interface OnSnoozeClickListener {
        void onClick(View v, int hoursToSnooze);
    }

    @VisibleForTesting
    void setSelectedAction(int selectedAction) {
        if (mSelectedAction == selectedAction) {
            return;
        }

        mSelectedAction = selectedAction;
    }

    public void bindNotification(
            ShortcutManager shortcutManager,
            PackageManager pm,
            INotificationManager iNotificationManager,
            VisualStabilityManager visualStabilityManager,
            String pkg,
            NotificationChannel notificationChannel,
            NotificationEntry entry,
            Notification.BubbleMetadata bubbleMetadata,
            OnSettingsClickListener onSettingsClick,
            OnSnoozeClickListener onSnoozeClickListener,
            ConversationIconFactory conversationIconFactory,
            Context userContext,
            Provider<PriorityOnboardingDialogController.Builder> builderProvider,
            boolean isDeviceProvisioned,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            OnConversationSettingsClickListener onConversationSettingsClickListener,
            BubbleController bubbleController) {
        mSelectedAction = -1;
        mINotificationManager = iNotificationManager;
        mVisualStabilityManager = visualStabilityManager;
        mPackageName = pkg;
        mEntry = entry;
        mSbn = entry.getSbn();
        mPm = pm;
        mAppName = mPackageName;
        mOnSettingsClickListener = onSettingsClick;
        mNotificationChannel = notificationChannel;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;
        mOnSnoozeClickListener = onSnoozeClickListener;
        mOnConversationSettingsClickListener = onConversationSettingsClickListener;
        mIconFactory = conversationIconFactory;
        mUserContext = userContext;
        mBubbleMetadata = bubbleMetadata;
        mBubbleController = bubbleController;
        mBuilderProvider = builderProvider;
        mMainHandler = mainHandler;
        mBgHandler = bgHandler;
        mShortcutManager = shortcutManager;
        mShortcutInfo = entry.getRanking().getShortcutInfo();
        if (mShortcutInfo == null) {
            throw new IllegalArgumentException("Does not have required information");
        }

        mNotificationChannel = NotificationChannelHelper.createConversationChannelIfNeeded(
                getContext(), mINotificationManager, entry, mNotificationChannel);

        try {
            mAppBubble = mINotificationManager.getBubblePreferenceForPackage(mPackageName, mAppUid);
        } catch (RemoteException e) {
            Log.e(TAG, "can't reach OS", e);
            mAppBubble = BUBBLE_PREFERENCE_SELECTED;
        }

        bindHeader();
        bindActions();

        View done = findViewById(R.id.done);
        done.setOnClickListener(mOnDone);
        done.setAccessibilityDelegate(mGutsContainer.getAccessibilityDelegate());
    }

    private void bindActions() {

        // TODO: b/152050825
        /*
        Button home = findViewById(R.id.home);
        home.setOnClickListener(mOnHomeClick);
        home.setVisibility(mShortcutInfo != null
                && mShortcutManager.isRequestPinShortcutSupported()
                ? VISIBLE : GONE);

        Button snooze = findViewById(R.id.snooze);
        snooze.setOnClickListener(mOnSnoozeClick);
        */

        if (mAppBubble == BUBBLE_PREFERENCE_ALL) {
            ((TextView) findViewById(R.id.default_summary)).setText(getResources().getString(
                    R.string.notification_channel_summary_default_with_bubbles, mAppName));
        }

        findViewById(R.id.priority).setOnClickListener(mOnFavoriteClick);
        findViewById(R.id.default_behavior).setOnClickListener(mOnDefaultClick);
        findViewById(R.id.silence).setOnClickListener(mOnMuteClick);

        final View settingsButton = findViewById(R.id.info);
        settingsButton.setOnClickListener(getSettingsOnClickListener());
        settingsButton.setVisibility(settingsButton.hasOnClickListeners() ? VISIBLE : GONE);

        updateToggleActions(getSelectedAction(), false);
    }

    private void bindHeader() {
        bindConversationDetails();

        // Delegate
        bindDelegate();
    }

    private OnClickListener getSettingsOnClickListener() {
        if (mAppUid >= 0 && mOnSettingsClickListener != null && mIsDeviceProvisioned) {
            final int appUidF = mAppUid;
            return ((View view) -> {
                mOnSettingsClickListener.onClick(view, mNotificationChannel, appUidF);
            });
        }
        return null;
    }

    private void bindConversationDetails() {
        final TextView channelName = findViewById(R.id.parent_channel_name);
        channelName.setText(mNotificationChannel.getName());

        bindGroup();
        // TODO: bring back when channel name does not include name
        // bindName();
        bindPackage();
        bindIcon(mNotificationChannel.isImportantConversation());
    }

    private void bindIcon(boolean important) {
        ImageView image = findViewById(R.id.conversation_icon);
        image.setImageDrawable(mIconFactory.getConversationDrawable(
                mShortcutInfo, mPackageName, mAppUid, important));
    }

    private void bindPackage() {
        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(
                    mPackageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        ((TextView) findViewById(R.id.pkg_name)).setText(mAppName);
    }

    private void bindDelegate() {
        TextView delegateView = findViewById(R.id.delegate_name);

        if (!TextUtils.equals(mPackageName, mDelegatePkg)) {
            // this notification was posted by a delegate!
            delegateView.setVisibility(View.VISIBLE);
        } else {
            delegateView.setVisibility(View.GONE);
        }
    }

    private void bindGroup() {
        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (mNotificationChannel != null && mNotificationChannel.getGroup() != null) {
            try {
                final NotificationChannelGroup notificationChannelGroup =
                        mINotificationManager.getNotificationChannelGroupForPackage(
                                mNotificationChannel.getGroup(), mPackageName, mAppUid);
                if (notificationChannelGroup != null) {
                    groupName = notificationChannelGroup.getName();
                }
            } catch (RemoteException e) {
            }
        }
        TextView groupNameView = findViewById(R.id.group_name);
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(VISIBLE);
        } else {
            groupNameView.setVisibility(GONE);
        }
    }

    @Override
    public boolean post(Runnable action) {
        if (mSkipPost) {
            action.run();
            return true;
        } else {
            return super.post(action);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mPriorityDescriptionView = findViewById(R.id.priority_summary);
        mDefaultDescriptionView = findViewById(R.id.default_summary);
        mSilentDescriptionView = findViewById(R.id.silence_summary);
    }

    @Override
    public void onFinishedClosing() {
        // TODO: do we need to do anything here?
    }

    @Override
    public boolean needsFalsingProtection() {
        return true;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null &&
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (mGutsContainer.isExposed()) {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_opened_accessibility, mAppName));
            } else {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_closed_accessibility, mAppName));
            }
        }
    }

    private void updateToggleActions(int selectedAction, boolean userTriggered) {
        if (userTriggered) {
            TransitionSet transition = new TransitionSet();
            transition.setOrdering(TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(new Fade(Fade.OUT))
                    .addTransition(new ChangeBounds())
                    .addTransition(
                            new Fade(Fade.IN)
                                    .setStartDelay(150)
                                    .setDuration(200)
                                    .setInterpolator(FAST_OUT_SLOW_IN));
            transition.setDuration(350);
            transition.setInterpolator(FAST_OUT_SLOW_IN);
            TransitionManager.beginDelayedTransition(this, transition);
        }

        View priority = findViewById(R.id.priority);
        View defaultBehavior = findViewById(R.id.default_behavior);
        View silence = findViewById(R.id.silence);

        switch (selectedAction) {
            case ACTION_FAVORITE:
                mPriorityDescriptionView.setVisibility(VISIBLE);
                mDefaultDescriptionView.setVisibility(GONE);
                mSilentDescriptionView.setVisibility(GONE);
                post(() -> {
                    priority.setSelected(true);
                    defaultBehavior.setSelected(false);
                    silence.setSelected(false);
                });
                break;

            case ACTION_MUTE:
                mSilentDescriptionView.setVisibility(VISIBLE);
                mDefaultDescriptionView.setVisibility(GONE);
                mPriorityDescriptionView.setVisibility(GONE);
                post(() -> {
                    priority.setSelected(false);
                    defaultBehavior.setSelected(false);
                    silence.setSelected(true);
                });
                break;

            case ACTION_DEFAULT:
                mDefaultDescriptionView.setVisibility(VISIBLE);
                mSilentDescriptionView.setVisibility(GONE);
                mPriorityDescriptionView.setVisibility(GONE);
                post(() -> {
                    priority.setSelected(false);
                    defaultBehavior.setSelected(true);
                    silence.setSelected(false);
                });
                break;

            default:
                throw new IllegalArgumentException("Unrecognized behavior: " + mSelectedAction);
        }

        boolean isAChange = getSelectedAction() != selectedAction;
        TextView done = findViewById(R.id.done);
        done.setText(isAChange
                ? R.string.inline_ok_button
                : R.string.inline_done_button);

        // update icon in case importance has changed
        bindIcon(selectedAction == ACTION_FAVORITE);
    }

    int getSelectedAction() {
        if (mNotificationChannel.getImportance() <= IMPORTANCE_LOW
                && mNotificationChannel.getImportance() > IMPORTANCE_UNSPECIFIED) {
            return ACTION_MUTE;
        } else {
            if (mNotificationChannel.isImportantConversation()) {
                return ACTION_FAVORITE;
            }
        }
        return ACTION_DEFAULT;
    }

    private void updateChannel() {
        mBgHandler.post(
                new UpdateChannelRunnable(mINotificationManager, mPackageName,
                        mAppUid, mSelectedAction, mNotificationChannel));
        mEntry.markForUserTriggeredMovement(true);
        mMainHandler.postDelayed(
                mVisualStabilityManager::temporarilyAllowReordering,
                StackStateAnimator.ANIMATION_DURATION_STANDARD);
    }

    private boolean shouldShowPriorityOnboarding() {
        return !Prefs.getBoolean(mUserContext, Prefs.Key.HAS_SEEN_PRIORITY_ONBOARDING, false);
    }

    private void showPriorityOnboarding() {
        View onboardingView = LayoutInflater.from(mContext)
                .inflate(R.layout.priority_onboarding_half_shell, null);

        boolean ignoreDnd = false;
        try {
            ignoreDnd = mINotificationManager
                    .getConsolidatedNotificationPolicy().priorityConversationSenders ==
                    NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT;
        } catch (RemoteException e) {
            Log.e(TAG, "Could not check conversation senders", e);
        }

        boolean showAsBubble = mBubbleMetadata != null
                && mBubbleMetadata.getAutoExpandBubble()
                && Settings.Global.getInt(mContext.getContentResolver(),
                        NOTIFICATION_BUBBLES, 0) == 1;

        Drawable person =  mIconFactory.getBaseIconDrawable(mShortcutInfo);
        if (person == null) {
            person = mContext.getDrawable(R.drawable.ic_person).mutate();
            TypedArray ta = mContext.obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
            int colorAccent = ta.getColor(0, 0);
            ta.recycle();
            person.setTint(colorAccent);
        }

        PriorityOnboardingDialogController controller = mBuilderProvider.get()
                .setContext(mUserContext)
                .setView(onboardingView)
                .setIgnoresDnd(ignoreDnd)
                .setShowsAsBubble(showAsBubble)
                .setIcon(person)
                .setBadge(mIconFactory.getAppBadge(
                        mPackageName, UserHandle.getUserId(mSbn.getUid())))
                .setOnSettingsClick(mOnConversationSettingsClickListener)
                .build();

        controller.init();
        controller.show();
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean willBeRemoved() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return mPressedApply;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (save && mSelectedAction > -1) {
            updateChannel();
        }
        return false;
    }

    @Override
    public int getActualHeight() {
        // Because we're animating the bounds, getHeight will return the small height at the
        // beginning of the animation. Instead we'd want it to already return the end value
        return mActualHeight;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mActualHeight = getHeight();
    }

    @VisibleForTesting
    public boolean isAnimating() {
        return false;
    }

    class UpdateChannelRunnable implements Runnable {

        private final INotificationManager mINotificationManager;
        private final String mAppPkg;
        private final int mAppUid;
        private  NotificationChannel mChannelToUpdate;
        private final @Action int mAction;

        public UpdateChannelRunnable(INotificationManager notificationManager,
                String packageName, int appUid, @Action int action,
                @NonNull NotificationChannel channelToUpdate) {
            mINotificationManager = notificationManager;
            mAppPkg = packageName;
            mAppUid = appUid;
            mChannelToUpdate = channelToUpdate;
            mAction = action;
        }

        @Override
        public void run() {
            try {
                switch (mAction) {
                    case ACTION_FAVORITE:
                        mChannelToUpdate.setImportantConversation(true);
                        if (mChannelToUpdate.isImportantConversation()) {
                            mChannelToUpdate.setAllowBubbles(true);
                            if (mAppBubble == BUBBLE_PREFERENCE_NONE) {
                                mINotificationManager.setBubblesAllowed(mAppPkg, mAppUid,
                                        BUBBLE_PREFERENCE_SELECTED);
                            }
                            post(() -> {
                                mBubbleController.onUserChangedImportance(mEntry);
                            });
                        }
                        mChannelToUpdate.setImportance(Math.max(
                                mChannelToUpdate.getOriginalImportance(), IMPORTANCE_DEFAULT));
                        break;
                    case ACTION_DEFAULT:
                        mChannelToUpdate.setImportance(Math.max(
                                mChannelToUpdate.getOriginalImportance(), IMPORTANCE_DEFAULT));
                        if (mChannelToUpdate.isImportantConversation()) {
                            mChannelToUpdate.setImportantConversation(false);
                            mChannelToUpdate.setAllowBubbles(false);
                        }
                        break;
                    case ACTION_MUTE:
                        if (mChannelToUpdate.getImportance() == IMPORTANCE_UNSPECIFIED
                                || mChannelToUpdate.getImportance() >= IMPORTANCE_DEFAULT) {
                            mChannelToUpdate.setImportance(IMPORTANCE_LOW);
                        }
                        if (mChannelToUpdate.isImportantConversation()) {
                            mChannelToUpdate.setImportantConversation(false);
                            mChannelToUpdate.setAllowBubbles(false);
                        }
                        break;
                }

                mINotificationManager.updateNotificationChannelForPackage(
                            mAppPkg, mAppUid, mChannelToUpdate);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to update notification channel", e);
            }
        }
    }
}
