package com.pkgmod.dex

import com.pkgmod.core.Bytes
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * DEX 字符串替换。
 *
 * 做法：不改动原有数据区，只在文件尾部追加新的 string_data 项，
 * 然后把对应的 string_ids 偏移指过去，最后重算 data_size、file_size、
 * checksum 与 signature。支持变长替换。
 */
class DexEditor(input: ByteArray) {

    companion object {
        private const val HEADER_SIZE = 0x70
    }

    private val data: ByteArray = input.copyOf()

    init {
        require(data.size >= HEADER_SIZE) { "dex 太小" }
        require(
            data[0] == 'd'.code.toByte() &&
                data[1] == 'e'.code.toByte() &&
                data[2] == 'x'.code.toByte()
        ) { "不是标准 dex（可能是 compact dex）" }
    }

    /**
     * 把字符串池中与包名相关的字符串替换成新包名。
     *
     * [includePrefix] 为 true 时，`旧包名.xxx` 会被替换，
     * 但类名风格（最后一段首字母大写）与类描述符会跳过。
     */
    fun replacePackageStrings(
        oldPkg: String,
        newPkg: String,
        includePrefix: Boolean = true
    ): ByteArray {
        val stringIdsSize = Bytes.i32(data, 56)
        val stringIdsOff = Bytes.i32(data, 60)
        require(stringIdsSize in 0..5_000_000) { "dex string_ids 数量异常" }
        require(stringIdsOff > 0 && stringIdsOff + stringIdsSize * 4 <= data.size) { "dex string_ids 越界" }

        val tail = ByteArrayOutputStream()
        val newOffsets = IntArray(stringIdsSize) { -1 }
        var replaced = 0

        for (index in 0 until stringIdsSize) {
            val offset = Bytes.i32(data, stringIdsOff + index * 4)
            if (offset <= 0 || offset >= data.size) continue

            val value = readString(data, offset)
            val mapped = mapString(value, oldPkg, newPkg, includePrefix) ?: continue

            newOffsets[index] = data.size + tail.size()
            writeUleb128(tail, mapped.length)
            tail.write(encodeMutf8(mapped))
            tail.write(0)
            replaced++
        }

        if (replaced == 0) return data

        val out = ByteArrayOutputStream(data.size + tail.size())
        out.write(data)
        out.write(tail.toByteArray())
        val result = out.toByteArray()

        for (index in 0 until stringIdsSize) {
            if (newOffsets[index] >= 0) {
                Bytes.putI32(result, stringIdsOff + index * 4, newOffsets[index])
            }
        }

        val fileSize = result.size
        val dataOff = Bytes.i32(result, 108)
        Bytes.putU32(result, 32, fileSize.toLong())
        Bytes.putU32(result, 104, (fileSize - dataOff).toLong())

        // 顺序不能反：checksum 覆盖包含 signature 在内的后续全部字节，
        // 所以先写 signature，再算 checksum。
        val digest = MessageDigest.getInstance("SHA-1").digest(result.copyOfRange(32, result.size))
        System.arraycopy(digest, 0, result, 12, 20)

        val adler = Adler32()
        adler.update(result, 12, result.size - 12)
        Bytes.putI32(result, 8, adler.value.toInt())

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

    private fun readString(buf: ByteArray, offset: Int): String {
        var pos = offset
        var length = 0
        var shift = 0
        while (pos < buf.size) {
            val b = buf[pos].toInt() and 0xFF
            pos++
            length = length or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val start = pos
        var end = start
        while (end < buf.size && buf[end] != 0.toByte()) {
            end++
        }
        return decodeMutf8(buf, start, end)
    }

    private fun decodeMutf8(bytes: ByteArray, start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        var index = start
        while (index < end) {
            val first = bytes[index].toInt() and 0xFF
            when {
                first < 0x80 -> {
                    builder.append(first.toChar())
                    index++
                }
                first in 0xC0..0xDF && index + 1 < end -> {
                    val second = bytes[index + 1].toInt() and 0xFF
                    builder.append((((first and 0x1F) shl 6) or (second and 0x3F)).toChar())
                    index += 2
                }
                first in 0xE0..0xEF && index + 2 < end -> {
                    val second = bytes[index + 1].toInt() and 0xFF
                    val third = bytes[index + 2].toInt() and 0xFF
                    builder.append(
                        (((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)).toChar()
                    )
                    index += 3
                }
                else -> {
                    builder.append('\uFFFD')
                    index++
                }
            }
        }
        return builder.toString()
    }

    private fun encodeMutf8(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 2)
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
        var remaining = value
        do {
            var byteValue = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) byteValue = byteValue or 0x80
            out.write(byteValue)
        } while (remaining != 0)
    }
}
