#include "fl_runtime.h"

#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <inttypes.h>
#include <link.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace {

struct ParsedArgs {
    std::string targetProcess;
    std::string stage;
    std::string abi;
    std::string loaderPath;
    std::string hookBridgePath;
    std::string payloadPath;
    std::string entrypoint;
    std::string logPath;
    bool dryRun = false;
};

struct RemoteModuleInfo {
    std::string path;
    uintptr_t base = 0;
};

struct RemoteSymbolPlan {
    bool resolved = false;
    std::string sourceModule;
    uintptr_t localBase = 0;
    uintptr_t remoteBase = 0;
    uintptr_t localSymbol = 0;
    uintptr_t remoteSymbol = 0;
    uintptr_t relativeOffset = 0;
    std::string detail;
};

struct RegisterSnapshot {
    bool available = false;
    std::string detail;
    uint64_t pc = 0;
    uint64_t sp = 0;
    uint64_t lr = 0;
};

struct RemoteArgumentLayout {
    size_t totalSize = 0;
    size_t totalSizeAligned = 0;
    size_t loaderPathOffset = 0;
    size_t hookBridgePathOffset = 0;
    size_t payloadPathOffset = 0;
    size_t hookSeedPathOffset = 0;
    size_t entrypointOffset = 0;
};

struct RemoteTransactionLayout {
    size_t stringBlockOffset = 0;
    size_t stringBlockSize = 0;
    size_t requestBlockOffset = 0;
    size_t requestBlockSize = 0;
    size_t resultBlockOffset = 0;
    size_t resultBlockSize = 0;
    size_t totalSize = 0;
    size_t totalSizeAligned = 0;
};

struct RemoteBlobPreview {
    std::string hexPreview;
    size_t byteCount = 0;
};

void write_log_line(const char* log_path, const char* format, ...) {
    if (log_path == nullptr || log_path[0] == '\0') {
        return;
    }
    FILE* file = fopen(log_path, "a");
    if (file == nullptr) {
        return;
    }
    va_list args;
    va_start(args, format);
    vfprintf(file, format, args);
    va_end(args);
    fputc('\n', file);
    fclose(file);
}

void print_and_log(const char* log_path, const char* format, ...) {
    va_list args;
    va_start(args, format);
    vfprintf(stdout, format, args);
    va_end(args);
    fputc('\n', stdout);

    if (log_path != nullptr && log_path[0] != '\0') {
        FILE* file = fopen(log_path, "a");
        if (file != nullptr) {
            va_start(args, format);
            vfprintf(file, format, args);
            va_end(args);
            fputc('\n', file);
            fclose(file);
        }
    }
}

bool file_exists(const std::string& path) {
    struct stat st = {};
    return !path.empty() && stat(path.c_str(), &st) == 0;
}

long file_size_or_minus_one(const std::string& path) {
    struct stat st = {};
    if (path.empty() || stat(path.c_str(), &st) != 0) {
        return -1;
    }
    return static_cast<long>(st.st_size);
}

bool ensure_parent_dir(const std::string& path) {
    auto slash = path.find_last_of('/');
    if (slash == std::string::npos) {
        return true;
    }
    std::string dir = path.substr(0, slash);
    if (dir.empty()) {
        return true;
    }
    std::string current;
    for (size_t i = 0; i < dir.size(); ++i) {
        current.push_back(dir[i]);
        if (dir[i] == '/' || i == dir.size() - 1) {
            if (current.size() > 1 && mkdir(current.c_str(), 0755) != 0 && errno != EEXIST) {
                return false;
            }
        }
    }
    return true;
}

int find_pid_by_process_name(const std::string& process_name) {
    DIR* proc = opendir("/proc");
    if (proc == nullptr) {
        return -1;
    }
    struct dirent* entry = nullptr;
    while ((entry = readdir(proc)) != nullptr) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') {
            continue;
        }
        std::string cmdline_path = std::string("/proc/") + entry->d_name + "/cmdline";
        FILE* cmdline = fopen(cmdline_path.c_str(), "rb");
        if (cmdline == nullptr) {
            continue;
        }
        char buffer[512] = {};
        size_t read = fread(buffer, 1, sizeof(buffer) - 1, cmdline);
        fclose(cmdline);
        if (read == 0) {
            continue;
        }
        buffer[read] = '\0';
        if (process_name == buffer) {
            closedir(proc);
            return atoi(entry->d_name);
        }
    }
    closedir(proc);
    return -1;
}

