package com.subbrain.zerotouch.api

import com.subbrain.zerotouch.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SupabaseUser(
    val id: String,
    val email: String? = null,
    val user_metadata: Map<String, Any>? = null
)

data class SupabaseSessionResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Int? = null,
    val token_type: String? = null,
    val user: SupabaseUser? = null
)

class SupabaseAuthApi(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun signInWithGoogleIdToken(
        idToken: String,
        accessToken: String? = null
    ): SupabaseSessionResponse = withContext(Dispatchers.IO) {
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            throw IllegalStateException("Supabase credentials are missing. Set SUPABASE_URL and SUPABASE_ANON_KEY.")
        }

        val payload = mutableMapOf<String, Any>(
            "provider" to "google",
            "id_token" to idToken
        )
        if (!accessToken.isNullOrBlank()) {
            payload["access_token"] = accessToken
        }

        val request = Request.Builder()
            .url("$supabaseUrl/auth/v1/token?grant_type=id_token")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw Exception("Supabase auth failed: ${response.code} $body")
        }
        gson.fromJson(body, SupabaseSessionResponse::class.java)
    }
}
