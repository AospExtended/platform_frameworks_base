/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.DebugUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Per-user information.
 * @hide
 */
public class UserInfo implements Parcelable {

    /** 16 bits for user type */
    public static final int FLAG_MASK_USER_TYPE = 0x0000FFFF;

    /**
     * *************************** NOTE ***************************
     * These flag values CAN NOT CHANGE because they are written
     * directly to storage.
     */

    /**
     * Primary user. Only one user can have this flag set. It identifies the first human user
     * on a device.
     */
    @UnsupportedAppUsage
    public static final int FLAG_PRIMARY = 0x00000001;

    /**
     * User with administrative privileges. Such a user can create and
     * delete users.
     */
    public static final int FLAG_ADMIN   = 0x00000002;

    /**
     * Indicates a guest user that may be transient.
     */
    public static final int FLAG_GUEST   = 0x00000004;

    /**
     * Indicates the user has restrictions in privileges, in addition to those for normal users.
     * Exact meaning TBD. For instance, maybe they can't install apps or administer WiFi access pts.
     */
    public static final int FLAG_RESTRICTED = 0x00000008;

    /**
     * Indicates that this user has gone through its first-time initialization.
     */
    public static final int FLAG_INITIALIZED = 0x00000010;

    /**
     * Indicates that this user is a profile of another user, for example holding a users
     * corporate data.
     */
    public static final int FLAG_MANAGED_PROFILE = 0x00000020;

    /**
     * Indicates that this user is disabled.
     *
     * <p>Note: If an ephemeral user is disabled, it shouldn't be later re-enabled. Ephemeral users
     * are disabled as their removal is in progress to indicate that they shouldn't be re-entered.
     */
    public static final int FLAG_DISABLED = 0x00000040;

    public static final int FLAG_QUIET_MODE = 0x00000080;

    /**
     * Indicates that this user is ephemeral. I.e. the user will be removed after leaving
     * the foreground.
     */
    public static final int FLAG_EPHEMERAL = 0x00000100;

