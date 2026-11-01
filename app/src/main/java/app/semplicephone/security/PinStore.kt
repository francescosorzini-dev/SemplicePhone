package app.semplicephone.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN delle impostazioni, salvato come "pbkdf2-sha256$<iter>$<salt b64>$<hash b64>":
 * stesso formato che userà la config remota (M6) per impostare il PIN da lontano.
 * Dopo 5 errori consecutivi l'inserimento è bloccato per 30 s (cresce a ogni serie).
 */
class PinStore(context: Context) {

    private val prefs = context.getSharedPreferences("pin", Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.getString(KEY_HASH, null) != null

    suspend fun set(pin: String) {
        require(pin.length in PIN_LENGTH && pin.all(Char::isDigit)) { "PIN non valido" }
        val encoded = encode(pin)
        prefs.edit().putString(KEY_HASH, encoded).putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0).apply()
    }

    /** Usato da M6: imposta un hash già calcolato altrove, dopo averne verificato il formato. */
    fun setEncoded(encoded: String) {
        require(parse(encoded) != null) { "hash PIN non valido" }
        prefs.edit().putString(KEY_HASH, encoded).apply()
    }

    /** Millisecondi di blocco residui (0 = si può provare). */
    fun lockRemainingMs(): Long =
        (prefs.getLong(KEY_LOCK_UNTIL, 0) - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    suspend fun check(pin: String): Boolean {
        if (lockRemainingMs() > 0) return false
        val stored = prefs.getString(KEY_HASH, null)?.let(::parse) ?: return false
        val candidate = withContext(Dispatchers.Default) { pbkdf2(pin, stored.salt, stored.iterations) }
        val ok = MessageDigest.isEqual(candidate, stored.hash)
        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).putInt(KEY_SERIES, 0).apply()
        } else {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            if (fails >= MAX_FAILS) {
                val series = prefs.getInt(KEY_SERIES, 0) + 1
                // elapsedRealtime: cambiare l'ora di sistema non accorcia il blocco.
                val until = SystemClock.elapsedRealtime() + BASE_LOCK_MS * series
                prefs.edit().putInt(KEY_FAILS, 0).putInt(KEY_SERIES, series).putLong(KEY_LOCK_UNTIL, until).apply()
            } else {
                prefs.edit().putInt(KEY_FAILS, fails).apply()
            }
        }
        return ok
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(s: String): Parsed? {
        val p = s.split('$')
        if (p.size != 4 || p[0] != ALGO) return null
        val iter = p[1].toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return null
        return try {
            Parsed(iter, Base64.decode(p[2], Base64.NO_WRAP), Base64.decode(p[3], Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun encode(pin: String): String = withContext(Dispatchers.Default) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt, ITERATIONS)
        "$ALGO\$$ITERATIONS\$${b64(salt)}\$${b64(hash)}"
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        val PIN_LENGTH = 4..8
        private const val ALGO = "pbkdf2-sha256"
        private const val ITERATIONS = 60_000
        private const val MAX_FAILS = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val KEY_HASH = "hash"
        private const val KEY_FAILS = "fails"
        private const val KEY_SERIES = "series"
        private const val KEY_LOCK_UNTIL = "lock_until"
    }
}
