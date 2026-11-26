package com.pkgmod

import com.pkgmod.arsc.ArscEditor
import com.pkgmod.axml.AxmlEditor
import com.pkgmod.core.Bytes
import com.pkgmod.dex.DexEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * 用合成数据跑生产代码里的三个二进制改写器（JVM 单元测试，不需要设备）。
 * 校验：包名与连带属性、结构尺寸、DEX 的 checksum / SHA-1。
 */
class PatchersTest {

    private companion object {
        const val TYPE_STRING_POOL = 0x0001
        const val TYPE_XML = 0x0003
        const val TYPE_START_ELEMENT = 0x0102
        const val TYPE_START_NAMESPACE = 0x0100
        const val TYPE_END_NAMESPACE = 0x0101
        const val TYPE_END_ELEMENT = 0x0103
        const val TYPE_RESOURCE_MAP = 0x0180
        const val NO_INDEX = -1
        const val TYPE_STRING = 0x03
        const val OLD = "com.aligames.kuang.kybc"
        const val NEW = "com.aligames.kuang.kybd.v2"
    }

    // ---------------- AXML ----------------

    @Test
    fun axmlRenamesPackageAndDependentAttributes() {
        for (utf8 in listOf(true, false)) {
            val input = buildAxml(OLD, utf8)
            val output = AxmlEditor(input).renamePackage(OLD, NEW)

            assertNotEquals("AXML 应该发生变化 (utf8=$utf8)", input.size, output.size)
            assertEquals("XML 头 size 必须与文件长度一致", output.size, Bytes.i32(output, 4))

            val reparsed = AxmlEditor(output)
            assertEquals("package 属性 (utf8=$utf8)", NEW, reparsed.packageName())

            val attrs = attributesByElement(output)
            assertEquals(NEW + ".permission.PUSH", attrs["uses-permission"]?.get("name"))
            assertEquals(NEW + ".fileprovider", attrs["provider"]?.get("authorities"))
            assertEquals(NEW, attrs["application"]?.get("taskAffinity"))
            assertEquals("类名属性不能被改动", "com.example.MainActivity", attrs["activity"]?.get("name"))
        }
    }

    @Test
    fun axmlKeepsChunksConsistent() {
        val output = AxmlEditor(buildAxml(OLD, true)).renamePackage(OLD, NEW)
        var pos = 8
        var seen = 0
        while (pos + 8 <= output.size) {
            val size = Bytes.i32(output, pos + 4)
            assertTrue("chunk 尺寸非法 @$pos", size >= 8)
            assertTrue("chunk 越界 @$pos", pos + size <= output.size)
            pos += size
            seen++
        }
        assertEquals("chunk 必须正好铺满文件", output.size, pos)
        assertTrue("至少要解析到几个 chunk", seen > 5)
    }

    // ---------------- ARSC ----------------

    @Test
    fun arscReplacesOnlyPackageLikeStrings() {
        val strings = listOf(
            OLD,
            "hello",
            "$OLD.channel",
            "com.old.SomeClass",
            "$OLD.SomeActivity"
        )
        val input = buildArsc(strings)
        val output = ArscEditor(input).replacePackageStrings(OLD, NEW)

        val result = ArscEditor(output)
        assertEquals(NEW, result.stringAt(0))
        assertEquals("hello", result.stringAt(1))
        assertEquals("$NEW.channel", result.stringAt(2))
        assertEquals("com.old.SomeClass", result.stringAt(3))
        assertEquals("$OLD.SomeActivity", result.stringAt(4))
        assertEquals("表头 size 必须同步", output.size, Bytes.i32(output, 4))
    }

    // ---------------- DEX ----------------

    @Test
    fun dexReplacesStringsAndFixesDigests() {
        val strings = listOf(
            OLD,
            "hello",
            "$OLD.channel",
            "Lcom/aligames/kuang/kybc/Main;",
            "com.old.SomeClass"
        )
        val input = buildDex(strings)
        val output = DexEditor(input).replacePackageStrings(OLD, NEW)

        val now = dexStrings(output)
        assertEquals(NEW, now[0])
        assertEquals("hello", now[1])
        assertEquals("$NEW.channel", now[2])
        assertEquals("Lcom/aligames/kuang/kybc/Main;", now[3])
        assertEquals("com.old.SomeClass", now[4])

        assertEquals("file_size", output.size, Bytes.i32(output, 32))
        assertEquals("data_size", output.size - Bytes.i32(output, 108), Bytes.i32(output, 104))

        val adler = Adler32()
        adler.update(output, 12, output.size - 12)
        assertEquals("checksum", adler.value.toInt(), Bytes.i32(output, 8))

        val digest = MessageDigest.getInstance("SHA-1").digest(output.copyOfRange(32, output.size))
        assertTrue("signature", digest.contentEquals(output.copyOfRange(12, 32)))
    }

