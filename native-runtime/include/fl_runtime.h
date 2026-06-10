#ifndef FL_RUNTIME_H
#define FL_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

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

// ── loader entrypoint (libfl_init64.so / libfl_app64.so) ──
typedef int (*FlLoaderEntryFn)(const FlRuntimeRequest* request, FlRuntimeResult* result);

// ── hook bridge (liblh64.so) ──
typedef int (*FlHookBridgePingFn)(const FlRuntimeRequest* request, FlRuntimeResult* result);

// flh_ping: health check + seed ingest (existing API)
int flh_ping(const FlRuntimeRequest* request, FlRuntimeResult* result);

// flh_install_hooks: install standard hooks for the given stage ("init" / "appHook")
// Returns number of hooks installed, or negative on error.
int flh_install_hooks(const char* stage, const char* log_path);

// flh_update_mock_location: update the mock data that trampolines return
void flh_update_mock_location(
    double lat, double lon, double alt,
    float acc, const char* provider, int64_t timestamp_millis);

// flh_stop_mock_location: deactivate mock (trampolines will pass through)
void flh_stop_mock_location(void);

// flh_get_hook_count: return the number of installed hooks
int flh_get_hook_count(void);

// flh_is_mock_active: query whether mock location is currently active
int flh_is_mock_active(void);

#ifdef __cplusplus
}
#endif

#endif
