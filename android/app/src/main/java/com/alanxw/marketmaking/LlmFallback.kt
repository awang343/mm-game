package com.alanxw.marketmaking

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.json.JSONObject
import java.io.File

/**
 * Google AI Edge / MediaPipe LLM Inference fallback for inputs the
 * deterministic QuoteParser can't handle.
 *
 * Requires a Gemma (or compatible) `.task` model file on the device. By
 * convention we look at /data/local/tmp/llm/gemma.task — pushed via:
 *     adb push gemma-3-1b-it-int4.task /data/local/tmp/llm/gemma.task
 * Get models from https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference
 *
 * If the model isn't present, parse() returns null and the caller can fall back
 * to "didn't catch that" re-prompt UX.
 */
class LlmFallback(context: Context) {
    private val modelPath = File("/data/local/tmp/llm/gemma.task")
    private val llm: LlmInference? = try {
        if (modelPath.exists()) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath.absolutePath)
                .setMaxTokens(256)
                .build()
            LlmInference.createFromOptions(context, options)
        } else null
    } catch (t: Throwable) {
        android.util.Log.w("LlmFallback", "failed to init: ${t.message}")
        null
    }

    val available: Boolean get() = llm != null

    /** Parse free-form text into a quote or action. Returns null on failure. */
    fun parse(text: String): ParseResult? {
        val engine = llm ?: return null
        val prompt = buildPrompt(text)
        val raw = try {
            LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0f)
                    .setTopK(1)
                    .build(),
            ).use { session ->
                session.addQueryChunk(prompt)
                session.generateResponse()
            }
        } catch (t: Throwable) {
            android.util.Log.w("LlmFallback", "generate failed: ${t.message}")
            return null
        }
        return parseJsonResponse(raw)
    }

    private fun buildPrompt(text: String) = """
You convert spoken trader input into JSON. Either an ACTION:
  {"action":"out"} | {"action":"quit"} | {"action":"repeat"} | {"action":"unparsed"}
or a QUOTE:
  {"bid":<number>,"bid_size":<int>,"ask":<number>,"ask_size":<int>}
"X at Y, Z up" means bid=X, ask=Y, size=Z. bid<ask. Return ONE JSON object only.

Input: $text
JSON:""".trimIndent()

    private fun parseJsonResponse(raw: String): ParseResult? {
        val s = raw.substringAfter('{').let { "{$it" }
            .substringBeforeLast('}') + "}"
        return try {
            val o = JSONObject(s)
            when {
                o.has("action") -> ParseResult.Action(o.getString("action"))
                o.has("bid") && o.has("ask") -> ParseResult.Quote(
                    bid = o.getDouble("bid"),
                    bidSize = o.optInt("bid_size", o.optInt("size", 1)),
                    ask = o.getDouble("ask"),
                    askSize = o.optInt("ask_size", o.optInt("size", 1)),
                )
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    sealed class ParseResult {
        data class Quote(val bid: Double, val bidSize: Int, val ask: Double, val askSize: Int) : ParseResult()
        data class Action(val name: String) : ParseResult()
    }
}
