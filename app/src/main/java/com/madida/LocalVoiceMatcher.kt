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
 * Offline closed-set spoken-name matcher.
 *
 * Each saved pronunciation is a separate acoustic template. Matching uses a
 * log-mel spectral representation plus short-term spectral changes, aligned
 * with Dynamic Time Warping (DTW). It deliberately does not identify speakers
 * or depend on speech-to-text or a particular language.
 *
 * A rejection limit is estimated from leave-one-out comparisons between the
 * enrolled samples for each name. It is therefore adapted to the variation
 * actually present in that name's recordings, rather than using one global
 * distance threshold for every phone and voice.
 */
internal object LocalVoiceMatcher {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_LENGTH = 400       // 25 ms
    private const val FRAME_HOP = 160          // 10 ms
    private const val FFT_SIZE = 512
    private const val MEL_FILTERS = 32
    private const val MIN_NAME_SAMPLES = 5
    private const val MAX_REFERENCES_PER_DECISION = 2

    private data class CacheEntry(
        val modified: Long,
        val size: Long,
        val features: Array<FloatArray>
    )

    private data class TemplateScore(
        val distance: Double,
        val rejectionLimit: Double
    )

    private data class Candidate(
        val contact: FamilyContact,
        val score: Double,
        val limit: Double
    ) {
        val normalizedScore: Double
            get() = score / max(limit, 1e-6)
    }

