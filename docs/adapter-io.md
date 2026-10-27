# Adapter Local I/O — Prompt 3

> 本地 Adapter；可选 localhost JSONL 仅用于开发调试，默认关闭。
> 目标：外部线程可以安全提交，Minecraft 只在服务器线程执行。

## 1. 公共入口

```java
SubmitAck ack = SwarmieAdapter.submit(robotId, commandSpec, correlationId);
BatchSubmitAck batch = SwarmieAdapter.submitBatch(new BatchSubmission(submissions));
AdapterEventBus.Subscription sub = SwarmieAdapter.subscribe(batch -> { ... });
```

开发诊断命令（不属于公共契约）：

```text
/swarmie adapter submit <target> stop <correlation>
/swarmie adapter submit <target> fly <x> <y> <z> <duration> <correlation>
```

## 2. 线程模型

- `submit`/`submitBatch` 可从任意线程调用；
- 提交线程只做：纯数据校验、registry 快照查询、幂等检查、线程安全入队；
- 不读取/修改 `Entity`、`Level`、`Runtime`、`FakePlayer`；
- 所有 Runtime 操作在 `START_SERVER_TICK` 的服务器线程执行；
- 事件存储在服务器线程写入；
- `END_SERVER_TICK` 将新事件发布给订阅者；
- 订阅者必须快速返回；禁止 socket、磁盘、外部进程等待等阻塞 I/O。

## 3. 有界入站

`InboundQueue`：

| 项目 | 值 |
|---|---:|
| 全局收件容量 | 256 |
| 每 tick 处理预算 | 32 |
| 幂等记录上限 | 1024 |
| 幂等终态 TTL | 600 秒 |
| cancel 队列上限 | 64 |
| 批次上限 | 64 |

- 队列满：拒绝，`INBOX_FULL`；不会无限积压；
- Runtime 每机器人队列满：入站已被接受，但会写终态 `FAILED` + `QUEUE_FULL`；
- 失败/拒绝都有结构化 `SubmitAck` 或终态事件；
- 被 Adapter 接受的命令最终必须产生可观察终态，不能静默消失。

## 4. 状态区分

```text
INBOX_ACCEPTED        SubmitAck.ACCEPTED，internalCommandId 可为空
RUNTIME_ACCEPTED      COMMAND_ACCEPTED（Runtime listener）
STARTED               COMMAND_STARTED
TERMINAL              COMPLETED/FAILED/CANCELLED/INTERRUPTED
```

`COMMAND_ENQUEUED` 记录“入站已接受并在服务器 tick 取出”；它早于 Runtime
accepted。`SubmitAck` 不是终态。

**重要**：`SubmitAck.ACCEPTED` 只表示入站/边界的接受，不代表命令一定执行。
Runtime 队列满等情况下，之后会收到 `FAILED/QUEUE_FULL` 终态；控制端必须以事件/
最终 `CommandResult` 为准，不能把 ack 当作执行保证。

## 5. correlationId 与幂等

- 同一 `correlationId` + 同一机器人 + 同一 `CommandSpec`：
  - 返回 `AckStatus.DUPLICATE`；
  - 返回已知 internalCommandId/CommandState；
  - 不重复执行。
- 同一 `correlationId` 不同机器人/命令：
  - 拒绝，`CORRELATION_CONFLICT`；
- 检查、登记、入队由 `InboundQueue` 的同一个 `synchronized` 临界区完成；
- 并发相同 correlation 只有一个 `ACCEPTED`，其余 `DUPLICATE`（JUnit 8 线程测试）；
- 幂等记录容量 1024；
- 仅 **终态** 记录按 600 秒 TTL 过期；
- QUEUED/RUNNING 记录不会因 TTL 删除，最多受入站容量限制；
- 容量压力下优先淘汰最旧终态记录；不会淘汰仍在执行的记录；
- 过期的终态 correlation 会被视为新请求（这是明确的边界）；
- 不承诺跨服务器重启保留幂等记录。

## 6. 公共取消

```java
CancelAck ack = SwarmieAdapter.cancel(robotId, correlationId);
```

语义：

- 未知 correlation：`CancelStatus.UNKNOWN` + `UNKNOWN_CORRELATION`；
- 已终态：`ALREADY_TERMINAL`，带原 `CommandState`；
- 已接受：`ACCEPTED`，在 START tick 处理；
  - 入站尚未进入 Runtime：移除并写 `COMMAND_CANCELLED`；
  - Runtime active/queued：调用 Runtime.cancel，listener 写 `COMMAND_CANCELLED`；
- cancel 队列上限 64；满时 `BACKPRESSURE`；
- 取消是异步的：ack 只表示请求已排队，终态以事件为准。

实机验证：

```text
active long flight -> cancel
→ CANCEL ACCEPTED state=QUEUED
→ COMMAND_CANCELLED state=CANCELLED message=cancelled while active
```

