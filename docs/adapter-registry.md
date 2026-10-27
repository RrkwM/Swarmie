# Adapter Registry, Lifecycle and Event Store

> Prompt 2。实现位于 `dev.swarmie.adapter`、`dev.swarmie.adapter.registry`、
> `dev.swarmie.adapter.event`。本文记录实现语义与真实客户端观察结果。
>
> 本轮不实现网络、入站命令通道或批量提交。

## 1. 服务器会话

每个 `SERVER_STARTED` 创建一个 `AdapterSession`：

- `serverEpoch`：随机正 63 位值（SecureRandom），不依赖系统时钟单调性；
- `RobotRegistry`：机器人身份和最后已知元数据；
- `AdapterEventStore`：有界环形事件存储；
- 已加载实体的临时查找表：只保存当前 LOADED 实体，卸载/移除时立即移除。

`SERVER_STOPPING` 会：

1. 对当前 loaded 实体执行 unload；
2. 写入未完成命令的 `INTERRUPTED` 事件；
3. 清除实体查找表；
4. 丢弃事件环（不承诺跨服务器进程重启保留历史）。

## 2. 注册表

键：机器人 UUID（`RobotId`）。

状态：

```text
LOADED
UNLOADED
REMOVED
```

记录内容（`RobotDescriptor`）：

```text
RobotId
dimensionId
Vec3Dto position
RobotLifecycleState lifecycle
Set<CommandType> supportedCommands
long lastUpdatedTick
```

约束：

- 区块卸载不会删除记录；
- 记录不保存 `Entity`/`Level` 强引用；
- 已移除记录最多保留 `RobotRegistry.MAX_REMOVED_RECORDS = 256` 条；
- 可变状态只在服务器线程修改；
- 外部读取 `RobotRegistry.snapshot()` 的不可变列表，不直接读内部 Map；
- 同一 UUID 重新加载不产生重复记录。

## 3. 生命周期分类

### 加载

`ServerEntityEvents.ENTITY_LOAD` → `AdapterSession.onEntityLoad`：

- `LOADED`；
- 写入 `ROBOT_LOADED`；
- 如果之前是 `UNLOADED` 且 pending dimension change，则 detail=`dimension change complete`；
- 注册 Runtime listener。

### 区块卸载

`ServerEntityEvents.ENTITY_UNLOAD` → `AdapterSession.onEntityUnload`：

- 先终止 Runtime 中 active/queued 命令，写入 `COMMAND_INTERRUPTED`；
- 关闭 `InteractionActor`；
- 写入 `ROBOT_UNLOADED`；
- 保留记录的 `UNLOADED` 状态和最后位置；
- 不把卸载误判为永久移除。

### 永久移除

`SwarmieEntity.onRemoval(reason)`，且 reason 不是
`UNLOADED_TO_CHUNK`/`UNLOADED_WITH_PLAYER`：

- `KILLED`/`DISCARDED` → 写入 `ROBOT_REMOVED`，状态 `REMOVED`；
- `CHANGED_DIMENSION` → 不写 REMOVED；写 `ROBOT_DIMENSION_CHANGED`，状态 `UNLOADED`，
  记录 pending dimension change；
- 先终止 Runtime 中未完成命令。

事件顺序在真实测试中观察到：

```text
COMMAND_INTERRUPTED
ROBOT_UNLOADED
ROBOT_DIMENSION_CHANGED
... 新实体加载 ...
ROBOT_LOADED (detail=dimension change complete)
```

`ROBOT_UNLOADED` 与 `ROBOT_DIMENSION_CHANGED` 同时出现是允许的；后者表明原因，
前者表明实体当前不可用。

**`ROBOT_UNLOADED` 不承诺机器人会回来。** 普通区块卸载可能随后重载；KILLED/DISCARDED
也可能在同一生命周期中紧接着产生 `ROBOT_REMOVED`。控制端不得仅凭 `UNLOADED` 判断
机器人稍后会恢复，必须以 `REMOVED`/epoch/后续加载事件为准。

## 4. 事件环

`AdapterEventStore`：

- 容量 `DEFAULT_CAPACITY = 1024`；
- `eventSequence` 从 1 开始，在 `serverEpoch` 内单调递增；
- `serverEpoch` 写入每个事件；
- 环形覆盖最旧事件；
- 读取 API：

```java
EventBatch batch = SwarmieAdapter.readEvents(afterSequence, maxEvents);
```

