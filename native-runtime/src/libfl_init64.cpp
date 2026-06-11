#include "fl_runtime.h"
#include "jni_helper.h"

#include <android/log.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#define TAG "FL-Init64"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr const char* kPayloadEntryClassNameUtf8 = "\xE0\xA2\xA1.\xE0\xA2\xA1";
constexpr const char* kPayloadEntryClassDescriptorLog = "L\\u08a1/\\u08a1;";

bool ensure_mock_state_visible(const FlRuntimeRequest* request, const char* log_path) {
    const char* payload_path = (request && request->payload_path) ? request->payload_path : "";
    char mock_state_path[512] = "/data/fl/metadata/mock-location-state.txt";
    const char* payload_marker = strstr(payload_path, "/payload/");
    if (payload_marker != nullptr) {
        size_t prefix_len = static_cast<size_t>(payload_marker - payload_path);
        const char* suffix = "/metadata/mock-location-state.txt";
        if (prefix_len + strlen(suffix) + 1 < sizeof(mock_state_path)) {
            memcpy(mock_state_path, payload_path, prefix_len);
            mock_state_path[prefix_len] = '\0';
            strncat(mock_state_path, suffix, sizeof(mock_state_path) - strlen(mock_state_path) - 1);
        }
    }

    FILE* state = fopen(mock_state_path, "r");
    if (state == nullptr) {
        FILE* log = fopen(log_path, "a");
        if (log) {
            fprintf(log, "shared_mock_state status=failed path=%s\n", mock_state_path);
            fclose(log);
        }
        LOGE("mock state file missing: %s", mock_state_path);
        return false;
    }
    fclose(state);

    FILE* log = fopen(log_path, "a");
    if (log) {
        fprintf(log, "shared_mock_state status=ok path=%s\n", mock_state_path);
        fclose(log);
    }
    return true;
}

// ── JVM helper: get system ClassLoader via ActivityThread ──
// Returns a local reference; caller must not delete.
jobject get_system_classloader(JNIEnv* env, const char* log_path) {
    // android.app.ActivityThread.currentActivityThread()
    jclass at_class = env->FindClass("android/app/ActivityThread");
    if (at_class == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ActivityThread class not found");
        FILE* f = fopen(log_path, "a");
        if (f) { fprintf(f, "jvm_bind_phase=find_activitythread status=failed\n"); fclose(f); }
        return nullptr;
    }
    jmethodID current_at = env->GetStaticMethodID(
        at_class, "currentActivityThread", "()Landroid/app/ActivityThread;");
    if (current_at == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("currentActivityThread() not found");
        return nullptr;
    }
    jobject at_instance = env->CallStaticObjectMethod(at_class, current_at);
    if (at_instance == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("currentActivityThread returned null");
        return nullptr;
    }

    // .getSystemContext()
    jmethodID get_sys_ctx = env->GetMethodID(
        at_class, "getSystemContext", "()Landroid/app/ContextImpl;");
    if (get_sys_ctx == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        // Try Context return type (some ROMs)
        get_sys_ctx = env->GetMethodID(
            at_class, "getSystemContext", "()Landroid/content/Context;");
    }
    if (get_sys_ctx == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getSystemContext() not found");
        return nullptr;
    }
    jobject context = env->CallObjectMethod(at_instance, get_sys_ctx);
    if (context == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getSystemContext returned null");
        return nullptr;
    }

    // .getClassLoader()
    jclass ctx_class = env->GetObjectClass(context);
    jmethodID get_cl = env->GetMethodID(
        ctx_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (get_cl == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getClassLoader() not found");
        return nullptr;
    }
    jobject class_loader = env->CallObjectMethod(context, get_cl);
    if (class_loader == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getClassLoader returned null");
        return nullptr;
    }
    LOGI("system ClassLoader obtained");
    return class_loader;
}

// ── create a DexClassLoader for the payload jar ──
jobject create_payload_classloader(
    JNIEnv* env,
    const char* payload_path,
    jobject parent_cl,
    const char* log_path)
{
    jclass dcl_class = env->FindClass("dalvik/system/DexClassLoader");
    if (dcl_class == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("DexClassLoader class not found");
        return nullptr;
    }

    // Constructor: DexClassLoader(String dexPath, String optimizedDir,
    //                              String libPath, ClassLoader parent)
    jmethodID ctor = env->GetMethodID(dcl_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (ctor == nullptr) {
        env->ExceptionClear();
        LOGE("DexClassLoader ctor not found");
        return nullptr;
    }

    jstring j_dex_path = env->NewStringUTF(payload_path);
    jstring j_opt_dir  = env->NewStringUTF("/data/fl/oat");
    jstring j_lib_path = env->NewStringUTF("/data/fl/native");

    jobject payload_cl = env->NewObject(
        dcl_class, ctor, j_dex_path, j_opt_dir, j_lib_path, parent_cl);
    if (payload_cl == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("DexClassLoader creation failed for %s", payload_path);
        return nullptr;
    }

    LOGI("DexClassLoader created for payload %s", payload_path);
    return payload_cl;
}

// Load and invoke the InitApp entry discovered in the original payload.
bool invoke_init_entry(
    JNIEnv* env,
    jobject payload_cl,
    jobject context,
    const char* log_path)
{
    // loadClass("\u08a1.\u08a1")
    jclass cl_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(
        cl_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == nullptr) {
        env->ExceptionClear();
        LOGE("ClassLoader.loadClass not found");
        return false;
    }

    jstring j_entry_name = env->NewStringUTF(kPayloadEntryClassNameUtf8);
    jobject entry_class_obj = env->CallObjectMethod(payload_cl, load_class, j_entry_name);
    if (entry_class_obj == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        // Not necessarily fatal — payload might use a different entry class.
        LOGE("%s not found in payload dex", kPayloadEntryClassDescriptorLog);
        FILE* f = fopen(log_path, "a");
        if (f) {
            fprintf(f, "jvm_bind_phase=load_entry_class status=warning "
                "detail=%s not in payload dex\n", kPayloadEntryClassDescriptorLog);
            fclose(f);
        }
        return false;
    }

    jclass entry_class = reinterpret_cast<jclass>(entry_class_obj);

    // getDeclaredMethod("i", Object.class)
    // Method signature: i(Ljava/lang/Object;)V  (per original analysis, returns void)
    jmethodID entry_method = env->GetStaticMethodID(
        entry_class, "i", "(Ljava/lang/Object;)V");
    if (entry_method == nullptr) {
        env->ExceptionClear();
        LOGE("%s.i(Object) not found", kPayloadEntryClassDescriptorLog);
        return false;
    }

    // i(null) — the Object parameter is context, but original often passes null initially
    // then the method resolves context internally.
    // We pass the system context we obtained.
    env->CallStaticVoidMethod(entry_class, entry_method, context);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("%s.i() threw exception", kPayloadEntryClassDescriptorLog);
        return false;
    }

    LOGI("InitApp entry %s.i() invoked successfully", kPayloadEntryClassDescriptorLog);
    return true;
}

}  // namespace

