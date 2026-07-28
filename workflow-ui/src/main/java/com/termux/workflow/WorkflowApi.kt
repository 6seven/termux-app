package com.termux.workflow

import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

interface WorkflowApi : ActivationGateway {
    suspend fun listIssues(): List<IssueSummary>
    suspend fun issue(redmineId: String, issueId: String): IssueDetail
    suspend fun createIssue(mutation: IssueMutation): IssueSummary
    suspend fun updateIssue(mutation: IssueMutation)
    suspend fun upload(redmineId: String, filename: String, contentType: String?, input: () -> InputStream): Map<String, String>
    suspend fun bindingPresets(redmineId: String, issueId: String, projectKey: String?): List<BindingPreset>
    suspend fun saveBinding(redmineId: String, issueId: String, projectKey: String?, mainProjectId: String, references: List<String>)
    suspend fun deleteBinding(redmineId: String, issueId: String)
    suspend fun startWorkspace(redmineId: String, issueId: String, projectKey: String?, subject: String): StartWorkspaceResult
    suspend fun listProjects(): List<ProjectSummary>
    suspend fun listWorkspaces(includeArchived: Boolean = true): List<ActivationTarget>
    suspend fun trackerState(): TrackerState
    suspend fun refreshUsage(): TrackerState
    suspend fun activateTrackerItem(item: TrackerItem): ActivationResult
    suspend fun acknowledgeTrackerItem(item: TrackerItem)
}

