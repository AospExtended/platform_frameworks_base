/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.OverlayInfo;
import android.os.UserHandle;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Data structure representing the current state of all overlay packages in the
 * system.
 *
 * Modifications to the data are signaled by returning true from any state mutating method.
 *
 * @see OverlayManagerService
 */
final class OverlayManagerSettings {
    /**
     * All overlay data for all users and target packages is stored in this list.
     * This keeps memory down, while increasing the cost of running queries or mutating the
     * data. This is ok, since changing of overlays is very rare and has larger costs associated
     * with it.
     *
     * The order of the items in the list is important, those with a lower index having a lower
     * priority.
     */
    private final ArrayList<SettingsItem> mItems = new ArrayList<>();

    void init(@NonNull final String packageName, final int userId,
            @NonNull final String targetPackageName, @NonNull final String baseCodePath,
            boolean isStatic, int priority) {
        remove(packageName, userId);
        final SettingsItem item =
                new SettingsItem(packageName, userId, targetPackageName, baseCodePath,
                        isStatic, priority);
        if (isStatic) {
            int i;
            for (i = mItems.size() - 1; i >= 0; i--) {
                SettingsItem parentItem = mItems.get(i);
                if (parentItem.mIsStatic && parentItem.mPriority <= priority) {
                    break;
                }
            }
            int pos = i + 1;
            if (pos == mItems.size()) {
                mItems.add(item);
            } else {
                mItems.add(pos, item);
            }
        } else {
            mItems.add(item);
        }
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean remove(@NonNull final String packageName, final int userId) {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            return false;
        }

        mItems.remove(idx);
        return true;
    }