extern "C" int fl_loader_entry(const FlRuntimeRequest* request, FlRuntimeResult* result) {
    if (request == nullptr || result == nullptr) {
        return 71;
    }

    const char* log_path = request->log_path ? request->log_path : "";
    FILE* log = fopen(log_path, "a");
    auto log_line = [&](const char* fmt, ...) {
        if (log) {
            va_list args;
            va_start(args, fmt);
            vfprintf(log, fmt, args);
            va_end(args);
            fputc('\n', log);
        }
    };

    log_line("loader=libfl_init64 stage=%s target=%s pid=%d entry=%s",
        request->stage, request->target_process, request->target_pid, request->entrypoint);
    if (!ensure_mock_state_visible(request, log_path)) {
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_init64: shared mock state unavailable");
        result->code = 90;
        return 90;
    }

    // ── Phase 1: Get JavaVM and attach ──
    // JNI_GetCreatedJavaVMs is resolved at runtime via dlsym to avoid a
    // link-time dependency (the symbol lives in ART, not in any NDK lib).
    auto jni_get_vms = resolve_jni_get_created_java_vms();
    if (!jni_get_vms) {
        log_line("jvm_bind_phase=get_jvm status=failed detail=JNI_GetCreatedJavaVMs not resolvable via dlsym");
        LOGE("JNI_GetCreatedJavaVMs not resolvable via dlsym");
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_init64: cannot resolve JNI_GetCreatedJavaVMs in process %s", request->target_process);
        result->code = 91;
        return 91;
    }
    JavaVM* jvm = nullptr;
    jsize vm_count = 0;
    jint jni_err = jni_get_vms(&jvm, 1, &vm_count);
    if (jni_err != JNI_OK || vm_count == 0) {
        log_line("jvm_bind_phase=get_jvm status=failed detail=JNI_GetCreatedJavaVMs err=%d count=%d",
            jni_err, vm_count);
        LOGE("JNI_GetCreatedJavaVMs failed: err=%d count=%d", jni_err, vm_count);
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_init64: no JVM in process %s", request->target_process);
        result->code = 91;
        return 91;
    }
    log_line("jvm_bind_phase=get_jvm status=ok detail=vm_count=%d", vm_count);
    LOGI("JavaVM obtained, count=%d", vm_count);

    // ── Phase 2: Attach thread to JVM ──
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env == JNI_EDETACHED) {
        JavaVMAttachArgs attach_args = {};
        attach_args.version = JNI_VERSION_1_6;
        attach_args.name = const_cast<char*>("FL-Init64-Loader");
        if (jvm->AttachCurrentThread(&env, &attach_args) != JNI_OK) {
            log_line("jvm_bind_phase=attach status=failed detail=AttachCurrentThread failed");
            LOGE("AttachCurrentThread failed");
            if (log) fclose(log);
            snprintf(result->message, sizeof(result->message),
                "libfl_init64: cannot attach to JVM");
            result->code = 92;
            return 92;
        }
        need_detach = true;
        log_line("jvm_bind_phase=attach status=ok detail=thread attached");
    } else if (get_env == JNI_OK) {
        log_line("jvm_bind_phase=attach status=ok detail=already attached");
    } else {
        log_line("jvm_bind_phase=attach status=failed detail=GetEnv err=%d", get_env);
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_init64: GetEnv failed with %d", get_env);
        result->code = 93;
        return 93;
    }

    // ── Phase 3: Get system ClassLoader ──
    jobject sys_cl = get_system_classloader(env, log_path);
    if (sys_cl == nullptr) {
        log_line("jvm_bind_phase=classloader status=failed detail=unable to get system ClassLoader");
        if (need_detach) jvm->DetachCurrentThread();
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_init64: system ClassLoader unavailable");
        result->code = 94;
        return 94;
    }
    log_line("jvm_bind_phase=classloader status=ok");

    // ── Phase 4: Load payload via DexClassLoader ──
    jobject payload_cl = create_payload_classloader(
        env, request->payload_path, sys_cl, log_path);
    if (payload_cl == nullptr) {
        log_line("jvm_bind_phase=payload_load status=failed detail=DexClassLoader creation failed");
        if (need_detach) jvm->DetachCurrentThread();
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_init64: cannot create DexClassLoader for %s", request->payload_path);
        result->code = 95;
        return 95;
    }
    log_line("jvm_bind_phase=payload_load status=ok detail=payload=%s", request->payload_path);

    // ── Phase 5: Get system Context ──
    jclass at_class = env->FindClass("android/app/ActivityThread");
    jmethodID current_at = env->GetStaticMethodID(
        at_class, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject at_instance = env->CallStaticObjectMethod(at_class, current_at);
    jmethodID get_sys_ctx = env->GetMethodID(
        at_class, "getSystemContext", "()Landroid/app/ContextImpl;");
    if (get_sys_ctx == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        get_sys_ctx = env->GetMethodID(
            at_class, "getSystemContext", "()Landroid/content/Context;");
    }
    jobject sys_context = env->CallObjectMethod(at_instance, get_sys_ctx);

    // ── Phase 6: Invoke InitApp payload entry ──
    bool invoked = invoke_init_entry(env, payload_cl, sys_context, log_path);
    log_line("jvm_bind_phase=entry_invoke status=%s detail=InitApp %s.i()",
        invoked ? "ok" : "warning",
        kPayloadEntryClassDescriptorLog);

    // ── Phase 7: Load and invoke the native hook bridge ──
    // Only install hooks if the payload entry succeeded — otherwise the
    // service registration and data plumbing are not ready.
    if (invoked && request->hook_bridge_path && request->hook_bridge_path[0]) {
        void* lh64 = dlopen(request->hook_bridge_path, RTLD_NOW);
        if (lh64) {
            auto install_fn = reinterpret_cast<int(*)(const char*, const char*)>(
                dlsym(lh64, "flh_install_hooks"));
            if (install_fn) {
                int n = install_fn(request->stage, request->log_path);
                log_line("jvm_bind_phase=hook_bridge status=%s detail=installed %d hooks",
                    n > 0 ? "ok" : "warning", n);
            } else {
                log_line("jvm_bind_phase=hook_bridge status=warning "
                    "detail=dlsym flh_install_hooks failed: %s", dlerror());
            }
        } else {
            log_line("jvm_bind_phase=hook_bridge status=warning "
                "detail=dlopen liblh64 failed: %s", dlerror());
        }
    } else if (!invoked) {
        log_line("jvm_bind_phase=hook_bridge status=skipped "
            "detail=payload entry did not succeed");
    }

    // ── Cleanup ──
    if (need_detach) {
        jvm->DetachCurrentThread();
        log_line("jvm_bind_phase=detach status=ok");
    }

    if (log) fclose(log);
    snprintf(result->message, sizeof(result->message),
        "libfl_init64: InitApp JVM bind complete for %s pid=%d invoked=%s",
        request->target_process, request->target_pid, invoked ? "true" : "false");
    result->code = 0;
    LOGI("InitApp loader complete, invoked=%s", invoked ? "true" : "false");
    return 0;
}
