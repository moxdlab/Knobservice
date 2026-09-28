package com.example.knobservice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.input.InputManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice.SOURCE_KEYBOARD
import android.view.KeyEvent
import androidx.annotation.RequiresPermission
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import android.car.Car
import android.car.input.CarInputManager
import android.car.CarOccupantZoneManager
import android.car.input.RotaryEvent
import android.view.InputDevice
import android.view.KeyCharacterMap

class KnobService : Service() {

    private val KNOB_SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val TX_CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-4321-4321-cba987654321")
    private val CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isNotificationEnabled = false

    private var lastSnapPoint: Int? = null
    private var lastButtonState: Int = 0
    private var lastNavTime = 0L
    private val NAV_DEBOUNCE_MS = 180L
    
    private var carInputManager: CarInputManager? = null

    private var targetDeviceAddress: String? = null

    private val carMenus = listOf(
        "com.android.car.carlauncher/.CarLauncher",
        "com.android.car.dialer/com.android.car.dialer.ui.TelecomActivity",
        "com.android.car.carlauncher/.AppGridActivity",
        "com.android.car.settings/com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity",
        "com.android.car.settings/com.android.car.settings.common.CarSettingActivities\$NetworkAndInternetActivity",
        "com.android.car.settings/com.android.car.settings.common.CarSettingActivities\$ProfileDetailsActivity"
    )
    private var currentMenuIndex = 0
    private var lastZapTime = 0L

    private val KEY_CLICK = KeyEvent.KEYCODE_DPAD_CENTER
    private val CHANNEL_ID = "KnobServiceChannel"
    private val NOTIFICATION_ID = 1

