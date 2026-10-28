# Swarmie Public API v1 — Contract Design

> Status: **design only**. This document does not implement the API. No runtime,
> contract, JSONL, command or Python SDK behavior was changed by this design.
>
> Branch: `design/api-v1-contract`. Parent: `ca121f2`.
> Current implementation is pre-stable contract `0.1`; v1 is a design target,
> not a claim that v1 already exists.

## 0. How to read this document

- **MUST / MUST NOT** are requirements for a v1-conforming implementation.
- **SHOULD** is the expected behavior; a deviation must be documented.
- **MAY** is genuinely optional.
- **PROPOSED** marks a design element that has no current implementation.
- **CURRENT** marks observed implementation behavior, with a code path.
- `SUPPORTED`, `PARTIAL`, `MISSING`, `UNVERIFIED` are used **only** in the
  implementation mapping in §12. They are not claims in the API sections.

The design intentionally specifies the Python-facing semantic contract and the
minimum wire additions needed to support it. It does not redesign the local
JSONL transport, add authentication, or create a distributed system.

## 1. Five groups and their boundaries

| Group | Question | Owns | Does not own |
|---|---|---|---|
| **Robot** | Who am I controlling, and what can it do? | stable ID, lifecycle/dimension metadata, capability snapshot, exact-ID lookup | live pose/observation data, action lifecycle |
| **State** | What is true now? | self state, inventory, block/region/entity/sensor observation, freshness and completeness | identity selection, control, side effects |
| **Setpoints** | What should be maintained? | persistent velocity/orientation targets, replacement and clearing | queued actions, completion events |
| **Actions** | What should be done to the world? | request, admission, execution lifecycle, cancellation, terminal result, side-effect observation path | continuous control, capability discovery |
| **Events** | What happened asynchronously? | ordered facts, session epoch, sequence, gaps, action-result association | direct query responses, per-tick control acknowledgement |

### 1.1 Robot vs State

`Robot` is the **identity and capability entry point**. It binds one explicit
UUID and exposes the last-known registry metadata and capabilities.

`State` is the **current-data group**. It exposes live server-side observations
and is accessed through the bound `Robot` object so that no other object can
silently retarget. `Robot` MUST NOT grow a second live-state method that
duplicates `Robot.state()`, `Robot.inventory()` or the observation methods.

`Robot.info.position` is registry metadata refreshed at server tick end; it is
not a substitute for `Robot.state().position`. It may be one or more ticks old.
`Robot.info.last_updated_tick` makes that visible.

The existing `Robot.status()` remains a compatibility alias for refreshing
registry metadata; it is **not** the v1 live-state entry point.

### 1.2 Actions vs ActionHandle

`Robot` is responsible for constructing/validating an action request and
submitting it. The returned `ActionHandle` is responsible only for the
lifecycle of that one request: acknowledgement, state, terminal result,
cancellation, and waiting. An `ActionHandle` cannot be retargeted to another
robot or action.

`submit`, `wait` and `cancel` are API mechanics, not semantic primitives. They
are not a sixth group and do not appear in the capability catalogue. Cancel is
action lifecycle management on the returned handle; there is no separate
`Cancel` capability or group.

State calls are **queries**: they MUST NOT mutate the world, the inventory, the
action queue or any setpoint. Observation itself cannot be used to “probe by
doing”.

### 1.3 Direct responses vs events

| Interaction | Response | Event |
|---|---|---|
| `robots`, `state`, `inventory`, observations | direct response | none |
| action submit | direct `ActionAck` | action lifecycle events |
| action cancel | direct `CancelAck` | terminal event is authoritative |
| setpoint set/clear | direct `SetpointAck` | no per-update event |
| lifecycle change | none | `ROBOT_*` event |
| event cursor | `EventBatch` | — |

Direct acceptance is not completion or achievement. This distinction applies to
actions and setpoints alike.

### 1.4 Time semantics

The three behaviors are analysis categories, not new base classes:

| Kind | Temporal shape | Success question |
|---|---|---|
| **Query** | one request, one snapshot at a server tick | was this fact observable, and how complete is it? |
| **Setpoint** | persistent target until replaced/cleared/invalidated | was the target accepted, and what does state now show? |
| **Action** | admitted, then queued/running, then terminal | what terminal state, and what does post-action observation show? |

A query has no expected world side effect. A setpoint has no terminal state. An
action has a lifecycle and can be cancelled. The transport may reuse the same
request/response mechanism for all three; that does not make their semantics
interchangeable.

## 2. Minimal Python API

The v1 surface is deliberately small. It uses the existing synchronous SDK style
and adds no base-class hierarchy or registry framework.

```python
from swarmie import (
    SwarmieClient, Vec3, BlockPos, BlockTarget, BoundingBox,
    SensorConfig, ActionType, ObservationStatus, EventType,
)

with SwarmieClient() as client:
    # Robot: identity, metadata, capabilities.
    infos = client.robots()                 # List[RobotInfo]
    robot = client.robot(robot_uuid)        # exact UUID; never "first loaded"
    caps = robot.capabilities               # RobotCapabilities

    # State: current facts, never inferred from the registry.
    self_state = robot.state()              # RobotState
    inventory = robot.inventory()           # InventoryState
    block = robot.observe_block(BlockPos(10, 64, -3))
    region = robot.observe_region(BlockPos(8, 60, -6), BlockPos(12, 64, -2))
    entities = robot.observe_entities(
        BoundingBox(...), type_id="minecraft:cow", exclude_self=True
    )
    sensed = robot.sense(SensorConfig(range=6.0, fov_degrees=90.0))

    # Setpoints: replace-in-place control targets, not queued actions.
    ack = robot.set_velocity(Vec3(0.1, 0.0, 0.0))
    ack = robot.set_orientation(yaw=90.0, pitch=0.0)
    robot.clear_velocity()
    robot.clear_orientation()

    # Actions: independent lifecycle; accepted != completed.
    handle = robot.mine_block(BlockPos(10, 64, -3))
    handle = robot.use_item_on_block(
        BlockTarget(BlockPos(10, 64, -3), face="UP", hit=Vec3(10.5, 65.0, -2.5))
    )
    result = handle.wait(timeout=5.0)       # terminal ActionResult or WaitTimeout
    if result.failed:
        print(result.error, result.message)
    ack = handle.cancel()                   # CancelAck; terminal event authoritative

    # Events: existing cursor/epoch model.
    batch = client.poll_events(max_events=64)
    for event in batch.events:
        if event.event_type is EventType.COMMAND_COMPLETED:
            ...
```

### 2.1 Call-surface rules

- `SwarmieClient.robots()` is the only enumeration call.
- `SwarmieClient.robot(uuid)` MUST match the exact canonical UUID and MUST raise
  `RobotNotFound` when absent. It MUST NOT choose the first robot, the only
  loaded robot, or the nearest robot.
