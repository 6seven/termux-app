package com.termux.workflow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ConsoleColors = darkColorScheme(
    primary = Color(0xFF6EE7B7),
    secondary = Color(0xFFF5C66A),
    background = Color(0xFF090D12),
    surface = Color(0xFF111821),
    surfaceVariant = Color(0xFF18222E),
    onPrimary = Color(0xFF07110D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    outline = Color(0xFF344253),
    error = Color(0xFFFF8585),
)
private val WaitingColor = Color(0xFFFF8B1F)

@Composable
fun WorkflowConsoleApp(requestedDestination: WorkflowDestination, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileStore = remember { HostProfileStore(context) }
    val cache = remember { WorkflowCache(context) }
    val repository = remember { WorkflowRepository(cache) }
    val selector = remember { HostProfileSelector() }
    val sessionStarter = remember { TermuxSshSessionStarter(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var profiles by remember { mutableStateOf(profileStore.profiles()) }
    var state by remember { mutableStateOf(WorkflowState(destination = requestedDestination)) }
    var activeToken by remember { mutableStateOf("") }
    var showProfiles by remember { mutableStateOf(profiles.isEmpty()) }
    var selectedIssue by remember { mutableStateOf<IssueDetail?>(null) }
    var issueMode by remember { mutableStateOf<IssueMode?>(null) }
    var bindingIssue by remember { mutableStateOf<IssueDetail?>(null) }
    var bindingPresets by remember { mutableStateOf<List<BindingPreset>>(emptyList()) }

    fun dispatch(event: WorkflowEvent) {
        state = WorkflowReducer.reduce(state, event)
    }

    fun refresh(profile: HostProfile, token: String) {
        scope.launch {
            try {
                val data = repository.refresh(profile, token)
                dispatch(WorkflowEvent.DataLoaded(data))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                dispatch(WorkflowEvent.LoadFailed(error.message ?: "Host is unreachable", repository.cached(profile) != null))
            }
        }
    }

    fun openProfile(profile: HostProfile) {
        profileStore.select(profile.id)
        activeToken = profileStore.token(profile.id)
        selectedIssue = null
        issueMode = null
        bindingIssue = null
        dispatch(WorkflowEvent.ProfileSelected(profile, repository.cached(profile)))
        refresh(profile, activeToken)
    }

    fun api(): WorkflowApi? {
        val profile = state.activeProfile ?: return null
        return runCatching { WorkflowApiClient(profile.pmgrUrl, activeToken) }.getOrNull()
    }

    fun activate(target: ActivationTarget? = null, trackerItem: TrackerItem? = null, closeAfterActivation: Boolean = false) {
        val profile = state.activeProfile ?: return
        val activationId = trackerItem?.id ?: target?.id ?: return
        if (!state.canMutate) {
            val cachedTarget = target
            val connectionTmuxSession = state.data.tracker.connectionTmuxSession
            if (cachedTarget != null && cachedTarget.id == state.data.tracker.activeTarget?.id && cachedTarget.tmuxSession != null) {
                runCatching {
                    SshLauncher(ActivationGateway { error("Offline") }, sessionStarter).focusCached(
                        profile,
                        cachedTarget,
                        openActivity = !closeAfterActivation,
                    )
                }.onSuccess {
                    if (closeAfterActivation) onClose()
                }.onFailure { dispatch(WorkflowEvent.ActivationFailed(it.message ?: "Could not focus cached SSH session")) }
            } else if (trackerItem != null && connectionTmuxSession != null) {
                runCatching {
                    SshLauncher(ActivationGateway { error("Offline") }, sessionStarter).focusCached(
                        profile,
                        trackerItem.id,
                        connectionTmuxSession,
                    )
                }.onFailure { dispatch(WorkflowEvent.ActivationFailed(it.message ?: "Could not focus cached SSH session")) }
            }
            return
        }
        val client = api() ?: return
        dispatch(WorkflowEvent.ActivationStarted(activationId))
        scope.launch {
            val result = try {
                val launcher = SshLauncher(client, sessionStarter)
                if (trackerItem != null) {
                    launcher.launch(profile) { client.activateTrackerItem(trackerItem) }
                } else {
                    launcher.launch(profile, requireNotNull(target).id, openActivity = !closeAfterActivation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                dispatch(WorkflowEvent.ActivationFailed(error.message ?: "Activation failed"))
                return@launch
            }
            if (trackerItem == null) {
                dispatch(WorkflowEvent.ActivationSucceeded(result))
                cache.write(profile.id, state.data)
                if (closeAfterActivation) onClose()
                return@launch
            }
            dispatch(WorkflowEvent.TrackerActivationSucceeded(result, trackerItem.id))
            cache.write(profile.id, state.data)
            if (!trackerItem.completed) return@launch
            try {
                client.acknowledgeTrackerItem(trackerItem)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                dispatch(WorkflowEvent.TrackerAcknowledgementFailed(error.message ?: "Acknowledgement failed"))
                return@launch
            }
            dispatch(WorkflowEvent.TrackerTaskAcknowledged(trackerItem.id))
            cache.write(profile.id, state.data)
        }
    }

    fun loadIssue(issue: IssueSummary) {
        val client = api()
        if (client == null || !state.canMutate) {
            selectedIssue = IssueDetail(issue)
            return
        }
        scope.launch {
            runCatching { client.issue(issue.redmineId, issue.issueId) }
                .onSuccess { selectedIssue = it }
                .onFailure {
                    selectedIssue = IssueDetail(issue)
                    dispatch(WorkflowEvent.LoadFailed(it.message ?: "Issue detail unavailable", hasCache = true))
                }
        }
    }

    fun saveIssue(mutation: IssueMutation) {
        val profile = state.activeProfile ?: return
        val client = api() ?: return
        scope.launch {
            try {
                if (mutation.issueId == null) {
                    val created = client.createIssue(mutation)
                    selectedIssue = client.issue(created.redmineId, created.issueId)
                } else {
                    client.updateIssue(mutation)
                    selectedIssue = client.issue(mutation.redmineId, mutation.issueId)
                }
                issueMode = null
                dispatch(WorkflowEvent.DataLoaded(repository.refresh(profile, activeToken)))
            } catch (error: Throwable) {
                dispatch(WorkflowEvent.ActivationFailed(error.message ?: "Issue save failed"))
            }
        }
    }

    fun openBinding(issue: IssueDetail) {
        bindingIssue = issue
        bindingPresets = emptyList()
        val client = api() ?: return
        scope.launch {
            bindingPresets = runCatching {
                client.bindingPresets(issue.summary.redmineId, issue.summary.issueId, issue.summary.project)
            }.getOrDefault(emptyList())
        }
    }

    fun deleteBinding(issue: IssueDetail) {
        val profile = state.activeProfile ?: return
        val client = api() ?: return
        scope.launch {
            try {
                client.deleteBinding(issue.summary.redmineId, issue.summary.issueId)
                selectedIssue = client.issue(issue.summary.redmineId, issue.summary.issueId)
                dispatch(WorkflowEvent.DataLoaded(repository.refresh(profile, activeToken)))
            } catch (error: Throwable) {
                dispatch(WorkflowEvent.ActivationFailed(error.message ?: "Binding delete failed"))
            }
        }
    }

    fun saveBinding(mainProjectId: String, references: List<String>) {
        val issue = bindingIssue ?: return
        val profile = state.activeProfile ?: return
        val client = api() ?: return
        scope.launch {
            try {
                client.saveBinding(
                    issue.summary.redmineId,
                    issue.summary.issueId,
                    issue.summary.project,
                    mainProjectId,
                    references,
                )
                dispatch(WorkflowEvent.BindingSaved(issue.summary.redmineId, issue.summary.issueId))
                val started = client.startWorkspace(
                    issue.summary.redmineId,
                    issue.summary.issueId,
                    issue.summary.project,
                    issue.summary.subject,
                )
                dispatch(WorkflowEvent.WorkspaceStarted(started.target.id))
                val result = SshLauncher(client, sessionStarter).launch(profile, started.target.id)
                dispatch(WorkflowEvent.ActivationSucceeded(result))
                cache.write(profile.id, state.data)
                bindingIssue = null
                selectedIssue = client.issue(issue.summary.redmineId, issue.summary.issueId)
                dispatch(WorkflowEvent.DataLoaded(repository.refresh(profile, activeToken)))
            } catch (error: Throwable) {
                if (state.bindingFlow == null) {
                    dispatch(WorkflowEvent.ActivationFailed(error.message ?: "Binding save failed"))
                } else {
                    dispatch(WorkflowEvent.WorkspaceStartFailed(error.message ?: "Workspace creation failed"))
                }
            }
        }
    }

    LaunchedEffect(requestedDestination) {
        dispatch(WorkflowEvent.DestinationSelected(requestedDestination))
    }

    LaunchedEffect(Unit) {
        if (profiles.isEmpty()) return@LaunchedEffect
        val fastest = selector.fastest(profiles) { profile -> profileStore.token(profile.id) }
        val fallback = profiles.firstOrNull { it.id == profileStore.selectedProfileId() } ?: profiles.first()
        openProfile(fastest ?: fallback)
    }

    LaunchedEffect(state.activeProfile?.id, activeToken) {
        val profile = state.activeProfile ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(5_000)
                val client = runCatching { WorkflowApiClient(profile.pmgrUrl, activeToken) }.getOrNull() ?: continue
                runCatching { client.trackerState() }
                    .onSuccess { tracker ->
                        dispatch(WorkflowEvent.DataLoaded(state.data.copy(tracker = tracker)))
                        cache.write(profile.id, state.data)
                    }
                    .onFailure { error ->
                        dispatch(WorkflowEvent.LoadFailed(error.message ?: "Host is unreachable", hasCache = true))
                    }
            }
        }
    }

    MaterialTheme(colorScheme = ConsoleColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = ConsoleColors.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                SignalRail(
                    selected = state.destination,
                    profileName = state.activeProfile?.name,
                    onDestination = { destination ->
                        selectedIssue = null
                        issueMode = null
                        bindingIssue = null
                        dispatch(WorkflowEvent.DestinationSelected(destination))
                    },
                    onProfiles = { showProfiles = true },
                    onClose = onClose,
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    ConsoleHeader(
                        destination = state.destination,
                        profile = state.activeProfile,
                        mode = state.connectionMode,
                        onRefresh = { state.activeProfile?.let { refresh(it, activeToken) } },
                    )
                    state.error?.let { ErrorBanner(it, onDismiss = { dispatch(WorkflowEvent.ErrorCleared) }) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            bindingIssue != null -> BindingScreen(
                                issue = bindingIssue!!,
                                projects = state.data.projects,
                                presets = bindingPresets,
                                enabled = state.canMutate,
                                retrying = state.bindingFlow?.stage == BindingFlowStage.BindingSaved,
                                onSave = ::saveBinding,
                                onCancel = { bindingIssue = null },
                            )
                            issueMode != null -> IssueForm(
                                mode = issueMode!!,
                                api = api(),
                                enabled = state.canMutate,
                                onSave = ::saveIssue,
                                onCancel = { issueMode = null },
                            )
                            selectedIssue != null -> IssueDetailScreen(
                                issue = selectedIssue!!,
                                enabled = state.canMutate,
                                onBack = { selectedIssue = null },
                                onEdit = { issueMode = IssueMode.Edit(selectedIssue!!) },
                                onBinding = { openBinding(selectedIssue!!) },
                                onDeleteBinding = { deleteBinding(selectedIssue!!) },
                                onActivate = { selectedIssue!!.workspace?.let { activate(it) } },
                            )
                            state.connectionMode == ConnectionMode.Loading && state.activeProfile == null -> LoadingPanel()
                            else -> when (state.destination) {
                                WorkflowDestination.Issues -> IssuesScreen(
                                    data = state.data,
                                    onIssue = ::loadIssue,
                                    onCreate = { issueMode = IssueMode.Create },
                                    createEnabled = state.canMutate,
                                )
                                WorkflowDestination.Projects -> ProjectsScreen(
                                    projects = state.data.projects,
                                    targets = state.data.targets,
                                    switchingId = state.switchingTargetId,
                                    enabled = state.canMutate,
                                    onActivate = { activate(target = it, closeAfterActivation = true) },
                                )
                                WorkflowDestination.Workspaces -> WorkspacesScreen(
                                    targets = state.data.targets,
                                    activeTarget = state.data.tracker.activeTarget,
                                    switchingId = state.switchingTargetId,
                                    online = state.canMutate,
                                    onActivate = { activate(target = it, closeAfterActivation = true) },
                                )
                                WorkflowDestination.Usage -> UsageScreen(
                                    tracker = state.data.tracker,
                                    enabled = state.canMutate,
                                    onRefresh = {
                                        val client = api() ?: return@UsageScreen
                                        scope.launch {
                                            runCatching { client.refreshUsage() }
                                                .onSuccess { tracker -> dispatch(WorkflowEvent.DataLoaded(state.data.copy(tracker = tracker))) }
                                                .onFailure { dispatch(WorkflowEvent.ActivationFailed(it.message ?: "Usage refresh failed")) }
                                        }
                                    },
                                )
                                WorkflowDestination.Tracker -> TrackerScreen(
                                    tracker = state.data.tracker,
                                    switchingId = state.switchingTargetId,
                                    enabled = state.canMutate || state.data.tracker.connectionTmuxSession != null,
                                    onOpen = { item -> activate(trackerItem = item) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showProfiles) {
            ProfilesDialog(
                profiles = profiles,
                selectedId = state.activeProfile?.id,
                tokenForProfile = profileStore::token,
                onSave = { profile, token ->
                    val saved = profileStore.save(profile, token)
                    profiles = profileStore.profiles()
                    showProfiles = false
                    openProfile(saved)
                },
                onSelect = {
                    showProfiles = false
                    openProfile(it)
                },
                onDelete = {
                    profileStore.delete(it.id)
                    profiles = profileStore.profiles()
                    if (state.activeProfile?.id == it.id) {
                        state = WorkflowState(destination = state.destination)
                        profiles.firstOrNull()?.let(::openProfile)
                    }
                    showProfiles = profiles.isEmpty()
                },
                onDismiss = { if (profiles.isNotEmpty()) showProfiles = false },
            )
        }
    }
}

@Composable
private fun SignalRail(
    selected: WorkflowDestination,
    profileName: String?,
    onDestination: (WorkflowDestination) -> Unit,
    onProfiles: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.width(56.dp).fillMaxHeight().background(Color(0xFF0D141C)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailButton(profileName?.take(2)?.uppercase() ?: "HO", selected = false, onClick = onProfiles)
        Spacer(Modifier.height(16.dp))
        WorkflowDestination.entries.forEach { destination ->
            RailButton(destination.shortLabel, selected == destination) { onDestination(destination) }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        RailButton("TX", selected = false, onClick = onClose)
    }
}

@Composable
private fun RailButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(42.dp).clickable(onClick = onClick),
        color = if (selected) ConsoleColors.primary.copy(alpha = 0.18f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) ConsoleColors.primary else ConsoleColors.outline),
        shape = RoundedCornerShape(0.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConsoleHeader(
    destination: WorkflowDestination,
    profile: HostProfile?,
    mode: ConnectionMode,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0D141C)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(destination.name.uppercase(), fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            Text(
                profile?.let { "${it.name} | ${it.sshHostAlias}" } ?: "No Host Profile",
                color = ConsoleColors.onSurface.copy(alpha = 0.62f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        StatusTag(mode.name.uppercase(), mode != ConnectionMode.Offline)
        TextButton(onClick = onRefresh, enabled = profile != null) { Text("SYNC") }
    }
}

@Composable
private fun IssuesScreen(data: WorkflowData, onIssue: (IssueSummary) -> Unit, onCreate: () -> Unit, createEnabled: Boolean) {
    var sortMode by remember { mutableStateOf(IssueSortMode.Current) }
    val current = data.currentIssue
    val remaining = data.issues.filterNot { it.redmineId == current?.redmineId && it.issueId == current.issueId }
    val grouped = sortIssues(remaining, sortMode).groupBy { it.project.ifBlank { "No Project" } }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        item {
            SectionHeading("CURRENT ISSUE")
            if (current == null) EmptyLine("Active target is not mapped to an issue") else IssueCard(current, pinned = true) { onIssue(current) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { sortMode = sortMode.next() }) { Text("SORT ${sortMode.label}") }
                Spacer(Modifier.weight(1f))
                ConsoleButton("CREATE ISSUE", enabled = createEnabled, onClick = onCreate)
            }
        }
        grouped.toSortedMap().forEach { (project, issues) ->
            item { SectionHeading(project.uppercase()) }
            issues.groupBy { it.fixedVersion.ifBlank { "No Version" } }.toSortedMap().forEach { (version, versionIssues) ->
                item { Text(version, modifier = Modifier.padding(vertical = 7.dp), color = ConsoleColors.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                items(versionIssues, key = { "${it.redmineId}:${it.issueId}" }) { issue -> IssueCard(issue) { onIssue(issue) } }
            }
        }
    }
}

@Composable
private fun IssueCard(issue: IssueSummary, pinned: Boolean = false, onClick: () -> Unit) {
    ConsoleCard(onClick = onClick, accent = if (pinned) ConsoleColors.primary else ConsoleColors.outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${issue.issueId}", color = ConsoleColors.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(issue.subject.ifBlank { "Untitled issue" }, Modifier.weight(1f).padding(start = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            if (pinned) StatusTag("PINNED", true)
        }
        MetaLine(listOf(issue.redmineId, issue.status, issue.priority).filter(String::isNotBlank).joinToString(" | "))
    }
}

@Composable
private fun IssueDetailScreen(
    issue: IssueDetail,
    enabled: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onBinding: () -> Unit,
    onDeleteBinding: () -> Unit,
    onActivate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("BACK") }
            Text("#${issue.summary.issueId}", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            TextButton(onClick = onEdit, enabled = enabled) { Text("EDIT") }
        }
        Text(issue.summary.subject.ifBlank { "Untitled issue" }, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        MetaLine(listOf(issue.summary.project, issue.summary.fixedVersion, issue.summary.status, issue.summary.priority, issue.assignedTo).filter(String::isNotBlank).joinToString(" | "))
        DetailBlock("DESCRIPTION", issue.description.ifBlank { "Empty" })
        DetailBlock(
            "PROJECT BINDING",
            buildString {
                append(issue.binding.mainProject?.name ?: "No main project")
                if (issue.binding.referenceProjects.isNotEmpty()) append("\nRefs: ${issue.binding.referenceProjects.joinToString { it.name }}")
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleButton("BIND + START", enabled = enabled, modifier = Modifier.weight(1f), onClick = onBinding)
            ConsoleButton("UNBIND", enabled = enabled && issue.binding.mainProject != null, modifier = Modifier.weight(1f), onClick = onDeleteBinding)
            ConsoleButton("ACTIVATE", enabled = enabled && issue.workspace != null, modifier = Modifier.weight(1f), onClick = onActivate)
        }
        if (issue.attachments.isNotEmpty()) DetailBlock("ATTACHMENTS", issue.attachments.joinToString("\n") { it.filename })
        if (issue.feedback.isNotEmpty()) DetailBlock("FEEDBACK", issue.feedback.joinToString("\n\n") { "${it.author}: ${it.notes}" })
        if (issue.history.isNotEmpty()) DetailBlock("HISTORY", issue.history.joinToString("\n\n") { "${it.createdOn} ${it.notes}" })
    }
}

private sealed interface IssueMode {
    data object Create : IssueMode
    data class Edit(val issue: IssueDetail) : IssueMode
}

@Composable
private fun IssueForm(mode: IssueMode, api: WorkflowApi?, enabled: Boolean, onSave: (IssueMutation) -> Unit, onCancel: () -> Unit) {
    val initial = (mode as? IssueMode.Edit)?.issue
    var redmineId by remember(mode) { mutableStateOf(initial?.summary?.redmineId.orEmpty()) }
    var projectId by remember(mode) { mutableStateOf("") }
    var subject by remember(mode) { mutableStateOf(initial?.summary?.subject.orEmpty()) }
    var description by remember(mode) { mutableStateOf(initial?.description.orEmpty()) }
    var statusId by remember(mode) { mutableStateOf("") }
    var priorityId by remember(mode) { mutableStateOf("") }
    var assignedToId by remember(mode) { mutableStateOf("") }
    var fixedVersionId by remember(mode) { mutableStateOf("") }
    var notes by remember(mode) { mutableStateOf("") }
    var uploads by remember(mode) { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || api == null || redmineId.isBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val filename = context.displayName(uri)
                api.upload(redmineId, filename, context.contentResolver.getType(uri)) {
                    context.contentResolver.openInputStream(uri) ?: error("Attachment is unavailable")
                }
            }.onSuccess { uploads = uploads + it }.onFailure { uploadError = it.message }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("CANCEL") }
            Text(if (mode is IssueMode.Create) "CREATE ISSUE" else "EDIT ISSUE", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        }
        if (mode is IssueMode.Create) {
            FormField("Redmine ID", redmineId) { redmineId = it }
            FormField("Project ID", projectId) { projectId = it }
        }
        FormField("Subject", subject) { subject = it }
        FormField("Description", description, minLines = 4) { description = it }
        FormField("Status ID", statusId) { statusId = it }
        FormField("Priority ID", priorityId) { priorityId = it }
        FormField("Assignee ID", assignedToId) { assignedToId = it }
        FormField("Fixed Version ID", fixedVersionId) { fixedVersionId = it }
        FormField("Notes", notes, minLines = 3) { notes = it }
        uploadError?.let { Text(it, color = ConsoleColors.error) }
        if (uploads.isNotEmpty()) MetaLine("${uploads.size} attachment upload(s) ready")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = enabled && api != null && redmineId.isNotBlank(),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.weight(1f),
            ) { Text("ATTACH") }
            ConsoleButton(
                "SAVE",
                enabled = enabled && subject.isNotBlank() && redmineId.isNotBlank() && (mode is IssueMode.Edit || projectId.isNotBlank()),
                modifier = Modifier.weight(1f),
            ) {
                onSave(
                    IssueMutation(
                        redmineId = redmineId,
                        projectId = projectId,
                        issueId = initial?.summary?.issueId,
                        subject = subject,
                        description = description,
                        statusId = statusId.takeIf(String::isNotBlank),
                        priorityId = priorityId.takeIf(String::isNotBlank),
                        assignedToId = assignedToId.takeIf(String::isNotBlank),
                        fixedVersionId = fixedVersionId.takeIf(String::isNotBlank),
                        notes = notes,
                        uploads = uploads,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BindingScreen(
    issue: IssueDetail,
    projects: List<ProjectSummary>,
    presets: List<BindingPreset>,
    enabled: Boolean,
    retrying: Boolean,
    onSave: (String, List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var mainId by remember(issue.summary.issueId) { mutableStateOf(issue.binding.mainProject?.id.orEmpty()) }
    var references by remember(issue.summary.issueId) { mutableStateOf(issue.binding.referenceProjects.map { it.id }.toSet()) }
    val usablePresets = presets.filter { it.mainProject != null }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("BACK") }
            Column(Modifier.weight(1f)) {
                Text("BIND PROJECTS", fontWeight = FontWeight.Bold)
                MetaLine("#${issue.summary.issueId} ${issue.summary.subject}")
            }
            ConsoleButton(if (retrying) "RETRY START" else "SAVE + START", enabled && mainId.isNotBlank()) {
                onSave(mainId, references.filterNot { it == mainId })
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (usablePresets.isNotEmpty()) {
                item { SectionHeading("PREVIOUS COMBINATIONS") }
                items(usablePresets, key = { "preset:${it.id}" }) { preset ->
                    val main = requireNotNull(preset.mainProject)
                    ConsoleCard(onClick = {
                        mainId = main.id
                        references = preset.referenceProjects.map { it.id }.toSet() - main.id
                    }) {
                        Text(main.name, fontWeight = FontWeight.Bold)
                        MetaLine("Refs: ${preset.referenceProjects.joinToString { it.name }.ifBlank { "None" }} | Used ${preset.usageCount}")
                    }
                }
            }
            item { SectionHeading("PROJECT CATALOG") }
            items(projects, key = { "project:${it.id}" }) { project ->
                val isMain = project.id == mainId
                val isReference = project.id in references
                val accent = when {
                    isMain -> ConsoleColors.primary
                    isReference -> ConsoleColors.secondary
                    else -> ConsoleColors.outline
                }
                ConsoleCard(accent = accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        if (isMain) BindingTag("MAIN", ConsoleColors.primary)
                        if (isReference) BindingTag("REF", ConsoleColors.secondary)
                    }
                    MetaLine(project.repoPath)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { mainId = project.id; references = references - project.id }, enabled = enabled && !isMain) { Text(if (isMain) "MAIN SELECTED" else "SET MAIN") }
                        TextButton(
                            onClick = { references = if (project.id in references) references - project.id else references + project.id },
                            enabled = enabled && !isMain,
                        ) { Text(if (isReference) "REMOVE REF" else "ADD REF") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsScreen(
    projects: List<ProjectSummary>,
    targets: List<ActivationTarget>,
    switchingId: String?,
    enabled: Boolean,
    onActivate: (ActivationTarget) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { SectionHeading("PROJECT HOMES") }
        items(projects, key = { it.id }) { project ->
            val target = targets.firstOrNull { it.kind == TargetKind.ProjectHome && it.projectId == project.id }
                ?: targets.firstOrNull { it.id == "project:${project.id}" }
                ?: ActivationTarget("project:${project.id}", project.name, TargetKind.ProjectHome, projectId = project.id, projectName = project.name)
            ConsoleCard(onClick = { if (enabled) onActivate(target) }, accent = Color(0xFF39765A)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    if (switchingId == target.id) StatusTag("SWITCHING", true) else StatusTag("HOME", true)
                }
                MetaLine("${project.boundIssueCount} issues | ${project.workspaceCount} workspaces | ${project.repoPath}")
            }
        }
    }
}

@Composable
private fun WorkspacesScreen(
    targets: List<ActivationTarget>,
    activeTarget: ActivationTarget?,
    switchingId: String?,
    online: Boolean,
    onActivate: (ActivationTarget) -> Unit,
) {
    var showArchived by remember { mutableStateOf(false) }
    val visible = targets.filter { showArchived || !it.archived }.filterNot { it.id == activeTarget?.id }
    val homes = visible.filter { it.kind == TargetKind.ProjectHome }
    val issues = visible.filter { it.kind == TargetKind.IssueWorkspace }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            SectionHeading("ACTIVE TARGET")
            if (activeTarget == null) EmptyLine("No active target") else TargetCard(activeTarget, switchingId, online || activeTarget.tmuxSession != null) { onActivate(activeTarget) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showArchived, onCheckedChange = { showArchived = it })
                Text("Show archived workspaces")
            }
            SectionHeading("PROJECT HOMES")
        }
        items(homes, key = { it.id }) { target -> TargetCard(target, switchingId, online) { onActivate(target) } }
        item { SectionHeading("ACTIVE ISSUE WORKSPACES") }
        items(issues, key = { it.id }) { target -> TargetCard(target, switchingId, online) { onActivate(target) } }
    }
}

@Composable
private fun TargetCard(target: ActivationTarget, switchingId: String?, enabled: Boolean, onClick: () -> Unit) {
    ConsoleCard(onClick = { if (enabled) onClick() }, accent = if (target.archived) ConsoleColors.outline else Color(0xFF665A99)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(target.name.ifBlank { target.id }, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            StatusTag(if (switchingId == target.id) "SWITCHING" else target.kind.name.uppercase(), !target.archived)
        }
        MetaLine(listOf(target.projectName, target.issueId?.let { "#$it" }.orEmpty(), target.subject, target.status).filter(String::isNotBlank).joinToString(" | "))
    }
}

@Composable
private fun UsageScreen(tracker: TrackerState, enabled: Boolean, onRefresh: () -> Unit) {
    val usage = tracker.usage
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeading("USAGE SNAPSHOT", Modifier.weight(1f))
                ConsoleButton("REFRESH", enabled && !tracker.usageRefreshing, onClick = onRefresh)
            }
            MetaLine(if (tracker.usageRefreshing) "Refreshing usage..." else usage.refreshedAt.ifBlank { "No refresh timestamp" })
            tracker.error?.let { EmptyLine(it) }
        }
        items(usage.metrics, key = { it.label }) { metric ->
            ConsoleCard {
                Text(metric.label, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(metric.displayValue)
                        metric.displayLimit?.let { append(" / $it") }
                        if (metric.unit.isNotBlank() && metric.unit != "tokens") append(" ${metric.unit}")
                    },
                    color = ConsoleColors.secondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                )
                if (metric.resetAt.isNotBlank()) {
                    MetaLine("NEXT REFRESH IN ${compactDurationUntil(metric.resetAt, nowMillis)}")
                }
            }
        }
        if (usage.tokenCost.totalTokens > 0 || usage.dailyTokenCost.totalTokens > 0) {
            item { CostSummary(usage.tokenCost, usage.dailyTokenCost) }
        }
        item {
            SectionHeading("RESET CREDITS")
            ConsoleCard(accent = ConsoleColors.secondary) {
                Text("${usage.resetCreditExpiries.size} AVAILABLE", color = ConsoleColors.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                if (usage.resetCreditExpiries.isEmpty()) {
                    MetaLine("No reset credit expiry")
                } else {
                    usage.resetCreditExpiries.forEachIndexed { index, expiry ->
                        MetaLine("#${index + 1} EXPIRES IN ${compactDurationUntil(expiry, nowMillis)} | ${formatUsageTimestamp(expiry)}")
                    }
                }
            }
        }
        item { DailyUsageChart(usage.days) }
        item { ProjectUsageChart(usage.projects) }
    }
}

@Composable
private fun CostSummary(total: TokenCost, today: TokenCost) {
    SectionHeading("COST ESTIMATE")
    ConsoleCard(accent = ConsoleColors.secondary) {
        Text("TODAY CNY ${today.cnyDisplay}", color = ConsoleColors.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        MetaLine("TOTAL CNY ${total.cnyDisplay} | DeepSeek-V4-Pro")
        MetaLine("Input ${today.inputUncached.let(::compactTokenCount)} | Output ${today.output.let(::compactTokenCount)} | Cached ${today.inputCached.let(::compactTokenCount)}")
    }
}

@Composable
private fun DailyUsageChart(days: List<UsageDay>) {
    val scrollState = rememberScrollState()
    val visibleDays = days.asReversed()
    val maxTotal = visibleDays.maxOfOrNull(UsageDay::total)?.coerceAtLeast(1) ?: 1
    SectionHeading("DAILY HISTORY  /  DRAG HORIZONTALLY")
    ConsoleCard(accent = ConsoleColors.primary) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ChartLegend("OpenCode", ConsoleColors.primary)
            ChartLegend("Codex", ConsoleColors.secondary)
        }
        if (visibleDays.isEmpty()) {
            EmptyLine("No daily usage")
            return@ConsoleCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            visibleDays.forEach { day ->
                val codexHeight = (120f * day.codex / maxTotal).coerceAtLeast(if (day.codex > 0) 2f else 0f)
                val opencodeHeight = (120f * day.opencode / maxTotal).coerceAtLeast(if (day.opencode > 0) 2f else 0f)
                Column(
                    modifier = Modifier.width(58.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(day.totalDisplay, color = ConsoleColors.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Column(
                        modifier = Modifier.height(124.dp).width(24.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Box(Modifier.fillMaxWidth().height(opencodeHeight.dp).background(ConsoleColors.primary))
                        Box(Modifier.fillMaxWidth().height(codexHeight.dp).background(ConsoleColors.secondary))
                    }
                    if (day.cost.totalTokens > 0) {
                        Text("CNY ${day.cost.cnyDisplay}", color = ConsoleColors.secondary, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                    Text(day.day.takeLast(5), color = ConsoleColors.outline, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ProjectUsageChart(projects: List<UsageProject>) {
    val scrollState = rememberScrollState()
    val sortedProjects = projects.sortedByDescending(UsageProject::total)
    val maxTotal = sortedProjects.maxOfOrNull(UsageProject::total)?.coerceAtLeast(1) ?: 1
    SectionHeading("PROJECT USAGE  /  DRAG VERTICALLY")
    ConsoleCard(accent = ConsoleColors.secondary) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ChartLegend("Total", ConsoleColors.primary)
            ChartLegend("Today", ConsoleColors.secondary)
        }
        if (sortedProjects.isEmpty()) {
            EmptyLine("No project usage")
            return@ConsoleCard
        }
        Column(
            modifier = Modifier.fillMaxWidth().height(340.dp).verticalScroll(scrollState).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            sortedProjects.forEach { project ->
                val totalFraction = (project.total.toFloat() / maxTotal).coerceIn(0f, 1f)
                val dailyFraction = (project.daily.toFloat() / maxTotal).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text("${project.totalDisplay} / ${project.dailyDisplay}", color = ConsoleColors.secondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).background(ConsoleColors.outline.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(totalFraction).fillMaxHeight().background(ConsoleColors.primary))
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp).background(ConsoleColors.outline.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(dailyFraction).fillMaxHeight().background(ConsoleColors.secondary))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color))
        Text(label.uppercase(), color = ConsoleColors.outline, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun TrackerScreen(
    tracker: TrackerState,
    switchingId: String?,
    enabled: Boolean,
    onOpen: (TrackerItem) -> Unit,
) {
    val inbox = tracker.items.filter { !it.completed || !it.acknowledged }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { SectionHeading("TRACKER INBOX") }
        tracker.error?.let { error -> item { EmptyLine(error) } }
        if (inbox.isEmpty()) item { EmptyLine("No in-progress or unacknowledged completed tasks") }
        items(inbox, key = { it.id }) { item ->
            val accent = when {
                item.waiting -> WaitingColor
                item.completed -> ConsoleColors.secondary
                else -> ConsoleColors.primary
            }
            ConsoleCard(onClick = { if (enabled) onOpen(item) }, accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title.ifBlank { item.id }, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    when {
                        switchingId == item.id -> StatusTag("SWITCHING", true)
                        item.waiting -> BindingTag("WAITING", WaitingColor)
                        else -> StatusTag(item.state.uppercase(), !item.completed)
                    }
                }
                val attention = if (item.waiting) "Waiting for user" else item.attentionReason
                MetaLine(listOf(attention, item.session, item.window, item.completedAt).filter(String::isNotBlank).joinToString(" | "))
            }
        }
    }
}

@Composable
private fun ProfilesDialog(
    profiles: List<HostProfile>,
    selectedId: String?,
    tokenForProfile: (String) -> String,
    onSave: (HostProfile, String) -> Unit,
    onSelect: (HostProfile) -> Unit,
    onDelete: (HostProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember(profiles) { mutableStateOf<HostProfile?>(null) }
    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var url by remember(editing) { mutableStateOf(editing?.pmgrUrl.orEmpty()) }
    var alias by remember(editing) { mutableStateOf(editing?.sshHostAlias.orEmpty()) }
    var token by remember(editing) { mutableStateOf(editing?.let { tokenForProfile(it.id) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(0.dp),
        title = { Text("HOST PROFILES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                profiles.forEach { profile ->
                    ConsoleCard(onClick = { onSelect(profile) }, accent = if (profile.id == selectedId) ConsoleColors.primary else ConsoleColors.outline) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.Bold)
                                MetaLine("${profile.pmgrUrl} | ${profile.sshHostAlias}")
                            }
                            TextButton(onClick = { editing = profile }) { Text("EDIT") }
                            TextButton(onClick = { onDelete(profile) }) { Text("DEL") }
                        }
                    }
                }
                SectionHeading(if (editing == null) "ADD PROFILE" else "EDIT PROFILE")
                FormField("Name", name) { name = it }
                FormField("PMGR URL", url) { url = it }
                FormField("SSH Host alias", alias) { alias = it }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    shape = RoundedCornerShape(0.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        HostProfile(editing?.id ?: UUID.randomUUID().toString(), name, url, alias),
                        token,
                    )
                },
                enabled = url.isNotBlank() && alias.isNotBlank() && token.isNotBlank(),
            ) { Text("SAVE") }
        },
        dismissButton = { if (profiles.isNotEmpty()) TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

@Composable
private fun ConsoleCard(
    onClick: (() -> Unit)? = null,
    accent: Color = ConsoleColors.outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).let { base -> if (onClick == null) base else base.clickable(onClick = onClick) }
    Surface(modifier = modifier, color = ConsoleColors.surface, border = BorderStroke(1.dp, accent), shape = RoundedCornerShape(0.dp)) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun ConsoleButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ConsoleColors.primary),
    ) { Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 12.dp, bottom = 8.dp), color = ConsoleColors.onSurface.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

@Composable
private fun DetailBlock(title: String, value: String) {
    SectionHeading(title)
    ConsoleCard { Text(value, lineHeight = 20.sp) }
}

@Composable
private fun FormField(label: String, value: String, minLines: Int = 1, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, minLines = minLines, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(0.dp))
}

@Composable
private fun MetaLine(value: String) {
    if (value.isNotBlank()) Text(value, modifier = Modifier.padding(top = 5.dp), color = ConsoleColors.onSurface.copy(alpha = 0.58f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun EmptyLine(value: String) {
    Text(value, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), color = ConsoleColors.onSurface.copy(alpha = 0.55f))
}

@Composable
private fun StatusTag(text: String, positive: Boolean) {
    Surface(color = if (positive) ConsoleColors.primary.copy(alpha = 0.14f) else ConsoleColors.error.copy(alpha = 0.14f), border = BorderStroke(1.dp, if (positive) ConsoleColors.primary else ConsoleColors.error), shape = RoundedCornerShape(0.dp)) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

@Composable
private fun BindingTag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color), shape = RoundedCornerShape(0.dp)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(ConsoleColors.error.copy(alpha = 0.13f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = ConsoleColors.error, maxLines = 2)
        TextButton(onClick = onDismiss) { Text("DISMISS") }
    }
}

@Composable
private fun LoadingPanel() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ConsoleColors.primary)
    }
}

private fun Context.displayName(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
}?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "attachment" }
