package com.pkgmod.core

import android.content.Context
import android.content.pm.ApplicationInfo
import com.pkgmod.arsc.ArscEditor
import com.pkgmod.axml.AxmlEditor
import com.pkgmod.dex.DexEditor
import com.pkgmod.install.ApkInstaller
import com.pkgmod.sign.ApkSigning
import com.pkgmod.zip.ApkRepacker
import java.io.File
import java.util.zip.ZipFile

/**
 * 改包流程编排：复制 → 改清单/资源/dex → 重打包 → 重签名 → 校验 → 安装。
 */
class PackageRenamer(private val context: Context) {

    data class RenameResult(
        val success: Boolean,
        val message: String,
        val outputApk: File? = null
    )

    fun rename(
        app: ApplicationInfo,
        oldPackage: String,
        newPackage: String,
        scope: Set<String>,
        onProgress: (String) -> Unit
    ): RenameResult {
        val source = File(app.sourceDir)
        if (!source.exists() || !source.canRead()) {
            return RenameResult(false, "读取原 APK 失败（${source.absolutePath}）")
        }

        val splits = app.splitSourceDirs?.filterNotNull().orEmpty()
        if (splits.isNotEmpty()) {
            return RenameResult(false, "该应用是分体 APK（${splits.size} 个 split），暂不支持")
        }

        return renameFromFile(source, oldPackage, newPackage, scope, onProgress)
    }

    /** 直接从 APK 文件改包，不要求应用已经安装。 */
    fun renameFromFile(
        source: File,
        oldPackage: String,
        newPackage: String,
        scope: Set<String>,
        onProgress: (String) -> Unit
    ): RenameResult {
        if (!source.exists() || !source.canRead()) {
            return RenameResult(false, "读取 APK 失败（${source.absolutePath}）")
        }

        cleanupStaleWorkDirs()

        val workDir = File(context.cacheDir, "pkgmod-${System.currentTimeMillis()}")
        workDir.mkdirs()
        var exportedApk: File? = null

        return try {
            // 直接以原 APK 为输入流式重打包，避免为 3GB 级游戏再复制一份
            onProgress("读取 APK…")
            val replacements = computeReplacements(source, oldPackage, newPackage, scope, onProgress)

            onProgress("重新打包…")
            val unsignedApk = File(workDir, "unsigned.apk")
            ApkRepacker(source).repack(unsignedApk, replacements)

            onProgress("重新签名…")
            val signedApk = File(workDir, "signed.apk")
            context.assets.open("signing.p12").use { stream ->
                ApkSigning.sign(unsignedApk, signedApk, stream, "pkgmod", KEYSTORE_PASSWORD, 26)
            }
            unsignedApk.delete()

            onProgress("校验产物…")
            val actualPackage = readPackageName(signedApk)
            if (actualPackage != newPackage) {
                return RenameResult(false, "校验失败，产物包名为 $actualPackage")
            }

            // 小包额外导出一份到外部目录，安装失败时可以手动点安装；大包不导出，省空间
            if (signedApk.length() <= EXPORT_LIMIT_BYTES) {
                exportedApk = exportApk(signedApk, newPackage)
            }

            onProgress("提交安装…")
            ApkInstaller.install(context, signedApk, newPackage)

            // 安装会话已经复制过内容，这里可以清理缓存
            runCatching { workDir.deleteRecursively() }
            RenameResult(true, "已提交安装：$newPackage", signedApk)
        } catch (t: Throwable) {
            val suffix = exportedApk?.let { "（产物已导出：${it.absolutePath}）" } ?: ""
            RenameResult(false, (t.message ?: t.javaClass.simpleName) + suffix)
        }
    }

    private fun exportApk(signed: File, newPackage: String): File? = runCatching {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "pkgmod").apply { mkdirs() }
        val target = File(dir, "$newPackage.apk")
        signed.copyTo(target, overwrite = true)
        target
    }.getOrNull()

    private fun computeReplacements(
        apk: File,
        oldPackage: String,
        newPackage: String,
        scope: Set<String>,
        onProgress: (String) -> Unit
    ): Map<String, ByteArray> {
        val replacements = HashMap<String, ByteArray>()

        ZipFile(apk).use { zip ->
            val entries = java.util.Collections.list(zip.entries())

            if ("manifest" in scope) {
                onProgress("修改清单包名…")
                val bytes = readEntry(zip, "AndroidManifest.xml")
                    ?: throw IllegalStateException("APK 缺少 AndroidManifest.xml")
                replacements["AndroidManifest.xml"] = AxmlEditor(bytes).renamePackage(oldPackage, newPackage)
            }

            if ("arsc" in scope) {
                onProgress("修改资源字符串…")
                val bytes = readEntry(zip, "resources.arsc")
                if (bytes != null) {
                    replacements["resources.arsc"] =
                        ArscEditor(bytes).replacePackageStrings(oldPackage, newPackage)
                }
            }

            if ("dex" in scope) {
                onProgress("修改代码字符串…")
                for (entry in entries) {
                    val name = entry.name
                    if (!name.startsWith("classes") || !name.endsWith(".dex")) continue
                    val bytes = readEntry(zip, name) ?: continue
                    replacements[name] = DexEditor(bytes).replacePackageStrings(oldPackage, newPackage)
                }
            }
        }
        return replacements
    }

    private fun readPackageName(apk: File): String? {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        return AxmlEditor(bytes).packageName()
    }

    private fun cleanupStaleWorkDirs() {
        runCatching {
            val now = System.currentTimeMillis()
            context.cacheDir.listFiles { file -> file.name.startsWith("pkgmod-") }
                ?.filter { now - it.lastModified() > 60_000 }
                ?.forEach { it.deleteRecursively() }
        }
    }

    private fun readEntry(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    companion object {
        private val KEYSTORE_PASSWORD = "pkgmod2019".toCharArray()

        /** 超过这个大小就不再额外导出到外部目录，避免几 GB 的重复占用。 */
        private const val EXPORT_LIMIT_BYTES = 512L * 1024 * 1024
    }
}
