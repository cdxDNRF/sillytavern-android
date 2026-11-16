#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "node.h"
#include "v8.h"

namespace {

constexpr const char* kLogTag = "SillyTavernNode";
constexpr size_t kMaxLogLines = 400;
constexpr size_t kMaxLogBytes = 128 * 1024;
constexpr off_t kMaxDiskLogBytes = 192 * 1024;

JavaVM* g_jvm = nullptr;

std::mutex g_log_mutex;
std::deque<std::string> g_log_lines;
size_t g_log_bytes = 0;
std::once_flag g_stdio_redirect_once;

// Fixed async-signal-safe buffers populated by nativeSetDiagDir before crashes can happen.
std::mutex g_diag_mutex;
std::atomic<bool> g_diag_ready{false};
char g_native_log_path[1024] = {0};
char g_native_prev_log_path[1024] = {0};
char g_crash_log_path[1024] = {0};
char g_last_milestone[160] = "uninitialized";

std::once_flag g_crash_handler_once;
constexpr int kMonitoredSignals[] = {SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGFPE, SIGTRAP};
constexpr size_t kNumMonitoredSignals = sizeof(kMonitoredSignals) / sizeof(kMonitoredSignals[0]);
struct sigaction g_prev_sigactions[kNumMonitoredSignals] = {};
std::atomic<int> g_in_crash_handler{0};

std::mutex g_env_mutex;
node::Environment* g_active_env = nullptr;
std::atomic<bool> g_node_running{false};
std::atomic<bool> g_stop_requested{false};

std::once_flag g_node_process_init_once;
std::shared_ptr<node::InitializationResult> g_init_result;
node::MultiIsolatePlatform* g_platform = nullptr;

std::mutex g_ares_mutex;
jobject g_connectivity_manager_global = nullptr;

std::mutex g_callback_mutex;
jobject g_callback_global = nullptr;
jmethodID g_on_ready_mid = nullptr;
jmethodID g_on_error_mid = nullptr;

// --- Async-signal-safe raw formatting helpers ---

void WriteAllRaw(int fd, const char* data, size_t len) {
    if (fd < 0 || data == nullptr || len == 0) return;
    size_t offset = 0;
    while (offset < len) {
        ssize_t n = write(fd, data + offset, len - offset);
        if (n > 0) {
            offset += static_cast<size_t>(n);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
}

void WriteStrRaw(int fd, const char* s) {
    if (s == nullptr) return;
    size_t len = 0;
    while (s[len] != '\0') {
        ++len;
    }
    WriteAllRaw(fd, s, len);
}

void WriteU64DecRaw(int fd, uint64_t value) {
    char buf[24];
    size_t idx = sizeof(buf);
    if (value == 0) {
        WriteAllRaw(fd, "0", 1);
        return;
    }
    while (value > 0 && idx > 0) {
        buf[--idx] = static_cast<char>('0' + (value % 10));
        value /= 10;
    }
    WriteAllRaw(fd, buf + idx, sizeof(buf) - idx);
}

void WriteI64DecRaw(int fd, int64_t value) {
    if (value < 0) {
        WriteAllRaw(fd, "-", 1);
        uint64_t mag = static_cast<uint64_t>(-(value + 1)) + 1ULL;
        WriteU64DecRaw(fd, mag);
    } else {
        WriteU64DecRaw(fd, static_cast<uint64_t>(value));
    }
}

void WriteHex64Raw(int fd, uint64_t value) {
    static constexpr char kHex[] = "0123456789abcdef";
    char buf[18];
    buf[0] = '0';
    buf[1] = 'x';
    for (int i = 0; i < 16; ++i) {
        int shift = (15 - i) * 4;
        buf[2 + i] = kHex[(value >> shift) & 0xFULL];
    }
    WriteAllRaw(fd, buf, sizeof(buf));
}

const char* SignalNameRaw(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGILL:  return "SIGILL";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

void WriteSymbolLineRaw(int fd, const char* label, uintptr_t addr) {
    WriteStrRaw(fd, label);
    WriteHex64Raw(fd, static_cast<uint64_t>(addr));
    Dl_info info{};
    if (addr != 0 && dladdr(reinterpret_cast<const void*>(addr), &info) != 0) {
        if (info.dli_fname != nullptr) {
            WriteStrRaw(fd, " ");
            WriteStrRaw(fd, info.dli_fname);
            if (info.dli_fbase != nullptr) {
                uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
                if (addr >= base) {
                    WriteStrRaw(fd, "+");
                    WriteHex64Raw(fd, static_cast<uint64_t>(addr - base));
                }
            }
        }
        if (info.dli_sname != nullptr) {
            WriteStrRaw(fd, " (");
            WriteStrRaw(fd, info.dli_sname);
            if (info.dli_saddr != nullptr) {
                uintptr_t saddr = reinterpret_cast<uintptr_t>(info.dli_saddr);
                if (addr >= saddr) {
                    WriteStrRaw(fd, "+");
                    WriteHex64Raw(fd, static_cast<uint64_t>(addr - saddr));
                }
            }
            WriteStrRaw(fd, ")");
        }
    }
    WriteStrRaw(fd, "\n");
}

struct CrashUnwindState {
    int fd;
    int depth;
    int max_depth;
};

_Unwind_Reason_Code CrashUnwindStep(struct _Unwind_Context* ctx, void* arg) {
    auto* st = static_cast<CrashUnwindState*>(arg);
    if (st->depth >= st->max_depth) {
        return _URC_END_OF_STACK;
    }
    uintptr_t ip = static_cast<uintptr_t>(_Unwind_GetIP(ctx));
    if (ip == 0) {
        return _URC_NO_REASON;
    }
    WriteStrRaw(st->fd, "  #");
    WriteI64DecRaw(st->fd, st->depth);
    WriteSymbolLineRaw(st->fd, " ", ip);
    st->depth++;
    return _URC_NO_REASON;
}

void SafeCopyCString(char* dst, size_t dst_size, const char* src) {
    if (dst == nullptr || dst_size == 0) return;
    if (src == nullptr) {
        dst[0] = '\0';
        return;
    }
    size_t i = 0;
    while (i + 1 < dst_size && src[i] != '\0') {
        dst[i] = src[i];
        ++i;
    }
    dst[i] = '\0';
}

void NativeCrashSignalHandler(int sig, siginfo_t* info, void* ucontext_ptr) {
    int saved_errno = errno;
    int expected = 0;
    if (!g_in_crash_handler.compare_exchange_strong(expected, 1)) {
        struct sigaction dfl{};
        sigemptyset(&dfl.sa_mask);
        dfl.sa_handler = SIG_DFL;
        sigaction(sig, &dfl, nullptr);
        raise(sig);
        return;
    }

    if (g_diag_ready.load(std::memory_order_acquire) && g_crash_log_path[0] != '\0') {
        int fd = open(g_crash_log_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd >= 0) {
            WriteStrRaw(fd, "=== NATIVE CRASH IN :engine ===\n");
            WriteStrRaw(fd, "signal=");
            WriteI64DecRaw(fd, sig);
            WriteStrRaw(fd, " (");
            WriteStrRaw(fd, SignalNameRaw(sig));
            WriteStrRaw(fd, ") si_code=");
            WriteI64DecRaw(fd, info != nullptr ? static_cast<int64_t>(info->si_code) : 0);
            WriteStrRaw(fd, " si_addr=");
            WriteHex64Raw(
                fd,
                info != nullptr ? static_cast<uint64_t>(reinterpret_cast<uintptr_t>(info->si_addr)) : 0ULL);
            WriteStrRaw(fd, " errno=");
            WriteI64DecRaw(fd, saved_errno);
            WriteStrRaw(fd, "\n");

            WriteStrRaw(fd, "pid=");
            WriteI64DecRaw(fd, static_cast<int64_t>(getpid()));
            WriteStrRaw(fd, " tid=");
            WriteI64DecRaw(fd, static_cast<int64_t>(syscall(SYS_gettid)));
            WriteStrRaw(fd, "\nlast_milestone=");
            WriteStrRaw(fd, g_last_milestone);
            WriteStrRaw(fd, "\n");

            uintptr_t pc = 0;
            uintptr_t lr = 0;
#if defined(__aarch64__)
            if (ucontext_ptr != nullptr) {
                const auto* uc = reinterpret_cast<const ucontext_t*>(ucontext_ptr);
                pc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
                lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
                WriteStrRaw(fd, "pc=");
                WriteHex64Raw(fd, static_cast<uint64_t>(pc));
                WriteStrRaw(fd, " lr=");
                WriteHex64Raw(fd, static_cast<uint64_t>(lr));
                WriteStrRaw(fd, " sp=");
                WriteHex64Raw(fd, static_cast<uint64_t>(uc->uc_mcontext.sp));
                WriteStrRaw(fd, " fp=");
                WriteHex64Raw(fd, static_cast<uint64_t>(uc->uc_mcontext.regs[29]));
                WriteStrRaw(fd, " pstate=");
                WriteHex64Raw(fd, static_cast<uint64_t>(uc->uc_mcontext.pstate));
                WriteStrRaw(fd, "\n");
                for (int r = 0; r < 8; ++r) {
                    WriteStrRaw(fd, r == 0 ? "x0=" : (r == 4 ? "\nx4=" : " "));
                    if (r != 0 && r != 4) {
                        WriteStrRaw(fd, "x");
                        WriteI64DecRaw(fd, r);
                        WriteStrRaw(fd, "=");
                    }
                    WriteHex64Raw(fd, static_cast<uint64_t>(uc->uc_mcontext.regs[r]));
                }
                WriteStrRaw(fd, "\n");
            }
#endif
            // Flush Phase 1 before attempting dladdr/unwind in case unwinding touches corrupt state.
            fsync(fd);

            if (pc != 0) {
                WriteSymbolLineRaw(fd, "pc_sym=", pc);
            }
            if (lr != 0) {
                WriteSymbolLineRaw(fd, "lr_sym=", lr);
            }
            WriteStrRaw(fd, "backtrace:\n");
            CrashUnwindState unwind_st{fd, 0, 32};
            _Unwind_Backtrace(&CrashUnwindStep, &unwind_st);
            fsync(fd);
            close(fd);
        }
    }

    __android_log_print(
        ANDROID_LOG_FATAL,
        kLogTag,
        "Fatal native signal %d (%s) at milestone='%s'",
        sig,
        SignalNameRaw(sig),
        g_last_milestone);

    struct sigaction prev{};
    bool found = false;
    for (size_t i = 0; i < kNumMonitoredSignals; ++i) {
        if (kMonitoredSignals[i] == sig) {
            prev = g_prev_sigactions[i];
            found = true;
            break;
        }
    }

    errno = saved_errno;
    if (found && (prev.sa_flags & SA_SIGINFO) && prev.sa_sigaction != nullptr) {
        sigaction(sig, &prev, nullptr);
        prev.sa_sigaction(sig, info, ucontext_ptr);
    } else if (found && prev.sa_handler != SIG_DFL && prev.sa_handler != SIG_IGN && prev.sa_handler != nullptr) {
        sigaction(sig, &prev, nullptr);
        prev.sa_handler(sig);
    } else {
        struct sigaction dfl{};
        sigemptyset(&dfl.sa_mask);
        dfl.sa_handler = SIG_DFL;
        sigaction(sig, &dfl, nullptr);
        sigset_t unblock;
        sigemptyset(&unblock);
        sigaddset(&unblock, sig);
        sigprocmask(SIG_UNBLOCK, &unblock, nullptr);
        if (sig == SIGABRT) {
            raise(sig);
        }
    }
}

void InstallNativeCrashHandlersOnce() {
    std::call_once(g_crash_handler_once, []() {
        for (size_t i = 0; i < kNumMonitoredSignals; ++i) {
            struct sigaction act{};
            sigemptyset(&act.sa_mask);
            act.sa_sigaction = &NativeCrashSignalHandler;
            act.sa_flags = SA_SIGINFO | SA_ONSTACK;
            sigaction(kMonitoredSignals[i], &act, &g_prev_sigactions[i]);
        }
    });
}

void WriteDiskLogLocked(const std::string& line, bool force_sync) {
    if (!g_diag_ready.load(std::memory_order_acquire) || g_native_log_path[0] == '\0') {
        return;
    }
    struct stat st{};
    if (stat(g_native_log_path, &st) == 0 && st.st_size > kMaxDiskLogBytes) {
        if (g_native_prev_log_path[0] != '\0') {
            rename(g_native_log_path, g_native_prev_log_path);
        } else {
            unlink(g_native_log_path);
        }
    }
    int fd = open(g_native_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd >= 0) {
        WriteAllRaw(fd, line.data(), line.size());
        WriteAllRaw(fd, "\n", 1);
        if (force_sync) {
            fsync(fd);
        }
        close(fd);
    }
}

void AppendLogLine(const std::string& line, bool force_sync = false) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, line.c_str());
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_lines.push_back(line);
    g_log_bytes += line.size() + 1;
    while (g_log_lines.size() > kMaxLogLines || g_log_bytes > kMaxLogBytes) {
        if (g_log_lines.empty()) break;
        g_log_bytes -= g_log_lines.front().size() + 1;
        g_log_lines.pop_front();
    }
    WriteDiskLogLocked(line, force_sync);
}

void WriteNativeMilestone(const char* milestone) {
    {
        std::lock_guard<std::mutex> diag_lock(g_diag_mutex);
        SafeCopyCString(g_last_milestone, sizeof(g_last_milestone), milestone);
        std::atomic_thread_fence(std::memory_order_release);
    }
    AppendLogLine(std::string("[NativeStep] ") + milestone, true);
}

class ScopedJniEnv {
public:
    ScopedJniEnv() = default;
    ~ScopedJniEnv() {
        if (attached_ && g_jvm != nullptr) {
            g_jvm->DetachCurrentThread();
        }
    }

    JNIEnv* get() {
        if (g_jvm == nullptr) return nullptr;
        JNIEnv* env = nullptr;
        jint rc = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_OK) {
            return env;
        }
        if (rc == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached_ = true;
                return env;
            }
        }
        return nullptr;
    }