- `Robot` MUST expose `robot_id`, `lifecycle`, `dimension`, `capabilities`,
  `info`, `refresh()`, `state()`, `inventory()`, the four observation methods,
  the setpoint methods, and the two v1 action methods.
- `Robot` MUST NOT expose a method that returns a different robot.
- `ActionHandle` MUST expose `correlation_id`, `action_type`, `ack`, `state`,
  `result`, `is_terminal`, `wait(timeout=...)`, `cancel()`. `result` is `None`
  until a terminal result is known; `state` is the last known non-terminal or
  terminal state.
- `client.poll_events()` remains the event-read entry point. A separate
  subscription transport is not part of v1.
- `CommandHandle` remains a compatibility alias for `ActionHandle`; existing
  `submit(TimedFlight(...))` calls remain wire-compatible during the transition.
  New code uses the typed action methods.

The exact Python method names are part of the design proposal. Renaming during
implementation is allowed only if the semantic boundaries in §1 remain intact.

## 3. Data model

All observations are immutable snapshots. Timestamps are server-derived; client
wall-clock time is never used as a freshness proof. Code snippets omit
`typing`/`dataclasses` imports and use `Optional`/`Tuple` to preserve the
existing Python 3.9+ baseline.

### 3.1 Common values

```python
@dataclass(frozen=True)
class BlockPos:
    x: int
    y: int
    z: int

@dataclass(frozen=True)
class Vec3:
    x: float
    y: float
    z: float

@dataclass(frozen=True)
class BoundingBox:
    min: Vec3
    max: Vec3

@dataclass(frozen=True)
class BlockTarget:
    position: BlockPos
    face: Optional[str] = None   # DOWN/UP/NORTH/SOUTH/EAST/WEST
    hit: Optional[Vec3] = None   # required for use_item_on_block
```

`BlockTarget` is the v1 target model. It names an exact block and, for
item-on-block operations, an exact face and hit point. It does not resolve
"what the robot is looking at". There is no raycast target, nearest-block
target, entity selector, or pathfinding target in v1.

`observe_entities(box, type_id=None, exclude_self=True)` queries the exact box
and defaults to excluding the calling robot. `exclude_self=False` may include
the robot itself if it intersects the box.

`MINE_BLOCK` uses `position` only. `USE_ITEM_ON_BLOCK` requires both `face` and
`hit`. A dedicated `place_block` target is not part of v1; placement is
currently expressed through `use_item_on_block` when the selected item places a
block. This is a semantic limitation, not a claim that all placement cases are
covered.

### 3.2 Capabilities

```python
@dataclass(frozen=True)
class CapabilityLimits:
    max_speed: float                  # blocks/tick
    max_interaction_range: float      # blocks
    max_region_volume: int
    max_entity_results: int
    max_entity_query_volume: int
    sensor_max_range: float
    sensor_max_fov_degrees: float
    sensor_max_results: int
    action_queue_capacity: int
    parallel_actions: bool

@dataclass(frozen=True)
class RobotCapabilities:
    version: str                      # "1" for this contract
    state: frozenset[str]             # self, inventory, block, region, entities, sensor
    actions: frozenset[str]           # mine_block, use_item_on_block, ...
    setpoints: frozenset[str]         # velocity, orientation
    limits: CapabilityLimits
```

Capability names are stable strings. Clients MUST check the relevant set before
calling an operation. A missing name means **unsupported**, not "try it and see".
Unknown names from a newer server MUST be ignored, not treated as an error.

`version` changes when the capability set or a limit changes; it is not an
ordering token. The client MUST NOT infer support from a version comparison
alone. A capability addition/removal MUST change `version`; a bug fix that does
not change support MAY leave it unchanged.

`RobotInfo.supported_commands` remains for compatibility and maps to the legacy
four command types. `RobotCapabilities.actions` and `.setpoints` are the v1
source of truth.

Current implementation values, for reference only (they are `CURRENT`, not a
promise if a capability advertises a different limit): max speed `0.35`
blocks/tick, interaction range `6.0` blocks, region volume `4096`, entity
results `256`, entity query volume `65536`, sensor range `16` blocks/FOV `180`
degrees/results `64`, action queue `16`, `parallel_actions=false`.

### 3.3 Self and inventory

```python
@dataclass(frozen=True)
class RobotState:
    robot_id: str
    server_epoch: int
    server_tick: int
    dimension: str
    position: Vec3
    velocity: Vec3        # actual native velocity, not the setpoint target
    yaw: float            # degrees
    pitch: float          # degrees

@dataclass(frozen=True)
class ItemStack:
    item_id: str
    count: int

@dataclass(frozen=True)
class InventoryState:
    robot_id: str
    server_epoch: int
    server_tick: int
    selected_slot: int
    slots: Tuple[Optional[ItemStack], ...]   # exactly 9 entries; None = empty
```

`velocity` MUST be the actual entity velocity at `server_tick`, not the last
setpoint. `position` is the exact entity position, not a block coordinate.
`server_tick` is the single freshness field for the whole snapshot. Freshness is
a caller policy: the server tick proves ordering within an epoch, not that the
data is still current when the client uses it. The client MUST NOT use its local
wall clock as a freshness proof.

The inventory is the robot-owned nine-slot inventory. `selected_slot` is 0..8.
`None` means observed empty; there is no "unknown slot" in a successful
inventory response. Item components (durability, enchantments, custom data) are
**not** represented in v1; `item_id` and `count` are intentionally minimal.

`Robot.state()` and `Robot.inventory()` MUST fail with `RobotNotLoaded` rather
than returning a fabricated empty state when the robot is not `LOADED`.

All State calls require a `LOADED` robot; otherwise they MUST raise
`RobotNotLoaded`. A loaded robot may still observe an `UNLOADED` target: that is
an observation status, not a reason to raise away the boundary information.

### 3.4 World observations

