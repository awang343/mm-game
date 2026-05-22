package com.alanxw.marketmaking

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * On-device speech recognition via Vosk. Bypasses Android's SpeechRecognizer
 * entirely — required on LineageOS / no-GMS where the system recognizer
 * doesn't work.
 *
 * Captures mic audio directly with AudioRecord (so we can also emit live RMS
 * to the UI), feeds it to a Vosk Recognizer, and returns the final transcript
 * once Vosk's endpoint detection triggers (or a max-duration cap fires).
 */
class VoskRecognizer(private val context: Context) {

    @Volatile private var model: Model? = null

    /**
     * Restrict Vosk's vocabulary to the words we actually expect in a market
     * quote — numbers + trader keywords only. Action words (skip/quit/repeat
     * etc.) are intentionally excluded; those are UI-button concerns. `[unk]`
     * is a catch-all so off-script audio doesn't crash the recognizer.
     */
    private val grammar: String = JSONArray(listOf(
        // ones / digit-by-digit
        "zero", "one", "two", "three", "four", "five",
        "six", "seven", "eight", "nine", "oh",
        // teens
        "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        // tens
        "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety",
        // scales
        "hundred", "thousand", "million",
        // glue
        "and", "a",
        // trader keywords
        "at", "bid", "for", "up", "to", "by",
        "offered", "offer", "ask", "in", "over",
        // catch-all
        "[unk]",
    )).toString()

