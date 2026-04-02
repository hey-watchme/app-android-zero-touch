package com.subbrain.zerotouch.ui.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subbrain.zerotouch.ui.components.SideDetailDrawer

@Composable
fun ContextSettingsSheet(
    initialDraft: ContextProfileDraft,
    isSaving: Boolean,
    onSave: (ContextProfileDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(initialDraft) }

    LaunchedEffect(initialDraft) {
        draft = initialDraft
    }

    SideDetailDrawer(
        title = "Context Settings",
        onClose = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Account")
            OutlinedTextField(
                value = draft.ownerName,
                onValueChange = { draft = draft.copy(ownerName = it) },
                label = { Text("あなたの名前") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.roleTitle,
                onValueChange = { draft = draft.copy(roleTitle = it) },
                label = { Text("役割 / 肩書き") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.identitySummary,
                onValueChange = { draft = draft.copy(identitySummary = it) },
                label = { Text("あなたについて") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            SectionTitle("Workspace")
            OutlinedTextField(
                value = draft.profileName,
                onValueChange = { draft = draft.copy(profileName = it) },
                label = { Text("ワークスペース名") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.workspaceSummary,
                onValueChange = { draft = draft.copy(workspaceSummary = it) },
                label = { Text("ワークスペースの説明") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            SectionTitle("Device")
            OutlinedTextField(
                value = draft.deviceSummary,
                onValueChange = { draft = draft.copy(deviceSummary = it) },
                label = { Text("デバイスの設置場所と用途") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            SectionTitle("Environment")
            OutlinedTextField(
                value = draft.environmentSummary,
                onValueChange = { draft = draft.copy(environmentSummary = it) },
                label = { Text("環境コンテクスト") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            SectionTitle("Analysis")
            OutlinedTextField(
                value = draft.analysisGoal,
                onValueChange = { draft = draft.copy(analysisGoal = it) },
                label = { Text("このアプリで把握したいこと") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            OutlinedTextField(
                value = draft.analysisNotes,
                onValueChange = { draft = draft.copy(analysisNotes = it) },
                label = { Text("補足メモ (任意)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onSave(draft) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaving) "保存中..." else "保存する")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold
    )
}
