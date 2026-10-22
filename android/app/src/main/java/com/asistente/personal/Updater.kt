package com.asistente.personal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val SERVER_HTTP = "http://127.0.0.1:8000"

/**
 * Actualización con un toque: el PC sirve el APK recién compilado y la app lo instala
 * con el instalador de Android (el usuario solo confirma «Instalar»). Así no hace falta
 * «Instalar vía USB», que en Xiaomi exige una SIM.
 */
class Updater(private val context: Context) {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    /** Devuelve el número de la versión nueva del PC, o null si no hay ninguna más reciente. */
    suspend fun newerVersion(): Int? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("$SERVER_HTTP/app/version").build()).execute().use { resp ->
                val code = JSONObject(resp.body.string()).optInt("version_code", 0)
                code.takeIf { it > BuildConfig.VERSION_CODE }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Icono de una app del panel (PNG que el PC saca de su .exe), o null si no hay. */
    suspend fun appIcon(id: String): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("$SERVER_HTTP/app/icons/$id.png").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body.bytes()
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Descarga e inicia la instalación. Devuelve un mensaje de error para el usuario, o null si fue bien. */
    suspend fun downloadAndInstall(): String? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Primera vez: Android pide permitir que esta app instale actualizaciones.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Permite «Instalar apps desconocidas» para Asistente y vuelve a pulsar ACTUALIZAR."
        }
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "update.apk")
                http.newCall(Request.Builder().url("$SERVER_HTTP/app/latest.apk").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    out.outputStream().use { resp.body.byteStream().copyTo(it) }
                }
                out
            } catch (_: Exception) {
                null
            }
        } ?: return "No pude descargar la actualización del PC."
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }
}
