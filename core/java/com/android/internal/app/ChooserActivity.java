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

package com.android.internal.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.NonNull;
import android.app.Activity;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.service.chooser.ChooserTarget;
import android.service.chooser.ChooserTargetService;
import android.service.chooser.IChooserTargetResult;
import android.service.chooser.IChooserTargetService;
import android.text.TextUtils;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity.TargetInfo;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.google.android.collect.Lists;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ChooserActivity extends ResolverActivity {
    private static final String TAG = "ChooserActivity";

    /**
     * Boolean extra to change the following behavior: Normally, ChooserActivity finishes itself
     * in onStop when launched in a new task. If this extra is set to true, we do not finish
     * ourselves when onStop gets called.
     */
    public static final String EXTRA_PRIVATE_RETAIN_IN_ON_STOP
            = "com.android.internal.app.ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP";

    private static final boolean DEBUG = false;

    private static final int QUERY_TARGET_SERVICE_LIMIT = 3;
    private static final int WATCHDOG_TIMEOUT_MILLIS = 2000;

    private Bundle mReplacementExtras;
    private IntentSender mChosenComponentSender;
    private IntentSender mRefinementIntentSender;
    private RefinementResultReceiver mRefinementResultReceiver;
    private ChooserTarget[] mCallerChooserTargets;
    private ComponentName[] mFilteredComponentNames;

    private Intent mReferrerFillInIntent;

    private long mChooserShownTime;
    protected boolean mIsSuccessfullySelected;

    private ChooserListAdapter mChooserListAdapter;
    private ChooserRowAdapter mChooserRowAdapter;

    private SharedPreferences mPinnedSharedPrefs;
    private static final float PINNED_TARGET_SCORE_BOOST = 1000.f;
    private static final float CALLER_TARGET_SCORE_BOOST = 900.f;
    private static final String PINNED_SHARED_PREFS_NAME = "chooser_pin_settings";
    private static final String TARGET_DETAILS_FRAGMENT_TAG = "targetDetailsFragment";

    private final List<ChooserTargetServiceConnection> mServiceConnections = new ArrayList<>();

    private static final int CHOOSER_TARGET_SERVICE_RESULT = 1;
    private static final int CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT = 2;

    private final Handler mChooserHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CHOOSER_TARGET_SERVICE_RESULT:
                    if (DEBUG) Log.d(TAG, "CHOOSER_TARGET_SERVICE_RESULT");
                    if (isDestroyed()) break;
                    final ServiceResultInfo sri = (ServiceResultInfo) msg.obj;
                    if (!mServiceConnections.contains(sri.connection)) {
                        Log.w(TAG, "ChooserTargetServiceConnection " + sri.connection
                                + " returned after being removed from active connections."
                                + " Have you considered returning results faster?");
                        break;
                    }
                    if (sri.resultTargets != null) {
                        mChooserListAdapter.addServiceResults(sri.originalTarget,
                                sri.resultTargets);
                    }
                    unbindService(sri.connection);
                    sri.connection.destroy();
                    mServiceConnections.remove(sri.connection);
                    if (mServiceConnections.isEmpty()) {
                        mChooserHandler.removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT);
                        sendVoiceChoicesIfNeeded();
                        mChooserListAdapter.setShowServiceTargets(true);
                    }
                    break;

                case CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT:
                    if (DEBUG) {
                        Log.d(TAG, "CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT; unbinding services");
                    }
                    unbindRemainingServices();
                    sendVoiceChoicesIfNeeded();
                    mChooserListAdapter.setShowServiceTargets(true);
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final long intentReceivedTime = System.currentTimeMillis();
        mIsSuccessfullySelected = false;
        Intent intent = getIntent();
        Parcelable targetParcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (!(targetParcelable instanceof Intent)) {
            Log.w("ChooserActivity", "Target is not an intent: " + targetParcelable);
            finish();
            super.onCreate(null);
            return;
        }
        Intent target = (Intent) targetParcelable;
        if (target != null) {
            modifyTargetIntent(target);
        }
        Parcelable[] targetsParcelable
                = intent.getParcelableArrayExtra(Intent.EXTRA_ALTERNATE_INTENTS);
        if (targetsParcelable != null) {
            final boolean offset = target == null;
            Intent[] additionalTargets =
                    new Intent[offset ? targetsParcelable.length - 1 : targetsParcelable.length];
            for (int i = 0; i < targetsParcelable.length; i++) {
                if (!(targetsParcelable[i] instanceof Intent)) {
                    Log.w(TAG, "EXTRA_ALTERNATE_INTENTS array entry #" + i + " is not an Intent: "
                            + targetsParcelable[i]);
                    continue;
                }
                final Intent additionalTarget = (Intent) targetsParcelable[i];
                if (i == 0 && target == null) {
                    target = additionalTarget;
                    modifyTargetIntent(target);
                } else {
                    additionalTargets[offset ? i - 1 : i] = additionalTarget;
                    modifyTargetIntent(additionalTarget);
                }
            }
            setAdditionalTargets(additionalTargets);
        }

        mReplacementExtras = intent.getBundleExtra(Intent.EXTRA_REPLACEMENT_EXTRAS);
        CharSequence title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
        int defaultTitleRes = 0;
        if (title == null) {
            defaultTitleRes = com.android.internal.R.string.chooseActivity;
        }
        Parcelable[] pa = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        Intent[] initialIntents = null;
        if (pa != null) {
            initialIntents = new Intent[pa.length];
            for (int i=0; i<pa.length; i++) {
                if (!(pa[i] instanceof Intent)) {
                    Log.w(TAG, "Initial intent #" + i + " not an Intent: " + pa[i]);
                    continue;
                }
                final Intent in = (Intent) pa[i];
                modifyTargetIntent(in);
                initialIntents[i] = in;
            }
        }

        mReferrerFillInIntent = new Intent().putExtra(Intent.EXTRA_REFERRER, getReferrer());

        mChosenComponentSender = intent.getParcelableExtra(
                Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER);
        mRefinementIntentSender = intent.getParcelableExtra(
                Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER);
        setSafeForwardingMode(true);

        pa = intent.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS);
        if (pa != null) {
            ComponentName[] names = new ComponentName[pa.length];
            for (int i = 0; i < pa.length; i++) {
                if (!(pa[i] instanceof ComponentName)) {
                    Log.w(TAG, "Filtered component #" + i + " not a ComponentName: " + pa[i]);
                    names = null;
                    break;
                }
                names[i] = (ComponentName) pa[i];
            }
            mFilteredComponentNames = names;
        }

        pa = intent.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_TARGETS);
        if (pa != null) {
            ChooserTarget[] targets = new ChooserTarget[pa.length];
            for (int i = 0; i < pa.length; i++) {
                if (!(pa[i] instanceof ChooserTarget)) {
                    Log.w(TAG, "Chooser target #" + i + " not a ChooserTarget: " + pa[i]);
                    targets = null;
                    break;
                }
                targets[i] = (ChooserTarget) pa[i];
            }
            mCallerChooserTargets = targets;
        }

        mPinnedSharedPrefs = getPinnedSharedPrefs(this);
        setRetainInOnStop(intent.getBooleanExtra(EXTRA_PRIVATE_RETAIN_IN_ON_STOP, false));
        super.onCreate(savedInstanceState, target, title, defaultTitleRes, initialIntents,
                null, false);

        MetricsLogger.action(this, MetricsEvent.ACTION_ACTIVITY_CHOOSER_SHOWN);

        mChooserShownTime = System.currentTimeMillis();
        final long systemCost = mChooserShownTime - intentReceivedTime;
        MetricsLogger.histogram(null, "system_cost_for_smart_sharing", (int) systemCost);
        if (DEBUG) {
            Log.d(TAG, "System Time Cost is " + systemCost);
        }
    }

    static SharedPreferences getPinnedSharedPrefs(Context context) {
        // The code below is because in the android:ui process, no one can hear you scream.
        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        // This fails silently under the hood if there's a problem, so if we find ourselves in
        // the case where we don't have access to credential encrypted storage we just won't
        // have our pinned target info.
        final File prefsFile = new File(new File(
                Environment.getDataUserCePackageDirectory(StorageManager.UUID_PRIVATE_INTERNAL,
                        context.getUserId(), context.getPackageName()),
                "shared_prefs"),
                PINNED_SHARED_PREFS_NAME + ".xml");
        return context.getSharedPreferences(prefsFile, MODE_PRIVATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        unbindRemainingServices();
        mChooserHandler.removeMessages(CHOOSER_TARGET_SERVICE_RESULT);
    }

    @Override
    public Intent getReplacementIntent(ActivityInfo aInfo, Intent defIntent) {
        Intent result = defIntent;
        if (mReplacementExtras != null) {
            final Bundle replExtras = mReplacementExtras.getBundle(aInfo.packageName);
            if (replExtras != null) {
                result = new Intent(defIntent);
                result.putExtras(replExtras);
            }
        }
        if (aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_PARENT)
                || aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            result = Intent.createChooser(result,
                    getIntent().getCharSequenceExtra(Intent.EXTRA_TITLE));

            // Don't auto-launch single intents if the intent is being forwarded. This is done
            // because automatically launching a resolving application as a response to the user
            // action of switching accounts is pretty unexpected.
            result.putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false);
        }
        return result;
    }

    @Override
    public void onActivityStarted(TargetInfo cti) {
        if (mChosenComponentSender != null) {
            final ComponentName target = cti.getResolvedComponentName();
            if (target != null) {
                final Intent fillIn = new Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, target);
                try {
                    mChosenComponentSender.sendIntent(this, Activity.RESULT_OK, fillIn, null, null);
                } catch (IntentSender.SendIntentException e) {
                    Slog.e(TAG, "Unable to launch supplied IntentSender to report "
                            + "the chosen component: " + e);
                }
            }
        }
    }

    @Override
    public void onPrepareAdapterView(AbsListView adapterView, ResolveListAdapter adapter) {
        final ListView listView = adapterView instanceof ListView ? (ListView) adapterView : null;
        mChooserListAdapter = (ChooserListAdapter) adapter;
        if (mCallerChooserTargets != null && mCallerChooserTargets.length > 0) {
            mChooserListAdapter.addServiceResults(null, Lists.newArrayList(mCallerChooserTargets));
        }
        mChooserRowAdapter = new ChooserRowAdapter(mChooserListAdapter);
        mChooserRowAdapter.registerDataSetObserver(new OffsetDataSetObserver(adapterView));
        adapterView.setAdapter(mChooserRowAdapter);
        if (listView != null) {
            listView.setItemsCanFocus(true);
        }
    }

    @Override
    public int getLayoutResource() {
        return R.layout.chooser_grid;
    }

    @Override
    public boolean shouldGetActivityMetadata() {
        return true;
    }

    @Override
    public boolean shouldAutoLaunchSingleChoice(TargetInfo target) {
        // Note that this is only safe because the Intent handled by the ChooserActivity is
        // guaranteed to contain no extras unknown to the local ClassLoader. That is why this
        // method can not be replaced in the ResolverActivity whole hog.
        return getIntent().getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE,
                super.shouldAutoLaunchSingleChoice(target));
    }

    @Override
    public void showTargetDetails(ResolveInfo ri) {
        ComponentName name = ri.activityInfo.getComponentName();
        boolean pinned = mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        ResolverTargetActionsDialogFragment f =
                new ResolverTargetActionsDialogFragment(ri.loadLabel(getPackageManager()),
                        name, pinned);
        f.show(getFragmentManager(), TARGET_DETAILS_FRAGMENT_TAG);
    }

    private void modifyTargetIntent(Intent in) {
        final String action = in.getAction();
        if (Intent.ACTION_SEND.equals(action) ||
                Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
    }

    @Override
    protected boolean onTargetSelected(TargetInfo target, boolean alwaysCheck) {
        if (mRefinementIntentSender != null) {
            final Intent fillIn = new Intent();
            final List<Intent> sourceIntents = target.getAllSourceIntents();
            if (!sourceIntents.isEmpty()) {
                fillIn.putExtra(Intent.EXTRA_INTENT, sourceIntents.get(0));
                if (sourceIntents.size() > 1) {
                    final Intent[] alts = new Intent[sourceIntents.size() - 1];
                    for (int i = 1, N = sourceIntents.size(); i < N; i++) {
                        alts[i - 1] = sourceIntents.get(i);
                    }
                    fillIn.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, alts);
                }
                if (mRefinementResultReceiver != null) {
                    mRefinementResultReceiver.destroy();
                }
                mRefinementResultReceiver = new RefinementResultReceiver(this, target, null);
                fillIn.putExtra(Intent.EXTRA_RESULT_RECEIVER,
                        mRefinementResultReceiver);
                try {
                    mRefinementIntentSender.sendIntent(this, 0, fillIn, null, null);
                    return false;
                } catch (SendIntentException e) {
                    Log.e(TAG, "Refinement IntentSender failed to send", e);
                }
            }
        }
        updateModelAndChooserCounts(target);
        return super.onTargetSelected(target, alwaysCheck);
    }

    @Override
    public void startSelected(int which, boolean always, boolean filtered) {
        final long selectionCost = System.currentTimeMillis() - mChooserShownTime;
        super.startSelected(which, always, filtered);

        if (mChooserListAdapter != null) {
            // Log the index of which type of target the user picked.
            // Lower values mean the ranking was better.
            int cat = 0;
            int value = which;
            switch (mChooserListAdapter.getPositionTargetType(which)) {
                case ChooserListAdapter.TARGET_CALLER:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_APP_TARGET;
                    break;
                case ChooserListAdapter.TARGET_SERVICE:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET;
                    value -= mChooserListAdapter.getCallerTargetCount();
                    break;
                case ChooserListAdapter.TARGET_STANDARD:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_STANDARD_TARGET;
                    value -= mChooserListAdapter.getCallerTargetCount()
                            + mChooserListAdapter.getServiceTargetCount();
                    break;
            }

            if (cat != 0) {
                MetricsLogger.action(this, cat, value);
            }

            if (mIsSuccessfullySelected) {
                if (DEBUG) {
                    Log.d(TAG, "User Selection Time Cost is " + selectionCost);
                    Log.d(TAG, "position of selected app/service/caller is " +
                            Integer.toString(value));
                }
                MetricsLogger.histogram(null, "user_selection_cost_for_smart_sharing",
                        (int) selectionCost);
                MetricsLogger.histogram(null, "app_position_for_smart_sharing", value);
            }
        }
    }

    void queryTargetServices(ChooserListAdapter adapter) {
        final PackageManager pm = getPackageManager();
        int targetsToQuery = 0;
        for (int i = 0, N = adapter.getDisplayResolveInfoCount(); i < N; i++) {
            final DisplayResolveInfo dri = adapter.getDisplayResolveInfo(i);
            if (adapter.getScore(dri) == 0) {
                // A score of 0 means the app hasn't been used in some time;
                // don't query it as it's not likely to be relevant.
                continue;
            }
            final ActivityInfo ai = dri.getResolveInfo().activityInfo;
            final Bundle md = ai.metaData;
            final String serviceName = md != null ? convertServiceName(ai.packageName,
                    md.getString(ChooserTargetService.META_DATA_NAME)) : null;
            if (serviceName != null) {
                final ComponentName serviceComponent = new ComponentName(
                        ai.packageName, serviceName);
                final Intent serviceIntent = new Intent(ChooserTargetService.SERVICE_INTERFACE)
                        .setComponent(serviceComponent);

                if (DEBUG) {
                    Log.d(TAG, "queryTargets found target with service " + serviceComponent);
                }

                try {
                    final String perm = pm.getServiceInfo(serviceComponent, 0).permission;
                    if (!ChooserTargetService.BIND_PERMISSION.equals(perm)) {
                        Log.w(TAG, "ChooserTargetService " + serviceComponent + " does not require"
                                + " permission " + ChooserTargetService.BIND_PERMISSION
                                + " - this service will not be queried for ChooserTargets."
                                + " add android:permission=\""
                                + ChooserTargetService.BIND_PERMISSION + "\""
                                + " to the <service> tag for " + serviceComponent
                                + " in the manifest.");
                        continue;
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Could not look up service " + serviceComponent
                            + "; component name not found");
                    continue;
                }

                final ChooserTargetServiceConnection conn =
                        new ChooserTargetServiceConnection(this, dri);

                // Explicitly specify Process.myUserHandle instead of calling bindService
                // to avoid the warning from calling from the system process without an explicit
                // user handle
                if (bindServiceAsUser(serviceIntent, conn, BIND_AUTO_CREATE | BIND_NOT_FOREGROUND,
                        Process.myUserHandle())) {
                    if (DEBUG) {
                        Log.d(TAG, "Binding service connection for target " + dri
                                + " intent " + serviceIntent);
                    }
                    mServiceConnections.add(conn);
                    targetsToQuery++;
                }
            }
            if (targetsToQuery >= QUERY_TARGET_SERVICE_LIMIT) {
                if (DEBUG) Log.d(TAG, "queryTargets hit query target limit "
                        + QUERY_TARGET_SERVICE_LIMIT);
                break;
            }
        }

        if (!mServiceConnections.isEmpty()) {
            if (DEBUG) Log.d(TAG, "queryTargets setting watchdog timer for "
                    + WATCHDOG_TIMEOUT_MILLIS + "ms");
            mChooserHandler.sendEmptyMessageDelayed(CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT,
                    WATCHDOG_TIMEOUT_MILLIS);
        } else {
            sendVoiceChoicesIfNeeded();
        }
    }

    private String convertServiceName(String packageName, String serviceName) {
        if (TextUtils.isEmpty(serviceName)) {
            return null;
        }

        final String fullName;
        if (serviceName.startsWith(".")) {
            // Relative to the app package. Prepend the app package name.
            fullName = packageName + serviceName;
        } else if (serviceName.indexOf('.') >= 0) {
            // Fully qualified package name.
            fullName = serviceName;
        } else {
            fullName = null;
        }
        return fullName;
    }

    void unbindRemainingServices() {
        if (DEBUG) {
            Log.d(TAG, "unbindRemainingServices, " + mServiceConnections.size() + " left");
        }
        for (int i = 0, N = mServiceConnections.size(); i < N; i++) {
            final ChooserTargetServiceConnection conn = mServiceConnections.get(i);
            if (DEBUG) Log.d(TAG, "unbinding " + conn);
            unbindService(conn);
            conn.destroy();
        }
        mServiceConnections.clear();
        mChooserHandler.removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT);
    }

    public void onSetupVoiceInteraction() {
        // Do nothing. We'll send the voice stuff ourselves.
    }

    void updateModelAndChooserCounts(TargetInfo info) {
        if (info != null) {
            final ResolveInfo ri = info.getResolveInfo();
            Intent targetIntent = getTargetIntent();
            if (ri != null && ri.activityInfo != null && targetIntent != null) {
                if (mAdapter != null) {
                    mAdapter.updateModel(info.getResolvedComponentName());
                    mAdapter.updateChooserCounts(ri.activityInfo.packageName, getUserId(),
                            targetIntent.getAction());
                }
                if (DEBUG) {
                    Log.d(TAG, "ResolveInfo Package is " + ri.activityInfo.packageName);
                    Log.d(TAG, "Action to be updated is " + targetIntent.getAction());
                }
            } else if(DEBUG) {
                Log.d(TAG, "Can not log Chooser Counts of null ResovleInfo");
            }
        }
        mIsSuccessfullySelected = true;
    }

    void onRefinementResult(TargetInfo selectedTarget, Intent matchingIntent) {
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        if (selectedTarget == null) {
            Log.e(TAG, "Refinement result intent did not match any known targets; canceling");
        } else if (!checkTargetSourceIntent(selectedTarget, matchingIntent)) {
            Log.e(TAG, "onRefinementResult: Selected target " + selectedTarget
                    + " cannot match refined source intent " + matchingIntent);
        } else {
            TargetInfo clonedTarget = selectedTarget.cloneFilledIn(matchingIntent, 0);
            if (super.onTargetSelected(clonedTarget, false)) {
                updateModelAndChooserCounts(clonedTarget);
                finish();
                return;
            }
        }
        onRefinementCanceled();
    }

    void onRefinementCanceled() {
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        finish();
    }

    boolean checkTargetSourceIntent(TargetInfo target, Intent matchingIntent) {
        final List<Intent> targetIntents = target.getAllSourceIntents();
        for (int i = 0, N = targetIntents.size(); i < N; i++) {
            final Intent targetIntent = targetIntents.get(i);
            if (targetIntent.filterEquals(matchingIntent)) {
                return true;
            }
        }
        return false;
    }

    void filterServiceTargets(String packageName, List<ChooserTarget> targets) {
        if (targets == null) {
            return;
        }

        final PackageManager pm = getPackageManager();
        for (int i = targets.size() - 1; i >= 0; i--) {
            final ChooserTarget target = targets.get(i);
            final ComponentName targetName = target.getComponentName();
            if (packageName != null && packageName.equals(targetName.getPackageName())) {
                // Anything from the original target's package is fine.
                continue;
            }

            boolean remove;
            try {
                final ActivityInfo ai = pm.getActivityInfo(targetName, 0);
                remove = !ai.exported || ai.permission != null;
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Target " + target + " returned by " + packageName
                        + " component not found");
                remove = true;
            }

            if (remove) {
                targets.remove(i);
            }
        }
    }

    public class ChooserListController extends ResolverListController {
        public ChooserListController(Context context,
                PackageManager pm,
                Intent targetIntent,
                String referrerPackageName,
                int launchedFromUid) {
            super(context, pm, targetIntent, referrerPackageName, launchedFromUid);
        }

        @Override
        boolean isComponentPinned(ComponentName name) {
            return mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        }

        @Override
        boolean isComponentFiltered(ComponentName name) {
            if (mFilteredComponentNames == null) {
                return false;
            }
            for (ComponentName filteredComponentName : mFilteredComponentNames) {
                if (name.equals(filteredComponentName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public float getScore(DisplayResolveInfo target) {
            if (target == null) {
                return CALLER_TARGET_SCORE_BOOST;
            }
            float score = super.getScore(target);
            if (target.isPinned()) {
                score += PINNED_TARGET_SCORE_BOOST;
            }
            return score;
        }
    }

    @Override
    public ResolveListAdapter createAdapter(Context context, List<Intent> payloadIntents,
            Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
            boolean filterLastUsed) {
        final ChooserListAdapter adapter = new ChooserListAdapter(context, payloadIntents,
                initialIntents, rList, launchedFromUid, filterLastUsed, createListController());
        return adapter;
    }

    @VisibleForTesting
    protected ResolverListController createListController() {
        return new ChooserListController(
                this,
                mPm,
                getTargetIntent(),
                getReferrerPackageName(),
                mLaunchedFromUid);
    }

    final class ChooserTargetInfo implements TargetInfo {
        private final DisplayResolveInfo mSourceInfo;
        private final ResolveInfo mBackupResolveInfo;
        private final ChooserTarget mChooserTarget;
        private Drawable mBadgeIcon = null;
        private CharSequence mBadgeContentDescription;
        private Drawable mDisplayIcon;
        private final Intent mFillInIntent;
        private final int mFillInFlags;
        private final float mModifiedScore;

        public ChooserTargetInfo(DisplayResolveInfo sourceInfo, ChooserTarget chooserTarget,
                float modifiedScore) {
            mSourceInfo = sourceInfo;
            mChooserTarget = chooserTarget;
            mModifiedScore = modifiedScore;
            if (sourceInfo != null) {
                final ResolveInfo ri = sourceInfo.getResolveInfo();
                if (ri != null) {
                    final ActivityInfo ai = ri.activityInfo;
                    if (ai != null && ai.applicationInfo != null) {
                        final PackageManager pm = getPackageManager();
                        mBadgeIcon = pm.getApplicationIcon(ai.applicationInfo);
                        mBadgeContentDescription = pm.getApplicationLabel(ai.applicationInfo);
                    }
                }
            }
            final Icon icon = chooserTarget.getIcon();
            // TODO do this in the background
            mDisplayIcon = icon != null ? icon.loadDrawable(ChooserActivity.this) : null;

            if (sourceInfo != null) {
                mBackupResolveInfo = null;
            } else {
                mBackupResolveInfo = getPackageManager().resolveActivity(getResolvedIntent(), 0);
            }

            mFillInIntent = null;
            mFillInFlags = 0;
        }

        private ChooserTargetInfo(ChooserTargetInfo other, Intent fillInIntent, int flags) {
            mSourceInfo = other.mSourceInfo;
            mBackupResolveInfo = other.mBackupResolveInfo;
            mChooserTarget = other.mChooserTarget;
            mBadgeIcon = other.mBadgeIcon;
            mBadgeContentDescription = other.mBadgeContentDescription;
            mDisplayIcon = other.mDisplayIcon;
            mFillInIntent = fillInIntent;
            mFillInFlags = flags;
            mModifiedScore = other.mModifiedScore;
        }

        public float getModifiedScore() {
            return mModifiedScore;
        }

        @Override
        public Intent getResolvedIntent() {
            if (mSourceInfo != null) {
                return mSourceInfo.getResolvedIntent();
            }

            final Intent targetIntent = new Intent(getTargetIntent());
            targetIntent.setComponent(mChooserTarget.getComponentName());
            targetIntent.putExtras(mChooserTarget.getIntentExtras());
            return targetIntent;
        }

        @Override
        public ComponentName getResolvedComponentName() {
            if (mSourceInfo != null) {
                return mSourceInfo.getResolvedComponentName();
            } else if (mBackupResolveInfo != null) {
                return new ComponentName(mBackupResolveInfo.activityInfo.packageName,
                        mBackupResolveInfo.activityInfo.name);
            }
            return null;
        }

        private Intent getBaseIntentToSend() {
            Intent result = getResolvedIntent();
            if (result == null) {
                Log.e(TAG, "ChooserTargetInfo: no base intent available to send");
            } else {
                result = new Intent(result);
                if (mFillInIntent != null) {
                    result.fillIn(mFillInIntent, mFillInFlags);
                }
                result.fillIn(mReferrerFillInIntent, 0);
            }
            return result;
        }

        @Override
        public boolean start(Activity activity, Bundle options) {
            throw new RuntimeException("ChooserTargets should be started as caller.");
        }

        @Override
        public boolean startAsCaller(Activity activity, Bundle options, int userId) {
            final Intent intent = getBaseIntentToSend();
            if (intent == null) {
                return false;
            }
            intent.setComponent(mChooserTarget.getComponentName());
            intent.putExtras(mChooserTarget.getIntentExtras());

            // Important: we will ignore the target security checks in ActivityManager
            // if and only if the ChooserTarget's target package is the same package
            // where we got the ChooserTargetService that provided it. This lets a
            // ChooserTargetService provide a non-exported or permission-guarded target
            // to the chooser for the user to pick.
            //
            // If mSourceInfo is null, we got this ChooserTarget from the caller or elsewhere
            // so we'll obey the caller's normal security checks.
            final boolean ignoreTargetSecurity = mSourceInfo != null
                    && mSourceInfo.getResolvedComponentName().getPackageName()
                    .equals(mChooserTarget.getComponentName().getPackageName());
            activity.startActivityAsCaller(intent, options, ignoreTargetSecurity, userId);
            return true;
        }

        @Override
        public boolean startAsUser(Activity activity, Bundle options, UserHandle user) {
            throw new RuntimeException("ChooserTargets should be started as caller.");
        }

        @Override
        public ResolveInfo getResolveInfo() {
            return mSourceInfo != null ? mSourceInfo.getResolveInfo() : mBackupResolveInfo;
        }

        @Override
        public CharSequence getDisplayLabel() {
            return mChooserTarget.getTitle();
        }

        @Override
        public CharSequence getExtendedInfo() {
            // ChooserTargets have badge icons, so we won't show the extended info to disambiguate.
            return null;
        }

        @Override
        public Drawable getDisplayIcon() {
            return mDisplayIcon;
        }

        @Override
        public Drawable getBadgeIcon() {
            return mBadgeIcon;
        }

        @Override
        public CharSequence getBadgeContentDescription() {
            return mBadgeContentDescription;
        }

        @Override
        public TargetInfo cloneFilledIn(Intent fillInIntent, int flags) {
            return new ChooserTargetInfo(this, fillInIntent, flags);
        }

        @Override
        public List<Intent> getAllSourceIntents() {
            final List<Intent> results = new ArrayList<>();
            if (mSourceInfo != null) {
                // We only queried the service for the first one in our sourceinfo.
                results.add(mSourceInfo.getAllSourceIntents().get(0));
            }
            return results;
        }

        @Override
        public boolean isPinned() {
            return mSourceInfo != null ? mSourceInfo.isPinned() : false;
        }
    }

    public class ChooserListAdapter extends ResolveListAdapter {
        public static final int TARGET_BAD = -1;
        public static final int TARGET_CALLER = 0;
        public static final int TARGET_SERVICE = 1;
        public static final int TARGET_STANDARD = 2;

        private static final int MAX_SERVICE_TARGETS = 4;
        private static final int MAX_TARGETS_PER_SERVICE = 2;

        private final List<ChooserTargetInfo> mServiceTargets = new ArrayList<>();
        private final List<TargetInfo> mCallerTargets = new ArrayList<>();
        private boolean mShowServiceTargets;

        private float mLateFee = 1.f;

        private final BaseChooserTargetComparator mBaseTargetComparator
                = new BaseChooserTargetComparator();

        public ChooserListAdapter(Context context, List<Intent> payloadIntents,
                Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
                boolean filterLastUsed, ResolverListController resolverListController) {
            // Don't send the initial intents through the shared ResolverActivity path,
            // we want to separate them into a different section.
            super(context, payloadIntents, null, rList, launchedFromUid, filterLastUsed,
                    resolverListController);

            if (initialIntents != null) {
                final PackageManager pm = getPackageManager();
                for (int i = 0; i < initialIntents.length; i++) {
                    final Intent ii = initialIntents[i];
                    if (ii == null) {
                        continue;
                    }

                    // We reimplement Intent#resolveActivityInfo here because if we have an
                    // implicit intent, we want the ResolveInfo returned by PackageManager
                    // instead of one we reconstruct ourselves. The ResolveInfo returned might
                    // have extra metadata and resolvePackageName set and we want to respect that.
                    ResolveInfo ri = null;
                    ActivityInfo ai = null;
                    final ComponentName cn = ii.getComponent();
                    if (cn != null) {
                        try {
                            ai = pm.getActivityInfo(ii.getComponent(), 0);
                            ri = new ResolveInfo();
                            ri.activityInfo = ai;
                        } catch (PackageManager.NameNotFoundException ignored) {
                            // ai will == null below
                        }
                    }
                    if (ai == null) {
                        ri = pm.resolveActivity(ii, PackageManager.MATCH_DEFAULT_ONLY);
                        ai = ri != null ? ri.activityInfo : null;
                    }
                    if (ai == null) {
                        Log.w(TAG, "No activity found for " + ii);
                        continue;
                    }
                    UserManager userManager =
                            (UserManager) getSystemService(Context.USER_SERVICE);
                    if (ii instanceof LabeledIntent) {
                        LabeledIntent li = (LabeledIntent)ii;
                        ri.resolvePackageName = li.getSourcePackage();
                        ri.labelRes = li.getLabelResource();
                        ri.nonLocalizedLabel = li.getNonLocalizedLabel();
                        ri.icon = li.getIconResource();
                        ri.iconResourceId = ri.icon;
                    }
                    if (userManager.isManagedProfile()) {
                        ri.noResourceId = true;
                        ri.icon = 0;
                    }
                    mCallerTargets.add(new DisplayResolveInfo(ii, ri,
                            ri.loadLabel(pm), null, ii));
                }
            }
        }

        @Override
        public boolean showsExtendedInfo(TargetInfo info) {
            // We have badges so we don't need this text shown.
            return false;
        }

        @Override
        public boolean isComponentPinned(ComponentName name) {
            return mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        }

        @Override
        public View onCreateView(ViewGroup parent) {
            return mInflater.inflate(
                    com.android.internal.R.layout.resolve_grid_item, parent, false);
        }

        @Override
        public void onListRebuilt() {
            if (mServiceTargets != null) {
                pruneServiceTargets();
            }
            if (DEBUG) Log.d(TAG, "List built querying services");
            queryTargetServices(this);
        }

        @Override
        public boolean shouldGetResolvedFilter() {
            return true;
        }

        @Override
        public int getCount() {
            return super.getCount() + getServiceTargetCount() + getCallerTargetCount();
        }

        @Override
        public int getUnfilteredCount() {
            return super.getUnfilteredCount() + getServiceTargetCount() + getCallerTargetCount();
        }

        public int getCallerTargetCount() {
            return mCallerTargets.size();
        }

        public int getServiceTargetCount() {
            if (!mShowServiceTargets) {
                return 0;
            }
            return Math.min(mServiceTargets.size(), MAX_SERVICE_TARGETS);
        }

        public int getStandardTargetCount() {
            return super.getCount();
        }

        public int getPositionTargetType(int position) {
            int offset = 0;

            final int callerTargetCount = getCallerTargetCount();
            if (position < callerTargetCount) {
                return TARGET_CALLER;
            }
            offset += callerTargetCount;

            final int serviceTargetCount = getServiceTargetCount();
            if (position - offset < serviceTargetCount) {
                return TARGET_SERVICE;
            }
            offset += serviceTargetCount;

            final int standardTargetCount = super.getCount();
            if (position - offset < standardTargetCount) {
                return TARGET_STANDARD;
            }

            return TARGET_BAD;
        }

        @Override
        public TargetInfo getItem(int position) {
            return targetInfoForPosition(position, true);
        }

        @Override
        public TargetInfo targetInfoForPosition(int position, boolean filtered) {
            int offset = 0;

            final int callerTargetCount = getCallerTargetCount();
            if (position < callerTargetCount) {
                return mCallerTargets.get(position);
            }
            offset += callerTargetCount;

            final int serviceTargetCount = getServiceTargetCount();
            if (position - offset < serviceTargetCount) {
                return mServiceTargets.get(position - offset);
            }
            offset += serviceTargetCount;

            return filtered ? super.getItem(position - offset)
                    : getDisplayInfoAt(position - offset);
        }

        public void addServiceResults(DisplayResolveInfo origTarget, List<ChooserTarget> targets) {
            if (DEBUG) Log.d(TAG, "addServiceResults " + origTarget + ", " + targets.size()
                    + " targets");
            final float parentScore = getScore(origTarget);
            Collections.sort(targets, mBaseTargetComparator);
            float lastScore = 0;
            for (int i = 0, N = Math.min(targets.size(), MAX_TARGETS_PER_SERVICE); i < N; i++) {
                final ChooserTarget target = targets.get(i);
                float targetScore = target.getScore();
                targetScore *= parentScore;
                targetScore *= mLateFee;
                if (i > 0 && targetScore >= lastScore) {
                    // Apply a decay so that the top app can't crowd out everything else.
                    // This incents ChooserTargetServices to define what's truly better.
                    targetScore = lastScore * 0.95f;
                }
                insertServiceTarget(new ChooserTargetInfo(origTarget, target, targetScore));

                if (DEBUG) {
                    Log.d(TAG, " => " + target.toString() + " score=" + targetScore
                            + " base=" + target.getScore()
                            + " lastScore=" + lastScore
                            + " parentScore=" + parentScore
                            + " lateFee=" + mLateFee);
                }

                lastScore = targetScore;
            }

            mLateFee *= 0.95f;

            notifyDataSetChanged();
        }

        /**
         * Set to true to reveal all service targets at once.
         */
        public void setShowServiceTargets(boolean show) {
            if (show != mShowServiceTargets) {
                mShowServiceTargets = show;
                notifyDataSetChanged();
            }
        }

        private void insertServiceTarget(ChooserTargetInfo chooserTargetInfo) {
            final float newScore = chooserTargetInfo.getModifiedScore();
            for (int i = 0, N = mServiceTargets.size(); i < N; i++) {
                final ChooserTargetInfo serviceTarget = mServiceTargets.get(i);
                if (newScore > serviceTarget.getModifiedScore()) {
                    mServiceTargets.add(i, chooserTargetInfo);
                    return;
                }
            }
            mServiceTargets.add(chooserTargetInfo);
        }

        private void pruneServiceTargets() {
            if (DEBUG) Log.d(TAG, "pruneServiceTargets");
            for (int i = mServiceTargets.size() - 1; i >= 0; i--) {
                final ChooserTargetInfo cti = mServiceTargets.get(i);
                if (!hasResolvedTarget(cti.getResolveInfo())) {
                    if (DEBUG) Log.d(TAG, " => " + i + " " + cti);
                    mServiceTargets.remove(i);
                }
            }
        }
    }

    static class BaseChooserTargetComparator implements Comparator<ChooserTarget> {
        @Override
        public int compare(ChooserTarget lhs, ChooserTarget rhs) {
            // Descending order
            return (int) Math.signum(rhs.getScore() - lhs.getScore());
        }
    }

    static class RowScale {
        private static final int DURATION = 400;

        float mScale;
        ChooserRowAdapter mAdapter;
        private final ObjectAnimator mAnimator;

        public static final FloatProperty<RowScale> PROPERTY =
                new FloatProperty<RowScale>("scale") {
            @Override
            public void setValue(RowScale object, float value) {
                object.mScale = value;
                object.mAdapter.notifyDataSetChanged();
            }

            @Override
            public Float get(RowScale object) {
                return object.mScale;
            }
        };

        public RowScale(@NonNull ChooserRowAdapter adapter, float from, float to) {
            mAdapter = adapter;
            mScale = from;
            if (from == to) {
                mAnimator = null;
                return;
            }

            mAnimator = ObjectAnimator.ofFloat(this, PROPERTY, from, to)
                .setDuration(DURATION);
            mAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mAdapter.onAnimationStart();
                }
                @Override
                public void onAnimationEnd(Animator animation) {
                    mAdapter.onAnimationEnd();
                }
            });
        }

        public RowScale setInterpolator(Interpolator interpolator) {
            if (mAnimator != null) {
                mAnimator.setInterpolator(interpolator);
            }
            return this;
        }

        public float get() {
            return mScale;
        }

        public void startAnimation() {
            if (mAnimator != null) {
                mAnimator.start();
            }
        }

        public void cancelAnimation() {
            if (mAnimator != null) {
                mAnimator.cancel();
            }
        }
    }

    class ChooserRowAdapter extends BaseAdapter {
        private ChooserListAdapter mChooserListAdapter;
        private final LayoutInflater mLayoutInflater;
        private final int mColumnCount = 4;
        private RowScale[] mServiceTargetScale;
        private final Interpolator mInterpolator;
        private int mAnimationCount = 0;

        public ChooserRowAdapter(ChooserListAdapter wrappedAdapter) {
            mChooserListAdapter = wrappedAdapter;
            mLayoutInflater = LayoutInflater.from(ChooserActivity.this);

            mInterpolator = AnimationUtils.loadInterpolator(ChooserActivity.this,
                    android.R.interpolator.decelerate_quint);

            wrappedAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    super.onChanged();
                    final int rcount = getServiceTargetRowCount();
                    if (mServiceTargetScale == null
                            || mServiceTargetScale.length != rcount) {
                        RowScale[] old = mServiceTargetScale;
                        int oldRCount = old != null ? old.length : 0;
                        mServiceTargetScale = new RowScale[rcount];
                        if (old != null && rcount > 0) {
                            System.arraycopy(old, 0, mServiceTargetScale, 0,
                                    Math.min(old.length, rcount));
                        }

                        for (int i = rcount; i < oldRCount; i++) {
                            old[i].cancelAnimation();
                        }

                        for (int i = oldRCount; i < rcount; i++) {
                            final RowScale rs = new RowScale(ChooserRowAdapter.this, 0.f, 1.f)
                                    .setInterpolator(mInterpolator);
                            mServiceTargetScale[i] = rs;
                        }

                        // Start the animations in a separate loop.
                        // The process of starting animations will result in
                        // binding views to set up initial values, and we must
                        // have ALL of the new RowScale objects created above before
                        // we get started.
                        for (int i = oldRCount; i < rcount; i++) {
                            mServiceTargetScale[i].startAnimation();
                        }
                    }

                    notifyDataSetChanged();
                }

                @Override
                public void onInvalidated() {
                    super.onInvalidated();
                    notifyDataSetInvalidated();
                    if (mServiceTargetScale != null) {
                        for (RowScale rs : mServiceTargetScale) {
                            rs.cancelAnimation();
                        }
                    }
                }
            });
        }

        private float getRowScale(int rowPosition) {
            final int start = getCallerTargetRowCount();
            final int end = start + getServiceTargetRowCount();
            if (rowPosition >= start && rowPosition < end) {
                return mServiceTargetScale[rowPosition - start].get();
            }
            return 1.f;
        }

        public void onAnimationStart() {
            final boolean lock = mAnimationCount == 0;
            mAnimationCount++;
            if (lock) {
                mResolverDrawerLayout.setDismissLocked(true);
            }
        }

        public void onAnimationEnd() {
            mAnimationCount--;
            if (mAnimationCount == 0) {
                mResolverDrawerLayout.setDismissLocked(false);
            }
        }

        @Override
        public int getCount() {
            return (int) (
                    getCallerTargetRowCount()
                    + getServiceTargetRowCount()
                    + Math.ceil((float) mChooserListAdapter.getStandardTargetCount() / mColumnCount)
            );
        }

        public int getCallerTargetRowCount() {
            return (int) Math.ceil(
                    (float) mChooserListAdapter.getCallerTargetCount() / mColumnCount);
        }

        public int getServiceTargetRowCount() {
            return (int) Math.ceil(
                    (float) mChooserListAdapter.getServiceTargetCount() / mColumnCount);
        }

        @Override
        public Object getItem(int position) {
            // We have nothing useful to return here.
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final RowViewHolder holder;
            if (convertView == null) {
                holder = createViewHolder(parent);
            } else {
                holder = (RowViewHolder) convertView.getTag();
            }
            bindViewHolder(position, holder);

            return holder.row;
        }

        RowViewHolder createViewHolder(ViewGroup parent) {
            final ViewGroup row = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row,
                    parent, false);
            final RowViewHolder holder = new RowViewHolder(row, mColumnCount);
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

            for (int i = 0; i < mColumnCount; i++) {
                final View v = mChooserListAdapter.createView(row);
                final int column = i;
                v.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startSelected(holder.itemIndices[column], false, true);
                    }
                });
                v.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        showTargetDetails(
                                mChooserListAdapter.resolveInfoForPosition(
                                        holder.itemIndices[column], true));
                        return true;
                    }
                });
                row.addView(v);
                holder.cells[i] = v;

                // Force height to be a given so we don't have visual disruption during scaling.
                LayoutParams lp = v.getLayoutParams();
                v.measure(spec, spec);
                if (lp == null) {
                    lp = new LayoutParams(LayoutParams.MATCH_PARENT, v.getMeasuredHeight());
                    row.setLayoutParams(lp);
                } else {
                    lp.height = v.getMeasuredHeight();
                }
                if (i != (mColumnCount - 1)) {
                    row.addView(new Space(ChooserActivity.this),
                            new LinearLayout.LayoutParams(0, 0, 1));
                }
            }

            // Pre-measure so we can scale later.
            holder.measure();
            LayoutParams lp = row.getLayoutParams();
            if (lp == null) {
                lp = new LayoutParams(LayoutParams.MATCH_PARENT, holder.measuredRowHeight);
                row.setLayoutParams(lp);
            } else {
                lp.height = holder.measuredRowHeight;
            }
            row.setTag(holder);
            return holder;
        }

        void bindViewHolder(int rowPosition, RowViewHolder holder) {
            final int start = getFirstRowPosition(rowPosition);
            final int startType = mChooserListAdapter.getPositionTargetType(start);

            int end = start + mColumnCount - 1;
            while (mChooserListAdapter.getPositionTargetType(end) != startType && end >= start) {
                end--;
            }

            if (startType == ChooserListAdapter.TARGET_SERVICE) {
                holder.row.setBackgroundColor(
                        getColor(R.color.chooser_service_row_background_color));
                int nextStartType = mChooserListAdapter.getPositionTargetType(
                        getFirstRowPosition(rowPosition + 1));
                int serviceSpacing = holder.row.getContext().getResources()
                        .getDimensionPixelSize(R.dimen.chooser_service_spacing);
                if (rowPosition == 0 && nextStartType != ChooserListAdapter.TARGET_SERVICE) {
                    // if the row is the only row for target service
                    setVertPadding(holder, 0, 0);
                } else {
                    int top = rowPosition == 0 ? serviceSpacing : 0;
                    if (nextStartType != ChooserListAdapter.TARGET_SERVICE) {
                        setVertPadding(holder, top, serviceSpacing);
                    } else {
                        setVertPadding(holder, top, 0);
                    }
                }
            } else {
                holder.row.setBackgroundColor(Color.TRANSPARENT);
                int lastStartType = mChooserListAdapter.getPositionTargetType(
                        getFirstRowPosition(rowPosition - 1));
                if (lastStartType == ChooserListAdapter.TARGET_SERVICE || rowPosition == 0) {
                    int serviceSpacing = holder.row.getContext().getResources()
                            .getDimensionPixelSize(R.dimen.chooser_service_spacing);
                    setVertPadding(holder, serviceSpacing, 0);
                } else {
                    setVertPadding(holder, 0, 0);
                }
            }

            final int oldHeight = holder.row.getLayoutParams().height;
            holder.row.getLayoutParams().height = Math.max(1,
                    (int) (holder.measuredRowHeight * getRowScale(rowPosition)));
            if (holder.row.getLayoutParams().height != oldHeight) {
                holder.row.requestLayout();
            }

            for (int i = 0; i < mColumnCount; i++) {
                final View v = holder.cells[i];
                if (start + i <= end) {
                    v.setVisibility(View.VISIBLE);
                    holder.itemIndices[i] = start + i;
                    mChooserListAdapter.bindView(holder.itemIndices[i], v);
                } else {
                    v.setVisibility(View.INVISIBLE);
                }
            }
        }

        private void setVertPadding(RowViewHolder holder, int top, int bottom) {
            holder.row.setPadding(holder.row.getPaddingLeft(), top,
                    holder.row.getPaddingRight(), bottom);
        }

        int getFirstRowPosition(int row) {
            final int callerCount = mChooserListAdapter.getCallerTargetCount();
            final int callerRows = (int) Math.ceil((float) callerCount / mColumnCount);

            if (row < callerRows) {
                return row * mColumnCount;
            }

            final int serviceCount = mChooserListAdapter.getServiceTargetCount();
            final int serviceRows = (int) Math.ceil((float) serviceCount / mColumnCount);

            if (row < callerRows + serviceRows) {
                return callerCount + (row - callerRows) * mColumnCount;
            }

            return callerCount + serviceCount
                    + (row - callerRows - serviceRows) * mColumnCount;
        }
    }

    static class RowViewHolder {
        final View[] cells;
        final ViewGroup row;
        int measuredRowHeight;
        int[] itemIndices;

        public RowViewHolder(ViewGroup row, int cellCount) {
            this.row = row;
            this.cells = new View[cellCount];
            this.itemIndices = new int[cellCount];
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            row.measure(spec, spec);
            measuredRowHeight = row.getMeasuredHeight();
        }
    }

    static class ChooserTargetServiceConnection implements ServiceConnection {
        private DisplayResolveInfo mOriginalTarget;
        private ComponentName mConnectedComponent;
        private ChooserActivity mChooserActivity;
        private final Object mLock = new Object();

        private final IChooserTargetResult mChooserTargetResult = new IChooserTargetResult.Stub() {
            @Override
            public void sendResult(List<ChooserTarget> targets) throws RemoteException {
                synchronized (mLock) {
                    if (mChooserActivity == null) {
                        Log.e(TAG, "destroyed ChooserTargetServiceConnection received result from "
                                + mConnectedComponent + "; ignoring...");
                        return;
                    }
                    mChooserActivity.filterServiceTargets(
                            mOriginalTarget.getResolveInfo().activityInfo.packageName, targets);
                    final Message msg = Message.obtain();
                    msg.what = CHOOSER_TARGET_SERVICE_RESULT;
                    msg.obj = new ServiceResultInfo(mOriginalTarget, targets,
                            ChooserTargetServiceConnection.this);
                    mChooserActivity.mChooserHandler.sendMessage(msg);
                }
            }
        };

        public ChooserTargetServiceConnection(ChooserActivity chooserActivity,
                DisplayResolveInfo dri) {
            mChooserActivity = chooserActivity;
            mOriginalTarget = dri;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.d(TAG, "onServiceConnected: " + name);
            synchronized (mLock) {
                if (mChooserActivity == null) {
                    Log.e(TAG, "destroyed ChooserTargetServiceConnection got onServiceConnected");
                    return;
                }

                final IChooserTargetService icts = IChooserTargetService.Stub.asInterface(service);
                try {
                    icts.getChooserTargets(mOriginalTarget.getResolvedComponentName(),
                            mOriginalTarget.getResolveInfo().filter, mChooserTargetResult);
                } catch (RemoteException e) {
                    Log.e(TAG, "Querying ChooserTargetService " + name + " failed.", e);
                    mChooserActivity.unbindService(this);
                    mChooserActivity.mServiceConnections.remove(this);
                    destroy();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected: " + name);
            synchronized (mLock) {
                if (mChooserActivity == null) {
                    Log.e(TAG,
                            "destroyed ChooserTargetServiceConnection got onServiceDisconnected");
                    return;
                }

                mChooserActivity.unbindService(this);
                mChooserActivity.mServiceConnections.remove(this);
                if (mChooserActivity.mServiceConnections.isEmpty()) {
                    mChooserActivity.mChooserHandler.removeMessages(
                            CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT);
                    mChooserActivity.sendVoiceChoicesIfNeeded();
                }
                mConnectedComponent = null;
                destroy();
            }
        }

        public void destroy() {
            synchronized (mLock) {
                mChooserActivity = null;
                mOriginalTarget = null;
            }
        }

        @Override
        public String toString() {
            return "ChooserTargetServiceConnection{service="
                    + mConnectedComponent + ", activity="
                    + (mOriginalTarget != null
                    ? mOriginalTarget.getResolveInfo().activityInfo.toString()
                    : "<connection destroyed>") + "}";
        }
    }

    static class ServiceResultInfo {
        public final DisplayResolveInfo originalTarget;
        public final List<ChooserTarget> resultTargets;
        public final ChooserTargetServiceConnection connection;

        public ServiceResultInfo(DisplayResolveInfo ot, List<ChooserTarget> rt,
                ChooserTargetServiceConnection c) {
            originalTarget = ot;
            resultTargets = rt;
            connection = c;
        }
    }

    static class RefinementResultReceiver extends ResultReceiver {
        private ChooserActivity mChooserActivity;
        private TargetInfo mSelectedTarget;

        public RefinementResultReceiver(ChooserActivity host, TargetInfo target,
                Handler handler) {
            super(handler);
            mChooserActivity = host;
            mSelectedTarget = target;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (mChooserActivity == null) {
                Log.e(TAG, "Destroyed RefinementResultReceiver received a result");
                return;
            }
            if (resultData == null) {
                Log.e(TAG, "RefinementResultReceiver received null resultData");
                return;
            }

            switch (resultCode) {
                case RESULT_CANCELED:
                    mChooserActivity.onRefinementCanceled();
                    break;
                case RESULT_OK:
                    Parcelable intentParcelable = resultData.getParcelable(Intent.EXTRA_INTENT);
                    if (intentParcelable instanceof Intent) {
                        mChooserActivity.onRefinementResult(mSelectedTarget,
                                (Intent) intentParcelable);
                    } else {
                        Log.e(TAG, "RefinementResultReceiver received RESULT_OK but no Intent"
                                + " in resultData with key Intent.EXTRA_INTENT");
                    }
                    break;
                default:
                    Log.w(TAG, "Unknown result code " + resultCode
                            + " sent to RefinementResultReceiver");
                    break;
            }
        }

        public void destroy() {
            mChooserActivity = null;
            mSelectedTarget = null;
        }
    }

    class OffsetDataSetObserver extends DataSetObserver {
        private final AbsListView mListView;
        private int mCachedViewType = -1;
        private View mCachedView;

        public OffsetDataSetObserver(AbsListView listView) {
            mListView = listView;
        }

        @Override
        public void onChanged() {
            if (mResolverDrawerLayout == null) {
                return;
            }

            final int chooserTargetRows = mChooserRowAdapter.getServiceTargetRowCount();
            int offset = 0;
            for (int i = 0; i < chooserTargetRows; i++)  {
                final int pos = mChooserRowAdapter.getCallerTargetRowCount() + i;
                final int vt = mChooserRowAdapter.getItemViewType(pos);
                if (vt != mCachedViewType) {
                    mCachedView = null;
                }
                final View v = mChooserRowAdapter.getView(pos, mCachedView, mListView);
                int height = ((RowViewHolder) (v.getTag())).measuredRowHeight;

                offset += (int) (height * mChooserRowAdapter.getRowScale(pos));

                if (vt >= 0) {
                    mCachedViewType = vt;
                    mCachedView = v;
                } else {
                    mCachedViewType = -1;
                }
            }

            mResolverDrawerLayout.setCollapsibleHeightReserved(offset);
        }
    }
}
