#include <jni.h>
#include <android/log.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <link.h>
#include <string.h>
#include <stdlib.h>
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

static std::string json_escape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 16);
    for (char c : in) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned char>(c));
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

static std::string hex_ptr(uintptr_t value) {
    char buf[32];
    snprintf(buf, sizeof(buf), "0x%" PRIxPTR, value);
    return std::string(buf);
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
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeAndroidDlopenExt(
        JNIEnv* env, jclass, jstring path, jint flags, jint ext_flags) {
    std::string p = jstr(env, path);
    if (p.empty()) return env->NewStringUTF("ERR:path is required");

    android_dlextinfo extinfo{};
    extinfo.flags = static_cast<uint64_t>(ext_flags);

#ifdef ANDROID_DLEXT_USE_NAMESPACE
    if ((extinfo.flags & ANDROID_DLEXT_USE_NAMESPACE) != 0) {
        return env->NewStringUTF("ERR:ANDROID_DLEXT_USE_NAMESPACE requires an android_namespace_t pointer; namespace bypass is not implemented");
    }
#endif

    int fd = -1;
#ifdef ANDROID_DLEXT_USE_LIBRARY_FD
    if ((extinfo.flags & ANDROID_DLEXT_USE_LIBRARY_FD) != 0) {
        fd = open(p.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            char buf[512];
            snprintf(buf, sizeof(buf), "ERR:open library fd failed: errno=%d %s", errno, strerror(errno));
            return env->NewStringUTF(buf);
        }
        extinfo.library_fd = fd;
    }
#endif

    dlerror();
    void* handle = android_dlopen_ext(p.c_str(), flags, &extinfo);
    const char* err = dlerror();
    int saved = errno;
    if (fd >= 0) close(fd);

    char buf[1024];
    if (handle) {
        snprintf(buf, sizeof(buf), "OK:%p", handle);
    } else {
        snprintf(buf, sizeof(buf), "ERR:%s errno=%d %s", err ? err : "android_dlopen_ext failed", saved, strerror(saved));
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "android_dlopen_ext(%s, flags=%d, ext_flags=%" PRIu64 ") => %s", p.c_str(), flags, extinfo.flags, buf);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeDlsym(
        JNIEnv* env, jclass, jstring handle_text, jstring symbol) {
    std::string h = jstr(env, handle_text);
    std::string sym = jstr(env, symbol);
    if (sym.empty()) return env->NewStringUTF("ERR:symbol required");
    void* handle = RTLD_DEFAULT;
    if (!h.empty() && h != "default" && h != "RTLD_DEFAULT") {
        const char* raw = h.c_str();
        if (strncmp(raw, "0x", 2) == 0 || strncmp(raw, "0X", 2) == 0) raw += 2;
        uintptr_t value = static_cast<uintptr_t>(strtoull(raw, nullptr, 16));
        handle = reinterpret_cast<void*>(value);
    }
    dlerror();
    void* addr = dlsym(handle, sym.c_str());
    const char* err = dlerror();
    char buf[1024];
    if (addr) {
        snprintf(buf, sizeof(buf), "OK:%p", addr);
    } else {
        snprintf(buf, sizeof(buf), "ERR:%s", err ? err : "unknown dlsym failure");
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dlsym(%s,%s) => %s", h.empty() ? "RTLD_DEFAULT" : h.c_str(), sym.c_str(), buf);
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



struct NativeModuleState {
    int max;
    std::string filter;
    int matched;
    int emitted;
    bool truncated;
    std::string json;
};

static int module_callback(struct dl_phdr_info* info, size_t, void* data) {
    auto* state = reinterpret_cast<NativeModuleState*>(data);
    const char* raw_name = info->dlpi_name ? info->dlpi_name : "";
    std::string name(raw_name);
    if (!state->filter.empty() && name.find(state->filter) == std::string::npos) {
        return 0;
    }
    state->matched++;
    if (state->emitted >= state->max) {
        state->truncated = true;
        return 0;
    }

    uintptr_t min_addr = UINTPTR_MAX;
    uintptr_t max_addr = 0;
    int load_segments = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD) continue;
        uintptr_t start = static_cast<uintptr_t>(info->dlpi_addr) + static_cast<uintptr_t>(phdr.p_vaddr);
        uintptr_t end = start + static_cast<uintptr_t>(phdr.p_memsz);
        if (start < min_addr) min_addr = start;
        if (end > max_addr) max_addr = end;
        load_segments++;
    }
    if (load_segments == 0) {
        min_addr = static_cast<uintptr_t>(info->dlpi_addr);
        max_addr = static_cast<uintptr_t>(info->dlpi_addr);
    }

    if (state->emitted > 0) state->json += ",";
    state->json += "{\"index\":" + std::to_string(state->emitted)
            + ",\"name\":\"" + json_escape(name) + "\""
            + ",\"base\":\"" + hex_ptr(static_cast<uintptr_t>(info->dlpi_addr)) + "\""
            + ",\"load_start\":\"" + hex_ptr(min_addr) + "\""
            + ",\"load_end\":\"" + hex_ptr(max_addr) + "\""
            + ",\"phdr_count\":" + std::to_string(info->dlpi_phnum)
            + ",\"load_segments\":" + std::to_string(load_segments)
            + "}";
    state->emitted++;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeModules(
        JNIEnv* env, jclass, jint max, jstring filter) {
    NativeModuleState state{};
    state.max = max <= 0 ? 1 : max;
    state.filter = jstr(env, filter);
    state.matched = 0;
    state.emitted = 0;
    state.truncated = false;
    state.json.reserve(8192);

    int rc = dl_iterate_phdr(module_callback, &state);
    std::string out = "{\"ok\":true,\"count\":" + std::to_string(state.emitted)
            + ",\"matched\":" + std::to_string(state.matched)
            + ",\"truncated\":" + (state.truncated ? std::string("true") : std::string("false"))
            + ",\"dl_iterate_phdr_rc\":" + std::to_string(rc)
            + ",\"filter\":" + (state.filter.empty() ? std::string("null") : (std::string("\"") + json_escape(state.filter) + "\""))
            + ",\"modules\":[" + state.json + "]}";
    return env->NewStringUTF(out.c_str());
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
