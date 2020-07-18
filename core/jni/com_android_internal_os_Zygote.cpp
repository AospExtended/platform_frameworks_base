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

/*
 * Disable optimization of this file if we are compiling with the address
 * sanitizer.  This is a mitigation for b/122921367 and can be removed once the
 * bug is fixed.
 */
#if __has_feature(address_sanitizer)
#pragma clang optimize off
#endif

#define LOG_TAG "Zygote"
#define ATRACE_TAG ATRACE_TAG_DALVIK

#include <async_safe/log.h>

// sys/mount.h has to come before linux/fs.h due to redefinition of MS_RDONLY, MS_BIND, etc
#include <sys/mount.h>
#include <linux/fs.h>

#include <array>
#include <atomic>
#include <functional>
#include <list>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>

#include <android/fdsan.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <grp.h>
#include <inttypes.h>
#include <link.h>
#include <malloc.h>
#include <mntent.h>
#include <paths.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/capability.h>
#include <sys/cdefs.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/personality.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <bionic_malloc.h>
#include <cutils/ashmem.h>
#include <cutils/fs.h>
#include <cutils/multiuser.h>
#include <private/android_filesystem_config.h>
#include <utils/String8.h>
#include <utils/Trace.h>
#include <selinux/android.h>
#include <seccomp_policy.h>
#include <stats_event_list.h>
#include <processgroup/processgroup.h>
#include <processgroup/sched_policy.h>

#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "fd_utils.h"

#include "nativebridge/native_bridge.h"

namespace {

// TODO (chriswailes): Add a function to initialize native Zygote data.
// TODO (chriswailes): Fix mixed indentation style (2 and 4 spaces).

using namespace std::placeholders;

using android::String8;
using android::base::StringAppendF;
using android::base::StringPrintf;
using android::base::WriteStringToFile;
using android::base::GetBoolProperty;

#define CREATE_ERROR(...) StringPrintf("%s:%d: ", __FILE__, __LINE__). \
                              append(StringPrintf(__VA_ARGS__))

// This type is duplicated in fd_utils.h
typedef const std::function<void(std::string)>& fail_fn_t;

static pid_t gSystemServerPid = 0;

static constexpr const char* kZygoteClassName = "com/android/internal/os/Zygote";
static jclass gZygoteClass;
static jmethodID gCallPostForkSystemServerHooks;
static jmethodID gCallPostForkChildHooks;

static constexpr const char* kZygoteInitClassName = "com/android/internal/os/ZygoteInit";
static jclass gZygoteInitClass;
static jmethodID gCreateSystemServerClassLoader;

static bool gIsSecurityEnforced = true;

/**
 * The maximum number of characters (not including a null terminator) that a
 * process name may contain.
 */
static constexpr size_t MAX_NAME_LENGTH = 15;

/**
 * The prefix string for environmental variables storing socket FDs created by
 * init.
 */

static constexpr std::string_view ANDROID_SOCKET_PREFIX("ANDROID_SOCKET_");

/**
 * The file descriptor for the Zygote socket opened by init.
 */

static int gZygoteSocketFD = -1;

/**
 * The file descriptor for the unspecialized app process (USAP) pool socket opened by init.
 */

static int gUsapPoolSocketFD = -1;

/**
 * The number of USAPs currently in this Zygote's pool.
 */
static std::atomic_uint32_t gUsapPoolCount = 0;

/**
 * Event file descriptor used to communicate reaped USAPs to the
 * ZygoteServer.
 */
static int gUsapPoolEventFD = -1;

/**
 * The maximum value that the gUSAPPoolSizeMax variable may take.  This value
 * is a mirror of ZygoteServer.USAP_POOL_SIZE_MAX_LIMIT
 */
static constexpr int USAP_POOL_SIZE_MAX_LIMIT = 100;

/** The numeric value for the maximum priority a process may possess. */
static constexpr int PROCESS_PRIORITY_MAX = -20;

/** The numeric value for the minimum priority a process may possess. */
static constexpr int PROCESS_PRIORITY_MIN = 19;

/** The numeric value for the normal priority a process should have. */
static constexpr int PROCESS_PRIORITY_DEFAULT = 0;

/**
 * A helper class containing accounting information for USAPs.
 */
class UsapTableEntry {
 public:
  struct EntryStorage {
    int32_t pid;
    int32_t read_pipe_fd;

    bool operator!=(const EntryStorage& other) {
      return pid != other.pid || read_pipe_fd != other.read_pipe_fd;
    }
  };

 private:
  static constexpr EntryStorage INVALID_ENTRY_VALUE = {-1, -1};

  std::atomic<EntryStorage> mStorage;
  static_assert(decltype(mStorage)::is_always_lock_free);

 public:
  constexpr UsapTableEntry() : mStorage(INVALID_ENTRY_VALUE) {}

  /**
   * If the provided PID matches the one stored in this entry, the entry will
   * be invalidated and the associated file descriptor will be closed.  If the
   * PIDs don't match nothing will happen.
   *
   * @param pid The ID of the process who's entry we want to clear.
   * @return True if the entry was cleared by this call; false otherwise
   */
  bool ClearForPID(int32_t pid) {
    EntryStorage storage = mStorage.load();

    if (storage.pid == pid) {
      /*
       * There are three possible outcomes from this compare-and-exchange:
       *   1) It succeeds, in which case we close the FD
       *   2) It fails and the new value is INVALID_ENTRY_VALUE, in which case
       *      the entry has already been cleared.
       *   3) It fails and the new value isn't INVALID_ENTRY_VALUE, in which
       *      case the entry has already been cleared and re-used.
       *
       * In all three cases the goal of the caller has been met, but only in
       * the first case do we need to decrement the pool count.
       */
      if (mStorage.compare_exchange_strong(storage, INVALID_ENTRY_VALUE)) {
        close(storage.read_pipe_fd);
        return true;
      } else {
        return false;
      }

    } else {
      return false;
    }
  }

  void Clear() {
    EntryStorage storage = mStorage.load();

    if (storage != INVALID_ENTRY_VALUE) {
      close(storage.read_pipe_fd);
      mStorage.store(INVALID_ENTRY_VALUE);
    }
  }

  void Invalidate() {
    mStorage.store(INVALID_ENTRY_VALUE);
  }

  /**
   * @return A copy of the data stored in this entry.
   */
  std::optional<EntryStorage> GetValues() {
    EntryStorage storage = mStorage.load();

    if (storage != INVALID_ENTRY_VALUE) {
      return storage;
    } else {
      return std::nullopt;
    }
  }