```python
class ObservationStatus(str, Enum):
    OK = "OK"
    UNLOADED = "UNLOADED"
    OUT_OF_BOUNDS = "OUT_OF_BOUNDS"
    INVALID = "INVALID"
    UNSUPPORTED = "UNSUPPORTED"
    ERROR = "ERROR"

@dataclass(frozen=True)
class BlockSnapshot:
    position: BlockPos
    block_id: str
    properties: Mapping[str, str]
    air: bool

@dataclass(frozen=True)
class BlockObservation:
    position: BlockPos
    status: ObservationStatus
    block: Optional[BlockSnapshot]
    reason: str
    server_epoch: int
    server_tick: int

@dataclass(frozen=True)
class RegionObservation:
    from_pos: BlockPos
    to_pos: BlockPos
    status: ObservationStatus
    blocks: Tuple[BlockSnapshot, ...]
    unavailable: Tuple[BlockPos, ...]
    complete: bool
    requested_volume: int
    reason: str
    server_epoch: int
    server_tick: int

@dataclass(frozen=True)
class EntitySnapshot:
    uuid: str
    type_id: str
    position: Vec3
    bounding_box: BoundingBox
    distance: float

@dataclass(frozen=True)
class EntityObservation:
    status: ObservationStatus
    entities: Tuple[EntitySnapshot, ...]
    complete: bool
    truncated: bool
    reason: str
    server_epoch: int
    server_tick: int

@dataclass(frozen=True)
class SensorConfig:
    range: float = 8.0
    fov_degrees: float = 90.0
    max_results: int = 32
    line_of_sight: bool = False

@dataclass(frozen=True)
class SensorObservation:
    status: ObservationStatus
    origin: Vec3
    direction: Vec3
    config: SensorConfig
    blocks: Tuple[SensorBlock, ...]
    entities: Tuple[SensorEntity, ...]
    truncated: bool
    incomplete: bool
    reason: str
    server_epoch: int
    server_tick: int
```

`SensorBlock` and `SensorEntity` carry the same snapshot plus relative geometry:

```python
@dataclass(frozen=True)
class SensorBlock:
    block: BlockSnapshot
    relative: Vec3
    distance: float

@dataclass(frozen=True)
class SensorEntity:
    entity: EntitySnapshot
    relative: Vec3
    distance: float
    angle_degrees: float
```

### 3.5 Information-boundary rules

These are MUST requirements:

1. **Unknown is not absent.** A block/region query that cannot read a position
   MUST report `UNLOADED`, `OUT_OF_BOUNDS`, `UNSUPPORTED` or `ERROR`; it MUST NOT
   return an air block for that position.
2. **Partial is not complete.** `RegionObservation.complete` MUST be false if
   any requested position is unavailable, and every such position MUST appear in
   `unavailable`. `EntityObservation.complete` MUST be false if the query box
   spans unloaded chunks. `SensorObservation.incomplete` MUST be true if any
   scanned position/chunk was unavailable.
3. **Truncation is explicit.** `EntityObservation.truncated` and
   `SensorObservation.truncated` MUST be true when results were cut to the
   limit. `complete`/`incomplete` and `truncated` are independent: a result can
   be complete (all queried chunks were readable) while also truncated (the
   result list was capped). An empty list with `complete=true` means
   "observed none"; an empty list with `complete=false` means "cannot say".
4. **Air is an observation.** `BlockObservation.status == OK` with
   `block.air == true` is an observed air block. `status != OK` with
   `block is None` is not an observation.
5. **No fabricated aggregates.** A region response MUST NOT synthesize blocks
   for unavailable positions, and a sensor response MUST NOT synthesize
   line-of-sight visibility that the implementation did not test.

Limits are part of the capability contract: block regions are capped at 4096
positions, entity query boxes at 65536 volume and 256 returned entities, sensor
range at 16 blocks, FOV at 180 degrees, and sensor results at 64. A request that
exceeds a hard limit MUST fail with `INVALID`/`INVALID_ARGUMENT`, not silently
truncate unless the operation explicitly has a truncation field.

### 3.6 Setpoint acknowledgement

```python
@dataclass(frozen=True)
class SetpointAck:
    channel: str                 # "velocity" | "orientation"
    revision: int
    active: bool                 # True for set, False for clear
    accepted: bool
    effective: Optional[Vec3]    # velocity only; None for orientation/clear
    clamped: bool
    server_epoch: int
    server_tick: int
    error: Optional[str]
    message: str
```

`revision` is a per-robot, per-channel monotonically increasing counter for
accepted set/clear operations. A client that receives an older revision MUST
ignore it for determining the current target. Revisions are not durable across
server epochs. A rejected setpoint request MUST NOT change the target or
increment the revision; its ack reports the current `active`/`revision` and a
non-null `error`.

## 4. Setpoint contract

### 4.1 Channels and behavior

| Channel | Operations | Semantics |
|---|---|---|
| `velocity` | `set_velocity(Vec3)`, `clear_velocity()` | desired body velocity in blocks/tick; latest accepted request replaces the old target |
| `orientation` | `set_orientation(yaw, pitch)`, `clear_orientation()` | desired look direction in degrees; latest accepted request replaces the old target |

A setpoint is **not an action**:

- it MUST NOT enter the per-robot action FIFO;
- it has no `QUEUED`/`RUNNING`/terminal lifecycle;
- it has no per-update completion event;
- it remains in effect across ticks until replaced, cleared, or invalidated by
  robot lifecycle.

`set_velocity` MUST reject non-finite values. The v1 **proposal** is to clamp a
velocity longer than `CapabilityLimits.max_speed` on the server, set
`clamped=true`, and report the clamped vector in `effective`; the alternative is
to reject it. This clamping behavior is an open decision (§13 item 2), not a
current implementation claim.

`set_orientation` MUST reject non-finite values, yaw outside `[-180, 180]`, or
pitch outside `[-90, 90]`. It does not promise a rotation rate; a future
implementation MAY snap or rate-limit, and the capability contract does not
guarantee smoothness.

### 4.2 Replacement, clearing and parallelism

1. **Acceptance is not achievement.** `SetpointAck.accepted=true` means the
   server accepted the target. It does not mean the robot has reached it, and
   the target is applied on a subsequent server tick, not necessarily before the
   ack is emitted. `Robot.state().velocity` is the achievement observation.
2. **Same channel is last-writer-wins by server order.** A newer accepted
   request replaces the older target; no queue and no “apply both”. `revision`
   disambiguates acknowledgement order.
3. **Different channels may coexist.** `velocity` and `orientation` are
   separate channels. If both are advertised, they MUST be independently
   replaceable. A deployment that cannot apply them independently MUST NOT
   advertise both.
4. **Clear releases the target.** `clear_velocity()` removes the velocity
   target; the controller returns to its idle behavior (deceleration/damping).
   `clear_orientation()` removes the orientation target; the robot keeps its
   current orientation under whatever native behavior applies.
5. **Zero is not a separate hover primitive.** `set_velocity(Vec3.ZERO)` is an
   explicit zero-velocity target. With the current no-gravity body it is the
   v1 “hover/station-keeping” setting, but it is **not** a position-hold
   controller: collisions, external pushes or dimension changes can move the
   robot, and v1 does not correct position error. A true position hold would be
   a new setpoint channel or controller, not a v1 guarantee.
6. **Lifecycle invalidation.** Unload, removal or dimension change clears all
   setpoints for that robot. A reload does not resume the old target; the client
   must set it again. The lifecycle event is the explanation; no separate
   `SETPOINT_CLEARED` event is required.
