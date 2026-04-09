package space.atlantislabs.fips.service

import android.content.Intent
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
 * Lifecycle:
 *   1. Activity calls VpnService.prepare() for user consent
 *   2. Activity binds to this service and calls startTun(node)
 *   3. This service calls Builder.establish() → fd
 *   4. Passes detached fd to node.startTun(fd)
 *   5. stopTun() or onRevoke() tears down
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
     * Create the TUN interface and start routing packets through the node.
     *
     * @param fipsNode The running FipsMobileNode instance.
     * @param ownIpv6 This node's fd00:: IPv6 address (from show_status ipv6_addr).
     */
    fun startTun(fipsNode: FipsMobileNode, ownIpv6: String) {
        if (tunFd != null) {
            Log.w(TAG, "TUN already active")
            return
        }

        node = fipsNode

        // Build the TUN interface
        val builder = Builder()
            .setSession("FIPS Mesh")
            .addAddress(ownIpv6, 128)
            .addRoute("fd00::", 8)
            .setMtu(MTU)
            .setBlocking(true)

        // Protect the node's own UDP socket from the VPN routing loop.
        // Without this, outbound mesh UDP packets would loop back through the TUN.

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "VPN establish() returned null — consent not granted?")
            return
        }

        tunFd = fd

        // Start foreground notification (required for VPN services)
        startForeground(NOTIFICATION_ID, createNotification())

        // Pass the raw fd to Rust. detachFd() transfers ownership.
        val rawFd = fd.detachFd()
        try {
            fipsNode.startTun(rawFd)
            Log.i(TAG, "TUN started on fd=$rawFd, address=$ownIpv6")
        } catch (e: Exception) {
            Log.e(TAG, "startTun failed", e)
            stopSelf()
        }
    }

    /**
     * Stop TUN and tear down the VPN interface.
     */
    fun stopTun() {
        try {
            node?.stopTun()
        } catch (e: Exception) {
            Log.w(TAG, "stopTun error", e)
        }
        node = null
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

    private fun createNotification() =
        NotificationCompat.Builder(this, FipsNodeService.CHANNEL_ID)
            .setContentTitle("FIPS VPN Active")
            .setContentText("Routing fd00::/8 through mesh")
            .setSmallIcon(R.drawable.ic_mesh)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "FipsTunService"
        private const val MTU = 1280
        private const val NOTIFICATION_ID = 2 // Different from FipsNodeService
    }
}
