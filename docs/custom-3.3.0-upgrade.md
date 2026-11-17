# 自用 3.3.0 升级与诊断优化

> 本文记录本次代码、测试与构建证据。**代码、回归和原固定签名APK构建已完成**：JVM实际通过1,183项，另1项缺外部真实样本跳过；数据库Room及备份13项已通过。主用、备用签名在独立Actions runner上均恢复、构建、验签成功。**签名APK通过Actions产物交付；当前环境下载被代理403阻止，本地下载后复验未完成。**历史版本的测试与 APK 不计作本次交付。

## 基线与整合目标

- 仓库：`0425-sketch/rikkahub`；自用分支：`custom-3.2.0`。
- 实际自用基线：`929c17c88f349dd9b8b08f81e87b4bab35457041`，保留 `f614e7d` 之后的已有修改。
- 上游：`rikkahub/rikkahub` 的正式 tag **`3.3.0`**，不是 `v3.3.0` 或最新 master。
- tag / release commit：`eb33917e483aae1427db9cb10232642a640d2986`。
- 正式发布时间：`2026-11-16T15:36:03Z`。
- 应用整合 commit：`895ff3239a43005107ae0677eff5937e56a8b755`；签名构建 commit：`91f7c76b8f8f07d1b49e214cbc7aefff4c3a4c2c`。后者仅增加已有签名的产物构建入口及对应权限测试，没有修改应用生产代码。其后的提交仅更新本交付报告。

开始时工作区无未提交改动，检出的是 `work` 分支；核对远端后切换到上述自用分支再实施整合。没有 reset 或覆盖用户工作。用户随后明确授权临时分支推送与签名构建；已推送 `build/personal-3.3.0`，远端 `custom-3.2.0` 保持原状。未创建Release、改仓库可见性或向上游提交PR。

## 五项改动

### 1. 日志脱敏与关闭

统一通过凭据策略、HTTP 拦截器及惰性 Provider 诊断入口处理日志副本。请求头大小写不敏感，覆盖 Authorization、Proxy-Authorization、Cookie、Set-Cookie、X-Api-Key、Api-Key、x-goog-api-key 等凭据；URL 查询及认证上下文中的 Form / JSON 字段按凭据语义处理，普通业务字段和 token 数量统计仍保留。

OAuth 注册、授权、换码、刷新和撤销的敏感内容优先不进入日志，按类型标记移除拦截器以兼容R8。请求scope快照模式代次与凭据；OFF开始或跨OFF/ON的旧请求不补采。Basic两种编码、Cookie尾项和Brave凭据头均有回归。异常原因、服务端回显及导出走同一脱敏边界。Gemini Developer API 的 `x-goog-api-key` 和 Vertex API Key 的 URL `key` 都在范围内。只处理日志副本，不修改真实请求、聊天、提示词或工具参数。

入口：**设置 → 请求日志 → 诊断日志**。复用原设置项，显示“关闭 / 开启（脱敏）”，没有真实密钥输出模式。新安装及缺失设置值默认关闭，已明确保存的选择继续生效。关闭后停止 HTTP 和 Provider 请求/响应诊断、清空日志缓冲，惰性正文输出不执行额外序列化；切换和进程重启使用持久设置。正常错误提示、异常传播和工作区程序输出保留；此设置不控制 Android 系统崩溃记录。

### 2. 缓存诊断分级与开销

入口：**设置 → 模型服务商 → ChatGPT 服务商 → 套餐卡片 → 缓存诊断**；同处保留“保存脱敏缓存诊断”。这是全局诊断模式，导出仍按当前账号隔离。

| 档位 | 采集行为 |
| --- | --- |
| 关闭（默认） | 不安装请求诊断观察器，不额外读取请求体、解析 JSON 或计算内容指纹；停止新采集并清空诊断缓冲。 |
| 轻量 | 只采集模型、耗时、状态及服务端真实 usage 等低成本信息，不遍历聊天上下文或图片 base64。 |
| 完整 | 额外采集有界请求指纹、前缀差异和重试关联；超限明确标记不完整，不截断真实模型请求。 |

网络观察器复用已经构造的 JSON 对象，只有最终请求体仍为同一对象时才计算其内容指纹；若其他拦截器替换正文，标记 `wire_body_replaced_by_interceptor`，不重新读取、复制或解析正文。重型规范化/HMAC 在锁外完成，提交记录时检查模式代次，防止关闭后旧请求重新填充缓冲。整个 input 的摘要由分项摘要派生，避免再次遍历全部 input。

完整采集默认边界为每请求 262,144 个规范化字符、8,192 个节点、最多 512 个 input 项、最大深度 32；保留最多 40 条记录。导出写明采集模式、缺失原因及未报告值；例如工作量超限标记 `diagnostic_work_budget_exceeded`。原始聊天及凭据不进入诊断记录，进程级 HMAC 密钥不导出。

