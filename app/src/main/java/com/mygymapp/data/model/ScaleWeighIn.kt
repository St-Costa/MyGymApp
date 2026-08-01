package com.mygymapp.data.model

data class ScaleWeighIn(
    val id: String,
    val date: String,
    val recordedAt: String,
    val weightKg: Double,
    val bmi: Double,
    val bodyFatPercent: Double,
    val leanMassPercent: Double,
)
