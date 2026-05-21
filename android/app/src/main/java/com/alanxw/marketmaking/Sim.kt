package com.alanxw.marketmaking

import kotlin.math.absoluteValue
import kotlin.random.Random

data class Contract(val id: Int, val question: String, val answer: Double)

data class RoundResult(
    val contract: Contract,
    val quote: QuoteParser.Quote,
    val trueFair: Double,
    val cpFair: Double,
    val pnl: Double,
    val outcome: String,
    val outcomeSpoken: String,
    val straddled: Boolean,
)

data class SessionStats(
    val trades: Int = 0,
    val pnl: Double = 0.0,
    val straddled: Int = 0,
) {
    fun apply(r: RoundResult) = SessionStats(
        trades = trades + 1,
        pnl = pnl + r.pnl,
        straddled = straddled + (if (r.straddled) 1 else 0),
    )
}

object Sim {
    /** Counterparty's view of fair is Gaussian around true fair; trade decision uses
     *  it but PnL is computed against the true answer — lets the player get lucky. */
    fun grade(
        contract: Contract,
        quote: QuoteParser.Quote,
        cpNoise: Double = 0.20,
        rng: Random = Random.Default,
    ): RoundResult {
        val fair = contract.answer
        val cpFair = if (cpNoise > 0) fair + gauss(rng) * fair.absoluteValue * cpNoise else fair
        val straddled = quote.bid <= fair && fair <= quote.ask

        val (pnl, outcome, spoken) = when {
            quote.bid > cpFair -> Triple(
                (fair - quote.bid) * quote.bidSize,
                "Counterparty SOLD ${quote.bidSize} to you at ${quote.bid.fmt()}.",
                "Counterparty sold ${quote.bidSize} to you at ${quote.bid.fmt()}.",
            )
            quote.ask < cpFair -> Triple(
                (quote.ask - fair) * quote.askSize,
                "Counterparty BOUGHT ${quote.askSize} from you at ${quote.ask.fmt()}.",
                "Counterparty bought ${quote.askSize} from you at ${quote.ask.fmt()}.",
            )
            else -> Triple(
                0.0,
                "No trade — counterparty saw no edge.",
                "No trade.",
            )
        }
        return RoundResult(contract, quote, fair, cpFair, pnl, outcome, spoken, straddled)
    }

    fun resultSpeech(r: RoundResult): String {
        val verb = when {
            r.pnl > 0 -> "made ${r.pnl.absoluteValue.toInt()}"
            r.pnl < 0 -> "lost ${r.pnl.absoluteValue.toInt()}"
            else -> "broke even"
        }
        return "${r.outcomeSpoken} True answer was ${r.trueFair.fmt()}. You $verb."
    }

    private fun gauss(rng: Random): Double {
        // Box-Muller
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun Double.fmt(): String =
        if (this == this.toLong().toDouble()) this.toLong().toString() else "%g".format(this)
}
