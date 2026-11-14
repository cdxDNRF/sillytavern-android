#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <vector>

#include "node.h"
#include "v8.h"

namespace {

constexpr const char* kLogTag = "SillyTavernNode";
constexpr size_t kMaxLogLines = 400;
constexpr size_t kMaxLogBytes = 128 * 1024;

JavaVM* g_jvm = nullptr;

std::mutex g_log_mutex;
std::deque<std::string> g_log_lines;
size_t g_log_bytes = 0;
std::once_flag g_stdio_redirect_once;

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

void AppendLogLine(const std::string& line) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, line.c_str());
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_lines.push_back(line);
    g_log_bytes += line.size() + 1;
    while (g_log_lines.size() > kMaxLogLines || g_log_bytes > kMaxLogBytes) {
        if (g_log_lines.empty()) break;
        g_log_bytes -= g_log_lines.front().size() + 1;
        g_log_lines.pop_front();
    }
}

void RedirectStdioToLogcatOnce() {
    std::call_once(g_stdio_redirect_once, []() {
        int pipe_fds[2] = {-1, -1};
        if (pipe2(pipe_fds, O_CLOEXEC) != 0) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Failed to create stdio pipe");
            return;
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
                    AppendLogLine(line);
                    pending.erase(0, pos + 1);
                }
                if (pending.size() > 8192) {
                    AppendLogLine(pending);
                    pending.clear();
                }
            }
            if (!pending.empty()) {
                AppendLogLine(pending);
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
    AppendLogLine(std::string("[STBridge Error] ") + message);
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
        // Ignore SIGPIPE for closed localhost sockets without resetting Android ART
        // signal handlers (SIGSEGV/SIGQUIT/SIGUSR1) installed by libsigchain.
        struct sigaction pipe_act {};
        sigemptyset(&pipe_act.sa_mask);
        pipe_act.sa_handler = SIG_IGN;
        sigaction(SIGPIPE, &pipe_act, nullptr);

        std::vector<std::string> init_args = {
            "node",
            "--max-old-space-size=1536",
        };
        g_init_result = node::InitializeOncePerProcess(
            init_args,
            {
                node::ProcessInitializationFlags::kNoInitializeV8,
                node::ProcessInitializationFlags::kNoInitializeNodeV8Platform,
                node::ProcessInitializationFlags::kDisableNodeOptionsEnv,
                node::ProcessInitializationFlags::kNoDefaultSignalHandling,
                node::ProcessInitializationFlags::kNoStdioInitialization,
            });

        if (!g_init_result) {
            AppendLogLine("node::InitializeOncePerProcess returned null");
            ok = false;
            return;
        }
        for (const std::string& err : g_init_result->errors()) {
            AppendLogLine(std::string("Node init error: ") + err);
        }
        if (g_init_result->early_return() != 0) {
            ok = false;
            return;
        }

        static std::unique_ptr<node::MultiIsolatePlatform> s_platform_holder =
            node::MultiIsolatePlatform::Create(4);
        g_platform = s_platform_holder.get();
        v8::V8::InitializePlatform(g_platform);
        v8::V8::Initialize();
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sillytavern_app_engine_NativeNodeEngine_nativeInitDns(
    JNIEnv* env, jobject /*thiz*/, jobject connectivity_manager) {
    if (connectivity_manager == nullptr) {
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
        return JNI_FALSE;
    }
    int rc = init_android(g_connectivity_manager_global);
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

    RedirectStdioToLogcatOnce();

    const std::string app_dir = JStringToUtf8(env, j_app_dir);
    const std::string data_root = JStringToUtf8(env, j_data_root);
    const std::string config_path = JStringToUtf8(env, j_config_path);
    const std::string cache_dir = JStringToUtf8(env, j_cache_dir);
    const std::string app_token = JStringToUtf8(env, j_app_token);
    const std::string bootstrap_path = app_dir + "/android-bootstrap.mjs";
    const std::string tmp_dir = (cache_dir.empty() ? data_root : cache_dir) + "/tmp";
    const std::string xdg_cache = (cache_dir.empty() ? data_root : cache_dir) + "/xdg_cache";

    EnsureDirExists(data_root);
    EnsureDirExists(tmp_dir);
    EnsureDirExists(xdg_cache);

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

        auto preload = [](node::Environment* /*target_env*/,
                          v8::Local<v8::Value> /*process_val*/,
                          v8::Local<v8::Value> /*require_val*/) {
            v8::Isolate* cur_iso = v8::Isolate::GetCurrent();
            v8::HandleScope hs(cur_iso);
            v8::Local<v8::Context> ctx = cur_iso->GetCurrentContext();
            v8::Local<v8::Object> global = ctx->Global();

            auto ready_fn = v8::Function::New(ctx, JsNotifyReady);
            if (!ready_fn.IsEmpty()) {
                auto key = v8::String::NewFromUtf8(cur_iso, "__ST_ANDROID_NOTIFY_READY__").ToLocalChecked();
                global->Set(ctx, key, ready_fn.ToLocalChecked()).Check();
            }

            auto err_fn = v8::Function::New(ctx, JsNotifyError);
            if (!err_fn.IsEmpty()) {
                auto key = v8::String::NewFromUtf8(cur_iso, "__ST_ANDROID_NOTIFY_ERROR__").ToLocalChecked();
                global->Set(ctx, key, err_fn.ToLocalChecked()).Check();
            }
        };

        v8::MaybeLocal<v8::Value> load_ret =
            node::LoadEnvironment(node_env, bootstrap_launcher, preload);
        if (load_ret.IsEmpty()) {
            loaded_ok = false;
            NotifyErrorCallback("JavaScript exception occurred while loading SillyTavern bootstrap");
        } else {
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

            {
                std::lock_guard<std::mutex> env_lock(g_env_mutex);
                g_active_env = nullptr;
            }
        }
    }

    node::Stop(node_env);
    setup.reset();
    cleanup_callback();
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
