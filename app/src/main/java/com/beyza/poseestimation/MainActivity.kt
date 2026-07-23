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

class MainActivity : AppCompatActivity() {

    // MoveNet Helper
    private lateinit var moveNetHelper: MoveNetHelper

    // Kamera analiz thread'i
    private lateinit var cameraExecutor: ExecutorService

    // Overlay (iskelet çizim katmanı)
    private lateinit var overlayView: OverlayView

    // Pose estimation çalışıyor mu?
    private var isRunning = false

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
        val models = listOf("MoveNet Lightning", "MoveNet Thunder")
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models
        )
        spinner.adapter = adapter

        // START Butonu
        // START butonu
        val startButton = findViewById<Button>(R.id.btnStart)
        startButton.setOnClickListener {
            val selectedModel = spinner.selectedItem.toString()

            // Seçilen isme göre model tipini belirle
            val modelType = when (selectedModel) {
                "MoveNet Thunder" -> MoveNetModel.THUNDER
                else -> MoveNetModel.LIGHTNING
            }

            // Modeli yükle
            moveNetHelper = MoveNetHelper(assets, modelType)

            // Analizi başlat
            isRunning = true

            Toast.makeText(this, "$selectedModel başlatıldı", Toast.LENGTH_SHORT).show()
        }

        // STOP butonu
        val stopButton = findViewById<Button>(R.id.btnStop)
        stopButton.setOnClickListener {
            // Analizi durdur
            isRunning = false

            // Ekrandaki iskeleti temizle
            overlayView.setKeyPoints(emptyList())

            Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
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
                // Sadece isRunning true ise modeli çalıştır
                if (bitmap != null && isRunning) {

                    val keyPoints = moveNetHelper.estimatePose(bitmap)

                    runOnUiThread {
                        // Çizmeden önce tekrar kontrol et
                        // (model çalışırken STOP'a basılmış olabilir)
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
        cameraExecutor.shutdown()
    }
}