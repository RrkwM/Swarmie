# P1-T1c 供给与首次构建发现（阻断）

> 后续根因诊断见 [`p1-t1c-fix-diagnosis.md`](p1-t1c-fix-diagnosis.md)：ZipFS 的 `Files.isWritable()` 在当前 Low 完整性上下文中返回 false，项目内无安全修复方式。
>
> 状态：**未完成 / blocked**。网络 provisioning 与依赖解析成功，但 Loom 无法写入其处理后的 Minecraft jar，首次有效 Fabric 构建未达成。**未声称 C2 通过。**

## 1. 结论摘要

- 工具链：项目内 Temurin 25.0.4.1+1、Gradle 9.8.2、Loom 1.19.3 均已落地并可执行。
- 依赖：Loom 插件、Fabric Loader 0.20.1、Fabric API 0.165.0+26.3 及传递依赖已解析。
- 阻断：Loom 在 `MinecraftJarSplitter` / server-only 处理阶段调用 zipfs 写入时失败：
  - split：`java.nio.file.ReadOnlyFileSystemException`
  - server-only：`IOException: the jar file ...\minecraft-SERVER-only.jar can't be written`
- 根因：当前命令进程为 **Low 完整性**，文件带 `Low Mandatory Level:(NW)`；Java 的 `Files.isWritable()` 对这些**实际可写**的文件返回 `false`。Loom 的 zipfs 以此判定只读。
- 不是缓存损坏、不是 Loom 版本单一问题、也不是 JDK 版本单一问题（JDK 21 与 25 均复现）。

## 2. 版本变更与证据

| 变更 | 证据 |
|---|---|
| Loom 1.17.21 → **1.19.3** | 1.17.21 的 `splitEnvironmentSourceSets()` 在 `MinecraftJarSplitter.copyEntriesToJar` 失败（`ReadOnlyFileSystemException`）。1.18.1/1.18.4 要求 Gradle 9.7.0；1.19.3 要求 Gradle ≥9.8.0。官方 `fabric-example-mod/26.3` 使用 Loom 1.19-SNAPSHOT。 |
| Gradle 9.6.0 → **9.8.2** | Loom 1.19.3 的 Gradle 元数据要求 `org.gradle.plugin.api-version=9.8.0`。9.8.2 为当前稳定版，支持 Java 25。已从 `services.gradle.org` 下载并核对官方 SHA-256。 |
| 移除 `mappings(...)` | Minecraft 26.3 为非混淆版本；Loom 明确报错 “Cannot use Mojang mappings in a non-obfuscated environment”。 |
| `modImplementation` → `implementation` | 26.3 的 Loom no-remap 模式不创建 `modImplementation` 配置；官方 26.3 示例同样使用 `implementation`。 |
| 使用 `splitEnvironmentSourceSets()` | 官方 26.3 示例配置；merged jar 在 26.3 下为空/不可用。 |

## 3. 实际访问的网络主机

从 provisioning 日志中提取：

- `maven.fabricmc.net`
- `libraries.minecraft.net`
- `plugins.gradle.org`
- `repo.maven.apache.org`
- `downloads.gradle.org` / `services.gradle.org`（Gradle 发行包）

说明：

- `docs.gradle.org` 仅出现在 Gradle 日志的文档链接文本中，未被实际请求。
- `piston-meta.mojang.com` / `piston-data.mojang.com` 未在 info 日志中显式出现；Loom 通过其内部下载器获取 Minecraft 元数据/jar，日志未打印 URL。不能据此声称“未访问”，但也没有发现非官方主机。
- 未访问第三方镜像、Modrinth、GitHub 任意文件或账号服务。

## 4. 构建尝试与结果

| # | 配置 | 结果 |
|---|---|---|
| 1 | Loom 1.17.21 + `FAIL_ON_PROJECT_REPOS` | 失败：Loom 注入项目仓库 `LoomLocalRemappedMods` 与模式冲突 |
| 2 | Loom 1.17.21 + `PREFER_SETTINGS` | 失败：`mappings`/`modImplementation` 未解析 |
| 3 | Loom 1.17.21 + 无 mappings + merged | **BUILD SUCCESSFUL，但无效**：`minecraft-merged.jar` 为 22 字节空 ZIP；MC 探针编译失败（`package net.minecraft.world.entity does not exist`） |
| 4 | Loom 1.17.21 + split | 失败：`Failed to split minecraft` → `ReadOnlyFileSystemException` |
| 5 | Loom 1.19.3 + split | 同上 |
| 6 | Loom 1.19.3 + `serverOnlyMinecraftJar()` | 失败：`minecraft-SERVER-only.jar can't be written` |

