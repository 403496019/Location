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
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char* kRemoteExecutionLogRoot = "/data/local/tmp/fakelocation/logs";

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
    bool stdoutOnly = false;
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
    size_t targetProcessOffset = 0;
    size_t stageOffset = 0;
    size_t abiOffset = 0;
    size_t totalSize = 0;
    size_t totalSizeAligned = 0;
    size_t loaderPathOffset = 0;
    size_t hookBridgePathOffset = 0;
    size_t payloadPathOffset = 0;
    size_t hookSeedPathOffset = 0;
    size_t entrypointOffset = 0;
    size_t logPathOffset = 0;
    size_t planPathOffset = 0;
};

struct RemoteTransactionLayout {
    size_t stringBlockOffset = 0;
    size_t stringBlockSize = 0;
    size_t requestBlockOffset = 0;
    size_t requestBlockSize = 0;
    size_t resultBlockOffset = 0;
    size_t resultBlockSize = 0;
    size_t symbolNameOffset = 0;
    size_t symbolNameSize = 0;
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

std::string derive_mock_state_path(const std::string& payload_path) {
    size_t payload_index = payload_path.rfind("/payload/");
    if (payload_index == std::string::npos) {
        return "/data/fl/metadata/mock-location-state.txt";
    }
    return payload_path.substr(0, payload_index) + "/metadata/mock-location-state.txt";
}

std::string derive_plan_path(const std::string& process_name, const std::string& stage) {
    std::string safe = process_name;
    for (char& ch : safe) {
        if (ch == '.' || ch == ':') {
            ch = '_';
        }
    }
    return std::string(kRemoteExecutionLogRoot) + "/injection_plan_" + safe + "_" + stage + ".txt";
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
    layout.targetProcessOffset = cursor;
    cursor += strlen(request.target_process != nullptr ? request.target_process : "") + 1;
    layout.stageOffset = cursor;
    cursor += strlen(request.stage != nullptr ? request.stage : "") + 1;
    layout.abiOffset = cursor;
    cursor += strlen(request.abi != nullptr ? request.abi : "") + 1;
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
    layout.logPathOffset = cursor;
    cursor += strlen(request.log_path != nullptr ? request.log_path : "") + 1;
    layout.planPathOffset = cursor;
    cursor += strlen(request.plan_path != nullptr ? request.plan_path : "") + 1;
    layout.totalSize = cursor;
    layout.totalSizeAligned = align_up(cursor, 4096);
    return layout;
}

RemoteTransactionLayout build_remote_transaction_layout(
    const FlRuntimeRequest& request,
    const RemoteArgumentLayout& args
) {
    RemoteTransactionLayout layout;
    constexpr size_t kRemoteSymbolNameSize = 64;
    layout.stringBlockOffset = 0;
    layout.stringBlockSize = args.totalSize;
    layout.requestBlockOffset = align_up(layout.stringBlockOffset + layout.stringBlockSize, alignof(FlRuntimeRequest));
    layout.requestBlockSize = sizeof(FlRuntimeRequest);
    layout.resultBlockOffset = align_up(layout.requestBlockOffset + layout.requestBlockSize, alignof(FlRuntimeResult));
    layout.resultBlockSize = sizeof(FlRuntimeResult);
    layout.symbolNameOffset = align_up(layout.resultBlockOffset + layout.resultBlockSize, alignof(char));
    layout.symbolNameSize = kRemoteSymbolNameSize;
    layout.totalSize = layout.symbolNameOffset + layout.symbolNameSize;
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
    write_string(layout.targetProcessOffset, request.target_process);
    write_string(layout.stageOffset, request.stage);
    write_string(layout.abiOffset, request.abi);
    write_string(layout.loaderPathOffset, request.loader_path);
    write_string(layout.hookBridgePathOffset, request.hook_bridge_path);
    write_string(layout.payloadPathOffset, request.payload_path);
    write_string(layout.hookSeedPathOffset, request.hook_seed_path);
    write_string(layout.entrypointOffset, request.entrypoint);
    write_string(layout.logPathOffset, request.log_path);
    write_string(layout.planPathOffset, request.plan_path);
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

// ── aarch64 full register structure (shared across all remote ops) ──
struct aarch64_user_regs {
    unsigned long long regs[31];  // x0–x30
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

bool aarch64_save_regs(int pid, aarch64_user_regs* out) {
    if (out == nullptr) return false;
    memset(out, 0, sizeof(*out));
    struct iovec io = { .iov_base = out, .iov_len = sizeof(*out) };
    return ptrace(PTRACE_GETREGSET, pid, reinterpret_cast<void*>(NT_PRSTATUS), &io) == 0;
}

bool aarch64_restore_regs(int pid, const aarch64_user_regs* regs) {
    if (regs == nullptr) return false;
    struct iovec io = { .iov_base = const_cast<aarch64_user_regs*>(regs), .iov_len = sizeof(*regs) };
    return ptrace(PTRACE_SETREGSET, pid, reinterpret_cast<void*>(NT_PRSTATUS), &io) == 0;
}

// Set up registers for a remote function call.  LR is set to 0 so the
// function will SIGSEGV on return — we catch that in remote_call_and_wait.
bool aarch64_set_call_regs(
    int pid, const aarch64_user_regs* base,
    uintptr_t fn,
    uintptr_t x0, uintptr_t x1, uintptr_t x2, uintptr_t x3,
    uintptr_t x4, uintptr_t x5, uintptr_t x6, uintptr_t x7)
{
    aarch64_user_regs call_regs = *base;
    call_regs.regs[0]  = x0;
    call_regs.regs[1]  = x1;
    call_regs.regs[2]  = x2;
    call_regs.regs[3]  = x3;
    call_regs.regs[4]  = x4;
    call_regs.regs[5]  = x5;
    call_regs.regs[6]  = x6;
    call_regs.regs[7]  = x7;
    call_regs.pc        = fn;
    call_regs.regs[30]  = 0;   // LR = 0  →  crash on return
    // Make room for callee stack usage (shrink SP by one page).
    if (call_regs.sp > 4096) {
        call_regs.sp -= 4096;
    }
    return aarch64_restore_regs(pid, &call_regs);
}

// PTRACE_CONT with signal 0 (suppress pending signal), then wait for the
// tracee to stop again (it will SIGSEGV when the function returns to 0).
// After that, read x0 and restore the saved base registers.
// Returns the value of x0 on success; (uintptr_t)-1 on failure.
uintptr_t aarch64_remote_call_and_wait(
    int pid, const aarch64_user_regs* base, const char* log_path)
{
    // Continue, suppressing the pending signal (SIGSTOP or previous crash).
    if (ptrace(PTRACE_CONT, pid, nullptr, reinterpret_cast<void*>(0)) != 0) {
        write_log_line(log_path, "remote_call: PTRACE_CONT errno=%d", errno);
        return static_cast<uintptr_t>(-1);
    }

    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        write_log_line(log_path, "remote_call: waitpid errno=%d", errno);
        return static_cast<uintptr_t>(-1);
    }

    // Read x0 from the stopped state.
    aarch64_user_regs ret_regs = {};
    uintptr_t result = static_cast<uintptr_t>(-1);
    if (aarch64_save_regs(pid, &ret_regs)) {
        result = static_cast<uintptr_t>(ret_regs.regs[0]);
    }

    int stop_sig = WIFSTOPPED(status) ? WSTOPSIG(status) : 0;
    write_log_line(log_path, "remote_call: stopped sig=%d x0=0x%" PRIxPTR, stop_sig, result);

    // Restore original registers so the tracee is ready for the next call.
    aarch64_restore_regs(pid, base);
    return result;
}

// ── remote memory access via /proc/<pid>/mem (requires ptrace-attach) ──

ssize_t remote_mem_write(int pid, uintptr_t addr, const void* data, size_t len) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    off_t off = lseek(fd, static_cast<off_t>(addr), SEEK_SET);
    if (off < 0) { close(fd); return -1; }
    ssize_t wrote = write(fd, data, len);
    close(fd);
    return wrote;
}

ssize_t remote_mem_read(int pid, uintptr_t addr, void* buf, size_t len) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    off_t off = lseek(fd, static_cast<off_t>(addr), SEEK_SET);
    if (off < 0) { close(fd); return -1; }
    ssize_t n = read(fd, buf, len);
    close(fd);
    return n;
}

