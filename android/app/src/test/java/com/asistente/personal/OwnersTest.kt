package com.asistente.personal

import androidx.test.core.app.ApplicationProvider
import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnersTest {
    @Test fun `arranca con el primero y para con el ultimo`() {
        val o = Owners()
        assertTrue(o.add("pantalla")) // el primero: hay que arrancar
        assertFalse(o.add("servicio")) // ya estaba viva
        assertFalse(o.add("servicio")) // repetir no cuenta
        assertFalse(o.remove("pantalla")) // aún la necesita el servicio: sigue
        assertTrue(o.has("servicio"))
        assertTrue(o.remove("servicio")) // el último: para
        assertFalse(o.remove("servicio")) // ya no estaba
    }

    @Test fun `la notificacion del servicio es fija y de baja prioridad`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val n = vegaNotification(context)
        assertEquals("Vega", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
    }
}
