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
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ready.set(true)
        }
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        // Wait briefly for engine init on first call
        var waited = 0
        while (!ready.get() && waited < 2000) {
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
