package com.example.zero_touch.api

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

data class Card(
    val type: String,
    val title: String,
    val content: String,
    val urgency: String? = null,
    val mentioned_by: String? = null,
    val context: String? = null
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
        limit: Int = 20,
        offset: Int = 0
    ): SessionListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/sessions?limit=$limit&offset=$offset")
            if (deviceId != null) append("&device_id=$deviceId")
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
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0,
        includeChildren: Boolean = true
    ): TopicListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/topics?limit=$limit&offset=$offset&include_children=$includeChildren")
            if (deviceId != null) append("&device_id=$deviceId")
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
