package com.termux.workflow

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppRelease(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val available: Boolean,
    val downloadUrl: HttpUrl?,
    val sizeBytes: Long,
    val sha256: String,
)

class AppUpdateClient(
    baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val root = baseUrl.trimEnd('/').toHttpUrl()
    @Volatile private var activeCall: Call? = null

    fun cancel() { activeCall?.cancel() }

    suspend fun check(applicationId: String, currentVersion: Long, abi: String): AppRelease = withContext(Dispatchers.IO) {
        val url = root.newBuilder().addPathSegments("mobile/app-update")
            .addQueryParameter("application_id", applicationId)
            .addQueryParameter("current_version_code", currentVersion.toString())
            .addQueryParameter("abi", abi).build()
        val call = client.newCall(request(url))
        activeCall = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "检查更新失败（HTTP ${response.code}）" }
                val json = JSONObject(requireNotNull(response.body).string())
                require(json.optString("application_id") == applicationId) { "更新服务未提供此应用的安装包，请更新 PMGR 服务" }
                val version = json.getLong("latest_version_code")
                val size = json.getLong("size_bytes")
                val hash = json.getString("sha256").lowercase()
                require(version > 0 && size > 0 && hash.matches(Regex("[0-9a-f]{64}"))) { "更新信息不完整" }
                val available = json.getBoolean("available") && version > currentVersion
                val downloadUrl = if (available) {
                    requireNotNull(root.resolve(json.getString("download_url"))).also { requireSameOrigin(it) }
                } else null
                AppRelease(applicationId, version, json.getString("latest_version_name"), available, downloadUrl, size, hash)
            }
        } finally {
            activeCall = null
        }
    }

    suspend fun download(release: AppRelease, destination: File, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val url = requireNotNull(release.downloadUrl) { "没有可下载的更新" }
        requireSameOrigin(url)
        val partial = File(destination.parentFile, "${destination.name}.part")
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建下载目录" }
        val call = client.newCall(request(url))
        activeCall = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                val body = requireNotNull(response.body) { "下载内容为空" }
                val length = body.contentLength()
                require(length < 0 || length == release.sizeBytes) { "安装包已变更，请重新检查更新" }
                val hash = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                onProgress(0, release.sizeBytes)
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            downloaded += count
                            require(downloaded <= release.sizeBytes) { "安装包大小不匹配" }
                            output.write(buffer, 0, count)
                            hash.update(buffer, 0, count)
                            onProgress(downloaded, release.sizeBytes)
                        }
                    }
                }
                require(downloaded == release.sizeBytes) { "下载未完成，请重试" }
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                require(digest == release.sha256) { "安装包校验失败，请重新下载" }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(destination)) { "无法保存安装包" }
                destination
            }
        } finally {
            activeCall = null
            partial.delete()
        }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()

    private fun requireSameOrigin(url: HttpUrl) {
        require(url.scheme == root.scheme && url.host == root.host && url.port == root.port && url.username.isEmpty() && url.password.isEmpty()) {
            "下载地址与当前主机不一致"
        }
    }
}
