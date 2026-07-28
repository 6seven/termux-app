package com.termux.workflow

import android.content.Context
import android.content.Intent

enum class WorkflowDestination(val extraValue: String, val shortLabel: String) {
    Issues("issues", "IS"),
    Projects("projects", "PR"),
    Workspaces("workspaces", "WS"),
    Usage("usage", "US"),
    Tracker("tracker", "TR");

    fun intent(context: Context): Intent = Intent(context, WorkflowActivity::class.java)
        .putExtra(EXTRA_DESTINATION, extraValue)

    companion object {
        const val EXTRA_DESTINATION = "com.termux.workflow.extra.DESTINATION"

        @JvmStatic
        fun fromExtra(value: String?): WorkflowDestination = entries.firstOrNull { it.extraValue == value } ?: Issues
    }
}
