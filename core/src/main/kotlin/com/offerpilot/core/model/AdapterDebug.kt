package com.offerpilot.core.model

data class AdapterDebug(
    val note: String,
    val moneyCandidates: List<Double> = emptyList(),
    val distanceCandidates: List<Double> = emptyList(),
    val durationCandidates: List<Double> = emptyList(),
)
