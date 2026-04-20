package com.example.wear_vital.presentation

data class VitalsRequest(
    val heartRate: Int,
    val bloodOxygen: Double,
    val trend: String = "flat"
)