    /**
     * User is for demo purposes only and can be removed at any time.
     */
    public static final int FLAG_DEMO = 0x00000200;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = "FLAG_", value = {
            FLAG_PRIMARY,
            FLAG_ADMIN,
            FLAG_GUEST,
            FLAG_RESTRICTED,
            FLAG_INITIALIZED,
            FLAG_MANAGED_PROFILE,
            FLAG_DISABLED,
            FLAG_QUIET_MODE,
            FLAG_EPHEMERAL,
            FLAG_DEMO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserInfoFlag {
    }

    public static final int NO_PROFILE_GROUP_ID = UserHandle.USER_NULL;

    @UnsupportedAppUsage
    public int id;
    @UnsupportedAppUsage
    public int serialNumber;
    @UnsupportedAppUsage
    public String name;
    @UnsupportedAppUsage
    public String iconPath;
    @UnsupportedAppUsage
    public int flags;
    @UnsupportedAppUsage
    public long creationTime;
    @UnsupportedAppUsage
    public long lastLoggedInTime;
    public String lastLoggedInFingerprint;
    /**
     * If this user is a parent user, it would be its own user id.
     * If this user is a child user, it would be its parent user id.
     * Otherwise, it would be {@link #NO_PROFILE_GROUP_ID}.
     */
    @UnsupportedAppUsage
    public int profileGroupId;
    public int restrictedProfileParentId;
    /** Which profile badge color/label to use. */
    public int profileBadge;

    /** User is only partially created. */
    @UnsupportedAppUsage
    public boolean partial;
    @UnsupportedAppUsage
    public boolean guestToRemove;

    /**
     * This is used to optimize the creation of an user, i.e. OEMs might choose to pre-create a
     * number of users at the first boot, so the actual creation later is faster.
     *
     * <p>A {@code preCreated} user is not a real user yet, so it should not show up on regular
     * user operations (other than user creation per se).
     *
     * <p>Once the pre-created is used to create a "real" user later on, {@code preCreate} is set to
     * {@code false}.
     */
    public boolean preCreated;

    @UnsupportedAppUsage
    public UserInfo(int id, String name, int flags) {
        this(id, name, null, flags);
    }

    @UnsupportedAppUsage
    public UserInfo(int id, String name, String iconPath, int flags) {
        this.id = id;
        this.name = name;
        this.flags = flags;
        this.iconPath = iconPath;
        this.profileGroupId = NO_PROFILE_GROUP_ID;
        this.restrictedProfileParentId = NO_PROFILE_GROUP_ID;
    }

    @UnsupportedAppUsage
    public boolean isPrimary() {
        return (flags & FLAG_PRIMARY) == FLAG_PRIMARY;
    }

    @UnsupportedAppUsage
    public boolean isAdmin() {
        return (flags & FLAG_ADMIN) == FLAG_ADMIN;
    }

    @UnsupportedAppUsage
    public boolean isGuest() {
        return isGuest(flags);
    }

    /**
     * Checks if the flag denotes a guest user.
     */
    public static boolean isGuest(@UserInfoFlag int flags) {
        return (flags & FLAG_GUEST) == FLAG_GUEST;
    }

    @UnsupportedAppUsage
    public boolean isRestricted() {
        return (flags & FLAG_RESTRICTED) == FLAG_RESTRICTED;
    }

    @UnsupportedAppUsage
    public boolean isManagedProfile() {
        return isManagedProfile(flags);
    }

    /**
     * Checks if the flag denotes a managed profile.
     */
    public static boolean isManagedProfile(@UserInfoFlag int flags) {
        return (flags & FLAG_MANAGED_PROFILE) == FLAG_MANAGED_PROFILE;
    }

    @UnsupportedAppUsage
    public boolean isEnabled() {
        return (flags & FLAG_DISABLED) != FLAG_DISABLED;
    }

    public boolean isQuietModeEnabled() {
        return (flags & FLAG_QUIET_MODE) == FLAG_QUIET_MODE;
    }

    public boolean isEphemeral() {
        return (flags & FLAG_EPHEMERAL) == FLAG_EPHEMERAL;
    }

    public boolean isInitialized() {
        return (flags & FLAG_INITIALIZED) == FLAG_INITIALIZED;
    }

    public boolean isDemo() {
        return (flags & FLAG_DEMO) == FLAG_DEMO;
    }

    /**
     * Returns true if the user is a split system user.
     * <p>If {@link UserManager#isSplitSystemUser split system user mode} is not enabled,
     * the method always returns false.
     */
    public boolean isSystemOnly() {
        return isSystemOnly(id);
    }

    /**
     * Returns true if the given user is a split system user.
     * <p>If {@link UserManager#isSplitSystemUser split system user mode} is not enabled,
     * the method always returns false.
     */
    public static boolean isSystemOnly(int userId) {
        return userId == UserHandle.USER_SYSTEM && UserManager.isSplitSystemUser();
    }

    /**
     * @return true if this user can be switched to.
     **/
    public boolean supportsSwitchTo() {
        if (isEphemeral() && !isEnabled()) {
            // Don't support switching to an ephemeral user with removal in progress.
            return false;
        }
        return !isManagedProfile();
    }

    /**
     * @return true if this user can be switched to by end user through UI.
     */
    public boolean supportsSwitchToByUser() {
        // Hide the system user when it does not represent a human user.
        boolean hideSystemUser = UserManager.isSplitSystemUser();
        return (!hideSystemUser || id != UserHandle.USER_SYSTEM) && supportsSwitchTo();
    }

    /* @hide */
    public boolean canHaveProfile() {
        if (isManagedProfile() || isGuest() || isRestricted()) {
            return false;
        }
        if (UserManager.isSplitSystemUser()) {
            return id != UserHandle.USER_SYSTEM;
        } else {
            return id == UserHandle.USER_SYSTEM;
        }
    }

    public UserInfo() {
    }

    public UserInfo(UserInfo orig) {
        name = orig.name;
        iconPath = orig.iconPath;
        id = orig.id;
        flags = orig.flags;
        serialNumber = orig.serialNumber;
        creationTime = orig.creationTime;
        lastLoggedInTime = orig.lastLoggedInTime;
        lastLoggedInFingerprint = orig.lastLoggedInFingerprint;
        partial = orig.partial;
        preCreated = orig.preCreated;
        profileGroupId = orig.profileGroupId;
        restrictedProfileParentId = orig.restrictedProfileParentId;
        guestToRemove = orig.guestToRemove;
        profileBadge = orig.profileBadge;
    }

    @UnsupportedAppUsage
    public UserHandle getUserHandle() {
        return new UserHandle(id);
    }

    @Override
    public String toString() {
        return "UserInfo{" + id + ":" + name + ":" + Integer.toHexString(flags) + "}";
    }

    /** @hide */
    public String toFullString() {
        return "UserInfo[id=" + id
                + ", name=" + name
                + ", flags=" + flagsToString(flags)
                + (preCreated ? " (pre-created)" : "")
                + (partial ? " (partial)" : "")
                + "]";
    }

    /** @hide */
    public static String flagsToString(int flags) {
        return DebugUtils.flagsToString(UserInfo.class, "FLAG_", flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(id);
        dest.writeString(name);
        dest.writeString(iconPath);
        dest.writeInt(flags);
        dest.writeInt(serialNumber);
        dest.writeLong(creationTime);
        dest.writeLong(lastLoggedInTime);
        dest.writeString(lastLoggedInFingerprint);
        dest.writeBoolean(partial);
        dest.writeBoolean(preCreated);
        dest.writeInt(profileGroupId);
        dest.writeBoolean(guestToRemove);
        dest.writeInt(restrictedProfileParentId);
        dest.writeInt(profileBadge);
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<UserInfo> CREATOR
            = new Parcelable.Creator<UserInfo>() {
        public UserInfo createFromParcel(Parcel source) {
            return new UserInfo(source);
        }
        public UserInfo[] newArray(int size) {
            return new UserInfo[size];
        }
    };

    private UserInfo(Parcel source) {
        id = source.readInt();
        name = source.readString();
        iconPath = source.readString();
        flags = source.readInt();
        serialNumber = source.readInt();
        creationTime = source.readLong();
        lastLoggedInTime = source.readLong();
        lastLoggedInFingerprint = source.readString();
        partial = source.readBoolean();
        preCreated = source.readBoolean();
        profileGroupId = source.readInt();
        guestToRemove = source.readBoolean();
        restrictedProfileParentId = source.readInt();
        profileBadge = source.readInt();
    }
}
