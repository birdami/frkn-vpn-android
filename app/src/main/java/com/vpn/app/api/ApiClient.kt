package com.vpn.app.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ─── Data classes ───────────────────────────────────────────────────────

data class RegisterRequest(
    @SerializedName("device_id") val deviceId: String,
    val platform: String = "android",
    val name: String = android.os.Build.MODEL,
)

data class RegisterResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("referral_code") val referralCode: String,
    @SerializedName("is_new_user") val isNewUser: Boolean,
)

data class ServerConnection(
    @SerializedName("server_ip") val serverIp: String,
    val port: Int,
    @SerializedName("auth_password") val authPassword: String,
    @SerializedName("obfs_type") val obfsType: String?,
    @SerializedName("obfs_password") val obfsPassword: String?,
    val insecure: Boolean,
    @SerializedName("connection_uri") val connectionUri: String,
)

data class VpnConfigResponse(
    val servers: List<ServerConnection>,
)

// ─── API Client ─────────────────────────────────────────────────────────

class ApiClient(private val baseUrl: String = "https://riga.baby") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val json = "application/json".toMediaType()

    /**
     * Register device and get JWT token.
     */
    suspend fun register(deviceId: String): RegisterResponse = withContext(Dispatchers.IO) {
        val body = gson.toJson(RegisterRequest(deviceId = deviceId)).toRequestBody(json)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/register")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ApiException("Registration failed: ${response.code}")
        }
        gson.fromJson(response.body!!.string(), RegisterResponse::class.java)
    }

    /**
     * Get VPN server configs (Hysteria2 params).
     */
    suspend fun getConfig(token: String): VpnConfigResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/config")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ApiException("Config fetch failed: ${response.code}")
        }
        gson.fromJson(response.body!!.string(), VpnConfigResponse::class.java)
    }
}

class ApiException(message: String) : Exception(message)
