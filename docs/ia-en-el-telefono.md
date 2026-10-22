# IA local en el teléfono — plan (en estudio)

**Objetivo.** Que Vega funcione **sin el PC**, en el propio teléfono:
- sin gastar la tarjeta gráfica del PC;
- en cualquier sitio (el «modo bolsillo»);
- y como paso hacia equipos modestos (otros Android, Raspberry Pi).

Cuando el teléfono esté conectado al PC, el PC puede seguir ayudando y se sincronizan
memoria, notas y recordatorios.

> Estado: **idea en estudio**. Nada de esto está hecho ni medido todavía en el teléfono.
> Las cifras de abajo son estimaciones que hay que comprobar.

## El teléfono

- **Modelo:** Redmi 15C, Android 15.
- **Chip:** MediaTek MT6769 (Helio G81–G85: 2 núcleos rápidos + 6 lentos).
- **Gráfica:** Mali-G52.
- **Memoria:** 4 GB de RAM.

Es un teléfono modesto. Eso obliga a modelos pequeños y a que casi todo lo útil no
dependa de un modelo de lenguaje.

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

1. **Medir antes de decidir.** Una versión de prueba de la app que mida, en el Redmi 15C:
   - Vosk español: velocidad y aciertos;
   - Qwen 3.5 0.8B: palabras por segundo y RAM;
   - la voz de Android frente a Piper.
2. **Habilidades en el teléfono:** recordatorios, temporizadores, notas y memoria, sin
   conexión.
3. **Escucha «Vega» y voz en el teléfono** (Vosk y la voz de Android o Piper).
4. **Sincronización** con el PC al conectar el cable.
5. **Conversación libre en el teléfono**, solo si las medidas del paso 1 lo justifican.
