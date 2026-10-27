package com.termux.workflow

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateClientTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var api: AppUpdateClient
    private val apk = ByteArray(150_000) { (it % 251).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = AppUpdateClient(server.url("/").toString(), "test-token")
    }

    @After fun tearDown() { server.shutdown() }

    private fun releaseJson(url: String = "/mobile/app-update/apk?application_id=com.termux&abi=arm64-v8a", packageName: String = "com.termux", available: Boolean = true) =
        """{"application_id":"$packageName","latest_version_code":120,"latest_version_name":"0.118.2","available":$available,"download_url":"$url","size_bytes":${apk.size},"sha256":"$hash"}"""

    @Test fun checkAndDownloadUsePackageArchitectureAuthenticationAndReportProgress() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson()))
        val release = api.check("com.termux", 119, "arm64-v8a")
        val check = server.takeRequest()
        assertEquals("/mobile/app-update?application_id=com.termux&current_version_code=119&abi=arm64-v8a", check.path)
        assertEquals("Bearer test-token", check.getHeader("Authorization"))
        assertTrue(release.available)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val progress = mutableListOf<Long>()
        val target = File(folder.root, "update.apk")
        api.download(release, target) { bytes, total ->
            assertEquals(apk.size.toLong(), total)
            progress.add(bytes)
        }
        assertArrayEquals(apk, target.readBytes())
        assertTrue(progress.size > 2)
        assertEquals(0L, progress.first())
        assertEquals(apk.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertFalse(File(folder.root, "update.apk.part").exists())
        val download = server.takeRequest()
        assertEquals("/mobile/app-update/apk?application_id=com.termux&abi=arm64-v8a", download.path)
        assertEquals("Bearer test-token", download.getHeader("Authorization"))
    }

    @Test fun currentVersionDoesNotOfferDowngrade() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson()))
        val release = api.check("com.termux", 121, "arm64-v8a")
        assertFalse(release.available)
        assertNull(release.downloadUrl)
    }

    @Test fun wrongApplicationAndForeignDownloadOriginAreRejected() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson(packageName = "com.hadesflowy.android")))
        assertTrue(runCatching { api.check("com.termux", 119, "arm64-v8a") }.isFailure)
        server.enqueue(MockResponse().setBody(releaseJson(url = "https://other.example/update.apk")))
        assertTrue(runCatching { api.check("com.termux", 119, "arm64-v8a") }.isFailure)
    }

    @Test fun badHashAndTruncatedDownloadLeaveNoInstallableFile() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson()))
        val release = api.check("com.termux", 119, "arm64-v8a")
        val target = File(folder.root, "update.apk")
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(apk.size))))
        assertTrue(runCatching { api.download(release, target) { _, _ -> } }.isFailure)
        assertFalse(target.exists())
        assertFalse(File(folder.root, "update.apk.part").exists())
        server.enqueue(MockResponse().setBody("truncated"))
        assertTrue(runCatching { api.download(release, target) { _, _ -> } }.isFailure)
        assertFalse(target.exists())
    }

    @Test fun authenticationErrorsAndRedirectsDoNotDownload() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { api.check("com.termux", 119, "arm64-v8a") }.isFailure)
        server.enqueue(MockResponse().setBody(releaseJson()))
        val release = api.check("com.termux", 119, "arm64-v8a")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/update.apk"))
        assertTrue(runCatching { api.download(release, File(folder.root, "update.apk")) { _, _ -> } }.isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun cancelledDownloadRemovesPartialFileAndCanRetry() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson()))
        val release = api.check("com.termux", 119, "arm64-v8a")
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val target = File(folder.root, "update.apk")
        assertTrue(runCatching { api.download(release, target) { _, _ -> api.cancel() } }.isFailure)
        assertFalse(target.exists())
        assertFalse(File(folder.root, "update.apk.part").exists())
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        api.download(release, target) { _, _ -> }
        assertArrayEquals(apk, target.readBytes())
    }
}
