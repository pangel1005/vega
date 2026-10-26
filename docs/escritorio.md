# App de escritorio (Windows)

Una app **nativa** (Qt, con PySide6; sin navegador) que vive en la bandeja del sistema y se
sincroniza en tiempo real con el teléfono.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\escritorio.ps1            # abrir la ventana
powershell -ExecutionPolicy Bypass -File scripts\escritorio.ps1 -Bandeja   # solo el icono
powershell -ExecutionPolicy Bypass -File scripts\escritorio.ps1 -Atajo     # acceso «Vega» en el menú Inicio
```

Arranca sola con Windows (tarea «Vega (escritorio)», creada por `scripts\autostart.ps1`). Las
dependencias (PySide6, unos 300 MB) están en el grupo `desktop` de `pyproject.toml`: el servidor no
las necesita.

## Páginas

| Página | Para qué |
|---|---|
| **Panel** | La cara de Vega en vivo, el estado del teléfono (despierta/dormida, batería, dónde piensa), y controles para dormirlo, cambiar de IA, el micrófono y el estilo. Estado del PC, lo que suena, las notificaciones (con ×) y los botones del panel |
| **Conversación** | Lo que se dice con Vega (también lo que se habla en el teléfono, en espejo) y una caja para escribirle |
| **Botones** | Arriba, **los 6 huecos de la fila principal del teléfono**: pulsa un hueco para elegir su botón, mueve con las flechas o manda a «Más» con la cruz. Debajo, la lista completa para añadir, quitar, ordenar y poner frases de voz y confirmación (`config/actions.toml`). Se reparte al teléfono sin reiniciar |
| **Coche** | Edita `config/car.toml` (la pantalla de Android Auto) con vista previa |
| **Teléfono** | Estado del Redmi por cable (adb), «Dedicar a Vega» y «Deshacer» |
| **Mantenimiento** | **Liberar RAM** y **limpiar disco**, viendo antes qué se toca (ver abajo) |
| **Ajustes** | Lo principal de `config/assistant.toml` y arrancar, reiniciar o parar el servidor |
| **Registros** | `server.log`, arranque, vigilante, botones pulsados y los informes de cuelgues |

Al editar archivos de configuración se **conservan los comentarios**, se guarda una copia
(`.bak`) y se valida con las mismas reglas que el servidor antes de escribir.

## Mantenimiento: liberar RAM y limpiar disco

La página **Mantenimiento** y los botones **Liberar RAM** y **Limpiar disco** (también por voz: «Vega, libera
la memoria», «Vega, limpia el disco») hacen cosas distintas según dónde los pulses:

| | Página del PC | Botón del teléfono o voz |
|---|---|---|
| **Liberar RAM** | Descarga el modelo de Ollama y devuelve a Windows la memoria que Vega no usa. Con **«A fondo»**, además reduce las demás aplicaciones (se vuelven a llenar si las usas) y detiene los servicios de Gradle y Kotlin | Solo lo básico (sin «a fondo») |
| **Limpiar disco** | **Buscar** muestra qué se puede limpiar y cuánto ocupa; marcas lo que quieres y confirma | Solo lo que se regenera al momento (registros viejos, cachés de programación y temporales de más de 7 días), y pide confirmación |

Reglas de seguridad, comprobadas con pruebas:
- **No se borra nada sin verlo antes** (en la página) y las categorías son una lista cerrada en el código
  (`server/core/maintenance.py`): ni el teléfono ni la app pueden pedir otra ruta.
- **No toca** documentos, descargas, la papelera, `audit.log` (el rastro de las acciones) ni el espacio de
  trabajo de la app de Claude (`%TEMP%\claude…`). No sigue enlaces ni uniones.
- **No cierra programas** de otros: solo les pide que devuelvan la memoria que no usan.
- Categorías: registros viejos de Vega, cachés de programación, temporales de Windows (de más de 7 días),
  compilaciones de la app del teléfono y caché de Gradle (estas dos, sin marcar de serie: tardan en volver a
  crearse).

**Ojo con lo que de verdad libera:** reducir lo que ocupa un programa no aumenta el dato de «libres» de
Windows (esa memoria pasa a la reserva, que ya cuenta como disponible). Lo que sube la RAM libre de verdad es
descargar el modelo de Ollama (~3 GB) y detener los servicios de Gradle (~4 GB); cerrar programas pesados
(como el navegador) lo hace el usuario.

Medido en este PC (25-10-2026): Vega ocupa ~0,6 GB y Ollama ~0,07 GB sin modelo cargado (con
`qwen3.5:4b` cargado, unos 3 GB). Lo que más RAM gasta es el navegador (~12 GB), la app de Claude, Discord y
los servicios de Gradle que dejan las compilaciones (~4 GB): por eso existe el modo «a fondo». Se pueden
liberar ~2,2 GB de disco sin riesgo, y ~2,2 GB más si se borran las compilaciones y la caché de Gradle.

## Cómo se sincroniza

La app es un cliente más del servidor local (`ws://127.0.0.1:8000/ws`) con rol `desktop`:

- El **teléfono publica su estado** cada vez que cambia (`phone_state`); el servidor se lo pasa
  al escritorio. Si el teléfono se desconecta, el escritorio lo sabe.
- Lo que Vega **oye y responde en el teléfono** se refleja en el escritorio.
- El escritorio **manda órdenes** al teléfono (`phone_control`): dormir, despertar, cambiar
  de IA, estilo y micrófono. Solo existen esas ocho; el servidor rechaza cualquier otra, y un
  teléfono no puede mandarse órdenes a sí mismo por esta vía.
- El escritorio **no oye ni habla**: su conexión no tiene micrófono ni voz.
