package com.example.negotiation.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val onAudioChunk: (ByteArray) -> Unit) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SECONDS = 5
    }

    fun start() {
        if (isRecording.get()) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2
        ).apply {
            startRecording()
        }

        isRecording.set(true)

        scope.launch {
            // 5秒的PCM数据: 16000 * 2 bytes * 5 = 160000 bytes
            val chunkSize = SAMPLE_RATE * 2 * CHUNK_SECONDS
            val buffer = ByteArray(chunkSize)

            while (isRecording.get()) {
                var totalRead = 0
                while (totalRead < chunkSize && isRecording.get()) {
                    val read = audioRecord?.read(buffer, totalRead, chunkSize - totalRead) ?: 0
                    if (read > 0) {
                        totalRead += read
                    } else if (read < 0) {
                        break
                    }
                }

                if (totalRead > 0 && isRecording.get()) {
                    val chunk = buffer.copyOf(totalRead)
                    onAudioChunk(chunk)
                }
            }
        }
    }

    fun stop() {
        isRecording.set(false)
        scope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