    // 1. NEU: Überwacht den Bluetooth-Status des Systems (wichtig für den Initial-Boot)
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d("KnobService", "Bluetooth wurde im System aktiviert (STATE_ON). Starte Bluetooth-Setup...")
                        checkExistingOrScan()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.w("KnobService", "Bluetooth ist ausgeschaltet (STATE_OFF).")
                    }
                }
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

                if (device != null && targetDeviceAddress != null && device.address == targetDeviceAddress) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        Log.d("KnobService", "Pairing erfolgreich! Jetzt GATT-Verbindung aufbauen...")
                        myConnectToDevice(device)
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("KnobService", "GATT Verbunden! Fordere MTU an...")
                gatt.requestMtu(128)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w("KnobService", "GATT getrennt. Starte Scan für Reconnect...")

                isNotificationEnabled = false

                bluetoothGatt?.close()
                bluetoothGatt = null
                Thread.sleep(1000)
                startScan()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("KnobService", "MTU erfolgreich auf $mtu gesetzt. Suche Services...")
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(KNOB_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(TX_CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    Log.d("KnobService", "Service & Charakteristik gefunden. Aktiviere Datenstrom...")
                    enableNotification(gatt, characteristic)
                } else {
                    Log.e("KnobService", "Fehler: Charakteristik nicht gefunden. UUIDs prüfen!")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleKnobData(value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Knob Service läuft")
            .setContentText("Warte auf Bluetooth-Bereitschaft...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val bondFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bondStateReceiver, bondFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bondStateReceiver, bondFilter)
        }

        val stateFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothStateReceiver, stateFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, stateFilter)
        }

        setupBluetooth()
        
        // Car-Verbindung asynchron aufbauen
        Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
            if (ready) {
                carInputManager = car.getCarManager(Car.CAR_INPUT_SERVICE) as? CarInputManager
         }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bondStateReceiver)
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) { e.printStackTrace() }

        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Knob Service Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun setupBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        if (bluetoothAdapter.isEnabled) {
            Log.d("KnobService", "Bluetooth ist bereits aktiv. Starte direkt.")
            checkExistingOrScan()
        } else {
            Log.w("KnobService", "Bluetooth ist noch inaktiv. Warte auf Aktivierung durch das System...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkExistingOrScan() {
        val bondedDevices = bluetoothAdapter.bondedDevices
        var foundExistingDevice = false

        for (device in bondedDevices) {
            val uuids = device.uuids
            if (uuids != null && uuids.contains(ParcelUuid(KNOB_SERVICE_UUID))) {
                Log.d("KnobService", "Bereits gepaartes Gerät im OS-Speicher gefunden: ${device.address}")
                targetDeviceAddress = device.address
                foundExistingDevice = true
                myConnectToDevice(device)
                break
            }
        }

        if (!foundExistingDevice) {
            startScan()
        }
    }

    private fun startScan() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) return

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(KNOB_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d("KnobService", "Scanning gestartet...")
        } catch (e: SecurityException) {
            Log.e("KnobService", "Keine Berechtigung zum Scannen", e)
        } catch (e: Exception) {
            Log.e("KnobService", "Scanner konnte nicht initialisiert werden (evtl. BT-Stack ausgelastet)", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val advertisedServices: List<ParcelUuid>? = scanRecord.serviceUuids
            val targetParcelUuid = ParcelUuid(KNOB_SERVICE_UUID)

            if (advertisedServices != null && advertisedServices.contains(targetParcelUuid)) {
                val device = result.device
                targetDeviceAddress = device.address
                myStopScan()
                Log.d("KnobService", "Gerät per UUID gefunden! Name: ${device.name}, Adresse: $targetDeviceAddress")

                when (device.bondState) {
                    BluetoothDevice.BOND_NONE -> {
                        Log.d("KnobService", "Starte Bonding...")
                        device.createBond()
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d("KnobService", "Bonding läuft...")
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        Log.d("KnobService", "Bereits gebonded. Verbinde...")
                        myConnectToDevice(device)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun myStopScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) { e.printStackTrace() }
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun myConnectToDevice(device: BluetoothDevice) {
        if (bluetoothGatt != null) return
        Log.d("KnobService", "Verbinde mit GATT Server auf ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (isNotificationEnabled) {
            Log.d("KnobService", "Notifications bereits aktiv. Überspringe doppelte Aktivierung.")
            return
        }

        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.d("KnobService", "Notifications aktiviert.")
            }
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun handleKnobData(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        if (value.size < 12) return

        if (value[5].toInt() == 0x03) {
            val currentSnapPoint = ByteBuffer.wrap(value, 6, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt()

            var buttonState = lastButtonState
            if (value[10].toInt() == 0x04) {
                buttonState = value[11].toInt()
            }

            var fingerCount = 0
            if (value.size > 13 && value[12].toInt() == 0x09) {
                fingerCount = value[13].toInt()
            }

            // Klick auf dem Rotary Controller (Center Button)
            if (buttonState == 1 && lastButtonState == 0) {
                injectCenterClick()
            }
            lastButtonState = buttonState

            if (lastSnapPoint != null) {
                val delta = currentSnapPoint - lastSnapPoint!!
                if (delta != 0) {
                    val currentTime = SystemClock.uptimeMillis()

                    if (currentTime - lastNavTime >= NAV_DEBOUNCE_MS) {
                        lastNavTime = currentTime

                        val direction = if (delta > 0) "NEXT" else "PREV"
                        if (delta > 0) {
                            Log.d("KnobService", "Drehung nach RECHTS (Delta: $delta)")
                            when (fingerCount) {
                                // 0, 1, 2 Finger -> Native AAOS Rotary Navigation (Vorwärts)
                                0, 1, 2 -> injectRotaryNavigation(isForward = true)
                                3 -> injectNudge(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN)
                                //4 -> sendMenuBroadcast(direction)
                                5 -> injectNudge(KeyEvent.KEYCODE_BACK)
                            }
                        } else {
                            Log.d("KnobService", "Drehung nach LINKS (Delta: $delta)")
                            when (fingerCount) {
                                // 0, 1, 2 Finger -> Native AAOS Rotary Navigation (Rückwärts)
                                0, 1, 2 -> injectRotaryNavigation(isForward = false)
                                3 -> injectNudge(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP)
                                //4 -> sendMenuBroadcast(direction)
                                5 -> injectNudge(KeyEvent.KEYCODE_BACK)
                            }
                        }
                    }
                }
            }
            lastSnapPoint = currentSnapPoint
        }
    }

    private fun injectRotaryNavigation(isForward: Boolean) {
        if (carInputManager == null) return
    
        val uptime = SystemClock.uptimeMillis()
        // 10 steht für INPUT_TYPE_ROTARY_NAVIGATION
        val inputType = CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION 
        
        // Im AOSP verlangt der RotaryEvent-Konstruktor oft ein Array für die Klick-Zeiten:
        val uptimesForClicks = longArrayOf(uptime)
        val rotaryEvent = RotaryEvent(inputType, isForward, uptimesForClicks)
    
        carInputManager?.injectRotaryEvent(rotaryEvent, CarOccupantZoneManager.DISPLAY_TYPE_MAIN)
    }
    
    private fun injectNudge(keyCode: Int) {
    val manager = carInputManager

    if (manager == null) {
        Log.e("KnobService", "Nudge verworfen: CarInputManager ist nicht verfügbar")
        return
    }

    try {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val downEvent = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0
        )

        val upEvent = KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            keyCode,
            0
        )

        manager.injectKeyEvent(
            downEvent,
            CarOccupantZoneManager.DISPLAY_TYPE_MAIN
        )

        manager.injectKeyEvent(
            upEvent,
            CarOccupantZoneManager.DISPLAY_TYPE_MAIN
        )

        Log.d("KnobService", "Nudge injiziert: keyCode=$keyCode, " +
                "source=${downEvent.source}, deviceId=${downEvent.deviceId}")
    } catch (e: SecurityException) {
        Log.e("KnobService", "Nudge-Injection verweigert", e)
    } catch (e: Exception) {
        Log.e("KnobService", "Nudge konnte nicht injiziert werden", e)
    }
   }
   
   private fun injectCenterClick() {
    val manager = carInputManager

    if (manager == null) {
        Log.e("KnobService", "Center-Klick verworfen: CarInputManager nicht verfügbar")
        return
    }

    try {
        val downTime = SystemClock.uptimeMillis()

        val downEvent = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            0
        )

        val upEvent = KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            0
        )

        manager.injectKeyEvent(
            downEvent,
            CarOccupantZoneManager.DISPLAY_TYPE_MAIN
        )

        manager.injectKeyEvent(
            upEvent,
            CarOccupantZoneManager.DISPLAY_TYPE_MAIN
        )

        Log.i("KnobService", "Center-Klick injiziert")
    } catch (e: Exception) {
        Log.e("KnobService", "Center-Klick fehlgeschlagen", e)
    }
   }

    private fun injectKeyEvent(keyCode: Int, metaState: Int = 0) {
        Log.d("KnobService", ">>> INJIZIERE NATIVEN ROTARY KEY: KeyCode $keyCode <<<")

        // Input Source für Rotary Encoder
        val SOURCE_ROTARY_ENCODER = 0x00400000
        val finalSource = SOURCE_ROTARY_ENCODER or 0x00000001 // SOURCE_CLASS_BUTTON

        val DEVICE_ID = 1

        Thread {
            try {
                val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

                val eventTimeDown = SystemClock.uptimeMillis()
                val eventDown = KeyEvent(
                    eventTimeDown, eventTimeDown, KeyEvent.ACTION_DOWN, keyCode, 0,
                    metaState, DEVICE_ID, 0, KeyEvent.FLAG_FROM_SYSTEM, finalSource
                )
                inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(inputManager, eventDown, 0)

                //Thread.sleep(20)

                val eventTimeUp = SystemClock.uptimeMillis()
                val eventUp = KeyEvent(
                    eventTimeUp, eventTimeUp, KeyEvent.ACTION_UP, keyCode, 0,
                    metaState, DEVICE_ID, 0, KeyEvent.FLAG_FROM_SYSTEM, finalSource
                )
                inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(inputManager, eventUp, 0)

            } catch (e: Exception) {
                Log.e("KnobService", "Fehler beim Injizieren des Rotary Events", e)
            }
        }.start()
    }

    override fun onBind(intent: Intent?) = null
}
