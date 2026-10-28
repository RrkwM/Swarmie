English | [简体中文](local-transport.zh-CN.md)

# Local JSONL Development Transport

> Local development only. Default-off, binds `127.0.0.1`, no authentication, no
> remote/public/multi-user security model.

## 1. Enable

Create `config/swarmie-transport.json` in the Minecraft run directory:

```json
{
  "enabled": true,
  "port": 25580,
  "maxConnections": 4,
  "maxLineBytes": 8192,
  "maxOutboundEvents": 256
}
```

No file or `enabled=false` means the listener is not started. See
[`swarmie-transport.json.example`](../tools/client/swarmie-transport.json.example).

## 2. Threads and queues

- accept/reader/writer threads are separate;
- the server-thread event listener only offers to a bounded queue, never writes a
  socket;
- per-connection outbound limit `maxOutboundEvents`;
- a slow consumer is disconnected asynchronously; reconnect uses epoch/sequence/
  `missed`;
- request line limit `maxLineBytes`;
- connection limit `maxConnections`.

## 3. Protocol

One JSON object per line.

```json
{"op":"robots","id":"1"}
{"op":"events","after":0,"max":64,"id":"2"}
{"op":"submit","robot":"<uuid>","correlation":"c1","command":{"type":"STOP"},"id":"3"}
{"op":"cancel","robot":"<uuid>","correlation":"c1","id":"4"}
{"op":"state","robot":"<uuid>","id":"5"}
{"op":"setpoint","robot":"<uuid>","channel":"velocity","value":{"x":0.1,"y":0,"z":0},"id":"6"}
{"op":"setpoint","robot":"<uuid>","channel":"velocity","clear":true,"id":"7"}
```

Commands:

```json
{"type":"TIMED_FLIGHT","velocity":{"x":0.1,"y":0,"z":0},"durationTicks":20}
{"type":"STOP"}
{"type":"MINE_BLOCK","position":{"x":500,"y":99,"z":500}}
{"type":"USE_ITEM_ON_BLOCK","position":{"x":500,"y":99,"z":500},"face":"UP","hit":{"x":500.5,"y":100,"z":500.5}}
```

Responses: `hello`, `robots`, `state`, `events`, `ack`, `cancel_ack`,
`setpoint_ack`, `error`, `pong`. State is a server-authoritative snapshot whose
`serverTick` is its freshness point. Setpoint acceptance is synchronous; it is
not an action and no per-update events are emitted. Events are also pushed at
END tick.

A velocity setpoint and legacy `TIMED_FLIGHT`/`STOP` cannot own the motion
channel simultaneously; the loser receives `MOTION_CHANNEL_BUSY`. Clear the
setpoint before using legacy motion, or wait for legacy motion to terminate
before setting a setpoint. The low-level debug commands are not part of this
protocol.

## 4. Python

The first official client library is the Python SDK in `tools/sdk/swarmie`,
documented in [`python-sdk.md`](python-sdk.md). It wraps discovery, explicit
UUID selection, live self-state, persistent velocity setpoints, submit/wait/
cancel and epoch/gap handling; it remains standard-library-only and synchronous.

```powershell
python tools/sdk/examples/01_list_robots.py
python tools/sdk/examples/02_fly_and_stop.py --robot <uuid>
python tools/sdk/examples/05_curve_flight.py --robot <uuid>
```

The end-to-end transport smoke test remains:

```powershell
python tools/client/swarmie_client.py --robot <uuid>
```

It queries robots, mines, flies and waits, stops, duplicates the stop
correlation, starts a long flight and cancels it, then reconnects and reports
epoch/latest/missed.

## 5. Live result

- transport bound to `127.0.0.1:25580`;
- Python mine/fly/stop succeeded;
- duplicate returned `DUPLICATE state=SUCCEEDED`;
- cancel produced `COMMAND_CANCELLED`;
- active cancel: `cancelled while active`;
- unknown cancel: `UNKNOWN`/`UNKNOWN_CORRELATION`;
- terminal cancel: `ALREADY_TERMINAL`;
- reconnect got epoch/latest/missed successfully;
- event sequence remained contiguous.

## 6. Limits

- no auth/TLS/remote binding/multi-user model;
- no disk persistence;
- slow consumers are disconnected, not blocked on;
- development protocol only.
