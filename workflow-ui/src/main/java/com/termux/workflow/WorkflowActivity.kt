package com.termux.workflow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class WorkflowActivity : ComponentActivity() {
    private var requestedDestination by mutableStateOf(WorkflowDestination.Issues)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDestination = WorkflowDestination.fromExtra(intent?.getStringExtra(WorkflowDestination.EXTRA_DESTINATION))
        setContent {
            WorkflowConsoleApp(requestedDestination = requestedDestination, onClose = ::finish)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination = WorkflowDestination.fromExtra(intent.getStringExtra(WorkflowDestination.EXTRA_DESTINATION))
    }
}
