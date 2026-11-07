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

    private val paths: Map<String, List<String>> = mapOf(
        "phone" to listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"),
        "message" to listOf("M4 5h16v11H8l-4 4z"),
        "video" to listOf(
            "M5 7h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z",
            "M16 11l5-3v8l-5-3",
        ),
        "photo" to listOf(
            "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M7 10a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
            "M21 16l-5-5-9 9",
        ),
        "camera" to listOf(
            "M4 8h3l2-3h6l2 3h3v11H4z",
            "M8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0",
        ),
        "weather" to listOf(
            "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0",
            "M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5",
        ),
        "web" to listOf(
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0",
            "M3 12h18",
            "M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18",
        ),
        "person" to listOf("M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M4 21a8 8 0 0 1 16 0"),
        "alert" to listOf("M12 3l10 18H2z", "M12 10v5", "M12 18v0.5"),
        "pill" to listOf("M4.6 12.4l7.8-7.8a4.2 4.2 0 0 1 6 6l-7.8 7.8a4.2 4.2 0 0 1-6-6z", "M8.5 8.5l7 7"),
        "home" to listOf("M3 11l9-7 9 7", "M5 10v10h14V10", "M10 20v-6h4v6"),
        "heart" to listOf("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"),
        "music" to listOf("M9 18V6l10-2v12", "M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M15 16a2 2 0 1 0 4 0a2 2 0 1 0-4 0"),
        "radio" to listOf("M4 9h16v11H4z", "M7 9l10-5", "M9 14.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M16 13h1M16 16h1"),
        "grid" to listOf("M5 5h6v6H5zM13 5h6v6h-6zM5 13h6v6H5zM13 13h6v6h-6z"),
        "shower" to listOf("M5 21V7a3 3 0 0 1 6 0", "M8 9h8a4 4 0 0 0-8 0", "M9 13v1M12 13v1M15 13v1M10.5 16.5v1M13.5 16.5v1M12 20v1"),
        "cart" to listOf("M3 4h2l2.5 11h10L20 7H6", "M9 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M16 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0"),
        "doctor" to listOf("M6 3v6a4 4 0 0 0 8 0V3", "M10 13v2a4 4 0 0 0 8 0v-1", "M16 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0"),
        "cards" to listOf("M4 7l7-2.5 3.5 12L7.5 19z", "M14 4.5l5.5 1.5-3.5 13"),
        "calendar" to listOf("M4 6h16v14H4z", "M4 10h16", "M8 3v4M16 3v4", "M8 14h2M14 14h2M8 17h2"),
        "walk" to listOf("M13 4.5a1.5 1.5 0 1 0 0.01 0", "M12 8l-2 5 3 2.5 1 5.5", "M10 13l-3 7", "M12 8l3 3h3"),
        "food" to listOf("M7 3v7a2 2 0 0 0 2 2v9", "M11 3v7a2 2 0 0 1-2 2", "M9 3v5", "M17 3c-2.5 2-2.5 7 0 9v9"),
        "bed" to listOf("M3 19V7", "M3 15h18v4", "M21 15v-3a3 3 0 0 0-3-3h-7v6", "M7 11.5a1.5 1.5 0 1 0 0.01 0"),
        "water" to listOf("M6 4h12l-1.5 16h-9z", "M7 9h10"),
        "cloud" to listOf("M7 18h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 18z"),
        "rain" to listOf("M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z", "M8 17l-1 3M12 17l-1 3M16 17l-1 3"),
        "snow" to listOf("M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z", "M8 18h.01M12 18h.01M16 18h.01M10 21h.01M14 21h.01"),
        "storm" to listOf("M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z", "M12 14l-2 4h4l-2 4"),
        "fog" to listOf("M4 9h16M6 13h12M4 17h16"),
        "missed" to listOf("M5 6h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 15l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 3 8a2 2 0 0 1 2-2", "M15 3l5 5M20 3l-5 5"),
        "battery" to listOf("M6 7h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2", "M21 10.5v3", "M11.5 8.5L9 12h4l-2.5 3.5"),
        "radio" to listOf("M5 9h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1", "M7 9L16 4", "M13 14.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0", "M7 13h3M7 16h3"),
        "search" to listOf("M4 10a6 6 0 1 0 12 0a6 6 0 1 0-12 0", "M14.5 14.5L20 20"),
        "torch" to listOf("M8 3h8v4l-2 3v11h-4V10L8 7z", "M8 7h8", "M12 13v2"),
        "magnifier" to listOf("M3 10a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M15 15l6 6", "M10 7v6M7 10h6"),
        "pin" to listOf("M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z", "M9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"),
        "news" to listOf("M4 5h13v14H6a2 2 0 0 1-2-2z", "M17 9h3v8a2 2 0 0 1-2 2", "M7 8h7M7 11h7M7 14h4"),
        "callme" to listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", "M15 3h6M18 3l-3 3", "M21 3v6"),
        "check" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M8 12.5l2.5 2.5 5.5-6"),
    )

    private val cache = mutableMapOf<String, ImageVector>()

    val names: Set<String> get() = paths.keys

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
