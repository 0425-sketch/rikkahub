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

## 当前状态（2026-11-16，Asia/Singapore）

- 已获取远端 `custom-3.2.0`，从干净的 `work` 分支切换到同名开发分支。
- 初始记录：`c648d112`；构建配置：`bb71920d`；公共API/工具：`af26646b`；完整应用授权/UI/安全保护/工作流：`1d6332e6191f9693fb3bcf064027a6a332fd01d2`，均已提交并推送。当前仅后续进度记录可能未提交；完整源码已持久保存。
- 已读取完整用户需求、根 `AGENTS.md`、`docs/custom-3.2.0-build.md`、`docs/custom-3.2.0-release.md`。
- 基线旧 APK：`custom-3.2.0-test`；Actions `43867882083`；不是本次交付。
- 子任务：协议核实代理读取六份官方文档及源码；交付代理核对原签名和构建环境并准备新版 workflow。

## 阶段更新：官方协议与交付路线已确认

- 六份官方文档直接 HTTPS 返回 200，正文及相关规范存于 `/workspace/work/siwc-docs/`。授权端点 `/api/accounts/authorize`，交换/刷新端点 `/api/accounts/oauth/token`，资源 `https://api.openai.com/v1`；正式 client ID 来自首次回调，套餐权限仅依据 token 响应 `chatgpt.tokens.use.direct`。
- 公共 `/v1/models` 返回 `models[]`；`/v1/responses` 必须 `store:false`、`stream:true`、完整历史，只有 `response.completed` 才判成功；本地 function tools 使用 namespace。OIDC discovery 确认官方 issuer、JWKS 和 revoke 端点。
- 授权代理负责凭据/安装host/刷新/退出竞态与测试；独立验证代理负责维护库 JWT/JWKS 校验；推理代理负责模型、Responses、工具与回归；主代理负责账号/套餐状态 UI、整合和验收。
- 已复用现有 SDK/JDK/NDK/CMake。原 Debug keystore 在已检查的工作区和用户 Android 目录未找到。旧 APK：包名 `me.rerere.rikkahub.debug`，versionCode `196`，证书 SHA-256 `a630b83127a5ddb0b976e48668906de0d70d0172d7a83c27d09790204a63c8d5`。
- 新版采用独立包名 `me.rerere.rikkahub.chatgpt.debug`、versionCode `197`，不要求卸载旧版本。仓库 Actions secrets 查询实际返回 403（integration 无权限），当前无可用安全签名持久化工具；先完成可安装独立包，后续同签名升级为已知未完成项。keystore 不进入仓库、日志或任何普通 artifact。
- 新工作流由 Actions GITHUB_TOKEN 构建当前提交并创建独立 Pre-release，支持可选安全注入固定签名；现阶段无新版 Actions/Release/APK，不能交付旧包。

## 已确认问题与不重复路径

- Firecrawl `firecrawl_scrape` 调用返回 `Unknown tool`，当前不可用；改用直接 HTTPS 读取官方文档，不重复该插件调用。
- 此 checkout 的 fetch 配置未创建 `origin/custom-3.2.0` 远端跟踪 ref；已通过成功 fetch 的 `FETCH_HEAD` 创建正确本地分支，后续显式指定分支/ref。
- 历史 `upload-existing-apk.yml` 固定旧源码/产物，不能充当新版构建或交付。

## 下一步

1. 核实官方协议并审阅现有 OAuth、Provider、模型和流式实现，分配互不冲突的代码工作。
2. 提交并推送本记录；分阶段实现和验证，及时回填具体提交、测试结论、Actions/Release/产物。
3. 完成本次 ARM64 APK 的自动发布与下载校验，保留真实账号未测项目。

## 阶段更新：首轮实现与测试