    private suspend fun loadModel(): Result<Model> = withContext(Dispatchers.IO) {
        model?.let { return@withContext Result.success(it) }
        suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context,
                "vosk-model",
                "model",
                { m ->
                    model = m
                    if (cont.isActive) cont.resume(Result.success(m))
                },
                { e ->
                    if (cont.isActive) cont.resume(Result.failure(e))
                },
            )
        }
    }

    suspend fun listen(
        useBluetoothMic: Boolean = false,
        initialSilenceMs: Long = 30_000,
        maxDurationMs: Long = 30_000,
        trailingSilenceMs: Long = 1_500,
        onLevel: (Float) -> Unit,
    ): VoiceInput.ListenResult = withContext(Dispatchers.IO) {
        val m = model ?: loadModel().getOrElse {
            return@withContext VoiceInput.ListenResult.Failure(-1, "Vosk model load failed: ${it.message}")
        }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalMode = audio.mode
        val scoActive = if (useBluetoothMic) acquireBluetoothMic(audio) else false

        val sampleRate = 16000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) {
            if (scoActive) releaseBluetoothMic(audio, originalMode)
            return@withContext VoiceInput.ListenResult.Failure(
                -1, "Mic config rejected (minBuf=$minBuf)"
            )
        }
        val bufSize = maxOf(minBuf, sampleRate / 10)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channel, encoding, bufSize * 2,
            )
        } catch (e: SecurityException) {
            if (scoActive) releaseBluetoothMic(audio, originalMode)
            return@withContext VoiceInput.ListenResult.Failure(-1, "RECORD_AUDIO not granted.")
        } catch (t: Throwable) {
            if (scoActive) releaseBluetoothMic(audio, originalMode)
            return@withContext VoiceInput.ListenResult.Failure(-1, "AudioRecord init: ${t.message}")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            if (scoActive) releaseBluetoothMic(audio, originalMode)
            return@withContext VoiceInput.ListenResult.Failure(-1, "AudioRecord not initialised.")
        }

        val recognizer = Recognizer(m, sampleRate.toFloat(), grammar)
        recognizer.setWords(true)  // emit per-word start/end timings in result() JSON
        val buffer = ShortArray(bufSize)
        val start = System.currentTimeMillis()
        var firstSpeechAt: Long? = null
        var lastChangeAt = start
        var lastSeenPartial = ""
        // Segments = words from each Vosk endpoint emission. We insert a comma
        // between segments (Vosk already decided there was silence there) AND
        // inside a segment wherever the inter-word gap exceeds the threshold.
        val segments = mutableListOf<List<WordTime>>()

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble() / 32768.0
                    sumSq += s * s
                }
                onLevel(sqrt(sumSq / read).toFloat())

                val ended = recognizer.acceptWaveForm(buffer, read)
                if (ended) {
                    val seg = parseWords(recognizer.result)
                    if (seg.isNotEmpty()) segments.add(seg)
                }
                val partial = jsonField(recognizer.partialResult, "partial").orEmpty()
                val now = System.currentTimeMillis()
                val activityText = segments.flatten().joinToString(" ") { it.word } + " " + partial
                if (activityText.isNotBlank() && firstSpeechAt == null) firstSpeechAt = now
                if (activityText != lastSeenPartial) {
                    lastSeenPartial = activityText
                    lastChangeAt = now
                }

                if (firstSpeechAt != null && now - lastChangeAt > trailingSilenceMs) break
                if (firstSpeechAt == null && now - start > initialSilenceMs) break
                if (now - start > maxDurationMs) break
            }
            // flush anything still buffered
            val tail = parseWords(recognizer.finalResult)
            if (tail.isNotEmpty()) segments.add(tail)
            val finalText = buildTextWithCommas(segments)
            if (finalText.isBlank()) {
                return@withContext VoiceInput.ListenResult.NoMatch
            }
            return@withContext VoiceInput.ListenResult.Text(finalText)
        } catch (t: Throwable) {
            return@withContext VoiceInput.ListenResult.Failure(-1, "Vosk runtime: ${t.message}")
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
            try { recognizer.close() } catch (_: Throwable) {}
            if (scoActive) releaseBluetoothMic(audio, originalMode)
        }
    }

    private fun jsonField(raw: String?, key: String): String? = try {
        if (raw.isNullOrBlank()) null else JSONObject(raw).optString(key, "").takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    private data class WordTime(val word: String, val start: Double, val end: Double)

    /** Pull the `result` array (word + start + end) out of Vosk's JSON. */
    private fun parseWords(raw: String?): List<WordTime> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("result") ?: return emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(WordTime(
                        word = o.optString("word", ""),
                        start = o.optDouble("start", 0.0),
                        end = o.optDouble("end", 0.0),
                    ))
                }
            }.filter { it.word.isNotBlank() }
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * Build a single transcript string with commas inserted at:
     *   - segment boundaries (Vosk endpointed there → user clearly paused)
     *   - within a segment wherever inter-word gap > 0.30 s
     * normalizeNumbers downstream treats commas as sequence breakers, so
     * "four [pause] five ten up" becomes "four, five ten up" → bid=4, ask=5, size=10.
     */
    private fun buildTextWithCommas(segments: List<List<WordTime>>, gapThreshold: Double = 0.30): String {
        val sb = StringBuilder()
        for ((segIdx, seg) in segments.withIndex()) {
            if (segIdx > 0) sb.append(", ")
            for ((wIdx, w) in seg.withIndex()) {
                if (wIdx > 0) {
                    val gap = w.start - seg[wIdx - 1].end
                    sb.append(if (gap > gapThreshold) ", " else " ")
                }
                sb.append(w.word)
            }
        }
        return sb.toString().trim()
    }

    // Mirror VoiceInput's BT mic helpers — duplicated rather than shared to keep
    // VoskRecognizer independent of the system-recognizer path.
    private fun acquireBluetoothMic(audio: AudioManager): Boolean {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (Build.VERSION.SDK_INT >= 31) {
            val sco = audio.availableCommunicationDevices
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (sco != null) audio.setCommunicationDevice(sco) else false
        } else {
            @Suppress("DEPRECATION") audio.startBluetoothSco()
            @Suppress("DEPRECATION") audio.isBluetoothScoOn = true
            true
        }
    }

    private fun releaseBluetoothMic(audio: AudioManager, originalMode: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION") audio.stopBluetoothSco()
            }
        } catch (_: Throwable) {}
        audio.mode = originalMode
    }
}
