# 版本基线（候选锁定，待构建实测）

> 状态：**candidate lock**。本文的版本组合要在 Phase 1 实际构建通过后，才升级为 `locked`。
> 原则：不因为“有更新版本”就升级；优先选择官方明确配套、可复现、相互兼容的组合。
> 2026-10-27：Gradle 9.8.2 + Loom 1.19.3 为**provisional**（因 26.3 非混淆与 Loom/Gradle 兼容性）。首次有效构建在普通用户干净副本中成功；原工作区仍受 DSH ACL/ZipFS 阻断。构建/客户端验证见 `docs/runtime-verification.md`。

## 1. 候选组合

| 组件 | 候选值 | 证据 | 状态 |
|---|---|---|---|
| Minecraft | `26.3` | [Mojang version_manifest_v2.json](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) 中 `latest.release = 26.3`，`releaseTime = 2026-09-15T11:23:02Z` | 已确认 |
| Java（MC 要求） | `25`（`java-runtime-epsilon`） | [26.3.json](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json) 的 `javaVersion.majorVersion = 25` | 已确认 |
| Fabric Loader | `0.20.1` | [meta/versions/loader](https://meta.fabricmc.net/v2/versions/loader) 标记 `stable: true`；Phase 0 读取的 [meta/versions/loader/26.3](https://meta.fabricmc.net/v2/versions/loader/26.3) 列表包含 0.20.1 | 已确认兼容 |
| Fabric API | `0.165.0+26.3` | [Modrinth 26.3 版本列表](https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.3%22%5D&loaders=%5B%22fabric%22%5D)，2026-10-23 发布 | 已确认存在 |
| Loom | `1.19.3` | 26.3 官方示例使用 Loom 1.19 线；1.17.21 在 JDK 25 下 split 失败；1.18.x 要求 Gradle 9.7；1.19.3 要求 Gradle ≥9.8.0 | 已解析，构建被环境阻断 |
| Gradle | `9.8.2` | Loom 1.19.3 要求 Gradle ≥9.8.0；9.8.2 当前稳定且支持 Java 25 | 已下载校验，构建被环境阻断 |
| Gradle ↔ Java 25 | 支持 | [Gradle v9.6.0 兼容矩阵](https://raw.githubusercontent.com/gradle/gradle/v9.6.0/platforms/documentation/docs/src/docs/userguide/releases/compatibility.adoc)：JVM 17–26 可运行 Gradle；Java 25 “9.1.0 and after” | 已确认 |
| JDK 供应 | Eclipse Temurin `25.0.4.1` | 本机 `java -version`（25.0.4.1+1-LTS）；项目内将使用同版本便携副本 | 已确认本机存在 |
| 映射 | Loom 默认（Mojang official mappings） | Loom 默认行为；CC:Tweaked 参考代码使用 Mojang 名称 | 待确认（是否与 CC 参考一致） |

### 为什么不用“最新”

- Gradle 已从 9.6.0 升级到 **9.8.2**：Loom 1.19.3 拒绝在 9.6.0 上运行（要求 ≥9.8.0），而 Loom 1.17/1.18 在 JDK 25 下无法完成 26.3 的 split。升级是构建失败的实证结果，不是“追新”。
- Loom 已确定为 **1.19.3**：26.3 官方示例使用 1.19 线；1.17.21 与 1.18.x 均无法在当前组合下完成 26.3 的 split（1.18 还要求 Gradle 9.7）。
- Fabric Loader **0.20.1** 是当前稳定版且明确支持 26.3；公告发布时（2026-09-15）稳定版还是 0.19.5，属正常版本推进，不构成冲突。

## 2. Fabric API 的最低 Loader 要求

- 26.3 分支的 `fabric-api-base` 模块声明：`"fabricloader": ">=0.19.3"`（[源码](https://raw.githubusercontent.com/FabricMC/fabric/26.3/fabric-api-base/src/main/resources/fabric.mod.json)）。
- 因此候选组合 `0.20.1 ≥ 0.19.3` 成立。
- **仍待验证**：聚合 jar `fabric-api-0.165.0+26.3.jar` 内 `fabric.mod.json` 的确切下限（模块级声明不必然等于聚合声明）。Phase 1 用 `jar xf`/`unzip -p` 读取后记录，不靠猜测。

## 3. 测试与渲染设施

| 能力 | 结论 | 证据 |
|---|---|---|
| GameTest | Fabric API 在 26.3 分支包含 `fabric-gametest-api-v1` 模块 | [GitHub API](https://api.github.com/repos/FabricMC/fabric/contents/fabric-gametest-api-v1?ref=26.3) |
| 实体渲染 | `fabric-rendering-v1` 在 26.3 分支存在 | [GitHub API](https://api.github.com/repos/FabricMC/fabric/contents/fabric-rendering-v1?ref=26.3) |
| 实体/对象构建 | `fabric-object-builder-api-v1` 在 26.3 分支存在 | [GitHub API](https://api.github.com/repos/FabricMC/fabric/contents/fabric-object-builder-api-v1?ref=26.3) |
| 假玩家（关卡 5） | Fabric API 提供 `net.fabricmc.fabric.api.entity.FakePlayer`；CC:Tweaked 26.3 直接继承它 | [CC FakePlayer.java](https://github.com/cc-tweaked/CC-Tweaked/blob/v26.3-1.120.3/projects/fabric/src/main/java/dan200/computercraft/shared/platform/FakePlayer.java) |

**未验证**：26.3 的 vanilla `/test` 命令、GameTest 注解/运行器在 26.3 的确切形态；需在关卡 1 之后用最小 GameTest 验证。

## 4. 仍需实际构建才能回答的问题

1. Loom 1.19.3 + Gradle 9.8.2 + JDK 25 的 configure/build（当前被沙箱 `Files.isWritable` 失真阻断）。
2. Loom 1.19.3 是否接受 Fabric Loader 0.20.1（Loom 不直接锁定 Loader，但需实测）。
3. `fabric-api-0.165.0+26.3.jar` 的实际 `fabricloader` / `minecraft` 依赖范围。
4. 26.3 的映射选择与 CC:Tweaked 参考代码（`setPosRaw`、`containerMenu` 等内部名）是否一致。
5. Loom 是否向项目注入远程仓库（影响 C2 的“无远程声明”）。
6. Loom 在 `--offline` 且缓存完整时是否完全不发起网络请求（C2 的核心约束）。
7. Gradle Wrapper 是否接受项目相对的 `file:` `distributionUrl`。

## 5. 升级规则

- 只有满足下列之一才允许改版本：官方 26.3 配套声明变化；当前组合被实测证明不可行；安全修复。
- 任何版本变更必须同时更新：`gradle/libs.versions.toml`、`docs/version-baseline.md`、`docs/provenance/artifacts.json`，并重跑离线验收。
