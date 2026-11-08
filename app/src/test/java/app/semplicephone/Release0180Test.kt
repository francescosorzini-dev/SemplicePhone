package app.semplicephone

import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.PhoneConfig
import app.semplicephone.model.TileType
import app.semplicephone.ui.SymbolDrawables
import app.semplicephone.ui.Symbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Release0180Test {
    @Test fun everyTileTypeHasItsOwnSymbol() {
        // Le funzioni integrate hanno un simbolo proprio; «grid» resta solo per app, scorciatoie e sconosciuti.
        val generic = setOf(TileType.APP, TileType.SHORTCUT, TileType.UNKNOWN)
        TileType.entries.filter { it !in generic }.forEach { t ->
            val s = Symbols.defaultFor(t)
            assertTrue("$t → $s", s in Symbols.names)
            assertNotEquals("$t senza simbolo proprio", "grid", s)
        }
        assertEquals("clock", Symbols.defaultFor(TileType.CLOCK))
    }

    @Test fun buttonSymbolsAreNotOfferedForTiles() {
        listOf("close", "minus", "plus", "backspace", "chevron", "mic", "speaker", "play", "folder").forEach {
            assertTrue(it, it in Symbols.names)
            assertTrue(it, it !in Symbols.pickable)
        }
        listOf("clock", "trash", "plant", "syringe", "thermometer", "moon", "paw", "family").forEach { assertTrue(it, it in Symbols.pickable) }
    }

    @Test fun notificationIconsCoverAllSymbols() {
        Symbols.names.filter { it != "grid" }.forEach { assertNotEquals(it, R.drawable.sym_grid, SymbolDrawables.get(it)) }
        assertEquals(R.drawable.sym_grid, SymbolDrawables.get("nessuno"))
    }

    @Test fun appIconsSettingRoundTrips() {
        assertEquals("original", PhoneConfig().appIcons)
        val c = LauncherConfig(phone = PhoneConfig(appIcons = "mono"))
        val j = ConfigRepository.json
        assertEquals("mono", j.decodeFromString(LauncherConfig.serializer(), j.encodeToString(LauncherConfig.serializer(), c)).phone.appIcons)
    }
}
