#include <jni.h>

// Deterministic dynamic JNI fixture for validating jnitrace/Frida integration.
// It is intentionally inert until explicitly loaded by a device test.
extern "C" JNIEXPORT jstring JNICALL
Java_com_luckylca_runtimeinspector_testapp_JniTraceProbe_run(JNIEnv* env, jclass) {
    jclass string_class = env->FindClass("java/lang/String");
    jstring marker = env->NewStringUTF("AUTOCRACK_JNITRACE_NATIVE_METHOD");
    if (string_class != nullptr) {
        env->DeleteLocalRef(string_class);
    }
    return marker;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm == nullptr ||
        vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return JNI_ERR;
    }

    jclass string_class = env->FindClass("java/lang/String");
    jstring marker = env->NewStringUTF("AUTOCRACK_JNITRACE_PROBE");
    if (marker != nullptr) {
        env->DeleteLocalRef(marker);
    }
    if (string_class != nullptr) {
        env->DeleteLocalRef(string_class);
    }
    return JNI_VERSION_1_6;
}
