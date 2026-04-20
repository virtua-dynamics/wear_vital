package com.example.wear_vital.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.UUID

@androidx.compose.runtime.Immutable
data class HealthData(
    val heartRate: String = "--",
    val spo2: String = "--",
    val steps: String = "--",
    val calories: String = "--",
    val distance: String = "--",
    val status: String = "Initializing..."
)

class VitalMonitoringService : Service() {

    private val tag = "VitalService"
    private val channelId = "VitalMonitoringChannel"
    private val notificationId = 1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val healthClient by lazy { HealthServices.getClient(this) }
    private val measureClient by lazy { healthClient.measureClient }

    private lateinit var vitalsApi: VitalsApi
    
    private var gattServer: BluetoothGattServer? = null
    private val gattClients = mutableSetOf<BluetoothDevice>()
    private lateinit var vitalsCharacteristic: BluetoothGattCharacteristic

    companion object {
        private val _healthDataFlow = MutableStateFlow(HealthData())
        val healthDataFlow: StateFlow<HealthData> = _healthDataFlow.asStateFlow()
        
        var isServiceRunning = false
            private set

        // GATT UUIDs — must match bluetooth.ts on the phone
        val VITALS_SERVICE_UUID: UUID = UUID.fromString("4FAFC201-1FB5-459E-8FCC-C5C9C331914B")
        val VITALS_CHAR_UUID: UUID    = UUID.fromString("BEB5483E-36E1-4688-B7F5-EA07361B26A8")
        val CCCD_UUID: UUID           = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattClients.add(device)
                Log.d(tag, "GATT client connected: ${device.address}")
            } else {
                gattClients.remove(device)
                Log.d(tag, "GATT client disconnected: ${device.address}")
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
            offset: Int, characteristic: BluetoothGattCharacteristic) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }
    }

    private val callback = object : MeasureCallback {
        override fun onDataReceived(data: DataPointContainer) {
            var current = _healthDataFlow.value.copy(status = "Live")
            var shouldSendApi = false

            // 1. Heart Rate
            data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let {
                if (it is SampleDataPoint<Double>) {
                    current = current.copy(heartRate = String.format(Locale.US, "%.0f", it.value))
                    shouldSendApi = true
                }
            }

            // 2. SpO2 (TODO: SpO2 support varies by SDK version and device)
            // Note: OXYGEN_SATURATION / SPO2 is not available in the current DataType constants.
            /* 
            data.getData(DataType.SPO2).lastOrNull()?.let {
                if (it is SampleDataPoint<Double>) {
                    current = current.copy(spo2 = String.format(Locale.US, "%.1f", it.value))
                    shouldSendApi = true
                }
            }
            */

            // 3. Daily Steps
            data.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                if (it is IntervalDataPoint<Long>) {
                    current = current.copy(steps = it.value.toString())
                }
            }

            // 4. Daily Calories
            data.getData(DataType.CALORIES_DAILY).lastOrNull()?.let {
                if (it is IntervalDataPoint<Double>) {
                    current = current.copy(calories = String.format(Locale.US, "%.0f", it.value))
                }
            }

            // 5. Daily Distance
            data.getData(DataType.DISTANCE_DAILY).lastOrNull()?.let {
                if (it is IntervalDataPoint<Double>) {
                    current = current.copy(distance = String.format(Locale.US, "%.0f m", it.value))
                }
            }

            _healthDataFlow.value = current

            if (shouldSendApi) {
                notifyVitalsViaBle(current)
                sendVitalsToServer(current)
            }
        }

        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(tag, "Availability: ${dataType.name} -> $availability")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        setupRetrofit()
        setupGattServer()
        createNotificationChannel()
    }

    private fun setupGattServer() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = bm.openGattServer(this, gattCallback) ?: run {
            Log.e(tag, "Unable to open GATT server")
            return
        }

        vitalsCharacteristic = BluetoothGattCharacteristic(
            VITALS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { char ->
            // CCCD descriptor — required for notifications
            char.addDescriptor(BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }

        val service = BluetoothGattService(VITALS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(vitalsCharacteristic)
        gattServer?.addService(service)
        Log.d(tag, "GATT server started")
    }

    private fun setupRetrofit() {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(Config.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        vitalsApi = retrofit.create(VitalsApi::class.java)
    }

    private fun notifyVitalsViaBle(data: HealthData) {
        if (gattClients.isEmpty()) return
        val json = """{"heartRate":"${data.heartRate}","spo2":"${data.spo2}","steps":"${data.steps}","calories":"${data.calories}","distance":"${data.distance}"}"""
        vitalsCharacteristic.value = json.toByteArray(Charsets.UTF_8)
        for (device in gattClients.toList()) {
            gattServer?.notifyCharacteristicChanged(device, vitalsCharacteristic, false)
        }
        Log.d(tag, "GATT vitals notified to ${gattClients.size} client(s)")
    }

    private fun sendVitalsToServer(data: HealthData) {
        val hr = data.heartRate.toIntOrNull() ?: return
        val spo2Value = data.spo2.toDoubleOrNull() ?: 0.0
        
        serviceScope.launch {
            try {
                val request = VitalsRequest(
                    heartRate = hr,
                    bloodOxygen = spo2Value,
                    trend = "flat"
                )
                val response = vitalsApi.sendVitals(
                    apiKey = Config.WATCH_API_KEY,
                    body = request
                )
                if (response.isSuccessful) {
                    Log.i(tag, "API Success: Vitals Sent")
                } else {
                    Log.e(tag, "API Error: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(tag, "API Exception: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(notificationId, notification)
        }
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        val types = mutableListOf(
            DataType.HEART_RATE_BPM,
            DataType.STEPS_DAILY,
            DataType.CALORIES_DAILY,
            DataType.DISTANCE_DAILY
        )
        // types.add(DataType.SPO2) // Uncomment if SDK version supports it
        types.forEach { measureClient.registerMeasureCallback(it, callback) }
    }

    private fun stopMonitoring() {
        val types = mutableListOf(
            DataType.HEART_RATE_BPM,
            DataType.STEPS_DAILY,
            DataType.CALORIES_DAILY,
            DataType.DISTANCE_DAILY
        )
        // types.add(DataType.SPO2)
        types.forEach { measureClient.unregisterMeasureCallbackAsync(it, callback) }
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopMonitoring()
        gattServer?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vital Monitoring")
            .setContentText("Actively reading sensors & sending to server...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Vitals", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
