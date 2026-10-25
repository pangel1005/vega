package com.asistente.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Quién necesita a Vega viva (la pantalla, el servicio en primer plano, el coche…).
 * Arranca cuando llega el primero y para cuando se va el último: así la pantalla puede irse a
 * segundo plano sin cortar el micrófono mientras el servicio la mantiene.
 */
class Owners {
    private val set = mutableSetOf<String>()

    /** true si con este pasa de nadie a alguien (hay que arrancar). */
    @Synchronized fun add(owner: String): Boolean = set.add(owner) && set.size == 1

    /** true si con este queda nadie (hay que parar). */
    @Synchronized fun remove(owner: String): Boolean = set.remove(owner) && set.isEmpty()

    @Synchronized fun has(owner: String): Boolean = owner in set
}

/** Un solo controlador para toda la app: la pantalla, el servicio y el coche comparten el mismo. */
object VegaRuntime {
    @Volatile private var instance: AssistantController? = null

    fun controller(context: Context): AssistantController =
        instance ?: synchronized(this) {
            instance ?: AssistantController(context.applicationContext).also { instance = it }
        }
}

private const val CHANNEL = "vega_activa"
private const val NOTIFICATION_ID = 7

/** La notificación fija de «Vega está activa» (Android la exige a los servicios que usan el micrófono). */
fun vegaNotification(context: Context): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Vega activa", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mientras Vega escucha en segundo plano"
            setShowBadge(false)
        },
    )
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Vega")
        .setContentText("Escuchando · toca para abrir")
        .setContentIntent(open)
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()
}

/**
 * Servicio en primer plano: mantiene a Vega escuchando (micrófono y enlace con el PC) aunque la
 * pantalla pase a segundo plano o se apague. También lo necesita el coche, donde la pantalla del
 * teléfono no es la que se ve.
 */
class VegaService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf() // sin permiso de micrófono, Android no deja usar un servicio de micrófono
            return START_NOT_STICKY
        }
        val notification = vegaNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        VegaRuntime.controller(this).apply {
            start(OWNER)
            micPermission(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        VegaRuntime.controller(this).stop(OWNER)
        super.onDestroy()
    }

    companion object {
        const val OWNER = "servicio"

        @Volatile var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VegaService::class.java))
        }
    }
}