// ── full remote-injection orchestration ──
// Executes:  mmap → write params → dlopen(loader) → dlsym(fl_loader_entry)
//            → call entry(request, result) → read result
// Returns true if the entire chain executed; out_result holds the loader's output.

bool execute_remote_injection(
    int pid,
    const aarch64_user_regs* saved_regs,
    const FlRuntimeRequest& request,
    const RemoteSymbolPlan& dlopen_plan,
    const RemoteSymbolPlan& dlsym_plan,
    const RemoteSymbolPlan& mmap_plan,
    const RemoteArgumentLayout& args_layout,
    const RemoteTransactionLayout& tx_layout,
    const std::vector<unsigned char>& string_block,
    FlRuntimeResult* out_result,
    const char* log_path)
{
    if (!dlopen_plan.resolved || !dlsym_plan.resolved || !mmap_plan.resolved) {
        write_log_line(log_path, "remote_inject: unresolved symbols — abort");
        return false;
    }

    // Step 1 — remote mmap(NULL, size, PROT_READ|PROT_WRITE|PROT_EXEC,
    //                      MAP_PRIVATE|MAP_ANONYMOUS, -1, 0)
    const uintptr_t prot = PROT_READ | PROT_WRITE | PROT_EXEC;   // 7
    const uintptr_t flags = MAP_PRIVATE | MAP_ANONYMOUS;         // 0x22
    aarch64_set_call_regs(pid, saved_regs, mmap_plan.remoteSymbol,
        0, tx_layout.totalSizeAligned, prot, flags,
        static_cast<uintptr_t>(-1), 0, 0, 0);
    uintptr_t remote_block = aarch64_remote_call_and_wait(pid, saved_regs, log_path);
    if (remote_block == static_cast<uintptr_t>(-1) || remote_block == 0) {
        write_log_line(log_path, "remote_inject: mmap failed block=0x%" PRIxPTR, remote_block);
        return false;
    }
    write_log_line(log_path, "remote_inject: mmap ok block=0x%" PRIxPTR " size=%zu",
        remote_block, tx_layout.totalSizeAligned);

    // Step 2 — build the complete remote transaction blob and write it
    std::vector<unsigned char> blob(tx_layout.totalSizeAligned, 0);
    // Copy string block
    memcpy(blob.data() + tx_layout.stringBlockOffset,
        string_block.data(), string_block.size());
    // Build request struct with pointers rebased into remote block
    FlRuntimeRequest remote_req = request;
    remote_req.target_process   = nullptr;
    remote_req.stage            = nullptr;
    remote_req.abi              = nullptr;
    remote_req.loader_path      = nullptr;  // will be patched below
    remote_req.hook_bridge_path = nullptr;
    remote_req.payload_path     = nullptr;
    remote_req.hook_seed_path   = nullptr;
    remote_req.entrypoint       = nullptr;
    remote_req.log_path         = nullptr;
    remote_req.plan_path        = nullptr;
    // Patch string pointers to offsets inside remote_block.
    remote_req.target_process   = reinterpret_cast<const char*>(remote_block + args_layout.targetProcessOffset);
    remote_req.stage            = reinterpret_cast<const char*>(remote_block + args_layout.stageOffset);
    remote_req.abi              = reinterpret_cast<const char*>(remote_block + args_layout.abiOffset);
    remote_req.loader_path      = reinterpret_cast<const char*>(remote_block + args_layout.loaderPathOffset);
    remote_req.hook_bridge_path = reinterpret_cast<const char*>(remote_block + args_layout.hookBridgePathOffset);
    remote_req.payload_path     = reinterpret_cast<const char*>(remote_block + args_layout.payloadPathOffset);
    remote_req.hook_seed_path   = reinterpret_cast<const char*>(remote_block + args_layout.hookSeedPathOffset);
    remote_req.entrypoint       = reinterpret_cast<const char*>(remote_block + args_layout.entrypointOffset);
    remote_req.log_path         = reinterpret_cast<const char*>(remote_block + args_layout.logPathOffset);
    remote_req.plan_path        = reinterpret_cast<const char*>(remote_block + args_layout.planPathOffset);

    memcpy(blob.data() + tx_layout.requestBlockOffset, &remote_req, sizeof(remote_req));
    // Zero-initialize the result block
    memset(blob.data() + tx_layout.resultBlockOffset, 0, tx_layout.resultBlockSize);

    ssize_t wrote = remote_mem_write(pid, remote_block, blob.data(), blob.size());
    if (wrote != static_cast<ssize_t>(blob.size())) {
        write_log_line(log_path, "remote_inject: write failed wrote=%zd expected=%zu errno=%d",
            wrote, blob.size(), errno);
        return false;
    }
    write_log_line(log_path, "remote_inject: wrote %zd bytes to block=0x%" PRIxPTR, wrote, remote_block);

    // Step 3 — remote dlopen(loader_path, RTLD_NOW)
    const uintptr_t rmt_loader_path = remote_block + args_layout.loaderPathOffset;
    aarch64_set_call_regs(pid, saved_regs, dlopen_plan.remoteSymbol,
        rmt_loader_path, RTLD_NOW, 0, 0, 0, 0, 0, 0);
    uintptr_t loader_handle = aarch64_remote_call_and_wait(pid, saved_regs, log_path);
    if (loader_handle == static_cast<uintptr_t>(-1) || loader_handle == 0) {
        write_log_line(log_path, "remote_inject: dlopen failed handle=0x%" PRIxPTR, loader_handle);
        return false;
    }
    write_log_line(log_path, "remote_inject: dlopen ok handle=0x%" PRIxPTR, loader_handle);

    // Step 4 — write "fl_loader_entry\0" string into remote block for dlsym
    const char* entry_sym = "fl_loader_entry";
    // Place the symbol name in the dedicated scratch area after the result block.
    const size_t sym_name_offset = tx_layout.symbolNameOffset;
    if (sym_name_offset + strlen(entry_sym) + 1 <= blob.size()) {
        memcpy(blob.data() + sym_name_offset, entry_sym, strlen(entry_sym) + 1);
        wrote = remote_mem_write(pid, remote_block + sym_name_offset,
            blob.data() + sym_name_offset, strlen(entry_sym) + 1);
            if (wrote != static_cast<ssize_t>(strlen(entry_sym) + 1)) {
                write_log_line(log_path, "remote_inject: write sym name failed");
                return false;
            }
        }

    // Step 5 — remote dlsym(handle, "fl_loader_entry")
    aarch64_set_call_regs(pid, saved_regs, dlsym_plan.remoteSymbol,
        loader_handle, remote_block + sym_name_offset, 0, 0, 0, 0, 0, 0);
    uintptr_t entry_fn = aarch64_remote_call_and_wait(pid, saved_regs, log_path);
    if (entry_fn == static_cast<uintptr_t>(-1) || entry_fn == 0) {
        write_log_line(log_path, "remote_inject: dlsym failed fn=0x%" PRIxPTR, entry_fn);
        return false;
    }
    write_log_line(log_path, "remote_inject: dlsym ok fn=0x%" PRIxPTR, entry_fn);

    // Step 6 — remote call fl_loader_entry(request_ptr, result_ptr)
    const uintptr_t rmt_req_ptr  = remote_block + tx_layout.requestBlockOffset;
    const uintptr_t rmt_res_ptr  = remote_block + tx_layout.resultBlockOffset;
    aarch64_set_call_regs(pid, saved_regs, entry_fn,
        rmt_req_ptr, rmt_res_ptr, 0, 0, 0, 0, 0, 0);
    uintptr_t entry_rc = aarch64_remote_call_and_wait(pid, saved_regs, log_path);
    write_log_line(log_path, "remote_inject: entry returned x0=%" PRIdPTR, static_cast<intptr_t>(entry_rc));

    // Step 7 — read result block back
    FlRuntimeResult remote_result = {};
    ssize_t nr = remote_mem_read(pid, remote_block + tx_layout.resultBlockOffset,
        &remote_result, sizeof(remote_result));
    if (nr == sizeof(remote_result) && out_result != nullptr) {
        *out_result = remote_result;
    }
    write_log_line(log_path, "remote_inject: result code=%d msg=%s",
        remote_result.code, remote_result.message);

    return true;
}

