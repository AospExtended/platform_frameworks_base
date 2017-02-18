/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2012-2015 The CyanogenMod Project
 * Copyright 2014-2015 The Euphoria-OS Project
 * Copyright (C) 2017 Android Ice Cold Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyControl;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import java.util.ArrayList;
import java.util.Arrays;

/** Quick settings tile: Expanded desktop **/
public class ExpandedDesktopTile extends QSTileImpl<State> {

    private static final int STATE_ENABLE_FOR_ALL = 1;
    private static final int STATE_ENABLE_FOR_STATUSBAR = 2;
    private static final int STATE_ENABLE_FOR_NAVBAR = 3;
    private static final int STATE_USER_CONFIGURABLE = 4;

    private int mExpandedDesktopState;
    private int mExpandedDesktopStyle;
    private ExpandedDesktopObserver mObserver;
    private boolean mListening;
    private boolean mHasNavigationBar;

    private String[] mEntries, mValues;
    private boolean mShowingDetail;
    private ArrayList<Integer> mAnimationList = new ArrayList<Integer>();

    public ExpandedDesktopTile(QSHost host) {
        super(host);
        mExpandedDesktopState = getExpandedDesktopState(mContext.getContentResolver());
        mExpandedDesktopStyle = getExpandedDesktopStyle(mContext.getContentResolver());
        mObserver = new ExpandedDesktopObserver(mHandler);
        populateList();
    }

