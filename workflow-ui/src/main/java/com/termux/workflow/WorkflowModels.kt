package com.termux.workflow

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HostProfile(
    val id: String,
    val name: String,
    val pmgrUrl: String,
    val sshHostAlias: String,
)

data class IssueSummary(
    val redmineId: String,
    val issueId: String,
    val subject: String = "",
    val project: String = "",
    val status: String = "",
    val priority: String = "",
    val fixedVersion: String = "",
    val updatedOn: String = "",
    val hasBinding: Boolean = false,
    val hasWorkspace: Boolean = false,
)

enum class IssueSortMode(val label: String) {
    Current("current"),
    IdAscending("#asc"),
    IdDescending("#desc");

    fun next(): IssueSortMode = entries[(ordinal + 1) % entries.size]
}

internal fun sortIssues(issues: List<IssueSummary>, mode: IssueSortMode): List<IssueSummary> {
    if (mode == IssueSortMode.Current) return issues
    val comparator = Comparator<IssueSummary> { left, right ->
        val leftId = left.issueId.trim()
        val rightId = right.issueId.trim()
        val leftNumber = leftId.toIntOrNull()
        val rightNumber = rightId.toIntOrNull()
        val order = if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber) else leftId.compareTo(rightId)
        if (mode == IssueSortMode.IdDescending) -order else order
    }
    return issues.sortedWith(comparator)
}

data class Attachment(
    val id: String,
    val filename: String,
    val contentType: String = "",
    val contentUrl: String = "",
)

data class JournalEntry(
    val id: String,
    val author: String = "",
    val notes: String = "",
    val createdOn: String = "",
)

data class BindingProject(val id: String, val name: String = "")

data class ProjectBinding(
    val source: String = "none",
    val mainProject: BindingProject? = null,
    val referenceProjects: List<BindingProject> = emptyList(),
)

data class IssueDetail(
    val summary: IssueSummary,
    val description: String = "",
    val assignedTo: String = "",
    val attachments: List<Attachment> = emptyList(),
    val feedback: List<JournalEntry> = emptyList(),
    val history: List<JournalEntry> = emptyList(),
    val binding: ProjectBinding = ProjectBinding(),
    val workspace: ActivationTarget? = null,
)

data class ProjectSummary(
    val id: String,
    val name: String,
    val slug: String = "",
    val repoPath: String = "",
    val boundIssueCount: Int = 0,
    val workspaceCount: Int = 0,
    val status: String = "",
)

enum class TargetKind { ProjectHome, IssueWorkspace }

data class ActivationTarget(
    val id: String,
    val name: String,
    val kind: TargetKind,
    val projectId: String? = null,
    val projectName: String = "",
    val issueRedmineId: String? = null,
    val issueId: String? = null,
    val subject: String = "",
    val status: String = "",
    val archived: Boolean = false,
    val tmuxSession: String? = null,
)

data class TrackerItem(
    val sessionId: String,
    val session: String,
    val windowId: String,
    val window: String,
    val pane: String,
    val state: String,
    val title: String,
    val attentionReason: String = "",
    val acknowledged: Boolean = false,
    val completedAt: String = "",
) {
    val id: String get() = "$sessionId::$windowId::$pane"
    val completed: Boolean get() = state.equals("completed", true) || completedAt.isNotBlank()
    val waiting: Boolean get() = !acknowledged && attentionReason.equals("waiting", true)
}

data class UsageMetric(
    val label: String,
    val used: Double,
    val limit: Double?,
    val unit: String = "",
    val resetAt: String = "",
) {
    val displayValue: String get() = if (unit == "tokens") compactTokenCount(used.toLong()) else compactDecimal(used)
    val displayLimit: String? get() = limit?.let(::compactDecimal)
}

data class TokenCost(
    val inputUncached: Long = 0,
    val output: Long = 0,
    val inputCached: Long = 0,
) {
    val totalTokens: Long get() = inputUncached + output + inputCached
    val cny: Double get() = (inputUncached * 3.0 + output * 6.0 + inputCached * 0.025) / 1_000_000
    val cnyDisplay: String get() = compactMoney(cny)
}

data class UsageDay(val day: String, val codex: Long, val opencode: Long, val cost: TokenCost = TokenCost()) {
    val total: Long get() = codex + opencode
    val codexDisplay: String get() = compactTokenCount(codex)
    val opencodeDisplay: String get() = compactTokenCount(opencode)
    val totalDisplay: String get() = compactTokenCount(total)
}

