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
 * Fully local, language-independent matcher for a small, speaker-specific list of names.
 *
 * It compares acoustic examples directly. It does not transcribe speech or depend on spelling.
 * Scores and thresholds are provisional until they have been measured with the target speaker.
 */
internal object LocalVoiceMatcher {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_LENGTH = 400       // 25 ms
    private const val FRAME_HOP = 160          // 10 ms
    private const val FFT_SIZE = 512
    private const val MEL_FILTERS = 26
    private const val CEPSTRA = 12
    private const val FEATURE_DIMENSIONS = CEPSTRA * 2
    private const val DELTA_SCALE = 2.0
    private const val MAX_DISTANCE = 0.82
    private const val MIN_MARGIN = 0.10
    private const val MAX_REFERENCES_PER_DECISION = 3
    private const val MAX_AUDIO_SECONDS = 10

    private data class CacheEntry(
        val modified: Long,
        val size: Long,
        val features: Array<FloatArray>
    )

    private val cache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)
    private val filters by lazy(LazyThreadSafetyMode.PUBLICATION) { melFilterBank() }

    data class ContactDecision(
        val contact: FamilyContact? = null,
        val status: String,
        val bestDistance: Double? = null,
        val margin: Double? = null,
        val bestCandidate: FamilyContact? = null
    )

    fun identify(audioFile: File, contacts: List<FamilyContact>): ContactDecision {
        val query = getFeatures(audioFile)
            ?: return ContactDecision(status = "audio_invalid")

        if (contacts.isEmpty()) {
            return ContactDecision(status = "need_enrollment")
        }

        // Closed-set identification: compare every contact that has at least one usable
        // enrollment sample. Unenrolled or damaged entries are skipped; the closest usable
        // candidate always wins, without an absolute-distance or minimum-margin rejection.
        val scores = mutableListOf<Pair<FamilyContact, Double>>()

        for (contact in contacts) {
            val samplePaths = contact.samplePaths.filter { it.isNotBlank() }.distinct()
            // A contact that has not been enrolled yet, or whose recordings are missing,
            // must not block the other enrolled contacts from being compared.
            if (samplePaths.isEmpty()) continue

            val distances = samplePaths.mapNotNull { path ->
                getFeatures(File(path))?.let { reference -> dtwDistance(query, reference) }
            }.filter { it.isFinite() }.sorted()

            if (distances.isEmpty()) continue

            scores.add(contact to averageBest(distances))
        }

        if (scores.isEmpty()) {
            return ContactDecision(status = "need_enrollment")
        }

        val ranked = scores.sortedBy { it.second }
        val best = ranked.first()
        val secondDistance = ranked.getOrNull(1)?.second ?: Double.POSITIVE_INFINITY
        val margin = secondDistance - best.second

        // A contact is selected even when every score is poor or the top scores are close.
        // The calling flow still announces the candidate and waits for explicit confirmation.
        return ContactDecision(
            contact = best.first,
            status = "match",
            bestDistance = best.second,
            margin = margin,
            bestCandidate = best.first
        )
    }

    /** Returns yes, no or unclear after comparing the reply with locally enrolled examples. */
    fun classifyYesNo(audioFile: File, responses: VoiceResponses): String {
        if (responses.yesPaths.size < 2 || responses.noPaths.size < 2) return "unclear"

        val query = getFeatures(audioFile) ?: return "unclear"
        val yes = scoreAgainst(query, responses.yesPaths) ?: return "unclear"
        val no = scoreAgainst(query, responses.noPaths) ?: return "unclear"
        val difference = abs(yes - no)

        if (min(yes, no) > MAX_DISTANCE || difference < MIN_MARGIN) return "unclear"
        return if (yes < no) "yes" else "no"
    }

    /** Removes a temporary recording from the in-memory feature cache. */
    fun forget(audioFile: File) {
        synchronized(cache) {
            cache.remove(audioFile.absolutePath)
        }
    }

    private fun scoreAgainst(query: Array<FloatArray>, paths: List<String>): Double? {
        val distances = paths.distinct().mapNotNull { path ->
            getFeatures(File(path))?.let { reference -> dtwDistance(query, reference) }
        }.filter { it.isFinite() }.sorted()

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
            if (cached != null &&
                cached.modified == file.lastModified() &&
                cached.size == file.length()
            ) {
                return cached.features
            }
        }

        val samples = readPcmWav(file) ?: return null
        val features = extractFeatures(samples) ?: return null

        synchronized(cache) {
            cache[file.absolutePath] = CacheEntry(file.lastModified(), file.length(), features)
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

    /**
     * Reads PCM16 WAV by locating RIFF chunks instead of assuming a fixed 44-byte header.
     * The recorder writes 16 kHz WAV. Mono and stereo PCM16 are accepted and stereo is downmixed.
     */
    private fun readPcmWav(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var audioFormat = -1
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataLength = 0
            var position = 12

            while (position <= bytes.size - 8) {
                val chunkId = String(bytes, position, 4, Charsets.US_ASCII)
                val chunkSize = buffer.getInt(position + 4).toLong() and 0xffffffffL
                val chunkStart = position + 8
                val chunkEnd = chunkStart.toLong() + chunkSize

                if (chunkEnd > bytes.size || chunkSize > Int.MAX_VALUE) break

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16) return null
                        audioFormat = buffer.getShort(chunkStart).toInt() and 0xffff
                        channels = buffer.getShort(chunkStart + 2).toInt() and 0xffff
                        sampleRate = buffer.getInt(chunkStart + 4)
                        bitsPerSample = buffer.getShort(chunkStart + 14).toInt() and 0xffff
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkSize.toInt()
                    }
                }

                val next = chunkEnd + (chunkSize and 1L)
                if (next > bytes.size) break
                position = next.toInt()
            }

            if (audioFormat != 1 || bitsPerSample != 16 ||
                channels !in 1..2 || sampleRate != SAMPLE_RATE ||
                dataOffset < 0 || dataLength < channels * 2
            ) return null

            val frameSize = channels * 2
            val frameCount = dataLength / frameSize
            if (frameCount < SAMPLE_RATE / 8 || frameCount > SAMPLE_RATE * MAX_AUDIO_SECONDS) {
                return null
            }

            val samples = FloatArray(frameCount)
            var offset = dataOffset
            for (frame in 0 until frameCount) {
                var channelSum = 0
                repeat(channels) {
                    channelSum += buffer.getShort(offset).toInt()
                    offset += 2
                }
                samples[frame] = channelSum.toFloat() / channels / 32768f
            }
            trimSilence(samples)
        } catch (_: Exception) {
            null
        }
    }

    private fun trimSilence(input: FloatArray): FloatArray? {
        if (input.size < SAMPLE_RATE / 8) return null

        val block = 320 // 20 ms
        var peakRms = 0f
        var index = 0

        while (index < input.size) {
            val end = min(input.size, index + block)
            peakRms = max(peakRms, rms(input, index, end))
            index += block
        }

        if (peakRms < 0.003f) return null

        val threshold = max(0.0025f, peakRms * 0.055f)
        var first = 0
        while (first + block < input.size &&
            rms(input, first, min(input.size, first + block)) < threshold
        ) {
            first += block
        }

        var last = input.size
        while (last - block > first &&
            rms(input, max(first, last - block), last) < threshold
        ) {
            last -= block
        }

        val padding = SAMPLE_RATE / 10 // preserve 100 ms around the word
        first = max(0, first - padding)
        last = min(input.size, last + padding)

        if (last - first < SAMPLE_RATE / 8) return null
        return input.copyOfRange(first, last)
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sum = 0.0
        for (index in start until end) {
            sum += samples[index] * samples[index]
        }
        return sqrt(sum / (end - start)).toFloat()
    }

    /**
     * 12 MFCC coefficients plus 12 first-order temporal deltas.
     * Cepstral mean normalization is retained, but variance normalization is removed:
     * per-recording variance normalization can erase useful differences between short names.
     */
    private fun extractFeatures(samples: FloatArray): Array<FloatArray>? {
        if (samples.size < FRAME_LENGTH) return null

        val frameCount = 1 + (samples.size - FRAME_LENGTH) / FRAME_HOP
        if (frameCount < 5) return null

        val staticFeatures = Array(frameCount) { FloatArray(CEPSTRA) }
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)

        for (frame in 0 until frameCount) {
            val start = frame * FRAME_HOP

            for (index in 0 until FFT_SIZE) {
                if (index < FRAME_LENGTH) {
                    val sampleIndex = start + index
                    val current = samples[sampleIndex]
                    val previous = if (sampleIndex > 0) samples[sampleIndex - 1] else 0f
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

            val energies = DoubleArray(MEL_FILTERS)
            for (filter in 0 until MEL_FILTERS) {
                var energy = 0.0
                for (bin in power.indices) {
                    energy += power[bin] * filters[filter][bin]
                }
                energies[filter] = ln(max(energy, 1e-10))
            }

            for (coefficient in 1..CEPSTRA) {
                var value = 0.0
                for (filter in 0 until MEL_FILTERS) {
                    value += energies[filter] * cos(
                        PI * coefficient * (filter + 0.5) / MEL_FILTERS
                    )
                }
                staticFeatures[frame][coefficient - 1] = value.toFloat()
            }
        }

        // Cepstral mean normalization: reduce channel offset without flattening each
        // recording's coefficient variance.
        for (dimension in 0 until CEPSTRA) {
            val mean = staticFeatures.sumOf { it[dimension].toDouble() } / frameCount
            for (vector in staticFeatures) {
                vector[dimension] = (vector[dimension] - mean).toFloat()
            }
        }

        val combined = Array(frameCount) { FloatArray(FEATURE_DIMENSIONS) }
        for (frame in 0 until frameCount) {
            for (dimension in 0 until CEPSTRA) {
                combined[frame][dimension] = staticFeatures[frame][dimension]

                var numerator = 0.0
                for (step in 1..2) {
                    val ahead = staticFeatures[min(frameCount - 1, frame + step)][dimension]
                    val behind = staticFeatures[max(0, frame - step)][dimension]
                    numerator += step * (ahead - behind)
                }
                combined[frame][CEPSTRA + dimension] =
                    (numerator / 10.0 * DELTA_SCALE).toFloat()
            }
        }

        return combined
    }

    private fun melFilterBank(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val low = hzToMel(40.0)
        val high = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(MEL_FILTERS + 2) { index ->
            melToHz(low + (high - low) * index / (MEL_FILTERS + 1))
        }
        val bins = points.map {
            kotlin.math.floor(it * (FFT_SIZE + 1) / SAMPLE_RATE)
                .toInt()
                .coerceIn(0, FFT_SIZE / 2)
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
        val size = real.size
        var j = 0

        for (index in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit

            if (index < j) {
                val realValue = real[index]
                real[index] = real[j]
                real[j] = realValue

                val imaginaryValue = imag[index]
                imag[index] = imag[j]
                imag[j] = imaginaryValue
            }
        }

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val windowReal = cos(angle)
            val windowImaginary = sin(angle)
            var start = 0

            while (start < size) {
                var wr = 1.0
                var wi = 0.0

                for (offset in 0 until length / 2) {
                    val evenReal = real[start + offset]
                    val evenImaginary = imag[start + offset]
                    val oddIndex = start + offset + length / 2
                    val oddReal = real[oddIndex] * wr - imag[oddIndex] * wi
                    val oddImaginary = real[oddIndex] * wi + imag[oddIndex] * wr

                    real[start + offset] = evenReal + oddReal
                    imag[start + offset] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imag[oddIndex] = evenImaginary - oddImaginary

                    val nextWr = wr * windowReal - wi * windowImaginary
                    wi = wr * windowImaginary + wi * windowReal
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
    }

    /**
     * Banded DTW with rolling rows. This keeps computation bounded and avoids allocating
     * a full frames-by-frames matrix for every comparison.
     */
    private fun dtwDistance(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY

        val window = max(abs(n - m), max(n, m) / 4)
        var previousCosts = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var currentCosts = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var previousLengths = IntArray(m + 1)
        var currentLengths = IntArray(m + 1)
        previousCosts[0] = 0.0

        for (i in 1..n) {
            currentCosts.fill(Double.POSITIVE_INFINITY)
            currentLengths.fill(0)

            val startJ = max(1, i - window)
            val endJ = min(m, i + window)

            for (j in startJ..endJ) {
                var bestCost = previousCosts[j - 1]
                var bestLength = previousLengths[j - 1]

                if (previousCosts[j] < bestCost) {
                    bestCost = previousCosts[j]
                    bestLength = previousLengths[j]
                }
                if (currentCosts[j - 1] < bestCost) {
                    bestCost = currentCosts[j - 1]
                    bestLength = currentLengths[j - 1]
                }
                if (!bestCost.isFinite()) continue

                var localCost = 0.0
                for (dimension in 0 until FEATURE_DIMENSIONS) {
                    val difference = a[i - 1][dimension] - b[j - 1][dimension]
                    localCost += difference * difference
                }
                localCost = sqrt(localCost / FEATURE_DIMENSIONS)

                currentCosts[j] = bestCost + localCost
                currentLengths[j] = bestLength + 1
            }

            val oldCosts = previousCosts
            previousCosts = currentCosts
            currentCosts = oldCosts

            val oldLengths = previousLengths
            previousLengths = currentLengths
            currentLengths = oldLengths
        }

        val pathLength = previousLengths[m]
        return if (pathLength > 0) previousCosts[m] / pathLength
        else Double.POSITIVE_INFINITY
    }
}
