package com.mygymapp.ui.screen.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.polar.UserProfileRepository
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.scale.BleScaleManager
import com.mygymapp.data.scale.BodyCompositionCalculator
import com.mygymapp.data.scale.KnownScaleRepository
import com.mygymapp.data.scale.ScaleConnectionState
import com.mygymapp.data.scale.ScaleDeviceInfo
import com.mygymapp.data.scale.ScaleReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.random.Random

data class ScaleDebugUiState(
    val connectionState: ScaleConnectionState = ScaleConnectionState.DISCONNECTED,
    val discoveredDevices: List<ScaleDeviceInfo> = emptyList(),
    val lastReading: ScaleReading? = null,
    val lastError: String? = null,
)

/**
 * Phase-1 validation screen: scan, connect, show raw parsed readings.
 * Not wired into any workout flow yet — exists to confirm the ESF-551
 * protocol against the real VT701 before building the real feature.
 */
@HiltViewModel
class ScaleDebugViewModel @Inject constructor(
    private val scaleManager: BleScaleManager,
    private val knownScaleRepository: KnownScaleRepository,
    private val scaleHistoryRepository: ScaleHistoryRepository,
    private val userProfileRepository: UserProfileRepository,
) : ViewModel() {

    val uiState: StateFlow<ScaleDebugUiState> = combine(
        scaleManager.connectionState,
        scaleManager.discoveredDevices,
        scaleManager.lastReading,
        scaleManager.lastError,
    ) { connectionState, devices, reading, error ->
        ScaleDebugUiState(
            connectionState = connectionState,
            discoveredDevices = devices,
            lastReading = reading,
            lastError = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScaleDebugUiState())

    fun startScan() = scaleManager.startScan()
    fun stopScan() = scaleManager.stopScan()
    fun connectToDevice(address: String) = scaleManager.connectToDevice(address)
    fun disconnect() = scaleManager.disconnect()
    fun forgetKnownScale() = knownScaleRepository.forget()

    /** Debug-only: fills the last ~65 days with fake daily weigh-ins, each day varying up to ±20% from the previous. */
    fun seedDebugWeighIns() {
        viewModelScope.launch {
            val profile = userProfileRepository.get()
            var weight = 80.0
            val today = LocalDateTime.now()
            for (daysAgo in 65 downTo 0) {
                val deltaPct = Random.nextDouble(-0.20, 0.20)
                weight = (weight * (1.0 + deltaPct)).coerceIn(50.0, 150.0)
                val impedance = Random.nextInt(400, 650)
                val composition = BodyCompositionCalculator.calculate(
                    weightKg = weight,
                    heightCm = profile.heightCm,
                    age = profile.age,
                    isMale = profile.isMale,
                    impedanceOhm = impedance,
                )
                scaleHistoryRepository.saveForDate(
                    recordedAt = today.minusDays(daysAgo.toLong()),
                    weightKg = weight,
                    bmi = composition.bmi,
                    bodyFatPercent = composition.bodyFatPercent,
                    leanMassPercent = composition.leanMassPercent,
                )
            }
        }
    }
}
