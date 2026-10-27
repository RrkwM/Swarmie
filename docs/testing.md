# Testing and Verification

> 当前活动工作区：`<workspace>`
> 原工作区仅作为只读备份，不再参与日常构建或同步。

## 1. 证据分层

| 证据 | 含义 | 当前状态 |
|---|---|---|
| Gradle/JUnit 自动化测试 | 在 JVM 中执行真实生产类/纯数据逻辑 | 已建立并通过 |
| 静态编译/边界检查 | `javac`、契约 import 检查、JAR 内容检查 | 已通过 |
| 真实 Minecraft 客户端测试 | 启动 `runClient` 并在世界内观察行为 | 基线世界测试通过；Prompt 1 已重跑启动并确认契约类加载 |
| 未验证 | 没有执行过的行为 | 见 `docs/runtime-verification.md` |

三类证据不能互相替代。

## 2. 自动化测试基础

- JUnit：JUnit Jupiter `5.13.1` / Platform `1.13.1`
- 依赖位置：`tools/repo`（本地 Maven 布局，仅测试作用域）
- 根项目 `build.gradle.kts`：

```kotlin
testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.1")
testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.1")
testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.1")
testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.1")
```

- `test` 任务：`useJUnitPlatform()`；失败会返回非零退出码。
- 根 `test` 依赖 `:contract:test`；契约测试使用独立的、无 Minecraft/Fabric 的
  `:contract` 编译 classpath。
- 测试依赖不进入生产 Mod JAR。

契约隔离实测：

```text
.\gradlew.bat :contract:dependencies --configuration compileClasspath
→ compileClasspath: No dependencies
```

## 3. 已迁入版本控制的测试

```text
contract/src/test/java/dev/swarmie/contract/ContractTest.java
src/test/java/dev/swarmie/runtime/SwarmieRuntimeTest.java
src/test/java/dev/swarmie/runtime/SwarmieRuntimeEventTest.java
src/test/java/dev/swarmie/adapter/registry/RobotRegistryTest.java
src/test/java/dev/swarmie/adapter/event/AdapterEventStoreTest.java
src/test/java/dev/swarmie/adapter/event/AdapterEventBusTest.java
src/test/java/dev/swarmie/adapter/io/InboundQueueTest.java
src/test/java/dev/swarmie/interaction/InteractionRuntimeTest.java
src/test/java/dev/swarmie/sensing/SensingTest.java
src/test/java/dev/swarmie/flight/FlightControllerTest.java
```

覆盖：

- 契约版本、四条命令参数、提交/确认/结果/事件；
- Runtime FIFO、active/queued cancellation、cancel all、恢复、队列满、owner removal；
- 库存槽位、交互成功/失败、队列取消、缺少 handler；
- region volume/overflow、FOV、距离、结果上限、不可变结果；
- flight speed clamp、stop damping。

## 4. 执行命令与实际结果

```text
cd <workspace>
.\gradlew.bat test
```

实际结果（2026-10-28）：

```text
43 tests PASSED
BUILD SUCCESSFUL
```

测试类：

```text
ContractTest                  7 passed
AdapterEventBusTest           2 passed
AdapterEventStoreTest         3 passed
InboundQueueTest              8 passed
RobotRegistryTest             4 passed
FlightControllerTest          2 passed
InteractionRuntimeTest        5 passed
SwarmieRuntimeEventTest       4 passed
SwarmieRuntimeTest            5 passed
SensingTest                   3 passed
```

总计 43 项（`:contract:test` 7 + 根 `test` 36）。

构建配置变更后曾执行：

```text
.\gradlew.bat --offline test
→ 首次因本地测试依赖元数据未进入 Gradle 缓存而失败
```

随后使用项目本地 `tools/repo` 正常解析（依赖不来自远程公共仓库），`test` 通过。
依赖进入缓存后，`--offline cleanTest test --no-build-cache` 也实际执行并通过 43 项测试。
本地依赖解析不改变 Gradle/Loom/Minecraft/JDK 版本。

`.\gradlew.bat test` 的当前结构：

```text
:contract:test   6 passed
:test           33 passed
```

## 5. 真实客户端测试

基线 JAR（`dd529fd8…`，79,806 bytes）已在真实客户端中验证世界行为：

- 实体 summon/渲染；
- X/Y/Z、stop、碰撞；
- Runtime FIFO/取消/失败恢复；
- 库存持久化、挖掘/耐久、放置/消耗；
- block/region query、传感器 FOV/超距/截断；
- 保存/重载、区块卸载/重载、active removal；
- GUI 容器限制。

完整记录：[`runtime-verification.md`](runtime-verification.md)。

Prompt 1 修改了契约子项目和启动日志，因此重新执行了一次 `runClient` 启动验证：

```text
Swarmie initialized; registered entity type swarmie:swarmie
Adapter contract loaded: v0.1
Backend library: LWJGL 3.4.3-snapshot
Sound engine started
```

Prompt 2/3 进一步在真实客户端中验证：

- 4 个机器人加载/卸载/重载 UUID 不变；
- 命令卸载 `INTERRUPTED`；
- overworld→nether 维度切换不产生 `ROBOT_REMOVED`；
- KILLED → `ROBOT_REMOVED`；
- `serverEpoch` 从 `1793118649024` 变为 `1793119217468`；
- Adapter submit stop 被接受，重复 correlation 返回 `DUPLICATE state=SUCCEEDED`，
  冲突返回 `CORRELATION_CONFLICT`；
- 事件 enqueued→accepted→started→completed 连续；
- 飞行、stop、block query、sensor、铁镐挖掘石头成功。

完整逐项证据见 [`test-matrix.md`](test-matrix.md) 与
[`adapter-registry.md`](adapter-registry.md)。

当前构建：

```text
build/libs/swarmie-0.1.0.jar
162,555 bytes
SHA-256 fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a
```

JAR 含 `dev/swarmie/contract/*.class`。基线 JAR 仍保留。

## 6. 已知测试限制

- 尚未建立 Fabric GameTest 源集/运行器；游戏世界内的生命周期/交互仍以人工客户端步骤为准。
- `:contract` 已建立，但契约 0.x 仍不稳定；入站/事件/注册表/cancel 已实现；
  事件环实机溢出、批量实机提交、慢消费者断连仍主要靠 JUnit/代码审查。
- 第二客户端、专用服务器、GUI 容器、LOS 仍未验证。
- 可选 Prompt 3.5 已实现 localhost JSONL + Python；默认关闭、仅 127.0.0.1；
  不声称支持远程/公网/多人安全模型。

## 7. 测试约定

- 不把“JUnit 通过”写成“真实客户端通过”。
- 不把“静态 import 检查”写成“运行时隔离”。
- 新增生产行为必须有对应自动化测试，或在文档中明确标为人工验证/未验证。
- 测试不得依赖未声明的本地路径；测试依赖必须能从 `tools/repo` 解析。




