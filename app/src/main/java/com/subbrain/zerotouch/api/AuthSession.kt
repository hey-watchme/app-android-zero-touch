package com.subbrain.zerotouch.api

data class AuthSession(
    val userId: String,
    val email: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val accessToken: String,
    val refreshToken: String? = null
)
