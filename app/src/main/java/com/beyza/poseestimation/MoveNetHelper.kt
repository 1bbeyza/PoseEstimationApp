package com.beyza.poseestimation

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import android.os.Build


// Kullanılabilir MoveNet modelleri ve özellikleri
enum class MoveNetModel(val fileName: String, val inputSize: Int) {
    LIGHTNING("movenet_singlepose_lightning_int8.tflite", 192),
    THUNDER("movenet_singlepose_thunder_int8.tflite", 256)
}


class MoveNetHelper(
    assetManager: AssetManager,
    private val model: MoveNetModel = MoveNetModel.LIGHTNING
) : PoseEstimator {

    private var interpreter: Interpreter? = null

    // MoveNet'in 17 keypoint'i - model bunları hep bu sırada döndürür
    private val keypointNames = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle"
    )

    init {

        Log.d("MoveNet", ">>> INIT BAŞLADI")

        // Cihaz mimarisini kontrol et: emülatör (x86) mi, gerçek cihaz (ARM) mı?
        val isEmulator = Build.SUPPORTED_ABIS.any {
            it.contains("x86", ignoreCase = true)
        }

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            // Emülatörde XNNPACK çöküyor → kapat. Gerçek ARM cihazda → aç (hızlı)
            setUseXNNPACK(!isEmulator)
        }

        Log.d("MoveNet", "Emülatör mü: $isEmulator, XNNPACK: ${!isEmulator}")

        interpreter = Interpreter(loadModelFile(assetManager), options)

        Log.d("MoveNet", ">>> INTERPRETER OLUŞTU")

        printModelInfo()

        Log.d("MoveNet", ">>> printModelInfo BİTTİ")
    }

    private fun loadModelFile(assetManager: AssetManager): MappedByteBuffer {

        val fileDescriptor =
            assetManager.openFd(model.fileName)

        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)

        val fileChannel = inputStream.channel

        val startOffset = fileDescriptor.startOffset

        val declaredLength = fileDescriptor.declaredLength

        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            startOffset,
            declaredLength
        )
    }

    /**
     * Modelin giriş ve çıkış tensor bilgilerini Logcat'e yazar.
     */
    private fun printModelInfo() {

        try {

            Log.d("MoveNet", ">>> printModelInfo başladı")

            val inputTensor = interpreter!!.getInputTensor(0)

            Log.d("MoveNet", "Input Shape : ${inputTensor.shape().contentToString()}")
            Log.d("MoveNet", "Input Type  : ${inputTensor.dataType()}")

            val outputTensor = interpreter!!.getOutputTensor(0)

            Log.d("MoveNet", "Output Shape: ${outputTensor.shape().contentToString()}")
            Log.d("MoveNet", "Output Type : ${outputTensor.dataType()}")

            Log.d("MoveNet", ">>> printModelInfo bitti")

        } catch (e: Exception) {

            Log.e("MoveNet", "MODEL BİLGİSİ OKUNAMADI", e)

        }
    }

    /**
     * Kameradan gelen görüntüyü MoveNet'in beklediği
     * 192x192 boyutuna getirir.
     */
    private fun preprocess(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, model.inputSize, model.inputSize, true)
    }

    /**
     * Bitmap piksellerini okuyup INT8/UINT8 formatında ByteBuffer'a dönüştürür.
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val size = model.inputSize
        val inputBuffer = ByteBuffer.allocateDirect(1 * size * size * 3)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in pixels) {
            inputBuffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            inputBuffer.put(((pixel shr 8) and 0xFF).toByte())  // G
            inputBuffer.put((pixel and 0xFF).toByte())          // B
        }

        inputBuffer.rewind()
        return inputBuffer
    }

    override fun estimatePose(bitmap: Bitmap): List<KeyPoint> {

        val inputBitmap = preprocess(bitmap)
        val inputBuffer = bitmapToByteBuffer(inputBitmap)

        // Model çıktısı: [1, 1, 17, 3] → 17 keypoint, her biri (y, x, score)
        val output = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }

        interpreter!!.run(inputBuffer, output)

        // Ham çıktıyı KeyPoint listesine çevir
        val keyPoints = mutableListOf<KeyPoint>()

        for (i in 0 until 17) {
            val y = output[0][0][i][0]      // 0. değer = y
            val x = output[0][0][i][1]      // 1. değer = x
            val score = output[0][0][i][2]  // 2. değer = güven

            keyPoints.add(
                KeyPoint(
                    name = keypointNames[i],
                    x = x,
                    y = y,
                    score = score
                )
            )
        }

        return keyPoints
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}