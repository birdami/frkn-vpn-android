package com.vpn.app.service

import com.google.gson.GsonBuilder
import com.vpn.app.api.ServerConnection

/**
 * Builds sing-box JSON config from Hysteria2 server params.
 */
object SingBoxConfig {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun build(server: ServerConnection, cacheDir: String? = null): String {
        val config = mutableMapOf<String, Any>()

        // Log
        config["log"] = mapOf("level" to "info")

        // DNS
        config["dns"] = mapOf(
            "servers" to listOf(
                mapOf("tag" to "cloudflare", "address" to "https://1.1.1.1/dns-query", "detour" to "proxy"),
                mapOf("tag" to "local", "address" to "local"),
            ),
            "rules" to listOf(
                mapOf("outbound" to listOf("any"), "server" to "local"),
            ),
        )

        // Inbounds — TUN
        config["inbounds"] = listOf(
            mapOf(
                "type" to "tun",
                "tag" to "tun-in",
                "address" to listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                "auto_route" to true,
                "strict_route" to true,
                "sniff" to true,
                "sniff_override_destination" to true,
            )
        )

        // Outbounds — Hysteria2 + direct + dns
        val hy2Outbound = mutableMapOf<String, Any>(
            "type" to "hysteria2",
            "tag" to "proxy",
            "server" to server.serverIp,
            "server_port" to server.port,
            "password" to server.authPassword,
            "tls" to mapOf(
                "enabled" to true,
                "insecure" to server.insecure,
            ),
        )

        // Add obfuscation if present
        if (server.obfsType != null && server.obfsPassword != null) {
            hy2Outbound["obfs"] = mapOf(
                "type" to server.obfsType,
                "password" to server.obfsPassword,
            )
        }

        config["outbounds"] = listOf(
            hy2Outbound,
            mapOf("type" to "direct", "tag" to "direct"),
            mapOf("type" to "dns", "tag" to "dns-out"),
        )

        // Route
        config["route"] = mapOf(
            "auto_detect_interface" to true,
            "rules" to listOf(
                mapOf("protocol" to "dns", "outbound" to "dns-out"),
                mapOf("ip_is_private" to true, "outbound" to "direct"),
            ),
        )

        // Experimental — cache file with explicit path (or disabled)
        if (cacheDir != null) {
            config["experimental"] = mapOf(
                "cache_file" to mapOf(
                    "enabled" to true,
                    "path" to "$cacheDir/cache.db",
                )
            )
        } else {
            config["experimental"] = mapOf(
                "cache_file" to mapOf(
                    "enabled" to false,
                )
            )
        }

        return gson.toJson(config)
    }
}
