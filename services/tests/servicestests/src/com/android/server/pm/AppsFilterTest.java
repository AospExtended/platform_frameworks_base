/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedProvider;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;

@Presubmit
@RunWith(JUnit4.class)
public class AppsFilterTest {

    private static final int DUMMY_CALLING_APPID = 10345;
    private static final int DUMMY_TARGET_APPID = 10556;
    private static final int DUMMY_ACTOR_APPID = 10656;
    private static final int DUMMY_OVERLAY_APPID = 10756;
    private static final int SYSTEM_USER = 0;
    private static final int SECONDARY_USER = 10;
    private static final int[] USER_ARRAY = {SYSTEM_USER, SECONDARY_USER};
    private static final UserInfo[] USER_INFO_LIST = Arrays.stream(USER_ARRAY).mapToObj(
            id -> new UserInfo(id, Integer.toString(id), 0)).toArray(UserInfo[]::new);

    @Mock
    AppsFilter.FeatureConfig mFeatureConfigMock;
    @Mock
    AppsFilter.StateProvider mStateProvider;
    @Mock
    Executor mMockExecutor;

    private ArrayMap<String, PackageSetting> mExisting = new ArrayMap<>();

    private static ParsingPackage pkg(String packageName) {
        return PackageImpl.forTesting(packageName)
                .setTargetSdkVersion(Build.VERSION_CODES.R);
    }

