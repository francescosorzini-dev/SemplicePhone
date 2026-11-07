package app.semplicephone.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Torcia: il flash della fotocamera posteriore acceso o spento con un tocco (nessun permesso).
 * Si spegne da sola dopo 15 minuti, per non scaricare la batteria se ci si dimentica.
 */
object Torch {
    private const val TAG = "Torch"
    private const val AUTO_OFF_MS = 15 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val off = Runnable { appContext?.let { set(it, false) } }
    private var appContext: Context? = null
    private var callbackOn = false

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private fun cameraId(context: Context): String? = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    fun available(context: Context): Boolean = cameraId(context) != null

    fun toggle(context: Context) = set(context, !_on.value)

    fun set(context: Context, value: Boolean) {
        val app = context.applicationContext
        appContext = app
        val id = cameraId(app) ?: return
        val cm = app.getSystemService(CameraManager::class.java)
        if (!callbackOn) {
            // Segue anche la torcia accesa/spenta dal menu rapido di Android.
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) _on.value = enabled }
            }, main)
            callbackOn = true
        }
        runCatching { cm.setTorchMode(id, value) }
            .onSuccess {
                _on.value = value
                main.removeCallbacks(off)
                if (value) main.postDelayed(off, AUTO_OFF_MS)
            }
            .onFailure { Log.w(TAG, "torcia non comandabile", it) }
    }
}
