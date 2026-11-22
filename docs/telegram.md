# Vega por Telegram («Vega fuera de casa»)

Fecha: 11-11-2026 (apagar la PC, /hoy y /noche: 22-11-2026) · Estado: **activado** (`[telegram] enabled = true`).

## Qué hace

- Le escribes a tu bot desde cualquier sitio y contesta **Vega**, con lo mismo que en la app: recordatorios («recuérdame en
  10 minutos…»), notas, fondo de pantalla, volumen, abrir juegos y cualquier pregunta al modelo del PC.
- Si el **teléfono no está conectado** (cable suelto, USB caído…), los **recordatorios te llegan por Telegram** en vez de
  quedarse esperando. Con el teléfono conectado los dice él en voz alta y Telegram calla (`reminders = "away"`; también
  `"always"` y `"never"`).
- Comandos: `/hoy` (el resumen del día: recordatorios, entregas de Moodle, la llama del gimnasio, el tiempo),
  `/noche` («buenas noches»: lo de mañana, la llama y si apago la PC), `/apagar`, `/cancelar`, `/estado` (procesador,
  memoria, gráfica, juego y si el teléfono está conectado), `/recordatorios`, `/ayuda`.

## Apagar la PC desde Telegram

1. `/apagar`, «apaga la PC» o el final de `/noche` → «⚠️ ¿Apago la PC?» con dos botones, **🔌 Sí, apagar** y
   **Cancelar**. Si hay un juego abierto lo dice («🎮 Tienes abierto Fortnite: se cerrará.»).
2. Con **Sí, apagar**, Windows se apaga en **60 s** y el mensaje cambia a un botón **✋ Cancelar apagado** (también vale
   `/cancelar` o «no apagues la PC»).
3. Cada pregunta vale **una sola vez** y **caduca a los 2 minutos** (y con cada reinicio del servidor): un botón viejo no
   apaga nada. Los botones de otras cuentas se ignoran. Todo queda en el registro de auditoría (`cliente: telegram`).
4. Solo funciona si el botón «Apagar PC» existe en `config/actions.toml`: si lo quitas, Telegram tampoco apaga.
5. Límite: con la PC apagada Vega tampoco está, así que **no se puede volver a encender desde Telegram** (haría falta
   Wake-on-LAN desde otro aparato despierto en casa). Reiniciar o suspender no se ofrecen.

## Seguridad

- **Todo es saliente**: el PC pregunta a Telegram cada pocos segundos. No se abre ningún puerto ni se expone el servidor de
  Vega a internet.
- Solo obedece a **las cuentas de `config/telegram.json`** y solo en chats privados (un grupo, otra persona o un bot se
  ignoran sin responder). Sin ninguna cuenta autorizada no arranca.
- **De los botones del PC solo apaga**, y siempre con la pregunta y los 60 s de arriba. Reiniciar, suspender, rutinas o
  atajos de teclado no se manejan desde Telegram.
- El token está en `config/telegram.json`, **fuera de git**, y **nunca va a los registros** (los errores solo dicen su tipo o
  el código HTTP). Una prueba lo comprueba.
- Los mensajes con más de 2 minutos se descartan: una orden vieja no se ejecuta cuando el PC enciende.
- Telegram **no cifra de extremo a extremo** los chats con bots: lo que escribas pasa por sus servidores. Aquí eso es nuevo
  (hasta ahora nada salía del PC), y para una cuenta con datos sensibles conviene tenerlo presente.

## Cómo activarlo (una vez)

1. En Telegram abre **@BotFather**, escribe `/newbot`, elige un nombre y copia el **token**.
2. En el PC ejecuta `python -m server.tools.telegram_setup` (con el entorno del proyecto). Pide el token sin mostrarlo, lo
   comprueba, te pide que le escribas cualquier cosa al bot desde **tu** cuenta, te enseña quién eres y, si confirmas, guarda
   `config/telegram.json`.
3. En `config/assistant.toml` pon `[telegram] enabled = true` y reinicia Vega.

## Qué falta / límites

- Sin probar contra Telegram de verdad (solo con respuestas simuladas): la primera conexión real puede traer sorpresas.
- Los avisos que fallan 3 veces seguidas (sin internet) se pierden: el recordatorio ya cuenta como entregado.
- Con un juego abierto, el modelo del PC contesta en el procesador y puede tardar (hay un límite de 2,5 minutos).
- Ideas siguientes: avisar si el teléfono lleva un rato desconectado, ver una captura de pantalla del PC y encender la PC
  a distancia (Wake-on-LAN).
