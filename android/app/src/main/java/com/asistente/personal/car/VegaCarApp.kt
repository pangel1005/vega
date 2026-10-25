package com.asistente.personal.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.asistente.personal.AssistantController
import com.asistente.personal.BuildConfig
import com.asistente.personal.VegaRuntime
import com.asistente.personal.net.LinkStatus

/** Une la pantalla del coche con el controlador de Vega (el mismo que usa la pantalla del teléfono). */
class ControllerCarBackend(private val c: AssistantController) : CarBackend {
    override fun snapshot(): CarSnapshot {
        val ui = c.ui
        val t = ui.telemetry
        return CarSnapshot(
            online = ui.link == LinkStatus.OPEN,
            sleeping = c.sleeping,
            brainOnPhone = c.brainOnPhone,
            media = ui.media?.let { m -> listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" · ") },
            playing = ui.media?.playing == true,
            notes = ui.pcNotes.map { it.title to it.body },
            pc = t?.let { CarPc(it.cpu, it.gpuTemp, it.ramUsed, it.ramTotal) },
            vehicle = c.vehicle,
        )
    }

    override fun layout(): Pair<String, List<CarItem>> =
        c.carTitle to c.carItems.ifEmpty { DEFAULT_CAR_ITEMS }

    override fun toggleSleep() = if (c.sleeping) c.wake() else c.sleep()
    override fun toggleBrain() = c.toggleBrain()
    override fun mediaPlayPause() = c.mediaControl("play_pause")
    override fun mediaNext() = c.mediaControl("next")
    override fun needsConfirm(actionId: String) = c.ui.actions.firstOrNull { it.id == actionId }?.confirm == true
    override fun pressAction(actionId: String, confirm: Boolean) =
        if (confirm) c.pressAndConfirm(actionId) else c.press(actionId)
}

/**
 * Vega en Android Auto. Categoría «IoT» (controlar cosas desde el coche): enseña una cuadrícula con
 * las casillas que se configuran en el PC. No dibuja nada propio: la pantalla del coche la pinta
 * Android Auto con sus plantillas, y no escribe nada en el coche.
 */
class VegaCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Solo Android Auto y Android Automotive (la lista de ejemplo de Google).
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = VegaCarSession()
}

class VegaCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val controller = VegaRuntime.controller(carContext)
        // Mientras el coche esté conectado, Vega sigue viva aunque la pantalla del teléfono no se vea.
        controller.start(OWNER)
        controller.startVehicle()
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    controller.stopVehicle()
                    controller.stop(OWNER)
                }
            },
        )
        return VegaCarScreen(carContext, ControllerCarBackend(controller))
    }

    companion object {
        const val OWNER = "coche"
    }
}
