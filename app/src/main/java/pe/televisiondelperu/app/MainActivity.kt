package pe.televisiondelperu.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import pe.televisiondelperu.app.catalog.CatalogRepository
import pe.televisiondelperu.app.catalog.Channel
import pe.televisiondelperu.app.player.PlaybackService
import pe.televisiondelperu.app.player.PlaybackStatus
import pe.televisiondelperu.app.ui.ChannelAdapter
import pe.televisiondelperu.app.update.UpdateInfo
import pe.televisiondelperu.app.update.Updater
import java.io.File

/**
 * Pantalla única.
 *
 * No manda en la reproducción: el que decide (y el que reintenta) es
 * [PlaybackService], porque tiene que funcionar también con la pantalla bloqueada.
 * Aquí solo se pinta el estado que publica el servicio, se dibuja el video y se
 * envían las órdenes.
 *
 * También vive aquí el actualizador: etiqueta de versión al pie, "Actualizar" cuando
 * hay versión nueva y el gesto de 5 toques sobre el título para forzarla.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChannelAdapter
    private lateinit var txtNowTitle: TextView
    private lateinit var txtNowStatus: TextView
    private lateinit var txtVersion: TextView
    private lateinit var txtUpdate: TextView
    private lateinit var btnAction: Button
    private lateinit var playerView: PlayerView
    private lateinit var videoPlaceholder: View
    private lateinit var txtPlaceholder: TextView
    private lateinit var txtPlaceholderName: TextView
    private lateinit var rootView: View
    private lateinit var header: View
    private lateinit var infoPanel: View
    private lateinit var channelList: RecyclerView
    private lateinit var videoFrame: View
    private lateinit var btnFullscreen: View
    private lateinit var btnExitFullscreen: View

    private var isFullscreen = false
    private var normalVideoHeight = 0

    /** Oculta el botón SALIR solo si el usuario no vuelve a tocar el video. */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideFullscreenExit = Runnable {
        btnExitFullscreen.visibility = View.GONE
    }

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var channels: List<Channel> = emptyList()
    private var catalogVersion = 0

    private val repository by lazy { CatalogRepository(this) }
    private val updater by lazy { Updater(this) }

    private var pendingUpdate: UpdateInfo? = null
    private var pendingApk: File? = null
    private var downloading = false

    private var taps = 0
    private var firstTapAt = 0L

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

    private val onStatusChanged: (PlaybackStatus.State) -> Unit = { state ->
        render(state)
    }

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
            render(PlaybackStatus.current)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            playerView.keepScreenOn = controller?.isPlaying == true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        rootView = root
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Si la actividad se recrea (por ejemplo tras morir el proceso), se vuelve
        // siempre al modo normal vertical, nunca a una pantalla completa huérfana.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())

        txtNowTitle = findViewById(R.id.txtNowTitle)
        txtNowStatus = findViewById(R.id.txtNowStatus)
        txtVersion = findViewById(R.id.txtVersion)
        txtUpdate = findViewById(R.id.btnUpdate)
        btnAction = findViewById(R.id.btnStop)
        playerView = findViewById(R.id.playerView)
        videoPlaceholder = findViewById(R.id.videoPlaceholder)
        txtPlaceholder = findViewById(R.id.txtPlaceholder)
        txtPlaceholderName = findViewById(R.id.txtPlaceholderName)
        header = findViewById(R.id.header)
        infoPanel = findViewById(R.id.infoPanel)
        videoFrame = findViewById(R.id.videoFrame)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnExitFullscreen = findViewById(R.id.btnExitFullscreen)

        setupVideoSize()

        adapter = ChannelAdapter { channel -> onChannelClicked(channel) }
        channelList = findViewById(R.id.rvChannels)
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = adapter
        channelList.itemAnimator = null

        val snapshot = repository.load()
        channels = snapshot.channels
        catalogVersion = snapshot.version
        adapter.submit(channels)

        // El catálogo remoto se consulta en segundo plano; si hay versión nueva, se
        // refresca la lista sin molestar al usuario y sin cortar el video.
        repository.refreshAsync { fresh -> applyCatalog(fresh) }

        btnAction.setOnClickListener { onActionClicked() }
        btnFullscreen.setOnClickListener { enterFullscreen() }
        btnExitFullscreen.setOnClickListener { exitFullscreen() }
        // Tocar el video lo pone en pantalla completa. Dentro de pantalla completa,
        // un toque muestra u oculta el botón SALIR; no se queda fijo todo el tiempo.
        // El PlayerView consume los toques, por eso el gesto también se escucha aquí.
        playerView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (isFullscreen) {
                        toggleFullscreenExit()
                    } else if (PlaybackStatus.current.channelId != null) {
                        enterFullscreen()
                    }
                }
            }
            true
        }
        videoFrame.setOnClickListener {
            if (isFullscreen) {
                toggleFullscreenExit()
            } else if (PlaybackStatus.current.channelId != null) {
                enterFullscreen()
            }
        }

        // Actualizador: una comprobación al día, chip "Actualizar" solo si hay novedad
        // y 5 toques sobre el título como atajo para forzarla.
        updater.listener = updateListener
        findViewById<View>(R.id.txtTitle).setOnClickListener { onTitleTapped() }
        txtUpdate.setOnClickListener { onUpdateClicked() }
        renderVersionLabel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // En pantalla completa, atrás primero sale de ella. Nunca cerramos
                // de golpe: fuera de pantalla completa la app se minimiza y el video
                // se pausa.
                if (isFullscreen) exitFullscreen() else moveTaskToBack(true)
            }
        })

        render(PlaybackStatus.current)
    }

    override fun onStart() {
        super.onStart()
        PlaybackStatus.addListener(onStatusChanged)
        connectController()
        // Como mucho una vez al día; si ya se comprobó hoy, no hace nada.
        updater.check(forced = false)
    }

    override fun onStop() {
        PlaybackStatus.removeListener(onStatusChanged)

        // Un video no debe seguir sonando con la app en segundo plano: se pausa y, al
        // volver, el botón dice VER y reanuda en vivo (LiveChannelPlayer).
        controller?.let { c ->
            if (c.isPlaying || c.playWhenReady) c.pause()
            c.removeListener(controllerListener)
        }

        // El PlayerView debe soltar el reproductor antes de liberar el MediaController.
        playerView.player = null
        playerView.keepScreenOn = false

        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        uiHandler.removeCallbacks(hideFullscreenExit)
        btnExitFullscreen.visibility = View.GONE
        super.onStop()
    }

    override fun onDestroy() {
        updater.listener = null
        uiHandler.removeCallbacks(hideFullscreenExit)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // El giro a horizontal puede volver a mostrar las barras del sistema.
        if (isFullscreen) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
            rootView.setPadding(0, 0, 0, 0)
            rootView.setBackgroundColor(Color.BLACK)
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            extendIntoDisplayCutout(true)
        }
    }

    /**
     * El video ocupa el ancho de la pantalla con proporción 16:9. Se calcula en píxeles
     * para que quepa también en pantallas pequeñas y en tablets.
     */
    private fun setupVideoSize() {
        val metrics = resources.displayMetrics
        val byWidth = metrics.widthPixels * 9 / 16
        val byHeight = (metrics.heightPixels * 0.46f).toInt()
        val height = minOf(byWidth, byHeight).coerceAtLeast(dp(140))
        normalVideoHeight = height
        videoFrame.layoutParams = videoFrame.layoutParams.apply { this.height = height }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun connectController() {
        if (controllerFuture != null) return

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                MediaController.releaseFuture(future)
                return@addListener
            }

            controller = try {
                future.get()
            } catch (_: Exception) {
                null
            }

            controller?.let { c ->
                c.addListener(controllerListener)
                playerView.player = c
                playerView.keepScreenOn = c.isPlaying
            }
            render(PlaybackStatus.current)
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------ actualizador

    /** Cinco toques seguidos sobre el título (menos de 3 s entre el primero y el último). */
    private fun onTitleTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - firstTapAt > TAPS_WINDOW_MS) {
            taps = 0
            firstTapAt = now
        }
        taps++
        if (taps >= TAPS_REQUIRED) {
            taps = 0
            firstTapAt = 0L
            updater.check(forced = true)
        }
    }

    private fun onUpdateClicked() {
        if (downloading) return

        val apk = pendingApk
        if (apk != null && apk.exists()) {
            updater.install(apk)
            return
        }
        // Si Android limpió la caché, se vuelve a descargar en vez de quedarse trabado.
        pendingApk = null
        pendingUpdate?.let { startDownload(it) }
    }

    private fun startDownload(info: UpdateInfo) {
        if (downloading) return
        downloading = true
        txtUpdate.visibility = View.VISIBLE
        txtUpdate.text = getString(R.string.update_downloading, 0)
        updater.download(info)
    }

    private fun renderVersionLabel() {
        val installed = updater.installedVersionName
        txtVersion.text = if (catalogVersion > 0) {
            getString(R.string.version_label, installed, catalogVersion)
        } else {
            getString(R.string.version_label_short, installed)
        }
    }

    private val updateListener = object : Updater.Listener {

        override fun onChecking(forced: Boolean) {
            if (!forced) return
            Toast.makeText(this@MainActivity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }

        override fun onUpToDate(forced: Boolean, installedVersion: String) {
            // Solo hablamos si el usuario lo pidió con los 5 toques.
            if (!forced) return
            Toast.makeText(
                this@MainActivity,
                getString(R.string.update_up_to_date, installedVersion),
                Toast.LENGTH_SHORT
            ).show()
        }

        override fun onUpdateAvailable(info: UpdateInfo, forced: Boolean) {
            pendingUpdate = info
            txtUpdate.visibility = View.VISIBLE
            txtUpdate.text = getString(R.string.update_action)

            if (forced) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_available_toast, info.versionName),
                    Toast.LENGTH_SHORT
                ).show()
                startDownload(info)
            }
        }

        override fun onDownloading(percent: Int) {
            txtUpdate.visibility = View.VISIBLE
            txtUpdate.text = getString(R.string.update_downloading, percent)
        }

        override fun onReady(file: File) {
            downloading = false
            pendingApk = file
            txtUpdate.visibility = View.VISIBLE
            txtUpdate.text = getString(R.string.update_install)
            updater.install(file)
        }

        override fun onError(message: String) {
            downloading = false
            txtUpdate.visibility = if (pendingUpdate != null) View.VISIBLE else View.GONE
            txtUpdate.text = getString(R.string.update_action)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }

        override fun onInstallPermissionNeeded() {
            Toast.makeText(
                this@MainActivity,
                R.string.update_permission_needed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------------------------------------------------------- acciones

    /**
     * El único botón grande. Con el mismo botón se ve, se detiene y se reanuda:
     *  - reproduciendo / conectando → DETENER (corta del todo y quita la tarjeta);
     *  - en pausa                  → VER (vuelve al directo, en el momento actual).
     */
    private fun onActionClicked() {
        val c = controller ?: run {
            Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()
            return
        }

        when (PlaybackStatus.current.phase) {
            PlaybackStatus.Phase.PAUSED,
            PlaybackStatus.Phase.UNAVAILABLE -> c.play()

            PlaybackStatus.Phase.LIVE,
            PlaybackStatus.Phase.CONNECTING,
            PlaybackStatus.Phase.RETRYING -> stopPlayback(c)

            PlaybackStatus.Phase.IDLE -> Unit
        }
    }

    private fun onChannelClicked(channel: Channel) {
        val c = controller ?: run {
            Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()
            return
        }

        val state = PlaybackStatus.current
        if (state.channelId != channel.id) {
            playChannel(c, channel)
            return
        }

        when (state.phase) {
            PlaybackStatus.Phase.LIVE,
            PlaybackStatus.Phase.CONNECTING,
            PlaybackStatus.Phase.RETRYING -> stopPlayback(c)

            PlaybackStatus.Phase.PAUSED,
            PlaybackStatus.Phase.UNAVAILABLE -> c.play()

            PlaybackStatus.Phase.IDLE -> playChannel(c, channel)
        }
    }

    private fun playChannel(c: MediaController, channel: Channel) {
        prepareNotificationPermission()

        val item = PlaybackService.mediaItemFor(channel) ?: return
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun stopPlayback(c: MediaController) {
        c.stop()
        c.clearMediaItems()
        playerView.keepScreenOn = false
        // El servicio ve la lista vacía, borra el canal recordado y deja el estado en reposo.
    }

    // ---------------------------------------------------------------- estado UI

    private fun render(state: PlaybackStatus.State) {
        val channel = state.channelId?.let { id -> channels.firstOrNull { it.id == id } }

        if (channel == null || state.phase == PlaybackStatus.Phase.IDLE) {
            adapter.setActive(null)
            renderIdle()
            return
        }

        adapter.setActive(
            if (state.phase == PlaybackStatus.Phase.UNAVAILABLE) null else channel.id
        )
        btnFullscreen.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        txtNowTitle.text = channel.name
        btnAction.visibility = View.VISIBLE
        btnAction.text = when (state.phase) {
            PlaybackStatus.Phase.PAUSED,
            PlaybackStatus.Phase.UNAVAILABLE -> getString(R.string.action_watch)

            else -> getString(R.string.action_stop)
        }

        when (state.phase) {
            PlaybackStatus.Phase.CONNECTING -> showStatus(
                getString(R.string.status_connecting),
                colorRes = R.color.live
            )

            PlaybackStatus.Phase.LIVE -> showStatus(
                getString(R.string.status_live),
                colorRes = R.color.live
            )

            PlaybackStatus.Phase.PAUSED -> showStatus(
                getString(R.string.status_paused),
                colorRes = R.color.text_secondary
            )

            PlaybackStatus.Phase.RETRYING -> showStatus(
                getString(R.string.status_retrying),
                colorRes = R.color.text_secondary
            )

            PlaybackStatus.Phase.UNAVAILABLE -> showStatus(
                getString(R.string.status_unavailable),
                colorRes = R.color.text_secondary
            )

            PlaybackStatus.Phase.IDLE -> renderIdle()
        }

        updateVideoOverlay(channel, state.phase)
    }

    private fun applyCatalog(snapshot: CatalogRepository.Snapshot) {
        channels = snapshot.channels
        catalogVersion = snapshot.version
        adapter.submit(channels)
        renderVersionLabel()
        render(PlaybackStatus.current)
    }

    // ------------------------------------------------------- pantalla completa

    /**
     * Pasa el video a pantalla completa y gira a horizontal.
     *
     * No se recrea la actividad (el manifiesto ya declara orientation|screenSize):
     * se ocultan cabecera, panel y lista, y el marco del video pasa a ocupar toda la
     * pantalla. Las barras del sistema se ocultan para que el video tenga todo el alto.
     */
    private fun enterFullscreen() {
        if (isFullscreen) return
        if (PlaybackStatus.current.channelId == null) return

        isFullscreen = true
        if (videoFrame.layoutParams.height > 0) {
            normalVideoHeight = videoFrame.layoutParams.height
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Todo negro también detrás del recorte de la cámara. En un AMOLED una franja
        // gris se nota muchísimo; el color de fondo normal no sirve aquí.
        rootView.setBackgroundColor(Color.BLACK)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        extendIntoDisplayCutout(true)
        playerView.setShutterBackgroundColor(Color.BLACK)

        rootView.setPadding(0, 0, 0, 0)
        header.visibility = View.GONE
        infoPanel.visibility = View.GONE
        channelList.visibility = View.GONE
        videoFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        btnFullscreen.visibility = View.GONE
        // Nada de botón fijo: aparece al tocar el video y se oculta solo.
        uiHandler.removeCallbacks(hideFullscreenExit)
        btnExitFullscreen.visibility = View.GONE
    }

    /** Vuelve a la pantalla normal en vertical y restaura el tamaño 16:9 del video. */
    private fun exitFullscreen() {
        if (!isFullscreen) return

        isFullscreen = false
        uiHandler.removeCallbacks(hideFullscreenExit)
        btnExitFullscreen.visibility = View.GONE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())

        val background = ContextCompat.getColor(this, R.color.bg)
        rootView.setBackgroundColor(background)
        window.setBackgroundDrawable(ColorDrawable(background))
        window.statusBarColor = background
        window.navigationBarColor = background
        extendIntoDisplayCutout(false)

        header.visibility = View.VISIBLE
        infoPanel.visibility = View.VISIBLE
        channelList.visibility = View.VISIBLE
        videoFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (normalVideoHeight > 0) normalVideoHeight else dp(180)
        )

        ViewCompat.requestApplyInsets(rootView)
        render(PlaybackStatus.current)
    }

    /** Un toque en el video muestra SALIR; otro toque (o 3 s) lo vuelve a esconder. */
    private fun toggleFullscreenExit() {
        uiHandler.removeCallbacks(hideFullscreenExit)

        if (btnExitFullscreen.visibility == View.VISIBLE) {
            btnExitFullscreen.visibility = View.GONE
        } else {
            btnExitFullscreen.visibility = View.VISIBLE
            uiHandler.postDelayed(hideFullscreenExit, EXIT_HIDE_DELAY_MS)
        }
    }

    /**
     * Permite que el video y el fondo negro lleguen hasta el borde del recorte de la
     * cámara. Sin esto, la zona del recorte se queda con el color de la ventana y en
     * un AMOLED se ve como una franja gris.
     */
    private fun extendIntoDisplayCutout(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode = when {
            !enabled -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = attrs
    }

    private fun renderIdle() {
        txtNowTitle.text = getString(R.string.idle_title)
        txtNowStatus.text = getString(R.string.idle_hint)
        txtNowStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnAction.visibility = View.GONE
        btnFullscreen.visibility = View.GONE

        playerView.visibility = View.INVISIBLE
        playerView.keepScreenOn = false
        videoPlaceholder.visibility = View.VISIBLE
        txtPlaceholder.visibility = View.VISIBLE
        txtPlaceholder.text = "TV"
        txtPlaceholderName.visibility = View.GONE
    }

    /**
     * Mientras no hay imagen en vivo se tapa el reproductor con el monograma del canal.
     * Así el cuadro negro no se queda vacío al pausar, al conectar o al fallar.
     */
    private fun updateVideoOverlay(channel: Channel, phase: PlaybackStatus.Phase) {
        val showVideo = phase == PlaybackStatus.Phase.LIVE ||
            phase == PlaybackStatus.Phase.CONNECTING

        playerView.visibility = if (showVideo) View.VISIBLE else View.INVISIBLE
        playerView.keepScreenOn = phase == PlaybackStatus.Phase.LIVE

        videoPlaceholder.visibility = if (showVideo) View.GONE else View.VISIBLE
        txtPlaceholder.visibility = if (showVideo) View.GONE else View.VISIBLE
        txtPlaceholder.text = channel.initials
        txtPlaceholderName.text = channel.name
        txtPlaceholderName.visibility = if (showVideo) View.GONE else View.VISIBLE
    }

    private fun showStatus(text: String, colorRes: Int) {
        txtNowStatus.text = text
        txtNowStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun prepareNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAPS_REQUIRED = 5
        const val TAPS_WINDOW_MS = 3_000L
        const val EXIT_HIDE_DELAY_MS = 3_000L
    }
}
