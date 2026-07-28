# Workflow Console

The Workflow Console is the Termux-hosted client for observing and operating a remote development workflow. Remote systems remain authoritative; device-side data is limited to connection configuration, credentials, and disposable caches.

## Language

**Workflow Console**:
The Termux-hosted client boundary that presents workflow state and sends user actions to its authoritative remote systems.
_Avoid_: Workflow engine, local workflow owner, mobile backend

**Authoritative State**:
Workflow data whose accepted current value is owned by PMGR or ai-tracker rather than by the Workflow Console.
_Avoid_: Synced local state, mobile state

**Disposable Cache**:
A device-side copy of authoritative state that may be deleted or rebuilt without changing the workflow.
_Avoid_: Local source of truth, offline state

**Development Host**:
The remote machine that runs PMGR, ai-tracker, and the tmux environment containing development workspaces.
_Avoid_: Backend, server machine, target box

**Host Profile**:
The connection identity for one Development Host, pairing its PMGR endpoint, API credential, SSH Host alias, and Disposable Cache.
_Avoid_: Server profile, account

**Active Host**:
The reachable Host Profile with the fastest PMGR response when the Workflow Console opens. All views and actions are scoped to this host until selection runs again.
_Avoid_: Default server, current account

**Remote Tmux Connection**:
A long-lived Termux SSH session attached to the tmux environment of one Development Host. Workspaces are switched inside this connection rather than opened as separate SSH sessions.
_Avoid_: Workspace session, per-workspace SSH session

**Activation Target**:
A Project Home or Issue Workspace that can be selected in the Development Host's tmux environment.
_Avoid_: Generic workspace, SSH session

**Active Target**:
The Activation Target currently selected in the Development Host's tmux environment.
_Avoid_: Selected list item, current page

**Activate Target**:
Make an Activation Target current in the Development Host's tmux environment, then bring its Remote Tmux Connection to the foreground.
_Avoid_: Open a new SSH session, launch workspace

**Project Home**:
The long-lived activation target for a project's main repository, independent of any issue-specific work.
_Avoid_: Main workspace, default issue workspace

**Issue Workspace**:
A task-scoped activation target created for work on one issue and its bound projects.
_Avoid_: Project Home, generic workspace

**Current Issue**:
The Issue associated with the Active Target. It is distinct from an Issue merely selected for viewing in the Workflow Console.
_Avoid_: Selected Issue, latest Issue

**Main Project**:
The project that supplies an Issue Workspace's primary worktree. It remains fixed after that Issue Workspace is created.
_Avoid_: Primary repository, active project

**Reference Project**:
A project included in an Issue Workspace for supporting context without becoming its Main Project. Reference Projects may be adjusted during the workspace lifetime.
_Avoid_: Secondary workspace, dependency

**Archived Workspace**:
An Issue Workspace hidden from the default work list after its issue closes, while its tmux window, worktrees, branches, and uncommitted work remain intact and recoverable.
_Avoid_: Deleted workspace, closed terminal

**AI Task**:
An ai-tracker record of AI work associated with a tmux target. It is either in progress or completed, with a completed task optionally requiring attention.
_Avoid_: Notification, Issue, OpenCode Session

**Tracker Inbox**:
The actionable view containing all in-progress AI Tasks and completed AI Tasks that have not been acknowledged.
_Avoid_: Task history, notification log

**Tracker Alert**:
An Android system notification for a completed AI Task that has not been acknowledged. Its public preview identifies the Project or Issue and attention reason but omits task details; opening it activates the target before acknowledgement.
_Avoid_: AI Task, progress notification

**Acknowledge**:
Record that the user has activated the tmux target for a completed AI Task and no longer needs it in the Tracker Inbox.
_Avoid_: Complete task, dismiss without opening

**Usage Snapshot**:
The quota and token-usage view reported for the Active Host. Snapshots from different Host Profiles remain separate and are never combined.
_Avoid_: Global usage, cross-host total, live billing state

**Offline View**:
A read-only presentation of a Host Profile's Disposable Cache while PMGR is unreachable. It permits returning to an existing Remote Tmux Connection but queues no remote actions.
_Avoid_: Offline mode, pending sync, local workflow