7. **No high-frequency event stream.** A setpoint request produces a direct
   ack, not an event per update. Rejections are visible in the ack. This avoids
   turning a control loop into an event flood.

### 4.3 Existing `TIMED_FLIGHT` and `STOP`

`TIMED_FLIGHT` is a bounded, queued action in the current runtime, not a
continuous setpoint. It remains a legacy action during transition. New code
that needs continuous control MUST use `set_velocity`/`clear_velocity`; it MUST
NOT assume `TIMED_FLIGHT` is non-blocking or persistent.

`STOP` is likewise a legacy queued action that waits for speed below a
threshold. The v1 setpoint equivalent is `clear_velocity()` (or an explicit
zero target); it is not queued and does not wait for a terminal event. The
implementation MUST NOT route setpoint requests through the action FIFO.

`TIMED_FLIGHT` and `STOP` remain in `RobotInfo.supported_commands` for
compatibility, but they are not entries in the v1 action catalogue and are not
setpoints. New code should not use them for continuous control.

## 5. Action contract

### 5.1 Catalogue and v1 scope

The public catalogue names all candidate operations, but v1 does **not** promise
all of them. Only the first two are v1 requirements; the rest are
capability-gated and absent from the v1 Python methods until implemented.

| Action | Target | v1 required? | Resources | Current status |
|---|---|---:|---|---|
| `mine_block` | `BlockPos` | **yes** | hand, interaction | SUPPORTED, with caveats |
| `use_item_on_block` | `BlockTarget` | **yes** | hand, interaction, selected slot (implicit) | SUPPORTED, with caveats |
| `attack_entity` | entity UUID | no | hand, interaction | MISSING |
| `use_item` | explicit aim/hand | no | hand, inventory | MISSING |
| `place_block` | `BlockTarget` | no | hand, interaction, inventory | PARTIAL via `use_item_on_block` |
| `pickup_item` | entity UUID or item filter | no | hand, inventory | MISSING |
| `transfer_item` | source/destination container | no | hand, inventory, interaction | MISSING |

`ActionType` values may be reserved for all rows so capability data is stable,
but the v1 Python object exposes only `mine_block` and `use_item_on_block`.
Adding a future method is a capability addition, not a silent activation of an
unimplemented operation.

### 5.2 Request, acceptance, execution, result

```python
@dataclass(frozen=True)
class ActionAck:
    robot_id: str
    correlation_id: str
    action_type: str
    accepted: bool
    state: Optional[str]       # QUEUED when accepted
    error: Optional[str]
    message: str

@dataclass(frozen=True)
class ActionResult:
    robot_id: str
    correlation_id: str
    action_type: str
    state: str                 # SUCCEEDED/FAILED/CANCELLED/INTERRUPTED
    error: Optional[str]
    message: str
    event_sequence: Optional[int]
    server_tick: Optional[int]
    from_ack: bool
    had_gap: bool

    @property
    def ok(self) -> bool:
        return self.state == "SUCCEEDED"

    @property
    def failed(self) -> bool:
        return self.state == "FAILED"
```

The lifecycle is:

```text
submit -> ActionAck(accepted) -> QUEUED -> RUNNING -> terminal ActionResult
                                                     (SUCCEEDED/FAILED/CANCELLED/INTERRUPTED)
```

- **Accepted** means admitted by the boundary; it does not mean started.
- **Rejected** means the request never entered the queue and no action lifecycle
  events will follow. The typed SDK action method MUST raise `RequestRejected`
  for a rejected ack and MUST NOT return a handle; the caller must use a new
  correlation for a retry.
- **Running** means the runtime has begun the operation.
- **Terminal** is final; no resume.
- **Cancel** is a request. `CancelAck.ACCEPTED` only queues cancellation; the
  terminal `COMMAND_CANCELLED` event/result is authoritative. A queued action
  is removed before execution. An active action is asked to stop; movement,
  already-performed native effects and synchronous one-tick operations cannot be
  rolled back. Cancellation before start produces `CANCELLED`; cancellation
  after a terminal state returns `ALREADY_TERMINAL`, not a second result.
- **Interruptibility:** every v1 action is cancellable before it starts. Active
  cancellation is best-effort. The current `mine_block`/`use_item_on_block` are
  atomic one-tick native operations, so active cancellation cannot undo them.
  No action is resumable after interruption; a retry is a new correlation.
- **Idempotency** is by caller-supplied `correlation_id` (1..128 characters).
  Re-submitting the same correlation while live returns the known state
  (`DUPLICATE`), not a second execution. A new correlation is a new attempt.
- **Wait timeout** is a client-side condition; it MUST NOT change the action's
  server state and MUST NOT be reported as action failure.
- **Gap/session change** makes the result uncertain; the client MUST NOT infer
  success or failure from silence.

The existing `CommandResult`/`CommandState` names remain wire-compatible during
transition. v1 `ActionResult` is the semantic name; `CommandResult` may be a
compatibility alias.

`from_ack=true` means the terminal state came from a duplicate acknowledgement
rather than a retained terminal event; `had_gap=true` means a gap was observed
while resolving the result. These fields are diagnostic: they MUST NOT be used
to claim a fresh side effect or to fill a missing event.

### 5.3 Preconditions and failure

For the two v1 actions, the implementation MUST validate at execution time (not
only at submission):

- robot is `LOADED` and not removed;
- target chunk is loaded;
- target is within the world border;
- distance from the robot to the block center is within the interaction range;
- the target is not air;
- for mining, the block is breakable;
- for item use, the selected slot is the handoff slot and the native result
  consumes an action.

A failure at submission is a rejected `ActionAck`; a failure after admission is
a terminal `FAILED` result with an error code/message. The two MUST NOT be
conflated.

There is no target reservation or target-version token. A submitted block
coordinate is revalidated against the current world at execution time. If the
block changed between submission and execution, the target is stale; the action
MUST fail or use the current block state according to its operation semantics,
and it MUST NOT pretend the originally observed block was still there.

### 5.4 World side effects

`SUCCEEDED` means the operation's native call returned success/consumed an
action. It does **not** prove a particular world state:

- mining: native destruction may be true while the resulting drops/state still
  need observation; a subsequent `observe_block` is the confirmation path;
- item-on-block: effects are item/block-specific; a container may report success
  without opening a GUI or changing contents (CURRENT limitation);
- the action result deliberately does **not** embed a claimed world-effect
  payload in v1. The client observes `observe_block`, `inventory()` or
  `sense()` and decides.

If an implementation wants to add an effect-evidence payload later, it must be
an explicit new field/version; v1 MUST NOT fabricate `confirmed=true`.

## 6. Resources and concurrency

### 6.1 Resource classes

