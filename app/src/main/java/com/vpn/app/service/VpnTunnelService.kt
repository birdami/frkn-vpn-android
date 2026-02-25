package com.vpn.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.NetworkInterface as JavaNetworkInterface

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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var interfaceListener: InterfaceUpdateListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config != null) startVpn(config)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { stopVpn(); super.onRevoke() }

    private fun startVpn(config: String) {
        if (isRunning) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

            // Setup libbox — ALL paths must point to writable dirs
            val baseDir = filesDir.absolutePath
            val setupOptions = io.nekohasekai.libbox.SetupOptions()
            setupOptions.basePath = baseDir
            setupOptions.workingPath = baseDir
            setupOptions.tempPath = cacheDir.absolutePath
            Libbox.setup(setupOptions)

            Log.i(TAG, "libbox setup done, basePath=$baseDir")
            Log.i(TAG, "Config: ${config.take(200)}...")

            val service = Libbox.newService(config, this)
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
        try { boxService?.close() } catch (e: Exception) { Log.e(TAG, "Error closing box", e) }
        boxService = null
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        isRunning = false
        listener?.onStateChanged(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    // ─── PlatformInterface (16 methods, exact match libbox 1.11.11) ─────

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        Log.d(TAG, "autoDetectInterfaceControl: protecting fd=$fd")
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        val builder = Builder().apply {
            addAddress("172.19.0.1", 30)
            addAddress("fdfe:dcba:9876::1", 126)
            addDnsServer("1.1.1.1")
            addRoute("0.0.0.0", 0)
            addRoute("::", 0)
            setMtu(9000)
            setSession("FRKN VPN")
            setBlocking(false)
        }
        tunFd = builder.establish()
        return tunFd?.fd ?: throw Exception("Failed to create TUN interface")
    }

    override fun writeLog(message: String?) { Log.d(TAG, message ?: "") }
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}
    override fun useProcFS(): Boolean = false
    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int
    ): Int = -1
    override fun packageNameByUid(uid: Int): String = ""
    override fun uidByPackageName(packageName: String?): Int = -1

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.i(TAG, "startDefaultInterfaceMonitor called")
        this.interfaceListener = listener

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Immediately notify about current default network
        notifyCurrentDefaultInterface(cm, listener)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "NetworkCallback: onAvailable $network")
                notifyCurrentDefaultInterface(cm, listener)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.d(TAG, "NetworkCallback: onLinkPropertiesChanged ${linkProperties.interfaceName}")
                notifyCurrentDefaultInterface(cm, listener)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.d(TAG, "NetworkCallback: onCapabilitiesChanged")
                notifyCurrentDefaultInterface(cm, listener)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "NetworkCallback: onLost $network")
                notifyCurrentDefaultInterface(cm, listener)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "Default interface monitor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.i(TAG, "closeDefaultInterfaceMonitor called")
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
        this.interfaceListener = null
    }

    private fun notifyCurrentDefaultInterface(cm: ConnectivityManager, listener: InterfaceUpdateListener?) {
        try {
            val activeNetwork = cm.activeNetwork
            if (activeNetwork == null) {
                Log.w(TAG, "No active network")
                listener?.updateDefaultInterface("", -1, false, false)
                return
            }

            val linkProps = cm.getLinkProperties(activeNetwork)
            val ifName = linkProps?.interfaceName
            if (ifName == null) {
                Log.w(TAG, "Active network has no interface name")
                listener?.updateDefaultInterface("", -1, false, false)
                return
            }

            // Get the interface index
            var ifIndex = 0
            try {
                val javaIf = JavaNetworkInterface.getByName(ifName)
                ifIndex = javaIf?.index ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Could not get interface index for $ifName", e)
            }

            // Check if network is metered (cellular = expensive)
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val isExpensive = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            // Check if network is constrained (e.g. Data Saver)
            val isConstrained = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

            Log.i(TAG, "Default interface: $ifName (index=$ifIndex, expensive=$isExpensive, constrained=$isConstrained)")
            listener?.updateDefaultInterface(ifName, ifIndex, isExpensive, isConstrained)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default interface", e)
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val ifList = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()

        // Get DNS servers and metered status from ConnectivityManager for the active network
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val linkProps = if (activeNetwork != null) cm.getLinkProperties(activeNetwork) else null
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        val activeIfName = linkProps?.interfaceName
        val dnsAddrs = linkProps?.dnsServers?.mapNotNull { it.hostAddress?.split("%")?.get(0) } ?: emptyList()
        val isMetered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        try {
            val javaInterfaces = JavaNetworkInterface.getNetworkInterfaces()
            if (javaInterfaces != null) {
                for (javaIf in javaInterfaces) {
                    // Collect addresses in CIDR notation
                    val addrs = mutableListOf<String>()
                    for (ifAddr in javaIf.interfaceAddresses) {
                        val hostAddr = ifAddr.address?.hostAddress ?: continue
                        val prefix = ifAddr.networkPrefixLength.toInt()
                        if (prefix < 0 || prefix > 128) continue
                        val clean = hostAddr.split("%")[0]
                        addrs.add("$clean/$prefix")
                    }

                    val libIf = io.nekohasekai.libbox.NetworkInterface()
                    libIf.name = javaIf.name
                    libIf.index = javaIf.index
                    libIf.mtu = javaIf.mtu

                    // Go net.Flags: FlagUp=1, FlagBroadcast=2, FlagLoopback=4,
                    // FlagPointToPoint=8, FlagMulticast=16, FlagRunning=32
                    var flags = 0
                    if (javaIf.isUp) flags = flags or (1 or 32)       // UP | RUNNING
                    if (javaIf.supportsMulticast()) flags = flags or 16 // MULTICAST
                    if (javaIf.isLoopback) flags = flags or 4           // LOOPBACK
                    if (javaIf.isPointToPoint) flags = flags or 8       // POINTTOPOINT
                    if (!javaIf.isLoopback && !javaIf.isPointToPoint) flags = flags or 2 // BROADCAST
                    libIf.flags = flags

                    libIf.addresses = object : StringIterator {
                        private var idx = 0
                        override fun hasNext(): Boolean = idx < addrs.size
                        override fun next(): String = addrs[idx++]
                        override fun len(): Int = addrs.size
                    }

                    // Set DNS servers and metered for the active interface
                    if (javaIf.name == activeIfName && dnsAddrs.isNotEmpty()) {
                        libIf.dnsServer = object : StringIterator {
                            private var idx = 0
                            override fun hasNext(): Boolean = idx < dnsAddrs.size
                            override fun next(): String = dnsAddrs[idx++]
                            override fun len(): Int = dnsAddrs.size
                        }
                        libIf.metered = isMetered
                    }

                    Log.d(TAG, "getInterfaces: ${javaIf.name} idx=${javaIf.index} flags=$flags addrs=$addrs dns=${if (javaIf.name == activeIfName) dnsAddrs else "[]"}")
                    ifList.add(libIf)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating interfaces", e)
        }
        Log.i(TAG, "getInterfaces: returning ${ifList.size} interfaces (active=$activeIfName)")
        return object : NetworkInterfaceIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < ifList.size
            override fun next(): io.nekohasekai.libbox.NetworkInterface = ifList[idx++]
        }
    }
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}

    // ─── Notifications ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "VPN connection status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
