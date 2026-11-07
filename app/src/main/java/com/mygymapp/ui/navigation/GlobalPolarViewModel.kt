package com.mygymapp.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.PolarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Collegamento globale Polar, indipendente dalla schermata visibile.
 *
 * La connessione del Polar può avvenire da qualunque schermata (o a schermo spento,
 * se una scan è già attiva nel singleton): la misura readiness da 60s parte comunque
 * in [PolarManager] e il beep è già globale (vedi startReadinessMeasurement). Questo
 * ViewModel espone quel segnale alla navigazione così che, quando la misura parte
 * davvero (prima connessione con tutti i requisiti), l'app vada alla pagina Heart.
 */
@HiltViewModel
class GlobalPolarViewModel @Inject constructor(
    private val polarManager: PolarManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val readinessStarted: SharedFlow<Unit> = polarManager.readinessStarted
    val connectionState: StateFlow<ConnectionState> = polarManager.connectionState
    val isScanning: StateFlow<Boolean> = polarManager.isScanning

    fun hasKnownDevice(): Boolean = polarManager.hasKnownDevice()

    fun hasBlePermissions(): Boolean = blePermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Avvia la scan solo se serve: strap noto, link giù, nessuna scan in corso e
     * permessi BLE concessi. Idempotente — chiamabile da ogni ON_RESUME globale.
     */
    fun ensureScanningIfNeeded() {
        if (!hasBlePermissions()) return
        if (!hasKnownDevice()) return
        if (polarManager.connectionState.value != ConnectionState.DISCONNECTED) return
        if (polarManager.isScanning.value) return
        polarManager.startScan()
    }

    private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