| Resource | Held by | Rule |
|---|---|---|
| `body.motion` | velocity setpoint | one active target; latest accepted wins |
| `body.orientation` | orientation setpoint | one active target; latest accepted wins |
| `hand` | mine/use/place/attack | one action at a time per robot |
| `inventory` | pickup/transfer and item handoff | mutation only by the owning action; state reads are snapshots |
| `interaction` | mine/use/place/attack | one native interaction at a time per robot |

### 6.2 v1 rules

1. Actions are serialized per robot by a FIFO. There is no parallel action
   execution in v1; `CapabilityLimits.parallel_actions` MUST be false.
2. A second action request is queued, not rejected for resource conflict, unless
   the per-robot queue is full (`QUEUE_FULL`). Resource conflict is therefore not
   normally observable in v1; it becomes observable only if a future
   implementation advertises `parallel_actions=true`.
3. Motion setpoints and hand actions may coexist. Mining/item-use is atomic on
   one server tick in the current implementation; it MUST revalidate range at
   execution. A motion setpoint may continue during the action.
4. **Mining does not block the motion setpoint in v1**, because the current
   operation is a one-tick native call. If a future implementation makes mining
   multi-tick, it MUST either pause/clear `body.motion` or declare a conflict;
   it cannot silently keep moving out of range while claiming the old semantics.
5. Orientation and hand actions may coexist **if** orientation is advertised.
   Orientation is MISSING today.
6. Two competing actions use FIFO; the second waits. A future parallel
   implementation MUST reject a conflicting active-resource request with
   `RESOURCE_CONFLICT` naming the holder; it must not run both silently.

The current runtime's FIFO ordering is the basis for rule 1. It is not a
promise that a future scheduler will preserve FIFO; that would be a capability
change.

## 7. Robot and capability contract

- Robot identity is the canonical UUID (`RobotId`). It is stable within a world
  and across unload/reload; a removed record may be pruned after the registry
  retention limit (currently 256 removed records), after which lookup raises
  `RobotNotFound`.
- `LOADED` means queries and actions are available. `UNLOADED` means metadata is
  retained but live state/actions are unavailable. `REMOVED` is permanent from
  the client's perspective until a new entity with the same UUID appears.
- A dimension change is a lifecycle sequence
  (`ROBOT_DIMENSION_CHANGED`, then unloaded/reloaded), not a new robot ID.
  Setpoints and in-flight actions do not survive it.
- `RobotInfo` is last-known metadata; `last_updated_tick` is the freshness
  field. It is refreshed at server tick end, not per client request. The
  capability snapshot is part of that metadata; clients SHOULD `refresh()` after
  a lifecycle event and MUST tolerate a stale snapshot producing a
  request-level unsupported error.
- Capability discovery is advisory but authoritative for support:
  - a client MUST NOT call a method whose capability is absent;
  - an implementation MUST reject unsupported calls with
    `UNSUPPORTED_CAPABILITY` (or the legacy `UNSUPPORTED_COMMAND` for old
    command submission);
  - capability absence is not an error in the robot itself; it is a normal
    capability difference.

## 8. Events, sessions and gaps

### 8.1 Event model

The existing `RobotEvent` shape is reused:

```text
event_id, server_epoch, sequence, server_tick, type,
robot_id, correlation_id?, command_result?, detail
```

v1 keeps the existing `COMMAND_*` event names for action lifecycle
compatibility. They are action events in the v1 vocabulary; no duplicate
`ACTION_*` stream is introduced. Robot lifecycle events remain `ROBOT_*`.

No setpoint events are added in v1. A setpoint set/clear has a direct ack;
lifecycle invalidation is explained by `ROBOT_UNLOADED`/`ROBOT_REMOVED`/
`ROBOT_DIMENSION_CHANGED`.

### 8.2 Sequence, epoch and gaps

- `server_epoch` identifies one Adapter session. It changes on server restart;
  it is not a wall-clock value.
- `sequence` starts at 1 and increases contiguously within an epoch.
- The event store is bounded (currently 1024 events). If a cursor is older than
  the oldest retained event, the batch sets `missed=true` and starts at the
  oldest available sequence.
- Clients MUST use `(server_epoch, sequence)` and MUST NOT fabricate missing
  events. A gap is a visible uncertainty, not a retryable "probably fine".
- If a terminal action event falls in a gap and no terminal state was already
  obtained from a direct duplicate ack, the action result is unknown. `wait()`
  MUST raise a gap error rather than return success or a timeout that implies
  the action is still running. If a terminal state was already known directly,
  it may be returned with `had_gap=true`; it must not be presented as a fresh
  event.
- On reconnect:
  - same epoch: replay from the cursor if retained; otherwise gap;
  - different epoch: old sequence/cursor is invalid; pending actions are not
    resumable and old handles raise `SessionChangedError`. The client must
    re-discover robots and re-read state.
- Delivery is not exactly-once. A reconnect may replay events; clients SHOULD
  deduplicate by `(server_epoch, sequence)` and MUST treat terminal processing
  as idempotent.
- Events for other robots are not discarded merely because the current handle
  is for one robot; the client cursor is global to the connection.
- A slow consumer may be disconnected when the outbound event queue is full.
  Events in the ring may still be readable after reconnect; events dropped from
  the ring are permanently unavailable.

### 8.3 Association and ordering

- `correlation_id` associates submit, cancel, events and `ActionResult`.
- Within one epoch, events are ordered by `sequence`. Per-robot command events
  follow `ENQUEUED` → `ACCEPTED` → `STARTED` → terminal.
- Cross-robot ordering is only the global sequence; no stronger ordering is
  promised.
- `event_id` is unique; clients may use it for deduplication but must not assume
  it is numeric or ordered.

## 9. Errors and results

### 9.1 Three failure layers

| Layer | Examples | Representation |
|---|---|---|
| Request | malformed request, unknown robot, unsupported capability, invalid setpoint | `RequestRejected` / rejected ack with code+message |
| Action terminal | precondition failed, native operation failed, interrupted, cancelled | terminal `ActionResult` with `FAILED`/`INTERRUPTED`/`CANCELLED` |
| Client wait | deadline exceeded, connection lost, event gap, epoch changed | `WaitTimeout`, `ConnectionClosed`, `EventGapError`, `SessionChangedError` |

An action failure MUST NOT be raised as a request rejection; a wait timeout MUST
NOT be reported as an action failure.

`RobotNotLoaded` is a proposed typed convenience for the existing
`ROBOT_NOT_LOADED` condition. The current SDK exposes that condition through
`CommandRejected`/ack codes for command submission, not through a dedicated
state API (which does not exist yet).

### 9.2 Error-code mapping