  /**
   * Sets the entry to the given values if it is currently invalid.
   *
   * @param pid  The process ID for the new entry.
   * @param read_pipe_fd  The read end of the USAP control pipe for this
   * process.
   * @return True if the entry was set; false otherwise.
   */
  bool SetIfInvalid(int32_t pid, int32_t read_pipe_fd) {
    EntryStorage new_value_storage;

    new_value_storage.pid = pid;
    new_value_storage.read_pipe_fd = read_pipe_fd;

    EntryStorage expected = INVALID_ENTRY_VALUE;

    return mStorage.compare_exchange_strong(expected, new_value_storage);
  }
};

/**
 * A table containing information about the USAPs currently in the pool.
 *
 * Multiple threads may be attempting to modify the table, either from the
 * signal handler or from the ZygoteServer poll loop.  Atomic loads/stores in
 * the USAPTableEntry class prevent data races during these concurrent
 * operations.
 */
static std::array<UsapTableEntry, USAP_POOL_SIZE_MAX_LIMIT> gUsapTable;

/**
 * The list of open zygote file descriptors.
 */
static FileDescriptorTable* gOpenFdTable = nullptr;

// Must match values in com.android.internal.os.Zygote.
enum MountExternalKind {
  MOUNT_EXTERNAL_NONE = 0,
  MOUNT_EXTERNAL_DEFAULT = 1,
  MOUNT_EXTERNAL_READ = 2,
  MOUNT_EXTERNAL_WRITE = 3,
  MOUNT_EXTERNAL_LEGACY = 4,
  MOUNT_EXTERNAL_INSTALLER = 5,
  MOUNT_EXTERNAL_FULL = 6,
};

// Must match values in com.android.internal.os.Zygote.
enum RuntimeFlags : uint32_t {
  DEBUG_ENABLE_JDWP = 1,
  PROFILE_FROM_SHELL = 1 << 15,
};

// Forward declaration so we don't have to move the signal handler.
static bool RemoveUsapTableEntry(pid_t usap_pid);

static void RuntimeAbort(JNIEnv* env, int line, const char* msg) {
  std::ostringstream oss;
  oss << __FILE__ << ":" << line << ": " << msg;
  env->FatalError(oss.str().c_str());
}

// This signal handler is for zygote mode, since the zygote must reap its children
static void SigChldHandler(int /*signal_number*/) {
  pid_t pid;
  int status;
  int64_t usaps_removed = 0;

  // It's necessary to save and restore the errno during this function.
  // Since errno is stored per thread, changing it here modifies the errno
  // on the thread on which this signal handler executes. If a signal occurs
  // between a call and an errno check, it's possible to get the errno set
  // here.
  // See b/23572286 for extra information.
  int saved_errno = errno;

  while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
     // Log process-death status that we care about.
    if (WIFEXITED(status)) {
      async_safe_format_log(ANDROID_LOG_INFO, LOG_TAG,
                            "Process %d exited cleanly (%d)", pid, WEXITSTATUS(status));

      // Check to see if the PID is in the USAP pool and remove it if it is.
      if (RemoveUsapTableEntry(pid)) {
        ++usaps_removed;
      }
    } else if (WIFSIGNALED(status)) {
      async_safe_format_log(ANDROID_LOG_INFO, LOG_TAG,
                            "Process %d exited due to signal %d (%s)%s", pid,
                            WTERMSIG(status), strsignal(WTERMSIG(status)),
                            WCOREDUMP(status) ? "; core dumped" : "");

      // If the process exited due to a signal other than SIGTERM, check to see
      // if the PID is in the USAP pool and remove it if it is.  If the process
      // was closed by the Zygote using SIGTERM then the USAP pool entry will
      // have already been removed (see nativeEmptyUsapPool()).
      if (WTERMSIG(status) != SIGTERM && RemoveUsapTableEntry(pid)) {
        ++usaps_removed;
      }
    }

    // If the just-crashed process is the system_server, bring down zygote
    // so that it is restarted by init and system server will be restarted
    // from there.
    if (pid == gSystemServerPid) {
      async_safe_format_log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Exit zygote because system server (pid %d) has terminated", pid);
      kill(getpid(), SIGKILL);
    }
  }

  // Note that we shouldn't consider ECHILD an error because
  // the secondary zygote might have no children left to wait for.
  if (pid < 0 && errno != ECHILD) {
    async_safe_format_log(ANDROID_LOG_WARN, LOG_TAG,
                          "Zygote SIGCHLD error in waitpid: %s", strerror(errno));
  }

  if (usaps_removed > 0) {
    if (TEMP_FAILURE_RETRY(write(gUsapPoolEventFD, &usaps_removed, sizeof(usaps_removed))) == -1) {
      // If this write fails something went terribly wrong.  We will now kill
      // the zygote and let the system bring it back up.
      async_safe_format_log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Zygote failed to write to USAP pool event FD: %s",
                            strerror(errno));
      kill(getpid(), SIGKILL);
    }
  }

  errno = saved_errno;
}

// Configures the SIGCHLD/SIGHUP handlers for the zygote process. This is
// configured very late, because earlier in the runtime we may fork() and
// exec() other processes, and we want to waitpid() for those rather than
// have them be harvested immediately.
//
// Ignore SIGHUP because all processes forked by the zygote are in the same
// process group as the zygote and we don't want to be notified if we become
// an orphaned group and have one or more stopped processes. This is not a
// theoretical concern :
// - we can become an orphaned group if one of our direct descendants forks
//   and is subsequently killed before its children.
// - crash_dump routinely STOPs the process it's tracing.
//
// See issues b/71965619 and b/25567761 for further details.
//
// This ends up being called repeatedly before each fork(), but there's
// no real harm in that.
static void SetSignalHandlers() {
  struct sigaction sig_chld = {};
  sig_chld.sa_handler = SigChldHandler;

  if (sigaction(SIGCHLD, &sig_chld, nullptr) < 0) {
    ALOGW("Error setting SIGCHLD handler: %s", strerror(errno));
  }

  struct sigaction sig_hup = {};
  sig_hup.sa_handler = SIG_IGN;
  if (sigaction(SIGHUP, &sig_hup, nullptr) < 0) {
    ALOGW("Error setting SIGHUP handler: %s", strerror(errno));
  }
}

// Sets the SIGCHLD handler back to default behavior in zygote children.
static void UnsetChldSignalHandler() {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SIG_DFL;

  if (sigaction(SIGCHLD, &sa, nullptr) < 0) {
    ALOGW("Error unsetting SIGCHLD handler: %s", strerror(errno));
  }
}

// Calls POSIX setgroups() using the int[] object as an argument.
// A nullptr argument is tolerated.
static void SetGids(JNIEnv* env, jintArray managed_gids, fail_fn_t fail_fn) {
  if (managed_gids == nullptr) {
    return;
  }

  ScopedIntArrayRO gids(env, managed_gids);
  if (gids.get() == nullptr) {
    fail_fn(CREATE_ERROR("Getting gids int array failed"));
  }

  if (setgroups(gids.size(), reinterpret_cast<const gid_t*>(&gids[0])) == -1) {
    fail_fn(CREATE_ERROR("setgroups failed: %s, gids.size=%zu", strerror(errno), gids.size()));
  }
}

// Sets the resource limits via setrlimit(2) for the values in the
// two-dimensional array of integers that's passed in. The second dimension
// contains a tuple of length 3: (resource, rlim_cur, rlim_max). nullptr is
// treated as an empty array.
static void SetRLimits(JNIEnv* env, jobjectArray managed_rlimits, fail_fn_t fail_fn) {
  if (managed_rlimits == nullptr) {
    return;
  }

  rlimit rlim;
  memset(&rlim, 0, sizeof(rlim));

  for (int i = 0; i < env->GetArrayLength(managed_rlimits); ++i) {
    ScopedLocalRef<jobject>
        managed_rlimit_object(env, env->GetObjectArrayElement(managed_rlimits, i));
    ScopedIntArrayRO rlimit_handle(env, reinterpret_cast<jintArray>(managed_rlimit_object.get()));

    if (rlimit_handle.size() != 3) {
      fail_fn(CREATE_ERROR("rlimits array must have a second dimension of size 3"));
    }

    rlim.rlim_cur = rlimit_handle[1];
    rlim.rlim_max = rlimit_handle[2];

    if (setrlimit(rlimit_handle[0], &rlim) == -1) {
      fail_fn(CREATE_ERROR("setrlimit(%d, {%ld, %ld}) failed",
                           rlimit_handle[0], rlim.rlim_cur, rlim.rlim_max));
    }
  }
}

static void EnableDebugger() {
  // To let a non-privileged gdbserver attach to this
  // process, we must set our dumpable flag.
  if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) == -1) {
    ALOGE("prctl(PR_SET_DUMPABLE) failed");
  }

  // A non-privileged native debugger should be able to attach to the debuggable app, even if Yama
  // is enabled (see kernel/Documentation/security/Yama.txt).
  if (prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0) == -1) {
    // if Yama is off prctl(PR_SET_PTRACER) returns EINVAL - don't log in this
    // case since it's expected behaviour.
    if (errno != EINVAL) {
      ALOGE("prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY) failed");
    }
  }

  // Set the core dump size to zero unless wanted (see also coredump_setup in build/envsetup.sh).
  if (!GetBoolProperty("persist.zygote.core_dump", false)) {
    // Set the soft limit on core dump size to 0 without changing the hard limit.
    rlimit rl;
    if (getrlimit(RLIMIT_CORE, &rl) == -1) {
      ALOGE("getrlimit(RLIMIT_CORE) failed");
    } else {
      rl.rlim_cur = 0;
      if (setrlimit(RLIMIT_CORE, &rl) == -1) {
        ALOGE("setrlimit(RLIMIT_CORE) failed");
      }
    }
  }
}

static void PreApplicationInit() {
  // The child process sets this to indicate it's not the zygote.
  android_mallopt(M_SET_ZYGOTE_CHILD, nullptr, 0);

  // Set the jemalloc decay time to 1.
  mallopt(M_DECAY_TIME, 1);
}

static void SetUpSeccompFilter(uid_t uid, bool is_child_zygote) {
  if (!gIsSecurityEnforced) {
    ALOGI("seccomp disabled by setenforce 0");
    return;
  }

  // Apply system or app filter based on uid.
  if (uid >= AID_APP_START) {
    if (is_child_zygote) {
      set_app_zygote_seccomp_filter();
    } else {
      set_app_seccomp_filter();
    }
  } else {
    set_system_seccomp_filter();
  }
}

