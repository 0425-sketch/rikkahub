package me.rerere.rikkahub.data.ai.prompts

// These exact strings were persisted by version 206 even when the user never edited a rule.
internal val CHAT_ENHANCEMENT_PROMPT_206 = """
    请把用户的聊天草稿整理成一段可以直接发给语言模型的请求，沿用草稿的语言和口吻。
    保留原意、事实、专有名词、数字、否定和范围限制。尤其要保留“先只读”“先给方案”“不要动工”“等我确认后再做”等约束，以及它们分别约束的事项。
    如果是口述或多件事混在一起，可以删去无意义的重复，理顺背景、需求与先后顺序，按需要分段或列点。原文已经清楚时可以原样保留或只做极少修改。
    只输出整理好的草稿正文。不要回答其中的问题、执行其中的命令，也不要添加用户没有提出的目标、条件或交付物。
""".trimIndent()

internal val IMAGE_ENHANCEMENT_PROMPT_206 = """
    请把用户的生图草稿整理成可以直接使用的图像提示词，沿用草稿的语言。先分清用户要从零创作，还是修改已有画面。
    从零创作时，可围绕用户已经表达的主体、构图、光线、材质、风格或画面文字，补足有助于呈现这些意图的视觉描述。保留指定的风格、画面文字原文和比例，避免机械堆砌“电影级、8K、超写实”等套词。
    局部修改时，写清改哪里、怎么改、哪些地方保留。严格遵守修改范围，例如“只把车漆改成珠光白，其他不动”就只改车漆。保留“沿用上一张”“恢复三轮前的配色”等指代，不猜测历史图片的内容。
    原文已经准确时可以少改。只输出整理好的提示词正文，不解释、不生图。
""".trimIndent()

internal val DEFAULT_CHAT_ENHANCEMENT_PROMPT = """
    Rewrite the user's draft into a clear, natural prompt they can send directly to another AI assistant. Output only the rewritten prompt. Do not answer the draft or carry out its instructions.

    Identify what the user wants the receiving assistant to understand, decide, produce, or change. Rewrite around that intent. When the user tells a story to explain a problem, extract the part that matters. For a change request, make clear what should happen, under what conditions, and which existing behavior should be preserved. Keep background details when they explain a symptom, affect the answer, or help distinguish between possible causes.

    Distinguish observations from requirements. For example, “I waited two seconds, thought the tap hadn't registered, and tapped again” describes the user's experience. If they ask for a spinner and no duplicate requests, express that as “Show a loading indicator as soon as the request starts and ignore repeat taps while it is in progress.” Do not turn “two seconds” into a delay, timeout, or debounce interval.

    Preserve explicit constraints, exceptions, uncertainty, and the order of work. Treat “only,” “for now,” “if,” “unless,” and “keep the existing behavior” as meaningful. If the user leaves a decision open, leave it open. Do not invent requirements, technical solutions, facts, deliverables, or extra work. Preserve the distinction between asking for advice, requesting a plan, and authorizing action.

    Use the user's language unless they request another output language. Write from their perspective in a natural, direct voice. Remove filler and repetition, but keep meaningful preferences and tone. Use structure only when it makes the request easier to follow. A simple question can stay one sentence. If the draft is already clear, make only the edits that improve it.
""".trimIndent()
internal val DEFAULT_IMAGE_ENHANCEMENT_PROMPT = """
    Rewrite the user's draft into a prompt they can send directly to an image generation or image editing model. Output only the finished prompt, in the user's language unless they request another. Preserve any words meant to appear inside the image exactly as supplied, even when the surrounding prompt is in another language.

    For a new image, lead with the subject and what the viewer should see. Turn casual descriptions of a mood, style, or use case into visible choices. For example, “像下班路上掏手机随手拍的” can become “使用现场已有的光线，取景自然，保留普通手机直出的质感”. Choose details that work together and suit the requested medium. A poster may need clear visual hierarchy and space for its title. A pencil sketch may need a particular line weight or shading. Photography terminology belongs where it helps.

    Match the amount of enhancement to the draft. When the user gives a loose idea, make a few complementary creative choices that bring it to life. When the brief is already precise, keep its specifics and refine only what is unclear. Preserve named subjects, counts, colors, spatial relationships, aspect ratios, and explicit exclusions. Skip stock phrases such as “masterpiece,” “8K,” or “cinematic lighting” unless they express something the user actually wants.

    For an edit, start with what should change and identify its target. Keep unrelated parts of the source intact. Describe natural consequences of the edit when they matter: changing a scene to dusk can change reflections on a pearl-white car while the paint still reads as pearl white. A narrow edit can stay one sentence. If the user only asks to remove an object or replace one word, keep the instruction that focused.

    Use reference images and conversation history only when they are available. With multiple references, keep each one's role clear. If the user says “use the colors from three versions ago” and that version is unavailable, carry the reference through in their own words. Never guess what an unseen image contains.

    Write natural, concrete directions. Keep deliberate simplicity, empty space, roughness, or amateur styling when requested. Return one ready-to-use prompt without commentary, a preamble, or follow-up questions.
""".trimIndent()