| Situation | v1 representation | Existing support |
|---|---|---|
| Robot UUID absent | `RobotNotFound` / `ROBOT_NOT_FOUND` | SUPPORTED |
| Robot unloaded/removed | `ROBOT_NOT_LOADED` / `ROBOT_REMOVED` | SUPPORTED |
| Target invalid/unloaded/out of bounds | observation `status` or action `FAILED` with `TARGET_INVALID` (PROPOSED) | PARTIAL: statuses exist internally; no public code |
| Target changed since submission | no reservation; revalidate at execution; `FAILED`/`TARGET_INVALID` or current-state semantics | PARTIAL: no versioned target |
| Target unreachable/out of range | action `FAILED` with `EXECUTION_FAILED` + message; `TARGET_UNREACHABLE` is PROPOSED | PARTIAL: message only |
| Precondition failed | action `FAILED` (message/EXECUTION_FAILED) | PARTIAL |
| Capability unsupported | `UNSUPPORTED_CAPABILITY` (PROPOSED) or `UNSUPPORTED_COMMAND` for legacy commands | PARTIAL |
| Resource conflict | `RESOURCE_CONFLICT` (PROPOSED; not observable in FIFO v1) | MISSING |
| Request rejected | `ActionAck.accepted=false` + `RequestRejected` | SUPPORTED |
| Cancel correlation unknown | `CancelUnknown` / `CancelAck.UNKNOWN` | SUPPORTED |
| Action execution failed | terminal `ActionResult(FAILED)` | SUPPORTED |
| Wait timeout | `WaitTimeout` (client-only) | SUPPORTED |
| Event gap / session change | `EventGapError` / `SessionChangedError` | SUPPORTED |

The minimum exception hierarchy is `SwarmieError` plus the existing concrete
types; v1 MUST NOT create one exception per Minecraft failure. New codes are
wire strings and may be added without a new exception class.

## 10. Proposed transport shape (not implemented)

The existing JSONL ops remain: `robots`, `events`, `submit`, `cancel`, `ping`.
v1 needs two additions; both are local development protocol, not a remote API.

```json
{"op":"observe","id":"7","robot":"<uuid>",
 "query":{"kind":"block","position":{"x":10,"y":64,"z":-3}}}
```

```json
{"op":"setpoint","id":"8","robot":"<uuid>",
 "channel":"velocity","value":{"x":0.1,"y":0,"z":0}}
```

Proposed responses:

```json
{"type":"observation","id":"7","serverEpoch":123,"serverTick":456,
 "observation":{"kind":"block","status":"OK","block":{...}}}
```

```json
{"type":"setpoint_ack","id":"8",
 "ack":{"channel":"velocity","revision":3,"active":true,"accepted":true,
        "effective":{"x":0.1,"y":0,"z":0},"clamped":false,
        "serverEpoch":123,"serverTick":456,"error":null,"message":""}}
```

- `observe.query.kind` is one of `self`, `inventory`, `block`, `region`,
  `entities`, `sensor`.
- The `robots` response descriptor MUST carry the v1 `capabilities` object (and
  retain `supportedCommands` for compatibility). A client must not infer
  state/setpoint support from `supportedCommands` alone.
- Minimal query fields:

  | kind | fields |
  |---|---|
  | `self` | none |
  | `inventory` | none |
  | `block` | `position` |
  | `region` | `from`, `to` |
  | `entities` | `box` (`min`,`max`), `typeId?`, `excludeSelf?` (default true) |
  | `sensor` | `range?`, `fovDegrees?`, `maxResults?`, `lineOfSight?` (default false) |

- One request produces one response; no per-observation event.
- Queries execute on the server thread and include `serverEpoch`/`serverTick`.
- World-boundary failures are returned as `observation.status`, not as a
  protocol error; malformed requests are protocol errors.
- `setpoint` accepts `value` for set and `clear:true` for clear. Invalid values
  are rejected in `setpoint_ack`; velocity length handling is the proposed
  clamp-or-reject decision in §13 item 2, not a hidden behavior.
- No batching, streaming subscription, authentication, TLS or remote binding is
  designed here.

This shape is deliberately minimal. It does not require a new message bus or a
new controller framework; it extends the existing request/response transport
and reuses the existing event model.

## 11. Scenario validation

### 11.1 Scenario: smart farming

The robot must observe soil/crops, inspect inventory, move to a target, use a
tool or seed, and check the result.

The contract expresses this as:

1. `client.robot(uuid)`, then `robot.capabilities` to confirm `state.block`,
   `state.inventory`, `state.region`, `setpoints.velocity`, `actions.mine_block`
   and `actions.use_item_on_block`.
2. `robot.observe_region(field_min, field_max)` returns blocks plus
   `unavailable` and `complete`. If `complete=false`, the client MUST NOT
   conclude that an unavailable position is empty; it queries those positions
   individually or chooses a smaller region.
3. `robot.inventory()` gives nine slots and `selected_slot`. The client checks
   the seed/tool item ID and count. It cannot see tool durability/components in
   v1; that is a known limitation, not a hidden capability.
4. `robot.set_velocity(Vec3.ZERO)` (or a small approach velocity followed by
   `clear_velocity()`) brakes the robot; `robot.state().velocity` confirms
   convergence. There is no “setpoint reached” event; polling `state()` is the
   contract.
5. `handle = robot.use_item_on_block(BlockTarget(soil_pos, face="UP",
   hit=...))`; `handle.ack.accepted` only means admitted. `handle.wait()` returns
   `SUCCEEDED`/`FAILED`. A `SUCCEEDED` result is not proof that a seed was
   planted: the client re-reads `observe_block(soil_pos)` and `inventory()`.
6. If the selected item is a tool and the operation is mining,
   `mine_block(BlockPos(...))` has the same acceptance/result/side-effect
   distinction. Drops are not auto-collected.
7. If the robot is unloaded or the target chunk is unloaded, the action fails
   or the observation reports `UNLOADED`; neither is converted to “air” or
   “success”.
8. If the client waits past its deadline, `WaitTimeout` means “still unknown”,
   not “failed”; the action may still complete later. The client can cancel and
   wait, or re-read state.

What the contract cannot express in v1: tool durability/components, inventory
transfer into a container, pickup of drops, and a guaranteed position hold.
Those are explicit gaps, not implied by this scenario.

### 11.2 Scenario: three-dimensional curve

The robot follows a continuous 3D curve, adjusting control from its own state.

```python
while running:
    s = robot.state()
    target_v = curve_velocity(s.position, s.server_tick)
    robot.set_velocity(target_v)
    robot.set_orientation(yaw_for(target_v), pitch_for(target_v))
    # no action submit; no FIFO; no per-update event
```

- No new primitive is required. `State` supplies feedback; `Setpoints` supply
  continuous control.
- `set_velocity` is latest-wins and persists between calls. The loop can run
  slower than the server tick; the last target remains.
