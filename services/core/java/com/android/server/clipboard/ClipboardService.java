/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.clipboard;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.SystemService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.List;

// The following class is Android Emulator specific. It is used to read and
// write contents of the host system's clipboard.
class HostClipboardMonitor implements Runnable {
    public interface HostClipboardCallback {
        void onHostClipboardUpdated(String contents);
    }

    private RandomAccessFile mPipe = null;
    private HostClipboardCallback mHostClipboardCallback;
    private static final String PIPE_NAME = "pipe:clipboard";
    private static final String PIPE_DEVICE = "/dev/qemu_pipe";

    private void openPipe() {
        try {
            // String.getBytes doesn't include the null terminator,
            // but the QEMU pipe device requires the pipe service name
            // to be null-terminated.
            byte[] b = new byte[PIPE_NAME.length() + 1];
            b[PIPE_NAME.length()] = 0;
            System.arraycopy(
                PIPE_NAME.getBytes(),
                0,
                b,
                0,
                PIPE_NAME.length());
            mPipe = new RandomAccessFile(PIPE_DEVICE, "rw");
            mPipe.write(b);
        } catch (IOException e) {
            try {
                if (mPipe != null) mPipe.close();
            } catch (IOException ee) {}
            mPipe = null;
        }
    }

    public HostClipboardMonitor(HostClipboardCallback cb) {
        mHostClipboardCallback = cb;
    }

    @Override
    public void run() {
        while(!Thread.interrupted()) {
            try {
                // There's no guarantee that QEMU pipes will be ready at the moment
                // this method is invoked. We simply try to get the pipe open and
                // retry on failure indefinitely.
                while (mPipe == null) {
                    openPipe();
                    Thread.sleep(100);
                }
                int size = mPipe.readInt();
                size = Integer.reverseBytes(size);
                byte[] receivedData = new byte[size];
                mPipe.readFully(receivedData);
                mHostClipboardCallback.onHostClipboardUpdated(
                    new String(receivedData));
            } catch (IOException e) {
                try {
                    mPipe.close();
                } catch (IOException ee) {}
                mPipe = null;
            } catch (InterruptedException e) {}
        }
    }

    public void setHostClipboard(String content) {
        try {
            if (mPipe != null) {
                mPipe.writeInt(Integer.reverseBytes(content.getBytes().length));
                mPipe.write(content.getBytes());
            }
        } catch(IOException e) {
            Slog.e("HostClipboardMonitor",
                   "Failed to set host clipboard " + e.getMessage());
        }
    }
}

/**
 * Implementation of the clipboard for copy and paste.
 */
public class ClipboardService extends SystemService {

    private static final String TAG = "ClipboardService";
    private static final boolean IS_EMULATOR =
        SystemProperties.getBoolean("ro.kernel.qemu", false);

    private final IActivityManager mAm;
    private final IUserManager mUm;
    private final PackageManager mPm;
    private final AppOpsManager mAppOps;
    private final IBinder mPermissionOwner;
    private HostClipboardMonitor mHostClipboardMonitor = null;
    private Thread mHostMonitorThread = null;

    private final SparseArray<PerUserClipboard> mClipboards = new SparseArray<>();

    /* AppOps check variants for the clipboardAccessAllowed method */
    private static final int APPOP_NOTE = 1;    /** Call AppOps.noteOp method */
    private static final int APPOP_CHECK = 2;   /** Call AppOps.checkOp method */
    private static final int APPOP_NOTHROW = 3; /** Call AppOps.checkOpNoThrow method */

    /**
     * Instantiates the clipboard.
     */
    public ClipboardService(Context context) {
        super(context);

        mAm = ActivityManager.getService();
        mPm = getContext().getPackageManager();
        mUm = (IUserManager) ServiceManager.getService(Context.USER_SERVICE);
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        IBinder permOwner = null;
        try {
            permOwner = mAm.newUriPermissionOwner("clipboard");
        } catch (RemoteException e) {
            Slog.w("clipboard", "AM dead", e);
        }
        mPermissionOwner = permOwner;
        if (IS_EMULATOR) {
            mHostClipboardMonitor = new HostClipboardMonitor(
                new HostClipboardMonitor.HostClipboardCallback() {
                    @Override
                    public void onHostClipboardUpdated(String contents){
                        ClipData clip =
                            new ClipData("host clipboard",
                                         new String[]{"text/plain"},
                                         new ClipData.Item(contents));
                        synchronized(mClipboards) {
                            setPrimaryClipInternal(getClipboard(0), clip,
                                    android.os.Process.SYSTEM_UID);
                        }
                    }
                });
            mHostMonitorThread = new Thread(mHostClipboardMonitor);
            mHostMonitorThread.start();
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.CLIPBOARD_SERVICE, new ClipboardImpl());
    }