std::string read_text_file(const std::string& path, size_t max_bytes) {
    FILE* file = fopen(path.c_str(), "rb");
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

std::string read_proc_status_excerpt(int pid) {
    std::string text = read_text_file("/proc/" + std::to_string(pid) + "/status", 4096);
    if (text.empty()) {
        return "unavailable";
    }
    std::vector<std::string> keys = {
        "Name:", "State:", "Tgid:", "Pid:", "PPid:", "TracerPid:", "Uid:", "Gid:", "VmRSS:",
    };
    std::string excerpt;
    size_t line_start = 0;
    while (line_start < text.size()) {
        size_t line_end = text.find('\n', line_start);
        if (line_end == std::string::npos) {
            line_end = text.size();
        }
        std::string line = text.substr(line_start, line_end - line_start);
        for (const auto& key : keys) {
            if (line.rfind(key, 0) == 0) {
                excerpt += line;
                excerpt += "\n";
                break;
            }
        }
        line_start = line_end + 1;
    }
    return excerpt.empty() ? "unavailable" : excerpt;
}

std::string read_maps_excerpt(int pid, size_t max_lines) {
    std::string path = "/proc/" + std::to_string(pid) + "/maps";
    FILE* file = fopen(path.c_str(), "rb");
    if (file == nullptr) {
        return "unavailable";
    }
    std::string excerpt;
    char buffer[1024];
    size_t line_count = 0;
    while (fgets(buffer, sizeof(buffer), file) != nullptr && line_count < max_lines) {
        excerpt += buffer;
        ++line_count;
    }
    fclose(file);
    return excerpt.empty() ? "unavailable" : excerpt;
}

std::vector<RemoteModuleInfo> read_remote_modules(int pid) {
    std::vector<RemoteModuleInfo> modules;
    std::string path = "/proc/" + std::to_string(pid) + "/maps";
    FILE* file = fopen(path.c_str(), "rb");
    if (file == nullptr) {
        return modules;
    }
    char buffer[1024];
    while (fgets(buffer, sizeof(buffer), file) != nullptr) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char perms[8] = {};
        unsigned long long offset = 0;
        char device[16] = {};
        unsigned long inode = 0;
        char pathname[768] = {};
        int scanned = sscanf(
            buffer,
            "%llx-%llx %7s %llx %15s %lu %767[^\n]",
            &start,
            &end,
            perms,
            &offset,
            device,
            &inode,
            pathname
        );
        if (scanned >= 6 && offset == 0 && scanned == 7 && pathname[0] != '\0') {
            modules.push_back(RemoteModuleInfo{
                .path = pathname,
                .base = static_cast<uintptr_t>(start),
            });
        }
    }
    fclose(file);
    return modules;
}

const RemoteModuleInfo* find_remote_module(
    const std::vector<RemoteModuleInfo>& modules,
    const std::vector<std::string>& suffixes
) {
    for (const auto& suffix : suffixes) {
        for (const auto& module : modules) {
            if (module.path.size() >= suffix.size() &&
                module.path.compare(module.path.size() - suffix.size(), suffix.size(), suffix) == 0) {
                return &module;
            }
        }
    }
    return nullptr;
}

std::string read_link_target(const std::string& path) {
    std::vector<char> buffer(1024, '\0');
    ssize_t len = readlink(path.c_str(), buffer.data(), buffer.size() - 1);
    if (len <= 0) {
        return "unavailable";
    }
    buffer[static_cast<size_t>(len)] = '\0';
    return std::string(buffer.data());
}

std::string derive_hook_seed_path(const std::string& payload_path) {
    size_t payload_index = payload_path.rfind("/payload/");
    if (payload_index == std::string::npos) {
        return "/data/fl/metadata/hook-registry-seed.txt";
    }
    return payload_path.substr(0, payload_index) + "/metadata/hook-registry-seed.txt";
}

std::string derive_plan_path(const std::string& process_name, const std::string& stage) {
    std::string safe = process_name;
    for (char& ch : safe) {
        if (ch == '.' || ch == ':') {
            ch = '_';
        }
    }
    return "/data/fl/logs/injection_plan_" + safe + "_" + stage + ".txt";
}

bool looks_like_elf(const std::string& path) {
    std::string header = read_text_file(path, 4);
    return header.size() == 4 &&
        static_cast<unsigned char>(header[0]) == 0x7f &&
        header[1] == 'E' &&
        header[2] == 'L' &&
        header[3] == 'F';
}

size_t align_up(size_t value, size_t alignment) {
    return (value + alignment - 1) & ~(alignment - 1);
}

