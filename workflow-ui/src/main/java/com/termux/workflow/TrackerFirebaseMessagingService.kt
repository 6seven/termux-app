package com.termux.workflow

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TrackerFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        TrackerPushRegistration.registerToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val trackerMessage = TrackerPushMessage.from(message.data) ?: return
        TrackerPushRefresh.enqueue(this, trackerMessage)
    }
}
