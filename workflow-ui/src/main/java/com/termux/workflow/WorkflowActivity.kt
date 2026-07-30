package com.termux.workflow

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.termux.shared.android.PermissionUtils

class WorkflowActivity : ComponentActivity() {
    private var requestedDestination by mutableStateOf(WorkflowDestination.Tracker)
    private var requestedTrackerTarget by mutableStateOf<TrackerPushTarget?>(null)
    private var requestedTrackerVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDestination = WorkflowDestination.fromExtra(intent?.getStringExtra(WorkflowDestination.EXTRA_DESTINATION))
        requestedTrackerTarget = TrackerPushTarget.fromIntent(intent)
        TrackerNotifications.initialize(this)
        if (FirebaseApp.getApps(this).isNotEmpty() && !PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this)
        }
        TrackerPushRegistration.registerAll(this)
        setContent {
            WorkflowConsoleApp(
                requestedDestination = requestedDestination,
                requestedTrackerTarget = requestedTrackerTarget,
                requestedTrackerVersion = requestedTrackerVersion,
                onClose = ::openTerminal,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination = WorkflowDestination.fromExtra(intent.getStringExtra(WorkflowDestination.EXTRA_DESTINATION))
        requestedTrackerTarget = TrackerPushTarget.fromIntent(intent)
        requestedTrackerVersion++
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.repeatCount == 0) {
            openTerminal()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openTerminal() {
        startActivity(Intent(Intent.ACTION_MAIN).setClassName(packageName, TERMINAL_ACTIVITY))
        finish()
    }

    private companion object {
        const val TERMINAL_ACTIVITY = "com.termux.app.TermuxActivity"
    }
}
