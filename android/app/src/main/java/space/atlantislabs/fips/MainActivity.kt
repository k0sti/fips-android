package space.atlantislabs.fips

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val autotest = intent.getBooleanExtra("autotest", false)

        setContent {
            val vm: FipsViewModel = viewModel()
            StatusScreen(vm)

            if (autotest && savedInstanceState == null) {
                runAutotest(vm)
            }
        }
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
