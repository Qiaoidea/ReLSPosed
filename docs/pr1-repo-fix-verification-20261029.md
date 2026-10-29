## 真机根因分析完成 —— 修复已推送（2f06eaa0），端到端验证通过 ✅

设备：96dc3064（OnePlus PJD110 / Android 16 / arm64-v8a）

### 发现 0（关键前置，解开「REPO_TRACE 不出现」之谜）：真机上运行的 manager 不是安装的 APK

- 设备 framework 为 zygisk_lsposed `1.0.2_cli_auto (7215)`，其 magisk 模块携带内置 `manager.apk`（versionCode **7215**），并注入到 `org.lsposed.manager` 进程（`/proc/<pid>/maps` 可见 `/data/adb/modules/zygisk_lsposed/manager.apk`）
- 注入 dex 与安装 APK **同名同类**（`Lorg/lsposed/manager/App;` 等），运行时类解析到注入副本，**安装 APK 的代码被完全遮蔽**
- 因此：安装 build 的 dex 里明明有 3 处 `REPO_TRACE`（已 pull APK 逐 dex grep 确认），logcat 却永远不出现 —— 运行的是注入版旧代码；`repo.json` mtime 停在 10-13 也是注入版所为
- 另外 Oplus ROM 的 `LOG_FLOWCTRL` 按进程限流（启动期 quota 300 行直接 DROPPED），进一步吞掉日志

### 根因 1：注入版（framework 内置）用单斜杠 URL

注入 manager.apk 的 dex 字符串为 `https://modules.lsposed.org/`（单斜杠）→ `/modules.json` 403，即本 PR 描述的问题原样存在。

### 根因 2：PR 分支 RepoLoader 仍有状态机回归（本次修复主体）

`loadRemoteData()` 入口 `repoLoaded=false`：
- UI 侧 `getOnlineModules()` 在刷新窗口内返回 null → `setData(null)` 直接 return → 列表空白、`isLoaded=false`
- 失败收尾的 `loadLocalData(false)` 入口同样置 false → 若其 `updateLatestVersion` 与 `onRepoLoaded` 回调次序交错，UI 拿到的永远是 null → **永久空白「加载中」，且无任何错误提示**

### 根因 3：无 callTimeout，挂死连接无终态

真机 `kill -3` 线程栈实证：请求线程卡在 `SocketInputStream.socketRead0`（TLS read）超过 60s —— 到 `43.174.246.57:443`（modules.lsposed.org / EdgeOne）TCP+TLS 已建立但服务端不回数据（同机 `curl https://modules.lsposed.org//modules.json` 却 200/0.8s）。同步 `execute()` 永不返回 → 状态机停在中间态。

### 根因 4：DoH 被阻断且无系统回退

`doh=true`（preferences 实测）+ `private_dns_mode=off` → 全部 DNS 走 DoH；实测 `https://1.1.1.1/dns-query` **8s 超时 code=000**（被阻断），`1.0.0.1` 通；bootstrap 无系统 DNS fallback，DoH 失败即整个请求失败。

### 修复内容（commit `2f06eaa0`，3 文件 +32/−5）

1. **`RepoLoader.loadRemoteData()`**：刷新期间保持 `repoLoaded=true`（内存目录仍有效，UI 持续显示旧列表）；失败收尾**优先内存目录**，仅在内存为空时才回读磁盘 —— 消除空白竞态，落实「失败不得丢弃可用目录」的设计目标
2. **`App.getOkHttpClient()`**：加 `callTimeout(30s)`（绝对 deadline，覆盖 DNS+连接+读，不怕服务端建连后挂起）
3. **`CloudflareDNS`**：DoH 失败（UnknownHostException）自动回退系统 DNS；DoH 单次尝试 `callTimeout(10s)` 快速失败

### 真机验证结果

- 修复代码以文件日志取证（绕开 LOG_FLOWCTRL）：`loadLocalData parsed modules=1131` → `loadRemoteData success url=https://modules.lsposed.org// modules=1253` → `repo.json` 落盘 2.34MB —— **远程加载 1.2s 成功**
- UI：仓库页副标题由「加载中…」变为「**3 个模块可更新**」，列表完整显示（含可更新高亮/更新时间）
- 验证方式说明：因注入遮蔽，验证时将本构建替换了 `/data/adb/modules/zygisk_lsposed/manager.apk`（原文件已备份于 `/data/local/tmp/manager.apk.orig`）
### 对发布路径的含义（已核实构建链路）

`magisk-loader/build.gradle.kts:165-167`：

```kotlin
from(project(":app").tasks.getByName("package$buildTypeCapped").outputs) {
    include("*.apk")
    rename(".*\\.apk", "manager.apk")
}
```

framework zip 内的 `manager.apk` **直接由 `:app` 构建产物重命名而来**，不另走独立源码。因此：

- ✅ **新构建的 framework 会自动携带本修复**，无需额外改动 framework 侧代码或脚本
- ⚠️ **但对已刷旧 framework 的设备，仅升级 manager APK 无效** —— 注入副本遮蔽安装 APK（发现 0）。这类用户必须**刷入含本修复的新 framework**，或手动替换 `/data/adb/modules/zygisk_lsposed/manager.apk`（本次验证即采用此路径）
- 建议 release note 明确提示：「升级 manager 后仓库页仍空白 → 请同步刷入新版 framework」
