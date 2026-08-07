package com.agani.syncup.data

import retrofit2.http.Body
import retrofit2.http.POST

/** Backend interface — see docs/api-contract.md. Extended as the backend grows. */
interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse
}
