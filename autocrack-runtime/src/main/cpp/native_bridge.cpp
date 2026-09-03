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

struct ModuleLoadSegment {
    uintptr_t start;
    uintptr_t end;
    bool readable;
    bool executable;
};

struct ModuleScanState {
    std::string needle;
    std::string name;
    uintptr_t base = 0;
    std::vector<ModuleLoadSegment> segments;
};

static int module_scan_callback(struct dl_phdr_info* info, size_t, void* data) {
    auto* state = reinterpret_cast<ModuleScanState*>(data);
    std::string name = info->dlpi_name ? info->dlpi_name : "";
    if (name.find(state->needle) == std::string::npos) return 0;
    state->name = name;
    state->base = static_cast<uintptr_t>(info->dlpi_addr);
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || ph.p_memsz == 0) continue;
        uintptr_t start = state->base + static_cast<uintptr_t>(ph.p_vaddr);
        uintptr_t end = start + static_cast<uintptr_t>(ph.p_memsz);
        if (end <= start) continue;
        state->segments.push_back({start, end, (ph.p_flags & PF_R) != 0, (ph.p_flags & PF_X) != 0});
    }
    return 1;
}

static bool address_in_segments(uintptr_t address, size_t size,
                                const std::vector<ModuleLoadSegment>& segments,
                                bool require_exec = false) {
    if (size == 0) return false;
    uintptr_t end = address + size;
    if (end < address) return false;
    for (const auto& seg : segments) {
        if (!seg.readable) continue;
        if (require_exec && !seg.executable) continue;
        if (address >= seg.start && end <= seg.end) return true;
    }
    return false;
}

static bool module_cstring_equals(uintptr_t address, const char* expected,
                                  const std::vector<ModuleLoadSegment>& segments) {
    if (!expected) return false;
    size_t len = strlen(expected);
    if (!address_in_segments(address, len + 1, segments, false)) return false;
    const char* actual = reinterpret_cast<const char*>(address);
    return memcmp(actual, expected, len) == 0 && actual[len] == '\0';
}

static const char* kXmlBlockNativeNames[] = {
    "nativeCreate", "nativeGetStringBlock", "nativeCreateParseState", "nativeDestroyParseState",
    "nativeDestroy", "nativeNext", "nativeGetNamespace", "nativeGetName", "nativeGetText",
    "nativeGetLineNumber", "nativeGetAttributeCount", "nativeGetAttributeNamespace",
    "nativeGetAttributeName", "nativeGetAttributeResource", "nativeGetAttributeDataType",
    "nativeGetAttributeData", "nativeGetAttributeStringValue", "nativeGetAttributeIndex",
    "nativeGetIdAttribute", "nativeGetClassAttribute", "nativeGetStyleAttribute", "nativeGetSourceResId"
};
static const char* kXmlBlockNativeSigs[] = {
    "([BII)J", "(J)J", "(JI)J", "(J)V", "(J)V", "(J)I", "(J)I", "(J)I", "(J)I",
    "(J)I", "(J)I", "(JI)I", "(JI)I", "(JI)I", "(JI)I", "(JI)I", "(JI)I",
    "(JLjava/lang/String;Ljava/lang/String;)I", "(J)I", "(J)I", "(J)I", "(J)I"
};
static constexpr size_t kXmlBlockNativeCount = sizeof(kXmlBlockNativeNames) / sizeof(kXmlBlockNativeNames[0]);

