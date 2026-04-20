package com.example.wear_vital.presentation

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface VitalsApi {
    @POST("api/vitals/watch-ingest")
    suspend fun sendVitals(
        @Header("x-watch-api-key") apiKey: String,
        @Body body: VitalsRequest
    ): Response<Unit>
}
