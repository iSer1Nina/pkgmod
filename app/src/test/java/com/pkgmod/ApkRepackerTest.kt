package com.pkgmod

import com.pkgmod.zip.ApkRepacker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 重打包器测试：校验条目替换、签名文件剔除、以及 STORED 条目的对齐。
 *
 * 对齐用中央目录里的 local header 偏移来算真实数据位置，模拟 Android 安装器的检查。
 */
class ApkRepackerTest {

    @Test
    fun repackAlignsStoredEntriesAndDropsOldSignatures() {
        val workDir = File(System.getProperty("java.io.tmpdir") ?: ".", "pkgmod-repack-${System.nanoTime()}")
        workDir.mkdirs()
        try {
            val source = File(workDir, "source.apk")
            writeSourceApk(source)

            val newManifest = "new-manifest-bytes".toByteArray()
            val newArsc = ByteArray(300) { 9 }
            val target = File(workDir, "out.apk")

            ApkRepacker(source).repack(
                target,
                mapOf(
                    "AndroidManifest.xml" to newManifest,
                    "resources.arsc" to newArsc
                )
            )

            val bytes = target.readBytes()
            val entries = centralEntries(bytes)
            val byName = entries.associateBy { it.name }

            assertNull("旧签名文件应该被丢掉", byName["META-INF/CERT.RSA"])
            assertNull("旧 MANIFEST.MF 应该被丢掉", byName["META-INF/MANIFEST.MF"])

            val arsc = byName.getValue("resources.arsc")
            assertEquals("resources.arsc 必须不压缩", 0, arsc.method)
            assertEquals("resources.arsc 必须 4 字节对齐", 0L, dataOffset(bytes, arsc.localOffset) % 4)

            val so = byName.getValue("lib/arm64-v8a/libfoo.so")
            assertEquals("原本不压缩的 .so 必须继续不压缩", 0, so.method)
            assertEquals("未压缩 .so 必须页对齐", 0L, dataOffset(bytes, so.localOffset) % PAGE_ALIGN)

            ZipFile(target).use { zip ->
                assertArrayEquals(
                    newManifest,
                    zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
                )
                assertArrayEquals(
                    newArsc,
                    zip.getInputStream(zip.getEntry("resources.arsc")).use { it.readBytes() }
                )
                assertEquals(
                    "hello dex",
                    zip.getInputStream(zip.getEntry("classes.dex")).use { String(it.readBytes()) }
                )

                // 全部条目读一遍，顺带校验 CRC / 解压
                for (entry in Collections.list(zip.entries())) {
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            }

            assertTrue("产物里应该有 AndroidManifest.xml", byName.containsKey("AndroidManifest.xml"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    // ---------------- 构造源 APK ----------------

    private fun writeSourceApk(target: File) {
        ZipOutputStream(target.outputStream()).use { zos ->
            addStored(zos, "resources.arsc", ByteArray(128) { 1 })
            addDeflated(zos, "classes.dex", "hello dex".toByteArray())
            addStored(zos, "lib/arm64-v8a/libfoo.so", ByteArray(1024) { 2 })
            addDeflated(zos, "META-INF/CERT.RSA", ByteArray(16) { 3 })
            addDeflated(zos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0".toByteArray())
            addDeflated(zos, "res/layout/main.xml", ByteArray(64) { 4 })
        }
    }

    private fun addStored(zos: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32()
        crc.update(data)
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED.toInt()
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        entry.crc = crc.value
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun addDeflated(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED.toInt()
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    // ---------------- ZIP 结构解析 ----------------

    private data class CentralEntry(val name: String, val method: Int, val localOffset: Long)

    private fun centralEntries(bytes: ByteArray): List<CentralEntry> {
        val eocd = findEocd(bytes)
        val count = u16(bytes, eocd + 10)
        var pos = u32(bytes, eocd + 16).toInt()
        val result = ArrayList<CentralEntry>(count)

        repeat(count) {
            require(i32(bytes, pos) == CENTRAL_SIGNATURE) { "中央目录签名不对 @$pos" }
            val method = u16(bytes, pos + 10)
            val nameLength = u16(bytes, pos + 28)
            val extraLength = u16(bytes, pos + 30)
            val commentLength = u16(bytes, pos + 32)
            val localOffset = u32(bytes, pos + 42)
            val name = String(bytes, pos + 46, nameLength, Charsets.UTF_8)
            result.add(CentralEntry(name, method, localOffset))
            pos += 46 + nameLength + extraLength + commentLength
        }
        return result
    }

    private fun findEocd(bytes: ByteArray): Int {
        val min = maxOf(0, bytes.size - 22 - 0xFFFF)
        for (pos in bytes.size - 22 downTo min) {
            if (i32(bytes, pos) == EOCD_SIGNATURE) return pos
        }
        throw IllegalStateException("找不到 EOCD")
    }

    private fun dataOffset(bytes: ByteArray, localOffset: Long): Long {
        val pos = localOffset.toInt()
        require(i32(bytes, pos) == LOCAL_SIGNATURE) { "本地头签名不对 @$pos" }
        val nameLength = u16(bytes, pos + 26)
        val extraLength = u16(bytes, pos + 28)
        return localOffset + 30 + nameLength + extraLength
    }

    private fun u16(bytes: ByteArray, pos: Int): Int =
        (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)

    private fun i32(bytes: ByteArray, pos: Int): Int =
        (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)

    private fun u32(bytes: ByteArray, pos: Int): Long = i32(bytes, pos).toLong() and 0xFFFFFFFFL

    private companion object {
        const val PAGE_ALIGN = 16384L
        const val LOCAL_SIGNATURE = 0x04034b50
        const val CENTRAL_SIGNATURE = 0x02014b50
        const val EOCD_SIGNATURE = 0x06054b50
    }
}
