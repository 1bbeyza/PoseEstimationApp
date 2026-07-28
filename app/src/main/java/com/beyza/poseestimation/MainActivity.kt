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

class MainActivity : AppCompatActivity() {

    // MoveNet Helper
    private lateinit var poseEstimator: PoseEstimator

    // Kamera analiz thread'i
    private lateinit var cameraExecutor: ExecutorService

    // Overlay (iskelet çizim katmanı)
    private lateinit var overlayView: OverlayView

    // Pose estimation çalışıyor mu?
    private var isRunning = false

    // Ölçüm için biriktiriciler
    private var totalInferenceTime = 0L      // toplam süre (ms)
    private var totalConfidence = 0f         // toplam güven
    private var frameCount = 0               // işlenen frame sayısı
    private var currentModelName = ""        // o an çalışan model

    // Kamera izni isteme
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Kamera izni verilmedi.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // OverlayView'ı bul
        overlayView = findViewById<OverlayView>(R.id.overlayView)

        // MoveNet modelini yükle
        // Başlangıçta Lightning ile başla
        // moveNetHelper = MoveNetHelper(assets, MoveNetModel.LIGHTNING)

        // Kamera analiz thread'i oluştur
        cameraExecutor = Executors.newSingleThreadExecutor()


        // Spinner
        val spinner = findViewById<Spinner>(R.id.spinnerModel)

        val models = listOf("MoveNet Lightning", "MoveNet Thunder",
            "MediaPipe", "RTMPose")

        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            models
        )

        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        spinner.adapter = adapter

        // START Butonu
        // START butonu
        val startButton = findViewById<TextView>(R.id.btnStart)
        startButton.setOnClickListener {
            val selectedModel = spinner.selectedItem.toString()

            // Önceki modeli kapat (bellek sızıntısını önle)
            if (::poseEstimator.isInitialized) {
                poseEstimator.close()
            }

            // Seçime göre uygun estimator'ı oluştur
            poseEstimator = when (selectedModel) {
                "MoveNet Thunder" -> MoveNetHelper(assets, MoveNetModel.THUNDER)
                "MediaPipe" -> MediaPipeHelper(this)
                "RTMPose" -> RTMPoseHelper(this)
                else -> MoveNetHelper(assets, MoveNetModel.LIGHTNING)
            }


            // Ölçüm biriktiricilerini sıfırla
            totalInferenceTime = 0L
            totalConfidence = 0f
            frameCount = 0
            currentModelName = selectedModel

            // Analizi başlat
            isRunning = true

            Toast.makeText(this, "$selectedModel başlatıldı", Toast.LENGTH_SHORT).show()
        }

        // STOP butonu
        val stopButton = findViewById<TextView>(R.id.btnStop)
        stopButton.setOnClickListener {
            isRunning = false
            overlayView.setKeyPoints(emptyList())

            // Ölçüm varsa kaydet
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

                Toast.makeText(this, "İstatistik kaydedildi", Toast.LENGTH_SHORT).show()

                // Kayıttan sonra sıfırla — tekrar STOP'a basınca mükerrer kayıt olmasın
                totalInferenceTime = 0L
                totalConfidence = 0f
                frameCount = 0

            } else {
                Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
            }
        }

        // İstatistikler butonu (STOP'un DIŞINDA, kendi başına)
        val statsButton = findViewById<TextView>(R.id.btnStats)
        statsButton.setOnClickListener {
            val intent = Intent(this, StatsActivity::class.java)
            startActivity(intent)
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

            // Kare atlamasını önleyen ve bellek/performans dostu ImageAnalysis stratejisi
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->

                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)

                // Sadece isRunning true ise modeli çalıştır
                if (bitmap != null && isRunning) {

                    // Süreyi ölç
                    val startTime = System.currentTimeMillis()
                    val keyPoints = poseEstimator.estimatePose(bitmap)
                    val elapsed = System.currentTimeMillis() - startTime

                    // Ortalama güveni hesapla (bu frame için)
                    val avgConf = if (keyPoints.isNotEmpty()) {
                        keyPoints.map { it.score }.average().toFloat()
                    } else 0f

                    // Biriktir
                    totalInferenceTime += elapsed
                    totalConfidence += avgConf
                    frameCount++

                    runOnUiThread {
                        if (isRunning) {
                            overlayView.setKeyPoints(keyPoints)
                        }
                    }
                }
                imageProxy.close()
            }

            preview.surfaceProvider = previewView.surfaceProvider
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Kamera başlatılamadı.",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::poseEstimator.isInitialized) {
            poseEstimator.close()
        }

        cameraExecutor.shutdown()
    }
}