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
| **Botones** | Edita `config/actions.toml`: añadir, quitar, ordenar, frases de voz, confirmación. Se reparte al teléfono sin reiniciar |
| **Coche** | Edita `config/car.toml` (la pantalla de Android Auto) con vista previa |
| **Teléfono** | Estado del Redmi por cable (adb), «Dedicar a Vega» y «Deshacer» |
| **Ajustes** | Lo principal de `config/assistant.toml` y arrancar, reiniciar o parar el servidor |
| **Registros** | `server.log`, arranque, vigilante, botones pulsados y los informes de cuelgues |

Al editar archivos de configuración se **conservan los comentarios**, se guarda una copia
(`.bak`) y se valida con las mismas reglas que el servidor antes de escribir.

## Cómo se sincroniza

La app es un cliente más del servidor local (`ws://127.0.0.1:8000/ws`) con rol `desktop`:

- El **teléfono publica su estado** cada vez que cambia (`phone_state`); el servidor se lo pasa
  al escritorio. Si el teléfono se desconecta, el escritorio lo sabe.
- Lo que Vega **oye y responde en el teléfono** se refleja en el escritorio.
- El escritorio **manda órdenes** al teléfono (`phone_control`): dormir, despertar, cambiar
  de IA, estilo y micrófono. Solo existen esas ocho; el servidor rechaza cualquier otra, y un
  teléfono no puede mandarse órdenes a sí mismo por esta vía.
- El escritorio **no oye ni habla**: su conexión no tiene micrófono ni voz.
