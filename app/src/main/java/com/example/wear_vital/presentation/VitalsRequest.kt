package com.example.wear_vital.presentation

data class VitalsRequest(
    val heartRate: Int,
    val bloodOxygen: Double,
    val temperature: Double? = null,
    val bloodPressure: String? = null,
    val trend: String? = null
)
