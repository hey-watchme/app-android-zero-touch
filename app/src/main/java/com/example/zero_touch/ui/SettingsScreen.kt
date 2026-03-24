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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutline

/**
 * Mock settings bottom sheet.
 * Contains realistic-looking but non-functional controls.
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
    var ambientAudioSource by remember {
        mutableStateOf(AmbientPreferences.getAmbientAudioSource(context))
    }
    var hpfEnabled by remember {
        mutableStateOf(AmbientPreferences.isHighPassFilterEnabled(context))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(16.dp))

            // --- Recording Section ---
            SectionHeader("Recording")

            // Sensitivity slider (mock)
            var sensitivity by remember { mutableFloatStateOf(0.5f) }
            SettingsRow(
                icon = Icons.Outlined.Speed,
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

            // Min session length (mock)
            var minLength by remember { mutableFloatStateOf(3f) }
            SettingsRow(
                icon = Icons.Outlined.Timer,
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

            // Auto-transcribe toggle (mock)
            var autoTranscribe by remember { mutableStateOf(true) }
            SettingsToggleRow(
                icon = Icons.Outlined.Mic,
                title = "Auto-transcribe",
                subtitle = "Automatically transcribe after recording",
                checked = autoTranscribe,
                onCheckedChange = { autoTranscribe = it }
            )

            Spacer(Modifier.height(8.dp))

            SectionHeader("Transcription")
            val providerSubtitle = when (asrProvider) {
                "deepgram" -> "Deepgram (nova-3) — fast + smart format"
                else -> "Speechmatics (batch) — diarization + entities"
            }
            SettingsRow(
                icon = Icons.Outlined.Mic,
                title = "ASR Provider",
                subtitle = providerSubtitle
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = asrProvider == "speechmatics",
                        onClick = {
                            asrProvider = "speechmatics"
                            AmbientPreferences.setAsrProvider(context, "speechmatics")
                        },
                        label = { Text("Speechmatics") },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                    FilterChip(
                        selected = asrProvider == "deepgram",
                        onClick = {
                            asrProvider = "deepgram"
                            AmbientPreferences.setAsrProvider(context, "deepgram")
                        },
                        label = { Text("Deepgram") },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SectionHeader("Ambient")
            val ambientSubtitle = when (ambientAudioSource) {
                "voice_recognition" -> "Voice recognition stream — system-level input"
                else -> "Microphone input — raw ambient audio"
            }
            SettingsRow(
                icon = Icons.Outlined.Mic,
                title = "Audio source",
                subtitle = ambientSubtitle
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = ambientAudioSource == "mic",
                        onClick = {
                            ambientAudioSource = "mic"
                            AmbientPreferences.setAmbientAudioSource(context, "mic")
                        },
                        label = { Text("Mic") },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                    FilterChip(
                        selected = ambientAudioSource == "voice_recognition",
                        onClick = {
                            ambientAudioSource = "voice_recognition"
                            AmbientPreferences.setAmbientAudioSource(
                                context,
                                "voice_recognition"
                            )
                        },
                        label = { Text("Voice recognition") },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            SettingsToggleRow(
                icon = Icons.Outlined.Speed,
                title = "High-pass filter",
                subtitle = "Reduce low-frequency noise before transcription",
                checked = hpfEnabled,
                onCheckedChange = {
                    hpfEnabled = it
                    AmbientPreferences.setHighPassFilterEnabled(context, it)
                }
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = ZtOutline)
            Spacer(Modifier.height(8.dp))

            // --- Device Section ---
            SectionHeader("Device")

            SettingsRow(
                icon = Icons.Outlined.Storage,
                title = "Device ID",
                subtitle = deviceId.take(8) + "..."
            )

            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "API Status",
                subtitle = "Connected — api.hey-watch.me"
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = ZtOutline)
            Spacer(Modifier.height(8.dp))

            // --- About Section ---
            SectionHeader("About")

            SettingsRow(
                icon = null,
                title = "ZeroTouch",
                subtitle = "Version 0.1.0 — MVP"
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = ZtCaption,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    content: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = ZtOnSurfaceVariant
                    )
                }
                Column {
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
            }
            content?.invoke()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = ZtOnSurfaceVariant
                )
                Column {
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