static void EnableKeepCapabilities(fail_fn_t fail_fn) {
  if (prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0) == -1) {
    fail_fn(CREATE_ERROR("prctl(PR_SET_KEEPCAPS) failed: %s", strerror(errno)));
  }
}

static void DropCapabilitiesBoundingSet(fail_fn_t fail_fn) {
  for (int i = 0; prctl(PR_CAPBSET_READ, i, 0, 0, 0) >= 0; i++) {;
    if (prctl(PR_CAPBSET_DROP, i, 0, 0, 0) == -1) {
      if (errno == EINVAL) {
        ALOGE("prctl(PR_CAPBSET_DROP) failed with EINVAL. Please verify "
              "your kernel is compiled with file capabilities support");
      } else {
        fail_fn(CREATE_ERROR("prctl(PR_CAPBSET_DROP, %d) failed: %s", i, strerror(errno)));
      }
    }
  }
}

static void SetInheritable(uint64_t inheritable, fail_fn_t fail_fn) {
  __user_cap_header_struct capheader;
  memset(&capheader, 0, sizeof(capheader));
  capheader.version = _LINUX_CAPABILITY_VERSION_3;
  capheader.pid = 0;

  __user_cap_data_struct capdata[2];
  if (capget(&capheader, &capdata[0]) == -1) {
    fail_fn(CREATE_ERROR("capget failed: %s", strerror(errno)));
  }

  capdata[0].inheritable = inheritable;
  capdata[1].inheritable = inheritable >> 32;

  if (capset(&capheader, &capdata[0]) == -1) {
    fail_fn(CREATE_ERROR("capset(inh=%" PRIx64 ") failed: %s", inheritable, strerror(errno)));
  }
}

static void SetCapabilities(uint64_t permitted, uint64_t effective, uint64_t inheritable,
                            fail_fn_t fail_fn) {
  __user_cap_header_struct capheader;
  memset(&capheader, 0, sizeof(capheader));
  capheader.version = _LINUX_CAPABILITY_VERSION_3;
  capheader.pid = 0;

  __user_cap_data_struct capdata[2];
  memset(&capdata, 0, sizeof(capdata));
  capdata[0].effective = effective;
  capdata[1].effective = effective >> 32;
  capdata[0].permitted = permitted;
  capdata[1].permitted = permitted >> 32;
  capdata[0].inheritable = inheritable;
  capdata[1].inheritable = inheritable >> 32;

  if (capset(&capheader, &capdata[0]) == -1) {
    fail_fn(CREATE_ERROR("capset(perm=%" PRIx64 ", eff=%" PRIx64 ", inh=%" PRIx64 ") "
                         "failed: %s", permitted, effective, inheritable, strerror(errno)));
  }
}

static void SetSchedulerPolicy(fail_fn_t fail_fn, bool is_top_app) {
  SchedPolicy policy = is_top_app ? SP_TOP_APP : SP_DEFAULT;

  if (is_top_app && cpusets_enabled()) {
    errno = -set_cpuset_policy(0, policy);
    if (errno != 0) {
      fail_fn(CREATE_ERROR("set_cpuset_policy(0, %d) failed: %s", policy, strerror(errno)));
    }
  }

  errno = -set_sched_policy(0, policy);
  if (errno != 0) {
    fail_fn(CREATE_ERROR("set_sched_policy(0, %d) failed: %s", policy, strerror(errno)));
  }

  // We are going to lose the permission to set scheduler policy during the specialization, so make
  // sure that we don't cache the fd of cgroup path that may cause sepolicy violation by writing
  // value to the cached fd directly when creating new thread.
  DropTaskProfilesResourceCaching();
}

static int UnmountTree(const char* path) {
  ATRACE_CALL();

  size_t path_len = strlen(path);

  FILE* fp = setmntent("/proc/mounts", "r");
  if (fp == nullptr) {
    ALOGE("Error opening /proc/mounts: %s", strerror(errno));
    return -errno;
  }

  // Some volumes can be stacked on each other, so force unmount in
  // reverse order to give us the best chance of success.
  std::list<std::string> to_unmount;
  mntent* mentry;
  while ((mentry = getmntent(fp)) != nullptr) {
    if (strncmp(mentry->mnt_dir, path, path_len) == 0) {
      to_unmount.push_front(std::string(mentry->mnt_dir));
    }
  }
  endmntent(fp);

  for (const auto& path : to_unmount) {
    if (umount2(path.c_str(), MNT_DETACH)) {
      ALOGW("Failed to unmount %s: %s", path.c_str(), strerror(errno));
    }
  }
  return 0;
}

// Create a private mount namespace and bind mount appropriate emulated
// storage for the given user.
static void MountEmulatedStorage(uid_t uid, jint mount_mode,
        bool force_mount_namespace,
        fail_fn_t fail_fn) {
  // See storage config details at http://source.android.com/tech/storage/
  ATRACE_CALL();

  String8 storage_source;
  if (mount_mode == MOUNT_EXTERNAL_DEFAULT) {
    storage_source = "/mnt/runtime/default";
  } else if (mount_mode == MOUNT_EXTERNAL_READ) {
    storage_source = "/mnt/runtime/read";
  } else if (mount_mode == MOUNT_EXTERNAL_WRITE
      || mount_mode == MOUNT_EXTERNAL_LEGACY
      || mount_mode == MOUNT_EXTERNAL_INSTALLER) {
    storage_source = "/mnt/runtime/write";
  } else if (mount_mode == MOUNT_EXTERNAL_FULL) {
    storage_source = "/mnt/runtime/full";
  } else if (mount_mode == MOUNT_EXTERNAL_NONE && !force_mount_namespace) {
    // Sane default of no storage visible
    return;
  }

  // Create a second private mount namespace for our process
  if (unshare(CLONE_NEWNS) == -1) {
    fail_fn(CREATE_ERROR("Failed to unshare(): %s", strerror(errno)));
  }

  // Handle force_mount_namespace with MOUNT_EXTERNAL_NONE.
  if (mount_mode == MOUNT_EXTERNAL_NONE) {
    return;
  }

  if (TEMP_FAILURE_RETRY(mount(storage_source.string(), "/storage", nullptr,
                               MS_BIND | MS_REC | MS_SLAVE, nullptr)) == -1) {
    fail_fn(CREATE_ERROR("Failed to mount %s to /storage: %s",
                         storage_source.string(),
                         strerror(errno)));
  }

  // Mount user-specific symlink helper into place
  userid_t user_id = multiuser_get_user_id(uid);
  const String8 user_source(String8::format("/mnt/user/%d", user_id));
  if (fs_prepare_dir(user_source.string(), 0751, 0, 0) == -1) {
    fail_fn(CREATE_ERROR("fs_prepare_dir failed on %s",
                         user_source.string()));
  }

  if (TEMP_FAILURE_RETRY(mount(user_source.string(), "/storage/self",
                               nullptr, MS_BIND, nullptr)) == -1) {
    fail_fn(CREATE_ERROR("Failed to mount %s to /storage/self: %s",
                         user_source.string(), strerror(errno)));
  }
}

static bool NeedsNoRandomizeWorkaround() {
#if !defined(__arm__)
    return false;
#else
    int major;
    int minor;
    struct utsname uts;
    if (uname(&uts) == -1) {
        return false;
    }

    if (sscanf(uts.release, "%d.%d", &major, &minor) != 2) {
        return false;
    }

    // Kernels before 3.4.* need the workaround.
    return (major < 3) || ((major == 3) && (minor < 4));
#endif
}

// Utility to close down the Zygote socket file descriptors while
// the child is still running as root with Zygote's privileges.  Each
// descriptor (if any) is closed via dup3(), replacing it with a valid
// (open) descriptor to /dev/null.

static void DetachDescriptors(JNIEnv* env,
                              const std::vector<int>& fds_to_close,
                              fail_fn_t fail_fn) {

  if (fds_to_close.size() > 0) {
    android::base::unique_fd devnull_fd(open("/dev/null", O_RDWR | O_CLOEXEC));
    if (devnull_fd == -1) {
      fail_fn(std::string("Failed to open /dev/null: ").append(strerror(errno)));
    }

    for (int fd : fds_to_close) {
      ALOGV("Switching descriptor %d to /dev/null", fd);
      if (dup3(devnull_fd, fd, O_CLOEXEC) == -1) {
        fail_fn(StringPrintf("Failed dup3() on descriptor %d: %s", fd, strerror(errno)));
      }
    }
  }
}