RemoteArgumentLayout build_remote_argument_layout(const FlRuntimeRequest& request) {
    RemoteArgumentLayout layout;
    size_t cursor = 0;
    layout.loaderPathOffset = cursor;
    cursor += strlen(request.loader_path) + 1;
    layout.hookBridgePathOffset = cursor;
    cursor += strlen(request.hook_bridge_path) + 1;
    layout.payloadPathOffset = cursor;
    cursor += strlen(request.payload_path) + 1;
    layout.hookSeedPathOffset = cursor;
    cursor += strlen(request.hook_seed_path != nullptr ? request.hook_seed_path : "") + 1;
    layout.entrypointOffset = cursor;
    cursor += strlen(request.entrypoint) + 1;
    layout.totalSize = cursor;
    layout.totalSizeAligned = align_up(cursor, 4096);
    return layout;
}

RemoteTransactionLayout build_remote_transaction_layout(
    const FlRuntimeRequest& request,
    const RemoteArgumentLayout& args
) {
    RemoteTransactionLayout layout;
    layout.stringBlockOffset = 0;
    layout.stringBlockSize = args.totalSize;
    layout.requestBlockOffset = align_up(layout.stringBlockOffset + layout.stringBlockSize, alignof(FlRuntimeRequest));
    layout.requestBlockSize = sizeof(FlRuntimeRequest);
    layout.resultBlockOffset = align_up(layout.requestBlockOffset + layout.requestBlockSize, alignof(FlRuntimeResult));
    layout.resultBlockSize = sizeof(FlRuntimeResult);
    layout.totalSize = layout.resultBlockOffset + layout.resultBlockSize;
    layout.totalSizeAligned = align_up(layout.totalSize, 4096);
    return layout;
}

std::vector<unsigned char> build_remote_string_block(
    const FlRuntimeRequest& request,
    const RemoteArgumentLayout& layout
) {
    std::vector<unsigned char> block(layout.totalSize, 0);
    auto write_string = [&](size_t offset, const char* value) {
        if (value == nullptr) {
            return;
        }
        const size_t len = strlen(value);
        if (offset + len + 1 <= block.size()) {
            memcpy(block.data() + offset, value, len + 1);
        }
    };
    write_string(layout.loaderPathOffset, request.loader_path);
    write_string(layout.hookBridgePathOffset, request.hook_bridge_path);
    write_string(layout.payloadPathOffset, request.payload_path);
    write_string(layout.hookSeedPathOffset, request.hook_seed_path);
    write_string(layout.entrypointOffset, request.entrypoint);
    return block;
}

RemoteBlobPreview preview_blob_hex(
    const std::vector<unsigned char>& blob,
    size_t max_bytes
) {
    static const char* hex = "0123456789abcdef";
    RemoteBlobPreview preview;
    preview.byteCount = blob.size();
    const size_t limit = blob.size() < max_bytes ? blob.size() : max_bytes;
    preview.hexPreview.reserve(limit * 2);
    for (size_t i = 0; i < limit; ++i) {
        unsigned char byte = blob[i];
        preview.hexPreview.push_back(hex[(byte >> 4) & 0x0f]);
        preview.hexPreview.push_back(hex[byte & 0x0f]);
    }
    if (blob.size() > max_bytes) {
        preview.hexPreview += "...";
    }
    return preview;
}

RegisterSnapshot capture_register_snapshot(int pid) {
    RegisterSnapshot snapshot;
#if defined(__aarch64__)
    struct user_pt_regs {
        unsigned long long regs[31];
        unsigned long long sp;
        unsigned long long pc;
        unsigned long long pstate;
    } regs = {};
    struct iovec io = {
        .iov_base = &regs,
        .iov_len = sizeof(regs),
    };
    if (ptrace(PTRACE_GETREGSET, pid, reinterpret_cast<void*>(NT_PRSTATUS), &io) == 0) {
        snapshot.available = true;
        snapshot.pc = regs.pc;
        snapshot.sp = regs.sp;
        snapshot.lr = regs.regs[30];
        snapshot.detail = "aarch64 register snapshot ok";
    } else {
        snapshot.detail = std::string("PTRACE_GETREGSET errno=") + std::to_string(errno);
    }
#else
    snapshot.detail = "register snapshot unavailable on non-aarch64 build";
#endif
    return snapshot;
}

RemoteSymbolPlan resolve_remote_symbol_from_local(
    const std::vector<RemoteModuleInfo>& remote_modules,
    const char* symbol_name,
    const std::vector<std::string>& module_suffixes
) {
    RemoteSymbolPlan plan;
    void* local_symbol = dlsym(RTLD_DEFAULT, symbol_name);
    if (local_symbol == nullptr) {
        plan.detail = std::string("dlsym failed for ") + symbol_name;
        return plan;
    }
    Dl_info info = {};
    if (dladdr(local_symbol, &info) == 0 || info.dli_fbase == nullptr || info.dli_fname == nullptr) {
        plan.detail = std::string("dladdr failed for ") + symbol_name;
        return plan;
    }
    const RemoteModuleInfo* remote = find_remote_module(remote_modules, module_suffixes);
    if (remote == nullptr) {
        plan.detail = std::string("remote module missing for ") + symbol_name;
        return plan;
    }
    plan.resolved = true;
    plan.sourceModule = remote->path;
    plan.localBase = reinterpret_cast<uintptr_t>(info.dli_fbase);
    plan.remoteBase = remote->base;
    plan.localSymbol = reinterpret_cast<uintptr_t>(local_symbol);
    plan.relativeOffset = plan.localSymbol - plan.localBase;
    plan.remoteSymbol = plan.remoteBase + plan.relativeOffset;
    plan.detail = std::string("resolved via ") + symbol_name;
    return plan;
}

