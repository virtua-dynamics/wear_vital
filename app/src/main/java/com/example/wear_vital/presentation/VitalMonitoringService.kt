package com.example.wear_vital.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import retrofit2.converter.gson.GsonGsonConverterFactory
import java.util.Locale

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

    companion object {
        private val _healthDataFlow = MutableStateFlow(HealthData())
        val healthDataFlow: StateFlow<HealthData> = _healthDataFlow.asStateFlow()
        
        var isServiceRunning = false
            private set
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

            // 2. SpO2
            data.getData(DataType.OXYGEN_SATURATION).lastOrNull()?.let {
                if (it is SampleDataPoint<Double>) {
                    current = current.copy(spo2 = String.format(Locale.US, "%.1f", it.value))
                    shouldSendApi = true
                }
            }

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
        createNotificationChannel()
    }

    private fun setupRetrofit() {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://192.168.0.32/") // Your local server IP
            .client(client)
            .addConverterFactory(GsonGsonConverterFactory.create())
            .build()

        vitalsApi = retrofit.create(VitalsApi::class.java)
    }

    private fun sendVitalsToServer(data: HealthData) {
        val hr = data.heartRate.toIntOrNull() ?: return
        val spo2 = data.spo2.toDoubleOrNull() ?: 0.0
        
        serviceScope.launch {
            try {
                val request = VitalsRequest(
                    heartRate = hr,
                    bloodOxygen = spo2,
                    trend = "flat"
                )
                val response = vitalsApi.sendVitals(request)
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
        val types = listOf(
            DataType.HEART_RATE_BPM,
            DataType.OXYGEN_SATURATION,
            DataType.STEPS_DAILY,
            DataType.CALORIES_DAILY,
            DataType.DISTANCE_DAILY
        )
        types.forEach { measureClient.registerMeasureCallback(it, callback) }
    }

    private fun stopMonitoring() {
        val types = listOf(
            DataType.HEART_RATE_BPM,
            DataType.OXYGEN_SATURATION,
            DataType.STEPS_DAILY,
            DataType.CALORIES_DAILY,
            DataType.DISTANCE_DAILY
        )
        types.forEach { measureClient.unregisterMeasureCallbackAsync(it, callback) }
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopMonitoring()
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
