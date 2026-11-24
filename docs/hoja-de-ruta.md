# Hoja de ruta de Vega (23-11-2026)

Hacia dónde va Vega ahora que es pública. La idea de fondo no cambia: **revivir aparatos viejos** (el teléfono del
cajón y un PC normal) con un asistente de voz 100 % local. El plan detallado de funciones para el uso diario sigue
en [plan-de-mejoras.md](plan-de-mejoras.md). ¿Ideas? [Abre una](https://github.com/pangel1005/vega/issues/new?template=idea.yml)
o escribe en *Discussions*.

## 1. Que cualquiera pueda probarla (lo primero)

| Qué | Por qué | Tamaño |
|---|---|---|
| **APK ya hecho en GitHub Releases**, firmado con una clave propia de Vega y compilado por GitHub Actions | Hoy instalar la app pide Android Studio: ahí abandona la mayoría | M |
| **Vídeo o GIF de 30 s** en el README: teléfono viejo → Vega respondiendo | La idea se entiende al verla | S |
| **Modo demo sin teléfono** (la cara y la voz en una ventana del PC) | Probar antes de comprometer un teléfono | M |
| **Inglés**: frases, app y voz (Whisper y Kokoro ya lo saben) | Multiplica a quién le sirve | L |
| **Instalador .exe** (o un ZIP con todo) en vez de PowerShell | Menos miedo para quien no programa | M |
| Arranque en Linux (dependencias de Windows opcionales) | Mucha gente de *self-hosted* usa Linux | L |

## 2. Fiabilidad

- ✅ 23-11: un solo contexto SSL para todos los clientes HTTP y la carátula fuera del bucle (eran la mayoría de
  los cuelgues que vio el vigilante).
- Lo que queda dentro del bucle: leer las notificaciones de Windows (WinRT) y cambiar Whisper de sitio al abrir un
  juego. Pasarlos a otro hilo y volver a mirar `logs/cuelgues/` una semana después.
- El filtro de «Vega» del teléfono deja pasar casi todo (~94 %): recalibrarlo con grabaciones reales, solo con
  permiso explícito y borrándolas después.
- Descargas de modelos que se puedan reanudar si se corta internet.

## 3. Cosas nuevas

**Para más gente**
- **Vega solo en el teléfono**, sin PC: ya existe el modo «IA: teléfono» (modelo de 0,8B, Whisper y Piper en el
  propio teléfono); hacerlo independiente encaja de lleno con revivir aparatos viejos.
- **Habilidades como complementos** (*plugins*): una carpeta `skills/` donde cualquiera añade una habilidad sin
  tocar el núcleo. Es lo que hace crecer un proyecto abierto.
- **Home Assistant**: luces y enchufes por voz («Vega, apaga la luz»).
- **Varios teléfonos, varias habitaciones**: el mismo PC, un Vega en cada cuarto.

**Para el día a día**
- Encender la PC desde Telegram (Wake-on-LAN desde el teléfono de Vega, que sigue despierto).
- Informe del domingo por Telegram: gimnasio, entregas, horas de juego y lo de la semana que viene.
- Más llamas además del gimnasio: estudio, agua, dormir bien.
- Horario de clases: «¿qué clase tengo ahora?» y aviso antes de cada una.
- Despertador con amanecer: el brillo sube poco a poco y Vega te da «buenos días».
- Modo juego automático: silenciar avisos y, al cerrar, decirte cuánto jugaste.
- Altavoz ESP32 como segundo «cuerpo» barato (prototipo en `docs/xiaozhi-prototipo.md`).

## 4. Comunidad

- Formularios en GitHub: [🙋 Probé Vega](https://github.com/pangel1005/vega/issues/new?template=probe-vega.yml),
  [🐞 Algo falla](https://github.com/pangel1005/vega/issues/new?template=fallo.yml) y
  [💡 Idea](https://github.com/pangel1005/vega/issues/new?template=idea.yml). *Discussions* para charlar.
- Un grupo de Telegram de testers para avisar de cada versión.
- Dónde enseñarla: la universidad, r/LocalLLaMA, r/selfhosted, r/Android, comunidades de programación en español
  y vídeos cortos («convertí mi teléfono viejo en un asistente de IA»).
