# Televisión del Perú — Plan de ejecución

**App Android de TV peruana en vivo · una pantalla · para personas mayores · sin publicidad**

Documento de trabajo. Fecha: 17/11/2026 · Estado: **F1–F3 completadas y compiladas;
F4 pendiente de pruebas en teléfono real; F5 pendiente de publicar en GitHub.**

---

## 1. Decisiones cerradas

| Tema | Decisión |
|---|---|
| Nombre visible | **Televisión del Perú** |
| Package / applicationId | `pe.televisiondelperu.app` |
| Distribución | **APK directo** (sin Play Store por ahora) |
| SDK | `minSdk 24`, `compileSdk 36`, `targetSdk 36` |
| Kotlin / AGP / Gradle | `2.4.21` / `8.13.2` / `8.14.3` |
| JDK | 21 (portable, sin permisos de administrador) |
| Reproductor | Media3 ExoPlayer + MediaSessionService (HLS) |
| UI | Vistas XML + RecyclerView (sin Compose) |
| Catálogo | JSON remoto en GitHub + copia embebida |
| Firma | Keystore nueva, fija, respaldada fuera del proyecto |
| Carpeta local | `X:\APPS CREADAS\Televisión` (repo `TelevisionDelPeru`) |

La carpeta local lleva tilde. AGP avisa de que las rutas no ASCII pueden fallar en
Windows; se añadió `android.overridePathCheck=true` a `gradle.properties` y se compiló
sin incidencias con JDK 21. En GitHub el repo se llama `TelevisionDelPeru` y la ruta no
tiene tilde.

---

## 2. Estado de ejecución

| Fase | Estado | Resultado |
|---|---|---|
| **F1** Proyecto y compilación | ✅ hecho | Proyecto Gradle creado, `.\build.ps1` compila release firmado. |
| **F2** Catálogo, lista y pantalla | ✅ hecho | 20 canales, monogramas, colores día/noche, video 16:9, botón grande. |
| **F3** Reproducción | ✅ hecho | ExoPlayer HLS en el servicio, cambio de canal, detener, estados. |
| **F4** Servicio + reanudar en vivo | 🟢 probado en emulador | Reintentos, URLs alternativas, `onPlaybackResumption`, `MediaButtonReceiver`, pausa al salir y vigilante de conexiones colgadas. Falta probarlo en teléfono real. |
| **F5** Actualizador + release + docs | 🟡 casi | `version.json`, `tools/release.ps1`, APK firmado y README/PLAN listos. Falta crear el repo y publicar el Release v1.0.0. |

---

## 3. Entorno verificado

| Elemento | Valor |
|---|---|
| JDK | Temurin 21.0.12.1+1 en `X:\APPS CREADAS\Radio\.toolchain` (el `build.ps1` lo busca ahí) |
| Gradle | 8.14.3 (wrapper, distribución ya cacheada) |
| SDK | Android 36 · build-tools 36.0.0 / 36.1.0 |
| Node | 26.1.0 para `tools/validate.mjs` |
| Git | 2.54.0, sin `gh` CLI |
| Red | `dl.google.com`, `repo1.maven.org`, `raw.githubusercontent.com` accesibles |

No hay teléfono conectado por `adb` en este entorno: las pruebas de reproducción real
quedan pendientes y están listadas en §7.

---

## 4. Catálogo verificado (20 canales)

Todos los candidatos se probaron el 17/11/2026 con `tools/validate.mjs`: manifiesto
`#EXTM3U`, variante y primer/último segmento con HTTP 200/206 y contenido binario.

