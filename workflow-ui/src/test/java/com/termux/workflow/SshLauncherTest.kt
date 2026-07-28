package com.termux.workflow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshLauncherTest {
    @Test
    fun activatesBeforeStartingStableNamedSshSession() = runTest {
        val calls = mutableListOf<String>()
        val starter = SshSessionStarter { command -> calls += "start:${command.arguments.joinToString("|")}" }
        val launcher = SshLauncher(
            activationGateway = ActivationGateway { targetId ->
                calls += "activate:$targetId"
                ActivationResult(targetId, "development", "RM-42")
            },
            sessionStarter = starter,
        )

        launcher.launch(HostProfile("profile-a", "Desk", "https://pmgr", "desk"), "issue:42")

        assertEquals("activate:issue:42", calls[0])
        assertEquals("start:-tt|development|tmux|attach-session|-t|RM-42", calls[1])
        assertEquals("/data/data/com.termux/files/usr/bin/ssh", starter.lastCommand?.executable)
        assertEquals("workflow-ssh-profile-a", starter.lastCommand?.shellName)
        assertTrue(starter.lastCommand?.reuseNamedShell == true)
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotStartSshWhenActivationFails() = runTest {
        val starter = SshSessionStarter { }
        val launcher = SshLauncher(
            activationGateway = ActivationGateway { throw IllegalStateException("activation failed") },
            sessionStarter = starter,
        )

        try {
            launcher.launch(HostProfile("profile-a", "Desk", "https://pmgr", "desk"), "issue:42")
        } finally {
            assertEquals(null, starter.lastCommand)
        }
    }
}