- 授权、公共 API、namespace 工具、严格流式终态、套餐 UI 与新版 workflow 已落盘，仍为未提交的工作区改动。UI新增重新授权、无套餐权限、旧账号迁移状态；官方用量入口已从官方文档确认是 `https://chatgpt.com/settings/usage`。
- 新流程：`.github/workflows/chatgpt-test-apk.yml` + `tools/publish-chatgpt-test.py`，首次提交工作流会触发；后续可更新 `docs/chatgpt-oauth-build-request.txt` 触发。源码精确 checkout `github.sha`，标签包含 SHA/run/attempt，不覆盖旧版。
- 构建配置 `./gradlew help` 通过；发布脚本语法、YAML、6 项下载完整性用例通过；真实旧 APK 被包名/版本检查拒绝。日志 `/workspace/work/delivery-gradle-config.log`。尚未运行新版 Actions。
- 首轮本地 `:oauth:testDebugUnitTest :ai:testDebugUnitTest :app:testGithubDebugUnitTest`：OAuth任务通过；AI 313 项中2项失败，应用测试未运行到。失败为旧额度期望及完整工具多轮回放比较；推理代理正在定位修正。完整日志 `/workspace/work/chatgpt-tests-1.log`。
- 审阅发现刷新 ID token 的可选 nonce 仍需匹配原始 nonce，已要求授权代理补齐并测试。不能把该首轮写为全部测试通过。
- 凭据审计发现日志 header/表单、手动备份、Web 设置广播会带出凭据，协议代理正做针对性脱敏；安装 host ID 使用不备份的私有文件。
- 下一步：修复上述失败，完成应用编译/测试与代码审阅；提交推送合理代码阶段，再实际跑 Actions、发布和下载验证。

## 阶段更新：第二轮编译与独立审阅

- 第二轮日志 `/workspace/work/chatgpt-tests-2.log`：应用源码编译通过，OAuth和AI测试通过；应用测试失败7项。6项为新增Manager测试用 `.providers.single()` 忽略SettingsStore追加默认Provider；改为按ID选择。另1项为基线 `DefaultProvidersTest` 误期望Vercel禁用，已核实提交 `bb71920d` 的原实现本来启用，仅修正旧测试期望，保留产品行为。
- 独立审阅发现“刷新成功但JWKS暂时不可达”不能当作身份伪造直接退出；正在区分可重试验签密钥错误并保存待验证轮换结果，补重启恢复和退出撤销测试。原始授权 nonce 已保存并用于刷新 ID token 的可选 nonce 校验。
- 日志、手动备份及Web设置快照的ChatGPT凭据保护已落盘；Android系统备份排除含凭据的Settings datastore，应用内备份保留非敏感设置和所有聊天。新增对应回归测试，仍需在最终冻结源码后重跑。
- 尚无新版Release/APK；待所有必要测试通过后提交功能并触发工作流。不要把第二轮源码编译通过误记为交付完成。

## 阶段更新：公共 API 与工具阶段验证通过

- 第三轮仅OAuth/AI增量测试完成，日志 `/workspace/work/chatgpt-ai-tests-3.log`，`BUILD SUCCESSFUL`。本轮覆盖最终推理代码，包括公共端点、权限拒绝、能力解析、完整本地工具多轮回放、错误/早断流/工具参数快照补齐和实际OkHttp取消。
- 该合理代码阶段已提交推送 `af26646b`（AI/OAuth及兼容凭据结构），OAuth 10/10、AI 317/317通过；应用授权/UI/安全保护仍未提交，不能把中间源码状态当作可交付版。
- 应用剩余事项：待验证刷新响应的持久化/重启恢复、退出撤销最新令牌、短有效期防重复刷新测试，以及最终统一编译测试。新版工作流暂不提交触发，避免构建未完整版本。

## 阶段更新：实现冻结，最终统一检查运行中

