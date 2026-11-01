# Intercom Puente

App Android casera para pasar la voz de un intercomunicador Bluetooth a otro cuando los
dos modelos no se pueden emparejar entre sí.

```
Intercom 1 <-BT-> Teléfono A <-WiFi / hotspot-> Teléfono B <-BT-> Intercom 2
```

Cada teléfono toma el audio del micrófono (que Android enruta al intercomunicador por
Bluetooth) y lo manda por UDP al otro. Lo que recibe lo reproduce por la salida de voz,
que Android enruta al mismo intercomunicador. No usa internet ni minutos.

## Cómo usarla

1. Instala el APK en los dos teléfonos (Releases del repo). Android pedirá permitir
   instalar apps de esta fuente.
2. Empareja cada intercomunicador con su teléfono por Bluetooth, como un manos libres.
3. En el teléfono A activa el hotspot. Conecta el teléfono B a ese hotspot.
4. Abre la app en los dos y pulsa **Iniciar puente**. Acepta los permisos de micrófono,
   Bluetooth y notificaciones.
5. En unos segundos cada teléfono debe mostrar la IP del otro y los contadores de
   *Enviados* y *Recibidos* deben subir. Habla por un intercom y escucha por el otro.

Si no se encuentran solos, escribe en uno de los dos la IP que el otro muestra en
*Mis IPs* y vuelve a iniciar.

## Probar sin intercomunicadores

Si el teléfono no tiene un intercom Bluetooth conectado, la app usa el micrófono y el
altavoz del teléfono (verás "Audio: altavoz del teléfono"). Separa bien los dos teléfonos
para que no haya acople.

## Detalles técnicos

- Audio PCM 16 kHz mono, tramas de 20 ms, por UDP (puerto 40000). Descubrimiento por
  broadcast en el puerto 40001, con la puerta de enlace del WiFi como primer intento.
- La ruta Bluetooth usa el perfil de manos libres (llamada), no el de música.
- Compila solo con GitHub Actions: cada push a `main` genera un APK nuevo en Releases.

## Pendiente

- Compartir música del teléfono (hace falta captura de audio de otras apps).
- Cifrado / contraseña: hoy cualquiera en la misma red podría mandar audio.
