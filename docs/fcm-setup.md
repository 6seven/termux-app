# Tracker FCM Setup

## Firebase Project

1. Create a Firebase project and register an Android app with package name `com.termux`.
2. Download its `google-services.json` to `app/google-services.json`.
3. Enable Firebase Cloud Messaging.
4. Create a Firebase Admin service account key on each Development Host that will send Tracker Alerts. Store the JSON outside every repository.

The Android Google Services plugin is applied only when `app/google-services.json` exists. Builds without that file continue to work with push disabled.

## PMGR

Set the service account path in the PMGR environment:

```bash
PMGR_FIREBASE_CREDENTIALS_PATH=/absolute/path/to/firebase-service-account.json
```

Restart PMGR after installing the updated Python dependencies and applying this setting. Device registration records are stored in the PMGR SQLite database.

## ai-tracker

Set the local PMGR endpoint before installing or restarting the tracker server:

```bash
AI_TRACKER_PMGR_URL=http://127.0.0.1:8710
AI_TRACKER_PMGR_TOKEN_FILE=/absolute/path/to/pmgr-api-token
```

Store the same value as `PMGR_API_TOKEN` in the token file with mode `0600`. Leave the token file setting unset when PMGR API authentication is disabled. Re-run `scripts/install_brew_service.sh` so the Homebrew service receives these values.

## Android Registration

Build and install the app after adding `google-services.json`. Open the Workflow Console once and allow notifications when Android asks. Each Host Profile registers independently with its PMGR endpoint.

When an AI Task completes, the app should show a Tracker Alert. Opening it must activate the matching target before acknowledging the task.