static const uintptr_t* find_xmlblock_native_table(ModuleScanState* output_state, bool* output_all_exec) {
    ModuleScanState state{};
    state.needle = "libandroid_runtime.so";
    dl_iterate_phdr(module_scan_callback, &state);
    if (output_state) *output_state = state;
    if (output_all_exec) *output_all_exec = false;
    if (state.segments.empty()) return nullptr;

    for (const auto& seg : state.segments) {
        if (!seg.readable || seg.executable) continue;
        uintptr_t start = (seg.start + alignof(uintptr_t) - 1) & ~(static_cast<uintptr_t>(alignof(uintptr_t) - 1));
        size_t table_bytes = kXmlBlockNativeCount * 3 * sizeof(uintptr_t);
        if (seg.end <= start || seg.end - start < table_bytes) continue;
        for (uintptr_t cursor = start; cursor + table_bytes <= seg.end; cursor += sizeof(uintptr_t)) {
            const auto* entries = reinterpret_cast<const uintptr_t*>(cursor);
            if (!module_cstring_equals(entries[0], kXmlBlockNativeNames[0], state.segments)
                    || !module_cstring_equals(entries[1], kXmlBlockNativeSigs[0], state.segments)) continue;
            bool valid = true;
            bool all_exec = true;
            for (size_t i = 0; i < kXmlBlockNativeCount; ++i) {
                uintptr_t name = entries[i * 3];
                uintptr_t sig = entries[i * 3 + 1];
                uintptr_t fn = entries[i * 3 + 2];
                if (!module_cstring_equals(name, kXmlBlockNativeNames[i], state.segments)
                        || !module_cstring_equals(sig, kXmlBlockNativeSigs[i], state.segments)
                        || fn == 0) {
                    valid = false;
                    break;
                }
                if (!address_in_segments(fn, 1, state.segments, true)) all_exec = false;
            }
            if (valid) {
                if (output_all_exec) *output_all_exec = all_exec;
                return entries;
            }
        }
    }
    return nullptr;
}

