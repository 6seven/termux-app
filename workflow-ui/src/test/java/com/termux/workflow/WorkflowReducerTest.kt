package com.termux.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowReducerTest {
    @Test
    fun cachedDataBecomesReadOnlyWhenActiveProfileIsUnreachable() {
        val cached = WorkflowData(issues = listOf(IssueSummary("r", "42", "Cached issue")))
        val state = WorkflowReducer.reduce(
            WorkflowState(data = cached),
            WorkflowEvent.LoadFailed("unreachable", hasCache = true),
        )

        assertEquals(ConnectionMode.Offline, state.connectionMode)
        assertEquals(cached, state.data)
        assertFalse(state.canMutate)
    }

    @Test
    fun activationTracksSwitchingUntilRemoteConfirmation() {
        val switching = WorkflowReducer.reduce(
            WorkflowState(
                connectionMode = ConnectionMode.Online,
                data = WorkflowData(
                    targets = listOf(ActivationTarget("issue:42", "Issue 42", TargetKind.IssueWorkspace)),
                ),
            ),
            WorkflowEvent.ActivationStarted("issue:42"),
        )
        assertEquals("issue:42", switching.switchingTargetId)

        val active = WorkflowReducer.reduce(
            switching,
            WorkflowEvent.ActivationSucceeded(ActivationResult("issue:42", "dev", "RM-42")),
        )

        assertEquals("issue:42", active.data.tracker.activeTarget?.id)
        assertEquals("RM-42", active.data.tracker.activeTarget?.tmuxSession)
        assertEquals(null, active.switchingTargetId)
        assertTrue(active.canMutate)
    }

    @Test
    fun trackerActivationAcknowledgesTaskWithoutReplacingActiveTarget() {
        val activeTarget = ActivationTarget("issue:42", "Issue 42", TargetKind.IssueWorkspace, tmuxSession = "RM-42")
        val task = TrackerItem("$0", "dev", "@7", "project-manager", "%12", "completed", "Review result")
        val state = WorkflowState(
            connectionMode = ConnectionMode.Online,
            data = WorkflowData(
                tracker = TrackerState(activeTarget = activeTarget, items = listOf(task)),
            ),
            switchingTargetId = task.id,
        )

        val activated = WorkflowReducer.reduce(
            state,
            WorkflowEvent.TrackerActivationSucceeded(
                ActivationResult(task.id, "dev", "dev"),
                task.id,
            ),
        )

        assertEquals(null, activated.data.tracker.activeTarget)
        assertFalse(activated.data.tracker.items.single().acknowledged)
        assertEquals("dev", activated.data.tracker.connectionTmuxSession)
        assertEquals(null, activated.switchingTargetId)

        val acknowledged = WorkflowReducer.reduce(
            activated,
            WorkflowEvent.TrackerTaskAcknowledged(task.id),
        )

        assertTrue(acknowledged.data.tracker.items.single().acknowledged)
    }

    @Test
    fun unavailableTrackerRefreshRetainsCachedAuthoritativeData() {
        val activeTarget = ActivationTarget("issue:42", "Issue 42", TargetKind.IssueWorkspace, tmuxSession = "RM-42")
        val task = TrackerItem("$0", "dev", "@7", "project-manager", "%12", "in_progress", "Implement")
        val cached = TrackerState(activeTarget = activeTarget, items = listOf(task))
        val state = WorkflowState(connectionMode = ConnectionMode.Online, data = WorkflowData(tracker = cached))
        val unavailable = WorkflowData(tracker = TrackerState(available = false, error = "Tracker client is unavailable."))

        val refreshed = WorkflowReducer.reduce(state, WorkflowEvent.DataLoaded(unavailable))

        assertEquals(activeTarget, refreshed.data.tracker.activeTarget)
        assertEquals(listOf(task), refreshed.data.tracker.items)
        assertFalse(refreshed.data.tracker.available)
        assertEquals("Tracker client is unavailable.", refreshed.data.tracker.error)
    }

    @Test
    fun bindingWorkspaceFlowDoesNotRollBackSavedBindingWhenWorkspaceFails() {
        val saved = WorkflowReducer.reduce(WorkflowState(), WorkflowEvent.BindingSaved("r", "42"))
        val failed = WorkflowReducer.reduce(saved, WorkflowEvent.WorkspaceStartFailed("creation failed"))

        assertEquals(BindingFlowStage.BindingSaved, failed.bindingFlow?.stage)
        assertEquals("creation failed", failed.error)
    }
}
