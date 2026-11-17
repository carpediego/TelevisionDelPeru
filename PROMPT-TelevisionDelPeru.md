# Prompt de proyecto — «Televisión del Perú» (app Android de TV en vivo)

> Copia este archivo completo como primer mensaje de un chat nuevo. Es autocontenido:
> incluye el encargo, las decisiones ya tomadas, los datos exactos del proyecto y las
> trampas que ya pagamos en la app hermana de radio.

---

## 1. Encargo

Construye **desde cero** una app Android nativa llamada **Televisión del Perú**: una sola
pantalla con canales peruanos **en vivo**, pensada para personas mayores. El APK lo van a
usar gente real en teléfonos reales: no es una demo, no hay margen para "más o menos
funciona".

Debe quedar: proyecto compilando, APK release firmado, catálogo remoto en GitHub,
robot de validación, actualizador dentro de la app y documentación.

---

## 2. La app que ya existe (contexto)

Hay una app hermana ya probada en teléfono: **Radios del Perú** (v4, 1.4.0), en
`X:\APPS CREADAS\Radio\v4` y en `github.com/carpediego/RadiosdelPeru`. Este proyecto
repite su arquitectura y sus decisiones, cambiando audio por video y emisoras por canales.

**Si tienes acceso a esa carpeta, úsala como plantilla** (ver sección 14). Si no,
construye siguiendo este documento: está completo.

---

## 3. Alcance funcional

- Lista de **12 a 20 canales peruanos** en vivo (nacionales y algunos regionales).
- Tocar un canal → se reproduce el video en vivo en la zona superior de la pantalla.
- Un solo botón grande: **VER** cuando está en pausa, **DETENER** cuando suena.
- Estados visibles y en letra grande: `Conectando…`, `● EN VIVO`, `En pausa`,
  `Sin conexión. Reintentando…`, `Este canal no está disponible. Prueba otro.`
- Sin cuentas, sin publicidad, sin analítica, sin permisos innecesarios.
- Al salir de la app el video **se pausa** (no sigue sonando a escondidas).
- El catálogo se actualiza solo desde GitHub; el código se actualiza desde la app.

**Fuera de alcance (v1):** EPG, grabación, Chromecast, favoritos, pantalla completa,
canales por YouTube, idiomas.

---

## 4. Decisiones cerradas (no las re-litigues)

| Decisión | Por qué |
|---|---|
| **Media3 (ExoPlayer) + `MediaSessionService`** | Reproduce HLS, sobrevive a la pantalla bloqueada, da notificación y controles del sistema. |
| **Nada de WebView** | El audio/video se corta en segundo plano y no hay controles en la notificación. Ya se descartó. |
| **El servicio manda; la pantalla solo dibuja** | Lo que debe funcionar sin la app abierta (reintentos, reconexión) no puede vivir en una Activity. |
| **Catálogo remoto + copia embebida** | Cambiar una URL no debe requerir compilar ni reinstalar. |
| **Un solo módulo, sin librerías de terceros** | APK chico, sin publicidad ni rastreadores. |
| **Actualizador propio con GitHub Releases** | No hay Play Store. Android siempre pedirá confirmar la instalación: eso no se puede evitar. |
| **Firma fija y respaldada** | Sin la keystore no hay actualizaciones: habría que desinstalar en todos los teléfonos. |

---

## 5. Datos exactos del proyecto

| Dato | Valor |
|---|---|
| Nombre visible | `Televisión del Perú` |
| `applicationId` / `namespace` | `pe.televisiondelperu.app` |
| Repositorio | `github.com/carpediego/TelevisionDelPeru` (raíz del repo = raíz de la app) |
| Carpeta local | `X:\APPS CREADAS\TelevisionDelPeru` |
| Versión inicial | `1.0.0` (`versionCode 1`) |
| SDK | `minSdk 24`, `compileSdk 36`, `targetSdk 36` |
| Kotlin | `2.4.21` |
| AGP | `8.13.2` |
| Gradle | `8.14.3` |
| JDK | 21 (toolchain portable) |
| Java source/target | 17 |
| Dependencias | `androidx.core:core-ktx:1.16.0`, `androidx.appcompat:appcompat:1.8.0`, `androidx.recyclerview:recyclerview:1.4.0`, `androidx.constraintlayout:constraintlayout:2.2.2`, `androidx.media3:media3-exoplayer:1.11.1`, `androidx.media3:media3-exoplayer-hls:1.11.1`, `androidx.media3:media3-session:1.11.1`, `androidx.media3:media3-ui:1.11.1` |
| APK esperado | 2–3 MB, release con R8 y recursos reducidos |

