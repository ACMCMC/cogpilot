package fyi.acmc.cogpilot

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.car.app.model.Template
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.Speed
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * Android Auto Service for CogPilot.
 * This ensures the app is visible in the Android Auto launcher.
 */
class AutomotiveService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return AutomotiveSession()
    }
}

class AutomotiveSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Start monitoring car hardware when session starts
        setupCarHardware()
        return MainCarScreen(carContext)
    }

    private fun setupCarHardware() {
        try {
            val carHardwareManager = carContext.getCarService(CarHardwareManager::class.java)
            val carInfo = carHardwareManager.carInfo
            
            // Subscribe to speed
            val executor = ContextCompat.getMainExecutor(carContext)
            carInfo.addSpeedListener(executor) { speed ->
                handleSpeedUpdate(speed)
            }
            Log.i("AutomotiveService", "✓ Vehicle speed listener registered")
        } catch (e: Exception) {
            Log.e("AutomotiveService", "Error setting up car hardware: ${e.message}")
        }
    }

    private fun handleSpeedUpdate(speed: Speed) {
        val rawSpeedMs = speed.rawSpeedMetersPerSecond
        if (rawSpeedMs.status == CarValue.STATUS_SUCCESS) {
            val speedMs = rawSpeedMs.value
            if (speedMs != null) {
                // Forward to RiskDecisionEngine
                VoiceAgentService.riskEngine?.updateVehicleSpeed(speedMs)
            }
        }
    }
}

class MainCarScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val action = Action.Builder()
            .setTitle("Open on Phone")
            .setOnClickListener {
                // In a real app, this could launch the MainActivity on phone
            }
            .build()

        return MessageTemplate.Builder("CogPilot is monitoring your drive.")
            .setTitle("CogPilot Active")
            .setHeaderAction(Action.APP_ICON)
            .addAction(action)
            .build()
    }
}
