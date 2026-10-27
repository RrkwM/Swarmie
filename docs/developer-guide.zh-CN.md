[English](developer-guide.md) | 简体中文

# Developer Guide

> Swarmie `0.1.0` / Adapter contract `0.1`。这是 pre-1.0 项目，公共契约尚未稳定。

## 1. 技术栈

| 组件 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Loom | 1.19.3 |
| Gradle | 9.8.2 |
| Java | 25 |

版本来源：`gradle/libs.versions.toml` 和 `gradle.properties`。

## 2. 仓库结构

```text
contract/                        纯 Java 契约子项目
  src/main/java/dev/swarmie/contract/
  src/test/java/dev/swarmie/contract/

src/main/java/dev/swarmie/
  SwarmieMod.java                入口、EntityType、Fabric 事件
  SwarmieEntity.java             真实 Entity 身体
  SwarmieFlightController.java   速度控制
  runtime/                       FIFO、命令状态机
  interaction/                   FakePlayer 原生交互
  inventory/                     9 槽库存
  query/                         世界查询
  sensor/                        本地传感器
  command/                       /swarmie 调试命令
  adapter/
    registry/                    机器人注册表
    event/                       有界事件环、事件总线
    io/                          入站队列/幂等
    transport/                   默认关闭的 localhost JSONL

src/test/                        主 Mod JUnit
docs/                            规范、证据、交接
.github/workflows/ci.yml          CI
```

## 3. 本地开发

需要 JDK 25，不需要项目内 `tools/` 工具链：

```bash
git clone https://github.com/RrkwM/Swarmie.git
cd Swarmie
./gradlew test
./gradlew build
./gradlew runClient
```

Windows：

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runClient
```

可选快速进入测试世界：

```powershell
.\gradlew.bat runClient -PswarmieQuickPlay="New World"
```

产物：

```text
build/libs/swarmie-0.1.0.jar
```

## 4. 分层架构

```text
Minecraft layer
  Entity / ServerLevel / native move & collision / blocks & items
        │
Runtime + capabilities
  SwarmieRuntime FIFO / InteractionActor / WorldQuery / LocalSensor
        │
Adapter layer
  contract / registry / event store / inbound-outbound queues / transport
        │
Future external control plane
```

硬边界：

- Minecraft 负责位置、碰撞、方块/物品原生行为；
- Runtime 只调度命令，不遍历世界，不持有 FakePlayer；
- Contract 包不得 import `net.minecraft.*` 或 `net.fabricmc.*`；
- 外部控制通过 Adapter，不直接引用 `SwarmieEntity`；
- 经典调试命令（`velocity`、`inventory`、`query`、`sensor`）不是公共契约。

## 5. 契约与 Adapter

### Contract

`:contract` 是独立纯 Java 子项目，主 Mod 使用：

```kotlin
implementation(project(":contract"))
```

当前四个命令：

```java
CommandSpec.TimedFlight
CommandSpec.Stop
CommandSpec.MineBlock
CommandSpec.UseItemOnBlock
```

核心 DTO：`RobotId`、`CommandSubmission`、`SubmitAck`、`CommandResult`、
`CancelAck`、`RobotEvent`、`EventBatch`。

### Submit

```java
RobotId robot = new RobotId(uuid);
SubmitAck ack = SwarmieAdapter.submit(
        robot,
        new CommandSpec.Stop(),
        "my-correlation-id");
