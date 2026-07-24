package com.beyza.poseestimation

import android.graphics.Bitmap

interface PoseEstimator {

    fun estimatePose(bitmap: Bitmap): List<KeyPoint>

    fun close()
}