    String getTargetPackageName(@NonNull final String packageName, final int userId)
            throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).getTargetPackageName();
    }

    OverlayInfo getOverlayInfo(@NonNull final String packageName, final int userId)
            throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).getOverlayInfo();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setBaseCodePath(@NonNull final String packageName, final int userId,
            @NonNull final String path) throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).setBaseCodePath(path);
    }

    boolean getEnabled(@NonNull final String packageName, final int userId) throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).isEnabled();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setEnabled(@NonNull final String packageName, final int userId, final boolean enable)
            throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).setEnabled(enable);
    }

    int getState(@NonNull final String packageName, final int userId) throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).getState();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setState(@NonNull final String packageName, final int userId, final int state)
            throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).setState(state);
    }

    boolean getUpgrading(@NonNull final String packageName, final int userId)
            throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return mItems.get(idx).isUpgrading();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setUpgrading(@NonNull final String packageName, final int userId, final boolean newValue)
            throws BadKeyException {
        final int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }

        final SettingsItem item = mItems.get(idx);
        if (newValue == item.isUpgrading()) {
            return false;
        }

        if (newValue) {
            boolean result = item.setUpgrading(true);
            if (result) {
                item.setState(OverlayInfo.STATE_UNKNOWN); // hmmm
            }
            return result;
        } else {
            return item.setUpgrading(false);
        }
    }

    List<OverlayInfo> getOverlaysForTarget(@NonNull final String targetPackageName,
            final int userId) {
        return selectWhereTarget(targetPackageName, userId)
                .map(SettingsItem::getOverlayInfo)
                .collect(Collectors.toList());
    }

    ArrayMap<String, List<OverlayInfo>> getOverlaysForUser(final int userId) {
        return selectWhereUser(userId)
                .map(SettingsItem::getOverlayInfo)
                .collect(Collectors.groupingBy(info -> info.targetPackageName, ArrayMap::new,
                        Collectors.toList()));
    }

    int[] getUsers() {
        return mItems.stream().mapToInt(SettingsItem::getUserId).distinct().toArray();
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean removeUser(final int userId) {
        boolean removed = false;
        for (int i = 0; i < mItems.size(); i++) {
            final SettingsItem item = mItems.get(i);
            if (item.getUserId() == userId) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing overlay " + item.mPackageName + " for user " + userId
                            + " from settings because user was removed");
                }
                mItems.remove(i);
                removed = true;
                i--;
            }
        }
        return removed;
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setPriority(@NonNull final String packageName,
            @NonNull final String newParentPackageName, final int userId) {
        if (packageName.equals(newParentPackageName)) {
            return false;
        }
        final int moveIdx = select(packageName, userId);
        if (moveIdx < 0) {
            return false;
        }

        final int parentIdx = select(newParentPackageName, userId);
        if (parentIdx < 0) {
            return false;
        }

        final SettingsItem itemToMove = mItems.get(moveIdx);

        if (itemToMove.isUpgrading() || mItems.get(parentIdx).isUpgrading()) {
            return false;
        }

        // Make sure both packages are targeting the same package.
        if (!itemToMove.getTargetPackageName().equals(
                mItems.get(parentIdx).getTargetPackageName())) {
            return false;
        }

        mItems.remove(moveIdx);
        final int newParentIdx = select(newParentPackageName, userId);
        mItems.add(newParentIdx, itemToMove);
        return moveIdx != newParentIdx;
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setLowestPriority(@NonNull final String packageName, final int userId) {
        final int idx = select(packageName, userId);
        if (idx <= 0) {
            // If the item doesn't exist or is already the lowest, don't change anything.
            return false;
        }

        final SettingsItem item = mItems.get(idx);
        if (item.isUpgrading()) {
            return false;
        }
        mItems.remove(item);
        mItems.add(0, item);
        return true;
    }

    /**
     * Returns true if the settings were modified, false if they remain the same.
     */
    boolean setHighestPriority(@NonNull final String packageName, final int userId) {
        final int idx = select(packageName, userId);

        // If the item doesn't exist or is already the highest, don't change anything.
        if (idx < 0 || idx == mItems.size() - 1) {
            return false;
        }

        final SettingsItem item = mItems.get(idx);
        if (item.isUpgrading()) {
            return false;
        }
        mItems.remove(idx);
        mItems.add(item);
        return true;
    }

    void dump(@NonNull final PrintWriter p) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(p, "  ");
        pw.println("Settings");
        pw.increaseIndent();

        if (mItems.isEmpty()) {
            pw.println("<none>");
            return;
        }

        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            pw.println(item.mPackageName + ":" + item.getUserId() + " {");
            pw.increaseIndent();

            pw.print("mPackageName.......: "); pw.println(item.mPackageName);
            pw.print("mUserId............: "); pw.println(item.getUserId());
            pw.print("mTargetPackageName.: "); pw.println(item.getTargetPackageName());
            pw.print("mBaseCodePath......: "); pw.println(item.getBaseCodePath());
            pw.print("mState.............: "); pw.println(OverlayInfo.stateToString(item.getState()));
            pw.print("mIsEnabled.........: "); pw.println(item.isEnabled());
            pw.print("mIsStatic..........: "); pw.println(item.isStatic());
            pw.print("isUpgrading........: "); pw.println(item.isUpgrading());

            pw.decreaseIndent();
            pw.println("}");
        }
    }

    void restore(@NonNull final InputStream is) throws IOException, XmlPullParserException {
        Serializer.restore(mItems, is);
    }

    void persist(@NonNull final OutputStream os) throws IOException, XmlPullParserException {
        Serializer.persist(mItems, os);
    }

    private static final class Serializer {
        private static final String TAG_OVERLAYS = "overlays";
        private static final String TAG_ITEM = "item";

        private static final String ATTR_BASE_CODE_PATH = "baseCodePath";
        private static final String ATTR_IS_ENABLED = "isEnabled";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_TARGET_PACKAGE_NAME = "targetPackageName";
        private static final String ATTR_IS_STATIC = "isStatic";
        private static final String ATTR_PRIORITY = "priority";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_VERSION = "version";
        private static final String ATTR_IS_UPGRADING = "isUpgrading";

        private static final int CURRENT_VERSION = 3;

        public static void restore(@NonNull final ArrayList<SettingsItem> table,
                @NonNull final InputStream is) throws IOException, XmlPullParserException {

            try (InputStreamReader reader = new InputStreamReader(is)) {
                table.clear();
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(reader);
                XmlUtils.beginDocument(parser, TAG_OVERLAYS);
                int version = XmlUtils.readIntAttribute(parser, ATTR_VERSION);
                if (version != CURRENT_VERSION) {
                    upgrade(version);
                }
                int depth = parser.getDepth();

                while (XmlUtils.nextElementWithin(parser, depth)) {
                    switch (parser.getName()) {
                        case TAG_ITEM:
                            final SettingsItem item = restoreRow(parser, depth + 1);
                            table.add(item);
                            break;
                    }
                }
            }
        }

        private static void upgrade(int oldVersion) throws XmlPullParserException {
            switch (oldVersion) {
                case 0:
                case 1:
                case 2:
                    // Throw an exception which will cause the overlay file to be ignored
                    // and overwritten.
                    throw new XmlPullParserException("old version " + oldVersion + "; ignoring");
                default:
                    throw new XmlPullParserException("unrecognized version " + oldVersion);
            }
        }

        private static SettingsItem restoreRow(@NonNull final XmlPullParser parser, final int depth)
                throws IOException {
            final String packageName = XmlUtils.readStringAttribute(parser, ATTR_PACKAGE_NAME);
            final int userId = XmlUtils.readIntAttribute(parser, ATTR_USER_ID);
            final String targetPackageName = XmlUtils.readStringAttribute(parser,
                    ATTR_TARGET_PACKAGE_NAME);
            final String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_BASE_CODE_PATH);
            final int state = XmlUtils.readIntAttribute(parser, ATTR_STATE);
            final boolean isEnabled = XmlUtils.readBooleanAttribute(parser, ATTR_IS_ENABLED);
            final boolean isStatic = XmlUtils.readBooleanAttribute(parser, ATTR_IS_STATIC);
            final int priority = XmlUtils.readIntAttribute(parser, ATTR_PRIORITY);
            final boolean isUpgrading = XmlUtils.readBooleanAttribute(parser, ATTR_IS_UPGRADING);

            return new SettingsItem(packageName, userId, targetPackageName, baseCodePath, state,
                    isEnabled, isStatic, priority, isUpgrading);
        }

        public static void persist(@NonNull final ArrayList<SettingsItem> table,
                @NonNull final OutputStream os) throws IOException, XmlPullParserException {
            final FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(os, "utf-8");
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xml.startTag(null, TAG_OVERLAYS);
            XmlUtils.writeIntAttribute(xml, ATTR_VERSION, CURRENT_VERSION);

            final int N = table.size();
            for (int i = 0; i < N; i++) {
                final SettingsItem item = table.get(i);
                persistRow(xml, item);
            }
            xml.endTag(null, TAG_OVERLAYS);
            xml.endDocument();
        }

        private static void persistRow(@NonNull final FastXmlSerializer xml,
                @NonNull final SettingsItem item) throws IOException {
            xml.startTag(null, TAG_ITEM);
            XmlUtils.writeStringAttribute(xml, ATTR_PACKAGE_NAME, item.mPackageName);
            XmlUtils.writeIntAttribute(xml, ATTR_USER_ID, item.mUserId);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_PACKAGE_NAME, item.mTargetPackageName);
            XmlUtils.writeStringAttribute(xml, ATTR_BASE_CODE_PATH, item.mBaseCodePath);
            XmlUtils.writeIntAttribute(xml, ATTR_STATE, item.mState);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_ENABLED, item.mIsEnabled);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_STATIC, item.mIsStatic);
            XmlUtils.writeIntAttribute(xml, ATTR_PRIORITY, item.mPriority);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_UPGRADING, item.mIsUpgrading);
            xml.endTag(null, TAG_ITEM);
        }
    }

    private static final class SettingsItem {
        private final int mUserId;
        private final String mPackageName;
        private final String mTargetPackageName;
        private String mBaseCodePath;
        private int mState;
        private boolean mIsEnabled;
        private OverlayInfo mCache;
        private boolean mIsStatic;
        private int mPriority;
        private boolean mIsUpgrading;

        SettingsItem(@NonNull final String packageName, final int userId,
                @NonNull final String targetPackageName, @NonNull final String baseCodePath,
                final int state, final boolean isEnabled, final boolean isStatic,
                final int priority, final boolean isUpgrading) {
            mPackageName = packageName;
            mUserId = userId;
            mTargetPackageName = targetPackageName;
            mBaseCodePath = baseCodePath;
            mState = state;
            mIsEnabled = isEnabled;
            mCache = null;
            mIsStatic = isStatic;
            mPriority = priority;
            mIsUpgrading = isUpgrading;
        }

        SettingsItem(@NonNull final String packageName, final int userId,
                @NonNull final String targetPackageName, @NonNull final String baseCodePath,
                final boolean isStatic, final int priority) {
            this(packageName, userId, targetPackageName, baseCodePath, OverlayInfo.STATE_UNKNOWN,
                    false, isStatic, priority, false);
        }

        private String getTargetPackageName() {
            return mTargetPackageName;
        }

        private int getUserId() {
            return mUserId;
        }

        private String getBaseCodePath() {
            return mBaseCodePath;
        }

        private boolean setBaseCodePath(@NonNull final String path) {
            if (!mBaseCodePath.equals(path)) {
                mBaseCodePath = path;
                invalidateCache();
                return true;
            }
            return false;
        }

        private int getState() {
            return mState;
        }

        private boolean setState(final int state) {
            if (mState != state) {
                mState = state;
                invalidateCache();
                return true;
            }
            return false;
        }

        private boolean isEnabled() {
            return mIsEnabled;
        }

        private boolean setEnabled(final boolean enable) {
            if (mIsEnabled != enable) {
                mIsEnabled = enable;
                invalidateCache();
                return true;
            }
            return false;
        }

        private boolean isUpgrading() {
            return mIsUpgrading;
        }

        private boolean setUpgrading(final boolean upgrading) {
            if (mIsUpgrading != upgrading) {
                mIsUpgrading = upgrading;
                invalidateCache();
                return true;
            }
            return false;
        }

        private OverlayInfo getOverlayInfo() {
            if (mIsUpgrading) {
                return null;
            }

            if (mCache == null) {
                mCache = new OverlayInfo(mPackageName, mTargetPackageName, mBaseCodePath, mState,
                        mUserId);
            }
            return mCache;
        }

        private void invalidateCache() {
            mCache = null;
        }

        private boolean isStatic() {
            return mIsStatic;
        }

        private int getPriority() {
            return mPriority;
        }
    }

    private int select(@NonNull final String packageName, final int userId) {
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            final SettingsItem item = mItems.get(i);
            if (item.mUserId == userId && item.mPackageName.equals(packageName)) {
                return i;
            }
        }
        return -1;
    }

    private Stream<SettingsItem> selectWhereUser(final int userId) {
        return mItems.stream().filter(item -> item.mUserId == userId);
    }

    private Stream<SettingsItem> selectWhereTarget(@NonNull final String targetPackageName,
            final int userId) {
        return selectWhereUser(userId)
                .filter(item -> item.getTargetPackageName().equals(targetPackageName));
    }

    static final class BadKeyException extends RuntimeException {
        BadKeyException(@NonNull final String packageName, final int userId) {
            super("Bad key mPackageName=" + packageName + " mUserId=" + userId);
        }
    }
}
