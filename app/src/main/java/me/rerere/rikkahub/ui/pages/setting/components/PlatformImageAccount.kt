package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.imagehistory.siwcHistoryModels
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalSettings

internal fun CardGroupScope.platformImageAccountItems(
    setting: MediaGenerationProviderSetting.OpenAIPlatform,
    onChange: (MediaGenerationProviderSetting) -> Unit,
) {
    formItem(
        headlineContent = { Text("OpenAI Platform API Key") },
        supportingContent = { Text("生成和编辑图片使用此 Key 所属项目的 Platform API 额度。") },
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onChange(setting.copy(apiKey = it.trim())) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
    formItem(
        headlineContent = { Text("历史理解模型（可选）") },
        supportingContent = { Text("理解历史版本时使用已登录的 SIWC 账号，消耗 ChatGPT 套餐额度。手动选图可直接编辑。") },
    ) {
        val providers = LocalSettings.current.providers
        val models = remember(providers) { siwcHistoryModels(providers) }
        val selected = models.find { (_, model) -> model.id == setting.historyModelId }
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    selected?.let { (provider, model) -> "${provider.name} · ${model.displayName.ifBlank { model.modelId }}" }
                        ?: if (setting.historyModelId == null) "关闭历史理解" else "已选模型不可用",
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("关闭历史理解") },
                    onClick = {
                        expanded = false
                        onChange(setting.copy(historyModelId = null))
                    },
                )
                models.forEach { (provider, model) ->
                    DropdownMenuItem(
                        text = { Text("${provider.name} · ${model.displayName.ifBlank { model.modelId }}") },
                        onClick = {
                            expanded = false
                            onChange(setting.copy(historyModelId = model.id))
                        },
                    )
                }
            }
        }
        if (models.isEmpty()) {
            Text("请先在聊天提供商中通过 SIWC 登录，并启用支持图片输入的聊天模型。")
        }
    }
}
