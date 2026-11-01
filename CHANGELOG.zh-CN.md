[English](CHANGELOG.md) | 简体中文

# 变更日志

Swarmie 的重要变化记录在此。格式参考
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 和
[Semantic Versioning](https://semver.org/)。

## [Unreleased]

## [0.1.2] - 2026-11-02

pre-1.0 可用性补丁。Adapter 契约仍为 `0.1`。

### 新增

- `/swarmie list`：按 UUID 排序列出已加载机器人，带 UUID 与位置。
- `/swarmie select <index|uuid>`：按列表序号、完整 UUID 或唯一 UUID 前缀选择；`/swarmie selected` 显示当前选择。
- `/swarmie spawn`：在命令来源前方两格创建机器人并选中。
- `/swarmie remove`：删除明确选中的机器人，不会回退为删除最近机器人。
- `/swarmie help selection` 和更新后的双语玩家指南。

### 变更

- `fly`、`stop`、`status`、`results` 可以省略 target，使用选中的机器人；selector 多目标形式仍保留。
- 悬浮视觉偏移降到 0.32 格。
- idle 上下浮动提高到约 ±0.055 格；耳翼和侧翼摆幅更明显。

### 已验证

- 75 项 Java main 测试、12 项 contract 测试。
- 66 项 Python SDK/mock-server 测试。
- 33 项 audit 测试；完整性校验 `errors=0`、`review_findings=0`。
- 真实客户端聊天栏：`spawn`、`list`、`status`、短形式 `fly`/`stop`、显式 `remove`。
- 真实客户端视觉检查：悬浮偏移与 idle 动画。
- Release JAR：`swarmie-0.1.2.jar`，243,656 bytes，SHA-256 `0883966f087167af65e0a079deec261c10577027de818738a688b3e71af7ce9a`。

### 已知限制

- DCPU/MMIO 仍是 test-only 子集实验。
- SDB v0 仅为草案，无实现。
- `LocalSensor` 仍是 internal/debug，不是公共 Sensor API。
- 没有通用农业、寻路、任务调度、orientation setpoint 或公共 region/entity/sensor observation。
## [0.1.1] - 2026-11-02

pre-1.0 功能版本。Adapter 契约仍为 `0.1`；本次新增契约类型和 JSONL 操作为向后兼容追加。

### 新增

- API v1 self-state：位置、原生速度、yaw/pitch、维度、生命周期、`serverEpoch` 与 `serverTick` freshness。
- 持久速度 setpoint 通道：latest-wins、revision、`0.35` blocks/tick clamp、clear/减速，以及与 legacy `TIMED_FLIGHT`/`STOP` 的互斥所有权。
- 9 槽有序库存快照、selected slot 和稳定的 tick 边界数据。
- 显式单方块观察：`OK`、`UNLOADED`、`OUT_OF_BOUNDS`、`INVALID`、`UNSUPPORTED`、`ERROR`；不可用位置不会伪装成空气。
- 仅标准库 Python SDK：精确 UUID 机器人、发现、self-state、库存、方块观察、setpoint、动作句柄、取消、事件，以及感知 epoch/遗漏的重连和结构化错误。
- 可合成 `swarmie:swarmie_spawn_egg`（4 铁锭、4 红石、1 铜锭）、配方解锁、物品模型/纹理和 `en_us`/`zh_cn` 名称；使用后创建新 UUID、空库存的机器人。
- `/swarmie help`，包含 `flight`、`actions`、`inventory`、`query`、`adapter`、`lifecycle`，并明确标记为 OP 调试命令。
- `docs/api-current.md`：当前 Python/JSONL 已实现能力的 pre-stable 速查。
- 已验证的最小农业准备流程：读取 selected inventory、观察 farmland、使用小麦种子，再读取库存和作物方块。
- test-only DCPU-16/MMIO bridge spike：pure-Java `SET`/`IAS`/`RFI` 子集、motion/clock MMIO、guest → MMIO → 现有 Runtime → Entity 运动、终态/中断/ACK/cleanup；guest 持有 motion 时拒绝远程 setpoint/legacy motion。
- PCI-inspired SDB v0 设备总线规范草案：配置空间、slot、BAR、capability、IRQ、生命周期、fault 和玩家驱动职责；尚无实现。
- 新白/青机器人模型：黑色面屏、四片独立 idle 翼、视觉上下浮动、0.45 × 0.65 碰撞箱（每方块 2×2 个）。

### 变更

- 实体位置同步从每 3 tick 改为每 tick（`updateInterval(1)`），消除低速位置跳动。
- 文档刷新当前 API、help、测试数量，以及 public/experimental/internal 的边界。
- 旧 `LocalSensor` 前向扫描能力已明确标记为 internal/debug，不是公共 Sensor API，也不属于 SDB。

### 已验证

- 75 项 Java main 测试、12 项 contract 测试（`test --rerun-tasks`）。
- 66 项 Python SDK/mock-server 测试。
- 33 项 audit 测试；完整性校验 `errors=0`、`review_findings=0`。
- 真实 integrated server：生命周期、state/setpoint、库存/方块观察、农业准备、DCPU timed flight/终态 IRQ/ACK、远程 motion 拒绝和 cleanup。
- Release JAR：`swarmie-0.1.1.jar`，238,293 bytes，SHA-256 `0913341d63e587200d5bbccc91e1309e4ee7297e36abaa31ece56d60490ed031`。

### 已知限制

- DCPU 是指令预算子集，不是完整 DCPU-16；没有 assembler、cartridge、持久化、NIC 或 UI。
- SDB v0 仅为草案；没有设备总线、block-probe 设备或第三方 provider 实现。
- `LocalSensor` 是 internal/debug，不是玩家定义的 Sensor 系统。
- 没有通用农业、寻路、任务调度、orientation setpoint 或公共 region/entity/sensor observation。
- 不支持 GUI 容器、库存转移或物品组件。
- 专用服务器启动、第二客户端同步和部分碰撞边界仍未验证。
- localhost JSONL 没有认证、TLS 或远程绑定。
## [0.1.0] - 2026-10-28

首个公开版本。pre-1.0：公共 Adapter 契约和命令语义尚不稳定。

### 新增

- Minecraft `26.3` Fabric `SwarmieEntity`：服务端权威三轴悬浮、原生碰撞、
  持久化和 9 槽库存；
- 每机器人 Runtime FIFO：定时飞行、停止、挖掘、对方块使用物品；
- 通过临时 `FakePlayer` 进行原生交互，不向世界加入假玩家实体；
- 独立 `WorldQuery` 与 `LocalSensor`；
- 纯 Java `:contract`（`0.1` pre-stable）：四命令、ack、result、event、
  cancel、batch 契约；
- 注册表 `LOADED`/`UNLOADED`/`REMOVED`、最后已知元数据、有界 removed 记录；
- 会话 `serverEpoch`、1024 事件环、序列缺口检测（`missed`）、命令生命周期事件；
- 有界入站队列、每 tick 预算、correlation 幂等（容量 1024、终态 TTL 600 s）、
  批量提交和背压错误；
- 公共 `cancel(robotId, correlationId)`，覆盖 active/queued/terminal/unknown；
- 默认关闭、仅 loopback 的 JSONL 开发传输与无依赖 Python 客户端；
- JUnit、contract 边界检查、GitHub Actions CI、CONTRIBUTING、版本规范和交接文档。

### 已验证

- 43 项 JUnit；
- 真实客户端：加载/卸载/重载、区块卸载、维度切换、玩家移除、命令中断、
  Adapter 提交/重复/冲突、active cancel、Python mine/fly/stop/cancel/reconnect、
  石头放置和库存消耗；
- 公开构建：`swarmie-0.1.0.jar`；SHA-256 发布在 GitHub Release。

### 已知限制

- FakePlayer 路径不支持 GUI 容器；API 成功不等于容器副作用已确认；
- 事件环溢出只在 JUnit 小容量中验证，未在 1024 实机会话填满；
- 批量提交和事件环溢出缺少实机命令；
- 第二客户端、专用服务器、LOS 和部分高速/斜向/台阶/天花板碰撞未验证；
- localhost JSONL 无认证、TLS、远程绑定或多用户安全模型。