Configuración Gradle relevante:

- `buildFeatures { buildConfig = false }` (la versión se lee con `PackageManager`).
- `release { isMinifyEnabled = true; isShrinkResources = true }`.
- Firma desde `keystore/keystore.properties` (ver sección 9).
- Assets del catálogo apuntando a `catalog/`:
  `sourceSets["main"].assets.srcDirs(rootProject.file("catalog"))`.

---

## 6. Arquitectura obligatoria

```
TelevisionDelPeru/
├─ build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew(.bat)
├─ local.properties            (sdk.dir; no va a git)
├─ build.ps1                   compila con el JDK del toolchain y deja el APK en dist\
├─ keystore/                   (no va a git) keystore.properties + .jks
├─ version.json                última versión publicada (lo lee el actualizador)
├─ catalog/channels.json       catálogo (única fuente de verdad)
├─ tools/validate.mjs          validador/reparador del catálogo (lo usa el robot)
├─ tools/release.ps1           compila, calcula sha256 y escribe version.json
├─ .github/workflows/catalog.yml  robot cada 6 horas
├─ README.md, PLAN.md
└─ app/src/main/
   ├─ AndroidManifest.xml
   ├─ java/pe/televisiondelperu/app/
   │  ├─ MainActivity.kt            pantalla: estado + órdenes
   │  ├─ catalog/Channel.kt         modelo
   │  ├─ catalog/CatalogRepository.kt  embebido + caché + descarga remota
   │  ├─ player/PlaybackService.kt  servicio: Media3, reintentos, reanudación
   │  ├─ player/LiveChannelPlayer.kt  play = volver al directo
   │  ├─ player/PlaybackStatus.kt   estado compartido servicio ↔ pantalla
   │  ├─ player/SessionState.kt     último canal (SharedPreferences)
   │  ├─ update/UpdateInfo.kt       lectura de version.json
   │  ├─ update/Updater.kt          comprobar, descargar, instalar
   │  └─ ui/ChannelAdapter.kt       lista de canales
   └─ res/                          layout, colores día/noche, strings, xml/file_paths.xml
```

Responsabilidades:

- **`PlaybackService`** es dueño del `ExoPlayer`, de los reintentos, de la rotación de
  URLs alternativas, de la sesión y del canal recordado. Se declara con
  `foregroundServiceType="mediaPlayback"`.
- **`MainActivity`** no reproduce nada: pinta `PlaybackStatus` y manda órdenes al
  `MediaController`.
- **`PlaybackStatus`**: singleton en memoria (mismo proceso) con fases
  `IDLE, CONNECTING, LIVE, PAUSED, RETRYING, UNAVAILABLE`.
- **`SessionState`**: `SharedPreferences` con el último `channelId`.

---

## 7. Reglas de reproducción (esto es lo que más se rompe)

1. **Un directo no se pausa.** Si se pausa, la conexión caduca (HLS borra segmentos). Al
   recibir play después de una pausa hay que **reconectar al vivo**, no continuar el
   buffer. Se hace envolviendo el player en un `ForwardingPlayer`:

```kotlin
class LiveChannelPlayer(delegate: Player) : ForwardingPlayer(delegate) {
    private var pausedWaitingForLive = false
    private var reloading = false

    fun onPaused() { pausedWaitingForLive = true }
    fun onLive() { pausedWaitingForLive = false }
    fun onTrackChanged() { pausedWaitingForLive = false }
    fun onError() { pausedWaitingForLive = false }

    override fun play() { restartAtLiveEdge(); super.play() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) restartAtLiveEdge() else pausedWaitingForLive = true
        super.setPlayWhenReady(playWhenReady)
    }

    private fun restartAtLiveEdge() {
        if (!pausedWaitingForLive || reloading) return
        val item = currentMediaItem ?: return
        pausedWaitingForLive = false
        // En IDLE el player ya carga de cero: no dupliques el trabajo.
        if (playbackState == Player.STATE_IDLE || isPlaying) return
        reloading = true
        try { setMediaItem(item); prepare() } finally { reloading = false }
    }
}
```

   El servicio le avisa con `onPaused()` / `onLive()` / `onTrackChanged()` / `onError()`
   desde su `Player.Listener`. **No se reconecta si el usuario pausó**: si el stream falla
   en pausa, solo se actualiza el estado, no se arranca solo.

