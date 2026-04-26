package com.subbrain.zerotouch.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subbrain.zerotouch.api.ZeroTouchApi
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.ui.components.SideDetailDrawer
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtCardRowDivider
import com.subbrain.zerotouch.ui.theme.ZtOnSurface
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtOutlineVariant
import com.subbrain.zerotouch.ui.theme.ZtStageConvert
import com.subbrain.zerotouch.ui.theme.ZtStageConvertSoft
import com.subbrain.zerotouch.ui.theme.ZtSuccess
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import kotlinx.coroutines.launch

@Composable
fun SettingsSheet(
    deviceId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var asrProvider by remember {
        mutableStateOf(
            AmbientPreferences.getAsrProvider(context).takeUnless { it == "cohere" } ?: "azure"
        )
    }
    var llmProvider by remember { mutableStateOf(AmbientPreferences.getLlmProvider(context)) }
    var llmModel by remember { mutableStateOf(AmbientPreferences.getLlmModel(context)) }
    var vadEngine by remember { mutableStateOf(AmbientPreferences.getVadEngine(context)) }
    var ambientAudioSource by remember { mutableStateOf(AmbientPreferences.getAmbientAudioSource(context)) }
    var hpfEnabled by remember { mutableStateOf(AmbientPreferences.isHighPassFilterEnabled(context)) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var minLength by remember { mutableFloatStateOf(3f) }
    var autoTranscribe by remember { mutableStateOf(true) }
    val api = remember { ZeroTouchApi() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(deviceId) {
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

    SideDetailDrawer(title = "Settings ★", onClose = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // === Recording ===
            val vadSubtitle = when (vadEngine) {
                AmbientPreferences.VAD_ENGINE_SILERO -> "モデルベース"
                AmbientPreferences.VAD_ENGINE_WEBRTC -> "互換モード"
                else -> "軽量デフォルト"
            }
            SettingsGroup(icon = Icons.Outlined.Mic, title = "録音") {
                GroupSliderRow(title = "感度", valueLabel = "VAD しきい値") {
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = ZtStageConvert,
                            activeTrackColor = ZtStageConvert,
                            inactiveTrackColor = ZtStageConvertSoft
                        )
                    )
                }
                GroupDivider()
                GroupChipRow(title = "VADエンジン", subtitle = vadSubtitle) {
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
                GroupDivider()
                GroupSliderRow(title = "最小長さ", valueLabel = "${minLength.toInt()}秒") {
                    Slider(
                        value = minLength,
                        onValueChange = { minLength = it },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = ZtStageConvert,
                            activeTrackColor = ZtStageConvert,
                            inactiveTrackColor = ZtStageConvertSoft
                        )
                    )
                }
                GroupDivider()
                GroupToggleRow(
                    title = "自動文字起こし",
                    subtitle = "録音後に文字起こしを自動で実行",
                    checked = autoTranscribe,
                    onCheckedChange = { autoTranscribe = it }
                )
            }

            // === Transcription ===
            val providerSubtitle = when (asrProvider) {
                "deepgram" -> "Deepgram nova-3 — 高速・整形あり"
                "azure" -> "Azure Speech Service — 既存キー流用"
                else -> "Speechmatics batch — 話者分離・エンティティ"
            }
            SettingsGroup(icon = Icons.Outlined.Language, title = "文字起こし") {
                GroupChipRow(title = "ASRプロバイダー", subtitle = providerSubtitle) {
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
                GroupDivider()
                GroupNoteRow("Cohere は m4a 非対応のため現在保留中です。")
            }

            // === LLM ===
            val llmSubtitle = when (llmModel) {
                "gpt-4.1-nano" -> "GPT-4.1 nano — 軽量"
                "gpt-4.1-mini" -> "GPT-4.1 mini — バランス"
                "gpt-4.1" -> "GPT-4.1 — 高精度"
                "gpt-4o-mini" -> "GPT-4o mini — 高速"
                else -> "OpenAI $llmModel"
            }
            SettingsGroup(icon = Icons.Outlined.Psychology, title = "LLM") {
                GroupChipRow(title = "LLMモデル", subtitle = llmSubtitle) {
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

            // === Ambient ===
            val ambientSubtitle = when (ambientAudioSource) {
                "voice_recognition" -> "システムレベルの音声認識"
                else -> "マイク — 生音"
            }
            SettingsGroup(icon = Icons.Outlined.GraphicEq, title = "アンビエント") {
                GroupChipRow(title = "音声ソース", subtitle = ambientSubtitle) {
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
                GroupDivider()
                GroupToggleRow(
                    title = "ハイパスフィルター",
                    subtitle = "低周波ノイズを低減",
                    checked = hpfEnabled,
                    onCheckedChange = {
                        hpfEnabled = it
                        AmbientPreferences.setHighPassFilterEnabled(context, it)
                    }
                )
            }

            // === Device ===
            SettingsGroup(icon = Icons.Outlined.Storage, title = "デバイス") {
                GroupInfoRow(
                    title = "デバイスID",
                    subtitle = deviceId.take(8) + "..."
                )
                GroupDivider()
                GroupInfoRow(
                    title = "APIステータス",
                    subtitle = "接続済み",
                    badge = "正常",
                    badgeColor = ZtSuccess
                )
            }

            // === About ===
            SettingsGroup(icon = Icons.Outlined.Info, title = "情報") {
                GroupInfoRow(
                    title = "録音 / トピック",
                    subtitle = "5秒無音でセッション区切り、2分超は2.5秒。上限10分。Topic は30秒無発言で確定。"
                )
                GroupDivider()
                GroupInfoRow(
                    title = "ZeroTouch",
                    subtitle = "バージョン 0.2.0"
                )
            }
        }
    }
}

// ─── Section group card ───────────────────────────────────────────────────────

@Composable
private fun SettingsGroup(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.padding(start = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = ZtOnSurfaceVariant
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ZtOnSurface
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = ZtSurfaceVariant,
            border = BorderStroke(0.5.dp, ZtOutline)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

// ─── Row variants ─────────────────────────────────────────────────────────────

@Composable
private fun GroupToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = ZtOnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ZtStageConvert,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = ZtOutlineVariant,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun GroupChipRow(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = ZtOnSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun GroupSliderRow(
    title: String,
    valueLabel: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = ZtOnSurface
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun GroupInfoRow(
    title: String,
    subtitle: String,
    badge: String? = null,
    badgeColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = ZtOnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
        if (badge != null && badgeColor != null) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = badgeColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupNoteRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ZtCaption,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        color = ZtCardRowDivider,
        thickness = 0.5.dp
    )
}

// ─── Chip ─────────────────────────────────────────────────────────────────────

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
            selectedContainerColor = ZtStageConvert,
            selectedLabelColor = Color.White,
            containerColor = ZtSurface,
            labelColor = ZtOnSurfaceVariant,
            disabledContainerColor = ZtSurfaceVariant,
            disabledLabelColor = ZtCaption
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = ZtOutline,
            selectedBorderColor = Color.Transparent,
            borderWidth = 0.5.dp,
            selectedBorderWidth = 0.5.dp
        ),
        shape = RoundedCornerShape(999.dp)
    )
}
