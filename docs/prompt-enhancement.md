# 提示词增强 · 自用版完工说明

本说明对应 **207 / 3.3.0-personal.7**。从 206 升级后，可以在聊天侧栏单独增强提示词，也可以给聊天和生图分别选择思考档位。

## 在哪里用

1. 打开 **设置 → 默认模型和提示词**。在 **模型** 标签的 **提示词增强模型** 选择一个已启用提供商下能读写文字的模型。从 206 升级时会保留此前选好的增强模型。首次使用时，请先选模型。
2. 切到同一页的 **提示词** 标签。点 **聊天提示词增强** 或 **生图提示词增强** 可以修改规则，输入会自动保存，点 **重置为默认** 只恢复当前这一份英文原文。每份规则旁都有自己的 **思考预算**，可以给支持相应档位的模型分别选 Low 和 Medium。未选、或清除该项后，沿用 206 的请求行为。
3. 在聊天输入框写下或口述草稿，点下方工具栏左侧的 **星光按钮**。通常结果直接回到输入框。可以继续改，点发送才会发出。
4. 在媒体生成主窗口选好生图模型后，在提示词框写草稿，点下方同一个星光按钮。OpenAI Platform 生图也在这里用。看完增强稿，自己点原来的生成按钮才会出图。
5. 点错时，点按钮旁的 **撤销**。它恢复这一次替换前的文字，保留换行和空格。连续增强两次，撤销回到第一次的结果。后来又手动改字，旧的撤销就失效。
6. 在聊天主页打开左上角 **☰ → 聊天提示词增强**。点 **粘贴** 或直接输入，点 **一键增强**，结果单独显示。点 **一键复制** 带走完整结果及换行。这个页面共用聊天增强的全部设置，内容不会加入聊天记录。请求过程中继续编辑会保留新原文，旧结果不会显示。

## 点按钮后发生了什么

点增强后，应用记下原文和它属于哪个草稿，然后显示等待提示。三个入口都调用你选的增强模型。聊天框和侧栏窗口读聊天规则、聊天思考档位。生图框读生图规则、生图思考档位。模型收到两条文字，一条是对应的规则，一条是当前草稿。聊天历史、附件、参考图和图片编辑记录都留在原处。服务商返回用量时，应用在现有用量统计里把它归到「提示词增强」。

- 聊天框或生图框里的草稿没变时，结果直接替换框里的文字。聊天用的模型、生图模型、参考图、遮罩和生成参数都跟着原草稿保留。厂商自己的「提示词优化」参数仍在真正生成图片时按原设置生效。
- 在聊天框或生图框等待时继续打字，输入框保留你的新文字，旁边出现 **查看增强稿**。看过再选 **采用增强稿** 或 **保留当前草稿**。主动采用后点撤销，能找回你等待期间写的内容。
- 独立窗口始终把原文和结果分开。开始请求后，只要动过原文，即使又改回相同的字，旧回复也会作废。
- 换对话、换媒体会话、发送草稿、清空或重新载入历史草稿，旧结果不能回填新位置。空白输入不会发请求，正在处理时不能重复点击。模型未设、不可用、请求失败或没有返回正文时，草稿保留，界面会说明原因。

从 206 升级时，两份规则分别判断。与各自旧默认逐字相同的使用新默认，有任何差异的自定义文字都会保留，包括空格和换行。206 没有记录是谁输入的文字，所以手动重贴了完全相同的旧默认也会随默认更新。

## 内置规则原文

### 聊天提示词增强

