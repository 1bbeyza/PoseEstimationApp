package com.beyza.poseestimation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class RTMPoseHelper(context: Context) : PoseEstimator {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // RTMPose giriş boyutu (256 yükseklik, 192 genişlik)
    private val inputHeight = 256
    private val inputWidth = 192

    // SimCC çıktı boyutları (giriş x 2)
    private val simccX = inputWidth * 2   // 384
    private val simccY = inputHeight * 2  // 512

    // ImageNet normalizasyon değerleri (RTMPose bunları kullanır)
    private val mean = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val std = floatArrayOf(58.395f, 57.12f, 57.375f)

    private val keypointNames = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle"
    )

    init {
        Log.d("RTMPose", ">>> INIT BAŞLADI")

        ortEnv = OrtEnvironment.getEnvironment()

        // Modeli assets'ten byte olarak oku
        val modelBytes = context.assets.open("rtmpose_s.onnx").readBytes()

        // ONNX session oluştur
        ortSession = ortEnv!!.createSession(modelBytes, OrtSession.SessionOptions())

        Log.d("RTMPose", ">>> SESSION OLUŞTU")
        Log.d("RTMPose", "Girdi isimleri: ${ortSession!!.inputNames}")
        Log.d("RTMPose", "Çıktı isimleri: ${ortSession!!.outputNames}")
    }

    override fun estimatePose(bitmap: Bitmap): List<KeyPoint> {
        val session = ortSession ?: return emptyList()
        val env = ortEnv ?: return emptyList()

        // 1. Bitmap'i 256x192'ye resize et
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        // 2. Float tensor'a çevir [1, 3, H, W] - kanal önce, normalize
        val floatBuffer = preprocess(resized)

        // 3. Girdi tensor'u oluştur
        val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, shape)

        // 4. Modeli çalıştır
        val inputName = session.inputNames.iterator().next()
        val results = session.run(mapOf(inputName to inputTensor))

        // 5. İki çıktıyı al: simcc_x ve simcc_y
        @Suppress("UNCHECKED_CAST")
        val simccXData = (results[0].value as Array<Array<FloatArray>>)[0]  // [17][384]
        @Suppress("UNCHECKED_CAST")
        val simccYData = (results[1].value as Array<Array<FloatArray>>)[0]  // [17][512]

        // 6. Her keypoint için argmax ile konum bul
        val keyPoints = mutableListOf<KeyPoint>()

        for (i in 0 until 17) {
            // X ekseni: en yüksek olasılıklı indeksi bul
            val (xIdx, xScore) = argmax(simccXData[i])
            // Y ekseni: en yüksek olasılıklı indeksi bul
            val (yIdx, yScore) = argmax(simccYData[i])

            // SimCC indeksini 0-1 aralığına normalize et
            val x = xIdx.toFloat() / simccX
            val y = yIdx.toFloat() / simccY
            val score = (xScore + yScore) / 2f

            keyPoints.add(
                KeyPoint(
                    name = keypointNames[i],
                    x = x,
                    y = y,
                    score = score
                )
            )
        }

        inputTensor.close()
        results.close()

        return keyPoints
    }

    // Bitmap → normalize edilmiş float buffer [1,3,H,W]
    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val buffer = FloatBuffer.allocate(3 * inputHeight * inputWidth)

        // Kanal önce sırası: önce tüm R, sonra tüm G, sonra tüm B
        // R kanalı
        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF).toFloat()
            buffer.put((r - mean[0]) / std[0])
        }
        // G kanalı
        for (pixel in pixels) {
            val g = (pixel shr 8 and 0xFF).toFloat()
            buffer.put((g - mean[1]) / std[1])
        }
        // B kanalı
        for (pixel in pixels) {
            val b = (pixel and 0xFF).toFloat()
            buffer.put((b - mean[2]) / std[2])
        }

        buffer.rewind()
        return buffer
    }

    // Bir dağılımdaki en yüksek değeri ve indeksini bul
    private fun argmax(array: FloatArray): Pair<Int, Float> {
        var maxIdx = 0
        var maxVal = array[0]
        for (i in array.indices) {
            if (array[i] > maxVal) {
                maxVal = array[i]
                maxIdx = i
            }
        }
        return Pair(maxIdx, maxVal)
    }

    override fun close() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        ortEnv = null
    }
}