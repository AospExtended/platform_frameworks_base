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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

public class ThemeTile extends QSTileImpl<BooleanState> {

    private final String substratum = "projekt.substratum";

    static final List<ThemeTileItem> sThemeItems = new ArrayList<ThemeTileItem>();
    static {
        sThemeItems.add(new ThemeTileItem(0, R.color.quick_settings_theme_tile_default,
                R.string.quick_settings_theme_tile_color_default));
        sThemeItems.add(new ThemeTileItem(1, R.color.quick_settings_theme_tile_red,
                R.string.quick_settings_theme_tile_color_red));
        sThemeItems.add(new ThemeTileItem(2, R.color.quick_settings_theme_tile_pink,
                R.string.quick_settings_theme_tile_color_pink));
        sThemeItems.add(new ThemeTileItem(3, R.color.quick_settings_theme_tile_purple,
                R.string.quick_settings_theme_tile_color_purple));
        sThemeItems.add(new ThemeTileItem(4, R.color.quick_settings_theme_tile_deep_purple,
                R.string.quick_settings_theme_tile_color_deep_purple));
        sThemeItems.add(new ThemeTileItem(5, R.color.quick_settings_theme_tile_indigo,
                R.string.quick_settings_theme_tile_color_indigo));
        sThemeItems.add(new ThemeTileItem(6, R.color.quick_settings_theme_tile_blue,
                R.string.quick_settings_theme_tile_color_blue));
        sThemeItems.add(new ThemeTileItem(7, R.color.quick_settings_theme_tile_light_blue,
                R.string.quick_settings_theme_tile_color_light_blue));
        sThemeItems.add(new ThemeTileItem(8, R.color.quick_settings_theme_tile_cyan,
                R.string.quick_settings_theme_tile_color_cyan));
        sThemeItems.add(new ThemeTileItem(9, R.color.quick_settings_theme_tile_teal,
                R.string.quick_settings_theme_tile_color_teal));
        sThemeItems.add(new ThemeTileItem(10, R.color.quick_settings_theme_tile_green,
                R.string.quick_settings_theme_tile_color_green));
        sThemeItems.add(new ThemeTileItem(11, R.color.quick_settings_theme_tile_light_green,
                R.string.quick_settings_theme_tile_color_light_green));
        sThemeItems.add(new ThemeTileItem(12, R.color.quick_settings_theme_tile_lime,
                R.string.quick_settings_theme_tile_color_lime));
        sThemeItems.add(new ThemeTileItem(13, R.color.quick_settings_theme_tile_yellow,
                R.string.quick_settings_theme_tile_color_yellow));
        sThemeItems.add(new ThemeTileItem(14, R.color.quick_settings_theme_tile_amber,
                R.string.quick_settings_theme_tile_color_amber));
        sThemeItems.add(new ThemeTileItem(15, R.color.quick_settings_theme_tile_orange,
                R.string.quick_settings_theme_tile_color_orange));
        sThemeItems.add(new ThemeTileItem(16, R.color.quick_settings_theme_tile_deep_orange,
                R.string.quick_settings_theme_tile_color_deep_orange));
        sThemeItems.add(new ThemeTileItem(17, R.color.quick_settings_theme_tile_brown,
                R.string.quick_settings_theme_tile_color_brown));
        sThemeItems.add(new ThemeTileItem(18, R.color.quick_settings_theme_tile_grey,
                R.string.quick_settings_theme_tile_color_grey));
        sThemeItems.add(new ThemeTileItem(19, R.color.quick_settings_theme_tile_blue_grey,
                R.string.quick_settings_theme_tile_color_blue_grey));
    }

