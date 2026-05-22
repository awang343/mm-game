package com.alanxw.marketmaking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Phase { Idle, Speaking, Listening, Thinking, Result, Error }

data class UiState(
    val phase: Phase = Phase.Idle,
    val contract: Contract? = null,
    val heardText: String? = null,
    val lastResult: RoundResult? = null,
    val stats: SessionStats = SessionStats(),
    val errorMessage: String? = null,
    val serverUrl: String? = null,
    val useBluetoothMic: Boolean = false,
    val recognizerKey: String? = null,
    val availableRecognizers: List<VoiceInput.RecognizerInfo> = emptyList(),
    val showSettings: Boolean = false,
    /** Normalised mic level 0..1 while listening, else 0. */
    val micLevel: Float = 0f,
)

class SimViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val voice = VoiceInput(app)
    private val vosk = VoskRecognizer(app)
    private val speech = Speech(app)
    private val rng = Random(System.nanoTime())

    companion object {
        const val VOSK_KEY = "vosk:bundled"
    }

    private val _state = MutableStateFlow(
        UiState(
            serverUrl = settings.serverUrl,
            useBluetoothMic = settings.useBluetoothMic,
            recognizerKey = settings.recognizerComponent,
            availableRecognizers = voice.listRecognizers(),
            showSettings = settings.serverUrl == null,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun openSettings() {
        _state.update {
            it.copy(
                showSettings = true,
                availableRecognizers = voice.listRecognizers(),
            )
        }
    }

    fun saveSettings(
        url: String,
        useBluetoothMic: Boolean,
        recognizerKey: String?,
    ) {
        val cleaned = url.trim()
        if (cleaned.isEmpty()) return
        settings.serverUrl = cleaned
        settings.useBluetoothMic = useBluetoothMic
        settings.recognizerComponent = recognizerKey
        _state.update {
            it.copy(
                serverUrl = cleaned,
                useBluetoothMic = useBluetoothMic,
                recognizerKey = recognizerKey,
                showSettings = false,
                errorMessage = null,
            )
        }
    }

    fun cancelSettings() {
        if (_state.value.serverUrl != null) {
            _state.update { it.copy(showSettings = false) }
        }
    }

    fun startRound() {
        if (_state.value.phase == Phase.Listening || _state.value.phase == Phase.Speaking) return
        val url = _state.value.serverUrl ?: run {
            _state.update { it.copy(showSettings = true) }
            return
        }
        val client = ContractClient(url)
        viewModelScope.launch {
            val contract = try {
                client.randomContract()
            } catch (t: Throwable) {
                _state.update { it.copy(
                    phase = Phase.Error,
                    errorMessage = "Cannot reach contract server: ${t.message}",
                ) }
                return@launch
            }
            _state.update { it.copy(phase = Phase.Speaking, contract = contract, heardText = null, errorMessage = null) }
            speech.speak(contract.question)
            speech.lastError?.let { e -> _state.update { it.copy(errorMessage = e) } }
            runListenLoop(contract)
        }
    }

    fun repeatQuestion() {
        val c = _state.value.contract ?: return
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Speaking) }
            speech.speak(c.question)
            speech.lastError?.let { e -> _state.update { it.copy(errorMessage = e) } }
            runListenLoop(c)
        }
    }

    private suspend fun runListenLoop(contract: Contract) {
        while (true) {
            _state.update { it.copy(phase = Phase.Listening, micLevel = 0f, errorMessage = null) }
            val recognizerKey = _state.value.recognizerKey
            val result = if (recognizerKey == VOSK_KEY) {
                vosk.listen(
                    useBluetoothMic = _state.value.useBluetoothMic,
                    onLevel = { rms ->
                        _state.update { it.copy(micLevel = (rms * 10f).coerceIn(0f, 1f)) }
                    },
                )
            } else {
                voice.listen(
                    useBluetoothMic = _state.value.useBluetoothMic,
                    recognizerComponent = recognizerKey,
                    onLevel = { rmsDb ->
                        val norm = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
                        _state.update { it.copy(micLevel = norm) }
                    },
                )
            }
            _state.update { it.copy(micLevel = 0f) }

            val transcript = when (result) {
                is VoiceInput.ListenResult.Text -> result.transcript
                is VoiceInput.ListenResult.NoMatch -> continue
                is VoiceInput.ListenResult.Failure -> {
                    _state.update {
                        it.copy(
                            phase = Phase.Error,
                            errorMessage = "Voice failed: ${result.message}",
                        )
                    }
                    return
                }
            }
            _state.update { it.copy(heardText = transcript, phase = Phase.Thinking) }

            val normalized = QuoteParser.filterToVocab(QuoteParser.normalizeNumbers(transcript))
            val quote = QuoteParser.parse(transcript)
            android.util.Log.d("mm-parse", "heard=\"$transcript\"  normalized=\"$normalized\"  parsed=$quote")
            if (quote == null) {
                _state.update {
                    it.copy(errorMessage = "Couldn't parse.\nHeard: \"$transcript\"\nNormalized: \"$normalized\"")
                }
                speech.speak("Sorry, I didn't catch that. Please re-quote your market.")
                continue
            }
            if (quote.ask <= quote.bid) {
                speech.speak("Inverted market. Please re-quote.")
                continue
            }

            grade(contract, quote)
            return
        }
    }

    private suspend fun grade(contract: Contract, quote: QuoteParser.Quote) {
        val result = Sim.grade(contract, quote, cpNoise = 0.20, rng = rng)
        _state.update { it.copy(phase = Phase.Result, lastResult = result, stats = it.stats.apply(result)) }
        speech.speak(Sim.resultSpeech(result))
        _state.update { it.copy(phase = Phase.Idle) }
    }

    override fun onCleared() {
        speech.shutdown()
        super.onCleared()
    }
}
