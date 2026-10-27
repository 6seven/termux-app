package com.termux.workflow

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlinx.coroutines.launch

class AppUpdateActivity : ComponentActivity() {
    private val model: AppUpdateViewModel by viewModels()
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            openInstaller()
        } else {
            model.report("尚未允许安装应用，点击“安装更新”可重新授权")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.configure(intent.getStringExtra("profile_id"))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { state ->
                    if (state.autoInstallPending) {
                        model.consumeAutoInstall()
                        openInstaller()
                    }
                }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6EE7B7), background = Color(0xFF090D12))) {
                AppUpdateScreen(state, model::check, model::download, model::cancel, ::openInstaller, ::finish)
            }
        }
    }

    private fun openInstaller() {
        val apk = model.state.value.apk ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                model.report("请允许此应用安装 APK，返回后将继续安装")
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                startActivity(AppUpdateInstaller.intent(this, apk))
                model.report("已打开系统安装界面")
            }
        } catch (error: Exception) {
            model.report("无法打开安装界面：${error.message.orEmpty()}")
        }
    }
}

@Composable
internal fun AppUpdateScreen(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("应用更新", style = MaterialTheme.typography.headlineMedium)
            Text("当前版本：${state.currentVersion}")
            if (state.hostName.isNotBlank()) Text("更新来源：${state.hostName}")
            state.release?.let { release ->
                Text("最新版本：${release.versionName} (${release.versionCode})")
                Text("安装包大小：${formatUpdateBytes(release.sizeBytes)}")
            }
            when (state.phase) {
                UpdatePhase.Checking -> {
                    Text("正在检查更新…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                UpdatePhase.Downloading -> {
                    val total = state.release?.sizeBytes ?: 0L
                    val progress = if (total > 0) (state.downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
                    Text("正在下载 ${(progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${formatUpdateBytes(state.downloaded)} / ${formatUpdateBytes(total)}")
                }
                UpdatePhase.Verifying -> {
                    Text("下载完成，正在校验安装包…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> Unit
            }
            if (state.message.isNotBlank()) Text(state.message)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.busy -> Button(onClick = onCancel) { Text("取消") }
                    state.phase == UpdatePhase.Ready -> Button(onClick = onInstall) { Text("安装更新") }
                    state.release?.available == true -> Button(onClick = onDownload) { Text("下载并安装") }
                    else -> Button(onClick = onCheck, enabled = state.hostName.isNotBlank()) { Text("检查更新") }
                }
                if (!state.busy && state.release?.available == true) TextButton(onClick = onCheck) { Text("重新检查") }
            }
            TextButton(onClick = onClose) { Text("返回") }
        }
    }
}

private fun formatUpdateBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
