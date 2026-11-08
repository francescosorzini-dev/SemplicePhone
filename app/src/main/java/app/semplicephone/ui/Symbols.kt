package app.semplicephone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.semplicephone.model.TileType

/**
 * Set di simboli a tratto (24x24, stroke 2) usati quando un tile non ha icona d'app o foto.
 * Il colore effettivo arriva dal tint dell'Icon.
 */
object Symbols {

    /**
     * Tracciati (viewBox 24, tratto 2, estremi tondi). Regola: disegno dentro 2–22 e centrato, cerchi
     * da 18, niente parti che escono dal contorno. Sorgente unica: da qui si generano anche le icone delle
     * notifiche (res/drawable/sym_*.xml) e i simboli del portale (tools/symbols/gen.py).
     */
    private val paths: Map<String, List<String>> = mapOf(
        "phone" to listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"),
        "message" to listOf("M6 4h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9l-5 4V6a2 2 0 0 1 2-2z"),
        "video" to listOf("M5 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M15 10.5l6-3.5v10l-6-3.5"),
        "photo" to listOf("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 10a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M21 16l-5-5-9 9"),
        "camera" to listOf("M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0"),
        "weather" to listOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5"),
        "web" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M3 12h18", "M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18"),
        "person" to listOf("M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M4 21a8 8 0 0 1 16 0"),
        "alert" to listOf("M12 3.5l9 16.5H3z", "M12 10v4.5", "M12 17.5v.5"),
        "pill" to listOf("M5.1 12.9l7.8-7.8a4.2 4.2 0 0 1 6 6l-7.8 7.8a4.2 4.2 0 0 1-6-6z", "M9 9l6 6"),
        "home" to listOf("M3 11l9-7 9 7", "M5 10v10h14V10", "M10 20v-6h4v6"),
        "heart" to listOf("M12 20s-8-4.9-8-11a4.5 4.5 0 0 1 8-2.8A4.5 4.5 0 0 1 20 9c0 6.1-8 11-8 11z"),
        "music" to listOf("M9 18V6l10-2v12", "M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M15 16a2 2 0 1 0 4 0a2 2 0 1 0-4 0"),
        "radio" to listOf("M5 9h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1", "M7 9L16 4", "M13 14.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0", "M7 13h3M7 16h3"),
        "grid" to listOf("M4 5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M14 5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z", "M4 15a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M14 15a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z"),
        "shower" to listOf("M5.5 21V7a3 3 0 0 1 3-3h3a3 3 0 0 1 3 3", "M10.5 11a4 4 0 0 1 8 0z", "M12 14.5v1.5M17 14.5v1.5M14.5 14.5v1.5M13 19v1M16 19v1"),
        "cart" to listOf("M3 4h2l2.5 11h10L20 7H6", "M9 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M16 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0"),
        "doctor" to listOf("M5 4v6a4 4 0 0 0 8 0V4", "M4 4h2M12 4h2", "M9 14v2a4 4 0 0 0 8 0v-1", "M15 13a2 2 0 1 0 4 0a2 2 0 1 0-4 0"),
        "cards" to listOf("M5 9a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z", "M9 7V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-2"),
        "calendar" to listOf("M6 5h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M4 10h16", "M8 3v4M16 3v4", "M8 14h.01M12 14h.01M16 14h.01M8 17.5h.01M12 17.5h.01"),
        "walk" to listOf("M11.25 4.5a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0-3.5 0", "M12.5 8.5L11 14", "M11 14l-2.5 3.5L7 21", "M11 14l3 3 .5 4", "M12.5 8.5L9.5 10l-1 3", "M12.5 8.5l2 3 2.5 1"),
        "food" to listOf("M4.5 3v5a3.5 3.5 0 0 0 7 0V3", "M8 3v18", "M19.5 14V3a4 4 0 0 0-4 4v5a2 2 0 0 0 2 2h2z", "M19.5 14v7"),
        "bed" to listOf("M3 5v14", "M3 15h18v4", "M21 15v-3a3 3 0 0 0-3-3h-7v6", "M5.5 11.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"),
        "water" to listOf("M6 4h12l-1.5 16h-9z", "M7 9h10"),
        "cloud" to listOf("M7 18h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 18z"),
        "rain" to listOf("M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z", "M8 17l-1 3M12 17l-1 3M16 17l-1 3"),
        "snow" to listOf("M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z", "M8 18h.01M12 18h.01M16 18h.01M10 21h.01M14 21h.01"),
        "storm" to listOf("M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z", "M12 14l-2 4h4l-2 4"),
        "fog" to listOf("M4 8h16M6 12h12M4 16h16"),
        "missed" to listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", "M16 3l5 5M21 3l-5 5"),
        "battery" to listOf("M4.5 6h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-12a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M21.5 10v4", "M12 9.5L9.5 12H13l-2.5 3"),
        "search" to listOf("M4 10a6 6 0 1 0 12 0a6 6 0 1 0-12 0", "M14.5 14.5L20 20"),
        "torch" to listOf("M7 3h10v3l-3 4v11h-4V10L7 6z", "M7 6h10", "M12 13v2"),
        "magnifier" to listOf("M3 10a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M15 15l6 6", "M10 7v6M7 10h6"),
        "pin" to listOf("M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z", "M9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"),
        "news" to listOf("M4 5h13v14H6a2 2 0 0 1-2-2z", "M17 9h3v8a2 2 0 0 1-2 2", "M7 8h7M7 11h7M7 14h4"),
        "callme" to listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", "M21 3l-6 6", "M15 4v5h5"),
        "check" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M8 12.5l2.5 2.5 5.5-6"),
        "clock" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 7v5l3 2"),
        "car" to listOf("M6.5 10l1.5-4h8l1.5 4", "M5 10h14a2 2 0 0 1 2 2v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-4a2 2 0 0 1 2-2z", "M7 13.5h.01M17 13.5h.01", "M5.5 17v2M18.5 17v2"),
        "bus" to listOf("M6 3h12a2 2 0 0 1 2 2v12H4V5a2 2 0 0 1 2-2z", "M4 11h16", "M7 17v3M17 17v3", "M8 14h.01M16 14h.01"),
        "key" to listOf("M4 16a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M10.8 13.2L20 4", "M17 7l2.5 2.5M14.5 9.5l2 2"),
        "glasses" to listOf("M3 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0", "M14 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0", "M10 13a2 2 0 0 1 4 0", "M3.5 12L5 6.5M20.5 12L19 6.5"),
        "paw" to listOf("M8.5 16.5c0-2.5 1.6-4.5 3.5-4.5s3.5 2 3.5 4.5c0 2-1.6 3-3.5 3s-3.5-1-3.5-3z", "M4.5 11a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0", "M8 6.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0", "M13 6.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0", "M16.5 11a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"),
        "plant" to listOf("M7 14h10l-1.5 7h-7z", "M12 14V8", "M12 10C9 10 7 8 7 5c3 0 5 2 5 5z", "M12 8c0-3 2-5 5-5 0 3-2 5-5 5z"),
        "tooth" to listOf("M7 3C4.5 3 3 5 3 7.5c0 3 1.5 4.5 2 7l1 5c.3 1.3 2 1.3 2.3 0l1.2-4.5c.3-1 2.7-1 3 0l1.2 4.5c.3 1.3 2 1.3 2.3 0l1-5c.5-2.5 2-4 2-7C21 5 19.5 3 17 3c-2 0-3 1-5 1S9 3 7 3z"),
        "cake" to listOf("M5 21v-7a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v7", "M3 21h18", "M12 13V9", "M12 6.5c-.8-.8-.8-2 0-3 .8 1 .8 2.2 0 3z", "M5 16.5c1.2 0 1.2 1 2.3 1s1.2-1 2.3-1 1.2 1 2.4 1 1.2-1 2.3-1 1.2 1 2.3 1 1.2-1 2.4-1"),
        "trash" to listOf("M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13", "M10 11v6M14 11v6"),
        "wallet" to listOf("M4 7a2 2 0 0 1 2-2h11v4", "M4 7v11a2 2 0 0 0 2 2h14V9H6a2 2 0 0 1-2-2z", "M16 14.5h.01"),
        "book" to listOf("M12 6.5C10 5 7 4.5 3 5v14c4-.5 7 0 9 1.5 2-1.5 5-2 9-1.5V5c-4-.5-7 0-9 1.5z", "M12 6.5v14"),
        "tv" to listOf("M5 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M8 3l4 4 4-4"),
        "mail" to listOf("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3.5 7l8.5 6 8.5-6"),
        "thermometer" to listOf("M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z", "M12 9v8"),
        "syringe" to listOf("M16 5l3 3-9 9-3-3z", "M14 3l7 7", "M8.5 15.5L3 21", "M12.5 9.5l2 2"),
        "family" to listOf("M5 8.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M2.5 20a5.5 5.5 0 0 1 11 0", "M14 9.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0", "M15 14.7a4.5 4.5 0 0 1 6.5 4v1.3"),
        "moon" to listOf("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"),
        "speaker" to listOf("M4 9h3l5-4v14l-5-4H4z", "M15.5 9a4 4 0 0 1 0 6", "M18 6.5a7.5 7.5 0 0 1 0 11"),
        "mic" to listOf("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z", "M5.5 11a6.5 6.5 0 0 0 13 0", "M12 17.5V21", "M9 21h6"),
        "folder" to listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
        "close" to listOf("M6 6l12 12M18 6L6 18"),
        "backspace" to listOf("M8 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-6-7z", "M11 9l6 6M17 9l-6 6"),
        "play" to listOf("M6 4.5v15L19 12z"),
        "chevron" to listOf("M6 9l6 6 6-6"),
        "minus" to listOf("M5 12h14"),
        "plus" to listOf("M12 5v14M5 12h14"),
    )

