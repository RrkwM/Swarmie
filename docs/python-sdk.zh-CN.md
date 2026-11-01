[English](python-sdk.md) | 简体中文

# Python SDK

> 状态：第一版（`0.1.2`），仅使用 Python 3.9+ 标准库，pre-1.0。
> 它使用现有的 localhost JSONL 开发传输，不是远程或带认证的接口。

## 1. 这是什么

SDK 是 Adapter JSONL 协议之上的一层轻量、类型化的 Python 封装：

- `SwarmieClient`：负责一条连接、机器人发现、事件和会话状态；
- `Robot`：绑定**一个明确 UUID**，不会隐式切换目标；
- `CommandHandle`：跟踪一条已提交命令，从确认到终态，包含取消与超时；
- `RobotInfo`、`CommandResult`、`EventBatch` 和类型化异常使失败显式可见。

支持的命令只有 Adapter 现有的四条：

```text
TIMED_FLIGHT
STOP
MINE_BLOCK
USE_ITEM_ON_BLOCK
```

SDK 不提供寻路、`MoveTo`、命名、库存、GUI 容器或传感器 API，因为 Adapter
没有这些命令。

## 2. 启用传输

传输**默认关闭**。在 Minecraft 运行目录创建 `config/swarmie-transport.json`：

```json
{
  "enabled": true,
  "port": 25580,
  "maxConnections": 4,
  "maxLineBytes": 8192,
  "maxOutboundEvents": 256
}
```

重启世界/客户端，日志应出现：

```text
Local JSONL transport listening on 127.0.0.1:25580
```

监听地址固定为 loopback，没有认证、TLS 或多用户安全模型；不要暴露到网络。
传输与协议细节见 [`local-transport.md`](local-transport.zh-CN.md)。

## 3. 导入

包位于 `tools/sdk/swarmie`，目前没有 `pip` 包。可在仓库脚本中：

```python
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # 示例中的 tools/sdk
from swarmie import SwarmieClient, TimedFlight, Vec3, Stop
```

或在 VS Code 中把 `tools/sdk` 加入 `PYTHONPATH`：

```json
{ "python.analysis.extraPaths": ["./tools/sdk"] }
```

可运行示例会自行处理路径。

## 4. 发现并选择机器人

```python
from swarmie import SwarmieClient, RobotNotFound

with SwarmieClient("127.0.0.1", 25580) as client:
    print(client.epoch, client.protocol)
    for info in client.robots():
        print(info.robot_id, info.lifecycle.value, info.dimension, info.position)

    robot = client.robot("3f2a...")  # 精确 UUID；不存在时抛 RobotNotFound
    print(robot.status().lifecycle.value)
```

`robot(uuid)` 查询注册表快照并精确匹配 UUID。SDK 不会默认选择第一台已加载
机器人。`UNLOADED` / `REMOVED` 会作为生命周期状态返回；对这类机器人提交会被
服务端明确拒绝（`ROBOT_NOT_LOADED` / `ROBOT_REMOVED`）。

`SwarmieClient.from_env()` 读取 `SWARMIE_HOST` 与 `SWARMIE_PORT`。
所有方法都是同步的；每个线程使用一个独立 client。

## 5. 提交、等待、查询

```python
from swarmie import TimedFlight, Vec3

handle = robot.submit(TimedFlight(Vec3(0.1, 0.0, 0.0), 20))
print(handle.ack.status.value, handle.state.value)   # ACCEPTED QUEUED

result = handle.wait(timeout=15.0)                   # 只返回终态
print(result.state.value, result.error, result.message)
```

`SubmitAck.ACCEPTED` 只表示入站边界接受了请求。`handle.state` 会经历
`QUEUED`/`RUNNING`；只有 `wait()` 返回终态 `CommandResult`
（`SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED`）。

`CommandResult` 字段：

```text
robot_id, correlation_id, internal_command_id, state, error, message,
event_type, sequence, server_tick, from_ack, had_gap
```

`from_ack=True` 表示终态来自重复提交的 ack（因为没有可用的保留终态事件），
它是已知状态，不是新事件。

## 6. 取消

