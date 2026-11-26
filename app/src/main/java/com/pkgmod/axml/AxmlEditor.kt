package com.pkgmod.axml

import com.pkgmod.core.Bytes
import com.pkgmod.core.putU32le
import java.io.ByteArrayOutputStream

/**
 * 二进制 AndroidManifest.xml（AXML）读写。
 *
 * 只重建字符串池，其余 chunk 原样搬运；因此可以支持任意长度的包名替换，
 * 并且不会破坏资源映射、命名空间等结构。
 *
 * 注意：只修改“包名语义”的属性，不会去动 android:name 这类类名属性。
 */
class AxmlEditor(input: ByteArray) {

    companion object {
        private const val TYPE_XML = 0x0003
        private const val TYPE_STRING_POOL = 0x0001
        private const val TYPE_START_ELEMENT = 0x0102
        private const val UTF8_FLAG = 0x0100
        private const val NO_INDEX = -1
        private const val TYPE_STRING = 0x03
        private const val ATTRIBUTE_SIZE = 20
        private const val STRING_POOL_HEADER_SIZE = 28

        private val PERMISSION_ELEMENTS = setOf(
            "uses-permission",
            "uses-permission-sdk-23",
            "permission",
            "permission-group",
            "permission-tree"
        )

        private val AFFINITY_ELEMENTS = setOf(
            "application",
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider"
        )

        /** 组件名/类名属性：以 "." 开头的是相对包名的写法，改包名后会指向不存在的类。 */
        private val COMPONENT_ELEMENTS = setOf(
            "application",
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider",
            "instrumentation"
        )

        private val CLASS_NAME_ATTRIBUTES = setOf(
            "name",
            "targetActivity",
            "backupAgent",
            "manageSpaceActivity",
            "appComponentFactory"
        )
    }

    private val data: ByteArray = input.copyOf()
    private val strings = ArrayList<String>()
    private var utf8Pool = false
    private var poolEnd = 0

    init {
        parse()
    }

    private data class Edit(
        val elementOrder: Int,
        val attributeOrder: Int,
        val value: String
    )

    private fun parse() {
        require(data.size >= 12) { "文件太小，不是二进制 XML" }
        require(Bytes.u16(data, 0) == TYPE_XML) { "不是二进制 AndroidManifest.xml（AXML）" }
        require(Bytes.u16(data, 8) == TYPE_STRING_POOL) { "缺少字符串池" }

        val off = 8
        val headerSize = Bytes.u16(data, off + 2)
        val size = Bytes.i32(data, off + 4)
        val count = Bytes.i32(data, off + 8)
        val styleCount = Bytes.i32(data, off + 12)
        val flags = Bytes.i32(data, off + 16)
        val stringsStart = Bytes.i32(data, off + 20)

        require(styleCount == 0) { "暂不支持带 style 的字符串池" }
        require(count in 0..1_000_000) { "字符串数量异常" }
        require(headerSize in 28..64) { "字符串池头异常" }
        require(stringsStart > 0 && size > 0) { "字符串池头损坏" }

        utf8Pool = (flags and UTF8_FLAG) != 0
        val offsetsBase = off + headerSize
        val dataBase = off + stringsStart

        for (i in 0 until count) {
            val rel = Bytes.i32(data, offsetsBase + i * 4)
            strings.add(decodeString(dataBase + rel))
        }

        poolEnd = off + size
        require(poolEnd in 12..data.size) { "字符串池越界" }
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

    /** 读取清单里的 package 属性。 */
    fun packageName(): String? {
        val attr = findAttribute("manifest", "package", requireNoNamespace = true) ?: return null
        return attributeString(attr)
    }

    /**
     * 把包名从 [oldPkg] 改成 [newPkg]，同时修正依赖包名的属性。
     * 返回新的 AXML 字节（原数组不被修改）。
     */
    fun renamePackage(oldPkg: String, newPkg: String): ByteArray {
        val edits = collectEdits(oldPkg, newPkg)
        if (edits.isEmpty()) return data

        val allStrings = ArrayList(strings)
        val resolved = ArrayList<Triple<Int, Int, Int>>(edits.size)
        for (edit in edits) {
            resolved.add(Triple(edit.elementOrder, edit.attributeOrder, intern(allStrings, edit.value)))
        }

        val pool = buildStringPool(allStrings)
        val out = ByteArrayOutputStream(data.size + pool.size)
        out.write(byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00))
        out.write(pool)
        out.write(data, poolEnd, data.size - poolEnd)

        val result = out.toByteArray()
        Bytes.putU32(result, 4, result.size.toLong())
        applyEdits(result, resolved)
        return result
    }