    // ---------------- 合成 AXML ----------------

    private fun buildAxml(pkg: String, utf8: Boolean, withRawPackage: Boolean = true): ByteArray {
        val strings = listOf(
            "android",
            "http://schemas.android.com/apk/res/android",
            "manifest",
            "package",
            pkg,
            "uses-permission",
            "name",
            "$pkg.permission.PUSH",
            "application",
            "provider",
            "authorities",
            "$pkg.fileprovider",
            "activity",
            "taskAffinity",
            "com.example.MainActivity"
        )
        val index = { s: String -> strings.indexOf(s) }

        val chunks = ByteArrayOutputStream()
        val map = ByteArrayOutputStream()
        map.writeU16le(TYPE_RESOURCE_MAP)
        map.writeU16le(8)
        map.writeI32le(8 + strings.size * 4)
        for (i in strings.indices) map.writeI32le(0x01010000 + i)
        chunks.write(map.toByteArray())

        chunks.write(namespace(TYPE_START_NAMESPACE, index("android"), index("http://schemas.android.com/apk/res/android")))

        chunks.write(
            startElement(
                index("manifest"),
                listOf(
                    intArrayOf(
                        NO_INDEX,
                        index("package"),
                        if (withRawPackage) index(pkg) else NO_INDEX,
                        TYPE_STRING,
                        index(pkg)
                    )
                )
            )
        )

        chunks.write(
            startElement(
                index("uses-permission"),
                listOf(intArrayOf(index("android"), index("name"), index("$pkg.permission.PUSH"), TYPE_STRING, index("$pkg.permission.PUSH")))
            )
        )
        chunks.write(endElement(index("uses-permission")))

        chunks.write(
            startElement(
                index("application"),
                listOf(intArrayOf(index("android"), index("taskAffinity"), index(pkg), TYPE_STRING, index(pkg)))
            )
        )

        chunks.write(
            startElement(
                index("provider"),
                listOf(intArrayOf(index("android"), index("authorities"), index("$pkg.fileprovider"), TYPE_STRING, index("$pkg.fileprovider")))
            )
        )
        chunks.write(endElement(index("provider")))

        chunks.write(
            startElement(
                index("activity"),
                listOf(intArrayOf(index("android"), index("name"), index("com.example.MainActivity"), TYPE_STRING, index("com.example.MainActivity")))
            )
        )
        chunks.write(endElement(index("activity")))
        chunks.write(endElement(index("application")))
        chunks.write(endElement(index("manifest")))
        chunks.write(namespace(TYPE_END_NAMESPACE, index("android"), index("http://schemas.android.com/apk/res/android")))

        val pool = stringPool(strings, utf8)
        val total = 8 + pool.size + chunks.size()
        val header = ByteArrayOutputStream()
        header.writeU16le(TYPE_XML)
        header.writeU16le(8)
        header.writeI32le(total)
        return header.toByteArray() + pool + chunks.toByteArray()
    }

    private fun stringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val count = strings.size
        val headerSize = 28
        val stringsStart = headerSize + count * 4
        val body = ByteArrayOutputStream()
        val offsets = IntArray(count)

        for (i in strings.indices) {
            offsets[i] = body.size()
            val text = strings[i]
            if (utf8) {
                val encoded = text.toByteArray(Charsets.UTF_8)
                writeUtf8Length(body, text.length)
                writeUtf8Length(body, encoded.size)
                body.write(encoded)
                body.write(0)
            } else {
                val encoded = text.toByteArray(Charsets.UTF_16LE)
                val chars = encoded.size / 2
                body.write(chars and 0xFF)
                body.write((chars ushr 8) and 0xFF)
                body.write(encoded)
                body.write(0)
                body.write(0)
            }
        }

        val out = ByteArrayOutputStream()
        out.writeU16le(TYPE_STRING_POOL)
        out.writeU16le(headerSize)
        out.writeI32le(stringsStart + body.size())
        out.writeI32le(count)
        out.writeI32le(0)
        out.writeI32le(if (utf8) 0x100 else 0)
        out.writeI32le(stringsStart)
        out.writeI32le(0)
        for (offset in offsets) out.writeI32le(offset)
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun namespace(type: Int, prefix: Int, uri: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeU16le(type)
        out.writeU16le(16)
        out.writeI32le(24)
        out.writeI32le(NO_INDEX)
        out.writeI32le(NO_INDEX)
        out.writeI32le(prefix)
        out.writeI32le(uri)
        return out.toByteArray()
    }

    private fun endElement(name: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeU16le(TYPE_END_ELEMENT)
        out.writeU16le(16)
        out.writeI32le(24)
        out.writeI32le(NO_INDEX)
        out.writeI32le(NO_INDEX)
        out.writeI32le(NO_INDEX)
        out.writeI32le(name)
        return out.toByteArray()
    }

