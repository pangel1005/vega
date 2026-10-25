import os

# Sin pantalla: las pruebas dibujan en memoria.
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
