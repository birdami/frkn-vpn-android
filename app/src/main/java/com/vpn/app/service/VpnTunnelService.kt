package com.vpn.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpn.app.R
import com.vpn.app.ui.MainActivity
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File

class VpnTunnelService : VpnService(), PlatformInterface {

    companion object {
        private const val TAG = "VpnTunnel"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.vpn.app.START"
        const val ACTION_STOP = "com.vpn.app.STOP"
        const val EXTRA_CONFIG = "config"

        var isRunning = false
            private set

        var listener: StateListener? = null

        fun start(context: Context, config: String) {
            val intent = Intent(context, VpnTunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnTunnelService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    interface StateListener {
        fun onStateChanged(running: Boolean)
    }

    private var boxService: BoxService? = null
    private var tunFd: ParcelFileDescriptor? = null

    // ─── Service lifecycle ───────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config != null) {
                    startVpn(config)
                }
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    // ─── VPN start/stop ─────────────────────────────────────────────────

    private fun startVpn(config: String) {
        if (isRunning) return

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

            val configFile = File(filesDir, "config.json")
            configFile.writeText(config)

            val service = Libbox.newService(configFile.absolutePath, this)
            service.start()
            boxService = service

            isRunning = true
            listener?.onStateChanged(true)
            updateNotification("Connected")
            Log.i(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            isRunning = false
            listener?.onStateChanged(false)
            stopSelf()
        }
    }

    private fun stopVpn() {
        try {
            boxService?.close()
            boxService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing box service", e)
        }
        try {
            tunFd?.close()
            tunFd = null
        } catch (_: Exception) {}

        isRunning = false
        listener?.onStateChanged(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    // ─── PlatformInterface ──────────────────────────────────────────────

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        val builder = Builder().apply {
            options.getInet4Address(0)?.let { addr ->
                addAddress(addr, options.inet4AddressPrefixLength)
            }
            options.getInet6Address(0)?.let { addr ->
                addAddress(addr, options.inet6AddressPrefixLength)
            }
            for (i in 0 until options.dnsServerAddressCount) {
                options.getDnsServerAddress(i)?.let { addDnsServer(it) }
            }
            addRoute("0.0.0.0", 0)
            addRoute("::", 0)
            setMtu(options.mtu)
            setSession("FRKN VPN")
            setBlocking(false)
        }
        tunFd = builder.establish()
        return tunFd?.fd ?: throw Exception("Failed to create TUN interface")
    }

    override fun writeLog(message: String?) {
        Log.d(TAG, message ?: "")
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {
        // libbox may send its own notifications; we ignore and use our own
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int
    ): Int = -1

    override fun packageNameByUid(uid: Int): String = ""

    override fun uidByPackageName(packageName: String?): Int = -1

    override fun usePlatformDefaultInterfaceMonitor(): Boolean = false

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

    override fun usePlatformInterfaceGetter(): Boolean = false

    override fun getInterfaces(): NetworkInterfaceIterator? = null

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}

    // ─── Notifications ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "VPN connection status" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FRKN VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
