# engine-ndk

Self-written native Android (`arm64-v8a`, Android 10+ / API 29+) embedding engine for running SillyTavern's Node.js backend inside an isolated `:engine` Android process without spawning shell binaries.

## Components

1. **`scripts/build-libnode.sh`**
   - Cross-compiles upstream Node.js LTS (`v22.23.5`) with Android NDK r27 (`API 29`, `aarch64`) as a single shared library (`libnode.so`, unversioned `SONAME`).
   - Enforces matching `libc++_shared.so` linkage (`-nostdlib++ -lc++_shared`) and 16 KB ELF `PT_LOAD` page alignment (`-Wl,-z,max-page-size=16384`).
   - Runs a fast C++ preflight probe before building V8/Node and validates ELF headers afterward with `llvm-readelf`.
2. **`jni-bridge/st_bridge.cpp` & `CMakeLists.txt`**
   - Builds `libst_bridge.so` linking `libnode.so` using the exact same NDK STL (`c++_shared`).
   - Wires Android `ConnectivityManager` into c-ares DNS (`ares_library_init_jvm` / `ares_library_init_android`) when exported, redirects `stdout`/`stderr` into Logcat + a bounded ring buffer, and invokes `android-bootstrap.mjs` inside an embedded Node `Environment`.