命令（最终尝试）：

```text
gradlew.bat -I tools/bootstrap/online-repos.init.gradle.kts --no-daemon --console=plain \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 build
```

退出码 **1**。日志：`tools/logs/provision-20261027-034933.log`（无效成功）、`...-035427.log`、`...-035911.log` 等。

## 5. 根因证据

- `whoami /groups`：`Mandatory Label\Low Mandatory Level`。
- 工作区文件 ACL：`Mandatory Label\Low Mandatory Level:(I)(NW)`。
- 文件 ACL 实际授予写权限，`Files.newByteChannel(..., CREATE_NEW/WRITE/TRUNCATE)` 成功。
- 但 Java：
  - `Files.isWritable(file)` → `false`
  - `Files.isWritable(parent)` → `false`
- 最小探针：
  - `FileSystems.newFileSystem(uri, {"create":"true"})` → `OPENED readOnly=true`，随后 `ReadOnlyFileSystemException`
  - `{"create":"true","accessMode":"readWrite"}` → `IOException: the ZIP file is not writable`
  - JDK 21 与 JDK 25 均复现。
- 结论：DSH 沙箱的 Low/NW 完整性模型使 Java 的可写性探测失真；Loom 依赖该探测，无法完成 zipfs 写入。

## 6. 已捕获内容（partial）

- `tools/repo/`：335 个文件，约 98.5 MB，包含：
  - `net.fabricmc:fabric-loom:1.19.3` 插件与插件标记
  - Fabric Loader 0.20.1
  - Fabric API 0.165.0+26.3
  - Minecraft libraries（如 `com.mojang:text2speech:1.19.12`）
  - POM、`.module` 与传递依赖
- `tools/mc/loom-cache/`：6 个文件（client/server/extracted server/version manifest 等）；**处理后的 split/server-only jar 不完整**。
- `tools/manifest/dependency-provenance.json`：`task=P1-T1c-partial`，335 条，SHA-256 全部与本地文件一致；**不证明闭包完整**。
- `tools/manifest/mc-artifacts.json`：6 条，标注 partial。

## 7. 构建产物

- `build/libs/swarmie-0.1.0-SNAPSHOT.jar`（1,666 B）来自尝试 #3，**未引用任何 Minecraft 类**，不是有效交付物。
- 当前没有可声明“可加载”的 Mod JAR。

## 8. 验收状态

| 验收项 | 状态 |
|---|---|
| provisioner 无 `-ApproveNetwork` 拒绝 | 通过（exit 2） |
| 活动仅在项目路径内 | 通过 |
| 仅官方主机 | 未发现违规；MC 下载 URL 未被日志显式记录 |
| 普通配置无远程仓库声明 | 通过（Loom 运行时注入仓库仍待 C2 阶段处理） |
| Minecraft 26.3 + Fabric 工程成功配置 | **未通过**（Loom zipfs 写入阻断） |
| 最小 Mod 编译 | **未通过**（仅不引用 MC 的版本可编译） |
| 产出 Mod JAR | **未通过**（现有 jar 无效） |
| 依赖捕获 | 部分（335 项 inventory） |
| MC/Loom 构件清单 | 部分（6 项，处理 jar 不完整） |
| 来源记录 | 部分（SHA-256 有，发布方校验和未逐项获取） |
| build.ps1 仍 fail-closed | 通过 |
| 未做破坏性清理 | 通过（仅搬迁旧 Loom 缓存到备份） |
| 不声称 C2 | 通过 |

## 9. 最小修复任务

**P1-T1c-fix：在 `Files.isWritable()` 行为正常的环境中重跑 provisioning。**

要求：

1. 使用普通用户完整性（非 Low/NW 沙箱）运行；不得用管理员提权绕过。
2. 保持当前 Gradle 9.8.2 + Loom 1.19.3 + split 配置。
3. 先放入临时 `McProbe` 引用 `net.minecraft.world.entity.Entity`，确认编译通过；再删除探针并重跑。
4. 成功后由 `provision.ps1 -ApproveNetwork` 重新捕获 `tools/repo`、`tools/mc`、provenance。
5. 再进行 C2 离线验证（删除 `modules-2`、断网）；本阶段不做。

在该修复完成前，不应把 P1-T1c 标记为完成，也不应声称构建或 C2 可用。
