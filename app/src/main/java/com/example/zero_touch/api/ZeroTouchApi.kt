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

    suspend fun transcribe(sessionId: String, autoChain: Boolean = true): Map<String, Any> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/zerotouch/api/transcribe/$sessionId?auto_chain=$autoChain")
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
        val request = Request.Builder()
            .url("$baseUrl/zerotouch/api/sessions/$sessionId")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Get session failed: ${response.code}")
        }
        gson.fromJson(response.body!!.string(), SessionDetail::class.java)
    }

    suspend fun listSessions(deviceId: String? = null, limit: Int = 20): SessionListResponse = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/zerotouch/api/sessions?limit=$limit")
            if (deviceId != null) append("&device_id=$deviceId")
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("List sessions failed: ${response.code}")
        }
        gson.fromJson(response.body!!.string(), SessionListResponse::class.java)
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
