package com.vpn.app.service

import android.content.Context
import android.provider.Settings
import com.vpn.app.api.ApiClient

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

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-${System.currentTimeMillis()}"
    }

    suspend fun ensureRegistered(): String {
        deviceToken?.let { return it }
        val response = api.register(getDeviceId())
        deviceToken = response.deviceToken
        referralCode = response.referralCode
        return response.deviceToken
    }

    suspend fun connect() {
        val token = ensureRegistered()
        val config = api.getConfig(token)

        if (config.servers.isEmpty()) {
            throw Exception("No servers available")
        }

        val server = config.servers.first()
        val singBoxJson = SingBoxConfig.build(server)

        VpnTunnelService.start(context, singBoxJson)
    }

    fun disconnect() {
        VpnTunnelService.stop(context)
    }
}
