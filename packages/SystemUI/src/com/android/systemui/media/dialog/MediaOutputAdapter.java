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
 * limitations under the License.
 */

package com.android.systemui.media.dialog;

import static android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;

import java.util.List;

/**
 * Adapter for media output dialog.
 */
public class MediaOutputAdapter extends MediaOutputBaseAdapter {

    private static final String TAG = "MediaOutputAdapter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private ViewGroup mConnectedItem;
    private boolean mIncludeDynamicGroup;

    public MediaOutputAdapter(MediaOutputController controller) {
        super(controller);
    }

    @Override
    public MediaDeviceBaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        super.onCreateViewHolder(viewGroup, viewType);
        return new MediaDeviceViewHolder(mHolderView);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaDeviceBaseViewHolder viewHolder, int position) {
        final int size = mController.getMediaDevices().size();
        if (position == size && mController.isZeroMode()) {
            viewHolder.onBind(CUSTOMIZED_ITEM_PAIR_NEW, false /* topMargin */,
                    true /* bottomMargin */);
        } else if (mIncludeDynamicGroup) {
            if (position == 0) {
                viewHolder.onBind(CUSTOMIZED_ITEM_DYNAMIC_GROUP, true /* topMargin */,
                        false /* bottomMargin */);
            } else {
                // When group item is added at the first(position == 0), devices will be added from
                // the second item(position == 1). It means that the index of device list starts
                // from "position - 1".
                viewHolder.onBind(((List<MediaDevice>) (mController.getMediaDevices()))
                                .get(position - 1),
                        false /* topMargin */, position == size /* bottomMargin */, position);
            }
        } else if (position < size) {
            viewHolder.onBind(((List<MediaDevice>) (mController.getMediaDevices())).get(position),
                    position == 0 /* topMargin */, position == (size - 1) /* bottomMargin */,
                    position);
        } else if (DEBUG) {
            Log.d(TAG, "Incorrect position: " + position);
        }
    }

    @Override
    public int getItemCount() {
        mIncludeDynamicGroup = mController.getSelectedMediaDevice().size() > 1;
        if (mController.isZeroMode() || mIncludeDynamicGroup) {
            // Add extra one for "pair new" or dynamic group
            return mController.getMediaDevices().size() + 1;
        }
        return mController.getMediaDevices().size();
    }

    @Override
    CharSequence getItemTitle(MediaDevice device) {
        if (device.getDeviceType() == MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE
                && !device.isConnected()) {
            final CharSequence deviceName = device.getName();
            // Append status to title only for the disconnected Bluetooth device.
            final SpannableString spannableTitle = new SpannableString(
                    mContext.getString(R.string.media_output_dialog_disconnected, deviceName));
            spannableTitle.setSpan(new ForegroundColorSpan(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorSecondary)),
                    deviceName.length(),
                    spannableTitle.length(), SPAN_EXCLUSIVE_EXCLUSIVE);
            return spannableTitle;
        }
        return super.getItemTitle(device);
    }

    class MediaDeviceViewHolder extends MediaDeviceBaseViewHolder {

        MediaDeviceViewHolder(View view) {
            super(view);
        }

        @Override
        void onBind(MediaDevice device, boolean topMargin, boolean bottomMargin, int position) {
            super.onBind(device, topMargin, bottomMargin, position);
            final boolean currentlyConnected = !mIncludeDynamicGroup
                    && isCurrentlyConnected(device);
            if (currentlyConnected) {
                mConnectedItem = mContainerLayout;
            }
            mCheckBox.setVisibility(View.GONE);
            mStatusIcon.setVisibility(View.GONE);
            mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext, R.color.media_dialog_inactive_item_main_content));
            if (mCurrentActivePosition == position) {
                mCurrentActivePosition = -1;
            }
            if (device.getDeviceType() != 4 || device.isConnected()) {
                mTitleText.setAlpha(1.0f);
                mTitleIcon.setAlpha(1.0f);
            } else {
                mTitleText.setAlpha(0.5f);
                mTitleIcon.setAlpha(0.5f);
            }
            if (mController.isTransferring()) {
                if (device.getState() != 1 || mController.hasAdjustVolumeUserRestriction()) {
                    setSingleLineLayout(getItemTitle(device), false);
                } else {
                    setSingleLineLayout(getItemTitle(device), true, false, true, false);
                }
            } else if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                mTitleText.setAlpha(1.0f);
                mTitleIcon.setAlpha(1.0f);
                mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                setSingleLineLayout(getItemTitle(device), false, false, false, true);
            } else if (mController.getSelectedMediaDevice().size() > 1 && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext, R.color.media_dialog_item_status));
                setSingleLineLayout(getItemTitle(device), true, true, false, false);
                mCheckBox.setVisibility(0);
                mCheckBox.setChecked(true);
                initSessionSeekbar();
            } else if (!mController.hasAdjustVolumeUserRestriction() && currentlyConnected) {
                mStatusIcon.setImageDrawable(mContext.getDrawable(R.drawable.media_output_status_check));
                mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext, R.color.media_dialog_item_status));
                setSingleLineLayout(getItemTitle(device), true, true, false, true);
                initSeekbar(device);
                mCurrentActivePosition = position;
            } else if (isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                mCheckBox.setVisibility(0);
                mCheckBox.setChecked(false);
                mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                setSingleLineLayout(getItemTitle(device), false, false, false, false);
            } else {
                mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                setSingleLineLayout(getItemTitle(device), false);
            }
        }

        private boolean isDeviceIncluded(List<MediaDevice> deviceList, MediaDevice targetDevice) {
            for (MediaDevice device : deviceList) {
                if (TextUtils.equals(device.getId(), targetDevice.getId())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            super.onBind(customizedItem, topMargin, bottomMargin);
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext, R.color.media_dialog_inactive_item_main_content));
                mCheckBox.setVisibility(View.GONE);
                setSingleLineLayout(mContext.getText(R.string.media_output_dialog_pairing_new),
                        false /* bFocused */);
                final Drawable d = mContext.getDrawable(R.drawable.ic_add);
                d.setColorFilter(new PorterDuffColorFilter(
                        Utils.getColorAccentDefaultColor(mContext), PorterDuff.Mode.SRC_IN));
                mTitleIcon.setImageDrawable(d);
                mContainerLayout.setOnClickListener(v -> onItemClick(CUSTOMIZED_ITEM_PAIR_NEW));
            }
        }

        private void onItemClick(View view, MediaDevice device) {
            if (mController.isTransferring()) {
                return;
            }

            mCurrentActivePosition = -1;
            playSwitchingAnim(mConnectedItem, view);
            mController.connectDevice(device);
            device.setState(MediaDeviceState.STATE_CONNECTING);
            if (!isAnimating()) {
                notifyDataSetChanged();
            }
        }

        private void onItemClick(int customizedItem) {
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mController.launchBluetoothPairing();
            }
        }

        private void onEndItemClick() {
            mController.launchMediaOutputGroupDialog();
        }
    }
}