2. **Reanudación del sistema.** Implementa `MediaSession.Callback.onPlaybackResumption`
   devolviendo el último canal con `C.TIME_UNSET` (posición por defecto = el vivo). Sin
   esto, el play de la tarjeta de la pantalla de bloqueo no hace nada.

```kotlin
override fun onPlaybackResumption(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val id = sessionState.lastChannelId()
    val channel = channels.firstOrNull { it.id == id }
        ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Sin canal"))
    return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(listOf(mediaItemFor(channel)), 0, C.TIME_UNSET)
    )
}
```

3. **`MediaButtonReceiver` en el manifiesto.** Sin él, Android no puede mandar el play de
   la pantalla de bloqueo cuando la app ya no está viva:

```xml
<receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true">
    <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" /></intent-filter>
</receiver>
```

4. **Video en segundo plano:** en `MainActivity.onStop`, si está reproduciendo, pausa
   (`controller.pause()`). Al volver, el botón dice VER y reanuda **en vivo**. No dejes
   video sonando con la app en segundo plano.

5. **Pantalla encendida mientras se ve:** `playerView.keepScreenOn = isPlaying`.

6. **Audio focus y audífonos:** `AudioAttributes` con
   `CONTENT_TYPE_MOVIE` + `USAGE_MEDIA` y `handleAudioFocus = true`;
   `setHandleAudioBecomingNoisy(true)`.

7. **Errores:** primero probar las URLs alternativas del canal; después reintentar con
   espera 1,5 → 3 → 6 → 12 s (máx. 4); si se agota, estado `UNAVAILABLE` y dejar el botón
   para reintentar a mano.

8. **HLS:** usa `media3-exoplayer-hls`. Para volver al vivo, re-setear el `MediaItem` y
   `prepare()` (el `seekToDefaultPosition` solo no basta si el manifiesto quedó viejo).

9. **Nunca guardes URLs finales con token.** Los proveedores redirigen a una CDN con
   token que caduca en minutos. Guarda siempre la URL de entrada. (En el proyecto de
   radio el robot llegó a guardar la URL final: no repitas ese error.)

10. **Cerrar la app de "recientes":** `onTaskRemoved` solo detiene el servicio si no está
    reproduciendo; el último canal queda guardado para poder reanudar.

---

## 8. Catálogo y robot

`catalog/channels.json`:

```json
{
  "schema": 1,
  "version": 1,
  "channels": [
    {
      "id": "tvperu",
      "name": "TV Perú",
      "tagline": "Canal del Estado",
      "category": "nacional",
      "color": "#C62828",
      "priority": 1,
      "active": true,
      "candidates": [
        { "url": "https://ejemplo.com/tvperu/index.m3u8", "type": "hls" },
        { "url": "https://ejemplo.com/tvperu-alt/index.m3u8", "type": "hls" }
      ],
      "provider": { "kind": "hls", "id": "tvperu" },
      "health": { "fails": 0, "lastOk": "2026-01-01T00:00:00.000Z" }
    }
  ]
}
```

Reglas:

- Las URLs del ejemplo **no sirven**: hay que buscar y verificar las vigentes de cada
  canal. Si no puedes verificar una, **no la inventes**: deja el canal fuera.
- Solo **HLS (`.m3u8`)** o DASH; **nada de YouTube** (ExoPlayer no lo reproduce).
- Mínimo 2 candidatos por canal cuando existan (principal + alternativa).
- La app usa la copia embebida si no hay internet o si el remoto llega corrupto
  (< 3 canales válidos → ignorar).
- La app consulta el remoto al arrancar, como máximo **1 vez cada 15 min**.

`tools/validate.mjs` (Node 20+, sin dependencias):

1. Descarga cada candidato con timeout de 10 s y `User-Agent` propio.
2. Si es `.m3u8`: comprobar `#EXTM3U`, que tenga variantes y que el primer segmento
   responda (200/206). Si devuelve HTML, está roto.
