package com.beyza.poseestimation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Intent
import android.widget.TextView
import android.graphics.Bitmap

class MainActivity : AppCompatActivity() {

    private lateinit var poseEstimator: PoseEstimator
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var overlayView: OverlayView
    private lateinit var livePanel: TextView

    // Kamera yönü (varsayılan: ön)
    private var lensFacing = CameraSelector.DEFAULT_FRONT_CAMERA

    private var isRunning = false

    private var totalInferenceTime = 0L
    private var totalConfidence = 0f
    private var frameCount = 0
    private var currentModelName = ""

    // Frame atlama için sayaç
    private var frameSkipCounter = 0
    private val processEveryNthFrame = 2   // her 2 frame'de 1 işle

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        overlayView = findViewById<OverlayView>(R.id.overlayView)
        livePanel = findViewById(R.id.livePanel)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Spinner
        val spinner = findViewById<Spinner>(R.id.spinnerModel)
        val models = listOf("MoveNet Lightning", "MoveNet Thunder", "MediaPipe", "RTMPose")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, models)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter

        // START butonu
        val startButton = findViewById<TextView>(R.id.btnStart)
        startButton.setOnClickListener {
            val selectedModel = spinner.selectedItem.toString()

            if (::poseEstimator.isInitialized) {
                poseEstimator.close()
            }

            poseEstimator = when (selectedModel) {
                "MoveNet Thunder" -> MoveNetHelper(assets, MoveNetModel.THUNDER)
                "MediaPipe" -> MediaPipeHelper(this)
                "RTMPose" -> RTMPoseHelper(this)
                else -> MoveNetHelper(assets, MoveNetModel.LIGHTNING)
            }

            totalInferenceTime = 0L
            totalConfidence = 0f
            frameCount = 0
            currentModelName = selectedModel

            isRunning = true

            Toast.makeText(this, "$selectedModel started", Toast.LENGTH_SHORT).show()
        }

        // STOP butonu
        val stopButton = findViewById<TextView>(R.id.btnStop)
        stopButton.setOnClickListener {
            isRunning = false
            overlayView.setKeyPoints(emptyList())
            livePanel.text = "Ready"

            if (frameCount > 0) {
                val avgMs = totalInferenceTime.toFloat() / frameCount
                val avgFps = if (avgMs > 0) 1000f / avgMs else 0f
                val avgConf = totalConfidence / frameCount

                val stats = ModelStats(
                    modelName = currentModelName,
                    avgInferenceMs = avgMs,
                    avgFps = avgFps,
                    avgConfidence = avgConf,
                    frameCount = frameCount
                )

                StatsRepository.addRecord(stats)

                Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()

                totalInferenceTime = 0L
                totalConfidence = 0f
                frameCount = 0

            } else {
                Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
            }
        }

        // İstatistikler butonu
        val statsButton = findViewById<TextView>(R.id.btnStats)
        statsButton.setOnClickListener {
            val intent = Intent(this, StatsActivity::class.java)
            startActivity(intent)
        }

        // Kamera değiştir butonu
        val switchCameraButton = findViewById<TextView>(R.id.btnSwitchCamera)
        switchCameraButton.setOnClickListener {
            // Ön ise arkaya, arka ise öne geç
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            // OverlayView'a kamera yönünü bildir
            val isFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
            overlayView.setFrontCamera(isFront)


            // Kamerayı yeni yönle yeniden başlat
            startCamera()
        }

        // Kamera izni kontrolü
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->


                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)

                if (bitmap != null && isRunning) {

                    frameSkipCounter++

                    // Sadece her N. frame'i işle, diğerlerini atla
                    if (frameSkipCounter >= processEveryNthFrame) {
                        frameSkipCounter = 0

                        val squareBitmap = cropCenterSquare(bitmap)

                        val startTime = System.currentTimeMillis()
                        val keyPoints = poseEstimator.estimatePose(squareBitmap)
                        val elapsed = System.currentTimeMillis() - startTime

                        val avgConf = if (keyPoints.isNotEmpty()) {
                            keyPoints.map { it.score }.average().toFloat()
                        } else 0f

                        totalInferenceTime += elapsed
                        totalConfidence += avgConf
                        frameCount++

                        val fps = if (elapsed > 0) 1000f / elapsed else 0f

                        runOnUiThread {
                            if (isRunning) {
                                overlayView.setKeyPoints(keyPoints)
                                livePanel.text = "%s · %d ms · %.1f FPS · conf %.2f".format(
                                    currentModelName, elapsed, fps, avgConf
                                )
                            }
                        }
                    }
                }
                imageProxy.close()
            }

            preview.surfaceProvider = previewView.surfaceProvider
            val cameraSelector = lensFacing

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed to start", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Bitmap'i merkezinden kare olarak kırpar (aspect ratio ezilmesini önler)
    private fun cropCenterSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = minOf(width, height)   // kısa kenar = kare boyutu

        // Kareyi ortalamak için başlangıç noktaları
        val xStart = (width - size) / 2
        val yStart = (height - size) / 2

        return Bitmap.createBitmap(bitmap, xStart, yStart, size, size)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::poseEstimator.isInitialized) {
            poseEstimator.close()
        }

        cameraExecutor.shutdown()
    }
}