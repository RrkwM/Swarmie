# Swarmie 环境部署蓝图

> 状态：**提案（Phase 0.1）**。本阶段只写文档，未安装、未下载、未构建。
> 2026-10-27 更新：P1-T1c 实测表明 26.3 非混淆需要 Loom 1.19.3 + Gradle 9.8.2；本文早期出现的 1.17.21 / 9.6.0 数字属于历史方案，以 `docs/version-baseline.md` 与 `docs/provisioning-findings.md` 为准。
> 适用目标：Windows 11 Enterprise x64 上的 Minecraft 26.3 + Fabric 开发环境。

## 1. 目标与边界

**冻结目标（Part 2）**

> 经过**一次显式授权的 provisioning** 之后，Swarmie 必须能够仅使用所提供的工作区或离线包完成离线构建。

provisioning 之后的环境**不得**依赖：

- 用户特定的绝对路径；
- 全局 Gradle 缓存（`%USERPROFILE%\.gradle`）；
- 系统安装的 Gradle 发行版；
- 可变的全局 JDK 选择（`JAVA_HOME`、Scoop `current` 联接）；
- 常规构建期间的互联网访问；
- 机器上其他位置安装的、未记录的工具。

尽可能使用**项目内便携 JDK**，而不是依赖现有的 Scoop JDK。

**provisioning 例外**：允许从显式批准的白名单来源下载（见 §5.3）。该例外只属于 `provision.ps1`，不得延伸到 `build.ps1`；两个脚本的授权参数互相独立。

**本蓝图的四项独立属性**（不得混为一谈，分别测试）：

| 属性 | 含义 |
|---|---|
| P1 | 常规构建期间无网络访问 |
| P2 | 常规构建配置中无外部仓库声明 |
| P3 | 不依赖工作区之外的路径 |
| P4 | Swarmie 模组运行时不调用外部服务 |

## 2. C1 与 C2：定义、成本、验证

### 2.1 Level C1 — 离线执行

**定义**：provisioning 后所有依赖在本地可用；常规构建在无网络下运行；构建配置**仍可**声明远程仓库。

**实现**：预热项目内 `GRADLE_USER_HOME`（含 `caches/modules-2`、Loom 缓存），`build.ps1` 强制 `--offline`。

**成本**：低（约 1 人日）。**可行性**：高——这是 Gradle 的标准离线模式。

**验证**：断网后 `gradlew --offline build` 成功；删除 `build/` 后仍成功。

**局限**：
- 依赖闭包是**不透明缓存**，无法从清单审计“到底需要什么”；
- 配置里仍写着 `https://maven.fabricmc.net`，只是离线时不访问；
- 缓存损坏或缺失时，错误信息不指向具体缺失构件；
- 换机器必须整体搬运 `GRADLE_USER_HOME`，且很难判断是否完整。

### 2.2 Level C2 — 仅本地解析（推荐）

**定义**：常规构建配置**只**从项目内文件或本地仓库解析依赖；远程 URL 只出现在独立、显式调用的 bootstrap 过程中，常规构建不需要它们。

**实现**：
1. `tools/repo/` 是标准 Maven 布局的本地仓库（`file:`），包含 Loom 插件标记、Loader、Fabric API 及全部传递依赖的 jar + POM/`.module`。
2. `settings.gradle.kts` 的 `pluginManagement` 与 `dependencyResolutionManagement` 只声明 `file:` 仓库。
3. Loom 的 Minecraft/资源下载不经过 Maven 仓库，而是其内部下载器；这部分以**预置缓存**（`tools/gradle-home/caches/fabric-loom`）满足，属于“受控的本地构件集”，不是 Maven 仓库。
4. Gradle 发行包由项目内 zip/解压目录提供，wrapper 指向本地文件。
5. 守卫脚本在构建开始时断言：`--offline` 已开启、所有仓库 URL 为 `file:`、Gradle home 与 JDK 均在工作区内。

