package com.example.zero_touch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.zero_touch.api.ZeroTouchApi
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtCardRowDivider
import com.example.zero_touch.ui.theme.ZtFilterBorder
import com.example.zero_touch.ui.theme.ZtFilterSelected
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutline
import com.example.zero_touch.ui.theme.ZtPrimary
import com.example.zero_touch.ui.theme.ZtSuccess
import kotlinx.coroutines.launch

/**
 * Settings bottom sheet — refined layout with grouped sections and better spacing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    deviceId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var asrProvider by remember { mutableStateOf(AmbientPreferences.getAsrProvider(context)) }
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            // === Recording ===
            SectionHeader(icon = Icons.Outlined.Mic, title = "Recording")

            var sensitivity by remember { mutableFloatStateOf(0.5f) }
            SettingsRow(
                title = "Sensitivity",
                subtitle = "VAD detection threshold"
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
                AmbientPreferences.VAD_ENGINE_SILERO -> "Silero — model-backed"
                AmbientPreferences.VAD_ENGINE_WEBRTC -> "WebRTC — compatibility mode"
                else -> "Threshold — lightweight default"
            }
            SettingsRow(
                title = "VAD engine",
                subtitle = vadSubtitle
            ) {
                ChipGroup {
                    SettingsChip(
                        label = "Threshold",
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
                title = "Minimum length",
                subtitle = "${minLength.toInt()}s — discard shorter clips"
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
                title = "Auto-transcribe",
                subtitle = "Transcribe after recording",
                checked = autoTranscribe,
                onCheckedChange = { autoTranscribe = it }
            )

            SettingsDivider()

            // === Transcription ===
            SectionHeader(icon = Icons.Outlined.Language, title = "Transcription")
            val providerSubtitle = when (asrProvider) {
                "deepgram" -> "Deepgram nova-3 — fast + smart format"
                else -> "Speechmatics batch — diarization + entities"
            }
            SettingsRow(
                title = "ASR Provider",
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
                }
            }

            SettingsDivider()

            // === LLM ===
            SectionHeader(icon = Icons.Outlined.Psychology, title = "LLM")
            val llmSubtitle = when (llmModel) {
                "gpt-4.1-nano" -> "GPT-4.1 nano — lightweight"
                "gpt-4.1-mini" -> "GPT-4.1 mini — balanced"
                "gpt-4.1" -> "GPT-4.1 — stronger reasoning"
                "gpt-4o-mini" -> "GPT-4o mini — fast"
                else -> "OpenAI $llmModel"
            }
            SettingsRow(
                title = "LLM Model",
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
            SectionHeader(icon = Icons.Outlined.GraphicEq, title = "Ambient")
            val ambientSubtitle = when (ambientAudioSource) {
                "voice_recognition" -> "Voice recognition — system-level"
                else -> "Microphone — raw ambient"
            }
            SettingsRow(
                title = "Audio source",
                subtitle = ambientSubtitle
            ) {
                ChipGroup {
                    SettingsChip(
                        label = "Mic",
                        selected = ambientAudioSource == "mic",
                        onClick = {
                            ambientAudioSource = "mic"
                            AmbientPreferences.setAmbientAudioSource(context, "mic")
                        }
                    )
                    SettingsChip(
                        label = "Voice recognition",
                        selected = ambientAudioSource == "voice_recognition",
                        onClick = {
                            ambientAudioSource = "voice_recognition"
                            AmbientPreferences.setAmbientAudioSource(context, "voice_recognition")
                        }
                    )
                }
            }

            SettingsToggleRow(
                title = "High-pass filter",
                subtitle = "Reduce low-frequency noise",
                checked = hpfEnabled,
                onCheckedChange = {
                    hpfEnabled = it
                    AmbientPreferences.setHighPassFilterEnabled(context, it)
                }
            )

            SettingsDivider()

            // === Device ===
            SectionHeader(icon = Icons.Outlined.Storage, title = "Device")

            SettingsRow(
                title = "Device ID",
                subtitle = deviceId.take(8) + "..."
            )

            SettingsRow(
                title = "API Status",
                subtitle = "Connected",
                trailingColor = ZtSuccess
            )

            SettingsDivider()

            // === About ===
            SectionHeader(icon = Icons.Outlined.Info, title = "About")

            SettingsRow(
                title = "ZeroTouch",
                subtitle = "Version 0.2.0 — Redesign"
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
                            text = "OK",
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
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ZtFilterSelected,
            selectedLabelColor = ZtPrimary,
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = ZtOnSurfaceVariant
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = ZtFilterBorder,
            selectedBorderColor = ZtPrimary.copy(alpha = 0.3f),
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