bool attempt_ptrace_attach_probe(int pid, const char* log_path, std::string* detail) {
    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != 0) {
        if (detail != nullptr) {
            *detail = std::string("ptrace_attach errno=") + std::to_string(errno);
        }
        return false;
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        if (detail != nullptr) {
            *detail = std::string("waitpid errno=") + std::to_string(errno);
        }
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }
    long detach_result = ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
    if (detach_result != 0) {
        if (detail != nullptr) {
            *detail = std::string("ptrace_detach errno=") + std::to_string(errno);
        }
        write_log_line(log_path, "ptrace_detach warning errno=%d", errno);
        return false;
    }
    if (detail != nullptr) {
        *detail = "ptrace attach/detach ok";
    }
    return true;
}

void write_injection_plan(
    const FlRuntimeRequest& request,
    const std::string& selinux,
    const std::string& target_exe,
    const std::string& target_status,
    const std::string& target_maps_excerpt,
    const RegisterSnapshot& register_snapshot,
    const std::vector<RemoteModuleInfo>& remote_modules,
    const RemoteSymbolPlan& remote_dlopen,
    const RemoteSymbolPlan& remote_dlsym,
    const RemoteSymbolPlan& remote_mmap,
    const RemoteArgumentLayout& remote_args,
    const RemoteTransactionLayout& remote_tx,
    const RemoteBlobPreview& remote_blob_preview,
    bool ptrace_ok,
    const std::string& ptrace_detail
) {
    if (request.plan_path == nullptr || request.plan_path[0] == '\0') {
        return;
    }
    ensure_parent_dir(request.plan_path);
    FILE* file = fopen(request.plan_path, "w");
    if (file == nullptr) {
        return;
    }
    fprintf(file, "target_process=%s\n", request.target_process);
    fprintf(file, "stage=%s\n", request.stage);
    fprintf(file, "abi=%s\n", request.abi);
    fprintf(file, "entrypoint=%s\n", request.entrypoint);
    fprintf(file, "target_pid=%d\n", request.target_pid);
    fprintf(file, "selinux=%s\n", selinux.c_str());
    fprintf(file, "target_exe=%s\n", target_exe.c_str());
    fprintf(file, "loader_path=%s\n", request.loader_path);
    fprintf(file, "loader_is_elf=%s\n", looks_like_elf(request.loader_path) ? "true" : "false");
    fprintf(file, "loader_size=%ld\n", file_size_or_minus_one(request.loader_path));
    fprintf(file, "hook_bridge_path=%s\n", request.hook_bridge_path);
    fprintf(file, "hook_bridge_is_elf=%s\n", looks_like_elf(request.hook_bridge_path) ? "true" : "false");
    fprintf(file, "hook_bridge_size=%ld\n", file_size_or_minus_one(request.hook_bridge_path));
    fprintf(file, "payload_path=%s\n", request.payload_path);
    fprintf(file, "payload_size=%ld\n", file_size_or_minus_one(request.payload_path));
    fprintf(file, "hook_seed_path=%s\n", request.hook_seed_path ? request.hook_seed_path : "none");
    fprintf(file, "hook_seed_size=%ld\n", file_size_or_minus_one(request.hook_seed_path ? request.hook_seed_path : ""));
    fprintf(file, "ptrace_probe_ok=%s\n", ptrace_ok ? "true" : "false");
    fprintf(file, "ptrace_probe_detail=%s\n", ptrace_detail.c_str());
    fprintf(file, "register_snapshot_available=%s\n", register_snapshot.available ? "true" : "false");
    fprintf(file, "register_snapshot_detail=%s\n", register_snapshot.detail.c_str());
    fprintf(file, "register_pc=0x%" PRIx64 "\n", register_snapshot.pc);
    fprintf(file, "register_sp=0x%" PRIx64 "\n", register_snapshot.sp);
    fprintf(file, "register_lr=0x%" PRIx64 "\n", register_snapshot.lr);
    fprintf(file, "remote_module_count=%zu\n", remote_modules.size());
    fprintf(file, "remote_dlopen=%s remote=0x%" PRIxPTR " offset=0x%" PRIxPTR "\n",
        remote_dlopen.resolved ? "resolved" : "unresolved",
        remote_dlopen.remoteSymbol,
        remote_dlopen.relativeOffset);
    fprintf(file, "remote_dlsym=%s remote=0x%" PRIxPTR " offset=0x%" PRIxPTR "\n",
        remote_dlsym.resolved ? "resolved" : "unresolved",
        remote_dlsym.remoteSymbol,
        remote_dlsym.relativeOffset);
    fprintf(file, "remote_mmap=%s remote=0x%" PRIxPTR " offset=0x%" PRIxPTR "\n",
        remote_mmap.resolved ? "resolved" : "unresolved",
        remote_mmap.remoteSymbol,
        remote_mmap.relativeOffset);
    fprintf(file, "remote_arg_total_size=%zu\n", remote_args.totalSize);
    fprintf(file, "remote_arg_total_size_aligned=%zu\n", remote_args.totalSizeAligned);
    fprintf(file, "remote_arg_loader_offset=%zu\n", remote_args.loaderPathOffset);
    fprintf(file, "remote_arg_hook_bridge_offset=%zu\n", remote_args.hookBridgePathOffset);
    fprintf(file, "remote_arg_payload_offset=%zu\n", remote_args.payloadPathOffset);
    fprintf(file, "remote_arg_hook_seed_offset=%zu\n", remote_args.hookSeedPathOffset);
    fprintf(file, "remote_arg_entrypoint_offset=%zu\n", remote_args.entrypointOffset);
    fprintf(file, "remote_tx_string_block_offset=%zu\n", remote_tx.stringBlockOffset);
    fprintf(file, "remote_tx_string_block_size=%zu\n", remote_tx.stringBlockSize);
    fprintf(file, "remote_tx_request_block_offset=%zu\n", remote_tx.requestBlockOffset);
    fprintf(file, "remote_tx_request_block_size=%zu\n", remote_tx.requestBlockSize);
    fprintf(file, "remote_tx_result_block_offset=%zu\n", remote_tx.resultBlockOffset);
    fprintf(file, "remote_tx_result_block_size=%zu\n", remote_tx.resultBlockSize);
    fprintf(file, "remote_tx_total_size=%zu\n", remote_tx.totalSize);
    fprintf(file, "remote_tx_total_size_aligned=%zu\n", remote_tx.totalSizeAligned);
    fprintf(file, "remote_blob_preview_bytes=%zu\n", remote_blob_preview.byteCount);
    fprintf(file, "remote_blob_preview_hex=%s\n", remote_blob_preview.hexPreview.c_str());
    fprintf(file, "remote_strategy=ptrace_remote_dlopen_planned\n");
    fprintf(file, "\n[remote_call_blueprint]\n");
    fprintf(file, "step1_call=mmap(addr=0,len=%zu,prot=PROT_READ|PROT_WRITE|PROT_EXEC,flags=MAP_PRIVATE|MAP_ANON,fd=-1,off=0)\n",
        remote_tx.totalSizeAligned);
    fprintf(file, "step1_aarch64_regs=x0=0 x1=%zu x2=7 x3=0x22 x4=-1 x5=0\n", remote_tx.totalSizeAligned);
    fprintf(file, "step2_write=string_block@+%zu request_block@+%zu result_block@+%zu\n",
        remote_tx.stringBlockOffset,
        remote_tx.requestBlockOffset,
        remote_tx.resultBlockOffset);
    fprintf(file, "step2_string_offsets=loader_path@+%zu hook_bridge_path@+%zu payload_path@+%zu hook_seed_path@+%zu entrypoint@+%zu\n",
        remote_args.loaderPathOffset,
        remote_args.hookBridgePathOffset,
        remote_args.payloadPathOffset,
        remote_args.hookSeedPathOffset,
        remote_args.entrypointOffset);
    fprintf(file, "step3_call=dlopen(remote_base+loader_offset,RTLD_NOW|RTLD_LOCAL)\n");
    fprintf(file, "step3_aarch64_regs=x0=remote_base+%zu x1=0x2\n", remote_args.loaderPathOffset);
    fprintf(file, "step4_call=dlsym(loader_handle,\"fl_loader_entry\")\n");
    fprintf(file, "step4_aarch64_regs=x0=loader_handle x1=remote_base+%zu\n", remote_args.entrypointOffset);
    fprintf(file, "step5_call=invoke fl_loader_entry(remote_request_ptr,remote_result_ptr)\n");
    fprintf(file, "step5_aarch64_regs=x0=remote_base+%zu x1=remote_base+%zu\n",
        remote_tx.requestBlockOffset,
        remote_tx.resultBlockOffset);
    fprintf(file, "step6_readback=result_block from remote_base+%zu size=%zu\n",
        remote_tx.resultBlockOffset,
        remote_tx.resultBlockSize);
    fprintf(file, "\n[status_excerpt]\n%s", target_status.c_str());
    fprintf(file, "\n[maps_excerpt]\n%s", target_maps_excerpt.c_str());
    fprintf(file, "\n[remote_modules]\n");
    for (const auto& module : remote_modules) {
        fprintf(file, "base=0x%" PRIxPTR " path=%s\n", module.base, module.path.c_str());
    }
    fclose(file);
}

