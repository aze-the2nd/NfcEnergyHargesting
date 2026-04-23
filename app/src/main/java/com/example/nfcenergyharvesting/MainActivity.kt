package com.example.nfcenergyharvesting

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.nfcenergyharvesting.ui.theme.NfcEnergyHarvestingTheme
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val sessionRunning = AtomicBoolean(false)

    private var statusText by mutableStateOf("App gestartet. Prüfe NFC...")

    // aktive Werte
    private var harvestTimeMs by mutableStateOf(1000L)
    private var presenceCheckDelayMs by mutableStateOf(500)

    // Texte in den Eingabefeldern
    private var harvestTimeInput by mutableStateOf("1000")
    private var presenceDelayInput by mutableStateOf("500")


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        statusText = when {
            nfcAdapter == null -> "Dieses Gerät unterstützt kein NFC."
            !nfcAdapter!!.isEnabled -> "NFC ist vorhanden, aber aktuell deaktiviert."
            else -> "NFC ist verfügbar und aktiviert."
        }

        setContent {
            NfcEnergyHarvestingTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        statusText = statusText,
                        harvestTimeInput = harvestTimeInput,
                        presenceDelayInput = presenceDelayInput,
                        currentHarvestTimeMs = harvestTimeMs,
                        currentPresenceDelayMs = presenceCheckDelayMs,
                        onHarvestTimeInputChange = { harvestTimeInput = it },
                        onPresenceDelayInputChange = { presenceDelayInput = it },
                        onApplyClick = { applySettings() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableReaderModeWithCurrentSettings()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    private fun enableReaderModeWithCurrentSettings() {
        val adapter = nfcAdapter
        if (adapter == null) {
            statusText = "Kein NFC-Adapter vorhanden."
            return
        }

        if (!adapter.isEnabled) {
            statusText = "NFC ist deaktiviert."
            return
        }

        val options = Bundle().apply {
            putInt(
                NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                presenceCheckDelayMs
            )
        }

        statusText =
            "Reader Mode aktiv. Warte auf NTAG..." +
                    "Harvest: ${harvestTimeMs} ms\n" +
                    "Presence Delay: ${presenceCheckDelayMs} ms"

        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            options
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) {
            updateStatus("Tag erkannt, aber ungültig")
            return
        }

        if (!sessionRunning.compareAndSet(false, true)){
            return
        }


        val nfcA = NfcA.get(tag)
        if (nfcA == null){
            updateStatus("Tag erkannt, aber NfcA wird nicht unterstützt.")
            sessionRunning.set(false)
            return
        }


        val techList = tag.techList.joinToString(", ")

        try {
            updateStatus(
                "NFC-Modul erkannt. \n" +
                "Techs: $techList\n" +
                "Verbinde mit NfcA..."
            )
            nfcA.connect()

            updateStatus(
                "NFC-Modul verbunden. \n" +
                "Harvesting läuft für ${harvestTimeMs} ms"
            )

            val startTime = SystemClock.elapsedRealtime()

            while (SystemClock.elapsedRealtime() - startTime < harvestTimeMs) {
                SystemClock.sleep(50)
            }

            updateStatus(
                "Harvesting-Phase abgeschlossen.\n" +
                "Verbindung war ${harvestTimeMs} ms offen. \n" +
                "ATQA: ${toHex(nfcA.atqa)}\n" +
                "SAK: 0x${String.format(Locale.GERMANY, "%02X", nfcA.sak)}"
            )

        } catch (e: IOException) {
            updateStatus("NfcA-Verbindung fehlgeschlagen: ${e.message ?: "I/O-Fehler"}")
        } catch (e: Exception) {
            updateStatus("Unerwarteter Fehler: ${e.message ?: "unbekannt"}")
        } finally {
            try {
                if (nfcA.isConnected){
                    nfcA.close()
                }
            } catch (_: IOException) {
            }

            sessionRunning.set(false)
        }

    }

    private fun applySettings() {
        val newHarvest = harvestTimeInput.toLongOrNull()
        val newPresenceDelay = presenceDelayInput.toIntOrNull()

        if (newHarvest == null || newHarvest <= 0L) {
            statusText = "Ungültiger Wert für HARVEST_TIME_MS."
            return
        }

        if (newPresenceDelay == null || newPresenceDelay <= 0){
            statusText = "Ungültiger Wert für PRESENCE_CHECK_DELAY_MS."
            return
        }

        harvestTimeMs = newHarvest
        presenceCheckDelayMs = newPresenceDelay

        nfcAdapter?.disableReaderMode(this)
        enableReaderModeWithCurrentSettings()

        statusText =
            "Werte übernommen. \n" +
                    "Harvest: ${harvestTimeMs} ms \n" +
                    "Presence Delay: ${presenceCheckDelayMs} ms \n" +
                    "Reader Mode neu gestartet."
    }


    private fun updateStatus(text: String){
        runOnUiThread {
            statusText = text
        }
    }
    private fun toHex(bytes: ByteArray?): String{
        if  (bytes == null) return "-"

        return bytes.joinToString(" ") {
            byte -> String.format(Locale.GERMANY, "%02X", byte.toInt() and 0xFF)
        }
    }
}

@androidx.compose.runtime.Composable
fun MainScreen(
    statusText: String,
    harvestTimeInput: String,
    presenceDelayInput: String,
    currentHarvestTimeMs: Long,
    currentPresenceDelayMs: Int,
    onHarvestTimeInputChange: (String) -> Unit,
    onPresenceDelayInputChange: (String) -> Unit,
    onApplyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NFC Energy Harvesting",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Aktiv: Harvest ${currentHarvestTimeMs} ms | Presence ${currentPresenceDelayMs} ms",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        OutlinedTextField(
            value = harvestTimeInput,
            onValueChange = onHarvestTimeInputChange,
            label = { Text("HARVEST_TIME_MS") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.padding(top = 16.dp)
        )

        OutlinedTextField(
            value = presenceDelayInput,
            onValueChange = onPresenceDelayInputChange,
            label = { Text("PRESENCE_CHECK_DELAY_MS") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(
            onClick = onApplyClick,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Übernehmen")
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}