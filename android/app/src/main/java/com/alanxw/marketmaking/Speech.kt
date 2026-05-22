package com.alanxw.marketmaking

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin wrapper over android.speech.tts.TextToSpeech with a coroutine-friendly
 * speak() that suspends until playback finishes.
 */
class Speech(context: Context) {
    private val ready = AtomicReference(false)
    /** Set if init failed or no usable voice; surfaced to caller for UI display. */
    @Volatile var lastError: String? = null
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            lastError = "TTS engine init failed (status=$status). " +
                "Install a TTS engine (e.g. RHVoice or eSpeak NG from F-Droid)."
            return@TextToSpeech
        }
        // Try US first, then any available locale the engine supports.
        val tried = listOf(Locale.US, Locale.UK, Locale.ENGLISH, Locale.getDefault())
        val picked = tried.firstOrNull { loc ->
            val rc = tts.setLanguage(loc)
            rc != TextToSpeech.LANG_MISSING_DATA && rc != TextToSpeech.LANG_NOT_SUPPORTED
        } ?: tts.availableLanguages?.firstOrNull()?.also { tts.language = it }

        if (picked == null) {
            lastError = "TTS engine has no installed voices. " +
                "Open the TTS engine's app (Settings → Accessibility → Text-to-speech) and download a voice."
            return@TextToSpeech
        }
        ready.set(true)
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        // Wait briefly for engine init on first call
        var waited = 0
        while (!ready.get() && lastError == null && waited < 2000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        if (!ready.get()) return  // give up if engine never initialized

        val id = "u-${System.nanoTime()}"
        suspendCancellableCoroutine<Unit> { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("legacy", ReplaceWith(""))
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
