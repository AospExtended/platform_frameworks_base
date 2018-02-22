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
import com.android.internal.util.aospextended.ThemesUtils;
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

    static final List<ThemeTileItem> sThemeItems = new ArrayList<ThemeTileItem>();
    static {
        sThemeItems.add(new ThemeTileItem(0, R.color.quick_settings_theme_tile_default,
                R.string.quick_settings_theme_tile_color_default));
        sThemeItems.add(new ThemeTileItem(1, R.color.quick_settings_theme_tile_space,
                R.string.quick_settings_theme_tile_color_space, "com.android.theme.color.space"));
        sThemeItems.add(new ThemeTileItem(2, R.color.quick_settings_theme_tile_purple,
                R.string.quick_settings_theme_tile_color_purple, "com.android.theme.color.purple"));
        sThemeItems.add(new ThemeTileItem(3, R.color.quick_settings_theme_tile_orchid,
                R.string.quick_settings_theme_tile_color_orchid, "com.android.theme.color.orchid"));
        sThemeItems.add(new ThemeTileItem(4, R.color.quick_settings_theme_tile_ocean,
                R.string.quick_settings_theme_tile_color_ocean, "com.android.theme.color.ocean"));
        sThemeItems.add(new ThemeTileItem(5, R.color.quick_settings_theme_tile_green,
                R.string.quick_settings_theme_tile_color_green, "com.android.theme.color.green"));
        sThemeItems.add(new ThemeTileItem(6, R.color.quick_settings_theme_tile_cinnamon,
                R.string.quick_settings_theme_tile_color_cinnamon, "com.android.theme.color.cinnamon"));
        sThemeItems.add(new ThemeTileItem(7, R.color.quick_settings_theme_tile_amber,
                R.string.quick_settings_theme_tile_color_amber, "com.android.theme.color.amber"));
        sThemeItems.add(new ThemeTileItem(8, R.color.quick_settings_theme_tile_blue,
                R.string.quick_settings_theme_tile_color_blue, "com.android.theme.color.blue"));
        sThemeItems.add(new ThemeTileItem(9, R.color.quick_settings_theme_tile_bluegrey,
                R.string.quick_settings_theme_tile_color_bluegrey, "com.android.theme.color.bluegrey"));
        sThemeItems.add(new ThemeTileItem(10, R.color.quick_settings_theme_tile_brown,
                R.string.quick_settings_theme_tile_color_brown, "com.android.theme.color.brown"));
        sThemeItems.add(new ThemeTileItem(11, R.color.quick_settings_theme_tile_cyan,
                R.string.quick_settings_theme_tile_color_cyan, "com.android.theme.color.cyan"));
        sThemeItems.add(new ThemeTileItem(12, R.color.quick_settings_theme_tile_deeporange,
                R.string.quick_settings_theme_tile_color_deeporange, "com.android.theme.color.deeporange"));
        sThemeItems.add(new ThemeTileItem(13, R.color.quick_settings_theme_tile_deeppurple,
                R.string.quick_settings_theme_tile_color_deeppurple, "com.android.theme.color.deeppurple"));
        sThemeItems.add(new ThemeTileItem(14, R.color.quick_settings_theme_tile_grey,
                R.string.quick_settings_theme_tile_color_grey, "com.android.theme.color.grey"));
        sThemeItems.add(new ThemeTileItem(15, R.color.quick_settings_theme_tile_indigo,
                R.string.quick_settings_theme_tile_color_indigo, "com.android.theme.color.indigo"));
        sThemeItems.add(new ThemeTileItem(16, R.color.quick_settings_theme_tile_lightblue,
                R.string.quick_settings_theme_tile_color_lightblue, "com.android.theme.color.lightblue"));
        sThemeItems.add(new ThemeTileItem(17, R.color.quick_settings_theme_tile_lightgreen,
                R.string.quick_settings_theme_tile_color_lightgreen, "com.android.theme.color.lightgreen"));
        sThemeItems.add(new ThemeTileItem(18, R.color.quick_settings_theme_tile_lime,
                R.string.quick_settings_theme_tile_color_lime, "com.android.theme.color.lime"));
        sThemeItems.add(new ThemeTileItem(19, R.color.quick_settings_theme_tile_orange,
                R.string.quick_settings_theme_tile_color_orange, "com.android.theme.color.orange"));
        sThemeItems.add(new ThemeTileItem(20, R.color.quick_settings_theme_tile_pink,
                R.string.quick_settings_theme_tile_color_pink, "com.android.theme.color.pink"));
        sThemeItems.add(new ThemeTileItem(21, R.color.quick_settings_theme_tile_red,
                R.string.quick_settings_theme_tile_color_red, "com.android.theme.color.red"));
        sThemeItems.add(new ThemeTileItem(22, R.color.quick_settings_theme_tile_teal,
                R.string.quick_settings_theme_tile_color_teal, "com.android.theme.color.teal"));
        sThemeItems.add(new ThemeTileItem(23, R.color.quick_settings_theme_tile_yellow,
                R.string.quick_settings_theme_tile_color_yellow, "com.android.theme.color.yellow"));
        sThemeItems.add(new ThemeTileItem(24, R.color.quick_settings_theme_tile_extendedgreen,
                R.string.quick_settings_theme_tile_color_extendedgreen, "com.android.theme.color.extendedgreen"));
        sThemeItems.add(new ThemeTileItem(25, R.color.quick_settings_theme_tile_elegantgreen,
                R.string.quick_settings_theme_tile_color_elegantgreen, "com.android.theme.color.elegantgreen"));
    }

    static final List<ThemeTileItem> sStyleItems = new ArrayList<ThemeTileItem>();
    static {
        sStyleItems.add(new ThemeTileItem(UiModeManager.MODE_NIGHT_YES, -1,
                R.string.system_theme_style_dark));
        sStyleItems.add(new ThemeTileItem(UiModeManager.MODE_NIGHT_NO, -1,
                R.string.system_theme_style_light));
    }

    private enum Mode {
        ACCENT, STYLE
    }

    private static IOverlayManager mOverlayManager;
    private Mode mMode;
    private static UiModeManager mUiModeManager;

    @Inject
    public ThemeTile(QSHost host) {
        super(host);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mMode = Mode.ACCENT;
    }

    private static class ThemeTileItem {
        final int settingsVal;
        final int colorRes;
        final int labelRes;
        String uri;

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

        public void commit() {
            try {
                for (int i = 0; i < ThemesUtils.ACCENTS.length; i++) {
                    String accent = ThemesUtils.ACCENTS[i];
                    try {
                        mOverlayManager.setEnabled(accent, false, USER_SYSTEM);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                mOverlayManager.setEnabled(uri, true, USER_SYSTEM);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        public void styleCommit(Context context) {
            mUiModeManager.setNightMode(settingsVal);
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
