package space.atlantislabs.fips

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import space.atlantislabs.fips.model.DashboardPeer
import space.atlantislabs.fips.model.DashboardPeersResponse
import space.atlantislabs.fips.model.DashboardStatus
import space.atlantislabs.fips.model.DashboardTransport
import space.atlantislabs.fips.model.DashboardTransportsResponse
import space.atlantislabs.fips.service.FipsTunService
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
import uniffi.fips_mobile.generateIdentity
import android.util.Log
import java.io.File

private const val TAG = "FipsViewModel"
private const val IDENTITY_FILE = "fips.nsec"

class FipsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(FipsUiState())
    val state: StateFlow<FipsUiState> = _state.asStateFlow()

    private var node: FipsMobileNode? = null
    private var pollingJob: Job? = null
    private var stopJob: Job? = null
    private var tunService: FipsTunService? = null

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val identityFile: File
        get() = File(getApplication<Application>().filesDir, IDENTITY_FILE)

    /** Load nsec from disk, or generate and persist a new one. */
    private fun loadOrCreateIdentity(): String {
        val file = identityFile
        if (file.exists()) {
            val nsec = file.readText().trim()
            if (nsec.startsWith("nsec1")) {
                Log.i(TAG, "Loaded persistent identity from ${file.path}")
                return nsec
            }
        }
        val identity = generateIdentity()
        file.writeText(identity.nsec)
        Log.i(TAG, "Generated new identity: ${identity.npub}")
        return identity.nsec
    }

    /** Delete identity file and stop node. Next startNode() gets a new keypair. */
    fun regenerateIdentity() {
        stopNode()
        val deleted = identityFile.delete()
        Log.i(TAG, "Identity file deleted: $deleted")
    }

    fun startNode() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                // Wait for previous stop to finish (socket release)
                stopJob?.join()
                val nsec = loadOrCreateIdentity()
                val configYaml = buildConfigYaml(nsec)
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

    private fun buildConfigYaml(nsec: String): String = """
        node:
          identity:
            nsec: "$nsec"
          control:
            enabled: false
        tun:
          enabled: true
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
            appendLine("running: ${s.isRunning}, loading: ${s.isLoading}, vpn: ${s.vpnActive}")
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
        stopVpn()
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

    /** Store the bound TUN service reference. Called from Activity after binding. */
    fun setTunService(service: FipsTunService?) {
        tunService = service
    }

    /** Start VPN — call from Activity after VPN consent is granted. */
    fun startVpn() {
        val n = node ?: return
        val svc = tunService ?: run {
            _state.update { it.copy(error = "TUN service not bound") }
            return
        }
        val ipv6 = _state.value.status?.ipv6Addr
        if (ipv6.isNullOrBlank()) {
            _state.update { it.copy(error = "No IPv6 address — is the node running?") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                svc.startTun(n, ipv6)
                _state.update { it.copy(vpnActive = true) }
            } catch (e: Exception) {
                Log.e(TAG, "startVpn failed", e)
                _state.update { it.copy(error = "VPN: ${e.message}") }
            }
        }
    }

    /** Stop VPN. */
    fun stopVpn() {
        _state.update { it.copy(vpnActive = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                tunService?.stopTun()
            } catch (e: Exception) {
                Log.w(TAG, "stopVpn error", e)
            }
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
    val vpnActive: Boolean = false,
    val error: String? = null,
)