**成本**：中（约 2–4 人日 + 验证）。主要成本在**依赖闭包捕获**与**负向测试**，不在构建本身。

**可行性判断**：Gradle/Maven 依赖部分完全可行；唯一不确定点是 **Loom 内部下载器是否严格服从 `--offline`**。这不是“远程仓库声明”问题，而是构建工具内部行为，必须用断网构建证明。

**如果遇到真实阻塞**，按以下顺序取**最小妥协**，并书面记录：
1. 若 Loom 向项目注入远程仓库：保留注入但断言其在离线构建中从未被使用；记录例外 `E-LOOM-REPO`。
2. 若 Loom 下载器不检查 `--offline` 但在缓存完整时不发起请求：断网验证通过即可，记录 `E-LOOM-CACHE`。
3. 若 Loom 在缓存完整时仍强制联网校验：C2 不成立，降级为 C1，并在 `docs/provenance/` 写明证据与原因。**不得静默降级。**

**验证**：见 §12 的 C2-AC1…AC7，特别是“删除 `caches/modules-2` 后仍能离线构建”与“断网 VM 构建”。

### 2.3 结论

**推荐 C2**。C1 只能证明“这次没联网”，C2 才能证明“构建只依赖工作区”。C2 的额外成本主要是一次性的闭包捕获与校验，换来的是可审计、可搬运、可复现。

## 3. 最终目录布局

```
Swarmie/
├─ SPEC.md                      # 规范（归档副本的逐字节复制）
├─ PROGRESS.md                  # 状态跟踪
├─ docs/
│  ├─ phase-0-brief.md          # Phase 0 任务书（历史原文）
│  ├─ environment-plan.md       # 本文件
│  ├─ version-baseline.md       # 版本基线
│  ├─ provenance/               # provisioning 生成：清单、许可、日志
│  └─ archive/                  # 两个原始文件（只读）
├─ gradle/
│  ├─ wrapper/                  # gradle-wrapper.jar + properties
│  ├─ libs.versions.toml        # 唯一版本源
│  ├─ verification-metadata.xml # 依赖校验和（严格模式）
│  └─ guard.init.gradle.kts     # 离线/本地路径守卫
├─ gradlew / gradlew.bat
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
├─ src/
│  ├─ main/java/                # 后续关卡
│  ├─ main/resources/
│  ├─ test/java/
│  └─ gametest/
├─ tools/
│  ├─ manifest/
│  │  ├─ toolchain.lock.json    # JDK/Gradle 的 URL + SHA-256 + 大小
│  │  └─ network-allowlist.txt  # provisioning 白名单
│  ├─ scripts/
│  │  ├─ provision.ps1
│  │  ├─ build.ps1
│  │  ├─ verify-offline.ps1
│  │  ├─ export-bundle.ps1
│  │  └─ restore-bundle.ps1
│  ├─ jdk/                      # 便携 JDK（生成，不入 git）
│  ├─ gradle/                   # 解压后的 Gradle（生成，不入 git）
│  ├─ dist/                     # Gradle 发行 zip（生成，不入 git）
│  ├─ repo/                     # 本地 Maven 仓库（生成，不入 git）
│  ├─ mc/                       # MC 构件与资源镜像（生成，不入 git）
│  └─ gradle-home/              # 项目内 GRADLE_USER_HOME（生成，不入 git）
└─ .gitignore                   # 忽略 tools/ 下生成物与 build/
```

**入 git 的**：`SPEC.md`、`PROGRESS.md`、`docs/`（归档与清单）、`gradle/`（wrapper、版本、校验、守卫）、`gradlew*`、`settings/build/gradle.properties`、`src/`、`tools/scripts/`、`tools/manifest/`。
**不入 git 的**：`tools/jdk`、`tools/gradle`、`tools/dist`、`tools/repo`、`tools/mc`、`tools/gradle-home`、`build/`、`.gradle/`。

