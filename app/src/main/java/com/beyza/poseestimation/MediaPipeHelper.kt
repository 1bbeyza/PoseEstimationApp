package com.beyza.poseestimation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class MediaPipeHelper(context: Context) : PoseEstimator {

    private var poseLandmarker: PoseLandmarker? = null

    // BlazePose 33 landmark'tan COCO 17 keypoint'e eşleme
    // Sıra: bizim keypointNames sırasıyla aynı
    private val blazePoseToCoco = listOf(
        0,   // nose
        2,   // left_eye
        5,   // right_eye
        7,   // left_ear
        8,   // right_ear
        11,  // left_shoulder
        12,  // right_shoulder
        13,  // left_elbow
        14,  // right_elbow
        15,  // left_wrist
        16,  // right_wrist
        23,  // left_hip
        24,  // right_hip
        25,  // left_knee
        26,  // right_knee
        27,  // left_ankle
        28   // right_ankle
    )

    private val keypointNames = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle"
    )

    init {
        Log.d("MediaPipe", ">>> INIT BAŞLADI")

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)

        Log.d("MediaPipe", ">>> POSE LANDMARKER OLUŞTU")
    }

    override fun estimatePose(bitmap: Bitmap): List<KeyPoint> {

        val landmarker = poseLandmarker ?: return emptyList()

        // Bitmap'i MediaPipe formatına çevir
        val mpImage = BitmapImageBuilder(bitmap).build()

        // Modeli çalıştır
        val result: PoseLandmarkerResult = landmarker.detect(mpImage)

        // Kişi bulunamadıysa boş liste döndür
        if (result.landmarks().isEmpty()) {
            return emptyList()
        }

        // İlk kişinin 33 landmark'ını al
        val landmarks = result.landmarks()[0]

        // 33'ten 17 COCO keypoint'i seç
        val keyPoints = mutableListOf<KeyPoint>()

        for (i in 0 until 17) {
            val blazeIndex = blazePoseToCoco[i]
            val landmark = landmarks[blazeIndex]

            keyPoints.add(
                KeyPoint(
                    name = keypointNames[i],
                    x = landmark.x(),
                    y = landmark.y(),
                    score = landmark.visibility().orElse(0f)
                )
            )
        }

        return keyPoints
    }

    override fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}