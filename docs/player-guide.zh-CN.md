[English](player-guide.md) | 简体中文

# Player Guide

> 适用于 **Swarmie 0.1.0**。当前是管理员/开发者预览，不是完整生存玩法。

## 1. 前置条件

| 组件 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | 25 |

缺少 Fabric API 或低于 Java 25 时，游戏不会正常加载 Swarmie。

## 2. 安装

1. 安装 Fabric Loader 0.20.1 到 Minecraft 26.3。
2. 从 [Releases](https://github.com/RrkwM/Swarmie/releases) 下载：
   - `swarmie-0.1.0.jar`
   - `swarmie-0.1.0.jar.sha256`
3. 把 `swarmie-0.1.0.jar` 放入游戏目录的 `mods/`。
4. 把对应版本的 Fabric API 也放入 `mods/`。
5. 启动游戏。日志中应出现：

```text
Swarmie initialized; registered entity type swarmie:swarmie
```

### 校验下载

```powershell
Get-FileHash .\swarmie-0.1.0.jar -Algorithm SHA256
```

应得到 Release 页面公布的 SHA-256。

## 3. 当前如何获得机器人

**0.1.0 没有合成配方、物品或刷怪蛋。** 这是当前最重要的使用限制。

需要 OP/权限等级 2，使用：

```mcfunction
/summon swarmie:swarmie ~ ~ ~
```

查询已加载机器人：

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
```

选择器必须使用命名空间 ID：

```text
@e[type=swarmie:swarmie]
```

不要只写 `swarmie`，否则会被解析为 `minecraft:swarmie`。

## 4. 基础命令

所有 `/swarmie` 命令都需要 OP/权限等级 2。

### 飞行与停止

```mcfunction
/swarmie fly @e[type=swarmie:swarmie] 0.1 0 0 20
/swarmie stop @e[type=swarmie:swarmie]
```

`fly` 参数：

```text
x y z         速度，单位 blocks/tick
duration      持续 tick，范围 1..6000
```

这是速度控制，不是“移动到坐标”。持续时间结束后机器人会减速停止。

### 状态与结果

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie results @e[type=swarmie:swarmie,limit=1]
```

- `status`：当前命令、排队数、保留结果数；
- `results`：最近 5 条命令结果。

### 取消

```mcfunction
/swarmie cancel @e[type=swarmie:swarmie]
/swarmie cancelall @e[type=swarmie:swarmie]
/swarmie cancelid @e[type=swarmie:swarmie] 3
```

排队中的命令取消后不会执行；正在飞行的命令会停止，但已经产生的位移不可回滚。

### 挖掘

```mcfunction
/swarmie mine @e[type=swarmie:swarmie,limit=1] 100 64 100
```

- 目标必须在机器人 6 格范围内；
- 目标区块必须已加载；
- 掉落物留在世界中，**不会自动进入机器人库存**；
- 工具使用机器人当前选中槽。

### 使用物品/放置

```mcfunction
/swarmie use @e[type=swarmie:swarmie,limit=1] 100 63 100 up
```

face 可选：

```text
down up north south east west
```

- 使用机器人当前选中槽的物品；
- `inventory give` 只插入库存，不保证切换选中槽；
- 标准 GUI 容器（箱子、熔炉、漏斗等）目前不能真正打开；
- 箱子交互返回成功也不代表内容发生变化。

### 库存

```mcfunction
/swarmie inventory give @e[type=swarmie:swarmie,limit=1] minecraft:stone 1
/swarmie inventory show @e[type=swarmie:swarmie,limit=1]
```

库存为 9 槽，选中槽显示为 `*`。

### 查询与传感器

```mcfunction
/swarmie query block 100 64 100
/swarmie query region 100 64 100 110 74 110
/swarmie sensor scan @e[type=swarmie:swarmie,limit=1] 8
```

查询是只读操作；不会隐式加载区块。传感器最多 64 个结果，LOS 当前未支持。

### 低层速度（调试）

```mcfunction
/swarmie velocity @e[type=swarmie:swarmie] 0.1 0 0
```

这条路径绕过 Runtime 队列和命令结果，只适合调试；正式操作请使用 `fly`/`stop`。

## 5. 生命周期行为

- 区块卸载/重载后，机器人 UUID 和库存保留；
- 运行中或排队中的命令在卸载时变为 `INTERRUPTED`，不会在重载后恢复；
- 维度切换不会把机器人当作永久删除；
- `/kill` 等永久删除会产生 `REMOVED`；
- 命令结果和事件不会跨服务器进程持久化。

## 6. 故障排查

| 现象 | 处理 |
|---|---|
| 游戏启动时没有 Swarmie | 检查 Java 25、Fabric Loader、Fabric API、`mods/` 中的 JAR |
| `/swarmie` 提示无权限 | 需要 OP/权限等级 2 |
| 找不到机器人 | 确认已 `/summon`，并使用 `@e[type=swarmie:swarmie]` |
| `MINE_BLOCK FAILED (target is air)` | 目标坐标是空气，换一个实体方块 |
| `target is outside interaction range` | 目标距机器人超过 6 格 |
| `UNLOADED_CHUNK` | 先让目标区块加载 |
| 箱子没有打开 | 当前 FakePlayer 路径不支持 GUI 容器，属于已知限制 |
| 放置失败返回 `Pass[]` | 当前选中槽物品不能用于该方块；`inventory give` 不会自动切换选中槽 |

## 7. 当前限制

- 没有合成、物品、刷怪蛋或生存获取方式；
- 没有 AI、寻路、自动避障、任务编排；
- 没有 GUI 容器操作；
- 没有远程/公网控制；localhost JSONL 仅开发者调试用；
- 专用服务器、第二客户端、LOS、部分碰撞边界未验证；
- 0.1.0 是 pre-1.0 版本，行为和 Adapter 契约仍可能变化。

## 8. 下一步阅读

- [Developer Guide](developer-guide.zh-CN.md)
- [Adapter command specs](adapter-command-specs.zh-CN.md)
- [Test matrix](test-matrix.zh-CN.md)
- [Troubleshooting/FAQ](wiki/FAQ-zh-CN.md)



