[English](Player-Guide) | 简体中文

# 玩家指南

> Wiki 简短镜像。版本化完整文档：
> [docs/player-guide.zh-CN.md](https://github.com/RrkwM/Swarmie/blob/main/docs/player-guide.zh-CN.md)。

## 前置条件

- Minecraft 26.3
- Fabric Loader 0.20.1
- Fabric API 0.165.0+26.3
- Java 25

## 安装

1. 从 [Releases](https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0) 下载
   `swarmie-0.1.0.jar`；
2. 与 Fabric API 一起放入 `mods/`；
3. 启动游戏。

## 获得机器人

0.1.0 没有配方、物品或刷怪蛋。OP 使用：

```mcfunction
/summon swarmie:swarmie ~ ~ ~
```

选择器必须带命名空间：

```text
@e[type=swarmie:swarmie]
```

## 基础命令

```mcfunction
/swarmie status @e[type=swarmie:swarmie,limit=1]
/swarmie fly @e[type=swarmie:swarmie] 0.1 0 0 20
/swarmie stop @e[type=swarmie:swarmie]
/swarmie results @e[type=swarmie:swarmie,limit=1]
```

所有 `/swarmie` 命令需要 OP/权限等级 2。

## 重要限制

- 尚无生存获取方式；
- 不支持 GUI 容器；
- 挖掘掉落不会自动收集；
- `inventory give` 不切换选中槽；`use` 使用选中槽；
- 卸载时命令中断，不会恢复；
- 0.1.0 为 pre-1.0。

完整命令参考、生命周期与排障：
[玩家指南](https://github.com/RrkwM/Swarmie/blob/main/docs/player-guide.zh-CN.md)。
