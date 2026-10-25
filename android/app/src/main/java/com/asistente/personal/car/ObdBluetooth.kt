package com.asistente.personal.car

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Nombres con los que se anuncian los lectores ELM327 más comunes. */
private val ADAPTER_NAME = Regex("obd|elm|v-?link|vgate|veepeak|konnwei|icar", RegexOption.IGNORE_CASE)

fun hasBluetoothPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** El lector OBD-II ya emparejado con el teléfono (en Ajustes → Bluetooth), si hay uno. */
@SuppressLint("MissingPermission")
fun findObdAdapter(context: Context): BluetoothDevice? {
    if (!hasBluetoothPermission(context)) return null
    val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
    return adapter.bondedDevices.firstOrNull { it.name?.let(ADAPTER_NAME::containsMatchIn) == true }
}

/** Habla con un ELM327 por Bluetooth clásico (puerto serie). */
@SuppressLint("MissingPermission")
class BluetoothObdLink(device: BluetoothDevice) : ObdLink {
    private val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP)

    init {
        socket.connect()
    }

    override fun send(command: String): String {
        socket.outputStream.write((command + "\r").toByteArray())
        socket.outputStream.flush()
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        // El lector termina cada respuesta con «>».
        while (System.currentTimeMillis() < deadline) {
            if (socket.inputStream.available() > 0) {
                val c = socket.inputStream.read()
                if (c < 0) throw IOException("El lector cerró la conexión")
                if (c.toChar() == '>') return out.toString()
                out.append(c.toChar())
            } else {
                Thread.sleep(20)
            }
        }
        throw IOException("El lector no contestó a $command")
    }

    override fun close() = try { socket.close() } catch (_: IOException) { }

    companion object {
        const val REPLY_TIMEOUT_MS = 3_000L
    }
}

/**
 * Lee el coche mientras haga falta (el coche está conectado a Vega): busca el lector, se conecta,
 * pide las lecturas cada ~2 s y las entrega. Si se pierde, reintenta cada 10 s. Solo lectura.
 */
class ObdMonitor(private val context: Context, private val onReading: (VehicleReading?) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "vega-obd", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        onReading(null)
    }

    private fun loop() {
        while (running) {
            val device = findObdAdapter(context)
            if (device == null) {
                sleep(RETRY_MS)
                continue
            }
            try {
                BluetoothObdLink(device).use { link ->
                    val reader = ObdReader(link).also { it.open() }
                    while (running) {
                        onReading(reader.read())
                        sleep(POLL_MS)
                    }
                }
            } catch (_: IOException) {
                onReading(null) // se perdió el lector (coche apagado, fuera de alcance…)
            } catch (_: SecurityException) {
                onReading(null)
            }
            sleep(RETRY_MS)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // se está parando
        }
    }

    companion object {
        const val POLL_MS = 2_000L
        const val RETRY_MS = 10_000L
    }
}
