# 自用 3.2.0 Debug 构建

开发分支：`custom-3.2.0`。基线为标签 `3.2.0`，提交
`e033a47d395fe57d16db2144069c3abdb7117547`。

## 当前云环境

复用初始化阶段安装的 JDK 21、Gradle 9.6.0、Android SDK 37.0/37.2、
Build Tools 37.0.0、NDK 28.2.13676358、CMake 3.22.1，以及前端依赖：

```bash
source /workspace/.setup/env.sh
# 初始化脚本设置了两个不同的 Android 用户目录；AGP 要求只保留一种设置。
unset ANDROID_SDK_HOME
cd /workspace/rikkahub
./gradlew assembleGithubDebug
```

保留 `ANDROID_USER_HOME` 和现有 Gradle 代理、CA 信任配置。
`web:buildWebUi` 会自动构建并复制 Web UI，无需手动跳过此任务。

初始化时 JitPack 的 HTTP 403 出现在域名白名单尚未应用到运行环境的阶段。
当前环境已放行 `jitpack.io` 和 `repo.itextsupport.com`；原失败坐标通过 curl、
Java HTTPS 客户端及 Gradle 均可解析。未更换仓库或依赖版本，也未关闭 TLS 验证。

## Debug 的 Firebase 行为

- Debug 跳过 Google Services 配置任务，不需要 `app/google-services.json`。
- Debug 不自动初始化 Firebase，不注册 Firebase 统计和 Crashlytics 单例；聊天统计调用为空操作。
- Debug 清单停用统计和崩溃收集，构建配置禁止 mapping 与 native symbol 上传。
- Firebase SDK 保留在依赖中，Release 配置和依赖解析逻辑保留；Release 仍需要真实 Firebase 配置及签名。
- OAuth 没有修改。

## APK

输出目录：`app/build/outputs/apk/github/debug/`。

- `app-github-arm64-v8a-debug.apk`：64 位 ARM 手机。
- `app-github-x86_64-debug.apk`：x86_64 设备或模拟器。
- `app-github-universal-debug.apk`：包含上述两种架构。

包名沿用基线的 `me.rerere.rikkahub.debug`，与正式版 `me.rerere.rikkahub` 不同，
应用数据和 Provider authority 也按包名隔离。使用 Android Debug 签名，最低 Android 8.0（API 26）。
后续覆盖安装同一个测试包需要保留当前环境的 Debug keystore；不要把私钥提交到仓库。

可使用 SDK 的 `apksigner verify --verbose --print-certs` 和 `aapt2 dump badging`
检查生成 APK 的签名、包名、版本和 ABI。安装及运行验证需要连接兼容的 Android 设备。
