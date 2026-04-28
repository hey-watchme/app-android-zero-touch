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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

data class UploadResponse(
    val session_id: String,
    val s3_path: String,
    val status: String
)

sealed class ZeroTouchApiException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    abstract val operation: String
    abstract val retryable: Boolean

    class Http(
        override val operation: String,
        val statusCode: Int,
        val responseBody: String?,
        override val retryable: Boolean
    ) : ZeroTouchApiException(
        "$operation failed: HTTP $statusCode${responseBody?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}"
    )

    class Network(
        override val operation: String,
        cause: IOException
    ) : ZeroTouchApiException(
        "$operation failed: ${cause.message ?: cause.javaClass.simpleName}",
        cause
    ) {
        override val retryable: Boolean = true
    }
}

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

data class WorkspaceMemberSummary(
    val workspace_id: String,
    val account_id: String,
    val role: String? = null,
    val account: AccountSummary? = null
)

data class WorkspaceMembersResponse(
    val members: List<WorkspaceMemberSummary>,
    val count: Int = 0
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

data class ActionCandidate(
    val id: String,
    val topic_id: String,
    val device_id: String,
    val workspace_id: String? = null,
    val domain: String,
    val intent_type: String,
    val title: String? = null,
    val summary: String? = null,
    val payload: Map<String, Any?>? = null,
    val sources: Map<String, Any?>? = null,
    val destination: String? = null,
    val status: String,
    val confidence: Double? = null,
    val requires_review: Boolean = true,
    val review_state: Map<String, Any?>? = null,
    val provider: String? = null,
    val model: String? = null,
    val created_at: String,
    val updated_at: String
)

data class ActionCandidateListResponse(
    val candidates: List<ActionCandidate>,
    val count: Int
)

data class ActionConvertResult(
    val topic_id: String,
    val ok: Boolean,
    val created: Int? = null,
    val candidate_ids: List<String>? = null,
    val reused: Boolean? = null,
    val superseded: Int? = null,
    val reason: String? = null
)

data class ActionConvertResponse(
    val status: String,
    val result: ActionConvertResult
)

data class ActionReviewResponse(
    val ok: Boolean,
    val candidate: ActionCandidate? = null,
    val reason: String? = null
)

data class LiveSession(
    val id: String,
    val device_id: String,
    val workspace_id: String? = null,
    val share_token: String,
    val status: String,
    val started_at: String? = null,
    val ended_at: String? = null,
    val expires_at: String? = null,
    val visibility: String? = null
)

data class LiveSessionCreateRequest(
    val device_id: String,
    val workspace_id: String? = null,
    val language_primary: String = "ja",
    val visibility: String = "public",
    val metadata: Map<String, Any?>? = null
)

data class LiveSessionEndResponse(
    val status: String,
    val live_session: LiveSession? = null
)

data class RealtimeTranscribeResponse(
    val live_session_id: String,
    val chunk_index: Int,
    val text: String? = null
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

    private fun httpException(operation: String, code: Int, body: String?): ZeroTouchApiException.Http {
        return ZeroTouchApiException.Http(
            operation = operation,
            statusCode = code,
            responseBody = body,
            retryable = code == 408 || code == 429 || code in 500..599
        )
    }

    private suspend fun <T> withRetry(
        operation: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1_000L,
        block: () -> T
    ): T {
        var nextDelayMs = initialDelayMs
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: ZeroTouchApiException) {
                if (!e.retryable) throw e
                Log.w(TAG, "$operation retry ${attempt + 1}/$maxAttempts after retryable failure: ${e.message}")
                delay(nextDelayMs)
                nextDelayMs *= 2
            }
        }
        return block()
    }

    suspend fun uploadAudio(
        file: File,
        deviceId: String,
        localRecordingId: String? = null
    ): UploadResponse = withContext(Dispatchers.IO) {
        val operation = "Upload"
        val requestBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType())
            )
        if (!localRecordingId.isNullOrBlank()) {
            requestBuilder.addFormDataPart("local_recording_id", localRecordingId)
        }
        val requestBody = requestBuilder.build()

        val request = Request.Builder()
            .url("$baseUrl/zerotouch/api/upload")
            .post(requestBody)
            .build()

        val executeUpload = {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        throw httpException(operation, response.code, body)
                    }
                    gson.fromJson(body, UploadResponse::class.java)
                }
            } catch (e: IOException) {
                throw ZeroTouchApiException.Network(operation, e)
            }
        }
        if (localRecordingId.isNullOrBlank()) executeUpload() else withRetry(operation = operation, block = executeUpload)
    }

    suspend fun transcribe(
        sessionId: String,
        autoChain: Boolean = true,
        provider: String? = null,
        model: String? = null,
        language: String? = null
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        val operation = "Transcribe trigger"
        withRetry(operation = operation) {
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

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        throw httpException(operation, response.code, body)
                    }
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    gson.fromJson(body, type)
                }
            } catch (e: IOException) {
                throw ZeroTouchApiException.Network(operation, e)
            }
        }
    }

    suspend fun createLiveSession(
        deviceId: String,
        workspaceId: String? = null,
        languagePrimary: String = "ja",
        visibility: String = "public",
        metadata: Map<String, Any?>? = null
    ): LiveSession = withContext(Dispatchers.IO) {
        val operation = "Create live session"
        withRetry(operation = operation) {
            val url = "$baseUrl/zerotouch/api/live-sessions"
            val payload = LiveSessionCreateRequest(
                device_id = deviceId,
                workspace_id = workspaceId,
                language_primary = languagePrimary,
                visibility = visibility,
                metadata = metadata
            )
            val request = Request.Builder()
                .url(url)
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw httpException(operation, response.code, body)
                    }
                    gson.fromJson(body, LiveSession::class.java)
                }
            } catch (e: IOException) {
                throw ZeroTouchApiException.Network(operation, e)
            }
        }
    }

    suspend fun endLiveSession(liveSessionId: String): LiveSessionEndResponse = withContext(Dispatchers.IO) {
        val operation = "End live session"
        withRetry(operation = operation) {
            val url = "$baseUrl/zerotouch/api/live-sessions/$liveSessionId/end"
            val request = Request.Builder()
                .url(url)
                .post("".toRequestBody(null))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw httpException(operation, response.code, body)
                    }
                    gson.fromJson(body, LiveSessionEndResponse::class.java)
                }
            } catch (e: IOException) {
                throw ZeroTouchApiException.Network(operation, e)
            }
        }
    }

    suspend fun transcribeRealtimeChunk(
        file: File,
        liveSessionId: String,
        chunkIndex: Int
    ): RealtimeTranscribeResponse = withContext(Dispatchers.IO) {
        val operation = "Realtime transcribe"
        withRetry(operation = operation) {
            val url = "$baseUrl/zerotouch/api/transcribe/realtime"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("live_session_id", liveSessionId)
                .addFormDataPart("chunk_index", chunkIndex.toString())
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody("audio/mp4".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw httpException(operation, response.code, body)
                    }
                    gson.fromJson(body, RealtimeTranscribeResponse::class.java)
                }
            } catch (e: IOException) {
                throw ZeroTouchApiException.Network(operation, e)
            }
        }
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

    suspend fun listWorkspaceMembers(workspaceId: String): WorkspaceMembersResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/zerotouch/api/workspaces/$workspaceId/members"
            val response = client.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "GET $url failed: ${response.code}")
                return@withContext WorkspaceMembersResponse(emptyList())
            }
            gson.fromJson(body, WorkspaceMembersResponse::class.java)
                ?: WorkspaceMembersResponse(emptyList())
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

    suspend fun convertTopicToActions(
        topicId: String,
        force: Boolean = false,
        domain: String = "knowledge_worker",
        provider: String? = null,
        model: String? = null,
    ): ActionConvertResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/action-candidates/from-topic/$topicId"
        val payload = mutableMapOf<String, Any?>(
            "force" to force,
            "domain" to domain,
        )
        if (!provider.isNullOrBlank()) payload["provider"] = provider
        if (!model.isNullOrBlank()) payload["model"] = model
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        Log.d(TAG, "POST $url")
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST $url failed: ${response.code} body=$body")
            throw Exception("Convert topic to actions failed: ${response.code}")
        }
        gson.fromJson(body, ActionConvertResponse::class.java)
    }

    suspend fun listActionCandidates(
        deviceId: String? = null,
        topicId: String? = null,
        status: String? = null,
        intentType: String? = null,
        limit: Int = 50,
    ): ActionCandidateListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/action-candidates")
            val params = mutableListOf<String>()
            if (!deviceId.isNullOrBlank()) params += "device_id=$deviceId"
            if (!topicId.isNullOrBlank()) params += "topic_id=$topicId"
            if (!status.isNullOrBlank()) params += "status=$status"
            if (!intentType.isNullOrBlank()) params += "intent_type=$intentType"
            params += "limit=$limit"
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        }
        Log.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "GET $url failed: ${response.code} body=$body")
            throw Exception("List action candidates failed: ${response.code}")
        }
        gson.fromJson(body, ActionCandidateListResponse::class.java)
    }

    suspend fun reviewActionCandidate(
        candidateId: String,
        action: String,
        edits: Map<String, Any?>? = null,
        notes: String? = null,
        reviewer: String? = null,
    ): ActionReviewResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/zerotouch/api/action-candidates/$candidateId/review"
        val payload = mutableMapOf<String, Any?>("action" to action)
        if (edits != null) payload["edits"] = edits
        if (!notes.isNullOrBlank()) payload["notes"] = notes
        if (!reviewer.isNullOrBlank()) payload["reviewer"] = reviewer
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        Log.d(TAG, "POST $url")
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "POST $url failed: ${response.code} body=$body")
            throw Exception("Review action candidate failed: ${response.code}")
        }
        gson.fromJson(body, ActionReviewResponse::class.java)
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
