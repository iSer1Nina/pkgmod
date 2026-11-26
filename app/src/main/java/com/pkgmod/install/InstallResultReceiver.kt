package com.pkgmod.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            save(context, "安装成功")
            Toast.makeText(context, "安装成功", Toast.LENGTH_LONG).show()
            return
        }

        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val text = "会话安装失败（code=$status）：$message"

        // 会话失败时，用已导出到「下载」目录的 APK 拉起系统安装器，等于手动点安装
        val uriText = intent.getStringExtra("apk_uri")
        val launched = if (!uriText.isNullOrEmpty()) {
            runCatching {
                ApkInstaller.openWithSystemInstaller(context, Uri.parse(uriText))
                true
            }.getOrDefault(false)
        } else {
            false
        }

        val finalText = if (launched) "$text → 已改用系统安装器，请在弹出的界面点安装" else text
        save(context, finalText)
        Toast.makeText(context, finalText, Toast.LENGTH_LONG).show()
    }

    private fun save(context: Context, text: String) {
        context.getSharedPreferences("pkgmod", Context.MODE_PRIVATE)
            .edit()
            .putString("last_install_result", text)
            .apply()
    }
}
