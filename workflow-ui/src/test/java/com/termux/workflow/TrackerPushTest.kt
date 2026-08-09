package com.termux.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerPushTest {
    @Test
    fun parsesTrackerStateChangeWithoutTaskDetails() {
        val message = TrackerPushMessage.from(
            mapOf(
                "type" to "tracker_state_changed",
                "event_id" to "event-1",
                "profile_id" to "profile-1",
                "change" to "completed",
                "session_id" to "$0",
                "session" to "dev",
                "window_id" to "@7",
                "window" to "project-manager",
                "pane" to "%12",
                "attention_reason" to "waiting",
            ),
        )

        requireNotNull(message)
        assertEquals("event-1", message.target.eventId)
        assertEquals("completed", message.target.change)
        assertEquals("$0::@7::%12", message.target.id)
    }

    @Test
    fun rejectsMessageWithoutProfileOrTmuxCoordinates() {
        assertNull(
            TrackerPushMessage.from(
                mapOf(
                    "type" to "tracker_state_changed",
                    "change" to "completed",
                ),
            ),
        )
    }

    @Test
    fun alertRequiresAuthoritativeUnacknowledgedCompletion() {
        val target = TrackerPushTarget("event-1", "waiting", "profile-1", "$0", "dev", "@7", "project", "%12")
        val completed = TrackerItem("$0", "dev", "@7", "project", "%12", "completed", "Review")
        val waiting = TrackerItem("$0", "dev", "@7", "project", "%12", "in_progress", "Approve", "waiting")

        assertEquals(completed, TrackerState(items = listOf(completed)).alertItem(target))
        assertEquals(waiting, TrackerState(items = listOf(waiting)).alertItem(target))
        assertNull(TrackerState(items = listOf(completed.copy(acknowledged = true))).alertItem(target))
        assertNull(TrackerState(items = listOf(waiting.copy(acknowledged = true))).alertItem(target))
        assertNull(TrackerState().alertItem(target))
    }

    @Test
    fun notificationIdentityIncludesHostProfile() {
        val first = TrackerPushTarget("event-1", "completed", "profile-1", "$0", "dev", "@7", "project", "%12")
        val second = first.copy(profileId = "profile-2")

        assertNotEquals(TrackerNotifications.notificationId(first), TrackerNotifications.notificationId(second))
    }

    @Test
    fun permissionWaitUsesSpecificNotificationMessage() {
        val permission = TrackerItem(
            "$0",
            "dev",
            "@7",
            "project",
            "%12",
            "in_progress",
            "Approve",
            attentionReason = "waiting",
            eventType = "permission",
        )

        assertEquals("AI Task needs permission", trackerNotificationBody(permission))
        assertEquals(
            "AI Task is waiting for input",
            trackerNotificationBody(permission.copy(eventType = "question")),
        )
    }
}