void SetThreadName(const std::string& thread_name) {
  bool hasAt = false;
  bool hasDot = false;

  for (const char str_el : thread_name) {
    if (str_el == '.') {
      hasDot = true;
    } else if (str_el == '@') {
      hasAt = true;
    }
  }

  const char* name_start_ptr = thread_name.c_str();
  if (thread_name.length() >= MAX_NAME_LENGTH && !hasAt && hasDot) {
    name_start_ptr += thread_name.length() - MAX_NAME_LENGTH;
  }

  // pthread_setname_np fails rather than truncating long strings.
  char buf[16];       // MAX_TASK_COMM_LEN=16 is hard-coded into bionic
  strlcpy(buf, name_start_ptr, sizeof(buf) - 1);
  errno = pthread_setname_np(pthread_self(), buf);
  if (errno != 0) {
    ALOGW("Unable to set the name of current thread to '%s': %s", buf, strerror(errno));
  }
  // Update base::logging default tag.
  android::base::SetDefaultTag(buf);
}

/**
 * A failure function used to report fatal errors to the managed runtime.  This
 * function is often curried with the process name information and then passed
 * to called functions.
 *
 * @param env  Managed runtime environment
 * @param process_name  A native representation of the process name
 * @param managed_process_name  A managed representation of the process name
 * @param msg  The error message to be reported
 */
[[noreturn]]
static void ZygoteFailure(JNIEnv* env,
                          const char* process_name,
                          jstring managed_process_name,
                          const std::string& msg) {
  std::unique_ptr<ScopedUtfChars> scoped_managed_process_name_ptr = nullptr;
  if (managed_process_name != nullptr) {
    scoped_managed_process_name_ptr.reset(new ScopedUtfChars(env, managed_process_name));
    if (scoped_managed_process_name_ptr->c_str() != nullptr) {
      process_name = scoped_managed_process_name_ptr->c_str();
    }
  }

  const std::string& error_msg =
      (process_name == nullptr) ? msg : StringPrintf("(%s) %s", process_name, msg.c_str());

  env->FatalError(error_msg.c_str());
  __builtin_unreachable();
}

/**
 * A helper method for converting managed strings to native strings.  A fatal
 * error is generated if a problem is encountered in extracting a non-null
 * string.
 *
 * @param env  Managed runtime environment
 * @param process_name  A native representation of the process name
 * @param managed_process_name  A managed representation of the process name
 * @param managed_string  The managed string to extract
 *
 * @return An empty option if the managed string is null.  A optional-wrapped
 * string otherwise.
 */
static std::optional<std::string> ExtractJString(JNIEnv* env,
                                                 const char* process_name,
                                                 jstring managed_process_name,
                                                 jstring managed_string) {
  if (managed_string == nullptr) {
    return std::nullopt;
  } else {
    ScopedUtfChars scoped_string_chars(env, managed_string);

    if (scoped_string_chars.c_str() != nullptr) {
      return std::optional<std::string>(scoped_string_chars.c_str());
    } else {
      ZygoteFailure(env, process_name, managed_process_name, "Failed to extract JString.");
    }
  }
}

/**
 * A helper method for converting managed string arrays to native vectors.  A
 * fatal error is generated if a problem is encountered in extracting a non-null array.
 *
 * @param env  Managed runtime environment
 * @param process_name  A native representation of the process name
 * @param managed_process_name  A managed representation of the process name
 * @param managed_array  The managed integer array to extract
 *
 * @return An empty option if the managed array is null.  A optional-wrapped
 * vector otherwise.
 */
static std::optional<std::vector<int>> ExtractJIntArray(JNIEnv* env,
                                                        const char* process_name,
                                                        jstring managed_process_name,
                                                        jintArray managed_array) {
  if (managed_array == nullptr) {
    return std::nullopt;
  } else {
    ScopedIntArrayRO managed_array_handle(env, managed_array);

    if (managed_array_handle.get() != nullptr) {
      std::vector<int> native_array;
      native_array.reserve(managed_array_handle.size());

      for (size_t array_index = 0; array_index < managed_array_handle.size(); ++array_index) {
        native_array.push_back(managed_array_handle[array_index]);
      }

      return std::move(native_array);

    } else {
      ZygoteFailure(env, process_name, managed_process_name, "Failed to extract JIntArray.");
    }
  }
}

/**
 * A utility function for blocking signals.
 *
 * @param signum  Signal number to block
 * @param fail_fn  Fatal error reporting function
 *
 * @see ZygoteFailure
 */
static void BlockSignal(int signum, fail_fn_t fail_fn) {
  sigset_t sigs;
  sigemptyset(&sigs);
  sigaddset(&sigs, signum);

  if (sigprocmask(SIG_BLOCK, &sigs, nullptr) == -1) {
    fail_fn(CREATE_ERROR("Failed to block signal %s: %s", strsignal(signum), strerror(errno)));
  }
}


/**
 * A utility function for unblocking signals.
 *
 * @param signum  Signal number to unblock
 * @param fail_fn  Fatal error reporting function
 *
 * @see ZygoteFailure
 */
static void UnblockSignal(int signum, fail_fn_t fail_fn) {
  sigset_t sigs;
  sigemptyset(&sigs);
  sigaddset(&sigs, signum);

  if (sigprocmask(SIG_UNBLOCK, &sigs, nullptr) == -1) {
    fail_fn(CREATE_ERROR("Failed to un-block signal %s: %s", strsignal(signum), strerror(errno)));
  }
}

static void ClearUsapTable() {
  for (UsapTableEntry& entry : gUsapTable) {
    entry.Clear();
  }

  gUsapPoolCount = 0;
}

// Utility routine to fork a process from the zygote.
static pid_t ForkCommon(JNIEnv* env, bool is_system_server,
                        const std::vector<int>& fds_to_close,
                        const std::vector<int>& fds_to_ignore,
                        bool is_priority_fork) {
  SetSignalHandlers();

  // Curry a failure function.
  auto fail_fn = std::bind(ZygoteFailure, env, is_system_server ? "system_server" : "zygote",
                           nullptr, _1);

  // Temporarily block SIGCHLD during forks. The SIGCHLD handler might
  // log, which would result in the logging FDs we close being reopened.
  // This would cause failures because the FDs are not whitelisted.
  //
  // Note that the zygote process is single threaded at this point.
  BlockSignal(SIGCHLD, fail_fn);

  // Close any logging related FDs before we start evaluating the list of
  // file descriptors.
  __android_log_close();
  stats_log_close();

  // If this is the first fork for this zygote, create the open FD table.  If
  // it isn't, we just need to check whether the list of open files has changed
  // (and it shouldn't in the normal case).
  if (gOpenFdTable == nullptr) {
    gOpenFdTable = FileDescriptorTable::Create(fds_to_ignore, fail_fn);
  } else {
    gOpenFdTable->Restat(fds_to_ignore, fail_fn);
  }

  android_fdsan_error_level fdsan_error_level = android_fdsan_get_error_level();

  pid_t pid = fork();

  if (pid == 0) {
    if (is_priority_fork) {
      setpriority(PRIO_PROCESS, 0, PROCESS_PRIORITY_MAX);
    } else {
      setpriority(PRIO_PROCESS, 0, PROCESS_PRIORITY_MIN);
    }

    // The child process.
    PreApplicationInit();

    // Clean up any descriptors which must be closed immediately
    DetachDescriptors(env, fds_to_close, fail_fn);

    // Invalidate the entries in the USAP table.
    ClearUsapTable();

    // Re-open all remaining open file descriptors so that they aren't shared
    // with the zygote across a fork.
    gOpenFdTable->ReopenOrDetach(fail_fn);

    // Turn fdsan back on.
    android_fdsan_set_error_level(fdsan_error_level);
  } else {
    ALOGD("Forked child process %d", pid);
  }

  // We blocked SIGCHLD prior to a fork, we unblock it here.
  UnblockSignal(SIGCHLD, fail_fn);

  return pid;
}