    private fun intern(list: MutableList<String>, value: String): Int {
        val existing = list.indexOf(value)
        if (existing >= 0) return existing
        list.add(value)
        return list.size - 1
    }

    private fun collectEdits(oldPkg: String, newPkg: String): List<Edit> {
        val edits = ArrayList<Edit>()
        var elementOrder = 0
        var pos = 8

        while (pos + 8 <= data.size) {
            val type = Bytes.u16(data, pos)
            val size = Bytes.i32(data, pos + 4)
            if (size < 8 || pos + size > data.size) break

            if (type == TYPE_START_ELEMENT) {
                val headerSize = Bytes.u16(data, pos + 2)
                val nameIdx = Bytes.i32(data, pos + 20)
                if (nameIdx in strings.indices) {
                    val elementName = strings[nameIdx]
                    val attributeStart = Bytes.u16(data, pos + 24)
                    val attributeCount = Bytes.u16(data, pos + 28)
                    val attributeBase = pos + headerSize + attributeStart

                    for (index in 0 until attributeCount) {
                        val attrOff = attributeBase + index * ATTRIBUTE_SIZE
                        if (attrOff + ATTRIBUTE_SIZE > data.size) break

                        val ns = Bytes.i32(data, attrOff)
                        val attrNameIdx = Bytes.i32(data, attrOff + 4)
                        if (attrNameIdx !in strings.indices) continue

                        val value = attributeString(attrOff) ?: continue
                        val updated = mapValue(
                            elementName,
                            strings[attrNameIdx],
                            ns,
                            value,
                            oldPkg,
                            newPkg
                        ) ?: continue
                        edits.add(Edit(elementOrder, index, updated))
                    }
                }
                elementOrder++
            }
            pos += size
        }
        return edits
    }

    private fun mapValue(
        element: String,
        attribute: String,
        ns: Int,
        value: String,
        oldPkg: String,
        newPkg: String
    ): String? {
        if (element == "manifest" && attribute == "package" && ns == NO_INDEX) {
            return if (value == oldPkg || value.isEmpty()) newPkg else null
        }
        if (element in PERMISSION_ELEMENTS && attribute == "name") {
            return withPrefix(value, oldPkg, newPkg)
        }
        if (element == "provider" && attribute == "authorities") {
            return replaceAuthorities(value, oldPkg, newPkg)
        }
        if (element in AFFINITY_ELEMENTS && attribute == "taskAffinity") {
            return withPrefix(value, oldPkg, newPkg)
        }
        // 相对写法必须补全成旧包名，否则改包名后会去新包名下找类
        if (element in COMPONENT_ELEMENTS && attribute in CLASS_NAME_ATTRIBUTES && value.startsWith(".")) {
            return oldPkg + value
        }
        return null
    }

    private fun withPrefix(value: String, oldPkg: String, newPkg: String): String? = when {
        value == oldPkg -> newPkg
        value.startsWith("$oldPkg.") -> newPkg + value.substring(oldPkg.length)
        else -> null
    }

    private fun replaceAuthorities(value: String, oldPkg: String, newPkg: String): String? {
        if (!value.contains(oldPkg)) return null
        var changed = false
        val parts = value.split(';').map { part ->
            val replaced = withPrefix(part, oldPkg, newPkg)
            if (replaced != null) {
                changed = true
                replaced
            } else {
                part
            }
        }
        return if (changed) parts.joinToString(";") else null
    }

