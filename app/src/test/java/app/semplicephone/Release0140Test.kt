package app.semplicephone

import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.Page
import app.semplicephone.model.PhoneConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType
import app.semplicephone.phone.NightQuiet
import app.semplicephone.reminders.ChargeReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class Release0140Test {
    @Test fun nightWindowAcrossMidnight() {
        assertTrue(NightQuiet.inWindow("22:00", "07:00", LocalTime.of(23, 30)))
        assertTrue(NightQuiet.inWindow("22:00", "07:00", LocalTime.of(3, 0)))
        assertFalse(NightQuiet.inWindow("22:00", "07:00", LocalTime.of(7, 0)))
        assertFalse(NightQuiet.inWindow("22:00", "07:00", LocalTime.of(12, 0)))
        assertTrue(NightQuiet.inWindow("13:00", "15:00", LocalTime.of(14, 0)))
        assertFalse(NightQuiet.inWindow("bad", "07:00", LocalTime.of(3, 0)))
    }

    @Test fun chargeReminderTiming() {
        val p = PhoneConfig(chargeReminder = true, chargeTime = "20:00", chargeBelow = 40)
        val z = ZoneId.of("Europe/Rome")
        val before = ZonedDateTime.of(2026, 11, 6, 18, 0, 0, 0, z)
        assertEquals(ZonedDateTime.of(2026, 11, 6, 20, 0, 0, 0, z), ChargeReminder.nextAt(p, before))
        val after = ZonedDateTime.of(2026, 11, 6, 21, 0, 0, 0, z)
        assertEquals(ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, z), ChargeReminder.nextAt(p, after))
        assertTrue(ChargeReminder.shouldWarn(p, 30, charging = false))
        assertFalse(ChargeReminder.shouldWarn(p, 30, charging = true))
        assertFalse(ChargeReminder.shouldWarn(p, 60, charging = false))
        assertFalse(ChargeReminder.shouldWarn(p.copy(chargeReminder = false), 10, charging = false))
    }

    @Test fun placeOnOtherPage() {
        val big = Tile(id = "big", col = 0, row = 0, w = 2, h = 2, type = TileType.APP)
        val page = Page(cols = 2, rows = 3, tiles = listOf(Tile(id = "a", col = 0, row = 0, type = TileType.APP)))
        val placed = ConfigRepository.placeOnPage(page, big)
        assertNotNull(placed)
        assertEquals(1, placed!!.row)
        assertEquals(0, placed.col)
        val full = page.copy(tiles = page.tiles + Tile(id = "b", col = 0, row = 1, w = 2, h = 2, type = TileType.APP))
        assertNull(ConfigRepository.placeOnPage(full, big))
    }
}