    /** attrs 的每一项是 [ns, name, raw, dataType, data]。 */
    private fun startElement(name: Int, attrs: List<IntArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeI32le(NO_INDEX)
        body.writeI32le(NO_INDEX)
        body.writeI32le(NO_INDEX)
        body.writeI32le(name)
        body.writeU16le(20)
        body.writeU16le(20)
        body.writeU16le(attrs.size)
        body.writeU16le(0)
        body.writeU16le(0)
        body.writeU16le(0)
        for (attr in attrs) {
            body.writeI32le(attr[0])
            body.writeI32le(attr[1])
            body.writeI32le(attr[2])
            body.writeU16le(8)
            body.write(0)
            body.write(attr[3])
            body.writeI32le(attr[4])
        }

        val out = ByteArrayOutputStream()
        out.writeU16le(TYPE_START_ELEMENT)
        out.writeU16le(16)
        out.writeI32le(8 + body.size())
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun attributesByElement(data: ByteArray): Map<String, Map<String, String>> {
        val (strings, end) = readPool(data, 8)
        val result = HashMap<String, MutableMap<String, String>>()
        var pos = end
        while (pos + 8 <= data.size) {
            val type = Bytes.u16(data, pos)
            val size = Bytes.i32(data, pos + 4)
            if (size < 8 || pos + size > data.size) break
            if (type == TYPE_START_ELEMENT) {
                val element = strings[Bytes.i32(data, pos + 20)]
                val attrStart = Bytes.u16(data, pos + 24)
                val count = Bytes.u16(data, pos + 28)
                val base = pos + Bytes.u16(data, pos + 2) + attrStart
                val values = result.getOrPut(element) { HashMap() }
                for (i in 0 until count) {
                    val off = base + i * 20
                    val attrName = strings[Bytes.i32(data, off + 4)]
                    val raw = Bytes.i32(data, off + 8)
                    if (raw in strings.indices) values[attrName] = strings[raw]
                }
            }
            pos += size
        }
        return result
    }

    private fun readPool(data: ByteArray, off: Int): Pair<List<String>, Int> {
        val count = Bytes.i32(data, off + 8)
        val headerSize = Bytes.u16(data, off + 2)
        val size = Bytes.i32(data, off + 4)
        val stringsStart = Bytes.i32(data, off + 20)
        val utf8 = (Bytes.i32(data, off + 16) and 0x100) != 0
        val base = off + stringsStart
        val list = ArrayList<String>(count)
        for (i in 0 until count) {
            val rel = Bytes.i32(data, off + headerSize + i * 4)
            list.add(decodeString(data, base + rel, utf8))
        }
        return list to off + size
    }

    private fun decodeString(data: ByteArray, start: Int, utf8: Boolean): String {
        var pos = start
        return if (utf8) {
            var charLength = Bytes.u8(data, pos)
            pos++
            if (charLength and 0x80 != 0) {
                charLength = ((charLength and 0x7F) shl 8) or Bytes.u8(data, pos)
                pos++
            }
            var byteLength = Bytes.u8(data, pos)
            pos++
            if (byteLength and 0x80 != 0) {
                byteLength = ((byteLength and 0x7F) shl 8) or Bytes.u8(data, pos)
                pos++
            }
            String(data, pos, byteLength, Charsets.UTF_8)
        } else {
            val len = Bytes.u16(data, pos)
            String(data, pos + 2, len * 2, Charsets.UTF_16LE)
        }
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, value: Int) {
        if (value > 0x7F) {
            out.write(0x80 or ((value ushr 8) and 0x7F))
        }
        out.write(value and 0xFF)
    }

    @Test
    fun axmlRewritesStringOnlyAttribute() {
        // rawValue 缺失、只靠 typedValue 指向字符串的属性（回归：dataType 在 +15，res0 在 +14）
        val input = buildAxml(OLD, true, withRawPackage = false)
        val output = AxmlEditor(input).renamePackage(OLD, NEW)

        assertEquals(NEW, AxmlEditor(output).packageName())

        val attrOffset = packageAttributeOffset(output)
        assertEquals("res0 必须保持 0", 0, Bytes.u8(output, attrOffset + 14))
        assertEquals("dataType 必须是 TYPE_STRING", 0x03, Bytes.u8(output, attrOffset + 15))
    }

    private fun packageAttributeOffset(data: ByteArray): Int {
        val (strings, end) = readPool(data, 8)
        var pos = end
        while (pos + 8 <= data.size) {
            val type = Bytes.u16(data, pos)
            val size = Bytes.i32(data, pos + 4)
            if (size < 8 || pos + size > data.size) break
            if (type == TYPE_START_ELEMENT) {
                val element = strings[Bytes.i32(data, pos + 20)]
                if (element == "manifest") {
                    val attrStart = Bytes.u16(data, pos + 24)
                    val count = Bytes.u16(data, pos + 28)
                    val base = pos + Bytes.u16(data, pos + 2) + attrStart
                    for (i in 0 until count) {
                        val off = base + i * 20
                        if (strings[Bytes.i32(data, off + 4)] == "package") return off
                    }
                }
            }
            pos += size
        }
        error("找不到 package 属性")
    }

    // ---------------- 合成 ARSC ----------------

    private fun buildArsc(strings: List<String>): ByteArray {
        val pool = stringPool(strings, true)
        val packageSize = 0x11C
        val packageChunk = ByteArray(packageSize)
        Bytes.putU16(packageChunk, 0, 0x0200)
        Bytes.putU16(packageChunk, 2, 0x11C)
        Bytes.putU32(packageChunk, 4, packageSize.toLong())
        val total = 12 + pool.size + packageSize
        val header = ByteArray(12)
        Bytes.putU16(header, 0, 0x0002)
        Bytes.putU16(header, 2, 12)
        Bytes.putU32(header, 4, total.toLong())
        Bytes.putU32(header, 8, 1L)
        return header + pool + packageChunk
    }

    // ---------------- 合成 DEX ----------------

    private fun buildDex(strings: List<String>): ByteArray {
        val idsOffset = 0x70
        val dataOffset = idsOffset + strings.size * 4
        val body = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = dataOffset + body.size()
            writeUleb128(body, strings[i].length)
            body.write(mutf8(strings[i]))
            body.write(0)
        }

        val total = dataOffset + body.size()
        val out = ByteArray(total)
        val magic = byteArrayOf(
            'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
            '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0
        )
        System.arraycopy(magic, 0, out, 0, 8)
        Bytes.putU32(out, 32, total.toLong())
        Bytes.putU32(out, 36, 0x70L)
        Bytes.putU32(out, 40, 0x12345678L)
        Bytes.putU32(out, 56, strings.size.toLong())
        Bytes.putU32(out, 60, idsOffset.toLong())
        Bytes.putU32(out, 104, body.size().toLong())
        Bytes.putU32(out, 108, dataOffset.toLong())
        for (i in strings.indices) {
            Bytes.putI32(out, idsOffset + i * 4, offsets[i])
        }
        System.arraycopy(body.toByteArray(), 0, out, dataOffset, body.size())

        val digest = MessageDigest.getInstance("SHA-1").digest(out.copyOfRange(32, out.size))
        System.arraycopy(digest, 0, out, 12, 20)
        val adler = Adler32()
        adler.update(out, 12, out.size - 12)
        Bytes.putI32(out, 8, adler.value.toInt())
        return out
    }