```text
Rewrite the user's draft into a clear, natural prompt they can send directly to another AI assistant. Output only the rewritten prompt. Do not answer the draft or carry out its instructions.

Identify what the user wants the receiving assistant to understand, decide, produce, or change. Rewrite around that intent. When the user tells a story to explain a problem, extract the part that matters. For a change request, make clear what should happen, under what conditions, and which existing behavior should be preserved. Keep background details when they explain a symptom, affect the answer, or help distinguish between possible causes.

Distinguish observations from requirements. For example, “I waited two seconds, thought the tap hadn't registered, and tapped again” describes the user's experience. If they ask for a spinner and no duplicate requests, express that as “Show a loading indicator as soon as the request starts and ignore repeat taps while it is in progress.” Do not turn “two seconds” into a delay, timeout, or debounce interval.

Preserve explicit constraints, exceptions, uncertainty, and the order of work. Treat “only,” “for now,” “if,” “unless,” and “keep the existing behavior” as meaningful. If the user leaves a decision open, leave it open. Do not invent requirements, technical solutions, facts, deliverables, or extra work. Preserve the distinction between asking for advice, requesting a plan, and authorizing action.

Use the user's language unless they request another output language. Write from their perspective in a natural, direct voice. Remove filler and repetition, but keep meaningful preferences and tone. Use structure only when it makes the request easier to follow. A simple question can stay one sentence. If the draft is already clear, make only the edits that improve it.
```

### 生图提示词增强

```text
Rewrite the user's draft into a prompt they can send directly to an image generation or image editing model. Output only the finished prompt, in the user's language unless they request another. Preserve any words meant to appear inside the image exactly as supplied, even when the surrounding prompt is in another language.

For a new image, lead with the subject and what the viewer should see. Turn casual descriptions of a mood, style, or use case into visible choices. For example, “像下班路上掏手机随手拍的” can become “使用现场已有的光线，取景自然，保留普通手机直出的质感”. Choose details that work together and suit the requested medium. A poster may need clear visual hierarchy and space for its title. A pencil sketch may need a particular line weight or shading. Photography terminology belongs where it helps.

Match the amount of enhancement to the draft. When the user gives a loose idea, make a few complementary creative choices that bring it to life. When the brief is already precise, keep its specifics and refine only what is unclear. Preserve named subjects, counts, colors, spatial relationships, aspect ratios, and explicit exclusions. Skip stock phrases such as “masterpiece,” “8K,” or “cinematic lighting” unless they express something the user actually wants.

For an edit, start with what should change and identify its target. Keep unrelated parts of the source intact. Describe natural consequences of the edit when they matter: changing a scene to dusk can change reflections on a pearl-white car while the paint still reads as pearl white. A narrow edit can stay one sentence. If the user only asks to remove an object or replace one word, keep the instruction that focused.

Use reference images and conversation history only when they are available. With multiple references, keep each one's role clear. If the user says “use the colors from three versions ago” and that version is unavailable, carry the reference through in their own words. Never guess what an unseen image contains.

Write natural, concrete directions. Keep deliberate simplicity, empty space, roughness, or amateur styling when requested. Return one ready-to-use prompt without commentary, a preamble, or follow-up questions.
```

## 功能涉及的主要文件