> “工作区自包含”的标准是：**git 工作区 + 离线包** 即可构建；单靠 git clone 不够（因为 JDK/Gradle/依赖不在 git 中）。这是刻意选择：不把数百 MB 二进制放进版本库。

## 4. 组件位置与来源

| 组件 | 位置 | 来源（provisioning） | 运行期引用方式 |
|---|---|---|---|
| JDK 25 | `tools/jdk/` | Eclipse Temurin 25.0.4.1 Windows x64 zip；SHA-256 记录在 `toolchain.lock.json` | `build.ps1` 设置 `JAVA_HOME`，并传 `-Dorg.gradle.java.home` |
| Gradle 9.6.0 | `tools/dist/gradle-9.6.0-bin.zip` + `tools/gradle/` | `services.gradle.org`；SHA-256 固定 | wrapper 的 `distributionUrl` 指向项目内文件（待实测）或直接调用 `tools/gradle/bin/gradle.bat` |
| Gradle 用户目录 | `tools/gradle-home/` | 由 wrapper/构建生成 | `GRADLE_USER_HOME`，由脚本强制 |
| 本地 Maven 仓库 | `tools/repo/` | 由依赖闭包导出任务生成 | `settings.gradle.kts` 中唯一的 `file:` 仓库 |
| Loom 插件 | `tools/repo/net/fabricmc/fabric-loom/1.17.21/` + 插件标记 | `maven.fabricmc.net`，经闭包导出 | `plugins { id("net.fabricmc.fabric-loom") version "1.17.21" }` |
| Fabric Loader / API | `tools/repo/net/fabricmc/...` | `maven.fabricmc.net` | 普通依赖坐标 |
| Minecraft 版本 JSON / client / server / libraries | `tools/mc/` + `tools/gradle-home/caches/fabric-loom/` | `piston-meta.mojang.com`、`piston-data.mojang.com`、`libraries.minecraft.net` | Loom 内部读取缓存 |
| 资源（assets） | `tools/mc/assets/` 或 Loom 缓存 `assets/` | `resources.download.minecraft.net`（asset index 34，`totalSize = 484,894,038 B`） | Loom/游戏运行期读取 |
| 映射 | Loom 缓存 `mappings/` | Mojang official mappings（Loom 下载） | Loom 编译期 |

**不放在**：`%USERPROFILE%\.gradle`、`%APPDATA%\.minecraft`、任何 `C:\Program Files` 下的可变安装。

## 5. 一次性 provisioning 与常规离线构建

### 5.1 provisioning（唯一允许联网的环节）

`tools/scripts/provision.ps1 -ApproveNetwork`：

1. 校验 `toolchain.lock.json` 自身完整性；确认工作区可写、磁盘 ≥ 6 GB。
2. 下载 JDK zip → 校验 SHA-256 → 解压到 `tools/jdk/` → 运行 `tools/jdk/bin/java -version` 断言为 25.0.4.1。
3. 下载 Gradle 发行 zip → 校验 → 解压到 `tools/gradle/`。
4. 用 `tools/gradle/bin/gradle` 生成 wrapper，`distributionSha256Sum` 固定；`distributionUrl` 指向项目内相对路径（待实测，见 §13）。
5. 首次联网解析：运行 `gradlew --write-verification-metadata sha256 help`，只允许白名单主机。
6. 运行依赖闭包导出任务（§7.3），产出 `tools/repo/` 与 `docs/provenance/artifacts.json`。
7. 联网执行一次 `gradlew build`（关卡 1 的最小 Mod），填充 Loom 的 Minecraft/资源/反编译缓存。
8. 把 Loom 缓存与 MC 构件导出到 `tools/mc/`，生成 `mc-artifacts.json`。
9. 记录 `docs/provenance/provision-log.json`：时间、URL、SHA-256、大小、许可、退出码。
10. 自检：复制工作区到临时目录，断网执行 C2 验收（§12）。失败则 provisioning 不算完成。

