package app.semplicephone

import app.semplicephone.data.ConfigRepository
import app.semplicephone.remote.Envelope
import app.semplicephone.remote.RemotePayload
import app.semplicephone.remote.Signature
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * La busta in test/resources è stata prodotta dal server PHP (tools/remote-server):
 * verifica l'interoperabilità sodium (PHP) ↔ BouncyCastle (app) e il formato del payload.
 */
class RemoteEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun rfc8032Vector1() {
        val pk = Base64.getEncoder().encodeToString(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
        val sig = Base64.getEncoder().encodeToString(hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ))
        assertTrue(Signature.verify(pk, ByteArray(0), sig))
        assertFalse(Signature.verify(pk, byteArrayOf(1), sig))
    }

    @Test fun phpEnvelopeVerifiesAndDecodes() {
        val env = json.decodeFromString(Envelope.serializer(), res("envelope.json"))
        val pk = res("pubkey.txt").trim()
        val payload = Base64.getDecoder().decode(env.payload)
        assertTrue(Signature.verify(pk, payload, env.sig))

        val p = json.decodeFromString(RemotePayload.serializer(), payload.toString(Charsets.UTF_8))
        assertEquals("abc123defg", p.device)
        assertNotNull(p.config)
        assertEquals(emptyList<String>(), ConfigRepository.validate(p.config!!))
        assertTrue(p.pinHash!!.startsWith("pbkdf2-sha256$60000$"))
    }

    @Test fun tamperedPayloadIsRejected() {
        val env = json.decodeFromString(Envelope.serializer(), res("envelope.json"))
        val payload = Base64.getDecoder().decode(env.payload).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(Signature.verify(res("pubkey.txt").trim(), payload, env.sig))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
