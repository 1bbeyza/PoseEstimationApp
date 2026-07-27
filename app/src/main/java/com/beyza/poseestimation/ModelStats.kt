package com.beyza.poseestimation

// Bir modelin bir oturumdaki performans kaydı
data class ModelStats(
    val modelName: String,      // "MoveNet Lightning" gibi
    val avgInferenceMs: Float,  // ortalama çıkarım süresi (ms)
    val avgFps: Float,          // ortalama FPS
    val avgConfidence: Float,   // ortalama güven skoru
    val frameCount: Int         // kaç frame işlendi
)