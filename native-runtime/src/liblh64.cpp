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

extern "C" int flh_ping(const FlRuntimeRequest* request, FlRuntimeResult* result) {
    if (result == nullptr) {
        return 73;
    }
    std::string seed_snippet = request != nullptr ? read_snippet(request->hook_seed_path, 192) : "";
    if (request != nullptr && request->log_path != nullptr) {
        FILE* log = fopen(request->log_path, "a");
        if (log != nullptr) {
            fprintf(log, "hook_bridge=liblh64 target=%s stage=%s status=ping-ok\n",
                request->target_process, request->stage);
            fprintf(log, "hook_bridge_phase=seed_ingest status=%s detail=bytes=%zu\n",
                seed_snippet.empty() ? "warning" : "ok",
                seed_snippet.size());
            if (!seed_snippet.empty()) {
                fprintf(log, "hook_bridge_seed_snippet=%s\n", seed_snippet.c_str());
            }
            fclose(log);
        }
    }
    result->code = 0;
    snprintf(result->message, sizeof(result->message), "liblh64 ping ok seed=%zu", seed_snippet.size());
    return 0;
}
