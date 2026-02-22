package fyi.acmc.cogpilot

import android.util.Log
import kotlin.math.*

/**
 * Extracts 29 acoustic features from PCM audio buffers.
 * Features: 
 * - Time Domain: Mean, Std, Mean Abs, Peak (4)
 * - Temporal: ZCR (1)
 * - Energy: Energy (1)
 * - Spectral: Centroid, Rolloff, Bandwidth (3)
 * - Mel-bins: 20 energy bins (20)
 * Total: 29
 */
class AudioFeatureExtractor {

    companion object {
        private const val TAG = "AudioFeatureExtractor"
        private const val SAMPLE_RATE = 16000f // Common for voice models
        private const val MEL_BINS = 20
    }

    /**
     * Extractions features from a float buffer (normalized -1.0 to 1.0).
     */
    fun extractFeatures(audio: FloatArray): FloatArray {
        val features = FloatArray(29)
        if (audio.isEmpty()) return features

        // 1. Time Domain Features
        var sum = 0f
        var sumAbs = 0f
        var peak = 0f
        for (sample in audio) {
            sum += sample
            sumAbs += abs(sample)
            if (abs(sample) > peak) peak = abs(sample)
        }
        val mean = sum / audio.size
        val meanAbs = sumAbs / audio.size
        
        var sumSqDiff = 0f
        for (sample in audio) {
            sumSqDiff += (sample - mean).pow(2)
        }
        val std = sqrt(sumSqDiff / audio.size)

        features[0] = mean
        features[1] = std
        features[2] = meanAbs
        features[3] = peak

        // 2. Zero Crossing Rate
        var zeroCrossings = 0
        for (i in 1 until audio.size) {
            if ((audio[i-1] >= 0 && audio[i] < 0) || (audio[i-1] < 0 && audio[i] >= 0)) {
                zeroCrossings++
            }
        }
        features[4] = zeroCrossings.toFloat() / audio.size

        // 3. Energy
        var energySum = 0f
        for (sample in audio) {
            energySum += sample * sample
        }
        val energy = energySum / audio.size
        features[5] = energy

        // 4. Spectral Features (requires FFT)
        // Find next power of 2 for FFT
        val n = 1 shl (32 - Integer.numberOfLeadingZeros(audio.size - 1))
        val real = FloatArray(n) { if (it < audio.size) audio[it] else 0f }
        val imag = FloatArray(n) { 0f }
        
        fft(real, imag)
        
        val magnitude = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            magnitude[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        // Spectral Centroid
        var weightedSum = 0f
        var magSum = 0f
        for (i in 0 until n / 2) {
            val freq = i * SAMPLE_RATE / n
            weightedSum += freq * magnitude[i]
            magSum += magnitude[i]
        }
        val spectralCentroid = if (magSum > 1e-10) weightedSum / magSum else 0f
        features[6] = spectralCentroid

        // Spectral Rolloff (85% frequency)
        val targetEnergy = 0.85f * magSum
        var cumulativeEnergy = 0f
        var rolloffFreq = 0f
        for (i in 0 until n / 2) {
            cumulativeEnergy += magnitude[i]
            if (cumulativeEnergy >= targetEnergy) {
                rolloffFreq = i * SAMPLE_RATE / n
                break
            }
        }
        features[7] = rolloffFreq

        // Spectral Bandwidth
        var bandwidthSum = 0f
        for (i in 0 until n / 2) {
            val freq = i * SAMPLE_RATE / n
            bandwidthSum += (freq - spectralCentroid).pow(2) * magnitude[i]
        }
        val spectralBandwidth = if (magSum > 1e-10) sqrt(bandwidthSum / magSum) else 0f
        features[8] = spectralBandwidth

        // 5. Mel-frequency binning (simplified)
        val binSize = (n / 2) / MEL_BINS
        for (i in 0 until MEL_BINS) {
            val start = i * binSize
            val end = if (i == MEL_BINS - 1) n / 2 else (i + 1) * binSize
            var binEnergy = 0f
            var binCount = 0
            for (j in start until end) {
                binEnergy += magnitude[j]
                binCount++
            }
            features[9 + i] = if (binCount > 0) binEnergy / binCount else 0f
        }

        return features
    }

    /**
     * Simple iterative Cooley-Tukey FFT implementation.
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var bitlen = 2
        while (bitlen <= n) {
            val ang = 2.0 * Math.PI / bitlen
            val wpr = cos(ang).toFloat()
            val wpi = -sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (k in 0 until bitlen / 2) {
                    val r = real[i + k + bitlen / 2]
                    val im = imag[i + k + bitlen / 2]
                    val tr = r * wr - im * wi
                    val ti = r * wi + im * wr
                    real[i + k + bitlen / 2] = real[i + k] - tr
                    imag[i + k + bitlen / 2] = imag[i + k] - ti
                    real[i + k] += tr
                    imag[i + k] += ti
                    val nextWr = wr * wpr - wi * wpi
                    wi = wr * wpi + wi * wpr
                    wr = nextWr
                }
                i += bitlen
            }
            bitlen *= 2
        }
    }
}