    private val cache = mutableMapOf<String, ImageVector>()

    val names: Set<String> get() = paths.keys

    /** Simboli dei pulsanti dell'app: non si scelgono per riquadri e promemoria. */
    private val uiOnly = setOf("speaker", "mic", "folder", "close", "backspace", "play", "chevron", "minus", "plus", "check")

    /** Simboli da offrire nella scelta (riquadri, promemoria). */
    val pickable: List<String> get() = paths.keys.filter { it !in uiOnly }

    fun get(name: String): ImageVector = cache.getOrPut(name) { build(name, paths[name] ?: paths.getValue("grid")) }

    fun defaultFor(type: TileType): String = when (type) {
        TileType.CALL -> "phone"
        TileType.WA_VIDEO, TileType.TG_VIDEO -> "video"
        TileType.SMS -> "message"
        TileType.URL -> "web"
        TileType.SOS -> "alert"
        TileType.WEATHER -> "weather"
        TileType.PHOTOS -> "photo"
        TileType.MISSED_CALLS -> "missed"
        TileType.SEARCH -> "search"
        TileType.MESSAGES -> "message"
        TileType.RADIO -> "radio"
        TileType.TORCH -> "torch"
        TileType.MAGNIFIER -> "magnifier"
        TileType.WHERE_AM_I -> "pin"
        TileType.CALL_ME -> "callme"
        TileType.MUSIC -> "music"
        TileType.NEWS -> "news"
        TileType.CLOCK -> "clock"
        else -> "grid"
    }

    private fun build(name: String, data: List<String>): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        data.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
