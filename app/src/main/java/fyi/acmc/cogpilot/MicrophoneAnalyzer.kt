package fyi.acmc.cogpilot

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

/**
 * MicrophoneAnalyzer: Captures live audio and feeds it to the RiskDecisionEngine.
 * Uses VOICE_RECOGNITION source for concurrent capture support on Android 10+.
 */
class MicrophoneAnalyzer(private val riskEngine: RiskDecisionEngine) {

    companion object {
        private const val TAG = "MicrophoneAnalyzer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CAPTURE_WINDOW_MS = 2000L // 2 seconds
        private const val FEED_INTERVAL_MS = 5000L    // Every 5 seconds
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "🎙️ Real-time audio capture started (Concurrent Mode)")

            captureJob = scope.launch {
                runCaptureLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
        }
    }

    private suspend fun runCaptureLoop() {
        val samplesToCapture = SAMPLE_RATE * (CAPTURE_WINDOW_MS / 1000).toInt()
        val buffer = ShortArray(samplesToCapture)
        
        while (isRecording) {
            var totalRead = 0
            while (totalRead < samplesToCapture && isRecording) {
                val read = audioRecord?.read(buffer, totalRead, samplesToCapture - totalRead) ?: -1
                if (read > 0) {
                    totalRead += read
                } else {
                    yield()
                }
            }

            if (totalRead >= samplesToCapture) {
                // Convert to FloatArray normalized to [-1.0, 1.0]
                val floatBuffer = FloatArray(samplesToCapture)
                for (i in 0 until samplesToCapture) {
                    floatBuffer[i] = buffer[i] / 32768f
                }

                // Feed to engine
                riskEngine.updateOnnxAudioTelemetry(floatBuffer)
            }

            // Wait for next interval
            delay(FEED_INTERVAL_MS)
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping audio capture")
        isRecording = false
        captureJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
    }
}