- `state()` and `set_velocity()` are separate direct requests. They are not
  serialized behind a mining action's FIFO. The synchronous SDK still performs
  one request at a time per thread; it does not add an unnecessary action-queue
  delay. A future async client MAY pipeline them, but that is not a new
  primitive.
- `set_orientation` is only usable if the capability is advertised. Without it,
  the curve can still be followed by velocity alone; the robot's facing is not
  a v1 guarantee.
- A zero velocity target is not a position hold. If the curve requires exact
  station-keeping under collisions, v1 cannot promise it; that is a controller
  gap, not a reason to add a `MoveTo` primitive.
- There is no discrete action in this scenario, so no action lifecycle, no
  cancellation semantics, and no event per setpoint update are needed.

Conclusion: both scenarios are expressible with Robot + State + Setpoints +
Actions + Events. The farming scenario exposes content/observation limitations;
the curve scenario exposes the missing orientation implementation and the lack
of a position-hold guarantee. Neither requires a new public primitive class.

## 12. Implementation mapping

Status meanings:

- **SUPPORTED**: the current implementation satisfies the defined contract,
  including the public path and a test or live evidence.
- **PARTIAL**: some implementation exists, but a required guarantee or public
  path is missing.
- **MISSING**: no implementation of the defined contract.
- **UNVERIFIED**: implementation may exist, but evidence is insufficient; do not
  treat it as a guarantee.

| Contract item | Status | Evidence / gap |
|---|---|---|
| Stable UUID and exact-ID lookup | **SUPPORTED** | `RobotId` is a UUID; SDK `client.robot()` matches exact UUID and raises `RobotNotFound` (`tools/sdk/swarmie/client.py:172-190`); `test_exact_robot_selection_never_defaults_to_first` (`tools/sdk/tests/test_client.py:69`). |
| Robot lifecycle and dimension metadata | **SUPPORTED** | `RobotDescriptor` has lifecycle/dimension/lastUpdatedTick (`contract/src/main/java/dev/swarmie/contract/RobotDescriptor.java:6-12`); registry load/unload/remove (`src/main/java/dev/swarmie/adapter/registry/RobotRegistry.java`); lifecycle tests in `RobotRegistryTest.java` and SDK `test_robot_info_is_structured`. |
| Robot live position/velocity/orientation | **PARTIAL** | Registry position is refreshed at END tick (`AdapterSession.java:135-150`), but there is no public state query, and no velocity/yaw/pitch in `RobotDescriptor`. `SwarmieEntity.getVelocity()` exists (`SwarmieEntity.java:159-162`) but is not exposed. |
| Capability discovery and version | **PARTIAL** | `RobotDescriptor.supportedCommands` and global `ContractVersion` exist (`RobotDescriptor.java:6-12`, `ContractVersion.java:4-5`); `AdapterSession` advertises all four `CommandType`s (`AdapterSession.java:59`). No per-robot capability version, no state/setpoint capability names. |
| `state.self` | **MISSING** | No Adapter/JSONL query; only registry position and internal entity getters. |
| `state.inventory` | **MISSING** | `SwarmieInventory` has 9 slots and selected slot (`SwarmieInventory.java:17-23,100-114`), but it is only exposed by debug `swarmie inventory show`, not Adapter/JSONL. |
| `state.block` | **MISSING** | `WorldQuery.queryBlock` and `BlockQueryResult` exist (`WorldQuery.java`, `DefaultWorldQuery.java:31-56`); no Adapter/JSONL operation. |
| `state.region` | **MISSING** | `queryRegion` exists with 4096 cap and `complete`/`unavailable` (`DefaultWorldQuery.java:59-93`); no public operation. |
| `state.entities` | **MISSING** | `queryEntities` exists with 256/65536 limits and `complete`/`truncated` (`DefaultWorldQuery.java:95-148`); no public operation. |
| `state.sensor` | **MISSING** | `LocalSensor`/`SensorObservation` and `DefaultLocalSensor` exist (`DefaultLocalSensor.java:60-153`); no public operation; line-of-sight returns `UNSUPPORTED` (`DefaultLocalSensor.java:56-61`). |
| Observation completeness/status model | **PARTIAL** | Internal `QueryStatus`, `RegionQueryResult.complete`, `EntityQueryResult.complete/truncated`, `SensorObservation.incomplete/truncated` exist; not exposed. |
| Continuous velocity setpoint | **MISSING** | Only `TIMED_FLIGHT` queued action (`SwarmieRuntime.java:73-88`, `TimedFlightCommand.java`) and debug `swarmie velocity`; no Adapter setpoint op. |
| Orientation setpoint | **MISSING** | No controller or transport; entity yaw/pitch are inherited but never controlled. |
| Latest-wins/revision/clear semantics | **MISSING** | No setpoint state or revision; `STOP` is a queued action (`StopCommand.java`) and `TIMED_FLIGHT` is FIFO. |
| `mine_block` | **SUPPORTED** (with limits) | `CommandSpec.MineBlock` (`CommandSpec.java:36-46`), `MineBlockCommand`, `InteractionActor.mineBlock` (`InteractionActor.java:34-50`), validation (`InteractionActor.java:92-125`), `InteractionRuntimeTest` mine tests, live matrix in `docs/adapter-command-specs.md`. Native return is not independent side-effect confirmation. |
| `use_item_on_block` | **SUPPORTED** (with limits) | `CommandSpec.UseItemOnBlock` (`CommandSpec.java:49-59`), `UseItemOnBlockCommand`, `InteractionActor.useItemOn` (`InteractionActor.java:52-90`), tests and live matrix. Container GUI is not opened; chest success may not mean content changed (`docs/adapter-command-specs.md`). |
| `attack_entity` | **MISSING** | No contract spec, runtime command or interaction method. |
| `use_item` (air) | **MISSING** | No contract spec; only block-targeted use. |
| `place_block` | **PARTIAL** | Placement can occur through `USE_ITEM_ON_BLOCK`; no dedicated placement semantics/target. |
| `pickup_item` | **MISSING** | Drops remain in world; no pickup command (`docs/adapter-command-specs.md`). |
| `transfer_item` | **MISSING** | No transfer action; Fabric storage APIs exist in the audit only. |
| Action accepted/running/terminal/cancel | **SUPPORTED** | `SubmitAck`, `CommandState`, `CancelAck`, runtime transitions (`SwarmieRuntime.java:233-361`), SDK handle tests (`tools/sdk/tests/test_client.py:86-241`). |
| Batch action submission | **PARTIAL** | Contract `BatchSubmission`/`BatchSubmitAck` and `AdapterSession.submitBatch` exist (`AdapterSession.java:246`), but the JSONL transport and SDK expose single submit only. |
| Action timeout | **PARTIAL** | Client `WaitTimeout` exists; server has no general action watchdog; `ErrorCode.TIMEOUT` is defined but unused (`docs/adapter-command-specs.md`). |
| Side-effect confirmation | **MISSING** | `CommandResult` has only state/error/message (`CommandResult.java:3-10`); no post-action observation or effect evidence. Native `destroyBlock` success is not a world snapshot. |
| Resource conflict semantics | **MISSING** | Per-robot FIFO serializes actions (`SwarmieRuntime.java:25,233,318`); no resource model or `RESOURCE_CONFLICT`. |
| `COMMAND_*` / `ROBOT_*` events | **SUPPORTED** | `RobotEventType` and `RobotEvent`; listener emits accepted/started/terminal (`AdapterSession.java:498-575`); runtime event tests. |
| Event sequence/epoch/missed | **SUPPORTED** | `RobotEvent` comment/fields (`RobotEvent.java:3-16`), `EventBatch` (`EventBatch.java:5-16`), event store ring capacity 1024 and `missed` logic (`AdapterEventStore.java:17,70-91`); `AdapterEventStoreTest`, SDK gap tests. |
| Reconnect continuity | **SUPPORTED** (bounded) | SDK reconnect/replay and epoch invalidation tests (`test_client.py:262-306`); no cross-epoch action recovery, and events older than the ring are lost. |
| Event loss semantics | **SUPPORTED** | `missed=true` and `oldestAvailableSequence`; SDK raises `EventGapError` (`tools/sdk/swarmie/errors.py`), tests `test_gap_*`. |
| Direct response vs event separation | **SUPPORTED** | `robots`/`ack`/`cancel_ack` direct; command events async; SDK `test_ack_is_not_completion`. |
| Public JSONL `observe`/`setpoint` ops | **MISSING** | Current ops are `robots`, `events`, `submit`, `cancel`, `ping` (`docs/local-transport.md`; `TransportCodec.java`). |
| Capability-gated unsupported errors | **MISSING** | `UNSUPPORTED_COMMAND` exists (`ErrorCode.java:21`), but no state/setpoint capability checks; no `UNSUPPORTED_CAPABILITY`. |
| Error layering (request/terminal/timeout) | **PARTIAL** | SDK separates `CommandRejected`, terminal `CommandResult`, `WaitTimeout`; no public target/resource/capability codes (see §9). |

