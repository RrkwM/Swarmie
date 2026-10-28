[English](Installation) | 简体中文

# 安装

## 前置条件

| 组件 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | 25 |

## 客户端

1. 为 Minecraft 26.3 安装 Fabric Loader；
2. 从 Release 下载 JAR：
   https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0
3. 把 `swarmie-0.1.0.jar` 放入游戏 `mods/`；
4. 把匹配版本的 Fabric API 也放入 `mods/`；
5. 用 Java 25 启动。

校验：

```powershell
Get-FileHash .\swarmie-0.1.0.jar -Algorithm SHA256
```

与 Release 页面公布的 SHA-256 对比。

## 第一个机器人

0.1.0 没有合成/物品/刷怪蛋。OP 使用：

```mcfunction
/summon swarmie:swarmie ~ ~ ~
/swarmie status @e[type=swarmie:swarmie,limit=1]
```

未发布的 `0.1.1-SNAPSHOT` 新增可合成刷怪蛋：4 铁锭、4 红石、1 铜锭，位于
“刷怪蛋”标签页。它只会创建新机器人，不会捕获、存储或召回已有机器人。

## 专用服务器

尚未完成专用服务器 smoke test。除非你愿意自行测试启动，否则请使用客户端预览。
`environment: "*"` 不是专用服务器已验证的声明。
