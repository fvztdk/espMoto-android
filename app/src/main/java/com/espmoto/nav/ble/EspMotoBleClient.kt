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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.atomic.AtomicBoolean

object EspMotoBleClient {
    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, WAITING_IN_RANGE, CONNECTED }

    private const val SCAN_TIMEOUT_MS = 10_000L
    private const val DIRECT_CONNECT_TIMEOUT_MS = 12_000L
    private const val LOW_POWER_SCAN_REFRESH_MS = 20 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gattLock = Any()
    private val btReceiverRegistered = AtomicBoolean(false)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _statusMessage = MutableStateFlow("Not connected to ${BleProtocol.DEVICE_NAME}")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _lastPacket = MutableStateFlow<NavPacket?>(null)
    val lastPacket: StateFlow<NavPacket?> = _lastPacket

    private val _pairedDevice = MutableStateFlow<PairedDevice?>(null)
    val pairedDevice: StateFlow<PairedDevice?> = _pairedDevice

    private val _autoReconnectEnabled = MutableStateFlow(true)
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled

    private var gatt: BluetoothGatt? = null
    private var navChar: BluetoothGattCharacteristic? = null
    private var obdConfigChar: BluetoothGattCharacteristic? = null
    private var pairChar: BluetoothGattCharacteristic? = null
    private var simObdChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23
    private var adapter: BluetoothAdapter? = null
    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var connectTimeoutRunnable: Runnable? = null
    private var scanRefreshRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttemptCount = 0
    private var appContext: Context? = null
    private var lastDevice: BluetoothDevice? = null
    private var userRequestedDisconnect = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val ctx = appContext ?: context?.applicationContext ?: return
            when (state) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    cancelTimers()
                    stopActiveScan()
                    closeCurrentGatt()
                    _state.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = if (
                        _autoReconnectEnabled.value &&
                        _pairedDevice.value != null &&
                        !userRequestedDisconnect
                    ) {
                        "Bluetooth is off — will auto-reconnect when enabled"
                    } else {
                        "Bluetooth is off"
                    }
                }
                BluetoothAdapter.STATE_ON -> {
                    if (_autoReconnectEnabled.value &&
                        _pairedDevice.value != null &&
                        !userRequestedDisconnect
                    ) {
                        cancelTimers()
                        val retry = Runnable { ensureAutoConnectIfEligible(ctx) }
                        reconnectRunnable = retry
                        mainHandler.postDelayed(retry, 1000L)
                    }
                }
            }
        }
    }

    fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        HelmetAudioAlerts.load(ctx)
        _pairedDevice.value = PairingStore.load(ctx)
        _autoReconnectEnabled.value = PairingStore.loadAutoReconnect(ctx)
        if (btReceiverRegistered.compareAndSet(false, true)) {
            val registered = runCatching {
                ContextCompat.registerReceiver(
                    ctx,
                    btStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )
            }.isSuccess
            if (!registered) {
                btReceiverRegistered.set(false)
            }
        }
    }

    fun refreshPairing(context: Context) {
        initialize(context)
    }

    fun onAppForegrounded(context: Context, isFreshLaunch: Boolean) {
        val ctx = context.applicationContext
        initialize(ctx)
        if (isFreshLaunch &&
            _state.value == ConnectionState.DISCONNECTED &&
            _autoReconnectEnabled.value
        ) {
            userRequestedDisconnect = false
        }
        ensureAutoConnectIfEligible(ctx)
    }

    @SuppressLint("MissingPermission")
    fun ensureAutoConnectIfEligible(context: Context) {
        val ctx = context.applicationContext
        initialize(ctx)
        val paired = _pairedDevice.value ?: return
        if (!_autoReconnectEnabled.value || userRequestedDisconnect) return
        if (_state.value != ConnectionState.DISCONNECTED) return

        if (!hasBlePermissions(ctx)) {
            _statusMessage.value = "Bluetooth permission needed for auto-reconnect"
            return
        }

        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled) {
            _statusMessage.value = "Bluetooth is off — will auto-reconnect when enabled"
            return
        }

        startAutoWaitForPairedDevice(ctx, paired, preferScanFallback = false)
    }

    fun setAutoReconnectEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        initialize(ctx)
        PairingStore.saveAutoReconnect(ctx, enabled)
        _autoReconnectEnabled.value = enabled
        if (!enabled) {
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            reconnectRunnable = null
            scanRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
            scanRefreshRunnable = null
            if (_state.value == ConnectionState.WAITING_IN_RANGE) {
                cancelTimers()
                stopActiveScan()
                closeCurrentGatt()
                _state.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Auto-reconnect disabled"
            }
        } else {
            userRequestedDisconnect = false
            reconnectAttemptCount = 0
            if (_state.value == ConnectionState.DISCONNECTED && _pairedDevice.value != null) {
                ensureAutoConnectIfEligible(ctx)
            }
        }
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
        initialize(ctx)
        PairingStore.save(ctx, parsed)
        _pairedDevice.value = parsed
        lastDevice = null
        userRequestedDisconnect = false
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
        lastDevice = null
        disconnect()
        _statusMessage.value = "Device unpaired — scan QR on espMoto to pair"
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        val ctx = context.applicationContext
        initialize(ctx)
        userRequestedDisconnect = false
        reconnectAttemptCount = 0
        cancelTimers()
        stopActiveScan()

        if (!hasBlePermissions(ctx)) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth permission required"
            return
        }

        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
        val btAdapter = adapter
        if (btAdapter == null) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth not available on this device"
            return
        }
        if (!btAdapter.isEnabled) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = if (_autoReconnectEnabled.value && _pairedDevice.value != null) {
                "Bluetooth is off — will auto-reconnect when enabled"
            } else {
                "Enable Bluetooth and try again"
            }
            return
        }

        val paired = _pairedDevice.value
        if (paired == null) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Scan QR on espMoto to pair first"
            return
        }

        val remote = resolveBluetoothDevice(btAdapter, paired)
        val deviceType = remote?.let {
            runCatching { it.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        } ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN

        if (remote != null && deviceType != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
            startDirectConnect(ctx, remote, "${paired.name} (${paired.mac})")
            return
        }

        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "BLE scanner unavailable"
            return
        }

        closeCurrentGatt()
        _state.value = ConnectionState.SCANNING
        _statusMessage.value = "Scanning for ${paired.name} (${paired.mac})..."
        activeScanner = scanner

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.NAV_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.address.equals(paired.mac, ignoreCase = true)) {
                    return
                }
                stopActiveScan()
                startDirectConnect(ctx, result.device, paired.name)
            }

            override fun onScanFailed(errorCode: Int) {
                stopActiveScan()
                if (_autoReconnectEnabled.value && !userRequestedDisconnect) {
                    startAutoWaitForPairedDevice(ctx, paired, preferScanFallback = true)
                } else {
                    _state.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = "BLE scan failed (code $errorCode)"
                }
            }
        }

        activeScanCallback = callback
        val timeout = Runnable {
            if (_state.value == ConnectionState.SCANNING) {
                stopActiveScan()
                if (_autoReconnectEnabled.value && !userRequestedDisconnect) {
                    startAutoWaitForPairedDevice(ctx, paired, preferScanFallback = true)
                    _statusMessage.value = "${paired.name} out of range — waiting to auto-reconnect..."
                } else {
                    _state.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = "${paired.name} not found (scan timed out)"
                }
            }
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)

        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: SecurityException) {
            stopActiveScan()
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth permission required"
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveBluetoothDevice(
        btAdapter: BluetoothAdapter,
        paired: PairedDevice
    ): BluetoothDevice? {
        val cachedLast = lastDevice?.takeIf { it.address.equals(paired.mac, ignoreCase = true) }
        val lastType = cachedLast?.let {
            runCatching { it.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        } ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN
        if (cachedLast != null && lastType != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
            return cachedLast
        }
        if (BluetoothAdapter.checkBluetoothAddress(paired.mac)) {
            return runCatching { btAdapter.getRemoteDevice(paired.mac) }.getOrNull()
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startDirectConnect(
        ctx: Context,
        device: BluetoothDevice,
        displayName: String
    ) {
        cancelTimers()
        stopActiveScan()
        closeCurrentGatt()

        lastDevice = device
        _state.value = ConnectionState.CONNECTING
        _statusMessage.value = "Connecting to $displayName..."

        var secError = false
        val opened = synchronized(gattLock) {
            val cg = try {
                device.connectGatt(
                    ctx,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } catch (_: SecurityException) {
                secError = true
                null
            }
            gatt = cg
            cg
        }
        if (secError) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth permission required"
            return
        }
        if (opened == null) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Failed to open GATT connection"
            return
        }

        val timeout = Runnable {
            if (_state.value == ConnectionState.CONNECTING) {
                closeCurrentGatt()
                val paired = _pairedDevice.value
                if (_autoReconnectEnabled.value && !userRequestedDisconnect && paired != null) {
                    startAutoWaitForPairedDevice(ctx, paired, preferScanFallback = true)
                } else {
                    _state.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = "$displayName not in range"
                }
            }
        }
        connectTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, DIRECT_CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startAutoWaitForPairedDevice(
        ctx: Context,
        paired: PairedDevice,
        preferScanFallback: Boolean
    ) {
        if (!_autoReconnectEnabled.value || userRequestedDisconnect) return
        if (!hasBlePermissions(ctx)) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth permission needed for auto-reconnect"
            return
        }

        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = manager?.adapter
        adapter = btAdapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth is off — will auto-reconnect when enabled"
            return
        }

        cancelTimers()
        stopActiveScan()
        closeCurrentGatt()

        val remote = resolveBluetoothDevice(btAdapter, paired)
        val deviceType = remote?.let {
            runCatching { it.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        } ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN

        if (!preferScanFallback && remote != null && deviceType != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
            lastDevice = remote
            _state.value = ConnectionState.WAITING_IN_RANGE
            _statusMessage.value = "Waiting for ${paired.name} in range..."

            var secError = false
            val opened = synchronized(gattLock) {
                val cg = try {
                    remote.connectGatt(
                        ctx,
                        true,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } catch (_: SecurityException) {
                    secError = true
                    null
                }
                gatt = cg
                cg
            }
            if (secError) {
                _state.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Bluetooth permission needed for auto-reconnect"
                return
            }
            if (opened != null) {
                return
            }
        }

        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            if (remote != null) {
                lastDevice = remote
                _state.value = ConnectionState.WAITING_IN_RANGE
                _statusMessage.value = "Waiting for ${paired.name} in range..."
                synchronized(gattLock) {
                    gatt = runCatching {
                        remote.connectGatt(
                            ctx,
                            true,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE
                        )
                    }.getOrNull()
                }
            } else {
                _state.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "BLE scanner unavailable"
            }
            return
        }

        _state.value = ConnectionState.WAITING_IN_RANGE
        _statusMessage.value = "Waiting for ${paired.name} in range..."
        activeScanner = scanner

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.NAV_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.address.equals(paired.mac, ignoreCase = true)) {
                    return
                }
                stopActiveScan()
                reconnectAttemptCount = 0
                startDirectConnect(ctx, result.device, paired.name)
            }

            override fun onScanFailed(errorCode: Int) {
                stopActiveScan()
                if (!_autoReconnectEnabled.value || userRequestedDisconnect || _pairedDevice.value == null) {
                    _state.value = ConnectionState.DISCONNECTED
                    return
                }
                _state.value = ConnectionState.WAITING_IN_RANGE
                _statusMessage.value = "Waiting for ${paired.name} in range..."
                val retry = Runnable {
                    if (_autoReconnectEnabled.value &&
                        !userRequestedDisconnect &&
                        _state.value == ConnectionState.WAITING_IN_RANGE
                    ) {
                        val currentPaired = _pairedDevice.value ?: return@Runnable
                        val fallbackRemote = resolveBluetoothDevice(btAdapter, currentPaired)
                        if (fallbackRemote != null) {
                            lastDevice = fallbackRemote
                            synchronized(gattLock) {
                                gatt = runCatching {
                                    fallbackRemote.connectGatt(
                                        ctx,
                                        true,
                                        gattCallback,
                                        BluetoothDevice.TRANSPORT_LE
                                    )
                                }.getOrNull()
                            }
                        } else {
                            startAutoWaitForPairedDevice(ctx, currentPaired, preferScanFallback = true)
                        }
                    }
                }
                reconnectRunnable = retry
                mainHandler.postDelayed(retry, 10_000L)
            }
        }

        activeScanCallback = callback
        val refresh = Runnable {
            if (_state.value == ConnectionState.WAITING_IN_RANGE &&
                _autoReconnectEnabled.value &&
                !userRequestedDisconnect
            ) {
                _pairedDevice.value?.let { currentPaired ->
                    startAutoWaitForPairedDevice(ctx, currentPaired, preferScanFallback = true)
                }
            }
        }
        scanRefreshRunnable = refresh
        mainHandler.postDelayed(refresh, LOW_POWER_SCAN_REFRESH_MS)

        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: SecurityException) {
            stopActiveScan()
            _state.value = ConnectionState.DISCONNECTED
            _statusMessage.value = "Bluetooth permission needed for auto-reconnect"
        }
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

    private fun cancelTimers() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
        scanRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        scanRefreshRunnable = null
    }

    private fun clearCharacteristics() {
        navChar = null
        obdConfigChar = null
        pairChar = null
        simObdChar = null
        negotiatedMtu = 23
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        val oldGatt: BluetoothGatt?
        synchronized(gattLock) {
            oldGatt = gatt
            gatt = null
        }
        clearCharacteristics()
        if (oldGatt != null) {
            runCatching { oldGatt.disconnect() }
            runCatching { oldGatt.close() }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userRequestedDisconnect = true
        reconnectAttemptCount = 0
        cancelTimers()
        stopActiveScan()
        closeCurrentGatt()
        _state.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "Disconnected from ${BleProtocol.DEVICE_NAME}"
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicNoResponse(
        currentGatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        bytes: ByteArray
    ) {
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
    fun sendNavPacket(packet: NavPacket) {
        _lastPacket.value = packet

        val currentGatt = synchronized(gattLock) { gatt } ?: return
        val char = navChar ?: return
        writeCharacteristicNoResponse(currentGatt, char, packet.encode())
    }

    @SuppressLint("MissingPermission")
    fun sendSimObdPacket(packet: SimObdPacket) {
        val currentGatt = synchronized(gattLock) { gatt } ?: return
        val char = simObdChar ?: return
        writeCharacteristicNoResponse(currentGatt, char, packet.encode())
    }

    @SuppressLint("MissingPermission")
    fun sendObdConfig(profile: BikeProfile): Boolean {
        val wireJson = profile.toWireJson()

        val currentGatt = synchronized(gattLock) { gatt } ?: return false
        val char = obdConfigChar ?: return false
        val bytes = (wireJson + "\n").toByteArray(Charsets.UTF_8)
        val chunkSize = (negotiatedMtu - 3).coerceIn(20, 480)

        scope.launch {
            var offset = 0
            while (offset < bytes.size) {
                if (synchronized(gattLock) { gatt !== currentGatt }) break
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
                synchronized(gattLock) {
                    if (gatt !== g) return
                }
                cancelTimers()
                reconnectAttemptCount = 0
                _state.value = ConnectionState.CONNECTING
                _statusMessage.value = "Negotiating MTU & discovering services..."
                if (!g.requestMtu(512)) {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(gattLock) {
                    if (gatt !== g) {
                        runCatching { g.close() }
                        return
                    }
                    gatt = null
                }
                val wasConnected = (_state.value == ConnectionState.CONNECTED)
                cancelTimers()
                clearCharacteristics()
                runCatching { g.close() }

                if (wasConnected && !userRequestedDisconnect) {
                    HelmetAudioAlerts.playBleDisconnectedUnexpectedly()
                }

                val ctx = appContext
                val paired = _pairedDevice.value
                if (!userRequestedDisconnect && _autoReconnectEnabled.value && paired != null && ctx != null) {
                    val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val btAdapter = manager?.adapter
                    adapter = btAdapter
                    if (btAdapter?.isEnabled != true) {
                        _state.value = ConnectionState.DISCONNECTED
                        _statusMessage.value = "Bluetooth is off — will auto-reconnect when enabled"
                        return
                    }

                    val delayMs = if (wasConnected) {
                        reconnectAttemptCount = 0
                        1500L
                    } else {
                        reconnectAttemptCount += 1
                        minOf(30_000L, 2_000L * (1L shl reconnectAttemptCount.coerceAtMost(4)))
                    }
                    val useScanFallback = !wasConnected && reconnectAttemptCount >= 2

                    _state.value = ConnectionState.WAITING_IN_RANGE
                    _statusMessage.value = "Out of range — waiting to auto-reconnect..."
                    val retry = Runnable {
                        if (!userRequestedDisconnect &&
                            _autoReconnectEnabled.value &&
                            _pairedDevice.value != null &&
                            synchronized(gattLock) { gatt == null }
                        ) {
                            startAutoWaitForPairedDevice(ctx, paired, preferScanFallback = useScanFallback)
                        }
                    }
                    reconnectRunnable = retry
                    mainHandler.postDelayed(retry, delayMs)
                } else {
                    _state.value = ConnectionState.DISCONNECTED
                    if (!userRequestedDisconnect) {
                        _statusMessage.value = "Disconnected (status $status)"
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(gattLock) {
                if (gatt !== g) return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                negotiatedMtu = mtu
            }
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            synchronized(gattLock) {
                if (gatt !== g) return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusMessage.value = "Service discovery failed ($status)"
                g.disconnect()
                return
            }
            val service = g.getService(BleProtocol.NAV_SERVICE_UUID)
            val discoveredChar = service?.getCharacteristic(BleProtocol.NAV_CHAR_UUID)
            val discoveredCfgChar = service?.getCharacteristic(BleProtocol.OBD_CONFIG_CHAR_UUID)
            val discoveredPairChar = service?.getCharacteristic(BleProtocol.PAIR_CHAR_UUID)
            val discoveredSimObdChar = service?.getCharacteristic(BleProtocol.SIM_OBD_CHAR_UUID)
            if (discoveredChar != null) {
                navChar = discoveredChar
                obdConfigChar = discoveredCfgChar
                pairChar = discoveredPairChar
                simObdChar = discoveredSimObdChar

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

                val devName = runCatching { g.device.name }.getOrNull() ?: BleProtocol.DEVICE_NAME
                _state.value = ConnectionState.CONNECTED
                _statusMessage.value = "Connected to $devName"
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
            synchronized(gattLock) {
                if (gatt !== g) return
            }
            if (characteristic.uuid == BleProtocol.PAIR_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val devName = runCatching { g.device.name }.getOrNull() ?: BleProtocol.DEVICE_NAME
                    _state.value = ConnectionState.CONNECTED
                    _statusMessage.value = "Paired & connected to $devName"
                    HelmetAudioAlerts.playBleConnected()
                    _lastPacket.value?.let { sendNavPacket(it) }
                    appContext?.let { ctx ->
                        sendObdConfig(ObdConfigStore.load(ctx))
                    }
                } else {
                    _statusMessage.value = "Pairing token rejected — re-scan QR on espMoto"
                    userRequestedDisconnect = true
                    g.disconnect()
                }
            }
        }
    }
}