// Utility routine to specialize a zygote child process.
static void SpecializeCommon(JNIEnv* env, uid_t uid, gid_t gid, jintArray gids,
                             jint runtime_flags, jobjectArray rlimits,
                             jlong permitted_capabilities, jlong effective_capabilities,
                             jint mount_external, jstring managed_se_info,
                             jstring managed_nice_name, bool is_system_server,
                             bool is_child_zygote, jstring managed_instruction_set,
                             jstring managed_app_data_dir, bool is_top_app) {
  const char* process_name = is_system_server ? "system_server" : "zygote";
  auto fail_fn = std::bind(ZygoteFailure, env, process_name, managed_nice_name, _1);
  auto extract_fn = std::bind(ExtractJString, env, process_name, managed_nice_name, _1);

  auto se_info = extract_fn(managed_se_info);
  auto nice_name = extract_fn(managed_nice_name);
  auto instruction_set = extract_fn(managed_instruction_set);
  auto app_data_dir = extract_fn(managed_app_data_dir);

  // Keep capabilities across UID change, unless we're staying root.
  if (uid != 0) {
    EnableKeepCapabilities(fail_fn);
  }

  SetInheritable(permitted_capabilities, fail_fn);

  DropCapabilitiesBoundingSet(fail_fn);

  bool use_native_bridge = !is_system_server &&
                           instruction_set.has_value() &&
                           android::NativeBridgeAvailable() &&
                           android::NeedsNativeBridge(instruction_set.value().c_str());

  if (use_native_bridge && !app_data_dir.has_value()) {
    // The app_data_dir variable should never be empty if we need to use a
    // native bridge.  In general, app_data_dir will never be empty for normal
    // applications.  It can only happen in special cases (for isolated
    // processes which are not associated with any app).  These are launched by
    // the framework and should not be emulated anyway.
    use_native_bridge = false;
    ALOGW("Native bridge will not be used because managed_app_data_dir == nullptr.");
  }

  MountEmulatedStorage(uid, mount_external, use_native_bridge, fail_fn);

  // If this zygote isn't root, it won't be able to create a process group,
  // since the directory is owned by root.
  if (!is_system_server && getuid() == 0) {
    const int rc = createProcessGroup(uid, getpid());
    if (rc == -EROFS) {
      ALOGW("createProcessGroup failed, kernel missing CONFIG_CGROUP_CPUACCT?");
    } else if (rc != 0) {
      ALOGE("createProcessGroup(%d, %d) failed: %s", uid, /* pid= */ 0, strerror(-rc));
    }
  }

  SetGids(env, gids, fail_fn);
  SetRLimits(env, rlimits, fail_fn);

  if (use_native_bridge) {
    // Due to the logic behind use_native_bridge we know that both app_data_dir
    // and instruction_set contain values.
    android::PreInitializeNativeBridge(app_data_dir.value().c_str(),
                                       instruction_set.value().c_str());
  }

  if (setresgid(gid, gid, gid) == -1) {
    fail_fn(CREATE_ERROR("setresgid(%d) failed: %s", gid, strerror(errno)));
  }

  // Must be called when the new process still has CAP_SYS_ADMIN, in this case,
  // before changing uid from 0, which clears capabilities.  The other
  // alternative is to call prctl(PR_SET_NO_NEW_PRIVS, 1) afterward, but that
  // breaks SELinux domain transition (see b/71859146).  As the result,
  // privileged syscalls used below still need to be accessible in app process.
  SetUpSeccompFilter(uid, is_child_zygote);

  // Must be called before losing the permission to set scheduler policy.
  SetSchedulerPolicy(fail_fn, is_top_app);

  if (setresuid(uid, uid, uid) == -1) {
    fail_fn(CREATE_ERROR("setresuid(%d) failed: %s", uid, strerror(errno)));
  }

  // The "dumpable" flag of a process, which controls core dump generation, is
  // overwritten by the value in /proc/sys/fs/suid_dumpable when the effective
  // user or group ID changes. See proc(5) for possible values. In most cases,
  // the value is 0, so core dumps are disabled for zygote children. However,
  // when running in a Chrome OS container, the value is already set to 2,
  // which allows the external crash reporter to collect all core dumps. Since
  // only system crashes are interested, core dump is disabled for app
  // processes. This also ensures compliance with CTS.
  int dumpable = prctl(PR_GET_DUMPABLE);
  if (dumpable == -1) {
    ALOGE("prctl(PR_GET_DUMPABLE) failed: %s", strerror(errno));
    RuntimeAbort(env, __LINE__, "prctl(PR_GET_DUMPABLE) failed");
  }

  if (dumpable == 2 && uid >= AID_APP) {
    if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) == -1) {
      ALOGE("prctl(PR_SET_DUMPABLE, 0) failed: %s", strerror(errno));
      RuntimeAbort(env, __LINE__, "prctl(PR_SET_DUMPABLE, 0) failed");
    }
  }

  // Set process properties to enable debugging if required.
  if ((runtime_flags & RuntimeFlags::DEBUG_ENABLE_JDWP) != 0) {
    EnableDebugger();
  }
  if ((runtime_flags & RuntimeFlags::PROFILE_FROM_SHELL) != 0) {
    // simpleperf needs the process to be dumpable to profile it.
    if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) == -1) {
      ALOGE("prctl(PR_SET_DUMPABLE) failed: %s", strerror(errno));
      RuntimeAbort(env, __LINE__, "prctl(PR_SET_DUMPABLE, 1) failed");
    }
  }

  if (NeedsNoRandomizeWorkaround()) {
    // Work around ARM kernel ASLR lossage (http://b/5817320).
    int old_personality = personality(0xffffffff);
    int new_personality = personality(old_personality | ADDR_NO_RANDOMIZE);
    if (new_personality == -1) {
      ALOGW("personality(%d) failed: %s", new_personality, strerror(errno));
    }
  }

  SetCapabilities(permitted_capabilities, effective_capabilities, permitted_capabilities, fail_fn);

  __android_log_close();
  stats_log_close();

  const char* se_info_ptr = se_info.has_value() ? se_info.value().c_str() : nullptr;
  const char* nice_name_ptr = nice_name.has_value() ? nice_name.value().c_str() : nullptr;

  if (selinux_android_setcontext(uid, is_system_server, se_info_ptr, nice_name_ptr) == -1) {
    fail_fn(CREATE_ERROR("selinux_android_setcontext(%d, %d, \"%s\", \"%s\") failed",
                         uid, is_system_server, se_info_ptr, nice_name_ptr));
  }

  // Make it easier to debug audit logs by setting the main thread's name to the
  // nice name rather than "app_process".
  if (nice_name.has_value()) {
    SetThreadName(nice_name.value());
  } else if (is_system_server) {
    SetThreadName("system_server");
  }

  // Unset the SIGCHLD handler, but keep ignoring SIGHUP (rationale in SetSignalHandlers).
  UnsetChldSignalHandler();

  if (is_system_server) {
    env->CallStaticVoidMethod(gZygoteClass, gCallPostForkSystemServerHooks);
    if (env->ExceptionCheck()) {
      fail_fn("Error calling post fork system server hooks.");
    }

    // Prefetch the classloader for the system server. This is done early to
    // allow a tie-down of the proper system server selinux domain.
    env->CallStaticVoidMethod(gZygoteInitClass, gCreateSystemServerClassLoader);
    if (env->ExceptionCheck()) {
      // Be robust here. The Java code will attempt to create the classloader
      // at a later point (but may not have rights to use AoT artifacts).
      env->ExceptionClear();
    }

    // TODO(oth): Remove hardcoded label here (b/117874058).
    static const char* kSystemServerLabel = "u:r:system_server:s0";
    if (selinux_android_setcon(kSystemServerLabel) != 0) {
      fail_fn(CREATE_ERROR("selinux_android_setcon(%s)", kSystemServerLabel));
    }
  }

  env->CallStaticVoidMethod(gZygoteClass, gCallPostForkChildHooks, runtime_flags,
                            is_system_server, is_child_zygote, managed_instruction_set);

  // Reset the process priority to the default value.
  setpriority(PRIO_PROCESS, 0, PROCESS_PRIORITY_DEFAULT);

  if (env->ExceptionCheck()) {
    fail_fn("Error calling post fork hooks.");
  }
}

static uint64_t GetEffectiveCapabilityMask(JNIEnv* env) {
    __user_cap_header_struct capheader;
    memset(&capheader, 0, sizeof(capheader));
    capheader.version = _LINUX_CAPABILITY_VERSION_3;
    capheader.pid = 0;

    __user_cap_data_struct capdata[2];
    if (capget(&capheader, &capdata[0]) == -1) {
        ALOGE("capget failed: %s", strerror(errno));
        RuntimeAbort(env, __LINE__, "capget failed");
    }

    return capdata[0].effective | (static_cast<uint64_t>(capdata[1].effective) << 32);
}