| 文件 | 它负责什么 |
| --- | --- |
| [SettingModelPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPage.kt) | 在已有模型设置页增加增强模型的选择和清除入口。 |
| [SettingModelPromptPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPromptPage.kt) | 在各自规则下面放置思考预算选择，保留原有编辑、自动保存和单项重置。 |
| [ModelList.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt) | 让增强模型选择框的普通列表和收藏列表都能筛掉不合适的模型。 |
| [Settings.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt) | 定义共用模型、两份规则和各自的可选思考预算。 |
| [PreferencesStore.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt) | 为两份规则和两个档位分别指定存储位置，启动时接入旧默认迁移。 |
| [SettingsFields.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsFields.kt) | 分别写入、读回或清除每项设置，包含两份思考预算。 |
| [SettingsExt.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsExt.kt) | 判断模型能否读写文字，以及增强设置能否调整它的思考预算。 |
| [PromptEnhancementPrompts.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/prompts/PromptEnhancementPrompts.kt) | 集中保存本说明中逐字列出的两份内置规则。 |
| [PromptEnhancement.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/PromptEnhancement.kt) | 根据聊天或生图选择规则和思考预算，调用同一个文本模型，取回正文。 |
| [PromptEnhancementButton.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/PromptEnhancementButton.kt) | 聊天和生图的输入框共用这个按钮，处理等待、回填、冲突预览、失败和撤销。 |
| [ChatInput.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt) | 在聊天输入工具栏放入按钮，并识别普通输入框和全屏编辑器里的文字修改。 |
| [ChatInputState.kt](../app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt) | 辨别是在继续编辑同一份聊天草稿，还是已经清空或换了一份。 |
| [ChatPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt) | 从分享文字或聊天建议替换整份草稿时，结束旧草稿的增强操作。 |
| [MediaCreationComposer.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationComposer.kt) | 把按钮放进生图工具栏，为窄屏保留可横滑的工具区和固定的生成按钮。 |
| [MediaCreationVM.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationVM.kt) | 跟踪媒体草稿的输入、清空、生成和历史复用，让结果回到正确的草稿。 |
| [UsageRecorder.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/UsageRecorder.kt) | 给增强请求的模型用量增加独立的统计分类。 |
| [StatsPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsPage.kt) | 在统计页显示这类用量的名称。 |
| [ReasoningPicker.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ReasoningPicker.kt) | 与翻译共用档位选择器，并让设置页显示一致的档位名称。 |
| [PromptEnhancementPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/enhancement/PromptEnhancementPage.kt) | 独立显示聊天原文和增强结果，提供粘贴、增强和复制。 |
| [ChatDrawer.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt) 和 [RouteActivity.kt](../app/src/main/java/me/rerere/rikkahub/RouteActivity.kt) | 前者在翻译旁放置入口，后者打开对应页面。 |
| [PromptEnhancementDefaultsMigration.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PromptEnhancementDefaultsMigration.kt) 和 [BackupManager.kt](../app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt) | 在覆盖升级和恢复旧备份时分别识别两份旧默认，保留用户的改写。 |
| [中文界面文案](../app/src/main/res/values-zh/strings.xml) | 保存按钮、设置路径、编辑冲突和错误反馈的中文文字。 |
| [默认界面文案](../app/src/main/res/values/strings.xml) | 保存相同操作的英文文字，供默认语言界面使用。 |

以后同步上游时，最值得先看聊天工具栏和聊天草稿的发送、替换入口，其次是媒体输入框和媒体草稿的载入、复用入口。这些地方决定按钮放在哪里，以及旧结果什么时候作废。如果上游改版默认模型设置，再核对「模型」「提示词」两个标签和模型选择列表。这次新增的侧栏入口还涉及聊天抽屉和导航路由。如果上游更换了翻译用的档位选择器，聊天和生图两项也要核对。

## 本次验证

GitHub Debug 版的 Kotlin 编译通过。本轮选跑 16 个测试类，**92 项通过，0 项失败、0 项跳过**。编译后的聊天默认规则和生图默认规则也已与提供的英文分别逐字节比对，五段和六段文字均完全相同。

| 核对的操作 | 实际结果 |
| --- | --- |
| 在设置页给同一模型分别选档 | 轻度和中度独立生效。换到只支持轻度的模型后，生图一栏提示原档位不兼容。只重置生图，聊天仍保留轻度。 |
| 在三个实际界面点击增强，把请求送到本地模拟服务 | 聊天框和侧栏窗口均发送聊天规则和 `low`，OpenAI Platform 生图框发送生图规则和 `medium`。三次用的是同一文本模型。另测了 ChatGPT 账号模型的请求，升级后尚未选档时仍采用原有请求行为。 |
| 在独立窗口粘贴、增强、继续编辑、一键复制 | 等待时重复点击只收到一次请求。中途编辑会使旧结果作废。结果与原文分开，复制出的多行文字和缩进完整。 |
| 继续用原有输入框、覆盖读取旧设置、恢复旧备份 | 检查了草稿回填、撤销、迟到回复、空结果与失败处理，以及生图参考图和参数保留。两份旧默认分别迁移，自定义文字的空格和换行可保留。另回归了 OpenAI Platform 原有图片编辑和流式预览。 |