data class UsageProject(val name: String, val total: Long, val daily: Long) {
    val totalDisplay: String get() = compactTokenCount(total)
    val dailyDisplay: String get() = compactTokenCount(daily)
}

data class UsageState(
    val metrics: List<UsageMetric> = emptyList(),
    val resetCreditExpiries: List<String> = emptyList(),
    val tokenCost: TokenCost = TokenCost(),
    val dailyTokenCost: TokenCost = TokenCost(),
    val days: List<UsageDay> = emptyList(),
    val projects: List<UsageProject> = emptyList(),
    val refreshedAt: String = "",
)

internal fun compactTokenCount(rawValue: Long): String {
    val value = rawValue.coerceAtLeast(0)
    for ((suffix, unit) in listOf("B" to 1_000_000_000L, "M" to 1_000_000L, "K" to 1_000L)) {
        if (value < unit) continue
        val whole = value / unit
        if (whole >= 10 || value % unit == 0L) return "$whole$suffix"
        return String.format(Locale.US, "%.1f%s", value.toDouble() / unit, suffix)
    }
    return value.toString()
}

internal fun compactDurationUntil(target: String, nowMillis: Long = System.currentTimeMillis()): String {
    val durationMillis = (parseUsageInstant(target)?.toEpochMilli() ?: return "") - nowMillis
    var minutes = durationMillis.coerceAtLeast(0) / 60_000
    val days = minutes / (24 * 60)
    minutes -= days * 24 * 60
    val hours = minutes / 60
    minutes -= hours * 60
    return when {
        days > 0 -> "${days}d${hours}h"
        hours > 0 -> "${hours}h${minutes}m"
        else -> "${minutes}m"
    }
}

internal fun formatUsageTimestamp(timestamp: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(requireNotNull(parseUsageInstant(timestamp)))
}.getOrDefault(timestamp)

private fun parseUsageInstant(timestamp: String): Instant? = runCatching { OffsetDateTime.parse(timestamp).toInstant() }.getOrNull()

private fun compactDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.1f", value)

private fun compactMoney(value: Double): String = when {
    value >= 1_000 -> String.format(Locale.US, "%.0f", value)
    value >= 10 -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.2f", value.coerceAtLeast(0.0))
}

data class TrackerState(
    val available: Boolean = true,
    val error: String? = null,
    val activeTarget: ActivationTarget? = null,
    val currentTarget: ActivationTarget? = null,
    val connectionTmuxSession: String? = null,
    val items: List<TrackerItem> = emptyList(),
    val usage: UsageState = UsageState(),
    val usageRefreshing: Boolean = false,
)

internal fun TrackerState.withCachedConnection(cached: TrackerState?): TrackerState {
    if (cached == null) return this
    if (!available) {
        return cached.copy(
            available = false,
            error = error,
            usageRefreshing = false,
        )
    }
    return copy(
        activeTarget = activeTarget ?: cached.activeTarget,
        currentTarget = currentTarget ?: cached.currentTarget,
        connectionTmuxSession = connectionTmuxSession ?: cached.connectionTmuxSession,
    )
}

data class WorkflowData(
    val issues: List<IssueSummary> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val targets: List<ActivationTarget> = emptyList(),
    val tracker: TrackerState = TrackerState(),
) {
    val currentIssue: IssueSummary?
        get() {
            val target = tracker.currentTarget ?: tracker.activeTarget ?: return null
            return issues.firstOrNull {
                it.issueId == target.issueId && (target.issueRedmineId == null || it.redmineId == target.issueRedmineId)
            }
        }
}

data class ActivationResult(
    val targetId: String,
    val sshHostAlias: String? = null,
    val tmuxSession: String,
)

data class IssueMutation(
    val redmineId: String,
    val projectId: String = "",
    val issueId: String? = null,
    val subject: String,
    val description: String = "",
    val statusId: String? = null,
    val priorityId: String? = null,
    val assignedToId: String? = null,
    val fixedVersionId: String? = null,
    val notes: String = "",
    val uploads: List<Map<String, String>> = emptyList(),
)

data class BindingPreset(
    val id: String,
    val mainProject: BindingProject?,
    val referenceProjects: List<BindingProject>,
    val usageCount: Int,
)

data class StartWorkspaceResult(val target: ActivationTarget)
