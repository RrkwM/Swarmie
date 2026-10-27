[English](FAQ) | 简体中文

# FAQ / 故障排查

## Mod 没有加载

检查：

- Minecraft **26.3**
- Fabric Loader **0.20.1**
- Fabric API **0.165.0+26.3**
- Java **25**
- JAR 在 `mods/`，不在世界目录

## `/swarmie` 提示无权限

所有命令使用 `LEVEL_GAMEMASTERS`；使用 OP/权限等级 2 的账号。

## 选择器找不到机器人

使用带命名空间的 ID：

```text
@e[type=swarmie:swarmie]
```

只写 `swarmie` 会被解析为 `minecraft:swarmie`。

## 挖掘失败

常见原因：

- 目标是空气；
- 目标超出 6 格交互距离；
- 目标区块未加载；
- 方块受保护或不可破坏。

挖掘掉落留在地上，**不会**自动收集。

## 使用/放置物品失败

`/swarmie use` 使用机器人当前选中槽。`/swarmie inventory give` 只插入物品，
不一定会切换选中槽。某些物品对特定方块返回 `Pass`。

## 箱子没有打开

已知限制：FakePlayer 路径不会打开真实 GUI/menu。返回成功不代表容器内容变化。

## 区块卸载后命令消失

卸载会中断命令，重载不会恢复。这是有意行为；见生命周期文档。

## 能否连接外部程序？

只能通过默认关闭、仅 loopback 的 JSONL 开发传输。它没有认证/TLS，不是远程或
多用户服务。

## 为什么没有配方/物品？

0.1.0 是管理员/开发者预览。尚未实现生存获取；当前入口是 `/summon`。
