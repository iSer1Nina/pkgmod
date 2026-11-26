# 包名改造器（PkgMod）

安卓端「按规则识别包名 → 自动修改 applicationId → 重签名 → 安装」的工具。无需 root，安装时手动点确认。

典型用法：规则命中 `com.aligames.kuang.kybc`，自动产出 `com.aligames.kuang.kybd` 的独立安装包（与原版共存）。

## 当前状态

全部核心模块已写完（首次编译可能与预期有出入，见下方"验证"）。

| 模块 | 状态 |
| --- | --- |
| 规则引擎（JSON 规则、匹配、替换） | ✅ 已实现 |
| 已安装应用扫描 + 命中高亮 | ✅ 已实现 |
| 二进制 AndroidManifest（AXML）包名改写 | ✅ 已实现（支持变长） |
| resources.arsc 字符串改写 | ✅ 已实现 |
| DEX 字符串改写（追加 string_data + 重算校验） | ✅ 已实现 |
| ZIP 重打包 + 对齐（arsc 不压缩、.so 16KB 对齐） | ✅ 已实现 |
| apksig 重签名（v1+v2+v3） | ✅ 已实现 |
| PackageInstaller 安装 + 结果回调 | ✅ 已实现 |

## 验证

本机没有 JDK / Android SDK / 外网，代码是用 AOSP 头文件核对格式后手写的，**尚未经过编译与真机验证**。
第一次 GitHub Actions 构建如果报编译错误，把日志贴给我，我来逐条修。

已经做了的离线验证：`tools/verify_patchers.py` 用合成数据把 AXML / ARSC / DEX 三个改写器的
二进制逻辑完整跑一遍，再用独立解析器校验产物结构（chunk 尺寸、字符串池偏移、checksum、SHA-1）。

```bash
python3 tools/verify_patchers.py
# [OK] AXML(utf8=True) 变长改包
# [OK] AXML(utf8=False) 变长改包
# [OK] AXML(utf8=True) 非字符串属性/资源引用/中文不被误改
# [OK] AXML(utf8=False) 非字符串属性/资源引用/中文不被误改
# [OK] ARSC 变长替换
# [OK] ARSC(utf8=True) 多 package/type/key 池未被破坏
# [OK] ARSC(utf8=False) 多 package/type/key 池未被破坏
# [OK] DEX 变长替换
```

这套验证已经抓出并修掉三个真实 bug：`attributeCount` 偏移读错（+26 应为 +28）、
DEX 的 checksum 与 signature 计算顺序写反、`Res_value.dataType` 偏移读错（+14 是 `res0`，应为 +15）。
CI 里也会跑这个脚本。

拿到真实 APK 后，可以先做一次只读体检 + 改包预演（不需要 JDK、不需要设备）：

```bash
python3 tools/inspect_apk.py base.apk
python3 tools/inspect_apk.py base.apk com.aligames.kuang.kybd --out patched.apk
```

会打印包名、minSdk/targetSdk、权限/provider/taskAffinity、`resources.arsc` 与 `classes*.dex` 里
所有与包名相关的字符串；指定新包名后会在内存中跑一遍 AXML / ARSC / DEX 改写并重新解析校验。
`--out` 写出的 zip 未签名，只用于结构检查，不能直接安装。`--self-test` 会用合成 APK 自检。

另外 `app/src/test/java/com/pkgmod/` 下有三个 JVM 单元测试，直接调用**生产代码**，CI 上跑 `testDebugUnitTest`：

- `PatchersTest`：合成 AXML / ARSC / DEX，校验包名与连带属性、chunk 尺寸、DEX 的 checksum 与 SHA-1；
- `ApkRepackerTest`：构造 APK 后重打包，解析中央目录/本地头，校验 `resources.arsc` 不压缩且 4 字节对齐、未压缩 `.so` 页对齐、旧签名文件被剔除、CRC 可读；
- `RulesTest`：匹配方式（exact/prefix/suffix/contains/regex）、替换方式（fixed/template/lastCharInc/regexReplace）、
  第一条命中即生效、停用规则跳过、非法目标回落、目标未变化时忽略。

建议的真机验证顺序：

1. 构建通过 → 装到手机，确认能列出应用、命中规则显示正确的目标包名。
2. 拿一个无关紧要的小应用试改包，确认"改完能装、能启动"。
3. 再上目标游戏。


## 构建（无需本地环境）

1. 在 GitHub 新建一个仓库（Private 也可以）。
2. 把 `pkgmod/` 目录里的**全部内容**推上去（`.github/` 要一起推，别漏）。
3. 打开仓库 → **Actions** → 左侧 `build-apk` → 右侧 **Run workflow**。
4. 等 3~6 分钟，构建完成后在该次运行的页面底部下载 `pkgmod-debug-apk`。
5. 解压得到 `app-debug.apk`，传到手机安装。

