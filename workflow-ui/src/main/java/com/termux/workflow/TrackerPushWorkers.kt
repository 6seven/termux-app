package com.termux.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

class TrackerPushRegistrationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = HostProfileStore(applicationContext)
        val registrationToken = TrackerPushRegistration.registrationToken(this).ifBlank(store::pushRegistrationToken)
        if (registrationToken.isBlank()) return workerResult(IllegalStateException("FCM registration token is unavailable"))
        val requestedProfileId = TrackerPushRegistration.profileId(this)
        val profile = store.profiles().firstOrNull { it.id == requestedProfileId } ?: return Result.success()
        return runCatching {
            WorkflowApiClient(profile.pmgrUrl, store.token(profile.id)).registerPushDevice(
                store.deviceId(),
                profile.id,
                registrationToken,
            )
            Result.success()
        }.getOrElse(::workerResult)
    }
}

class TrackerPushRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val target = TrackerPushRefresh.target(this)
        val store = HostProfileStore(applicationContext)
        val profile = store.profiles().firstOrNull { it.id == target.profileId }
        if (profile == null) {
            TrackerNotifications.cancel(applicationContext, target)
            return Result.success()
        }
        return runCatching {
            val cache = WorkflowCache(applicationContext)
            val cached = cache.read(profile.id) ?: WorkflowData()
            val tracker = WorkflowApiClient(profile.pmgrUrl, store.token(profile.id)).trackerState()
            if (!tracker.available) throw WorkflowApiException(503, tracker.error ?: "Tracker is unavailable")
            cache.write(
                profile.id,
                cached.copy(tracker = tracker.withCachedConnection(cached.tracker)),
            )
            TrackerNotifications.reconcile(applicationContext, profile.id, tracker)
            val item = tracker.alertItem(target)
            if (item == null) {
                TrackerNotifications.cancel(applicationContext, target)
            } else {
                TrackerNotifications.show(applicationContext, target, item)
            }
            TrackerPushUpdates.publish(profile.id)
            Result.success()
        }.getOrElse(::workerResult)
    }
}

private fun CoroutineWorker.workerResult(error: Throwable): ListenableWorker.Result {
    val retryable = error !is WorkflowApiException || error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
    return if (retryable && runAttemptCount < 5) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
}
