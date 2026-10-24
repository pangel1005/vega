# sherpa-onnx (Whisper y Piper en el teléfono): su código nativo lee los campos de estas
# clases por nombre. Si R8 los renombra o quita, el reconocimiento de voz no arranca.
-keep class com.k2fsa.sherpa.onnx.** { *; }