    private fun findAttribute(
        element: String,
        attribute: String,
        requireNoNamespace: Boolean
    ): Int? {
        var pos = 8
        while (pos + 8 <= data.size) {
            val type = Bytes.u16(data, pos)
            val size = Bytes.i32(data, pos + 4)
            if (size < 8 || pos + size > data.size) break

            if (type == TYPE_START_ELEMENT) {
                val headerSize = Bytes.u16(data, pos + 2)
                val nameIdx = Bytes.i32(data, pos + 20)
                if (nameIdx in strings.indices && strings[nameIdx] == element) {
                    val attributeStart = Bytes.u16(data, pos + 24)
                    val attributeCount = Bytes.u16(data, pos + 28)
                    val attributeBase = pos + headerSize + attributeStart

                    for (index in 0 until attributeCount) {
                        val attrOff = attributeBase + index * ATTRIBUTE_SIZE
                        if (attrOff + ATTRIBUTE_SIZE > data.size) break
                        val ns = Bytes.i32(data, attrOff)
                        val attrNameIdx = Bytes.i32(data, attrOff + 4)
                        if (attrNameIdx !in strings.indices) continue
                        if (strings[attrNameIdx] != attribute) continue
                        if (requireNoNamespace && ns != NO_INDEX) continue
                        return attrOff
                    }
                }
            }
            pos += size
        }
        return null
    }

    private fun attributeString(attrOff: Int): String? {
        if (attrOff + ATTRIBUTE_SIZE > data.size) return null
        val raw = Bytes.i32(data, attrOff + 8)
        if (raw in strings.indices) return strings[raw]

        val dataType = Bytes.u8(data, attrOff + 15)
        if (dataType != TYPE_STRING) return null

        val typed = Bytes.i32(data, attrOff + 16)
        return if (typed in strings.indices) strings[typed] else null
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, value: Int) {
        if (value > 0x7F) {
            out.write(0x80 or ((value ushr 8) and 0x7F))
        }
        out.write(value and 0xFF)
    }

    private fun buildStringPool(list: List<String>): ByteArray {
        val count = list.size
        val stringsStart = STRING_POOL_HEADER_SIZE + count * 4
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

        // 池整体按 4 字节对齐：aapt 产出的 chunk 尺寸都是 4 的倍数
        while (body.size() % 4 != 0) {
            body.write(0)
        }

        val header = ByteArray(STRING_POOL_HEADER_SIZE)
        Bytes.putU16(header, 0, TYPE_STRING_POOL)
        Bytes.putU16(header, 2, STRING_POOL_HEADER_SIZE)
        Bytes.putU32(header, 4, (stringsStart + body.size()).toLong())
        Bytes.putU32(header, 8, count.toLong())
        Bytes.putU32(header, 12, 0L)
        Bytes.putU32(header, 16, if (utf8Pool) UTF8_FLAG.toLong() else 0L)
        Bytes.putU32(header, 20, stringsStart.toLong())
        Bytes.putU32(header, 24, 0L)

        val out = ByteArrayOutputStream(STRING_POOL_HEADER_SIZE + count * 4 + body.size())
        out.write(header)
        for (offset in offsets) {
            out.putU32le(offset.toLong())
        }
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun applyEdits(buf: ByteArray, edits: List<Triple<Int, Int, Int>>) {
        if (edits.isEmpty()) return
        val grouped = edits.groupBy({ it.first }, { it.second to it.third })

        var elementOrder = 0
        var pos = 8
        while (pos + 8 <= buf.size) {
            val type = Bytes.u16(buf, pos)
            val size = Bytes.i32(buf, pos + 4)
            if (size < 8 || pos + size > buf.size) break

            if (type == TYPE_START_ELEMENT) {
                val list = grouped[elementOrder]
                if (list != null) {
                    val headerSize = Bytes.u16(buf, pos + 2)
                    val attributeStart = Bytes.u16(buf, pos + 24)
                    val attributeBase = pos + headerSize + attributeStart

                    for ((attributeOrder, stringIndex) in list) {
                        val attrOff = attributeBase + attributeOrder * ATTRIBUTE_SIZE
                        if (attrOff + ATTRIBUTE_SIZE > buf.size) continue
                        Bytes.putI32(buf, attrOff + 8, stringIndex)
                        Bytes.putI32(buf, attrOff + 16, stringIndex)
                        Bytes.putU8(buf, attrOff + 15, TYPE_STRING)
                    }
                }
                elementOrder++
            }
            pos += size
        }
    }
}
