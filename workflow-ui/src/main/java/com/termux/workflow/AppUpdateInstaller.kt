package com.termux.workflow

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

object AppUpdateInstaller {
    @Suppress("DEPRECATION")
    fun validate(context: Context, apk: File, release: AppRelease) {
        val manager = context.packageManager
        val archive = requireNotNull(manager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)) { "无法读取 APK" }
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        require(archive.packageName == context.packageName && archive.packageName == release.applicationId) { "安装包不属于此应用" }
        require(PackageInfoCompat.getLongVersionCode(archive) == release.versionCode &&
            release.versionCode > PackageInfoCompat.getLongVersionCode(installed)) { "安装包版本不正确" }
        val signatures = archive.signatures?.toSet().orEmpty()
        require(signatures.isNotEmpty() && signatures == installed.signatures?.toSet()) { "安装包签名不匹配，无法覆盖安装" }
    }

    fun intent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("APK", uri) }
    }
}