// ── convenience: capture register snapshot from saved regs ──
RegisterSnapshot snapshot_from_saved_regs(const aarch64_user_regs& regs, bool available) {
    RegisterSnapshot s;
    s.available = available;
    s.pc = regs.pc;
    s.sp = regs.sp;
    s.lr = regs.regs[30];
    s.detail = available ? "aarch64 register snapshot ok" : "aarch64 regs unavailable";
    return s;
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
    std::string mock_state_path = derive_mock_state_path(request.payload_path);
    fprintf(file, "mock_state_path=%s\n", mock_state_path.c_str());
    fprintf(file, "mock_state_size=%ld\n", file_size_or_minus_one(mock_state_path));
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
        } else if (arg == "--stdout-only") {
            parsed->stdoutOnly = true;
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

    if (!parsed.stdoutOnly && parsed.logPath.empty()) {
        std::string safe = parsed.targetProcess;
        for (char& ch : safe) {
            if (ch == '.' || ch == ':') {
                ch = '_';
            }
        }
        parsed.logPath = std::string(kRemoteExecutionLogRoot) + "/inj64_" + safe + "_" + parsed.stage + ".log";
    }
    if (!parsed.logPath.empty()) {
        ensure_parent_dir(parsed.logPath);
    }

    const int target_pid = find_pid_by_process_name(parsed.targetProcess);
    const char* selinux = read_selinux_mode();
    std::string hook_seed_path = derive_hook_seed_path(parsed.payloadPath);
    std::string mock_state_path = derive_mock_state_path(parsed.payloadPath);
    std::string plan_path;
    if (!parsed.stdoutOnly) {
        plan_path = derive_plan_path(parsed.targetProcess, parsed.stage);
    }
    write_log_line(parsed.logPath.c_str(), "[inj64] target=%s stage=%s abi=%s selinux=%s",
        parsed.targetProcess.c_str(), parsed.stage.c_str(), parsed.abi.c_str(), selinux);

    if (target_pid <= 0) {
        print_and_log(parsed.logPath.c_str(), "phase=attach status=failed detail=target process missing");
        fprintf(stderr, "inj64: target process not running: %s\n", parsed.targetProcess.c_str());
        return 67;
    }
    print_and_log(parsed.logPath.c_str(), "phase=attach status=ok detail=pid=%d", target_pid);

    if (!file_exists(parsed.loaderPath) ||
        !file_exists(parsed.hookBridgePath) ||
        !file_exists(parsed.payloadPath) ||
        !file_exists(mock_state_path)) {
        print_and_log(parsed.logPath.c_str(), "phase=preflight status=failed detail=artifact missing");
        fprintf(stderr, "inj64: required runtime artifact missing\n");
        return 66;
    }
    print_and_log(parsed.logPath.c_str(), "phase=mock_state status=ok detail=state=%s", mock_state_path.c_str());

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
    request.log_path = parsed.logPath.empty() ? nullptr : parsed.logPath.c_str();
    request.plan_path = plan_path.empty() ? nullptr : plan_path.c_str();
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

    // ── ptrace: probe, then stay attached for remote execution ──
    bool remote_possible = false;
    aarch64_user_regs saved_regs = {};
    bool ptrace_attached = false;

    if (!parsed.dryRun) {
        ptrace_ok = attempt_ptrace_attach_probe(target_pid, parsed.logPath.c_str(), &ptrace_detail);
        print_and_log(parsed.logPath.c_str(), "phase=remote_attach_probe status=%s detail=%s",
            ptrace_ok ? "ok" : "warning",
            ptrace_detail.c_str());

        if (ptrace_ok) {
            // Second attach — this time we stay attached for the real work.
            if (ptrace(PTRACE_ATTACH, target_pid, nullptr, nullptr) == 0) {
                int status = 0;
                if (waitpid(target_pid, &status, 0) >= 0) {
                    ptrace_attached = true;
                    bool regs_ok = aarch64_save_regs(target_pid, &saved_regs);
                    register_snapshot = snapshot_from_saved_regs(saved_regs, regs_ok);
                    print_and_log(parsed.logPath.c_str(),
                        "phase=register_capture status=%s pc=0x%" PRIx64 " sp=0x%" PRIx64 " lr=0x%" PRIx64,
                        regs_ok ? "ok" : "failed",
                        register_snapshot.pc, register_snapshot.sp, register_snapshot.lr);
                } else {
                    register_snapshot.detail = std::string("waitpid errno=") + std::to_string(errno);
                }
            } else {
                register_snapshot.detail = std::string("second ptrace attach errno=") + std::to_string(errno);
            }

            // Resolve remote symbols (need the process attached for /proc/pid/maps)
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
            remote_possible = remote_dlopen.resolved && remote_dlsym.resolved && remote_mmap.resolved;

            print_and_log(parsed.logPath.c_str(),
                "phase=remote_symbol_plan status=%s detail=dlopen=0x%" PRIxPTR " dlsym=0x%" PRIxPTR " mmap=0x%" PRIxPTR,
                remote_possible ? "ok" : "warning",
                remote_dlopen.remoteSymbol,
                remote_dlsym.remoteSymbol,
                remote_mmap.remoteSymbol);
        }
    } else {
        print_and_log(parsed.logPath.c_str(), "phase=remote_attach_probe status=ok detail=dry-run skipped ptrace");
    }

    // ── always write the injection plan (useful for diagnostics) ──
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
    print_and_log(parsed.logPath.c_str(), "phase=remote_plan status=%s detail=%s",
        plan_path.empty() ? "skipped" : "ok",
        plan_path.empty() ? "stdout-only" : plan_path.c_str());

    // ── local hook bridge ping (pre-flight validation) ──
    FlRuntimeResult hook_result = {};
    if (ping != nullptr) {
        int hook_code = ping(&request, &hook_result);
        print_and_log(parsed.logPath.c_str(), "phase=hook_bridge_prepare status=%s detail=%s",
            hook_code == 0 ? "ok" : "warning",
            hook_result.message[0] == '\0' ? "bridge pinged" : hook_result.message);
    } else {
        print_and_log(parsed.logPath.c_str(), "phase=hook_bridge_prepare status=warning detail=missing flh_ping");
    }

    // ── payload dispatch: remote injection (preferred) or local dlopen (fallback) ──
    FlRuntimeResult loader_result = {};
    int loader_code = -1;
    bool remote_executed = false;

    if (ptrace_attached && remote_possible && !parsed.dryRun) {
        print_and_log(parsed.logPath.c_str(),
            "phase=remote_inject_start status=ok detail=target_pid=%d symbols=resolved", target_pid);

        bool remote_ok = execute_remote_injection(
            target_pid,
            &saved_regs,
            request,
            remote_dlopen,
            remote_dlsym,
            remote_mmap,
            remote_args,
            remote_tx,
            remote_blob,
            &loader_result,
            parsed.logPath.c_str()
        );

        if (remote_ok) {
            remote_executed = true;
            loader_code = loader_result.code;
            print_and_log(parsed.logPath.c_str(),
                "phase=entry_dispatch status=%s detail=remote code=%d msg=%s",
                loader_code == 0 ? "ok" : "failed",
                loader_code,
                loader_result.message[0] == '\0' ? "remote loader returned" : loader_result.message);
        } else {
            print_and_log(parsed.logPath.c_str(),
                "phase=remote_inject status=failed detail=remote execution chain failed; will attempt local fallback");
        }
    }

    if (!remote_executed) {
        // ── local fallback: dlopen loader in inj64's own process ──
        void* loader_handle = dlopen(parsed.loaderPath.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (loader_handle == nullptr) {
            fprintf(stderr, "inj64: dlopen loader failed: %s\n", dlerror());
            print_and_log(parsed.logPath.c_str(), "phase=payload_resolve status=failed detail=dlopen loader");
            if (ptrace_attached) {
                aarch64_restore_regs(target_pid, &saved_regs);
                ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
            }
            dlclose(hook_handle);
            return 69;
        }
        FlLoaderEntryFn loader_entry =
            reinterpret_cast<FlLoaderEntryFn>(dlsym(loader_handle, "fl_loader_entry"));
        if (loader_entry == nullptr) {
            fprintf(stderr, "inj64: missing fl_loader_entry symbol\n");
            print_and_log(parsed.logPath.c_str(), "phase=payload_resolve status=failed detail=missing entry");
            dlclose(loader_handle);
            if (ptrace_attached) {
                aarch64_restore_regs(target_pid, &saved_regs);
                ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
            }
            dlclose(hook_handle);
            return 70;
        }
        print_and_log(parsed.logPath.c_str(), "phase=payload_resolve status=ok detail=payload=%s (local fallback)",
            parsed.payloadPath.c_str());
        loader_code = loader_entry(&request, &loader_result);
        print_and_log(parsed.logPath.c_str(), "phase=entry_dispatch status=%s detail=%s (local)",
            loader_code == 0 ? "ok" : "failed",
            loader_result.message[0] == '\0' ? "loader returned" : loader_result.message);
        dlclose(loader_handle);
    }

    // ── cleanup: detach from target if we were attached ──
    if (ptrace_attached) {
        aarch64_restore_regs(target_pid, &saved_regs);
        // PTRACE_DETACH with signal 0 suppresses any pending signal (SIGSEGV from LR=0).
        long detach_rc = ptrace(PTRACE_DETACH, target_pid, nullptr, nullptr);
        if (detach_rc != 0) {
            write_log_line(parsed.logPath.c_str(), "ptrace_detach warning errno=%d", errno);
        }
        ptrace_attached = false;
        print_and_log(parsed.logPath.c_str(), "phase=ptrace_detach status=%s",
            detach_rc == 0 ? "ok" : "warning");
    }
    dlclose(hook_handle);

    // ── write completion marker ──
    std::string safe = parsed.targetProcess;
    for (char& ch : safe) {
        if (ch == '.' || ch == ':') {
            ch = '_';
        }
    }
    std::string marker_path;
    if (!parsed.stdoutOnly) {
        marker_path = std::string(kRemoteExecutionLogRoot) + "/last_injection_" + safe + "_" + parsed.stage + ".txt";
        ensure_parent_dir(marker_path);
        FILE* marker = fopen(marker_path.c_str(), "w");
        if (marker != nullptr) {
            fprintf(marker, "status=%s\n",
                loader_code == 0 ? (remote_executed ? "remote_loader_ok" : "local_loader_ok") : "loader_failed");
            fprintf(marker, "target_process=%s\n", parsed.targetProcess.c_str());
            fprintf(marker, "pid=%d\n", target_pid);
            fprintf(marker, "stage=%s\n", parsed.stage.c_str());
            fprintf(marker, "entrypoint=%s\n", parsed.entrypoint.c_str());
            fprintf(marker, "selinux=%s\n", selinux);
            fprintf(marker, "loader=%s\n", parsed.loaderPath.c_str());
            fprintf(marker, "hook_bridge=%s\n", parsed.hookBridgePath.c_str());
            fprintf(marker, "payload=%s\n", parsed.payloadPath.c_str());
            fprintf(marker, "hook_seed=%s\n", hook_seed_path.c_str());
            fprintf(marker, "mock_state=%s\n", mock_state_path.c_str());
            fprintf(marker, "plan_path=%s\n", plan_path.c_str());
            fprintf(marker, "ptrace_probe_ok=%s\n", ptrace_ok ? "true" : "false");
            fprintf(marker, "remote_possible=%s\n", remote_possible ? "true" : "false");
            fprintf(marker, "remote_executed=%s\n", remote_executed ? "true" : "false");
            fprintf(marker, "ptrace_probe_detail=%s\n", ptrace_detail.c_str());
            fprintf(marker, "loader_result_code=%d\n", loader_result.code);
            fprintf(marker, "loader_result_message=%s\n", loader_result.message);
            fclose(marker);
        }
    }
    print_and_log(parsed.logPath.c_str(), "phase=finalize status=%s detail=marker=%s remote=%s",
        loader_code == 0 ? "ok" : "failed",
        marker_path.empty() ? "stdout-only" : marker_path.c_str(),
        remote_executed ? "true" : "false");

    if (loader_code != 0) {
        fprintf(stderr, "inj64: loader failed: %s\n", loader_result.message);
        return loader_code;
    }
    printf("inj64 execute ok target=%s pid=%d stage=%s log=%s marker=%s remote=%s\n",
        parsed.targetProcess.c_str(), target_pid, parsed.stage.c_str(),
        parsed.logPath.empty() ? "stdout-only" : parsed.logPath.c_str(),
        marker_path.empty() ? "stdout-only" : marker_path.c_str(),
        remote_executed ? "true" : "false");
    return 0;
}
