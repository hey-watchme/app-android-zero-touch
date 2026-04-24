package com.subbrain.zerotouch.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

data class UploadResponse(
    val session_id: String,
    val s3_path: String,
    val status: String
)

data class SessionSummary(
    val id: String,
    val device_id: String,
    val status: String,
    val duration_seconds: Int? = null,
    val model_used: String? = null,
    val error_message: String? = null,
    val recorded_at: String? = null,
    val created_at: String,
    val updated_at: String
)

data class SessionDetail(
    val id: String,
    val device_id: String,
    val status: String,
    val s3_audio_path: String? = null,
    val duration_seconds: Int? = null,
    val transcription: String? = null,
    val transcription_metadata: Map<String, Any>? = null,
    val cards_result: Map<String, Any>? = null,
    val model_used: String? = null,
    val error_message: String? = null,
    val recorded_at: String? = null,
    val created_at: String,
    val updated_at: String
)

data class SessionListResponse(
    val sessions: List<SessionSummary>,
    val count: Int
)

data class TopicUtteranceSummary(
    val id: String,
    val topic_id: String? = null,
    val device_id: String,
    val status: String,
    val transcription: String? = null,
    val transcription_metadata: Map<String, Any>? = null,
    val duration_seconds: Int? = null,
    val recorded_at: String? = null,
    val created_at: String,
    val updated_at: String
)

data class TopicSummary(
    val id: String,
    val device_id: String,
    val topic_status: String,
    val live_title: String? = null,
    val live_summary: String? = null,
    val final_title: String? = null,
    val final_summary: String? = null,
    val utterance_count: Int? = null,
    val importance_level: Int? = null,
    val importance_reason: String? = null,
    val llm_provider: String? = null,
    val llm_model: String? = null,
    val start_at: String? = null,
    val end_at: String? = null,
    val last_utterance_at: String? = null,
    val updated_at: String? = null,
    val utterances: List<TopicUtteranceSummary> = emptyList()
)

data class TopicListResponse(
    val topics: List<TopicSummary>,
    val count: Int
)

data class FactSummary(
    val id: String,
    val topic_id: String,
    val device_id: String,
    val fact_text: String,
    val importance_level: Int? = null,
    val entities: List<Map<String, Any>>? = null,
    val categories: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val ttl_type: String? = null,
    val expires_at: String? = null,
    val source_cards: List<String> = emptyList(),
    val created_at: String? = null,
    val updated_at: String? = null
)

data class FactListResponse(
    val facts: List<FactSummary>,
    val count: Int
)

data class TopicBackfillResult(
    val target: Int = 0,
    val processed: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val limit: Int = 0
)

data class TopicBackfillResponse(
    val status: String,
    val result: TopicBackfillResult
)

data class TopicEvaluatePendingResponse(
    val status: String,
    val result: Map<String, Any>
)

data class DeviceSettings(
    val device_id: String,
    val llm_provider: String? = null,
    val llm_model: String? = null
)

data class ContextAccountContext(
    val owner_name: String? = null,
    val role_title: String? = null,
    val identity_summary: String? = null,
    val primary_roles: List<String>? = null,
    val product_summary: String? = null
)

data class WorkspaceKeyProject(
    val name: String = "",
    val type: String? = null,
    val summary: String? = null,
    val wiki_theme: String? = null
)

data class ContextWorkspaceContext(
    val profile_name: String? = null,
    val usage_scenario: String? = null,
    val workspace_summary: String? = null,
    val workspace_goals: List<String>? = null,
    val key_projects: List<WorkspaceKeyProject>? = null
)

data class ContextDeviceContext(
    val device_id: String? = null,
    val summary: String? = null
)

data class ContextEnvironmentContext(
    val summary: String? = null
)

data class ContextAnalysisContext(
    val goal: String? = null,
    val notes: String? = null,
    val analysis_objective: String? = null,
    val focus_topics: List<String>? = null,
    val ignore_topics: List<String>? = null
)

