package com.termux.workflow

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WorkflowApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WorkflowApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = WorkflowApiClient(server.url("/").toString(), "secret")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun issuesUseAssignedOpenMobileContractAndBearerToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"count":1,"items":[{"redmine_id":"r","issue_id":"42","subject":"Crash"}]}"""))

        val issues = api.listIssues()
        val request = server.takeRequest()

        assertEquals("/mobile/issues?view=assigned-open", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("42", issues.single().issueId)
    }

    @Test
    fun workspacesIncludeServerDirectory() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"count":1,"items":[{"id":"workspace:w1","workspace_id":"w1","name":"RM 1321","issue_id":"1321","directory":"/Users/dev/redmine/rm-1321/main"}]}""",
            ),
        )

        val target = api.listWorkspaces(includeArchived = false).single()
        val request = server.takeRequest()

        assertEquals("/mobile/workspaces?tracker_only=false&include_archived=false", request.path)
        assertEquals("/Users/dev/redmine/rm-1321/main", target.directory)
    }

    @Test
    fun imageUsesAuthenticatedPathContractAndReturnsBinaryContent() = runTest {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(bytes)),
        )

        val image = api.image(" \n /projects/game/\r\n  scene one.png \n ")
        val request = server.takeRequest()

        assertEquals(
            "/mobile/images?path=%2Fprojects%2Fgame%2Fscene%20one.png",
            request.path,
        )
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("image/*", request.getHeader("Accept"))
        assertEquals("/projects/game/scene one.png", image.path)
        assertEquals("image/png", image.contentType)
        assertArrayEquals(bytes, image.bytes)
    }

    @Test
    fun imageRejectsNonImageResponses() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("not an image"),
        )

        val error = runCatching { api.image("projects/readme.txt") }.exceptionOrNull()

        assertEquals(415, (error as WorkflowApiException).statusCode)
    }

    @Test
    fun imageDirectoryUsesWorkspaceBrowserContract() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"base_path":"/Users/dev/redmine/rm-1321/main","path":"assets/wave-four","parent_path":"assets","count":2,"truncated":false,"items":[{"name":"preparation","path":"assets/wave-four/preparation","kind":"directory","media_type":null,"size_bytes":null},{"name":"composition.png","path":"assets/wave-four/composition.png","kind":"image","media_type":"image/png","size_bytes":3456}]}""",
            ),
        )

        val directory = api.imageDirectory(
            "/Users/dev/redmine/rm-1321/main",
            "assets/wave-four",
        )
        val request = server.takeRequest()

        assertEquals(
            "/mobile/image-browser?base_path=%2FUsers%2Fdev%2Fredmine%2Frm-1321%2Fmain&path=assets%2Fwave-four",
            request.path,
        )
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("assets", directory.parentPath)
        assertEquals(true, directory.entries.first().isDirectory)
        assertEquals("image/png", directory.entries.last().contentType)
        assertEquals(3456, directory.entries.last().sizeBytes)
    }

    @Test
    fun activationUsesTargetContractAndReturnsTmuxSession() = runTest {
        server.enqueue(MockResponse().setBody("""{"target_id":"project:1","ssh_host_alias":"dev","tmux_session":"workflow"}"""))

        val result = api.activateTarget("project:1")
        val request = server.takeRequest()

        assertEquals("/mobile/targets/project%3A1/activate", request.path)
        assertEquals("POST", request.method)
        assertEquals("workflow", result.tmuxSession)
    }

    @Test
    fun trackerActivationAndAcknowledgementUseTmuxCoordinates() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tasks":[{"session_id":"$0","session":"dev","window_id":"@7","window":"project-manager","pane":"%12","status":"completed","summary":"Review result"}]}""",
            ),
        )
        val item = api.trackerState().items.single()
        server.enqueue(MockResponse().setBody("""{"target_id":"$0::@7::%12","tmux_session":"dev"}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val result = api.activateTrackerItem(item)
        api.acknowledgeTrackerItem(item)
        server.takeRequest()
        val activation = server.takeRequest()
        val acknowledgement = server.takeRequest()

        assertEquals("$0::@7::%12", item.id)
        assertEquals("dev", result.tmuxSession)
        assertEquals("/mobile/tracker-state/activate", activation.path)
        assertEquals(
            "{\"session_id\":\"$0\",\"window_id\":\"@7\",\"pane\":\"%12\"}",
            activation.body.readUtf8(),
        )
        assertEquals("/mobile/tracker-state/acknowledge", acknowledgement.path)
        assertEquals(
            "{\"session_id\":\"$0\",\"window_id\":\"@7\",\"pane\":\"%12\"}",
            acknowledgement.body.readUtf8(),
        )
    }

    @Test
    fun trackerStateAndUsageRefreshUseAgreedContracts() = runTest {
        server.enqueue(MockResponse().setBody("""{"accepted":true}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"tasks":[{"session_id":"$0","session":"dev","window_id":"@7","window":"project-manager","pane":"%12","status":"completed","summary":"Approve command","event_type":"permission","attention_reason":"waiting"}],"usage":{"fetched_at":"2026-07-28T08:00:00Z","weekly_remaining":72.5,"reset_credit_expiries":["2026-08-01T08:00:00Z"],"daily_token_total":1200000,"codex_token_total":700000,"opencode_token_total":500000,"token_cost":{"input_uncached":1000000,"output":500000,"input_cached":2000000},"daily_token_cost":{"input_uncached":100000,"output":50000,"input_cached":200000},"days":[{"day":"2026-07-28","codex":700000,"opencode":500000,"cost":{"input_uncached":100000,"output":50000,"input_cached":200000}}],"opencode_projects":[{"name":"project-manager","path":"/repo/project-manager","total":9000000,"daily":4000000}],"codex_limits":[{"label":"Five-hour","remaining_percent":80,"used_percent":20,"reset_at":"2026-07-28T12:00:00Z"},{"label":"7d","remaining_percent":72.5,"used_percent":27.5,"reset_at":"2026-08-03T08:00:00Z"}]}}""",
            ),
        )

        val tracker = api.refreshUsage()

        assertEquals("POST /mobile/tracker-state/usage-refresh", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("GET /mobile/tracker-state", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals(72.5, tracker.usage.metrics.first { it.label == "Weekly remaining" }.used, 0.0)
        val dailyTokens = tracker.usage.metrics.first { it.label == "Daily tokens" }
        assertEquals(1200000.0, dailyTokens.used, 0.0)
        assertEquals("1.2M", dailyTokens.displayValue)
        assertEquals(20.0, tracker.usage.metrics.first { it.label == "Five-hour" }.used, 0.0)
        assertEquals("2026-07-28T12:00:00Z", tracker.usage.metrics.first { it.label == "Five-hour" }.resetAt)
        assertEquals("2026-08-03T08:00:00Z", tracker.usage.metrics.first { it.label == "Weekly remaining" }.resetAt)
        assertEquals(listOf("2026-08-01T08:00:00Z"), tracker.usage.resetCreditExpiries)
        assertEquals(1000000, tracker.usage.tokenCost.inputUncached)
        assertEquals("6.05", tracker.usage.tokenCost.cnyDisplay)
        assertEquals(50000, tracker.usage.dailyTokenCost.output)
        assertEquals(700000, tracker.usage.days.single().codex)
        assertEquals("700K", tracker.usage.days.single().codexDisplay)
        assertEquals("0.61", tracker.usage.days.single().cost.cnyDisplay)
        assertEquals(9000000, tracker.usage.projects.single().total)
        assertEquals("9M", tracker.usage.projects.single().totalDisplay)
        assertEquals("4M", tracker.usage.projects.single().dailyDisplay)
        assertEquals("2026-07-28T08:00:00Z", tracker.usage.refreshedAt)
        assertEquals(true, tracker.items.single().waiting)
        assertEquals("permission", tracker.items.single().eventType)
    }

    @Test
    fun pushRegistrationUsesDeviceAndProfileContract() = runTest {
        server.enqueue(MockResponse().setBody("""{"registered":true}"""))

        api.registerPushDevice("device-1", "profile-1", "fcm-token")
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/mobile/push-registrations", request.path)
        assertEquals(
            "{\"device_id\":\"device-1\",\"profile_id\":\"profile-1\",\"registration_token\":\"fcm-token\"}",
            request.body.readUtf8(),
        )
    }

    @Test
    fun usageRefreshPollsWhilePmgrIsRefreshing() = runTest {
        server.enqueue(MockResponse().setBody("""{"accepted":true}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"available":true,"tasks":[],"usage":{"fetched_at":"old"},"usage_refreshing":true}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"available":true,"tasks":[],"usage":{"fetched_at":"new"},"usage_refreshing":false}""",
            ),
        )

        val tracker = api.refreshUsage()

        assertEquals("new", tracker.usage.refreshedAt)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun bindingDeleteUsesIssueScopedMobileContract() = runTest {
        server.enqueue(MockResponse().setBody("{}"))

        api.deleteBinding("top white", "42")
        val request = server.takeRequest()

        assertEquals("DELETE", request.method)
        assertEquals("/mobile/issues/top%20white/42/project-binding", request.path)
    }
}
