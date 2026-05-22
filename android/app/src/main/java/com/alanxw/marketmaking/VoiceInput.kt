package com.alanxw.marketmaking

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android SpeechRecognizer wrapper. The system handles VAD + STT in one call —
 * we just await the final transcript.
 *
 * Two extras vs. the basic version:
 *  - optional Bluetooth SCO routing so a paired headset's mic is used instead of
 *    the phone's built-in mic
 *  - per-frame RMS callback so the UI can show a live level meter
 */
class VoiceInput(private val context: Context) {

    data class RecognizerInfo(
        val packageName: String,
        val className: String,
        val label: String,
    ) {
        val key get() = "$packageName/$className"
        fun toComponentName() = ComponentName(packageName, className)
    }

    /** All apps on this device that register as `RecognitionService`. */
    fun listRecognizers(): List<RecognizerInfo> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val pm = context.packageManager
        return pm.queryIntentServices(intent, 0).map {
            RecognizerInfo(
                packageName = it.serviceInfo.packageName,
                className = it.serviceInfo.name,
                label = it.loadLabel(pm).toString(),
            )
        }
    }

    sealed class ListenResult {
        data class Text(val transcript: String) : ListenResult()
        object NoMatch : ListenResult()                   // expected: no speech detected
        data class Failure(val code: Int, val message: String) : ListenResult()
    }

    suspend fun listen(
        useBluetoothMic: Boolean = false,
        recognizerComponent: String? = null,
        onLevel: ((Float) -> Unit)? = null,
    ): ListenResult {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return ListenResult.Failure(-1, "No speech recognition service installed on this device.")
        }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalMode = audio.mode
        val scoActive = if (useBluetoothMic) acquireBluetoothMic(audio) else false

        return try {
            doListen(recognizerComponent, onLevel)
        } finally {
            if (scoActive) releaseBluetoothMic(audio, originalMode)
        }
    }

    private suspend fun doListen(
        recognizerComponent: String?,
        onLevel: ((Float) -> Unit)?,
    ): ListenResult =
        suspendCancellableCoroutine { cont ->
            val component = recognizerComponent?.let {
                val parts = it.split('/', limit = 2)
                if (parts.size == 2) ComponentName(parts[0], parts[1]) else null
            }
            val recognizer = if (component != null) {
                SpeechRecognizer.createSpeechRecognizer(context, component)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                // No EXTRA_LANGUAGE — the recognizer rejects explicit "en-US" on
                // some devices ("language not supported"). Defaulting lets it use
                // whatever the system locale already has installed.
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                private fun finish(r: ListenResult) {
                    if (cont.isActive) {
                        cont.resume(r)
                        try { recognizer.destroy() } catch (_: Throwable) {}
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        finish(ListenResult.NoMatch)
                    } else {
                        finish(ListenResult.Failure(error, errorName(error)))
                    }
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val first = list?.firstOrNull()
                    finish(if (first.isNullOrBlank()) ListenResult.NoMatch else ListenResult.Text(first))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            cont.invokeOnCancellation {
                try {
                    recognizer.cancel()
                    recognizer.destroy()
                } catch (_: Throwable) {}
            }

            recognizer.startListening(intent)
        }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        else -> "code $code"
    }

    /** Try to route mic input through a paired Bluetooth headset (SCO).
     *  Returns true on success — caller must call releaseBluetoothMic() to undo. */
    private fun acquireBluetoothMic(audio: AudioManager): Boolean {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (Build.VERSION.SDK_INT >= 31) {
            val sco = audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (sco != null) audio.setCommunicationDevice(sco) else false
        } else {
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            true
        }
    }

    private fun releaseBluetoothMic(audio: AudioManager, originalMode: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
            }
        } catch (_: Throwable) {}
        audio.mode = originalMode
    }
}
