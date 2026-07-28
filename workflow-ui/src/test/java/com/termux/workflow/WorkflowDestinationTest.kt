package com.termux.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowDestinationTest {
    @Test
    fun everyDrawerValueMapsToOneSignalRailDestination() {
        val values = listOf("issues", "projects", "workspaces", "usage", "tracker")

        assertEquals(WorkflowDestination.entries, values.map(WorkflowDestination::fromExtra))
    }

    @Test
    fun missingDrawerExtraDefaultsToIssues() {
        assertEquals(WorkflowDestination.Issues, WorkflowDestination.fromExtra(null))
    }
}
