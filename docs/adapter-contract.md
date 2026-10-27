# Adapter Contract 0.1

> 状态：Prompt 1（修订版）最小契约。**0.x 明确不稳定**，可在 Adapter 重构过程中调整。
>
> Gradle 子项目：`:contract`
>
> 包：`dev.swarmie.contract`
>
> 契约版本：`0.1`（`ContractVersion.CURRENT`）

## 1. 硬边界

`:contract` 是独立的纯 Java Gradle 子项目：

- `contract/build.gradle.kts` 只应用 `java-library`；
- 编译 classpath 不包含 Minecraft、Fabric、Loom 或游戏类型；
- 不依赖主 Mod 的类；
- 不依赖 `:contract` 之外的任何实现模块。

契约源码禁止：

```text
net.minecraft.*
net.fabricmc.*
```

检查命令：

```powershell
pwsh -NoProfile -File .\tools\scripts\check-contract-boundary.ps1
```

编译隔离不是 import 扫描替代的：`:contract:compileJava` 本身就是独立编译。

## 2. 版与兼容性

```text
ContractVersion.CURRENT = 0.1
```

0.x 规则：

- 当前只用于本项目内部 Adapter，不承诺正式外部兼容；
- major 不同：拒绝；
- peer minor 高于当前实现：拒绝；
- 未知字段：忽略；
- 缺失必填字段：拒绝；
- 未知命令类型/普通枚举：拒绝，不得猜测默认行为；
- 未知事件类型：消费方可映射为 `RobotEventType.UNKNOWN`，不得执行命令。

正式兼容性规则将在契约进入 1.x 时确定。

## 3. 只覆盖四条命令

`CommandType`：

```text
TIMED_FLIGHT
STOP
MINE_BLOCK
USE_ITEM_ON_BLOCK
```

`CommandSpec` 是 sealed interface：

| type | 字段 |
|---|---|
| `TIMED_FLIGHT` | `Vec3Dto velocity`、`int durationTicks` |
| `STOP` | 无 |
| `MINE_BLOCK` | `BlockPosDto position` |
| `USE_ITEM_ON_BLOCK` | `BlockPosDto position`、`DirectionDto face`、`Vec3Dto hit` |

约束：

- `durationTicks`：`1..6000`；
- 速度/命中点必须是有限浮点数；
- 方块坐标使用整数 `BlockPosDto`，不得传实体浮点坐标；
- `face` 六方向之一。

**明确不在契约中**（仍保留为 OP 调试工具，不产生 Adapter 命令事件）：

```text
/swarmie velocity
/swarmie inventory give
/swarmie inventory show
/swarmie query block
/swarmie query region
/swarmie sensor scan
```

它们不是正式 Adapter 命令，也不属于本轮公共 API。

## 4. 标识与坐标

`RobotId`：稳定机器人 UUID。

`BlockPosDto(int x, int y, int z)`：绝对方块整数坐标。

`Vec3Dto(double x, double y, double z)`：速度/命中点等活动坐标；单位由命令定义
（速度 blocks/tick，命中点 blocks）。

`DirectionDto`：`DOWN/UP/NORTH/SOUTH/EAST/WEST`。

## 5. 提交

`CommandSubmission`：

```text
RobotId robotId
String correlationId       // 1..128，调用方稳定关联键
CommandSpec command
```

`SubmitAck`：

```text
RobotId robotId
String correlationId
CommandType type
AckStatus status           // ACCEPTED / REJECTED / DUPLICATE
String internalCommandId?  // Runtime 接受后
CommandState state?        // 已知状态；ACCEPTED 初始 QUEUED
ErrorCode error?           // 拒绝时
String message
```

`ACCEPTED` 只表示 Adapter 收件边界接受，不等于 Runtime 已执行完成。
`DUPLICATE` 表示相同 correlation/robot/命令已存在，返回已知 internalCommandId 和
CommandState；`REJECTED` 的 conflict/backpressure 不走 Runtime。

批量：

```text
BatchSubmission(List<CommandSubmission>)   // 1..64
BatchSubmitAck(List<SubmitAck>)            // 逐条，不原子
```

取消：

```text
RobotId robotId
String correlationId
CancelStatus status       // ACCEPTED/REJECTED/ALREADY_TERMINAL/UNKNOWN
CommandState state?       // 已知状态
ErrorCode error?
String message
```

`CancelAck.ACCEPTED` 只表示取消请求已进入服务器线程队列；终态以
`COMMAND_CANCELLED` 事件为准。

## 6. 结果与状态

