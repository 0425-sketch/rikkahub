# 个人版 Android 发布与签名恢复

## 安装身份

Debug 209 是最后一个面向用户的 Debug 版本。第一次正式版的标题为「RikkaHub Release 209（Release 1）」，功能基线是 `590dca0ef8ca2ea3b6d9bd45d345717e5294b013`。Android 安装版本从 `209 / 3.3.0-personal.9` 增至 `210 / 3.3.0-personal.10`。新旧 APK 的包名均须为 `me.rerere.rikkahub.personal`，签名证书 SHA-256 均须为 `882c1cc87ccf36c2ac5eb178b8d8ef13ff74a3ac8b759b41aed6e48bddb4e1fd`。Android 据此原地升级，同一用户下沿用原 UID、数据目录和 Android Keystore。FileProvider 仍为 `me.rerere.rikkahub.personal.fileprovider`，SIWC 本地安装标识仍在 `noBackupFilesDir/chatgpt-host-id`。

固定签名、个人版本字段和应用名由 `githubDebug` 交接给 `githubRelease`。开发编译可用 `:app:assembleGithubDebug`，生成独立包名。Debug 209 及其附件保留在历史发布页。[Debug 209](https://github.com/0425-sketch/rikkahub/releases/tag/custom-3.3.0-personal-590dca0ef8ca-45763808599-1)

## 制作并发布正式 APK

1. 在默认分支 `custom-3.2.0` 的发布提交更新 `gradle.properties` 中的 `personalVersionCode` 与 `personalVersionName`。先检查已发布同包版本。两者继续递增，版本名沿用 `X.Y.Z-personal.N`。
2. 在 Actions 打开 **Personal Release · stage or publish a tested ARM64 APK**，选择默认分支及 `stage`。两台干净的 GitHub 托管运行器分别下载并核验原始 209，恢复固定签名，构建优化后的 GitHub Release。候选、备用恢复校验用 APK 和高一版的升级测试包暂存在仅仓库协作者可见的 `qa-personal-<run>-<attempt>` 草稿。该草稿由工作流管理。
3. 在独立测试设备上用原始已发布版本建立虚构聊天、图片、记忆、设置和用量。在受控测试源按草稿中的正式元数据测试发现与下载，对照主候选的 SHA-256，再覆盖安装并检查保留的数据。用高一版的非公开候选从正式包再走一次发现、下载、系统安装和数据复核。记录包名、签名、版本、数据验证、测试源和实际用到的服务。
4. 验收通过后，在同一构建提交的默认分支再次运行该工作流，选择 `publish`，填写原始 `stage_run`、`stage_attempt` 和已在设备上验收的主 APK 的 `tested_sha256`。工作流复用该 APK，验证两次独立恢复，上传 APK、校验和、恢复证明及构建证明，正式发布为 Latest 并回读。随后使用原始旧版复核线上发现、下载与系统覆盖安装。

已安装 209 的用户打开聊天侧栏，点新版本卡片，查看说明后点 ARM64 APK。下载完成后点系统下载通知，或到「下载」打开该 APK，在系统界面选择「更新」。Android 首次提示时可为打开 APK 的来源允许安装。保留原安装和数据即可。新用户从最新正式发布页选择 ARM64 APK。[稳定版下载页](https://github.com/0425-sketch/rikkahub/releases/latest)

## 恢复固定签名

公开指纹保存在 `docs/chatgpt-personal-signing.json`。主副副本分别位于 **同一个** GitHub 仓库 `0425-sketch/rikkahub` 的 Actions Secrets，名称为 `RIKKAHUB_PERSONAL_SIGNING` 和 `RIKKAHUB_PERSONAL_SIGNING_BACKUP`。两个独立的构建作业分别只接收自己的这份 Secret。每个作业使用以下恢复流程，其中 `SOURCE` 为 `primary` 或 `backup`。

```bash
python3 tools/chatgpt-signing.py restore --source "$SOURCE" \
  --identity docs/chatgpt-personal-signing.json --output "$RUNNER_TEMP/personal-signing"
```

恢复脚本打开 PKCS12，核对公开证书，并用密钥签名和验证本地挑战。GitHub Actions 向后续构建步骤注入原有 `CHATGPT_TEST_*` 签名配置，Gradle 的个人签名配置将它用于 `githubRelease`。工作流只保留公开恢复回执，清理临时签名文件，以 `apksigner verify --verbose --print-certs` 对两份实际 APK 验签。恢复失败时保留可验证的原副本并排查 Secret 或运行器配置，禁止生成替代密钥覆盖安装身份。

仓库中尚无维护者指定的外部安全保管目标或可用来加密恢复包的接收者公钥，现有的两个 Secrets 均依赖该仓库的访问权限。建立仓库外恢复副本时需另行确定维护者控制的目标或经核对的接收公钥，避免在聊天、Git、日志、附件或 CI 缓存中传递私钥与密码。
