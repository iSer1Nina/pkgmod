package com.pkgmod.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "安装成功"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "请在系统弹窗中确认安装"
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                "安装失败（code=$status）：$message"
            }
        }

        // 存下来，回到主界面时还能看到完整信息
        context.getSharedPreferences("pkgmod", Context.MODE_PRIVATE)
            .edit()
            .putString("last_install_result", text)
            .apply()

        Toast.makeText(context, text, Toast.LENGTH_LONG).show()

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
        }
    }
}
