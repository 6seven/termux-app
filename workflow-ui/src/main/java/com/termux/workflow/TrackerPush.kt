package com.termux.workflow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class TrackerPushTarget(
    val eventId: String,
    val profileId: String,
    val sessionId: String,
    val session: String,
    val windowId: String,
    val window: String,
    val pane: String,
) {
    val id: String get() = "$sessionId::$windowId::$pane"

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_PROFILE_ID, profileId)
        .putExtra(EXTRA_EVENT_ID, eventId)
        .putExtra(EXTRA_SESSION_ID, sessionId)
        .putExtra(EXTRA_SESSION, session)
        .putExtra(EXTRA_WINDOW_ID, windowId)
        .putExtra(EXTRA_WINDOW, window)
        .putExtra(EXTRA_PANE, pane)

    companion object {
        private const val EXTRA_PROFILE_ID = "com.termux.workflow.extra.TRACKER_PROFILE_ID"
        private const val EXTRA_EVENT_ID = "com.termux.workflow.extra.TRACKER_EVENT_ID"
        private const val EXTRA_SESSION_ID = "com.termux.workflow.extra.TRACKER_SESSION_ID"
        private const val EXTRA_SESSION = "com.termux.workflow.extra.TRACKER_SESSION"
        private const val EXTRA_WINDOW_ID = "com.termux.workflow.extra.TRACKER_WINDOW_ID"
        private const val EXTRA_WINDOW = "com.termux.workflow.extra.TRACKER_WINDOW"
        private const val EXTRA_PANE = "com.termux.workflow.extra.TRACKER_PANE"

        fun fromIntent(intent: Intent?): TrackerPushTarget? {
            val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
            val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            val windowId = intent?.getStringExtra(EXTRA_WINDOW_ID).orEmpty()
            val pane = intent?.getStringExtra(EXTRA_PANE).orEmpty()
            if (profileId.isBlank() || sessionId.isBlank() || windowId.isBlank() || pane.isBlank()) return null
            return TrackerPushTarget(
                eventId = intent?.getStringExtra(EXTRA_EVENT_ID).orEmpty(),
                profileId = profileId,
                sessionId = sessionId,
                session = intent?.getStringExtra(EXTRA_SESSION).orEmpty(),
                windowId = windowId,
                window = intent?.getStringExtra(EXTRA_WINDOW).orEmpty(),
                pane = pane,
            )
        }
    }
}

data class TrackerPushMessage(
    val target: TrackerPushTarget,
) {
    companion object {
        fun from(data: Map<String, String>): TrackerPushMessage? {
            if (data["type"] != "tracker_state_changed") return null
            val target = TrackerPushTarget(
                eventId = data["event_id"].orEmpty(),
                profileId = data["profile_id"].orEmpty(),
                sessionId = data["session_id"].orEmpty(),
                session = data["session"].orEmpty(),
                windowId = data["window_id"].orEmpty(),
                window = data["window"].orEmpty(),
                pane = data["pane"].orEmpty(),
            )
            if (target.profileId.isBlank() || target.sessionId.isBlank() || target.windowId.isBlank() || target.pane.isBlank()) return null
            return TrackerPushMessage(target)
        }
    }
}

internal fun TrackerState.alertItem(target: TrackerPushTarget): TrackerItem? =
    items.firstOrNull { it.id == target.id && !it.acknowledged && (it.completed || it.waiting) }

object TrackerPushRegistration {
    private const val TOKEN = "registration_token"
    private const val PROFILE_ID = "profile_id"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun registerAll(context: Context) = requestToken(context, null)

    fun registerProfile(context: Context, profileId: String) = requestToken(context, profileId)

    fun registerToken(context: Context, registrationToken: String, profileId: String? = null) {
        val store = HostProfileStore(context)
        store.savePushRegistrationToken(registrationToken)
        val profileIds = profileId?.let(::listOf) ?: store.profiles().map(HostProfile::id)
        profileIds.forEach { id ->
            val request = OneTimeWorkRequestBuilder<TrackerPushRegistrationWorker>()
                .setInputData(workDataOf(TOKEN to registrationToken, PROFILE_ID to id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tracker-push-registration:$id",
                ExistingWorkPolicy.REPLACE,
                request,
            )
            ensurePeriodicRegistration(context, id)
        }
    }

    fun unregister(context: Context, profile: HostProfile, apiToken: String) {
        WorkManager.getInstance(context).cancelUniqueWork(periodicRegistrationName(profile.id))
        val deviceId = HostProfileStore(context).deviceId()
        scope.launch {
            runCatching {
                WorkflowApiClient(profile.pmgrUrl, apiToken).unregisterPushDevice(deviceId, profile.id)
            }
        }
    }

    private fun requestToken(context: Context, profileId: String?) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val profileIds = profileId?.let(::listOf) ?: HostProfileStore(context).profiles().map(HostProfile::id)
        profileIds.forEach { ensurePeriodicRegistration(context, it) }
        runCatching { FirebaseMessaging.getInstance().token }.getOrNull()
            ?.addOnSuccessListener { token -> registerToken(context, token, profileId) }
    }

