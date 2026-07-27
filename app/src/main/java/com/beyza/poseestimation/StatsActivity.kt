package com.beyza.poseestimation

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        // Kayıtların ekleneceği container
        val container = findViewById<LinearLayout>(R.id.statsContainer)

        // Depodaki tüm kayıtları al
        val records = StatsRepository.getAllRecords()

        // Kayıt yoksa bilgi mesajı göster
        if (records.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Henüz kayıt yok.\nBir model çalıştırıp STOP'a basın."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 0)
            }
            container.addView(emptyText)
            return
        }

        // Her kayıt için bir kart oluştur
        for ((index, stats) in records.withIndex()) {

            val card = TextView(this).apply {
                text = buildString {
                    append("${index + 1}. ${stats.modelName}\n")
                    append("Süre: %.1f ms  |  FPS: %.1f\n".format(stats.avgInferenceMs, stats.avgFps))
                    append("Ort. Güven: %.3f\n".format(stats.avgConfidence))
                    append("İşlenen Frame: ${stats.frameCount}")
                }
                textSize = 16f
                setPadding(30, 30, 30, 30)
                setBackgroundColor(Color.parseColor("#F0F0F0"))
                setTextColor(Color.BLACK)
            }

            // Kartlar arası boşluk için layout parametresi
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 24)
            card.layoutParams = params

            container.addView(card)
        }
    }
}