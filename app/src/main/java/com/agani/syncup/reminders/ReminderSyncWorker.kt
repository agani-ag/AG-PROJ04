package com.agani.syncup.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agani.syncup.push.AppBootstrap
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Daily safety-net re-sync of reminders, so a device still catches admin changes even if the
 * user doesn't open the app that day. Applies the Remote Config base URL first, since a
 * background process (post-reboot) may not have run the app's normal bootstrap yet.
 * The primary sync path is on login / app-open / pull-to-refresh (see ReminderSync).
 */
class ReminderSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { withTimeoutOrNull(4000) { AppBootstrap.applyBaseUrl() } }
        ReminderSync.sync(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_DAILY = "reminder_sync_daily"

        /** Daily background sync so devices catch changes even without opening the app. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
