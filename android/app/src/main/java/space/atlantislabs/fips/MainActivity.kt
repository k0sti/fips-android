package space.atlantislabs.fips

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.atlantislabs.fips.service.FipsTunService

class MainActivity : ComponentActivity() {

    private var viewModelRef: FipsViewModel? = null

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            bindTunServiceAndStart()
        } else {
            Log.w("MainActivity", "VPN consent denied")
        }
    }

    private val tunConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as FipsTunService.LocalBinder
            viewModelRef?.setTunService(binder.service)
            viewModelRef?.startVpn()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            viewModelRef?.setTunService(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val autotest = intent.getBooleanExtra("autotest", false)

        setContent {
            val vm: FipsViewModel = viewModel()
            viewModelRef = vm
            StatusScreen(
                viewModel = vm,
                onVpnToggle = { enable ->
                    if (enable) requestVpn() else stopVpn(vm)
                },
            )

            if (autotest && savedInstanceState == null) {
                runAutotest(vm)
            }
        }
    }

    /** Request VPN consent, then bind and start TUN. */
    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnConsentLauncher.launch(intent)
        } else {
            // Already consented
            bindTunServiceAndStart()
        }
    }

    private fun bindTunServiceAndStart() {
        val intent = Intent(this, FipsTunService::class.java)
        startService(intent)
        bindService(intent, tunConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopVpn(vm: FipsViewModel) {
        vm.stopVpn()
        try {
            unbindService(tunConnection)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            unbindService(tunConnection)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun runAutotest(vm: FipsViewModel) {
        vm.startNode()
        lifecycleScope.launch {
            val timeout = 15_000L
            val poll = 1_000L
            var elapsed = 0L

            // Wait for peers to connect or timeout
            while (elapsed < timeout) {
                delay(poll)
                elapsed += poll
                val s = vm.state.value
                if (s.peers.any { it.connectivity == "connected" }) break
            }

            // Final dump
            val dump = vm.dumpState()
            Log.i("FipsAutotest", "=== AUTOTEST RESULT ===")
            Log.i("FipsAutotest", dump)
            Log.i("FipsAutotest", "=== AUTOTEST DONE ===")

            vm.stopNode()
            finish()
        }
    }
}
