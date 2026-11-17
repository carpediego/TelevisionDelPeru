package pe.televisiondelperu.app.player

import android.content.Context

/**
 * Recuerda el último canal visto.
 *
 * Sirve para dos cosas:
 *  - la pantalla principal sepa qué estaba puesto si el proceso se reinicia;
 *  - Android pueda reanudar desde la tarjeta de la pantalla de bloqueo, incluso
 *    cuando el servicio ya no estaba vivo (ver `PlaybackService.onPlaybackResumption`).
 */
class SessionState(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastChannelId(): String? = prefs.getString(KEY_CHANNEL, null)

    fun saveChannel(channelId: String) {
        prefs.edit().putString(KEY_CHANNEL, channelId).apply()
    }

    /** Se llama solo cuando el usuario pulsa DETENER: ahí sí queremos olvidar. */
    fun clear() {
        prefs.edit().remove(KEY_CHANNEL).apply()
    }

    private companion object {
        const val PREFS = "player_state"
        const val KEY_CHANNEL = "last_channel"
    }
}
