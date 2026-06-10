#include "fl_runtime.h"
#include "jni_helper.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <sys/system_properties.h>

#define TAG "FL-LH64"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════
//  Global mock data store (shared with Java side)
// ═══════════════════════════════════════════════════════

struct MockLocationData {
    bool active = false;
    double latitude = 31.2304;
    double longitude = 121.4737;
    double altitude = 12.0;
    float accuracy = 8.0f;
    char provider[32] = "gps";
    int64_t timestamp_millis = 0;
};

static MockLocationData g_mock_location;

// ═══════════════════════════════════════════════════════
//  Runtime metadata
// ═══════════════════════════════════════════════════════

static int g_sdk_int = 0;

struct InstalledHook {
    jclass    target_class;       // global ref
    jmethodID method_id;
    void*     original_native;    // saved JNI native binding (if any)
    char      class_name[256];
    char      method_name[128];
    char      signature[128];
    int       original_access_flags;
};

#define MAX_HOOKS 32
static InstalledHook g_hooks[MAX_HOOKS];
static int g_hook_count = 0;

// Original entrypoints saved during install — used by trampolines to
// forward calls when mock data is not active (passthrough mode).
static void* g_orig_getLastLocation = nullptr;
static void* g_orig_getLastLocation_req = nullptr;
static void* g_orig_getAllCellInfo = nullptr;
static void* g_orig_getScanResults = nullptr;

// ── Global JVM (cached during install, used by trampolines) ──
static JavaVM* g_jvm = nullptr;
static JNIEnv* get_env_for_trampoline();  // forward — defined below after jni.h types settle

// ── SDK probe ──
static int probe_sdk() {
    if (g_sdk_int == 0) {
        char sdk[PROP_VALUE_MAX] = {};
        if (__system_property_get("ro.build.version.sdk", sdk) > 0) {
            g_sdk_int = atoi(sdk);
        }
        LOGI("Probed SDK = %d", g_sdk_int);
    }
    return g_sdk_int;
}

