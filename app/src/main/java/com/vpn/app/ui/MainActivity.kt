package com.vpn.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vpn.app.databinding.ActivityMainBinding
import com.vpn.app.service.VpnManager
import com.vpn.app.service.VpnTunnelService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), VpnTunnelService.StateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vpnManager: VpnManager
    private var pendingConnect = false

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            doConnect()
        } else {
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
            updateUI(false)
        }
    }

    // Notification permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vpnManager = VpnManager(this)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnConnect.setOnClickListener { onConnectClick() }

        updateUI(VpnTunnelService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        VpnTunnelService.listener = this
        updateUI(VpnTunnelService.isRunning)
    }

    override fun onPause() {
        VpnTunnelService.listener = null
        super.onPause()
    }

    // ─── VPN state callback ─────────────────────────────────────────────

    override fun onStateChanged(running: Boolean) {
        runOnUiThread { updateUI(running) }
    }

    // ─── Connect/Disconnect ─────────────────────────────────────────────

    private fun onConnectClick() {
        if (vpnManager.isConnected) {
            vpnManager.disconnect()
            updateUI(false)
        } else {
            requestVpnPermissionAndConnect()
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnect = true
            vpnPermissionLauncher.launch(intent)
        } else {
            doConnect()
        }
    }

    private fun doConnect() {
        binding.btnConnect.isEnabled = false
        binding.tvStatus.text = "Connecting..."

        lifecycleScope.launch {
            try {
                vpnManager.connect()
                // State update will come via listener
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                updateUI(false)
            }
        }
    }

    // ─── UI ─────────────────────────────────────────────────────────────

    private fun updateUI(connected: Boolean) {
        binding.btnConnect.isEnabled = true

        if (connected) {
            binding.btnConnect.text = "Disconnect"
            binding.tvStatus.text = "Connected"
            binding.ivStatus.setImageResource(android.R.drawable.presence_online)
        } else {
            binding.btnConnect.text = "Connect"
            binding.tvStatus.text = "Disconnected"
            binding.ivStatus.setImageResource(android.R.drawable.presence_offline)
        }
    }
}
