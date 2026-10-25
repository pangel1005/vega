# Vega en el coche (Android Auto)

Vega tiene una pantalla propia en Android Auto: una cuadrícula de hasta 6 casillas que se
configura desde la app del PC (página **Coche**) o en `config/car.toml`.

## Qué hace

| Casilla | Qué hace |
|---|---|
| Dormir | Duerme o despierta a Vega |
| Pausa / Play · Siguiente | Controla la música del PC |
| Avisos del PC | Lista de las notificaciones del PC (solo lectura) |
| Estado del PC | Procesador, memoria y temperatura de la gráfica |
| IA: PC / móvil | Cambia dónde piensa Vega |
| Datos del coche | Velocidad, revoluciones, temperatura del motor y gasolina, si hay lector OBD-II |
| Un botón del PC | Cualquiera de `config/actions.toml`. Los que piden confirmación («Apagar PC») preguntan «¿Seguro?» también en el coche |

Mientras el coche está conectado, **Vega sigue viva** aunque la pantalla del teléfono no se
vea: un servicio en primer plano mantiene el micrófono y el enlace con el PC.

## Lo que NO hace (a propósito)

- **No escribe nada en el coche.** Vega no modifica la pantalla ni el software del coche:
  Android Auto dibuja la pantalla con sus plantillas, y lo único que cambia aquí es qué
  casillas enseña.
- **Los datos del coche son solo de lectura.** El código solo puede enviar al lector OBD-II
  una lista cerrada de órdenes (el modo 01, «leer datos actuales», y los ajustes del propio
  lector). No puede borrar errores, escribir, ni hablar con otras centralitas. Está
  comprobado con pruebas (`ObdTest`).
- **Vega no sustituye al asistente de voz de Google en el coche:** Android Auto no deja que otra
  app lo haga. Vega escucha por el micrófono del teléfono.

## Puesta en marcha (una sola vez)

1. **Reponer lo que Android Auto necesita.** La limpieza del teléfono quitó Google Play Services y
   Play Store; Android Auto no funciona sin ellos:

   ```powershell
   uv run python tools\phone_dedicate.py --car      # los repone
   uv run python tools\phone_dedicate.py --no-car   # los vuelve a quitar (recupera ~480 MB de RAM)
   ```

2. **Instalar Android Auto** desde Google Play en el teléfono (con tu cuenta). El que trae
   el teléfono es solo un marcador de sistema.
3. **Activar el modo desarrollador de Android Auto:** en la app, toca 10 veces «Versión»,
   abre **Ajustes de desarrollador** y activa **Fuentes desconocidas** (así acepta apps que
   no vienen de Google Play, como Vega).
4. **Instalar la última versión de Vega** (botón *Actualizar*). Vega aparece entonces en la
   lista de apps de Android Auto.

## Probarlo sin coche (en el PC)

Google ofrece un emulador de coche, el Desktop Head Unit (DHU), ya instalado con
`sdkmanager "extras;google;auto"`:

1. En Android Auto (teléfono): menú ⋮ → **Iniciar servidor de unidad principal**.
2. En el PC, con el teléfono por USB:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\dhu.ps1
   ```

3. En la ventana del DHU, abre el lanzador de apps y elige **Vega**.

## Datos del coche (OBD-II, opcional)

Hace falta un lector **ELM327 por Bluetooth** (unos pocos euros) en el conector OBD-II del
coche, **emparejado con el teléfono** en Ajustes → Bluetooth. Vega lo busca sola por su nombre
(«OBD», «ELM», «V-Link», «Vgate», «Veepeak»…), se conecta cuando el coche está conectado y lee
cada 2 s. La casilla «Datos del coche» enseña lo que el coche conteste.

## Lo que no he podido probar

Todo el código y la lógica están probados con pruebas automáticas (las casillas, sus estados,
las pantallas con el coche de mentira de `androidx.car.app:app-testing`, y el lector OBD con un
ELM327 simulado). **No se ha probado con un coche real ni con el DHU**, porque hace falta tu
cuenta de Google en el teléfono para instalar Android Auto. Cuando lo hagas, lo más probable
que haya que ajustar es el texto de las casillas y lo que cabe en tu pantalla.
