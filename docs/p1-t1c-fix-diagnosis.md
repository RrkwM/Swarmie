# P1-T1c-fix 诊断报告

> 结论：**根因已定位到 ZipFS 的 `Files.isWritable()` 判定；当前 Harness 执行上下文（Low 完整性）使其对所有项目文件返回 false。项目内没有不绕过安全策略的修复方式，因此停止并请求受支持的标准用户执行上下文。**

## 1. 直接原因

`java.nio.file.ReadOnlyFileSystemException` 不是 Windows 拒绝写入，也不是 Loom 配置错误，而是：

1. Loom 用 ZipFS 打开/创建处理后的 Minecraft jar；
2. JDK 的 `ZipFileSystem` 构造器执行 `readOnly = !Files.isWritable(zipPath)`；
3. 当前执行上下文中 `Files.isWritable()` 对**实际可写**的路径返回 `false`；
4. ZipFS 因此把 `isReadOnly()` 设为 `true`；
5. `MinecraftJarSplitter.copyEntriesToJar()` 调用 `Files.createDirectories()` 写入条目时抛出 `ReadOnlyFileSystemException`。

ZipFS 的 `isWritable` 调用点已由字节码确认：

```text
630: invokestatic Files.isWritable(Path)Z
633: ifne 640
636: iconst_1
641: putfield readOnly:Z
```

## 2. 完整异常链

```text
ProjectConfigurationException: A problem occurred configuring root project 'swarmie'
Caused by: RuntimeException: Failed to setup Minecraft
Caused by: RuntimeException: Failed to split minecraft
Caused by: java.nio.file.ReadOnlyFileSystemException
  at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.checkWritable(ZipFileSystem.java:405)
  at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.createDirectory(ZipFileSystem.java:739)
  at jdk.zipfs/jdk.nio.zipfs.ZipPath.createDirectory(ZipPath.java:729)
  at jdk.zipfs/jdk.nio.zipfs.ZipFileSystemProvider.createDirectory(ZipFileSystemProvider.java:183)
  at net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarSplitter.copyEntriesToJar(MinecraftJarSplitter.java:120)
  at net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarSplitter.split(MinecraftJarSplitter.java:67)
  at net.fabricmc.loom.configuration.providers.minecraft.SplitMinecraftProvider.provide(SplitMinecraftProvider.java:86)
```

- 失败对象是 **ZipFS**（`provider=jar`），不是 NTFS 文件系统。
- 失败发生在**向已打开的归档写入目录条目**，不是打开归档、也不是创建 JAR 文件本身。
- `FileSystem.isReadOnly() == true`，但底层 `WindowsFileSystem.isReadOnly() == false`。

## 3. 独立文件系统探针

在 `tools/provision-tmp/diagnostics/` 用项目内 JDK 25 运行 `FsProbe`（日志 `fs-probe.log`）：

| 操作 | 结果 |
|---|---|
| 普通文件创建/写入/删除 | **成功**；`isWritable=false` |
| `ZipOutputStream` 新建 zip 并写条目 | **成功**；`isWritable=false` |
| ZipFS 以 `create=true` 打开新 zip | `readOnly=true`，随后 `createDirectories` → `ReadOnlyFileSystemException` |
| ZipFS 打开已有 zip | `readOnly=true` |
| `WindowsFileSystem` | `readOnly=false` |
| Loom 26.3 目录 / `tools/repo` | `isWritable=false`，`fsReadOnly=false` |

同一个探针在 JDK 21 上也复现 ZipFS 只读。因此：

- 不是 JDK 25 独有；
- 不是 Loom 独有；
- 不是某个 jar 损坏；
- 是**执行上下文**导致 Java 的可写性探测失真。

## 4. 进程与文件完整性

- 进程：`Mandatory Label\Low Mandatory Level`。
- 项目根、`tools/provision-tmp/diagnostics/`、Loom `26.3` 目录：
  - ACL 含 `DESKTOP-U0PNK99\Admin:(F)` 与 `SYSTEM:(F)`；
  - 强制标签：`Low Mandatory Level:(NW)`。
- 实际写入（普通文件、ZipOutputStream、文件通道 `CREATE_NEW/WRITE/TRUNCATE`）成功。
- `Files.isWritable()` 对同样路径返回 false。

**未能证明的环节**：无法区分是 Low 完整性标签、受限令牌的 AccessCheck，还是 Harness 文件系统过滤导致 `isWritable` 返回 false。当前没有非沙箱的对照执行上下文可用；不做猜测。

## 5. Loom 实现路径

- `FileSystemUtil.getJarFileSystem(Path)` → tiny-remapper `FileSystemReference.openJar(Path)` → `FileSystemReference.open(URI, boolean create)` → `FileSystems.newFileSystem(URI, env)`。
- `create=true` 时会传 `{"create":"true"}`，但 ZipFS 仍在 `isWritable` 检查后把 FS 设为只读。
- `serverOnlyMinecraftJar()` 走 `SingleJarMinecraftProvider`，同样写入 `minecraft-SERVER-only.jar`，同样失败（`the jar file ... can't be written`）。
- 因此 Loom 1.17.21 / 1.19.3 的所有处理路径都会命中该问题。

## 6. 版本组合审计

| 结论 | 证据 |
|---|---|
| Loom 1.19.3 要求 Gradle ≥ 9.8.0 | 下载的 `fabric-loom-1.19.3.module` 中 `org.gradle.plugin.api-version: 9.8.0` |
| Gradle 9.8.2 可在 JDK 25 运行 | `gradlew --version` 报告 Gradle 9.8.2、Launcher JVM 25.0.4.1 |
| Loom 1.17.21 的 Gradle 约束为 9.5.0 | `fabric-loom-1.17.21.module` 中 `api-version: 9.5.0`；它可在 Gradle 9.6 应用，但 split 仍因 ZipFS 失败 |
| `net.fabricmc.fabric-loom` + `splitEnvironmentSourceSets()` | 与官方 `fabric-example-mod/26.3` 一致 |
| 26.3 非混淆 | `officialMojangMappings()` 被 Loom 拒绝；`modImplementation` 配置不存在；官方示例用 `implementation` |

Gradle 9.8.2 + Loom 1.19.3 的组合在元数据层面兼容；当前阻断是执行环境，不是版本组合。该版本升级在有有效构建前保持**provisional**。

## 7. 为什么没有应用修复

项目内可尝试的最小修正均被证据排除：

- 修改 Loom 配置：split/server-only 都命中同一 ZipFS 检查。
- 清理/重建缓存：已从干净 Loom 缓存重试，仍失败。
- 修改输出路径：任何 NTFS 路径上的 ZipFS 都只读。
- 修改 ACL/完整性标签：当前令牌无权限（`icacls` 拒绝），且任务禁止。
- 打补丁/Java agent/特权进程：属于绕过安全策略，任务禁止。

## 8. 需要的执行上下文

需要以**受支持的、非 Low 完整性的标准用户上下文**运行 Gradle，使 `Files.isWritable()` 对普通项目文件返回 true。该模式必须由用户显式授权/启用；本任务不自行提权、不修改沙箱策略。

在此之前的任何“构建成功”都不能作为 Minecraft 类可用的证据；此前的 Loom 1.17.21 merged 构建即为反例（空 22 字节 jar，MC 探针编译失败）。