### 5.2 常规构建（禁止联网）

`tools/scripts/build.ps1`：

1. 断言 `tools/jdk`、`tools/gradle`、`tools/repo`、`tools/gradle-home` 存在且 `toolchain.lock.json` 哈希匹配。
2. 设置进程级 `JAVA_HOME`、`GRADLE_USER_HOME`；传 `-Dorg.gradle.java.home`、`-Dorg.gradle.java.installations.paths`（值由脚本从 `$PSScriptRoot` 计算，不硬编码）。
3. 执行 `gradlew --offline build`（`--offline` 不可被调用者关闭）。
4. 任何“需要远程仓库”的错误都视为失败，不做自动重试或联网降级。

### 5.3 网络白名单（仅 provisioning）

```
api.adoptium.net           # 或 github.com/adoptium 的 release 资产
services.gradle.org
downloads.gradle.org
maven.fabricmc.net
libraries.minecraft.net
piston-meta.mojang.com
piston-data.mojang.com
resources.download.minecraft.net
repo.maven.apache.org      # Loom 传递依赖（bootstrap 专用）
```

- 白名单写入 `tools/manifest/network-allowlist.txt`，provision 日志逐条记录实际访问。
- `build.ps1` 不接受任何“允许网络”参数；即使传入也直接失败。

## 6. PowerShell 入口点

| 脚本 | 允许联网 | 职责 | 主要输出 | 退出码约定 |
|---|---|---|---|---|
| `provision.ps1` | 是（需 `-ApproveNetwork`） | 完整 bootstrap、闭包导出、缓存导出、自检 | `tools/*`、`docs/provenance/*` | 0 成功；10 校验失败；11 网络失败；12 自检失败；13 环境不满足 |
| `build.ps1` | 否 | 离线构建与快速守卫 | `build/libs/*.jar` | 0 成功；20 守卫失败；21 缺失构件；22 构建失败 |
| `verify-offline.ps1` | 否 | 执行 C1/C2 验收矩阵，生成报告 | `docs/provenance/offline-verification.md` | 0 全通过；30 有失败项 |
| `export-bundle.ps1` | 否 | 打包 `tools/` + 工作区（不含 build）并生成清单 | `dist/swarmie-offline-bundle.zip` + `BUNDLE.json` | 0 成功；40 打包失败 |
| `restore-bundle.ps1` | 否 | 在目标机解包并逐文件校验 | 工作区 | 0 成功；41 校验失败 |
| `clean.ps1` | 否 | 只删 `build/`、`tools/gradle-home/caches/transforms-*`；**永不删** `tools/repo`、`tools/mc` | — | 0 成功 |

所有脚本：

- 以 `$PSScriptRoot` 为根，不写死用户目录绝对路径；
- 不调用 `setx`、不写注册表、不改执行策略、不需要管理员；
- 需要网络时必须显式参数与交互确认，且记录到日志。

## 7. 依赖锁定、校验和与来源记录

### 7.1 版本锁定

- `gradle/libs.versions.toml`：所有版本为精确值，禁止 `+`、`latest.release`、版本范围。
- `gradle/wrapper/gradle-wrapper.properties`：`distributionSha256Sum` + 本地 `distributionUrl`。
- `gradle/verification-metadata.xml`：严格校验；缺校验和即失败。
- 可选：对纯 Java 配置启用 Gradle dependency locking（`dependencyLocking { lockAllConfigurations() }`）；Loom 的特殊配置可能不支持，按配置逐个启用。

### 7.2 校验和

| 对象 | 记录位置 |
|---|---|
| JDK zip | `tools/manifest/toolchain.lock.json` |
| Gradle zip | 同上 |
| 每个 Maven 构件（jar/pom/module） | `docs/provenance/artifacts.json` |
| MC client/server/libraries | `docs/provenance/mc-artifacts.json` |
| 离线包 | `BUNDLE.json` + `SHA256SUMS.txt` |

