package space.atlantislabs.fips.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DashboardStatus(
    val version: String? = null,
    val npub: String? = null,
    @SerialName("node_addr") val nodeAddr: String? = null,
    @SerialName("ipv6_addr") val ipv6Addr: String? = null,
    val state: String? = null,
    @SerialName("uptime_secs") val uptimeSecs: Long? = null,
    @SerialName("is_leaf_only") val isLeafOnly: Boolean = false,
    @SerialName("peer_count") val peerCount: Int = 0,
    @SerialName("link_count") val linkCount: Int = 0,
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("estimated_mesh_size") val estimatedMeshSize: Int? = null,
    val forwarding: ForwardingStats? = null,
)

@Serializable
data class ForwardingStats(
    @SerialName("forwarded_packets") val forwardedPackets: Long = 0,
    @SerialName("originated_packets") val originatedPackets: Long = 0,
    @SerialName("delivered_packets") val deliveredPackets: Long = 0,
    @SerialName("received_packets") val receivedPackets: Long = 0,
)

@Serializable
data class DashboardPeersResponse(
    val peers: List<DashboardPeer> = emptyList(),
)

@Serializable
data class DashboardTransportsResponse(
    val transports: List<DashboardTransport> = emptyList(),
)

@Serializable
data class DashboardPeer(
    @SerialName("display_name") val displayName: String? = null,
    val npub: String? = null,
    @SerialName("node_addr") val nodeAddr: String? = null,
    val connectivity: String? = null,
    @SerialName("is_parent") val isParent: Boolean = false,
    @SerialName("is_child") val isChild: Boolean = false,
    @SerialName("link_id") val linkId: Int? = null,
    val mmp: PeerMmp? = null,
    val stats: PeerStats? = null,
    @SerialName("transport_addr") val transportAddr: String? = null,
    @SerialName("transport_type") val transportType: String? = null,
    @SerialName("tree_depth") val treeDepth: Int? = null,
) {
    val srttMs: Double? get() = mmp?.srttMs
    val lossRate: Double? get() = mmp?.lossRate
    val packetsSent: Long get() = stats?.packetsSent ?: 0
    val packetsRecv: Long get() = stats?.packetsRecv ?: 0
    val bytesSent: Long get() = stats?.bytesSent ?: 0
    val bytesRecv: Long get() = stats?.bytesRecv ?: 0
}

@Serializable
data class PeerMmp(
    @SerialName("srtt_ms") val srttMs: Double? = null,
    @SerialName("loss_rate") val lossRate: Double? = null,
    @SerialName("delivery_ratio_forward") val deliveryRatioForward: Double? = null,
    @SerialName("delivery_ratio_reverse") val deliveryRatioReverse: Double? = null,
    val etx: Double? = null,
    @SerialName("goodput_bps") val goodputBps: Double? = null,
)

@Serializable
data class PeerStats(
    @SerialName("packets_sent") val packetsSent: Long = 0,
    @SerialName("packets_recv") val packetsRecv: Long = 0,
    @SerialName("bytes_sent") val bytesSent: Long = 0,
    @SerialName("bytes_recv") val bytesRecv: Long = 0,
)

@Serializable
data class DashboardTransport(
    @SerialName("transport_id") val id: Int? = null,
    val type: String? = null,
    val state: String? = null,
    val mtu: Int? = null,
    @SerialName("local_addr") val localAddr: String? = null,
    val stats: TransportStats? = null,
) {
    val transportType: String? get() = type
    val bindAddr: String? get() = localAddr
    val packetsSent: Long get() = stats?.packetsSent ?: 0
    val packetsRecv: Long get() = stats?.packetsRecv ?: 0
}

@Serializable
data class TransportStats(
    @SerialName("packets_sent") val packetsSent: Long = 0,
    @SerialName("packets_recv") val packetsRecv: Long = 0,
    @SerialName("bytes_sent") val bytesSent: Long = 0,
    @SerialName("bytes_recv") val bytesRecv: Long = 0,
)
