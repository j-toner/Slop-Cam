package com.slopIpCam.cam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

class FlashlightManager(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String? = manager.cameraIdList.firstOrNull { id ->
        manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    fun setTorch(on: Boolean) {
        val id = cameraId ?: return
        try {
            manager.setTorchMode(id, on)
        } catch (e: Exception) {
            Log.e("Flashlight", "setTorchMode failed: ${e.message}")
        }
    }
}
