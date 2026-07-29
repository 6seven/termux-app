package com.termux.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowDestinationTest {
    @Test
    fun everyDrawerValueMapsToOneSignalRailDestination() {
        val values = listOf("tracker", "issues", "projects", "workspaces", "usage")

        assertEquals(WorkflowDestination.entries, values.map(WorkflowDestination::fromExtra))
    }

    @Test
    fun missingDrawerExtraDefaultsToTracker() {
        assertEquals(WorkflowDestination.Tracker, WorkflowDestination.fromExtra(null))
    }
}