static jlong CalculateCapabilities(JNIEnv* env, jint uid, jint gid, jintArray gids,
                                   bool is_child_zygote) {
  jlong capabilities = 0;

  /*
   *  Grant the following capabilities to the Bluetooth user:
   *    - CAP_WAKE_ALARM
   *    - CAP_NET_ADMIN
   *    - CAP_NET_RAW
   *    - CAP_NET_BIND_SERVICE (for DHCP client functionality)
   *    - CAP_SYS_NICE (for setting RT priority for audio-related threads)
   */

  if (multiuser_get_app_id(uid) == AID_BLUETOOTH) {
    capabilities |= (1LL << CAP_WAKE_ALARM);
    capabilities |= (1LL << CAP_NET_ADMIN);
    capabilities |= (1LL << CAP_NET_RAW);
    capabilities |= (1LL << CAP_NET_BIND_SERVICE);
    capabilities |= (1LL << CAP_SYS_NICE);
  }

  if (multiuser_get_app_id(uid) == AID_NETWORK_STACK) {
    capabilities |= (1LL << CAP_NET_ADMIN);
    capabilities |= (1LL << CAP_NET_BROADCAST);
    capabilities |= (1LL << CAP_NET_BIND_SERVICE);
    capabilities |= (1LL << CAP_NET_RAW);
  }

  /*
   * Grant CAP_BLOCK_SUSPEND to processes that belong to GID "wakelock"
   */

  bool gid_wakelock_found = false;
  if (gid == AID_WAKELOCK) {
    gid_wakelock_found = true;
  } else if (gids != nullptr) {
    jsize gids_num = env->GetArrayLength(gids);
    ScopedIntArrayRO native_gid_proxy(env, gids);

    if (native_gid_proxy.get() == nullptr) {
      RuntimeAbort(env, __LINE__, "Bad gids array");
    }

    for (int gids_index = 0; gids_index < gids_num; ++gids_index) {
      if (native_gid_proxy[gids_index] == AID_WAKELOCK) {
        gid_wakelock_found = true;
        break;
      }
    }
  }

  if (gid_wakelock_found) {
    capabilities |= (1LL << CAP_BLOCK_SUSPEND);
  }

  /*
   * Grant child Zygote processes the following capabilities:
   *   - CAP_SETUID (change UID of child processes)
   *   - CAP_SETGID (change GID of child processes)
   *   - CAP_SETPCAP (change capabilities of child processes)
   */

  if (is_child_zygote) {
    capabilities |= (1LL << CAP_SETUID);
    capabilities |= (1LL << CAP_SETGID);
    capabilities |= (1LL << CAP_SETPCAP);
  }

  /*
   * Containers run without some capabilities, so drop any caps that are not
   * available.
   */

  return capabilities & GetEffectiveCapabilityMask(env);
}

/**
 * Adds the given information about a newly created unspecialized app
 * processes to the Zygote's USAP table.
 *
 * @param usap_pid  Process ID of the newly created USAP
 * @param read_pipe_fd  File descriptor for the read end of the USAP
 * reporting pipe.  Used in the ZygoteServer poll loop to track USAP
 * specialization.
 */
static void AddUsapTableEntry(pid_t usap_pid, int read_pipe_fd) {
  static int sUsapTableInsertIndex = 0;

  int search_index = sUsapTableInsertIndex;

  do {
    if (gUsapTable[search_index].SetIfInvalid(usap_pid, read_pipe_fd)) {
      // Start our next search right after where we finished this one.
      sUsapTableInsertIndex = (search_index + 1) % gUsapTable.size();

      return;
    }

    search_index = (search_index + 1) % gUsapTable.size();
  } while (search_index != sUsapTableInsertIndex);

  // Much like money in the banana stand, there should always be an entry
  // in the USAP table.
  __builtin_unreachable();
}

/**
 * Invalidates the entry in the USAPTable corresponding to the provided
 * process ID if it is present.  If an entry was removed the USAP pool
 * count is decremented.
 *
 * @param usap_pid  Process ID of the USAP entry to invalidate
 * @return True if an entry was invalidated; false otherwise
 */
static bool RemoveUsapTableEntry(pid_t usap_pid) {
  for (UsapTableEntry& entry : gUsapTable) {
    if (entry.ClearForPID(usap_pid)) {
      --gUsapPoolCount;
      return true;
    }
  }

  return false;
}

/**
 * @return A vector of the read pipe FDs for each of the active USAPs.
 */
std::vector<int> MakeUsapPipeReadFDVector() {
  std::vector<int> fd_vec;
  fd_vec.reserve(gUsapTable.size());

  for (UsapTableEntry& entry : gUsapTable) {
    auto entry_values = entry.GetValues();

    if (entry_values.has_value()) {
      fd_vec.push_back(entry_values.value().read_pipe_fd);
    }
  }

  return fd_vec;
}

static void UnmountStorageOnInit(JNIEnv* env) {
  // Zygote process unmount root storage space initially before every child processes are forked.
  // Every forked child processes (include SystemServer) only mount their own root storage space
  // and no need unmount storage operation in MountEmulatedStorage method.
  // Zygote process does not utilize root storage spaces and unshares its mount namespace below.

  // See storage config details at http://source.android.com/tech/storage/
  // Create private mount namespace shared by all children
  if (unshare(CLONE_NEWNS) == -1) {
    RuntimeAbort(env, __LINE__, "Failed to unshare()");
    return;
  }

  // Mark rootfs as being a slave so that changes from default
  // namespace only flow into our children.
  if (mount("rootfs", "/", nullptr, (MS_SLAVE | MS_REC), nullptr) == -1) {
    RuntimeAbort(env, __LINE__, "Failed to mount() rootfs as MS_SLAVE");
    return;
  }

  // Create a staging tmpfs that is shared by our children; they will
  // bind mount storage into their respective private namespaces, which
  // are isolated from each other.
  const char* target_base = getenv("EMULATED_STORAGE_TARGET");
  if (target_base != nullptr) {
#define STRINGIFY_UID(x) __STRING(x)
    if (mount("tmpfs", target_base, "tmpfs", MS_NOSUID | MS_NODEV,
              "uid=0,gid=" STRINGIFY_UID(AID_SDCARD_R) ",mode=0751") == -1) {
      ALOGE("Failed to mount tmpfs to %s", target_base);
      RuntimeAbort(env, __LINE__, "Failed to mount tmpfs");
      return;
    }
#undef STRINGIFY_UID
  }

  UnmountTree("/storage");
}

}  // anonymous namespace