未知/已终态：

```text
UNKNOWN + UNKNOWN_CORRELATION
ALREADY_TERMINAL + state=CANCELLED
```

## 7. 批量

- `BatchSubmission` 最大 64 条；空批次/超限在构造时拒绝；
- 逐条 ack，不做原子批；
- 每条独立 correlation、幂等和终态；
- 受入站队列容量和 tick budget 约束；
- 不会绕过线程安全，也不会在一个 tick 中无预算分发整批。

## 8. 出站事件

- `AdapterEventStore` 是有界、可检测遗漏的唯一事件源；
- `AdapterEventBus` 在 END tick 发布 `EventBatch`；
- 同一 listener 重复订阅返回同一个 Subscription，不重复收到事件；
- 一个 listener 抛异常被隔离，不影响其他 listener；
- 慢消费者通过 `missed`/序号检测，而不是阻塞服务器；
- 网络适配器未来应在自己的线程消费并有界发送。

## 9. 卸载与未进入 Runtime 的命令

`AdapterSession.onEntityUnload`：

1. 终止 Runtime active/queued 命令 → `COMMAND_INTERRUPTED`；
2. 从入站队列移除该机器人的命令；
3. 对每一条写入 `COMMAND_INTERRUPTED`（`ROBOT_UNLOADED`），更新幂等记录；
4. 写 `ROBOT_UNLOADED`。

重载不会恢复这些命令。

## 10. 调试命令差异

`velocity`、`inventory give/show`、`query`、`sensor` 继续是直接 OP 调试工具：

- 不进入公共契约；
- 不产生 Adapter 命令事件；
- 仍直接调用现有 Minecraft/Runtime 能力。

`fly/stop/mine/use` 的旧 `/swarmie` 路径也保留为直接 Runtime 调试入口；
它们与 Adapter 路径的差异：

- 旧路径绕过入站队列、幂等和 Adapter correlation；
- 旧路径仍通过 Runtime listener 产生命令事件（correlation=null）；
- 正式外部 API 应使用 `SwarmieAdapter.submit`。

## 11. 测试

- `InboundQueueTest`：容量、budget、重复、冲突、并发；
- `AdapterEventBusTest`：重复订阅、异常隔离；
- `AdapterEventStoreTest`：序号、溢出、missed；
- `SwarmieRuntimeEventTest`：accepted/started/terminal、卸载中断；
- 真实客户端：见 `docs/adapter-registry.md` 的加载/卸载/维度测试，以及最终回归矩阵。

### 真实客户端 Prompt 3 验证

当前客户端 nether 世界，1 个机器人：

```text
/swarmie adapter submit @e[type=swarmie:swarmie,limit=1] stop manual-stop-1
→ Adapter ACCEPTED corr=manual-stop-1 state=QUEUED

同一 correlation 再提交
→ Adapter DUPLICATE corr=manual-stop-1 state=SUCCEEDED
  duplicate correlationId; existing command state returned

同一 correlation 改成 fly
→ Adapter REJECTED corr=manual-stop-1 state=null
  error=CORRELATION_CONFLICT
```

事件：

```text
#1 ROBOT_LOADED
#2 COMMAND_ENQUEUED c=manual-stop-1
#3 COMMAND_ACCEPTED c=manual-stop-1 state=QUEUED
#4 COMMAND_STARTED c=manual-stop-1 state=RUNNING
#5 COMMAND_COMPLETED c=manual-stop-1 state=SUCCEEDED detail=stopped
```

说明：

- 从 Adapter submit 到 Runtime 执行、终态、幂等记录和事件序号实际贯通；
- 重复 correlation 没有再次执行；
- 冲突在入站边界拒绝；
- 未实机验证 batch（JUnit 覆盖），未实机填满 1024 事件环；
- 调试 submit 命令仅用于验证，不属于公共契约。

### 真实客户端 Prompt 3.5（Python）

本地 JSONL transport 实机验证：

```text
MINE_BLOCK SUCCEEDED
TIMED_FLIGHT SUCCEEDED
STOP SUCCEEDED
DUPLICATE state=SUCCEEDED
CANCEL ACCEPTED -> COMMAND_CANCELLED (cancelled while active)
UNKNOWN + UNKNOWN_CORRELATION
ALREADY_TERMINAL + state=CANCELLED
reconnect events: epoch=..., latest=..., missed=false
```

详见 [`local-transport.md`](local-transport.md) 与
[`tools/client/swarmie_client.py`](../tools/client/swarmie_client.py)。

## 12. 未实现

- 跨服务器进程幂等/事件持久化；
- 每个消费者的独立事件队列（当前靠序号检测遗漏）；
- 远程/公网传输、TLS、认证、多人安全模型。

本地 JSONL transport 已实现，但明确仅用于开发调试，默认关闭。
