package com.pkgmod.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.pkgmod.R
import com.pkgmod.axml.AxmlEditor
import com.pkgmod.core.PackageRenamer
import com.pkgmod.install.ApkInstaller
import com.pkgmod.rule.RuleEngine
import com.pkgmod.rule.RulesParser
import java.io.File
import java.util.zip.ZipFile

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_IMPORT_RULES = 1001
        private const val REQUEST_RULE_MANAGER = 1002
        private const val REQUEST_PICK_APK = 1003
    }

    private var engine: RuleEngine? = null
    private lateinit var adapter: AppListAdapter
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        adapter = AppListAdapter(this)

        findViewById<ListView>(R.id.appList).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onAppClicked(position) }
        }

        findViewById<Button>(R.id.btnReload).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            startActivityForResult(Intent(this, RuleListActivity::class.java), REQUEST_RULE_MANAGER)
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener { importRules() }
        findViewById<Button>(R.id.btnPickApk).setOnClickListener { pickApk() }

        loadRules()
        scan()
        showPathsDialog()
    }

    /** 每次打开都要看的“下载路径”说明。 */
    private fun showPathsDialog() {
        val body = runCatching {
            resources.openRawResource(R.raw.paths).bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val view = layoutInflater.inflate(R.layout.dialog_paths, null)
        view.findViewById<TextView>(R.id.pathsText).text = body

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.paths_title)
            .setView(view)
            .setPositiveButton(R.string.got_it, null)
            .create()

        dialog.setOnShowListener { blur(dialog) }
        dialog.show()
    }

    private fun blur(dialog: AlertDialog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val window = dialog.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.setBackgroundBlurRadius(64)
        window.setDimAmount(0.35f)
    }

    private fun loadRules() {
        val text = runCatching { loadRulesText() }.getOrNull()
        if (text == null) {
            status.text = getString(R.string.no_rules)
            return
        }
        engine = runCatching { RuleEngine(RulesParser.parse(text)) }.getOrNull()
        if (engine == null) {
            status.text = "规则解析失败"
        }
    }

    private fun scan() {
        val current = engine ?: return
        status.text = getString(R.string.loading)

        val pm = packageManager
        Thread {
            val rows = ArrayList<AppRow>()
            val applications = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())

            for (info in applications) {
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                val match = current.targetOf(info.packageName)
                rows.add(
                    AppRow(
                        label = label,
                        pkg = info.packageName,
                        target = match?.target,
                        ruleName = match?.rule?.name,
                        scope = match?.rule?.scope ?: emptySet(),
                        info = info,
                        targetSdk = info.targetSdkVersion
                    )
                )
            }

            rows.sortWith(compareBy({ it.target == null }, { it.label.lowercase() }))
            val hit = rows.count { it.target != null }

            runOnUiThread {
                adapter.submit(rows)
                status.text = "已安装 ${rows.size} 个应用，命中规则 $hit 个，点击可改包"
            }
        }.start()
    }

    private fun onAppClicked(position: Int) {
        val row = adapter.rowAt(position)
        val target = row.target
        if (target == null) {
            Toast.makeText(this, "未命中规则", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "正在处理，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        val warning = if (row.targetSdk in 1..22) {
            "\n\n注意：目标应用 targetSdk=${row.targetSdk}，Android 14+ 会拒绝安装这类应用，本次很可能失败。"
        } else {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle("确认改包")
            .setMessage("${row.pkg}\n\u2192 $target\n\n会重新签名并提交安装，与原版共存。$warning")
            .setPositiveButton("开始") { _, _ -> startRename(row, target) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startRename(row: AppRow, target: String) {
        val info = row.info ?: return
        val scope = row.scope.ifEmpty { setOf("manifest", "arsc", "dex") }
        runRename { renamer, progress -> renamer.rename(info, row.pkg, target, scope, progress) }
    }

    private fun startRenameFromFile(source: File, oldPackage: String, target: String, scope: Set<String>) {
        val effectiveScope = scope.ifEmpty { setOf("manifest", "arsc", "dex") }
        runRename { renamer, progress ->
            renamer.renameFromFile(source, oldPackage, target, effectiveScope, progress)
        }
    }

    private fun runRename(
        action: (PackageRenamer, (String) -> Unit) -> PackageRenamer.RenameResult
    ) {
        if (!ApkInstaller.canInstall(this)) {
            Toast.makeText(this, "请先允许安装未知应用", Toast.LENGTH_LONG).show()
            ApkInstaller.openInstallPermission(this)
            return
        }
        if (busy) {
            Toast.makeText(this, "正在处理，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        busy = true
        status.text = "准备中…"
        val activity = this

        Thread {
            val progress: (String) -> Unit = { message -> runOnUiThread { status.text = message } }
            val result = action(PackageRenamer(activity), progress)
            runOnUiThread {
                busy = false
                status.text = result.message
                Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                maybeOfferManualInstall()
            }
        }.start()
    }

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    private fun handlePickedApk(uri: Uri) {
        val current = engine ?: return
        status.text = "读取 APK…"
        val activity = this

        Thread {
            val loaded = runCatching {
                val dir = File(cacheDir, "picked").apply { mkdirs() }
                val file = File(dir, "picked-${System.currentTimeMillis()}.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")

                val packageName = readPackageName(file)
                    ?: throw IllegalStateException("读不到包名（可能是 split APK）")
                packageName to file
            }

            runOnUiThread {
                val pair = loaded.getOrNull()
                if (pair == null) {
                    status.text = "读取失败"
                    Toast.makeText(
                        activity,
                        "读取失败：${loaded.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                val (packageName, file) = pair
                val match = current.targetOf(packageName)
                if (match == null) {
                    status.text = "$packageName 未命中规则"
                    Toast.makeText(activity, "该 APK 未命中规则", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                AlertDialog.Builder(activity)
                    .setTitle("确认改包")
                    .setMessage("$packageName\n\u2192 ${match.target}\n\n来源：${file.name}")
                    .setPositiveButton("开始") { _, _ ->
                        startRenameFromFile(file, packageName, match.target, match.rule.scope)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun readPackageName(apk: File): String? {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        return AxmlEditor(bytes).packageName()
    }

    override fun onResume() {
        super.onResume()
        val lastResult = getSharedPreferences("pkgmod", MODE_PRIVATE)
            .getString("last_install_result", null)
        if (!lastResult.isNullOrEmpty()) {
            status.text = lastResult
        }
    }

    private fun maybeOfferManualInstall() {
        val uri = getSharedPreferences("pkgmod", MODE_PRIVATE)
            .getString("last_apk_uri", null)
        if (uri.isNullOrEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("安装产物")
            .setMessage("产物已导出到「下载/pkgmod」。\n\n如果系统没有自动弹出安装界面，点下面的按钮用系统安装器打开（等同于在文件管理器里点 APK；大包会先复制几分钟）。")
            .setPositiveButton("用系统安装器打开") { _, _ ->
                runCatching { ApkInstaller.openWithSystemInstaller(this, Uri.parse(uri)) }
            }
            .setNegativeButton("已安装，不用了", null)
            .create()
            .apply {
                setOnShowListener { blur(this) }
                show()
            }
    }

    private fun loadRulesText(): String {
        val custom = File(filesDir, "rules.json")
        if (custom.exists()) return custom.readText()
        return assets.open("default_rules.json").bufferedReader().use { it.readText() }
    }

    private fun importRules() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT_RULES)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_APK) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { handlePickedApk(it) }
            }
            return
        }

        if (requestCode == REQUEST_RULE_MANAGER) {
            if (resultCode == RESULT_OK) {
                loadRules()
                scan()
            }
            return
        }

        if (requestCode != REQUEST_IMPORT_RULES || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: run {
            Toast.makeText(this, "无法读取规则文件", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val parsed = RulesParser.parse(text)
            File(filesDir, "rules.json").writeText(text)
            engine = RuleEngine(parsed)
            Toast.makeText(this, "已导入 ${parsed.rules.size} 条规则", Toast.LENGTH_SHORT).show()
            scan()
        }.onFailure {
            Toast.makeText(this, "规则导入失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
