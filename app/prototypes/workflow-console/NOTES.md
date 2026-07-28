# Prototype Answer

**Question**: What should the Termux Workflow Console look like?

**Selected direction**: A / Signal Rail.

Signal Rail is the baseline because its dense, dark, square-edged presentation fits Termux and the existing Hades visual language while keeping Issues, Projects, Workspaces, Usage, and Tracker immediately reachable.

Keep the 56px Signal Rail visible inside the Workflow Console. The Termux drawer provides direct entry from the terminal, while the rail handles navigation between Console destinations without returning to the drawer.

Pin the Current Issue above the issue browser. Organize the remaining issues in collapsible Project -> Version groups.

On Workspaces, pin the Active Target first, then separate Project Homes from Active Issue Workspaces. Archived Workspaces are available through a filter rather than the default list.

Activating an existing target shows a pending `Switching` state. Focus the existing Termux SSH session only after PMGR confirms the remote tmux switch; keep the Console visible with an error if activation fails.

Saving a new Issue binding continues directly through Issue Workspace creation and target activation. If workspace creation fails, keep the saved binding and show a retry action instead of rolling the binding back.

The first release keeps completed, unacknowledged AI Tasks in Tracker Inbox. Opening one activates its target and acknowledges the task only after activation succeeds.

Refresh tracker state only while the Console is in the foreground. Background Tracker Alert delivery, including FCM, is deferred.

After creating a Redmine Issue, open its Issue Detail and stop. Binding and workspace creation require a separate explicit user action.

On the Android lock screen, a Tracker Alert may show the Project or Issue identifier and attention reason, but not the task summary, completion note, issue body, repository path, or other detailed content.

Ship the customized app as a replacement for the existing Termux installation. Keep `com.termux` and the standard filesystem prefix, use a private signing key, and require backup plus removal or matching rebuilds of differently signed Termux plugins.

The prototype remains throwaway. Production implementation should rebuild the selected decisions rather than promote the prototype code directly.