static std::string xmlblock_backend_probe_json() {
    ModuleScanState state{};
    bool all_exec = false;
    const uintptr_t* table = find_xmlblock_native_table(&state, &all_exec);
    if (state.segments.empty()) {
        return "{\"ok\":false,\"supported\":false,\"reason\":\"libandroid_runtime.so is not visible in dl_iterate_phdr\"}";
    }
    void* reg = dlsym(RTLD_DEFAULT, "_ZN7android33register_android_content_XmlBlockEP7_JNIEnv");
    std::string json = "{\"ok\":true,\"supported\":true,\"module\":\"" + json_escape(state.name)
            + "\",\"register_symbol_resolved\":" + (reg ? std::string("true") : std::string("false"))
            + ",\"table_found\":" + (table ? std::string("true") : std::string("false"))
            + ",\"expected_method_count\":" + std::to_string(kXmlBlockNativeCount)
            + ",\"method_count\":" + std::to_string(table ? kXmlBlockNativeCount : 0)
            + ",\"all_functions_in_executable_segment\":" + (table && all_exec ? std::string("true") : std::string("false"))
            + ",\"methods\":[";
    if (table) {
        for (size_t i = 0; i < kXmlBlockNativeCount; ++i) {
            if (i) json += ",";
            json += "{\"name\":\"" + std::string(kXmlBlockNativeNames[i]) + "\",\"signature\":\"" + json_escape(kXmlBlockNativeSigs[i]) + "\"}";
        }
    }
    json += "]}";
    return json;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeXmlBlockBackendProbe(
        JNIEnv* env, jclass) {
    std::string json = xmlblock_backend_probe_json();
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeXmlBlockReplay(
        JNIEnv* env, jclass, jbyteArray axml, jint resource_id, jint max_events, jint max_attributes) {
    if (!axml) return env->NewStringUTF("{\"ok\":false,\"error\":\"AXML_BYTES_REQUIRED\"}");
    jsize length = env->GetArrayLength(axml);
    if (length <= 0 || length > 4 * 1024 * 1024) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"AXML_SIZE_OUT_OF_RANGE\"}");
    }
    int bounded_events = max_events < 1 ? 1 : (max_events > 2048 ? 2048 : max_events);
    int bounded_attributes = max_attributes < 0 ? 0 : (max_attributes > 512 ? 512 : max_attributes);

    ModuleScanState state{};
    bool all_exec = false;
    const uintptr_t* table = find_xmlblock_native_table(&state, &all_exec);
    if (!table || !all_exec) {
        return env->NewStringUTF("{\"ok\":false,\"supported\":false,\"error\":\"XMLBLOCK_NATIVE_TABLE_UNAVAILABLE\"}");
    }

    using CreateFn = jlong (*)(JNIEnv*, jclass, jbyteArray, jint, jint);
    using CreateParseFn = jlong (*)(JNIEnv*, jclass, jlong, jint);
    using DestroyFn = void (*)(JNIEnv*, jclass, jlong);
    using CriticalInt1Fn = jint (*)(jlong);
    using CriticalInt2Fn = jint (*)(jlong, jint);
    auto create = reinterpret_cast<CreateFn>(table[0 * 3 + 2]);
    auto create_parse = reinterpret_cast<CreateParseFn>(table[2 * 3 + 2]);
    auto destroy_parse = reinterpret_cast<DestroyFn>(table[3 * 3 + 2]);
    auto destroy_tree = reinterpret_cast<DestroyFn>(table[4 * 3 + 2]);
    auto next = reinterpret_cast<CriticalInt1Fn>(table[5 * 3 + 2]);
    auto namespace_index = reinterpret_cast<CriticalInt1Fn>(table[6 * 3 + 2]);
    auto name_index = reinterpret_cast<CriticalInt1Fn>(table[7 * 3 + 2]);
    auto text_index = reinterpret_cast<CriticalInt1Fn>(table[8 * 3 + 2]);
    auto line_number = reinterpret_cast<CriticalInt1Fn>(table[9 * 3 + 2]);
    auto attribute_count = reinterpret_cast<CriticalInt1Fn>(table[10 * 3 + 2]);
    auto attribute_namespace = reinterpret_cast<CriticalInt2Fn>(table[11 * 3 + 2]);
    auto attribute_name = reinterpret_cast<CriticalInt2Fn>(table[12 * 3 + 2]);
    auto attribute_resource = reinterpret_cast<CriticalInt2Fn>(table[13 * 3 + 2]);
    auto attribute_data_type = reinterpret_cast<CriticalInt2Fn>(table[14 * 3 + 2]);
    auto attribute_data = reinterpret_cast<CriticalInt2Fn>(table[15 * 3 + 2]);
    auto attribute_string_value = reinterpret_cast<CriticalInt2Fn>(table[16 * 3 + 2]);
    auto id_attribute = reinterpret_cast<CriticalInt1Fn>(table[18 * 3 + 2]);
    auto class_attribute = reinterpret_cast<CriticalInt1Fn>(table[19 * 3 + 2]);
    auto style_attribute = reinterpret_cast<CriticalInt1Fn>(table[20 * 3 + 2]);
    auto source_res_id = reinterpret_cast<CriticalInt1Fn>(table[21 * 3 + 2]);

    jlong tree = 0;
    jlong parser = 0;
    bool tree_created = false;
    bool parser_created = false;
    std::string events_json;
    int event_count = 0;
    bool ended = false;
    std::string error;

    tree = create(env, nullptr, axml, 0, length);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        error = "nativeCreate raised a Java exception";
    } else if (tree == 0) {
        error = "nativeCreate returned null";
    } else {
        tree_created = true;
        parser = create_parse(env, nullptr, tree, resource_id);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            error = "nativeCreateParseState raised a Java exception";
        } else if (parser == 0) {
            error = "nativeCreateParseState returned null";
        } else {
            parser_created = true;
            for (int i = 0; i < bounded_events; ++i) {
                jint event = next(parser);
                jint line = line_number(parser);
                jint ns = (event == 2 || event == 3) ? namespace_index(parser) : -1;
                jint name = (event == 2 || event == 3) ? name_index(parser) : -1;
                jint text = event == 4 ? text_index(parser) : -1;
                jint attrs = event == 2 ? attribute_count(parser) : 0;
                jint source = source_res_id(parser);
                std::string attrs_json;
                int emitted_attrs = 0;
                if (event == 2 && attrs > 0 && bounded_attributes > 0) {
                    int limit = attrs < bounded_attributes ? attrs : bounded_attributes;
                    for (int a = 0; a < limit; ++a) {
                        if (emitted_attrs > 0) attrs_json += ",";
                        attrs_json += "{\"index\":" + std::to_string(a)
                                + ",\"namespace_index\":" + std::to_string(attribute_namespace(parser, a))
                                + ",\"name_index\":" + std::to_string(attribute_name(parser, a))
                                + ",\"resource_id\":" + std::to_string(attribute_resource(parser, a))
                                + ",\"data_type\":" + std::to_string(attribute_data_type(parser, a))
                                + ",\"data\":" + std::to_string(attribute_data(parser, a))
                                + ",\"string_value_index\":" + std::to_string(attribute_string_value(parser, a)) + "}";
                        emitted_attrs++;
                    }
                }
                if (event_count > 0) events_json += ",";
                events_json += "{\"index\":" + std::to_string(i)
                        + ",\"event\":" + std::to_string(event)
                        + ",\"line\":" + std::to_string(line)
                        + ",\"namespace_index\":" + std::to_string(ns)
                        + ",\"name_index\":" + std::to_string(name)
                        + ",\"text_index\":" + std::to_string(text)
                        + ",\"source_res_id\":" + std::to_string(source)
                        + ",\"attribute_count\":" + std::to_string(attrs)
                        + ",\"attributes_truncated\":" + (attrs > emitted_attrs ? std::string("true") : std::string("false"))
                        + ",\"attributes\":[" + attrs_json + "]";
                if (event == 2) {
                    events_json += ",\"id_attribute_index\":" + std::to_string(id_attribute(parser))
                            + ",\"class_attribute_index\":" + std::to_string(class_attribute(parser))
                            + ",\"style_attribute_data\":" + std::to_string(style_attribute(parser));
                }
                events_json += "}";
                event_count++;
                if (event == 1 || event < 0) {
                    ended = event == 1;
                    break;
                }
            }
        }
    }

    if (parser_created) destroy_parse(env, nullptr, parser);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (tree_created) destroy_tree(env, nullptr, tree);
    if (env->ExceptionCheck()) env->ExceptionClear();

    std::string json = "{\"ok\":" + std::string(error.empty() ? "true" : "false")
            + ",\"native_backend_replay\":true"
            + ",\"existing_peer_reconstruction\":false"
            + ",\"module\":\"" + json_escape(state.name) + "\""
            + ",\"tree_created\":" + (tree_created ? std::string("true") : std::string("false"))
            + ",\"parser_created\":" + (parser_created ? std::string("true") : std::string("false"))
            + ",\"event_count\":" + std::to_string(event_count)
            + ",\"end_document_reached\":" + (ended ? std::string("true") : std::string("false"))
            + ",\"events\":[" + events_json + "]";
    if (!error.empty()) json += ",\"error\":\"" + json_escape(error) + "\"";
    json += "}";
    return env->NewStringUTF(json.c_str());
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

