package fyi.acmc.cogpilot

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.*

interface IDrowsinessDetector {
    fun predict(features: FloatArray): Float
    fun close()
}

class OnnxDrowsinessDetector(private val context: Context) : IDrowsinessDetector, AutoCloseable {

    companion object {
        private const val TAG = "OnnxDrowsinessDetector"
        private const val MODEL_NAME = "sleepiness_detector.onnx"
        private const val NUM_FEATURES = 29
    }

    private var env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    init {
        try {
            val modelBytes = loadModelFromAssets(MODEL_NAME)
            session = env.createSession(modelBytes)
            Log.i(TAG, "✓ ONNX Session initialized for $MODEL_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize ONNX session: ${e.message}", e)
        }
    }

    /**
     * Predicts drowsiness probability from 29 acoustic features.
     * @param features Array of 29 acoustic features matching the training script.
     * @return Probability of the SLEEPY class (0.0 to 1.0).
     */
    override fun predict(features: FloatArray): Float {
        if (session == null || features.size != NUM_FEATURES) {
            Log.w(TAG, "Inference skipped: session=${session != null}, features=${features.size}")
            return 0.0f
        }

        return try {
            val shape = longArrayOf(1, NUM_FEATURES.toLong())
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)
            
            val result = session?.run(Collections.singletonMap("float_input", inputTensor))
            
            // Random Forest in skl2onnx typically returns:
            // results[0]: label (int64)
            // results[1]: sequence of maps (probabilities)
            
            // Extracting probability of SLEEPY (label 1)
            // The output format can vary slightly depending on how it was converted.
            // Based on convert_to_onnx.py output validation, results.get(1) should have the probs.
            
            val probabilities = result?.get(1)?.value as? List<Map<Long, Float>>
            val sleepyProb = probabilities?.get(0)?.get(1L) ?: 0.0f
            
            inputTensor.close()
            result?.close()
            
            sleepyProb
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inference failed: ${e.message}")
            0.0f
        }
    }

    private fun loadModelFromAssets(filename: String): ByteArray {
        return context.assets.open(filename).use { it.readBytes() }
    }

    override fun close() {
        try {
            session?.close()
            env.close()
            Log.i(TAG, "✓ ONNX resources closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX resources: ${e.message}")
        }
    }
}
