package com.subbrain.zerotouch.ui.context

import com.subbrain.zerotouch.api.ContextProfile
import com.subbrain.zerotouch.api.ContextProfileRequest

data class ContextProfileDraft(
    val profileName: String = "",
    val ownerName: String = "",
    val roleTitle: String = "",
    val identitySummary: String = "",
    val workspaceSummary: String = "",
    val deviceSummary: String = "",
    val environmentSummary: String = "",
    val analysisGoal: String = "",
    val analysisNotes: String = ""
) {
    companion object {
        fun fromProfile(
            profile: ContextProfile?,
            workspaceName: String?,
            ownerName: String?
        ): ContextProfileDraft {
            if (profile == null) {
                return ContextProfileDraft(
                    profileName = workspaceName.orEmpty(),
                    ownerName = ownerName.orEmpty()
                )
            }

            return ContextProfileDraft(
                profileName = profile.profile_name.orEmpty(),
                ownerName = profile.owner_name.orEmpty(),
                roleTitle = profile.role_title.orEmpty(),
                environmentSummary = profile.environment.orEmpty(),
                workspaceSummary = profile.usage_scenario.orEmpty(),
                analysisGoal = profile.goal.orEmpty()
            )
        }
    }
}

fun ContextProfileDraft.toRequest(): ContextProfileRequest {
    return ContextProfileRequest(
        profile_name = profileName.trim().ifBlank { null },
        owner_name = ownerName.trim().ifBlank { null },
        role_title = roleTitle.trim().ifBlank { null },
        environment = environmentSummary.trim().ifBlank { null },
        usage_scenario = workspaceSummary.trim().ifBlank { null },
        goal = analysisGoal.trim().ifBlank { null },
        reference_materials = emptyList(),
        glossary = emptyList(),
        prompt_preamble = buildPromptPreamble()
    )
}

private fun ContextProfileDraft.buildPromptPreamble(): String? {
    val lines = listOf(
        identitySummary.trim().takeIf { it.isNotBlank() }?.let { "Identity: $it" },
        roleTitle.trim().takeIf { it.isNotBlank() }?.let { "Role: $it" },
        workspaceSummary.trim().takeIf { it.isNotBlank() }?.let { "Workspace: $it" },
        deviceSummary.trim().takeIf { it.isNotBlank() }?.let { "Device: $it" },
        environmentSummary.trim().takeIf { it.isNotBlank() }?.let { "Environment: $it" },
        analysisGoal.trim().takeIf { it.isNotBlank() }?.let { "Goal: $it" },
        analysisNotes.trim().takeIf { it.isNotBlank() }?.let { "Notes: $it" }
    ).filterNotNull()

    if (lines.isEmpty()) return null
    return lines.joinToString("\n")
}
