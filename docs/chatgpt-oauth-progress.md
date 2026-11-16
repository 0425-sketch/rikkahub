# ChatGPT 官方 OAuth 迁移与 APK 交付进度

## 目标、范围与验收

- 仓库：`0425-sketch/rikkahub`，仅开发分支 `custom-3.2.0`；原始基线 `3.2.0` / `e033a47d395fe57d16db2144069c3abdb7117547`。
- 将 ChatGPT Provider 从旧 Codex 接口迁移至官方 Sign in with ChatGPT 开源本地应用流程：动态注册、PKCE/state/nonce、JWKS 验签与身份检查、实际授予 scope、持久化及刷新/撤销、公共模型与 Responses API、流式终态及客户端 function tools 多轮调用。
- 旧账号明确要求重新授权；保留聊天、助手、设置和其他 Provider 的行为及隔离。无套餐授权时禁止推理，不切换其他计费渠道。
- 复用已成功的 GithubDebug 与依赖；保留 Firebase Debug 禁用和与正式版并存。仅在已验证签名及 versionCode 后声称覆盖升级；旧签名不可取得时采用独立包名。
- 编写并运行协议与回归测试；通过 GitHub Actions 的 GITHUB_TOKEN 构建本次确切提交、发布独立测试标签的 Pre-release、上传 ARM64 APK 与 SHA-256；实际下载后核对大小、摘要、签名、包名、版本、ABI，才交付下载链接。
- 真实 ChatGPT 登录/授权、套餐调用和真机反馈由用户完成；模拟测试不能表述为真实套餐已验证。

## 约束

- 不修改 master、不回退、不强推、不改写历史、不向上游提 PR、不升级整个应用、不重构无关 Provider。
- 不使用已撤销的 `RIKKAHUB_RELEASE_TOKEN` / `RIKKAHUB_RELEASE_PAT_DIRECT`；不重试已失败的 Codex 代理上传路径；不提交 APK 或签名私钥。
- 不记录任何密钥、令牌、Cookie、keystore、密码或其 Base64；长日志与官方文档缓存保存在 `/workspace/work/`。
- 本文件为唯一主进度记录。阶段完成、方案改变或关键阻碍立即更新；合理代码阶段及时提交并推送。恢复上下文时先读本文件，再核对 Git 与 Actions/Release 实际状态。

## 当前状态（2026-11-17，Asia/Singapore）

- 已获取远端 `custom-3.2.0`，从干净的 `work` 分支切换到同名开发分支。
- 当前已提交 HEAD：`ac089a8f`（既有交付记录）。本进度文件尚未提交；功能尚未修改、未测试。
- 已读取完整用户需求、根 `AGENTS.md`、`docs/custom-3.2.0-build.md`、`docs/custom-3.2.0-release.md`。
- 基线旧 APK：`custom-3.2.0-test`；Actions `43867882083`；不是本次交付。
- 子任务：协议核实代理读取六份官方文档及源码；交付代理核对原签名和构建环境并准备新版 workflow。

## 已确认问题与不重复路径

- Firecrawl `firecrawl_scrape` 调用返回 `Unknown tool`，当前不可用；改用直接 HTTPS 读取官方文档，不重复该插件调用。
- 此 checkout 的 fetch 配置未创建 `origin/custom-3.2.0` 远端跟踪 ref；已通过成功 fetch 的 `FETCH_HEAD` 创建正确本地分支，后续显式指定分支/ref。
- 历史 `upload-existing-apk.yml` 固定旧源码/产物，不能充当新版构建或交付。

## 下一步

1. 核实官方协议并审阅现有 OAuth、Provider、模型和流式实现，分配互不冲突的代码工作。
2. 提交并推送本记录；分阶段实现和验证，及时回填具体提交、测试结论、Actions/Release/产物。
3. 完成本次 ARM64 APK 的自动发布与下载校验，保留真实账号未测项目。
