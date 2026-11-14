package com.madida

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class FamilyContact(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val phoneNumber: String,
    val aliases: List<String> = emptyList(),
    val samplePaths: List<String> = emptyList(),
    val announcementPath: String? = null
)

internal data class VoicePrompts(
    val askWho: String? = null,
    val confirm: String? = null,
    val repeat: String? = null,
    val notFound: String? = null
)

internal data class VoiceResponses(
    val yesPaths: List<String> = emptyList(),
    val noPaths: List<String> = emptyList()
)

internal class FamilyStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("family_call_store", android.content.Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadContacts(): List<FamilyContact> {
        val raw = prefs.getString("contacts", "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<FamilyContact>>() {}.type
            gson.fromJson<List<FamilyContact>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveContacts(items: List<FamilyContact>) {
        prefs.edit().putString("contacts", gson.toJson(items)).apply()
    }

    fun loadPrompts(): VoicePrompts {
        val raw = prefs.getString("prompts", "{}") ?: "{}"
        return try { gson.fromJson(raw, VoicePrompts::class.java) ?: VoicePrompts() }
        catch (_: Exception) { VoicePrompts() }
    }

    fun savePrompts(value: VoicePrompts) {
        prefs.edit().putString("prompts", gson.toJson(value)).apply()
    }

    fun loadResponses(): VoiceResponses {
        val raw = prefs.getString("voice_responses", "{}") ?: "{}"
        return try { gson.fromJson(raw, VoiceResponses::class.java) ?: VoiceResponses() }
        catch (_: Exception) { VoiceResponses() }
    }

    fun saveResponses(value: VoiceResponses) {
        prefs.edit().putString("voice_responses", gson.toJson(value)).apply()
    }
}

/** Records 16 kHz mono PCM WAV locally. No network or cloud dependency. */
internal class SimpleAudioRecorder {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var currentFile: File? = null
    private var failureListener: ((String) -> Unit)? = null
    private val running = AtomicBoolean(false)

    @Volatile private var dataBytes: Long = 0
    @Volatile private var recordingFailure: String? = null

    @Synchronized
    fun start(file: File, onFailure: (String) -> Unit = {}) {
        stopQuietly()
        file.parentFile?.mkdirs()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("Le microphone ne prend pas en charge le format audio requis.")
        }

        val bufferSize = maxOf(minBuffer * 2, 8192)
        var recorder: AudioRecord? = null

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Impossible d'initialiser le microphone.")
            }

            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Le microphone n'a pas démarré correctement.")
            }

            currentFile = file
            dataBytes = 0
            recordingFailure = null
            failureListener = onFailure
            running.set(true)
            audioRecord = recorder

            val activeRecorder = recorder
            recordingThread = Thread({
                try {
                    FileOutputStream(file).use { output ->
                        output.write(ByteArray(44))
                        val buffer = ByteArray(bufferSize)

                        while (running.get()) {
                            val count = activeRecorder.read(buffer, 0, buffer.size)
                            if (count > 0) {
                                output.write(buffer, 0, count)
                                dataBytes += count
                            } else if (count < 0) {
                                reportRecordingFailure(
                                    "Le microphone a interrompu l'enregistrement ($count)."
                                )
                                break
                            }
                        }
                        output.flush()
                    }
                } catch (e: Exception) {
                    reportRecordingFailure(
                        e.localizedMessage ?: "Erreur pendant la capture audio."
                    )
                }
            }, "MaDida-AudioRecorder").apply { start() }
        } catch (e: Exception) {
            running.set(false)
            try {
                recorder?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                }
            } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }

            audioRecord = null
            recordingThread = null
            currentFile = null
            failureListener = null
            file.delete()

            throw IllegalStateException(
                e.localizedMessage ?: "Impossible de démarrer le microphone.",
                e
            )
        }
    }

    private fun reportRecordingFailure(message: String) {
        recordingFailure = message
        running.set(false)
        failureListener?.invoke(message)
    }

    @Synchronized
    fun stop(): File? {
        val file = currentFile ?: return null
        running.set(false)

        val activeRecorder = audioRecord
        try {
            if (activeRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                activeRecorder.stop()
            }
        } catch (_: Exception) { }

        val thread = recordingThread
        try {
            thread?.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try { activeRecorder?.release() } catch (_: Exception) { }

        if (thread?.isAlive == true) {
            try {
                thread.join(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        audioRecord = null
        recordingThread = null
        currentFile = null
        failureListener = null

        if (thread?.isAlive == true) {
            file.delete()
            throw IllegalStateException("Le microphone ne s'est pas arrêté correctement. Réessayez.")
        }

        if (dataBytes <= 0) {
            file.delete()
            return null
        }

        recordingFailure?.let {
            file.delete()
            throw IllegalStateException(it)
        }

        try {
            writeWavHeader(file, dataBytes)
        } catch (e: Exception) {
            file.delete()
            throw IllegalStateException("Impossible de finaliser le fichier audio.", e)
        }

        return file
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeIntLe(raf, (36L + pcmBytes).toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLe(raf, 16)
            writeShortLe(raf, 1)
            writeShortLe(raf, 1)
            writeIntLe(raf, SAMPLE_RATE)
            writeIntLe(raf, SAMPLE_RATE * 2)
            writeShortLe(raf, 2)
            writeShortLe(raf, 16)
            raf.writeBytes("data")
            writeIntLe(raf, pcmBytes.toInt())
        }
    }

    private fun writeIntLe(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
        raf.write((value shr 16) and 0xff)
        raf.write((value shr 24) and 0xff)
    }

    private fun writeShortLe(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
    }

    private fun stopQuietly() {
        try { stop() } catch (_: Exception) { }
    }

    fun release() = stopQuietly()
}

internal class PromptPlayer {
    private var active: MediaPlayer? = null

    fun playSequence(paths: List<String>) {
        stop()
        playAt(paths.filter { File(it).exists() }, 0)
    }

    private fun playAt(paths: List<String>, index: Int) {
        if (index >= paths.size) return
        try {
            val player = MediaPlayer()
            active = player
            player.setDataSource(paths[index])
            player.setOnCompletionListener {
                it.release()
                if (active === it) active = null
                playAt(paths, index + 1)
            }
            player.setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (active === mediaPlayer) active = null
                playAt(paths, index + 1)
                true
            }
            player.prepare()
            player.start()
        } catch (_: Exception) {
            try { active?.release() } catch (_: Exception) { }
            active = null
            playAt(paths, index + 1)
        }
    }

    fun stop() {
        try { active?.stop() } catch (_: Exception) { }
        try { active?.release() } catch (_: Exception) { }
        active = null
    }

    fun release() = stop()
}
