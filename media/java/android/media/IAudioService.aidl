/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import android.bluetooth.BluetoothDevice;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IAudioServerStateDispatcher;
import android.media.IPlaybackConfigDispatcher;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IStrategyPreferredDeviceDispatcher;
import android.media.IVolumeController;
import android.media.IVolumeController;
import android.media.PlayerBase;
import android.media.VolumePolicy;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.media.projection.IMediaProjection;
import android.net.Uri;
import android.view.KeyEvent;

/**
 * {@hide}
 */
interface IAudioService {
    // C++ and Java methods below.

    // WARNING: When methods are inserted or deleted in this section, the transaction IDs in
    // frameworks/native/include/audiomanager/IAudioManager.h must be updated to match the order
    // in this file.
    //
    // When a method's argument list is changed, BpAudioManager's corresponding serialization code
    // (if any) in frameworks/native/services/audiomanager/IAudioManager.cpp must be updated.

    int trackPlayer(in PlayerBase.PlayerIdCard pic);

    oneway void playerAttributes(in int piid, in AudioAttributes attr);

    oneway void playerEvent(in int piid, in int event);

    oneway void releasePlayer(in int piid);

    int trackRecorder(in IBinder recorder);

    oneway void recorderEvent(in int riid, in int event);

    oneway void releaseRecorder(in int riid);

    // Java-only methods below.