| # | Canal | Categoría | Candidatos | Fuente |
|---|---|---|---|---|
| 1 | TV Perú | nacional | 3 | directo + Cablered + IPTV |
| 2 | América TV | nacional | 1 | directo |
| 3 | Latina TV | nacional | 3 | directo + rudo.video + Cablered |
| 4 | ATV | nacional | 2 | directo + Cablered |
| 5 | Panamericana TV | nacional | 3 | directo ×3 |
| 6 | Canal N | noticias | 1 | directo |
| 7 | Willax TV | nacional | 2 | directo ×2 |
| 8 | Exitosa TV | nacional | 2 | directo ×2 |
| 9 | TV Perú Noticias | noticias | 1 | directo |
| 10 | RPP TV | noticias | 3 | directo + rudo.video + Bitel |
| 11 | Global TV | nacional | 3 | Cablered + Bitel + directo |
| 12 | Latina Noticias 24/7 | noticias | 2 | Google DAI + rudo.video |
| 13 | La Tele | nacional | 2 | directo + Cablered |
| 14 | Canal IPe | nacional | 1 | directo |
| 15 | Agro TV | regional | 1 | Bitel |
| 16 | Ucayalina de Televisión | regional | 1 | Bitel |
| 17 | Qosqo Times | regional | 1 | Bitel |
| 18 | TV Norte Chiclayo | regional | 1 | Bitel |
| 19 | Cajamarca TV | regional | 1 | Bitel |
| 20 | Andes TV | regional | 1 | Bitel |

Notas:

- Los streams son de terceros. Algunos son restreams públicos; no son canales oficiales
  y pueden cambiar sin aviso. El robot los vigila cada 6 horas.
- Se descartaron Canal N por una URL que devolvía 403 y se recuperó por otra; Justicia
  TV y ATV+ quedaron fuera para respetar el máximo de 20 canales.
- Ninguna URL guardada lleva token de sesión: solo la dirección de entrada.

---

## 5. Arquitectura implementada

```
MainActivity (dibuja estado + PlayerView + actualizador)
      │  MediaController
      ▼
PlaybackService : MediaSessionService
      │  ExoPlayer + LiveChannelPlayer
      ├─ reintentos 1,5 → 3 → 6 → 12 s (máx. 4)
      ├─ rotación de candidatos
      ├─ SessionState (último canal)
      └─ onPlaybackResumption (tarjeta del sistema)
      │
CatalogRepository → assets/channels.json + caché + raw.githubusercontent/jsDelivr
```

### Reglas que se implementaron

1. `LiveChannelPlayer` reconecta al directo tras una pausa (`setMediaItem` + `prepare`).
2. `onPlaybackResumption` devuelve el último canal con `C.TIME_UNSET`.
3. `MediaButtonReceiver` declarado en el manifiesto.
4. `MainActivity.onStop` pausa y suelta el `PlayerView` antes de liberar el controlador.
5. `playerView.keepScreenOn` solo mientras está en vivo.
6. `AudioAttributes` `CONTENT_TYPE_MOVIE` + `USAGE_MEDIA` y `setHandleAudioBecomingNoisy`.
7. Errores: candidatos → espera creciente → `UNAVAILABLE`.
8. HLS con `media3-exoplayer-hls`; `MediaItem` con `MimeTypes.APPLICATION_M3U8`.
9. Nunca se guardan URLs finales con token.
10. `onTaskRemoved` solo cierra el servicio si no está reproduciendo.
11. Vigilante cada 3 s: si la conexión lleva más de 30 s sin resolver, se trata como
    error y entra la cadena de candidatos/reintentos (evita quedarse en «Conectando…»).

---

## 6. Verificación hecha

| Comprobación | Resultado |
|---|---|
| `node tools/validate.mjs` | 20/20 canales OK (manifiesto + variante + segmento) |
| `.\build.ps1` | APK release firmado: `dist\TelevisionDelPeru-1.0.0.apk` · 1,97 MB |
| Firma | Keystore nueva en `keystore\televisiondelperu.jks` · SHA-256 `65DF9FBF…02BF7805` |
| `sha256` del APK | `53cc5f836bfc927db485e375c364f9da00f77976565ad4b48532b02cbceed7b2` |
| Verificación de firma | `apksigner verify` → esquema v2, 1 firmante, DN correcto |
| Catálogo embebido | APK incluye `assets/channels.json` (12.517 bytes, misma copia) |
| Permisos | Los 7 del documento + permiso interno de AndroidX (no visible al usuario) |