class WorkflowApiClient(
    baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
) : WorkflowApi {
    private val root = baseUrl.trimEnd('/').toHttpUrl()
    private val jsonType = "application/json".toMediaType()

    override suspend fun listIssues(): List<IssueSummary> = request("GET", listOf("mobile", "issues"), query = mapOf("view" to "assigned-open"))
        .items().map(::parseIssue)

    override suspend fun issue(redmineId: String, issueId: String): IssueDetail = parseIssueDetail(
        request("GET", listOf("mobile", "issues", redmineId, issueId)),
    )

    override suspend fun createIssue(mutation: IssueMutation): IssueSummary {
        val response = request("POST", listOf("mobile", "issues"), mutation.toJson(create = true))
        return IssueSummary(
            redmineId = response.string("redmine_id").ifBlank { mutation.redmineId },
            issueId = response.string("issue_id"),
            subject = response.string("subject").ifBlank { mutation.subject },
        )
    }

    override suspend fun updateIssue(mutation: IssueMutation) {
        require(!mutation.issueId.isNullOrBlank())
        request("PUT", listOf("mobile", "issues", mutation.redmineId, mutation.issueId), mutation.toJson(create = false))
    }

    override suspend fun upload(
        redmineId: String,
        filename: String,
        contentType: String?,
        input: () -> InputStream,
    ): Map<String, String> {
        val fileBody = object : RequestBody() {
            override fun contentType() = (contentType ?: "application/octet-stream").toMediaType()
            override fun writeTo(sink: BufferedSink) {
                input().use { source ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(bytes)
                        if (count < 0) break
                        sink.write(bytes, 0, count)
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("redmine_id", redmineId)
            .addFormDataPart("file", filename.ifBlank { "attachment" }, fileBody)
            .build()
        val response = request("POST", listOf("mobile", "uploads", "file"), requestBody = body)
        return mapOf(
            "token" to response.string("token"),
            "filename" to response.string("filename").ifBlank { filename },
            "content_type" to response.string("content_type").ifBlank { contentType.orEmpty() },
        )
    }

    override suspend fun bindingPresets(redmineId: String, issueId: String, projectKey: String?): List<BindingPreset> {
        val query = buildMap {
            put("limit", "10")
            projectKey?.takeIf(String::isNotBlank)?.let { put("redmine_project_key", it) }
        }
        return request("GET", listOf("mobile", "issues", redmineId, issueId, "project-binding-presets"), query = query)
            .array("presets").objects().map { preset ->
                BindingPreset(
                    id = preset.string("id"),
                    mainProject = preset.objectOrNull("main_project")?.let(::parseBindingProject),
                    referenceProjects = preset.array("reference_projects").objects().map(::parseBindingProject),
                    usageCount = preset.optInt("usage_count"),
                )
            }
    }

    override suspend fun saveBinding(
        redmineId: String,
        issueId: String,
        projectKey: String?,
        mainProjectId: String,
        references: List<String>,
    ) {
        val body = JSONObject().putIfNotBlank("redmine_project_key", projectKey)
            .put("main_project_ref", mainProjectId)
            .put("reference_project_refs", JSONArray(references))
        request("PUT", listOf("mobile", "issues", redmineId, issueId, "project-binding"), body)
    }

    override suspend fun deleteBinding(redmineId: String, issueId: String) {
        request("DELETE", listOf("mobile", "issues", redmineId, issueId, "project-binding"))
    }

    override suspend fun startWorkspace(
        redmineId: String,
        issueId: String,
        projectKey: String?,
        subject: String,
    ): StartWorkspaceResult {
        val body = JSONObject().putIfNotBlank("redmine_project_key", projectKey).putIfNotBlank("subject", subject)
        val response = request("POST", listOf("mobile", "issues", redmineId, issueId, "start-workspace"), body)
        val target = response.objectOrNull("target") ?: response.objectOrNull("workspace") ?: response
        return StartWorkspaceResult(parseTarget(target, TargetKind.IssueWorkspace, redmineId, issueId))
    }

    override suspend fun listProjects(): List<ProjectSummary> = request("GET", listOf("mobile", "projects"))
        .items().map { item ->
            ProjectSummary(
                id = item.string("id"),
                name = item.string("name"),
                slug = item.string("slug"),
                repoPath = item.string("repo_path"),
                boundIssueCount = item.optInt("bound_issue_count"),
                workspaceCount = item.optInt("workspace_count"),
                status = item.string("status"),
            )
        }

    override suspend fun listWorkspaces(includeArchived: Boolean): List<ActivationTarget> = request(
        "GET",
        listOf("mobile", "workspaces"),
        query = mapOf("tracker_only" to "false", "include_archived" to includeArchived.toString()),
    ).items().map { item ->
        val kind = if (item.string("workspace_id").isBlank() && item.string("issue_id").isBlank()) TargetKind.ProjectHome else TargetKind.IssueWorkspace
        parseTarget(item, kind)
    }

    override suspend fun trackerState(): TrackerState = parseTracker(request("GET", listOf("mobile", "tracker-state")))

    override suspend fun refreshUsage(): TrackerState {
        request("POST", listOf("mobile", "tracker-state", "usage-refresh"), JSONObject())
        var state = trackerState()
        repeat(45) {
            if (!state.usageRefreshing) return state
            delay(2_000)
            state = trackerState()
        }
        return state
    }

    override suspend fun activateTrackerItem(item: TrackerItem): ActivationResult {
        val response = request(
            "POST",
            listOf("mobile", "tracker-state", "activate"),
            item.trackerTargetJson(),
        )
        return ActivationResult(
            targetId = response.string("target_id").ifBlank { item.id },
            sshHostAlias = response.string("ssh_host_alias").takeIf(String::isNotBlank),
            tmuxSession = response.string("tmux_session"),
        ).also { require(it.tmuxSession.isNotBlank()) { "Tracker activation response did not include tmux_session" } }
    }

    override suspend fun acknowledgeTrackerItem(item: TrackerItem) {
        request(
            "POST",
            listOf("mobile", "tracker-state", "acknowledge"),
            item.trackerTargetJson(),
        )
    }

    override suspend fun activateTarget(targetId: String): ActivationResult {
        val response = request("POST", listOf("mobile", "targets", targetId, "activate"), JSONObject())
        return ActivationResult(
            targetId = response.string("target_id").ifBlank { targetId },
            sshHostAlias = response.string("ssh_host_alias").takeIf(String::isNotBlank),
            tmuxSession = response.string("tmux_session"),
        ).also { require(it.tmuxSession.isNotBlank()) { "Activation response did not include tmux_session" } }
    }

    private suspend fun request(
        method: String,
        segments: List<String>,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
        requestBody: RequestBody? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = segments.fold(root.newBuilder()) { builder, segment -> builder.addEncodedPathSegment(segment.encodedPathSegment()) }
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val payload = requestBody ?: body?.toString()?.toRequestBody(jsonType)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            when (method) {
                "POST" -> post(payload ?: "{}".toRequestBody(jsonType))
                "PUT" -> put(payload ?: "{}".toRequestBody(jsonType))
                "DELETE" -> delete(payload)
                else -> get()
            }
        }.build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WorkflowApiException(response.code, text.ifBlank { response.message })
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun parseIssueDetail(value: JSONObject): IssueDetail {
        val summary = parseIssue(value)
        return IssueDetail(
            summary = summary,
            description = value.string("description"),
            assignedTo = value.string("assigned_to"),
            attachments = value.array("attachments").objects().map {
                Attachment(it.string("id"), it.string("filename"), it.string("content_type"), it.string("content_url"))
            },
            feedback = value.array("feedback").objects().map(::parseJournal),
            history = value.array("history").objects().map(::parseJournal),
            binding = value.objectOrNull("project_binding")?.let(::parseBinding) ?: ProjectBinding(),
            workspace = value.objectOrNull("workspace")?.let { parseTarget(it, TargetKind.IssueWorkspace, summary.redmineId, summary.issueId) },
        )
    }

    private fun parseTracker(value: JSONObject): TrackerState {
        val active = value.objectOrNull("active_target")?.let { parseTarget(it) }
        val current = value.objectOrNull("current_target")?.let { parseTarget(it) } ?: active
        val itemArray = value.arrayFirst("items", "inbox", "tasks")
        val usage = value.objectOrNull("usage") ?: value.objectOrNull("usage_snapshot") ?: JSONObject()
        return TrackerState(
            available = value.optBoolean("available", true),
            error = value.string("error").takeIf(String::isNotBlank),
            activeTarget = active,
            currentTarget = current,
            items = itemArray.objects().map { item ->
                TrackerItem(
                    sessionId = item.string("session_id"),
                    session = item.string("session"),
                    windowId = item.string("window_id"),
                    window = item.string("window"),
                    pane = item.string("pane"),
                    state = item.stringFirst("state", "status"),
                    title = item.stringFirst("title", "summary", "project", "issue"),
                    attentionReason = item.string("attention_reason"),
                    acknowledged = item.optBoolean("acknowledged"),
                    completedAt = item.string("completed_at"),
                )
            },
            usage = parseUsage(usage),
            usageRefreshing = value.optBoolean("usage_refreshing"),
        )
    }

    private fun parseUsage(value: JSONObject): UsageState {
        val limits = value.array("codex_limits").objects().toList()
        val weeklyResetAt = limits.firstOrNull { it.string("label").equals("7d", ignoreCase = true) }?.string("reset_at").orEmpty()
        val metrics = buildList {
            value.optDoubleOrNull("weekly_remaining")?.let {
                add(UsageMetric("Weekly remaining", it, 100.0, "%", weeklyResetAt))
            }
            add(UsageMetric("Daily tokens", value.optDouble("daily_token_total"), null, "tokens"))
            add(UsageMetric("Codex tokens", value.optDouble("codex_token_total"), null, "tokens"))
            add(UsageMetric("OpenCode tokens", value.optDouble("opencode_token_total"), null, "tokens"))
            limits.forEach { limit ->
                val used = limit.optDoubleOrNull("used_percent")
                    ?: limit.optDoubleOrNull("remaining_percent")?.let { 100.0 - it }
                    ?: 0.0
                add(UsageMetric(limit.string("label"), used, 100.0, "%", limit.string("reset_at")))
            }
        }
        return UsageState(
            metrics = metrics,
            resetCreditExpiries = value.array("reset_credit_expiries").strings(),
            days = value.array("days").objects().map {
                UsageDay(it.string("day"), it.optLong("codex"), it.optLong("opencode"))
            },
            projects = value.array("opencode_projects").objects().map {
                UsageProject(
                    it.string("name"),
                    it.optLong("total"),
                    it.optLong("daily"),
                )
            },
            refreshedAt = value.string("fetched_at"),
        )
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

class WorkflowApiException(val statusCode: Int, message: String) : RuntimeException(message)

internal fun parseIssue(value: JSONObject) = IssueSummary(
    redmineId = value.string("redmine_id"),
    issueId = value.stringFirst("issue_id", "id"),
    subject = value.string("subject"),
    project = value.string("project"),
    status = value.string("status"),
    priority = value.string("priority"),
    fixedVersion = value.string("fixed_version"),
    updatedOn = value.string("updated_on"),
    hasBinding = value.optBoolean("has_project_binding"),
    hasWorkspace = value.optBoolean("has_workspace"),
)

private fun parseJournal(value: JSONObject) = JournalEntry(
    id = value.string("id"),
    author = value.string("author"),
    notes = value.stringFirst("notes", "kind"),
    createdOn = value.string("created_on"),
)

private fun parseBindingProject(value: JSONObject) = BindingProject(
    id = value.stringFirst("id", "slug"),
    name = value.stringFirst("name", "slug", "id"),
)

private fun parseBinding(value: JSONObject) = ProjectBinding(
    source = value.string("source").ifBlank { "none" },
    mainProject = value.objectOrNull("main_project")?.let(::parseBindingProject),
    referenceProjects = value.array("reference_projects").objects().map(::parseBindingProject),
)

internal fun parseTarget(
    value: JSONObject,
    fallbackKind: TargetKind? = null,
    redmineId: String? = null,
    issueId: String? = null,
): ActivationTarget {
    val type = value.stringFirst("kind", "type", "target_type")
    val resolvedIssueId = value.string("issue_id").takeIf(String::isNotBlank) ?: issueId
    val kind = fallbackKind ?: if (type.contains("project", true) || resolvedIssueId == null) TargetKind.ProjectHome else TargetKind.IssueWorkspace
    return ActivationTarget(
        id = value.stringFirst("target_id", "id", "workspace_id"),
        name = value.stringFirst("name", "title", "subject", "target_id", "id"),
        kind = kind,
        projectId = value.string("project_id").takeIf(String::isNotBlank),
        projectName = value.stringFirst("project_name", "main_project_name"),
        issueRedmineId = value.string("redmine_id").takeIf(String::isNotBlank) ?: redmineId,
        issueId = resolvedIssueId,
        subject = value.string("subject"),
        status = value.string("status"),
        archived = value.optBoolean("archived") || value.string("status").equals("archived", true),
        tmuxSession = value.string("tmux_session").takeIf(String::isNotBlank),
    )
}

private fun IssueMutation.toJson(create: Boolean): JSONObject = JSONObject().apply {
    if (create) {
        put("redmine_id", redmineId)
        put("project_id", projectId)
    }
    putIfNotBlank("subject", subject)
    putIfNotBlank("description", description)
    putIfNotBlank("status_id", statusId)
    putIfNotBlank("priority_id", priorityId)
    putIfNotBlank("assigned_to_id", assignedToId)
    putIfNotBlank("fixed_version_id", fixedVersionId)
    putIfNotBlank("notes", notes)
    put("uploads", JSONArray(uploads))
}

private fun TrackerItem.trackerTargetJson(): JSONObject = JSONObject()
    .put("session_id", sessionId)
    .put("window_id", windowId)
    .put("pane", pane)

private fun String.encodedPathSegment(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
private fun JSONObject.items(): List<JSONObject> = array("items").objects()
private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key, "")
private fun JSONObject.stringFirst(vararg keys: String): String = keys.firstNotNullOfOrNull { key -> string(key).takeIf(String::isNotBlank) }.orEmpty()
private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
private fun JSONObject.arrayFirst(vararg keys: String): JSONArray = keys.firstNotNullOfOrNull(::optJSONArray) ?: JSONArray()
private fun JSONObject.objectOrNull(key: String): JSONObject? = optJSONObject(key)
private fun JSONObject.putIfNotBlank(key: String, value: String?): JSONObject = apply { value?.takeIf(String::isNotBlank)?.let { put(key, it) } }
private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
