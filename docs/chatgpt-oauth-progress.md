# ChatGPT 官方 OAuth 迁移与 APK 交付进度

**当前任务：第二轮修复源码、936项测试和本地/Actions构建完成；覆盖安装交付仍被原签名私钥缺失阻断，未创建新Release，未交付新APK下载链接。** 用户已真机确认第一轮官方OAuth和普通聊天可用，相关成果已保留。最新产品源码 `b9f795224c260bcb0b3de7cd77c115eb188cb2f6`；全部代码已推送 `custom-3.2.0`，末尾记录最终Actions/证书/未完成事项。恢复时先读取本文件末尾并核对Git、Actions与Release，不能把构建成功当作交付完成。

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

## 最终验收：Release下载验证通过

- 分支：`custom-3.2.0`；全部实现已提交推送。交付源码：`7e30d7d4d0b5e3e5201c8c8407737da51dbeb45b`。本记录完成前的文档HEAD为 `99a069b2`，最终记录随本次文档提交推送；实际当前HEAD以 `git log -1` 和远端分支为准。
- 已测试：本地OAuth 10项、AI 317项、应用549项（合计876）全通过；完整本地assemble通过。Actions在干净runner重新完成协议/回归测试、完整构建和发布，`BUILD SUCCESSFUL`（7分44秒）。
- 成功Actions：<https://github.com/0425-sketch/rikkahub/actions/runs/43874333358>，job `133323306038`，verification artifact `14229323469`。完整日志 `/workspace/work/actions-43874333358/job-133323306038-complete.log`。
- Pre-release ID：`447075187`；独立标签：`custom-3.2.0-chatgpt-7e30d7d4d0b5-43874333358-1`。
- Release：<https://github.com/0425-sketch/rikkahub/releases/tag/custom-3.2.0-chatgpt-7e30d7d4d0b5-43874333358-1>
- **已验证APK**：<https://github.com/0425-sketch/rikkahub/releases/download/custom-3.2.0-chatgpt-7e30d7d4d0b5-43874333358-1/rikkahub-3.2.0-chatgpt-7e30d7d4d0b5-arm64.apk>
- SHA-256文件：<https://github.com/0425-sketch/rikkahub/releases/download/custom-3.2.0-chatgpt-7e30d7d4d0b5-43874333358-1/rikkahub-3.2.0-chatgpt-7e30d7d4d0b5-arm64.apk.sha256>
- 下载后大小：**60,843,249字节**；SHA-256：`580225a0947e2d243aff2ab6a24ddda264e14f587a1acb7015f6d3978d3426aa`。
- 下载后签名验证通过；证书SHA-256：`369f5d9de364514a92337b02efc50ce5c35bfb53753c325286c0efcaabc5b497`。
- 下载后包名 `me.rerere.rikkahub.chatgpt.debug`；versionCode `197`；versionName `3.2.0-chatgpt`；minSdk `26`（Android8.0）；仅 `arm64-v8a`。**独立安装，不覆盖旧自用版，不要求卸载或清数据。**
- Actions已做公开下载验证。主代理再次无令牌下载APK并独立复核大小、SHA-256、签名、包名/版本/ABI及公开校验文件，成功证据 `/workspace/work/release-43874333358/verified-delivery.json`。下载的是真正新Release包，不是本地包或旧基线产物。
- 未提交二进制、密钥或令牌；普通artifact仅测试报告及公开验证信息。旧版本/旧标签保留，未改master、未强推、未改写历史。

### 明确未验证/未完成项

- 用户真实ChatGPT登录、验证码、同意授权、实际套餐资格/额度调用、真机聊天和工具调用仍由用户安装后验证；模拟协议测试不能替代该验证。
- Actions secrets权限受限，签名安全持久化尚未完成，最终构建 `signing_persistence=this-build-only`。本次APK可安装，但不承诺后续构建能够同签名覆盖升级；不影响本次独立安装交付。
- 当前无待修复的已知测试失败、无待构建/待上传/待下载校验事项。用户下一步：下载安装→添加/进入ChatGPT提供商→登录授权→刷新并选择模型发消息→启用客户端工具并测试一次调用。

