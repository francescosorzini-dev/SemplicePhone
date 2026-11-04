package app.semplicephone.launch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.semplicephone.MainActivity
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import kotlin.math.abs

/**
 * Pulsante «Casa» sopra le altre app (permesso «Mostra sopra altre app»): compare quando la Home
 * non è visibile e un tocco riporta al launcher. Si sposta trascinandolo in verticale; la posizione
 * resta salvata. Nascosto durante le chiamate, per non coprire i tasti e non lasciare la chiamata
 * aperta dietro la Home per sbaglio.
 *
 * Nessun servizio: la finestra appartiene al processo del launcher, che resta vivo (è la Home e
 * una finestra visibile lo rende «percepibile» per il sistema).
 */
object HomeButton {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: View? = null
    private var started = 0
    private lateinit var app: Application

    /**
     * Nascosto per l'app aperta da un riquadro con «Pulsante Casa» spento; torna normale appena la
     * Home è di nuovo visibile.
     */
    private var suppressed = false

    /** Pura (testabile): il pulsante va mostrato? */
    fun shouldShow(enabled: Boolean, canDraw: Boolean, appVisible: Boolean, inCall: Boolean, suppressed: Boolean = false): Boolean =
        enabled && canDraw && !appVisible && !inCall && !suppressed

    /** Allarme dei promemoria a schermo (gira nel processo «:alarm», che lo segnala con un broadcast). */
    private var alarmVisible = false
    const val ACTION_ALARM = "app.semplicephone.ALARM_VISIBLE"

    /** Chiamato dall'allarme in onStart/onStop: arriva al processo principale come broadcast interno. */
    fun notifyAlarm(context: Context, visible: Boolean) {
        context.sendBroadcast(Intent(ACTION_ALARM).setPackage(context.packageName).putExtra("visible", visible))
    }

    /** Da chiamare subito prima di aprire l'app di un riquadro. */
    fun forNextApp(show: Boolean) { suppressed = !show }

    /** Da chiamare una volta in Application.onCreate: segue le Activity del launcher e le chiamate. */
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; suppressed = false; update(a) }
            override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0); main.postDelayed({ update(application) }, 400) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        androidx.core.content.ContextCompat.registerReceiver(application, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { alarmVisible = i.getBooleanExtra("visible", false); update(c) }
        }, android.content.IntentFilter(ACTION_ALARM), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                application.getSystemService(AudioManager::class.java)
                    .addOnModeChangedListener(application.mainExecutor) { update(application) }
            }
        }
    }

    private fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    /** Mostra o nasconde il pulsante secondo config, permesso, Home visibile e chiamata in corso. */
    fun update(context: Context) {
        if (!::app.isInitialized) return
        main.post {
            val enabled = ConfigRepository.get(app).config.value.phone.homeButton
            val show = shouldShow(enabled, Settings.canDrawOverlays(app), started > 0 || alarmVisible, inCall(app), suppressed)
            if (show) attach() else detach()
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        runCatching { app.getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        if (view != null) return
        val wm = app.getSystemService(WindowManager::class.java)
        val prefs = app.getSharedPreferences("home_button", Context.MODE_PRIVATE)
        // Dimensione fissa e testo che si rimpicciolisce da solo: «CASA», «ACCUEIL», «INICIO»…
        // entrano sempre, con qualsiasi carattere o dimensione di visualizzazione.
        val width = dp(84f)
        val pill = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.parseColor("#1F4FA8"))
                setStroke(dp(3f), Color.WHITE)
            }
            contentDescription = app.getString(R.string.home_button_desc)
            addView(android.widget.ImageView(app).apply {
                setImageResource(R.drawable.ic_home_button)
                layoutParams = LinearLayout.LayoutParams(dp(42f), dp(42f))
            })
            addView(TextView(app).apply {
                text = app.getString(R.string.home_button_label)
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24f)).apply { topMargin = dp(4f) }
                setAutoSizeTextTypeUniformWithConfiguration(9, 17, 1, TypedValue.COMPLEX_UNIT_DIP)
            })
        }
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Niente FLAG_LAYOUT_NO_LIMITS: il pulsante resta dentro lo schermo (bordi curvi, angoli arrotondati).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = dp(10f)
            // Predefinito a un terzo dello schermo: sotto le barre in alto, sopra tastiere e tastierini.
            y = prefs.getInt("y", (app.resources.displayMetrics.heightPixels * 0.30f).toInt())
        }
        // Trascinamento verticale; un tocco senza spostamento torna alla Home.
        var downY = 0f
        var startY = 0
        var moved = false
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false; pill.alpha = 0.8f }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > dp(12f)) moved = true
                    if (moved) {
                        val max = app.resources.displayMetrics.heightPixels - pill.height - dp(24f)
                        lp.y = (startY + dy.toInt()).coerceIn(dp(24f), max.coerceAtLeast(dp(24f)))
                        runCatching { wm.updateViewLayout(pill, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    pill.alpha = 1f
                    if (moved) prefs.edit().putInt("y", lp.y).apply() else goHome()
                }
                MotionEvent.ACTION_CANCEL -> pill.alpha = 1f
            }
            true
        }
        runCatching { wm.addView(pill, lp) }.onSuccess { view = pill }.onFailure { android.util.Log.w("HomeButton", "pulsante non mostrato", it) }
    }

    private fun goHome() {
        // Durante una chiamata non si lascia la schermata della chiamata (su Android 10-11 il
        // pulsante può restare visibile: non c'è un avviso di sistema per l'inizio della chiamata).
        if (inCall(app)) { detach(); return }
        detach()
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }
}
