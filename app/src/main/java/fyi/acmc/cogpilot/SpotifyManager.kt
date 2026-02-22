package fyi.acmc.cogpilot

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.media.AudioManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpotifyManager(private val context: Context) {

    private val TAG = "SpotifyManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var wasPlayingBeforeVoice: Boolean = true  // Default to TRUE - assume music was playing unless proven otherwise

    private val PLAYLISTS = mapOf(
        "energetic"  to "spotify:playlist:37i9dQZF1DXdPec7aLTmlC",
        "uplifting"  to "spotify:playlist:37i9dQZF1DXdPec7aLTmlC",
        "upbeat"     to "spotify:playlist:37i9dQZF1DX0XUsuxWHRQd",
        "calm"       to "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ",
        "chill"      to "spotify:playlist:37i9dQZF1DX4WYpdgoIcn6",
        "focus"      to "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ",
        "driving"    to "spotify:playlist:37i9dQZF1DWToT4CWx0ciy",
        "pop"        to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
        "wake up"    to "spotify:playlist:37i9dQZF1DX0XUsuxWHRQd",
        "sleepy"     to "spotify:playlist:37i9dQZF1DX4WYpdgoIcn6"
    )

    private fun sendMediaKeyEvent(keyCode: Int) {
        Log.d(TAG, "Sending media key event: $keyCode")
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        audioManager.dispatchMediaKeyEvent(event)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(eventUp)
    }

    fun authorize() {
        Log.d(TAG, "Using Android media controls - no authorization needed")
    }

    fun disconnect() {
        Log.d(TAG, "Using Android media controls - no disconnect needed")
    }

    suspend fun fadeOutAndPause(durationMs: Long = 5000L) {
        Log.i(TAG, "🎵 fadeOutAndPause: Using Android media controls")
        wasPlayingBeforeVoice = true  // Assume music was playing
        
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "📊 Current volume: $originalVolume")
        
        if (originalVolume == 0) {
            Log.d(TAG, "🎵 Volume already at 0, just pausing")
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            return
        }

        Log.i(TAG, "🎵 Fading out over ${durationMs}ms (from volume $originalVolume to 0)...")
        val steps = 20
        val stepDuration = durationMs / steps
        val volumeStep = originalVolume.toFloat() / steps

        try {
            for (i in 1..steps) {
                val newVol = (originalVolume - (volumeStep * i)).toInt().coerceAtLeast(0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                kotlinx.coroutines.delay(stepDuration)
            }
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            Log.i(TAG, "🎵 Music paused via media control")
        } finally {
            // Restore volume immediately after pause
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            Log.d(TAG, "Volume restored to $originalVolume")
        }
    }        

    suspend fun resumeIfNeeded() {
        Log.i(TAG, "🎵 resumeIfNeeded() called: wasPlayingBeforeVoice=$wasPlayingBeforeVoice")
        
        if (!wasPlayingBeforeVoice) {
            Log.d(TAG, "🎵 resumeIfNeeded: wasPlayingBeforeVoice=false, skipping resume")
            return
        }
        
        Log.i(TAG, "🎵 Resuming playback via Android media control...")
        
        // Set volume back to max first
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "Setting volume to max: $maxVolume")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}", e)
        }
        
        // Resume playback via media control
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
        Log.i(TAG, "✅ Resume media control sent")
        
        wasPlayingBeforeVoice = false  // Reset flag after resume attempt
        Log.i(TAG, "✅ Resume operation completed")
    }
}