首次构建会自动生成签名用 keystore（`app/src/main/assets/signing.p12`），已加入 `.gitignore`，
每次全新构建的密钥不同。如果想让改包产物的签名长期稳定，把这个文件提交进仓库即可。

## 规则格式

规则文件是一个 JSON，内置规则就是这两条渠道映射：

```json
{
  "version": 1,
  "rules": [
    {
      "name": "阿里游戏渠道：aligames 末位 +1",
      "match": "exact",
      "pattern": "com.aligames.kuang.kybc.aligames",
      "replace": "fixed",
      "value": "com.aligames.kuang.kybc.aligamet",
      "enabled": true
    },
    {
      "name": "B 站渠道：bilibili 末位 +1",
      "match": "exact",
      "pattern": "com.aligames.kuang.kybc.bilibili",
      "replace": "fixed",
      "value": "com.aligames.kuang.kybc.bilibilj",
      "enabled": true
    },
    {
      "name": "TapTap 渠道：tap 改为 kybd",
      "match": "exact",
      "pattern": "com.aligames.kuang.kybc.tap",
      "replace": "fixed",
      "value": "com.aligames.kuang.kybd",
      "enabled": true
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `match` | `exact` 完全相同 / `prefix` 前缀 / `suffix` 后缀 / `contains` 包含 / `regex` 正则全匹配 |
| `pattern` | 匹配用的字符串或正则 |
| `replace` | `fixed` 固定值 / `template` 模板（`{pkg}` 会被替换成原包名）/ `lastCharInc` 末位字符 +1（`kybc`→`kybd`）/ `regexReplace` 正则替换（`$1` 取分组） |
| `value` | 替换结果（`fixed`/`template`/`regexReplace` 使用） |
| `scope` | 要改哪些层：`manifest`（清单）、`arsc`（资源字符串）、`dex`（代码内字符串） |
| `enabled` | 是否启用 |

规则按数组顺序匹配，**第一条命中即生效**。改规则有三种方式（都不用重新构建 APK）：

- 首页「规则管理」：可视化列表，点条目编辑、长按删除、底部新增，保存后立即生效；
- 首页「规则管理」→「JSON 编辑」：直接改 JSON 原文；
- 「导入文件」：从手机里选一个 JSON 文件覆盖。

`tools/verify_patchers.py` 与单元测试不依赖规则内容，规则改动不需要重新验证。

## 工作原理

1. `PackageManager` 读取已安装应用的 `sourceDir`（APK 无需 root 即可读）。
2. 规则命中后，把 APK 解到内存：
   - **AXML**：只重建字符串池，改写 `manifest/package`、权限名、`authorities`、`taskAffinity` 等"包名语义"属性，`android:name` 这类类名一律不动。
   - **resources.arsc**：改写全局字符串池中包含旧包名的值（SDK 配置、渠道号等常藏在这里）。
   - **DEX**：字符串池里与旧包名完全相同、或 `旧包名.xxx` 形式的字符串会被替换（跳过 `Lcom/old/...;` 这类类描述符，避免破坏类引用）。
3. 重新打包 ZIP（`resources.arsc` 保持不压缩，`.so` 按页对齐）。
4. 用 `apksig` 重新签名（v1 + v2 + v3）。
5. `PackageInstaller` 会话安装，系统弹窗手动确认。

## 使用流程

1. 装好 App，打开会列出已安装应用，命中规则的显示 `→ 新包名`。
2. 点某个应用 → 确认 → 自动改包并提交安装（系统弹窗确认）。
3. 如果应用列表里读不到目标应用（部分系统限制读取其他应用 APK），或你想改的是下载来的
   APK 文件，用首页的「选择 APK 文件改包」：选中文件后会读出包名、按规则匹配，再走同一条流程。

改包产物会同时导出一份到 `Android/data/com.pkgmod/files/pkgmod/<新包名>.apk`，
系统安装被拒绝时可以到该目录手动点安装，也方便排查。

## 已知限制

- **签名必然变化**：改完的包无法覆盖安装原版，只能共存；依赖原签名的登录、支付、存档可能失效。
- **SDK 服务端校验**：部分 SDK（如九游/阿里游戏）会上报并校验包名，本地改完仍可能被拒。
- **Android 14/15**：系统拒绝安装 `targetSdk < 23` 的 APK，这类旧包需要额外抬高 `targetSdk`（可能运行异常）。
- **分体 APK**：带 `split` 的应用（App Bundle 安装）暂时只支持基包，需后续单独处理。
- 规则替换后的包名必须符合 Java 包名规范，否则会被跳过。