    static final List<ThemeTileItem> sStyleItems = new ArrayList<ThemeTileItem>();
    static {
        sStyleItems.add(new ThemeTileItem(0, -1,
                R.string.system_theme_style_auto, Settings.System.SYSTEM_THEME));
        sStyleItems.add(new ThemeTileItem(1, -1,
                R.string.system_theme_style_light, Settings.System.SYSTEM_THEME));
        sStyleItems.add(new ThemeTileItem(2, -1,
                R.string.system_theme_style_dark, Settings.System.SYSTEM_THEME));
    }

    private enum Mode {
        ACCENT, STYLE
    }

    private IOverlayManager mOverlayManager;
    private Mode mMode;

    public ThemeTile(QSHost host) {
        super(host);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mMode = Mode.ACCENT;
    }

    private static class ThemeTileItem {
        final int settingsVal;
        final int colorRes;
        final int labelRes;
        String uri = Settings.System.ACCENT_PICKER;

        public ThemeTileItem(int settingsVal, int colorRes, int labelRes) {
            this.settingsVal = settingsVal;
            this.colorRes = colorRes;
            this.labelRes = labelRes;
        }

        public ThemeTileItem(int settingsVal, int colorRes, int labelRes, String uri) {
            this(settingsVal, colorRes, labelRes);
            this.uri = uri;
        }

        public String getLabel(Context context) {
            return context.getString(labelRes);
        }

        public void commit(Context context) {
            Settings.System.putIntForUser(context.getContentResolver(),
                    uri, settingsVal, UserHandle.USER_CURRENT);
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
                    oval.getPaint().setColor(context.getColor(colorRes));
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
            if (mMode == Mode.ACCENT) {
                return mContext.getString(R.string.quick_settings_theme_tile_accent_detail_title);
            } else {
                return mContext.getString(R.string.quick_settings_theme_tile_style_detail_title);
            }
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
            Item lastItem = new Item();
            ThemeTileItem lastThemeTileItem = getThemeItemForStyleMode();
            lastItem.tag = lastThemeTileItem;
            lastItem.doDisableTint = true;
            lastItem.doDisableFocus = true;
            lastItem.icon = lastThemeTileItem.getIcon(mContext);
            lastItem.line1 = lastThemeTileItem.getLabel(mContext);
            items.add(lastItem);
            return items;
        }

        private List<Item> getStyleItems() {
            List<Item> items = new ArrayList<Item>();
            for (ThemeTileItem styleItem : sStyleItems) {
                Item item = new Item();
                item.tag = styleItem;
                item.doDisableFocus = true;
                item.iconResId = R.drawable.ic_qs_accent;
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
            return MetricsEvent.CUSTOM_QUICK_TILES;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Item item = (Item) parent.getItemAtPosition(position);
            if (item == null || item.tag == null)
                return;
            ThemeTileItem themeItem = (ThemeTileItem) item.tag;
            showDetail(false);
            themeItem.commit(mContext);
        }
    }

    private ThemeTileItem getThemeItemForStyleMode() {
        boolean isDark = isUsingDarkTheme();
        if (isDark) {
            return new ThemeTileItem(20, R.color.quick_settings_theme_tile_white,
                    R.string.quick_settings_theme_tile_color_white);
        } else {
            return new ThemeTileItem(20, R.color.quick_settings_theme_tile_black,
                    R.string.quick_settings_theme_tile_color_black);
        }
    }

    private boolean isUsingDarkTheme() {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = mOverlayManager.getOverlayInfo("com.android.system.theme.dark",
                    UserHandle.USER_CURRENT);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return themeInfo != null && themeInfo.isEnabled();
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
        state.icon = ResourceIcon.get(R.drawable.ic_qs_accent);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }

    @Override
    public boolean isAvailable() {
        return !isPackageInstalled();
    }

    private boolean isPackageInstalled() {
        try {
            PackageInfo info = mContext.getPackageManager()
                    .getPackageInfo(substratum, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    @Override
    public Intent getLongClickIntent() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected void handleSetListening(boolean listening) {
        // TODO Auto-generated method stub

    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_theme_tile_title);
    }

}