    private val cache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)

    data class ContactDecision(
        val contact: FamilyContact? = null,
        val status: String,
        val bestDistance: Double? = null,
        val margin: Double? = null
    )

    /** Remove stale acoustic features when a saved recording is deleted. */
    fun invalidate(path: String) {
        synchronized(cache) {
            cache.remove(File(path).absolutePath)
        }
    }

    fun identify(audioFile: File, contacts: List<FamilyContact>): ContactDecision {
        val query = getFeatures(audioFile)
            ?: return ContactDecision(status = "audio_invalid")

        val candidates = contacts.mapNotNull { contact ->
            val references = contact.samplePaths.distinct()
                .mapNotNull { getFeatures(File(it)) }

            if (references.size < MIN_NAME_SAMPLES) {
                return@mapNotNull null
            }

            val queryDistances = references.map { dtwDistance(query, it) }
                .filter { it.isFinite() }
                .sorted()

            if (queryDistances.size < MIN_NAME_SAMPLES) {
                return@mapNotNull null
            }

            val calibration = leaveOneOutScores(references)
            if (calibration.isEmpty()) {
                return@mapNotNull null
            }

            Candidate(
                contact = contact,
                score = averageBest(queryDistances),
                limit = rejectionLimit(calibration)
            )
        }.sortedBy { it.normalizedScore }

        if (candidates.isEmpty()) {
            return ContactDecision(status = "need_enrollment")
        }

        val best = candidates.first()
        val second = candidates.getOrNull(1)
        val margin = second?.let { it.normalizedScore - best.normalizedScore }
            ?: Double.POSITIVE_INFINITY

        // Reject inputs that do not resemble even the best enrolled name.
        if (best.normalizedScore > 1.0) {
            return ContactDecision(
                status = "no_match",
                bestDistance = best.score,
                margin = margin
            )
        }

        // Similar normalized scores mean the app should not guess between names.
        if (second != null && margin < 0.18) {
            return ContactDecision(
                status = "ambiguous",
                bestDistance = best.score,
                margin = margin
            )
        }

        return ContactDecision(
            contact = best.contact,
            status = "match",
            bestDistance = best.score,
            margin = margin
        )
    }

    /** Classify an enrolled OUI/NON reply with the same calibrated template scoring. */
    fun classifyYesNo(audioFile: File, responses: VoiceResponses): String {
        val query = getFeatures(audioFile) ?: return "unclear"
        val yes = scoreAgainst(query, responses.yesPaths) ?: return "unclear"
        val no = scoreAgainst(query, responses.noPaths) ?: return "unclear"

        val yesRatio = yes.distance / max(yes.rejectionLimit, 1e-6)
        val noRatio = no.distance / max(no.rejectionLimit, 1e-6)
        if (min(yesRatio, noRatio) > 1.0) return "unclear"
        if (abs(yesRatio - noRatio) < 0.18) return "unclear"
        return if (yesRatio < noRatio) "yes" else "no"
    }

    private fun scoreAgainst(
        query: Array<FloatArray>,
        paths: List<String>
    ): TemplateScore? {
        val references = paths.distinct().mapNotNull { getFeatures(File(it)) }
        if (references.size < 2) return null

        val distances = references.map { dtwDistance(query, it) }
            .filter { it.isFinite() }
            .sorted()
        if (distances.size < 2) return null

        val calibration = leaveOneOutScores(references)
        if (calibration.isEmpty()) return null

        return TemplateScore(
            distance = averageBest(distances),
            rejectionLimit = rejectionLimit(calibration)
        )
    }

    /**
     * Estimates natural within-name variation by comparing each enrollment
     * recording with the other recordings of the same name (leave-one-out).
     */
    private fun leaveOneOutScores(
        references: List<Array<FloatArray>>
    ): List<Double> {
        if (references.size < 2) return emptyList()

        return references.indices.mapNotNull { heldOut ->
            val distances = references.indices
                .filter { it != heldOut }
                .map { dtwDistance(references[heldOut], references[it]) }
                .filter { it.isFinite() }
                .sorted()

            if (distances.isEmpty()) null else averageBest(distances)
        }.sorted()
    }

    /**
     * Median + robust spread (median absolute deviation), with a proportional
     * allowance for ordinary variation. This is per-name, not a global magic
     * distance shared by all recordings.
     */
    private fun rejectionLimit(calibration: List<Double>): Double {
        val center = median(calibration)
        val mad = median(calibration.map { abs(it - center) })
        val robustSpread = 3.0 * 1.4826 * mad
        val proportionalAllowance = center * 0.35
        return center + max(0.04, max(robustSpread, proportionalAllowance))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun averageBest(distances: List<Double>): Double {
        val count = min(MAX_REFERENCES_PER_DECISION, distances.size)
        return distances.take(count).average()
    }

    private fun getFeatures(file: File): Array<FloatArray>? {
        if (!file.exists() || file.length() < 1000) return null

        val path = file.absolutePath
        synchronized(cache) {
            val cached = cache[path]
            if (cached != null &&
                cached.modified == file.lastModified() &&
                cached.size == file.length()
            ) {
                return cached.features
            }
        }

        val samples = readPcmWav(file) ?: return null
        val features = extractLogMelFeatures(samples) ?: return null

        synchronized(cache) {
            cache[path] = CacheEntry(file.lastModified(), file.length(), features)
            while (cache.size > 2500) {
                val iterator = cache.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return features
    }

    /** The recorder writes 16 kHz, mono, signed 16-bit PCM WAV files. */
    private fun readPcmWav(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= 44) return null
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE" ||
                String(bytes, 36, 4, Charsets.US_ASCII) != "data"
            ) return null

            val sampleRate = ByteBuffer.wrap(bytes, 24, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val channels = ByteBuffer.wrap(bytes, 22, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val bitsPerSample = ByteBuffer.wrap(bytes, 34, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            if (sampleRate != SAMPLE_RATE || channels != 1 || bitsPerSample != 16) return null

            val dataSize = ByteBuffer.wrap(bytes, 40, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val usableBytes = min(max(0, dataSize), bytes.size - 44)
            if (usableBytes < SAMPLE_RATE / 4) return null

            val buffer = ByteBuffer.wrap(bytes, 44, usableBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(buffer.remaining() / 2) {
                buffer.short / 32768f
            }
            trimSilence(samples)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Simple energy VAD: use short-frame RMS, estimate the quiet floor from
     * the lower part of the recording, retain speech plus a small safety pad.
     */
    private fun trimSilence(input: FloatArray): FloatArray? {
        val frame = 320       // 20 ms
        val hop = 160         // 10 ms
        if (input.size < SAMPLE_RATE / 5) return null

        val frameCount = 1 + (input.size - frame).coerceAtLeast(0) / hop
        val energies = FloatArray(frameCount)
        for (index in 0 until frameCount) {
            val start = index * hop
            energies[index] = rms(input, start, start + frame)
        }

        val sorted = energies.sorted()
        val quietFloor = sorted[(sorted.size * 0.20f).toInt().coerceIn(0, sorted.lastIndex)]
        val peak = sorted.last()
        if (peak < 0.0025f) return null

        val threshold = max(0.0018f, max(quietFloor * 2.2f, peak * 0.06f))
        var first = -1
        var last = -1
        var activeFrames = 0
        for (index in energies.indices) {
            if (energies[index] >= threshold) {
                if (first == -1) first = index
                last = index
                activeFrames++
            }
        }
        if (first == -1 || activeFrames < 6) return null

        val padding = SAMPLE_RATE / 12
        val startSample = max(0, first * hop - padding)
        val endSample = min(input.size, last * hop + frame + padding)
        if (endSample - startSample < SAMPLE_RATE / 6) return null
        return input.copyOfRange(startSample, endSample)
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        var sum = 0.0
        val safeEnd = min(end, samples.size)
        for (index in start until safeEnd) {
            sum += samples[index] * samples[index]
        }
        return sqrt((sum / max(1, safeEnd - start)).toFloat())
    }

    /**
     * Produces frame-wise log-mel spectral shape and first-order spectral
     * changes. Only the per-frame average is removed to reduce microphone
     * volume differences; per-recording variance normalization is avoided
     * because it can erase useful differences between spoken names.
     */
    private fun extractLogMelFeatures(samples: FloatArray): Array<FloatArray>? {
        if (samples.size < FRAME_LENGTH) return null

        val frameCount = 1 + (samples.size - FRAME_LENGTH) / FRAME_HOP
        if (frameCount < 6) return null

        val filters = melFilterBank()
        val staticFeatures = Array(frameCount) { FloatArray(MEL_FILTERS) }
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)

        for (frame in 0 until frameCount) {
            val start = frame * FRAME_HOP
            for (index in 0 until FFT_SIZE) {
                if (index < FRAME_LENGTH) {
                    val current = samples[start + index]
                    val previous = if (start + index > 0) samples[start + index - 1] else 0f
                    val emphasized = current - 0.97f * previous
                    val window = 0.54 - 0.46 * cos(
                        2.0 * PI * index / (FRAME_LENGTH - 1)
                    )
                    real[index] = emphasized * window
                } else {
                    real[index] = 0.0
                }
                imag[index] = 0.0
            }

            fft(real, imag)
            val power = DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                (real[bin] * real[bin] + imag[bin] * imag[bin]) / FFT_SIZE
            }
            val logEnergies = DoubleArray(MEL_FILTERS)
            for (mel in 0 until MEL_FILTERS) {
                var energy = 0.0
                for (bin in power.indices) {
                    energy += power[bin] * filters[mel][bin]
                }
                logEnergies[mel] = ln(max(energy, 1e-10))
            }

            val frameMean = logEnergies.average()
            for (mel in 0 until MEL_FILTERS) {
                staticFeatures[frame][mel] = (logEnergies[mel] - frameMean).toFloat()
            }
        }

        val output = Array(frameCount) { FloatArray(MEL_FILTERS * 2) }
        for (frame in 0 until frameCount) {
            for (mel in 0 until MEL_FILTERS) {
                output[frame][mel] = staticFeatures[frame][mel] * 0.8f
                output[frame][MEL_FILTERS + mel] =
                    delta(staticFeatures, frame, mel) * 0.5f
            }
        }
        return output
    }

    private fun delta(
        features: Array<FloatArray>,
        frame: Int,
        dimension: Int
    ): Float {
        var numerator = 0.0
        for (offset in 1..2) {
            val left = (frame - offset).coerceAtLeast(0)
            val right = (frame + offset).coerceAtMost(features.lastIndex)
            numerator += offset * (features[right][dimension] - features[left][dimension])
        }
        return (numerator / 10.0).toFloat()
    }

    private fun melFilterBank(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val low = hzToMel(50.0)
        val high = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(MEL_FILTERS + 2) { index ->
            melToHz(low + (high - low) * index / (MEL_FILTERS + 1))
        }
        val bins = points.map {
            kotlin.math.floor(it * (FFT_SIZE + 1) / SAMPLE_RATE)
                .toInt().coerceIn(0, FFT_SIZE / 2)
        }

        return Array(MEL_FILTERS) { filter ->
            DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val left = bins[filter]
                val center = bins[filter + 1]
                val right = bins[filter + 2]
                when {
                    bin < left || bin > right -> 0.0
                    bin <= center ->
                        if (center == left) 0.0
                        else (bin - left).toDouble() / (center - left)
                    else ->
                        if (right == center) 0.0
                        else (right - bin).toDouble() / (right - center)
                }
            }
        }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]
                real[i] = real[j]
                real[j] = r
                val im = imag[i]
                imag[i] = imag[j]
                imag[j] = im
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
                    val vR = real[i + k + length / 2] * wr -
                        imag[i + k + length / 2] * wi
                    val vI = real[i + k + length / 2] * wi +
                        imag[i + k + length / 2] * wr

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

    private fun dtwDistance(
        first: Array<FloatArray>,
        second: Array<FloatArray>
    ): Double {
        val n = first.size
        val m = second.size
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

                var staticCost = 0.0
                var deltaCost = 0.0
                for (dimension in 0 until MEL_FILTERS) {
                    val staticDifference =
                        first[i - 1][dimension] - second[j - 1][dimension]
                    val deltaDifference =
                        first[i - 1][MEL_FILTERS + dimension] -
                            second[j - 1][MEL_FILTERS + dimension]
                    staticCost += staticDifference * staticDifference
                    deltaCost += deltaDifference * deltaDifference
                }

                val local = sqrt(
                    (staticCost * 0.75 + deltaCost * 0.25) / MEL_FILTERS
                )
                costs[i][j] = previousCost + local
                lengths[i][j] = previousLength + 1
            }
        }

        val pathLength = lengths[n][m]
        return if (pathLength > 0) costs[n][m] / pathLength
        else Double.POSITIVE_INFINITY
    }
}