### 7.3 依赖闭包如何被“捕获、验证、转移”

**不是**“它在 Gradle 缓存里所以算打包了”。流程如下：

1. **枚举配置**：`compileClasspath`、`runtimeClasspath`、`testCompileClasspath`、`testRuntimeClasspath`、Loom 的 `minecraftLibraries` 等。
2. **解析并导出**：自定义 Gradle 任务遍历 `ResolvedComponentResult` 的 `ResolvedArtifactResult`：
   - 复制构件文件到 `tools/repo/<group>/<artifact>/<version>/`；
   - 从 Gradle 缓存复制对应的 `.pom` / `.module`（Gradle 优先 `.module`）；
   - 记录组、构件、版本、文件名、SHA-256、来源 URL。
3. **插件类路径**：用一个一次性 `buildscript { dependencies { classpath("net.fabricmc:fabric-loom:1.17.21") } }` 配置单独解析，捕获 Loom 及其全部传递依赖（含插件标记 POM）。
4. **拒绝动态版本**：发现 `SNAPSHOT`、版本范围、`+` 即失败。
5. **验证**：删除 `tools/gradle-home/caches/modules-2` 后离线构建（§12 C2-AC2）——这是“闭包完整”的证明。
6. **转移**：`export-bundle.ps1` 打包 `tools/`（不含 `gradle-home/caches/modules-2` 也可，因为可由 `repo/` 重建）+ 项目源；`restore-bundle.ps1` 逐文件校验后落盘。

**Loom 的 Minecraft 构件不走 Maven**：它们由 Loom 下载到 `tools/gradle-home/caches/fabric-loom/`。因此：
- 不能假装它们是 `tools/repo` 的一部分；
- 单独导出到 `tools/mc/` 并生成 `mc-artifacts.json`；
- 恢复时必须放回 Loom 期望的缓存路径（`GRADLE_USER_HOME/caches/fabric-loom`），否则 Loom 会尝试下载。

### 7.4 来源与许可记录

`docs/provenance/artifacts.json` 每条至少包含：

```json
{
  "group": "net.fabricmc",
  "artifact": "fabric-loader",
  "version": "0.20.1",
  "file": "tools/repo/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar",
  "sha256": "…",
  "size": 123456,
  "sourceUrl": "https://maven.fabricmc.net/…",
  "license": "Apache-2.0",
  "retrievedAt": "2026-10-27T…Z"
}
```

## 8. 如何阻止意外联网

四层防护，缺一不可：

1. **调用层**：`build.ps1` 永远追加 `--offline`；不提供关闭开关。
2. **配置层**：`settings.gradle.kts` 只声明 `file:` 仓库；`repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` 拒绝项目级仓库。
3. **守卫层**：`gradle/guard.init.gradle.kts`（由 `build.ps1` 以 `-I` 传入）断言：
   - `gradle.startParameter.isOffline == true`；
   - 所有 `RepositoryHandler` 的 URL scheme 为 `file`；
   - `gradle.gradleUserHomeDir` 位于工作区内；
   - Java home 位于工作区内。
   任一不满足 → 直接抛异常终止。
4. **校验层**：`verification-metadata.xml` 严格模式；缺失/不匹配校验和 → 失败，不会“下载看看”。

**负向测试**（必须做）：
- 临时重命名 `tools/repo` 中一个 jar → 构建应失败并指出缺失，而不是联网补齐；
- 去掉 `--offline` → 守卫应失败；
- 在配置中临时加入 `https://maven.fabricmc.net` → 守卫应失败。

**注意**：`--offline` 管住 Gradle 的仓库访问，但不必然管住 Loom 的内部下载器。P1 的最终证据是**断网环境**下的构建，而不是日志里没有 http。

## 9. 干净缓存离线验证

### 9.1 C1 验证

