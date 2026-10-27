English | [简体中文](adapter-io.zh-CN.md)

# Adapter Local I/O — Prompt 3

> Local Adapter; the optional localhost JSONL transport is developer-only and
> default-off.

## 1. Public entry points

```java
SubmitAck ack = SwarmieAdapter.submit(robotId, commandSpec, correlationId);
BatchSubmitAck batch = SwarmieAdapter.submitBatch(new BatchSubmission(submissions));
CancelAck cancel = SwarmieAdapter.cancel(robotId, correlationId);
AdapterEventBus.Subscription sub = SwarmieAdapter.subscribe(batch -> { ... });
```

## 2. Threading

- `submit`, `submitBatch`, `cancel` may be called from any thread;
- submission does only validation, registry snapshot lookup, idempotency and
  bounded enqueue;
- no Entity/Level/Runtime/FakePlayer access on the caller thread;
- Runtime work happens on the server thread at `START_SERVER_TICK`;
- events are written by the server thread and published at
  `END_SERVER_TICK`;
- listeners must return quickly and must not perform socket/disk/blocking I/O.

## 3. Bounded inbox

| Item | Value |
|---|---:|
| Global inbox capacity | 256 |
| Per-tick dispatch budget | 32 |
| Idempotency records | 1024 |
| Terminal TTL | 600 seconds |
| Cancel queue | 64 |
| Batch size | 64 |

- full inbox: `INBOX_FULL`;
- full Runtime queue: accepted by the inbox, then terminal
  `FAILED/QUEUE_FULL`;
- no accepted command may silently disappear.

## 4. Lifecycle distinction

```text
INBOX_ACCEPTED      SubmitAck.ACCEPTED, internal ID may be null
RUNTIME_ACCEPTED    COMMAND_ACCEPTED
STARTED             COMMAND_STARTED
TERMINAL            COMPLETED/FAILED/CANCELLED/INTERRUPTED
```

`COMMAND_ENQUEUED` means the inbound command was taken by the server tick.
`SubmitAck.ACCEPTED` is not execution completion.

## 5. correlationId and idempotency

- same correlation + same robot + same command: `DUPLICATE` with known
  internal ID/state, no re-execution;
- same correlation with a different robot/command: `CORRELATION_CONFLICT`;
- check/register/enqueue is one synchronized operation;
- 8-thread concurrency test ensures only one `ACCEPTED`;
- only terminal records expire (600 s); queued/running records do not expire;
- capacity pressure evicts the oldest terminal record, never an executing one;
- after expiry/eviction, a duplicate is treated as a new request;
- no cross-server-process persistence.

## 6. Cancel

```java
CancelAck ack = SwarmieAdapter.cancel(robotId, correlationId);
```

- unknown: `UNKNOWN`, `UNKNOWN_CORRELATION`;
- terminal: `ALREADY_TERMINAL` with the known state;
- accepted: queued for the server thread;
  - inbound queued: removed and `COMMAND_CANCELLED`;
  - Runtime active/queued: Runtime cancel, listener emits
    `COMMAND_CANCELLED`;
- cancellation is asynchronous; observe the terminal event.

Real-client results:

```text
active long flight -> CANCEL ACCEPTED -> COMMAND_CANCELLED (cancelled while active)
unknown            -> UNKNOWN + UNKNOWN_CORRELATION
terminal           -> ALREADY_TERMINAL + state=CANCELLED
```

## 7. Batch

- maximum 64; empty/oversized rejected by the constructor;
- per-item ack, no atomic batch;
- each item has its own correlation, idempotency and terminal event;
- constrained by inbox capacity and tick budget.

## 8. Outbound events

- `AdapterEventStore` is the single bounded source of truth;
- `AdapterEventBus` publishes `EventBatch` at END tick;
- subscribing the same listener twice returns the same subscription;
- one listener throwing does not break others;
- slow consumers detect loss through `missed`/sequence;
- a future network adapter must have its own bounded sender and dedicated I/O
  thread.

## 9. Unload and pre-Runtime commands

`AdapterSession.onEntityUnload`:

1. terminates Runtime active/queued commands;
2. removes queued inbound commands for that robot;
3. writes `COMMAND_INTERRUPTED` (`ROBOT_UNLOADED`) and updates idempotency;
4. writes `ROBOT_UNLOADED`.

Reload does not restore these commands.

## 10. Debug-command differences

`velocity`, `inventory give/show`, `query`, `sensor` are direct OP debug tools:
no public contract and no Adapter command events.

The old `fly/stop/mine/use` debug path also bypasses inbox/idempotency. It still
goes through the Runtime listener, so it produces command events with
`correlationId=null`. External clients must use `SwarmieAdapter.submit`.

## 11. Tests

- `InboundQueueTest`: capacity, budget, duplicates, conflicts, concurrency, TTL;
- `AdapterEventBusTest`: duplicate subscriptions, exception isolation;
- `AdapterEventStoreTest`: sequence, overflow, missed;
- `SwarmieRuntimeEventTest`: accepted/started/terminal, unload;
- real client and Python: see `local-transport.md`, `adapter-registry.md`.

## 12. Not implemented

- cross-process idempotency/event persistence;
- per-consumer independent event queues;
- remote/public transport, TLS, authentication, multi-user security.
