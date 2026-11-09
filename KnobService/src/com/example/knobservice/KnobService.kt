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
import android.view.Display
import android.view.KeyCharacterMap
import android.os.UserHandle
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

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
    private var lastFingerCount: Int = 0
    private var lastNavTime = 0L
    private var lastDirection: String = ""
    private val MENU_NAV_DEBOUNCE_MS = 25L
    private val OS_NAV_DEBOUNCE_MS = 15L

    private var lastNudgeTime = 0L
    private val NUDGE_DEBOUNCE_MS = 140L

    private var lastBackTime = 0L
    private val BACK_DEBOUNCE_MS = 300L
    
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
    @Volatile
    private var menuAppLaunchTime = 0L

    @Volatile
    private var isMenuAppVisible: Boolean = false
    private var isTaskStackListenerRegistered = false
    @Volatile
    private var cachedIsMenuAppInForeground = false

    private lateinit var knobThread: HandlerThread
    private lateinit var knobHandler: Handler

    private val knobPacketQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val isProcessingData = java.util.concurrent.atomic.AtomicBoolean(false)

    private var pendingSnapPoint: Int? = null
    private var pendingFingerCount: Int = 0
    private var isFlushScheduled = false

    private val processKnobDataRunnable = Runnable {
        do {
            isProcessingData.set(false)
            while (true) {
                val packet = knobPacketQueue.poll() ?: break
                handleKnobData(packet)
            }
        } while (knobPacketQueue.isNotEmpty() && isProcessingData.compareAndSet(false, true))
    }

    private val flushPendingTurnRunnable = Runnable {
        isFlushScheduled = false
        val targetSnap = pendingSnapPoint ?: return@Runnable
        val startSnap = lastSnapPoint ?: targetSnap
        val delta = targetSnap - startSnap
        if (delta != 0) {
            lastNavTime = SystemClock.uptimeMillis()
            lastSnapPoint = targetSnap
            processKnobTurn(pendingFingerCount, delta)
        }
    }

    private val KEY_CLICK = KeyEvent.KEYCODE_DPAD_CENTER
    private val CHANNEL_ID = "KnobServiceChannel"
    private val NOTIFICATION_ID = 1

    // Modus für die Nutzerstudie:
    // true  -> Standard-Drehregler (ignoriert Fingeranzahl, nur Rotary-Navigation & Klick)
    // false -> MultiKnob (normale Fingererkennung: 0-2 Rotary, 3 Nudge, 4 Menü, 5 Back)
    private var isStandardRotaryMode: Boolean = false

    // Long-Press Erkennung für den Center-Button:
    // Kurzer Klick (< 600ms) = Bestätigung (DPAD_CENTER)
    // Langer Klick (>= 600ms) = Zurück (KEYCODE_BACK)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var buttonPressStartTime = 0L
    private var isLongPressTriggered = false
    private val LONG_PRESS_TIMEOUT_MS = 600L

    private val longPressRunnable = Runnable {
        if (lastButtonState == 1) {
            isLongPressTriggered = true
            Log.i("KnobService", "Long-Press erkannt (> ${LONG_PRESS_TIMEOUT_MS}ms) -> Sende KEYCODE_BACK")
            injectBack()
        }
    }

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
                        try {
                            bluetoothGatt?.close()
                        } catch (e: Exception) {}
                        bluetoothGatt = null
                        isNotificationEnabled = false
                    }
                }
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

                if (device != null && (targetDeviceAddress == null || device.address == targetDeviceAddress || device.name == "MultiKnob")) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        Log.d("KnobService", "Pairing erfolgreich! Jetzt GATT-Verbindung sicherstellen...")
                        targetDeviceAddress = device.address
                        myConnectToDevice(device)
                    }
                }
            }
        }
    }

    private val pairingRequestReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (device != null && (targetDeviceAddress == null || device.address == targetDeviceAddress || device.name == "MultiKnob")) {
                    Log.d("KnobService", "Automatisches Bestätigen des Pairings für ${device.name ?: device.address}")
                    try {
                        device.setPairingConfirmation(true)
                        abortBroadcast()
                    } catch (e: Exception) {
                        Log.e("KnobService", "Fehler beim Auto-Pairing", e)
                    }
                }
            }
        }
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.knobservice.SIMULATE_KNOB") {
                val button = intent.getStringExtra("button")
                if (button != null) {
                    when (button.lowercase()) {
                        "long", "long_press", "back" -> {
                            Log.d("KnobService", "SIMULATE_KNOB: Long-Press (BACK) simuliert")
                            injectBack()
                            return
                        }
                        "click", "short", "center" -> {
                            Log.d("KnobService", "SIMULATE_KNOB: Short-Click simuliert")
                            injectCenterClick()
                            return
                        }
                        "close" -> {
                            Log.d("KnobService", "SIMULATE_KNOB: Close simuliert")
                            sendMenuCloseIntent()
                            return
                        }
                        "touch_down", "down" -> {
                            Log.d("KnobService", "SIMULATE_KNOB: Touch-Down simuliert")
                            if (!isMenuAppInForeground()) {
                                openMenuApp()
                            } else {
                                sendMenuTouchDownIntent()
                            }
                            return
                        }
                        "touch_up", "up", "release" -> {
                            Log.d("KnobService", "SIMULATE_KNOB: Touch-Up / Release simuliert")
                            sendMenuTouchUpIntent()
                            return
                        }
                    }
                }
                val fingers = intent.getIntExtra("fingers", 2)
                val delta = intent.getIntExtra("delta", 1)
                Log.d("KnobService", "SIMULATE_KNOB empfangen: fingers=$fingers, delta=$delta")
                processKnobTurn(fingers, delta)
            }
        }
    }

    private val modeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.example.knobservice.SET_MODE",
                "com.example.knobservice.SET_ROTARY_MODE",
                "com.example.knobservice.SET_STANDARD_ROTARY" -> {
                    val modeStr = intent.getStringExtra("mode")
                    val standardRotaryExtra = if (intent.hasExtra("standard_rotary")) {
                        intent.getBooleanExtra("standard_rotary", false)
                    } else if (intent.hasExtra("is_standard_rotary")) {
                        intent.getBooleanExtra("is_standard_rotary", false)
                    } else if (intent.hasExtra("enabled")) {
                        intent.getBooleanExtra("enabled", false)
                    } else if (modeStr != null) {
                        modeStr.equals("STANDARD_ROTARY", ignoreCase = true) ||
                        modeStr.equals("ROTARY", ignoreCase = true) ||
                        modeStr.equals("DREHKNOPF", ignoreCase = true) ||
                        modeStr.equals("DREHREGLER", ignoreCase = true)
                    } else {
                        false
                    }

                    isStandardRotaryMode = standardRotaryExtra
                    Log.i(
                        "KnobService",
                        "KNOB MODUS GEWECHSELT: isStandardRotaryMode=$isStandardRotaryMode " +
                        "(Standard-Drehregler: ${if (isStandardRotaryMode) "AKTIV (Fingererkennung AUS)" else "INAKTIV (MultiKnob mit Fingererkennung AN)"})"
                    )

                    // Automatisch die MenuApp-Reihenfolge auf Alphabetisch zurücksetzen bei Methodenwechsel
                    try {
                        sendBroadcast(Intent("com.example.menuapp.RESET_APP_ORDER").apply {
                            setPackage("com.example.menuapp")
                        })
                        Log.d("KnobService", "RESET_APP_ORDER an MenuApp gesendet")
                    } catch (e: Exception) {
                        Log.w("KnobService", "Konnte RESET_APP_ORDER nicht an MenuApp senden", e)
                    }

                    if (isStandardRotaryMode) {
                        checkAndFocusAppGridIfAppInForeground()
                    }
                }
            }
        }
    }

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo?) {
            val topPkg = taskInfo?.topActivity?.packageName
            val topCls = taskInfo?.topActivity?.className
            Log.d("KnobService", "onTaskMovedToFront: topPkg=$topPkg, topCls=$topCls")
            val isMenu = (topPkg == "com.example.menuapp")
            isMenuAppVisible = isMenu
            cachedIsMenuAppInForeground = isMenu

            if (isStandardRotaryMode) {
                if (topPkg != null &&
                    topPkg != "com.example.menuapp" &&
                    topPkg != "com.android.car.carlauncher" &&
                    topPkg != "com.android.systemui"
                ) {
                    Log.i("KnobService", "[STANDARD ROTARY] App geöffnet ($topPkg) -> Fokus auf App-Grid-Symbol")
                    focusAppGridInBottomBar()
                } else if (topPkg == "com.android.car.carlauncher") {
                    Log.i("KnobService", "[STANDARD ROTARY] AppGrid geöffnet -> Fokus zurück in App-Grid")
                    focusInAppGrid()
                }
            }
        }
    }

    private fun focusAppGridInBottomBar() {
        val intent = Intent("com.android.car.rotary.ACTION_FOCUS_APP_GRID").apply {
            setPackage("com.android.car.rotary")
        }
        sendBroadcastAsUser(intent, UserHandle.ALL)
        mainHandler.removeCallbacks(focusAppGridRunnable)
        mainHandler.postDelayed(focusAppGridRunnable, 250)
        mainHandler.postDelayed(focusAppGridRunnable, 600)
    }

    private val focusAppGridRunnable = Runnable {
        if (isStandardRotaryMode && !isMenuAppInForeground()) {
            val intent = Intent("com.android.car.rotary.ACTION_FOCUS_APP_GRID").apply {
                setPackage("com.android.car.rotary")
            }
            sendBroadcastAsUser(intent, UserHandle.ALL)
        }
    }

    private fun focusInAppGrid() {
        mainHandler.removeCallbacks(focusAppGridRunnable)
        val intent = Intent("com.android.car.rotary.ACTION_FOCUS_IN_APP_GRID").apply {
            setPackage("com.android.car.rotary")
        }
        sendBroadcastAsUser(intent, UserHandle.ALL)
    }

    private fun isAppInForegroundOtherThanLauncher(): Boolean {
        try {
            val tasks = ActivityTaskManager.getService()?.getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)
            val topPkg = tasks?.firstOrNull()?.topActivity?.packageName
            return topPkg != null &&
                    topPkg != "com.android.car.carlauncher" &&
                    topPkg != "com.example.menuapp" &&
                    topPkg != "com.android.systemui"
        } catch (e: Exception) {
            return false
        }
    }

    private fun openAppGrid() {
        try {
            val currentUserId = ActivityManager.getCurrentUser()
            val intent = Intent("com.android.car.carlauncher.ACTION_APP_GRID").apply {
                setPackage("com.android.car.carlauncher")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivityAsUser(intent, UserHandle.of(currentUserId))
            Log.i("KnobService", "[STANDARD ROTARY] openAppGrid: ACTION_APP_GRID gestartet für User $currentUserId")
        } catch (e: Exception) {
            Log.e("KnobService", "Fehler beim Starten von AppGrid", e)
        }
        focusInAppGrid()
    }

    private fun checkAndFocusAppGridIfAppInForeground() {
        try {
            val tasks = ActivityTaskManager.getService()?.getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)
            val topTask = tasks?.firstOrNull()?.topActivity
            val topPkg = topTask?.packageName
            Log.d("KnobService", "checkAndFocusAppGridIfAppInForeground: topPkg=$topPkg")
            if (topPkg != null &&
                topPkg != "com.example.menuapp" &&
                topPkg != "com.android.car.carlauncher" &&
                topPkg != "com.android.systemui"
            ) {
                focusAppGridInBottomBar()
            }
        } catch (e: Exception) {
            Log.w("KnobService", "Fehler bei checkAndFocusAppGridIfAppInForeground: ${e.message}")
        }
    }

    private fun registerTaskStackListenerSafe() {
        if (isTaskStackListenerRegistered) return
        try {
            ActivityTaskManager.getService()?.registerTaskStackListener(taskStackListener)
            isTaskStackListenerRegistered = true
            Log.d("KnobService", "TaskStackListener erfolgreich registriert")
        } catch (e: Exception) {
            Log.w("KnobService", "TaskStackListener konnte nicht registriert werden: ${e.message}")
        }
    }

    private fun unregisterTaskStackListenerSafe() {
        if (!isTaskStackListenerRegistered) return
        try {
            ActivityTaskManager.getService()?.unregisterTaskStackListener(taskStackListener)
            isTaskStackListenerRegistered = false
        } catch (e: Exception) {
            // Ignore
        }
    }

    private val menuAppVisibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.menuapp.ACTION_VISIBILITY") {
                isMenuAppVisible = intent.getBooleanExtra("extra_visible", false)
                cachedIsMenuAppInForeground = isMenuAppVisible
                Log.d("KnobService", "MenuApp Visibility Broadcast empfangen: isMenuAppVisible=$isMenuAppVisible")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("KnobService", "GATT Verbunden! Setze HIGH Priority und fordere MTU an...")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                val mtuRequested = gatt.requestMtu(128)
                if (!mtuRequested) {
                    Log.w("KnobService", "requestMtu fehlgeschlagen, starte direkt discoverServices...")
                    gatt.discoverServices()
                }
                // Watchdog falls MTU-Exchange nicht antwortet
                Handler(Looper.getMainLooper()).postDelayed({
                    if (bluetoothGatt != null && !isNotificationEnabled) {
                        Log.d("KnobService", "Fallback: Starte discoverServices nach MTU-Timeout...")
                        gatt.discoverServices()
                    }
                }, 1000)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w("KnobService", "GATT getrennt. Starte Scan für Reconnect...")

                mainHandler.removeCallbacks(longPressRunnable)
                isLongPressTriggered = false
                isNotificationEnabled = false
                knobPacketQueue.clear()
                isProcessingData.set(false)
                lastSnapPoint = null
                lastButtonState = 0
                lastFingerCount = 0
                lastNavTime = 0L

                try {
                    bluetoothGatt?.close()
                } catch (e: Exception) {}
                bluetoothGatt = null
                Handler(Looper.getMainLooper()).postDelayed({
                    startScan()
                }, 1000)
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
            knobPacketQueue.offer(value.clone())
            if (::knobHandler.isInitialized && isProcessingData.compareAndSet(false, true)) {
                knobHandler.post(processKnobDataRunnable)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (UserHandle.myUserId() != 0) {
            Log.w("KnobService", "KnobService darf nur unter User 0 laufen! Beende Instanz für User ${UserHandle.myUserId()}")
            stopSelf()
            return
        }

        knobThread = HandlerThread("KnobServiceWorker")
        knobThread.start()
        knobHandler = Handler(knobThread.looper)

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

        val pairingFilter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pairingRequestReceiver, pairingFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pairingRequestReceiver, pairingFilter)
        }

        val stateFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothStateReceiver, stateFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, stateFilter)
        }

        val testFilter = IntentFilter("com.example.knobservice.SIMULATE_KNOB")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(testReceiver, testFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(testReceiver, testFilter)
        }

        val modeFilter = IntentFilter().apply {
            addAction("com.example.knobservice.SET_MODE")
            addAction("com.example.knobservice.SET_ROTARY_MODE")
            addAction("com.example.knobservice.SET_STANDARD_ROTARY")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(modeReceiver, modeFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(modeReceiver, modeFilter)
        }

        val visibilityFilter = IntentFilter("com.example.menuapp.ACTION_VISIBILITY")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(menuAppVisibilityReceiver, visibilityFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(menuAppVisibilityReceiver, visibilityFilter)
        }

        registerTaskStackListenerSafe()

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
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(focusAppGridRunnable)
        if (::knobThread.isInitialized) {
            knobThread.quitSafely()
        }
        unregisterTaskStackListenerSafe()
        try {
            unregisterReceiver(bondStateReceiver)
            unregisterReceiver(pairingRequestReceiver)
            unregisterReceiver(bluetoothStateReceiver)
            unregisterReceiver(testReceiver)
            unregisterReceiver(modeReceiver)
            unregisterReceiver(menuAppVisibilityReceiver)
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
            val isTarget = (device.name != null && device.name == "MultiKnob") ||
                    (targetDeviceAddress != null && device.address == targetDeviceAddress) ||
                    (uuids != null && uuids.contains(ParcelUuid(KNOB_SERVICE_UUID)))

            if (isTarget) {
                Log.d("KnobService", "Bereits gepaartes Gerät im OS-Speicher gefunden: ${device.name ?: device.address}")
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

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(KNOB_SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName("MultiKnob").build()
        )
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
            val device = result.device
            val scanRecord = result.scanRecord
            val advertisedServices = scanRecord?.serviceUuids
            val targetParcelUuid = ParcelUuid(KNOB_SERVICE_UUID)
            val isTarget = (advertisedServices != null && advertisedServices.contains(targetParcelUuid)) ||
                    (scanRecord?.deviceName != null && scanRecord.deviceName == "MultiKnob") ||
                    (device.name != null && device.name == "MultiKnob")

            if (isTarget) {
                targetDeviceAddress = device.address
                myStopScan()
                Log.d("KnobService", "Gerät gefunden! Name: ${device.name}, Adresse: $targetDeviceAddress")

                // Direkt GATT-Verbindung aufbauen
                myConnectToDevice(device)

                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    Log.d("KnobService", "Starte Bonding im Hintergrund...")
                    device.createBond()
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
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null
        isNotificationEnabled = false
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
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                isNotificationEnabled = true
                lastNavTime = SystemClock.uptimeMillis()
                Log.d("KnobService", "Notifications aktiviert. Initialisiere Zeitstempel.")
            }
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun handleKnobData(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        if (value.size < 6) return

        var parsedSnapPoint: Int? = null
        var parsedButtonState: Int? = null
        var parsedFingerCount: Int? = null

        // Dynamischer TLV-Parser gemäß MultiKnob-Protokoll:
        var offset = 0
        val maxOffset = value.size - 2 // minus 2 für CRC16
        while (offset < maxOffset) {
            when (value[offset].toInt() and 0xFF) {
                0x01 -> offset += 1 + 4 // FORMAT_MOTOR_ANGLE (float: 4 Bytes)
                0x02 -> offset += 1 + 4 // FORMAT_MOTOR_ANGLE_DELTA (float: 4 Bytes)
                0x03 -> {
                    // FORMAT_MOTOR_SNAP_POINT (int32: 4 Bytes)
                    if (offset + 5 <= maxOffset) {
                        parsedSnapPoint = ByteBuffer.wrap(value, offset + 1, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getInt()
                    }
                    offset += 1 + 4
                }
                0x04 -> {
                    // FORMAT_BUTTON_STATE (uint8: 1 Byte)
                    if (offset + 2 <= maxOffset) {
                        parsedButtonState = value[offset + 1].toInt() and 0xFF
                    }
                    offset += 1 + 1
                }
                0x05 -> offset += 1 + 1 // FORMAT_BUTTON_STATE_DELTA (uint8: 1 Byte)
                0x06 -> {
                    // FORMAT_TOUCH_COUNT (uint8: 1 Byte)
                    if (offset + 2 <= maxOffset) {
                        parsedFingerCount = value[offset + 1].toInt() and 0xFF
                    }
                    offset += 1 + 1
                }
                0x07 -> offset += 1 + 1 // FORMAT_TOUCH_COUNT_DELTA (uint8: 1 Byte)
                0x08 -> offset += 1 + 4 // FORMAT_TOUCH_PRESSURE (int32: 4 Bytes)
                0x09 -> {
                    // FORMAT_TOUCH_COUNT_AND_TOUCHES (uint8 touch_count, uint8 format, touches...)
                    if (offset + 2 <= maxOffset) {
                        parsedFingerCount = value[offset + 1].toInt() and 0xFF
                    }
                    break // Touches-Payload folgt
                }
                else -> {
                    offset++
                }
            }
        }

        // Fallback für statisches Format (z.B. Test-Frames)
        if (parsedSnapPoint == null && value.size >= 10 && value[5].toInt() == 0x03) {
            parsedSnapPoint = ByteBuffer.wrap(value, 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (value.size >= 12 && value[10].toInt() == 0x04) {
                parsedButtonState = value[11].toInt() and 0xFF
            }
            if (value.size >= 14 && value[12].toInt() == 0x09) {
                parsedFingerCount = value[13].toInt() and 0xFF
            }
        }

        val currentSnapPoint = parsedSnapPoint ?: return
        val buttonState = parsedButtonState ?: lastButtonState
        val fingerCount = parsedFingerCount ?: lastFingerCount

            // Fingererkennung für MenuApp:
            // 4 Finger berühren -> MenuApp sofort öffnen (Touch-Down)
            // Finger loslassen (0 Finger) -> Touch-Up an MenuApp (500ms Loslass-Timer)
            // Finger wieder anlegen (>0 Finger) -> Touch-Down an MenuApp (Aushol-Lock)
            if (!isStandardRotaryMode) {
                if (fingerCount == 4 && lastFingerCount != 4) {
                    if (!isMenuAppInForeground()) {
                        Log.i("KnobService", "4-Finger Touch-Down erkannt -> Öffne MenuApp direkt per Berührung")
                        openMenuApp()
                    } else {
                        Log.d("KnobService", "4-Finger Touch-Down bei geöffneter MenuApp -> Sende TOUCH_DOWN")
                        sendMenuTouchDownIntent()
                    }
                } else if (lastFingerCount == 0 && fingerCount > 0 && isMenuAppInForeground()) {
                    Log.d("KnobService", "Wiederanfassen (Touch-Down: $fingerCount Finger) bei geöffneter MenuApp -> Sende TOUCH_DOWN (Aushol-Lock)")
                    sendMenuTouchDownIntent()
                } else if (lastFingerCount > 0 && fingerCount == 0 && isMenuAppInForeground()) {
                    Log.d("KnobService", "Knob losgelassen (Touch-Up: 0 Finger) bei geöffneter MenuApp -> Sende TOUCH_UP (Starte 500ms Timer)")
                    sendMenuTouchUpIntent()
                }
                lastFingerCount = fingerCount
            }

            // Erkennung von Short-Click vs. Long-Press
            if (buttonState == 1 && lastButtonState == 0) {
                // Taste wurde gedrückt (Button DOWN)
                buttonPressStartTime = SystemClock.uptimeMillis()
                isLongPressTriggered = false
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                Log.d("KnobService", "Taste gedrückt -> Long-Press-Timer (${LONG_PRESS_TIMEOUT_MS}ms) gestartet")
            } else if (buttonState == 0 && lastButtonState == 1) {
                // Taste wurde losgelassen (Button UP)
                mainHandler.removeCallbacks(longPressRunnable)
                val pressDuration = SystemClock.uptimeMillis() - buttonPressStartTime
                if (!isLongPressTriggered) {
                    if (isStandardRotaryMode && isAppInForegroundOtherThanLauncher()) {
                        Log.i("KnobService", "[STANDARD ROTARY] Klick bei geöffneter App (Dauer: ${pressDuration}ms) -> Kehre zu AppGrid zurück")
                        openAppGrid()
                    } else {
                        Log.i("KnobService", "Short-Click ausgeführt (Dauer: ${pressDuration}ms) -> injectCenterClick()")
                        injectCenterClick()
                    }
                } else {
                    Log.d("KnobService", "Taste nach Long-Press losgelassen (Dauer: ${pressDuration}ms)")
                }
            }
            lastButtonState = buttonState

            if (lastSnapPoint != null) {
                val delta = currentSnapPoint - lastSnapPoint!!
                if (delta != 0) {
                    val currentDirection = if (delta > 0) "NEXT" else "PREV"

                    // Bei Richtungswechsel (Hin- und Her-Drehen):
                    // Bisherigen Flush in der alten Richtung sofort abbrechen und ohne Latenz in die neue Richtung starten!
                    if (lastDirection.isNotEmpty() && currentDirection != lastDirection) {
                        knobHandler.removeCallbacks(flushPendingTurnRunnable)
                        isFlushScheduled = false
                        lastNavTime = 0L
                    }
                    lastDirection = currentDirection

                    // Falls während des Tastendrucks gedreht wird, brechen wir den Long-Press-Timer ab
                    if (buttonState == 1 && !isLongPressTriggered) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    val debounceMs = if (isMenuAppInForeground()) MENU_NAV_DEBOUNCE_MS else OS_NAV_DEBOUNCE_MS
                    val currentTime = SystemClock.uptimeMillis()
                    val timeSinceLast = currentTime - lastNavTime

                    pendingSnapPoint = currentSnapPoint
                    pendingFingerCount = fingerCount

                    if (timeSinceLast >= debounceMs) {
                        // Leading-Edge: Sofort ausführen
                        knobHandler.removeCallbacks(flushPendingTurnRunnable)
                        isFlushScheduled = false
                        lastNavTime = currentTime
                        lastSnapPoint = currentSnapPoint
                        processKnobTurn(fingerCount, delta)
                    } else {
                        // Trailing-Edge: Wenn innerhalb des Debounce-Fensters, für Restzeit einplanen!
                        if (!isFlushScheduled) {
                            val delay = (debounceMs - timeSinceLast).coerceAtLeast(1L)
                            knobHandler.postDelayed(flushPendingTurnRunnable, delay)
                            isFlushScheduled = true
                        }
                    }
                }
            } else {
                lastSnapPoint = currentSnapPoint
            }
    }

    private fun processKnobTurn(fingerCount: Int, delta: Int) {
        val direction = if (delta > 0) "NEXT" else "PREV"
        val isForward = delta > 0
        val count = Math.abs(delta).coerceIn(1, 20)
        Log.d("KnobService", "Drehung ${if (isForward) "RECHTS" else "LINKS"} (Delta: $delta, Count: $count, Finger: $fingerCount, StandardModus: $isStandardRotaryMode)")

        // Im Standard-Drehregler-Modus (Nutzerstudie Bedingung "Drehknopf" / "Drehregler"):
        // Die Fingererkennung wird vollständig ignoriert.
        if (isStandardRotaryMode) {
            if (isMenuAppInForeground()) {
                Log.d("KnobService", "[STANDARD ROTARY] MenuApp aktiv -> sendMenuIntent($direction, $count)")
                sendMenuIntent(direction, count)
            } else if (isAppInForegroundOtherThanLauncher()) {
                Log.d("KnobService", "[STANDARD ROTARY] App geöffnet -> Drehung ignoriert, Fokus bleibt auf App-Grid-Symbol")
                focusAppGridInBottomBar()
            } else {
                Log.d("KnobService", "[STANDARD ROTARY] Native Rotary Navigation injiziert (isForward=$isForward, count=$count)")
                injectRotaryNavigation(isForward, count)
            }
            return
        }

        when (fingerCount) {
            // 0, 1, 2 Finger -> Native AAOS Rotary Navigation (oder MenuApp iterieren falls aktiv)
            0, 1, 2 -> {
                if (isMenuAppInForeground()) {
                    Log.d("KnobService", "MenuApp aktiv: Rotary/2-Finger iteriert Menü ($direction, count=$count)")
                    sendMenuIntent(direction, count)
                } else {
                    injectRotaryNavigation(isForward, count)
                }
            }
            3 -> {
                if (isMenuAppInForeground()) {
                    Log.d("KnobService", "MenuApp aktiv: 3-Finger iteriert Menü ($direction, count=$count)")
                    sendMenuIntent(direction, count)
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNudgeTime >= NUDGE_DEBOUNCE_MS) {
                        lastNudgeTime = now
                        val keyCode = if (isForward) KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN else KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP
                        Log.d("KnobService", "MenuApp nicht aktiv: 3-Finger führt Nudge aus (keyCode=$keyCode, Debounce: ${NUDGE_DEBOUNCE_MS}ms)")
                        injectNudge(keyCode)
                    } else {
                        Log.v("KnobService", "3-Finger Nudge ignoriert (Debounce aktiv)")
                    }
                }
            }
            4 -> {
                // 4-Finger-Geste: Öffnet MenuApp und/oder iteriert direkt durch das Menü
                Log.d("KnobService", "4-Finger: Sende MenuIntent ($direction, count=$count)")
                sendMenuIntent(direction, count)
            }
            5, 6 -> {
                Log.d("KnobService", "5-Finger: Menü schließen / Zurück")
                val now = SystemClock.uptimeMillis()
                if (now - lastBackTime >= BACK_DEBOUNCE_MS) {
                    lastBackTime = now
                    if (isMenuAppInForeground()) {
                        sendMenuCloseIntent()
                    } else {
                        injectNudge(KeyEvent.KEYCODE_BACK)
                    }
                }
            }
        }
    }

    private fun isMenuAppInForeground(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - menuAppLaunchTime < 1000L) {
            return true
        }
        return cachedIsMenuAppInForeground
    }

    private fun injectRotaryNavigation(isForward: Boolean, count: Int = 1) {
        val manager = carInputManager ?: return
    
        val uptime = SystemClock.uptimeMillis()
        val inputType = CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION 
        
        val actualCount = count.coerceIn(1, 10)
        val uptimesForClicks = LongArray(actualCount) { uptime + it }
        val rotaryEvent = RotaryEvent(inputType, isForward, uptimesForClicks)
    
        manager.injectRotaryEvent(rotaryEvent, CarOccupantZoneManager.DISPLAY_TYPE_MAIN)
    }

    private fun sendMenuIntent(direction: String, count: Int = 1) {
        if (isMenuAppInForeground()) {
            val broadcastIntent = Intent(ACTION_KNOB_FOUR_FINGERS).apply {
                setPackage("com.example.menuapp")
                putExtra(EXTRA_KNOB_DIRECTION, direction)
                putExtra(EXTRA_KNOB_COUNT, count)
            }
            sendBroadcastAsUser(broadcastIntent, UserHandle.ALL)
            Log.d("KnobService", "Menu-Geste per Broadcast gesendet: direction=$direction, count=$count")
        } else {
            menuAppLaunchTime = SystemClock.uptimeMillis()
            cachedIsMenuAppInForeground = true
            isMenuAppVisible = true

            val activityIntent = Intent().apply {
                setClassName(
                    "com.example.menuapp",
                    "com.example.menuapp.MainActivity"
                )
                action = ACTION_KNOB_FOUR_FINGERS
                putExtra(EXTRA_KNOB_DIRECTION, direction)
                putExtra(EXTRA_KNOB_COUNT, count)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

            try {
                val currentUserId = ActivityManager.getCurrentUser()
                startActivityAsUser(
                    activityIntent,
                    UserHandle.of(currentUserId)
                )
                Log.d("KnobService", "MenuApp Activity gestartet: direction=$direction, count=$count für User $currentUserId")
            } catch (e: Exception) {
                Log.e("KnobService", "MenuApp konnte nicht gestartet werden", e)
            }
        }
    }

    private fun sendMenuCloseIntent() {
        cachedIsMenuAppInForeground = false
        isMenuAppVisible = false
        menuAppLaunchTime = 0L

        val broadcastIntent = Intent(ACTION_KNOB_CLOSE).apply {
            setPackage("com.example.menuapp")
        }
        sendBroadcastAsUser(broadcastIntent, UserHandle.ALL)
        Log.d("KnobService", "ACTION_KNOB_CLOSE an MenuApp per Broadcast gesendet")
    }

    private fun openMenuApp() {
        menuAppLaunchTime = SystemClock.uptimeMillis()
        cachedIsMenuAppInForeground = true
        isMenuAppVisible = true

        val activityIntent = Intent().apply {
            setClassName(
                "com.example.menuapp",
                "com.example.menuapp.MainActivity"
            )
            action = ACTION_KNOB_TOUCH_DOWN
            putExtra("ROTARY_MODE", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        try {
            val currentUserId = ActivityManager.getCurrentUser()
            startActivityAsUser(
                activityIntent,
                UserHandle.of(currentUserId)
            )
            Log.d("KnobService", "MenuApp Activity gestartet (4-Finger Touch-Down) für User $currentUserId")
        } catch (e: Exception) {
            Log.e("KnobService", "MenuApp konnte nicht gestartet werden", e)
        }
    }

    private fun sendMenuTouchDownIntent() {
        val broadcastIntent = Intent(ACTION_KNOB_TOUCH_DOWN).apply {
            setPackage("com.example.menuapp")
        }
        sendBroadcastAsUser(broadcastIntent, UserHandle.ALL)
        Log.d("KnobService", "ACTION_KNOB_TOUCH_DOWN an MenuApp per Broadcast gesendet")
    }

    private fun sendMenuTouchUpIntent() {
        val broadcastIntent = Intent(ACTION_KNOB_TOUCH_UP).apply {
            setPackage("com.example.menuapp")
        }
        sendBroadcastAsUser(broadcastIntent, UserHandle.ALL)
        Log.d("KnobService", "ACTION_KNOB_TOUCH_UP an MenuApp per Broadcast gesendet")
    }

    private fun injectNudge(keyCode: Int) {
        val manager = carInputManager

        if (manager == null) {
            Log.e("KnobService", "Nudge verworfen: CarInputManager ist nicht verfügbar")
            return
        }

        try {
            val downTime = SystemClock.uptimeMillis()

            val downEvent = KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
            )

            val upEvent = KeyEvent(
                downTime,
                downTime + 10L,
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

            Log.d("KnobService", "Nudge injiziert: keyCode=$keyCode")
        } catch (e: SecurityException) {
            Log.e("KnobService", "Nudge-Injection verweigert", e)
        } catch (e: Exception) {
            Log.e("KnobService", "Nudge konnte nicht injiziert werden", e)
        }
    }

    private fun sendMenuSelectIntent() {
        val broadcastIntent = Intent(ACTION_KNOB_SELECT).apply {
            setPackage("com.example.menuapp")
        }
        sendBroadcastAsUser(broadcastIntent, UserHandle.ALL)
        Log.d("KnobService", "ACTION_KNOB_SELECT an MenuApp per Broadcast gesendet")
    }

    private fun injectCenterClick() {
        if (isMenuAppInForeground()) {
            Log.i("KnobService", "MenuApp aktiv: Sende Direkt-Auswahl (ACTION_KNOB_SELECT)")
            sendMenuSelectIntent()
            return
        }

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
                downTime + 50L,
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

   private fun injectBack() {
       Log.i("KnobService", ">>> INJIZIERE BACK (Langer Druck am Drehregler) <<<")
       if (isMenuAppInForeground()) {
           sendMenuCloseIntent()
       } else {
           injectNudge(KeyEvent.KEYCODE_BACK)
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

    companion object {
        private const val ACTION_KNOB_FOUR_FINGERS =
            "com.example.carappdrawer.ACTION_KNOB_FOUR_FINGERS"
        private const val ACTION_KNOB_TOUCH_DOWN =
            "com.example.carappdrawer.ACTION_KNOB_TOUCH_DOWN"
        private const val ACTION_KNOB_TOUCH_UP =
            "com.example.carappdrawer.ACTION_KNOB_TOUCH_UP"
        private const val ACTION_KNOB_SELECT =
            "com.example.carappdrawer.ACTION_KNOB_SELECT"
        private const val ACTION_KNOB_CLOSE =
            "com.example.carappdrawer.ACTION_KNOB_CLOSE"

        private const val EXTRA_KNOB_DIRECTION =
            "com.example.carappdrawer.EXTRA_KNOB_DIRECTION"
        private const val EXTRA_KNOB_COUNT =
            "com.example.carappdrawer.EXTRA_KNOB_COUNT"
    }
}