    private fun dexStrings(data: ByteArray): List<String> {
        val count = Bytes.i32(data, 56)
        val idsOffset = Bytes.i32(data, 60)
        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val offset = Bytes.i32(data, idsOffset + i * 4)
            var pos = offset
            while ((data[pos].toInt() and 0x80) != 0) pos++
            pos++
            val start = pos
            var end = pos
            while (data[end] != 0.toByte()) end++
            result.add(decodeMutf8(data, start, end))
        }
        return result
    }

    private fun decodeMutf8(data: ByteArray, start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val first = data[i].toInt() and 0xFF
            when {
                first < 0x80 -> {
                    builder.append(first.toChar())
                    i++
                }
                first in 0xC0..0xDF && i + 1 < end -> {
                    builder.append((((first and 0x1F) shl 6) or (data[i + 1].toInt() and 0x3F)).toChar())
                    i += 2
                }
                else -> {
                    builder.append(
                        (
                            ((first and 0x0F) shl 12) or
                                ((data[i + 1].toInt() and 0x3F) shl 6) or
                                (data[i + 2].toInt() and 0x3F)
                            ).toChar()
                    )
                    i += 3
                }
            }
        }
        return builder.toString()
    }

    private fun mutf8(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (ch in text) {
            val code = ch.code
            when {
                code == 0 -> {
                    out.write(0xC0)
                    out.write(0x80)
                }
                code < 0x80 -> out.write(code)
                code < 0x800 -> {
                    out.write(0xC0 or (code shr 6))
                    out.write(0x80 or (code and 0x3F))
                }
                else -> {
                    out.write(0xE0 or (code shr 12))
                    out.write(0x80 or ((code shr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
            }
        }
        return out.toByteArray()
    }

    private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        do {
            var byteValue = rest and 0x7F
            rest = rest ushr 7
            if (rest != 0) byteValue = byteValue or 0x80
            out.write(byteValue)
        } while (rest != 0)
    }
}

private fun ByteArrayOutputStream.writeU16le(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}

private fun ByteArrayOutputStream.writeI32le(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}