```python
ack = handle.cancel()                 # CancelAck
print(ack.status.value)               # ACCEPTED / ALREADY_TERMINAL / UNKNOWN
result = handle.wait(timeout=10.0)    # COMMAND_CANCELLED 事件
```

- `ACCEPTED` 只表示取消请求已排队；终态事件才是权威结果；
- `ALREADY_TERMINAL` 携带已知状态，`wait()` 会返回；
- `UNKNOWN` 表示 correlation 已不可知；`cancel_and_wait()` 抛
  `CancelUnknown`，不会伪装成功；
- 被拒绝的取消（例如 correlation 属于另一台机器人）抛 `CancelRejected`。

## 7. 失败必须显式

| 情况 | SDK 行为 |
|---|---|
| UUID 不存在 | `RobotNotFound` |
| 提交被拒绝（未加载/已移除/队列满/冲突） | `CommandRejected`，携带结构化 ack |
| 命令超时未完成 | `WaitTimeout`，携带 handle 和当前状态 |
| 取消未知 | `CancelUnknown`（或返回 `status=UNKNOWN` 的 `CancelAck`） |
| 连接关闭/重置 | `ConnectionClosed` |
| 请求无响应 | `RequestTimeout` |
| 协议级 `error` 消息 | `ServerError`，含 `code`/`message` |
| 事件游标遗漏 | `client.missed=True`；`wait()` 在结果未终态时抛 `EventGapError`，已终态则 `result.had_gap=True` |
| 服务器 epoch 变化 | `SessionChangedError`；pending handle 无法完成 |

## 8. 事件、重连与会话

`wait()` 读取推送事件批次，并按 correlation id 路由；其他机器人的事件会被
保存，不会丢弃。`client.poll_events()` 用客户端游标发起读取请求。

- `client.last_sequence` 只在事件真正应用后前进；
- `client.missed` 与 `client.gap` 暴露丢失区间；SDK 不会伪造缺失事件；
- `client.reconnect()` 打开新连接：
  - 同一 `serverEpoch`：从游标补读，返回 `SessionInfo(resumed=True)`；
  - epoch 不同：pending handle 失效、游标重置，返回
    `SessionInfo(resumed=False)`；旧 handle 的 `wait()` 抛
    `SessionChangedError`。

重连是重新同步，不保证断开期间发布的事件仍被保留。

## 9. 示例

| 脚本 | 用途 |
|---|---|
| [`01_list_robots.py`](../tools/sdk/examples/01_list_robots.py) | 只读的机器人列表与元数据 |
| [`02_fly_and_stop.py`](../tools/sdk/examples/02_fly_and_stop.py) | 明确 UUID、短飞行、检查结果、停止 |
| [`03_cancel_command.py`](../tools/sdk/examples/03_cancel_command.py) | 启动长飞行、取消、读取终态 |
| [`04_mine_block.py`](../tools/sdk/examples/04_mine_block.py) | 可选的会改变世界的挖掘示例 |

在仓库根目录运行：

```powershell
python tools/sdk/examples/01_list_robots.py
python tools/sdk/examples/02_fly_and_stop.py --robot <uuid> --duration 20
```

端到端 smoke test 仍在
[`tools/client/swarmie_client.py`](../tools/client/swarmie_client.py)；它已改用
SDK，并接受 `--robot <uuid>`。

## 10. 测试

```powershell
python -m unittest discover -s tools/sdk/tests -v
```

测试使用可控的进程内 mock server，覆盖消息编解码、请求/响应关联、精确 UUID
选择、ack 与终态区别、超时、取消与终态、重复提交、拒绝、连接关闭、服务端
错误、同 epoch 重连、epoch 变化、事件遗漏和机器人隔离。它**不能替代**真实
Minecraft 集成测试。

## 11. 限制

- 仅 localhost 开发传输；默认关闭、loopback、无认证/TLS；
- 只有四条命令；没有 MoveTo、库存、容器、命名、LOS 或批量；
- 同步、单线程 API；没有 asyncio 或后台任务框架；
- 无跨进程持久化：命令/事件历史仅属于当前服务器会话；
- Python SDK `0.1.2` 与 Adapter 契约 `0.1` 均为 pre-stable。
