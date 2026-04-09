package space.atlantislabs.fips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import space.atlantislabs.fips.model.DashboardPeer
import space.atlantislabs.fips.model.DashboardPeersResponse
import space.atlantislabs.fips.model.DashboardStatus
import space.atlantislabs.fips.model.DashboardTransport
import space.atlantislabs.fips.model.DashboardTransportsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import uniffi.fips_mobile.FipsMobileNode
import android.util.Log

private const val TAG = "FipsViewModel"

class FipsViewModel : ViewModel() {

    private val _state = MutableStateFlow(FipsUiState())
    val state: StateFlow<FipsUiState> = _state.asStateFlow()

    private var node: FipsMobileNode? = null
    private var pollingJob: Job? = null
    private var stopJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun startNode() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                // Wait for previous stop to finish (socket release)
                stopJob?.join()
                val configYaml = buildConfigYaml()
                val n = FipsMobileNode(configYaml)
                node = n
                _state.update { it.copy(isLoading = false, isRunning = true) }
                startPolling()
            } catch (e: Exception) {
                Log.e(TAG, "startNode failed", e)
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun buildConfigYaml(): String = """
        node:
          control:
            enabled: false
        tun:
          enabled: false
        dns:
          enabled: false
        transports:
          udp:
            bind_addr: "0.0.0.0:2121"
        peers:
          - npub: "npub1zv58cn7v83mxvttl70w5fwjwuclfmntv9cnmv5wmz2nzz88u5urqvdx96n"
            alias: "fips.v0l.io"
            addresses:
              - transport: udp
                addr: "fips.v0l.io:2121"
          - npub: "npub1qmc3cvfz0yu2hx96nq3gp55zdan2qclealn7xshgr448d3nh6lks7zel98"
            alias: "fips-test-node"
            addresses:
              - transport: udp
                addr: "217.77.8.91:2121"
    """.trimIndent()

    private fun startPolling() {
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val n = node ?: break

                    val statusJson = n.query("show_status")
                    val peersJson = n.query("show_peers")
                    val transportsJson = n.query("show_transports")

                    val status = json.decodeFromString<DashboardStatus>(statusJson)
                    val peersResp = json.decodeFromString<DashboardPeersResponse>(peersJson)
                    val transportsResp = json.decodeFromString<DashboardTransportsResponse>(transportsJson)

                    _state.update {
                        it.copy(
                            status = status,
                            peers = peersResp.peers,
                            transports = transportsResp.transports,
                            error = null,
                        )
                    }
                    Log.d("FipsDump", dumpState())
                } catch (e: Exception) {
                    Log.e(TAG, "polling failed", e)
                    _state.update { it.copy(error = e.message) }
                }
                delay(2_000)
            }
        }
    }

    fun dumpState(): String {
        val s = _state.value
        val raw = node?.let {
            try {
                "--- raw json ---\nstatus: ${it.query("show_status")}\npeers: ${it.query("show_peers")}\ntransports: ${it.query("show_transports")}"
            } catch (e: Exception) {
                "raw query error: ${e.message}"
            }
        } ?: "node not running"

        val dump = buildString {
            appendLine("=== FIPS DEBUG DUMP ===")
            appendLine("running: ${s.isRunning}, loading: ${s.isLoading}")
            s.error?.let { appendLine("error: $it") }
            s.status?.let { st ->
                appendLine("--- status ---")
                appendLine("state: ${st.state}, npub: ${st.npub}")
                appendLine("uptime: ${st.uptimeSecs}s, peers: ${st.peerCount}, links: ${st.linkCount}, sessions: ${st.sessionCount}")
                appendLine("mesh_size: ${st.estimatedMeshSize}, leaf_only: ${st.isLeafOnly}")
            }
            if (s.peers.isNotEmpty()) {
                appendLine("--- peers (${s.peers.size}) ---")
                s.peers.forEach { p ->
                    appendLine("  ${p.displayName ?: p.npub}: ${p.connectivity}, rtt=${p.srttMs}ms, tx=${p.packetsSent}, rx=${p.packetsRecv}")
                }
            }
            if (s.transports.isNotEmpty()) {
                appendLine("--- transports (${s.transports.size}) ---")
                s.transports.forEach { t ->
                    appendLine("  ${t.transportType}: ${t.state}, bind=${t.bindAddr}, mtu=${t.mtu}")
                }
            }
            appendLine(raw)
        }
        Log.i("FipsDump", dump)
        return dump
    }

    fun stopNode() {
        pollingJob?.cancel()
        val n = node
        node = null
        _state.value = FipsUiState()
        stopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                n?.stop()
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        stopNode()
        super.onCleared()
    }
}

data class FipsUiState(
    val status: DashboardStatus? = null,
    val peers: List<DashboardPeer> = emptyList(),
    val transports: List<DashboardTransport> = emptyList(),
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val error: String? = null,
)
