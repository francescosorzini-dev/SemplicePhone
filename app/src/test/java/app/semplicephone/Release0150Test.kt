package app.semplicephone

import app.semplicephone.data.ConfigRepository
import app.semplicephone.data.Template
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Release0150Test {
    private fun t(id: String, w: Int = 1, type: TileType = TileType.CALL) = Tile(id, 0, 0, w = w, type = type)

    @Test fun layoutWithoutHolesAndSosAtBottom() {
        val items = listOf(t("a"), t("b"), t("c"), t("missed", 2, TileType.MISSED_CALLS), t("album", type = TileType.PHOTOS))
        val pages = Template.layout(items, rows = 5, sos = null)
        val cfg = LauncherConfig(pages = pages)
        assertEquals(emptyList<String>(), ConfigRepository.validate(cfg))
        val p = pages[0].tiles.associateBy { it.id }
        assertEquals(2 to 0, p.getValue("c").row to p.getValue("c").col)
        assertEquals(2 to 1, p.getValue("album").row to p.getValue("album").col)
        assertEquals(3 to 0, p.getValue("missed").row to p.getValue("missed").col)
        assertEquals(4, p.getValue("sos").row)
    }

    @Test fun tallTilesOverflowToNextPage() {
        val items = (1..9).map { t("p$it") }
        val pages = Template.layout(items, rows = 4, sos = null)
        assertEquals(emptyList<String>(), ConfigRepository.validate(LauncherConfig(pages = pages)))
        assertTrue(pages.size >= 2)
        assertEquals(10 + 1, pages.sumOf { it.tiles.size }) // 9 + orologio + SOS
        assertTrue(pages.all { it.rows == 4 })
    }
}
