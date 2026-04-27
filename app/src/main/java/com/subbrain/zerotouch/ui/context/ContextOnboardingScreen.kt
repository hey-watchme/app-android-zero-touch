package com.subbrain.zerotouch.ui.context

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subbrain.zerotouch.ui.theme.ZtBackground
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtOnBackground
import com.subbrain.zerotouch.ui.theme.ZtOnSurface
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtOutlineVariant
import com.subbrain.zerotouch.ui.theme.ZtPrimary
import com.subbrain.zerotouch.ui.theme.ZtStageConvert
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant

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
            .background(ZtBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Header ───────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "コンテクスト設定",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = ZtOnBackground
            )
            Text(
                text = "ワークスペースの前提を入力すると、会話分析の精度が上がります。",
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }

        // ── Step progress dots ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.indices.forEach { index ->
                val isCurrent = index == stepIndex
                val isDone = index < stepIndex
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isDone || isCurrent -> ZtStageConvert
                                else -> ZtOutlineVariant
                            }
                        )
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${stepIndex + 1} / ${steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption
            )
        }

        // ── Step title ────────────────────────────────────────────
        Text(
            text = steps[stepIndex],
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = ZtOnSurface
        )

        // ── Step content card ─────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            border = BorderStroke(0.5.dp, ZtOutline)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (stepIndex) {
                    0 -> AccountStep(draft = draft, onDraftChange = { draft = it })
                    1 -> WorkspaceStep(draft = draft, onDraftChange = { draft = it })
                    2 -> DeviceStep(draft = draft, onDraftChange = { draft = it })
                    3 -> EnvironmentStep(draft = draft, onDraftChange = { draft = it })
                    4 -> AnalysisStep(draft = draft, onDraftChange = { draft = it })
                }
            }
        }

        // ── Actions ───────────────────────────────────────────────
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
        // Primary action
        if (stepIndex < lastStep) {
            Button(
                onClick = onNext,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZtPrimary,
                    contentColor = Color.White
                )
            ) {
                Text("次へ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onComplete,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZtStageConvert,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (isSaving) "保存中..." else "保存して完了",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Back button
        if (stepIndex > 0) {
            OutlinedButton(
                onClick = onBack,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ZtOutline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZtOnSurface)
            ) {
                Text("戻る", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Skip link
        TextButton(
            onClick = onSkip,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "あとで設定する",
                style = MaterialTheme.typography.bodyMedium,
                color = ZtOnSurfaceVariant
            )
        }
    }
}

// ─── Field defaults ───────────────────────────────────────────────────────────

@Composable
private fun StepTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ZtStageConvert,
            unfocusedBorderColor = ZtOutline,
            focusedLabelColor = ZtStageConvert,
            unfocusedLabelColor = ZtCaption
        )
    )
}

// ─── Steps ────────────────────────────────────────────────────────────────────

@Composable
private fun AccountStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    StepTextField(
        value = draft.ownerName,
        onValueChange = { onDraftChange(draft.copy(ownerName = it)) },
        label = "あなたの名前"
    )
    StepTextField(
        value = draft.roleTitle,
        onValueChange = { onDraftChange(draft.copy(roleTitle = it)) },
        label = "役割 / 肩書き"
    )
    StepTextField(
        value = draft.identitySummary,
        onValueChange = { onDraftChange(draft.copy(identitySummary = it)) },
        label = "あなたについて",
        minLines = 3
    )
}

@Composable
private fun WorkspaceStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    StepTextField(
        value = draft.profileName,
        onValueChange = { onDraftChange(draft.copy(profileName = it)) },
        label = "ワークスペース名"
    )
    StepTextField(
        value = draft.workspaceSummary,
        onValueChange = { onDraftChange(draft.copy(workspaceSummary = it)) },
        label = "ワークスペースの説明",
        minLines = 3
    )
}

@Composable
private fun DeviceStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    StepTextField(
        value = draft.deviceSummary,
        onValueChange = { onDraftChange(draft.copy(deviceSummary = it)) },
        label = "デバイスの設置場所と用途",
        minLines = 3
    )
}

@Composable
private fun EnvironmentStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    StepTextField(
        value = draft.environmentSummary,
        onValueChange = { onDraftChange(draft.copy(environmentSummary = it)) },
        label = "環境コンテクスト",
        minLines = 3
    )
}

@Composable
private fun AnalysisStep(
    draft: ContextProfileDraft,
    onDraftChange: (ContextProfileDraft) -> Unit
) {
    StepTextField(
        value = draft.analysisGoal,
        onValueChange = { onDraftChange(draft.copy(analysisGoal = it)) },
        label = "このアプリで把握したいこと",
        minLines = 2
    )
    StepTextField(
        value = draft.analysisNotes,
        onValueChange = { onDraftChange(draft.copy(analysisNotes = it)) },
        label = "補足メモ (任意)",
        minLines = 2
    )
}
