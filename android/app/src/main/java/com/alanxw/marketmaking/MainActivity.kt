package com.alanxw.marketmaking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {

    private val vm: SimViewModel by viewModels()

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRound()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(
                        vm = vm,
                        onStartClick = ::requestStart,
                    )
                }
            }
        }
    }

    private fun requestStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startRound() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun AppScreen(vm: SimViewModel, onStartClick: () -> Unit) {
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Market Making", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Trades: ${state.stats.trades}   " +
                "Straddled: ${state.stats.straddled}   " +
                "P&L: ${state.stats.pnl.fmtSigned()}",
                fontSize = 14.sp,
            )
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ContractCard(state)
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            ResultCard(state)
            Spacer(Modifier.height(12.dp))
            Row(state, onStartClick, vm)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: speak naturally. \"thirty at forty, five up\". " +
                "Say \"repeat\", \"out\", or \"quit\".",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContractCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            val question = state.contract?.question
                ?: "Tap Start to begin."
            Text(question, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            state.heardText?.let {
                Spacer(Modifier.height(8.dp))
                Text("Heard: \"$it\"", fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(state.phase.label(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            state.errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResultCard(state: UiState) {
    val r = state.lastResult ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Last round", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(r.outcome, fontSize = 14.sp)
            Text("Fair: ${r.trueFair.fmt()}   CP saw: ${"%.2f".format(r.cpFair)}", fontSize = 13.sp)
            Text(
                "Your market: ${r.quote.bid.fmt()} bid for ${r.quote.bidSize}, " +
                "${r.quote.askSize} at ${r.quote.ask.fmt()}",
                fontSize = 13.sp,
            )
            Text("P&L: ${r.pnl.fmtSigned()}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Row(state: UiState, onStart: () -> Unit, vm: SimViewModel) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val busy = state.phase == Phase.Listening || state.phase == Phase.Speaking || state.phase == Phase.Thinking
        Button(onClick = onStart, enabled = !busy) {
            Text(if (state.lastResult == null && state.contract == null) "Start" else "Next contract")
        }
        if (state.contract != null) {
            TextButton(onClick = { vm.repeatQuestion() }, enabled = !busy) {
                Text("Repeat")
            }
        }
    }
}

private fun Phase.label(): String = when (this) {
    Phase.Idle -> "Ready."
    Phase.Speaking -> "Speaking question…"
    Phase.Listening -> "Listening…"
    Phase.Thinking -> "Parsing…"
    Phase.Result -> "Round complete."
    Phase.Error -> "Error."
}

private fun Double.fmt(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString() else "%g".format(this)

private fun Double.fmtSigned(): String {
    val sign = if (this >= 0) "+" else "-"
    return "$sign${this.absoluteValue.fmt()}"
}
