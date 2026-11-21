package com.example.call

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.agentring.AgentringBridge
import com.example.agentring.QrPayload
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * One-time pairing with the agentring relay running on the user's PC.
 *
 * The fast path is a QR scan: JARVIS prints a QR code (`agentring pair`) whose
 * payload is the relay URL plus a short-lived, single-use pairing token. The
 * phone scans it, redeems the token and stores the device credential — no
 * typing, no copying.
 *
 * The manual path (paste the same JSON payload) stays for when the camera is
 * not available.
 */
class PairActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PairScreen() }
    }

    @Composable
    private fun PairScreen() {
        val ctx = LocalContext.current
        val bridge = AgentringBridge.get(ctx)
        var url by remember { mutableStateOf(bridge.relayUrl.ifBlank { "http://100.118.194.3:8080" }) }
        var token by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        /**
         * Redeems a pairing payload. Accepts either the full QR JSON
         * (`{"v":1,"relay_url":…,"pairing_token":…}`) or a bare `pt_…` token
         * typed into the manual field.
         */
        fun pairWith(raw: String) {
            if (busy) return
            val trimmed = raw.trim()
            if (trimmed.isBlank()) {
                status = "Nothing to pair — scan the QR or paste a pairing token first."
                return
            }
            scope.launch {
                busy = true
                status = "Pairing…"
                try {
                    // A scanned QR carries the relay URL with the token; a bare
                    // token uses whatever URL is in the field above.
                    val parsed = if (trimmed.startsWith("{")) {
                        QrPayload.parse(trimmed)
                    } else {
                        null
                    }
                    val targetUrl = parsed?.relayUrl ?: url
                    val targetToken = parsed?.pairingToken ?: trimmed

                    bridge.relayUrl = targetUrl
                    val ok = bridge.pair(targetToken, "AUREX phone")
                    status = if (ok) {
                        "Paired ✓ — JARVIS can now reach this phone."
                    } else {
                        "Pairing failed — the token may have expired (they last 10 minutes) " +
                            "or the relay is unreachable. Generate a new one with `agentring pair`."
                    }
                    if (ok) {
                        Toast.makeText(ctx, "Paired with JARVIS ✓", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    status = "Pairing failed: ${e.message ?: e.javaClass.simpleName}"
                    Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Text("Pair with JARVIS", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Run `agentring pair` on your PC, then scan its QR code here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        status = "Opening scanner…"
                        val options = GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                        GmsBarcodeScanning.getClient(ctx, options).startScan()
                            .addOnSuccessListener { barcode ->
                                val value = barcode.rawValue
                                if (value.isNullOrBlank()) {
                                    status = "That QR code had no readable text."
                                } else {
                                    status = "QR read — pairing…"
                                    pairWith(value)
                                }
                            }
                            .addOnFailureListener { e ->
                                status = "Scanner failed: ${e.message ?: e.javaClass.simpleName}. " +
                                    "You can paste the pairing code below instead."
                            }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }

                Spacer(Modifier.height(16.dp))
                Text("or pair manually", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Relay URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Pairing token (pt_…)") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { pairWith(token) },
                    enabled = token.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair") }

                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
