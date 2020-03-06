/*
**
** Copyright 2012, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.os;

import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.content.pm.UserInfo;
import android.content.IntentSender;
import android.content.RestrictionEntry;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

/**
 *  {@hide}
 */
interface IUserManager {

    /*
     * DO NOT MOVE - UserManager.h depends on the ordering of this function.
     */
    int getCredentialOwnerProfile(int userHandle);
    int getProfileParentId(int userHandle);
    /*
     * END OF DO NOT MOVE
     */

    UserInfo createUser(in String name, int flags);
    UserInfo preCreateUser(int flags);
    UserInfo createProfileForUser(in String name, int flags, int userHandle,
            in String[] disallowedPackages);
    UserInfo createRestrictedProfile(String name, int parentUserHandle);
    void setUserEnabled(int userHandle);
    void setUserAdmin(int userId);
    void evictCredentialEncryptionKey(int userHandle);
    boolean removeUser(int userHandle);
    boolean removeUserEvenWhenDisallowed(int userHandle);
    void setUserName(int userHandle, String name);
    void setUserIcon(int userHandle, in Bitmap icon);
    ParcelFileDescriptor getUserIcon(int userHandle);
    UserInfo getPrimaryUser();
    List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated);
    List<UserInfo> getProfiles(int userHandle, boolean enabledOnly);
    int[] getProfileIds(int userId, boolean enabledOnly);
    boolean canAddMoreManagedProfiles(int userHandle, boolean allowedToRemoveOne);
    UserInfo getProfileParent(int userHandle);
    boolean isSameProfileGroup(int userHandle, int otherUserHandle);
    @UnsupportedAppUsage
    UserInfo getUserInfo(int userHandle);
    String getUserAccount(int userHandle);
    void setUserAccount(int userHandle, String accountName);
    long getUserCreationTime(int userHandle);
    boolean isRestricted();
    boolean canHaveRestrictedProfile(int userHandle);
    int getUserSerialNumber(int userHandle);
    int getUserHandle(int userSerialNumber);
    int getUserRestrictionSource(String restrictionKey, int userHandle);
    List<UserManager.EnforcingUser> getUserRestrictionSources(String restrictionKey, int userHandle);
    Bundle getUserRestrictions(int userHandle);
    boolean hasBaseUserRestriction(String restrictionKey, int userHandle);
    boolean hasUserRestriction(in String restrictionKey, int userHandle);
    boolean hasUserRestrictionOnAnyUser(in String restrictionKey);
    void setUserRestriction(String key, boolean value, int userHandle);
    void setApplicationRestrictions(in String packageName, in Bundle restrictions,
            int userHandle);
    Bundle getApplicationRestrictions(in String packageName);
    Bundle getApplicationRestrictionsForUser(in String packageName, int userHandle);
    void setDefaultGuestRestrictions(in Bundle restrictions);
    Bundle getDefaultGuestRestrictions();
    boolean markGuestForDeletion(int userHandle);
    boolean isQuietModeEnabled(int userHandle);
    void setSeedAccountData(int userHandle, in String accountName,
            in String accountType, in PersistableBundle accountOptions, boolean persist);
    String getSeedAccountName();
    String getSeedAccountType();
    PersistableBundle getSeedAccountOptions();
    void clearSeedAccountData();
    boolean someUserHasSeedAccount(in String accountName, in String accountType);
    boolean isManagedProfile(int userId);
    boolean isDemoUser(int userId);
    boolean isPreCreated(int userId);
    UserInfo createProfileForUserEvenWhenDisallowed(in String name, int flags, int userHandle,
            in String[] disallowedPackages);
    boolean isUserUnlockingOrUnlocked(int userId);
    int getManagedProfileBadge(int userId);
    boolean isUserUnlocked(int userId);
    boolean isUserRunning(int userId);
    boolean isUserNameSet(int userHandle);
    boolean hasRestrictedProfiles();
    boolean requestQuietModeEnabled(String callingPackage, boolean enableQuietMode, int userHandle, in IntentSender target);
    String getUserName();
    long getUserStartRealtime();
    long getUserUnlockRealtime();
}
