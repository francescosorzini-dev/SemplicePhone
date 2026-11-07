package app.semplicephone

import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.PhoneConfig
import app.semplicephone.model.SosConfig
import app.semplicephone.phone.OutgoingRules
import app.semplicephone.phone.ScamRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Release0170Test {
    private val cfg = LauncherConfig(sos = SosConfig(numbers = listOf("+39 333 1234567")), phone = PhoneConfig(blockPremium = true, blockInternational = true))

    @Test fun premiumAndInternationalBlocked() {
        assertTrue(OutgoingRules.isPremium("899123456"))
        assertTrue(OutgoingRules.isPremium("+39 892 424"))
        assertTrue(OutgoingRules.isPremium("4800"))
        assertFalse(OutgoingRules.isPremium("0612345678"))
        assertFalse(OutgoingRules.isPremium("3331234567"))
        assertTrue(OutgoingRules.isInternational("+44 20 7946 0000"))
        assertTrue(OutgoingRules.isInternational("0033123456789"))
        assertFalse(OutgoingRules.isInternational("+39 06 1234567"))
        assertEquals(R.string.call_out_blocked_premium, OutgoingRules.blockReason(cfg, "899000111"))
        assertEquals(R.string.call_out_blocked_international, OutgoingRules.blockReason(cfg, "+447946000000"))
        assertNull(OutgoingRules.blockReason(cfg, "112"))
        assertNull(OutgoingRules.blockReason(cfg, "3331234567")) // numero SOS sempre ammesso
        assertNull(OutgoingRules.blockReason(cfg.copy(phone = PhoneConfig()), "899000111"))
    }

    @Test fun scamMessages() {
        assertTrue(ScamRules.hasLink("Il tuo pacco è in giacenza: https://poste-it.top/x"))
        assertTrue(ScamRules.hasLink("vai su www.banca-sicura.com"))
        assertFalse(ScamRules.hasLink("Ci vediamo alle 5. Ciao"))
        assertTrue(ScamRules.looksUnknown("+39 351 000 1122"))
        assertTrue(ScamRules.looksUnknown("INFOPOSTE"))
        assertFalse(ScamRules.looksUnknown("Maria Rossi"))
        assertTrue(ScamRules.suspicious("+39 351 000 1122", "Sblocca il conto: bit.ly/abc") { false })
        assertFalse(ScamRules.suspicious("+39 351 000 1122", "Sblocca il conto: bit.ly/abc") { true })
        assertFalse(ScamRules.suspicious("Maria", "guarda https://youtube.com/x") { null })
    }
}
