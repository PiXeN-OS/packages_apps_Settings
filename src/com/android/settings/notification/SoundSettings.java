/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.settings.notification;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.preference.SeekBarVolumizer;
import android.provider.SearchIndexableResource;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.ListPreference;
import android.provider.Settings;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceChangeListener;
import android.support.v7.preference.TwoStatePreference;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.RingtonePreference;
import com.android.settings.Utils;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.sound.HandsFreeProfileOutputPreferenceController;
import com.android.settings.sound.MediaOutputPreferenceController;
import com.android.settings.widget.PreferenceCategoryController;
import com.android.settings.widget.UpdatableListPreferenceDialogFragment;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.instrumentation.Instrumentable;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SoundSettings extends DashboardFragment {
    private static final String TAG = "SoundSettings";

    private static final String SELECTED_PREFERENCE_KEY = "selected_preference";
    private static final String KEY_VOLUME_LINK_NOTIFICATION = "volume_link_notification";
    private static final int REQUEST_CODE = 200;
    private static final String KEY_ZEN_MODE = "zen_mode";
    private static final int SAMPLE_CUTOFF = 2000;  // manually cap sample playback at 2 seconds

    @VisibleForTesting
    static final int STOP_SAMPLE = 1;

    @VisibleForTesting
    final VolumePreferenceCallback mVolumeCallback = new VolumePreferenceCallback();
    @VisibleForTesting
    final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case STOP_SAMPLE:
                    mVolumeCallback.stopSample();
                    break;
            }
        }
    };

    private TwoStatePreference mVolumeLinkNotification;
    private static NotificationVolumePreferenceController sNotificationVolumeController;
    private static RingVolumePreferenceController sRingVolumePreferenceController;

    private RingtonePreference mRequestPreference;
    private UpdatableListPreferenceDialogFragment mDialogFragment;
    private String mMediaOutputControllerKey;
    private String mHfpOutputControllerKey;

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.SOUND;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            String selectedPreference = savedInstanceState.getString(SELECTED_PREFERENCE_KEY, null);
            if (!TextUtils.isEmpty(selectedPreference)) {
                mRequestPreference = (RingtonePreference) findPreference(selectedPreference);
            }

            UpdatableListPreferenceDialogFragment dialogFragment =
                    (UpdatableListPreferenceDialogFragment) getFragmentManager()
                            .findFragmentByTag(TAG);
            mDialogFragment = dialogFragment;
        }
        if (Utils.isVoiceCapable(getContext())) {
            mVolumeLinkNotification = (TwoStatePreference) findPreference(KEY_VOLUME_LINK_NOTIFICATION);
            initVolumeLinkNotification();
            updateVolumeLinkNotification();
        } else {
            removePreference(KEY_VOLUME_LINK_NOTIFICATION);
        }
    }

    @Override
    public int getHelpResource() {
        return R.string.help_url_sound;
    }

    @Override
    public void onPause() {
        super.onPause();
        mVolumeCallback.stopSample();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference instanceof RingtonePreference) {
            mRequestPreference = (RingtonePreference) preference;
            mRequestPreference.onPrepareRingtonePickerIntent(mRequestPreference.getIntent());
            startActivityForResultAsUser(
                    mRequestPreference.getIntent(),
                    REQUEST_CODE,
                    null,
                    UserHandle.of(mRequestPreference.getUserId()));
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        final int metricsCategory;
        if (mHfpOutputControllerKey.equals(preference.getKey())) {
            metricsCategory = MetricsProto.MetricsEvent.DIALOG_SWITCH_HFP_DEVICES;
        } else if (mMediaOutputControllerKey.equals(preference.getKey())) {
            metricsCategory = MetricsProto.MetricsEvent.DIALOG_SWITCH_A2DP_DEVICES;
        } else {
            metricsCategory = Instrumentable.METRICS_CATEGORY_UNKNOWN;
        }

        mDialogFragment = UpdatableListPreferenceDialogFragment.
                newInstance(preference.getKey(), metricsCategory);
        mDialogFragment.setTargetFragment(this, 0);
        mDialogFragment.show(getFragmentManager(), TAG);
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.sound_settings;
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        return buildPreferenceControllers(context, this, getLifecycle());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mRequestPreference != null) {
            mRequestPreference.onActivityResult(requestCode, resultCode, data);
            mRequestPreference = null;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mRequestPreference != null) {
            outState.putString(SELECTED_PREFERENCE_KEY, mRequestPreference.getKey());
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        ArrayList<VolumeSeekBarPreferenceController> volumeControllers = new ArrayList<>();
        volumeControllers.add(use(AlarmVolumePreferenceController.class));
        volumeControllers.add(use(MediaVolumePreferenceController.class));
        volumeControllers.add(use(RingVolumePreferenceController.class));
        volumeControllers.add(use(NotificationVolumePreferenceController.class));
        volumeControllers.add(use(CallVolumePreferenceController.class));

        use(MediaOutputPreferenceController.class).setCallback(listPreference ->
                onPreferenceDataChanged(listPreference));
        mMediaOutputControllerKey = use(MediaOutputPreferenceController.class).getPreferenceKey();
        use(HandsFreeProfileOutputPreferenceController.class).setCallback(listPreference ->
            onPreferenceDataChanged(listPreference));
        mHfpOutputControllerKey =
                use(HandsFreeProfileOutputPreferenceController.class).getPreferenceKey();

        for (VolumeSeekBarPreferenceController controller : volumeControllers) {
            controller.setCallback(mVolumeCallback);
            getLifecycle().addObserver(controller);
        }
    }

    // === Volumes ===

    final class VolumePreferenceCallback implements VolumeSeekBarPreference.Callback {
        private SeekBarVolumizer mCurrent;

        @Override
        public void onSampleStarting(SeekBarVolumizer sbv) {
            if (mCurrent != null && mCurrent != sbv) {
                mCurrent.stopSample();
            }
            mCurrent = sbv;
            if (mCurrent != null) {
                mHandler.removeMessages(STOP_SAMPLE);
                mHandler.sendEmptyMessageDelayed(STOP_SAMPLE, SAMPLE_CUTOFF);
            }
        }

        @Override
        public void onStreamValueChanged(int stream, int progress) {
            if (mCurrent != null) {
                mHandler.removeMessages(STOP_SAMPLE);
                mHandler.sendEmptyMessageDelayed(STOP_SAMPLE, SAMPLE_CUTOFF);
            }
        }

        public void stopSample() {
            if (mCurrent != null) {
                mCurrent.stopSample();
            }
        }
        @Override
        public void onMuted(int stream, boolean muted, boolean zenMuted) {
            // noop
        }
    }

    private static List<AbstractPreferenceController> buildPreferenceControllers(Context context,
            SoundSettings fragment, Lifecycle lifecycle) {
        final List<AbstractPreferenceController> controllers = new ArrayList<>();
        controllers.add(new ZenModePreferenceController(context, lifecycle, KEY_ZEN_MODE));

        // Volumes are added via xml
            sNotificationVolumeController = new NotificationVolumePreferenceController(context);
        controllers.add(sNotificationVolumeController);
            sRingVolumePreferenceController = new RingVolumePreferenceController(context);
        controllers.add(sRingVolumePreferenceController);


        // === Phone & notification ringtone ===
        controllers.add(new PhoneRingtonePreferenceController(context));
        controllers.add(new AlarmRingtonePreferenceController(context));

        // === Work Sound Settings ===
        controllers.add(new WorkSoundPreferenceController(context, fragment, lifecycle));

        // === Other Sound Settings ===
        final DialPadTonePreferenceController dialPadTonePreferenceController =
                new DialPadTonePreferenceController(context, fragment, lifecycle);
        final ScreenLockSoundPreferenceController screenLockSoundPreferenceController =
                new ScreenLockSoundPreferenceController(context, fragment, lifecycle);
        final ChargingSoundPreferenceController chargingSoundPreferenceController =
                new ChargingSoundPreferenceController(context, fragment, lifecycle);
        final ChargingVibroPreferenceController chargingVibroPreferenceController =
                new ChargingVibroPreferenceController(context, fragment, lifecycle);
        final DockingSoundPreferenceController dockingSoundPreferenceController =
                new DockingSoundPreferenceController(context, fragment, lifecycle);
        final TouchSoundPreferenceController touchSoundPreferenceController =
                new TouchSoundPreferenceController(context, fragment, lifecycle);
        final VibrateOnTouchPreferenceController vibrateOnTouchPreferenceController =
                new VibrateOnTouchPreferenceController(context, fragment, lifecycle);
        final DockAudioMediaPreferenceController dockAudioMediaPreferenceController =
                new DockAudioMediaPreferenceController(context, fragment, lifecycle);
        final BootSoundPreferenceController bootSoundPreferenceController =
                new BootSoundPreferenceController(context);
        final EmergencyTonePreferenceController emergencyTonePreferenceController =
                new EmergencyTonePreferenceController(context, fragment, lifecycle);
        final ScreenshotSoundPreferenceController screenshotSoundPreferenceController =
                new ScreenshotSoundPreferenceController(context, fragment, lifecycle);

        controllers.add(dialPadTonePreferenceController);
        controllers.add(screenLockSoundPreferenceController);
        controllers.add(chargingSoundPreferenceController);
        controllers.add(chargingVibroPreferenceController);
        controllers.add(dockingSoundPreferenceController);
        controllers.add(touchSoundPreferenceController);
        controllers.add(vibrateOnTouchPreferenceController);
        controllers.add(dockAudioMediaPreferenceController);
        controllers.add(bootSoundPreferenceController);
        controllers.add(emergencyTonePreferenceController);
        controllers.add(screenshotSoundPreferenceController);
        controllers.add(new PreferenceCategoryController(context,
                "other_sounds_and_vibrations_category").setChildren(
                Arrays.asList(dialPadTonePreferenceController,
                        screenLockSoundPreferenceController,
                        chargingSoundPreferenceController,
                        dockingSoundPreferenceController,
                        touchSoundPreferenceController,
                        vibrateOnTouchPreferenceController,
                        dockAudioMediaPreferenceController,
                        bootSoundPreferenceController,
                        emergencyTonePreferenceController)));

        return controllers;
    }

    private void initVolumeLinkNotification() {
        if (mVolumeLinkNotification != null) {
            mVolumeLinkNotification.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final boolean val = (Boolean)newValue;
                    if (val) {
                        // set to same volume as ringer by default if link is enabled
                        // otherwise notification volume will only change after next
                        // change of ringer volume
                        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        final int ringerVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, ringerVolume, 0);
                    }
                    Settings.System.putInt(getContentResolver(),
                            Settings.System.VOLUME_LINK_NOTIFICATION, val ? 1 : 0);

                    if (val) {
                        getPreferenceScreen().removePreference(sNotificationVolumeController.getPreference());
                    } else {
                        getPreferenceScreen().addPreference(sNotificationVolumeController.getPreference());
                    }
                    return true;
                }
            });
        }
    }

    private void updateVolumeLinkNotification() {
        if (mVolumeLinkNotification != null) {
            final boolean linkEnabled = Settings.System.getInt(getContentResolver(),
                    Settings.System.VOLUME_LINK_NOTIFICATION, 1) == 1;
            mVolumeLinkNotification.setChecked(linkEnabled);
            if (linkEnabled) {
                getPreferenceScreen().removePreference(sNotificationVolumeController.getPreference());
            } else {
                getPreferenceScreen().addPreference(sNotificationVolumeController.getPreference());
            }
        }
    }

    // === Indexing ===

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {

                public List<SearchIndexableResource> getXmlResourcesToIndex(
                        Context context, boolean enabled) {
                    final SearchIndexableResource sir = new SearchIndexableResource(context);
                    sir.xmlResId = R.xml.sound_settings;
                    return Arrays.asList(sir);
                }

                @Override
                public List<AbstractPreferenceController> createPreferenceControllers(
                        Context context) {
                    return buildPreferenceControllers(context, null /* fragment */,
                            null /* lifecycle */);
                }

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    // Duplicate results
                    keys.add((new ZenModePreferenceController(context, null, KEY_ZEN_MODE))
                            .getPreferenceKey());
                    return keys;
                }
            };

    // === Work Sound Settings ===

    void enableWorkSync() {
        final WorkSoundPreferenceController workSoundController =
                use(WorkSoundPreferenceController.class);
        if (workSoundController != null) {
            workSoundController.enableWorkSync();
        }
    }

    private void onPreferenceDataChanged(ListPreference preference) {
        if (mDialogFragment != null) {
            mDialogFragment.onListPreferenceUpdated(preference);
        }
    }
}
