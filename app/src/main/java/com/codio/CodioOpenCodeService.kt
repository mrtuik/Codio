package com.codio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CodioOpenCodeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: RuntimeManager

    override fun onCreate() {
        super.onCreate()
        manager = RuntimeManager(this, FileManager(this))
        createChannel()
        startForeground(NOTIFICATION_ID, notification("AI coding runtime is running"))
        serviceScope.launch {
            manager.start()
            while (isActive) {
                manager.health()
                delay(10_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        manager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Codio runtime", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Codio")
            .setContentText(text)
            .setSmallIcon(com.codio.R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "codio_runtime"
        private const val NOTIFICATION_ID = 1042
    }
}