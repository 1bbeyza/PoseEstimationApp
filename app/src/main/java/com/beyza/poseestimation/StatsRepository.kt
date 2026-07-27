package com.beyza.poseestimation

// Oturum boyunca model istatistiklerini bellekte tutar.
// Uygulama kapanınca veriler silinir (kalıcı değil).
object StatsRepository {

    // Tüm kayıtlar burada
    private val records = mutableListOf<ModelStats>()

    // Yeni kayıt ekle
    fun addRecord(stats: ModelStats) {
        records.add(stats)
    }

    // Tüm kayıtları getir
    fun getAllRecords(): List<ModelStats> {
        return records
    }

    // Tüm kayıtları temizle (istenirse)
    fun clear() {
        records.clear()
    }
}