    private static ParsingPackage pkg(String packageName, Intent... queries) {
        ParsingPackage pkg = pkg(packageName);
        if (queries != null) {
            for (Intent intent : queries) {
                pkg.addQueriesIntent(intent);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkgQueriesProvider(String packageName,
            String... queriesAuthorities) {
        ParsingPackage pkg = pkg(packageName);
        if (queriesAuthorities != null) {
            for (String authority : queriesAuthorities) {
                pkg.addQueriesProvider(authority);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkg(String packageName, String... queriesPackages) {
        ParsingPackage pkg = pkg(packageName);
        if (queriesPackages != null) {
            for (String queryPackageName : queriesPackages) {
                pkg.addQueriesPackage(queryPackageName);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkg(String packageName, IntentFilter... filters) {
        ParsedActivity activity = createActivity(packageName, filters);
        return pkg(packageName).addActivity(activity);
    }

    private static ParsingPackage pkgWithReceiver(String packageName, IntentFilter... filters) {
        ParsedActivity receiver = createActivity(packageName, filters);
        return pkg(packageName).addReceiver(receiver);
    }

    private static ParsedActivity createActivity(String packageName, IntentFilter[] filters) {
        ParsedActivity activity = new ParsedActivity();
        activity.setPackageName(packageName);
        for (IntentFilter filter : filters) {
            final ParsedIntentInfo info = new ParsedIntentInfo();
            if (filter.countActions() > 0) {
                filter.actionsIterator().forEachRemaining(info::addAction);
            }
            if (filter.countCategories() > 0) {
                filter.actionsIterator().forEachRemaining(info::addAction);
            }
            if (filter.countDataAuthorities() > 0) {
                filter.authoritiesIterator().forEachRemaining(info::addDataAuthority);
            }
            if (filter.countDataSchemes() > 0) {
                filter.schemesIterator().forEachRemaining(info::addDataScheme);
            }
            activity.addIntent(info);
            activity.setExported(true);
        }
        return activity;
    }

    private static ParsingPackage pkgWithInstrumentation(
            String packageName, String instrumentationTargetPackage) {
        ParsedInstrumentation instrumentation = new ParsedInstrumentation();
        instrumentation.setTargetPackage(instrumentationTargetPackage);
        return pkg(packageName).addInstrumentation(instrumentation);
    }

    private static ParsingPackage pkgWithProvider(String packageName, String authority) {
        ParsedProvider provider = new ParsedProvider();
        provider.setPackageName(packageName);
        provider.setExported(true);
        provider.setAuthority(authority);
        return pkg(packageName)
                .addProvider(provider);
    }

    @Before
    public void setup() throws Exception {
        mExisting = new ArrayMap<>();

        MockitoAnnotations.initMocks(this);
        doAnswer(invocation -> {
            ((AppsFilter.StateProvider.CurrentStateCallback) invocation.getArgument(0))
                    .currentState(mExisting, USER_INFO_LIST);
            return new Object();
        }).when(mStateProvider)
                .runWithState(any(AppsFilter.StateProvider.CurrentStateCallback.class));

        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return new Object();
        }).when(mMockExecutor).execute(any(Runnable.class));

        when(mFeatureConfigMock.isGloballyEnabled()).thenReturn(true);
        when(mFeatureConfigMock.packageIsEnabled(any(AndroidPackage.class))).thenAnswer(
                (Answer<Boolean>) invocation ->
                        ((AndroidPackage)invocation.getArgument(SYSTEM_USER)).getTargetSdkVersion()
                                >= Build.VERSION_CODES.R);
    }

    @Test
    public void testSystemReadyPropogates() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        appsFilter.onSystemReady();
        verify(mFeatureConfigMock).onSystemReady();
    }

    @Test
    public void testQueriesAction_FilterMatches() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package", new IntentFilter("TEST_ACTION")), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesProtectedAction_FilterDoesNotMatch() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final Signature frameworkSignature = Mockito.mock(Signature.class);
        final PackageParser.SigningDetails frameworkSigningDetails =
                new PackageParser.SigningDetails(new Signature[]{frameworkSignature}, 1);
        final ParsingPackage android = pkg("android");
        android.addProtectedBroadcast("TEST_ACTION");
        simulateAddPackage(appsFilter, android, 1000,
                b -> b.setSigningDetails(frameworkSigningDetails));
        appsFilter.onSystemReady();

        final int activityUid = DUMMY_TARGET_APPID;
        PackageSetting targetActivity = simulateAddPackage(appsFilter,
                pkg("com.target.activity", new IntentFilter("TEST_ACTION")), activityUid);
        final int receiverUid = DUMMY_TARGET_APPID + 1;
        PackageSetting targetReceiver = simulateAddPackage(appsFilter,
                pkgWithReceiver("com.target.receiver", new IntentFilter("TEST_ACTION")),
                receiverUid);
        final int callingUid = DUMMY_CALLING_APPID;
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.calling.action", new Intent("TEST_ACTION")), callingUid);
        final int wildcardUid = DUMMY_CALLING_APPID + 1;
        PackageSetting callingWildCard = simulateAddPackage(appsFilter,
                pkg("com.calling.wildcard", new Intent("*")), wildcardUid);

        assertFalse(appsFilter.shouldFilterApplication(callingUid, calling, targetActivity,
                SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(callingUid, calling, targetReceiver,
                SYSTEM_USER));

        assertFalse(appsFilter.shouldFilterApplication(
                wildcardUid, callingWildCard, targetActivity, SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(
                wildcardUid, callingWildCard, targetReceiver, SYSTEM_USER));
    }

    @Test
    public void testQueriesProvider_FilterMatches() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.authority"),
                DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesDifferentProvider_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.other.authority"),
                DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesProviderWithSemiColon_FilterMatches() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority;com.some.other.authority"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.authority"),
                DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesAction_NoMatchingAction_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesAction_NoMatchingActionFilterLowSdk_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        ParsingPackage callingPkg = pkg("com.some.other.package",
                new Intent("TEST_ACTION"))
                .setTargetSdkVersion(Build.VERSION_CODES.P);
        PackageSetting calling = simulateAddPackage(appsFilter, callingPkg,
                DUMMY_CALLING_APPID);


        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoQueries_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testForceQueryable_SystemDoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package").setForceQueryable(true), DUMMY_TARGET_APPID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }


    @Test
    public void testForceQueryable_NonSystemFilters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package").setForceQueryable(true), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testForceQueryableByDevice_SystemCaller_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{"com.some.package"},
                        false, null, mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }


    @Test
    public void testSystemSignedTarget_DoesntFilter() throws CertificateException {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        appsFilter.onSystemReady();

        final Signature frameworkSignature = Mockito.mock(Signature.class);
        final PackageParser.SigningDetails frameworkSigningDetails =
                new PackageParser.SigningDetails(new Signature[]{frameworkSignature}, 1);

        final Signature otherSignature = Mockito.mock(Signature.class);
        final PackageParser.SigningDetails otherSigningDetails =
                new PackageParser.SigningDetails(new Signature[]{otherSignature}, 1);

        simulateAddPackage(appsFilter, pkg("android"), 1000,
                b -> b.setSigningDetails(frameworkSigningDetails));
        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID,
                b -> b.setSigningDetails(frameworkSigningDetails)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID,
                b -> b.setSigningDetails(otherSigningDetails));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testForceQueryableByDevice_NonSystemCaller_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{"com.some.package"},
                        false, null, mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }


    @Test
    public void testSystemQueryable_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{},
                        true /* system force queryable */, null, mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesPackage_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", "com.some.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoQueries_FeatureOff_DoesntFilter() throws Exception {
        when(mFeatureConfigMock.packageIsEnabled(any(AndroidPackage.class)))
                .thenReturn(false);
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(
                appsFilter, pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(
                appsFilter, pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testSystemUid_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);

        assertFalse(appsFilter.shouldFilterApplication(SYSTEM_USER, null, target, SYSTEM_USER));
        assertFalse(appsFilter.shouldFilterApplication(Process.FIRST_APPLICATION_UID - 1,
                null, target, SYSTEM_USER));
    }

    @Test
    public void testSystemUidSecondaryUser_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);

        assertFalse(appsFilter.shouldFilterApplication(0, null, target, SECONDARY_USER));
        assertFalse(appsFilter.shouldFilterApplication(
                UserHandle.getUid(SECONDARY_USER, Process.FIRST_APPLICATION_UID - 1),
                null, target, SECONDARY_USER));
    }

    @Test
    public void testNonSystemUid_NoCallingSetting_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, null, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoTargetPackage_filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = new PackageSettingBuilder()
                .setAppId(DUMMY_TARGET_APPID)
                .setName("com.some.package")
                .setCodePath("/")
                .setResourcePath("/")
                .setPVersionCode(1L)
                .build();
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testActsOnTargetOfOverlay() throws Exception {
        final String actorName = "overlay://test/actorName";

        ParsingPackage target = pkg("com.some.package.target")
                .addOverlayable("overlayableName", actorName);
        ParsingPackage overlay = pkg("com.some.package.overlay")
                .setOverlay(true)
                .setOverlayTarget(target.getPackageName())
                .setOverlayTargetName("overlayableName");
        ParsingPackage actor = pkg("com.some.package.actor");

        final AppsFilter appsFilter = new AppsFilter(
                mStateProvider,
                mFeatureConfigMock,
                new String[]{},
                false,
                new OverlayReferenceMapper.Provider() {
                    @Nullable
                    @Override
                    public String getActorPkg(String actorString) {
                        if (actorName.equals(actorString)) {
                            return actor.getPackageName();
                        }
                        return null;
                    }

                    @NonNull
                    @Override
                    public Map<String, Set<String>> getTargetToOverlayables(
                            @NonNull AndroidPackage pkg) {
                        if (overlay.getPackageName().equals(pkg.getPackageName())) {
                            Map<String, Set<String>> map = new ArrayMap<>();
                            Set<String> set = new ArraySet<>();
                            set.add(overlay.getOverlayTargetName());
                            map.put(overlay.getOverlayTarget(), set);
                            return map;
                        }
                        return Collections.emptyMap();
                    }
                },
                mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting targetSetting = simulateAddPackage(appsFilter, target, DUMMY_TARGET_APPID);
        PackageSetting overlaySetting =
                simulateAddPackage(appsFilter, overlay, DUMMY_OVERLAY_APPID);
        PackageSetting actorSetting = simulateAddPackage(appsFilter, actor, DUMMY_ACTOR_APPID);

        // Actor can see both target and overlay
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSetting,
                targetSetting, SYSTEM_USER));
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSetting,
                overlaySetting, SYSTEM_USER));

        // But target/overlay can't see each other
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, targetSetting,
                overlaySetting, SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_OVERLAY_APPID, overlaySetting,
                targetSetting, SYSTEM_USER));

        // And can't see the actor
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, targetSetting,
                actorSetting, SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_OVERLAY_APPID, overlaySetting,
                actorSetting, SYSTEM_USER));
    }

    @Test
    public void testActsOnTargetOfOverlayThroughSharedUser() throws Exception {
//        Debug.waitForDebugger();

        final String actorName = "overlay://test/actorName";

        ParsingPackage target = pkg("com.some.package.target")
                .addOverlayable("overlayableName", actorName);
        ParsingPackage overlay = pkg("com.some.package.overlay")
                .setOverlay(true)
                .setOverlayTarget(target.getPackageName())
                .setOverlayTargetName("overlayableName");
        ParsingPackage actorOne = pkg("com.some.package.actor.one");
        ParsingPackage actorTwo = pkg("com.some.package.actor.two");

        final AppsFilter appsFilter = new AppsFilter(
                mStateProvider,
                mFeatureConfigMock,
                new String[]{},
                false,
                new OverlayReferenceMapper.Provider() {
                    @Nullable
                    @Override
                    public String getActorPkg(String actorString) {
                        // Only actorOne is mapped as a valid actor
                        if (actorName.equals(actorString)) {
                            return actorOne.getPackageName();
                        }
                        return null;
                    }

                    @NonNull
                    @Override
                    public Map<String, Set<String>> getTargetToOverlayables(
                            @NonNull AndroidPackage pkg) {
                        if (overlay.getPackageName().equals(pkg.getPackageName())) {
                            Map<String, Set<String>> map = new ArrayMap<>();
                            Set<String> set = new ArraySet<>();
                            set.add(overlay.getOverlayTargetName());
                            map.put(overlay.getOverlayTarget(), set);
                            return map;
                        }
                        return Collections.emptyMap();
                    }
                },
                mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting targetSetting = simulateAddPackage(appsFilter, target, DUMMY_TARGET_APPID);
        SharedUserSetting actorSharedSetting = new SharedUserSetting("actorSharedUser",
                targetSetting.pkgFlags, targetSetting.pkgPrivateFlags);
        PackageSetting overlaySetting =
                simulateAddPackage(appsFilter, overlay, DUMMY_OVERLAY_APPID);
        simulateAddPackage(appsFilter, actorOne, DUMMY_ACTOR_APPID,
                null /*settingBuilder*/, actorSharedSetting);
        simulateAddPackage(appsFilter, actorTwo, DUMMY_ACTOR_APPID,
                null /*settingBuilder*/, actorSharedSetting);


        // actorTwo can see both target and overlay
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSharedSetting,
                targetSetting, SYSTEM_USER));
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSharedSetting,
                overlaySetting, SYSTEM_USER));
    }

    @Test
    public void testInitiatingApp_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID, withInstallSource(target.name, null, null, false));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testUninstalledInitiatingApp_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID, withInstallSource(target.name, null, null, true));

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testOriginatingApp_Filters() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID, withInstallSource(null, target.name, null, false));

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testInstallingApp_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID, withInstallSource(null, null, target.name, false));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testInstrumentation_DoesntFilter() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();


        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting instrumentation = simulateAddPackage(appsFilter,
                pkgWithInstrumentation("com.some.other.package", "com.some.package"),
                DUMMY_CALLING_APPID);

        assertFalse(
                appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, instrumentation, target,
                        SYSTEM_USER));
        assertFalse(
                appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, target, instrumentation,
                        SYSTEM_USER));
    }

    @Test
    public void testWhoCanSee() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        final int systemAppId = Process.FIRST_APPLICATION_UID - 1;
        final int seesNothingAppId = Process.FIRST_APPLICATION_UID;
        final int hasProviderAppId = Process.FIRST_APPLICATION_UID + 1;
        final int queriesProviderAppId = Process.FIRST_APPLICATION_UID + 2;

        PackageSetting system = simulateAddPackage(appsFilter, pkg("some.system.pkg"), systemAppId);
        PackageSetting seesNothing = simulateAddPackage(appsFilter, pkg("com.some.package"),
                seesNothingAppId);
        PackageSetting hasProvider = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.other.package", "com.some.authority"), hasProviderAppId);
        PackageSetting queriesProvider = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.yet.some.other.package", "com.some.authority"),
                queriesProviderAppId);

        final SparseArray<int[]> systemFilter =
                appsFilter.getVisibilityWhitelist(system, USER_ARRAY, mExisting);
        assertThat(toList(systemFilter.get(SYSTEM_USER)),
                contains(seesNothingAppId, hasProviderAppId, queriesProviderAppId));

        final SparseArray<int[]> seesNothingFilter =
                appsFilter.getVisibilityWhitelist(seesNothing, USER_ARRAY, mExisting);
        assertThat(toList(seesNothingFilter.get(SYSTEM_USER)),
                contains(seesNothingAppId));
        assertThat(toList(seesNothingFilter.get(SECONDARY_USER)),
                contains(seesNothingAppId));

        final SparseArray<int[]> hasProviderFilter =
                appsFilter.getVisibilityWhitelist(hasProvider, USER_ARRAY, mExisting);
        assertThat(toList(hasProviderFilter.get(SYSTEM_USER)),
                contains(hasProviderAppId, queriesProviderAppId));

        SparseArray<int[]> queriesProviderFilter =
                appsFilter.getVisibilityWhitelist(queriesProvider, USER_ARRAY, mExisting);
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)),
                contains(queriesProviderAppId));

        // provider read
        appsFilter.grantImplicitAccess(hasProviderAppId, queriesProviderAppId);

        // ensure implicit access is included in the filter
        queriesProviderFilter =
                appsFilter.getVisibilityWhitelist(queriesProvider, USER_ARRAY, mExisting);
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)),
                contains(hasProviderAppId, queriesProviderAppId));
    }

    private List<Integer> toList(int[] array) {
        ArrayList<Integer> ret = new ArrayList<>(array.length);
        for (int i = 0; i < array.length; i++) {
            ret.add(i, array[i]);
        }
        return ret;
    }

    private interface WithSettingBuilder {
        PackageSettingBuilder withBuilder(PackageSettingBuilder builder);
    }

    private void simulateAddBasicAndroid(AppsFilter appsFilter) throws Exception {
        final Signature frameworkSignature = Mockito.mock(Signature.class);
        final PackageParser.SigningDetails frameworkSigningDetails =
                new PackageParser.SigningDetails(new Signature[]{frameworkSignature}, 1);
        final ParsingPackage android = pkg("android");
        simulateAddPackage(appsFilter, android, 1000,
                b -> b.setSigningDetails(frameworkSigningDetails));
    }

    private PackageSetting simulateAddPackage(AppsFilter filter,
            ParsingPackage newPkgBuilder, int appId) {
        return simulateAddPackage(filter, newPkgBuilder, appId, null /*settingBuilder*/);
    }

    private PackageSetting simulateAddPackage(AppsFilter filter,
            ParsingPackage newPkgBuilder, int appId, @Nullable WithSettingBuilder action) {
        return simulateAddPackage(filter, newPkgBuilder, appId, action, null /*sharedUserSetting*/);
    }

    private PackageSetting simulateAddPackage(AppsFilter filter,
                ParsingPackage newPkgBuilder, int appId, @Nullable WithSettingBuilder action,
            @Nullable SharedUserSetting sharedUserSetting) {
        AndroidPackage newPkg = ((ParsedPackage) newPkgBuilder.hideAsParsed()).hideAsFinal();

        final PackageSettingBuilder settingBuilder = new PackageSettingBuilder()
                .setPackage(newPkg)
                .setAppId(appId)
                .setName(newPkg.getPackageName())
                .setCodePath("/")
                .setResourcePath("/")
                .setPVersionCode(1L);
        final PackageSetting setting =
                (action == null ? settingBuilder : action.withBuilder(settingBuilder)).build();
        mExisting.put(newPkg.getPackageName(), setting);
        if (sharedUserSetting != null) {
            sharedUserSetting.addPackage(setting);
            setting.sharedUser = sharedUserSetting;
        }
        filter.addPackage(setting);
        return setting;
    }

    private WithSettingBuilder withInstallSource(String initiatingPackageName,
            String originatingPackageName, String installerPackageName,
            boolean isInitiatingPackageUninstalled) {
        final InstallSource installSource = InstallSource.create(initiatingPackageName,
                originatingPackageName, installerPackageName,
                /* isOrphaned= */ false, isInitiatingPackageUninstalled);
        return setting -> setting.setInstallSource(installSource);
    }
}