关闭诊断不改变服务端缓存策略、`session_id`、`prompt_cache_key`、工具顺序、加密推理回传，也不关闭正常 token / 缓存读写统计或删除用量历史。正常统计继续区分未知与明确报告的零。

### 3. 数据库 schema 分叉兼容

目标为 **Room schema 35**。上游 `3.3.0` 最终还包括 schema 34 的 workspace `storage_mounts`，因此不能只处理两个 schema 33 后停留在 34。

| 来源 | 到目标结构的处理 |
| --- | --- |
| 原版 schema 32 | 保留原自用 32→33 用量迁移，再执行兼容迁移到 35。 |
| 上游 schema 33 | 保留消息复合索引，补用量 presence/cache-write 字段及 storage mounts。 |
| 已安装自用 schema 33 | 保留已有用量字段和值，增加消息复合索引及 storage mounts。 |
| 上游 schema 34 | 保留 storage mounts 和复合索引，补缺失用量字段。 |
| 全新安装 | 直接创建同一 schema 35 结构。 |

兼容迁移查询真实表字段，按缺失项添加；最终均使用 `(conversation_id, node_index)` 复合索引并移除旧单列索引。已有 `prompt_tokens_reported`、`cached_tokens_reported`、`cache_write_tokens` 原样保留；只有新添加的 presence 字段才按旧正值推断，旧零值不会冒充明确报告的零。没有删除数据库、破坏性重建、伪造版本或只替换 identity hash。

备份导入继续先在 staging 副本运行迁移、Room 结构验证、路径重映射及 checkpoint，再发布 pending restore。失败或不兼容时不替换当前数据库。直接升级和不同来源备份导入分别测试，不能互相代替。

### 4. Gemini thoughtSignature 与上游修复

确认 `2898c3d` 已包含在目标 tag 中。保留 tag 的流式/非流式独立 `thoughtSignature` part 处理、已有签名不覆盖、未知 part 兼容、后续请求完整回传，以及纯签名空思考的显示处理。自用 `promptTokensReported` / `cachedTokensReported` 同时保留。

上游 `GoogleThoughtSignaturePartTest` 六项测试包含本地工具后继续 Google Search 的两轮 trace，对收到和回传的签名逐一比较；六项及本次AI模块全390项均已实际通过。

### 5. 大工具参数累积

`ResponseApiStreamDecoder` 内部验证缓冲改用可追加缓冲，在结束/校验节点才生成完整字符串。并行工具参数仍分别存储，保留完整参数验证、冲突检测、`arguments.done` / `output_item.done` / `response.completed` 处理及工具执行前的完整性要求。断流、停止或错误不能把残缺参数当作可执行调用。

没有改动 UI 不可变状态机制，没有丢弃 delta、截断工具参数或取消校验。

## 保留的自用功能

保留新版 SIWC 动态注册、套餐授权、公共 Responses 通道、注册信息复用、身份验证、刷新及撤销、namespaced 客户端工具和多轮工具调用、加密推理回传、稳定 session affinity 与工具顺序。上游旧 Codex OAuth 不替代 SIWC；已有用户显式设置不被上游新默认值强制覆盖。

自用包名保持 `me.rerere.rikkahub.personal`。源码配置升级到 `versionCode=201`、`versionName=3.3.0-personal.1`；应用数据和固定签名身份保持原路径与约束。

## 本次验证记录