    ScopedJniEnv(const ScopedJniEnv&) = delete;
    ScopedJniEnv& operator=(const ScopedJniEnv&) = delete;

private:
    bool attached_ = false;
};

// Converts standard UTF-8 (including 4-byte emoji sequences) into a UTF-16 jstring,
// avoiding ART CheckJNI Modified-UTF-8 aborts from NewStringUTF.
jstring Utf8ToJString(JNIEnv* env, const std::string& utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const unsigned char* p = reinterpret_cast<const unsigned char*>(utf8.data());
    const unsigned char* end = p + utf8.size();

    while (p < end) {
        uint32_t cp = 0xFFFD;
        unsigned char c = *p;
        if (c < 0x80) {
            cp = c;
            p += 1;
        } else if ((c & 0xE0) == 0xC0 && p + 1 < end && (p[1] & 0xC0) == 0x80) {
            cp = ((c & 0x1F) << 6) | (p[1] & 0x3F);
            if (cp < 0x80) cp = 0xFFFD;
            p += 2;
        } else if ((c & 0xF0) == 0xE0 && p + 2 < end && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80) {
            cp = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
            if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
            p += 3;
        } else if ((c & 0xF8) == 0xF0 && p + 3 < end && (p[1] & 0xC0) == 0x80 &&
                   (p[2] & 0xC0) == 0x80 && (p[3] & 0xC0) == 0x80) {
            cp = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
            if (cp < 0x10000 || cp > 0x10FFFF) cp = 0xFFFD;
            p += 4;
        } else {
            p += 1;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
        }
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string JStringToUtf8(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const jchar* utf16 = env->GetStringChars(jstr, nullptr);
    if (utf16 == nullptr) return "";
    const jsize len = env->GetStringLength(jstr);
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);

    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = utf16[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t low = utf16[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(jstr, utf16);
    return out;
}

void RedirectStdioToLogcatOnce() {
    std::call_once(g_stdio_redirect_once, []() {
        int pipe_fds[2] = {-1, -1};
        if (pipe2(pipe_fds, O_CLOEXEC) != 0) {
            AppendLogLine("Failed to create stdio pipe", true);
            return;
        }
        if (pipe_fds[0] <= STDERR_FILENO) {
            int moved = fcntl(pipe_fds[0], F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
            if (moved >= 0) {
                close(pipe_fds[0]);
                pipe_fds[0] = moved;
            }
        }
        if (pipe_fds[1] <= STDERR_FILENO) {
            int moved = fcntl(pipe_fds[1], F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
            if (moved >= 0) {
                close(pipe_fds[1]);
                pipe_fds[1] = moved;
            }
        }
        dup2(pipe_fds[1], STDOUT_FILENO);
        dup2(pipe_fds[1], STDERR_FILENO);
        if (pipe_fds[1] > STDERR_FILENO) {
            close(pipe_fds[1]);
        }
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IOLBF, 0);

        const int read_fd = pipe_fds[0];
        std::thread([read_fd]() {
            char buf[2048];
            std::string pending;
            while (true) {
                ssize_t n = read(read_fd, buf, sizeof(buf));
                if (n <= 0) {
                    if (n < 0 && errno == EINTR) continue;
                    break;
                }
                pending.append(buf, static_cast<size_t>(n));
                size_t pos = 0;
                while ((pos = pending.find('\n')) != std::string::npos) {
                    std::string line = pending.substr(0, pos);
                    if (!line.empty() && line.back() == '\r') {
                        line.pop_back();
                    }
                    AppendLogLine(line, false);
                    pending.erase(0, pos + 1);
                }
                if (pending.size() > 8192) {
                    AppendLogLine(pending, false);
                    pending.clear();
                }
            }
            if (!pending.empty()) {
                AppendLogLine(pending, false);
            }
            close(read_fd);
        }).detach();
    });
}

void EnsureDirExists(const std::string& path) {
    if (path.empty()) return;
    std::string curr;
    for (size_t i = 0; i < path.size(); ++i) {
        curr.push_back(path[i]);
        if (path[i] == '/' || i + 1 == path.size()) {
            if (curr.size() > 1) {
                mkdir(curr.c_str(), 0700);
            }
        }
    }
}

void* LookupNodeSymbol(const char* name) {
    void* sym = dlsym(RTLD_DEFAULT, name);
    if (sym != nullptr) return sym;
    void* handle = dlopen("libnode.so", RTLD_LAZY | RTLD_NOLOAD);
    if (handle != nullptr) {
        sym = dlsym(handle, name);
    }
    return sym;
}

void NotifyReadyCallback(int port, const std::string& url) {
    std::lock_guard<std::mutex> lock(g_callback_mutex);
    if (g_callback_global == nullptr || g_on_ready_mid == nullptr) return;
    ScopedJniEnv scoped;
    JNIEnv* env = scoped.get();
    if (env == nullptr) return;
    jstring jurl = Utf8ToJString(env, url);
    env->CallVoidMethod(g_callback_global, g_on_ready_mid, static_cast<jint>(port), jurl);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (jurl != nullptr) {
        env->DeleteLocalRef(jurl);
    }
}

void NotifyErrorCallback(const std::string& message) {
    AppendLogLine(std::string("[STBridge Error] ") + message, true);
    std::lock_guard<std::mutex> lock(g_callback_mutex);
    if (g_callback_global == nullptr || g_on_error_mid == nullptr) return;
    ScopedJniEnv scoped;
    JNIEnv* env = scoped.get();
    if (env == nullptr) return;
    jstring jmsg = Utf8ToJString(env, message);
    env->CallVoidMethod(g_callback_global, g_on_error_mid, jmsg);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (jmsg != nullptr) {
        env->DeleteLocalRef(jmsg);
    }
}

void JsNotifyReady(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    v8::Local<v8::Context> ctx = isolate->GetCurrentContext();

    int port = 0;
    std::string url;
    if (args.Length() >= 1 && args[0]->IsNumber()) {
        port = args[0]->Int32Value(ctx).FromMaybe(0);
    }
    if (args.Length() >= 2 && !args[1]->IsUndefined() && !args[1]->IsNull()) {
        v8::String::Utf8Value utf8(isolate, args[1]);
        if (*utf8 != nullptr) {
            url = *utf8;
        }
    }
    WriteNativeMilestone("N12: SillyTavern HTTP server reported ready");
    NotifyReadyCallback(port, url);
}

void JsNotifyError(const v8::FunctionCallbackInfo<v8::Value>& args) {
    v8::Isolate* isolate = args.GetIsolate();
    v8::HandleScope scope(isolate);
    std::string msg = "Unknown JavaScript error";
    if (args.Length() >= 1 && !args[0]->IsUndefined() && !args[0]->IsNull()) {
        v8::String::Utf8Value utf8(isolate, args[0]);
        if (*utf8 != nullptr) {
            msg = *utf8;
        }
    }
    NotifyErrorCallback(msg);
}

bool InitializeNodeProcessOnce() {
    bool ok = true;
    std::call_once(g_node_process_init_once, [&ok]() {
        // Ignore SIGPIPE for closed localhost sockets without resetting Android ART/libsigchain handlers.
        struct sigaction pipe_act{};
        sigemptyset(&pipe_act.sa_mask);
        pipe_act.sa_handler = SIG_IGN;
        sigaction(SIGPIPE, &pipe_act, nullptr);

        WriteNativeMilestone("N5: Calling node::InitializeOncePerProcess");
        std::vector<std::string> init_args = {
            "node",
            "--max-old-space-size=1536",
        };
        // Let Node initialize OpenSSL, NodePlatform, V8::InitializePlatform, V8::Initialize(),
        // absl deadlock detection, and cppgc::InitializeProcess() in upstream canonical order,
        // while preserving Android libsigchain signal handlers and our logcat stdio pipe.
        g_init_result = node::InitializeOncePerProcess(
            init_args,
            {
                node::ProcessInitializationFlags::kDisableNodeOptionsEnv,
                node::ProcessInitializationFlags::kNoDefaultSignalHandling,
                node::ProcessInitializationFlags::kNoStdioInitialization,
                node::ProcessInitializationFlags::kNoParseGlobalDebugVariables,
                node::ProcessInitializationFlags::kNoUseLargePages,
            });

        if (!g_init_result) {
            AppendLogLine("node::InitializeOncePerProcess returned null", true);
            ok = false;
            return;
        }
        for (const std::string& err : g_init_result->errors()) {
            AppendLogLine(std::string("Node init error: ") + err, true);
        }
        if (g_init_result->early_return() != 0) {
            AppendLogLine(
                "node::InitializeOncePerProcess early_return=" +
                    std::to_string(g_init_result->early_return()) +
                    " exit_code=" + std::to_string(g_init_result->exit_code()),
                true);
            ok = false;
            return;
        }

        g_platform = g_init_result->platform();
        if (g_platform == nullptr) {
            AppendLogLine("node::InitializeOncePerProcess returned null platform()", true);
            ok = false;
            return;
        }
        WriteNativeMilestone("N6: Node + V8 + cppgc initialized cleanly");
    });
    return ok && g_platform != nullptr && g_init_result && g_init_result->early_return() == 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    using AresInitJvmFn = void (*)(JavaVM*);
    auto init_jvm = reinterpret_cast<AresInitJvmFn>(LookupNodeSymbol("ares_library_init_jvm"));
    if (init_jvm != nullptr) {
        init_jvm(vm);
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sillytavern_app_engine_NativeNodeEngine_nativeSetDiagDir(
    JNIEnv* env, jobject /*thiz*/, jstring j_diag_dir) {
    std::string diag_dir = JStringToUtf8(env, j_diag_dir);
    if (diag_dir.empty()) return;
    EnsureDirExists(diag_dir);

    {
        std::lock_guard<std::mutex> lock(g_diag_mutex);
        std::string native_log = diag_dir + "/engine_native.log";
        std::string native_prev = diag_dir + "/engine_native.prev.log";
        std::string crash_log = diag_dir + "/last_native_crash.log";
        SafeCopyCString(g_native_log_path, sizeof(g_native_log_path), native_log.c_str());
        SafeCopyCString(g_native_prev_log_path, sizeof(g_native_prev_log_path), native_prev.c_str());
        SafeCopyCString(g_crash_log_path, sizeof(g_crash_log_path), crash_log.c_str());
        g_diag_ready.store(true, std::memory_order_release);
    }

    InstallNativeCrashHandlersOnce();
    WriteNativeMilestone("N0: Diagnostics initialized & signal handlers armed");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sillytavern_app_engine_NativeNodeEngine_nativeInitDns(
    JNIEnv* env, jobject /*thiz*/, jobject connectivity_manager) {
    WriteNativeMilestone("N1: nativeInitDns entered");
    if (connectivity_manager == nullptr) {
        AppendLogLine("nativeInitDns: null ConnectivityManager", true);
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_ares_mutex);
    if (g_connectivity_manager_global != nullptr) {
        env->DeleteGlobalRef(g_connectivity_manager_global);
        g_connectivity_manager_global = nullptr;
    }
    g_connectivity_manager_global = env->NewGlobalRef(connectivity_manager);

    using AresInitAndroidFn = int (*)(jobject);
    auto init_android = reinterpret_cast<AresInitAndroidFn>(LookupNodeSymbol("ares_library_init_android"));
    if (init_android == nullptr) {
        AppendLogLine("nativeInitDns: ares_library_init_android symbol not found", true);
        return JNI_FALSE;
    }
    int rc = init_android(g_connectivity_manager_global);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    AppendLogLine(std::string("nativeInitDns: ares_library_init_android rc=") + std::to_string(rc), true);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sillytavern_app_engine_NativeNodeEngine_nativeStartNode(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring j_app_dir,
    jstring j_data_root,
    jstring j_config_path,
    jstring j_cache_dir,
    jint port,
    jstring j_app_token,
    jobject callback) {
    bool expected = false;
    if (!g_node_running.compare_exchange_strong(expected, true)) {
        return -10;
    }
    g_stop_requested.store(false);

    // Dedicate a 64 KB alternate signal stack on STNodeMain thread so even deep stack
    // overflows can still flush crash diagnostics cleanly.
    alignas(16) static uint8_t s_node_alt_stack[64 * 1024];
    stack_t alt_ss{};
    stack_t old_ss{};
    bool alt_installed = false;
    alt_ss.ss_sp = s_node_alt_stack;
    alt_ss.ss_size = sizeof(s_node_alt_stack);
    alt_ss.ss_flags = 0;
    if (sigaltstack(&alt_ss, &old_ss) == 0) {
        alt_installed = true;
    }
    struct ScopedAltStackGuard {
        bool installed;
        stack_t old;
        ~ScopedAltStackGuard() {
            if (installed) {
                sigaltstack(&old, nullptr);
            }
        }
    } alt_guard{alt_installed, old_ss};

    WriteNativeMilestone("N2: nativeStartNode entered");
    RedirectStdioToLogcatOnce();
    WriteNativeMilestone("N3: RedirectStdioToLogcatOnce completed");

    const std::string app_dir = JStringToUtf8(env, j_app_dir);
    const std::string data_root = JStringToUtf8(env, j_data_root);
    const std::string config_path = JStringToUtf8(env, j_config_path);
    const std::string cache_dir = JStringToUtf8(env, j_cache_dir);
    const std::string app_token = JStringToUtf8(env, j_app_token);
    const std::string bootstrap_path = app_dir + "/android-bootstrap.mjs";
    const std::string tmp_dir = (cache_dir.empty() ? data_root : cache_dir) + "/tmp";
    const std::string xdg_cache = (cache_dir.empty() ? data_root : cache_dir) + "/xdg_cache";
    const std::string empty_openssl_cnf = tmp_dir + "/openssl.cnf";

    EnsureDirExists(data_root);
    EnsureDirExists(tmp_dir);
    EnsureDirExists(xdg_cache);

    // Guarantee OpenSSL config initialization never probes restricted system paths on SELinux devices.
    const char* cur_ssl_conf = getenv("OPENSSL_CONF");
    if (cur_ssl_conf == nullptr || cur_ssl_conf[0] == '\0') {
        int cfd = open(empty_openssl_cnf.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (cfd >= 0) {
            close(cfd);
            setenv("OPENSSL_CONF", empty_openssl_cnf.c_str(), 1);
        } else {
            setenv("OPENSSL_CONF", "/dev/null", 1);
        }
    }

    // Prepare Java callback global ref for lifetime of this run.
    {
        std::lock_guard<std::mutex> lock(g_callback_mutex);
        if (g_callback_global != nullptr) {
            env->DeleteGlobalRef(g_callback_global);
            g_callback_global = nullptr;
        }
        g_on_ready_mid = nullptr;
        g_on_error_mid = nullptr;
        if (callback != nullptr) {
            jclass cls = env->GetObjectClass(callback);
            if (cls != nullptr) {
                g_on_ready_mid = env->GetMethodID(cls, "onReady", "(ILjava/lang/String;)V");
                g_on_error_mid = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
                env->DeleteLocalRef(cls);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            g_callback_global = env->NewGlobalRef(callback);
        }
    }

    auto cleanup_callback = [&]() {
        std::lock_guard<std::mutex> lock(g_callback_mutex);
        if (g_callback_global != nullptr) {
            ScopedJniEnv scoped;
            JNIEnv* cur_env = scoped.get();
            if (cur_env != nullptr) {
                cur_env->DeleteGlobalRef(g_callback_global);
            }
            g_callback_global = nullptr;
        }
        g_on_ready_mid = nullptr;
        g_on_error_mid = nullptr;
        g_node_running.store(false);
    };

    WriteNativeMilestone("N4: Prepared app directories & OPENSSL_CONF");

    if (!InitializeNodeProcessOnce()) {
        NotifyErrorCallback("Failed to initialize embedded Node.js / V8 runtime");
        cleanup_callback();
        return -11;
    }

    setenv("NODE_ENV", "production", 1);
    setenv("HOME", data_root.c_str(), 1);
    setenv("TMPDIR", tmp_dir.c_str(), 1);
    setenv("TMP", tmp_dir.c_str(), 1);
    setenv("TEMP", tmp_dir.c_str(), 1);
    setenv("XDG_CACHE_HOME", xdg_cache.c_str(), 1);
    setenv("XDG_DATA_HOME", data_root.c_str(), 1);
    setenv("UV_THREADPOOL_SIZE", "4", 1);
    setenv("ST_ANDROID_APP_DIR", app_dir.c_str(), 1);
    setenv("ST_ANDROID_DATA_ROOT", data_root.c_str(), 1);
    setenv("ST_ANDROID_CONFIG_PATH", config_path.c_str(), 1);
    setenv("ST_ANDROID_PORT", std::to_string(port).c_str(), 1);
    setenv("ST_ANDROID_APP_TOKEN", app_token.c_str(), 1);
    setenv("ST_ANDROID_BOOTSTRAP_PATH", bootstrap_path.c_str(), 1);

    if (chdir(app_dir.c_str()) != 0) {
        NotifyErrorCallback("Failed to chdir into extracted SillyTavern directory");
        cleanup_callback();
        return -12;
    }

    WriteNativeMilestone("N7: Calling node::CommonEnvironmentSetup::Create");
    std::vector<std::string> args = {
        "node",
        bootstrap_path,
    };
    std::vector<std::string> errors;
    std::unique_ptr<node::CommonEnvironmentSetup> setup =
        node::CommonEnvironmentSetup::Create(
            g_platform,
            &errors,
            args,
            g_init_result->exec_args());

    if (!setup) {
        std::string combined = "Failed to create Node environment:";
        for (const std::string& e : errors) {
            combined += "\n" + e;
        }
        NotifyErrorCallback(combined);
        cleanup_callback();
        return -13;
    }
    WriteNativeMilestone("N8: CommonEnvironmentSetup::Create succeeded");

    v8::Isolate* isolate = setup->isolate();
    node::Environment* node_env = setup->env();
    int exit_code = 0;
    bool loaded_ok = true;
    static std::atomic<bool> s_has_js_exit_code{false};
    static std::atomic<int> s_js_exit_code{0};
    s_has_js_exit_code.store(false);
    s_js_exit_code.store(0);

    {
        v8::Locker locker(isolate);
        v8::Isolate::Scope isolate_scope(isolate);
        v8::HandleScope handle_scope(isolate);
        v8::Context::Scope context_scope(setup->context());

        node::SetProcessExitHandler(node_env, [](node::Environment* target_env, int code) {
            s_js_exit_code.store(code);
            s_has_js_exit_code.store(true);
            node::Stop(target_env);
        });

        const char* bootstrap_launcher =
            "const { pathToFileURL } = require('node:url');\n"
            "import(pathToFileURL(process.env.ST_ANDROID_BOOTSTRAP_PATH).href).catch((err) => {\n"
            "  console.error('[STBridge] Bootstrap import error:', err);\n"
            "  if (typeof globalThis.__ST_ANDROID_NOTIFY_ERROR__ === 'function') {\n"
            "    globalThis.__ST_ANDROID_NOTIFY_ERROR__(String((err && err.stack) || err));\n"
            "  }\n"
            "  process.exitCode = 1;\n"
            "});\n";

        auto preload = [](node::Environment* target_env,
                          v8::Local<v8::Value> /*process_val*/,
                          v8::Local<v8::Value> /*require_val*/) {
            v8::Local<v8::Context> ctx = node::GetMainContext(target_env);
            v8::Isolate* cur_iso = !ctx.IsEmpty() ? ctx->GetIsolate() : v8::Isolate::GetCurrent();
            if (cur_iso == nullptr) return;
            v8::HandleScope hs(cur_iso);
            if (ctx.IsEmpty()) {
                ctx = cur_iso->GetCurrentContext();
            }
            if (ctx.IsEmpty()) return;
            v8::Context::Scope cs(ctx);
            v8::Local<v8::Object> global = ctx->Global();

            v8::Local<v8::Function> ready_fn;
            if (v8::Function::New(ctx, JsNotifyReady).ToLocal(&ready_fn)) {
                v8::Local<v8::String> key;
                if (v8::String::NewFromUtf8(cur_iso, "__ST_ANDROID_NOTIFY_READY__").ToLocal(&key)) {
                    (void)global->Set(ctx, key, ready_fn).FromMaybe(false);
                }
            }

            v8::Local<v8::Function> err_fn;
            if (v8::Function::New(ctx, JsNotifyError).ToLocal(&err_fn)) {
                v8::Local<v8::String> key;
                if (v8::String::NewFromUtf8(cur_iso, "__ST_ANDROID_NOTIFY_ERROR__").ToLocal(&key)) {
                    (void)global->Set(ctx, key, err_fn).FromMaybe(false);
                }
            }
        };

        WriteNativeMilestone("N9: Calling node::LoadEnvironment");
        v8::MaybeLocal<v8::Value> load_ret =
            node::LoadEnvironment(node_env, bootstrap_launcher, preload);
        if (load_ret.IsEmpty()) {
            loaded_ok = false;
            WriteNativeMilestone("N10: node::LoadEnvironment failed (empty)");
            NotifyErrorCallback("JavaScript exception occurred while loading SillyTavern bootstrap");
        } else {
            WriteNativeMilestone("N10: node::LoadEnvironment succeeded; spinning event loop");
            {
                std::lock_guard<std::mutex> env_lock(g_env_mutex);
                g_active_env = node_env;
                if (g_stop_requested.load()) {
                    node::Stop(node_env);
                }
            }

            v8::Maybe<int> spin_ret = node::SpinEventLoop(node_env);
            if (spin_ret.IsJust()) {
                exit_code = spin_ret.FromJust();
            } else if (s_has_js_exit_code.load()) {
                exit_code = s_js_exit_code.load();
            } else {
                // SpinEventLoop returns Nothing when stopped externally via node::Stop().
                exit_code = 0;
            }
            WriteNativeMilestone("N11: node::SpinEventLoop returned");

            {
                std::lock_guard<std::mutex> env_lock(g_env_mutex);
                g_active_env = nullptr;
            }
        }
    }

    node::Stop(node_env);
    setup.reset();
    cleanup_callback();
    WriteNativeMilestone("N13: Native Node cleanup finished");
    return loaded_ok ? static_cast<jint>(exit_code) : -14;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sillytavern_app_engine_NativeNodeEngine_nativeStopNode(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_stop_requested.store(true);
    std::lock_guard<std::mutex> lock(g_env_mutex);
    if (g_active_env != nullptr) {
        node::Stop(g_active_env);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sillytavern_app_engine_NativeNodeEngine_nativeGetRecentLogs(JNIEnv* env, jobject /*thiz*/) {
    std::string combined;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (const std::string& line : g_log_lines) {
            combined.append(line);
            combined.push_back('\n');
        }
    }
    return Utf8ToJString(env, combined);
}