    private void populateList() {
        try {
            Context context = mContext.createPackageContext("org.lineageos.lineageparts", 0);
            Resources mSettingsResources = context.getResources();
            int id = mSettingsResources.getIdentifier("expanded_desktop_style_entries",
                    "array", "org.lineageos.lineageparts");
            if (id < 0) {
                return;
            }
            mEntries = mSettingsResources.getStringArray(id);
            id = mSettingsResources.getIdentifier("expanded_desktop_style_values",
                    "array", "org.lineageos.lineageparts");
            if (id < 0) {
                return;
            }
            mValues = mSettingsResources.getStringArray(id);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public void handleClick() {
        toggleState();
        refreshState();
    }

    @Override
    protected void handleLongClick() {
        if (mEntries.length > 0) {
            mShowingDetail = true;
            mAnimationList.clear();
            if (mExpandedDesktopState != STATE_ENABLE_FOR_ALL) {
                enableForAll();
            }
            showDetail(true);
        } else {
            super.handleLongClick();
        }
}

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$ExpandedDesktopSettingsActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_expanded_desktop_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new ExpandedDesktopDetailAdapter();
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (mAnimationList.isEmpty() && mShowingDetail && arg == null) {
            return;
        }
        if (mExpandedDesktopState == STATE_ENABLE_FOR_ALL) {
            if (mExpandedDesktopStyle == 1) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop_statusbar);
            } else if (mExpandedDesktopStyle == 2) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop_navbar);
            } else {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop);
            }
            state.label = mContext.getString(R.string.quick_settings_expanded_desktop);
        } else if (mExpandedDesktopState == 2) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_statusbar_off);
            state.label = mContext.getString(R.string.quick_settings_expanded_statusbar_off);
        } else if (mExpandedDesktopState == 3) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_navigation_off);
            state.label = mContext.getString(R.string.quick_settings_expanded_navigation_off);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop_off);
            state.label = mContext.getString(R.string.quick_settings_expanded_desktop_off);
        }
    }

    protected void toggleState() {
        try {
            mHasNavigationBar = WindowManagerGlobal.getWindowManagerService().hasNavigationBar();
        } catch (RemoteException e) {
            // Do nothing
        }
        int state = mExpandedDesktopState;
        switch (state) {
            case STATE_ENABLE_FOR_ALL:
                if (mHasNavigationBar) {
                  enableForStatusbar();
                } else {
                  userConfigurableSettings();
                }
                break;
            case STATE_ENABLE_FOR_STATUSBAR:
                enableForNavbar();
                break;
            case STATE_ENABLE_FOR_NAVBAR:
                userConfigurableSettings();
                break;
            case STATE_USER_CONFIGURABLE:
                enableForAll();
                break;
        }
    }

    private void writeValue(String value) {
        Settings.Global.putString(mContext.getContentResolver(),
             Settings.Global.POLICY_CONTROL, value);
    }

    private void enableForAll() {
        mExpandedDesktopState = STATE_ENABLE_FOR_ALL;
        writeValue("immersive.full=*");
    }

    private void enableForStatusbar() {
        mExpandedDesktopState = STATE_ENABLE_FOR_STATUSBAR;
        writeValue("immersive.status=*");
    }

    private void enableForNavbar() {
        mExpandedDesktopState = STATE_ENABLE_FOR_NAVBAR;
        writeValue("immersive.navigation=*");
    }

    private void userConfigurableSettings() {
        mExpandedDesktopState = STATE_USER_CONFIGURABLE;
        writeValue("");
        WindowManagerPolicyControl.reloadFromSetting(mContext);
    }

    private int getExpandedDesktopState(ContentResolver cr) {
        String value = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        if ("immersive.full=*".equals(value)) {
            return STATE_ENABLE_FOR_ALL;
        }
        if ("immersive.status=*".equals(value)) {
            return STATE_ENABLE_FOR_STATUSBAR;
        }
        if ("immersive.navigation=*".equals(value)) {
            return STATE_ENABLE_FOR_NAVBAR;
        }
        return STATE_USER_CONFIGURABLE;
    }

    private int getExpandedDesktopStyle(ContentResolver cr) {
        return Settings.Global.getInt(cr, Settings.Global.POLICY_CONTROL_STYLE, 0);
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        if (listening) {
            mObserver.startObserving();
        } else {
            mObserver.endObserving();
        }
    }

    private class ExpandedDesktopObserver extends ContentObserver {
        public ExpandedDesktopObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mExpandedDesktopState = getExpandedDesktopState(mContext.getContentResolver());
            mExpandedDesktopStyle = getExpandedDesktopStyle(mContext.getContentResolver());
            mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL),
                    false, this);
            mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL_STYLE), false, this);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }

    private class RadioAdapter extends ArrayAdapter<String> {

        public RadioAdapter(Context context, int resource, String[] objects) {
            super(context, resource, objects);
        }

        public RadioAdapter(Context context, int resource,
                            int textViewResourceId, String[] objects) {
            super(context, resource, textViewResourceId, objects);
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            view = super.getView(position, view, parent);
            view.setMinimumHeight(mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_detail_item_height));
            if (mExpandedDesktopState != STATE_ENABLE_FOR_ALL) {
                view.setVisibility(View.GONE);
            } else {
                view.setVisibility(View.VISIBLE);
            }
            notifyDataSetChanged();
            return view;
        }
    }

    private class ExpandedDesktopDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItems;

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.EXTENSIONS;
        }

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_expanded_desktop_label);
        }

        @Override
        public Boolean getToggleState() {
            return mExpandedDesktopState == STATE_ENABLE_FOR_ALL;
        }

        @Override
        public Intent getSettingsIntent() {
            return getLongClickIntent();
        }

        @Override
        public void setToggleState(boolean state) {
            MetricsLogger.action(mContext, getMetricsCategory());
            if (state) {
                enableForAll();
            } else {
                userConfigurableSettings();
                showDetail(false);
            }
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItems = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            ListView listView = mItems.getListView();
            listView.setOnItemClickListener(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            listView.setDivider(null);
            RadioAdapter adapter = new RadioAdapter(context,
                    android.R.layout.simple_list_item_single_choice, mEntries);
            int indexOfSelection = Arrays.asList(mValues).indexOf(String.valueOf(mExpandedDesktopStyle));
            mItems.setAdapter(adapter);
            listView.setItemChecked(indexOfSelection, true);
            mItems.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mUiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mShowingDetail = false;
                            refreshState(true);
                        }
                    }, 100);
                }
            });
            return mItems;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            mExpandedDesktopStyle = Integer.valueOf(mValues[position]);
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.POLICY_CONTROL_STYLE, mExpandedDesktopStyle);
            // We need to visually show the change
            // TODO: This is hacky, but it (usually) works
            writeValue("");
            writeValue("immersive.full=*");
            WindowManagerPolicyControl.reloadFromSetting(mContext);
        }
    }
}
