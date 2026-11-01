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
        "grid" to listOf("M5 5h6v6H5zM13 5h6v6h-6zM5 13h6v6H5zM13 13h6v6h-6z"),
    )

    private val cache = mutableMapOf<String, ImageVector>()

    val names: Set<String> get() = paths.keys

    fun get(name: String): ImageVector = cache.getOrPut(name) { build(name, paths[name] ?: paths.getValue("grid")) }

    fun defaultFor(type: TileType): String = when (type) {
        TileType.CALL -> "phone"
        TileType.WA_VIDEO -> "video"
        TileType.SMS -> "message"
        TileType.URL -> "web"
        TileType.SOS -> "alert"
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
