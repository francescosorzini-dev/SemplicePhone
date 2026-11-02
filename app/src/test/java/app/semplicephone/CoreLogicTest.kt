package app.semplicephone

import app.semplicephone.data.AppCatalog
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Page
import app.semplicephone.model.Reminder
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType
import app.semplicephone.reminders.ReminderTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class CoreLogicTest {

    private fun tile(id: String, c: Int, r: Int, w: Int = 1, h: Int = 1) =
        Tile(id = id, col = c, row = r, w = w, h = h, type = TileType.APP)

    @Test fun validConfigPasses() {
        val cfg = LauncherConfig(pages = listOf(Page(2, 3, listOf(tile("a", 0, 0, 2, 1), tile("b", 0, 1), tile("c", 1, 1)))))
        assertEquals(emptyList<String>(), ConfigRepository.validate(cfg))
    }

    @Test fun overlapAndOutOfGridAreRejected() {
        val overlap = LauncherConfig(pages = listOf(Page(2, 3, listOf(tile("a", 0, 0, 2, 1), tile("b", 1, 0)))))
        assertTrue(ConfigRepository.validate(overlap).any { "sovrapposto" in it })
        val out = LauncherConfig(pages = listOf(Page(2, 3, listOf(tile("a", 1, 0, 2, 1)))))
        assertTrue(ConfigRepository.validate(out).any { "fuori griglia" in it })
        val dup = LauncherConfig(pages = listOf(Page(2, 3, listOf(tile("a", 0, 0))), Page(2, 3, listOf(tile("a", 0, 0)))))
        assertTrue(ConfigRepository.validate(dup).any { "duplicato" in it })
    }

    @Test fun conflictAndFreeCell() {
        val page = Page(2, 2, listOf(tile("a", 0, 0, 2, 1), tile("b", 0, 1)))
        assertNotNull(ConfigRepository.conflict(page, tile("x", 1, 0)))
        assertNull(ConfigRepository.conflict(page, tile("x", 1, 1)))
        assertEquals(1 to 1, ConfigRepository.firstFreeCell(page))
    }

    @Test fun defaultAssetIsValid() {
        val text = File("src/main/assets/default_config.json").readText()
        val cfg = ConfigRepository.json.decodeFromString(LauncherConfig.serializer(), text)
        assertEquals(emptyList<String>(), ConfigRepository.validate(cfg))
    }

    @Test fun searchByContentAccentsAndInitials() {
        assertTrue(AppCatalog.matches("rome", "Chrome"))
        assertTrue(AppCatalog.matches("meteo", "Météo"))
        assertTrue(AppCatalog.matches("gm", "Google Maps"))
        assertFalse(AppCatalog.matches("xyz", "Telefono"))
    }

    @Test fun nextReminderRespectsDaysAndTime() {
        val zone = ZoneId.of("Europe/Rome")
        // Domenica 1 novembre 2026, 13:52
        val now = ZonedDateTime.of(2026, 11, 1, 13, 52, 0, 0, zone)
        val daily = Reminder(id = "a", time = "08:00")
        assertEquals(ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone), ReminderTime.next(daily, now))
        val later = Reminder(id = "b", time = "20:30")
        assertEquals(ZonedDateTime.of(2026, 11, 1, 20, 30, 0, 0, zone), ReminderTime.next(later, now))
        val weekdays = Reminder(id = "c", time = "13:00", days = listOf(3, 5)) // mer, ven
        assertEquals(ZonedDateTime.of(2026, 11, 4, 13, 0, 0, 0, zone), ReminderTime.next(weekdays, now))
        assertNull(ReminderTime.next(Reminder(id = "d", time = "25:00"), now))
    }

    @Test fun reminderAcrossDstChange() {
        val zone = ZoneId.of("Europe/Rome")
        // 28 marzo 2026: la notte successiva si passa all'ora legale (02:00 → 03:00).
        val now = ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, zone)
        val r = Reminder(id = "a", time = "02:30")
        val next = ReminderTime.next(r, now)!!
        assertEquals(29, next.dayOfMonth)
        assertEquals(3, next.hour) // 02:30 non esiste: spostato alle 03:30
    }
}

class PhoneLogicTest {

    @Test fun batteryAlertOncePerDischarge() {
        val p = app.semplicephone.phone.BatteryPolicy
        org.junit.Assert.assertEquals(app.semplicephone.phone.BatteryPolicy.Decision(true, true), p.decide(15, false, 15, false))
        org.junit.Assert.assertEquals(app.semplicephone.phone.BatteryPolicy.Decision(false, true), p.decide(12, false, 15, true))
        // risale ma sotto l'isteresi: nessun nuovo avviso, stato invariato
        org.junit.Assert.assertEquals(app.semplicephone.phone.BatteryPolicy.Decision(false, true), p.decide(20, false, 15, true))
        // in carica o sopra soglia+10: si riarma
        org.junit.Assert.assertEquals(app.semplicephone.phone.BatteryPolicy.Decision(false, false), p.decide(10, true, 15, true))
        org.junit.Assert.assertEquals(app.semplicephone.phone.BatteryPolicy.Decision(false, false), p.decide(25, false, 15, true))
        org.junit.Assert.assertEquals(app.semplicephone.phone.BatteryPolicy.Decision(false, false), p.decide(5, false, 0, false))
    }

    @Test fun smsCommandOnlyFromSosNumbers() {
        val c = app.semplicephone.remote.SmsCommand
        val sos = listOf("+39 333 1234567")
        org.junit.Assert.assertTrue(c.matches("#aggiorna", "3331234567", sos))
        org.junit.Assert.assertTrue(c.matches("  #AGGIORNA per favore", "+393331234567", sos))
        org.junit.Assert.assertFalse(c.matches("aggiorna", "3331234567", sos))
        org.junit.Assert.assertFalse(c.matches("#AGGIORNA", "3339999999", sos))
        org.junit.Assert.assertFalse(c.matches("#AGGIORNA", "123", listOf("123")))
    }

    @Test fun dragMovesOrSwaps() {
        fun t(id: String, c: Int, r: Int, w: Int = 1) = Tile(id = id, col = c, row = r, w = w, type = TileType.APP)
        val page = Page(2, 3, listOf(t("a", 0, 0), t("b", 1, 0), t("wide", 0, 1, 2)))
        // casella libera
        org.junit.Assert.assertEquals(listOf(t("a", 0, 2)), app.semplicephone.ui.settings.movedTiles(page, page.tiles[0], 0, 2))
        // stesso formato: scambio
        org.junit.Assert.assertEquals(listOf(t("a", 1, 0), t("b", 0, 0)), app.semplicephone.ui.settings.movedTiles(page, page.tiles[0], 1, 0))
        // formato diverso: rifiutato
        org.junit.Assert.assertNull(app.semplicephone.ui.settings.movedTiles(page, page.tiles[0], 0, 1))
    }
}
