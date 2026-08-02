#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char* kLogTag = "AutoCrackPty";
constexpr jint kStillRunning = INT_MIN;
constexpr int kDefaultCloseWaitMillis = 1500;
constexpr int kPollSliceMillis = 10;

struct PtySession {
    explicit PtySession(int master, pid_t child)
        : master_fd(master), pid(child) {}

    int master_fd;
    pid_t pid;
    std::mutex state_mutex;
    bool closed = false;
    bool reaped = false;
    int exit_status = kStillRunning;
};

std::mutex g_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<PtySession>> g_sessions;
std::atomic<jlong> g_next_handle{1};

void log_error(const char* operation) {
    __android_log_print(
        ANDROID_LOG_ERROR,
        kLogTag,
        "%s failed: errno=%d (%s)",
        operation,
        errno,
        std::strerror(errno));
}

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> to_string_vector(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) {
        return result;
    }
    const jsize size = env->GetArrayLength(values);
    result.reserve(static_cast<std::size_t>(size));
    for (jsize index = 0; index < size; ++index) {
        auto* value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(to_string(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

std::shared_ptr<PtySession> get_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const auto iterator = g_sessions.find(handle);
    if (iterator == g_sessions.end()) {
        return nullptr;
    }
    return iterator->second;
}

bool set_window_size(int fd, jint rows, jint columns) {
    if (rows <= 0 || columns <= 0 || rows > USHRT_MAX || columns > USHRT_MAX) {
        errno = EINVAL;
        return false;
    }
    struct winsize size {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    return ioctl(fd, TIOCSWINSZ, &size) == 0;
}

int normalize_wait_status(int status) {
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

int wait_for_session(const std::shared_ptr<PtySession>& session, int timeout_millis) {
    std::unique_lock<std::mutex> lock(session->state_mutex);
    if (session->reaped) {
        return session->exit_status;
    }
    const pid_t pid = session->pid;
    lock.unlock();

    const int bounded_timeout = timeout_millis < 0 ? 0 : timeout_millis;
    int elapsed = 0;
    while (true) {
        int status = 0;
        const pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) {
            const int normalized = normalize_wait_status(status);
            std::lock_guard<std::mutex> state_lock(session->state_mutex);
            session->reaped = true;
            session->exit_status = normalized;
            return normalized;
        }
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == ECHILD) {
                std::lock_guard<std::mutex> state_lock(session->state_mutex);
                session->reaped = true;
                if (session->exit_status == kStillRunning) {
                    session->exit_status = -1;
                }
                return session->exit_status;
            }
            return -errno;
        }
        if (elapsed >= bounded_timeout) {
            return kStillRunning;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kPollSliceMillis));
        elapsed += kPollSliceMillis;
    }
}

bool send_process_group_signal(pid_t pid, int signal_number) {
    if (pid <= 0) {
        errno = EINVAL;
        return false;
    }
    if (kill(-pid, signal_number) == 0) {
        return true;
    }
    if (errno == ESRCH && kill(pid, signal_number) == 0) {
        return true;
    }
    return false;
}

int duplicate_master(const std::shared_ptr<PtySession>& session) {
    std::lock_guard<std::mutex> lock(session->state_mutex);
    if (session->closed || session->master_fd < 0) {
        errno = EBADF;
        return -1;
    }
    return dup(session->master_fd);
}

