package space.atlantislabs.fips.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import space.atlantislabs.fips.R
import uniffi.fips_mobile.FipsMobileNode

class FipsNodeService : Service() {

    private var node: FipsMobileNode? = null

    inner class LocalBinder : Binder() {
        fun getNode(): FipsMobileNode? = node
        fun setNode(n: FipsMobileNode) {
            node = n
        }
    }

    private val binder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FIPS Node",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FIPS Node Running")
            .setContentText("Connected to mesh network")
            .setSmallIcon(R.drawable.ic_mesh)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            node?.stop()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "fips_node"
        const val NOTIFICATION_ID = 1
    }
}
