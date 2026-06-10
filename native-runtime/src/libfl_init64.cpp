#include "fl_runtime.h"

#include <stdio.h>
#include <string>

namespace {

std::string read_snippet(const char* path, size_t max_bytes) {
    if (path == nullptr || path[0] == '\0') {
        return {};
    }
    FILE* file = fopen(path, "rb");
    if (file == nullptr) {
        return {};
    }
    std::string out;
    out.resize(max_bytes);
    size_t read = fread(out.data(), 1, max_bytes, file);
    fclose(file);
    out.resize(read);
    return out;
}

}  // namespace

extern "C" int fl_loader_entry(const FlRuntimeRequest* request, FlRuntimeResult* result) {
    if (request == nullptr || result == nullptr) {
        return 71;
    }
    FILE* log = fopen(request->log_path, "a");
    std::string payload_snippet = read_snippet(request->payload_path, 160);
    std::string seed_snippet = read_snippet(request->hook_seed_path, 160);
    if (log != nullptr) {
        fprintf(log, "loader=libfl_init64 stage=%s target=%s pid=%d entry=%s\n",
            request->stage, request->target_process, request->target_pid, request->entrypoint);
        fprintf(log, "loader_phase=jvm_bind status=ok detail=stub-init-loader\n");
        fprintf(log, "loader_phase=plan_ingest status=ok detail=plan=%s\n",
            request->plan_path != nullptr ? request->plan_path : "none");
        fprintf(log, "loader_phase=payload_open status=%s detail=bytes=%zu\n",
            payload_snippet.empty() ? "warning" : "ok", payload_snippet.size());
        fprintf(log, "loader_phase=seed_open status=%s detail=bytes=%zu\n",
            seed_snippet.empty() ? "warning" : "ok", seed_snippet.size());
        fprintf(log, "loader_phase=payload_dispatch status=ok detail=init-stage placeholder handoff\n");
        if (!payload_snippet.empty()) {
            fprintf(log, "loader_payload_snippet=%s\n", payload_snippet.c_str());
        }
        if (!seed_snippet.empty()) {
            fprintf(log, "loader_seed_snippet=%s\n", seed_snippet.c_str());
        }
        fclose(log);
    }
    result->code = 0;
    snprintf(result->message, sizeof(result->message),
        "libfl_init64 invoked for %s pid=%d entry=%s payload=%zu seed=%zu",
        request->target_process, request->target_pid, request->entrypoint,
        payload_snippet.size(), seed_snippet.size());
    return 0;
}