    private fun ensurePeriodicRegistration(context: Context, profileId: String) {
        val request = PeriodicWorkRequestBuilder<TrackerPushRegistrationWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf(PROFILE_ID to profileId))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicRegistrationName(profileId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun periodicRegistrationName(profileId: String): String = "tracker-push-registration-renewal:$profileId"

    internal fun registrationToken(worker: TrackerPushRegistrationWorker): String = worker.inputData.getString(TOKEN).orEmpty()
    internal fun profileId(worker: TrackerPushRegistrationWorker): String = worker.inputData.getString(PROFILE_ID).orEmpty()
}

object TrackerPushRefresh {
    private const val EVENT_ID = "event_id"
    private const val PROFILE_ID = "profile_id"
    private const val SESSION_ID = "session_id"
    private const val SESSION = "session"
    private const val WINDOW_ID = "window_id"
    private const val WINDOW = "window"
    private const val PANE = "pane"

    fun enqueue(context: Context, message: TrackerPushMessage) {
        val request = OneTimeWorkRequestBuilder<TrackerPushRefreshWorker>()
            .setInputData(
                workDataOf(
                    EVENT_ID to message.target.eventId,
                    PROFILE_ID to message.target.profileId,
                    SESSION_ID to message.target.sessionId,
                    SESSION to message.target.session,
                    WINDOW_ID to message.target.windowId,
                    WINDOW to message.target.window,
                    PANE to message.target.pane,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "tracker-push-refresh:${message.target.profileId}:${message.target.id}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    internal fun target(worker: TrackerPushRefreshWorker): TrackerPushTarget = TrackerPushTarget(
        eventId = worker.inputData.getString(EVENT_ID).orEmpty(),
        profileId = worker.inputData.getString(PROFILE_ID).orEmpty(),
        sessionId = worker.inputData.getString(SESSION_ID).orEmpty(),
        session = worker.inputData.getString(SESSION).orEmpty(),
        windowId = worker.inputData.getString(WINDOW_ID).orEmpty(),
        window = worker.inputData.getString(WINDOW).orEmpty(),
        pane = worker.inputData.getString(PANE).orEmpty(),
    )
}

object TrackerPushUpdates {
    private val mutableUpdates = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val updates = mutableUpdates.asSharedFlow()

    fun publish(profileId: String) {
        mutableUpdates.tryEmit(profileId)
    }
}

object TrackerNotifications {
    private const val CHANNEL_ID = "workflow_tracker_alerts"
    private const val PREFERENCES = "workflow_tracker_notifications"
    private const val STORED_KEYS = "stored_keys"
    private val lock = Any()

    fun initialize(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tracker Alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun show(context: Context, target: TrackerPushTarget, item: TrackerItem) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        initialize(context)
        val notificationId = notificationId(target)
        val intent = target.putInto(WorkflowDestination.Tracker.intent(context))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = item.window.ifBlank { "Workflow Console" }
        val body = when (item.attentionReason) {
            "waiting" -> "AI Task is waiting for input"
            "error" -> "AI Task needs attention"
            else -> "AI Task completed"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            remember(context, target)
        }
    }

    fun cancel(context: Context, target: TrackerPushTarget) {
        NotificationManagerCompat.from(context).cancel(notificationId(target))
        forget(context, key(target))
    }

    fun reconcile(context: Context, profileId: String, tracker: TrackerState) {
        if (!tracker.available) return
        val activeKeys = tracker.items
            .filter { !it.acknowledged && (it.completed || it.waiting) }
            .mapTo(mutableSetOf()) { "$profileId:${it.id}" }
        val staleKeys = storedKeys(context).filter { it.startsWith("$profileId:") && it !in activeKeys }
        staleKeys.forEach { key -> NotificationManagerCompat.from(context).cancel(key.hashCode()) }
        if (staleKeys.isNotEmpty()) updateStoredKeys(context) { it.removeAll(staleKeys.toSet()) }
    }

    internal fun notificationId(target: TrackerPushTarget): Int = key(target).hashCode()

    private fun key(target: TrackerPushTarget): String = "${target.profileId}:${target.id}"

    private fun remember(context: Context, target: TrackerPushTarget) = updateStoredKeys(context) { it.add(key(target)) }

    private fun forget(context: Context, targetKey: String) = updateStoredKeys(context) { it.remove(targetKey) }

    private fun storedKeys(context: Context): Set<String> = synchronized(lock) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getStringSet(STORED_KEYS, emptySet()).orEmpty().toSet()
    }

    private fun updateStoredKeys(context: Context, update: (MutableSet<String>) -> Unit) {
        synchronized(lock) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val keys = preferences.getStringSet(STORED_KEYS, emptySet()).orEmpty().toMutableSet()
            update(keys)
            preferences.edit().putStringSet(STORED_KEYS, keys).commit()
        }
    }
}
