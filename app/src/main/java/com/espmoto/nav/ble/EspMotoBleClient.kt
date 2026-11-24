package com.espmoto.nav.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.espmoto.nav.obd.BikeProfile
import com.espmoto.nav.obd.ObdConfigStore
import com.espmoto.nav.service.HelmetAudioAlerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object EspMotoBleClient {
    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private const val SCAN_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _statusMessage = MutableStateFlow("Tap Connect to pair with ${BleProtocol.DEVICE_NAME}")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _lastPacket = MutableStateFlow<NavPacket?>(null)
    val lastPacket: StateFlow<NavPacket?> = _lastPacket

    private val _pairedDevice = MutableStateFlow<PairedDevice?>(null)
    val pairedDevice: StateFlow<PairedDevice?> = _pairedDevice

    private var gatt: BluetoothGatt? = null
    private var navChar: BluetoothGattCharacteristic? = null
    private var obdConfigChar: BluetoothGattCharacteristic? = null
    private var pairChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23
    private var adapter: BluetoothAdapter? = null
    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var appContext: Context? = null
    private var lastDevice: BluetoothDevice? = null
    private var userRequestedDisconnect = false

    fun refreshPairing(context: Context) {
        val ctx = context.applicationContext
        HelmetAudioAlerts.load(ctx)
        _pairedDevice.value = PairingStore.load(ctx)
    }

    fun hasBlePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun pairWithQrPayload(context: Context, rawQr: String): Result<PairedDevice> {
        val parsed = PairingStore.parsePairingUri(rawQr)
            ?: return Result.failure(IllegalArgumentException("Invalid espMoto QR code"))
        val ctx = context.applicationContext
        PairingStore.save(ctx, parsed)
        _pairedDevice.value = parsed
        if (hasBlePermissions(ctx)) {
            _statusMessage.value = "QR paired (${parsed.mac}) — connecting..."
            connect(ctx)
        } else {
            _statusMessage.value = "QR paired (${parsed.mac}) — grant Bluetooth permission to connect"
        }
        return Result.success(parsed)
    }

    fun clearPairing(context: Context) {
        val ctx = context.applicationContext
        PairingStore.clear(ctx)
        _pairedDevice.value = null
        disconnect()
        _statusMessage.value = "Pairing cleared — tap Scan QR to pair"
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        HelmetAudioAlerts.load(ctx)
        userRequestedDisconnect = false
        cancelPendingReconnect()
        if (_pairedDevice.value == null) {
            _pairedDevice.value = PairingStore.load(ctx)
        }

        if (!hasBlePermissions(ctx)) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth permission required"
            return
        }

        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
        if (adapter == null) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth not available on this device"
            return
        }
        if (adapter?.isEnabled != true) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Enable Bluetooth and try again"
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "BLE scanner unavailable"
            return
        }

        stopActiveScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        navChar = null
        obdConfigChar = null
        pairChar = null
        negotiatedMtu = 23

        val paired = _pairedDevice.value
        if (paired != null && BluetoothAdapter.checkBluetoothAddress(paired.mac)) {
            val remote = runCatching { adapter?.getRemoteDevice(paired.mac) }.getOrNull()
            if (remote != null) {
                lastDevice = remote
                _state.value = ConnectionState.CONNECTING
                _statusMessage.value = "Connecting to ${paired.name} (${paired.mac})..."
                try {
                    gatt = remote.connectGatt(
                        ctx,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } catch (_: SecurityException) {
                    _state.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = "Bluetooth permission required"
                }
                return
            }
        }

        _state.value = ConnectionState.SCANNING
        _statusMessage.value = "Scanning for ${BleProtocol.DEVICE_NAME}..."
        activeScanner = scanner

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.NAV_SERVICE_UUID))
                .build(),
            ScanFilter.Builder()
                .setDeviceName(BleProtocol.DEVICE_NAME)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: result.scanRecord?.deviceName
                val hasUuid = result.scanRecord?.serviceUuids?.any {
                    it.uuid == BleProtocol.NAV_SERVICE_UUID
                } == true
                if (!hasUuid && deviceName != BleProtocol.DEVICE_NAME) {
                    return
                }

                stopActiveScan()
                lastDevice = result.device
                _state.value = ConnectionState.CONNECTING
                _statusMessage.value = "Connecting to ${deviceName ?: result.device.address}..."
                gatt = result.device.connectGatt(
                    ctx,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }

            override fun onScanFailed(errorCode: Int) {
                stopActiveScan()
                _state.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "BLE scan failed (code $errorCode)"
            }
        }

        activeScanCallback = callback
        val timeout = Runnable {
            if (_state.value == ConnectionState.SCANNING) {
                stopActiveScan()
                _state.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "${BleProtocol.DEVICE_NAME} not found (scan timed out)"
            }
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)

        scanner.startScan(filters, settings, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        val cb = activeScanCallback
        val scanner = activeScanner
        activeScanCallback = null
        activeScanner = null
        if (cb != null && scanner != null) {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userRequestedDisconnect = true
        lastDevice = null
        cancelPendingReconnect()
        stopActiveScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        navChar = null
        obdConfigChar = null
        pairChar = null
        negotiatedMtu = 23
        _state.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "Disconnected from ${BleProtocol.DEVICE_NAME}"
    }

    @SuppressLint("MissingPermission")
    fun sendNavPacket(packet: NavPacket) {
        _lastPacket.value = packet

        val currentGatt = gatt ?: return
        val char = navChar ?: return
        val bytes = packet.encode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                char,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(char)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendObdConfig(profile: BikeProfile): Boolean {
        val wireJson = profile.toWireJson()

        val currentGatt = gatt ?: return false
        val char = obdConfigChar ?: return false
        val bytes = (wireJson + "\n").toByteArray(Charsets.UTF_8)
        val chunkSize = (negotiatedMtu - 3).coerceIn(20, 480)

        scope.launch {
            var offset = 0
            while (offset < bytes.size) {
                val end = (offset + chunkSize).coerceAtMost(bytes.size)
                val chunk = bytes.copyOfRange(offset, end)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    currentGatt.writeCharacteristic(
                        char,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    currentGatt.writeCharacteristic(char)
                }
                offset = end
                if (offset < bytes.size) {
                    kotlinx.coroutines.delay(25)
                }
            }
        }
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelPendingReconnect()
                gatt = g
                _statusMessage.value = "Negotiating MTU & discovering services..."
                if (!g.requestMtu(512)) {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = _state.value == ConnectionState.CONNECTED
                g.close()
                if (gatt === g) {
                    gatt = null
                }
                navChar = null
                obdConfigChar = null
                pairChar = null
                negotiatedMtu = 23

                if (wasConnected && !userRequestedDisconnect) {
                    HelmetAudioAlerts.playBleDisconnectedUnexpectedly()
                }

                val ctx = appContext
                val dev = lastDevice
                if (!userRequestedDisconnect && dev != null && ctx != null) {
                    _state.value = ConnectionState.CONNECTING
                    _statusMessage.value = "Reconnecting to ${BleProtocol.DEVICE_NAME}..."
                    cancelPendingReconnect()
                    val retry = Runnable {
                        if (!userRequestedDisconnect && gatt == null) {
                            runCatching {
                                gatt = dev.connectGatt(
                                    ctx,
                                    true,
                                    this,
                                    BluetoothDevice.TRANSPORT_LE
                                )
                            }
                        }
                    }
                    reconnectRunnable = retry
                    mainHandler.postDelayed(retry, 1500L)
                } else {
                    _state.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = "Disconnected (status $status)"
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                negotiatedMtu = mtu
            }
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusMessage.value = "Service discovery failed ($status)"
                g.disconnect()
                return
            }
            val service = g.getService(BleProtocol.NAV_SERVICE_UUID)
            val discoveredChar = service?.getCharacteristic(BleProtocol.NAV_CHAR_UUID)
            val discoveredCfgChar = service?.getCharacteristic(BleProtocol.OBD_CONFIG_CHAR_UUID)
            val discoveredPairChar = service?.getCharacteristic(BleProtocol.PAIR_CHAR_UUID)
            if (discoveredChar != null) {
                navChar = discoveredChar
                obdConfigChar = discoveredCfgChar
                pairChar = discoveredPairChar

                if (discoveredPairChar != null) {
                    val paired = _pairedDevice.value ?: appContext?.let { PairingStore.load(it) }
                    if (paired == null) {
                        _statusMessage.value = "Scan QR on espMoto to pair first"
                        userRequestedDisconnect = true
                        g.disconnect()
                        return
                    }
                    _statusMessage.value = "Authenticating pairing token..."
                    val tokenBytes = paired.token.toByteArray(Charsets.UTF_8)
                    val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            discoveredPairChar,
                            tokenBytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        discoveredPairChar.value = tokenBytes
                        @Suppress("DEPRECATION")
                        discoveredPairChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(discoveredPairChar)
                    }
                    if (!writeStarted) {
                        _statusMessage.value = "Failed to send pairing token"
                        g.disconnect()
                    }
                    return
                }

                _state.value = ConnectionState.CONNECTED
                _statusMessage.value = "Connected to ${g.device.name ?: BleProtocol.DEVICE_NAME}"
                HelmetAudioAlerts.playBleConnected()
                _lastPacket.value?.let { sendNavPacket(it) }
                appContext?.let { ctx ->
                    sendObdConfig(ObdConfigStore.load(ctx))
                }
            } else {
                _statusMessage.value = "Nav characteristic not found on device"
                g.disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == BleProtocol.PAIR_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _state.value = ConnectionState.CONNECTED
                    _statusMessage.value = "Paired & connected to ${g.device.name ?: BleProtocol.DEVICE_NAME}"
                    HelmetAudioAlerts.playBleConnected()
                    _lastPacket.value?.let { sendNavPacket(it) }
                    appContext?.let { ctx ->
                        sendObdConfig(ObdConfigStore.load(ctx))
                    }
                } else {
                    _statusMessage.value = "Pairing token rejected (status $status)"
                    userRequestedDisconnect = true
                    g.disconnect()
                }
            }
        }
    }
}
