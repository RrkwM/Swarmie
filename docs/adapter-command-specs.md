# Adapter Command Specifications (0.1)

> 忠实反映 `SwarmieRuntime`、`InteractionActor` 和 `AdapterSession` 的当前行为。
> 未实现的能力不会写成已完成；`SUCCESS` 返回值不等同于已确认世界副作用。

## 1. 公共命令提交

```java
SwarmieAdapter.submit(robotId, commandSpec, correlationId)
```

- 入站接受：`SubmitAck.ACCEPTED`，`state=QUEUED`，internalCommandId 可为空；
- 重复 correlation：`AckStatus.DUPLICATE`，返回已知 internalCommandId/state；
- 冲突：`AckStatus.REJECTED` + `CORRELATION_CONFLICT`；
- 入站满：`INBOX_FULL`；
- 机器人不存在/未加载/已移除：`ROBOT_NOT_FOUND`/`ROBOT_NOT_LOADED`/`ROBOT_REMOVED`。

事件顺序：

```text
COMMAND_ENQUEUED  (START tick 从入站取出)
COMMAND_ACCEPTED  (Runtime queue 接受)
COMMAND_STARTED   (开始执行)
COMMAND_COMPLETED/FAILED/CANCELLED/INTERRUPTED
```

## 2. TimedFlight

| 项目 | 规格 |
|---|---|
| 参数 | `velocity: Vec3Dto`，有限；`durationTicks: 1..6000` |
| 前置条件 | 机器人已加载、Runtime 未终止；不检查目标点/区块（速度控制命令） |
| 入队 | 进入该机器人 Runtime FIFO，最多 16 条 pending |
| 开始 | 下一个 Runtime tick `setDesiredVelocity(velocity)` |
| 耗时 | `durationTicks` 个执行 tick；之后调用 `stopFlight()`，等待速度 ≤ `0.01` |
| 成功 | 停止阶段速度降到阈值内，结果 `timed flight complete` |
| 失败 | Runtime 异常/编程异常 → `FAILED`；无目标点失败模式 |
| 取消 | queued：未执行；active：立即 `stopFlight()`，可能已经移动过 |
| 副作用 | 改变机器人速度/位置（由原生 `Entity.move` 和碰撞决定） |
| 中断 | 实体卸载/移除 → `INTERRUPTED`，不恢复 |
| 幂等 | 相同 correlation 不重复执行；新 correlation 可重试 |
| 超时 | 仅 duration 上限；没有额外 watchdog |
| 事件 | accepted/started/completed/failed/cancelled/interrupted；correlation 保留 |

已知限制：没有 MoveTo/寻路/避障；碰撞可能让速度提前归零；高速/斜向/台阶/天花板
边界未完全验证。

## 3. Stop

| 项目 | 规格 |
|---|---|
| 参数 | 无 |
| 前置条件 | 已加载、Runtime 未终止 |
| 开始 | `stopFlight()` |
| 成功 | 下一 tick 速度 ≤ `0.01`；否则继续等待 |
| 失败 | 无预期失败；异常 → `FAILED` |
| 取消 | queued 未执行；active 再次 stop，然后 `CANCELLED` |
| 副作用 | 减速/停止机器人 |
| 中断 | 卸载/移除 → `INTERRUPTED` |
| 耗时 | 取决于当前速度/阻尼；没有硬超时 |
| 事件 | 同上 |

## 4. MineBlock

| 项目 | 规格 |
|---|---|
| 参数 | `BlockPosDto` 绝对方块坐标 |
| 前置条件 | 已加载；目标区块已加载；世界边界内；距离 ≤ 6.0；方块有效 |
| 执行 | 在 Runtime start 时同步调用原生 `FakePlayer.gameMode.destroyBlock` |
| 成功 | 原生返回 `true`；命令在下一 tick 标记 `SUCCEEDED` |
| 失败 | 空气/保护/未加载/越界/距离/原生 false → `FAILED`（`EXECUTION_FAILED`） |
| 副作用 | 方块移除；掉落物生成在世界中；**不自动进入机器人库存** |
| 工具耐久 | 由原生 fake player/物品逻辑处理（基线实测铁镐耐久变化） |
| 取消 | start 前可取消；同步破坏已经开始后不能回滚 |
| 重试 | 失败后用新 correlation 再提交；相同 correlation 返回原终态 |
| 中断 | 卸载/移除期间尚未开始的命令 → `INTERRUPTED` |

