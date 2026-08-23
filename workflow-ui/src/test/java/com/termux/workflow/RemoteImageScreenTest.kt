package com.termux.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class RemoteImageScreenTest {

    @Test
    fun imageWorkspacesAreActiveUniqueAndCurrentFirst() {
        val duplicate = ActivationTarget(
            id = "workspace:1321-duplicate",
            name = "RM 1321 duplicate",
            kind = TargetKind.IssueWorkspace,
            issueId = "1321",
            directory = "/Users/dev/redmine/rm-1321/main/",
        )
        val active = duplicate.copy(
            id = "workspace:1321",
            name = "RM 1321",
            directory = "/Users/dev/redmine/rm-1321/main",
        )
        val unused = duplicate.copy(
            id = "workspace:165",
            name = "RM 165",
            issueId = "165",
            directory = "/Users/dev/redmine/rm-165/main",
        )
        val current = duplicate.copy(
            id = "workspace:current",
            name = "Current workspace",
            issueId = "197",
            directory = "/Users/dev/redmine/rm-197/main",
        )

        val options = imageWorkspaceOptions(
            workspaces = listOf(duplicate, unused, active, current),
            activeIssueIds = setOf("1321"),
            currentWorkspaceId = current.id,
        )

        assertEquals(listOf(current.id, active.id), options.map { it.id })
    }

    @Test
    fun staleWorkspaceCacheRequestsRefresh() {
        val stale = ActivationTarget(
            id = "workspace:w1",
            name = "RM 1321",
            kind = TargetKind.IssueWorkspace,
        )
        val ready = stale.copy(directory = "/Users/dev/redmine/rm-1321/main")

        assertTrue(needsImageWorkspaceRefresh(enabled = true, workspaces = listOf(stale)))
        assertFalse(needsImageWorkspaceRefresh(enabled = true, workspaces = listOf(ready)))
        assertFalse(needsImageWorkspaceRefresh(enabled = false, workspaces = listOf(stale)))
        assertFalse(needsImageWorkspaceRefresh(enabled = true, workspaces = emptyList()))
    }

    @Test
    fun relativeImagePathUsesSelectedWorkspaceDirectory() {
        assertEquals(
            "/Users/dev/redmine/rm-1321/main/assets/preview.png",
            resolveRemoteImagePath(
                "/Users/dev/redmine/rm-1321/main/",
                " assets/preview.png ",
            ),
        )
    }

    @Test
    fun repeatedWorkspaceDirectoryNameIsRemovedFromRelativePath() {
        assertEquals(
            "/Users/dev/redmine/rm-1321/main/assets/preview.png",
            resolveRemoteImagePath(
                "/Users/dev/redmine/rm-1321/main",
                "main/assets/preview.png",
            ),
        )
    }

    @Test
    fun absoluteImagePathIgnoresSelectedWorkspaceDirectory() {
        assertEquals(
            "/Users/dev/shared/preview.png",
            resolveRemoteImagePath(
                "/Users/dev/redmine/rm-1321/main",
                "/Users/dev/shared/preview.png",
            ),
        )
    }

    @Test
    fun imageSampleSizeKeepsOrdinaryImagesAtFullResolution() {
        assertEquals(1, imageSampleSize(2000, 1500))
    }

    @Test
    fun imageSampleSizeLimitsDimensionsAndDecodedPixels() {
        assertEquals(2, imageSampleSize(8000, 1000))
        assertEquals(4, imageSampleSize(8192, 8192))
    }
}
