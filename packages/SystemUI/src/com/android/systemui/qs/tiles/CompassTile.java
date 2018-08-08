/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017 AICP
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

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.ImageView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import android.service.quicksettings.Tile;

public class CompassTile extends QSTileImpl<BooleanState> implements SensorEventListener {
    private final static float ALPHA = 0.97f;

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_compass_on);

    private boolean mActive = false;

    private SensorManager mSensorManager;
    private Sensor mAccelerationSensor;
    private Sensor mGeomagneticFieldSensor;

    private float[] mAcceleration;
    private float[] mGeomagnetic;

    private ImageView mImage;
    private boolean mListeningSensors;

    public CompassTile(QSHost host) {
        super(host);
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mAccelerationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGeomagneticFieldSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        setListeningSensors(false);
        mSensorManager = null;
        mImage = null;
    }

    @Override
    public QSIconView createTileView(Context context) {
        QSIconView iconView = super.createTileView(context);
        mImage = (ImageView) iconView.findViewById(android.R.id.icon);
        return iconView;
    }

    @Override
    protected void handleClick() {
        mActive = !mActive;
        refreshState();
        setListeningSensors(mActive);
    }

    @Override
    public void handleLongClick() {
        handleClick();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    private void setListeningSensors(boolean listening) {
        if (listening == mListeningSensors) return;
        mListeningSensors = listening;
        if (mListeningSensors) {
            mSensorManager.registerListener(
                    this, mAccelerationSensor, SensorManager.SENSOR_DELAY_GAME);
            mSensorManager.registerListener(
                    this, mGeomagneticFieldSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_compass_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        Float degrees = arg == null ? 0 :(float) arg;

        if (state.slash == null) {
            state.slash = new SlashState();
        }

        state.value = mActive;
        state.icon = mIcon;
        if (state.value) {
            if (arg != null) {
                state.label = formatValueWithCardinalDirection(degrees);

                float target = 360 - degrees;
                float relative = target - mImage.getRotation();
                if (relative > 180) relative -= 360;

                mImage.setRotation(mImage.getRotation() + relative / 2);

            } else {
                state.label = mContext.getString(R.string.quick_settings_compass_init);
                mImage.setRotation(0);
            }
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_compass_on);
            state.value = true;
        } else {
            /*state.icon = ResourceIcon.get(R.drawable.ic_qs_compass_off);*/
            state.label = mContext.getString(R.string.quick_settings_compass_label);
            state.contentDescription = mContext.getString(
                    R.string.quick_settings_compass_label);
	    state.value = false;
            if (mImage != null) {
                mImage.setRotation(0);
            }
            state.state = Tile.STATE_INACTIVE;
        }
        state.slash.isSlashed = !state.value;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_compass_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_compass_changed_off);
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (!listening) {
            setListeningSensors(false);
            mActive = false;
        }
    }

    private String formatValueWithCardinalDirection(float degree) {
        int cardinalDirectionIndex = (int) (Math.floor(((degree - 22.5) % 360) / 45) + 1) % 8;
        String[] cardinalDirections = mContext.getResources().getStringArray(
                R.array.cardinal_directions);

        return mContext.getString(R.string.quick_settings_compass_value, degree,
                cardinalDirections[cardinalDirectionIndex]);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (mAcceleration == null) {
                mAcceleration = event.values.clone();
            }

            values = mAcceleration;
        } else {
            // Magnetic field sensor
            if (mGeomagnetic == null) {
                mGeomagnetic = event.values.clone();
            }

            values = mGeomagnetic;
        }

        for (int i = 0; i < 3; i++) {
            values[i] = ALPHA * values[i] + (1 - ALPHA) * event.values[i];
        }

        if (!mActive || !mListeningSensors || mAcceleration == null || mGeomagnetic == null) {
            // Nothing to do at this moment
            return;
        }

        float R[] = new float[9];
        float I[] = new float[9];
        if (!SensorManager.getRotationMatrix(R, I, mAcceleration, mGeomagnetic)) {
            // Rotation matrix couldn't be calculated
            return;
        }

        // Get the current orientation
        float[] orientation = new float[3];
        SensorManager.getOrientation(R, orientation);

        // Convert azimuth to degrees
        Float newDegree = Float.valueOf((float) Math.toDegrees(orientation[0]));
        newDegree = (newDegree + 360) % 360;

        refreshState(newDegree);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // noop
    }

}
