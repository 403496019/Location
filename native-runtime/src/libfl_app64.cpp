#include "fl_runtime.h"
#include "jni_helper.h"

#include <android/log.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#define TAG "FL-App64"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ── Get ClassLoader from com.android.phone process ──
// In phone process we use ActivityThread.getApplication().getClassLoader()
jobject get_app_classloader(JNIEnv* env, const char* log_path) {
    jclass at_class = env->FindClass("android/app/ActivityThread");
    if (at_class == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ActivityThread class not found");
        return nullptr;
    }

    // currentActivityThread()
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

    // .getApplication() → Application (has its own ClassLoader)
    jmethodID get_app = env->GetMethodID(
        at_class, "getApplication", "()Landroid/app/Application;");
    if (get_app == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getApplication() not found");
        // Fallback: try getSystemContext
        jmethodID get_sys_ctx = env->GetMethodID(
            at_class, "getSystemContext", "()Landroid/content/Context;");
        if (get_sys_ctx == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        jobject ctx = env->CallObjectMethod(at_instance, get_sys_ctx);
        jclass ctx_class = env->GetObjectClass(ctx);
        jmethodID get_cl = env->GetMethodID(
            ctx_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
        return env->CallObjectMethod(ctx, get_cl);
    }

    jobject app = env->CallObjectMethod(at_instance, get_app);
    if (app == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getApplication returned null");
        return nullptr;
    }

    // .getClassLoader()
    jclass app_class = env->GetObjectClass(app);
    jmethodID get_cl = env->GetMethodID(
        app_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (get_cl == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("getClassLoader() not found on Application");
        return nullptr;
    }
    jobject cl = env->CallObjectMethod(app, get_cl);
    LOGI("App ClassLoader obtained");
    return cl;
}

// ── Create DexClassLoader for payload ──
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
    jmethodID ctor = env->GetMethodID(dcl_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (ctor == nullptr) {
        env->ExceptionClear();
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
    LOGI("DexClassLoader created for AppHook payload");
    return payload_cl;
}

// ── Invoke AppHook entry: p000.C0091.ha(Context) ──
bool invoke_apphook_entry(
    JNIEnv* env,
    jobject payload_cl,
    jobject context,
    const char* log_path)
{
    jclass cl_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(
        cl_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == nullptr) {
        env->ExceptionClear();
        LOGE("ClassLoader.loadClass not found");
        return false;
    }

    jstring j_entry_name = env->NewStringUTF("p000.C0091");
    jobject entry_class_obj = env->CallObjectMethod(payload_cl, load_class, j_entry_name);
    if (entry_class_obj == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("p000.C0091 not found in payload dex (reproduction mode)");
        FILE* f = fopen(log_path, "a");
        if (f) {
            fprintf(f, "jvm_bind_phase=load_entry_class status=warning "
                "detail=p000.C0091 not in AppHook payload — reproduction mode\n");
            fclose(f);
        }
        return false;
    }

    jclass entry_class = reinterpret_cast<jclass>(entry_class_obj);

    // getDeclaredMethod("ha", Object.class)
    // Original: ha(Object) → C0033.m152(Context)
    jmethodID entry_method = env->GetStaticMethodID(
        entry_class, "ha", "(Ljava/lang/Object;)V");
    if (entry_method == nullptr) {
        env->ExceptionClear();
        LOGE("p000.C0091.ha(Object) not found");
        return false;
    }

    env->CallStaticVoidMethod(entry_class, entry_method, context);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("p000.C0091.ha() threw exception");
        return false;
    }

    LOGI("AppHook entry p000.C0091.ha() invoked successfully");
    return true;
}

}  // namespace

extern "C" int fl_loader_entry(const FlRuntimeRequest* request, FlRuntimeResult* result) {
    if (request == nullptr || result == nullptr) {
        return 72;
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

    log_line("loader=libfl_app64 stage=%s target=%s pid=%d entry=%s",
        request->stage, request->target_process, request->target_pid, request->entrypoint);

    // ── Phase 1: Get JavaVM ──
    // Resolved at runtime via dlsym — no link-time dependency.
    auto jni_get_vms = resolve_jni_get_created_java_vms();
    if (!jni_get_vms) {
        log_line("jvm_bind_phase=get_jvm status=failed detail=JNI_GetCreatedJavaVMs not resolvable");
        LOGE("JNI_GetCreatedJavaVMs not resolvable via dlsym");
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_app64: cannot resolve JNI_GetCreatedJavaVMs in process %s", request->target_process);
        result->code = 91;
        return 91;
    }
    JavaVM* jvm = nullptr;
    jsize vm_count = 0;
    jint jni_err = jni_get_vms(&jvm, 1, &vm_count);
    if (jni_err != JNI_OK || vm_count == 0) {
        log_line("jvm_bind_phase=get_jvm status=failed detail=err=%d count=%d", jni_err, vm_count);
        LOGE("JNI_GetCreatedJavaVMs failed: err=%d count=%d", jni_err, vm_count);
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_app64: no JVM in process %s", request->target_process);
        result->code = 91;
        return 91;
    }
    log_line("jvm_bind_phase=get_jvm status=ok detail=vm_count=%d", vm_count);

    // ── Phase 2: Attach thread ──
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env == JNI_EDETACHED) {
        JavaVMAttachArgs attach_args = {};
        attach_args.version = JNI_VERSION_1_6;
        attach_args.name = const_cast<char*>("FL-App64-Loader");
        if (jvm->AttachCurrentThread(&env, &attach_args) != JNI_OK) {
            log_line("jvm_bind_phase=attach status=failed");
            if (log) fclose(log);
            snprintf(result->message, sizeof(result->message),
                "libfl_app64: cannot attach to JVM");
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
            "libfl_app64: GetEnv failed with %d", get_env);
        result->code = 93;
        return 93;
    }

    // ── Phase 3: Get app ClassLoader ──
    jobject app_cl = get_app_classloader(env, log_path);
    if (app_cl == nullptr) {
        log_line("jvm_bind_phase=classloader status=failed");
        if (need_detach) jvm->DetachCurrentThread();
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_app64: app ClassLoader unavailable");
        result->code = 94;
        return 94;
    }
    log_line("jvm_bind_phase=classloader status=ok");

    // ── Phase 4: Load payload DexClassLoader ──
    jobject payload_cl = create_payload_classloader(
        env, request->payload_path, app_cl, log_path);
    if (payload_cl == nullptr) {
        log_line("jvm_bind_phase=payload_load status=failed");
        if (need_detach) jvm->DetachCurrentThread();
        if (log) fclose(log);
        snprintf(result->message, sizeof(result->message),
            "libfl_app64: cannot create DexClassLoader for %s", request->payload_path);
        result->code = 95;
        return 95;
    }
    log_line("jvm_bind_phase=payload_load status=ok detail=payload=%s", request->payload_path);

    // ── Phase 5: Get app Context ──
    jclass at_class = env->FindClass("android/app/ActivityThread");
    jmethodID current_at = env->GetStaticMethodID(
        at_class, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject at_instance = env->CallStaticObjectMethod(at_class, current_at);
    jmethodID get_app = env->GetMethodID(
        at_class, "getApplication", "()Landroid/app/Application;");
    jobject app_context = nullptr;
    if (get_app != nullptr && !env->ExceptionCheck()) {
        app_context = env->CallObjectMethod(at_instance, get_app);
    }
    if (app_context == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        // Fallback: system context
        jmethodID get_sys_ctx = env->GetMethodID(
            at_class, "getSystemContext", "()Landroid/content/Context;");
        if (get_sys_ctx != nullptr && !env->ExceptionCheck()) {
            app_context = env->CallObjectMethod(at_instance, get_sys_ctx);
        } else {
            env->ExceptionClear();
        }
    }

    // ── Phase 6: Invoke AppHook entry ──
    bool invoked = invoke_apphook_entry(env, payload_cl, app_context, log_path);
    log_line("jvm_bind_phase=entry_invoke status=%s detail=AppHook p000.C0091.ha()",
        invoked ? "ok" : "warning");

    // ── Phase 7: Load and invoke the native hook bridge ──
    // Only install hooks if the payload entry succeeded.
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

    if (need_detach) {
        jvm->DetachCurrentThread();
        log_line("jvm_bind_phase=detach status=ok");
    }
    if (log) fclose(log);

    snprintf(result->message, sizeof(result->message),
        "libfl_app64: AppHook JVM bind complete for %s pid=%d invoked=%s",
        request->target_process, request->target_pid, invoked ? "true" : "false");
    result->code = 0;
    LOGI("AppHook loader complete, invoked=%s", invoked ? "true" : "false");
    return 0;
}
