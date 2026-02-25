package com.vpn.app.service

import android.content.Context
import android.provider.Settings
import com.vpn.app.api.ApiClient

/**
 * Manages device registration, config fetching, and VPN lifecycle.
 * Stores token in SharedPreferences.
 */
class VpnManager(private val context: Context) {

    private val api = ApiClient()
    private val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

    val isConnected: Boolean get() = VpnTunnelService.isRunning

    var deviceToken: String?
        get() = prefs.getString("device_token", null)
        private set(value) = prefs.edit().putString("device_token", value).apply()

    var referralCode: String?
        get() = prefs.getString("referral_code", null)
        private set(value) = prefs.edit().putString("referral_code", value).apply()

    /**
     * Get unique device ID (Android ID).
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-${System.currentTimeMillis()}"
    }

    /**
     * Register device if needed, returns token.
     */
    suspend fun ensureRegistered(): String {
        deviceToken?.let { return it }

        val response = api.register(getDeviceId())
        deviceToken = response.deviceToken
        referralCode = response.referralCode
        return response.deviceToken
    }

    /**
     * Fetch server config and connect to the first available server.
     */
    suspend fun connect() {
        val token = ensureRegistered()
        val config = api.getConfig(token)

        if (config.servers.isEmpty()) {
            throw Exception("No servers available")
        }

        // Pick first server (MVP — single server)
        val server = config.servers.first()
        val singBoxJson = SingBoxConfig.build(server, context.filesDir.absolutePath)

        // Start VPN service
        VpnTunnelService.start(context, singBoxJson)
    }

    /**
     * Disconnect VPN.
     */
    fun disconnect() {
        VpnTunnelService.stop(context)
    }
}
