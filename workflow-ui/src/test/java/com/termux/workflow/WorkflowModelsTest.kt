package com.termux.workflow

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowModelsTest {
    private val issues = listOf(
        IssueSummary("r", "10"),
        IssueSummary("r", "2"),
        IssueSummary("r", "alpha"),
    )

    @Test
    fun issueSortCyclesLikeLocalTracker() {
        assertEquals(IssueSortMode.IdAscending, IssueSortMode.Current.next())
        assertEquals(IssueSortMode.IdDescending, IssueSortMode.IdAscending.next())
        assertEquals(IssueSortMode.Current, IssueSortMode.IdDescending.next())
        assertEquals(listOf("10", "2", "alpha"), sortIssues(issues, IssueSortMode.Current).map(IssueSummary::issueId))
        assertEquals(listOf("2", "10", "alpha"), sortIssues(issues, IssueSortMode.IdAscending).map(IssueSummary::issueId))
        assertEquals(listOf("alpha", "10", "2"), sortIssues(issues, IssueSortMode.IdDescending).map(IssueSummary::issueId))
    }

    @Test
    fun usageCountdownMatchesLocalTrackerFormat() {
        val now = Instant.parse("2026-07-28T08:00:00Z").toEpochMilli()

        assertEquals("2d3h", compactDurationUntil("2026-07-30T11:45:00Z", now))
        assertEquals("4h5m", compactDurationUntil("2026-07-28T12:05:00Z", now))
        assertEquals("6d19h", compactDurationUntil("2026-08-04T11:13:18+08:00", now))
        assertEquals("0m", compactDurationUntil("2026-07-28T07:00:00Z", now))
    }

    @Test
    fun costAndWaitingStateSurviveCacheRoundTrip() {
        val waiting = TrackerItem("$0", "dev", "@7", "project", "%12", "in_progress", "Approve", "waiting")
        val cost = TokenCost(inputUncached = 1_000_000, output = 500_000, inputCached = 2_000_000)
        val data = WorkflowData(
            tracker = TrackerState(
                items = listOf(waiting),
                usage = UsageState(tokenCost = cost, dailyTokenCost = cost, days = listOf(UsageDay("2026-07-28", 1, 2, cost))),
            ),
        )

        val decoded = WorkflowCacheCodec.decode(WorkflowCacheCodec.encode(data))

        assertTrue(decoded.tracker.items.single().waiting)
        assertFalse(waiting.copy(acknowledged = true).waiting)
        assertEquals(cost, decoded.tracker.usage.tokenCost)
        assertEquals(cost, decoded.tracker.usage.dailyTokenCost)
        assertEquals(cost, decoded.tracker.usage.days.single().cost)
        assertEquals("6.05", cost.cnyDisplay)
    }
}
