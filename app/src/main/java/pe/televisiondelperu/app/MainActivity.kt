package pe.televisiondelperu.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        txtNowTitle = findViewById(R.id.txtNowTitle)
        txtNowStatus = findViewById(R.id.txtNowStatus)
        txtVersion = findViewById(R.id.txtVersion)
        txtUpdate = findViewById(R.id.btnUpdate)
        btnAction = findViewById(R.id.btnStop)
        playerView = findViewById(R.id.playerView)
        videoPlaceholder = findViewById(R.id.videoPlaceholder)
        txtPlaceholder = findViewById(R.id.txtPlaceholder)
        txtPlaceholderName = findViewById(R.id.txtPlaceholderName)

        setupVideoSize()

        adapter = ChannelAdapter { channel -> onChannelClicked(channel) }
        val list = findViewById<RecyclerView>(R.id.rvChannels)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        val snapshot = repository.load()
        channels = snapshot.channels
        catalogVersion = snapshot.version
        adapter.submit(channels)

        // El catálogo remoto se consulta en segundo plano; si hay versión nueva, se
        // refresca la lista sin molestar al usuario y sin cortar el video.
        repository.refreshAsync { fresh -> applyCatalog(fresh) }

        btnAction.setOnClickListener { onActionClicked() }

        // Actualizador: una comprobación al día, chip "Actualizar" solo si hay novedad
        // y 5 toques sobre el título como atajo para forzarla.
        updater.listener = updateListener
        findViewById<View>(R.id.txtTitle).setOnClickListener { onTitleTapped() }
        txtUpdate.setOnClickListener { onUpdateClicked() }
        renderVersionLabel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Nunca cerramos de golpe: la app se minimiza y el video se pausa.
                moveTaskToBack(true)
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
        super.onStop()
    }

    override fun onDestroy() {
        updater.listener = null
        super.onDestroy()
    }

    /**
     * El video ocupa el ancho de la pantalla con proporción 16:9. Se calcula en píxeles
     * para que quepa también en pantallas pequeñas y en tablets.
     */
    private fun setupVideoSize() {
        val frame = findViewById<View>(R.id.videoFrame)
        val metrics = resources.displayMetrics
        val byWidth = metrics.widthPixels * 9 / 16
        val byHeight = (metrics.heightPixels * 0.46f).toInt()
        val height = minOf(byWidth, byHeight).coerceAtLeast(dp(140))
        frame.layoutParams = frame.layoutParams.apply { this.height = height }
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

    private fun renderIdle() {
        txtNowTitle.text = getString(R.string.idle_title)
        txtNowStatus.text = getString(R.string.idle_hint)
        txtNowStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnAction.visibility = View.GONE

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
    }
}
