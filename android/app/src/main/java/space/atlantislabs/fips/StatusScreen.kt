package space.atlantislabs.fips

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.atlantislabs.fips.model.DashboardPeer
import space.atlantislabs.fips.model.DashboardStatus
import space.atlantislabs.fips.model.DashboardTransport

private val DarkBg = Color(0xFF0A0A0A)
private val CardBg = Color(0xFF1A1A1A)
private val AccentGreen = Color(0xFF4CAF50)
private val AccentRed = Color(0xFFF44336)
private val AccentYellow = Color(0xFFFFEB3B)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)

@Composable
fun StatusScreen(viewModel: FipsViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 16.dp),
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Error banner
        state.error?.let { error ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1111)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = error,
                        color = AccentRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Header with start/stop + debug dump
        item {
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("FIPS Node", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.status?.npub?.let { formatNpub(it) } ?: "not started",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val dump = viewModel.dumpState()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("FIPS Debug", dump))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    ) {
                        Text("Debug")
                    }
                    Button(
                        onClick = { if (state.isRunning) viewModel.stopNode() else viewModel.startNode() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isRunning) AccentRed else AccentGreen
                        ),
                        enabled = !state.isLoading,
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                        } else {
                            Text(if (state.isRunning) "Stop" else "Start")
                        }
                    }
                }
            }
        }

        // Status card
        state.status?.let { status ->
            item { StatusCard(status) }
        }

        // Peers card
        if (state.peers.isNotEmpty()) {
            item {
                Text("Peers", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(state.peers) { peer ->
                PeerRow(peer)
            }
        }

        // Transports card
        if (state.transports.isNotEmpty()) {
            item {
                Text("Transports", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(state.transports) { transport ->
                TransportRow(transport)
            }
        }
    }
}

@Composable
private fun StatusCard(status: DashboardStatus) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusRow("State", status.state ?: "unknown", connectivityColor(status.state))
            status.version?.let { StatusRow("Version", it) }
            StatusRow("Uptime", formatUptime(status.uptimeSecs))
            StatusRow("Mode", if (status.isLeafOnly) "leaf" else "relay")
            StatusRow("Peers", "${status.peerCount}")
            StatusRow("Links", "${status.linkCount}")
            StatusRow("Sessions", "${status.sessionCount}")
            status.estimatedMeshSize?.let { StatusRow("Mesh size", "~$it") }

            status.forwarding?.let { fwd ->
                HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
                Text("Forwarding", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                StatusRow("Forwarded", "${fwd.forwardedPackets} pkts")
                StatusRow("Originated", "${fwd.originatedPackets} pkts")
                StatusRow("Delivered", "${fwd.deliveredPackets} pkts")
                StatusRow("Received", "${fwd.receivedPackets} pkts")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PeerRow(peer: DashboardPeer) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(qualityColor(peer.lossRate))
                    )
                    Column {
                        Text(
                            text = peer.displayName ?: peer.npub?.let { formatNpub(it) } ?: "unknown",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = peerRole(peer),
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    text = peer.connectivity ?: "unknown",
                    color = connectivityColor(peer.connectivity),
                    fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                peer.srttMs?.let {
                    Text("RTT: ${String.format("%.0f", it)}ms", color = TextSecondary, fontSize = 11.sp)
                }
                Text("TX: ${formatBytes(peer.bytesSent)}", color = TextSecondary, fontSize = 11.sp)
                Text("RX: ${formatBytes(peer.bytesRecv)}", color = TextSecondary, fontSize = 11.sp)
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))
                    peer.treeDepth?.let { StatusRow("Tree depth", "$it") }
                    peer.lossRate?.let { StatusRow("Loss", "${String.format("%.1f", it * 100)}%") }
                    peer.mmp?.etx?.let { StatusRow("ETX", String.format("%.2f", it)) }
                    peer.mmp?.goodputBps?.let { StatusRow("Goodput", "${formatBytes(it.toLong())}/s") }
                    peer.mmp?.deliveryRatioForward?.let { StatusRow("Delivery (fwd)", "${String.format("%.0f", it * 100)}%") }
                    peer.mmp?.deliveryRatioReverse?.let { StatusRow("Delivery (rev)", "${String.format("%.0f", it * 100)}%") }
                    StatusRow("Packets TX/RX", "${peer.packetsSent} / ${peer.packetsRecv}")
                    peer.transportType?.let { t ->
                        StatusRow("Transport", "$t${peer.transportAddr?.let { " @ $it" } ?: ""}")
                    }
                    peer.npub?.let {
                        Text(it, color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportRow(transport: DashboardTransport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = transport.transportType?.uppercase() ?: "?",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    transport.bindAddr?.let {
                        Text(it, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = transport.state ?: "unknown",
                        color = connectivityColor(transport.state),
                        fontSize = 12.sp,
                    )
                    transport.mtu?.let {
                        Text("MTU: $it", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
            transport.stats?.let { stats ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("TX: ${formatBytes(stats.bytesSent)} (${stats.packetsSent} pkts)", color = TextSecondary, fontSize = 11.sp)
                    Text("RX: ${formatBytes(stats.bytesRecv)} (${stats.packetsRecv} pkts)", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun connectivityColor(state: String?): Color = when (state?.lowercase()) {
    "running", "active", "established" -> AccentGreen
    "starting", "connecting", "created" -> AccentYellow
    else -> AccentRed
}

private fun qualityColor(lossRate: Double?): Color = when {
    lossRate == null -> TextSecondary
    lossRate < 0.02 -> AccentGreen
    lossRate < 0.10 -> AccentYellow
    else -> AccentRed
}

private fun peerRole(peer: DashboardPeer): String = buildString {
    when {
        peer.isParent -> append("parent")
        peer.isChild -> append("child")
        else -> append("peer")
    }
    peer.treeDepth?.let { append(" · depth $it") }
}

private fun formatNpub(npub: String): String {
    if (npub.length <= 20) return npub
    return "${npub.take(10)}...${npub.takeLast(6)}"
}

private fun formatUptime(secs: Long?): String {
    if (secs == null) return "-"
    val d = secs / 86400
    val h = (secs % 86400) / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0 || d > 0) append("${h}h ")
        if (m > 0 || h > 0 || d > 0) append("${m}m ")
        append("${s}s")
    }.trim()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "${bytes}B"
    bytes < 1_048_576 -> "${String.format("%.1f", bytes / 1_024.0)}KB"
    bytes < 1_073_741_824 -> "${String.format("%.1f", bytes / 1_048_576.0)}MB"
    else -> "${String.format("%.2f", bytes / 1_073_741_824.0)}GB"
}
