[English](test-matrix.md) | 简体中文

# Final Test Matrix — Adapter Refactor

> 证据分类：
> **A** = 自动化 JUnit；**P** = Python SDK unittest（mock server）；
> **C** = 真实 Minecraft 客户端；**S** = 静态编译/产物检查；
> **U** = 未验证；**L** = 已知限制。

## 1. 自动化与静态

| 项目 | 证据 | 结果 |
|---|---|---|
| `:contract` 独立编译 classpath | S | `compileClasspath: No dependencies` |
| 契约无 Minecraft/Fabric import | S | boundary check: 30 files |
| 契约包被最终 JAR 收录 | S | `dev/swarmie/contract/*.class` |
| `:contract:test` | A | 12 passed |
| 根 `test` | A | 75 passed |
| 合计 | A | **87 passed** |
| 无缓存测试 | A | `cleanTest test --no-build-cache` 通过 |
| 离线构建 | S | `--offline build` 通过 |
| 0.1.1 Release JAR | S | 238,293 bytes，SHA-256 `0913341d63e587200d5bbccc91e1309e4ee7297e36abaa31ece56d60490ed031` |
| 刷怪蛋物品/模型/配方/进度/语言数据 | A | 3 项数据契约测试通过 |
| Python SDK（mock server） | P | 66 passed |

测试类：

```text
ContractTest                  9
ObservationContractTest       3
RobotInventoryStoreTest       2
RobotStateStoreTest           2
VelocitySetpointsTest         7
AdapterEventBusTest           2
AdapterEventStoreTest         3
InboundQueueTest              8
RobotRegistryTest             4
TransportCodecTest            6
FlightControllerTest          2
InteractionRuntimeTest        5
SwarmieRuntimeEventTest       4
SwarmieRuntimeTest            5
SensingTest                   3
SwarmieSpawnEggDataTest       3
Dcpu16Test                    7
MotionDeviceTest              4
ClockDeviceTest               1
DcpuFixtureProgramTest        2
DcpuRuntimeBridgeTest         1
SwarmieCommandHelpTest        3
SwarmieCommandRegistrationTest 1
```

## 2. 真实客户端

客户端：Minecraft 26.3 / Fabric Loader 0.20.1 / Fabric API 0.165.0+26.3。
测试世界：`New World`（disposable），真实 integrated server。

| 项目 | 证据 | 结果 |
|---|---|---|
| 客户端启动/主菜单/进入世界 | C | 通过 |
| 契约类加载 | C | `Adapter contract loaded: v0.1` |
| 实体 summon/渲染 | C | 通过（基线 + 本轮） |
| TimedFlight 20 ticks | C | `TIMED_FLIGHT SUCCEEDED` |
| Stop | C | `STOP SUCCEEDED` |
| 单面墙碰撞 | C | 基线通过；本轮未重跑 |
| Runtime FIFO/取消/失败隔离 | C + A | 基线客户端通过；本轮自动化通过 |
| 铁镐挖掘石头 + 方块消失 | C | `MINE_BLOCK SUCCEEDED`，目标变 air |
| 挖掘空气 | C | `MINE_BLOCK FAILED (target is air)` |
| 工具耐久 | C | 基线铁镐 `damage=1`；本轮未单独复测 |
| 方块放置/库存消耗 | C | 石头 `useItemOn` 成功；目标为 stone，库存消耗为 empty |
| block query | C | stone/air 正确 |
| sensor scan | C | `range=8, blocks=0, entities=0`（环境内无目标） |
| 保存/重载 | C | 基线通过；本轮客户端重启后世界恢复 |
| chunk unload/reload | C | 4 个机器人 UNLOADED→LOADED，UUID 不变 |
| active removal | C | KILLED→REMOVED |
| 维度切换 | C | overworld→nether，UUID 不变，无 REMOVED |
| 命令卸载中断 | C | `COMMAND_INTERRUPTED (robot unloaded)` |
| 维度切换 active 命令 | C | `COMMAND_INTERRUPTED state=INTERRUPTED detail=robot unloaded` |
| `serverEpoch` 会话变化 | C | 随机值变化；283059907743973190 / 5351530808198020255 |
| Adapter submit | C | `Adapter ACCEPTED corr=manual-stop-1 state=QUEUED` |
| Adapter 幂等重复 | C | `DUPLICATE ... state=SUCCEEDED` |
| Adapter correlation 冲突 | C | `REJECTED ... CORRELATION_CONFLICT` |
| Adapter 事件顺序 | C | enqueued → accepted → started → completed |
| 公共 cancel（active） | C | `ACCEPTED` → `COMMAND_CANCELLED (cancelled while active)` |
| cancel unknown/terminal | C | `UNKNOWN/UNKNOWN_CORRELATION`；`ALREADY_TERMINAL` |
| localhost JSONL + Python | C | mine/fly/stop/duplicate/cancel/reconnect 实际通过 |
| 幂等 TTL 600s | A | 终态过期后可重新接受；QUEUED 不过期 |
| 刷怪蛋注册 + 资源/数据加载 | C | 日志 `Swarmie spawn egg registered`；无模型/配方错误 |
| 刷怪蛋获取 + 创造搜索 | C | `Gave 1 [Swarmie Spawn Egg]`；搜索 `swarmie` 返回该物品 |
| 配方解锁/自动填充/合成 | C | `Unlocked 1 recipe(s)`；配方书填充 3×3 网格；输出合成 |
| 右键生成 | C | 新 `ROBOT_LOADED` `ff69dd53…`；库存 `empty` |
| Python SDK 发现 + 指定 UUID 飞行/停止/取消 | C | 15 台机器人；`SUCCEEDED`/`SUCCEEDED`/`CANCELLED`；重连 `latest=63 missed=false` |
| Inventory + explicit block observation | C | 9 个有序槽位；`air`、`andesite`、`UNLOADED`、`OUT_OF_BOUNDS`；无副作用 |
| Python SDK inventory/block | C | 只读；`selectedSlot=0`；`UNLOADED` 不当成空气 |
| 最小农业工作流 | C | farmland + 16 seeds -> 消耗 1 seed；上方生成 crop；unloaded target FAILED 且无 mutation |
| DCPU guest motion bridge | C | MMIO timed flight；Entity 实际移动 1.1 blocks；终态 IRQ；cleanup 停止 VM |
| DCPU remote-motion exclusion | C | guest 持有 motion 时 setpoint/TIMED_FLIGHT 返回 `MOTION_CHANNEL_BUSY` |
| DCPU disabled gate | C | `development=true, configured=false` -> disabled |
| 快捷命令选择/CRUD | C | `/swarmie spawn`、`list`、`status`、短形式 `fly`/`stop`、显式 `remove` 通过聊天栏实测；测试机器人 `6c9daf28…` 已删除 |
| 悬浮视觉偏移 | C | 客户端截图确认外壳悬浮于实体脚点上方；不改变 gameplay 位置 |
| Python SDK 挖掘 smoke | C | 所选测试坐标返回 `MINE FAILED EXECUTION_FAILED 'target is air'`，被明确报告 |
| 事件环实机溢出 | U | 未填满 1024；小容量 JUnit 已验证 |
| 批量实机提交 | U | JUnit 已覆盖 batch 上限/逐条语义；无实机 batch 命令 |
| 第二客户端同步 | U | 未测试 |
| 专用服务器 | U | 未测试（EULA 未接受） |
| GUI 容器 | L | FakePlayer.openMenu no-op；SUCCESS 不代表内容变化 |
| LOS 传感器 | L | 未支持 |
| 高速/斜向/台阶/天花板碰撞 | U | 基线未覆盖 |