| 检查 | 本次状态与证据 |
| --- | --- |
| SQLite 主机迁移及 staging / 用量语义 | **14 项通过**；`/workspace/scratch/db-host-tests.log`。包括原版32、自用33、上游33/34直接升级与staging、全新安装结构一致及失败回滚。 |
| Room 实际迁移 / staging | **13项通过**：直接升级/新装5项、生产备份stage/publish/apply及失败回滚8项。Robolectric4.18/API35/Native SQLite实际执行生产Room校验和BackupManager；仅替换Requery JNI/libsimple适配层。原始XML：`/workspace/scratch/db-room-results/`，最终全套内再次通过。 |
| 签名/发布 Python 门禁 | **38项通过**，最终实际重跑5.760秒；包含原33项及5项CLI分支权限回归，临时分支只允许恢复已有签名。PyNaCl1.6.0，使用合成测试材料，无测试产生的远端写入；`/workspace/scratch/signing-final-tests.log`。 |
| 无密钥默认构建门禁 | **按预期失败**，`verifyPersonalSigning` 明确拒绝；`/workspace/scratch/signing-guard-locked.log`。 |
| 显式 unsigned 门禁 | **通过**，`-PpersonalUnsigned=true` 后该门禁实际执行成功；`/workspace/scratch/signing-guard-unsigned.log`。此结果不是 APK 构建成功。 |
| 流式解析器前后性能对照 | **已实际运行**；见下一节及 `/workspace/scratch/stream-benchmark.json`。 |
| 缓存诊断前后性能对照 | **已单独运行通过**；`/workspace/scratch/cache-benchmark-isolated.xml`，包含墙钟、线程CPU、分配及4线程锁等待。 |
| 日志、诊断模式/切换/持久化、SIWC、普通API及其他账号回归 | **完整JVM回归实际1,183项通过、0失败**；另1项DeepSeek真实归档测试缺外部样本而跳过。AI390、common20、OAuth13、search22、speech48、workspace60、app630通过。新日志14+9项、OAuth HTTP10项、缓存模式6项、DataStore落盘重开测试均包含在内。 |
| Gemini六项签名/多轮trace回放及大参数/断流回归 | **通过**；大参数新回归7项、原decoder14项、ChatGPT stream13项均包含在最终全套。Android迁移instrumentation源码也已实际编译通过，但未在设备执行。 |
| APK组装、包名/版本/签名核对 | **未签名构建通过**（4分13秒）；ARM64、x86_64和通用APK均核实personal包名、201/3.3.0-personal.1。apksigner均返回1 / DOES NOT VERIFY（缺签名），不能安装；`/workspace/scratch/upgrade-apk-build.log`、交付目录 `apk-verification.json`。 |
| Android真机安装、登录/重授权/刷新/撤销、实际模型调用 | **未验证**；当前没有连接的Android设备或可用模拟器。 |

最终全套使用 `--rerun-tasks`，285项Gradle任务实际执行，3分43秒成功。原始XML和时间戳完整归档在 `/workspace/scratch/final-test-results/`，汇总 `summary.json`；完整日志 `/workspace/scratch/upgrade-final-tests.log`。不把依赖编译的 `UP-TO-DATE`、旧日志或缺样本跳过计为通过。SIWC测试是MockWebServer/协议级验证，不能代替实际账号和手机验收。

复现完整回归：

```bash
source /workspace/.setup/env.sh
unset ANDROID_SDK_HOME
./gradlew :ai:testDebugUnitTest :oauth:testDebugUnitTest :common:testDebugUnitTest \
  :app:testGithubDebugUnitTest :speech:testDebugUnitTest :workspace:testDebugUnitTest \
  :search:testDebugUnitTest :app:compileGithubDebugAndroidTestKotlin --rerun-tasks --max-workers=4
```

本环境的Robolectric缓存/代理配置在 `/workspace/.setup/gradle/init.d/robolectric-runtime.gradle`；保持TLS校验。没有把环境代理或私有配置写入仓库。

## 可重复性能对照

流式对照直接从 Git 读取上述基线 decoder，在临时命名空间编译，与当前 decoder 使用相同预构造 SSE 输入；没有改写基线源码。每个场景两个并行工具、512字符delta、5次交替顺序采样，以下为中位数。测量包含解析器工作，不含 UI 状态更新和输入fixture构造。

环境：Linux amd64，OpenJDK `21.0.12.1` / OpenJDK 64-Bit Server VM，4个可见处理器，最大堆 `805306368` 字节。

| 每工具参数大小 | 墙钟ms：旧→新 | 线程CPU ms：旧→新 | 线程分配字节：旧→新 |
| --- | --- | --- | --- |
| 64 KiB | 13.342 → 7.971 | 13.115 → 7.978 | 10,294,848 → 2,094,032 |
| 256 KiB | 55.657 → 21.325 | 48.592 → 20.868 | 141,770,304 → 8,302,608 |
| 1 MiB | 675.659 → 49.893 | 640.780 → 49.297 | 2,177,490,240 → 33,021,808 |

这些结果只代表该JVM环境中的重复测试，不代表手机端到端延迟或网络响应速度。该次对照的源文件SHA-256分别为：

- 基线：`171a165d843dc062a35494f05d0db64c282f5ce4568440e200419d62004cb830`。
- 优化版：`72ee0e3850cd0b136acfd6ced11b76c83d3c1735ef655be29725c742cc791301`。

复现：

```bash
source /workspace/.setup/env.sh
unset ANDROID_SDK_HOME
python3 tools/benchmark-response-decoder.py \
  --baseline 929c17c88f349dd9b8b08f81e87b4bab35457041 \
  --output /workspace/scratch/stream-benchmark.log
```

详细样本与环境见 `/workspace/scratch/stream-benchmark.json`、`/workspace/scratch/stream-benchmark.log`。最终代码如再改变decoder，需重新运行而不是沿用这些数值。

