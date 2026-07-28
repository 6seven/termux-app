package com.termux.workflow

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class HostProfileStore(context: Context) {
    private val profiles = context.getSharedPreferences("workflow_host_profiles", Context.MODE_PRIVATE)
    private val credentials = EncryptedSharedPreferences.create(
        context,
        "workflow_host_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun profiles(): List<HostProfile> = runCatching {
        val array = JSONArray(profiles.getString(KEY_PROFILES, "[]"))
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }.map {
            HostProfile(it.getString("id"), it.getString("name"), it.getString("pmgr_url"), it.getString("ssh_host_alias"))
        }
    }.getOrDefault(emptyList())

    fun token(profileId: String): String = credentials.getString("token:$profileId", "").orEmpty()

    fun selectedProfileId(): String? = profiles.getString(KEY_SELECTED, null)

    fun save(profile: HostProfile, token: String): HostProfile {
        val normalized = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString() },
            name = profile.name.trim().ifBlank { profile.sshHostAlias.trim() },
            pmgrUrl = profile.pmgrUrl.trim().trimEnd('/'),
            sshHostAlias = profile.sshHostAlias.trim(),
        )
        require(normalized.pmgrUrl.startsWith("http://") || normalized.pmgrUrl.startsWith("https://"))
        require(normalized.sshHostAlias.isNotBlank())
        val updated = profiles().filterNot { it.id == normalized.id } + normalized
        val json = JSONArray(updated.map { it.toJson() }).toString()
        profiles.edit().putString(KEY_PROFILES, json).putString(KEY_SELECTED, normalized.id).apply()
        credentials.edit().putString("token:${normalized.id}", token.trim()).apply()
        return normalized
    }

    fun select(profileId: String) {
        profiles.edit().putString(KEY_SELECTED, profileId).apply()
    }

    fun delete(profileId: String) {
        val remaining = profiles().filterNot { it.id == profileId }
        profiles.edit().putString(KEY_PROFILES, JSONArray(remaining.map { it.toJson() }).toString()).apply()
        credentials.edit().remove("token:$profileId").apply()
    }

    private fun HostProfile.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("pmgr_url", pmgrUrl)
        .put("ssh_host_alias", sshHostAlias)

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_SELECTED = "selected_profile"
    }
}

