package fyi.acmc.cogpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * VoiceAgentService: Background service managing ElevenLabs conversation
 * Triggers: button click, voice command, shortcut, attention drop detection
 * Manages mic, audio, and conversation lifecycle
 */
class VoiceAgentService : Service() {

    companion object {
        private const val TAG = "VoiceAgentService"
        private const val CHANNEL_ID = "voice_agent_channel"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "fyi.acmc.cogpilot.voice.START"
        const val ACTION_STOP = "fyi.acmc.cogpilot.voice.STOP"
        const val ACTION_STATUS_CHANGE = "fyi.acmc.cogpilot.voice.STATUS_CHANGE"
        const val EXTRA_STATUS = "status"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var conversationManager: ElevenLabsConversationManager
    private var isRunning = false

    inner class VoiceAgentBinder : Binder() {
        fun getService(): VoiceAgentService = this@VoiceAgentService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        conversationManager = ElevenLabsConversationManager(this)
        setupCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")
        
        createNotificationChannel()
        val notification = createNotification("Initializing...")
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START -> startVoiceSession()
            ACTION_STOP -> stopVoiceSession()
            else -> Log.d(TAG, "Unknown action: ${intent?.action}")
        }

        return START_STICKY
    }

    private fun setupCallbacks() {
        conversationManager.onConnect = { conversationId ->
            Log.i(TAG, "✓ Connected: $conversationId")
            updateNotification("🎙️ Listening...")
            broadcastStatus("connected")
        }

        conversationManager.onStatusChange = { status ->
            Log.i(TAG, "Status: $status")
            val notifText = when (status) {
                "connected" -> "🎙️ Listening..."
                "disconnected" -> "Voice session ended"
                else -> "Connecting..."
            }
            updateNotification(notifText)
            broadcastStatus(status)

            if (status == "disconnected") {
                isRunning = false
                stopSelf()
            }
        }

        conversationManager.onModeChange = { mode ->
            Log.d(TAG, "Mode: $mode")
            val notifText = when (mode) {
                "speaking" -> "🤖 Agent speaking..."
                "listening" -> "🎙️ Listening..."
                else -> mode
            }
            updateNotification(notifText)
        }

        conversationManager.onVadScore = { score ->
            // Log VAD score for debugging; could trigger UI feedback
            if (score > 0.5f) {
                Log.d(TAG, "Voice detected: $score")
            }
        }

        conversationManager.onError = { error ->
            Log.e(TAG, "Conversation error: $error")
            updateNotification("❌ Error: $error")
            broadcastStatus("error")
            stopSelf()
        }
    }

    private fun startVoiceSession() {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        Log.i(TAG, "Starting voice session...")
        isRunning = true

        serviceScope.launch {
            try {
                val success = conversationManager.startConversation(
                    agentId = BuildConfig.ELEVENLABS_AGENT_ID,
                    userId = "driver"
                )
                if (!success) {
                    Log.e(TAG, "Failed to start conversation")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start error: ${e.message}", e)
                stopSelf()
            }
        }
    }

    private fun stopVoiceSession() {
        Log.i(TAG, "Stopping voice session...")
        isRunning = false
        conversationManager.stopConversation()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time voice conversation with AI agent"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CogPilot Voice Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = createNotification(text)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(ACTION_STATUS_CHANGE).apply {
            putExtra(EXTRA_STATUS, status)
            setPackage(packageName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        conversationManager.dispose()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = VoiceAgentBinder()
}
