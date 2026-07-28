package com.termux.workflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.termux.shared.shell.command.ExecutionCommand.Runner
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode
import com.termux.shared.termux.TermuxConstants

fun interface ActivationGateway {
    suspend fun activateTarget(targetId: String): ActivationResult
}

data class SshCommand(
    val executable: String,
    val arguments: List<String>,
    val shellName: String,
    val reuseNamedShell: Boolean,
)

fun interface SshSessionCommandStarter {
    fun start(command: SshCommand)
}

class SshSessionStarter(private val startCommand: (SshCommand) -> Unit) : SshSessionCommandStarter {
    var lastCommand: SshCommand? = null
        private set

    override fun start(command: SshCommand) {
        lastCommand = command
        startCommand(command)
    }
}

class SshLauncher(
    private val activationGateway: ActivationGateway,
    private val sessionStarter: SshSessionCommandStarter,
) {
    suspend fun launch(profile: HostProfile, targetId: String): ActivationResult {
        return launch(profile) { activationGateway.activateTarget(targetId) }
    }

    suspend fun launch(profile: HostProfile, activate: suspend () -> ActivationResult): ActivationResult {
        val result = activate()
        sessionStarter.start(command(profile, result))
        return result
    }

    fun focusCached(profile: HostProfile, target: ActivationTarget) {
        val tmuxSession = requireNotNull(target.tmuxSession) { "Cached target has no tmux session" }
        focusCached(profile, target.id, tmuxSession)
    }

    fun focusCached(profile: HostProfile, targetId: String, tmuxSession: String) {
        sessionStarter.start(command(profile, ActivationResult(targetId, profile.sshHostAlias, tmuxSession)))
    }

    companion object {
        fun command(profile: HostProfile, result: ActivationResult): SshCommand = SshCommand(
            executable = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/ssh",
            arguments = listOf(
                "-tt",
                result.sshHostAlias?.takeIf(String::isNotBlank) ?: profile.sshHostAlias,
                "tmux",
                "attach-session",
                "-t",
                result.tmuxSession,
            ),
            shellName = "workflow-ssh-${profile.id.replace(Regex("[^A-Za-z0-9._-]"), "-")}",
            reuseNamedShell = true,
        )
    }
}

class TermuxSshSessionStarter(private val context: Context) : SshSessionCommandStarter {
    override fun start(command: SshCommand) {
        val uri = Uri.Builder()
            .scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
            .path(command.executable)
            .build()
        val intent = Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri)
            .setComponent(ComponentName(context.packageName, "com.termux.app.TermuxService"))
            .putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, command.arguments.toTypedArray())
            .putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER, Runner.TERMINAL_SESSION.getName())
            .putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY.toString(),
            )
            .putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME, command.shellName)
            .putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE,
                if (command.reuseNamedShell) ShellCreateMode.NO_SHELL_WITH_NAME.getMode() else ShellCreateMode.ALWAYS.getMode(),
            )
            .putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Workflow SSH")
        context.startService(intent)
    }
}