const char* read_selinux_mode() {
    static char mode[32];
    FILE* file = fopen("/sys/fs/selinux/enforce", "rb");
    if (file == nullptr) {
        snprintf(mode, sizeof(mode), "unknown");
        return mode;
    }
    int value = fgetc(file);
    fclose(file);
    if (value == '1') {
        snprintf(mode, sizeof(mode), "Enforcing");
    } else if (value == '0') {
        snprintf(mode, sizeof(mode), "Permissive");
    } else {
        snprintf(mode, sizeof(mode), "unknown");
    }
    return mode;
}

bool parse_args(int argc, char** argv, ParsedArgs* parsed) {
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        auto require_value = [&](std::string* target) -> bool {
            if (i + 1 >= argc) {
                fprintf(stderr, "inj64: missing value for %s\n", arg.c_str());
                return false;
            }
            *target = argv[++i];
            return true;
        };
        if (arg == "--target-process") {
            if (!require_value(&parsed->targetProcess)) return false;
        } else if (arg == "--stage") {
            if (!require_value(&parsed->stage)) return false;
        } else if (arg == "--abi") {
            if (!require_value(&parsed->abi)) return false;
        } else if (arg == "--loader") {
            if (!require_value(&parsed->loaderPath)) return false;
        } else if (arg == "--hook-bridge") {
            if (!require_value(&parsed->hookBridgePath)) return false;
        } else if (arg == "--payload") {
            if (!require_value(&parsed->payloadPath)) return false;
        } else if (arg == "--entry") {
            if (!require_value(&parsed->entrypoint)) return false;
        } else if (arg == "--log-file") {
            if (!require_value(&parsed->logPath)) return false;
        } else if (arg == "--dry-run") {
            parsed->dryRun = true;
        } else {
            fprintf(stderr, "inj64: unknown argument %s\n", arg.c_str());
            return false;
        }
    }
    return !parsed->targetProcess.empty() &&
        !parsed->stage.empty() &&
        !parsed->loaderPath.empty() &&
        !parsed->hookBridgePath.empty() &&
        !parsed->payloadPath.empty() &&
        !parsed->entrypoint.empty();
}

