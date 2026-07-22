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
        moveNetHelper = MoveNetHelper(assets)

        // Kamera analiz thread'i oluştur
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Spinner
        val spinner = findViewById<Spinner>(R.id.spinnerModel)
        val models = listOf("MoveNet", "MediaPipe", "RTMPose")
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models
        )
        spinner.adapter = adapter

        // START Butonu
        val startButton = findViewById<Button>(R.id.btnStart)
        startButton.setOnClickListener {
            val selectedModel = spinner.selectedItem.toString()
            Toast.makeText(
                this,
                "$selectedModel başlatılıyor...",
                Toast.LENGTH_SHORT
            ).show()
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

                if (bitmap != null) {

                    Log.d("MoveNet", "Bitmap hazır: ${bitmap.width}x${bitmap.height}")

                    // Modeli çalıştır, 17 keypoint al
                    val keyPoints = moveNetHelper.estimatePose(bitmap)

                    // Çizimi ana thread'de yap (UI güncellemesi ana thread'de olmalı)
                    runOnUiThread {
                        overlayView.setKeyPoints(keyPoints)
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