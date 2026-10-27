[English](README.md) | 简体中文

# Swarmie

[![CI](https://github.com/RrkwM/Swarmie/actions/workflows/ci.yml/badge.svg)](https://github.com/RrkwM/Swarmie/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/RrkwM/Swarmie)](https://github.com/RrkwM/Swarmie/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> 面向 Minecraft 26.3 / Fabric 的可编程悬浮机器人模组，带有独立于 Minecraft 的
> Adapter 契约。

仓库：https://github.com/RrkwM/Swarmie  
最新 Release：https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0  
Wiki：https://github.com/RrkwM/Swarmie/wiki

## 项目是什么

Swarmie 增加了一个真实的 Minecraft `Entity`，包含：

- 服务端权威的三轴悬浮、原生碰撞与持久化；
- 9 槽机器人库存；
- 通过临时 FakePlayer 进行原生方块/物品交互；
- 每机器人 Runtime FIFO：定时飞行、停止、挖掘、对方块使用物品；
- 独立的世界查询与本地传感器；
- 纯 Java `:contract` 模块：公共 Adapter 提交、取消、结果、生命周期事件、
  幂等与批量契约；
- 有界注册表/事件存储，以及默认关闭的 localhost JSONL 开发传输。

它不是 AI、寻路、任务编排、GUI 容器或远程网络项目。

## 当前状态

| 项目 | 状态 |
|---|---|
| 版本 | `0.1.0`（pre-1.0） |
| Java | 必须 25 |
| Minecraft | 26.3 |
| Fabric Loader / API | 0.20.1 / 0.165.0+26.3 |
| CI | 通过 |
| JUnit | 43 项通过 |
| Release JAR | `swarmie-0.1.0.jar` |
| JAR SHA-256 | `fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a` |

Adapter 契约为 `0.1`，仍处于 pre-stable。

## 安装（玩家）

需要 Minecraft 26.3、Fabric Loader 0.20.1、Fabric API 0.165.0+26.3、Java 25。

1. 从 [Releases](https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0) 下载
   `swarmie-0.1.0.jar`。
2. 与 Fabric API 一起放入 `mods/`。
3. 使用 Java 25 启动游戏。

**0.1.0 是管理员/开发者预览。** 当前没有合成配方、机器人物品或刷怪蛋。
OP 可以用：

```mcfunction
/summon swarmie:swarmie ~ ~ ~
```

然后：

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie fly @e[type=swarmie:swarmie] 0.1 0 0 20
/swarmie stop @e[type=swarmie:swarmie]
```

完整玩家说明、命令和故障排查：**[玩家指南](docs/player-guide.zh-CN.md)**。

## 构建（开发者）

需要 JDK 25 和 Git。首次构建会从官方仓库下载 Gradle、Minecraft 和 Fabric
依赖。

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

产物：

```text
build/libs/swarmie-0.1.0.jar
```

架构、Adapter API、测试策略和扩展边界：**[开发者指南](docs/developer-guide.zh-CN.md)**。

## 架构

```text
Minecraft 层
  Entity / 原生移动、碰撞、方块与物品
        │
Runtime + 能力
  FIFO、InteractionActor、WorldQuery、LocalSensor
        │
Adapter 层
  纯 Java contract、registry、events、入站/出站队列、transport
        │
未来的外部控制平面
```

硬边界：

- `contract/` 不得依赖 Minecraft/Fabric/Loom；
- Runtime 负责调度，不直接访问世界；
- 原生方块/物品行为保持原生；
- 外部客户端使用 Adapter 契约，而不是 `SwarmieEntity`；
- 调试命令不是公共 API。

## 文档

| 内容 | 文档 |
|---|---|
| 玩家安装、命令、生命周期、排障 | [`docs/player-guide.zh-CN.md`](docs/player-guide.zh-CN.md) |
| 开发环境、架构、Adapter、测试 | [`docs/developer-guide.zh-CN.md`](docs/developer-guide.zh-CN.md) |
| 语言政策 / English docs | [`docs/LANGUAGE.zh-CN.md`](docs/LANGUAGE.zh-CN.md) |
| 契约字段、错误与序列化 | [`docs/adapter-contract.zh-CN.md`](docs/adapter-contract.zh-CN.md) |
| 注册表、生命周期、serverEpoch、事件环 | [`docs/adapter-registry.zh-CN.md`](docs/adapter-registry.zh-CN.md) |
| 入站/出站、幂等、批量、取消 | [`docs/adapter-io.zh-CN.md`](docs/adapter-io.zh-CN.md) |
| 四命令状态、副作用与限制 | [`docs/adapter-command-specs.zh-CN.md`](docs/adapter-command-specs.zh-CN.md) |
| localhost JSONL/Python 开发传输 | [`docs/local-transport.zh-CN.md`](docs/local-transport.zh-CN.md) |
| 语义化版本与发布流程 | [`docs/versioning.zh-CN.md`](docs/versioning.zh-CN.md) |
| 变更日志 | [`CHANGELOG.zh-CN.md`](CHANGELOG.zh-CN.md) |
| 测试证据矩阵 | [`docs/test-matrix.zh-CN.md`](docs/test-matrix.zh-CN.md) |
| 贡献规则 | [`CONTRIBUTING.zh-CN.md`](CONTRIBUTING.zh-CN.md) |
| 维护者交接 | [`HANDOFF.md`](HANDOFF.md) |
| Wiki 源文件/镜像 | [`docs/wiki/Home-zh-CN.md`](docs/wiki/Home-zh-CN.md) / [GitHub Wiki](https://github.com/RrkwM/Swarmie/wiki) |

## 版本与发布

Swarmie 遵循 [Semantic Versioning 2.0.0](https://semver.org/)。当前 `0.1.0` 是
首个公开 pre-1.0 版本。规则与发布检查清单见
[`docs/versioning.zh-CN.md`](docs/versioning.zh-CN.md)。

Release 资产包含 JAR 与 `.sha256`。

## 已知限制

- 尚无生存获取方式：目前只能由 OP `/summon`；
- FakePlayer 路径不支持 GUI 容器（箱子/熔炉/漏斗等）；API 成功不等于容器内容已改变；
- 挖掘掉落留在世界，不会自动收集；
- 命令在卸载时中断，重载后不会恢复；
- 专用服务器、第二客户端、LOS 和部分碰撞/规模边界未验证；
- localhost JSONL 默认关闭、仅 loopback，无认证/TLS/远程安全模型；
- Adapter 契约仍是 0.x，可能变化。

## 许可证

MIT，见 [`LICENSE`](LICENSE)。