`EventBatch` 字段：

```text
serverEpoch
afterSequence
oldestAvailableSequence
latestSequence
nextSequence
missed
events
```

消费者根据：

- `missed=true`：请求游标早于最旧可用事件，已发生覆盖；
- `oldestAvailableSequence/latestSequence`：判断断档；
- `nextSequence`：下一次读取游标（若被 maxEvents 截断则为下一条）。

事件不静默丢弃；容量溢出通过 `missed` 和序号暴露。

## 5. Runtime 观察

`SwarmieRuntime.Listener` 提供：

```text
onCommandAccepted
onCommandStarted
onCommandTerminal
```

Runtime 在队列接受、开始、终态时通知；listener 异常被隔离，不改变命令行为。
Adapter 事件：

```text
COMMAND_ACCEPTED
COMMAND_STARTED
COMMAND_COMPLETED / FAILED / CANCELLED / INTERRUPTED
```

`correlationId` 原样保留；内部命令 ID 不替代 correlationId。

## 6. 调试观察接口

开发命令（不属于公共契约）：

```text
/swarmie adapter status
/swarmie adapter events <after> <max>
```

`status` 输出 epoch、LOADED/UNLOADED/REMOVED 数量、前 8 个机器人；
`events` 输出序号、类型、UUID、correlation、命令状态和 detail。

## 7. 真实客户端验证

版本：Minecraft 26.3 / Fabric Loader 0.20.1 / Fabric API 0.165.0+26.3。

测试世界：`New World`，已有 3 个 Swarmie；另 summon 1 个。

### 加载与卸载

- 初始 3 个 + summon 1 个 → `robots=4 loaded=4`，事件 1–4 为 ROBOT_LOADED；
- 提交 4 条 1000-tick timed flight → 4 ACCEPTED + 4 STARTED；
- 玩家传送到远处（`/tp @s 2000 100 2000`）→ 4 个机器人 chunk unload；
- 观察到每个机器人：

```text
COMMAND_INTERRUPTED (detail=robot unloaded)
ROBOT_UNLOADED (detail=chunk unload)
```

- `status`：`loaded=0 unloaded=4`；
- 返回原点后，同一 4 个 UUID 重新 LOADED，`robots=4`，无重复记录；
- 中断命令没有恢复，符合“重载不恢复旧命令”。

### 维度切换

在 overworld 执行：

```text
/execute in minecraft:the_nether run tp @e[type=swarmie:swarmie] 0 64 0
```

随后玩家进入 nether。观察到：

- 4 个旧实体 `ROBOT_UNLOADED`；
- 随后 4 个 `ROBOT_DIMENSION_CHANGED`，没有 `ROBOT_REMOVED`；
- 新实体加载，UUID 不变；
- `dimensionChange=true`，维度为 `minecraft:the_nether`，坐标 `(0.5, 64, 0.5)`；
- 事件序号连续；
- 没有无条件记为永久删除；
- 维度切换时 active `TIMED_FLIGHT` 实际产生：

```text
COMMAND_STARTED
ROBOT_DIMENSION_CHANGED
COMMAND_INTERRUPTED state=INTERRUPTED detail=robot unloaded
```

queued 命令同样在 Runtime 终止时产生 `COMMAND_INTERRUPTED`，不会恢复。

### 永久移除

```text
/kill @e[type=swarmie:swarmie]
```

观察到：

```text
ROBOT_UNLOADED (chunk unload)
ROBOT_REMOVED (detail=KILLED)
```

最终 `robots=4 loaded=0 unloaded=0 removed=4`，事件 37–44 连续。
`REMOVED` 与普通 `UNLOADED` 明确区分。

### 数量

- 事件：1–44，未发生容量溢出；
- 注册表：4 个 UUID，无重复；
- 命令：4 条 accepted/started/interrupted；
- 上述观察来自实际客户端日志与 `/swarmie adapter status/events` 输出。

## 8. 已知限制

- `serverEpoch` 在客户端重启/新 integrated server 会话中实际变化：
  `1793118649024` → `1793119217468`。
- 事件环 1024 尚未在真实游戏中填满；溢出语义由 JUnit 小容量测试验证。
- 注册表/事件存储不持久化到磁盘。
- 入站/出站/幂等/batch 已在 Prompt 3 实现，见 `docs/adapter-io.md`。
