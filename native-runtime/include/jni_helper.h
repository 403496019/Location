#ifndef FL_JNI_HELPER_H
#define FL_JNI_HELPER_H

#include <dlfcn.h>
#include <jni.h>

// Resolve JNI_GetCreatedJavaVMs at runtime via dlsym.
// This avoids a link-time dependency on the symbol (which is only available
// inside a JVM process, not at NDK link time).
//
// Returns a function pointer on success, nullptr if unresolvable.

using JniGetCreatedJavaVMsFn = jint (*)(JavaVM**, jsize, jsize*);

static inline JniGetCreatedJavaVMsFn resolve_jni_get_created_java_vms() {
    // First try the global symbol table (already loaded in a JVM process).
    void* sym = dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    if (sym) return reinterpret_cast<JniGetCreatedJavaVMsFn>(sym);

    // Fallback: search known ART / NDK libraries.
    const char* libs[] = {
        "libart.so",
        "libnativehelper.so",
        "libandroid_runtime.so",
    };
    for (const char* lib : libs) {
        void* h = dlopen(lib, RTLD_NOW | RTLD_NOLOAD);
        if (!h) h = dlopen(lib, RTLD_NOW);
        if (!h) continue;
        sym = dlsym(h, "JNI_GetCreatedJavaVMs");
        if (sym) return reinterpret_cast<JniGetCreatedJavaVMsFn>(sym);
    }
    return nullptr;
}

#endif // FL_JNI_HELPER_H