namespace android {

static void com_android_internal_os_Zygote_nativePreApplicationInit(JNIEnv*, jclass) {
  PreApplicationInit();
}

static jint com_android_internal_os_Zygote_nativeForkAndSpecialize(
        JNIEnv* env, jclass, jint uid, jint gid, jintArray gids,
        jint runtime_flags, jobjectArray rlimits,
        jint mount_external, jstring se_info, jstring nice_name,
        jintArray managed_fds_to_close, jintArray managed_fds_to_ignore, jboolean is_child_zygote,
        jstring instruction_set, jstring app_data_dir, jboolean is_top_app) {
    jlong capabilities = CalculateCapabilities(env, uid, gid, gids, is_child_zygote);

    if (UNLIKELY(managed_fds_to_close == nullptr)) {
      ZygoteFailure(env, "zygote", nice_name, "Zygote received a null fds_to_close vector.");
    }

    std::vector<int> fds_to_close =
        ExtractJIntArray(env, "zygote", nice_name, managed_fds_to_close).value();
    std::vector<int> fds_to_ignore =
        ExtractJIntArray(env, "zygote", nice_name, managed_fds_to_ignore)
            .value_or(std::vector<int>());

    std::vector<int> usap_pipes = MakeUsapPipeReadFDVector();

    fds_to_close.insert(fds_to_close.end(), usap_pipes.begin(), usap_pipes.end());
    fds_to_ignore.insert(fds_to_ignore.end(), usap_pipes.begin(), usap_pipes.end());

    fds_to_close.push_back(gUsapPoolSocketFD);

    if (gUsapPoolEventFD != -1) {
      fds_to_close.push_back(gUsapPoolEventFD);
      fds_to_ignore.push_back(gUsapPoolEventFD);
    }

    pid_t pid = ForkCommon(env, false, fds_to_close, fds_to_ignore, true);

    if (pid == 0) {
      SpecializeCommon(env, uid, gid, gids, runtime_flags, rlimits,
                       capabilities, capabilities,
                       mount_external, se_info, nice_name, false,
                       is_child_zygote == JNI_TRUE, instruction_set, app_data_dir,
                       is_top_app == JNI_TRUE);
    }
    return pid;
}

static jint com_android_internal_os_Zygote_nativeForkSystemServer(
        JNIEnv* env, jclass, uid_t uid, gid_t gid, jintArray gids,
        jint runtime_flags, jobjectArray rlimits, jlong permitted_capabilities,
        jlong effective_capabilities) {
  std::vector<int> fds_to_close(MakeUsapPipeReadFDVector()),
                   fds_to_ignore(fds_to_close);

  fds_to_close.push_back(gUsapPoolSocketFD);

  if (gUsapPoolEventFD != -1) {
    fds_to_close.push_back(gUsapPoolEventFD);
    fds_to_ignore.push_back(gUsapPoolEventFD);
  }

  pid_t pid = ForkCommon(env, true,
                         fds_to_close,
                         fds_to_ignore,
                         true);
  if (pid == 0) {
      SpecializeCommon(env, uid, gid, gids, runtime_flags, rlimits,
                       permitted_capabilities, effective_capabilities,
                       MOUNT_EXTERNAL_DEFAULT, nullptr, nullptr, true,
                       false, nullptr, nullptr, /* is_top_app= */ false);
  } else if (pid > 0) {
      // The zygote process checks whether the child process has died or not.
      ALOGI("System server process %d has been created", pid);
      gSystemServerPid = pid;
      // There is a slight window that the system server process has crashed
      // but it went unnoticed because we haven't published its pid yet. So
      // we recheck here just to make sure that all is well.
      int status;
      if (waitpid(pid, &status, WNOHANG) == pid) {
          ALOGE("System server process %d has died. Restarting Zygote!", pid);
          RuntimeAbort(env, __LINE__, "System server process has died. Restarting Zygote!");
      }

      if (UsePerAppMemcg()) {
          // Assign system_server to the correct memory cgroup.
          // Not all devices mount memcg so check if it is mounted first
          // to avoid unnecessarily printing errors and denials in the logs.
          if (!SetTaskProfiles(pid, std::vector<std::string>{"SystemMemoryProcess"})) {
              ALOGE("couldn't add process %d into system memcg group", pid);
          }
      }
  }
  return pid;
}

/**
 * A JNI function that forks an unspecialized app process from the Zygote while
 * ensuring proper file descriptor hygiene.
 *
 * @param env  Managed runtime environment
 * @param read_pipe_fd  The read FD for the USAP reporting pipe.  Manually closed by blastlas
 * in managed code.
 * @param write_pipe_fd  The write FD for the USAP reporting pipe.  Manually closed by the
 * zygote in managed code.
 * @param managed_session_socket_fds  A list of anonymous session sockets that must be ignored by
 * the FD hygiene code and automatically "closed" in the new USAP.
 * @param is_priority_fork  Controls the nice level assigned to the newly created process
 * @return
 */
static jint com_android_internal_os_Zygote_nativeForkUsap(JNIEnv* env,
                                                          jclass,
                                                          jint read_pipe_fd,
                                                          jint write_pipe_fd,
                                                          jintArray managed_session_socket_fds,
                                                          jboolean is_priority_fork) {
  std::vector<int> fds_to_close(MakeUsapPipeReadFDVector()),
                   fds_to_ignore(fds_to_close);

  std::vector<int> session_socket_fds =
      ExtractJIntArray(env, "USAP", nullptr, managed_session_socket_fds)
          .value_or(std::vector<int>());

  // The USAP Pool Event FD is created during the initialization of the
  // USAP pool and should always be valid here.

  fds_to_close.push_back(gZygoteSocketFD);
  fds_to_close.push_back(gUsapPoolEventFD);
  fds_to_close.insert(fds_to_close.end(), session_socket_fds.begin(), session_socket_fds.end());

  fds_to_ignore.push_back(gZygoteSocketFD);
  fds_to_ignore.push_back(gUsapPoolSocketFD);
  fds_to_ignore.push_back(gUsapPoolEventFD);
  fds_to_ignore.push_back(read_pipe_fd);
  fds_to_ignore.push_back(write_pipe_fd);
  fds_to_ignore.insert(fds_to_ignore.end(), session_socket_fds.begin(), session_socket_fds.end());

  pid_t usap_pid = ForkCommon(env, /* is_system_server= */ false, fds_to_close, fds_to_ignore,
                              is_priority_fork == JNI_TRUE);

  if (usap_pid != 0) {
    ++gUsapPoolCount;
    AddUsapTableEntry(usap_pid, read_pipe_fd);
  }

  return usap_pid;
}

static void com_android_internal_os_Zygote_nativeAllowFileAcrossFork(
        JNIEnv* env, jclass, jstring path) {
    ScopedUtfChars path_native(env, path);
    const char* path_cstr = path_native.c_str();
    if (!path_cstr) {
        RuntimeAbort(env, __LINE__, "path_cstr == nullptr");
    }
    FileDescriptorWhitelist::Get()->Allow(path_cstr);
}

static void com_android_internal_os_Zygote_nativeInstallSeccompUidGidFilter(
        JNIEnv* env, jclass, jint uidGidMin, jint uidGidMax) {
  if (!gIsSecurityEnforced) {
    ALOGI("seccomp disabled by setenforce 0");
    return;
  }

  bool installed = install_setuidgid_seccomp_filter(uidGidMin, uidGidMax);
  if (!installed) {
      RuntimeAbort(env, __LINE__, "Could not install setuid/setgid seccomp filter.");
  }
}

/**
 * Called from an unspecialized app process to specialize the process for a
 * given application.
 *
 * @param env  Managed runtime environment
 * @param uid  User ID of the new application
 * @param gid  Group ID of the new application
 * @param gids  Extra groups that the process belongs to
 * @param runtime_flags  Flags for changing the behavior of the managed runtime
 * @param rlimits  Resource limits
 * @param mount_external  The mode (read/write/normal) that external storage will be mounted with
 * @param se_info  SELinux policy information
 * @param nice_name  New name for this process
 * @param is_child_zygote  If the process is to become a WebViewZygote
 * @param instruction_set  The instruction set expected/requested by the new application
 * @param app_data_dir  Path to the application's data directory
 * @param is_top_app  If the process is for top (high priority) application
 */
static void com_android_internal_os_Zygote_nativeSpecializeAppProcess(
    JNIEnv* env, jclass, jint uid, jint gid, jintArray gids,
    jint runtime_flags, jobjectArray rlimits,
    jint mount_external, jstring se_info, jstring nice_name,
    jboolean is_child_zygote, jstring instruction_set, jstring app_data_dir, jboolean is_top_app) {
  jlong capabilities = CalculateCapabilities(env, uid, gid, gids, is_child_zygote);

  SpecializeCommon(env, uid, gid, gids, runtime_flags, rlimits,
                   capabilities, capabilities,
                   mount_external, se_info, nice_name, false,
                   is_child_zygote == JNI_TRUE, instruction_set, app_data_dir,
                   is_top_app == JNI_TRUE);
}

/**
 * A helper method for fetching socket file descriptors that were opened by init from the
 * environment.
 *
 * @param env  Managed runtime environment
 * @param is_primary  If this process is the primary or secondary Zygote; used to compute the name
 * of the environment variable storing the file descriptors.
 */
static void com_android_internal_os_Zygote_nativeInitNativeState(JNIEnv* env, jclass,
                                                                 jboolean is_primary) {
  /*
   * Obtain file descriptors created by init from the environment.
   */

  std::string android_socket_prefix(ANDROID_SOCKET_PREFIX);
  std::string env_var_name = android_socket_prefix + (is_primary ? "zygote" : "zygote_secondary");
  char* env_var_val = getenv(env_var_name.c_str());

  if (env_var_val != nullptr) {
    gZygoteSocketFD = atoi(env_var_val);
    ALOGV("Zygote:zygoteSocketFD = %d", gZygoteSocketFD);
  } else {
    ALOGE("Unable to fetch Zygote socket file descriptor");
  }

  env_var_name = android_socket_prefix + (is_primary ? "usap_pool_primary" : "usap_pool_secondary");
  env_var_val = getenv(env_var_name.c_str());

  if (env_var_val != nullptr) {
    gUsapPoolSocketFD = atoi(env_var_val);
    ALOGV("Zygote:usapPoolSocketFD = %d", gUsapPoolSocketFD);
  } else {
    ALOGE("Unable to fetch USAP pool socket file descriptor");
  }

  /*
   * Security Initialization
   */

  // security_getenforce is not allowed on app process. Initialize and cache
  // the value before zygote forks.
  gIsSecurityEnforced = security_getenforce();

  selinux_android_seapp_context_init();

  /*
   * Storage Initialization
   */

  UnmountStorageOnInit(env);

  /*
   * Performance Initialization
   */

  if (!SetTaskProfiles(0, {})) {
    ZygoteFailure(env, "zygote", nullptr, "Zygote SetTaskProfiles failed");
  }

  /*
   * ashmem initialization to avoid dlopen overhead
   */
  ashmem_init();
}

/**
 * @param env  Managed runtime environment
 * @return  A managed array of raw file descriptors for the read ends of the USAP reporting
 * pipes.
 */
static jintArray com_android_internal_os_Zygote_nativeGetUsapPipeFDs(JNIEnv* env, jclass) {
  std::vector<int> usap_fds = MakeUsapPipeReadFDVector();

  jintArray managed_usap_fds = env->NewIntArray(usap_fds.size());
  env->SetIntArrayRegion(managed_usap_fds, 0, usap_fds.size(), usap_fds.data());

  return managed_usap_fds;
}

/**
 * A JNI wrapper around RemoveUsapTableEntry.
 *
 * @param env  Managed runtime environment
 * @param usap_pid  Process ID of the USAP entry to invalidate
 * @return  True if an entry was invalidated; false otherwise.
 */
static jboolean com_android_internal_os_Zygote_nativeRemoveUsapTableEntry(JNIEnv* env, jclass,
                                                                          jint usap_pid) {
  return RemoveUsapTableEntry(usap_pid);
}

/**
 * Creates the USAP pool event FD if it doesn't exist and returns it.  This is used by the
 * ZygoteServer poll loop to know when to re-fill the USAP pool.
 *
 * @param env  Managed runtime environment
 * @return A raw event file descriptor used to communicate (from the signal handler) when the
 * Zygote receives a SIGCHLD for a USAP
 */
static jint com_android_internal_os_Zygote_nativeGetUsapPoolEventFD(JNIEnv* env, jclass) {
  if (gUsapPoolEventFD == -1) {
    if ((gUsapPoolEventFD = eventfd(0, 0)) == -1) {
      ZygoteFailure(env, "zygote", nullptr, StringPrintf("Unable to create eventfd: %s", strerror(errno)));
    }
  }

  return gUsapPoolEventFD;
}

/**
 * @param env  Managed runtime environment
 * @return The number of USAPs currently in the USAP pool
 */
static jint com_android_internal_os_Zygote_nativeGetUsapPoolCount(JNIEnv* env, jclass) {
  return gUsapPoolCount;
}

/**
 * Kills all processes currently in the USAP pool and closes their read pipe
 * FDs.
 *
 * @param env  Managed runtime environment
 */
static void com_android_internal_os_Zygote_nativeEmptyUsapPool(JNIEnv* env, jclass) {
  for (auto& entry : gUsapTable) {
    auto entry_storage = entry.GetValues();

    if (entry_storage.has_value()) {
      kill(entry_storage.value().pid, SIGTERM);

      // Clean up the USAP table entry here.  This avoids a potential race
      // where a newly created USAP might not be able to find a valid table
      // entry if signal handler (which would normally do the cleanup) doesn't
      // run between now and when the new process is created.

      close(entry_storage.value().read_pipe_fd);

      // Avoid a second atomic load by invalidating instead of clearing.
      entry.Invalidate();
      --gUsapPoolCount;
    }
  }
}

static int disable_execute_only(struct dl_phdr_info *info, size_t size, void *data) {
  // Search for any execute-only segments and mark them read+execute.
  for (int i = 0; i < info->dlpi_phnum; i++) {
    if ((info->dlpi_phdr[i].p_type == PT_LOAD) && (info->dlpi_phdr[i].p_flags == PF_X)) {
      mprotect(reinterpret_cast<void*>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr),
              info->dlpi_phdr[i].p_memsz, PROT_READ | PROT_EXEC);
    }
  }
  // Return non-zero to exit dl_iterate_phdr.
  return 0;
}

