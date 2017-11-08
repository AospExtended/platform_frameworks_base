/*
 * Copyright (C) 2016-2017 The halogenOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;

import java.util.ArrayList;

public class VisualizerViewWrapper {

    private static final boolean DEBUG = false;

    private static ArrayList<VisualizerViewWrapper> mExtraVisualizers =
                new ArrayList<>();

    private static boolean haveFirst = false;
    private static boolean ignoreExtraEvents = false;

    public VisualizerView visualizerView;

    private Context context;
    private ViewGroup parent;
    private StateHolder state = new StateHolder();

    private boolean mHandleExtra;

    public VisualizerViewWrapper(Context context, ViewGroup parent) {
        this.context = context;
        this.parent = parent;
        if (!haveFirst) {
            haveFirst = true;
            mHandleExtra = true;
        }
    }

    private static void log(String... msg) {
        if (DEBUG) for (String s : msg) Log.d("VisualizerView", s);
    }

    public synchronized void vanish() {
        log("vanish");
        if (!isNull()) {
            visualizerView.setVisible(false);
            if (isAttachedToWindow()) {
                ((ViewGroup)visualizerView.getParent())
                        .removeView(visualizerView);
            }
            visualizerView.destroy();
            visualizerView = null;
            state.mDisplaying = false;
            state.mHaveInstance = false;
        }
    }

    private boolean isAttachedToWindow() {
        return visualizerView.getWindowToken() != null;
    }

    private boolean isNull() {
        if (DEBUG) {
            log("visualizerView is " +
                    (visualizerView != null ? "not " : "") + "null");
        }
        return visualizerView == null;
    }

    public synchronized void setPlaying(boolean playing) {
        log("setPlaying=" + playing);
        // Reset color when stopping, so that the next time when a song is played
        // the color won't fade from the one of the previous song to the new song,
        // it will fade in only. This only happens when the user stops the music
        // and e. g. unlocks his device and doesn't use it for some time. Later,
        // when he wants to listen to another song, it will just fade out from
        // transparent into the new color instead of using the old color.
        if (state.mPlayingIndependent && !playing) {
            state.mColor = Color.TRANSPARENT;
        }
        state.mPlayingIndependent = playing;
        state.mPlaying = playing;
        if (mHandleExtra) {
            for (VisualizerViewWrapper vis : mExtraVisualizers) {
                vis.setPlaying(playing);
            }
        }
        checkState();
    }

    private synchronized void setScreenOn(boolean screenOn) {
        if (!mHandleExtra && ignoreExtraEvents) return;
        log("setScreenOn=" + screenOn);
        state.mScreenOn = screenOn;
        state.mScreenOnInternal = screenOn;
    }

    public final boolean isScreenOn() {
        return state.mScreenOnInternal;
    }

    public synchronized void setKeyguardShowing(boolean showing) {
        if (!mHandleExtra && ignoreExtraEvents && showing) return;
        log("setKeyguardShowing=" + showing);
        state.mKeyguardVisible = showing;
        checkState();
    }

    private synchronized void setVisible(boolean visible) {
        if (!mHandleExtra && ignoreExtraEvents) return;
        log("setVisible=" + visible);
        state.mVisible = visible;
    }

    public synchronized void setBitmap(Bitmap bitmap) {
        log("setBitmap=[not null: " + (bitmap != null) + "]");
        if (!isNull()) {
            visualizerView.setBitmap(bitmap);
        } else {
            state.mColor = Color.TRANSPARENT;
            state.mCurrentBitmap = bitmap;
        }
        if (mHandleExtra) {
            for (VisualizerViewWrapper vis : mExtraVisualizers) {
                vis.setBitmap(bitmap);
            }
        }
    }

    private synchronized void ready() {
        log("Getting ready...");
        if (isNull() && !state.mHaveInstance) {
            visualizerView = new VisualizerView(context);
            visualizerView.ready(state);
            state.mHaveInstance = true;
        }
        log("Ready.");
    }

    private synchronized void prepare() {
        log("prepare");
        state.mScreenOn = state.mScreenOnInternal;
        state.mPlaying = state.mPlayingIndependent;
        ready();
    }

    public synchronized void checkState() {
        if (!mHandleExtra && ignoreExtraEvents) return;
        if (!state.mHaveInstance && state.mScreenOnInternal
                && state.mKeyguardVisible && state.mPlaying) {
            if (DEBUG) {
                log("Current color: " + state.mColor);
                log("Is bitmap null: " + (state.mCurrentBitmap == null));
            }
            if (mHandleExtra) {
                ignoreExtraEvents = true;
                for (VisualizerViewWrapper vis : mExtraVisualizers) {
                    vis.setKeyguardShowing(false);
                    vis.startOrStop(false);
                }
            }
            prepare();
            if (!isNull() && !isAttachedToWindow()) {
                log("Adding to view");
                parent.addView(visualizerView);
                for (int i = 0; i < parent.getChildCount(); i++) {
                    if (parent.getChildAt(i) != visualizerView) {
                        parent.getChildAt(i).bringToFront();
                    }
                }
                visualizerView.setTranslationZ(-2);
            }
            state.mVisible = true;
            visualizerView.refreshColor();
            visualizerView.checkStateChanged();
        } else if (state.mHaveInstance &&
                    (!state.mScreenOnInternal || !state.mKeyguardVisible)) {
            vanish();
            if (mHandleExtra) {
                ignoreExtraEvents = false;
            }
        } else if (state.mHaveInstance && state.mScreenOnInternal &&
                    state.mKeyguardVisible) {
            visualizerView.checkStateChanged();
        }
        if (mHandleExtra &&
                !state.mScreenOnInternal && !state.mKeyguardVisible) {
            for (VisualizerViewWrapper vis : mExtraVisualizers) {
                vis.checkState();
            }
        }
    }

    public synchronized void onScreenOn() {
        if (!mHandleExtra && ignoreExtraEvents) return;
        if (state.mIsAlwaysOn) return;
        startOrStop(true);
    }

    public synchronized void onScreenOff() {
        if (!mHandleExtra && ignoreExtraEvents) return;
        startOrStop(false);
    }

    public synchronized void startOrStop(boolean start) {
        if (!mHandleExtra && ignoreExtraEvents && start) return;
        // setScreenOn because don't wanna change the name now
        setScreenOn(start);
        checkState();
    }

    public synchronized void onAlwaysOn(boolean on) {
        if (!mHandleExtra && ignoreExtraEvents) return;
        if (on) {
            state.mIsAlwaysOn = true;
            onScreenOff();
        } else {
            state.mIsAlwaysOn = false;
            onScreenOn();
        }
    }

    public static synchronized void addExtraVisualizer(VisualizerViewWrapper vis) {
        mExtraVisualizers.add(vis);
    }

    static final class StateHolder {
        public boolean mVisible;
        public boolean mPlayingIndependent;
        public boolean mPlaying;
        public boolean mPowerSaveMode;
        public boolean mDisplaying;
        public boolean mOccluded;
        public boolean mScreenOn;
        public boolean mScreenOnInternal;
        public boolean mKeyguardVisible;
        public boolean mHaveInstance;
        public boolean mIsAlwaysOn;

        public int mColor = Color.TRANSPARENT;

        public Bitmap mCurrentBitmap;
    }

}