`CommandState`：

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
INTERRUPTED
```

`CommandResult`：

```text
RobotId robotId
String correlationId
String internalCommandId?
CommandState state
ErrorCode error?
String message
```

当前四条命令没有额外 query/sensor payload。`INTERRUPTED` 表示实体卸载/移除导致
无法继续；`CANCELLED` 表示显式取消。

## 7. 事件

`RobotEvent`：

```text
String eventId
long serverEpoch
long sequence
long serverTick
RobotEventType type
RobotId robotId
String correlationId?
CommandResult commandResult?
String detail?
```

`RobotEventType`：

```text
ROBOT_LOADED
ROBOT_UNLOADED
ROBOT_DIMENSION_CHANGED
ROBOT_REMOVED
COMMAND_ENQUEUED
COMMAND_ACCEPTED
COMMAND_STARTED
COMMAND_COMPLETED
COMMAND_FAILED
COMMAND_CANCELLED
COMMAND_INTERRUPTED
UNKNOWN
```

`serverEpoch` 在每个服务器会话唯一；`sequence` 在 epoch 内从 1 连续递增。
`EventBatch` 提供 `oldestAvailableSequence/latestSequence/nextSequence/missed` 供消费者
检测事件环覆盖。`COMMAND_ENQUEUED` 表示入站已接受并在服务器 tick 取出；
`COMMAND_ACCEPTED` 表示 Runtime 接受。

## 8. 错误码

```text
INVALID_ARGUMENT
INVALID_POSITION
INVALID_VECTOR
ROBOT_NOT_FOUND
ROBOT_NOT_LOADED
ROBOT_REMOVED
ROBOT_UNLOADED
ROBOT_UNAVAILABLE
QUEUE_FULL
INBOX_FULL
BATCH_TOO_LARGE
DUPLICATE_REQUEST
CORRELATION_CONFLICT
UNKNOWN_CORRELATION
ALREADY_TERMINAL
BACKPRESSURE
UNSUPPORTED_COMMAND
EXECUTION_FAILED
CANCELLED
INTERRUPTED
INTERNAL_ERROR
```

## 9. 序列化规则

本轮契约数据都是 record/enum/基本类型/UUID，按规范 JSON 映射：

- 字段使用 record 组件的 `camelCase` 名；
- UUID/资源标识符使用字符串；
- `CommandSpec` 使用 `type` 判别字段，字段平铺；
- 枚举使用 `name()` 字符串；
- 未知字段忽略；缺失必填字段拒绝；
- 未知命令类型/枚举拒绝；未知事件类型可映射 `UNKNOWN`；
- 0.x 不承诺跨版本 wire compatibility。

本轮不实现网络传输或完整 codec；后续 Adapter I/O 轮次负责实际编解码。

## 10. 打包与运行验证

主 Mod 通过：

```kotlin
implementation(project(":contract"))
```

使用 `:contract`；根 `jar` 任务将 `:contract` 的 main classes 合并进最终 Mod JAR。

构建后验证：

```text
jar tf build/libs/swarmie-0.1.0.jar | findstr dev/swarmie/contract
```

实际 JAR 包含 `dev/swarmie/contract/*.class`（20 个类 + 目录条目）。
`SwarmieMod` 启动时读取 `ContractVersion.CURRENT` 并记录版本，用于验证运行时
类加载。

实际验证（2026-10-27）：

```text
jar: build/libs/swarmie-0.1.0.jar
size: 162,555 bytes
sha256: fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a

client log:
  Swarmie initialized; registered entity type swarmie:swarmie
  Adapter contract loaded: v0.1
```

`runClient` exit 0；客户端到达 `Minecraft* 26.3` 后关闭。真实客户端世界内的
行为测试仍以基线证据为准，见 `docs/testing.md` 与
`docs/runtime-verification.md`。

## 11. 测试

- `:contract` 自己的 JUnit 测试：参数边界、ack/result/event、版本；
- 根 `test` 任务依赖 `:contract:test`；
- 契约模块独立编译 classpath；
- 不把 query/sensor/inventory/velocity 测试伪装成正式契约测试。

详细测试记录见 [`testing.md`](testing.md)。

## 12. 未实现

- 机器人注册表、事件存储、serverEpoch（Prompt 2，已实现）；
- 入站/出站通道、correlation 幂等、批量（Prompt 3，已实现）；
- 正式命令规格（Prompt 4，见 `adapter-command-specs.md`）；
- 网络传输、外部 Python 客户端（Prompt 3.5 已实现本地调试传输；不面向远程）；
- 容器 GUI 能力（已知限制）。