`destroyBlock` 返回 true 仍只表示原生调用被接受；保护插件/第三方方块可能产生
不同结果。掉落物自动拾取未实现。

## 5. UseItemOnBlock

| 项目 | 规格 |
|---|---|
| 参数 | `BlockPosDto position`、`DirectionDto face`、`Vec3Dto hit` |
| 前置条件 | 已加载；目标区块加载；边界/距离 ≤ 6.0；face/hit 有效 |
| 执行 | 同步调用原生 `FakePlayer.gameMode.useItemOn(...)`，使用机器人当前选中槽 |
| 成功 | 原生返回非 `PASS` 的 success（不是 `SUCCESS` 字符串）；下一 tick `SUCCEEDED` |
| 失败 | 空气/未加载/距离/face/null/原生 `PASS` → `FAILED` |
| 副作用 | 可能放置方块、消耗物品、点火、切换 lever；由具体物品/方块决定 |
| 容器 | **不保证打开 GUI**：FakePlayer `openMenu` 是 no-op；chest 可返回 SUCCESS 但内容不变 |
| 取消 | 同步交互开始后不能回滚 |
| 重试 | 新 correlation；重复 correlation 不重复执行 |
| 中断 | 同 MineBlock |

**已知容器限制**：标准 GUI 容器（chest/furnace/hopper/模组机器）不能通过当前
FakePlayer 路径实际打开。服务端 `MenuProvider.createMenu + AbstractContainerMenu.clicked`
路径已调查，但未实现、未 live 验证。不得把 `useItemOn` 返回值宣称为容器操作完成。

## 6. 取消

- 公共 API：`SwarmieAdapter.cancel(robotId, correlationId)` → `CancelAck`；
- 未知 correlation：`UNKNOWN` + `UNKNOWN_CORRELATION`；
- 已终态：`ALREADY_TERMINAL` + 原 `CommandState`；
- queued 取消：`ACCEPTED`，在服务器 tick 移除并入站/ Runtime 队列，产生
  `COMMAND_CANCELLED`；
- active TimedFlight 取消：调用 `stopFlight()`，已经发生的位移不可回滚；
- active Mine/Use 是同步执行，取消只能发生在 start 前；执行开始后不能中断；
- 取消是异步的：`CancelAck.ACCEPTED` 只表示请求已排队，终态以事件为准；
- cancel 队列上限 64，满时 `BACKPRESSURE`；
- 取消终态事件为 `COMMAND_CANCELLED`，已接受命令不会静默消失。

实测：

```text
active long flight -> CANCEL ACCEPTED state=QUEUED
                   -> COMMAND_CANCELLED state=CANCELLED message=cancelled while active
unknown            -> UNKNOWN + UNKNOWN_CORRELATION
terminal           -> ALREADY_TERMINAL + state=CANCELLED
```

## 7. 异常、超时、重试

- Runtime 捕获命令异常：`FAILED`；listener 异常被隔离；
- 未分类异常 → `INTERNAL_ERROR`，编程异常日志；
- 除 TimedFlight duration 外没有通用命令超时；`TIMEOUT` 错误码已定义但未使用；
- 不自动重试；重试产生新 correlation，旧 correlation 不重新执行；
- 卸载/移除 → `INTERRUPTED`；重载不恢复旧命令；
- 机器人 Runtime 队列满 → 入站命令终态 `FAILED` + `QUEUE_FULL`；
- 全局入站队列满 → `INBOX_FULL`，请求不进入 Runtime。

## 8. 测试证据

自动化：

- Runtime FIFO、排队/active 取消、历史、卸载中断；
- 并发 correlation 幂等、冲突、容量、batch 上限；
- 事件顺序、容量溢出、missed、监听器异常隔离。

真实客户端：

- 飞行 20 ticks 成功；stop 成功；查询/传感器；
- 铁镐挖掘石头成功、空气目标失败；
- 石头 `useItemOn` 放置成功：`USE_ITEM_ON_BLOCK SUCCEEDED`，目标位置变为 stone，
  机器人库存消耗为 empty；
- Adapter submit stop accepted → duplicate SUCCEEDED → conflict rejected；
- Python local transport：mine/fly/stop/cancel/duplicate/reconnect 成功；
- 加载/卸载/重载/维度/ KILLED 移除；
- FakePlayer 容器限制仍为已知限制。

没有把未执行的客户端行为标记为通过；完整矩阵见 `docs/test-matrix.md`。
