package com.alanxw.marketmaking

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * HTTP client for the contract server. One endpoint, one method.
 * URL is supplied by the caller — see SettingsStore / Settings screen.
 */
class ContractClient(private val baseUrl: String) {

    suspend fun randomContract(): Contract = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + "/contracts/random")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            Contract(
                id = o.getInt("id"),
                question = o.getString("question"),
                answer = o.getDouble("answer"),
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun health(): Int? = withContext(Dispatchers.IO) {
        try {
            val url = URL(baseUrl.trimEnd('/') + "/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).optInt("contracts", -1).takeIf { it >= 0 }
        } catch (t: Throwable) {
            Log.w("ContractClient", "health failed: ${t.message}")
            null
        }
    }

    companion object {
        // Useful as a placeholder hint in the settings screen.
        const val EXAMPLE_URL = "http://192.168.1.42:7878"
    }
}
