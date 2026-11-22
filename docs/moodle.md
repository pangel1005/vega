# Vega y Moodle: entregas de la universidad

Vega lee tu calendario de Moodle (sin tu contraseña), sabe qué entregas tienes pendientes y te avisa **una hora
antes** de que venza cada una.

## Conectarlo (una vez)

1. En Moodle: **Calendario → Importar o exportar calendarios → Exportar**.
2. Elige **Todos los eventos** y **Próximos 60 días** (o «Recientes y próximos 60 días»).
3. Pulsa **Obtener URL del calendario** y copia la dirección (lleva una clave que solo lee tu calendario).
4. En el PC:

   ```
   python -m server.tools.moodle_setup
   ```

   Pega la dirección (no se ve al escribirla), comprueba las entregas que enseña y di que sí. Se guarda en
   `config/moodle.json`, fuera de git.
5. En `config/assistant.toml` pon `[moodle] enabled = true` y reinicia Vega (tarea «Vega (asistente)»).

## Cómo funciona

- Cada 20 minutos (`refresh_minutes`) baja el calendario y, por cada evento que **vence o cierra**, programa un
  recordatorio a la hora configurada en `alerts` (`[60]` = una hora antes; `[1440, 60]` = un día y una hora antes).
- Es el mismo sistema de recordatorios de siempre: suena en el teléfono y aparece en «próximos avisos». Con
  `[telegram] enabled = true` y `reminders = "away"`, llega también a Telegram cuando el teléfono no está conectado
  (usa `"always"` si quieres el aviso siempre en el móvil).
- Si el profesor cambia la fecha, los avisos se rehacen. Si Vega descubre una entrega que vence en menos de una hora,
  avisa en el acto.
- Si no consigue leer Moodle durante 6 horas, te lo dice (para que no se te pase una entrega sin enterarte).

## Por voz (o por Telegram)

- «¿Qué tareas tengo?», «mis entregas pendientes», «¿qué tengo que entregar?»
- «Ya entregué el ensayo 2» / «marca el informe como entregada»: anula sus avisos.
- El resumen del día («buenos días») incluye las entregas de los próximos dos días.

## Límite conocido

El calendario exportado **no dice si ya entregaste** la tarea: por eso hay que decirle a Vega «ya entregué…». Si algún
día quieres que lo sepa solo, hay que usar el servicio web de Moodle con un token (depende de que tu universidad lo
tenga activado y de cómo inicies sesión).