3. Si un candidato falla y hay otro, reordena. Si no queda ninguno, `health.fails++`;
   a los 5 fallos → `active: false`.
4. `--fix` escribe cambios y sube `version`. Sin `--fix` solo informa.
5. **Guardrail:** si falla más de la mitad del catálogo, no toca nada (será su red).

`.github/workflows/catalog.yml`: corre cada 6 h y también a mano (`workflow_dispatch`),
ejecuta `node tools/validate.mjs --fix` y hace commit solo si cambió algo.

Fuente opcional de reemplazos: el proyecto `iptv-org` (respetando su licencia). Nunca
guardes URLs con token.

**Nota legal:** los streams son de terceros. La app es de uso personal/familiar; si algún
día se publica en una tienda, hay que revisar los derechos de cada canal.

---

## 9. Build y firma

**`build.ps1`**: toma el JDK de `..\.toolchain` (o `.\.toolchain`), ejecuta
`gradlew.bat assembleRelease -p <ruta>` y copia el APK a `dist\TelevisionDelPeru-<fecha>-release.apk`.
Si no hay toolchain en el proyecto, copia `X:\APPS CREADAS\Radio\.toolchain` o define
`JAVA_HOME`.

**Keystore (nueva, independiente de la de radio):**

```powershell
keytool -genkeypair -v -keystore keystore\televisiondelperu.jks -alias televisiondelperu `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Television del Peru, O=Television del Peru, L=Lima, C=PE"
```

Crea `keystore/keystore.properties` (nunca a git):

```properties
storeFile=keystore/televisiondelperu.jks
storePassword=...
keyAlias=televisiondelperu
keyPassword=...
```

`.gitignore`: `keystore/`, `local.properties`, `.gradle/`, `build/`, `*.apk`, `.idea/`,
`captures/`, `.toolchain/`.

**Regla:** la firma no se cambia nunca más. Guarda copia de la keystore y de sus
contraseñas fuera del proyecto (sin ella no hay actualizaciones).

---

## 10. Actualizador dentro de la app

Mismo patrón que radio (ya probado):

- `version.json` en la raíz del repo, leído de:
  `https://raw.githubusercontent.com/carpediego/TelevisionDelPeru/main/version.json`
  (respaldo: `https://cdn.jsdelivr.net/gh/carpediego/TelevisionDelPeru@main/version.json`).
- Comprobación **silenciosa 1 vez al día** al abrir; si hay versión nueva aparece un
  chip **Actualizar** al pie.
- **5 toques seguidos** (menos de 3 s) sobre el título → comprueba al instante; si hay
  versión nueva descarga y abre el instalador; si no, avisa "Ya tienes la última versión".
- Etiqueta discreta al pie: `v1.0.0 · catálogo 1`.
- Descarga a `cache/updates/`, verifica `sha256`, y abre el instalador con `FileProvider`
  (`${applicationId}.updates`) + `Intent.ACTION_VIEW` con
  `application/vnd.android.package-archive`.
- Permiso `REQUEST_INSTALL_PACKAGES` en el manifiesto; si falta el permiso de instalar,
  abrir `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` con el paquete.

`version.json`:

```json
{
  "schema": 1,
  "versionCode": 1,
  "versionName": "1.0.0",
  "apkUrl": "https://github.com/carpediego/TelevisionDelPeru/releases/download/v1.0.0/TelevisionDelPeru-1.0.0.apk",
  "sha256": "…",
  "notes": "Primera versión",
  "publishedAt": "2026-11-17"
}
```

`tools/release.ps1`: lee `versionCode`/`versionName` de `app/build.gradle.kts`, compila,
copia el APK a `dist\TelevisionDelPeru-<versión>.apk`, calcula el SHA-256, escribe
`version.json` e imprime los pasos para crear el release.

Publicar: subir versión en Gradle → `.\tools\release.ps1 -Notes "..."` → crear Release
`vX.Y.Z` en GitHub y subir el APK → commit y push de `version.json`.

**Aviso UX:** Android siempre pide confirmar la instalación; la primera vez hay que
permitir "instalar apps de esta fuente" para la app.

---

## 11. Pantalla (UI)

Vertical, una sola pantalla, sin scroll horizontal:

```
┌──────────────────────────────────────┐
│ Televisión del Perú                  │  30sp bold
│ Toca un canal para ver               │  17sp
├──────────────────────────────────────┤
│                                      │
│           VIDEO 16:9                 │  PlayerView, negro, sin controles propios
│                                      │
├──────────────────────────────────────┤
│ TV Perú                              │  21sp bold
│ ● EN VIVO                            │  16sp (verde si en vivo)
│ ┌──────────────────────────────────┐ │
│ │            DETENER               │ │  68dp, 21sp bold
│ └──────────────────────────────────┘ │
│        v1.0.0 · catálogo 1  Actualizar │  12sp / 13sp
├──────────────────────────────────────┤
│ [TP]  TV Perú                        │  lista de canales, filas grandes
│       Canal del Estado               │  21sp / 15sp
│ [AM]  América TV                     │
│ ...                                  │
└──────────────────────────────────────┘
```

- El título (`txtTitle`) recibe el gesto de 5 toques.
- El video, con `PlayerView`: `app:use_controller="false"`, `app:resize_mode="fit"`,
  `app:show_buffering="when_playing"`, ratio 16:9 (ConstraintLayout o altura calculada).
- Cuando no hay canal: en el video se muestra el nombre o monograma, y abajo
  "Elige un canal".
- Colores día: `bg #FFFFFF`, `card #F1F4F8`, `card_active #FFE3E3`,
  `card_stroke_active #C62828`, `text_primary #0E1216`, `text_secondary #4A5560`,
  `accent #C62828`, `live #0F7B3E`, `button #0F7B3E`, `button_text #FFFFFF`.
- Noche: `bg #0F1216`, `card #1A1F25`, `card_active #3A1A1A`,
  `card_stroke_active #FF8A80`, `text_primary #FFFFFF`, `text_secondary #B3BDC7`,
  `accent #FF8A80`, `live #3DD68C`, `button #1E8E56`.
- Contenido de fila: iniciales en un cuadro de color + nombre + tagline + ícono de estado.
- `onBackPressed` → `moveTaskToBack(true)` (nunca cerrar de golpe).

Strings mínimos: `app_name`, `header_title`, `header_subtitle`, `idle_title`,
`idle_hint`, `status_connecting`, `status_live`, `status_paused`, `status_retrying`,
`status_unavailable`, `action_watch`, `action_stop`, `please_wait`, `version_label`
(`v%1$s · catálogo %2$d`), `update_action`, `update_install`, `update_checking`,
`update_up_to_date`, `update_downloading`, `update_available_toast`,
`update_no_connection`, `update_download_failed`, `update_permission_needed`,
`update_install_failed`, `cd_watch`, `cd_stop`.

---

## 12. Trampas ya conocidas

- `buildConfig = false` → la versión se lee con `PackageManager.getPackageInfo`.
- Android 13+: pedir `POST_NOTIFICATIONS` al empezar a reproducir.
- Permisos del manifiesto: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`,
  `REQUEST_INSTALL_PACKAGES`.
- `usesCleartextTraffic="true"` por si algún stream es `http://`.
- Si un canal devuelve 403, prueba `Referer`/`User-Agent` en la factoría de datos.
- En Xiaomi/Redmi el ahorro de batería mata servicios: pedir al usuario que excluya la app.
- `PlayerView` debe soltarse en `onStop` (`playerView.player = null`) antes de liberar el
  `MediaController`.
- No llames `setMediaItem` dentro del listener de errores sin control: usa el servicio,
  con contador y espera.
- El APK crece rápido: no metas SDKs de anuncios, analítica ni reproductores de terceros.
- Prueba siempre con `adb logcat` filtrando `ExoPlayer|MediaSession|PlaybackService`.

---

## 13. Plan de trabajo

| Fase | Entregable | Criterio |
|---|---|---|
| **F1** | Proyecto creado, compila, APK vacío instalable | `.\build.ps1 -Debug` termina y el APK abre |
| **F2** | Catálogo, lista y pantalla | Se ven los canales, con nombres y monogramas |
| **F3** | Reproducción | Tocar un canal reproduce video en vivo; el botón detiene |
| **F4** | Servicio + reanudar en vivo | Pausar 5 min y reanudar vuelve al vivo; pantalla bloqueada funciona |
| **F5** | Actualizador + release + docs | `version.json`, release v1.0.0, README y PLAN |

