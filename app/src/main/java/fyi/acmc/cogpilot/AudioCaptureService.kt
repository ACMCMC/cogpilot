package fyi.acmc.cogpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * AudioCaptureService: Foreground service for background microphone access
 * Allows the app to listen to microphone input even when app is not in foreground
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "audio_capture_channel"
        private const val NOTIFICATION_ID = 42
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Audio capture service starting")
        
        // create notification channel if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background microphone access for CogPilot"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // persistent notification for foreground service
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CogPilot Active")
            .setContentText("Listening for voice commands")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Audio capture service stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
