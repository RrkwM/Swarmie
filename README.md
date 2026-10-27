# Swarmie

[![CI](https://github.com/RrkwM/Swarmie/actions/workflows/ci.yml/badge.svg)](https://github.com/RrkwM/Swarmie/actions/workflows/ci.yml)

> GitHub：https://github.com/RrkwM/Swarmie
> License：MIT
> Minecraft 26.3 + Fabric 的可编程悬浮机器人模组。
> 当前版本：`0.1.0` ｜ Mod ID：`swarmie`
> 当前 JAR：`build/libs/swarmie-0.1.0.jar`
> SHA-256：`fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a`
> 基线（Adapter 前）JAR 保留在 `docs/verification/swarmie-baseline-dd529fd8.jar`

Swarmie 的目标不是做一个“会走路的实体”，而是一个可以由外部程序逐步驱动的最小机器人平台：

- 真实的 Minecraft `Entity` 身体；
- 服务端权威的三轴悬浮运动；
- 每机器人独立的命令队列和生命周期；
- 机器人自己的 9 槽库存；
- 通过临时 FakePlayer 执行 Minecraft 原生方块破坏/物品使用；
- 独立的世界查询与受限局部传感器能力；
- 服务器端调试命令，用于实际驱动和观察上述能力。

项目**不包含** AI、寻路、任务编排、脚本语言、GUI 或通用客户端/公网网络协议。
唯一的网络代码是默认关闭、仅绑定 `127.0.0.1` 的开发用 JSONL transport。

---

## 目录

- [当前状态](#当前状态)
- [Adapter Contract](#adapter-contract)
- [快速开始](#快速开始)
- [架构总览](#架构总览)
- [代码结构](#代码结构)
- [核心模块](#核心模块)
  - [实体与飞行](#实体与飞行)
  - [Runtime](#runtime)
  - [库存](#库存)
  - [InteractionActor](#interactionactor)
  - [世界查询](#世界查询)
  - [局部传感器](#局部传感器)
  - [客户端渲染](#客户端渲染)
  - [调试命令](#调试命令)
- [生命周期、持久化与线程](#生命周期持久化与线程)
- [验证与测试](#验证与测试)
- [已知限制](#已知限制)
- [开发约束](#开发约束)
- [文档索引](#文档索引)

---

## 当前状态

### 构建与运行

| 项目 | 状态 |
|---|---|
| Minecraft 26.3 + Fabric 真实构建 | **通过**（在普通用户工作副本中） |
| Adapter Contract 0.1 | **已建立**：`:contract` 纯 Java 子项目、四条命令、边界检查 |
| Adapter registry/lifecycle | **已建立**：LOADED/UNLOADED/REMOVED、serverEpoch、有界事件环、命令中断 |
| Adapter local I/O | **已建立**：有界入站、correlation 幂等、batch、END tick 事件、监听器隔离 |
| Localhost JSONL | **已建立（默认关闭）**：仅 127.0.0.1，独立 I/O 线程，Python 客户端 |
| 有效 Mod JAR | **已生成**，162,555 bytes（0.1.0 release build） |
| 开发客户端启动 | **基线通过**，到达主菜单并进入单人世界 |
| 实体 summon / 渲染 | **通过**（基线客户端） |
| X/Y/Z 飞行、stop、单面墙碰撞 | **通过**（基线客户端） |
| 保存/重载、区块卸载/重载 | **通过** |
| Runtime FIFO、取消、失败恢复 | **通过**（部分边界未验证） |
| 库存、挖掘、放置、工具耐久 | **通过** |
| 方块/区域查询、未加载查询 | **通过** |
| 传感器 FOV、距离、截断 | **通过**（部分边界未验证） |
| 专用服务器 | **未测试**（未接受 EULA） |

### 六个 Gate 状态

> `SPEC.md` 是权威规范；这里只汇总当前实现进度，不替代规范。

| Gate | 内容 | 当前状态 |
|---|---|---|
| 1 | 构建环境与最小 Mod | runtime 唯一活动工作区构建/客户端通过；原工作区仅只读备份 |
| 2 | 真实 Entity 与生命周期 | 运行时部分验证；chunk unload/reload、维度切换、active removal 已测；第二客户端未覆盖 |
| 3 | 三维悬浮运动 | 运行时部分验证；高速、斜向、台阶、天花板碰撞未覆盖 |
| 4 | Runtime 与命令执行 | 运行时部分验证；FIFO/取消/失败恢复、Adapter 命令事件、卸载中断已测；编程异常注入未覆盖 |
| 5 | 原生交互与 InteractionActor | 运行时部分验证；原生挖掘/放置/耐久通过，GUI 容器受 FakePlayer 限制 |
| 6 | 世界查询与局部传感器 | 运行时部分验证；block/region/FOV/超距/截断通过，live entity query/LOS 未覆盖 |

**没有把任何 Gate 标记为“全部通过”**。源码编译、单个 smoke test 成功都不等于完整验收。

详细的构建、JAR、客户端和逐项运行记录见
[`docs/runtime-verification.md`](docs/runtime-verification.md)。

## 交接说明

### 唯一活动工作区

```text
<workspace>
branch: main
```

原工作区 `<original-workspace-backup>` 只作为只读备份，
不再同步或构建。最近提交用 `git log -1 --oneline` 查看。

### 构建与测试

```powershell
cd <workspace>
$env:JAVA_HOME = (Resolve-Path .\tools\jdk).Path
$env:GRADLE_USER_HOME = (Resolve-Path .\tools\gradle-home).Path
.\gradlew.bat test
.\tools\scripts\build.ps1
```

当前产物：

```text
build/libs/swarmie-0.1.0.jar
162,555 bytes
fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a
```

### Localhost JSONL（默认关闭）

开发远程调试必须手动创建 `run/config/swarmie-transport.json`；源码默认不监听端口。
协议和线程模型见 [`docs/local-transport.md`](docs/local-transport.md)。

```powershell
python tools/client/swarmie_client.py --host 127.0.0.1 --port 25580
```

该 transport 无认证、无 TLS、只绑定 loopback，不得用于远程/公网/多人安全场景。

### 文档优先级

| 需要了解 | 文档 |
|---|---|
| 构建、世界内历史证据 | [`docs/runtime-verification.md`](docs/runtime-verification.md) |
| 自动化/客户端/静态/未验证矩阵 | [`docs/test-matrix.md`](docs/test-matrix.md) |
| 契约字段/版本/错误码 | [`docs/adapter-contract.md`](docs/adapter-contract.md) |
| 注册表/生命周期/serverEpoch/事件环 | [`docs/adapter-registry.md`](docs/adapter-registry.md) |
| 入站/出站/幂等/批量/取消 | [`docs/adapter-io.md`](docs/adapter-io.md) |
| 四命令完整语义 | [`docs/adapter-command-specs.md`](docs/adapter-command-specs.md) |
| 本地 JSONL/Python | [`docs/local-transport.md`](docs/local-transport.md) |

## Adapter Contract

Prompt 1 已建立独立纯 Java 子项目 `:contract`，当前版本 **0.1（pre-stable）**：

- 包：`dev.swarmie.contract`
- 契约版本：`0.1`
- 仅覆盖四条命令：`TIMED_FLIGHT`、`STOP`、`MINE_BLOCK`、`USE_ITEM_ON_BLOCK`
- 数据：`RobotId`、`BlockPosDto`、`Vec3Dto`、`DirectionDto`、`CommandSpec`、
  `CommandSubmission`、`SubmitAck`、`CancelAck`、`CommandResult`、`RobotEvent`、
  `EventBatch`、状态/错误码
- `velocity`、`inventory give/show`、`query`、`sensor` 仍是 OP 调试工具，
  **不进入**公共契约、不产生 Adapter 命令事件
- `:contract` 的编译 classpath 不含 Minecraft/Fabric/Loom
- 坐标：方块用 `BlockPosDto(int)`，速度/命中点用 `Vec3Dto(double)`
- 根 Mod 通过 `implementation(project(":contract"))` 使用契约；最终 JAR 合并
  `dev/swarmie/contract/*.class`

架构检查：

```powershell
pwsh -NoProfile -File .\tools\scripts\check-contract-boundary.ps1
```

详细字段/版本/序列化规则见 [`docs/adapter-contract.md`](docs/adapter-contract.md)。

> 入站/注册表/事件环、公共 cancel 和默认关闭的 localhost JSONL 已实现；
> 不存在远程/公网/多人安全传输。外部 Python 仅可通过 loopback 调试端口连接。

---

## 快速开始

### 版本矩阵

| 组件 | 版本 |
|---|---|
| Minecraft | `26.3` |
| Fabric Loader | `0.20.1` |
| Fabric API | `0.165.0+26.3` |
| Loom | `1.19.3` |
| Gradle | `9.8.2` |
| Java | Temurin JDK `25.0.4.1+1` |

版本定义在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)，不要在不同机器上随意改动。

### 公开快速开始（游客）

需要：

- JDK **25**（Temurin 推荐）
- Git
- 首次构建可访问 Fabric/Maven 官方仓库
- 不需要项目内的 `tools/jdk`、`tools/repo` 或预置 Gradle 发行包

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

首次构建会下载 Gradle、Minecraft/Fabric 依赖；之后由 Gradle 缓存复用。
`fabric.mod.json` 声明 Java 25，低于 25 的 JDK 会失败。

### 已验证的维护者工作区

维护者当前工作区：

```text
<workspace>
```

它包含被 `.gitignore` 排除的本机工具链/依赖缓存；使用：

```powershell
pwsh -NoProfile -File .\tools\scripts\build.ps1
# 或显式离线：
pwsh -NoProfile -File .\tools\scripts\build.ps1 -Offline
```

`verify-runtime.ps1` 和 `HANDOFF.md` 记录的是该工作区的历史验证细节，
不是游客构建的前置条件。原 `<original-workspace-backup>`
只作为只读备份，不再构建。

### 产物

```text
build/libs/swarmie-0.1.0.jar
```

当前 0.1.0 release 构建：**162,555 bytes**，
SHA-256 `fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a`。
基线（Adapter 前）JAR 保留在
`docs/verification/swarmie-baseline-dd529fd8.jar`（79,806 bytes，
SHA-256 `dd529fd89192ff6f581fbcda9a5255d4945be790ef40a9b09202a57b7854861c`）。

该 JAR 包含 common 与 client 源集、实体/渲染器、Runtime、库存、交互、查询、传感器、
Adapter contract DTO、纹理与语言文件。

---

## 架构总览

```text
Minecraft Server
│
├─ SwarmieMod
│   ├─ EntityType<SwarmieEntity>  (swarmie:swarmie)
│   └─ CommandRegistrationCallback → SwarmieCommands
│
└─ SwarmieEntity : Entity, FlightTarget
    │
    ├─ SwarmieFlightController
    │     desired velocity / max speed / acceleration / damping
    │
    ├─ SwarmieInventory : Container
    │     9 slots + selectedSlot + ContainerHelper persistence
    │
    ├─ SwarmieRuntime
    │     FIFO queue / one active command / IDs / history
    │     ├─ TimedFlightCommand
    │     ├─ StopCommand
    │     ├─ MineBlockCommand
    │     └─ UseItemOnBlockCommand
    │
    ├─ InteractionActor : InteractionHandler, AutoCloseable
    │     FakePlayer context + robot→player inventory handoff
    │     native destroyBlock / useItemOn
    │
    ├─ WorldQuery (replaceable)
    │     DefaultWorldQuery: block / region / entities
    │
    └─ LocalSensor (replaceable)
          DefaultLocalSensor: bounded FOV observations
```

客户端：

```text
SwarmieClient
  → SwarmieEntityRenderer
  → SwarmieEntityModel
  → SwarmieRenderState
  → assets/swarmie/textures/entity/swarmie.png
```

关键边界：

- **Entity** 只负责身体、位置、旋转、飞行 tick 和持久化入口；
- **FlightController** 只负责把期望速度变成实际速度；
- **Runtime** 只负责命令调度和状态，不直接读写世界；
- **InteractionActor** 只负责原生交互与 FakePlayer 生命周期；
- **WorldQuery / LocalSensor** 是两个独立、可替换的能力，不依赖 Runtime；
- 客户端只做渲染，不参与逻辑模拟。

---

## 代码结构

```text
src/
├─ main/java/dev/swarmie/
│  ├─ SwarmieMod.java                  # 注册入口
│  ├─ SwarmieEntity.java               # 实体、tick、FlightTarget、能力所有权
│  ├─ SwarmieFlightController.java     # 三轴速度控制
│  ├─ command/
│  │  └─ SwarmieCommands.java          # /swarmie 服务端命令
│  ├─ inventory/
│  │  └─ SwarmieInventory.java         # 9 槽库存与持久化
│  ├─ interaction/
│  │  ├─ InteractionActor.java         # FakePlayer 交互上下文
│  │  ├─ InteractionHandler.java       # Runtime 使用的能力接口
│  │  └─ InteractionOutcome.java       # 结构化交互结果
│  ├─ runtime/
│  │  ├─ SwarmieRuntime.java           # FIFO 调度、取消、历史
│  │  ├─ SwarmieCommand.java           # 状态机基类
│  │  ├─ TimedFlightCommand.java
│  │  ├─ StopCommand.java
│  │  ├─ MineBlockCommand.java
│  │  ├─ UseItemOnBlockCommand.java
│  │  ├─ FlightTarget.java             # Runtime→实体窄接口
│  │  ├─ SubmissionResult.java
│  │  └─ CommandFailureException.java
│  ├─ query/
│  │  ├─ WorldQuery.java
│  │  ├─ DefaultWorldQuery.java
│  │  ├─ BlockSnapshot.java
│  │  ├─ BlockQueryResult.java
│  │  ├─ RegionQueryResult.java
│  │  ├─ EntitySnapshot.java
│  │  ├─ EntityQueryResult.java
│  │  └─ QueryStatus.java
│  └─ sensor/
│     ├─ LocalSensor.java
│     ├─ DefaultLocalSensor.java
│     ├─ SensorConfig.java
│     ├─ SensorObservation.java
│     ├─ SensorBlock.java
│     └─ SensorEntity.java
├─ main/resources/
│  ├─ fabric.mod.json
│  └─ assets/swarmie/
│     ├─ lang/en_us.json
│     ├─ lang/zh_cn.json
│     └─ textures/entity/swarmie.png
└─ client/java/dev/swarmie/client/
   ├─ SwarmieClient.java
   ├─ SwarmieEntityModel.java
   ├─ SwarmieEntityRenderer.java
   └─ SwarmieRenderState.java
```

构建相关：

```text
build.gradle.kts                 # Loom split source sets、资源版本展开
settings.gradle.kts              # 本地 tools/repo 仓库配置；include(":contract")
gradle/libs.versions.toml        # 唯一版本源
gradle/wrapper/                  # 本地 Gradle 发行包 wrapper
tools/scripts/verify-runtime.ps1 # 用户可运行的真实构建/启动入口
tools/verification/ContextProbe.java

contract/                        # 独立纯 Java 子项目，无 Minecraft/Fabric 依赖
├─ build.gradle.kts
└─ src/
   ├─ main/java/dev/swarmie/contract/
   │  ├─ ContractVersion.java
   │  ├─ RobotId.java
   │  ├─ BlockPosDto.java / Vec3Dto.java / DirectionDto.java
   │  ├─ CommandType.java / CommandSpec.java / CommandSubmission.java
   │  ├─ SubmitAck.java / CommandResult.java / CommandState.java
   │  ├─ ErrorCode.java
   │  └─ RobotEvent.java / RobotEventType.java
   └─ test/java/dev/swarmie/contract/ContractTest.java
```

---

## 核心模块

### 实体与飞行

`SwarmieEntity` 继承原生 `Entity`（不是 `LivingEntity`，也不是 `BlockEntity`）：

- `setNoGravity(true)`、`blocksBuilding = false`；
- 服务端每 tick：`runtime.tick()` → flight controller → 一次 `move(MoverType.SELF, ...)`；
- 客户端只接收原生位置/旋转更新并进行渲染插值；
- 碰撞通过原生 `Entity.move` 完成；
- 碰撞后比较请求位移与实际位移，仅清除被阻挡轴的速度，保留其他轴的滑动。

`SwarmieFlightController` 参数：

| 参数 | 值 |
|---|---:|
| `MAX_SPEED` | `0.35` blocks/tick |
| `MAX_ACCELERATION` | `0.04` |
| `IDLE_DAMPING` | `0.90` |

飞行控制器只接受有限向量；速度/加速度裁剪、阻尼和停止都在这里完成。实体本身不包含重复的物理实现。

### Runtime

`SwarmieRuntime` 是每实体独立的命令调度器：

- FIFO 队列；
- 同一时刻只有一个 active command；
- 单调递增、不可复用的命令 ID；
- 有界队列/历史；
- 命令状态：`QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED` / `INTERRUPTED`；
- 支持 active cancel、queued cancel by ID、cancel all；
- 失败/取消可观察，Runtime 继续处理后续命令。

限制：

| 常量 | 值 |
|---|---:|
| 最大排队数 | 16 |
| 最大历史数 | 32 |
| timed-flight 最大 duration | 6000 ticks |
| stop 速度阈值 | 0.01 blocks/tick |

命令类型：

- `TIMED_FLIGHT`：设置目标速度，保持 duration，然后请求 stop，等待实际速度降到阈值；
- `STOP`：请求 stop，等待阈值；
- `MINE_BLOCK`：通过 `InteractionHandler` 执行原生 `destroyBlock`；
- `USE_ITEM_ON_BLOCK`：通过 `InteractionHandler` 执行原生 `useItemOn`。

Runtime 不直接访问 `Level`、方块或实体位置；交互命令通过 `InteractionHandler` 窄接口调用实现。

### 库存

`SwarmieInventory` 是机器人自己的 9 槽 `Container`：

- 不共享玩家库存；
- `selectedSlot` 始终夹在 `0..8`；
- 入栈/出栈复制 `ItemStack`，避免外部可变 stack 污染；
- `insertItem` 先合并同类 stack，再填空槽；
- 使用 26.3 原生 `ContainerHelper.saveAllItems/loadAllItems` + `ValueOutput/ValueInput`；
- 实体子标签 `SwarmieInventory` 保存 `Items` 与 `SelectedSlot`；
- 加载时清理无效/越界数据，异常时回退为空库存。

权威库存始终是机器人库存；FakePlayer 的库存只是交互期间的临时副本。

### InteractionActor

`InteractionActor` 实现 `InteractionHandler` 和 `AutoCloseable`：

- 每个机器人拥有一个临时 `FakePlayer` 子类；
- FakePlayer 不加入世界、不作为机器人身体、不建立真实网络连接；
- 通过机器人当前 `ServerLevel`、位置、朝向和选中物品建立交互上下文；
- 交互前复制机器人选中 stack 到 FakePlayer 槽 0，交互后复制回机器人；
- 使用 `try/finally` 清理，且只有在机器人槽仍与操作前 stack 匹配时才写回；
- 使用原生 `ServerPlayerGameMode.destroyBlock` / `useItemOn`；
- 检查区块是否已加载、世界边界、交互距离和 `mayInteract`；
- 永久移除时关闭 FakePlayer，普通区块卸载不当作销毁。

**已知限制：GUI 容器。** Fabric `FakePlayer.openMenu` 返回
`OptionalInt.empty()`，且没有真实 connection。Chest 等标准 `MenuProvider` GUI
不会被实际打开；`ChestBlock` 仍可能返回 SUCCESS，但容器内容不变。这不是物品
复制/丢失，也不是 Runtime bug。详细机制和“服务端 `AbstractContainerMenu.clicked`”
可行性调查见 `docs/runtime-verification.md` §10.4。

### 世界查询

`WorldQuery` 是独立、可替换的能力，`DefaultWorldQuery` 为默认实现：

```java
queryBlock(ServerLevel, BlockPos)
queryRegion(ServerLevel, BlockPos from, BlockPos to)
queryEntities(ServerLevel, AABB box, Vec3 origin, EntityType<?> filter, UUID exclude)
```

- 查询前检查 `MinecraftServer.isSameThread()`；非服务器线程返回 `WRONG_THREAD`；
- 所有方块读取先 `isLoaded`，不隐式加载区块；
- 空气是成功的 `OK` 结果，不是错误；
- 区域最大 4,096 个位置，按位置报告 `unavailable`，`complete=false` 明确表示不完整；
- 实体 AABB 最大 65,536，最多 256 条结果，带 `truncated`；
- 结果都是不可变快照，不保留 live `Level`/`Entity`/`BlockState`。

`SwarmieEntity` 还提供：

```java
worldQuery() / setWorldQuery(...)
setWorldQueryEnabled(...) / queryBlock(...) / queryRegion(...)
```

### 局部传感器

`LocalSensor` 也是独立能力，`DefaultLocalSensor`：

- 只接受 `SwarmieEntity`，从实体权威位置和 `getViewVector(1.0F)` 获取观察姿态；
- 不能由调用者传入任意 origin，因此不能当世界查询用；
- 默认 range 8、FOV 90°、maxResults 32、LOS false；
- 上限 range 16、FOV 180°、maxResults 64；
- 结果为不可变 `SensorObservation`，包含 blocks/entities、相对位置、距离、`truncated`、`incomplete`；
- FOV 是围绕视线的 3D 锥形；超出 range/FOV 的不包含；
- `lineOfSight=true` 返回 `UNSUPPORTED`，未实现自建射线遮挡。

`SwarmieEntity.sense(SensorConfig)` 是面向实体的便捷入口。

### 客户端渲染

客户端只包含：

- `SwarmieClient`：Fabric client entrypoint，注册 renderer/model layer；
- `SwarmieEntityRenderer`：26.3 `EntityRenderer<T,S>` 路径；
- `SwarmieEntityModel`：无人机模型；
- `SwarmieRenderState`：渲染状态；
- `assets/swarmie/textures/entity/swarmie.png`：64×64 纹理。

26.3 的 `EntityRendererRegistry` 已弃用，当前源码使用兼容的注册路径；实际客户端已验证渲染可达。

### 调试命令

所有 `/swarmie` 命令要求 op level 2（`LEVEL_GAMEMASTERS`）。

> 选择器必须使用带命名空间的实体 ID：
> `@e[type=swarmie:swarmie,limit=1]`
> `@e[type=swarmie]` 会被解析成 `minecraft:swarmie` 并报 unknown entity type。

| 命令 | 作用 |
|---|---|
| `/swarmie velocity <targets> <x> <y> <z>` | 低层直接设置速度，不经过 Runtime |
| `/swarmie fly <targets> <x> <y> <z> <duration>` | 提交 timed-flight Runtime 命令 |
| `/swarmie stop <targets>` | 提交 stop 命令 |
| `/swarmie status <target>` | 当前命令、排队数、历史数 |
| `/swarmie results <target>` | 最近保留结果 |
| `/swarmie cancel <targets>` | 取消 active 命令 |
| `/swarmie cancelid <targets> <id>` | 按 ID 取消 active/queued |
| `/swarmie cancelall <targets>` | 取消 active + 全部 queued |
| `/swarmie mine <target> <x> <y> <z>` | 原生方块破坏 |
| `/swarmie use <target> <x> <y> <z> <face>` | 原生 item-on-block |
| `/swarmie inventory give <target> <item> <count>` | 特权测试：插入机器人库存 |
| `/swarmie inventory show <target>` | 查看机器人库存 |
| `/swarmie query block <x> <y> <z>` | 绝对方块查询 |
| `/swarmie query region <x1> <y1> <z1> <x2> <y2> <z2>` | 有界区域查询 |
| `/swarmie sensor scan <target> <range>` | 局部传感器扫描，range 0.1–16 |

示例：

```mcfunction
/summon swarmie:swarmie ~ ~1 ~
/swarmie fly @e[type=swarmie:swarmie,limit=1] 0.2 0 0 20
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie inventory give @e[type=swarmie:swarmie,limit=1] minecraft:iron_pickaxe 1
/swarmie inventory show @e[type=swarmie:swarmie,limit=1]
/swarmie mine @e[type=swarmie:swarmie,limit=1] 10 64 10
/swarmie use @e[type=swarmie:swarmie,limit=1] 10 64 10 up
/swarmie query block 10 64 10
/swarmie sensor scan @e[type=swarmie:swarmie,limit=1] 8
```

---

## 生命周期、持久化与线程

### Entity 生命周期

- 普通 chunk unload **不等于**永久销毁；
- `onRemoval` 对 `UNLOADED_TO_CHUNK` / `UNLOADED_WITH_PLAYER` 不作永久清理；
- 永久移除（kill/discard/dimension change）会关闭 InteractionActor，并终止 Runtime；
- 重载实体得到新 Runtime；队列和历史不跨重启持久化；
- 已验证：区块卸载后重载，UUID、位置、库存保持；Runtime 是新的。

### 线程

- `SwarmieEntity.tick()` 只在逻辑服务端调用 Runtime；
- WorldQuery 和 LocalSensor 检查 `MinecraftServer.isSameThread()`；
- 没有为查询引入线程池或异步调度；
- 没有全局队列或跨实体调度器。

### 数据

| 数据 | 持久化 |
|---|---|
| Entity UUID / position / rotation | Minecraft 原生 |
| 9 槽库存 + selectedSlot | `SwarmieInventory` + `ContainerHelper` |
| Runtime 队列/历史 | **不持久化** |
| 传感器/查询结果 | **不持久化**，调用级快照 |
| FakePlayer 上下文 | 临时；不持久化 |

---

## 验证与测试

### 自动化测试

当前唯一活动工作区：

```text
<workspace>
```

JUnit 5.13.1 已接入根项目，执行：

```powershell
.\gradlew.bat test
```

实际结果：

```text
43 tests PASSED
BUILD SUCCESSFUL
```

测试位于：

- `contract/src/test/java/dev/swarmie/contract/ContractTest.java`
- `src/test/java/dev/swarmie/runtime/SwarmieRuntimeTest.java`
- `src/test/java/dev/swarmie/runtime/SwarmieRuntimeEventTest.java`
- `src/test/java/dev/swarmie/adapter/registry/RobotRegistryTest.java`
- `src/test/java/dev/swarmie/adapter/event/AdapterEventStoreTest.java`
- `src/test/java/dev/swarmie/adapter/event/AdapterEventBusTest.java`
- `src/test/java/dev/swarmie/adapter/io/InboundQueueTest.java`
- `src/test/java/dev/swarmie/interaction/InteractionRuntimeTest.java`
- `src/test/java/dev/swarmie/sensing/SensingTest.java`
- `src/test/java/dev/swarmie/flight/FlightControllerTest.java`

覆盖 Runtime、库存/交互、传感几何、飞行控制、Adapter 契约。详细分层见
[`docs/testing.md`](docs/testing.md)。

### 构建与静态检查

```text
gradlew.bat --offline build
javac @tools/provision-tmp/diagnostics/javac.args
```

产物检查包括：

- `fabric.mod.json`、Mod ID、版本占位符、main/client entrypoint；
- `SwarmieMod`、`SwarmieEntity`、`SwarmieFlightController`；
- Runtime、库存、交互、query、sensor 类；
- Adapter contract 类；
- client renderer/model/state；
- 纹理与中英文语言文件；
- ZIP 完整性。

### 运行时实测

基线 JAR 已在一次性客户端世界实际执行并通过：

- summon、渲染；
- X/Y/Z timed flight、stop、单面墙碰撞；
- status/results、命令 ID、FIFO active/queued barrier；
- active/queued/cancelall、取消后恢复；
- 库存保存/重载、重载后飞行/放置；
- 铁镐挖掘 + `damage=1`、石头放置计数；
- 不可破坏方块、空气、未加载区块拒绝且库存不变；
- 打火石点燃火焰、lever 切换；
- block/region query、UNLOADED；
- 传感器方块/实体前后、超距、32-result `truncated=true`；
- 实体 active removal、chunk unload/reload、failure isolation。

**自动化测试通过不等于真实客户端回归通过。** 完整的命令、观察输出和未验证项见
[`docs/runtime-verification.md`](docs/runtime-verification.md)。

---

## 已知限制

### 环境

- **唯一活动源码工作区**：`<workspace>`（Git 分支 `main`）。
- 原工作区 `<original-workspace-backup>` 仅作为只读备份，
  不再参与日常同步；其 DSH ACL/ZipFS 问题不再研究。
- 未安装到正式 Minecraft launcher profile。

### 构建/运行

- 专用服务器未测试，EULA 未接受。
- 第二客户端同步未测试。
- 高速/斜向/台阶/天花板碰撞未覆盖。

### Runtime

- INTERRUPTED 内部历史在实体销毁后不可观察；
- 编程异常注入路径未通过公共命令验证；
- active cancel 的定量减速曲线未测量。

### 交互

- FakePlayer 无真实 connection，**标准 GUI 容器（chest/furnace/hopper/模组机器等）无法实际打开/操作**；
- `useItemOn` 的 SUCCESS 不代表容器操作成功；
- 保护区域、复杂方块、第三方模组 GUI 未验证；
- 服务端 `MenuProvider.createMenu + AbstractContainerMenu.clicked` 路径已调查为可行但**未实现、未 live 验证**。

### 查询/传感器

- 现有命令没有暴露 `queryEntities` 的 live 入口；实体查询 API 未做 live 验证；
- 传感器 `incomplete` 边界未构造；
- LOS 明确未支持；
- 传感器是 3D 锥形 FOV，不是矩形屏幕 FOV。

### 范围

不包含 AI、寻路、自动避障、任务编排、多智能体调度、GUI、脚本语言或通用网络协议；
仅保留默认关闭的 localhost JSONL 开发调试 transport。

---

## 开发约束

后续修改应保持以下边界：

1. `SwarmieEntity` 保持真实 `Entity`，不改成 `LivingEntity`/`BlockEntity`/玩家。
2. Minecraft 负责移动和碰撞；不要在 Runtime 里重写物理。
3. Runtime 只调度命令；不要让它遍历世界或持有 fake player。
4. 交互继续走原生 block/item API；不要用 `setBlock(AIR)` 替代挖掘。
5. WorldQuery 与 LocalSensor 保持独立、可替换；传感器不接受任意 origin。
6. 查询先检查 `isLoaded`，不得隐式加载区块。
7. 不引入全局可变队列、全局调度器或跨实体共享 Runtime。
8. 修复必须先有可复现的运行时证据；不要把环境问题误判成代码问题。
9. GUI 容器如需支持，另做窄的 menu adapter；不要伪造完整玩家连接或声称通用第三方 GUI 支持。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [`SPEC.md`](SPEC.md) | 权威开发规范与六关卡验收 |
| [`PROGRESS.md`](PROGRESS.md) | 当前状态、Gate 进度、历史 |
| [`docs/runtime-verification.md`](docs/runtime-verification.md) | 构建、JAR、客户端、regression、edge-case、GUI 容器调查 |
| [`docs/testing.md`](docs/testing.md) | 自动化测试、证据分层、真实客户端测试说明 |
| [`docs/adapter-contract.md`](docs/adapter-contract.md) | Adapter Contract 0.1 字段、版本、错误码 |
| [`docs/adapter-registry.md`](docs/adapter-registry.md) | 注册表、生命周期、serverEpoch、事件环与真实客户端观察 |
| [`docs/adapter-io.md`](docs/adapter-io.md) | 入站/出站、幂等、批量、事件分发 |
| [`docs/adapter-command-specs.md`](docs/adapter-command-specs.md) | 四命令完整规格、取消、副作用与容器限制 |
| [`docs/local-transport.md`](docs/local-transport.md) | 默认关闭的 localhost JSONL 传输与 Python 客户端 |
| [`HANDOFF.md`](HANDOFF.md) | 工作区、构建、传输启用/关闭、遗留项与交接顺序 |
| [`docs/test-matrix.md`](docs/test-matrix.md) | 最终自动化/客户端/静态/未验证矩阵 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 第三方克隆、构建、测试与 PR 约定 |
| [`CHANGELOG.md`](CHANGELOG.md) | 0.1.0 变更与已知限制 |
| [`docs/versioning.md`](docs/versioning.md) | Semantic Versioning 规范与发布流程 |
| [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | GitHub Actions：JDK 25 + Gradle test/build |
| [`docs/gate2-entity-notes.md`](docs/gate2-entity-notes.md) | 实体与渲染 |
| [`docs/gate3-flight-notes.md`](docs/gate3-flight-notes.md) | 飞行控制与碰撞 |
| [`docs/gate4-runtime-notes.md`](docs/gate4-runtime-notes.md) | Runtime 与命令 |
| [`docs/gate5-interaction-notes.md`](docs/gate5-interaction-notes.md) | 库存与原生交互 |
| [`docs/gate6-sensing-notes.md`](docs/gate6-sensing-notes.md) | 查询与传感器 |
| [`docs/version-baseline.md`](docs/version-baseline.md) | 版本矩阵与依据 |
| [`docs/provisioning-findings.md`](docs/provisioning-findings.md) | 构建环境阻断历史 |

---

## 当前结论

Swarmie 已经是一个**可构建、可启动、可实际驱动**的 Minecraft 26.3 Fabric 模组，核心链路已通过真实客户端测试；并已建立独立纯 Java `:contract`（0.1，pre-stable）、Adapter registry/事件/I/O、JUnit 测试和默认关闭的 localhost JSONL 调试 transport。

但它还不是“全部六关卡验收完成”的状态：构建环境统一在普通用户副本，GUI 容器、第二客户端、专用服务器、事件环实机溢出、部分 Runtime/传感器/碰撞边界仍未验证。当前最稳妥的策略是：

- 保留现有可用版本和基线 JAR；
- 不为了“让测试通过”重写架构；
- 只有在复现出明确缺陷时做最小修复；
- 新能力（如 GUI 容器适配器）单独设计、单独验证；
- localhost JSONL 只用于开发，不承担远程/公网/多人安全模型。

许可证：本项目使用 **MIT License**，见 [`LICENSE`](LICENSE)。`fabric.mod.json` 已同步为 `MIT`。