void close_master(const std::shared_ptr<PtySession>& session) {
    std::lock_guard<std::mutex> lock(session->state_mutex);
    if (!session->closed) {
        session->closed = true;
        if (session->master_fd >= 0) {
            close(session->master_fd);
            session->master_fd = -1;
        }
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeOpen(
    JNIEnv* env,
    jobject /* thiz */,
    jstring program_value,
    jobjectArray arguments_value,
    jint rows,
    jint columns) {
    const std::string program = to_string(env, program_value);
    const std::vector<std::string> arguments = to_string_vector(env, arguments_value);
    if (program.empty()) {
        errno = EINVAL;
        return -EINVAL;
    }

    const int master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master_fd < 0) {
        log_error("posix_openpt");
        return -errno;
    }
    if (grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) {
        const int saved_errno = errno;
        log_error("grantpt/unlockpt");
        close(master_fd);
        return -saved_errno;
    }

    char slave_path[PATH_MAX] = {};
    const int pts_result = ptsname_r(master_fd, slave_path, sizeof(slave_path));
    if (pts_result != 0) {
        close(master_fd);
        return -pts_result;
    }
    if (!set_window_size(master_fd, rows, columns)) {
        const int saved_errno = errno;
        close(master_fd);
        return -saved_errno;
    }

    std::vector<char*> argv;
    argv.reserve(arguments.size() + 2U);
    argv.push_back(const_cast<char*>(program.c_str()));
    for (const std::string& argument : arguments) {
        argv.push_back(const_cast<char*>(argument.c_str()));
    }
    argv.push_back(nullptr);

    const pid_t pid = fork();
    if (pid < 0) {
        const int saved_errno = errno;
        log_error("fork");
        close(master_fd);
        return -saved_errno;
    }

    if (pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGHUP);
        if (setsid() < 0) {
            _exit(126);
        }
        const int slave_fd = open(slave_path, O_RDWR);
        if (slave_fd < 0) {
            _exit(126);
        }
        if (ioctl(slave_fd, TIOCSCTTY, 0) < 0) {
            close(slave_fd);
            _exit(126);
        }
        if (dup2(slave_fd, STDIN_FILENO) < 0 ||
            dup2(slave_fd, STDOUT_FILENO) < 0 ||
            dup2(slave_fd, STDERR_FILENO) < 0) {
            close(slave_fd);
            _exit(126);
        }
        if (slave_fd > STDERR_FILENO) {
            close(slave_fd);
        }
        close(master_fd);
        execv(program.c_str(), argv.data());
        const char message[] = "AutoCrackApp PTY execv failed\r\n";
        static_cast<void>(write(STDERR_FILENO, message, sizeof(message) - 1U));
        _exit(127);
    }

    const int current_flags = fcntl(master_fd, F_GETFL, 0);
    if (current_flags >= 0) {
        static_cast<void>(fcntl(master_fd, F_SETFL, current_flags | O_NONBLOCK));
    }

    const jlong handle = g_next_handle.fetch_add(1);
    auto session = std::make_shared<PtySession>(master_fd, pid);
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        g_sessions.emplace(handle, std::move(session));
    }
    return handle;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeRead(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint max_bytes,
    jint timeout_millis) {
    const auto session = get_session(handle);
    if (session == nullptr || max_bytes <= 0 || max_bytes > 1'048'576) {
        return nullptr;
    }
    const int fd = duplicate_master(session);
    if (fd < 0) {
        return nullptr;
    }

    struct pollfd descriptor {};
    descriptor.fd = fd;
    descriptor.events = POLLIN | POLLHUP | POLLERR;
    int poll_result = 0;
    do {
        poll_result = poll(&descriptor, 1, timeout_millis < 0 ? 0 : timeout_millis);
    } while (poll_result < 0 && errno == EINTR);

    if (poll_result == 0) {
        close(fd);
        return env->NewByteArray(0);
    }
    if (poll_result < 0) {
        close(fd);
        return nullptr;
    }

    std::vector<jbyte> buffer(static_cast<std::size_t>(max_bytes));
    ssize_t count = -1;
    do {
        count = read(fd, buffer.data(), static_cast<std::size_t>(max_bytes));
    } while (count < 0 && errno == EINTR);
    close(fd);

    if (count == 0) {
        return nullptr;
    }
    if (count < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return env->NewByteArray(0);
        }
        return nullptr;
    }

    auto* result = env->NewByteArray(static_cast<jsize>(count));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(count), buffer.data());
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeWrite(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray data_value) {
    const auto session = get_session(handle);
    if (session == nullptr || data_value == nullptr) {
        return -EINVAL;
    }
    const jsize data_size = env->GetArrayLength(data_value);
    if (data_size <= 0) {
        return 0;
    }
    std::vector<jbyte> data(static_cast<std::size_t>(data_size));
    env->GetByteArrayRegion(data_value, 0, data_size, data.data());

    const int fd = duplicate_master(session);
    if (fd < 0) {
        return -errno;
    }

    std::size_t offset = 0U;
    while (offset < data.size()) {
        const auto* source = reinterpret_cast<const char*>(data.data() + offset);
        const std::size_t remaining = data.size() - offset;
        const ssize_t written = write(fd, source, remaining);
        if (written > 0) {
            offset += static_cast<std::size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd descriptor {};
            descriptor.fd = fd;
            descriptor.events = POLLOUT;
            if (poll(&descriptor, 1, 1000) > 0) {
                continue;
            }
        }
        const int saved_errno = errno == 0 ? EIO : errno;
        close(fd);
        return -saved_errno;
    }
    close(fd);
    return static_cast<jint>(offset);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeResize(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint rows,
    jint columns) {
    const auto session = get_session(handle);
    if (session == nullptr) {
        return JNI_FALSE;
    }
    const int fd = duplicate_master(session);
    if (fd < 0) {
        return JNI_FALSE;
    }
    const bool success = set_window_size(fd, rows, columns);
    close(fd);
    if (success) {
        static_cast<void>(send_process_group_signal(session->pid, SIGWINCH));
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeSignal(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint signal_number) {
    const auto session = get_session(handle);
    if (session == nullptr || signal_number <= 0 || signal_number >= NSIG) {
        return JNI_FALSE;
    }
    return send_process_group_signal(session->pid, signal_number) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeWait(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint timeout_millis) {
    const auto session = get_session(handle);
    if (session == nullptr) {
        return -ENOENT;
    }
    return wait_for_session(session, timeout_millis);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeClose(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint signal_number) {
    const auto session = get_session(handle);
    if (session == nullptr) {
        return -ENOENT;
    }

    int status = wait_for_session(session, 0);
    if (status == kStillRunning && signal_number > 0 && signal_number < NSIG) {
        static_cast<void>(send_process_group_signal(session->pid, signal_number));
        status = wait_for_session(session, kDefaultCloseWaitMillis);
    }
    if (status == kStillRunning) {
        static_cast<void>(send_process_group_signal(session->pid, SIGKILL));
        status = wait_for_session(session, kDefaultCloseWaitMillis);
    }
    close_master(session);
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        g_sessions.erase(handle);
    }
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativePid(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    const auto session = get_session(handle);
    return session == nullptr ? -1 : static_cast<jint>(session->pid);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_luckylca_autocrack_runtime_NativePtyBridge_nativeIsAlive(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    const auto session = get_session(handle);
    if (session == nullptr) {
        return JNI_FALSE;
    }
    const int status = wait_for_session(session, 0);
    return status == kStillRunning ? JNI_TRUE : JNI_FALSE;
}
