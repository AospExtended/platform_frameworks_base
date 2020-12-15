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

package com.android.systemui.screenrecord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.CurrentUserContextTracker;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class RecordingServiceTest extends SysuiTestCase {

    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private RecordingController mController;
    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private ScreenMediaRecorder mScreenMediaRecorder;
    @Mock
    private Notification mNotification;
    @Mock
    private Executor mExecutor;
    @Mock
    private CurrentUserContextTracker mUserContextTracker;
    private KeyguardDismissUtil mKeyguardDismissUtil = new KeyguardDismissUtil() {
        public void executeWhenUnlocked(ActivityStarter.OnDismissAction action,
                boolean requiresShadeOpen) {
            action.onDismiss();
        }
    };

    private RecordingService mRecordingService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRecordingService = Mockito.spy(new RecordingService(mController, mExecutor, mUiEventLogger,
                mNotificationManager, mUserContextTracker, mKeyguardDismissUtil));

        // Return actual context info
        doReturn(mContext).when(mRecordingService).getApplicationContext();
        doReturn(mContext.getUserId()).when(mRecordingService).getUserId();
        doReturn(mContext.getPackageName()).when(mRecordingService).getPackageName();
        doReturn(mContext.getContentResolver()).when(mRecordingService).getContentResolver();

        // Mock notifications
        doNothing().when(mRecordingService).createRecordingNotification();
        doReturn(mNotification).when(mRecordingService).createProcessingNotification();
        doReturn(mNotification).when(mRecordingService).createSaveNotification(any());

        doNothing().when(mRecordingService).startForeground(anyInt(), any());
        doReturn(mScreenMediaRecorder).when(mRecordingService).getRecorder();

        doReturn(mContext).when(mUserContextTracker).getCurrentUserContext();
    }

    @Test
    public void testLogStartRecording() {
        Intent startIntent = RecordingService.getStartIntent(mContext, 0, 0, false);
        mRecordingService.onStartCommand(startIntent, 0, 0);

        verify(mUiEventLogger, times(1)).log(Events.ScreenRecordEvent.SCREEN_RECORD_START);
    }

    @Test
    public void testLogStopFromQsTile() {
        Intent stopIntent = RecordingService.getStopIntent(mContext);
        mRecordingService.onStartCommand(stopIntent, 0, 0);

        // Verify that we log the correct event
        verify(mUiEventLogger, times(1)).log(Events.ScreenRecordEvent.SCREEN_RECORD_END_QS_TILE);
        verify(mUiEventLogger, times(0))
                .log(Events.ScreenRecordEvent.SCREEN_RECORD_END_NOTIFICATION);
    }

    @Test
    public void testLogStopFromNotificationIntent() {
        Intent stopIntent = RecordingService.getNotificationIntent(mContext);
        mRecordingService.onStartCommand(stopIntent, 0, 0);

        // Verify that we log the correct event
        verify(mUiEventLogger, times(1))
                .log(Events.ScreenRecordEvent.SCREEN_RECORD_END_NOTIFICATION);
        verify(mUiEventLogger, times(0)).log(Events.ScreenRecordEvent.SCREEN_RECORD_END_QS_TILE);
    }
}
