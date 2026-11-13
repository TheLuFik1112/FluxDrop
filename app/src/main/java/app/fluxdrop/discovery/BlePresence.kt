package app.fluxdrop.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import app.fluxdrop.core.PeerInfo
import app.fluxdrop.core.TLS_PORT
import kotlinx.coroutines.CoroutineScope

/**
 * BLE presence: устройство заявляет о себе, даже если LAN-обнаружение не сработало.
 * Полный PeerInfo не влезает в legacy-пакет, поэтому передаём префикс id и порт,
 * а сертификат забираем уже по TLS при первой передаче.
 */
class BlePresence(
    context: Context,
    @Suppress("unused") private val scope: CoroutineScope,
    private val self: () -> PeerInfo,
    private val onPeer: PeerCallback,
) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var advertising = false
    private var scanning = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (data.size < 9 || data[0].toInt() != VERSION) return

            val idPrefix = (0 until 4).joinToString("") { "%02x".format(data[1 + it]) }
            if (self().id.startsWith(idPrefix)) return

            val port = ((data[5].toInt() and 0xff) shl 8) or (data[6].toInt() and 0xff)
            val peer = PeerInfo(
                id = idPrefix,
                name = "Устройство рядом",
                platform = "android",
                port = port.takeIf { it > 0 } ?: TLS_PORT,
                certFingerprint = idPrefix,
            )
            onPeer(peer, "", "ble")
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val bluetooth = adapter ?: return
        if (!bluetooth.isEnabled) return

        runCatching { advertise(bluetooth) }
        runCatching { scan(bluetooth) }
    }

    @SuppressLint("MissingPermission")
    private fun advertise(bluetooth: BluetoothAdapter) {
        if (!bluetooth.isMultipleAdvertisementSupported) return
        val advertiser = bluetooth.bluetoothLeAdvertiser ?: return

        val me = self()
        val payload = buildPayload(me)
        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payload)
            .setIncludeDeviceName(false)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun scan(bluetooth: BluetoothAdapter) {
        if (!bluetooth.isEnabled) return
        val scanner = bluetooth.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setManufacturerData(MANUFACTURER_ID, ByteArray(0))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching {
            if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        runCatching {
            if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        advertising = false
        scanning = false
    }

    private fun buildPayload(me: PeerInfo): ByteArray {
        val hex = me.id.padEnd(8, '0')
        val idBytes = ByteArray(4) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val port = me.port
        val nameHash = me.name.hashCode() and 0xffff
        return byteArrayOf(
            VERSION.toByte(),
            idBytes[0], idBytes[1], idBytes[2], idBytes[3],
            (port shr 8).toByte(), port.toByte(),
            (nameHash shr 8).toByte(), nameHash.toByte(),
        )
    }

    companion object {
        const val MANUFACTURER_ID = 0x0FC5
        private const val VERSION = 1
    }
}
