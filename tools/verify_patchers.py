#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线验证脚本（不依赖 Android SDK / JDK）。

用途：把 Kotlin 里的 AXML / ARSC / DEX 三个改写器按同一套偏移计算移植成 Python，
用合成数据跑一遍，再用独立实现的解析器校验产物的二进制结构。
Kotlin 端没有编译器时，这是唯一能实际执行并发现格式错误的办法。

运行：python tools/verify_patchers.py
"""

import hashlib
import struct
import sys
import zlib

NO_INDEX = 0xFFFFFFFF

# ---------------------------------------------------------------- 基础读写


def u8(b, o):
    return b[o]


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def i32(b, o):
    return struct.unpack_from("<i", b, o)[0]


def p16(b, o, v):
    struct.pack_into("<H", b, o, v)


def p32(b, o, v):
    struct.pack_into("<I", b, o, v & 0xFFFFFFFF)


# ---------------------------------------------------------------- 合成 AXML


def build_string_pool(strings, utf8):
    count = len(strings)
    header_size = 28
    strings_start = header_size + 4 * count
    body = bytearray()
    offsets = []

    for text in strings:
        offsets.append(len(body))
        if utf8:
            enc = text.encode("utf-8")
            char_len = len(text.encode("utf-16-le")) // 2
            # 先写 UTF-16 字符长度，再写 UTF-8 字节长度
            for value in (char_len, len(enc)):
                if value > 0x7F:
                    body.append(0x80 | ((value >> 8) & 0x7F))
                body.append(value & 0xFF)
            body += enc
            body.append(0)
        else:
            enc = text.encode("utf-16-le")
            chars = len(enc) // 2
            assert chars <= 0x7FFF
            body += struct.pack("<H", chars)
            body += enc
            body += b"\x00\x00"

    # 池整体按 4 字节对齐（与 aapt 一致）
    while len(body) % 4 != 0:
        body.append(0)

    size = strings_start + len(body)
    header = bytearray(header_size)
    p16(header, 0, 0x0001)
    p16(header, 2, header_size)
    p32(header, 4, size)
    p32(header, 8, count)
    p32(header, 12, 0)
    p32(header, 16, 0x100 if utf8 else 0)
    p32(header, 20, strings_start)
    p32(header, 24, 0)

    out = bytearray(header)
    for offset in offsets:
        out += struct.pack("<I", offset)
    out += body
    return bytes(out)


def start_element(name_idx, attrs, ns_idx=0xFFFFFFFF):
    """attrs: [(ns_idx, name_idx, raw_idx, data_type, data)]"""
    body = bytearray()
    body += struct.pack("<II", 0xFFFFFFFF, 0xFFFFFFFF)  # lineNumber, comment
    body += struct.pack("<II", ns_idx, name_idx)
    body += struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0)
    for (a_ns, a_name, a_raw, a_type, a_data) in attrs:
        body += struct.pack("<III", a_ns, a_name, a_raw)
        body += struct.pack("<HBB", 8, 0, a_type)
        body += struct.pack("<I", a_data)

    size = 8 + len(body)
    header = struct.pack("<HHI", 0x0102, 16, size)
    return header + bytes(body)


def start_namespace(prefix_idx, uri_idx):
    header = struct.pack("<HHI", 0x0100, 16, 24)
    return header + struct.pack("<II", 0xFFFFFFFF, 0xFFFFFFFF) + struct.pack("<II", prefix_idx, uri_idx)


def end_namespace(prefix_idx, uri_idx):
    header = struct.pack("<HHI", 0x0101, 16, 24)
    return header + struct.pack("<II", 0xFFFFFFFF, 0xFFFFFFFF) + struct.pack("<II", prefix_idx, uri_idx)


def end_element(name_idx, ns_idx=0xFFFFFFFF):
    header = struct.pack("<HHI", 0x0103, 16, 24)
    return header + struct.pack("<II", 0xFFFFFFFF, 0xFFFFFFFF) + struct.pack("<II", ns_idx, name_idx)


def resource_map(string_count):
    header = struct.pack("<HHI", 0x0180, 8, 8 + 4 * string_count)
    body = b"".join(struct.pack("<I", 0x01010000 + i) for i in range(string_count))
    return header + body


def build_axml(pkg, utf8=True):
    strings = [
        "android",
        "http://schemas.android.com/apk/res/android",
        "manifest",
        "package",
        pkg,
        "uses-permission",
        "name",
        pkg + ".permission.PUSH",
        "application",
        "provider",
        "authorities",
        pkg + ".fileprovider",
        "activity",
        "taskAffinity",
        "com.example.MainActivity",
        "versionCode",
        "1",
    ]
    idx = {s: i for i, s in enumerate(strings)}

    chunks = bytearray()
    chunks += resource_map(len(strings))
    chunks += start_namespace(idx["android"], idx["http://schemas.android.com/apk/res/android"])

    # <manifest package="..." >
    chunks += start_element(
        idx["manifest"],
        [
            (0xFFFFFFFF, idx["package"], idx[pkg], 0x03, idx[pkg]),
            (idx["android"], idx["versionCode"], 0xFFFFFFFF, 0x10, 1),
        ],
    )

    # <uses-permission android:name="pkg.permission.PUSH" />
    perm = pkg + ".permission.PUSH"
    chunks += start_element(
        idx["uses-permission"],
        [(idx["android"], idx["name"], idx[perm], 0x03, idx[perm])],
    )
    chunks += end_element(idx["uses-permission"])

    # <application>
    chunks += start_element(
        idx["application"],
        [(idx["android"], idx["taskAffinity"], idx[pkg], 0x03, idx[pkg])],
    )

    # <provider android:authorities="pkg.fileprovider" />
    auth = pkg + ".fileprovider"
    chunks += start_element(
        idx["provider"],
        [(idx["android"], idx["authorities"], idx[auth], 0x03, idx[auth])],
    )
    chunks += end_element(idx["provider"])

    # <activity android:name="com.example.MainActivity" />
    chunks += start_element(
        idx["activity"],
        [(idx["android"], idx["name"], idx["com.example.MainActivity"], 0x03, idx["com.example.MainActivity"])],
    )
    chunks += end_element(idx["activity"])

    chunks += end_element(idx["application"])
    chunks += end_element(idx["manifest"])
    chunks += end_namespace(idx["android"], idx["http://schemas.android.com/apk/res/android"])

    pool = build_string_pool(strings, utf8)
    total = 8 + len(pool) + len(chunks)
    header = struct.pack("<HHI", 0x0003, 8, total)
    return header + pool + bytes(chunks)


# ---------------------------------------------------------------- 独立 AXML 解析器


def decode_pool_string(data, base, utf8):
    """UTF-8 池：UTF-16 字符长度 + UTF-8 字节长度 + 数据 + 0x00；
    UTF-16 池：字符长度 + 数据 + 0x0000。"""
    pos = base
    if utf8:
        char_len = u8(data, pos)
        pos += 1
        if char_len & 0x80:
            char_len = ((char_len & 0x7F) << 8) | u8(data, pos)
            pos += 1

        byte_len = u8(data, pos)
        pos += 1
        if byte_len & 0x80:
            byte_len = ((byte_len & 0x7F) << 8) | u8(data, pos)
            pos += 1

        return bytes(data[pos:pos + byte_len]).decode("utf-8")

    length = u16(data, pos)
    pos += 2
    if length & 0x8000:
        length = ((length & 0x7FFF) << 16) | u16(data, pos)
        pos += 2
    return bytes(data[pos:pos + length * 2]).decode("utf-16-le")


def parse_axml(data):
    assert u16(data, 0) == 0x0003, "根 chunk 不是 XML"
    assert i32(data, 4) == len(data), "XML 头 size 与文件长度不一致"

    pool_off = 8
    assert u16(data, pool_off) == 0x0001, "第一个 chunk 不是字符串池"
    hs = u16(data, pool_off + 2)
    count = i32(data, pool_off + 8)
    assert i32(data, pool_off + 12) == 0
    utf8 = bool(i32(data, pool_off + 16) & 0x100)
    strings_start = i32(data, pool_off + 20)
    base = pool_off + strings_start

    strings = []
    for i in range(count):
        rel = i32(data, pool_off + hs + i * 4)
        assert 0 <= rel < len(data) - base, "字符串偏移越界"
        strings.append(decode_pool_string(data, base + rel, utf8))

    pool_size = i32(data, pool_off + 4)
    assert count == len(strings)

    elements = []
    pos = pool_off + pool_size
    while pos + 8 <= len(data):
        ctype = u16(data, pos)
        chs = u16(data, pos + 2)
        csize = i32(data, pos + 4)
        assert csize >= 8 and pos + csize <= len(data), "chunk 越界 @%d" % pos
        if ctype == 0x0102:
            name_idx = i32(data, pos + 20)
            attr_start = u16(data, pos + 24)
            attr_size = u16(data, pos + 26)
            attr_count = u16(data, pos + 28)
            assert attr_size == 20
            attr_base = pos + chs + attr_start
            attrs = {}
            raw_attrs = []
            for a in range(attr_count):
                off = attr_base + a * 20
                ns = i32(data, off)
                an = i32(data, off + 4)
                raw = i32(data, off + 8)
                dtype = u8(data, off + 15)
                dval = i32(data, off + 16)
                value = None
                if 0 <= raw < len(strings):
                    value = strings[raw]
                elif dtype == 0x03 and 0 <= dval < len(strings):
                    value = strings[dval]
                attrs[strings[an]] = value
                raw_attrs.append((ns, an, value))
            elements.append((strings[name_idx], attrs, raw_attrs))
        pos += csize

    assert pos == len(data), "chunk 遍历未正好结束（pos=%d, len=%d）" % (pos, len(data))
    return strings, elements


# ---------------------------------------------------------------- AXML 改写器（Kotlin 移植）


class AxmlEditor:
    PERMISSION_ELEMENTS = {
        "uses-permission", "uses-permission-sdk-23", "permission",
        "permission-group", "permission-tree",
    }
    AFFINITY_ELEMENTS = {
        "application", "activity", "activity-alias", "service", "receiver", "provider",
    }
    COMPONENT_ELEMENTS = {
        "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation",
    }
    CLASS_NAME_ATTRIBUTES = {
        "name", "targetActivity", "backupAgent", "manageSpaceActivity", "appComponentFactory",
    }

    def __init__(self, data):
        self.data = bytes(data)
        self.strings = []
        self.utf8 = False
        self.pool_end = 0
        self._parse()

    def _parse(self):
        assert u16(self.data, 0) == 0x0003
        assert u16(self.data, 8) == 0x0001
        off = 8
        hs = u16(self.data, off + 2)
        size = i32(self.data, off + 4)
        count = i32(self.data, off + 8)
        style_count = i32(self.data, off + 12)
        flags = i32(self.data, off + 16)
        strings_start = i32(self.data, off + 20)
        assert style_count == 0
        self.utf8 = bool(flags & 0x100)
        base = off + strings_start
        for i in range(count):
            rel = i32(self.data, off + hs + i * 4)
            self.strings.append(decode_pool_string(self.data, base + rel, self.utf8))
        self.pool_end = off + size

    def _attr_string(self, attr_off):
        raw = i32(self.data, attr_off + 8)
        if 0 <= raw < len(self.strings):
            return self.strings[raw]
        if u8(self.data, attr_off + 15) != 0x03:
            return None
        typed = i32(self.data, attr_off + 16)
        return self.strings[typed] if 0 <= typed < len(self.strings) else None

    def _map_value(self, element, attribute, ns, value, old, new):
        if element == "manifest" and attribute == "package" and ns == -1:
            return new if value in (old, "") else None
        if element in self.PERMISSION_ELEMENTS and attribute == "name":
            return self._with_prefix(value, old, new)
        if element == "provider" and attribute == "authorities":
            return self._replace_authorities(value, old, new)
        if element in self.AFFINITY_ELEMENTS and attribute == "taskAffinity":
            return self._with_prefix(value, old, new)
        # 相对写法（.MainActivity）必须补全为旧包名，否则会去新包名找类
        if element in self.COMPONENT_ELEMENTS and attribute in self.CLASS_NAME_ATTRIBUTES and value.startswith("."):
            return old + value
        return None

    @staticmethod
    def _with_prefix(value, old, new):
        if value == old:
            return new
        if value.startswith(old + "."):
            return new + value[len(old):]
        return None

    def _replace_authorities(self, value, old, new):
        if old not in value:
            return None
        changed = False
        parts = []
        for part in value.split(";"):
            replaced = self._with_prefix(part, old, new)
            if replaced is not None:
                changed = True
                parts.append(replaced)
            else:
                parts.append(part)
        return ";".join(parts) if changed else None

    def _collect_edits(self, old, new):
        edits = []
        element_order = 0
        pos = 8
        while pos + 8 <= len(self.data):
            ctype = u16(self.data, pos)
            size = i32(self.data, pos + 4)
            if size < 8 or pos + size > len(self.data):
                break
            if ctype == 0x0102:
                hs = u16(self.data, pos + 2)
                name_idx = i32(self.data, pos + 20)
                if 0 <= name_idx < len(self.strings):
                    element = self.strings[name_idx]
                    attr_start = u16(self.data, pos + 24)
                    attr_count = u16(self.data, pos + 28)
                    attr_base = pos + hs + attr_start
                    for a in range(attr_count):
                        off = attr_base + a * 20
                        ns = i32(self.data, off)
                        an = i32(self.data, off + 4)
                        if not (0 <= an < len(self.strings)):
                            continue
                        value = self._attr_string(off)
                        if value is None:
                            continue
                        updated = self._map_value(element, self.strings[an], ns, value, old, new)
                        if updated is not None:
                            edits.append((element_order, a, updated))
                element_order += 1
            pos += size
        return edits

    def _intern(self, strings, value):
        if value in strings:
            return strings.index(value)
        strings.append(value)
        return len(strings) - 1

    def rename_package(self, old, new):
        edits = self._collect_edits(old, new)
        if not edits:
            return self.data

        strings = list(self.strings)
        resolved = [(e, a, self._intern(strings, v)) for (e, a, v) in edits]
        pool = build_string_pool(strings, self.utf8)

        result = bytearray(struct.pack("<HHI", 0x0003, 8, 0))
        result += pool
        result += self.data[self.pool_end:]
        p32(result, 4, len(result))

        grouped = {}
        for (e, a, s) in resolved:
            grouped.setdefault(e, []).append((a, s))

        element_order = 0
        pos = 8
        while pos + 8 <= len(result):
            ctype = u16(result, pos)
            size = i32(result, pos + 4)
            if size < 8 or pos + size > len(result):
                break
            if ctype == 0x0102:
                if element_order in grouped:
                    hs = u16(result, pos + 2)
                    attr_start = u16(result, pos + 24)
                    attr_base = pos + hs + attr_start
                    for (a, s) in grouped[element_order]:
                        off = attr_base + a * 20
                        p32(result, off + 8, s)
                        p32(result, off + 16, s)
                        result[off + 15] = 0x03
                element_order += 1
            pos += size
        return bytes(result)


# ---------------------------------------------------------------- ARSC


def build_arsc(strings, utf8=True):
    pool = build_string_pool(strings, utf8)
    # 伪造一个 package chunk，只用来验证“原样搬运”
    package = struct.pack("<HHI", 0x0200, 0x11C, 0x11C) + bytes(0x11C - 8)
    total = 12 + len(pool) + len(package)
    header = struct.pack("<HHI", 0x0002, 12, total) + struct.pack("<I", 1)
    return header + pool + package


class ArscEditor:
    def __init__(self, data):
        self.data = bytes(data)
        self.strings = []
        self.utf8 = False
        self.pool_start = 12
        self.pool_end = 0
        self._parse()

    def _parse(self):
        assert u16(self.data, 0) == 0x0002
        self.pool_start = u16(self.data, 2)
        assert u16(self.data, self.pool_start) == 0x0001
        off = self.pool_start
        hs = u16(self.data, off + 2)
        size = i32(self.data, off + 4)
        count = i32(self.data, off + 8)
        assert i32(self.data, off + 12) == 0
        self.utf8 = bool(i32(self.data, off + 16) & 0x100)
        base = off + i32(self.data, off + 20)
        for i in range(count):
            rel = i32(self.data, off + hs + i * 4)
            self.strings.append(decode_pool_string(self.data, base + rel, self.utf8))
        self.pool_end = off + size

    @staticmethod
    def _map(value, old, new):
        if value == old:
            return new
        if not value.startswith(old + "."):
            return None
        rest = value[len(old) + 1:]
        last = rest.rsplit(".", 1)[-1]
        if last and last[0].isupper():
            return None
        return new + "." + rest

    def replace(self, old, new):
        updated = []
        changed = False
        for value in self.strings:
            mapped = self._map(value, old, new)
            if mapped is None:
                updated.append(value)
            else:
                changed = True
                updated.append(mapped)
        if not changed:
            return self.data

        pool = build_string_pool(updated, self.utf8)
        result = bytearray(self.data[:self.pool_start])
        result += pool
        result += self.data[self.pool_end:]
        p32(result, 4, len(result))
        return bytes(result)


# ---------------------------------------------------------------- DEX


def mutf8_encode(text):
    out = bytearray()
    for ch in text:
        code = ord(ch)
        if code == 0:
            out += b"\xc0\x80"
        elif code < 0x80:
            out.append(code)
        elif code < 0x800:
            out.append(0xC0 | (code >> 6))
            out.append(0x80 | (code & 0x3F))
        else:
            out.append(0xE0 | (code >> 12))
            out.append(0x80 | ((code >> 6) & 0x3F))
            out.append(0x80 | (code & 0x3F))
    return bytes(out)


def mutf8_decode(data, start, end):
    out = []
    i = start
    while i < end:
        first = data[i]
        if first < 0x80:
            out.append(chr(first))
            i += 1
        elif 0xC0 <= first <= 0xDF and i + 1 < end:
            out.append(chr(((first & 0x1F) << 6) | (data[i + 1] & 0x3F)))
            i += 2
        elif 0xE0 <= first <= 0xEF and i + 2 < end:
            out.append(chr(((first & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)))
            i += 3
        else:
            out.append("\ufffd")
            i += 1
    return "".join(out)


def uleb_encode(value):
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def build_dex(strings):
    count = len(strings)
    string_ids_off = 0x70
    data_off = string_ids_off + 4 * count
    body = bytearray()
    offsets = []
    for text in strings:
        offsets.append(data_off + len(body))
        body += uleb_encode(len(text))
        body += mutf8_encode(text)
        body.append(0)

    total = data_off + len(body)
    header = bytearray(0x70)
    header[0:8] = b"dex\n035\0"
    p32(header, 32, total)
    p32(header, 36, 0x70)
    p32(header, 40, 0x12345678)
    p32(header, 56, count)
    p32(header, 60, string_ids_off)
    p32(header, 104, len(body))
    p32(header, 108, data_off)

    ids = b"".join(struct.pack("<I", o) for o in offsets)
    data = bytes(header) + ids + bytes(body)
    return finalize_dex(data)


def finalize_dex(data):
    buf = bytearray(data)
    # 顺序必须是先签名后校验和：checksum 覆盖签名之后的全部字节
    digest = hashlib.sha1(bytes(buf[32:])).digest()
    buf[12:32] = digest
    adler = zlib.adler32(bytes(buf[12:])) & 0xFFFFFFFF
    p32(buf, 8, adler)
    return bytes(buf)


def dex_strings(data):
    count = i32(data, 56)
    ids_off = i32(data, 60)
    result = []
    for i in range(count):
        off = i32(data, ids_off + 4 * i)
        pos = off
        while data[pos] & 0x80:
            pos += 1
        pos += 1
        end = pos
        while data[end] != 0:
            end += 1
        result.append(mutf8_decode(data, pos, end))
    return result


class DexEditor:
    def __init__(self, data):
        self.data = bytes(data)
        assert data[0:3] == b"dex"

    @staticmethod
    def _map(value, old, new):
        if value == old:
            return new
        if not value.startswith(old + "."):
            return None
        if value.startswith("L" + old + "/"):
            return None
        rest = value[len(old) + 1:]
        last = rest.rsplit(".", 1)[-1]
        if last and last[0].isupper():
            return None
        return new + "." + rest

    def replace(self, old, new):
        size = i32(self.data, 56)
        ids_off = i32(self.data, 60)
        tail = bytearray()
        new_offsets = [-1] * size

        for i in range(size):
            off = i32(self.data, ids_off + 4 * i)
            if off <= 0 or off >= len(self.data):
                continue
            pos = off
            while self.data[pos] & 0x80:
                pos += 1
            pos += 1
            end = pos
            while self.data[end] != 0:
                end += 1
            value = mutf8_decode(self.data, pos, end)

            mapped = self._map(value, old, new)
            if mapped is None:
                continue
            new_offsets[i] = len(self.data) + len(tail)
            tail += uleb_encode(len(mapped))
            tail += mutf8_encode(mapped)
            tail.append(0)

        if not any(o >= 0 for o in new_offsets):
            return self.data

        buf = bytearray(self.data) + tail
        for i, off in enumerate(new_offsets):
            if off >= 0:
                p32(buf, ids_off + 4 * i, off)

        file_size = len(buf)
        data_off = i32(buf, 108)
        p32(buf, 32, file_size)
        p32(buf, 104, file_size - data_off)
        return finalize_dex(bytes(buf))


# ---------------------------------------------------------------- 校验


def check_axml_case(utf8):
    old = "com.aligames.kuang.kybc"
    new = "com.aligames.kuang.kybd.v2"  # 故意变长
    original = build_axml(old, utf8=utf8)

    strings_before, elements_before = parse_axml(original)
    editor = AxmlEditor(original)
    assert editor.rename_package(old, new) != original

    result = editor.rename_package(old, new)
    strings_after, elements_after = parse_axml(result)

    manifest = [e for e in elements_after if e[0] == "manifest"][0]
    assert manifest[1]["package"] == new, manifest[1]["package"]

    permission = [e for e in elements_after if e[0] == "uses-permission"][0]
    assert permission[1]["name"] == new + ".permission.PUSH", permission[1]["name"]

    provider = [e for e in elements_after if e[0] == "provider"][0]
    assert provider[1]["authorities"] == new + ".fileprovider", provider[1]["authorities"]

    application = [e for e in elements_after if e[0] == "application"][0]
    assert application[1]["taskAffinity"] == new, application[1]["taskAffinity"]

    activity = [e for e in elements_after if e[0] == "activity"][0]
    assert activity[1]["name"] == "com.example.MainActivity", activity[1]["name"]

    # 未命中的字符串必须原样保留
    untouched = [s for s in strings_before if old not in s]
    for text in untouched:
        assert text in strings_after, "丢失字符串: " + text

    assert i32(result, 4) == len(result)
    return len(result)


def build_axml_rich(pkg, utf8=True):
    """更接近真实 aapt 产物：资源映射比字符串少、含 int/引用/布尔属性、非 ASCII 文本。"""
    strings = [
        "android",
        "http://schemas.android.com/apk/res/android",
        "manifest",
        "package",
        pkg,
        "versionCode",
        "versionName",
        "1.0",
        "application",
        "label",
        "狂野飙车",
        "debuggable",
        "uses-permission",
        "name",
        pkg + ".permission.PUSH",
        ".App",
    ]
    index = {s: i for i, s in enumerate(strings)}

    chunks = bytearray()
    chunks += resource_map(4)
    chunks += start_namespace(index["android"], index["http://schemas.android.com/apk/res/android"])
    chunks += start_element(
        index["manifest"],
        [
            (index["android"], index["versionCode"], NO_INDEX, 0x10, 5),
            (NO_INDEX, index["package"], index[pkg], 0x03, index[pkg]),
            (index["android"], index["versionName"], index["1.0"], 0x03, index["1.0"]),
        ],
    )
    chunks += start_element(
        index["application"],
        [
            (index["android"], index["debuggable"], NO_INDEX, 0x12, 0xFFFFFFFF),
            (index["android"], index["label"], NO_INDEX, 0x01, 0x7F010001),
            (index["android"], index["name"], index[".App"], 0x03, index[".App"]),
        ],
    )
    chunks += start_element(
        index["uses-permission"],
        [(index["android"], index["name"], index[pkg + ".permission.PUSH"], 0x03, index[pkg + ".permission.PUSH"])],
    )
    chunks += end_element(index["uses-permission"])
    chunks += end_element(index["application"])
    chunks += end_element(index["manifest"])
    chunks += end_namespace(index["android"], index["http://schemas.android.com/apk/res/android"])

    pool = build_string_pool(strings, utf8)
    total = 8 + len(pool) + len(chunks)
    return struct.pack("<HHI", 0x0003, 8, total) + pool + bytes(chunks)


def parse_axml_typed(data):
    """返回 (字符串池, [(元素名, {属性名: (类型, 数值, 文本)})])。"""
    pool_off = 8
    count = i32(data, pool_off + 8)
    header_size = u16(data, pool_off + 2)
    utf8 = bool(i32(data, pool_off + 16) & 0x100)
    base = pool_off + i32(data, pool_off + 20)
    strings = [
        decode_pool_string(data, base + i32(data, pool_off + header_size + i * 4), utf8)
        for i in range(count)
    ]

    pos = pool_off + i32(data, pool_off + 4)
    elements = []
    while pos + 8 <= len(data):
        chunk_type = u16(data, pos)
        chunk_header = u16(data, pos + 2)
        chunk_size = i32(data, pos + 4)
        if chunk_size < 8 or pos + chunk_size > len(data):
            break
        if chunk_type == 0x0102:
            name = strings[i32(data, pos + 20)]
            attr_start = u16(data, pos + 24)
            attr_count = u16(data, pos + 28)
            attr_base = pos + chunk_header + attr_start
            attrs = {}
            for a in range(attr_count):
                off = attr_base + a * 20
                attr_name = strings[i32(data, off + 4)]
                data_type = u8(data, off + 15)
                data_value = i32(data, off + 16)
                text = strings[data_value] if data_type == 0x03 and 0 <= data_value < len(strings) else None
                attrs[attr_name] = (data_type, data_value, text)
            elements.append((name, attrs))
        pos += chunk_size
    return strings, elements


def check_axml_rich(utf8):
    old = "com.aligames.kuang.kybc"
    new = "com.aligames.kuang.kybd"
    original = build_axml_rich(old, utf8)
    result = AxmlEditor(original).rename_package(old, new)

    strings, elements = parse_axml_typed(result)
    table = {}
    for name, attrs in elements:
        table.setdefault(name, {}).update(attrs)

    assert table["manifest"]["package"][2] == new, table["manifest"]["package"]
    assert table["manifest"]["versionCode"] == (0x10, 5, None), table["manifest"]["versionCode"]
    assert table["manifest"]["versionName"][2] == "1.0"

    assert table["application"]["label"] == (0x01, 0x7F010001, None), table["application"]["label"]
    assert table["application"]["debuggable"] == (0x12, -1, None), table["application"]["debuggable"]
    assert table["application"]["name"][2] == old + ".App", table["application"]["name"]

    assert table["uses-permission"]["name"][2] == new + ".permission.PUSH"

    assert "狂野飙车" in strings, "非 ASCII 字符串被破坏"

    # 资源映射 chunk 必须原样保留（长度比字符串池少的情况）
    pool_size = i32(result, 12)
    map_off = 8 + pool_size
    assert u16(result, map_off) == 0x0180, "资源映射 chunk 丢失"
    assert i32(result, map_off + 4) == 24, i32(result, map_off + 4)
    return len(result)


def parse_pool_at(data, off):
    """解析任意位置的字符串池，返回字符串列表。"""
    count = i32(data, off + 8)
    header_size = u16(data, off + 2)
    utf8 = bool(i32(data, off + 16) & 0x100)
    base = off + i32(data, off + 20)
    return [
        decode_pool_string(data, base + i32(data, off + header_size + i * 4), utf8)
        for i in range(count)
    ]


def build_package_chunk(pkg_id, name):
    """构造一个带 type/key 字符串池的 ResTable_package chunk。"""
    type_pool = build_string_pool(["attr", "string", "drawable"], False)
    key_pool = build_string_pool(["app_name", "config"], False)
    header_size = 0x120

    chunk = bytearray(header_size)
    p16(chunk, 0, 0x0200)
    p16(chunk, 2, header_size)
    p32(chunk, 4, header_size + len(type_pool) + len(key_pool))
    p32(chunk, 8, pkg_id)
    name_bytes = name.encode("utf-16-le")[:254]
    chunk[12:12 + len(name_bytes)] = name_bytes
    p32(chunk, 268, header_size)                 # typeStrings（相对 package 起点）
    p32(chunk, 272, 0)                           # lastPublicType
    p32(chunk, 276, header_size + len(type_pool))  # keyStrings（相对 package 起点）
    p32(chunk, 280, 0)                           # lastPublicKey
    p32(chunk, 284, 0)                           # typeIdOffset
    return bytes(chunk) + type_pool + key_pool


def build_arsc_rich(strings, utf8=True):
    """两个 package chunk，各自带 type/key 池，贴近真实资源表结构。"""
    pool = build_string_pool(strings, utf8)
    pkg0 = build_package_chunk(0x7F, "com.aligames.kuang.kybc")
    pkg1 = build_package_chunk(0x80, "com.other.lib")
    total = 12 + len(pool) + len(pkg0) + len(pkg1)
    return struct.pack("<HHI", 0x0002, 12, total) + struct.pack("<I", 2) + pool + pkg0 + pkg1


def check_arsc_rich(utf8):
    old = "com.aligames.kuang.kybc"
    new = "com.aligames.kuang.kybd.v2"
    strings = [old, "hello", old + ".channel", "app_name", "中文标签"]
    original = build_arsc_rich(strings, utf8)
    result = ArscEditor(original).replace(old, new)

    editor = ArscEditor(result)
    assert editor.strings[0] == new, editor.strings[0]
    assert editor.strings[1] == "hello"
    assert editor.strings[2] == new + ".channel", editor.strings[2]
    assert editor.strings[4] == "中文标签"

    # 全局池之后的所有 chunk（两个 package）必须逐字节不变
    old_pool_end = 12 + i32(original, 16)
    new_pool_end = 12 + i32(result, 16)
    assert original[old_pool_end:] == result[new_pool_end:], "package chunk 被破坏"

    # 两个 package 的内部字符串池仍可正常解析
    pos = new_pool_end
    for expected_id in (0x7F, 0x80):
        assert u16(result, pos) == 0x0200, "package chunk 类型错"
        assert i32(result, pos + 8) == expected_id, "package id 错"
        type_pool = parse_pool_at(result, pos + i32(result, pos + 268))
        key_pool = parse_pool_at(result, pos + i32(result, pos + 276))
        assert type_pool == ["attr", "string", "drawable"], type_pool
        assert key_pool == ["app_name", "config"], key_pool
        pos += i32(result, pos + 4)

    assert pos == len(result), "package chunk 未正好铺满文件"
    assert i32(result, 8) == 2, "packageCount 被改"
    return len(result)


def check_arsc_case():
    old = "com.aligames.kuang.kybc"
    new = "com.aligames.kuang.kybd.v2"
    strings = [old, "hello", old + ".channel", "com.old.SomeClass", old + ".SomeActivity",
               "中文标签", "x" + old, old + "2"]
    original = build_arsc(strings)
    result = ArscEditor(original).replace(old, new)

    editor = ArscEditor(result)
    got = editor.strings
    assert got[0] == new, got[0]
    assert got[1] == "hello"
    assert got[2] == new + ".channel", got[2]
    assert got[3] == "com.old.SomeClass"
    assert got[4] == old + ".SomeActivity", got[4]
    assert got[5] == "中文标签"
    assert got[6] == "x" + old, got[6]
    assert got[7] == old + "2", got[7]
    assert i32(result, 4) == len(result)
    assert i32(result, 8) == 1
    return len(result)


def check_dex_case():
    old = "com.aligames.kuang.kybc"
    new = "com.aligames.kuang.kybd.v2"
    strings = [
        old,
        "hello",
        old + ".channel",
        "L" + old.replace(".", "/") + "/Main;",
        "com.old.SomeClass",
        "中文标签",
        "prefix" + old,
    ]
    original = build_dex(strings)
    result = DexEditor(original).replace(old, new)

    assert i32(result, 32) == len(result)
    assert i32(result, 104) == len(result) - i32(result, 108)
    assert (zlib.adler32(result[12:]) & 0xFFFFFFFF) == u32_checksum(result)
    assert hashlib.sha1(result[32:]).digest() == bytes(result[12:32])

    got = dex_strings(result)
    assert got[0] == new, got[0]
    assert got[1] == "hello"
    assert got[2] == new + ".channel", got[2]
    assert got[3] == "L" + old.replace(".", "/") + "/Main;", got[3]
    assert got[4] == "com.old.SomeClass", got[4]
    assert got[5] == "中文标签", got[5]
    assert got[6] == "prefix" + old, got[6]
    return len(result)


def u32_checksum(data):
    return struct.unpack_from("<I", data, 8)[0]


def main():
    failures = 0
    for utf8 in (True, False):
        try:
            size = check_axml_case(utf8)
            print("[OK] AXML(utf8=%s) 变长改包 -> %d bytes" % (utf8, size))
        except AssertionError as exc:
            failures += 1
            print("[FAIL] AXML(utf8=%s): %s" % (utf8, exc))

    for utf8 in (True, False):
        try:
            size = check_axml_rich(utf8)
            print("[OK] AXML(utf8=%s) 非字符串属性/资源引用/中文不被误改 -> %d bytes" % (utf8, size))
        except AssertionError as exc:
            failures += 1
            print("[FAIL] AXML-rich(utf8=%s): %s" % (utf8, exc))

    try:
        size = check_arsc_case()
        print("[OK] ARSC 变长替换 -> %d bytes" % size)
    except AssertionError as exc:
        failures += 1
        print("[FAIL] ARSC: %s" % exc)

    for utf8 in (True, False):
        try:
            size = check_arsc_rich(utf8)
            print("[OK] ARSC(utf8=%s) 多 package/type/key 池未被破坏 -> %d bytes" % (utf8, size))
        except AssertionError as exc:
            failures += 1
            print("[FAIL] ARSC-rich(utf8=%s): %s" % (utf8, exc))

    try:
        size = check_dex_case()
        print("[OK] DEX 变长替换 -> %d bytes" % size)
    except AssertionError as exc:
        failures += 1
        print("[FAIL] DEX: %s" % exc)

    if failures:
        print("\n%d 个用例失败" % failures)
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
