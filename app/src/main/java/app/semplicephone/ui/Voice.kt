package app.semplicephone.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Voce del telefono (sintesi vocale di sistema): legge i riquadri e annuncia le azioni.
 * Se la sintesi non è disponibile l'azione parte comunque, senza voce.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val after = mutableMapOf<String, () -> Unit>()
    /** Frasi che la sintesi ha davvero iniziato a dire. */
    private val started = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var counter = 0

    private fun ensure(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) { started += id }
                    override fun onDone(id: String) = finish(id)
                    // Frase interrotta da una nuova (QUEUE_FLUSH): l'azione «dopo» non parte.
                    override fun onStop(id: String, interrupted: Boolean) { synchronized(after) { after.remove(id) }; started -= id }
                    @Deprecated("API precedente") override fun onError(id: String) = finish(id)
                    override fun onError(id: String, errorCode: Int) = finish(id)
                })
            }
        }
    }

    private var current: java.util.Locale? = null

    /**
     * Lingua dell'app (scelta per-app di Android 13+, altrimenti quella del contesto).
     * Va riletta a ogni frase: la lingua può cambiare mentre la sintesi resta inizializzata.
     */
    private fun appLocale(context: Context): java.util.Locale {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val app = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            if (app != null && !app.isEmpty) return app[0]
        }
        return context.resources.configuration.locales[0]
    }

    private fun applyLanguage(context: Context, t: TextToSpeech) {
        val wanted = appLocale(context)
        if (wanted == current) return
        // Prima la variante esatta (en-US), poi la sola lingua (en): alcune voci hanno solo quella.
        val ok = t.setLanguage(wanted) >= TextToSpeech.LANG_AVAILABLE ||
            t.setLanguage(java.util.Locale(wanted.language)) >= TextToSpeech.LANG_AVAILABLE
        current = if (ok) wanted else null
    }

    private fun finish(id: String) {
        started -= id
        val cb = synchronized(after) { after.remove(id) } ?: return
        main.post(cb)
    }

    /** Dice [text]; poi esegue [then] (al termine della frase, o subito se la voce non c'è). */
    fun speak(context: Context, text: String, then: (() -> Unit)? = null) {
        ensure(context)
        val t = tts
        if (t == null || !ready || text.isBlank()) {
            // La sintesi si inizializza in modo asincrono: alla prima richiesta si riprova tra poco.
            if (t != null && !ready && text.isNotBlank()) {
                main.postDelayed({ if (ready) speak(context, text, then) else then?.invoke() }, 1200)
            } else then?.invoke()
            return
        }
        applyLanguage(context, t)
        val id = "u${counter++}"
        if (then != null) {
            synchronized(after) { after[id] = then }
            // Rete di sicurezza solo se la voce non parte affatto; se sta parlando si aspetta la fine
            // vera (onDone), con un tetto largo per i motori che non la segnalano.
            main.postDelayed({ if (id !in started) finish(id) }, 5000)
            main.postDelayed({ finish(id) }, maxOf(20_000L, text.length * 300L))
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }
}