缓存诊断基准复现：`./gradlew :ai:testDebugUnitTest --tests '*CacheDiagnosticBenchmarkTest' --rerun-tasks`。最终数据在全套之后单独运行，避免其他模块编译竞争；5次预热、3组采样中位数，同一JDK/4CPU环境。基线为929c17c8中原recorder的精确副本。测量范围为 `recorder.begin()`，不含网络和HTTP序列化；网络重复读取已移除，并用真实HTTP测试断言每个请求body只写一次。

| 128×1KiB上下文 | 墙钟ms | 线程CPU ms | 每请求线程分配B |
| --- | ---: | ---: | ---: |
| 旧完整 | 5.773 | 5.012 | 5,938,670 |
| 新关闭 | 0.000198 | 0.000267 | 0 |
| 新轻量 | 0.006787 | 0.006876 | 640 |
| 新完整 | 1.022 | 1.017 | 1,006,160 |

该等工作量完整采集的线程CPU下降约79.7%、分配下降约83.1%。4线程共40请求：墙钟204.983→40.548ms，线程CPU合计200.023→84.397ms，监视器阻塞时间合计356→4ms。

2MB图片场景旧完整采集线程CPU20.842ms/分配48,234,111B；新完整0.025ms/15,693B，**这属于明确预算超限后停止指纹采集，不是等工作量速度比较**。原始图片和真实请求不截断，导出标明不完整。关闭模式测得额外分配0B；轻量模式不访问图片内容。原始结果：`/workspace/scratch/cache-benchmark-isolated.xml`、`.log`。

## APK与固定签名交付

原私钥保存在GitHub Actions加密Secrets `RIKKAHUB_PERSONAL_SIGNING`、`RIKKAHUB_PERSONAL_SIGNING_BACKUP`。本次从两份已有材料独立恢复，没有生成或替换应用签名，没有导出私钥到本地或产物。

```text
applicationId: me.rerere.rikkahub.personal
versionCode: 201
versionName: 3.3.0-personal.1
ABI: arm64-v8a
minSdk: 26
certificate SHA-256: 882c1cc87ccf36c2ac5eb178b8d8ef13ff74a3ac8b759b41aed6e48bddb4e1fd
signed source commit: 91f7c76b8f8f07d1b49e214cbc7aefff4c3a4c2c
```

[实际构建运行 44070125131](https://github.com/0425-sketch/rikkahub/actions/runs/44070125131)整体耗时7分25秒，两个独立runner均完成恢复、APK构建、`apksigner`验签、`aapt2`包名/版本/ABI校验、实际证书匹配及临时私钥清理。主用job `133993920515`，备用job `133993920489`。工作流只有`contents: read`，不创建Release。

- [主用签名APK产物ZIP](https://github.com/0425-sketch/rikkahub/actions/runs/44070125131/artifacts/14323705188)：解压取 `candidate.apk`，同包含公开验签元数据和恢复证明。
- [备用签名构建产物ZIP](https://github.com/0425-sketch/rikkahub/actions/runs/44070125131/artifacts/14321939200)：独立构建并通过同样的校验。
- 产物保留14天。GitHub记录的主用ZIP SHA-256为 `b7aaaff70ca5069364b7fd8f75ebca4b125aa94e490c8048e55be8fbe88889be`；备用为 `2fe445097ebc46e8dde5390ded4af3a17debd48b3e490fc3f355bef98cacb190`。这是**ZIP摘要，不是APK摘要**。

本次还重新下载了真正的 `3.2.0-personal.2` 旧APK并在本地执行验签：包名相同、versionCode=200、证书与上面的固定证书完全一致；SHA-256=`0b8b59d3d59d3c48995f6a2095a2a25bb00fa6f83d08dcb7858e76072f9adef3`。新APK满足相同包名、相同签名和递增版本号的覆盖条件。未在用户设备实际安装，也未运行真实账号在线授权流程。

当前云环境通过GitHub CLI和标准API下载新Actions产物时，代理对官方存储主机 `productionresultssa15.blob.core.windows.net` 的CONNECT请求返回403。因此没有把“构建机实际验签成功”写成本地下载后复验成功，没有声称已取得新APK的本地摘要。下载链接直接指向上述Actions产物；未为绕过下载阻塞公开发布。

本地 `/workspace/artifacts/rikkahub-3.3.0/` 保存交付报告、运行/API证据、签名测试日志及旧版实际APK核验结果。先前的 `rikkahub-3.3.0-personal.1-arm64-UNSIGNED.apk` 仍仅为未签名验证包，不能用它覆盖安装；它的SHA-256是 `0d61c2f7d3ba4fd77ff6ac852746ce8bb05cad082ec39d3fcbaa4fd7e8b30970`。