1. 把工作区复制到新目录（不同深度）。
2. `gradlew --stop`；删除 `build/`。
3. 禁用网络（VM 网卡断开，或经授权的防火墙出站阻断）。
4. 运行 `tools\scripts\build.ps1`。
5. 断言：退出码 0、jar 存在、日志无下载失败、JVM 为 `tools/jdk`。

### 9.2 C2 验证（更严格）

1. 在 C1 基础上，额外删除 `tools/gradle-home/caches/modules-2`（**保留** `tools/repo` 与 Loom 缓存）。
2. `gradlew --stop` 后离线构建。
3. 断言：成功 —— 证明 `tools/repo` 闭包完整。
4. 再删除 `build/` 与 `transforms-*`，重复一次。
5. 负向：临时移走 `tools/repo` 中任一构件，构建必须失败且不联网。

> “干净缓存”要区分两类：`modules-2` 是可从 `tools/repo` 重建的**派生缓存**；Loom 的 MC 缓存是**一次性 provisioned 构件集**，离线不可重建，只能随离线包转移。验收标准分别对待，不能混用。

## 10. 可移植性验证

1. 目标：另一台 **Windows 11 x64**、不同用户名、非管理员账户、不同盘符（如 `D:\swarmie\`）。
2. 用 `restore-bundle.ps1` 恢复，运行 `build.ps1`（断网）。
3. 静态扫描：`rg -n "[A-Za-z]:\\\\|C:\\\\Users" --glob '!docs/archive/**' --glob '!docs/provenance/**'` 应无结果（脚本内动态计算的路径除外）。
4. 长路径测试：把工作区放到接近 200 字符的路径下构建，确认不触发 MAX_PATH 问题。
5. 非 ASCII 路径测试（如 `D:\项目\swarmie`）。
6. 产物比对：在 `build.gradle.kts` 设置 `preserveFileTimestamps = false`、`reproducibleFileOrder = true`，比较两次构建 jar 的 SHA-256。若仍非逐字节一致，记录差异原因（编译器/时间戳），改为比较类清单与验收行为，不得宣称“完全可复现”。
7. Loom 缓存若包含绝对路径导致迁移失败：记录为已知限制，并以“恢复后重新 provision”作为兜底（这仍满足 C2 的运行时要求，但降低可移植性）。

## 11. 存储与许可

### 11.1 存储估算（近似，provisioning 后实测修正）

| 项 | 估算 |
|---|---|
| 便携 JDK 25（解压） | ~350 MB |
| Gradle 9.6.0（zip + 解压） | ~400 MB |
| `tools/repo`（Maven 闭包） | ~300–500 MB |
| Loom/MC 缓存（含 assets ≈462 MiB、client 41.5 MB、server 62.3 MB、libraries、transforms） | ~1.5–2.5 GB |
| 构建输出与临时 | ~200 MB |
| **合计** | **约 3–4 GB** |

本机 C: 可用 479.9 GB，充足。

### 11.2 许可与再分发约束

| 组件 | 许可 / 约束 |
|---|---|
| Eclipse Temurin JDK | GPLv2 + Classpath Exception；允许再分发，需保留许可与声明 |
| Gradle | Apache-2.0 |
| Fabric Loader | Apache-2.0 |
| Fabric API | Apache-2.0（其 `fabric.mod.json` 的 `license` 字段） |
| Fabric Loom | Apache-2.0（provision 时核对 LICENSE 文件） |
| Minecraft client / server / assets | **Mojang EULA，不可再分发**；离线包只能私有、本机/本团队内部使用，不得公开上传或提交到 git |
| Mojang official mappings | 受 Mojang 条款约束，**不随包外发** |
| Vineflower/反编译产物 | 通常 Apache-2.0；provision 时核对，反编译输出不随包外发 |

> 上述为工程判断，不构成法律意见。`provision.ps1` 必须在 `artifacts.json` 中记录每个构件的许可字段；无法确认许可的构件不得进入离线包。

## 12. 验收标准

### 12.1 C1 验收

- **C1-AC1**：断网（网卡禁用）后 `tools\scripts\build.ps1` 退出码 0，产出 jar。
- **C1-AC2**：删除 `build/` 与 `transforms-*` 后重复构建仍成功。
- **C1-AC3**：构建日志无“下载/连接”成功记录；JVM 为 `tools/jdk` 的 25.0.4.1。
- **C1-AC4**：`%USERPROFILE%\.gradle` 未被创建或写入（构建前后对比）。
- **C1-AC5**：`gradlew --stop` 后重新构建仍成功（不依赖常驻 daemon 的临时状态）。

### 12.2 C2 验收（在 C1 全部通过之上）

- **C2-AC1**：静态扫描 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/`，除 `provision` 专用文件外**无 `http://`/`https://` 仓库声明**。
- **C2-AC2**：删除 `tools/gradle-home/caches/modules-2` 后离线构建成功（证明本地仓库闭包完整）。
- **C2-AC3**：负向测试——移走 `tools/repo` 任一构件，构建失败且未联网；恢复后成功。
- **C2-AC4**：守卫测试——去掉 `--offline`、或加入远程仓库、或把 `GRADLE_USER_HOME` 指向工作区外，构建均失败并给出明确原因。
- **C2-AC5**：断网 VM 中，从 `swarmie-offline-bundle.zip` 恢复到不同路径后构建成功。
- **C2-AC6**：`artifacts.json` 与 `mc-artifacts.json` 中每个构件都有 SHA-256，校验脚本全通过；无动态版本。
- **C2-AC7**：工作区移动到不同盘符、不同深度、非 ASCII 路径后仍构建成功（或记录明确的已知限制）。

