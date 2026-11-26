package com.pkgmod.zip

import com.pkgmod.core.Bytes
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}

/**
 * 重打包 APK：替换指定条目、丢弃旧签名文件、保持必要对齐。
 *
 * - resources.arsc 强制不压缩并对齐 4 字节（Android 11+ 的要求）
 * - 原本不压缩的 .so 保持不压缩，按 16KB 页对齐
 */
class ApkRepacker(private val source: File) {

    companion object {
        private const val PAGE_ALIGN = 16384
        private const val ARSC_ALIGN = 4
        private const val STORED = 0

        private fun isSignatureFile(name: String): Boolean {
            if (!name.startsWith("META-INF/")) return false
            val upper = name.uppercase()
            return upper.endsWith(".SF") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".EC") ||
                upper == "META-INF/MANIFEST.MF"
        }
    }

    fun repack(target: File, replacements: Map<String, ByteArray>) {
        target.parentFile?.mkdirs()

        ZipFile(source).use { zip ->
            BufferedOutputStream(FileOutputStream(target)).use { fileOut ->
                val counting = CountingOutputStream(fileOut)
                ZipOutputStream(counting).use { zos ->
                    val seen = HashSet<String>()

                    for (entry in java.util.Collections.list(zip.entries())) {
                        val name = entry.name
                        if (isSignatureFile(name)) continue
                        if (!seen.add(name)) continue

                        val replacement = replacements[name]
                        if (replacement != null) {
                            writeBytes(zos, counting, name, replacement, name == "resources.arsc")
                        } else {
                            writeCopy(zos, counting, zip, entry)
                        }
                    }

                    for ((name, bytes) in replacements) {
                        if (seen.add(name)) {
                            writeBytes(zos, counting, name, bytes, false)
                        }
                    }
                }
            }
        }
    }

    private fun writeBytes(
        zos: ZipOutputStream,
        counting: CountingOutputStream,
        name: String,
        bytes: ByteArray,
        stored: Boolean
    ) {
        val align = when {
            stored && name == "resources.arsc" -> ARSC_ALIGN
            stored && name.endsWith(".so") -> PAGE_ALIGN
            else -> 1
        }

        val entry = ZipEntry(name)
        entry.setExtra(paddingBytes(counting.count, name, align))

        if (stored) {
            val crc = CRC32()
            crc.update(bytes)
            entry.method = STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun writeCopy(
        zos: ZipOutputStream,
        counting: CountingOutputStream,
        zip: ZipFile,
        src: ZipEntry
    ) {
        val name = src.name
        val stored = src.method == STORED
        val align = if (stored && name.endsWith(".so")) PAGE_ALIGN else 1

        val entry = ZipEntry(name)
        entry.setExtra(paddingBytes(counting.count, name, align))
        entry.method = src.method

        if (stored) {
            entry.size = src.size
            entry.compressedSize = src.compressedSize
            entry.crc = src.crc
        }

        zos.putNextEntry(entry)
        zip.getInputStream(src).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                zos.write(buffer, 0, read)
            }
        }
        zos.closeEntry()
    }

    private fun paddingBytes(position: Long, name: String, align: Int): ByteArray {
        if (align <= 1) return ByteArray(0)

        val nameLength = name.toByteArray(Charsets.UTF_8).size
        val base = position + 30 + nameLength
        var pad = ((align - (base % align)) % align).toInt()
        if (pad < 4) pad += align

        val extra = ByteArray(pad)
        Bytes.putU16(extra, 0, 0)
        Bytes.putU16(extra, 2, pad - 4)
        return extra
    }
}
