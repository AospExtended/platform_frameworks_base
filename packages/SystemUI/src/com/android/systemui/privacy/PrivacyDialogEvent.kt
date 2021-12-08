/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.privacy

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class PrivacyDialogEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Privacy dialog is clicked by user to go to the app settings page.")
    PRIVACY_DIALOG_ITEM_CLICKED_TO_APP_SETTINGS(904),

    @UiEvent(doc = "Privacy dialog is dismissed by user.")
    PRIVACY_DIALOG_DISMISSED(905);

    override fun getId() = _id
}