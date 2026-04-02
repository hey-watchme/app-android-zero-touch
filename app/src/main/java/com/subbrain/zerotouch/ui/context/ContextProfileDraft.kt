package com.subbrain.zerotouch.ui.context

import com.subbrain.zerotouch.api.ContextProfile
import com.subbrain.zerotouch.api.ContextAccountContext
import com.subbrain.zerotouch.api.ContextAnalysisContext
import com.subbrain.zerotouch.api.ContextDeviceContext
import com.subbrain.zerotouch.api.ContextEnvironmentContext
import com.subbrain.zerotouch.api.ContextProfileRequest
import com.subbrain.zerotouch.api.ContextWorkspaceContext

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
        private const val PREFIX_IDENTITY = "Identity:"
        private const val PREFIX_ROLE = "Role:"
        private const val PREFIX_WORKSPACE = "Workspace:"
        private const val PREFIX_DEVICE = "Device:"
        private const val PREFIX_ENVIRONMENT = "Environment:"
        private const val PREFIX_GOAL = "Goal:"
        private const val PREFIX_NOTES = "Notes:"

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

            val promptFields = parsePromptPreamble(profile.prompt_preamble)
            val accountContext = profile.account_context
            val workspaceContext = profile.workspace_context
            val environmentContext = profile.environment_context
            val analysisContext = profile.analysis_context
            val deviceSummaryFromContext = profile.device_contexts
                .firstNotNullOfOrNull { it.summary?.trim()?.takeIf { value -> value.isNotBlank() } }

            return ContextProfileDraft(
                profileName = firstNotBlank(
                    profile.profile_name,
                    workspaceContext?.profile_name
                ).orEmpty().ifBlank {
                    workspaceName.orEmpty()
                },
                ownerName = firstNotBlank(
                    profile.owner_name,
                    accountContext?.owner_name,
                    ownerName
                ).orEmpty(),
                roleTitle = firstNotBlank(
                    profile.role_title,
                    accountContext?.role_title,
                    promptFields.roleTitle
                ).orEmpty(),
                identitySummary = firstNotBlank(
                    profile.identity_summary,
                    accountContext?.identity_summary,
                    promptFields.identitySummary
                ).orEmpty(),
                workspaceSummary = firstNotBlank(
                    profile.usage_scenario,
                    workspaceContext?.usage_scenario,
                    promptFields.workspaceSummary
                ).orEmpty(),
                deviceSummary = firstNotBlank(
                    profile.device_summary,
                    deviceSummaryFromContext,
                    promptFields.deviceSummary
                ).orEmpty(),
                environmentSummary = firstNotBlank(
                    profile.environment,
                    environmentContext?.summary,
                    promptFields.environmentSummary
                ).orEmpty(),
                analysisGoal = firstNotBlank(
                    profile.goal,
                    analysisContext?.goal,
                    promptFields.analysisGoal
                ).orEmpty(),
                analysisNotes = firstNotBlank(
                    profile.analysis_notes,
                    analysisContext?.notes,
                    promptFields.analysisNotes
                ).orEmpty()
            )
        }

        private fun firstNotBlank(vararg values: String?): String? {
            values.forEach { value ->
                val normalized = value?.trim().orEmpty()
                if (normalized.isNotBlank()) return normalized
            }
            return null
        }

        private data class PromptFields(
            val identitySummary: String? = null,
            val roleTitle: String? = null,
            val workspaceSummary: String? = null,
            val deviceSummary: String? = null,
            val environmentSummary: String? = null,
            val analysisGoal: String? = null,
            val analysisNotes: String? = null
        )

        private fun parsePromptPreamble(preamble: String?): PromptFields {
            if (preamble.isNullOrBlank()) return PromptFields()

            var identitySummary: String? = null
            var roleTitle: String? = null
            var workspaceSummary: String? = null
            var deviceSummary: String? = null
            var environmentSummary: String? = null
            var analysisGoal: String? = null
            var analysisNotes: String? = null

            preamble.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    when {
                        line.startsWith(PREFIX_IDENTITY) -> identitySummary = line.removePrefix(PREFIX_IDENTITY).trim()
                        line.startsWith(PREFIX_ROLE) -> roleTitle = line.removePrefix(PREFIX_ROLE).trim()
                        line.startsWith(PREFIX_WORKSPACE) -> workspaceSummary = line.removePrefix(PREFIX_WORKSPACE).trim()
                        line.startsWith(PREFIX_DEVICE) -> deviceSummary = line.removePrefix(PREFIX_DEVICE).trim()
                        line.startsWith(PREFIX_ENVIRONMENT) -> environmentSummary = line.removePrefix(PREFIX_ENVIRONMENT).trim()
                        line.startsWith(PREFIX_GOAL) -> analysisGoal = line.removePrefix(PREFIX_GOAL).trim()
                        line.startsWith(PREFIX_NOTES) -> analysisNotes = line.removePrefix(PREFIX_NOTES).trim()
                    }
                }

            return PromptFields(
                identitySummary = identitySummary,
                roleTitle = roleTitle,
                workspaceSummary = workspaceSummary,
                deviceSummary = deviceSummary,
                environmentSummary = environmentSummary,
                analysisGoal = analysisGoal,
                analysisNotes = analysisNotes
            )
        }
    }
}

fun ContextProfileDraft.toRequest(): ContextProfileRequest {
    val profileNameValue = profileName.trim().ifBlank { null }
    val ownerNameValue = ownerName.trim().ifBlank { null }
    val roleTitleValue = roleTitle.trim().ifBlank { null }
    val identitySummaryValue = identitySummary.trim().ifBlank { null }
    val workspaceSummaryValue = workspaceSummary.trim().ifBlank { null }
    val deviceSummaryValue = deviceSummary.trim().ifBlank { null }
    val environmentSummaryValue = environmentSummary.trim().ifBlank { null }
    val analysisGoalValue = analysisGoal.trim().ifBlank { null }
    val analysisNotesValue = analysisNotes.trim().ifBlank { null }

    val accountContext = if (
        ownerNameValue != null ||
        roleTitleValue != null ||
        identitySummaryValue != null
    ) {
        ContextAccountContext(
            owner_name = ownerNameValue,
            role_title = roleTitleValue,
            identity_summary = identitySummaryValue
        )
    } else {
        null
    }

    val workspaceContext = if (
        profileNameValue != null ||
        workspaceSummaryValue != null
    ) {
        ContextWorkspaceContext(
            profile_name = profileNameValue,
            usage_scenario = workspaceSummaryValue
        )
    } else {
        null
    }

    val deviceContexts = if (deviceSummaryValue != null) {
        listOf(ContextDeviceContext(summary = deviceSummaryValue))
    } else {
        emptyList()
    }

    val environmentContext = if (environmentSummaryValue != null) {
        ContextEnvironmentContext(summary = environmentSummaryValue)
    } else {
        null
    }

    val analysisContext = if (
        analysisGoalValue != null ||
        analysisNotesValue != null
    ) {
        ContextAnalysisContext(
            goal = analysisGoalValue,
            notes = analysisNotesValue
        )
    } else {
        null
    }

    return ContextProfileRequest(
        schema_version = 2,
        profile_name = profileNameValue,
        owner_name = ownerNameValue,
        role_title = roleTitleValue,
        identity_summary = identitySummaryValue,
        environment = environmentSummaryValue,
        usage_scenario = workspaceSummaryValue,
        goal = analysisGoalValue,
        analysis_notes = analysisNotesValue,
        account_context = accountContext,
        workspace_context = workspaceContext,
        device_contexts = deviceContexts,
        environment_context = environmentContext,
        analysis_context = analysisContext,
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
