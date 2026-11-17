package pe.televisiondelperu.app.catalog

/**
 * Una URL de entrada del canal, en orden de preferencia.
 *
 * `type` es una pista para el reproductor: `hls` para listas `.m3u8` y `dash`
 * para manifiestos `.mpd`. Nunca se guarda la URL final de la CDN: muchos
 * proveedores redirigen a una dirección con token que caduca en minutos.
 */
data class StreamCandidate(
    val url: String,
    val type: String = "hls"
)

/**
 * Un canal de televisión del catálogo.
 *
 * [candidates] es la cadena de intentos: si la primera URL falla, el servicio
 * prueba la siguiente antes de darse por vencido.
 */
data class Channel(
    val id: String,
    val name: String,
    val tagline: String,
    val category: String,
    val color: String,
    val priority: Int,
    val active: Boolean,
    val candidates: List<StreamCandidate>,
    val providerKind: String? = null,
    val providerId: String? = null
) {
    /** Dos letras para el cuadro de color: «TV Perú» → TP, «América TV» → AM. */
    val initials: String
        get() {
            val words = name
                .split(" ", "-", ".", "(", ")")
                .filter { it.isNotBlank() && it.first().isLetter() }

            return when {
                words.isEmpty() -> "TV"

                // «Canal N» → CN, «Canal IPe» → CI.
                words.size >= 2 && words[0].equals("Canal", ignoreCase = true) ->
                    words.take(2).joinToString("") { it.first().uppercase() }

                // «TV Perú» → TP, «La Tele» → LT, «RPP TV» → RT.
                words.size >= 2 && words[0].length <= 3 ->
                    words.take(2).joinToString("") { it.first().uppercase() }

                // «América TV» → AM, «Panamericana TV» → PA.
                words.size >= 2 -> words[0].take(2).uppercase()

                // «ATV» → AT, «Latina» → LA.
                else -> words[0].take(2).uppercase()
            }
        }

    val streamUrl: String get() = candidates.first().url
}
