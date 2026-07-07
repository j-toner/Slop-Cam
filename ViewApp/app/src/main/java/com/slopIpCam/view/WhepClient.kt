package com.slopIpCam.view

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class WhepClient(
    private val whepUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    fun postOffer(offerSdp: String): String? {
        // bytes, not String: String.toRequestBody appends "; charset=utf-8"
        // to Content-Type, WHEP expects exactly "application/sdp"
        val body = offerSdp.toByteArray(Charsets.UTF_8)
            .toRequestBody("application/sdp".toMediaType())
        val request = Request.Builder()
            .url(whepUrl)
            .post(body)
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e("WhepClient", "postOffer failed: ${e.message}")
            null
        }
    }
}
