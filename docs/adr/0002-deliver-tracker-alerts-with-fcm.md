# Deliver Tracker Alerts with FCM

Status: Accepted

This decision supersedes ADR 0001 for releases that include Firebase configuration.

ai-tracker publishes task state changes to PMGR. PMGR sends a data-only FCM message to each registered Workflow Console installation. The message identifies the Host Profile and tmux target but does not include task summaries, completion notes, issue bodies, or repository paths.

The Workflow Console treats FCM as an invalidation signal. It fetches `/mobile/tracker-state` after a message and when the Console returns to the foreground. PMGR and ai-tracker remain authoritative; FCM delivery is not treated as workflow state.

Completed tasks produce a Tracker Alert. Opening the alert selects its Host Profile, activates its tmux target, and acknowledges the task only after activation succeeds.

When Firebase is not configured, push registration and delivery remain disabled without changing foreground refresh behavior.
