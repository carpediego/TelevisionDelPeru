package pe.televisiondelperu.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import pe.televisiondelperu.app.MainActivity
import pe.televisiondelperu.app.catalog.CatalogRepository
import pe.televisiondelperu.app.catalog.Channel

/**
 * Servicio de reproducción.
 *
 * MediaSessionService se encarga del servicio en primer plano, la notificación con
 * controles, los controles de la pantalla de bloqueo y la integración con Bluetooth.
 *
 * Aquí también vive todo lo que debe funcionar con la pantalla apagada:
 *  - reanudar siempre **en vivo** (delegado en [LiveChannelPlayer]);
 *  - probar las URLs alternativas del canal;
 *  - reintentar con espera cuando se cae la conexión;
 *  - recordar el último canal para poder reanudarlo aunque el proceso ya no exista.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var livePlayer: LiveChannelPlayer
    private lateinit var sessionState: SessionState
    private lateinit var catalog: CatalogRepository

    private var channels: List<Channel> = emptyList()
    private var channel: Channel? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var candidateIndex = 0
    private var retryCount = 0

    /** Momento en que se empezó a abrir el canal actual; 0 si ya está resuelto. */
    private var connectionStartedAt = 0L

    /** Estado "de espera" que no se puede deducir del reproductor: reintentando o caída. */
    private var pending: PlaybackStatus.Phase? = null

    /**
     * Hubo alguna vez un canal cargado. Sirve para no confundir "el usuario pulsó
     * DETENER" (hay que olvidar el canal) con "el servicio acaba de arrancar y aún
     * no hay nada" (hay que recordarlo para poder reanudar).
     */
    private var hasLoadedChannel = false

    /**
     * El usuario pausó a propósito. Sirve para no reconectar solos si el stream falla
     * mientras está en pausa: sonaría sin que nadie lo haya pedido.
     */
    private var pausedByUser = false

    /**
     * Vigila las conexiones que se quedan colgadas.
     *
     * ExoPlayer normalmente avisa con onPlayerError, pero una conexión puede quedarse
     * en BUFFERING sin responder (por ejemplo al volver de modo avión). Sin este
     * vigilante la pantalla se quedaría en «Conectando…» para siempre. A los 30 s se
     * trata como error y entra la cadena normal de candidatos y reintentos.
     */
    private val connectionWatchdog = object : Runnable {
        override fun run() {
            val current = channel
            val waitingForConnection = current != null &&
                !pausedByUser &&
                pending == null &&
                player.playWhenReady &&
                player.playbackState != Player.STATE_READY &&
                !player.isPlaying

            if (waitingForConnection && connectionStartedAt > 0L &&
                SystemClock.elapsedRealtime() - connectionStartedAt > CONNECT_WATCHDOG_MS
            ) {
                connectionStartedAt = 0L
                cancelRetry()
                onPlaybackError()
            }

            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        sessionState = SessionState(this)
        catalog = CatalogRepository(this)
        channels = catalog.load().channels

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Un canal de TV no debe sonar solo: el video se pausa al salir de la app
        // (lo hace MainActivity) y, si el stream falla en pausa, tampoco se reconecta.
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)

        livePlayer = LiveChannelPlayer(player)
        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, livePlayer)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback())
            // En Android 12+ el sistema a veces impide arrancar en segundo plano. Si pasa,
            // al menos el botón de play queda a la vista para reintentar desde la app.
            .setShowPlayButtonIfPlaybackIsSuppressed(true)
            .build()

        handler.postDelayed(connectionWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // ------------------------------------------------------------ eventos player

    private val playerListener = object : Player.Listener {

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                pausedByUser = false
                connectionStartedAt = SystemClock.elapsedRealtime()
                // Si veníamos de "no disponible", pulsar play es un reintento manual.
                // No basta con play(): el reproductor quedó en IDLE tras el error, así
                // que hay que volver a cargar el canal.
                if (pending == PlaybackStatus.Phase.UNAVAILABLE) {
                    pending = null
                    candidateIndex = 0
                    retryCount = 0
                    val target = channel
                    if (target != null) {
                        handler.post {
                            if (player.playWhenReady && channel?.id == target.id) {
                                startChannel(target, 0)
                            }
                        }
                    }
                }
            } else {
                livePlayer.onPaused()
                connectionStartedAt = 0L
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    pausedByUser = true
                }
            }
            refreshStatus()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                livePlayer.onLive()
                pausedByUser = false
                retryCount = 0
                pending = null
                connectionStartedAt = 0L
            }
            refreshStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Si llegó a READY, la conexión se resolvió: el vigilante ya no tiene que
            // contar ese intento. Un buffering posterior se detectará con el siguiente.
            if (playbackState == Player.STATE_READY) connectionStartedAt = 0L
            // No se limpia `pending` aquí: un evento de buffering tardío borraría el
            // estado RETRYING y la pantalla volvería a decir "Conectando…". El estado
            // lo limpian startChannel(), onIsPlayingChanged() y el cambio de canal.
            refreshStatus()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            livePlayer.onTrackChanged()
            val id = mediaItem?.mediaId

            if (id != channel?.id) {
                // Si había un reintento programado para el canal anterior, se cancela:
                // de lo contrario volvería a arrancar el canal viejo unos segundos después.
                cancelRetry()
                channel = channels.firstOrNull { it.id == id }
                candidateIndex = 0
                retryCount = 0
                pending = null
            }

            if (id != null) {
                hasLoadedChannel = true
                sessionState.saveChannel(id)
            } else if (hasLoadedChannel) {
                // El usuario pulsó DETENER: sí queremos olvidar el canal.
                hasLoadedChannel = false
                pausedByUser = false
                connectionStartedAt = 0L
                sessionState.clear()
            }
            refreshStatus()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (player.mediaItemCount == 0 && hasLoadedChannel) {
                cancelRetry()
                hasLoadedChannel = false
                channel = null
                pending = null
                sessionState.clear()
                refreshStatus()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            connectionStartedAt = 0L
            onPlaybackError()
        }
    }

    private fun refreshStatus() {
        val current = channel
        val phase: PlaybackStatus.Phase = when {
            current == null || player.currentMediaItem == null -> PlaybackStatus.Phase.IDLE
            pending != null -> pending as PlaybackStatus.Phase
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Phase.CONNECTING
            player.isPlaying -> PlaybackStatus.Phase.LIVE
            player.playWhenReady -> PlaybackStatus.Phase.CONNECTING
            else -> PlaybackStatus.Phase.PAUSED
        }

        PlaybackStatus.update(
            PlaybackStatus.State(
                channelId = if (phase == PlaybackStatus.Phase.IDLE) null else current?.id,
                phase = phase
            )
        )
    }

    // -------------------------------------------------------------- reintentos

    private fun onPlaybackError() {
        val current = channel ?: return
        cancelRetry()
        livePlayer.onError()

        // En pausa no se reconecta solo: el usuario no ha pedido que se vea.
        if (pausedByUser) {
            pending = null
            refreshStatus()
            return
        }

        // 1. Otra URL del mismo canal.
        if (candidateIndex < current.candidates.lastIndex) {
            candidateIndex++
            retryCount = 0
            startChannel(current, candidateIndex)
            return
        }

        // 2. Reintentos con espera creciente.
        if (retryCount >= MAX_RETRIES) {
            pending = PlaybackStatus.Phase.UNAVAILABLE
            refreshStatus()
            return
        }

        val delayMs = BACKOFF_MS[retryCount.coerceAtMost(BACKOFF_MS.lastIndex)]
        retryCount++
        pending = PlaybackStatus.Phase.RETRYING
        refreshStatus()

        val runnable = Runnable {
            retryRunnable = null
            startChannel(current, candidateIndex)
        }
        retryRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun startChannel(target: Channel, index: Int) {
        val item = mediaItemFor(target, index) ?: return
        pending = null
        hasLoadedChannel = true
        player.setMediaItem(item)
        player.prepare()
        player.play()
        connectionStartedAt = SystemClock.elapsedRealtime()
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    // ------------------------------------------------------- reanudar el sistema

    /**
     * Android llama aquí cuando el usuario pulsa play en la tarjeta de la pantalla de
     * bloqueo y la sesión ya no tenía nada cargado (app cerrada, teléfono reiniciado o
     * servicio detenido). Devolvemos el último canal; Media3 lo prepara y lo arranca.
     */
    private inner class SessionCallback : MediaSession.Callback {

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        // La variante nueva (con `isForPlayback`) todavía es @UnstableApi.
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Si el usuario pulsó DETENER, no hay nada que reanudar: mejor un no-op
            // que arrancar un canal que nadie eligió.
            val target = sessionState.lastChannelId()
                ?.let { id -> channels.firstOrNull { it.id == id } }
                ?: return Futures.immediateFailedFuture(
                    UnsupportedOperationException("No hay canal recordado")
                )

            val item = mediaItemFor(target, 0)
                ?: return Futures.immediateFailedFuture(
                    IllegalStateException("Canal sin URL: ${target.id}")
                )

            channel = target
            hasLoadedChannel = true
            candidateIndex = 0
            retryCount = 0
            pending = null
            sessionState.saveChannel(target.id)

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(item),
                    0,
                    C.TIME_UNSET // posición por defecto = el directo, ahora
                )
            )
        }
    }

    // ---------------------------------------------------------------- ciclo vida

    /**
     * Si el usuario desliza la app fuera de "recientes" con el video en pausa, el
     * servicio se cierra y Android deja la tarjeta de reanudación. Si todavía se está
     * reproduciendo, se mantiene (el video solo sigue con la pantalla encendida).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelRetry()
        handler.removeCallbacks(connectionWatchdog)
        player.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // Si el servicio se va, la pantalla no debe quedarse mostrando "EN VIVO".
        PlaybackStatus.update(PlaybackStatus.State())
        super.onDestroy()
    }

    companion object {

        private const val MAX_RETRIES = 4
        private val BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L)
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val CONNECT_WATCHDOG_MS = 30_000L

        /** El mismo MediaItem que usa la pantalla y la reanudación del sistema. */
        fun mediaItemFor(channel: Channel, candidateIndex: Int = 0): MediaItem? {
            val candidate = channel.candidates.getOrNull(candidateIndex) ?: return null
            val builder = MediaItem.Builder()
                .setUri(candidate.url)
                .setMediaId(channel.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(channel.name)
                        .setArtist(channel.tagline)
                        .setIsPlayable(true)
                        .build()
                )

            when (candidate.type.lowercase()) {
                "hls" -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                "dash" -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            }

            return builder.build()
        }
    }
}
