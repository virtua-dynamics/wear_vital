package com.example.wear_vital.presentation

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface VitalsApi {
    @POST("api/vitals")
    suspend fun sendVitals(@Body vitals: VitalsRequest): Response<Unit>
}
