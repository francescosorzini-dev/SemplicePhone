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
    private var counter = 0

    private fun ensure(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = context.resources.configuration.locales[0]
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) = finish(id)
                    @Deprecated("API precedente") override fun onError(id: String) = finish(id)
                    override fun onError(id: String, errorCode: Int) = finish(id)
                })
            }
        }
    }

    private fun finish(id: String) {
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
        val id = "u${counter++}"
        if (then != null) {
            synchronized(after) { after[id] = then }
            // Rete di sicurezza: se la sintesi non segnala la fine, l'azione parte lo stesso.
            main.postDelayed({ finish(id) }, 4000)
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }
}
