# IA local en el teléfono — plan (en estudio)

**Objetivo.** Que Vega funcione **sin el PC**, en el propio teléfono:
- sin gastar la tarjeta gráfica del PC;
- en cualquier sitio (el «modo bolsillo»);
- y como paso hacia equipos modestos (otros Android, Raspberry Pi).

Cuando el teléfono esté conectado al PC, el PC puede seguir ayudando y se sincronizan
memoria, notas y recordatorios.

> Estado: **construido (24-10-2026), sin probar aún en el Redmi**. Las cifras de la
> sección «Medidas» son reales; las de «Las piezas» eran la estimación previa.

## Cómo funciona ahora («IA: teléfono», en «Más»)

- **Lo que hace el teléfono:**
  - corta el micrófono en frases;
  - las transcribe con Whisper base (sherpa-onnx, dentro de la app);
  - mira si empiezan por «Vega»;
  - contesta con la voz Piper `es_MX`.
- **Con el PC conectado:** le pasa la orden ya escrita (`phone_command`). El PC usa solo sus
  habilidades y botones, sin modelo de lenguaje ni gráfica, y saca el modelo de la memoria
  de vídeo. Así se puede jugar.
- **Lo que el PC no resuelve, o si no hay PC**, lo contesta el teléfono:
  - hora y fecha;
  - cuentas, moneda, dado y chistes;
  - recordatorios, temporizadores, notas y memoria.
- **Lo que el teléfono guardó sin PC** pasa al PC al volver a conectar (`memory_import`), y
  desde entonces lo guarda y avisa el PC.
- **Los modelos** (~230 MB) los sirve el PC desde `data/telefono` (`/app/models`) y la app
  los baja una sola vez.

## El teléfono

- **Modelo:** Redmi 15C, Android 15.
- **Chip:** MediaTek MT6769 (Helio G81–G85: 2 núcleos rápidos + 6 lentos).
- **Gráfica:** Mali-G52.
- **Memoria:** 4 GB de RAM.

Es un teléfono modesto. Eso obliga a modelos pequeños y a que casi todo lo útil no
dependa de un modelo de lenguaje.

## Medidas en el Redmi 15C (23-10-2026)

Programas compilados para el teléfono y ejecutados por cable en `/data/local/tmp`, sin
instalar nada:
- llama.cpp, compilado con el NDK y `dotprod`;
- sherpa-onnx 1.13.8.

| Pieza | Medido | Veredicto |
|---|---|---|
| Qwen 3.5 **0.8B** Q8_0 (764 MB) | Lee 29–41 trozos/s; **escribe ~9 palabras/s** con los 2 núcleos rápidos (con 8 núcleos baja a 3,5). Carga en 4 s | Utilizable, pero pobre: «Un volcán es un punto caliente en el interior de una montaña o montaña» |
| Qwen 3.5 0.8B Q4_K_M (494 MB) | Escribe 7,8–9,1/s: **no es más rápido** que Q8 en este chip | Solo si falta RAM |
| Whisper **tiny** (sherpa-onnx, int8) | ~1,3 s por frase de 3 s | Se equivoca bastante («pampa Ramaniana») |
| Whisper **base** (sherpa-onnx, int8) | **~2,2 s por frase de 3 s** (4 núcleos) | **Bien**: «Apunta a comprar leche y pan para mañana». Escribe «Bega», que Vega ya acepta |
| Voz Piper `es_MX-claude-high` (sherpa-onnx) | **5,3 s de audio en ~2 s** | Bien: 2,5 veces más rápida que el tiempo real |
| Voz Kokoro `ef_dora` int8 (la del PC; 24-10-2026) | 2,9 s de audio en 11,6 s (igual con 2 o 4 núcleos) | **Demasiado lenta** |
| Voz Kokoro `ef_dora` fp32 | 3,1 s de audio en 8,4 s (4 núcleos) | **Demasiado lenta** |

