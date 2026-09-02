#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>
#include <vector>
#include <string>

#define LOG_TAG "AutoCrackNative"

static void throw_io(JNIEnv* env, const char* prefix) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: errno=%d %s", prefix, errno, strerror(errno));
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, buf);
}

static std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return std::string();
    const char* raw = env->GetStringUTFChars(value, nullptr);
    std::string out = raw ? raw : "";
    if (raw) env->ReleaseStringUTFChars(value, raw);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeReadMemory(
        JNIEnv* env, jclass, jlong address, jint size) {
    if (address <= 0 || size <= 0) {
        jclass cls = env->FindClass("java/lang/IllegalArgumentException");
        if (cls) env->ThrowNew(cls, "address and size must be positive");
        return nullptr;
    }
    std::vector<unsigned char> buffer(static_cast<size_t>(size));
    struct iovec local { buffer.data(), static_cast<size_t>(size) };
    struct iovec remote { reinterpret_cast<void*>(static_cast<uintptr_t>(address)), static_cast<size_t>(size) };
    ssize_t n = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    if (n < 0) {
        int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
        if (fd < 0) { throw_io(env, "process_vm_readv and /proc/self/mem open failed"); return nullptr; }
        n = pread(fd, buffer.data(), static_cast<size_t>(size), static_cast<off_t>(address));
        int saved = errno;
        close(fd);
        errno = saved;
        if (n < 0) { throw_io(env, "pread /proc/self/mem failed"); return nullptr; }
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(n));
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(n), reinterpret_cast<jbyte*>(buffer.data()));
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeDlopen(
        JNIEnv* env, jclass, jstring path, jint flags) {
    std::string p = jstr(env, path);
    dlerror();
    void* handle = dlopen(p.c_str(), flags);
    const char* err = dlerror();
    char buf[1024];
    if (handle) {
        snprintf(buf, sizeof(buf), "OK:%p", handle);
    } else {
        snprintf(buf, sizeof(buf), "ERR:%s", err ? err : "unknown dlopen failure");
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dlopen(%s) => %s", p.c_str(), buf);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeDladdr(
        JNIEnv* env, jclass, jlong address) {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(static_cast<uintptr_t>(address)), &info) == 0) {
        return env->NewStringUTF("ERR:dladdr failed");
    }
    char buf[2048];
    snprintf(buf, sizeof(buf), "OK:%s|%p|%s|%p",
             info.dli_fname ? info.dli_fname : "",
             info.dli_fbase,
             info.dli_sname ? info.dli_sname : "",
             info.dli_saddr);
    return env->NewStringUTF(buf);
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeProbe(
        JNIEnv* env, jclass) {
    uint64_t marker = 0x4155544f43524b4full; // "AUTOCRKO" marker for self-read probe.
    uint64_t copied = 0;
    struct iovec local { &copied, sizeof(copied) };
    struct iovec remote { &marker, sizeof(marker) };
    ssize_t n = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    const char* read_strategy = "process_vm_readv";
    char read_error[256] = {0};
    if (n < 0) {
        int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            n = pread(fd, &copied, sizeof(copied), reinterpret_cast<off_t>(&marker));
            int saved = errno;
            close(fd);
            errno = saved;
            read_strategy = "pread /proc/self/mem";
        }
    }
    if (n < 0) snprintf(read_error, sizeof(read_error), "errno=%d %s", errno, strerror(errno));
    Dl_info info{};
    int dl_ok = dladdr(reinterpret_cast<void*>(&Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeProbe), &info);
    char buf[4096];
    snprintf(buf, sizeof(buf),
             "{\"ok\":true,\"pid\":%d,\"self_read_supported\":%s,\"self_read_strategy\":\"%s\",\"self_read_bytes\":%zd,\"marker_ok\":%s,\"read_error\":\"%s\",\"dladdr_supported\":%s,\"file\":\"%s\",\"base\":\"%p\",\"symbol\":\"%s\",\"symbol_address\":\"%p\"}",
             getpid(), n == static_cast<ssize_t>(sizeof(marker)) ? "true" : "false", read_strategy,
             n, copied == marker ? "true" : "false", read_error,
             dl_ok ? "true" : "false", info.dli_fname ? info.dli_fname : "", info.dli_fbase,
             info.dli_sname ? info.dli_sname : "", info.dli_saddr);
    return env->NewStringUTF(buf);
}