- 已实现并补测JWKS临时失败后的待验证轮换响应持久化，恢复时先验签而不重用旧refresh；退出撤销待验证响应中的最新refresh；无有效验证不能发送推理。UI区分“等待恢复验证”和“没有套餐授权”，避免错误触发重新同意。
- 修复短有效期令牌导致递归刷新的风险；新增真实磁盘重启恢复、待验证退出、短有效期、取消/重复登录旧结果不可回写测试。Koin显式注入 `AppScope`，避免构造参数泛化后的运行时依赖缺失。
- 日志/备份保护4项测试已通过。最终统一命令 `:oauth:testDebugUnitTest :ai:testDebugUnitTest :app:testGithubDebugUnitTest` 已成功（34秒）；日志 `/workspace/work/chatgpt-tests-final.log`。所有当前代码编译、模块测试通过，具体数量由JUnit XML核对。真实账号登录及套餐调用仍未执行。
- 下一步：检查最终结果→修复实际失败→提交完整应用与workflow→推送触发Actions→监控与修复→下载Release APK并复核。

## 阶段更新：新版 Actions 已实际启动

- 最终本地结果：OAuth **10/10**、AI **317/317**、应用 **549/549**，无失败/错误/跳过；含新版Auth 8项、Manager 11项、Verifier 13项。`git diff --check`通过。
- 构建源码提交：`1d6332e6191f9693fb3bcf064027a6a332fd01d2`。
- Actions：`https://github.com/0425-sketch/rikkahub/actions/runs/43873871407`，head SHA匹配；已结束失败，具体阶段见下条。交付代理监看并保存长日志，主代理负责最终独立下载校验。
- 目前尚无新版Release下载验证结论。下一步核对Actions每阶段结果，必要时修复并用构建请求文件重新触发；成功后记录独立tag、Release、APK大小/SHA-256/证书/包名/版本/ABI。

## 阶段更新：首次Actions SDK准备失败

- Run `43873871407` / job `133321738399` 已完成，checkout、确切SHA检查、JDK21、pnpm11、Node22、锁定前端依赖安装均成功。
- `Prepare Android SDK packages` 实际错误：`sdkmanager: command not found`（exit127），不是平台版本缺失。构建/发布步骤未执行；完整日志 `/workspace/work/actions-43873871407/job-133321738399-complete.log`。
- 已修workflow：先用固定提交的 `android-actions/setup-android` 设置命令行工具与PATH，使用现有本地验证过的tools23.0/build16111833，再安装所需包；移除重复license管道。YAML解析与diff检查通过。此修复不改已测试的应用代码。
- 不重复运行原样失败流程。下一步提交必要workflow修复并自动触发新运行，记录确切新源码SHA。

## 阶段更新：SDK修复后的第二次Actions

- 修复已提交推送：`7e30d7d4d0b5e3e5201c8c8407737da51dbeb45b`。
- 新运行：`https://github.com/0425-sketch/rikkahub/actions/runs/43874333358`，已确认 `in_progress`，head SHA匹配。上一失败记录保留，不复用旧APK。
- 应用代码与本地876项通过的测试相同；第二次Actions仍需自行完成检查、构建、发布与下载验证。
- Run `43874333358` / job `133323306038` 已实际完成 Android命令行工具、SDK平台/Build Tools/NDK/CMake安装和签名准备，进入协议/回归测试及完整APK构建；此前 `sdkmanager` 缺失已解决。

## 阶段更新：本地完整APK打包与结构验证通过

- 本地 `:app:assembleGithubDebug` 成功（1分58秒），日志 `/workspace/work/chatgpt-local-apk.log`；未提交二进制。
- 使用交付脚本 `--verify-only` 实际检查本地ARM64 APK：包名 `me.rerere.rikkahub.chatgpt.debug`，versionCode `197`，versionName `3.2.0-chatgpt`，minSdk26，ABI仅arm64-v8a，签名验证通过。公开元数据 `/workspace/work/local-apk-check/apk-verification.json`。
- 本地APK为60,843,241字节，SHA-256 `07ed1b7f6cb09fad159082303e9cf6ff87e53044a28ca091514e305a159b6356`；这是本地检查产物，**不是最终Release下载验证结果**，签名也不代表Actions签名。
- 第二次Actions仍处于测试/构建阶段。运行中日志API尚未生成（404 BlobNotFound），等完成后取完整日志，不重复无效请求。
- 旧build/release文档已加历史记录提示，明确已撤销PAT不可再使用，最新交付统一指向本进度文件。