### Probado en emulador Android 15 (API 35)

| Prueba | Resultado |
|---|---|
| Instalar el APK y abrir | ✅ abre en la pantalla única, sin crashes |
| Tocar TV Perú | ✅ video HLS en vivo en menos de 5 s |
| DETENER | ✅ corta, vuelve a “Elige un canal” y la sesión queda en NONE |
| Home (salir de la app) | ✅ pausa; al volver el botón dice VER |
| VER después de la pausa | ✅ reconecta al directo (BUFFERING → PLAYING) |
| Matar el proceso y mandar play del sistema | ✅ el servicio renace y reanuda el último canal |
| Modo avión al iniciar | ✅ “Sin conexión. Reintentando…” y recuperación al volver la red |
| Notificación de reproducción | ✅ MediaStyle con TV Perú, Pausa y controles |
| 5 toques sobre el título | ✅ muestra “Buscando actualización…” |
| Vigilante de conexión colgada | ✅ a los 30 s fuerza un reintento si el player no responde |

### Por verificar en teléfono real

- [ ] Instalar el APK en Android 7+ y abrir.
- [ ] Tocar un canal reproduce video en menos de 5 s.
- [ ] Pausar 5 min y dar play vuelve al vivo.
- [ ] Pausar, cerrar de recientes y dar play desde la pantalla de bloqueo.
- [ ] Reiniciar el teléfono y dar play desde la tarjeta del sistema.
- [ ] Modo avión durante la reproducción → “Sin conexión. Reintentando…” y recuperación.
- [ ] DETENER corta y no deja nada sonando.
- [ ] Al pie se ve `v1.0.0 · catálogo 1`.
- [ ] 5 toques sobre el título → “Ya tienes la última versión (v1.0.0)”.
- [ ] Probar en Xiaomi/Redmi y Samsung el ahorro de batería.

---

## 7. Publicación pendiente

El código no tiene credenciales de GitHub en este entorno (`gh` no está instalado y no
hay `origin`). Para publicar:

```powershell
cd "X:\APPS CREADAS\Televisión"
git init
git add .
git commit -m "Television del Peru 1.0.0"
git remote add origin https://github.com/carpediego/TelevisionDelPeru.git
git branch -M main
git push -u origin main
```

Después:

1. Crear el Release `v1.0.0` y subir `dist\TelevisionDelPeru-1.0.0.apk`.
2. Verificar que `version.json` tenga la URL y el `sha256` correctos.
3. Activar *Actions → Catalogo* (necesita `permissions: contents: write`, ya está).
4. Instalar el APK en el teléfono y completar la lista de §6.

> El `version.json` generado apunta a
> `https://github.com/carpediego/TelevisionDelPeru/releases/download/v1.0.0/TelevisionDelPeru-1.0.0.apk`.
> Mientras el Release no exista, el actualizador responderá 404 (la app sigue funcionando
> con el catálogo embebido y muestra “No se pudo comprobar si hay actualización” solo si
> se fuerza con los 5 toques).

---

## 8. Riesgos y mitigaciones

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Un stream cambia de URL | Alta | 2–3 candidatos por canal + robot cada 6 h + catálogo remoto. |
| Stream público cae definitivamente | Media | Se oculta a los 5 fallos (`active: false`) sin publicar APK. |
| URL final con token | Alta | Solo se guarda la entrada; el validador nunca escribe URLs resueltas. |
| Android mata el servicio (MIUI) | Media | Foreground service correcto; documentar exclusión de batería. |
| Pérdida de la keystore | Baja pero grave | Copia en `keystore\` + `CREDENCIALES.txt`; respaldar fuera del proyecto. |
| Ruta con tilde en Windows | Baja | `android.overridePathCheck=true`; build verificado. |

---

## 9. Próximo paso

1. Probar el APK en un teléfono real (lista de §6).
2. Publicar el repo y el Release v1.0.0.
3. Si algún canal falla en el teléfono, corregir su URL en `catalog/channels.json` y
   hacer commit: la app lo recoge sola, sin recompilar.