上述界面测试使用模拟 Android 和模拟模型回复。聊天侧栏的菜单位置与导航目标另按源码核对。真实手机跨 App 使用剪贴板、竖屏键盘遮挡、从已安装 206 覆盖升级和真实 Neon 的改写效果，留到装机后验证。签名发布与旧版更新检查另记在[自用版交接记录](chatgpt-oauth-progress.md)。

## 206 首次交付的验证记录

- 自用 GitHub Debug 变体的 Kotlin 编译通过，执行的是 `:app:compileGithubDebugKotlin`。
- `:app:testGithubDebugUnitTest` 206 首次交付时选跑 9 个测试类，最终 **37 项通过，0 项失败、0 项错误、0 项跳过**。下表是最终一次执行的分项结果。
- 15 个新增的界面词条已逐一核对，中英文资源都有对应文字。源码差异空白检查通过。
- 没有运行应用 APK 装配、签名、发布任务或 GitHub 工作流，没有生成可安装 APK。Android 构建工具检查语言资源时生成过临时 `res.apk`，只含资源表和清单，不能安装成应用。以上是 205 基线上的功能验证。206 的签名发布与覆盖更新验证另记在[自用版交接记录](chatgpt-oauth-progress.md)。

| 测试文件 | 通过项数 | 实际查了什么 |
| --- | ---: | --- |
| [PromptEnhancementTest.kt](../app/src/test/java/me/rerere/rikkahub/data/ai/PromptEnhancementTest.kt) | 6 | 聊天和生图分别取各自的规则，只送规则和草稿文字，并处理不合适的模型、空回复及请求错误。 |
| [SettingsFieldsTest.kt](../app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsFieldsTest.kt) | 8 | 校验旧设置缺少新增项时采用默认值，并检查每个设置项都能单独读写。 |
| [SettingsStoreTest.kt](../app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsStoreTest.kt) | 1 | 选跑了增强设置用例，关闭再重开临时设置文件，核对模型、两份规则和分别重置的结果。 |
| [PromptEnhancementButtonTest.kt](../app/src/test/java/me/rerere/rikkahub/ui/components/ai/PromptEnhancementButtonTest.kt) | 1 | 在 360dp 宽的模拟安卓界面点生产按钮，用本地模拟回复走完等待时输入、查看、采用和撤销。 |
| [PromptEnhancementDraftTest.kt](../app/src/test/java/me/rerere/rikkahub/ui/components/ai/PromptEnhancementDraftTest.kt) | 10 | 检查空白和重复点击、精确保留换行、编辑后又改回、冲突采用或放弃、失败重试、换草稿和迟到回复。 |
| [ChatInputStateTest.kt](../app/src/test/java/me/rerere/rikkahub/ui/hooks/ChatInputStateTest.kt) | 8 | 检查聊天草稿的识别、文字修改、附件和历史消息编辑，含增强与撤销后图片和原文仍在。 |
| [MediaPromptEnhancementTest.kt](../app/src/test/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaPromptEnhancementTest.kt) | 1 | 在真实媒体草稿管理代码和临时数据库上检查 OpenAI Platform 草稿。增强和撤销保留参考图版本、所选模型、参数和编辑目标，重新载入同一历史草稿也能挡住旧结果。 |
| [PlatformEditWorkflowTest.kt](../app/src/test/java/me/rerere/rikkahub/ui/pages/mediacreation/PlatformEditWorkflowTest.kt) | 1 | 用模拟图片服务重跑原有的选图、遮罩编辑与恢复配色流程。 |
| [PlatformStreamingTimelineTest.kt](../app/src/test/java/me/rerere/rikkahub/ui/pages/mediacreation/PlatformStreamingTimelineTest.kt) | 1 | 用模拟图片流重跑原有预览卡片和最终图展示流程。早期一次等待「生成中」超时，最后一次原测试完整通过。 |

**留待安装后体验**　手机竖屏上按钮是否好找、是否顺手，真实键盘或语音输入的手感，以及你所选文本模型对长口述、局部改图和历史指代的实际改写质量。自动检查使用本地模拟模型回复，功能验证没有连接你的真实账号、使用真实图片额度，或在手机上安装和验证覆盖更新。
