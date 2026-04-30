package com.stardazz.smeeting.core.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRecorder @Inject constructor() {
    private val TAG = "AudioRecorder"
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var wavOutputStream: FileOutputStream? = null
    private var wavFile: File? = null
    private var writtenPcmBytes: Long = 0L
    private var lastRecordedFilePath: String? = null

    interface AudioDataListener {
        fun onAudioData(data: ShortArray)
    }

    private var listener: AudioDataListener? = null

    fun setListener(l: AudioDataListener) {
        this.listener = l
    }

    @SuppressLint("MissingPermission")
    fun start(listener: AudioDataListener, outputFile: File? = null) {
        if (isRecording.get()) return
        this.listener = listener
        lastRecordedFilePath = null
        wavFile = outputFile
        writtenPcmBytes = 0L

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording.set(true)

        recordingThread = Thread({
            val buffer = ShortArray(BUFFER_SIZE)
            val output = wavFile?.let { file ->
                runCatching {
                    file.parentFile?.let { parent ->
                        if (!parent.exists()) parent.mkdirs()
                    }
                    FileOutputStream(file).also {
                        it.write(createWavHeader(0))
                    }
                }.onFailure {
                    Log.e(TAG, "Failed to open wav output file: ${file.absolutePath}", it)
                }.getOrNull()
            }
            wavOutputStream = output
            while (isRecording.get()) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    val data = buffer.copyOf(readSize)
                    this.listener?.onAudioData(data)
                    if (output != null) {
                        writePcmToWav(output, data)
                    }
                }
            }
        }, "AudioRecordingThread")
        recordingThread?.start()
    }

    fun stop() {
        isRecording.set(false)
        try {
            recordingThread?.join()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording thread", e)
        }
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        wavOutputStream?.flush()
        wavOutputStream?.close()
        wavOutputStream = null

        val recorded = wavFile
        if (recorded != null && recorded.exists() && writtenPcmBytes > 0L) {
            runCatching {
                updateWavHeader(recorded, writtenPcmBytes)
                lastRecordedFilePath = recorded.absolutePath
            }.onFailure {
                Log.e(TAG, "Failed to finalize wav file", it)
                recorded.delete()
                lastRecordedFilePath = null
            }
        } else {
            recorded?.delete()
            lastRecordedFilePath = null
        }

        wavFile = null
        writtenPcmBytes = 0L
        recordingThread = null
    }

    fun consumeLastRecordedFilePath(): String? {
        val path = lastRecordedFilePath
        lastRecordedFilePath = null
        return path
    }

    private fun writePcmToWav(output: FileOutputStream, data: ShortArray) {
        val bytes = ByteArray(data.size * 2)
        var j = 0
        for (sample in data) {
            bytes[j++] = (sample.toInt() and 0xFF).toByte()
            bytes[j++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        output.write(bytes)
        writtenPcmBytes += bytes.size
    }

    private fun createWavHeader(pcmDataSize: Long): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = pcmDataSize + 36
        return ByteArray(44).apply {
            this[0] = 'R'.code.toByte()
            this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte()
            this[3] = 'F'.code.toByte()
            writeIntLE(4, totalDataLen.toInt())
            this[8] = 'W'.code.toByte()
            this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte()
            this[11] = 'E'.code.toByte()
            this[12] = 'f'.code.toByte()
            this[13] = 'm'.code.toByte()
            this[14] = 't'.code.toByte()
            this[15] = ' '.code.toByte()
            writeIntLE(16, 16)
            writeShortLE(20, 1)
            writeShortLE(22, channels.toShort())
            writeIntLE(24, SAMPLE_RATE)
            writeIntLE(28, byteRate)
            writeShortLE(32, blockAlign.toShort())
            writeShortLE(34, bitsPerSample.toShort())
            this[36] = 'd'.code.toByte()
            this[37] = 'a'.code.toByte()
            this[38] = 't'.code.toByte()
            this[39] = 'a'.code.toByte()
            writeIntLE(40, pcmDataSize.toInt())
        }
    }

    private fun updateWavHeader(file: File, pcmDataSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(createWavHeader(pcmDataSize))
        }
    }

    private fun ByteArray.writeIntLE(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.writeShortLE(offset: Int, value: Short) {
        val intValue = value.toInt()
        this[offset] = (intValue and 0xFF).toByte()
        this[offset + 1] = ((intValue shr 8) and 0xFF).toByte()
    }
}
