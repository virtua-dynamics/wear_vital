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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

@androidx.compose.runtime.Immutable
data class HealthData(
    val heartRate: String = "--",
    val spo2: String = "--",
    val steps: String = "--",
    val calories: String = "--",
    val distance: String = "--",
    val status: String = "Waiting for data..."
)

class VitalMonitoringService : Service() {

    private val tag = "VitalService"
    private val channelId = "VitalMonitoringChannel"
    private val notificationId = 1

    private val healthClient by lazy { HealthServices.getClient(this) }
    private val measureClient by lazy { healthClient.measureClient }

    companion object {
        private val _healthDataFlow = MutableStateFlow(HealthData())
        val healthDataFlow: StateFlow<HealthData> = _healthDataFlow.asStateFlow()
        
        var isServiceRunning = false
            private set
    }

    private val callback = object : MeasureCallback {
        override fun onDataReceived(data: DataPointContainer) {
            var current = _healthDataFlow.value.copy(status = "Live")

            // 1. Heart Rate
            val hrData = data.getData(DataType.HEART_RATE_BPM)
            if (hrData.isNotEmpty()) {
                val lastPoint = hrData.last()
                if (lastPoint is SampleDataPoint<Double>) {
                    current = current.copy(heartRate = String.format(Locale.US, "%.0f", lastPoint.value))
                }
            }

            // 2. SpO2 (VO2_MAX as proxy if OXYGEN_SATURATION is unresolved)
            // Note: VO2_MAX is a SampleDataPoint<Double> in alpha04
            val spo2Data = data.getData(DataType.VO2_MAX)
            if (spo2Data.isNotEmpty()) {
                val lastPoint = spo2Data.last()
                if (lastPoint is SampleDataPoint<Double>) {
                    current = current.copy(spo2 = String.format(Locale.US, "%.1f", lastPoint.value))
                }
            }

            // 3. Daily Steps (IntervalDataPoint<Long>)
            val stepData = data.getData(DataType.STEPS_DAILY)
            if (stepData.isNotEmpty()) {
                val lastPoint = stepData.last()
                if (lastPoint is IntervalDataPoint<Long>) {
                    current = current.copy(steps = lastPoint.value.toString())
                }
            }

            // 4. Calories (IntervalDataPoint<Double>)
            val calorieData = data.getData(DataType.CALORIES_DAILY)
            if (calorieData.isNotEmpty()) {
                val lastPoint = calorieData.last()
                if (lastPoint is IntervalDataPoint<Double>) {
                    current = current.copy(calories = String.format(Locale.US, "%.0f", lastPoint.value))
                }
            }

            // 5. Distance (IntervalDataPoint<Double>)
            val distanceData = data.getData(DataType.DISTANCE_DAILY)
            if (distanceData.isNotEmpty()) {
                val lastPoint = distanceData.last()
                if (lastPoint is IntervalDataPoint<Double>) {
                    current = current.copy(distance = String.format(Locale.US, "%.0f m", lastPoint.value))
                }
            }

            _healthDataFlow.value = current
            Log.d(tag, "Data updated: $current")
        }

        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(tag, "Availability changed for ${dataType.name}: $availability")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(notificationId, notification)
            }
            startMonitoring()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start foreground service", e)
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        val dataTypes = setOf(
            DataType.HEART_RATE_BPM,
            DataType.VO2_MAX,
            DataType.STEPS_DAILY,
            DataType.CALORIES_DAILY,
            DataType.DISTANCE_DAILY
        )

        dataTypes.forEach { dataType ->
            try {
                measureClient.registerMeasureCallback(dataType, callback)
            } catch (e: Exception) {
                Log.e(tag, "Failed to register ${dataType.name}", e)
            }
        }
    }

    private fun stopMonitoring() {
        val dataTypes = setOf(
            DataType.HEART_RATE_BPM,
            DataType.VO2_MAX,
            DataType.STEPS_DAILY,
            DataType.CALORIES_DAILY,
            DataType.DISTANCE_DAILY
        )

        dataTypes.forEach { dataType ->
            try {
                measureClient.unregisterMeasureCallbackAsync(dataType, callback)
            } catch (e: Exception) {
                Log.e(tag, "Failed to unregister ${dataType.name}", e)
            }
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopMonitoring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vital Monitoring")
            .setContentText("Monitoring health sensors in background")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Health Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