static void clear_pending(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static bool get_long_field(JNIEnv* env, jobject object, const char* field_name, jlong* out) {
    if (!object || !out) return false;
    jclass cls = env->GetObjectClass(object);
    if (!cls) { clear_pending(env); return false; }
    jfieldID field = env->GetFieldID(cls, field_name, "J");
    if (!field) { clear_pending(env); env->DeleteLocalRef(cls); return false; }
    *out = env->GetLongField(object, field);
    bool ok = !env->ExceptionCheck();
    clear_pending(env);
    env->DeleteLocalRef(cls);
    return ok;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_autocrack_runtime_shared_NativeBridge_nativeXmlBlockPeerProbe(
        JNIEnv* env, jclass, jobject parser, jobject block) {
    // Deliberately fixed whitelist: android.content.res.XmlBlock$Parser.mParseState and XmlBlock.mNative only.
    jlong parse_state = 0;
    jlong block_native = 0;
    bool parse_field = get_long_field(env, parser, "mParseState", &parse_state);
    bool native_field = get_long_field(env, block, "mNative", &block_native);
    char buf[1024];
    snprintf(buf, sizeof(buf),
             "{\"ok\":true,\"whitelisted\":true,\"parser_mParseState_lookup_succeeded\":%s,\"parser_mParseState_nonzero\":%s,\"block_mNative_lookup_succeeded\":%s,\"block_mNative_nonzero\":%s,\"field_absence_proven\":false,\"lookup_warning\":\"JNI GetFieldID is subject to Android hidden-API filtering; a failed lookup does not prove the field is absent\"}",
             parse_field ? "true" : "false", (parse_field && parse_state != 0) ? "true" : "false",
             native_field ? "true" : "false", (native_field && block_native != 0) ? "true" : "false");
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