// ── Build a mock android.location.Location via JNI ──
static jobject build_mock_location(JNIEnv* env) {
    jclass loc_class = env->FindClass("android/location/Location");
    if (!loc_class || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    // ctor: Location(String provider)
    jmethodID ctor = env->GetMethodID(loc_class, "<init>", "(Ljava/lang/String;)V");
    if (!ctor) { env->ExceptionClear(); return nullptr; }

    jstring provider = env->NewStringUTF(g_mock_location.provider);
    jobject location = env->NewObject(loc_class, ctor, provider);
    if (!location) return nullptr;

    // Set fields
    auto call_void_d = [&](const char* name, double v) {
        jmethodID m = env->GetMethodID(loc_class, name, "(D)V");
        if (m) env->CallVoidMethod(location, m, v);
        env->ExceptionClear();
    };
    auto call_void_f = [&](const char* name, float v) {
        jmethodID m = env->GetMethodID(loc_class, name, "(F)V");
        if (m) env->CallVoidMethod(location, m, v);
        env->ExceptionClear();
    };

    call_void_d("setLatitude",  g_mock_location.latitude);
    call_void_d("setLongitude", g_mock_location.longitude);
    call_void_d("setAltitude",  g_mock_location.altitude);
    call_void_f("setAccuracy",  g_mock_location.accuracy);

    jmethodID set_time = env->GetMethodID(loc_class, "setTime", "(J)V");
    if (set_time) {
        int64_t ts = g_mock_location.timestamp_millis;
        if (ts == 0) {
            struct timespec tp;
            clock_gettime(CLOCK_REALTIME, &tp);
            ts = tp.tv_sec * 1000LL + tp.tv_nsec / 1000000LL;
        }
        env->CallVoidMethod(location, set_time, ts);
    }
    env->ExceptionClear();

    return location;
}

// ═══════════════════════════════════════════════════════
//  Trampolines — called by ART as managed-code replacements
//
//  On aarch64 the C calling convention (x0=arg1, x1=arg2, ...,
//  return in x0) is ABI-compatible with ART's quick-compiled
//  managed code for reference-typed args/returns.
//  Each trampoline gets JNIEnv via the cached g_jvm.
// ═══════════════════════════════════════════════════════

// Trampoline: Location getLastLocation()
//   managed: x0 = this  →  x0 = Location*
//   C:       void* fn(void* this)
static void* trampoline_getLastLocation(void* receiver) {
    if (!g_mock_location.active) {
        auto orig = reinterpret_cast<void*(*)(void*)>(g_orig_getLastLocation);
        if (orig) return orig(receiver);
        return nullptr;
    }
    JNIEnv* env = get_env_for_trampoline();
    if (!env) return nullptr;
    LOGI("TRAMP getLastLocation mock active, returning fake Location");
    return build_mock_location(env);
}

// Trampoline: Location getLastLocation(LocationRequest, String)
//   managed: x0=this, x1=LocationRequest, x2=String  →  x0=Location*
static void* trampoline_getLastLocation_req(
    void* receiver, void* request, void* packageName)
{
    if (!g_mock_location.active) {
        auto orig = reinterpret_cast<void*(*)(void*,void*,void*)>(g_orig_getLastLocation_req);
        if (orig) return orig(receiver, request, packageName);
        return nullptr;
    }
    JNIEnv* env = get_env_for_trampoline();
    if (!env) return nullptr;
    LOGI("TRAMP getLastLocation(req,pkg) mock active");
    return build_mock_location(env);
}

// Trampoline: List<CellInfo> getAllCellInfo()
static void* trampoline_getAllCellInfo(void* receiver) {
    if (!g_mock_location.active) {
        auto orig = reinterpret_cast<void*(*)(void*)>(g_orig_getAllCellInfo);
        if (orig) return orig(receiver);
        return nullptr;
    }
    JNIEnv* env = get_env_for_trampoline();
    if (!env) return nullptr;
    LOGI("TRAMP getAllCellInfo mock active — returning empty list");
    jclass arraylist = env->FindClass("java/util/ArrayList");
    jmethodID ctor = env->GetMethodID(arraylist, "<init>", "()V");
    jobject empty_list = env->NewObject(arraylist, ctor);
    env->ExceptionClear();
    return empty_list;
}

// Trampoline: List<ScanResult> getScanResults()
static void* trampoline_getScanResults(void* receiver) {
    if (!g_mock_location.active) {
        auto orig = reinterpret_cast<void*(*)(void*)>(g_orig_getScanResults);
        if (orig) return orig(receiver);
        return nullptr;
    }
    JNIEnv* env = get_env_for_trampoline();
    if (!env) return nullptr;
    LOGI("TRAMP getScanResults mock active — returning empty list");
    jclass arraylist = env->FindClass("java/util/ArrayList");
    jmethodID ctor = env->GetMethodID(arraylist, "<init>", "()V");
    jobject empty_list = env->NewObject(arraylist, ctor);
    env->ExceptionClear();
    return empty_list;
}

// ═══════════════════════════════════════════════════════
//  get_env_for_trampoline — defined here after all JNI types are settled
// ═══════════════════════════════════════════════════════

// ── helper: get JNIEnv from cached JVM (for trampoline use) ──
static JNIEnv* get_env_for_trampoline() {
    if (g_jvm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// ── count parameter types from a JNI signature string ──
// "(Lfoo/Bar;ILjava/lang/String;)V" → 3
static int count_params_from_signature(const char* sig) {
    if (!sig) return -1;
    const char* start = strchr(sig, '(');
    const char* end   = strchr(sig, ')');
    if (!start || !end || end <= start) return -1;
    int count = 0;
    for (const char* p = start + 1; p < end; ) {
        switch (*p) {
            case 'L': { const char* semi = strchr(p, ';'); p = semi ? semi + 1 : p + 1; count++; break; }
            case '[': { p++; while (p < end && *p == '[') p++; if (*p == 'L') { const char* semi = strchr(p, ';'); p = semi ? semi + 1 : end; } else p++; count++; break; }
            case 'Z': case 'B': case 'C': case 'S': case 'I': case 'J': case 'F': case 'D': p++; count++; break;
            default:  return -1;  // malformed
        }
    }
    return count;
}

// ── find a java.lang.reflect.Method by name + parameter count ──
// Handles overloaded methods correctly.
static jobject find_method_by_name_and_params(
    JNIEnv* env, jclass target_class,
    const char* method_name, const char* signature,
    int* out_param_count)
{
    int expected_params = count_params_from_signature(signature);
    if (expected_params < 0) return nullptr;
    if (out_param_count) *out_param_count = expected_params;

    // target_class.getDeclaredMethods()
    jclass cls_class = env->FindClass("java/lang/Class");
    jmethodID get_methods = env->GetMethodID(
        cls_class, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    if (!get_methods || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jobjectArray methods = reinterpret_cast<jobjectArray>(
        env->CallObjectMethod(target_class, get_methods));
    if (!methods || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    jclass method_class = env->FindClass("java/lang/reflect/Method");
    jmethodID get_name = env->GetMethodID(method_class, "getName", "()Ljava/lang/String;");
    jmethodID get_param_types = env->GetMethodID(
        method_class, "getParameterTypes", "()[Ljava/lang/Class;");

    int len = env->GetArrayLength(methods);
    for (int i = 0; i < len; i++) {
        jobject m = env->GetObjectArrayElement(methods, i);
        if (!m) continue;

        jstring jname = reinterpret_cast<jstring>(env->CallObjectMethod(m, get_name));
        if (!jname) continue;
        const char* name_chars = env->GetStringUTFChars(jname, nullptr);
        bool name_ok = (name_chars && strcmp(name_chars, method_name) == 0);
        env->ReleaseStringUTFChars(jname, name_chars);
        if (!name_ok) continue;

        jobjectArray params = reinterpret_cast<jobjectArray>(
            env->CallObjectMethod(m, get_param_types));
        int param_count = params ? env->GetArrayLength(params) : 0;
        if (param_count == expected_params) {
            return m;
        }
    }
    return nullptr;
}

// ═══════════════════════════════════════════════════════
//  ArtMethod entrypoint offset table (64-bit ARM, by SDK)
// ═══════════════════════════════════════════════════════

// On 64-bit ART, the ArtMethod structure layout places
// `entry_point_from_quick_compiled_code_` at a known offset.
// These offsets are empirically derived per SDK level.
static size_t get_entrypoint_offset() {
    if (g_sdk_int >= 34) return 24;   // API 34+: PtrSizedFields at 16, entrypoint at +8
    if (g_sdk_int >= 30) return 24;   // API 30-33
    if (g_sdk_int >= 26) return 24;   // API 26-29 (Oreo / Pie)
    if (g_sdk_int >= 23) return 56;   // API 23-25 (Marshmallow / Nougat)
    return 72;                         // API 21-22 (Lollipop)
}

// ── read/write ArtMethod compiled-code entrypoint ──
static void* art_get_entrypoint(void* art_method) {
    size_t off = get_entrypoint_offset();
    void** slot = reinterpret_cast<void**>(
        reinterpret_cast<char*>(art_method) + off);
    return *slot;
}

static void art_set_entrypoint(void* art_method, void* new_ep) {
    size_t off = get_entrypoint_offset();
    void** slot = reinterpret_cast<void**>(
        reinterpret_cast<char*>(art_method) + off);
    *slot = new_ep;
}

// ═══════════════════════════════════════════════════════
//  Hook install — direct ArtMethod entrypoint replacement
// ═══════════════════════════════════════════════════════

struct HookSpec {
    const char* class_name;
    const char* method_name;
    const char* signature;
    void*       trampoline;
};

bool install_art_hook(JNIEnv* env, const HookSpec& spec, const char* log_path) {
    FILE* log = fopen(log_path, "a");
    auto logf = [&](const char* fmt, ...) {
        if (log) { va_list ap; va_start(ap, fmt); vfprintf(log, fmt, ap); va_end(ap); fputc('\n', log); }
    };

    probe_sdk();

    // 1. Find the class
    jclass target = env->FindClass(spec.class_name);
    if (!target || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ArtHook: class not found %s", spec.class_name);
        logf("hook_install=find_class status=failed class=%s", spec.class_name);
        if (log) fclose(log);
        return false;
    }

    // 2. Find the method (handles overloads via getDeclaredMethods)
    int param_count = 0;
    jobject method_obj = find_method_by_name_and_params(
        env, target, spec.method_name, spec.signature, &param_count);
    if (!method_obj) {
        LOGE("ArtHook: method not found %s.%s (params=%d)",
            spec.class_name, spec.method_name,
            count_params_from_signature(spec.signature));
        logf("hook_install=find_method status=failed class=%s method=%s sig=%s",
            spec.class_name, spec.method_name, spec.signature);
        if (log) fclose(log);
        return false;
    }
    logf("hook_install=find_method status=ok class=%s method=%s params=%d",
        spec.class_name, spec.method_name, param_count);

    // 3. Extract the ArtMethod* from the java.lang.reflect.Method
    //    On ART, Executable (parent of Method) has a long field `artMethod`
    //    or we can get the jmethodID via reflection on GetMethodID.
    jclass method_class = env->GetObjectClass(method_obj);

    // Try the "artMethod" field (long) — present on many ART builds
    jfieldID art_field = env->GetFieldID(method_class, "artMethod", "J");
    if (!art_field || env->ExceptionCheck()) {
        env->ExceptionClear();
        // Fallback: use GetMethodID to get the ArtMethod*
        // jmethodID *is* the ArtMethod pointer in ART
        jmethodID jmid = env->GetMethodID(target, spec.method_name, spec.signature);
        if (!jmid || env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("ArtHook: cannot get ArtMethod for %s.%s",
                spec.class_name, spec.method_name);
            logf("hook_install=get_artmethod status=failed");
            if (log) fclose(log);
            return false;
        }
        void* art_method = reinterpret_cast<void*>(jmid);
        void* orig_entry = art_get_entrypoint(art_method);

        if (orig_entry == nullptr) {
            LOGE("ArtHook: original entrypoint is null for %s.%s (SDK=%d off=%zu)",
                spec.class_name, spec.method_name, g_sdk_int, get_entrypoint_offset());
            logf("hook_install=read_entrypoint status=failed detail=null entrypoint");
            if (log) fclose(log);
            return false;
        }

        // 4. Replace the entrypoint
        art_set_entrypoint(art_method, spec.trampoline);
        void* new_entry = art_get_entrypoint(art_method);

        if (new_entry != spec.trampoline) {
            LOGE("ArtHook: entrypoint swap failed %s.%s (expected=%p got=%p)",
                spec.class_name, spec.method_name, spec.trampoline, new_entry);
            art_set_entrypoint(art_method, orig_entry);  // restore
            logf("hook_install=swap_entrypoint status=failed");
            if (log) fclose(log);
            return false;
        }

        // Record hook
        if (g_hook_count < MAX_HOOKS) {
            InstalledHook& h = g_hooks[g_hook_count++];
            h.target_class = reinterpret_cast<jclass>(env->NewGlobalRef(target));
            h.method_id = jmid;
            h.original_native = orig_entry;
            strncpy(h.class_name, spec.class_name, sizeof(h.class_name) - 1);
            strncpy(h.method_name, spec.method_name, sizeof(h.method_name) - 1);
            strncpy(h.signature, spec.signature, sizeof(h.signature) - 1);
            h.original_access_flags = 0;

            // Save original entrypoint for trampoline passthrough when mock inactive.
            if (strcmp(spec.method_name, "getLastLocation") == 0 &&
                strcmp(spec.signature, "()Landroid/location/Location;") == 0) {
                g_orig_getLastLocation = orig_entry;
            } else if (strcmp(spec.method_name, "getLastLocation") == 0 &&
                strcmp(spec.signature, "(Landroid/location/LocationRequest;Ljava/lang/String;)Landroid/location/Location;") == 0) {
                g_orig_getLastLocation_req = orig_entry;
            } else if (strcmp(spec.method_name, "getAllCellInfo") == 0) {
                g_orig_getAllCellInfo = orig_entry;
            } else if (strcmp(spec.method_name, "getScanResults") == 0) {
                g_orig_getScanResults = orig_entry;
            }
        }

        LOGI("ArtHook installed: %s.%s (entry %p → %p, SDK=%d off=%zu)",
            spec.class_name, spec.method_name, orig_entry, new_entry,
            g_sdk_int, get_entrypoint_offset());
        logf("hook_install=ok class=%s method=%s orig=%p new=%p sdk=%d off=%zu params=%d",
            spec.class_name, spec.method_name, orig_entry, new_entry,
            g_sdk_int, get_entrypoint_offset(), param_count);
        if (log) fclose(log);
        return true;
    }

    // Got the artMethod field from reflection
    jlong art_method_val = env->GetLongField(method_obj, art_field);
    void* art_method = reinterpret_cast<void*>(static_cast<uintptr_t>(art_method_val));

    if (art_method == nullptr) {
        LOGE("ArtHook: artMethod field is 0 for %s.%s", spec.class_name, spec.method_name);
        logf("hook_install=get_artmethod status=failed detail=null artMethod");
        if (log) fclose(log);
        return false;
    }

    void* orig_entry = art_get_entrypoint(art_method);
    if (orig_entry == nullptr) {
        logf("hook_install=read_entrypoint status=warning detail=null entrypoint (method may not be compiled yet)");
    }

    art_set_entrypoint(art_method, spec.trampoline);
    void* new_entry = art_get_entrypoint(art_method);

    if (new_entry != spec.trampoline) {
        LOGE("ArtHook: entrypoint swap failed %s.%s (expected=%p got=%p)",
            spec.class_name, spec.method_name, spec.trampoline, new_entry);
        if (orig_entry) art_set_entrypoint(art_method, orig_entry);
        logf("hook_install=swap_entrypoint status=failed");
        if (log) fclose(log);
        return false;
    }

    if (g_hook_count < MAX_HOOKS) {
        InstalledHook& h = g_hooks[g_hook_count++];
        h.target_class = reinterpret_cast<jclass>(env->NewGlobalRef(target));
        h.method_id = nullptr;
        h.original_native = orig_entry;
        strncpy(h.class_name, spec.class_name, sizeof(h.class_name) - 1);
        strncpy(h.method_name, spec.method_name, sizeof(h.method_name) - 1);
        strncpy(h.signature, spec.signature, sizeof(h.signature) - 1);

        // Save original entrypoint for trampoline passthrough when mock inactive.
        if (strcmp(spec.method_name, "getLastLocation") == 0 &&
            strcmp(spec.signature, "()Landroid/location/Location;") == 0) {
            g_orig_getLastLocation = orig_entry;
        } else if (strcmp(spec.method_name, "getLastLocation") == 0 &&
            strcmp(spec.signature, "(Landroid/location/LocationRequest;Ljava/lang/String;)Landroid/location/Location;") == 0) {
            g_orig_getLastLocation_req = orig_entry;
        } else if (strcmp(spec.method_name, "getAllCellInfo") == 0) {
            g_orig_getAllCellInfo = orig_entry;
        } else if (strcmp(spec.method_name, "getScanResults") == 0) {
            g_orig_getScanResults = orig_entry;
        }
    }

    LOGI("ArtHook installed: %s.%s (entry %p → %p, via artMethod)",
        spec.class_name, spec.method_name, orig_entry, new_entry);
    logf("hook_install=ok class=%s method=%s orig=%p new=%p sdk=%d off=%zu (artMethod ref)",
        spec.class_name, spec.method_name, orig_entry, new_entry,
        g_sdk_int, get_entrypoint_offset());
    if (log) fclose(log);
    return true;
}

// ═══════════════════════════════════════════════════════
//  Exported C API (called by inj64 → remote dlsym)
// ═══════════════════════════════════════════════════════

extern "C" int flh_ping(const FlRuntimeRequest* request, FlRuntimeResult* result) {
    if (!result) return 73;

    if (request && request->hook_seed_path) {
        FILE* seed = fopen(request->hook_seed_path, "r");
        if (seed) {
            char line[512];
            while (fgets(line, sizeof(line), seed)) {
                if (request->log_path) {
                    FILE* lf = fopen(request->log_path, "a");
                    if (lf) { fprintf(lf, "hook_seed_line=%s", line); fclose(lf); }
                }
            }
            fclose(seed);
        }
    }

    if (request && request->log_path) {
        FILE* lf = fopen(request->log_path, "a");
        if (lf) {
            fprintf(lf, "hook_bridge=liblh64 target=%s stage=%s status=ping-ok sdk=%d\n",
                request->target_process, request->stage, g_sdk_int);
            fclose(lf);
        }
    }

    result->code = 0;
    snprintf(result->message, sizeof(result->message),
        "liblh64 ping ok sdk=%d hooks=%d", g_sdk_int, g_hook_count);
    return 0;
}

// flh_install_hooks: install all standard hooks for the given stage
// stage: "init" or "appHook"
extern "C" int flh_install_hooks(const char* stage, const char* log_path) {
    // Get JVM and cache it for trampoline use — resolved via dlsym.
    auto jni_get_vms = resolve_jni_get_created_java_vms();
    if (!jni_get_vms) return -96;

    jsize nvm = 0;
    if (jni_get_vms(&g_jvm, 1, &nvm) != JNI_OK || nvm == 0) {
        return -96;
    }
    JNIEnv* env = nullptr;
    bool detach = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return -97;
        detach = true;
    }

    int installed = 0;

    // ── system_server / InitApp hooks ──
    if (!stage || strcmp(stage, "init") == 0 || strcmp(stage, "init_stage") == 0) {
        // LocationManagerService.getLastLocation() → Location
        if (install_art_hook(env, {
            "com/android/server/location/LocationManagerService",
            "getLastLocation",
            "()Landroid/location/Location;",
            (void*)trampoline_getLastLocation
        }, log_path)) installed++;

        // getLastLocation(LocationRequest, String) — API 30+ overload (best-effort)
        install_art_hook(env, {
            "com/android/server/location/LocationManagerService",
            "getLastLocation",
            "(Landroid/location/LocationRequest;Ljava/lang/String;)Landroid/location/Location;",
            (void*)trampoline_getLastLocation_req
        }, log_path);  // don't count — may not exist on older APIs
    }

    // ── com.android.phone / AppHook hooks ──
    if (!stage || strcmp(stage, "appHook") == 0 || strcmp(stage, "app_hook_stage") == 0) {
        if (install_art_hook(env, {
            "com/android/phone/PhoneInterfaceManager",
            "getAllCellInfo",
            "()Ljava/util/List;",
            (void*)trampoline_getAllCellInfo
        }, log_path)) installed++;

        if (install_art_hook(env, {
            "android/net/wifi/WifiManager",
            "getScanResults",
            "()Ljava/util/List;",
            (void*)trampoline_getScanResults
        }, log_path)) installed++;
    }

    if (detach) g_jvm->DetachCurrentThread();

    if (log_path) {
        FILE* lf = fopen(log_path, "a");
        if (lf) {
            fprintf(lf, "hook_bridge_phase=install_hooks status=%s "
                "stage=%s installed=%d sdk=%d ep_offset=%zu\n",
                installed > 0 ? "ok" : "warning",
                stage, installed, g_sdk_int, get_entrypoint_offset());
            fclose(lf);
        }
    }

    LOGI("flh_install_hooks stage=%s installed=%d sdk=%d",
        stage ? stage : "all", installed, g_sdk_int);
    return installed;
}

// flh_update_mock_location — called from Java to update the mock data
extern "C" void flh_update_mock_location(
    double lat, double lon, double alt,
    float acc, const char* provider, int64_t timestamp_ms)
{
    g_mock_location.active = true;
    g_mock_location.latitude = lat;
    g_mock_location.longitude = lon;
    g_mock_location.altitude = alt;
    g_mock_location.accuracy = acc;
    if (provider) strncpy(g_mock_location.provider, provider, sizeof(g_mock_location.provider) - 1);
    g_mock_location.timestamp_millis = timestamp_ms;
    LOGI("Mock location = %.6f, %.6f", lat, lon);
}

extern "C" void flh_stop_mock_location() {
    g_mock_location.active = false;
    LOGI("Mock location stopped");
}

extern "C" int flh_get_hook_count() { return g_hook_count; }
extern "C" int flh_is_mock_active(void) { return g_mock_location.active ? 1 : 0; }
