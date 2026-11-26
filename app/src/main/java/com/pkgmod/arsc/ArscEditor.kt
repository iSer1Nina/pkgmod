package com.pkgmod.arsc

import com.pkgmod.core.Bytes
import com.pkgmod.core.putU32le
import java.io.ByteArrayOutputStream

/**
 * resources.arsc 读写。
 *
 * 只重建全局字符串池（其他 chunk 原样保留）。arsc 里 package 内部偏移都是
 * 相对 chunk 起始的，所以全局池变长后其余结构不受影响。
 */
class ArscEditor(input: ByteArray) {

    companion object {
        private const val TYPE_TABLE = 0x0002
        private const val TYPE_STRING_POOL = 0x0001
        private const val UTF8_FLAG = 0x0100
        private const val POOL_HEADER_SIZE = 28
    }

    private val data: ByteArray = input.copyOf()
    private val strings = ArrayList<String>()
    private var utf8Pool = false
    private var poolStart = 12
    private var poolEnd = 0

    init {
        require(data.size > 32) { "resources.arsc 太小" }
        require(Bytes.u16(data, 0) == TYPE_TABLE) { "不是 resources.arsc" }

        val tableHeaderSize = Bytes.u16(data, 2)
        require(tableHeaderSize in 12..32) { "arsc 表头异常" }
        poolStart = tableHeaderSize
        require(Bytes.u16(data, poolStart) == TYPE_STRING_POOL) { "缺少全局字符串池" }
        parsePool()
    }

    fun stringCount(): Int = strings.size

    fun stringAt(index: Int): String = strings[index]

    private fun parsePool() {
        val off = poolStart
        val headerSize = Bytes.u16(data, off + 2)
        val size = Bytes.i32(data, off + 4)
        val count = Bytes.i32(data, off + 8)
        val styleCount = Bytes.i32(data, off + 12)
        val flags = Bytes.i32(data, off + 16)
        val stringsStart = Bytes.i32(data, off + 20)

        require(styleCount == 0) { "暂不支持带 style 的 arsc 字符串池" }
        require(count in 0..2_000_000) { "arsc 字符串数量异常" }
        require(stringsStart > 0 && size > 0) { "arsc 字符串池头损坏" }

        utf8Pool = (flags and UTF8_FLAG) != 0
        val offsetsBase = off + headerSize
        val dataBase = off + stringsStart

        for (i in 0 until count) {
            val rel = Bytes.i32(data, offsetsBase + i * 4)
            strings.add(decodeString(dataBase + rel))
        }

        poolEnd = off + size
        require(poolEnd in 32..data.size) { "arsc 字符串池越界" }
    }

    private fun decodeString(start: Int): String {
        var pos = start
        return if (utf8Pool) {
            // UTF-8 池：先 UTF-16 字符长度，再 UTF-8 字节长度
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
            var len = Bytes.u16(data, pos)
            pos += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or Bytes.u16(data, pos)
                pos += 2
            }
            String(data, pos, len * 2, Charsets.UTF_16LE)
        }
    }

    /**
     * 替换字符串池中与包名相关的值。
     *
     * [includePrefix] 为 true 时，`旧包名.xxx` 形式也会被替换；
     * 但最后一段首字母大写的（类名风格，如 com.old.MainActivity）会跳过。
     */
    fun replacePackageStrings(
        oldPkg: String,
        newPkg: String,
        includePrefix: Boolean = true
    ): ByteArray {
        var changed = false
        val updated = ArrayList<String>(strings.size)
        for (value in strings) {
            val mapped = mapString(value, oldPkg, newPkg, includePrefix)
            if (mapped != null) {
                changed = true
                updated.add(mapped)
            } else {
                updated.add(value)
            }
        }
        if (!changed) return data

        val pool = buildStringPool(updated)
        val out = ByteArrayOutputStream(data.size + pool.size)
        out.write(data, 0, poolStart)
        out.write(pool)
        out.write(data, poolEnd, data.size - poolEnd)

        val result = out.toByteArray()
        Bytes.putU32(result, 4, result.size.toLong())
        return result
    }

    private fun mapString(
        value: String,
        oldPkg: String,
        newPkg: String,
        includePrefix: Boolean
    ): String? {
        if (value == oldPkg) return newPkg
        if (!includePrefix) return null
        if (!value.startsWith("$oldPkg.")) return null
        if (value.startsWith("L$oldPkg/")) return null

        val rest = value.substring(oldPkg.length + 1)
        val lastSegment = rest.substringAfterLast('.')
        if (lastSegment.isNotEmpty() && lastSegment[0].isUpperCase()) return null
        return "$newPkg.$rest"
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, value: Int) {
        if (value > 0x7F) {
            out.write(0x80 or ((value ushr 8) and 0x7F))
        }
        out.write(value and 0xFF)
    }

    private fun buildStringPool(list: List<String>): ByteArray {
        val count = list.size
        val stringsStart = POOL_HEADER_SIZE + count * 4
        val body = ByteArrayOutputStream()
        val offsets = IntArray(count)

        for (index in list.indices) {
            offsets[index] = body.size()
            val text = list[index]

            if (utf8Pool) {
                val encoded = text.toByteArray(Charsets.UTF_8)
                writeUtf8Length(body, text.length)
                writeUtf8Length(body, encoded.size)
                body.write(encoded)
                body.write(0)
            } else {
                val encoded = text.toByteArray(Charsets.UTF_16LE)
                val chars = encoded.size / 2
                if (chars > 0x7FFF) {
                    val first = 0x8000 or ((chars ushr 16) and 0x7FFF)
                    body.write(first and 0xFF)
                    body.write((first ushr 8) and 0xFF)
                }
                body.write(chars and 0xFF)
                body.write((chars ushr 8) and 0xFF)
                body.write(encoded)
                body.write(0)
                body.write(0)
            }
        }

        // 池整体按 4 字节对齐
        while (body.size() % 4 != 0) {
            body.write(0)
        }

        val header = ByteArray(POOL_HEADER_SIZE)
        Bytes.putU16(header, 0, TYPE_STRING_POOL)
        Bytes.putU16(header, 2, POOL_HEADER_SIZE)
        Bytes.putU32(header, 4, (stringsStart + body.size()).toLong())
        Bytes.putU32(header, 8, count.toLong())
        Bytes.putU32(header, 12, 0L)
        Bytes.putU32(header, 16, if (utf8Pool) UTF8_FLAG.toLong() else 0L)
        Bytes.putU32(header, 20, stringsStart.toLong())
        Bytes.putU32(header, 24, 0L)

        val out = ByteArrayOutputStream(POOL_HEADER_SIZE + count * 4 + body.size())
        out.write(header)
        for (offset in offsets) {
            out.putU32le(offset.toLong())
        }
        out.write(body.toByteArray())
        return out.toByteArray()
    }
}
