package com.subbrain.zerotouch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import com.subbrain.zerotouch.api.ZeroTouchApi
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.ui.components.SideDetailDrawer
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtCardRowDivider
import com.subbrain.zerotouch.ui.theme.ZtFilterBorder
import com.subbrain.zerotouch.ui.theme.ZtFilterSelected
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtSuccess
import kotlinx.coroutines.launch

/**
 * Settings drawer — refined layout with grouped sections and better spacing.
 */
@Composable
fun SettingsSheet(
    deviceId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var asrProvider by remember {
        mutableStateOf(
            AmbientPreferences.getAsrProvider(context)
                .takeUnless { it == "cohere" }
                ?: "azure"
        )
    }
    var llmProvider by remember { mutableStateOf(AmbientPreferences.getLlmProvider(context)) }
    var llmModel by remember { mutableStateOf(AmbientPreferences.getLlmModel(context)) }
    var vadEngine by remember { mutableStateOf(AmbientPreferences.getVadEngine(context)) }
    var ambientAudioSource by remember {
        mutableStateOf(AmbientPreferences.getAmbientAudioSource(context))
    }
    var hpfEnabled by remember {
        mutableStateOf(AmbientPreferences.isHighPassFilterEnabled(context))
    }
    val api = remember { ZeroTouchApi() }
    val scope = rememberCoroutineScope()

    // Sync settings from server
    androidx.compose.runtime.LaunchedEffect(deviceId) {
        if (AmbientPreferences.getAsrProvider(context) == "cohere") {
            AmbientPreferences.setAsrProvider(context, "azure")
        }
        try {
            val remote = api.getDeviceSettings(deviceId)
            val remoteProvider = remote.llm_provider?.trim().orEmpty()
            val remoteModel = remote.llm_model?.trim().orEmpty()
            if (remoteProvider.isNotEmpty()) {
                llmProvider = remoteProvider
                AmbientPreferences.setLlmProvider(context, remoteProvider)
            }
            if (remoteModel.isNotEmpty()) {
                llmModel = remoteModel
                AmbientPreferences.setLlmModel(context, remoteModel)
            }
        } catch (e: Exception) {
            Log.w("SettingsSheet", "Failed to sync device settings", e)
        }
    }

    SideDetailDrawer(
        title = "Settings",
        onClose = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // === Recording ===
            SectionHeader(icon = Icons.Outlined.Mic, title = "録音")

            var sensitivity by remember { mutableFloatStateOf(0.5f) }
            SettingsRow(
                title = "感度",
                subtitle = "VAD検出しきい値"
            ) {
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            val vadSubtitle = when (vadEngine) {
                AmbientPreferences.VAD_ENGINE_SILERO -> "Silero — モデルベース"
                AmbientPreferences.VAD_ENGINE_WEBRTC -> "WebRTC — 互換モード"
                else -> "しきい値 — 軽量デフォルト"
            }
            SettingsRow(
                title = "VADエンジン",
                subtitle = vadSubtitle
            ) {
                ChipGroup {
                    SettingsChip(
                        label = "しきい値",
                        selected = vadEngine == AmbientPreferences.VAD_ENGINE_THRESHOLD,
                        onClick = {
                            vadEngine = AmbientPreferences.VAD_ENGINE_THRESHOLD
                            AmbientPreferences.setVadEngine(context, AmbientPreferences.VAD_ENGINE_THRESHOLD)
                        }
                    )
                    SettingsChip(
                        label = "Silero",
                        selected = vadEngine == AmbientPreferences.VAD_ENGINE_SILERO,
                        onClick = {
                            vadEngine = AmbientPreferences.VAD_ENGINE_SILERO
                            AmbientPreferences.setVadEngine(context, AmbientPreferences.VAD_ENGINE_SILERO)
                        }
                    )
                }
            }

            var minLength by remember { mutableFloatStateOf(3f) }
            SettingsRow(
                title = "最小長さ",
                subtitle = "${minLength.toInt()}秒 — これ未満は破棄"
            ) {
                Slider(
                    value = minLength,
                    onValueChange = { minLength = it },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            var autoTranscribe by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "自動文字起こし",
                subtitle = "録音後に文字起こし",
                checked = autoTranscribe,
                onCheckedChange = { autoTranscribe = it }
            )

            SettingsDivider()

            // === Transcription ===
            SectionHeader(icon = Icons.Outlined.Language, title = "文字起こし")
            val providerSubtitle = when (asrProvider) {
                "deepgram" -> "Deepgram nova-3 — 高速・整形あり"
                "azure" -> "Azure Speech Service — 既存キー流用"
                else -> "Speechmatics batch — 話者分離・エンティティ"
            }
            SettingsRow(
                title = "ASRプロバイダー",
                subtitle = providerSubtitle
            ) {
                ChipGroup {
                    SettingsChip(
                        label = "Speechmatics",
                        selected = asrProvider == "speechmatics",
                        onClick = {
                            asrProvider = "speechmatics"
                            AmbientPreferences.setAsrProvider(context, "speechmatics")
                        }
                    )
                    SettingsChip(
                        label = "Deepgram",
                        selected = asrProvider == "deepgram",
                        onClick = {
                            asrProvider = "deepgram"
                            AmbientPreferences.setAsrProvider(context, "deepgram")
                        }
                    )
                    SettingsChip(
                        label = "Azure",
                        selected = asrProvider == "azure",
                        onClick = {
                            asrProvider = "azure"
                            AmbientPreferences.setAsrProvider(context, "azure")
                        }
                    )
                    SettingsChip(
                        label = "Cohere",
                        selected = false,
                        enabled = false,
                        onClick = {}
                    )
                }
            }
            Text(
                text = "Cohere は保留中です。現在の ambient 録音形式は m4a のため、そのままでは利用できません。",
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption,
                modifier = Modifier.padding(top = 6.dp)
            )

            SettingsDivider()

            // === LLM ===
            SectionHeader(icon = Icons.Outlined.Psychology, title = "LLM")
            val llmSubtitle = when (llmModel) {
                "gpt-4.1-nano" -> "GPT-4.1 nano — 軽量"
                "gpt-4.1-mini" -> "GPT-4.1 mini — バランス"
                "gpt-4.1" -> "GPT-4.1 — 高い推論力"
                "gpt-4o-mini" -> "GPT-4o mini — 高速"
                else -> "OpenAI $llmModel"
            }
            SettingsRow(
                title = "LLMモデル",
                subtitle = llmSubtitle
            ) {
                ChipGroup {
                    SettingsChip(
                        label = "4.1 nano",
                        selected = llmModel == "gpt-4.1-nano",
                        onClick = {
                            llmProvider = "openai"; llmModel = "gpt-4.1-nano"
                            AmbientPreferences.setLlmProvider(context, llmProvider)
                            AmbientPreferences.setLlmModel(context, llmModel)
                            scope.launch { api.updateDeviceSettings(deviceId, llmProvider, llmModel) }
                        }
                    )
                    SettingsChip(
                        label = "4.1 mini",
                        selected = llmModel == "gpt-4.1-mini",
                        onClick = {
                            llmProvider = "openai"; llmModel = "gpt-4.1-mini"
                            AmbientPreferences.setLlmProvider(context, llmProvider)
                            AmbientPreferences.setLlmModel(context, llmModel)
                            scope.launch { api.updateDeviceSettings(deviceId, llmProvider, llmModel) }
                        }
                    )
                    SettingsChip(
                        label = "4.1",
                        selected = llmModel == "gpt-4.1",
                        onClick = {
                            llmProvider = "openai"; llmModel = "gpt-4.1"
                            AmbientPreferences.setLlmProvider(context, llmProvider)
                            AmbientPreferences.setLlmModel(context, llmModel)
                            scope.launch { api.updateDeviceSettings(deviceId, llmProvider, llmModel) }
                        }
                    )
                }
            }

            SettingsDivider()

            // === Ambient ===
            SectionHeader(icon = Icons.Outlined.GraphicEq, title = "アンビエント")
            val ambientSubtitle = when (ambientAudioSource) {
                "voice_recognition" -> "音声認識 — システムレベル"
                else -> "マイク — 生音"
            }
            SettingsRow(
                title = "音声ソース",
                subtitle = ambientSubtitle
            ) {
                ChipGroup {
                    SettingsChip(
                        label = "マイク",
                        selected = ambientAudioSource == "mic",
                        onClick = {
                            ambientAudioSource = "mic"
                            AmbientPreferences.setAmbientAudioSource(context, "mic")
                        }
                    )
                    SettingsChip(
                        label = "音声認識",
                        selected = ambientAudioSource == "voice_recognition",
                        onClick = {
                            ambientAudioSource = "voice_recognition"
                            AmbientPreferences.setAmbientAudioSource(context, "voice_recognition")
                        }
                    )
                }
            }

            SettingsToggleRow(
                title = "ハイパスフィルター",
                subtitle = "低周波ノイズを低減",
                checked = hpfEnabled,
                onCheckedChange = {
                    hpfEnabled = it
                    AmbientPreferences.setHighPassFilterEnabled(context, it)
                }
            )

            SettingsDivider()

            // === Device ===
            SectionHeader(icon = Icons.Outlined.Storage, title = "デバイス")

            SettingsRow(
                title = "デバイスID",
                subtitle = deviceId.take(8) + "..."
            )

            SettingsRow(
                title = "APIステータス",
                subtitle = "接続済み",
                trailingColor = ZtSuccess
            )

            SettingsDivider()

            // === About ===
            SectionHeader(icon = Icons.Outlined.Info, title = "情報")

            SettingsRow(
                title = "現在のチューニング",
                subtitle = "録音は通常 5 秒無音で区切り、2 分を超える連続会話は 2.5 秒無音で区切ります。1 セッションの上限は 10 分です。Topic は 30 秒無発言で確定します。これらの値は今後調整する可能性があります。"
            )

            SettingsRow(
                title = "ZeroTouch",
                subtitle = "バージョン 0.2.0 — リデザイン"
            )
        }
    }
}

// --- Reusable settings components ---

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = ZtCaption
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ZtCaption
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailingColor: androidx.compose.ui.graphics.Color? = null,
    content: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtOnSurfaceVariant
                    )
                }
                if (trailingColor != null) {
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = trailingColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "正常",
                            style = MaterialTheme.typography.labelSmall,
                            color = trailingColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            content?.invoke()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun ChipGroup(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ZtFilterSelected,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = ZtOnSurfaceVariant
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = ZtFilterBorder,
            selectedBorderColor = ZtOnSurfaceVariant.copy(alpha = 0.4f),
            borderWidth = 0.5.dp,
            selectedBorderWidth = 0.5.dp
        ),
        shape = RoundedCornerShape(6.dp)
    )
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = ZtCardRowDivider, thickness = 0.5.dp)
    Spacer(Modifier.height(2.dp))
}
