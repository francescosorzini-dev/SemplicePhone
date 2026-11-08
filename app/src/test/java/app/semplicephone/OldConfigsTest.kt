package app.semplicephone

import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regola verso la 1.0: un aggiornamento non deve mai rompere una configurazione salvata. Qui si
 * caricano configurazioni delle versioni passate (predefinita della prima versione, portale, prove
 * su emulatore di ogni milestone): devono essere ancora valide e non perdere dati rileggendole.
 * Per aggiungerne una: copiarla in src/test/resources/old-configs/.
 */
class OldConfigsTest {
    private val dir = File(javaClass.classLoader!!.getResource("old-configs")!!.toURI())
    private val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }

    @Test fun thereAreFixtures() = assertTrue("almeno 10 configurazioni vecchie", files.size >= 10)

    @Test fun everyOldConfigStillLoads() {
        files.forEach { f -> assertNotNull("${f.name} non si carica più", ConfigRepository.parse(f.readText(), f.name)) }
    }

    @Test fun rereadingKeepsEverything() {
        files.forEach { f ->
            val c = ConfigRepository.parse(f.readText(), f.name)!!
            val again = ConfigRepository.parse(ConfigRepository.toJson(c), f.name)
            assertEquals(f.name, c, again)
        }
    }

    @Test fun tilesAndNumbersAreNotLost() {
        files.forEach { f ->
            val raw = f.readText()
            val c: LauncherConfig = ConfigRepository.parse(raw, f.name)!!
            val tilesInFile = Regex("\"type\"\\s*:").findAll(raw).count()
            assertTrue("${f.name}: riquadri persi", c.pages.sumOf { it.tiles.size } >= minOf(1, tilesInFile))
            if (raw.contains("\"sos\"") && raw.contains("\"numbers\"")) assertTrue("${f.name}: numeri SOS persi", c.sos.numbers.isNotEmpty() || raw.contains("\"numbers\":[]") || raw.contains("\"numbers\": []"))
        }
    }

    @Test fun newOptionsHaveSafeDefaults() {
        // Le configurazioni salvate prima della 0.19 non hanno la sensibilità: vale «media».
        val c = ConfigRepository.parse(File(dir, "default-early.json").readText(), "early")!!
        assertEquals("medium", c.phone.fallSensitivity)
        assertEquals("original", c.phone.appIcons)
    }
}