data class ContextProfile(
    val workspace_id: String,
    val schema_version: Int? = null,
    val profile_name: String? = null,
    val owner_name: String? = null,
    val role_title: String? = null,
    val identity_summary: String? = null,
    val environment: String? = null,
    val usage_scenario: String? = null,
    val goal: String? = null,
    val device_summary: String? = null,
    val analysis_notes: String? = null,
    val account_context: ContextAccountContext? = null,
    val workspace_context: ContextWorkspaceContext? = null,
    val device_contexts: List<ContextDeviceContext> = emptyList(),
    val environment_context: ContextEnvironmentContext? = null,
    val analysis_context: ContextAnalysisContext? = null,
    val onboarding_completed_at: String? = null,
    val reference_materials: List<Map<String, Any>> = emptyList(),
    val glossary: List<Map<String, Any>> = emptyList(),
    val prompt_preamble: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class ContextProfileEnvelope(
    val workspace_id: String,
    val profile: ContextProfile? = null
)

data class ContextProfileResponse(
    val status: String,
    val profile: ContextProfile? = null
)

data class ContextProfileRequest(
    val schema_version: Int? = null,
    val profile_name: String? = null,
    val owner_name: String? = null,
    val role_title: String? = null,
    val identity_summary: String? = null,
    val environment: String? = null,
    val usage_scenario: String? = null,
    val goal: String? = null,
    val analysis_notes: String? = null,
    val account_context: ContextAccountContext? = null,
    val workspace_context: ContextWorkspaceContext? = null,
    val device_contexts: List<ContextDeviceContext>? = null,
    val environment_context: ContextEnvironmentContext? = null,
    val analysis_context: ContextAnalysisContext? = null,
    val onboarding_completed_at: String? = null,
    val reference_materials: List<Map<String, Any>>? = null,
    val glossary: List<Map<String, Any>>? = null,
    val prompt_preamble: String? = null
)

data class OrganizationSummary(
    val id: String,
    val name: String,
    val slug: String? = null,
    val role: String? = null
)

data class OrganizationListResponse(
    val organizations: List<OrganizationSummary>,
    val count: Int
)

data class OrganizationMemberSummary(
    val id: String,
    val organization_id: String,
    val account_id: String,
    val role: String
)

data class OrganizationMembersResponse(
    val members: List<OrganizationMemberSummary>,
    val count: Int
)

data class AccountSummary(
    val id: String,
    val display_name: String,
    val email: String? = null,
    val avatar_url: String? = null,
    val external_auth_provider: String? = null,
    val external_auth_subject: String? = null
)

data class AccountListResponse(
    val accounts: List<AccountSummary>,
    val count: Int
)

data class AccountCreateResponse(
    val status: String,
    val account: AccountSummary? = null
)

data class WorkspaceMutationResponse(
    val status: String,
    val workspace: WorkspaceSummary? = null
)

data class WorkspaceSummary(
    val id: String,
    val owner_account_id: String? = null,
    val name: String,
    val slug: String? = null,
    val description: String? = null
)

data class WorkspaceListResponse(
    val workspaces: List<WorkspaceSummary>,
    val count: Int
)

data class DeviceSummary(
    val id: String,
    val workspace_id: String,
    val device_id: String,
    val display_name: String,
    val device_kind: String? = null,
    val source_type: String? = null,
    val platform: String? = null,
    val is_virtual: Boolean = false,
    val is_active: Boolean = true
)

data class DeviceListResponse(
    val devices: List<DeviceSummary>,
    val count: Int
)

data class DeviceMutationResponse(
    val status: String,
    val device: DeviceSummary? = null
)

data class Card(
    val type: String,
    val title: String,
    val content: String,
    val urgency: String? = null,
    val mentioned_by: String? = null,
    val context: String? = null
)

data class WikiPage(
    val id: String,
    val title: String,
    val body: String,
    val project_id: String? = null,
    val project_key: String? = null,
    val project_name: String? = null,
    val category: String? = null,
    val page_key: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val version: Int? = null,
    val source_fact_ids: List<String>? = null,
    val last_ingest_at: String? = null,
    val created_at: String,
    val updated_at: String
)

data class WikiPagesResponse(
    val device_id: String,
    val pages: List<WikiPage>,
    val wiki_available: Boolean = true
)

class ZeroTouchApi(
    private val baseUrl: String = "https://api.hey-watch.me"
) {
    companion object {
        private const val TAG = "ZeroTouchApi"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun uploadAudio(file: File, deviceId: String): UploadResponse = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$baseUrl/zerotouch/api/upload")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Upload failed: ${response.code} ${response.body?.string()}")
        }
        gson.fromJson(response.body!!.string(), UploadResponse::class.java)
    }

    suspend fun transcribe(
        sessionId: String,
        autoChain: Boolean = true,
        provider: String? = null,
        model: String? = null,
        language: String? = null
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/transcribe/$sessionId?auto_chain=$autoChain")
            if (!provider.isNullOrBlank()) append("&provider=$provider")
            if (!model.isNullOrBlank()) append("&model=$model")
            if (!language.isNullOrBlank()) append("&language=$language")
        }

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Transcribe failed: ${response.code} ${response.body?.string()}")
        }
        val type = object : TypeToken<Map<String, Any>>() {}.type
        gson.fromJson(response.body!!.string(), type)
    }

    suspend fun getSession(sessionId: String): SessionDetail = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/sessions/$sessionId"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Get session failed: ${response.code}")
        }
        gson.fromJson(body, SessionDetail::class.java)
    }

    suspend fun listFacts(
        deviceId: String? = null,
        workspaceId: String? = null,
        topicId: String? = null,
        limit: Int = 200,
        offset: Int = 0
    ): FactListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/facts?limit=$limit&offset=$offset")
            if (!deviceId.isNullOrBlank()) append("&device_id=$deviceId")
            if (!workspaceId.isNullOrBlank()) append("&workspace_id=$workspaceId")
            if (!topicId.isNullOrBlank()) append("&topic_id=$topicId")
        }
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("List facts failed: ${response.code}")
        }
        gson.fromJson(body, FactListResponse::class.java)
    }

    suspend fun deleteSession(sessionId: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/sessions/$sessionId"
        Log.d(TAG, "DELETE $url")
        val request = Request.Builder()
            .url(url)
            .delete()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "DELETE $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Delete session failed: ${response.code} $body")
        }
        val type = object : TypeToken<Map<String, Any>>() {}.type
        gson.fromJson(body, type)
    }

    suspend fun listSessions(
        deviceId: String? = null,
        workspaceId: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): SessionListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/sessions?limit=$limit&offset=$offset")
            if (deviceId != null) append("&device_id=$deviceId")
            if (workspaceId != null) append("&workspace_id=$workspaceId")
        }

        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("List sessions failed: ${response.code}")
        }
        gson.fromJson(body, SessionListResponse::class.java)
    }

    suspend fun listTopics(
        deviceId: String? = null,
        workspaceId: String? = null,
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0,
        includeChildren: Boolean = true
    ): TopicListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/topics?limit=$limit&offset=$offset&include_children=$includeChildren")
            if (deviceId != null) append("&device_id=$deviceId")
            if (workspaceId != null) append("&workspace_id=$workspaceId")
            if (status != null) append("&status=$status")
        }

        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("List topics failed: ${response.code}")
        }
        gson.fromJson(body, TopicListResponse::class.java)
    }

    suspend fun backfillTopics(
        deviceId: String? = null,
        limit: Int = 20
    ): TopicBackfillResponse = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>("limit" to limit)
        if (!deviceId.isNullOrBlank()) {
            payload["device_id"] = deviceId
        }
        val requestBody = gson.toJson(payload)
            .toRequestBody("application/json".toMediaType())

        val url = "$baseUrl/zerotouch/api/topics/backfill"
        Log.d(TAG, "POST $url device=$deviceId limit=$limit")
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Backfill topics failed: ${response.code}")
        }
        gson.fromJson(body, TopicBackfillResponse::class.java)
    }

    suspend fun evaluatePendingTopics(
        deviceId: String,
        force: Boolean = false,
        idleSeconds: Int = 60,
        maxSessions: Int = 200,
        boundaryReason: String? = null
    ): TopicEvaluatePendingResponse = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "device_id" to deviceId,
            "force" to force,
            "idle_seconds" to idleSeconds,
            "max_sessions" to maxSessions
        )
        if (!boundaryReason.isNullOrBlank()) {
            payload["boundary_reason"] = boundaryReason
        }
        val requestBody = gson.toJson(payload)
            .toRequestBody("application/json".toMediaType())

        val url = "$baseUrl/zerotouch/api/topics/evaluate-pending"
        Log.d(TAG, "POST $url device=$deviceId force=$force idleSeconds=$idleSeconds maxSessions=$maxSessions")
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Evaluate pending topics failed: ${response.code}")
        }
        gson.fromJson(body, TopicEvaluatePendingResponse::class.java)
    }

    suspend fun generateCards(sessionId: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/zerotouch/api/generate-cards/$sessionId")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Generate cards failed: ${response.code}")
        }
        val type = object : TypeToken<Map<String, Any>>() {}.type
        gson.fromJson(response.body!!.string(), type)
    }

    suspend fun getDeviceSettings(deviceId: String): DeviceSettings = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/device-settings/$deviceId"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Get device settings failed: ${response.code}")
        }
        gson.fromJson(body, DeviceSettings::class.java)
    }

    suspend fun updateDeviceSettings(
        deviceId: String,
        llmProvider: String?,
        llmModel: String?
    ): DeviceSettings = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>()
        llmProvider?.let { payload["llm_provider"] = it }
        llmModel?.let { payload["llm_model"] = it }

        val request = Request.Builder()
            .url("$baseUrl/zerotouch/api/device-settings/$deviceId")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST device settings failed: ${response.code} ${response.message} body=$body")
            throw Exception("Update device settings failed: ${response.code}")
        }

        val type = object : TypeToken<Map<String, Any>>() {}.type
        val envelope: Map<String, Any> = gson.fromJson(body, type)
        val settings = envelope["settings"]
        if (settings is Map<*, *>) {
            val json = gson.toJson(settings)
            gson.fromJson(json, DeviceSettings::class.java)
        } else {
            DeviceSettings(device_id = deviceId, llm_provider = llmProvider, llm_model = llmModel)
        }
    }

    suspend fun listAccounts(): AccountListResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/accounts"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("List accounts failed: ${response.code}")
        }
        gson.fromJson(body, AccountListResponse::class.java)
    }

    suspend fun createAccount(
        displayName: String,
        email: String? = null,
        externalAuthProvider: String? = null,
        externalAuthSubject: String? = null,
        avatarUrl: String? = null
    ): AccountSummary? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/accounts"
        val payload = mapOf(
            "display_name" to displayName,
            "email" to email,
            "external_auth_provider" to externalAuthProvider,
            "external_auth_subject" to externalAuthSubject,
            "avatar_url" to avatarUrl
        )
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Create account failed: ${response.code}")
        }
        val envelope = gson.fromJson(body, AccountCreateResponse::class.java)
        envelope.account
    }

    suspend fun updateAccount(
        accountId: String,
        displayName: String? = null,
        avatarUrl: String? = null
    ): AccountSummary = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/accounts/$accountId"
        val payload = mutableMapOf<String, Any?>()
        if (displayName != null) payload["display_name"] = displayName
        if (avatarUrl != null) payload["avatar_url"] = avatarUrl

        val request = Request.Builder()
            .url(url)
            .patch(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "PATCH $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Update account failed: ${response.code}")
        }
        val envelope = gson.fromJson(body, AccountCreateResponse::class.java)
        envelope.account ?: throw Exception("Account missing in update response")
    }

    suspend fun listOrganizations(accountId: String? = null): OrganizationListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/organizations")
            if (!accountId.isNullOrBlank()) append("?account_id=$accountId")
        }
        Log.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} body=$body")
            throw Exception("List organizations failed: ${response.code}")
        }
        gson.fromJson(body, OrganizationListResponse::class.java)
    }

    suspend fun listOrganizationMembers(organizationId: String): OrganizationMembersResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/organizations/$organizationId/members"
        Log.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} body=$body")
            throw Exception("List org members failed: ${response.code}")
        }
        gson.fromJson(body, OrganizationMembersResponse::class.java)
    }

    suspend fun listWorkspaces(accountId: String? = null): WorkspaceListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/workspaces")
            if (!accountId.isNullOrBlank()) append("?account_id=$accountId")
        }
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("List workspaces failed: ${response.code}")
        }
        gson.fromJson(body, WorkspaceListResponse::class.java)
    }

    suspend fun updateWorkspace(
        workspaceId: String,
        name: String? = null,
        description: String? = null
    ): WorkspaceSummary = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/workspaces/$workspaceId"
        val payload = mutableMapOf<String, Any?>()
        if (name != null) payload["name"] = name
        if (description != null) payload["description"] = description

        val request = Request.Builder()
            .url(url)
            .patch(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "PATCH $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Update workspace failed: ${response.code}")
        }
        val envelope = gson.fromJson(body, WorkspaceMutationResponse::class.java)
        envelope.workspace ?: throw Exception("Workspace missing in update response")
    }

    suspend fun listDevices(
        workspaceId: String? = null,
        accountId: String? = null
    ): DeviceListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/devices")
            val params = mutableListOf<String>()
            if (!workspaceId.isNullOrBlank()) params.add("workspace_id=$workspaceId")
            if (!accountId.isNullOrBlank()) params.add("account_id=$accountId")
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        }
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("List devices failed: ${response.code}")
        }
        gson.fromJson(body, DeviceListResponse::class.java)
    }

    suspend fun updateDevice(
        deviceRowId: String,
        displayName: String? = null
    ): DeviceSummary = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/devices/$deviceRowId"
        val payload = mutableMapOf<String, Any?>()
        if (displayName != null) payload["display_name"] = displayName

        val request = Request.Builder()
            .url(url)
            .patch(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "PATCH $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Update device failed: ${response.code}")
        }
        val envelope = gson.fromJson(body, DeviceMutationResponse::class.java)
        envelope.device ?: throw Exception("Device missing in update response")
    }

    suspend fun listWikiPages(deviceId: String? = null): WikiPagesResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/wiki-pages")
            if (!deviceId.isNullOrBlank()) append("?device_id=$deviceId")
        }
        Log.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} body=$body")
            throw Exception("List wiki pages failed: ${response.code}")
        }
        gson.fromJson(body, WikiPagesResponse::class.java)
    }

    suspend fun getContextProfile(workspaceId: String): ContextProfileEnvelope = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/context-profiles/$workspaceId"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Get context profile failed: ${response.code}")
        }
        val type = object : TypeToken<ContextProfileEnvelope>() {}.type
        gson.fromJson(body, type)
    }

    suspend fun upsertContextProfile(
        workspaceId: String,
        payload: ContextProfileRequest
    ): ContextProfile = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/context-profiles/$workspaceId"
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST $url failed: ${response.code} ${response.message} body=$body")
            throw Exception("Upsert context profile failed: ${response.code}")
        }
        val type = object : TypeToken<ContextProfileResponse>() {}.type
        val envelope: ContextProfileResponse = gson.fromJson(body, type)
        envelope.profile ?: throw Exception("Context profile missing in response")
    }

    fun parseCards(cardsResult: Map<String, Any>?): List<Card> {
        if (cardsResult == null) return emptyList()

        try {
            val cardsV1 = cardsResult["cards_v1"] as? Map<*, *> ?: return emptyList()
            val cardsList = cardsV1["cards"] as? List<*> ?: return emptyList()
            return cardsList.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                Card(
                    type = map["type"] as? String ?: "memo",
                    title = map["title"] as? String ?: "",
                    content = map["content"] as? String ?: "",
                    urgency = map["urgency"] as? String,
                    mentioned_by = map["mentioned_by"] as? String,
                    context = map["context"] as? String
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }
}