## 3. Python SDK（mock server）

```powershell
python -m unittest discover -s tools/sdk/tests -v
```

66 项测试，无需启动 Minecraft。

| 项目 | 证据 | 结果 |
|---|---|---|
| 命令/ack/batch JSON 编解码 | P | 通过 |
| 请求/响应关联（含穿插推送） | P | 通过 |
| 精确 UUID 选择 / 未知 UUID | P | 通过 |
| ack 与终态结果区分 | P | 通过 |
| 等待超时 | P | 通过 |
| 取消 accepted/already-terminal/unknown | P | 通过 |
| 重复提交与拒绝提交 | P | 通过 |
| 连接关闭与服务端错误 | P | 通过 |
| 同 epoch 重连与 epoch 变化 | P | 通过 |
| 事件遗漏与机器人隔离 | P | 通过 |
| 新连接游标基线 | P | 通过 |

## 4. 生命周期实测细节

- 4 个机器人加载 → 4 个 `ROBOT_LOADED`；
- 提交 4 条 1000-tick flight → 4 `COMMAND_ACCEPTED` + 4 `COMMAND_STARTED`；
- 玩家远离 → 每个机器人 `COMMAND_INTERRUPTED` 后 `ROBOT_UNLOADED`；
- 返回 → 同 UUID 重新 LOADED，无重复记录；
- `/execute in minecraft:the_nether run tp @e[type=swarmie:swarmie] ...` →
  `ROBOT_UNLOADED` + `ROBOT_DIMENSION_CHANGED`，不产生 `ROBOT_REMOVED`；
- 玩家进入 nether → 同 UUID 以 `minecraft:the_nether` 重新 LOADED；
- `/kill` → `ROBOT_UNLOADED` + `ROBOT_REMOVED (KILLED)`；
- 事件序号 1–44 连续，`missed=false`。

## 5. 结论

核心 Adapter 本地链路已达到：

- 独立 `:contract`、四命令契约、主 Mod 打包和真实客户端类加载；
- 注册表跨卸载存续、维度切换不误删、事件环可检测遗漏；
- 入站幂等/冲突/反压/批量的自动化语义；
- Runtime 命令事件与 correlation 贯通；
- 真实客户端加载/卸载/维度/移除/命令中断/Adapter submit 验证；
- 刷怪蛋物品、模型、配方、进度和语言数据（仅单元检查）；
- 真实客户端：刷怪蛋可获取并在创造搜索中找到；配方已解锁并通过配方书
  在 3×3 网格中合成；右键生成新机器人且库存为空；
- Python SDK（仅标准库）：明确 UUID 机器人、命令句柄、取消、感知 epoch/遗漏的
  重连，以及 66 项 mock-server 测试；
- 真实客户端：SDK 发现、指定 UUID 飞行到终态、停止、取消和重连均通过
  localhost 传输验证。

不能宣称：

- 支持远程/公网连接、TLS、认证或多人安全模型；
- GUI 容器可用；
- Python SDK 是远程或带认证的 API（它只是 localhost 开发工具）；
- 第二客户端/专用服务器/事件环实机溢出已验证；
- 所有碰撞/传感器边界已验证。





