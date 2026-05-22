package com.alanxw.marketmaking

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Raw AudioRecord-based mic capture for diagnostics. Bypasses SpeechRecognizer
 * entirely — if this works but the recognizer doesn't, the bug is in the
 * recognizer delegation, not the mic itself.
 */
suspend fun runMicTest(
    durationMs: Long = 5000,
    onLevel: (Float) -> Unit,
    onStatus: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    val sampleRate = 16000
    val channel = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
    if (minBuf <= 0) {
        onStatus("AudioRecord.getMinBufferSize returned $minBuf — mic config rejected.")
        return@withContext
    }
    val bufSize = maxOf(minBuf, sampleRate / 10)

    val recorder = try {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channel,
            encoding,
            bufSize * 2,
        )
    } catch (e: SecurityException) {
        onStatus("Permission denied: RECORD_AUDIO not granted to this app.")
        return@withContext
    } catch (t: Throwable) {
        onStatus("AudioRecord init threw: ${t.message}")
        return@withContext
    }

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        onStatus("AudioRecord not initialised (mic busy or hardware unavailable).")
        recorder.release()
        return@withContext
    }

    val buffer = ShortArray(bufSize)
    var samplesRead = 0L
    var peak = 0f
    try {
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onStatus("startRecording() didn't transition to RECORDING. Audio policy denial?")
            return@withContext
        }
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                samplesRead += read
                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble() / 32768.0
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / read).toFloat()
                if (rms > peak) peak = rms
                onLevel(rms)
            } else if (read < 0) {
                onStatus("AudioRecord.read() error code: $read")
                return@withContext
            }
        }
        recorder.stop()
        val verdict = when {
            samplesRead == 0L -> "No audio frames captured (mic produced silence buffer)."
            peak < 0.001f     -> "Captured ${samplesRead / sampleRate}s but signal is dead silent (peak=$peak). Likely muted/blocked."
            peak < 0.01f      -> "Captured signal but very quiet (peak=${"%.4f".format(peak)}). Maybe mic gain is low."
            else              -> "OK — captured ${samplesRead / sampleRate}s, peak RMS ${"%.3f".format(peak)}."
        }
        onStatus(verdict)
    } catch (t: Throwable) {
        onStatus("Recording threw: ${t.message}")
    } finally {
        try { recorder.release() } catch (_: Throwable) {}
    }
}
