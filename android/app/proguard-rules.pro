# sherpa-onnx (Whisper y Piper en el teléfono): su código nativo lee los campos de estas
# clases por nombre. Si R8 los renombra o quita, el reconocimiento de voz no arranca.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Modelo de lenguaje del teléfono (libvegallm.so): el código nativo llama a onPiece por su nombre.
-keep class com.asistente.personal.local.LlamaNative { *; }
-keep interface com.asistente.personal.local.LlamaNative$Piece { *; }
-keep class * implements com.asistente.personal.local.LlamaNative$Piece { *; }
