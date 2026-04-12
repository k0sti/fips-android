package space.atlantislabs.fips.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import space.atlantislabs.fips.R
import uniffi.fips_mobile.FipsMobileNode

/**
 * Android VpnService that creates a TUN interface and routes fd00::/8
 * (FIPS mesh) traffic through the Rust node.
 *
 * Lifecycle (VPN-first):
 *   1. Activity calls VpnService.prepare() for user consent
 *   2. Activity binds to this service
 *   3. establishVpn(ownIpv6) creates the TUN interface (before node starts)
 *   4. Node starts, attachNode(node) passes the TUN fd to the running node
 *   5. DNS queries to 10.1.1.1:53 are intercepted by TUN reader (dns_intercept)
 *   6. stopTun() or onRevoke() tears down
 *
 * The app is excluded from VPN routing via addDisallowedApplication(),
 * so transport sockets bypass the VPN without needing protect() calls.
 */
class FipsTunService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var node: FipsMobileNode? = null

    inner class LocalBinder : Binder() {
        val service: FipsTunService get() = this@FipsTunService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * Establish the VPN interface. Call BEFORE starting the FIPS node.
     *
     * @param ownIpv6 This node's fd00:: IPv6 address (computed from nsec).
     * @return The raw TUN file descriptor, or -1 on failure.
     */
    fun establishVpn(ownIpv6: String): Int {
        if (tunFd != null) {
            Log.w(TAG, "VPN already established")
            return tunFd!!.fd
        }

        // Split-tunnel: fd00::/8 goes through mesh.
        // DNS: queries to 10.1.1.1:53 are routed through TUN and intercepted
        // by the Rust TUN reader (dns_intercept module). No local address needed.
        val builder = Builder()
            .setSession("FIPS Mesh")
            .addAddress(ownIpv6, 128)
            .addRoute("fd00::", 8)
            // Route DNS traffic through TUN (no local address — packets reach TUN reader)
            .addRoute("10.0.0.0", 8)
            .setMtu(MTU)
            .setBlocking(true)
            // Primary DNS: resolved via TUN-level interception in Rust
            .addDnsServer(DNS_ADDR)

        // Add system DNS servers as fallback for non-.fips queries
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach { dns ->
                val addr = dns.hostAddress
                if (addr != null && addr != DNS_ADDR && addr != "127.0.0.1") {
                    builder.addDnsServer(addr)
                    Log.d(TAG, "Added system DNS fallback: $addr")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system DNS servers", e)
        }

        // Last-resort public DNS
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        // Exclude our own app — transport sockets go over real network,
        // no protect() calls needed.
        builder.addDisallowedApplication(packageName)

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "VPN establish() returned null — consent not granted?")
            return -1
        }

        tunFd = fd

        // Start foreground notification (required for VPN services)
        startForeground(NOTIFICATION_ID, createNotification())

        Log.i(TAG, "VPN established: fd=${fd.fd}, address=$ownIpv6")
        return fd.fd
    }

    /**
     * Attach a running node and pass the TUN fd to it.
     * Call AFTER establishVpn() and node creation.
     */
    fun attachNode(fipsNode: FipsMobileNode) {
        val fd = tunFd ?: run {
            Log.e(TAG, "attachNode called but VPN not established")
            return
        }

        node = fipsNode

        // Dup the fd for Rust — Android keeps the original ParcelFileDescriptor
        // so closing it in stopTun() properly tears down the VPN interface.
        val rustFd = fd.dup().detachFd()
        try {
            fipsNode.startTun(rustFd)
            Log.i(TAG, "TUN attached to node on rustFd=$rustFd")
        } catch (e: Exception) {
            Log.e(TAG, "startTun failed", e)
            stopTun()
        }
    }

    /**
     * Stop TUN and tear down the VPN interface.
     */
    fun stopTun() {
        if (tunFd == null && node == null) return // already stopped

        // Stop Rust TUN threads first
        try {
            node?.stopTun()
        } catch (e: Exception) {
            Log.w(TAG, "stopTun error", e)
        }
        node = null

        // Close ParcelFileDescriptor — signals Android to tear down VPN interface
        tunFd?.close()
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "TUN stopped")
    }

    /**
     * Called when the user revokes VPN permission from system settings.
     */
    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked")
        stopTun()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTun()
        super.onDestroy()
    }

    private fun createNotification(): android.app.Notification {
        val channel = NotificationChannel(
            FipsNodeService.CHANNEL_ID,
            "FIPS Node",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, FipsNodeService.CHANNEL_ID)
            .setContentTitle("FIPS VPN Active")
            .setContentText("Routing fd00::/8 through mesh")
            .setSmallIcon(R.drawable.ic_mesh)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FipsTunService"
        private const val MTU = 1280
        private const val NOTIFICATION_ID = 2 // Different from FipsNodeService
        // IPv4 address for DNS interception — Android sends DNS queries here,
        // TUN reader intercepts and resolves .fips names in Rust.
        private const val DNS_ADDR = "10.1.1.1"
    }
}
