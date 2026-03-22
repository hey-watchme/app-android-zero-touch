package com.example.zero_touch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zero_touch.audio.VoiceMemoEngine
import java.io.File

@Composable
fun VoiceMemoScreen(
    modifier: Modifier = Modifier,
    onUpload: ((File) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val outputFile = remember(context) { File(context.filesDir, "voice_memo.m4a") }
    val engine = remember { VoiceMemoEngine() }

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isRecording by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasRecordPermission = granted
            message = if (granted) null else "Microphone permission required"
        }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                engine.release()
                isRecording = false
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.release()
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "ZeroTouch Recorder",
            style = MaterialTheme.typography.titleLarge,
        )

        if (!hasRecordPermission) {
            Text(
                text = "Microphone permission is required to record.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.semantics { testTag = "permission_button" },
            ) {
                Text("Grant Permission")
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val recordEnabled = hasRecordPermission && !isPlaying
            Button(
                enabled = recordEnabled,
                onClick = {
                    message = null
                    runCatching {
                        if (isRecording) {
                            engine.stopRecording()
                            isRecording = false
                            message = "Saved: ${outputFile.name}"
                        } else {
                            engine.startRecording(outputFile)
                            isRecording = true
                        }
                    }.onFailure {
                        engine.release()
                        isRecording = false
                        message = "Recording failed: ${it.message}"
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { testTag = "record_button" },
            ) {
                Text(if (isRecording) "Stop" else "Record")
            }

            val playEnabled = !isRecording && outputFile.exists()
            Button(
                enabled = playEnabled,
                onClick = {
                    message = null
                    runCatching {
                        if (isPlaying) {
                            engine.stopPlayback()
                            isPlaying = false
                        } else {
                            isPlaying = true
                            engine.startPlayback(outputFile) { isPlaying = false }
                        }
                    }.onFailure {
                        engine.release()
                        isPlaying = false
                        message = "Playback failed: ${it.message}"
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { testTag = "play_button" },
            ) {
                Text(if (isPlaying) "Stop" else "Play")
            }
        }

        // Upload button
        if (onUpload != null) {
            Button(
                enabled = !isRecording && !isPlaying && outputFile.exists(),
                onClick = {
                    message = "Uploading..."
                    onUpload(outputFile)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Upload & Analyze")
            }
        }

        Text(
            text = when {
                isRecording -> "Recording..."
                isPlaying -> "Playing..."
                else -> "Ready"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (message != null) {
            Text(
                text = message!!,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
