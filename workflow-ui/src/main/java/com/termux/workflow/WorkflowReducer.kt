package com.termux.workflow

enum class ConnectionMode { Loading, Online, Offline }
enum class BindingFlowStage { BindingSaved, WorkspaceStarted }

data class BindingFlow(
    val redmineId: String,
    val issueId: String,
    val stage: BindingFlowStage,
    val targetId: String? = null,
)

data class WorkflowState(
    val destination: WorkflowDestination = WorkflowDestination.Tracker,
    val activeProfile: HostProfile? = null,
    val connectionMode: ConnectionMode = ConnectionMode.Loading,
    val data: WorkflowData = WorkflowData(),
    val switchingTargetId: String? = null,
    val bindingFlow: BindingFlow? = null,
    val error: String? = null,
) {
    val canMutate: Boolean get() = connectionMode == ConnectionMode.Online
}

sealed interface WorkflowEvent {
    data class DestinationSelected(val destination: WorkflowDestination) : WorkflowEvent
    data class ProfileSelected(val profile: HostProfile, val cachedData: WorkflowData?) : WorkflowEvent
    data class DataLoaded(val data: WorkflowData) : WorkflowEvent
    data class LoadFailed(val message: String, val hasCache: Boolean) : WorkflowEvent
    data class ActivationStarted(val targetId: String) : WorkflowEvent
    data class ActivationSucceeded(val result: ActivationResult) : WorkflowEvent
    data class TrackerActivationSucceeded(val result: ActivationResult, val taskId: String) : WorkflowEvent
    data class TrackerTaskAcknowledged(val taskId: String) : WorkflowEvent
    data class TrackerAcknowledgementFailed(val message: String) : WorkflowEvent
    data class ActivationFailed(val message: String) : WorkflowEvent
    data class BindingSaved(val redmineId: String, val issueId: String) : WorkflowEvent
    data class WorkspaceStarted(val targetId: String) : WorkflowEvent
    data class WorkspaceStartFailed(val message: String) : WorkflowEvent
    data object ErrorCleared : WorkflowEvent
}

object WorkflowReducer {
    fun reduce(state: WorkflowState, event: WorkflowEvent): WorkflowState = when (event) {
        is WorkflowEvent.DestinationSelected -> state.copy(destination = event.destination, error = null)
        is WorkflowEvent.ProfileSelected -> state.copy(
            activeProfile = event.profile,
            connectionMode = ConnectionMode.Loading,
            data = event.cachedData ?: WorkflowData(),
            switchingTargetId = null,
            bindingFlow = null,
            error = null,
        )
        is WorkflowEvent.DataLoaded -> state.copy(
            connectionMode = ConnectionMode.Online,
            data = event.data.copy(
                tracker = event.data.tracker.withCachedConnection(state.data.tracker),
            ),
            error = null,
        )
        is WorkflowEvent.LoadFailed -> state.copy(
            connectionMode = ConnectionMode.Offline,
            error = event.message,
        )
        is WorkflowEvent.ActivationStarted -> state.copy(switchingTargetId = event.targetId, error = null)
        is WorkflowEvent.ActivationSucceeded -> state.copy(
            connectionMode = ConnectionMode.Online,
            switchingTargetId = null,
            data = state.data.copy(
                tracker = state.data.tracker.copy(
                    activeTarget = state.data.targets.firstOrNull { it.id == event.result.targetId }?.copy(tmuxSession = event.result.tmuxSession)
                        ?: ActivationTarget(event.result.targetId, event.result.targetId, TargetKind.IssueWorkspace, tmuxSession = event.result.tmuxSession),
                ),
            ),
            bindingFlow = null,
            error = null,
        )
        is WorkflowEvent.TrackerActivationSucceeded -> state.copy(
            connectionMode = ConnectionMode.Online,
            switchingTargetId = null,
            data = state.data.copy(
                tracker = state.data.tracker.copy(
                    activeTarget = null,
                    currentTarget = null,
                    connectionTmuxSession = event.result.tmuxSession,
                ),
            ),
            error = null,
        )
        is WorkflowEvent.TrackerTaskAcknowledged -> state.copy(
            data = state.data.copy(
                tracker = state.data.tracker.copy(
                    items = state.data.tracker.items.map { item ->
                        if (item.id == event.taskId) item.copy(acknowledged = true) else item
                    },
                ),
            ),
            error = null,
        )
        is WorkflowEvent.TrackerAcknowledgementFailed -> state.copy(error = event.message)
        is WorkflowEvent.ActivationFailed -> state.copy(switchingTargetId = null, error = event.message)
        is WorkflowEvent.BindingSaved -> state.copy(
            bindingFlow = BindingFlow(event.redmineId, event.issueId, BindingFlowStage.BindingSaved),
            error = null,
        )
        is WorkflowEvent.WorkspaceStarted -> state.copy(
            bindingFlow = state.bindingFlow?.copy(stage = BindingFlowStage.WorkspaceStarted, targetId = event.targetId),
            error = null,
        )
        is WorkflowEvent.WorkspaceStartFailed -> state.copy(error = event.message)
        WorkflowEvent.ErrorCleared -> state.copy(error = null)
    }
}
