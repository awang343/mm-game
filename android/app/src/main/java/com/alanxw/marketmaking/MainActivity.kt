package com.alanxw.marketmaking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        enableEdgeToEdge()
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
    if (state.showSettings) {
        SettingsScreen(
            initialUrl = state.serverUrl,
            initialUseBluetooth = state.useBluetoothMic,
            initialRecognizerKey = state.recognizerKey,
            recognizers = state.availableRecognizers,
            onSave = vm::saveSettings,
            onCancel = if (state.serverUrl != null) vm::cancelSettings else null,
        )
    } else {
        GameScreen(state = state, vm = vm, onStartClick = onStartClick)
    }
}

@Composable
private fun GameScreen(state: UiState, vm: SimViewModel, onStartClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(serverUrl = state.serverUrl, stats = state.stats, onSettings = vm::openSettings)
        ContractCard(state)
        state.lastResult?.let { ResultCard(it) }
        Spacer(Modifier.height(0.dp))
        ActionRow(state, onStartClick, vm)
        Text(
            "Speak naturally: \"thirty at forty, five up\". Say \"repeat\", \"out\", or \"quit\".",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(serverUrl: String?, stats: SessionStats, onSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Market Making",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Text(
            "Trades: ${stats.trades}   Straddled: ${stats.straddled}   P&L: ${stats.pnl.fmtSigned()}",
            fontSize = 13.sp,
        )
        serverUrl?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContractCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            val question = state.contract?.question ?: "Tap Start to begin."
            Text(question, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            state.heardText?.let {
                Spacer(Modifier.height(6.dp))
                Text("Heard: \"$it\"", fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(state.phase.label(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            if (state.phase == Phase.Listening) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.micLevel },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "mic level (live)",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.errorMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResultCard(r: RoundResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Last round", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(r.outcome, fontSize = 13.sp)
            Text("Fair: ${r.trueFair.fmt()}   CP saw: ${"%.2f".format(r.cpFair)}", fontSize = 12.sp)
            Text(
                "You: ${r.quote.bid.fmt()} bid for ${r.quote.bidSize}, " +
                "${r.quote.askSize} at ${r.quote.ask.fmt()}",
                fontSize = 12.sp,
            )
            Text("P&L: ${r.pnl.fmtSigned()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActionRow(state: UiState, onStart: () -> Unit, vm: SimViewModel) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val busy = state.phase == Phase.Listening || state.phase == Phase.Speaking || state.phase == Phase.Thinking
        Button(onClick = onStart, enabled = !busy) {
            Text(if (state.lastResult == null && state.contract == null) "Start" else "Next contract")
        }
        if (state.contract != null) {
            OutlinedButton(onClick = { vm.repeatQuestion() }, enabled = !busy) {
                Text("Repeat")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    initialUrl: String?,
    initialUseBluetooth: Boolean,
    initialRecognizerKey: String?,
    recognizers: List<VoiceInput.RecognizerInfo>,
    onSave: (String, Boolean, String?) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var url by remember { mutableStateOf(initialUrl ?: "") }
    var useBluetooth by remember { mutableStateOf(initialUseBluetooth) }
    var recognizerKey by remember { mutableStateOf(initialRecognizerKey) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Address of the contract server you want to connect to. Saved on this device.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text(ContractClient.EXAMPLE_URL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use Bluetooth headset mic", fontSize = 14.sp)
                Text(
                    "Routes audio through a paired headset's mic via SCO. " +
                    "Make sure the headset is connected first.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useBluetooth, onCheckedChange = { useBluetooth = it })
        }

        Text("Speech recognizer", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Pick which installed speech recognition service to use. " +
            "Useful on LineageOS / no-GMS devices where the default is a broken stub.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecognizerOption(
            selected = recognizerKey == SimViewModel.VOSK_KEY,
            label = "Vosk (bundled, recommended)",
            subtitle = "On-device, works without GMS. ~50 MB model included in the APK.",
            onSelect = { recognizerKey = SimViewModel.VOSK_KEY },
        )
        RecognizerOption(
            selected = recognizerKey == null,
            label = "System default",
            subtitle = "Whatever the OS picks (often broken on LineageOS).",
            onSelect = { recognizerKey = null },
        )
        recognizers.forEach { r ->
            RecognizerOption(
                selected = recognizerKey == r.key,
                label = r.label,
                subtitle = r.packageName,
                onSelect = { recognizerKey = r.key },
            )
        }
        if (recognizers.isEmpty()) {
            Text(
                "No recognizer services found at all. Install one (e.g. Sayboard, FUTO Voice Input) from F-Droid.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        MicTestSection()

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onSave(url, useBluetooth, recognizerKey) },
                enabled = url.isNotBlank(),
            ) { Text("Save") }
            onCancel?.let {
                OutlinedButton(onClick = it) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun MicTestSection() {
    val scope = rememberCoroutineScope()
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    Text("Mic test", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Text(
        "Captures raw audio via AudioRecord for 5s. Bypasses the speech recognizer " +
        "so we can tell whether the mic itself works.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = {
        if (active) {
            job?.cancel()
            active = false
        } else {
            level = 0f
            peak = 0f
            status = null
            active = true
            job = scope.launch {
                runMicTest(
                    durationMs = 5000,
                    onLevel = { l ->
                        level = l
                        if (l > peak) peak = l
                    },
                    onStatus = { msg ->
                        status = msg
                        active = false
                    },
                )
            }
        }
    }) {
        Text(if (active) "Stop" else "Test mic (5s)")
    }
    if (active || peak > 0f) {
        LinearProgressIndicator(
            progress = { (level * 10f).coerceAtMost(1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "live=${"%.4f".format(level)}   peak=${"%.4f".format(peak)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    status?.let {
        val isErr = it.startsWith("OK").not()
        Text(
            it,
            fontSize = 12.sp,
            color = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RecognizerOption(
    selected: Boolean,
    label: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.height(0.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
