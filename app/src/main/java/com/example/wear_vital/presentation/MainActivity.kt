package com.example.wear_vital.presentation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.*
import com.example.wear_vital.presentation.theme.Wear_vitalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        setContent {
            HealthMonitorApp()
        }
    }
}

@Composable
fun HealthMonitorApp() {
    val context = LocalContext.current
    val healthData by VitalMonitoringService.healthDataFlow.collectAsState()
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            val intent = Intent(context, VitalMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    Wear_vitalTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GALAXY FULL VITALS",
                    style = MaterialTheme.typography.caption1,
                    color = Color.LightGray
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                SensorCard("Heart Rate", healthData.heartRate, "BPM", Color(0xFFFF4B4B))
                SensorCard("Oxygen (SpO2)", healthData.spo2, "%", Color(0xFF4B9AFF))
                SensorCard("Daily Steps", healthData.steps, "steps", Color(0xFF4BFF9A))
                SensorCard("Calories", healthData.calories, "kcal", Color(0xFFFFAB40))
                SensorCard("Distance", healthData.distance, "", Color(0xFFE14BFF))

                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = healthData.status,
                    style = MaterialTheme.typography.caption2,
                    color = if (healthData.status == "Live") Color.Green else Color.Yellow
                )

                if (permissionsGranted) {
                    Button(
                        onClick = {
                            context.stopService(Intent(context, VitalMonitoringService::class.java))
                        },
                        modifier = Modifier.padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                    ) {
                        Text("Stop Service")
                    }
                }
            }
        }
    }
}

@Composable
fun SensorCard(title: String, value: String, unit: String, iconColor: Color) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.caption2, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(iconColor, shape = androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.caption2, color = Color.Gray)
            }
        }
    }
}
