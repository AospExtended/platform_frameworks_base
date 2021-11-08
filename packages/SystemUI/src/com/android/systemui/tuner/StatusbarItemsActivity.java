/*
 * Copyright (C) 2016 The Pure Android Project
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
package com.android.systemui.tuner;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import com.android.systemui.Dependency;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.fragments.FragmentService;

import javax.inject.Inject;

public class StatusbarItemsActivity extends Activity {

    @Inject
    StatusbarItemsActivity() {
        super();
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new StatusbarItems())
                .commit();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Dependency.destroy(FragmentService.class, s -> s.destroyAll());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
            switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            }
        return super.onOptionsItemSelected(item);
    }
}

