[English](Home) | 简体中文

# Swarmie

面向 **Minecraft 26.3 / Fabric** 的可编程悬浮机器人。

- 当前版本：**0.1.0**（pre-1.0）
- 许可证：MIT
- 仓库：https://github.com/RrkwM/Swarmie
- Releases：https://github.com/RrkwM/Swarmie/releases

## 从这里开始

- [玩家指南](Player-Guide-zh-CN) — 安装与当前管理员预览
- [安装](Installation-zh-CN) — Fabric、Java 25 与 Release JAR
- [开发者指南](Developer-Guide-zh-CN) — 构建、架构与 Adapter
- [FAQ](FAQ-zh-CN) — 常见故障和限制

## 当前范围

Swarmie 提供真实 Minecraft `Entity`、服务端权威飞行、9 槽库存、原生交互、
每机器人 Runtime 命令、世界查询、本地传感器，以及独立于 Minecraft 的
Adapter 契约。

0.1.0 **没有合成/物品/刷怪蛋获取方式**，是管理员/开发者预览，使用
`/summon swarmie:swarmie` 创建机器人。

没有 AI、寻路、GUI 容器、远程网络服务或多机器人调度器。

## Canonical 文档

Wiki 只是简短导航/镜像。版本化 canonical 文档在仓库：

- https://github.com/RrkwM/Swarmie/blob/main/README.zh-CN.md
- https://github.com/RrkwM/Swarmie/blob/main/docs/player-guide.zh-CN.md
- https://github.com/RrkwM/Swarmie/blob/main/docs/developer-guide.zh-CN.md
- https://github.com/RrkwM/Swarmie/blob/main/CHANGELOG.zh-CN.md

## 已知限制

- FakePlayer 路径不支持 GUI 容器；
- 挖掘掉落留在地上，不会自动收集；
- localhost JSONL 仅开发者调试、默认关闭；
- 专用服务器、第二客户端、LOS 和部分碰撞边界未验证；
- Adapter 契约为 0.x，可能变化。