void fill_result(FlRuntimeResult* result, int code, const char* message) {
    if (result == nullptr) {
        return;
    }
    result->code = code;
    snprintf(result->message, sizeof(result->message), "%s", message);
}

}  // namespace

int main(int argc, char** argv) {
    ParsedArgs parsed;
    if (!parse_args(argc, argv, &parsed)) {
        return 64;
    }

    if (parsed.logPath.empty()) {
        std::string safe = parsed.targetProcess;
        for (char& ch : safe) {
            if (ch == '.' || ch == ':') {
                ch = '_';
            }
        }
        parsed.logPath = "/data/fl/logs/inj64_" + safe + "_" + parsed.stage + ".log";
    }
    ensure_parent_dir(parsed.logPath);

    const int target_pid = find_pid_by_process_name(parsed.targetProcess);
    const char* selinux = read_selinux_mode();
    std::string hook_seed_path = derive_hook_seed_path(parsed.payloadPath);
    std::string plan_path = derive_plan_path(parsed.targetProcess, parsed.stage);
    write_log_line(parsed.logPath.c_str(), "[inj64] target=%s stage=%s abi=%s selinux=%s",
        parsed.targetProcess.c_str(), parsed.stage.c_str(), parsed.abi.c_str(), selinux);

    if (target_pid <= 0) {
        print_and_log(parsed.logPath.c_str(), "phase=attach status=failed detail=target process missing");
        fprintf(stderr, "inj64: target process not running: %s\n", parsed.targetProcess.c_str());
        return 67;
    }
    print_and_log(parsed.logPath.c_str(), "phase=attach status=ok detail=pid=%d", target_pid);

    if (!file_exists(parsed.loaderPath) || !file_exists(parsed.hookBridgePath) || !file_exists(parsed.payloadPath)) {
        print_and_log(parsed.logPath.c_str(), "phase=preflight status=failed detail=artifact missing");
        fprintf(stderr, "inj64: required runtime artifact missing\n");
        return 66;
    }

    if (parsed.dryRun) {
        print_and_log(parsed.logPath.c_str(), "phase=preflight status=ok detail=dry-run");
        printf("inj64 dry-run ok target=%s pid=%d stage=%s log=%s\n",
            parsed.targetProcess.c_str(), target_pid, parsed.stage.c_str(), parsed.logPath.c_str());
        return 0;
    }

    print_and_log(parsed.logPath.c_str(), "phase=loader_prepare status=ok detail=loader=%s",
        parsed.loaderPath.c_str());
    void* hook_handle = dlopen(parsed.hookBridgePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (hook_handle == nullptr) {
        fprintf(stderr, "inj64: dlopen hook bridge failed: %s\n", dlerror());
        print_and_log(parsed.logPath.c_str(), "phase=hook_bridge_prepare status=failed detail=dlopen");
        return 68;
    }
    FlHookBridgePingFn ping = reinterpret_cast<FlHookBridgePingFn>(dlsym(hook_handle, "flh_ping"));
    FlRuntimeRequest request = {};
    request.target_process = parsed.targetProcess.c_str();
    request.stage = parsed.stage.c_str();
    request.abi = parsed.abi.c_str();
    request.loader_path = parsed.loaderPath.c_str();
    request.hook_bridge_path = parsed.hookBridgePath.c_str();
    request.payload_path = parsed.payloadPath.c_str();
    request.hook_seed_path = hook_seed_path.c_str();
    request.entrypoint = parsed.entrypoint.c_str();
    request.log_path = parsed.logPath.c_str();
    request.plan_path = plan_path.c_str();
    request.dry_run = 0;
    request.target_pid = target_pid;

    std::string target_exe = read_link_target("/proc/" + std::to_string(target_pid) + "/exe");
    std::string target_status = read_proc_status_excerpt(target_pid);
    std::string target_maps_excerpt = read_maps_excerpt(target_pid, 32);
    std::vector<RemoteModuleInfo> remote_modules = read_remote_modules(target_pid);
    std::string ptrace_detail = "not attempted";
    bool ptrace_ok = false;
    RegisterSnapshot register_snapshot;
    RemoteSymbolPlan remote_dlopen;
    RemoteSymbolPlan remote_dlsym;
    RemoteSymbolPlan remote_mmap;
    RemoteArgumentLayout remote_args = build_remote_argument_layout(request);
    RemoteTransactionLayout remote_tx = build_remote_transaction_layout(request, remote_args);
    std::vector<unsigned char> remote_blob = build_remote_string_block(request, remote_args);
    RemoteBlobPreview remote_blob_preview = preview_blob_hex(remote_blob, 128);
    if (!parsed.dryRun) {
        ptrace_ok = attempt_ptrace_attach_probe(target_pid, parsed.logPath.c_str(), &ptrace_detail);
        print_and_log(parsed.logPath.c_str(), "phase=remote_attach_probe status=%s detail=%s",
            ptrace_ok ? "ok" : "warning",
            ptrace_detail.c_str());
        if (ptrace_ok) {
            if (ptrace(PTRACE_ATTACH, target_pid, nullptr, nullptr) == 0) {
                int status = 0;
                if (waitpid(target_pid, &status, 0) >= 0) {
                    register_snapshot = capture_register_snapshot(target_pid);
                }
                ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
            } else {
                register_snapshot.detail = std::string("second ptrace attach errno=") + std::to_string(errno);
            }
            remote_dlopen = resolve_remote_symbol_from_local(
                remote_modules,
                "dlopen",
                {"libdl.so", "/linker64", "/apex/com.android.runtime/bin/linker64"}
            );
            remote_dlsym = resolve_remote_symbol_from_local(
                remote_modules,
                "dlsym",
                {"libdl.so", "/linker64", "/apex/com.android.runtime/bin/linker64"}
            );
            remote_mmap = resolve_remote_symbol_from_local(
                remote_modules,
                "mmap",
                {"libc.so", "/libc.so"}
            );
            print_and_log(parsed.logPath.c_str(), "phase=remote_symbol_plan status=%s detail=dlopen=0x%" PRIxPTR " dlsym=0x%" PRIxPTR " mmap=0x%" PRIxPTR,
                (remote_dlopen.resolved && remote_dlsym.resolved && remote_mmap.resolved) ? "ok" : "warning",
                remote_dlopen.remoteSymbol,
                remote_dlsym.remoteSymbol,
                remote_mmap.remoteSymbol);
        }
    } else {
        print_and_log(parsed.logPath.c_str(), "phase=remote_attach_probe status=ok detail=dry-run skipped ptrace");
    }
    write_injection_plan(
        request,
        selinux,
        target_exe,
        target_status,
        target_maps_excerpt,
        register_snapshot,
        remote_modules,
        remote_dlopen,
        remote_dlsym,
        remote_mmap,
        remote_args,
        remote_tx,
        remote_blob_preview,
        ptrace_ok,
        ptrace_detail
    );
    print_and_log(parsed.logPath.c_str(), "phase=remote_plan status=ok detail=plan=%s",
        plan_path.c_str());

    FlRuntimeResult hook_result = {};
    if (ping != nullptr) {
        int hook_code = ping(&request, &hook_result);
        print_and_log(parsed.logPath.c_str(), "phase=hook_bridge_prepare status=%s detail=%s",
            hook_code == 0 ? "ok" : "warning",
            hook_result.message[0] == '\0' ? "bridge pinged" : hook_result.message);
    } else {
        print_and_log(parsed.logPath.c_str(), "phase=hook_bridge_prepare status=warning detail=missing flh_ping");
    }

    void* loader_handle = dlopen(parsed.loaderPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (loader_handle == nullptr) {
        fprintf(stderr, "inj64: dlopen loader failed: %s\n", dlerror());
        print_and_log(parsed.logPath.c_str(), "phase=payload_resolve status=failed detail=dlopen loader");
        dlclose(hook_handle);
        return 69;
    }
    FlLoaderEntryFn loader_entry =
        reinterpret_cast<FlLoaderEntryFn>(dlsym(loader_handle, "fl_loader_entry"));
    if (loader_entry == nullptr) {
        fprintf(stderr, "inj64: missing fl_loader_entry symbol\n");
        print_and_log(parsed.logPath.c_str(), "phase=payload_resolve status=failed detail=missing entry");
        dlclose(loader_handle);
        dlclose(hook_handle);
        return 70;
    }

    print_and_log(parsed.logPath.c_str(), "phase=payload_resolve status=ok detail=payload=%s",
        parsed.payloadPath.c_str());
    FlRuntimeResult loader_result = {};
    int loader_code = loader_entry(&request, &loader_result);
    print_and_log(parsed.logPath.c_str(), "phase=entry_dispatch status=%s detail=%s",
        loader_code == 0 ? "ok" : "failed",
        loader_result.message[0] == '\0' ? "loader returned" : loader_result.message);

    std::string safe = parsed.targetProcess;
    for (char& ch : safe) {
        if (ch == '.' || ch == ':') {
            ch = '_';
        }
    }
    std::string marker_path = "/data/fl/logs/last_injection_" + safe + "_" + parsed.stage + ".txt";
    ensure_parent_dir(marker_path);
    FILE* marker = fopen(marker_path.c_str(), "w");
    if (marker != nullptr) {
        fprintf(marker, "status=%s\n", loader_code == 0 ? "native_loader_invoked" : "native_loader_failed");
        fprintf(marker, "target_process=%s\n", parsed.targetProcess.c_str());
        fprintf(marker, "pid=%d\n", target_pid);
        fprintf(marker, "stage=%s\n", parsed.stage.c_str());
        fprintf(marker, "entrypoint=%s\n", parsed.entrypoint.c_str());
        fprintf(marker, "selinux=%s\n", selinux);
        fprintf(marker, "loader=%s\n", parsed.loaderPath.c_str());
        fprintf(marker, "hook_bridge=%s\n", parsed.hookBridgePath.c_str());
        fprintf(marker, "payload=%s\n", parsed.payloadPath.c_str());
        fprintf(marker, "hook_seed=%s\n", hook_seed_path.c_str());
        fprintf(marker, "plan_path=%s\n", plan_path.c_str());
        fprintf(marker, "ptrace_probe_ok=%s\n", ptrace_ok ? "true" : "false");
        fprintf(marker, "ptrace_probe_detail=%s\n", ptrace_detail.c_str());
        fprintf(marker, "loader_result_code=%d\n", loader_result.code);
        fprintf(marker, "loader_result_message=%s\n", loader_result.message);
        fclose(marker);
    }
    print_and_log(parsed.logPath.c_str(), "phase=finalize status=%s detail=marker=%s",
        loader_code == 0 ? "ok" : "failed",
        marker_path.c_str());

    dlclose(loader_handle);
    dlclose(hook_handle);

    if (loader_code != 0) {
        fprintf(stderr, "inj64: loader failed: %s\n", loader_result.message);
        return loader_code;
    }
    printf("inj64 execute ok target=%s pid=%d stage=%s log=%s marker=%s\n",
        parsed.targetProcess.c_str(), target_pid, parsed.stage.c_str(), parsed.logPath.c_str(), marker_path.c_str());
    return 0;
}
