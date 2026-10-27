package com.termux.workflow

import android.app.Application
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdatePhase { Idle, Checking, Available, Downloading, Verifying, Ready }

data class AppUpdateState(
    val currentVersion: String = "",
    val hostName: String = "",
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: AppRelease? = null,
    val downloaded: Long = 0,
    val apk: File? = null,
    val autoInstallPending: Boolean = false,
    val message: String = "",
    val error: String? = null,
) {
    val busy get() = phase in listOf(UpdatePhase.Checking, UpdatePhase.Downloading, UpdatePhase.Verifying)
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var configured = false
    private var client: AppUpdateClient? = null
    private var currentVersion = 0L
    private var job: Job? = null

    fun configure(profileId: String?) {
        if (configured) return
        configured = true
        try {
            val context = getApplication<Application>()
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            currentVersion = PackageInfoCompat.getLongVersionCode(info)
            mutableState.update { it.copy(currentVersion = "${info.versionName} ($currentVersion)") }
            val store = HostProfileStore(context)
            val profiles = store.profiles()
            val selectedId = profileId ?: store.selectedProfileId()
            val profile = profiles.find { it.id == selectedId } ?: profiles.firstOrNull()
            requireNotNull(profile) { "请先在工作台配置 PMGR 主机" }
            client = AppUpdateClient(profile.pmgrUrl, store.token(profile.id))
            mutableState.update { it.copy(hostName = profile.name) }
        } catch (error: Exception) {
            report(error.message ?: "无法读取更新配置")
        }
    }

    fun check() {
        val api = client ?: return
        if (state.value.busy) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(phase = UpdatePhase.Checking, release = null, apk = null, error = null, message = "", autoInstallPending = false) }
            try {
                val release = api.check(getApplication<Application>().packageName, currentVersion, Build.SUPPORTED_ABIS.firstOrNull() ?: "universal")
                mutableState.update {
                    it.copy(release = release, phase = if (release.available) UpdatePhase.Available else UpdatePhase.Idle,
                        message = if (release.available) "发现新版本" else "当前已是最新版本")
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(phase = UpdatePhase.Idle, error = error.message ?: "检查更新失败") }
            }
        }
    }

    fun download() {
        val api = client ?: return
        val release = state.value.release?.takeIf { it.available } ?: return
        if (state.value.busy) return
        job = viewModelScope.launch {
            val context = getApplication<Application>()
            val destination = File(context.cacheDir, "updates/update.apk")
            mutableState.update { it.copy(phase = UpdatePhase.Downloading, downloaded = 0, apk = null, error = null, message = "", autoInstallPending = false) }
            try {
                val apk = api.download(release, destination) { bytes, _ ->
                    mutableState.update { it.copy(downloaded = bytes) }
                }
                mutableState.update { it.copy(phase = UpdatePhase.Verifying) }
                withContext(Dispatchers.IO) { AppUpdateInstaller.validate(context, apk, release) }
                mutableState.update { it.copy(phase = UpdatePhase.Ready, apk = apk, autoInstallPending = true, message = "下载完成，准备安装") }
            } catch (error: Exception) {
                destination.delete()
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(phase = UpdatePhase.Available, error = error.message ?: "下载失败，请重试") }
            }
        }
    }

    fun cancel() {
        val cancelled = job
        cancelled?.cancel()
        client?.cancel()
        viewModelScope.launch {
            cancelled?.join()
            mutableState.update { it.copy(phase = if (it.release?.available == true) UpdatePhase.Available else UpdatePhase.Idle, message = "已取消", autoInstallPending = false) }
        }
    }

    fun consumeAutoInstall() { mutableState.update { it.copy(autoInstallPending = false) } }
    fun report(message: String) { mutableState.update { it.copy(message = message) } }

    override fun onCleared() {
        client?.cancel()
        super.onCleared()
    }
}
