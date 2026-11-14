package com.madida

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fully local, language-independent voice-pattern matcher.
 *
 * The speaker enrolls several isolated examples of every contact name. At call time the app
 * compares the new utterance with those samples using MFCC features + Dynamic Time Warping.
 * This deliberately avoids transcription: the app only needs to choose from the local contact
 * list, not understand free-form Kabyle conversation.
 *
 * Thresholds are conservative starting values and must be validated with this speaker/device.
 */
internal object LocalVoiceMatcher {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_LENGTH = 400       // 25 ms
    private const val FRAME_HOP = 160          // 10 ms
    private const val FFT_SIZE = 512
    private const val MEL_FILTERS = 26
    private const val CEPSTRA = 13
    private const val MAX_DISTANCE = 0.82
    private const val MIN_MARGIN = 0.10
    private const val MIN_NAME_SAMPLES = 3
    private const val MAX_REFERENCES_PER_DECISION = 3

    private data class CacheEntry(val modified: Long, val size: Long, val features: Array<FloatArray>)
    private val cache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)

    // Removing an audio sample also removes its cached acoustic features.
    fun invalidate(path: String) {
        synchronized(cache) { cache.remove(File(path).absolutePath) }
    }

    data class ContactDecision(
        val contact: FamilyContact? = null,
        val status: String,
        val bestDistance: Double? = null,
        val margin: Double? = null
    )

    fun identify(audioFile: File, contacts: List<FamilyContact>): ContactDecision {
        val query = getFeatures(audioFile) ?: return ContactDecision(status = "audio_invalid")
        val scores = contacts.mapNotNull { contact ->
            if (contact.samplePaths.size < MIN_NAME_SAMPLES) return@mapNotNull null
            val distances = contact.samplePaths.mapNotNull sampleLoop@{ path ->
                val reference = getFeatures(File(path)) ?: return@sampleLoop null
                dtwDistance(query, reference)
            }.sorted()
            // A single damaged/missing file should not invalidate the other enrolled examples.
            if (distances.size < 2) null else contact to averageBest(distances)
        }.sortedBy { it.second }

        if (scores.isEmpty()) return ContactDecision(status = "need_enrollment")
        val best = scores.first()
        val secondDistance = scores.getOrNull(1)?.second ?: Double.POSITIVE_INFINITY
        val margin = secondDistance - best.second

        if (best.second > MAX_DISTANCE) {
            return ContactDecision(status = "no_match", bestDistance = best.second, margin = margin)
        }
        if (margin < MIN_MARGIN) {
            return ContactDecision(status = "ambiguous", bestDistance = best.second, margin = margin)
        }
        return ContactDecision(contact = best.first, status = "match", bestDistance = best.second, margin = margin)
    }

    /** Returns yes, no or unclear after comparing the reply with locally enrolled reply samples. */
    fun classifyYesNo(audioFile: File, responses: VoiceResponses): String {
        if (responses.yesPaths.size < 2 || responses.noPaths.size < 2) return "unclear"
        val query = getFeatures(audioFile) ?: return "unclear"
        val yes = scoreAgainst(query, responses.yesPaths) ?: return "unclear"
        val no = scoreAgainst(query, responses.noPaths) ?: return "unclear"
        val difference = abs(yes - no)
        if (min(yes, no) > MAX_DISTANCE || difference < MIN_MARGIN) return "unclear"
        return if (yes < no) "yes" else "no"
    }

    private fun scoreAgainst(query: Array<FloatArray>, paths: List<String>): Double? {
        val distances = paths.mapNotNull { getFeatures(File(it))?.let { ref -> dtwDistance(query, ref) } }.sorted()
        return if (distances.size < 2) null else averageBest(distances)
    }

    private fun averageBest(distances: List<Double>): Double {
        val count = min(MAX_REFERENCES_PER_DECISION, distances.size)
        return distances.take(count).average()
    }

    private fun getFeatures(file: File): Array<FloatArray>? {
        if (!file.exists() || file.length() < 1000) return null
        synchronized(cache) {
            val cached = cache[file.absolutePath]
            if (cached != null && cached.modified == file.lastModified() && cached.size == file.length()) {
                return cached.features
            }
        }
        val samples = readPcmWav(file) ?: return null
        val features = extractMfcc(samples) ?: return null
        synchronized(cache) {
            cache[file.absolutePath] = CacheEntry(file.lastModified(), file.length(), features)
            while (cache.size > 2500) cache.entries.iterator().run { if (hasNext()) { next(); remove() } }
        }
        return features
    }

    private fun readPcmWav(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(buffer.remaining() / 2) { buffer.short / 32768f }
            trimSilence(samples)
        } catch (_: Exception) {
            null
        }
    }

    private fun trimSilence(input: FloatArray): FloatArray? {
        if (input.size < SAMPLE_RATE / 8) return null
        val block = 320
        var peak = 0f
        var i = 0
        while (i < input.size) {
            var sum = 0.0
            val end = min(input.size, i + block)
            for (j in i until end) sum += input[j] * input[j]
            peak = max(peak, sqrt((sum / (end - i)).toFloat()))
            i += block
        }
        if (peak < 0.003f) return null
        val threshold = max(0.0025f, peak * 0.055f)
        var first = 0
        while (first + block < input.size && rms(input, first, min(input.size, first + block)) < threshold) first += block
        var last = input.size
        while (last - block > first && rms(input, max(first, last - block), last) < threshold) last -= block
        val pad = SAMPLE_RATE / 10
        first = max(0, first - pad)
        last = min(input.size, last + pad)
        if (last - first < SAMPLE_RATE / 8) return null
        return input.copyOfRange(first, last)
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        var sum = 0.0
        for (i in start until end) sum += samples[i] * samples[i]
        return sqrt((sum / max(1, end - start)).toFloat())
    }

    private fun extractMfcc(samples: FloatArray): Array<FloatArray>? {
        if (samples.size < FRAME_LENGTH) return null
        val frames = 1 + (samples.size - FRAME_LENGTH) / FRAME_HOP
        if (frames < 5) return null
        val filters = melFilterBank()
        val output = Array(frames) { FloatArray(CEPSTRA - 1) }
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)

        for (frame in 0 until frames) {
            val start = frame * FRAME_HOP
            for (i in 0 until FFT_SIZE) {
                if (i < FRAME_LENGTH) {
                    val current = samples[start + i]
                    val previous = if (start + i > 0) samples[start + i - 1] else 0f
                    val emphasized = current - 0.97f * previous
                    val window = 0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_LENGTH - 1))
                    real[i] = emphasized * window
                } else {
                    real[i] = 0.0
                }
                imag[i] = 0.0
            }
            fft(real, imag)
            val power = DoubleArray(FFT_SIZE / 2 + 1) { index ->
                (real[index] * real[index] + imag[index] * imag[index]) / FFT_SIZE
            }
            val energies = DoubleArray(MEL_FILTERS)
            for (m in 0 until MEL_FILTERS) {
                var energy = 0.0
                for (k in power.indices) energy += power[k] * filters[m][k]
                energies[m] = ln(max(energy, 1e-10))
            }
            for (c in 1 until CEPSTRA) {
                var value = 0.0
                for (m in 0 until MEL_FILTERS) {
                    value += energies[m] * cos(PI * c * (m + 0.5) / MEL_FILTERS)
                }
                output[frame][c - 1] = value.toFloat()
            }
        }
        // Cepstral mean/variance normalisation reduces recording-volume and channel differences.
        val dimensions = CEPSTRA - 1
        for (d in 0 until dimensions) {
            val mean = output.sumOf { it[d].toDouble() } / output.size
            var variance = 0.0
            for (vector in output) variance += (vector[d] - mean).pow(2)
            val std = sqrt(variance / output.size).coerceAtLeast(1e-4)
            for (vector in output) vector[d] = ((vector[d] - mean) / std).toFloat()
        }
        return output
    }

    private fun melFilterBank(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
        val low = hzToMel(40.0)
        val high = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(MEL_FILTERS + 2) { index ->
            melToHz(low + (high - low) * index / (MEL_FILTERS + 1))
        }
        val bins = points.map { floorToInt(it * (FFT_SIZE + 1) / SAMPLE_RATE).coerceIn(0, FFT_SIZE / 2) }
        return Array(MEL_FILTERS) { filter ->
            DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val left = bins[filter]
                val center = bins[filter + 1]
                val right = bins[filter + 2]
                when {
                    bin < left || bin > right -> 0.0
                    bin <= center -> if (center == left) 0.0 else (bin - left).toDouble() / (center - left)
                    else -> if (right == center) 0.0 else (right - bin).toDouble() / (right - center)
                }
            }
        }
    }

    private fun floorToInt(value: Double): Int = kotlin.math.floor(value).toInt()

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + length / 2] * wr - imag[i + k + length / 2] * wi
                    val vI = real[i + k + length / 2] * wi + imag[i + k + length / 2] * wr
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + length / 2] = uR - vR
                    imag[i + k + length / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += length
            }
            length = length shl 1
        }
    }

    private fun dtwDistance(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY
        val window = max(abs(n - m), max(n, m) / 4)
        val costs = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        val lengths = Array(n + 1) { IntArray(m + 1) }
        costs[0][0] = 0.0

        for (i in 1..n) {
            val startJ = max(1, i - window)
            val endJ = min(m, i + window)
            for (j in startJ..endJ) {
                var previousCost = costs[i - 1][j - 1]
                var previousLength = lengths[i - 1][j - 1]
                if (costs[i - 1][j] < previousCost) {
                    previousCost = costs[i - 1][j]
                    previousLength = lengths[i - 1][j]
                }
                if (costs[i][j - 1] < previousCost) {
                    previousCost = costs[i][j - 1]
                    previousLength = lengths[i][j - 1]
                }
                var local = 0.0
                for (d in a[i - 1].indices) {
                    val delta = a[i - 1][d] - b[j - 1][d]
                    local += delta * delta
                }
                local = sqrt(local / a[i - 1].size)
                costs[i][j] = previousCost + local
                lengths[i][j] = previousLength + 1
            }
        }
        val pathLength = lengths[n][m]
        return if (pathLength > 0) costs[n][m] / pathLength else Double.POSITIVE_INFINITY
    }
}