```

- `ACCEPTED` 只表示入站接受，不是执行保证；
- 相同 correlation/robot/command 返回 `DUPLICATE`；
- 内容冲突返回 `CORRELATION_CONFLICT`；
- Runtime 满/卸载等终态以事件为准。

### Events

```java
EventBatch batch = SwarmieAdapter.readEvents(lastSequence, 64);
AdapterEventBus.Subscription sub = SwarmieAdapter.subscribe(b -> {
    // 必须快速返回；不要在这里做 socket/磁盘/阻塞 I/O
});
```

- `serverEpoch` 区分服务器会话；
- `sequence` 在 epoch 内连续递增；
- `missed` 表示消费者游标早于最旧保留事件；
- 事件环容量 1024，溢出会覆盖最旧事件。

### Cancel

```java
CancelAck ack = SwarmieAdapter.cancel(robot, "my-correlation-id");
```

`ACCEPTED` 只表示取消请求已排队；终态为 `COMMAND_CANCELLED`。
未知 correlation 返回 `UNKNOWN`，已终态返回 `ALREADY_TERMINAL`。

## 6. 线程与生命周期

- `SwarmieAdapter.submit/cancel` 可从任意线程调用；
- 提交只做纯数据校验、registry 快照、幂等检查和有界入队；
- 所有实体/Runtime 操作在 `START_SERVER_TICK` 的服务器线程执行；
- `END_SERVER_TICK` 发布事件；
- 监听器异常被隔离，但监听器必须非阻塞；
- 卸载时 Runtime active/queued 命令和入站队列命令都会终止；
- 重载不会恢复旧命令；
- 维度切换不产生 `REMOVED`，而是 `ROBOT_DIMENSION_CHANGED` + 重新加载。

详细规则：

- [`adapter-registry.md`](adapter-registry.zh-CN.md)
- [`adapter-io.md`](adapter-io.zh-CN.md)
- [`adapter-command-specs.md`](adapter-command-specs.zh-CN.md)

## 7. 测试与证据

```bash
./gradlew test
```

当前基线：43 项 JUnit。

测试分布：

- `contract/`：契约、batch、cancel；
- `adapter/event`：事件环、bus；
- `adapter/io`：入站容量/预算/幂等/冲突/并发；
- `adapter/registry`：生命周期三态、维度切换；
- `runtime`：FIFO、取消、卸载中断、事件；
- `interaction`、`sensing`、`flight`：既有能力非回归。

不要用 JUnit 通过替代真实客户端验证。证据分层和未验证项见
[`test-matrix.md`](test-matrix.zh-CN.md)。

## 8. 开发者调试

### `/swarmie` 调试命令

- `status/results/cancel/cancelid/cancelall`
- `mine/use`
- `inventory give/show`
- `query block/region`
- `sensor scan`
- `velocity`

这些是开发/管理工具，不是正式 Adapter API。

### localhost JSONL

默认关闭。启用方式见 [`local-transport.md`](local-transport.zh-CN.md)：

```text
run/config/swarmie-transport.json
```

只监听 `127.0.0.1`，无认证/TLS/远程安全模型。Python 客户端：

```bash
python tools/client/swarmie_client.py --host 127.0.0.1 --port 25580
```

### Adapter 诊断

```text
/swarmie adapter status
/swarmie adapter events <after> <max>
/swarmie adapter submit <target> stop <correlation>
```

## 9. 扩展点与约束

当前可替换：

- `WorldQuery` / `DefaultWorldQuery`；
- `LocalSensor` / `DefaultLocalSensor`；
- `InteractionHandler` / `InteractionActor`；
- Adapter 契约、registry、events、io、transport 的内部实现。

当前**没有**通用 capability registry、脚本运行时、全局调度器或网络服务。
新增正式命令需要同时更新：

1. `CommandSpec` / `CommandType`；
2. contract 测试；
3. Runtime command 与 Adapter 分发；
4. 命令规格和测试矩阵；
5. 版本号/CHANGELOG（如改变公共行为）。

不要：

- 用 `setBlock(AIR)` 代替原生挖掘；
- 伪造 FakePlayer 的完整连接；
- 把调试命令当公共 API；
- 在服务器 tick 线程做 socket/磁盘/阻塞等待；
- 把 API `SUCCESS` 直接宣称为世界副作用完成。

## 10. 版本与发布

遵循 Semantic Versioning，见 [`versioning.md`](versioning.zh-CN.md)。

发布流程概要：

1. 更新 `mod_version`，去掉 `-SNAPSHOT`；
2. 更新 `CHANGELOG.md`；
3. `./gradlew test build`；
4. CI 通过；
5. 打 annotated tag `vX.Y.Z`；
6. GitHub Release 附带 JAR + `.sha256`。

## 11. 阅读顺序

1. [`player-guide.md`](player-guide.zh-CN.md)
2. [`adapter-contract.md`](adapter-contract.zh-CN.md)
3. [`adapter-registry.md`](adapter-registry.zh-CN.md)
4. [`adapter-io.md`](adapter-io.zh-CN.md)
5. [`adapter-command-specs.md`](adapter-command-specs.zh-CN.md)
6. [`test-matrix.md`](test-matrix.zh-CN.md)
7. [`versioning.md`](versioning.zh-CN.md)