## 第二轮：真机反馈、范围与验收（2026-11-16）

- 用户确认第一轮最新版已能使用，官方登录和正常聊天是真机已验证状态；保护凭据、聊天、账号及已跑通自动交付。三张截图已收到并阅读。
- 本轮目标：按实际Provider/路由/模型能力修复内置工具UI；ChatGPT不支持的原生生图禁用并解释；旧保存开关在此路由迁移/忽略并给一次提示，不能阻断普通文本；高级自定义非法能力仍精确报错。保留图像输入、客户端MCP/函数、其他Provider能力及独立生图提供商。
- 搜索：截图展示 `搜索网页: 天舟八号发射成功` 和模型引用的 `Search failed: no results found`。先沿真实参数→请求→原始结果→解析→结果回传/历史比对原始3.2.0，不能把模型解释当根因；不换用户搜索服务或增加收费服务。
- 额度：重新核实SIWC官方文档/接口/响应字段，确认可靠数据源才实现套餐/应用限额、周期、重置、缓存/刷新/账号隔离；不把单次token usage、RPM、token有效期或本地累计当余额。无可靠来源则记录具体依据并保留准确状态和官方管理入口，不阻塞其余代码修复。
- 安装硬约束：用户本轮要求沿用 `me.rerere.rikkahub.chatgpt.debug` 和上一Release证书 `369f5d9de364514a92337b02efc50ce5c35bfb53753c325286c0efcaabc5b497`，versionCode大于197，覆盖安装保留数据；本轮不授权擅自换包名/签名/卸载。第一轮签名未持久化风险必须先查证，不能虚报覆盖能力。
- 当前分支 `custom-3.2.0`，已fetch并确认远端HEAD仍为 `e4fcd0bd`，工作区开始时干净。本节尚未提交，新功能尚未修改或测试。
- 并行任务：工具能力/请求/UI修复代理；搜索调用链与原始响应调查代理；官方额度/能力文档核实代理；签名来源及可升级交付调查代理。主代理整合、维护本记录、统一Gradle测试和提交推送。
- 已确认初步证据：搜索UI对应客户端 `search_web`，SearchTools/BingSearchService/对应UI与原始 `e033a47` 无差异；尚未确认手机HTTP返回。图三错误文本存在于本地ChatGPT请求体校验，继续验证保存开关注入链。
- 下一步：完成证据调查与局部修复/回归测试，明确签名可用性；任何关键阻碍立即记录，继续完成不依赖该阻碍的工作。

## 第二轮调查：已确认问题与签名阻碍

- 图三错误已定位为本地 `ChatGPTProvider.prepareChatGPTRequest` 对 `image_generation` 的校验；UI通用开关允许保存后，shared Responses构建器将其加入请求，发送前即失败。官方Preview页面本轮重新HTTP200读取，仍明确不支持该服务端工具；保留校验，修UI和已保存开关兼容，不伪装实现生图。
- 原版 `e033a47` 的SearchTools、BingSearchService和SearchWebToolUI与第一轮交付无差异。截图对应客户端 `search_web`；不能仅凭模型对结果的描述认定解析错误。
- 云端已取得Bing实际HTTP200 HTML：`新闻`解析11条；`天舟八号发射成功`解析10条但原始返回本身是“天”字相关内容，网页title/输入框仍回显完整query。尚未复现手机的空结果。发现Bing独用Jsoup直连绕过共享OkHttp/应用代理及取消机制，拟做有证据的最小修复。原始响应与解析证据 `/workspace/work/search-v2/`。
- **覆盖升级存在明确签名阻碍**：重新验证上一Release APK证书为 `369f5d...b497`；当前本地APK为 `25eba60...5289`，不同。上次成功Actions日志证明固定签名secret为空、使用临时debug key、清理步骤成功删除该key；唯一artifact仅测试/公开验证报告，cache路径不含keystore。当前无恢复原私钥的安全来源，secrets API仍403；APK中的公开证书不能恢复私钥。证据 `/workspace/work/signing-v2-certificates.json`。
- 用户本轮不授权改包/换签名/卸载，因此发布脚本将增加原证书强校验；错误签名不得创建Release或冒充覆盖升级包。继续完成全部独立代码、测试和候选构建，不能因签名问题丢弃工程成果；最后只说明无法由工程代码弥补的签名条件。
- 最新文档/证据仍未包含手机真实HTTP headers或用户token；不索取登录凭据，不把模拟响应当真实套餐余额依据。

