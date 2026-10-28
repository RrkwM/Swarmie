[English](CHANGELOG.md) | 简体中文

# 变更日志

Swarmie 的重要变化记录在此。格式参考
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 和
[Semantic Versioning](https://semver.org/)。

## [Unreleased]

### 新增

- 可合成物品 `swarmie:swarmie_spawn_egg`（未发布的 `0.1.1` 版本线）：
  - 有序合成：4 铁锭、4 红石、1 铜锭；
  - 显示在原版“刷怪蛋”创造模式标签页；
  - 使用后创建**新**机器人：新 UUID、空 9 槽库存；不会捕获、召回或恢复已有机器人；
  - `/summon swarmie:swarmie` 行为不变。
- 物品定义、模型、16×16 纹理、`en_us`/`zh_cn` 名称和配方解锁进度。
- 物品定义/模型/配方/进度/语言键的数据契约 JUnit（共 46 项测试）。

### 文档

- README 拆分为简短入口页；
- 新增 canonical [玩家指南](docs/player-guide.zh-CN.md) 与
  [开发者指南](docs/developer-guide.zh-CN.md)；
- 发布 GitHub Wiki 导航、玩家/开发者摘要、安装和 FAQ；
- 匿名化公开树中的维护者本机绝对路径；
- 公开文档增加英文默认版本和中文 `.zh-CN` 版本。

### 已验证

- 46 项 JUnit（`test --rerun-tasks`）；
- 真实客户端（Minecraft 26.3，disposable 世界）：
  - 刷怪蛋以 `Swarmie Spawn Egg` 名称发放，并可在创造搜索中找到；
  - `/recipe give` 解锁配方；配方书自动填充 3×3 铁/红石/铜网格，输出合成刷怪蛋；
  - 右键刷怪蛋会记录新的 `ROBOT_LOADED`，`/swarmie inventory show` 显示空库存；
  - 创造模式不消耗刷怪蛋（原版刷怪蛋行为）。
- 仍未验证：专用服务器启动、第二客户端同步和其余碰撞边界。

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