No pases de fase sin compilar y verificar lo anterior.

---

## 14. Si tienes la carpeta de Radio v4

`X:\APPS CREADAS\Radio\v4` tiene todo esto ya funcionando. Cópiala y adapta:

| Cambiar | De | A |
|---|---|---|
| `settings.gradle.kts` | `rootProject.name = "RadiosDelPeru"` | `"TelevisionDelPeru"` |
| `app/build.gradle.kts` | `namespace`/`applicationId` | `pe.televisiondelperu.app` |
| `app/build.gradle.kts` | versión | `1.0.0` / `1` |
| Dependencias | `media3-exoplayer` | `+ media3-exoplayer-hls`, `media3-ui` |
| `MainActivity`, `StationAdapter` | audio | video con `PlayerView` |
| `player/PlaybackService.kt` | audio | `CONTENT_TYPE_MOVIE`; pausar al salir (`onStop`) |
| `catalog/stations.json` | emisoras | `catalog/channels.json` (canales) |
| `res/` strings y colores | radios | TV, acento rojo |
| `README`, `PLAN`, `version.json`, `tools/release.ps1` | radio | TV (repo y URLs nuevas) |
| Keystore | radio | **nueva** para TV (sección 9) |

Lo que **no** debes cambiar: la estructura del servicio, `LiveChannelPlayer`,
`PlaybackStatus`, `SessionState`, `onPlaybackResumption`, el `MediaButtonReceiver`, el
patrón del actualizador y la forma de compilar/firmar.

---

## 15. Criterios de aceptación

- [ ] `.\build.ps1` genera un APK release firmado en `dist\`.
- [ ] El APK se instala en un teléfono Android 7+ y abre.
- [ ] Tocar un canal reproduce video en vivo en menos de 5 s.
- [ ] Pausar 5 minutos y dar play vuelve **al vivo** (no a lo pausado).
- [ ] Pausar, cerrar la app de recientes y dar play desde la pantalla de bloqueo → suena.
- [ ] Reiniciar el teléfono y dar play desde la tarjeta del sistema → suena.
- [ ] Modo avión durante la reproducción → "Sin conexión. Reintentando…" y se recupera.
- [ ] DETENER corta y no deja nada sonando.
- [ ] Al pie se ve `v1.0.0 · catálogo 1`.
- [ ] 5 toques sobre el título muestran "Ya tienes la última versión (1.0.0)".
- [ ] `version.json` publicado y Release con el APK cuyo `sha256` coincide.
- [ ] README explica instalar, compilar, publicar catálogo y publicar versión.

---

## 16. Prohibido

- WebView o reproductor de terceros.
- Publicidad, analítica, cuentas, login.
- Guardar URLs finales con token.
- Inventar URLs de canales que no hayas verificado.
- Cambiar la keystore después de publicar.
- Instalar en silencio (Android no lo permite; no lo intentes con hacks).
- Pedir permisos que no se usan.

---

## 17. Entorno (Windows)

```powershell
# SDK
"$env:LOCALAPPDATA\Android\Sdk"

# JDK portable (cópialo al proyecto o usa el de Radio)
X:\APPS CREADAS\Radio\.toolchain\jdk-21.0.12.1+1

# Compilar
cd X:\APPS CREADAS\TelevisionDelPeru
.\build.ps1

# Instalar en el teléfono
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\dist\<apk>.apk"

# Ver errores
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat | Select-String "ExoPlayer|MediaSession|PlaybackService"
```

Notas de arranque:

- Copia el JDK: `Copy-Item -Recurse X:\APPS CREADAS\Radio\.toolchain X:\APPS CREADAS\TelevisionDelPeru\.toolchain`
  (o ajusta `build.ps1` para buscarlo en `..\Radio\.toolchain`).
- El repo de GitHub puede no existir todavía: créalo con el token del usuario o deja el
  proyecto listo y documenta `git remote add origin https://github.com/carpediego/TelevisionDelPeru.git`
  para empujarlo después.
- Crea `local.properties` con `sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk`
  (o deja que Android Studio lo genere).

Empieza por la fase F1 y no pidas confirmación para avanzar: este documento ya define el
qué y el cómo. Si algo del entorno falta (SDK, JDK, teléfono), resuélvelo o déjalo
documentado, pero entrega el proyecto compilando.
