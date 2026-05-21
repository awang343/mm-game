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
)

class SimViewModel(app: Application) : AndroidViewModel(app) {

    private val client = ContractClient()
    private val voice = VoiceInput(app)
    private val speech = Speech(app)
    private val llm = LlmFallback(app)
    private val rng = Random(System.nanoTime())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun startRound() {
        if (_state.value.phase == Phase.Listening || _state.value.phase == Phase.Speaking) return
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
            runListenLoop(contract)
        }
    }

    fun repeatQuestion() {
        val c = _state.value.contract ?: return
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Speaking) }
            speech.speak(c.question)
            runListenLoop(c)
        }
    }

    private suspend fun runListenLoop(contract: Contract) {
        while (true) {
            _state.update { it.copy(phase = Phase.Listening) }
            val transcript = voice.listen()
            if (transcript.isNullOrBlank()) {
                // silently re-listen on noise/timeout false-triggers
                continue
            }
            _state.update { it.copy(heardText = transcript, phase = Phase.Thinking) }

            val action = QuoteParser.shortcircuitAction(transcript)
            if (action != null) {
                when (action) {
                    "repeat" -> { speech.speak(contract.question); continue }
                    "out"    -> { startRound(); return }
                    "quit"   -> { _state.update { it.copy(phase = Phase.Idle) }; return }
                }
            }

            val det = QuoteParser.parse(transcript)
            val quote = det ?: tryLlm(transcript)
            if (quote == null) {
                val msg = "Sorry, I didn't catch that. Please re-quote your market."
                speech.speak(msg)
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

    private fun tryLlm(transcript: String): QuoteParser.Quote? {
        if (!llm.available) return null
        val r = llm.parse(transcript)
        return when (r) {
            is LlmFallback.ParseResult.Quote -> QuoteParser.Quote(r.bid, r.bidSize, r.ask, r.askSize)
            else -> null
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
