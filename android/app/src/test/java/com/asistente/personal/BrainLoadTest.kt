package com.asistente.personal

import com.asistente.personal.ui.BrainLoad
import com.asistente.personal.ui.brainLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainLoadTest {
    @Test fun `la descarga se lee de la pastilla con su porcentaje`() {
        assertEquals(BrainLoad(busy = true, fraction = 0.45f, failed = false), brainLoad("Bajando IA 45 %"))
        assertEquals(BrainLoad(true, 0f, false), brainLoad("Bajando IA 0 %"))
        assertEquals(BrainLoad(true, 1f, false), brainLoad("Bajando IA 100 %"))
    }

    @Test fun `cargar no tiene porcentaje y los fallos se distinguen`() {
        assertEquals(BrainLoad(busy = true, fraction = null, failed = false), brainLoad("Cargando IA\u2026"))
        assertTrue(brainLoad("IA: error").failed)
        assertTrue(brainLoad("IA: sin modelos").failed)
        assertFalse(brainLoad("IA: error").busy)
    }

    @Test fun `con la IA lista o en el PC no hay barra`() {
        assertEquals(BrainLoad.None, brainLoad("IA: tel\u00e9fono"))
        assertEquals(BrainLoad.None, brainLoad("IA: PC"))
        assertEquals(BrainLoad.None, brainLoad(""))
    }
}
