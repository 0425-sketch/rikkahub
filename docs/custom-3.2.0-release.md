# RikkaHub Release 附件自动交付

`upload-release-assets.py` 只交付已有 APK 与其 `.sha256` 文件，不运行 Gradle、不改 OAuth、不提交 APK、不创建或发布 Release，也不覆盖已有附件。目标 Release 必须已经发布。

## 已验证的 GitHub Actions 交付

2026-11-16 已通过 `.github/workflows/upload-existing-apk.yml` 完成 APK 上传，
并从公开 Release 下载后核对大小与 SHA-256。运行记录：
https://github.com/0425-sketch/rikkahub/actions/runs/43867882083

此流程在 GitHub 托管 runner 上运行，使用自动提供的、仅限本仓库的 `GITHUB_TOKEN`
和 `contents: write` 权限，不使用 Codex 云环境中的 PAT 或 HTTP 密钥注入。
它复用已审阅提交 `4bd01157b0c8d32a9a1aac4e4266208c2a472a4a` 中的上传脚本与现有 APK，
只补充缺失的附件，不覆盖同名文件；上传前后均检查内容。

当前工作流固定交付本次基线测试包，**不会自动构建后续版本**。
后续新版的构建与产物交付应接入 GitHub Actions；本次未配置新版自动构建，也未改动签名。

APK：
https://github.com/0425-sketch/rikkahub/releases/download/custom-3.2.0-test/app-github-arm64-v8a-debug.apk

下面保留原有云端上传配置供排查。该路径曾在 APK 上传时被 Codex
`codex-secret-uploads.github.com` 凭据集成层拒绝，不应再为本测试包重复调整令牌或白名单。

## 一次性凭据设置

在当前云环境的**安全凭据**设置中保存令牌，不要粘贴进聊天、脚本、仓库或命令行参数。

- Key：`RIKKAHUB_RELEASE_TOKEN`。
- 适用域名：`api.github.com`、`uploads.github.com`；两项都需要。
- GitHub 建议使用 fine-grained personal access token：Resource owner 选 `0425-sketch`，Repository access 仅选 `rikkahub`，Repository permissions 中仅启用 **Contents: Read and write**；Metadata 只读为 GitHub 必需项。
- 无需授予 Administration、Actions、Workflows、OAuth 或其他仓库权限。按使用周期设置到期日。
- 网络允许列表也必须包含上述两个域名。公开下载使用 `github.com` 与 GitHub 附件跳转域名（通常为 `release-assets.githubusercontent.com`）；下载不发送令牌。

凭据设置的标签、是否保存即作用于当前实例、以及域名作用范围应以实际设置界面为准。脚本要求运行实例能读取 `RIKKAHUB_RELEASE_TOKEN` 的安全变量或代理占位符，并要求该凭据对两个域名的 HTTPS 请求都生效。不要只验证 GitHub API 请求成功就认定上传域名凭据已就绪。

脚本显式选择 `RIKKAHUB_RELEASE_TOKEN` 作为 gh 子进程的 `GH_TOKEN`，不会静默使用预置 `GH_TOKEN`。不会打印令牌、授权头或下载重定向中的签名查询参数。保留继承的代理与 CA 信任设置；禁止在运行脚本时使用 shell `set -x` 或 `GH_DEBUG`。

## 当前测试包

在仓库根目录运行（也可使用 APK 绝对路径）：

```sh
python3 tools/upload-release-assets.py \
  --repo 0425-sketch/rikkahub \
  --tag custom-3.2.0-test \
  --release-id 446959646 \
  --apk artifacts/3.2.0/app-github-arm64-v8a-debug.apk
```

默认校验文件为 APK 路径加 `.sha256`，可用 `--checksum` 指定。当前 APK 的预期 SHA-256 为：

```text
6f93a66b393c050b61ed6001c0dbf3c259997ce786418a4f4e7137cf7b2ceb4d
```

只做本地校验，无需凭据、不会联网：

```sh
python3 tools/upload-release-assets.py \
  --tag custom-3.2.0-test \
  --release-id 446959646 \
  --apk artifacts/3.2.0/app-github-arm64-v8a-debug.apk \
  --check-local
```

以后构建完成后更新 `--tag`、`--release-id`、`--apk`，并提供该 APK 的单行 `sha256sum` 校验文件，即可复用。需要 Python 3 和现有 GitHub CLI `gh`，脚本不会安装依赖。

## 续传与验证

1. 本地核对 APK 的 SHA-256 与校验文件。
2. 根据仓库及 Release ID 读取 Release，检查 tag 相符且已发布。
3. 所有同名现有附件先完整下载校验，一致则跳过；大小、摘要或内容不一致就停止，不覆盖或删除。
4. 先上传缺失的校验文件，再上传 APK，上传后从公开 `browser_download_url` 完整流式下载校验。
5. 成功时输出两个已验证的公开下载 URL。可以重复执行同一命令来恢复部分交付。

脚本不自动重试失败上传。`401` 表示上传认证被拒绝；`403` 需结合服务响应及代理策略区分；代理 CONNECT 失败属于网络层；`413`／payload limit 属于上传容量限制。发生超时或中断时可能已上传部分内容，先检查 Release 附件，再执行相同命令；脚本会核对并跳过内容完全一致的附件。

官方参考：[GitHub Release 附件 API](https://docs.github.com/en/rest/releases/assets)、[Fine-grained PAT 配置](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)、[仓库权限](https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens#repository-permissions-for-contents)。
