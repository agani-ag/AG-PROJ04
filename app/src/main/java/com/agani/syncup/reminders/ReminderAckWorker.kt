package com.agani.syncup.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agani.syncup.push.AppBootstrap
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reliably reports a "fired" receipt after a reminder's alarm shows the notification — even if the
 * app process was dead. Runs when the network is available and retries on failure.
 */
class ReminderAckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        runCatching { withTimeoutOrNull(4000) { AppBootstrap.applyBaseUrl() } }
        val ok = ReminderAck.report(applicationContext, listOf(id), ReminderAck.FIRED)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_ID = "reminder_id"

        fun reportFired(context: Context, reminderId: String) {
            val request = OneTimeWorkRequestBuilder<ReminderAckWorker>()
                .setInputData(workDataOf(KEY_ID to reminderId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
