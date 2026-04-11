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
import uniffi.fips_mobile.identityFromNsec
import uniffi.fips_mobile.ipv6FromNsec
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

    init {
        // Show identity in UI immediately (before node starts)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nsec = loadOrCreateIdentity()
                val id = identityFromNsec(nsec)
                _state.update { it.copy(npub = id.npub) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load identity", e)
            }
        }
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
        // Generate new identity and show npub immediately
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nsec = loadOrCreateIdentity()
                val id = identityFromNsec(nsec)
                _state.update { it.copy(npub = id.npub) }
            } catch (_: Exception) {}
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
          enabled: true
          bind_addr: "10.1.1.1"
          port: 53
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
        _state.update { FipsUiState(npub = it.npub) }
        stopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                tunService?.stopTun()
            } catch (_: Exception) {}
            try {
                n?.stop()
            } catch (_: Exception) {}
        }
    }

    /** Store the bound TUN service reference. Called from Activity after binding. */
    fun setTunService(service: FipsTunService?) {
        tunService = service
    }

    /**
     * Start VPN + node in the correct order:
     *   1. Compute IPv6 from identity (no node needed)
     *   2. Establish VPN interface (creates VPN addresses)
     *   3. Start FIPS node (DNS responder can bind to VPN interface address)
     *   4. Attach TUN fd to node
     *
     * Call from Activity after VPN consent is granted.
     */
    fun startNode() {
        val svc = tunService ?: run {
            _state.update { it.copy(error = "TUN service not bound") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                // Wait for previous stop to finish (socket release)
                stopJob?.join()

                // 1. Get identity and compute IPv6 (no running node needed)
                val nsec = loadOrCreateIdentity()
                val ownIpv6 = ipv6FromNsec(nsec)

                // 2. Establish VPN — creates the interface
                val tunFd = svc.establishVpn(ownIpv6)
                if (tunFd < 0) {
                    _state.update { it.copy(isLoading = false, error = "VPN establish failed") }
                    return@launch
                }

                // 3. Start FIPS node — DNS responder can now bind to VPN address
                val configYaml = buildConfigYaml(nsec)
                val n = FipsMobileNode(configYaml)
                node = n

                // 4. Attach TUN fd to running node
                svc.attachNode(n)

                _state.update { it.copy(isLoading = false, isRunning = true) }
                startPolling()
            } catch (e: Exception) {
                Log.e(TAG, "startNode failed", e)
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    override fun onCleared() {
        stopNode()
        super.onCleared()
    }
}

data class FipsUiState(
    val npub: String? = null,
    val status: DashboardStatus? = null,
    val peers: List<DashboardPeer> = emptyList(),
    val transports: List<DashboardTransport> = emptyList(),
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val error: String? = null,
)
