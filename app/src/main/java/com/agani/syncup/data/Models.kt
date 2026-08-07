package com.agani.syncup.data

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val email: String,
    val password: String,
)

data class User(
    val id: String,
    val name: String,
    val email: String,
)

data class UrlItem(
    val id: String,
    val title: String,
    val url: String,
    val icon: String? = null,
    val description: String? = null,
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    val user: User,
    val urls: List<UrlItem> = emptyList(),
)
