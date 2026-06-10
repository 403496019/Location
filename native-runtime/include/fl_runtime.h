#ifndef FL_RUNTIME_H
#define FL_RUNTIME_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FlRuntimeRequest {
    const char* target_process;
    const char* stage;
    const char* abi;
    const char* loader_path;
    const char* hook_bridge_path;
    const char* payload_path;
    const char* hook_seed_path;
    const char* entrypoint;
    const char* log_path;
    const char* plan_path;
    int dry_run;
    int target_pid;
} FlRuntimeRequest;

typedef struct FlRuntimeResult {
    int code;
    char message[256];
} FlRuntimeResult;

typedef int (*FlLoaderEntryFn)(const FlRuntimeRequest* request, FlRuntimeResult* result);
typedef int (*FlHookBridgePingFn)(const FlRuntimeRequest* request, FlRuntimeResult* result);

#ifdef __cplusplus
}
#endif

#endif