/**
 * @param env  Managed runtime environment
 * @return  True if disable was successful.
 */
static jboolean com_android_internal_os_Zygote_nativeDisableExecuteOnly(JNIEnv* env, jclass) {
  return dl_iterate_phdr(disable_execute_only, nullptr) == 0;
}

static void com_android_internal_os_Zygote_nativeBlockSigTerm(JNIEnv* env, jclass) {
  auto fail_fn = std::bind(ZygoteFailure, env, "usap", nullptr, _1);
  BlockSignal(SIGTERM, fail_fn);
}

static void com_android_internal_os_Zygote_nativeUnblockSigTerm(JNIEnv* env, jclass) {
  auto fail_fn = std::bind(ZygoteFailure, env, "usap", nullptr, _1);
  UnblockSignal(SIGTERM, fail_fn);
}

static void com_android_internal_os_Zygote_nativeBoostUsapPriority(JNIEnv* env, jclass) {
  setpriority(PRIO_PROCESS, 0, PROCESS_PRIORITY_MAX);
}

static const JNINativeMethod gMethods[] = {
    { "nativeForkAndSpecialize",
      "(II[II[[IILjava/lang/String;Ljava/lang/String;[I[IZLjava/lang/String;Ljava/lang/String;Z)I",
      (void *) com_android_internal_os_Zygote_nativeForkAndSpecialize },
    { "nativeForkSystemServer", "(II[II[[IJJ)I",
      (void *) com_android_internal_os_Zygote_nativeForkSystemServer },
    { "nativeAllowFileAcrossFork", "(Ljava/lang/String;)V",
      (void *) com_android_internal_os_Zygote_nativeAllowFileAcrossFork },
    { "nativePreApplicationInit", "()V",
      (void *) com_android_internal_os_Zygote_nativePreApplicationInit },
    { "nativeInstallSeccompUidGidFilter", "(II)V",
      (void *) com_android_internal_os_Zygote_nativeInstallSeccompUidGidFilter },
    { "nativeForkUsap", "(II[IZ)I",
      (void *) com_android_internal_os_Zygote_nativeForkUsap },
    { "nativeSpecializeAppProcess",
      "(II[II[[IILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Z)V",
      (void *) com_android_internal_os_Zygote_nativeSpecializeAppProcess },
    { "nativeInitNativeState", "(Z)V",
      (void *) com_android_internal_os_Zygote_nativeInitNativeState },
    { "nativeGetUsapPipeFDs", "()[I",
      (void *) com_android_internal_os_Zygote_nativeGetUsapPipeFDs },
    { "nativeRemoveUsapTableEntry", "(I)Z",
      (void *) com_android_internal_os_Zygote_nativeRemoveUsapTableEntry },
    { "nativeGetUsapPoolEventFD", "()I",
      (void *) com_android_internal_os_Zygote_nativeGetUsapPoolEventFD },
    { "nativeGetUsapPoolCount", "()I",
      (void *) com_android_internal_os_Zygote_nativeGetUsapPoolCount },
    { "nativeEmptyUsapPool", "()V",
      (void *) com_android_internal_os_Zygote_nativeEmptyUsapPool },
    { "nativeDisableExecuteOnly", "()Z",
      (void *) com_android_internal_os_Zygote_nativeDisableExecuteOnly },
    { "nativeBlockSigTerm", "()V",
      (void* ) com_android_internal_os_Zygote_nativeBlockSigTerm },
    { "nativeUnblockSigTerm", "()V",
      (void* ) com_android_internal_os_Zygote_nativeUnblockSigTerm },
    { "nativeBoostUsapPriority", "()V",
      (void* ) com_android_internal_os_Zygote_nativeBoostUsapPriority }
};

int register_com_android_internal_os_Zygote(JNIEnv* env) {
  gZygoteClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, kZygoteClassName));
  gCallPostForkSystemServerHooks = GetStaticMethodIDOrDie(env, gZygoteClass,
                                                          "callPostForkSystemServerHooks",
                                                          "()V");
  gCallPostForkChildHooks = GetStaticMethodIDOrDie(env, gZygoteClass, "callPostForkChildHooks",
                                                   "(IZZLjava/lang/String;)V");

  gZygoteInitClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, kZygoteInitClassName));
  gCreateSystemServerClassLoader = GetStaticMethodIDOrDie(env, gZygoteInitClass,
                                                          "createSystemServerClassLoader",
                                                          "()V");

  RegisterMethodsOrDie(env, "com/android/internal/os/Zygote", gMethods, NELEM(gMethods));

  return JNI_OK;
}
}  // namespace android
