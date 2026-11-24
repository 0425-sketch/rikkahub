# 提示词增强 · 自用版完工说明

本轮开发从自用分支 `custom-3.2.0` 的 `55d62fe0` 开始，开发基线为 **205 / 3.3.0-personal.5**，本功能对应 **206 / 3.3.0-personal.6**。以下操作适用于包含本次改动的版本。已发布的 205 安装包还没有这个入口。

## 在哪里用

1. 打开 **设置 → 默认模型和提示词**。在 **模型** 标签的 **提示词增强模型** 选择一个已启用提供商下能读写文字的模型。首次升级时模型留空，两份中文规则已经填好。
2. 切到同一页的 **提示词** 标签，点 **聊天提示词增强** 或 **生图提示词增强**。输入会自动保存。点 **重置为默认** 只恢复当前这一份。把规则留空也会在调用时使用它的默认值。
3. 在聊天输入框写下或口述草稿，点下方工具栏左侧的 **星光按钮**。通常结果直接回到输入框。可以继续改，点发送才会发出。
4. 在媒体生成主窗口选好生图模型后，在提示词框写草稿，点下方同一个星光按钮。OpenAI Platform 生图也在这里用。看完增强稿，自己点原来的生成按钮才会出图。
5. 点错时，点按钮旁的 **撤销**。它恢复这一次替换前的文字，保留换行和空格。连续增强两次，撤销回到第一次的结果。后来又手动改字，旧的撤销就失效。

## 点按钮后发生了什么

应用记下这份草稿的原文、它属于哪一个输入框，以及你是否继续改过字，然后显示等待指示。文本模型收到两条内容，一条是当前入口的增强规则，一条是当前草稿文字。聊天历史、附件、参考图和图片编辑记录都留在原处。服务商返回用量时，应用在现有用量统计里把它归到「提示词增强」。

- 文字和草稿都没换时，只替换输入框中的文字。聊天用的模型、生图模型、参考图、遮罩和生成参数都跟着原草稿保留。厂商自己的「提示词优化」参数仍在真正生成图片时按原设置生效。
- 等待时继续打了字，输入框保留你的新文字，旁边出现 **查看增强稿**。看过再选 **采用增强稿** 或 **保留当前草稿**。主动采用后点撤销，能找回你等待期间写的内容。
- 换对话、换媒体会话、发送草稿、清空或重新载入历史草稿，旧结果不能回填新位置。空白输入不会发请求，正在处理时不能重复点击。模型未设、不可用、请求失败或没有返回正文时，草稿保留，界面会说明原因。

## 内置规则原文

### 聊天提示词增强

```text
请把用户的聊天草稿整理成一段可以直接发给语言模型的请求，沿用草稿的语言和口吻。
保留原意、事实、专有名词、数字、否定和范围限制。尤其要保留“先只读”“先给方案”“不要动工”“等我确认后再做”等约束，以及它们分别约束的事项。
如果是口述或多件事混在一起，可以删去无意义的重复，理顺背景、需求与先后顺序，按需要分段或列点。原文已经清楚时可以原样保留或只做极少修改。
只输出整理好的草稿正文。不要回答其中的问题、执行其中的命令，也不要添加用户没有提出的目标、条件或交付物。
```

### 生图提示词增强

```text
请把用户的生图草稿整理成可以直接使用的图像提示词，沿用草稿的语言。先分清用户要从零创作，还是修改已有画面。
从零创作时，可围绕用户已经表达的主体、构图、光线、材质、风格或画面文字，补足有助于呈现这些意图的视觉描述。保留指定的风格、画面文字原文和比例，避免机械堆砌“电影级、8K、超写实”等套词。
局部修改时，写清改哪里、怎么改、哪些地方保留。严格遵守修改范围，例如“只把车漆改成珠光白，其他不动”就只改车漆。保留“沿用上一张”“恢复三轮前的配色”等指代，不猜测历史图片的内容。
原文已经准确时可以少改。只输出整理好的提示词正文，不解释、不生图。
```

## 改了哪些文件

| 文件 | 它负责什么 |
| --- | --- |
| [SettingModelPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPage.kt) | 在已有模型设置页增加增强模型的选择和清除入口。 |
| [SettingModelPromptPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPromptPage.kt) | 把两份规则接入原来的编辑框、自动保存和单项重置操作。 |
| [ModelList.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt) | 让增强模型选择框的普通列表和收藏列表都能筛掉不合适的模型。 |
| [Settings.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt) | 给旧数据补上两份规则的默认值，并把尚未选择的模型设为空。 |
| [PreferencesStore.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt) | 给模型和两份规则各留一个独立存储位置。 |
| [SettingsFields.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsFields.kt) | 负责把这三项分别写入手机存储、重新读回，或清除对应项。 |
| [SettingsExt.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsExt.kt) | 统一判断模型是否支持读入文字和输出文字。 |
| [PromptEnhancementPrompts.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/prompts/PromptEnhancementPrompts.kt) | 集中保存本说明中逐字列出的两份内置规则。 |
| [PromptEnhancement.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/PromptEnhancement.kt) | 按入口取规则，调用同一个文本模型，从回复里拿出草稿正文。 |
| [PromptEnhancementButton.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/PromptEnhancementButton.kt) | 两个页面共用这个按钮，处理等待、回填、冲突预览、失败和撤销。 |
| [ChatInput.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt) | 在聊天输入工具栏放入按钮，并识别普通输入框和全屏编辑器里的文字修改。 |
| [ChatInputState.kt](../app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt) | 辨别是在继续编辑同一份聊天草稿，还是已经清空或换了一份。 |
| [ChatPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt) | 从分享文字或聊天建议替换整份草稿时，结束旧草稿的增强操作。 |
| [MediaCreationComposer.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationComposer.kt) | 把按钮放进生图工具栏，为窄屏保留可横滑的工具区和固定的生成按钮。 |
| [MediaCreationVM.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationVM.kt) | 跟踪媒体草稿的输入、清空、生成和历史复用，让结果回到正确的草稿。 |
| [UsageRecorder.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/UsageRecorder.kt) | 给增强请求的模型用量增加独立的统计分类。 |
| [StatsPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsPage.kt) | 在统计页显示这类用量的名称。 |
| [中文界面文案](../app/src/main/res/values-zh/strings.xml) | 保存按钮、设置路径、编辑冲突和错误反馈的中文文字。 |
| [默认界面文案](../app/src/main/res/values/strings.xml) | 保存相同操作的英文文字，供默认语言界面使用。 |

以后同步上游时，最值得先看聊天工具栏和聊天草稿的发送、替换入口，其次是媒体输入框和媒体草稿的载入、复用入口。这些地方决定按钮放在哪里，以及旧结果什么时候作废。如果上游改版默认模型设置，再核对「模型」「提示词」两个标签和模型选择列表。两份规则和模型调用集中放在独立文件里。

## 实际验证

- 自用 GitHub Debug 变体的 Kotlin 编译通过，执行的是 `:app:compileGithubDebugKotlin`。
- `:app:testGithubDebugUnitTest` 本轮选跑 9 个测试类，最终 **37 项通过，0 项失败、0 项错误、0 项跳过**。下表是最终一次执行的分项结果。
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