Por eso, con la IA en el teléfono y el PC conectado, el teléfono **pide la voz al PC**
(mensaje `say`): Kokoro en el procesador del PC, sin gráfica (3,2 s de voz en 1,2 s). Sin PC,
o si no contesta en 3 s, habla Piper en el teléfono.

**Memoria.** Con todo cargado se usarían unos 1,3 GB: el modelo 0,8 GB, Whisper base
~0,3 GB y Piper ~0,1 GB. El teléfono tenía 1,5–2 GB libres. Cabe, pero justo.

**Tiempos estimados sin PC**, desde que terminas de hablar hasta oír la voz:
- **Habilidades** (recordatorios, notas, temporizadores, memoria): **~3 s**.
  - Whisper base: 2,2 s.
  - Primera frase de voz: 0,5 s.
- **Conversación libre: ~5–6 s.**
  - Leer una pregunta corta: 1,6 s. Si las instrucciones se leen una sola vez y se guardan,
    baja a ~0,5 s.
  - Primeras palabras: ~1 s.
  - Voz: 0,5 s.

**Conclusión:**
- Vega sin PC es viable con las habilidades, Whisper base y Piper.
- El modelo de 0.8B sirve para charlar poco y con paciencia: se equivoca a menudo.

## Las piezas (de más fácil a más difícil)

| Pieza | Opción más prometedora | Por qué | Coste aproximado |
|---|---|---|---|
| Habilidades (memoria, recordatorios, temporizadores, notas, alarmas) | Pasar `server/skills/` a Kotlin | Son reglas, no IA: rápidas, fiables y sin coste | Casi nada |
| Voz → texto | [Vosk](https://alphacephei.com/vosk/) español pequeño (~40 MB) o Whisper tiny/base con [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Sin conexión, en la CPU del teléfono | Hay que medirlo |
| Palabra «Vega» | Vosk con gramática cerrada («vega» o nada): escucha continua barata | Evita transcribir todo el rato | Bajo |
| Texto → voz | La voz de Android (Google TTS en español, sin conexión) o Piper `es_MX` con sherpa-onnx | La de Android no cuesta nada; Piper suena como en el PC | Bajo |
| Conversación libre | Qwen 3.5 **0.8B** cuantizado (~0,5 GB) con llama.cpp | Lo único que cabe con 4 GB de RAM | ¿5–8 palabras/s? Hay que medirlo |

**Expectativa honesta.** Un modelo de 0.8B en este teléfono conversa peor que el de 4B del
PC: se equivoca más y sabe menos. Lo práctico es que las habilidades hagan casi todo el
trabajo útil sin conexión, y dejar la conversación libre para cuando hay PC, o usarla
con paciencia.

## Cómo encaja con lo que ya hay

- **Con cable y el PC encendido:** igual que ahora. El PC entiende, piensa y habla, y el
  teléfono es el cuerpo.
- **Sin PC:** el teléfono entiende, usa sus habilidades y habla él solo. Guarda las notas,
  los recordatorios y lo que aprende de ti.
- **Al volver a conectar:** se sincronizan memoria, notas y recordatorios en los dos
  sentidos.

## Pasos propuestos

1. ~~**Medir antes de decidir.**~~ Hecho (23-10-2026), ver «Medidas».
   Lo previsto era: Una versión de prueba de la app que mida, en el Redmi 15C:
   - Vosk español: velocidad y aciertos;
   - Qwen 3.5 0.8B: palabras por segundo y RAM;
   - la voz de Android frente a Piper.
2. **Habilidades en el teléfono:** recordatorios, temporizadores, notas y memoria, sin
   conexión.
3. **Escucha «Vega» y voz en el teléfono** (Vosk y la voz de Android o Piper).
4. **Sincronización** con el PC al conectar el cable.
5. **Conversación libre en el teléfono**, solo si las medidas del paso 1 lo justifican.