The table is intentionally conservative. “Internal implementation exists” is
not upgraded to `SUPPORTED` when the public contract path is missing. A
`SUPPORTED` action lifecycle means the current `SubmitAck`/`CommandState`/
`CommandResult`/`CommandHandle` path implements the same semantics; it does not
mean the proposed `ActionAck`/`ActionResult`/`ActionHandle` classes exist.

## 13. Open decisions and required implementation work

These must be resolved before v1 is declared implemented:

1. **Orientation in v1.** Is orientation a required v1 setpoint or an optional
   capability? The design defines its semantics, but it is MISSING. If deferred,
   remove it from v1 `setpoints` and document the curve scenario's facing
   limitation.
2. **Velocity clamping vs rejection.** This design proposes clamping with
   `clamped=true`; confirm that or choose rejection. The choice must be frozen
   before implementation, because it changes the ack contract.
3. **Position hold / hover.** The design intentionally does not promise position
   hold. If farming or inspection needs exact station-keeping, a new setpoint
   channel/controller is required.
4. **Parallelism.** The v1 design keeps the action FIFO and
   `parallel_actions=false`. Confirm that a future parallel scheduler is out of
   scope and that `RESOURCE_CONFLICT` is reserved rather than implemented now.
5. **Action set.** Confirm the v1 required set is only `mine_block` and
   `use_item_on_block`. Attack, air item use, pickup and transfer remain
   catalogue entries with capability=false.
6. **Placement semantics.** Decide whether `use_item_on_block` is sufficient
   for v1 placement or whether a dedicated `place_block` target/result is
   required. The current implementation only provides the former.
7. **Item components.** Decide whether durability/enchantments/components are
   required for farming. The current contract only exposes item ID/count.
8. **Side-effect evidence.** Decide whether v1 needs a structured
   `EffectEvidence` payload or whether post-action State observation is the only
   confirmation mechanism. This design chooses the latter.
9. **Capability version format.** The design uses a string `"1"` and stable
   names. Confirm whether a semver-like string or integer is preferred, and
   whether capability version is per robot or global.
10. **State query batching/caching.** The design has one observation per
    request. Decide whether region+entities or self+inventory need one combined
    request for latency; do not add a framework without a measured need.
11. **Async client.** The synchronous SDK is sufficient for the scenarios but
    serializes state/read/control calls. Decide whether a future async client is
    needed; it is a client API change, not a new primitive.
12. **Error code names.** `UNSUPPORTED_CAPABILITY`, `RESOURCE_CONFLICT`,
    `TARGET_INVALID`, `TARGET_UNREACHABLE` are proposed strings; freeze them
    before the wire protocol is versioned.
13. **Wire version.** Decide how the JSONL `hello` negotiates API v1 separately
    from `ContractVersion 0.1`; do not silently reuse the old version string.
14. **Batch/stream support.** Contract has `BatchSubmission`, but the transport
    and SDK expose only single submit; decide whether v1 needs batch for farming
    sequences. This design says no.
15. **Capability advertisement timing.** A capability MUST only be advertised
    when its public path exists. Internal `WorldQuery`/`LocalSensor` do not
    justify advertising `state.block`/`state.sensor` before the transport is
    implemented.

## 14. Completion checklist

- Five groups answer distinct questions; Robot/State and Actions/ActionHandle
  boundaries are explicit.
- Continuous setpoints are not in the action FIFO; acceptance, achievement,
  completion, failure, cancellation and timeout are distinct.
- Events state epoch/sequence/gap/reconnect/loss semantics and associate action
  results by correlation.
- Both scenarios are expressible without new primitives; limitations are named.
- Every implementation claim is separated from the proposed contract and has a
  code/test pointer.
- The design changes no production behavior.

## 15. References

- Contract: `contract/src/main/java/dev/swarmie/contract/`
- Runtime: `src/main/java/dev/swarmie/runtime/`
- Entity/flight: `src/main/java/dev/swarmie/SwarmieEntity.java`,
  `src/main/java/dev/swarmie/SwarmieFlightController.java`
- Interaction: `src/main/java/dev/swarmie/interaction/`
- Query/sensor: `src/main/java/dev/swarmie/query/`,
  `src/main/java/dev/swarmie/sensor/`
- Adapter/JSONL: `src/main/java/dev/swarmie/adapter/`
- SDK: `tools/sdk/swarmie/`
- Existing behavior notes: `docs/adapter-command-specs.md`,
  `docs/adapter-contract.md`, `docs/adapter-registry.md`,
  `docs/local-transport.md`, `docs/python-sdk.md`
