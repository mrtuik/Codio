package com.tuik.rlaude

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

class RlaudeOpenCodeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: RuntimeManager

    override fun onCreate() {
        super.onCreate()
        manager = RuntimeManager(this, FileManager(this))
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Rlaude runtime active"))
        serviceScope.launch {
            manager.start()
            while (isActive) {
                manager.health()
                delay(10_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Rlaude runtime", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rlaude")
            .setContentText(text)
            .setSmallIcon(com.tuik.rlaude.R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_STOP = "com.tuik.rlaude.action.STOP_RUNTIME"
        private const val CHANNEL_ID = "rlaude_runtime"
        private const val NOTIFICATION_ID = 1042
    }
}