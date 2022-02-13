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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.animation.ValueAnimator;
import com.android.systemui.animation.Interpolators;
import androidx.cardview.widget.CardView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import android.widget.FrameLayout;

/**
 * Base adapter for media output dialog.
 */
public abstract class MediaOutputBaseAdapter extends
        RecyclerView.Adapter<MediaOutputBaseAdapter.MediaDeviceBaseViewHolder> {

    static final int CUSTOMIZED_ITEM_PAIR_NEW = 1;
    static final int CUSTOMIZED_ITEM_GROUP = 2;
    static final int CUSTOMIZED_ITEM_DYNAMIC_GROUP = 3;

    final MediaOutputController mController;

    private int mMargin;
    private boolean mIsAnimating;

    Context mContext;
    View mHolderView;
    boolean mIsDragging;
    int mCurrentActivePosition;

    public MediaOutputBaseAdapter(MediaOutputController controller) {
        mController = controller;
        mIsDragging = false;
        mCurrentActivePosition = -1;
    }

    @Override
    public MediaDeviceBaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        mContext = viewGroup.getContext();
        mMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_margin);
        mHolderView = LayoutInflater.from(mContext).inflate(R.layout.media_output_list_item,
                viewGroup, false);

        return null;
    }

    CharSequence getItemTitle(MediaDevice device) {
        return device.getName();
    }

    boolean isCurrentlyConnected(MediaDevice device) {
        return TextUtils.equals(device.getId(),
                mController.getCurrentConnectedMediaDevice().getId());
    }

    boolean isDragging() {
        return mIsDragging;
    }

    boolean isAnimating() {
        return mIsAnimating;
    }

    int getCurrentActivePosition() {
        return mCurrentActivePosition;
    }

    /**
     * ViewHolder for binding device view.
     */
    abstract class MediaDeviceBaseViewHolder extends RecyclerView.ViewHolder {

        private static final int ANIM_DURATION = 200;

        final LinearLayout mContainerLayout;
        final TextView mTitleText;
        final ImageView mTitleIcon;
        final ProgressBar mProgressBar;
        final SeekBar mSeekBar;
        final CheckBox mCheckBox;
        private String mDeviceId;
        public final ImageView mStatusIcon;
        public final CardView mItemLayout;

        MediaDeviceBaseViewHolder(View view) {
            super(view);
            mContainerLayout = view.requireViewById(R.id.device_container);
            mTitleText = view.requireViewById(R.id.title);
            mItemLayout = view.requireViewById(R.id.card_rdnt);
            mTitleIcon = view.requireViewById(R.id.title_icon);
            mProgressBar = view.requireViewById(R.id.volume_indeterminate_progress);
            mSeekBar = view.requireViewById(R.id.volume_seekbar);
            mStatusIcon = view.requireViewById(R.id.media_output_item_status);
            mCheckBox = view.requireViewById(R.id.check_box);
        }

        void onBind(MediaDevice device, boolean topMargin, boolean bottomMargin, int position) {
            mDeviceId = device.getId();
            ThreadUtils.postOnBackgroundThread(() -> {
                Icon icon = mController.getDeviceIconCompat(device).toIcon(mContext);
                ThreadUtils.postOnMainThread(() -> {
                    if (!TextUtils.equals(mDeviceId, device.getId())) {
                        return;
                    }
                    mTitleIcon.setImageIcon(icon);
                    setMargin(topMargin, bottomMargin);
                });
            });
        }

        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            setMargin(topMargin, bottomMargin);
        }

        private void setMargin(boolean topMargin, boolean bottomMargin) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mContainerLayout
                    .getLayoutParams();
            params.topMargin = 0;
            params.bottomMargin = 0;
            mContainerLayout.setLayoutParams(params);
        }

        public void setSingleLineLayout(CharSequence title, boolean bFocused) {
            setSingleLineLayout(title, bFocused, false, false, false);
        }

        public void setSingleLineLayout(CharSequence title, boolean bFocused, boolean showSeekBar, boolean showProgressBar, boolean showSubtitle) {
            Drawable drawable;
            if (showSeekBar || showProgressBar) {
                mItemLayout.setRadius(mContext.getResources().getDimensionPixelSize(R.dimen.notification_corner_radius));
            } else {
                mItemLayout.setRadius(mContext.getResources().getDimensionPixelSize(R.dimen.navigation_edge_action_drag_threshold));
            }
            int i = 8;
            mProgressBar.setVisibility(showProgressBar ? View.VISIBLE : View.GONE);
            mSeekBar.setAlpha(1.0f);
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
            ImageView imageView = mStatusIcon;
            if (showSubtitle) {
                i = 0;
            }
            imageView.setVisibility(i);
            mTitleText.setText(title);
            mTitleText.setVisibility(0);
        }

        void initSeekbar(MediaDevice device) {
            if (!mController.isVolumeControlEnabled(device)) {
                disableSeekBar();
            }
            mSeekBar.setMax(device.getMaxVolume());
            mSeekBar.setMin(0);
            final int currentVolume = device.getCurrentVolume();
            if (mSeekBar.getProgress() != currentVolume) {
                mSeekBar.setProgress(currentVolume);
            }
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (device == null || !fromUser) {
                        return;
                    }
                    mController.adjustVolume(device, progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mIsDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mIsDragging = false;
                }
            });
        }

        void initSessionSeekbar() {
            disableSeekBar();
            mSeekBar.setMax(mController.getSessionVolumeMax());
            mSeekBar.setMin(0);
            final int currentVolume = mController.getSessionVolume();
            if (mSeekBar.getProgress() != currentVolume) {
                mSeekBar.setProgress(currentVolume);
            }
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    mController.adjustSessionVolume(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mIsDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mIsDragging = false;
                }
            });
        }

        void playSwitchingAnim(@NonNull View from, @NonNull View to) {
            final SeekBar fromSeekBar = from.requireViewById(R.id.volume_seekbar);
            final SeekBar toSeekBar = to.requireViewById(R.id.volume_seekbar);
            final TextView toTitleText = to.requireViewById(R.id.title);
            final CardView fromItemLayout = from.requireViewById(R.id.card_rdnt);
            final CardView toItemLayout = to.requireViewById(R.id.card_rdnt);
            if (fromSeekBar.getVisibility() != View.VISIBLE || toTitleText.getVisibility()
                    != View.VISIBLE) {
                return;
            }
            mIsAnimating = true;

            Float mLarge = mContext.getResources().getDimension(R.dimen.notification_corner_radius);
            Float mSmall = mContext.getResources().getDimension(R.dimen.navigation_edge_action_drag_threshold);

            AnimatorSet mMediaAnims = new AnimatorSet();
            mMediaAnims.playTogether(valueCardAnim(mLarge, mSmall, fromItemLayout),
                    valueAlphaAnim(1.0f, 0.0f, fromSeekBar, false),
                    valueCardAnim(mSmall, mLarge, toItemLayout),
                    valueAlphaAnim(0.0f, 1.0f, toSeekBar, true));
            mMediaAnims.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mIsAnimating = false;
                    notifyDataSetChanged();
                }
            });
            mMediaAnims.start();
        }

        ValueAnimator valueCardAnim(Float initial, Float end, CardView mView){
            ValueAnimator mAnimator = ValueAnimator.ofFloat(initial, end);
            mAnimator.setDuration(250);
            mAnimator.setInterpolator(Interpolators.LINEAR);
            mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator anim) {
                        mView.setRadius((float) anim.getAnimatedValue());
                    }
            });
            return mAnimator;
        }

        ValueAnimator valueAlphaAnim(Float initial, Float end, View mView, boolean changeVis){
            if (changeVis){
                mView.setAlpha(initial);
                mView.setVisibility(View.VISIBLE);
            }
            ValueAnimator mAnimator = ValueAnimator.ofFloat(initial, end);
            mAnimator.setDuration(250);
            mAnimator.setInterpolator(Interpolators.LINEAR);
            mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator anim) {
                        mView.setAlpha((float) anim.getAnimatedValue());
                    }
            });
            return mAnimator;
        }

        Drawable getSpeakerDrawable() {
            final Drawable drawable = mContext.getDrawable(R.drawable.ic_speaker_group_black_24dp)
                    .mutate();
            final ColorStateList list = mContext.getResources().getColorStateList(
                    R.color.advanced_icon_color, mContext.getTheme());
            drawable.setColorFilter(new PorterDuffColorFilter(list.getDefaultColor(),
                    PorterDuff.Mode.SRC_IN));
            return BluetoothUtils.buildAdvancedDrawable(mContext, drawable);
        }

        private void disableSeekBar() {
            mSeekBar.setEnabled(false);
            mSeekBar.setOnTouchListener((v, event) -> true);
        }
    }
}