class HostProfileSelector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build(),
) {
    suspend fun fastest(profiles: List<HostProfile>, token: (HostProfile) -> String): HostProfile? = coroutineScope {
        profiles.map { profile ->
            async(Dispatchers.IO) {
                val request = Request.Builder().url("${profile.pmgrUrl}/health").apply {
                    token(profile).takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
                }.build()
                val started = System.nanoTime()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code == 401) profile to (System.nanoTime() - started) else null
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().minByOrNull { it.second }?.first
    }
}

class WorkflowCache(private val context: Context) {
    fun read(profileId: String): WorkflowData? = runCatching {
        val file = file(profileId)
        if (!file.exists()) return null
        WorkflowCacheCodec.decode(file.readText())
    }.getOrNull()

    fun write(profileId: String, data: WorkflowData) {
        val file = file(profileId)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(WorkflowCacheCodec.encode(data))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun file(profileId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "workflow-cache/$digest.json")
    }
}

object WorkflowCacheCodec {
    fun encode(data: WorkflowData): String = JSONObject()
        .put("issues", JSONArray(data.issues.map { issue -> JSONObject()
            .put("redmine_id", issue.redmineId).put("issue_id", issue.issueId).put("subject", issue.subject)
            .put("project", issue.project).put("status", issue.status).put("priority", issue.priority)
            .put("fixed_version", issue.fixedVersion).put("updated_on", issue.updatedOn)
            .put("has_project_binding", issue.hasBinding).put("has_workspace", issue.hasWorkspace) }))
        .put("projects", JSONArray(data.projects.map { project -> JSONObject()
            .put("id", project.id).put("name", project.name).put("slug", project.slug).put("repo_path", project.repoPath)
            .put("bound_issue_count", project.boundIssueCount).put("workspace_count", project.workspaceCount).put("status", project.status) }))
        .put("targets", JSONArray(data.targets.map(::targetJson)))
        .put("tracker", trackerJson(data.tracker))
        .toString()

    fun decode(text: String): WorkflowData {
        val root = JSONObject(text)
        val issues = root.optJSONArray("issues") ?: JSONArray()
        val projects = root.optJSONArray("projects") ?: JSONArray()
        val targets = root.optJSONArray("targets") ?: JSONArray()
        return WorkflowData(
            issues = (0 until issues.length()).map { parseIssue(issues.getJSONObject(it)) },
            projects = (0 until projects.length()).map { item -> projects.getJSONObject(item) }.map {
                ProjectSummary(it.optString("id"), it.optString("name"), it.optString("slug"), it.optString("repo_path"), it.optInt("bound_issue_count"), it.optInt("workspace_count"), it.optString("status"))
            },
            targets = (0 until targets.length()).map { parseTarget(targets.getJSONObject(it)) },
            tracker = root.optJSONObject("tracker")?.let(::parseTracker) ?: TrackerState(),
        )
    }

    private fun targetJson(target: ActivationTarget) = JSONObject()
        .put("target_id", target.id).put("name", target.name).put("kind", target.kind.name)
        .put("project_id", target.projectId).put("project_name", target.projectName)
        .put("redmine_id", target.issueRedmineId).put("issue_id", target.issueId).put("subject", target.subject)
        .put("status", target.status).put("archived", target.archived).put("tmux_session", target.tmuxSession)

    private fun trackerJson(tracker: TrackerState) = JSONObject()
        .put("available", tracker.available)
        .put("error", tracker.error)
        .put("active_target", tracker.activeTarget?.let(::targetJson))
        .put("current_target", tracker.currentTarget?.let(::targetJson))
        .put("connection_tmux_session", tracker.connectionTmuxSession)
        .put("items", JSONArray(tracker.items.map { JSONObject().put("session_id", it.sessionId).put("session", it.session).put("window_id", it.windowId).put("window", it.window).put("pane", it.pane).put("state", it.state).put("title", it.title).put("attention_reason", it.attentionReason).put("acknowledged", it.acknowledged).put("completed_at", it.completedAt) }))
        .put("usage", JSONObject()
            .put("metrics", JSONArray(tracker.usage.metrics.map { JSONObject().put("label", it.label).put("used", it.used).put("limit", it.limit).put("unit", it.unit).put("reset_at", it.resetAt) }))
            .put("reset_credit_expiries", JSONArray(tracker.usage.resetCreditExpiries))
            .put("token_cost", tokenCostJson(tracker.usage.tokenCost))
            .put("daily_token_cost", tokenCostJson(tracker.usage.dailyTokenCost))
            .put("days", JSONArray(tracker.usage.days.map { JSONObject().put("day", it.day).put("codex", it.codex).put("opencode", it.opencode).put("cost", tokenCostJson(it.cost)) }))
            .put("projects", JSONArray(tracker.usage.projects.map { JSONObject().put("name", it.name).put("total", it.total).put("daily", it.daily) }))
            .put("refreshed_at", tracker.usage.refreshedAt))
        .put("usage_refreshing", tracker.usageRefreshing)

    private fun tokenCostJson(cost: TokenCost) = JSONObject()
        .put("input_uncached", cost.inputUncached)
        .put("output", cost.output)
        .put("input_cached", cost.inputCached)

    private fun parseTracker(value: JSONObject): TrackerState {
        val items = value.optJSONArray("items") ?: JSONArray()
        val usage = value.optJSONObject("usage") ?: JSONObject()
        val metrics = usage.optJSONArray("metrics") ?: JSONArray()
        val days = usage.optJSONArray("days") ?: JSONArray()
        val projects = usage.optJSONArray("projects") ?: JSONArray()
        return TrackerState(
            available = value.optBoolean("available", true),
            error = value.optString("error").takeIf(String::isNotBlank),
            activeTarget = value.optJSONObject("active_target")?.let(::parseTarget),
            currentTarget = value.optJSONObject("current_target")?.let(::parseTarget),
            connectionTmuxSession = value.optString("connection_tmux_session").takeIf(String::isNotBlank),
            items = (0 until items.length()).map { items.getJSONObject(it) }.map { TrackerItem(it.optString("session_id"), it.optString("session"), it.optString("window_id"), it.optString("window"), it.optString("pane"), it.optString("state"), it.optString("title"), it.optString("attention_reason"), it.optBoolean("acknowledged"), it.optString("completed_at")) },
            usage = UsageState(
                metrics = (0 until metrics.length()).map { metrics.getJSONObject(it) }.map { UsageMetric(it.optString("label"), it.optDouble("used"), if (it.isNull("limit")) null else it.optDouble("limit"), it.optString("unit"), it.optString("reset_at")) },
                resetCreditExpiries = usage.optJSONArray("reset_credit_expiries")?.let { expiries -> (0 until expiries.length()).map(expiries::optString) }.orEmpty(),
                tokenCost = parseTokenCost(usage.optJSONObject("token_cost")),
                dailyTokenCost = parseTokenCost(usage.optJSONObject("daily_token_cost")),
                days = (0 until days.length()).map { days.getJSONObject(it) }.map { UsageDay(it.optString("day"), it.optLong("codex"), it.optLong("opencode"), parseTokenCost(it.optJSONObject("cost"))) },
                projects = (0 until projects.length()).map { projects.getJSONObject(it) }.map { UsageProject(it.optString("name"), it.optLong("total"), it.optLong("daily")) },
                refreshedAt = usage.optString("refreshed_at"),
            ),
            usageRefreshing = value.optBoolean("usage_refreshing"),
        )
    }

    private fun parseTokenCost(value: JSONObject?) = TokenCost(
        inputUncached = value?.optLong("input_uncached") ?: 0,
        output = value?.optLong("output") ?: 0,
        inputCached = value?.optLong("input_cached") ?: 0,
    )
}

class WorkflowRepository(
    private val cache: WorkflowCache,
    private val apiFactory: (HostProfile, String) -> WorkflowApi = { profile, token -> WorkflowApiClient(profile.pmgrUrl, token) },
) {
    fun cached(profile: HostProfile): WorkflowData? = cache.read(profile.id)

    suspend fun refresh(profile: HostProfile, token: String): WorkflowData = coroutineScope {
        val api = apiFactory(profile, token)
        val cachedTracker = cache.read(profile.id)?.tracker
        val issues = async { api.listIssues() }
        val projects = async { api.listProjects() }
        val targets = async { api.listWorkspaces(includeArchived = true) }
        val tracker = async { api.trackerState() }
        WorkflowData(
            issues.await(),
            projects.await(),
            targets.await(),
            tracker.await().withCachedConnection(cachedTracker),
        ).also { cache.write(profile.id, it) }
    }
}