    oneway void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags,
            String callingPackage, String caller);

    void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);

    @UnsupportedAppUsage
    void setStreamVolume(int streamType, int index, int flags, String callingPackage);

    oneway void handleVolumeKey(in KeyEvent event, boolean isOnTv,
            String callingPackage, String caller);

    boolean isStreamMute(int streamType);

    void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb);

    boolean isMasterMute();

    void setMasterMute(boolean mute, int flags, String callingPackage, int userId);

    @UnsupportedAppUsage
    int getStreamVolume(int streamType);

    int getStreamMinVolume(int streamType);

    @UnsupportedAppUsage
    int getStreamMaxVolume(int streamType);

    List<AudioVolumeGroup> getAudioVolumeGroups();

    void setVolumeIndexForAttributes(in AudioAttributes aa, int index, int flags, String callingPackage);

    int getVolumeIndexForAttributes(in AudioAttributes aa);

    int getMaxVolumeIndexForAttributes(in AudioAttributes aa);

    int getMinVolumeIndexForAttributes(in AudioAttributes aa);

    int getLastAudibleStreamVolume(int streamType);

    void setSupportedSystemUsages(in int[] systemUsages);

    int[] getSupportedSystemUsages();

    List<AudioProductStrategy> getAudioProductStrategies();

    boolean isMicrophoneMuted();

    void setMicrophoneMute(boolean on, String callingPackage, int userId);

    oneway void setMicrophoneMuteFromSwitch(boolean on);

    void setRingerModeExternal(int ringerMode, String caller);

    void setRingerModeInternal(int ringerMode, String caller);

    int getRingerModeExternal();

    int getRingerModeInternal();

    boolean isValidRingerMode(int ringerMode);

    void setVibrateSetting(int vibrateType, int vibrateSetting);

    int getVibrateSetting(int vibrateType);

    boolean shouldVibrate(int vibrateType);

    void setMode(int mode, IBinder cb, String callingPackage);

    int getMode();

    oneway void playSoundEffect(int effectType);

    oneway void playSoundEffectVolume(int effectType, float volume);

    boolean loadSoundEffects();

    oneway void unloadSoundEffects();

    oneway void reloadAudioSettings();

    oneway void avrcpSupportsAbsoluteVolume(String address, boolean support);

    void setSpeakerphoneOn(IBinder cb, boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    boolean isBluetoothScoOn();

    void setBluetoothA2dpOn(boolean on);

    boolean isBluetoothA2dpOn();

    int requestAudioFocus(in AudioAttributes aa, int durationHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags,
            IAudioPolicyCallback pcb, int sdk);

    int abandonAudioFocus(IAudioFocusDispatcher fd, String clientId, in AudioAttributes aa,
            in String callingPackageName);

    void unregisterAudioFocusClient(String clientId);

    int getCurrentAudioFocus();

    void startBluetoothSco(IBinder cb, int targetSdkVersion);
    void startBluetoothScoVirtualCall(IBinder cb);
    void stopBluetoothSco(IBinder cb);

    void forceVolumeControlStream(int streamType, IBinder cb);

    void setRingtonePlayer(IRingtonePlayer player);
    IRingtonePlayer getRingtonePlayer();
    int getUiSoundsStreamType();

    void setWiredDeviceConnectionState(int type, int state, String address, String name,
            String caller);

    void handleBluetoothA2dpDeviceConfigChange(in BluetoothDevice device);

    void handleBluetoothA2dpActiveDeviceChange(in BluetoothDevice device,
            int state, int profile, boolean suppressNoisyIntent, int a2dpVolume);

    @UnsupportedAppUsage
    AudioRoutesInfo startWatchingRoutes(in IAudioRoutesObserver observer);

    boolean isCameraSoundForced();

    void setVolumeController(in IVolumeController controller);

    void notifyVolumeControllerVisible(in IVolumeController controller, boolean visible);

    boolean isStreamAffectedByRingerMode(int streamType);

    boolean isStreamAffectedByMute(int streamType);

    void disableSafeMediaVolume(String callingPackage);

    int setHdmiSystemAudioSupported(boolean on);

    boolean isHdmiSystemAudioSupported();

    String registerAudioPolicy(in AudioPolicyConfig policyConfig,
            in IAudioPolicyCallback pcb, boolean hasFocusListener, boolean isFocusPolicy,
            boolean isTestFocusPolicy,
            boolean isVolumeController, in IMediaProjection projection);

    oneway void unregisterAudioPolicyAsync(in IAudioPolicyCallback pcb);

    void unregisterAudioPolicy(in IAudioPolicyCallback pcb);

    int addMixForPolicy(in AudioPolicyConfig policyConfig, in IAudioPolicyCallback pcb);

    int removeMixForPolicy(in AudioPolicyConfig policyConfig, in IAudioPolicyCallback pcb);

    int setFocusPropertiesForPolicy(int duckingBehavior, in IAudioPolicyCallback pcb);

    void setVolumePolicy(in VolumePolicy policy);

    boolean hasRegisteredDynamicPolicy();

    void registerRecordingCallback(in IRecordingConfigDispatcher rcdb);

    oneway void unregisterRecordingCallback(in IRecordingConfigDispatcher rcdb);

    List<AudioRecordingConfiguration> getActiveRecordingConfigurations();

    void registerPlaybackCallback(in IPlaybackConfigDispatcher pcdb);

    oneway void unregisterPlaybackCallback(in IPlaybackConfigDispatcher pcdb);

    List<AudioPlaybackConfiguration> getActivePlaybackConfigurations();

    void disableRingtoneSync(in int userId);

    int getFocusRampTimeMs(in int focusGain, in AudioAttributes attr);

    int dispatchFocusChange(in AudioFocusInfo afi, in int focusChange,
            in IAudioPolicyCallback pcb);

    oneway void playerHasOpPlayAudio(in int piid, in boolean hasOpPlayAudio);

    void setBluetoothHearingAidDeviceConnectionState(in BluetoothDevice device,
            int state, boolean suppressNoisyIntent, int musicDevice);

    void setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(in BluetoothDevice device,
            int state, int profile, boolean suppressNoisyIntent, int a2dpVolume);

    oneway void setFocusRequestResultFromExtPolicy(in AudioFocusInfo afi, int requestResult,
            in IAudioPolicyCallback pcb);

    void registerAudioServerStateDispatcher(IAudioServerStateDispatcher asd);

    oneway void unregisterAudioServerStateDispatcher(IAudioServerStateDispatcher asd);

    boolean isAudioServerRunning();

    int setUidDeviceAffinity(in IAudioPolicyCallback pcb, in int uid, in int[] deviceTypes,
             in String[] deviceAddresses);

    int removeUidDeviceAffinity(in IAudioPolicyCallback pcb, in int uid);

    int setUserIdDeviceAffinity(in IAudioPolicyCallback pcb, in int userId, in int[] deviceTypes,
             in String[] deviceAddresses);
    int removeUserIdDeviceAffinity(in IAudioPolicyCallback pcb, in int userId);

    boolean hasHapticChannels(in Uri uri);

    boolean isCallScreeningModeSupported();

    int setPreferredDeviceForStrategy(in int strategy, in AudioDeviceAttributes device);

    int removePreferredDeviceForStrategy(in int strategy);

    AudioDeviceAttributes getPreferredDeviceForStrategy(in int strategy);

    List<AudioDeviceAttributes> getDevicesForAttributes(in AudioAttributes attributes);

    int setAllowedCapturePolicy(in int capturePolicy);

    int getAllowedCapturePolicy();

    void registerStrategyPreferredDeviceDispatcher(IStrategyPreferredDeviceDispatcher dispatcher);

    oneway void unregisterStrategyPreferredDeviceDispatcher(
            IStrategyPreferredDeviceDispatcher dispatcher);

    oneway void setRttEnabled(in boolean rttEnabled);

    void setDeviceVolumeBehavior(in AudioDeviceAttributes device,
             in int deviceVolumeBehavior, in String pkgName);

    int getDeviceVolumeBehavior(in AudioDeviceAttributes device);

    // WARNING: read warning at top of file, new methods that need to be used by native
    // code via IAudioManager.h need to be added to the top section.

    oneway void setMultiAudioFocusEnabled(in boolean enabled);
}
