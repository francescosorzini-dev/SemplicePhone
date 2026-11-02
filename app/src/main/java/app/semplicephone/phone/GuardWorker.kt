package app.semplicephone.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Rete di sicurezza ogni 15 minuti: suoneria e batteria anche se il processo era stato chiuso. */
class GuardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { PhoneGuard.checkAll(applicationContext) }
        return Result.success()
    }
}
