package com.beyza.poseestimation

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object ImageUtils {

    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        // CameraX'in yerleşik dönüştürücüsünü kullanıyoruz
        val bitmap = imageProxy.toBitmap() ?: return null

        // Kamera açısından kaynaklanan oryantasyon düzeltmesi
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
}