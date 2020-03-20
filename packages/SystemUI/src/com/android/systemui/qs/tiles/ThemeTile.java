/*
 * Copyright (C) 2018 The Dirty Unicorns Project
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

import static android.os.UserHandle.USER_SYSTEM;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.aospextended.ThemeUtils;
import com.android.internal.util.aospextended.AEXUtils;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

public class ThemeTile extends QSTileImpl<BooleanState> {

    private List<String> mLabels;
    private List<String> mAccentPkgs;
    private List<String> mThemePkgs;
    private List<Integer> mColors;
    private List<String> mThemeLabels;

    private List<ThemeTileItem> sThemeItems = new ArrayList<ThemeTileItem>();
    private List<ThemeTileItem> sStyleItems = new ArrayList<ThemeTileItem>();

    private enum Mode {
        ACCENT, STYLE
    }

    private static IOverlayManager mOverlayManager;
    private Mode mMode;
    private static UiModeManager mUiModeManager;

    private ThemeUtils mThemeUtils;

    @Inject
    public ThemeTile(QSHost host) {
        super(host);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mMode = Mode.ACCENT;
        mThemeUtils = new ThemeUtils(mContext);
        mLabels = mThemeUtils.getLabels(ThemeUtils.ACCENT_KEY);
        mAccentPkgs = mThemeUtils.getOverlayPackagesForCategory(ThemeUtils.ACCENT_KEY);
        mThemePkgs = mThemeUtils.getThemePackages();
        mColors = mThemeUtils.getColors();
        mThemeLabels = mThemeUtils.getThemeLabels();
        initItems();
    }

    public void initItems() {
        for (String overlayPackage : mAccentPkgs) {
            sThemeItems.add(new ThemeTileItem(mAccentPkgs.indexOf(overlayPackage), mColors.get(mAccentPkgs.indexOf(overlayPackage)),
                    mLabels.get(mAccentPkgs.indexOf(overlayPackage)), overlayPackage));
        }
        for (String themePackage : mThemePkgs) {
            sStyleItems.add(new ThemeTileItem(mThemePkgs.indexOf(themePackage) + 1, -1,
                    mThemeLabels.get(mThemePkgs.indexOf(themePackage)), themePackage));
        }
    }

    private class ThemeTileItem {
        final int settingsVal;
        final int colorRes;
        final String labelRes;
        String uri;

        public ThemeTileItem(int settingsVal, int colorRes, String labelRes) {
            this.settingsVal = settingsVal;
            this.colorRes = colorRes;
            this.labelRes = labelRes;
        }

        public ThemeTileItem(int settingsVal, int colorRes, String labelRes, String uri) {
            this(settingsVal, colorRes, labelRes);
            this.uri = uri;
        }

        public String getLabel(Context context) {
            return labelRes;
        }

        public void commit() {
            mThemeUtils.setOverlayEnabled(ThemeUtils.ACCENT_KEY, uri);
        }

        public void styleCommit(Context context) {
            mThemeUtils.setThemeEnabled(uri);
        }

        public QSTile.Icon getIcon(Context context) {
            QSTile.Icon icon = new QSTile.Icon() {
                @Override
                public Drawable getDrawable(Context context) {
                    ShapeDrawable oval = new ShapeDrawable(new OvalShape());
                    oval.setIntrinsicHeight(context.getResources()
                            .getDimensionPixelSize(R.dimen.qs_detail_image_height));
                    oval.setIntrinsicWidth(context.getResources()
                            .getDimensionPixelSize(R.dimen.qs_detail_image_width));
                    oval.getPaint().setColor(colorRes);
                    return oval;
                }
            };
            return icon;
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new ThemeDetailAdapter();
    }

    private class ThemeDetailAdapter
            implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItemsList;
        private QSDetailItemsList.QSDetailListAdapter mAdapter;
        private List<Item> mThemeItems = new ArrayList<>();

        @Override
        public CharSequence getTitle() {
            return mContext.getString(mMode == Mode.ACCENT ?
                    R.string.quick_settings_theme_tile_accent_detail_title :
                    R.string.quick_settings_theme_tile_style_detail_title);
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItemsList = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            mAdapter = new QSDetailItemsList.QSDetailListAdapter(context, mThemeItems);
            ListView listView = mItemsList.getListView();
            listView.setDivider(null);
            listView.setOnItemClickListener(this);
            listView.setAdapter(mAdapter);
            updateItems();
            return mItemsList;
        }

        private void updateItems() {
            if (mAdapter == null)
                return;
            mThemeItems.clear();
            if (mMode == Mode.ACCENT) {
                mThemeItems.addAll(getAccentItems());
            } else {
                mThemeItems.addAll(getStyleItems());
            }
            mAdapter.notifyDataSetChanged();
        }

        private List<Item> getAccentItems() {
            List<Item> items = new ArrayList<Item>();
            for (int i = 0; i < sThemeItems.size(); i++) {
                ThemeTileItem themeTileItem = sThemeItems.get(i);
                Item item = new Item();
                item.tag = themeTileItem;
                item.doDisableTint = true;
                item.doDisableFocus = true;
                item.icon = themeTileItem.getIcon(mContext);
                item.line1 = themeTileItem.getLabel(mContext);
                items.add(item);
            }
            return items;
        }

        private List<Item> getStyleItems() {
            List<Item> items = new ArrayList<Item>();
            for (ThemeTileItem styleItem : sStyleItems) {
                Item item = new Item();
                item.tag = styleItem;
                item.doDisableFocus = true;
                item.iconResId = R.drawable.ic_qs_style_list;
                item.line1 = styleItem.getLabel(mContext);
                items.add(item);
            }
            return items;
        }

        @Override
        public Intent getSettingsIntent() {
            return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.EXTENSIONS;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Item item = (Item) parent.getItemAtPosition(position);
            if (item == null || item.tag == null)
                return;
            ThemeTileItem themeItem = (ThemeTileItem) item.tag;
            showDetail(false);
            if (mMode == Mode.ACCENT) {
                themeItem.commit();
            } else {
                themeItem.styleCommit(mContext);
            }
        }
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleLongClick() {
        mMode = mMode == Mode.ACCENT ? Mode.STYLE : Mode.ACCENT;
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(mMode == Mode.ACCENT
                ? R.string.quick_settings_theme_tile_title : R.string.system_theme_style_title);
        state.icon = ResourceIcon.get(mMode == Mode.ACCENT
                ? R.drawable.ic_qs_accent : R.drawable.ic_qs_style);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleSetListening(boolean listening) {
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_theme_tile_title);
    }
}
