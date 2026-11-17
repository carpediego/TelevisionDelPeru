package pe.televisiondelperu.app.catalog

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Origen del catálogo de canales.
 *
 * Estrategia:
 *   1. `assets/channels.json` viene dentro del APK: la app funciona siempre, incluso sin internet.
 *   2. Si hay una copia remota más nueva en caché, se usa esa (el robot corrige URLs rotas).
 *   3. Al arrancar se consulta el CDN como máximo una vez cada 15 minutos.
 *
 * Regla que nunca se rompe: solo se guardan URLs de *entrada*. Los proveedores
 * redirigen a una CDN con token que caduca, así que cachear la URL final dejaría
 * el canal mudo en minutos.
 */
class CatalogRepository(private val context: Context) {

    data class Snapshot(val channels: List<Channel>, val version: Int)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Catálogo vigente: la copia remota cacheada si es más nueva; si no, la embebida. */
    fun load(): Snapshot {
        val embedded = readEmbedded() ?: return Snapshot(emptyList(), 0)
        val cached = readCached()
        return if (cached != null && cached.version > embedded.version) cached else embedded
    }

    /**
     * Busca una versión nueva del catálogo sin bloquear la interfaz.
     * Si encuentra una versión más nueva y válida, la guarda y avisa en el hilo principal.
     */
    fun refreshAsync(onUpdated: (Snapshot) -> Unit) {
        if (!shouldCheck()) return

        Thread {
            val fresh = fetchRemote()
            if (fresh != null && fresh.version > load().version) {
                writeCache(fresh)
                mainHandler.post { onUpdated(fresh) }
            }
            val now = System.currentTimeMillis()
            // Si no se pudo consultar (sin internet), reintentamos en 5 min en vez de 15.
            val stamp = if (fresh != null) now else now - CHECK_INTERVAL_MS + FAILURE_RETRY_MS
            prefs.edit().putLong(KEY_LAST_CHECK, stamp).apply()
        }.start()
    }

    // ------------------------------------------------------------------ red

    private fun shouldCheck(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    private fun fetchRemote(): Snapshot? {
        for (url in REMOTE_URLS) {
            val text = try {
                httpGet(url)
            } catch (_: Exception) {
                null
            } ?: continue

            val snapshot = try {
                parse(text)
            } catch (_: Exception) {
                null
            } ?: continue

            // Salvaguarda: si el remoto llega mutilado, seguimos con el embebido.
            if (snapshot.channels.size >= MIN_CHANNELS) return snapshot
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.length in 100..MAX_BODY_CHARS) text else null
        } finally {
            connection.disconnect()
        }
    }

    // --------------------------------------------------------------- archivo

    private fun readEmbedded(): Snapshot? = try {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        parse(raw)
    } catch (_: Exception) {
        null
    }

    private fun readCached(): Snapshot? = try {
        if (!cacheFile.exists()) null else parse(cacheFile.readText())
    } catch (_: Exception) {
        null
    }

    private fun writeCache(snapshot: Snapshot) {
        try {
            val text = snapshot.toJson()
            val tmp = File(cacheFile.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(text)
            if (cacheFile.exists()) cacheFile.delete()
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
            // Si falla la caché, no pasa nada: seguimos con la copia embebida.
        }
    }

    // ---------------------------------------------------------------- parseo

    private fun parse(raw: String): Snapshot {
        val root = JSONObject(raw)

        val schema = root.optInt("schema", 1)
        require(schema <= SUPPORTED_SCHEMA) { "Esquema de catálogo no soportado: $schema" }

        val array = root.optJSONArray("channels") ?: return Snapshot(emptyList(), 0)
        val out = ArrayList<Channel>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val candidates = ArrayList<StreamCandidate>()
            val candidatesJson = obj.optJSONArray("candidates")
            if (candidatesJson != null) {
                for (j in 0 until candidatesJson.length()) {
                    val candidate = candidatesJson.optJSONObject(j) ?: continue
                    val url = candidate.optString("url", "")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                    val type = candidate.optString("type", "hls")
                    candidates.add(StreamCandidate(url, type))
                }
            }
            if (candidates.isEmpty()) continue

            val provider = obj.optJSONObject("provider")

            out.add(
                Channel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    tagline = obj.optString("tagline", ""),
                    category = obj.optString("category", "nacional"),
                    color = obj.optString("color", "#C62828"),
                    priority = obj.optInt("priority", 100),
                    active = obj.optBoolean("active", true),
                    candidates = candidates,
                    providerKind = provider?.optString("kind"),
                    providerId = provider?.optString("id")
                )
            )
        }

        val visible = out
            .filter { it.active }
            .sortedWith(compareBy({ it.priority }, { it.name }))

        return Snapshot(visible, root.optInt("version", 0))
    }

    private fun Snapshot.toJson(): String {
        // Solo se reescribe lo necesario: la app nunca inventa datos del catálogo.
        val root = JSONObject()
        root.put("schema", SUPPORTED_SCHEMA)
        root.put("version", version)
        val array = org.json.JSONArray()
        for (channel in channels) {
            val obj = JSONObject()
            obj.put("id", channel.id)
            obj.put("name", channel.name)
            obj.put("tagline", channel.tagline)
            obj.put("category", channel.category)
            obj.put("color", channel.color)
            obj.put("priority", channel.priority)
            obj.put("active", channel.active)
            val candidates = org.json.JSONArray()
            for (candidate in channel.candidates) {
                candidates.put(
                    JSONObject()
                        .put("url", candidate.url)
                        .put("type", candidate.type)
                )
            }
            obj.put("candidates", candidates)
            if (channel.providerKind != null && channel.providerId != null) {
                obj.put(
                    "provider",
                    JSONObject().put("kind", channel.providerKind).put("id", channel.providerId)
                )
            }
            array.put(obj)
        }
        root.put("channels", array)
        return root.toString()
    }

    private companion object {
        const val ASSET_NAME = "channels.json"
        const val CACHE_FILE = "catalog-cache.json"
        const val PREFS = "catalog"
        const val KEY_LAST_CHECK = "last_check"

        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 8_000
        // Comprueba en cada arranque de la app, como máximo una vez cada 15 min.
        const val CHECK_INTERVAL_MS = 15L * 60 * 1000
        const val FAILURE_RETRY_MS = 5L * 60 * 1000
        const val MAX_BODY_CHARS = 2_000_000
        const val MIN_CHANNELS = 3
        const val SUPPORTED_SCHEMA = 1
        const val USER_AGENT =
            "TelevisionDelPeru/1.0 (+https://github.com/carpediego/TelevisionDelPeru)"

        /**
         * Primero el archivo crudo (se actualiza en ~5 minutos), luego el CDN
         * de jsDelivr como respaldo (más rápido pero con caché de horas).
         */
        val REMOTE_URLS = listOf(
            "https://raw.githubusercontent.com/carpediego/TelevisionDelPeru/main/catalog/channels.json",
            "https://cdn.jsdelivr.net/gh/carpediego/TelevisionDelPeru@main/catalog/channels.json"
        )
    }
}