    @Override
    public void onCleanupUser(int userId) {
        synchronized (mClipboards) {
            mClipboards.remove(userId);
        }
    }

    private class ListenerInfo {
        final int mUid;
        final String mPackageName;
        ListenerInfo(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }
    }

    private class PerUserClipboard {
        final int userId;

        final RemoteCallbackList<IOnPrimaryClipChangedListener> primaryClipListeners
                = new RemoteCallbackList<IOnPrimaryClipChangedListener>();

        /** Current primary clip. */
        ClipData primaryClip;
        /** UID that set {@link #primaryClip}. */
        int primaryClipUid = android.os.Process.NOBODY_UID;

        final HashSet<String> activePermissionOwners
                = new HashSet<String>();

        PerUserClipboard(int userId) {
            this.userId = userId;
        }
    }

    private class ClipboardImpl extends IClipboard.Stub {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                if (!(e instanceof SecurityException)) {
                    Slog.wtf("clipboard", "Exception: ", e);
                }
                throw e;
            }

        }

        @Override
        public void setPrimaryClip(ClipData clip, String callingPackage) {
            synchronized (this) {
                if (clip == null || clip.getItemCount() <= 0) {
                    throw new IllegalArgumentException("No items");
                }
                final int callingUid = Binder.getCallingUid();
                if (!clipboardAccessAllowed(AppOpsManager.OP_WRITE_CLIPBOARD, callingPackage,
                            callingUid, APPOP_NOTE)) {
                    return;
                }
                checkDataOwnerLocked(clip, callingUid);
                setPrimaryClipInternal(clip, callingUid);
            }
        }

        @Override
        public void clearPrimaryClip(String callingPackage) {
            synchronized (this) {
                final int callingUid = Binder.getCallingUid();
                if (!clipboardAccessAllowed(AppOpsManager.OP_WRITE_CLIPBOARD, callingPackage,
                        callingUid, APPOP_NOTHROW)) {
                    return;
                }
                setPrimaryClipInternal(null, callingUid);
            }
        }

        @Override
        public ClipData getPrimaryClip(String pkg) {
            synchronized (this) {
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, pkg,
                            Binder.getCallingUid(), APPOP_NOTE) || isDeviceLocked()) {
                    return null;
                }
                addActiveOwnerLocked(Binder.getCallingUid(), pkg);
                return getClipboard().primaryClip;
            }
        }

        @Override
        public ClipDescription getPrimaryClipDescription(String callingPackage) {
            synchronized (this) {
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                            Binder.getCallingUid(), APPOP_CHECK) || isDeviceLocked()) {
                    return null;
                }
                PerUserClipboard clipboard = getClipboard();
                return clipboard.primaryClip != null ? clipboard.primaryClip.getDescription() : null;
            }
        }

        @Override
        public boolean hasPrimaryClip(String callingPackage) {
            synchronized (this) {
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                            Binder.getCallingUid(), APPOP_CHECK) || isDeviceLocked()) {
                    return false;
                }
                return getClipboard().primaryClip != null;
            }
        }

        @Override
        public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
                String callingPackage) {
            synchronized (this) {
                getClipboard().primaryClipListeners.register(listener,
                        new ListenerInfo(Binder.getCallingUid(), callingPackage));
            }
        }

        @Override
        public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
            synchronized (this) {
                getClipboard().primaryClipListeners.unregister(listener);
            }
        }

        @Override
        public boolean hasClipboardText(String callingPackage) {
            synchronized (this) {
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                            Binder.getCallingUid(), APPOP_CHECK) || isDeviceLocked()) {
                    return false;
                }
                PerUserClipboard clipboard = getClipboard();
                if (clipboard.primaryClip != null) {
                    CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
                    return text != null && text.length() > 0;
                }
                return false;
            }
        }
    };

    private PerUserClipboard getClipboard() {
        return getClipboard(UserHandle.getCallingUserId());
    }

    private PerUserClipboard getClipboard(int userId) {
        synchronized (mClipboards) {
            PerUserClipboard puc = mClipboards.get(userId);
            if (puc == null) {
                puc = new PerUserClipboard(userId);
                mClipboards.put(userId, puc);
            }
            return puc;
        }
    }

    List<UserInfo> getRelatedProfiles(int userId) {
        final List<UserInfo> related;
        final long origId = Binder.clearCallingIdentity();
        try {
            related = mUm.getProfiles(userId, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager: " + e);
            return null;
        } finally{
            Binder.restoreCallingIdentity(origId);
        }
        return related;
    }

    /** Check if the user has the given restriction set. Default to true if error occured during
     * calling UserManager, so it fails safe.
     */
    private boolean hasRestriction(String restriction, int userId) {
        try {
            return mUm.hasUserRestriction(restriction, userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager.getUserRestrictions: ", e);
            // Fails safe
            return true;
        }
    }

    void setPrimaryClipInternal(@Nullable ClipData clip, int callingUid) {
        // Push clipboard to host, if any
        if (mHostClipboardMonitor != null) {
            if (clip == null) {
                // Someone really wants the clipboard cleared, so push empty
                mHostClipboardMonitor.setHostClipboard("");
            } else if (clip.getItemCount() > 0) {
                final CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    mHostClipboardMonitor.setHostClipboard(text.toString());
                }
            }
        }

        // Update this user
        final int userId = UserHandle.getUserId(callingUid);
        setPrimaryClipInternal(getClipboard(userId), clip, callingUid);

        // Update related users
        List<UserInfo> related = getRelatedProfiles(userId);
        if (related != null) {
            int size = related.size();
            if (size > 1) { // Related profiles list include the current profile.
                final boolean canCopy = !hasRestriction(
                        UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, userId);
                // Copy clip data to related users if allowed. If disallowed, then remove
                // primary clip in related users to prevent pasting stale content.
                if (!canCopy) {
                    clip = null;
                } else {
                    // We want to fix the uris of the related user's clip without changing the
                    // uris of the current user's clip.
                    // So, copy the ClipData, and then copy all the items, so that nothing
                    // is shared in memmory.
                    clip = new ClipData(clip);
                    for (int i = clip.getItemCount() - 1; i >= 0; i--) {
                        clip.setItemAt(i, new ClipData.Item(clip.getItemAt(i)));
                    }
                    clip.fixUrisLight(userId);
                }
                for (int i = 0; i < size; i++) {
                    int id = related.get(i).id;
                    if (id != userId) {
                        final boolean canCopyIntoProfile = !hasRestriction(
                                UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, id);
                        if (canCopyIntoProfile) {
                            setPrimaryClipInternal(getClipboard(id), clip, callingUid);
                        }
                    }
                }
            }
        }
    }

    void setPrimaryClipInternal(PerUserClipboard clipboard, @Nullable ClipData clip,
            int callingUid) {
        revokeUris(clipboard);
        clipboard.activePermissionOwners.clear();
        if (clip == null && clipboard.primaryClip == null) {
            return;
        }
        clipboard.primaryClip = clip;
        if (clip != null) {
            clipboard.primaryClipUid = callingUid;
        } else {
            clipboard.primaryClipUid = android.os.Process.NOBODY_UID;
        }
        if (clip != null) {
            final ClipDescription description = clip.getDescription();
            if (description != null) {
                description.setTimestamp(System.currentTimeMillis());
            }
        }
        final long ident = Binder.clearCallingIdentity();
        final int n = clipboard.primaryClipListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    ListenerInfo li = (ListenerInfo)
                            clipboard.primaryClipListeners.getBroadcastCookie(i);

                    if (clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, li.mPackageName,
                                li.mUid, APPOP_NOTHROW)) {
                        clipboard.primaryClipListeners.getBroadcastItem(i)
                                .dispatchPrimaryClipChanged();
                    }
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
        } finally {
            clipboard.primaryClipListeners.finishBroadcast();
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isDeviceLocked() {
        int callingUserId = UserHandle.getCallingUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            final KeyguardManager keyguardManager = getContext().getSystemService(
                    KeyguardManager.class);
            return keyguardManager != null && keyguardManager.isDeviceLocked(callingUserId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private final void checkUriOwnerLocked(Uri uri, int sourceUid) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException if caller can't grant
            mAm.checkGrantUriPermission(sourceUid, null,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void checkItemOwnerLocked(ClipData.Item item, int uid) {
        if (item.getUri() != null) {
            checkUriOwnerLocked(item.getUri(), uid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            checkUriOwnerLocked(intent.getData(), uid);
        }
    }

    private final void checkDataOwnerLocked(ClipData data, int uid) {
        final int N = data.getItemCount();
        for (int i=0; i<N; i++) {
            checkItemOwnerLocked(data.getItemAt(i), uid);
        }
    }

    private final void grantUriLocked(Uri uri, int sourceUid, String targetPkg,
            int targetUserId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            mAm.grantUriPermissionFromOwner(mPermissionOwner, sourceUid, targetPkg,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)),
                    targetUserId);
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void grantItemLocked(ClipData.Item item, int sourceUid, String targetPkg,
            int targetUserId) {
        if (item.getUri() != null) {
            grantUriLocked(item.getUri(), sourceUid, targetPkg, targetUserId);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriLocked(intent.getData(), sourceUid, targetPkg, targetUserId);
        }
    }

    private final void addActiveOwnerLocked(int uid, String pkg) {
        final IPackageManager pm = AppGlobals.getPackageManager();
        final int targetUserHandle = UserHandle.getCallingUserId();
        final long oldIdentity = Binder.clearCallingIdentity();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0, targetUserHandle);
            if (pi == null) {
                throw new IllegalArgumentException("Unknown package " + pkg);
            }
            if (!UserHandle.isSameApp(pi.applicationInfo.uid, uid)) {
                throw new SecurityException("Calling uid " + uid
                        + " does not own package " + pkg);
            }
        } catch (RemoteException e) {
            // Can't happen; the package manager is in the same process
        } finally {
            Binder.restoreCallingIdentity(oldIdentity);
        }
        PerUserClipboard clipboard = getClipboard();
        if (clipboard.primaryClip != null && !clipboard.activePermissionOwners.contains(pkg)) {
            final int N = clipboard.primaryClip.getItemCount();
            for (int i=0; i<N; i++) {
                grantItemLocked(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid, pkg,
                        UserHandle.getUserId(uid));
            }
            clipboard.activePermissionOwners.add(pkg);
        }
    }

    private final void revokeUriLocked(Uri uri, int sourceUid) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            mAm.revokeUriPermissionFromOwner(mPermissionOwner,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void revokeItemLocked(ClipData.Item item, int sourceUid) {
        if (item.getUri() != null) {
            revokeUriLocked(item.getUri(), sourceUid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            revokeUriLocked(intent.getData(), sourceUid);
        }
    }

    private final void revokeUris(PerUserClipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        final int N = clipboard.primaryClip.getItemCount();
        for (int i=0; i<N; i++) {
            revokeItemLocked(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid);
        }
    }

    private boolean clipboardAccessAllowed(int op, String callingPackage,
            int callingUid, int appOpMethod) {
        int appOpResult;

        // Check the AppOp depending on the specified method.
        switch (appOpMethod) {
            case APPOP_NOTE:
                appOpResult = mAppOps.noteOp(op, callingUid, callingPackage);
                break;
            case APPOP_NOTHROW:
                appOpResult = mAppOps.checkOpNoThrow(op, callingUid, callingPackage);
                break;
            default:
                appOpResult = mAppOps.checkOp(op, callingUid, callingPackage);
                break;
        }

        if (appOpResult != AppOpsManager.MODE_ALLOWED) {
            return false;
        }
        try {
            // Installed apps can access the clipboard at any time.
            if (!AppGlobals.getPackageManager().isInstantApp(callingPackage,
                        UserHandle.getUserId(callingUid))) {
                return true;
            }
            // Instant apps can only access the clipboard if they are in the foreground.
            return mAm.isAppForeground(callingUid);
        } catch (RemoteException e) {
            Slog.e("clipboard", "Failed to get Instant App status for package " + callingPackage,
                    e);
            return false;
        }
    }
}
