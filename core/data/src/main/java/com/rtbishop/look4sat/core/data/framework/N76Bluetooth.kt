/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.rtbishop.look4sat.core.domain.model.BluetoothAddress
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Classic BT RFCOMM connection to the N76.
 * Tries SPP, then GAIA, then insecure RFCOMM channel 1.
 */
class N76Bluetooth(private val adapter: BluetoothAdapter?) {

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val _connected = AtomicBoolean(false)
    private var device: BluetoothDevice? = null
    private var connecting = AtomicBoolean(false)

    var onLog: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onPacket: ((ByteArray) -> Unit)? = null
    val isConnected get() = _connected.get()

    fun connect(mac: String) {
        val normalized = BluetoothAddress.normalize(mac)
        if (!BluetoothAddress.isValid(normalized)) {
            onLog?.invoke("BT: invalid MAC $mac")
            return
        }
        device = try { adapter?.getRemoteDevice(normalized) } catch (_: Exception) { null }
        if (device == null) {
            onLog?.invoke("BT: no adapter or bad MAC $normalized")
            return
        }
        tryConnect()
    }

    private fun tryConnect() {
        if (connecting.getAndSet(true)) return
        val dev = device ?: run { connecting.set(false); return }
        thread(name = "n76bt-connect", isDaemon = true) {
            try {
                disconnectInternal()
                attemptConnect(dev)
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun attemptConnect(dev: BluetoothDevice) {
        // Inquiry blocks RFCOMM. N76 is not a system-paired accessory — insecure sockets first.
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        val tries = listOf<() -> Pair<String, BluetoothSocket>>(
            { "insecure SPP" to dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { "insecure GAIA" to dev.createInsecureRfcommSocketToServiceRecord(GAIA_UUID) },
            {
                val method = dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                "insecure ch1" to (method.invoke(dev, 1) as BluetoothSocket)
            },
            { "SPP" to dev.createRfcommSocketToServiceRecord(SPP_UUID) },
            { "GAIA" to dev.createRfcommSocketToServiceRecord(GAIA_UUID) }
        )
        for (factory in tries) {
            var sock: BluetoothSocket? = null
            try {
                val (via, created) = factory()
                sock = created
                sock.connect()
                setConnected(sock, via, try { dev.name } catch (_: SecurityException) { null })
                return
            } catch (e: Exception) {
                onLog?.invoke("${e.javaClass.simpleName}: ${e.message}")
                try { sock?.close() } catch (_: Exception) {}
            }
        }
        onLog?.invoke("BT connect failed for ${dev.address}")
        _connected.set(false)
    }

    private fun setConnected(sock: BluetoothSocket, via: String, name: String?) {
        socket = sock
        outputStream = sock.outputStream
        _connected.set(true)
        onLog?.invoke("BT connected ($via) to ${name ?: "unknown"}")
        startReader(sock)
        onConnected?.invoke()
    }

    private fun startReader(sock: BluetoothSocket) {
        thread(name = "n76bt-reader", isDaemon = true) {
            val stream = try {
                sock.inputStream
            } catch (_: Exception) {
                disconnectInternal(); return@thread
            }
            val chunk = ByteArray(512)
            var buf = ByteArray(0)
            try {
                while (true) {
                    val n = stream.read(chunk)
                    if (n <= 0) break
                    buf += chunk.copyOf(n)
                    var i = 0
                    while (i < buf.size) {
                        if (buf[i] != 0xFF.toByte()) { i++; continue }
                        if (buf.size - i < 8) break
                        val plen = buf[i + 3].toInt() and 0xFF
                        val flags = buf[i + 2].toInt() and 0xFF
                        val total = 8 + plen + (flags and 1)
                        if (buf.size - i < total) break
                        try { onPacket?.invoke(buf.copyOfRange(i, i + total)) } catch (_: Exception) {}
                        i += total
                    }
                    buf = if (i < buf.size) buf.copyOfRange(i, buf.size) else ByteArray(0)
                }
            } catch (_: Exception) {}
            onLog?.invoke("BT reader ended")
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        _connected.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
    }

    @Synchronized
    fun send(bytes: ByteArray): Boolean {
        if (!_connected.get()) {
            tryConnect()
            return false
        }
        return try {
            outputStream?.write(bytes)
            outputStream?.flush()
            true
        } catch (e: Exception) {
            onLog?.invoke("BT write error: ${e.message}, reconnecting…")
            disconnectInternal()
            tryConnect()
            false
        }
    }

    fun disconnect() {
        disconnectInternal()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val GAIA_UUID: UUID = UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")
    }
}
