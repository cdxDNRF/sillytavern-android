# SillyTavern Android (自研原生嵌入式架构)

将 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 完整封装为可独立安装运行的原生 Android APK，**零电脑本地算力/硬盘占用**，全流程在 GitHub Actions 云端自动完成打包、Node.js NDK 交叉编译与 APK 签名构建。

## 核心架构特性

- **100% 自研原生嵌入架构**（无第三方包装壳 / 无 Termux / 无命令行二进制拉起）：
  - **`:engine` 独立后台服务进程**：通过自研 C++17 JNI 桥接库（[`st_bridge.cpp`](engine-ndk/jni-bridge/st_bridge.cpp)）直接将交叉编译的 `libnode.so`（Node.js `v22.23.5` + `full-icu`）嵌入 `arm64-v8a`（API 29+，16 KB ELF 页对齐）进程内，无需 `fork/exec` 外部可执行文件，完全符合现代 Android 10–15 W^X / SELinux 约束。
  - **主进程纯原生 Jetpack Compose + WebView 容器**：仅在应用内加载 `http://127.0.0.1:<ephemeral_port>`，并由每个会话随机生成的 `X-App-Token` → `HttpOnly` Cookie 保护，外部应用或系统浏览器无法访问本地后端；外部超链接需经弹窗确认后才交由系统浏览器处理。
- **完全私有沙盒与卸载零残留**：
  - 不申请任何外部存储权限（`READ/WRITE/MANAGE_EXTERNAL_STORAGE`），所有运行代码（`filesDir/st_app`）、用户数据（`filesDir/st_data`）、配置（`filesDir/st_config`）、缓存及捕获的导出文件（`filesDir/exports`）均严格保存在系统应用私有目录下。
  - 卸载应用即由 Android 系统原子清空全部文件，手机中不留下任何零散目录。
- **应用内导入 / 导出工作流**：
  - **导入**：SillyTavern 界面内点击上传角色卡、背景、聊天、世界书、预设时，通过原生 SAF 文档选择器直传入内核写入私有沙盒 `st_data/`；安装第三方前端扩展由内置纯 JS `isomorphic-git` 垫片（[`st-packager/overlay/shims/simple-git`](st-packager/overlay/shims/simple-git/index.js)）直接通过 HTTPS 拉取/更新，不依赖外部 `git` 命令。
  - **导出**：内置 JS Bridge（`STAndroidBridge`）与回退 HTTP `DownloadListener` 统一拦截 `blob:`、`data:` 及本地文件下载流，先存入应用私有**导出箱**，再通过底部悬浮工具球按需“保存到手机目录（SAF）”“分享”或“删除”。

---

## 目录结构

| 目录 | 职责说明 |
| :--- | :--- |
| [`st-packager/`](st-packager/package.mjs) | SillyTavern 上游打包器：拉取指定版本、预编译前端 `dist/lib.js`、依赖树裁剪瘦身、应用私有沙盒补丁与端到端自动化校验 |
| [`engine-ndk/`](engine-ndk/README.md) | 嵌入式 Node.js NDK 交叉编译脚本（`libnode.so`）与 C++17 JNI 桥接层（`libst_bridge.so`，含 c-ares Android DNS 绑定与日志环形缓冲） |
| [`android-app/`](android-app/app/build.gradle.kts) | Android 应用工程（Kotlin / Compose / Edge-to-Edge / AIDL `:engine` 跨进程保活服务 / 私有导出箱 / 返回键与软键盘适配） |
| [`.github/workflows/`](.github/workflows/build-apk.yml) | 云端 CI 流水线：自动构建并缓存 `libnode.so`、打包校验 SillyTavern，并产出可安装的 `arm64-v8a` APK |

---

## 云端一键构建与更新 SillyTavern 版本

无需本地安装 Android Studio 或 NDK：

1. 在 GitHub 仓库打开 **Actions** → **Build SillyTavern Android APK** → **Run workflow**：
   - `sillytavern_ref`：填写需要打包的 SillyTavern 分支或 Tag（默认 `release`，也可填 `staging` 或具体版本号如 `1.19.0`）。
   - `node_version`：默认 `v22.23.5`。首次构建会自动交叉编译 `libnode.so` 并缓存为仓库预编译 Release（`engine-node-v22.23.5`），后续同步 SillyTavern 新版本时将直接秒级复用该引擎，整套 APK 打包通常仅需数分钟。
   - `create_release`：勾选后将在构建完成时自动发布 GitHub Release，手机端可直接下载 `.apk` 安装包。
2. 构建完成后可在 **Releases** 页面或 Workflow Run 的 **Artifacts** 下载 `SillyTavern-v<version>-arm64-release.apk`。

---

## 配置固定签名证书（推荐，用于保留数据覆盖升级）

若未配置仓库密钥，CI 会自动使用构建机的 debug keystore 签名，APK 依然可直接安装使用；但为了确保日后 SillyTavern 出新版本时**直接覆盖安装并完整保留私有目录内的聊天记录与角色卡**，建议配置固定签名密钥：

1. 在云端或本地临时环境生成一次 `.jks` 签名证书并转为 Base64：

   ```bash
   keytool -genkeypair -v -keystore sillytavern-release.jks \
     -alias sillytavern -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 sillytavern-release.jks
   ```

2. 在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加以下 4 个 Secrets（随后销毁临时 `.jks` 文件即可）：
   - `ANDROID_KEYSTORE_BASE64`：上一步生成的 Base64 字符串
   - `ANDROID_KEYSTORE_PASSWORD`：Keystore 密码
   - `ANDROID_KEY_ALIAS`：`sillytavern`
   - `ANDROID_KEY_PASSWORD`：Key 密码
