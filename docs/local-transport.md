# Local JSONL Development Transport

> 仅本地开发调试。默认关闭，只监听 `127.0.0.1`，无认证，不面向远程/公网/多人。

## 1. 开启

在 Minecraft 的 `config/` 目录放置 `swarmie-transport.json`：

```json
{
  "enabled": true,
  "port": 25580,
  "maxConnections": 4,
  "maxLineBytes": 8192,
  "maxOutboundEvents": 256
}
```

没有配置文件或 `enabled=false` 时不启动。示例见
[`tools/client/swarmie-transport.json.example`](../tools/client/swarmie-transport.json.example)。

## 2. 线程与队列

- accept/reader/writer 都在独立 I/O 线程；
- 服务器线程的事件监听器只做有界队列 `offer`，不写 socket；
- 每个连接 outbound 队列上限 `maxOutboundEvents`；
- 慢消费者队列满时异步关闭连接；客户端可通过 epoch/sequence/`missed` 检测遗漏；
- 单连接请求行上限 `maxLineBytes`；
- 最大连接数 `maxConnections`。

## 3. 协议

每个消息是一行 JSON。

请求：

```json
{"op":"robots","id":"1"}
{"op":"events","after":0,"max":64,"id":"2"}
{"op":"submit","robot":"<uuid>","correlation":"c1","command":{"type":"STOP"},"id":"3"}
{"op":"cancel","robot":"<uuid>","correlation":"c1","id":"4"}
```

命令：

```json
{"type":"TIMED_FLIGHT","velocity":{"x":0.1,"y":0,"z":0},"durationTicks":20}
{"type":"STOP"}
{"type":"MINE_BLOCK","position":{"x":500,"y":99,"z":500}}
{"type":"USE_ITEM_ON_BLOCK","position":{"x":500,"y":99,"z":500},"face":"UP","hit":{"x":500.5,"y":100,"z":500.5}}
```

响应类型：`hello`、`robots`、`events`、`ack`、`cancel_ack`、`error`、
`pong`。

`hello`：

```json
{"type":"hello","protocol":"swarmie-jsonl/1","epoch":123}
```

事件既可由 `events` 请求拉取，也会在 END tick 主动推送：

```json
{"type":"events","batch":{...}}
```

## 4. Python 客户端

```text
python tools/client/swarmie_client.py \
  --host 127.0.0.1 --port 25580 \
  --duration 20 --mine-x 501 --mine-y 99 --mine-z 500
```

脚本无第三方依赖，执行：

1. `robots` 查询可用机器人；
2. mine 先执行（保证近距离）；
3. fly 20 ticks → 等待 `COMMAND_COMPLETED`；
4. stop → 等待终态；
5. 重复 stop correlation → `DUPLICATE`；
6. long flight → `cancel` → `COMMAND_CANCELLED`；
7. 关闭并用新连接拉取 `events`，报告 epoch/latest/missed。

## 5. 实机结果（2026-10-28）

- Minecraft 26.3 / Fabric Loader 0.20.1；transport 绑定 `127.0.0.1:25580`；
- Python `MINE_BLOCK SUCCEEDED`；
- Python `TIMED_FLIGHT SUCCEEDED`；
- Python `STOP SUCCEEDED`；
- duplicate stop：`DUPLICATE state=SUCCEEDED`；
- cancel：`CANCEL ACCEPTED`，随后 `COMMAND_CANCELLED`；
- active cancel 单独测试：`COMMAND_CANCELLED ... cancelled while active`；
- unknown cancel：`UNKNOWN` + `UNKNOWN_CORRELATION`；
- terminal cancel：`ALREADY_TERMINAL` + `state=CANCELLED`；
- reconnect：新连接 `poll_events(0,1)` 返回 epoch/latest/missed；
- 事件序号连续，未发生 ring 溢出。

## 6. 限制

- 无认证、无 TLS、无远程绑定、无多人安全模型；
- 无磁盘持久化；
- 事件推送队列满会断开慢消费者，不会阻塞服务器；
- 不是正式网络协议；仅开发调试。
