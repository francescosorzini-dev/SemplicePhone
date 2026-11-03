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
        assertTrue(ConfigRepository.validate(overlap).any { "overlaps" in it })
        val out = LauncherConfig(pages = listOf(Page(2, 3, listOf(tile("a", 1, 0, 2, 1)))))
        assertTrue(ConfigRepository.validate(out).any { "outside the grid" in it })
        val dup = LauncherConfig(pages = listOf(Page(2, 3, listOf(tile("a", 0, 0))), Page(2, 3, listOf(tile("a", 0, 0)))))
        assertTrue(ConfigRepository.validate(dup).any { "duplicate" in it })
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

class StoreDetectionTest {
    @Test fun storesDisableSelfUpdate() {
        assertEquals("Google Play", app.semplicephone.update.Updater.storeName("com.android.vending"))
        assertEquals("F-Droid", app.semplicephone.update.Updater.storeName("org.fdroid.fdroid"))
        // APK dal browser o via ADB: nessuno store, l'app si aggiorna da sola
        org.junit.Assert.assertNull(app.semplicephone.update.Updater.storeName(null))
        org.junit.Assert.assertNull(app.semplicephone.update.Updater.storeName("com.google.android.packageinstaller"))
    }
}

class I18nTest {
    @Test fun smsKeywordInEveryLanguage() {
        val c = app.semplicephone.remote.SmsCommand
        val sos = listOf("+39 333 1234567")
        listOf("#AGGIORNA", "#update", "#Aktualisieren", "#ACTUALISER", "#actualizar").forEach {
            assertTrue(it, c.matches(it, "3331234567", sos))
        }
    }

    @Test fun daysFollowLocale() {
        assertEquals("ogni giorno", ReminderTime.daysSummary(emptyList(), "ogni giorno", "feriali", java.util.Locale.ITALIAN))
        assertEquals("feriali", ReminderTime.daysSummary(listOf(1, 2, 3, 4, 5), "ogni giorno", "feriali", java.util.Locale.ITALIAN))
        assertEquals("Mon, Sat", ReminderTime.daysSummary(listOf(6, 1), "", "", java.util.Locale.ENGLISH))
        assertEquals("L", ReminderTime.dayInitial(1, java.util.Locale.ITALIAN))
        assertEquals("G", ReminderTime.dayInitial(4, java.util.Locale.ITALIAN))
        assertEquals("T", ReminderTime.dayInitial(4, java.util.Locale.ENGLISH))
    }

    /** Ogni lingua ha le stesse chiavi e gli stessi segnaposto della lingua di riserva (inglese). */
    @Test fun translationsMatchBase() {
        val res = java.io.File("src/main/res")
        val fmt = Regex("%(\\d+\\$)?[sd]")
        fun read(f: java.io.File): Map<String, List<String>> {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val out = mutableMapOf<String, List<String>>()
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val e = strings.item(i) as org.w3c.dom.Element
                if (e.getAttribute("translatable") == "false") continue
                out[e.getAttribute("name")] = fmt.findAll(e.textContent).map { it.value }.sorted().toList()
            }
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val e = plurals.item(i) as org.w3c.dom.Element
                out["plurals:" + e.getAttribute("name")] = fmt.findAll(e.textContent).map { it.value }.distinct().sorted().toList()
            }
            return out
        }
        val base = read(java.io.File(res, "values/strings.xml"))
        val langs = res.listFiles { f -> f.name.startsWith("values-") && java.io.File(f, "strings.xml").exists() }!!
        assertTrue("almeno l'italiano", langs.any { it.name == "values-it" })
        langs.forEach { dir ->
            val t = read(java.io.File(dir, "strings.xml"))
            assertEquals("${dir.name}: chiavi", base.keys.sorted(), t.keys.sorted())
            base.forEach { (k, v) -> assertEquals("${dir.name}: segnaposto di $k", v, t[k]) }
        }
    }
}

class CompatibilityTest {
    /** Config scritte da versioni precedenti: i campi nuovi prendono il valore predefinito. */
    @Test fun newFieldsHaveDefaults() {
        val cfg = ConfigRepository.parse(
            """{"pages":[{"cols":2,"rows":5,"tiles":[{"id":"a","col":0,"row":0,"type":"call","target":"123","icon":{"kind":"photo"}}]}],"phone":{"badges":true}}""",
            "test",
        )!!
        assertTrue(cfg.phone.freshStart)
        assertFalse(cfg.pages[0].tiles[0].icon.fill)
    }
}

class OpenModeTest {
    @Test fun tileChoiceWinsOverPhone() {
        val t = Tile(id = "a", col = 0, row = 0, type = TileType.APP)
        val f: (Tile, Boolean) -> Boolean = { tile, d -> app.semplicephone.launch.tileFreshStart(tile, d) }
        assertTrue(f(t, true)); assertFalse(f(t, false))
        assertTrue(f(t.copy(open = "fresh"), false))
        assertFalse(f(t.copy(open = "resume"), true))
        assertTrue(f(t.copy(open = "valore-futuro"), true))
    }
}

class Features090Test {
    @Test fun unknownTileTypeDoesNotRejectConfig() {
        val cfg = ConfigRepository.parse(
            """{"pages":[{"cols":2,"rows":5,"tiles":[{"id":"x","col":0,"row":0,"type":"hologram"},{"id":"w","col":1,"row":0,"type":"weather","target":"44.49,11.34","label":"Bologna"}]}]}""",
            "test",
        )!!
        assertEquals(TileType.UNKNOWN, cfg.pages[0].tiles[0].type)
        assertEquals(TileType.WEATHER, cfg.pages[0].tiles[1].type)
        assertEquals(listOf(44.49, 11.34), app.semplicephone.data.Weather.parseTarget("44.49,11.34")!!.toList())
        org.junit.Assert.assertNull(app.semplicephone.data.Weather.parseTarget("abc"))
    }

    @Test fun oneDayReminder() {
        val zone = java.time.ZoneId.of("Europe/Rome")
        val now = java.time.ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone)
        val r = Reminder(id = "v", time = "10:30", date = "2026-11-05", icon = "doctor")
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 5, 10, 30, 0, 0, zone), ReminderTime.next(r, now))
        org.junit.Assert.assertNull(ReminderTime.next(r, now.plusDays(3)))
        assertTrue(ConfigRepository.validate(LauncherConfig(pages = listOf(Page()), reminders = listOf(r.copy(date = "2026-13-40")))).any { "invalid date" in it })
    }

    @Test fun callFilter() {
        val cfg = LauncherConfig(
            pages = listOf(Page(tiles = listOf(Tile(id = "m", col = 0, row = 0, type = TileType.CALL, target = "+39 333 1234567")))),
            sos = app.semplicephone.model.SosConfig(numbers = listOf("3477654321")),
        )
        val known = app.semplicephone.phone.CallFilter.knownNumbers(cfg)
        assertTrue(app.semplicephone.phone.CallFilter.allowed("3331234567", known))
        assertTrue(app.semplicephone.phone.CallFilter.allowed("+393477654321", known))
        assertFalse(app.semplicephone.phone.CallFilter.allowed("+390212345678", known))
        assertFalse(app.semplicephone.phone.CallFilter.allowed("", known))
    }
}