## 第二轮阶段：官方额度结论与修复方案

- 本轮重新读取13个官方页面及3份固定SHA官方源码，证据 `/workspace/work/siwc-v2-docs/quota-and-capabilities-review.md`、`retrieval.json`。重点来源：[Preview限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)、[模型与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[错误与恢复](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)、[限流头](https://developers.openai.com/api/docs/guides/rate-limits)。
- 已查 `GET https://api.openai.com/v1/models`（模型目录）、`POST .../v1/responses` 的usage/events（单次输入/输出token）、`x-ratelimit-*`（RPM/TPM）、token reference（认证有效期）；均没有建立真实套餐剩余数据源。Admin `GET /v1/organization/usage/completions` 需不同的管理员API凭据且统计消费，不是SIWC套餐余额。
- 对照原版 `https://chatgpt.com/backend-api/wham/usage` 及Codex源码 `x-codex-*` 限额头：旧实现存在，但当前新路由文档/实际已知响应未证明其适用于新scope和套餐/应用统计口径；未擅自用新token调用旧后台。官方限额错误还明确不能据此推断整个套餐耗尽或重置时间。
- **本轮尚未实现应用内实时剩余额度**：缺的是适用于当前授权且能识别限额范围/周期/reset的接口或真实响应字段。增加明确的不可用状态说明，保留官方 `https://chatgpt.com/settings/usage`，不把网页入口描述成实时额度；没有伪造百分比/缓存/刷新按钮。
- 工具能力修复使用共享helper识别有效Provider（包括model.providerOverwrite），仅ChatGPT旧不兼容开关被过滤/迁移；持久化一次提示，不动其他Provider设置，不误伤图像输入或客户端函数。请求自定义明确指定非法工具仍精确拒绝。
- 覆盖版本准备为同包名 versionCode198 / `3.2.0-chatgpt.1`；发布前必须匹配旧证书。4项发布门禁测试通过，错签名即使伪造显示字段也不能创建Release。当前这部分未提交；仍需统一测试、构建并实际验证签名条件。

## 第二轮追加范围：思考档位与最终请求参数

- 用户追加要求已合并同一轮实现/测试/交付：逐项对照原始3.2.0的ChatGPTProvider、ResponseAPI、ReasoningLevel与参数合并逻辑，检查模型/助手/会话/持久化→TextGenerationParams→能力配置→自定义Body→最终HTTP JSON完整链路。
- 逐项验证AUTO（默认省略）、OFF、高/超高和所有界面档位；依据实际模型能力，禁止静默降档或统一high。最终交付需提供界面→请求值对照，区分客户端已验证与真实账号服务端未验证。
- 强制检查最终合并后的store:false、stream:true及不支持字段，同时审计summary、encrypted_content、instructions/developer、历史/工具结果、prompt_cache_key；自定义冲突需明确处理，隔离其他Provider。
- 覆盖保存重载、模型切换、多轮/工具循环、标题、压缩、连接测试的实际模型/档位；采用请求构造/模拟HTTP捕获，不依据回答风格或0秒显示。
- 当前已提交推送仅阶段记录 `583afbb9`；本轮代码未提交。初版工具兼容AI测试运行 `:ai:testDebugUnitTest` 已成功（22秒，日志 `/workspace/work/chatgpt-v2-ai-tests.log`），不代表新增思考审计已测试。
- 独立审阅发现并分配修复：会话builtInSearch可重新加入目录不支持的Search，以及tool_choice.allowed_tools嵌套非法托管工具未校验。两个新代理分别负责应用配置/辅助请求链和AI最终协议，不改OAuth；主代理继续统一测试、发布门禁与进度。

## 第二轮阶段：搜索修复已单独验证

- Bing保持原搜索服务和结果选择器，改为共享OkHttp，遵循应用代理/TLS/超时/取消；URL用query参数编码完整中文与特殊字符。错误明确区分HTTP状态、验证页、明确空结果和无法识别页面，不再把所有情况统一说成无结果。
- 已执行 `:search:testDebugUnitTest`：22/22通过（含6项新增Bing用例），日志 `/workspace/work/chatgpt-v2-search-tests.log`。应用层完整runAgent/搜索/两轮Responses集成用例已落盘，但尚未执行；不能混称全部搜索回传已测。
- 搜索独立阶段随本次提交推送；AI/App新思考与UI迁移仍为未提交且待统一测试。详细调查原始证据 `/workspace/work/search-v2/investigation.md`，不把云端结果当作手机网络复现。
- 已确认新思考问题：旧OFF在catalog不支持none时静默省略effort。正在实现准确能力约束及已保存无效档位提示；主聊天默认AUTO，辅助请求未明确选档时仅ChatGPT采用AUTO，其他Provider默认行为保持。

## 第二轮阶段：思考参数官方依据复核

- 搜索阶段 `274eb04d` 已推送。当前新增思考/UI/交付改动仍在工作区，尚未统一测试。
- 独立文档审阅确认：SIWC明确接受instructions或developer；HTTP不支持字段清单与当前过滤列表一致。`reasoning.encrypted_content`用于store:false的多轮延续，与可见summary独立；summary接受auto/concise/detailed，旧generate_summary已deprecated。`prompt_cache_key`可选string/null不在禁用名单，沿用原实现但不保证命中。
- 当前Responses规范分别列出xhigh和max，必须按模型目录发送原值，UI预算数字不能当作本路由实际token保证。官方SIWC目录说明未承诺扩展reasoning字段；字段缺失时只能确认AUTO可省略effort，UI需说明目录未声明，不能断言模型没有推理能力。
- 子代理只读审阅结论已汇入本记录及协议实现任务。完整参考仍在 `/workspace/work/siwc-v2-docs/`，不保存账号token、个人请求内容或密钥。

## 第二轮实现冻结：协议与思考能力

- AI协议实现已冻结待最终测试：OFF仅目录声明none时实际发none；AUTO省略effort；LOW/MEDIUM/HIGH/XHIGH/MAX分别发送low/medium/high/xhigh/max，仅启用目录已声明档位。不支持或未知的已保存选择保留并清楚报错，不静默降档。原始3.2.0与当前Assistant/ConversationConfig默认均为AUTO；仅SDK TextGenerationParams默认OFF，辅助OCR已针对ChatGPT显式用AUTO。
- 高级Body按原有深合并顺序处理，再验证最终effort/summary/include/tool_choice；模型字段仍高于助手字段，但不能覆盖界面档位或受保护的store/stream/model/input/tools。普通模型不支持参数按路由省略，高级显式不支持字段准确报错。补回encrypted_content保证无存储多轮历史。
- 已新增捕获最终HTTP JSON的所有档位保存重载、Body冲突、可见summary/加密历史、cache、工具两轮XHIGH及普通API Key隔离用例。独立只读审阅未发现新的阻断问题；目前新测试尚未完成运行。
- 应用链补齐有效Provider能力UI、旧工具迁移、旧会话搜索过滤；连接测试冻结实际模型/override，OCR仅ChatGPT用AUTO，记忆回退聊天模型的档位修正仅限ChatGPT，保留其他Provider原行为。应用测试仍在最后完善。
- 本地i18n CLI完成17项新增英文/中文文案规范化；未使用自动翻译API、未变更其他语言。未知目录档位文案使用“未声明支持”，不虚称模型没有推理能力。

## 第二轮阶段：最终协议测试通过

- 冻结AI实现执行 `:oauth:testDebugUnitTest :ai:testDebugUnitTest :search:testDebugUnitTest` 成功，日志 `/workspace/work/chatgpt-v2-protocol-tests.log`。JUnit XML核对OAuth10/10、AI332/332、搜索22/22，无失败/错误/跳过。
- 已测包括8项新增最终JSON思考用例、两轮XHIGH工具执行/回传、旧生图开关不阻断文本与看图/函数能力、显式高级非法字段/工具拒绝、原API Key请求行为。全部HTTP账号/目录fixture为合成数据，不能宣称真实服务端已接受全部档位。
- 本协议阶段随本次提交推送；应用层旧配置迁移、全链路重载、辅助请求、实际runAgent搜索测试正在单独执行，日志 `/workspace/work/chatgpt-v2-app-tests.log`。候选APK尚未构建，原签名阻碍未解除。

## 第二轮阶段：应用测试与独立审阅全部通过

- `:app:testGithubDebugUnitTest` 成功（1分15秒），JUnit XML为572/572，无失败/错误/跳过；日志 `/workspace/work/chatgpt-v2-app-tests.log`。四模块合计936项通过（OAuth10、AI332、搜索22、App572），发布门禁Python另外4项通过。
- 应用已测：旧开关迁移和一次提示存盘/确认/重启、Provider覆盖隔离、旧会话搜索过滤、runAgent实际客户端搜索执行与完整结果/历史回传、所有七档Settings+Conversation序列化重载到最终HTTP JSON、助手→模型深合并优先级、模型切换拒绝不支持档位、工具循环/压缩档位、快速模型/翻译/OCR/连接测试/记忆回退参数来源。
- 原版标题/建议使用快速模型和它的独立档位；自动压缩没有独立模型配置，沿原版复用当前会话模型/档位/工具前缀，测试已确认。翻译只在有效ChatGPT路由补带所选模型自身Body/Headers，其他Provider保持原行为。
- 独立审阅确认App有效Provider/UI/一次提示/DataStore字段/会话覆盖后过滤及其他Provider隔离，未发现新实质问题；源码和测试冻结。普通API Key/Gemini相关原有测试保持通过。真机新的工具/网络与真实账号逐档服务端接受仍未测。
- 本应用阶段随本次提交推送；接下来提交交付门禁并触发精确提交Actions，同时本地完整ARM64打包核对versionCode198和原证书。签名不匹配不得发布Release或给覆盖安装链接。

## 第二轮阶段：已推送源码，准备Actions

- 已推送合理代码阶段：搜索 `274eb04d`、AI协议/思考 `2b81be52`、App设置/辅助请求/版本198 `59e7bc7b`。应用与AI源码无未提交修改。
- 发布脚本现固定校验上一版证书 `369f5d9de364514a92337b02efc50ce5c35bfb53753c325286c0efcaabc5b497`，在任何Release创建/上传之前检查；不接受通过调用参数更换期望值。不匹配时仅保留公开apk-verification.json，不泄露签名材料，不发布误导更新。
- 工作流保持原exact github.sha checkout与现有构建设置，加入搜索测试/报告和4项升级门禁测试，versionCode198同包名。Python4项重新通过、语法及workflow YAML通过、git diff --check通过。以下交付提交将触发新Actions，需核对实际headSHA、测试/构建结果和签名门禁；不得重复以缺失原key的同一路径尝试发布。

## 第二轮阶段：新版Actions已启动

- 交付源码 `b9f795224c260bcb0b3de7cd77c115eb188cb2f6` 已推送，工作区代码干净。
- 新Actions：<https://github.com/0425-sketch/rikkahub/actions/runs/43888601864>，已确认in_progress且head SHA完全匹配。尚未得到构建/签名/发布结论；独立代理只读监看并保留完成日志，不重跑已确认无效路径。
- 本地同源码ARM64完整assemble进行中，日志 `/workspace/work/chatgpt-v2-local-apk.log`。后续必须核对实际APK包名/版本/ABI/签名；不能用全部单测通过代替可覆盖安装验收。

## 第二轮阶段：本地完整APK完成，证书不兼容已实测

- 同源码 `b9f795224c260bcb0b3de7cd77c115eb188cb2f6` 的 `:app:assembleGithubDebug` 成功（36秒），日志 `/workspace/work/chatgpt-v2-local-apk.log`。签名/元数据实测文件 `/workspace/work/chatgpt-v2-local-apk-check/apk-verification.json`。
- 本地候选APK：60,872,297字节；SHA-256 `7a82ca16ee9ed337670e2aeb32c515845a29545bf0a0b6a4132d6de70cafa702`；包名 `me.rerere.rikkahub.chatgpt.debug`，versionCode198，versionName `3.2.0-chatgpt.1`，minSdk26，仅arm64-v8a。
- APK签名本身有效，但证书 `25eba60a12a309f294a68f93cd9b77966827e3b07b6ab742d11aad49f7d85289` 与已安装版证书不同，`upgrade_certificate_matches=false`。此文件是内部候选，不能作为覆盖升级交付；未上传/创建Release，未要求用户卸载或清数据。
- Actions `43888601864` / job `133373383281` 已通过exact源码与4项发布门禁测试、SDK准备，现进行全套测试+完整打包。签名配置步骤成功仅说明流程执行，不代表原key存在；等待最终证书校验事实。

## 原始3.2.0逐项对照及最终客户端映射

| 对照项 | 保留与本轮必要调整 |
| --- | --- |
| ReasoningLevel序列化与档位 | 原版off/auto/low/medium/high/xhigh/max值完全保留；仅ChatGPT使用目录限制与最终JSON一致性校验。 |
| AUTO/显式档位 | AUTO仍省略effort；OFF→none，LOW→low，MEDIUM→medium，HIGH→high，XHIGH→xhigh，MAX→max；除AUTO外须目录声明。旧未支持OFF不再悄悄省略。 |
| 参数优先级 | 原版助手Body→模型Body深合并顺序保留；会话快照覆盖助手的聊天模型/档位。ChatGPT在最终合并后验证，不允许高级Body悄改已选档位。 |
| 系统提示与历史 | 原版developer消息、buildMessages、加密推理元数据、function_call_output序列化复用；新HTTP路由坚持完整input历史，禁止previous_response_id。 |
| 客户端搜索/MCP/functions | 原版参数schema、strict:false、客户端执行、结果文本、UI/多轮历史保持；仅官方路由要求的namespace包装与解包适配。 |
| 内置工具 | 依据有效Provider/模型过滤ChatGPT旧不兼容开关；Google原有三项与API Key Responses原能力保留。显式高级非法托管工具有准确错误。 |
| stream/store/采样 | 原版ChatGPT generateText继续聚合SSE；新SIWC最终stream:true/store:false，普通不支持参数省略，高级显式不兼容字段拒绝；不照搬Codex身份头或旧后台参数。 |
| summary/encrypted/cache | 摘要依据目录；encrypted_content在自定义include后仍保留；原sessionId→prompt_cache_key保留，允许合法自定义缓存键但不承诺缓存命中。 |
| 辅助请求 | 标题/建议继续快速模型独立档位；压缩复用当前会话；仅ChatGPT修正OCR无选择档位、翻译模型自定义字段、记忆回退档位。 |
| Bing网络 | 原搜索参数/选择器/返回结构保持，修复Jsoup直连绕过共享HTTP配置，原始上游不相关结果不伪造过滤或替换服务。 |

- 上表的最终请求映射已通过模拟HTTP与持久化测试；用户已真机确认官方登录/普通聊天可用，但本轮全部档位的真实账号服务端接受仍未验证，不能以思考耗时或回答风格代替。

## 第二轮最终工程状态：验证完成，覆盖交付阻断

- 分支 `custom-3.2.0`；产品源码提交 `b9f795224c260bcb0b3de7cd77c115eb188cb2f6`，此前搜索/AI/App阶段分别 `274eb04d` / `2b81be52` / `59e7bc7b`；后续仅主进度记录更新。最后文档HEAD由本次提交生成，以git log与远端分支核对。
- 本地：OAuth10、AI332、搜索22、App572（合计936）全部通过；Python发布门禁4项通过；完整ARM64 assemble成功；文案CLI/XML、workflow YAML、脚本语法、git diff --check通过。
- **Actions实际结果**：<https://github.com/0425-sketch/rikkahub/actions/runs/43888601864>，job `133373383281`，exact source与上述产品SHA一致。测试及ARM64打包步骤成功，日志 `BUILD SUCCESSFUL in 8m 57s`；仅发布前原签名校验步骤失败，实际错误 `UPGRADE_SIGNING`。没有重跑已确认缺失原key的路径。
- 已实际下载核对verification artifact `14235619229`（104,782字节），ZIP SHA-256 `cec7fc1997d4e859eaabe8d22d47d8e66b60094e7a0477162a9adb656ba20626` 与GitHub记录一致。131个JUnit XML再次独立核对936项，failure/error/skipped均0。文件 `/workspace/work/actions-43888601864/verification.zip`；公开脱敏日志 `job-133373383281-public.log` 与summary同目录。
- 云端候选APK元数据：包名 `me.rerere.rikkahub.chatgpt.debug`，versionCode198，versionName `3.2.0-chatgpt.1`，minSdk26，仅arm64-v8a，60,872,301字节，SHA-256 `3ca489e75559ac60de1276be411a78ab4c862769aa249f7f66dc7e9392254bd2`。云端证书 `9ecc0935ffdf7c616fee46caf4b31f27f57e505fe3be7bcb66db3924cbd07a9e`，与已安装197版原证书 `369f5d9de364514a92337b02efc50ce5c35bfb53753c325286c0efcaabc5b497` 不同。`upgrade_certificate_matches=false`。
- **尚未完成交付**：未创建第二轮Release、未上传APK、没有新版APK公开下载验证。下载验证的是公开测试/元数据artifact，不是APK；不能混淆。新云端临时签名已正常清理；artifact不含APK或任何签名私钥。原版Release保持不变，未要求用户卸载、清数据或改配置。
- 阻碍原因已查证：上一轮一次性GitHub runner签名私钥已删除且未安全持久化，公开APK证书不能恢复私钥；现有工作区和可查存储无原key，原secrets能力受限。此次Actions再次证明没有匹配原证书的固定签名。此限制无法通过修改versionCode、安装命令或移除门禁解决。
- 用户已再次明确拒绝并存安装，坚持同包同签名。该方案已撤下，不再等待或请求用户放宽安装约束。交付的必要条件仍是取得与已安装版证书匹配的原签名私钥；目前没有可用恢复来源，交付保持阻断。不得改包名、换签名、卸载或清数据，不得移除签名校验或将异签名APK作为更新发布。

### 第二轮功能结论与仍待验证

- **已修复设置与聊天兼容，套餐原生生图尚不支持**：旧ChatGPT不兼容内置工具开关迁移并一次提示；UI按有效Provider/路由/目录限制；高级显式非法工具准确拒绝；看图、客户端MCP/functions、其他Provider和独立生图方式保留。依据官方SIWC Preview限制本轮仍明确禁止image_generation。
- **应用内实时剩余额度未实现**：已查官方SIWC/Responses/限流/用量文档及原版wham/Codex来源，未建立适用新凭据且明确套餐/应用范围、周期、reset的可靠数据源。没有手机真实授权HTTP headers/events可检验，不能把文档或合成数据当实际余额。应用显示准确说明和官方管理用量入口，无伪造百分比/刷新/缓存。
- **搜索**：截图对应客户端search_web，原版schema/执行/UI未被OAuth迁移改变；修复Bing绕过共享网络配置并准确区分错误。云端完整查询已到Bing，但原始HTML含不相关结果，未复现手机空结果。真实手机网络/代理下的搜索仍待后续可安装版本复测，不宣称彻底修复上游结果质量。
- **思考**：七档客户端最终JSON映射、保存恢复、自定义冲突、多轮工具、辅助模型/档位来源均通过测试；除AUTO外只有目录声明才可选，未声明的旧选择保留并提示。真实账号服务端逐档接受仍未验证，不以回答风格或耗时判定。


## 用户决定：坚持同包同签名

- 用户最新原文：“不确认，要同包同签名”。该决定优先于此前提出的并存安装建议；已撤下该建议，禁止将放宽要求作为默认下一步。
- 已核对当前分支custom-3.2.0，起始HEAD为ddb5c1cb，工作区干净；此前936项测试、Actions构建和签名失败证据仍有效。本次仅更新约束记录，未修改产品代码、签名、包名或版本，未触发新的构建/发布。
- 已完成修复与测试成果保留在远端。剩余唯一可接受的APK交付路径：原私钥可恢复时核对证书，再沿现有Actions构建发布并下载验证；现有证据未发现该私钥的可用副本，不能承诺可恢复，也不重复已确认失败的构建或密钥查找路径。
