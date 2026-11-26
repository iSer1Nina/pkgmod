#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查一个真实 APK 的「改包名」可行性，并可做一次内存内的改包预演。

用法:
    python tools/inspect_apk.py app.apk
    python tools/inspect_apk.py app.apk com.aligames.kuang.kybd
    python tools/inspect_apk.py app.apk com.aligames.kuang.kybd --out patched.apk

说明:
- 只做只读分析；加 --out 才会写出一个改过的 zip（未签名、未对齐，仅供结构校验）。
- 复用 verify_patchers.py 里的 AXML / ARSC / DEX 实现，也就是 Kotlin 逻辑的 Python 镜像。
  拿真实 APK 跑一遍，能验证真实 aapt2 产物的边界情况（UTF-8/UTF-16 混合字符串池、
  多 package 资源表、资源引用型属性等）。
"""

import argparse
import os
import shutil
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_patchers as vp  # noqa: E402

MANIFEST = "AndroidManifest.xml"
ARSC = "resources.arsc"
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


# ---------------------------------------------------------------- 清单解析


def iter_elements(data):
    """逐个产出 (元素名, [(属性名, 类型, 数值, 字符串值)])。"""
    pool_off = 8
    count = vp.i32(data, pool_off + 8)
    header_size = vp.u16(data, pool_off + 2)
    utf8 = bool(vp.i32(data, pool_off + 16) & 0x100)
    strings_start = vp.i32(data, pool_off + 20)
    base = pool_off + strings_start
    strings = []
    for i in range(count):
        rel = vp.i32(data, pool_off + header_size + i * 4)
        strings.append(vp.decode_pool_string(data, base + rel, utf8))

    pos = pool_off + vp.i32(data, pool_off + 4)
    while pos + 8 <= len(data):
        chunk_type = vp.u16(data, pos)
        chunk_header = vp.u16(data, pos + 2)
        chunk_size = vp.i32(data, pos + 4)
        if chunk_size < 8 or pos + chunk_size > len(data):
            break
        if chunk_type == 0x0102:
            name = strings[vp.i32(data, pos + 20)]
            attr_start = vp.u16(data, pos + 24)
            attr_count = vp.u16(data, pos + 28)
            attr_base = pos + chunk_header + attr_start
            attrs = []
            for a in range(attr_count):
                off = attr_base + a * 20
                attr_name = strings[vp.i32(data, off + 4)]
                raw = vp.i32(data, off + 8)
                data_type = vp.u8(data, off + 15)
                data_value = vp.i32(data, off + 16)
                text = None
                if 0 <= raw < len(strings):
                    text = strings[raw]
                elif data_type == 0x03 and 0 <= data_value < len(strings):
                    text = strings[data_value]
                attrs.append((attr_name, data_type, data_value, text))
            yield name, attrs
        pos += chunk_size


def summarize_manifest(data):
    package = None
    split = None
    min_sdk = None
    target_sdk = None
    permissions = []
    authorities = []
    affinities = []

    for element, attrs in iter_elements(data):
        lookup = {a[0]: a for a in attrs}
        if element == "manifest":
            if "package" in lookup and lookup["package"][3]:
                package = lookup["package"][3]
            if "split" in lookup and lookup["split"][3]:
                split = lookup["split"][3]
        elif element == "uses-sdk":
            if "minSdkVersion" in lookup:
                min_sdk = lookup["minSdkVersion"][2]
            if "targetSdkVersion" in lookup:
                target_sdk = lookup["targetSdkVersion"][2]
        elif element in ("uses-permission", "uses-permission-sdk-23", "permission"):
            if "name" in lookup and lookup["name"][3]:
                permissions.append(lookup["name"][3])
        elif element == "provider":
            if "authorities" in lookup and lookup["authorities"][3]:
                authorities.append(lookup["authorities"][3])
            if "taskAffinity" in lookup and lookup["taskAffinity"][3]:
                affinities.append(lookup["taskAffinity"][3])
        elif element in ("activity", "activity-alias", "service", "receiver", "application"):
            if "taskAffinity" in lookup and lookup["taskAffinity"][3]:
                affinities.append(lookup["taskAffinity"][3])

    return {
        "package": package,
        "split": split,
        "minSdk": min_sdk,
        "targetSdk": target_sdk,
        "permissions": permissions,
        "authorities": authorities,
        "affinities": affinities,
    }


def related_strings(strings, old_package, limit=12):
    exact = [s for s in strings if s == old_package]
    prefixed = [s for s in strings if s.startswith(old_package + ".")]
    return exact, prefixed[:limit], len(prefixed)


# ---------------------------------------------------------------- 报告


def report(apk_path, old_package_guess=None):
    print("== APK ==")
    print("文件:", apk_path)
    print("大小: %.2f MB" % (os.path.getsize(apk_path) / (1024.0 * 1024.0)))

    with zipfile.ZipFile(apk_path) as zf:
        names = zf.namelist()
        dex_names = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
        libs = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
        print("条目数:", len(names))
        print("dex:", ", ".join(dex_names) if dex_names else "无")
        print("native 库:", len(libs), "个" + ("（如 " + libs[0] + "）" if libs else ""))
        print("已签名文件:", sum(1 for n in names if n.startswith("META-INF/") and n.upper().endswith(SIGNATURE_SUFFIXES)))

        if MANIFEST not in names:
            print("!! 缺少 AndroidManifest.xml，不是可安装 APK")
            return None
        manifest_bytes = zf.read(MANIFEST)
        info = summarize_manifest(manifest_bytes)
        package = info["package"]
        print("\n== 清单 ==")
        print("包名:", package)
        if info["split"]:
            print("!! 这是分体 APK 的 split:", info["split"], "（需要完整安装包）")
        print("minSdk:", info["minSdk"], " targetSdk:", info["targetSdk"])

        related = [p for p in info["permissions"] if package and p.startswith(package + ".")]
        print("权限:", len(info["permissions"]), "个，其中以包名开头:", len(related))
        for p in related[:8]:
            print("   -", p)

        print("provider authorities:", len(info["authorities"]))
        for a in info["authorities"][:8]:
            print("   -", a)

        print("taskAffinity:", len(info["affinities"]))
        for a in info["affinities"][:8]:
            print("   -", a)

        if ARSC in names:
            arsc = vp.ArscEditor(zf.read(ARSC))
            strings = arsc.strings
            exact, prefixed, total = related_strings(strings, package) if package else ([], [], 0)
            print("\n== resources.arsc ==")
            print("字符串数:", len(strings), " 与包名相关:", len(exact) + total)
            for s in exact + prefixed:
                print("   -", s)
        else:
            print("\n!! 没有 resources.arsc")

        print("\n== dex 字符串 ==")
        total = 0
        for name in dex_names:
            data = zf.read(name)
            strings = vp.dex_strings(data)
            exact, prefixed, count = related_strings(strings, package) if package else ([], [], 0)
            total += len(exact) + count
            print("%s: %d 个字符串，与包名相关 %d" % (name, len(strings), len(exact) + count))
            for s in (exact + prefixed)[:8]:
                print("   -", s)
        print("合计相关 dex 字符串:", total)

    return package


# ---------------------------------------------------------------- 改包预演


def patch_bytes(apk_path, old_package, new_package):
    """返回 {条目名: 新字节}，并做一次重解析校验。"""
    replacements = {}
    with zipfile.ZipFile(apk_path) as zf:
        manifest = vp.AxmlEditor(zf.read(MANIFEST)).rename_package(old_package, new_package)
        replacements[MANIFEST] = manifest

        if ARSC in zf.namelist():
            replacements[ARSC] = vp.ArscEditor(zf.read(ARSC)).replace(old_package, new_package)

        for name in zf.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                replacements[name] = vp.DexEditor(zf.read(name)).replace(old_package, new_package)

    actual = summarize_manifest(replacements[MANIFEST])["package"]
    assert actual == new_package, "清单包名改写失败: %r" % actual

    arsc_strings = vp.ArscEditor(replacements[ARSC]).strings if ARSC in replacements else []
    assert old_package not in arsc_strings, "arsc 里仍有旧包名"
    return replacements


def write_patched(apk_path, replacements, out_path):
    with zipfile.ZipFile(apk_path) as src, zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            name = item.filename
            if name.startswith("META-INF/") and name.upper().endswith(SIGNATURE_SUFFIXES + (".MF",)):
                continue
            data = replacements.get(name)
            if data is None:
                data = src.read(name)
            dst.writestr(item, data)
    print("\n已写出:", out_path, "（未签名，仅供结构校验）")


def self_test():
    """用合成 APK 验证本脚本自身可用。"""
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp")
    work = os.path.join(base, "inspect-%d" % int(time.time() * 1000))
    os.makedirs(work, exist_ok=True)
    try:
        return _self_test_in(work)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _self_test_in(work):
    apk = os.path.join(work, "sample.apk")
    old = "com.aligames.kuang.kybc"
    new = "com.aligames.kuang.kybd"

    with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(MANIFEST, vp.build_axml(old, utf8=True))
        zf.writestr(ARSC, vp.build_arsc([old, "hello", old + ".channel"]))
        zf.writestr("classes.dex", vp.build_dex([old, "hello", old + ".channel"]))
        zf.writestr("lib/arm64-v8a/libfoo.so", b"\x7fELF")
        zf.writestr("META-INF/CERT.RSA", b"x")

    detected = report(apk)
    assert detected == old, detected

    replacements = patch_bytes(apk, old, new)
    patched = os.path.join(work, "patched.apk")
    write_patched(apk, replacements, patched)

    with zipfile.ZipFile(patched) as zf:
        assert summarize_manifest(zf.read(MANIFEST))["package"] == new
        assert old not in vp.ArscEditor(zf.read(ARSC)).strings
        assert old not in vp.dex_strings(zf.read("classes.dex"))
    print("\n自检通过")
    return 0


def main():
    parser = argparse.ArgumentParser(description="检查真实 APK 的改包可行性")
    parser.add_argument("apk", nargs="?")
    parser.add_argument("new_package", nargs="?")
    parser.add_argument("--out")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not args.apk:
        parser.print_help()
        return 2

    package = report(args.apk)
    if args.new_package:
        if not package:
            print("读不到原包名，无法预演")
            return 1
        print("\n== 改包预演: %s -> %s ==" % (package, args.new_package))
        try:
            replacements = patch_bytes(args.apk, package, args.new_package)
            print("清单/arsc/dex 改写并重解析通过")
            if args.out:
                write_patched(args.apk, replacements, args.out)
        except AssertionError as exc:
            print("预演失败:", exc)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
