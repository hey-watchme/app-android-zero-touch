package com.subbrain.zerotouch.ui.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ContextOnboardingScreen(
    initialDraft: ContextProfileDraft,
    isSaving: Boolean,
    onSkip: () -> Unit,
    onComplete: (ContextProfileDraft) -> Unit
) {
    var draft by remember { mutableStateOf(initialDraft) }
    var stepIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(initialDraft) {
        draft = initialDraft
    }

    val steps = listOf(
        "あなたについて",
        "ワークスペース",
        "デバイス",
        "環境",
        "分析"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Context Onboarding",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "${stepIndex + 1} / ${steps.size}  ${steps[stepIndex]}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(8.dp))

        when (stepIndex) {
            0 -> AccountStep(draft = draft, onDraftChange = { draft = it })
            1 -> WorkspaceStep(draft = draft, onDraftChange = { draft = it })
            2 -> DeviceStep(draft = draft, onDraftChange = { draft = it })
            3 -> EnvironmentStep(draft = draft, onDraftChange = { draft = it })
            4 -> AnalysisStep(draft = draft, onDraftChange = { draft = it })
        }

        Spacer(Modifier.height(12.dp))

        RowActions(
            isSaving = isSaving,
            stepIndex = stepIndex,
            lastStep = steps.lastIndex,
            onBack = { if (stepIndex > 0) stepIndex -= 1 },
            onNext = { if (stepIndex < steps.lastIndex) stepIndex += 1 },
            onSkip = onSkip,
            onComplete = { onComplete(draft) }
        )
    }
}

@Composable
private fun RowActions(
    isSaving: Boolean,
    stepIndex: Int,
    lastStep: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (stepIndex > 0) {
            OutlinedButton(
                onClick = onBack,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("戻る")
            }
        }

        if (stepIndex < lastStep) {
            Button(
                onClick = onNext,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("次へ")
            }
        } else {
            Button(
                onClick = onComplete,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaving) "保存中..." else "保存して完了")
            }
        }

        OutlinedButton(
            onClick = onSkip,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("あとで設定する")
        }
    }
}

@Composable
private fun AccountStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.ownerName,
            onValueChange = { onDraftChange(draft.copy(ownerName = it)) },
            label = { Text("あなたの名前") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.roleTitle,
            onValueChange = { onDraftChange(draft.copy(roleTitle = it)) },
            label = { Text("役割 / 肩書き") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.identitySummary,
            onValueChange = { onDraftChange(draft.copy(identitySummary = it)) },
            label = { Text("あなたについて") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
    }
}

@Composable
private fun WorkspaceStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.profileName,
            onValueChange = { onDraftChange(draft.copy(profileName = it)) },
            label = { Text("ワークスペース名") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.workspaceSummary,
            onValueChange = { onDraftChange(draft.copy(workspaceSummary = it)) },
            label = { Text("ワークスペースの説明") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
    }
}

@Composable
private fun DeviceStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.deviceSummary,
            onValueChange = { onDraftChange(draft.copy(deviceSummary = it)) },
            label = { Text("デバイスの設置場所と用途") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
    }
}

@Composable
private fun EnvironmentStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.environmentSummary,
            onValueChange = { onDraftChange(draft.copy(environmentSummary = it)) },
            label = { Text("環境コンテクスト") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
    }
}

@Composable
private fun AnalysisStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.analysisGoal,
            onValueChange = { onDraftChange(draft.copy(analysisGoal = it)) },
            label = { Text("このアプリで把握したいこと") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = draft.analysisNotes,
            onValueChange = { onDraftChange(draft.copy(analysisNotes = it)) },
            label = { Text("補足メモ (任意)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
}