### 12.3 四类属性矩阵

| 属性 | 证明方式 | 对应验收 |
|---|---|---|
| P1 构建无网络 | 断网 VM 构建 | C1-AC1、C2-AC5 |
| P2 配置无远程仓库 | 静态扫描 + 守卫负向测试 | C2-AC1、C2-AC4 |
| P3 无工作区外路径 | 移动路径 + 绝对路径扫描 | C2-AC7、C1-AC4 |
| P4 Mod 无外部服务 | 代码审查 + 依赖清单检查（关卡 1 起） | 关卡验收时补充 |

**C2 完成的定义**：C1 全部 + C2 全部通过，且 `docs/provenance/` 中有对应的构建日志与清单。任何例外必须以 `E-*` 编号写入 `docs/provenance/exceptions.md`。

## 13. 待验证项与风险

| # | 问题 | 影响 | 处理 |
|---|---|---|---|
| V1 | Loom 1.17.21 是否严格服从 `--offline` | C2 核心 | Phase 1 断网构建验证 |
| V2 | Loom 是否向项目注入远程仓库 | C2-AC1 | `./gradlew dependencies` 与 settings 检查 |
| V3 | Gradle Wrapper 是否接受项目相对 `file:` URL | 启动方式 | 不支持则改用 `tools/gradle/bin/gradle.bat` 包装器 |
| V4 | Fabric API 聚合 jar 的最低 Loader | 版本锁定 | 读取 jar 内 `fabric.mod.json` |
| V5 | Loom 缓存是否含绝对路径 | 可移植性 | 迁移测试；必要时恢复后重跑 provision |
| V6 | 26.3 客户端缺失 | 关卡 1/2 验收 | 安装客户端需单独授权 |
| V7 | GameTest 在 26.3 的实际运行方式 | 测试策略 | 关卡 1 后最小验证 |
| V8 | JDK/Gradle/Loom 组合的实际告警 | 稳定性 | 首次构建记录完整日志 |
| V9 | 企业代理 TLS 拦截 | provisioning 失败 | 先做连通性与证书验证 |
| V10 | 磁盘/路径长度/杀毒软件锁文件 | 构建失败 | 迁移测试与排除项记录 |

