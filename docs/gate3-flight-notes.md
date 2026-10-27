# Gate 3 — Flight control implementation notes

> Scope: source implementation only. Gate 1 (Loom build environment) remains
> blocked, so **no in-game runtime validation has been performed**.

## 1. Parameters

| Parameter | Value | Unit |
|---|---|---|
| `MAX_SPEED` | `0.35` | blocks/tick |
| `MAX_ACCELERATION` | `0.04` | blocks/tick² |
| `IDLE_DAMPING` | `0.90` | per tick |

Defined in `SwarmieFlightController`.

## 2. Control API

`SwarmieFlightController`:

- `setDesiredVelocity(Vec3)` — rejects non-finite input; clamps the requested
  vector to `MAX_SPEED`.
- `stop()` — sets desired velocity to zero; actual deceleration happens over
  subsequent ticks.
- `getDesiredVelocity()`.
- `update(Vec3 currentVelocity)` — returns the next velocity:
  - while a target exists: `delta = target - current`, clamped to
    `MAX_ACCELERATION`;
  - when stopping: `delta = current * 0.90 - current`, also clamped to
    `MAX_ACCELERATION` so damping never exceeds the acceleration limit;
  - non-finite current velocity falls back to zero.

`SwarmieEntity`:

- `setDesiredVelocity(Vec3)`
- `stopFlight()`
- `getVelocity()` — returns the entity's native `deltaMovement`
- `getFlightController()` — for future Runtime/debug use

## 3. Tick integration

`SwarmieEntity.tick()`:

1. calls `super.tick()`;
2. returns immediately on `level().isClientSide()`;
3. reads current native velocity (`getDeltaMovement()`);
4. computes the next velocity with the controller;
5. `setDeltaMovement(next)`;
6. calls `move(MoverType.SELF, next)` **once**;
7. compares requested vs. actual displacement per axis and zeroes velocity only
   on axes clipped by native collision.

Verified against the 26.3 client jar: `Entity.tick()` only invokes `baseTick()`,
so there is no second native movement call hidden in `super.tick()`.

- Server-authoritative: the client never runs the controller or calls `move`.
- Native collision: `MoverType.SELF` + `Entity.move`; no `noPhysics`, no
  `setPos`-based normal movement, no custom block collision engine.
- Axis handling: `moved = position() - before`; if
  `abs(moved.axis - requested.axis) > 1e-7`, that axis' velocity is set to zero.
  Unobstructed axes keep their requested velocity, allowing sliding.
- Collision box, registration, renderer and standard entity persistence are
  unchanged.

## 4. Persistence / synchronization

- No custom networking and no custom synced data.
- `deltaMovement` is the native velocity field; standard entity tracking sends
  position updates to clients.
- The **desired** velocity is intentionally transient and is not saved. After a
  reload, a robot with no new command decelerates from the persisted native
  motion using idle damping.

## 5. Debug commands

Registered through Fabric `CommandRegistrationCallback` in `SwarmieMod`.
All execution is server-side.

| Command | Effect |
|---|---|
| `/swarmie velocity <targets> <x> <y> <z>` | Sets desired velocity on matching `SwarmieEntity` instances; speed clamping is inside the entity API |
| `/swarmie stop <targets>` | Requests controlled deceleration |

- Permission: `Commands.LEVEL_GAMEMASTERS` (operator level 2).
- `<targets>` is an entity selector (`@e`, `@e[type=swarmie:swarmie]`, `@s`, etc.).
- Non-`SwarmieEntity` selections are skipped.
- Non-finite numeric input is rejected with a failure message.
- Success/failure reports the number of affected robots.
- Physics is not duplicated in the command handler.

## 6. Static verification

Command:

```text
javac @tools/provision-tmp/diagnostics/javac.args
```

- Classpath: 26.3 `minecraft-client.jar`, extracted `server-26.3.jar`, and local
  Fabric/dependency jars.
- **Exit code 0** for all common and client sources, including
  `SwarmieFlightController` and `SwarmieCommands`.

Controller numeric probe (`FlightProbe`, pure logic, no world):

```text
FIRST_STEP=0.04 expected=0.04
CRUISE=0.35 max=0.35 within=true
STOP_DELTA=0.034999999999999976 maxAccel=0.04 decelerated=true notZero=true
NAN_REJECTED=true
ZERO_UPDATE=0.0
```

## 7. Not verified / limitations

- Loom build, resource processing, packaging and in-game behavior remain blocked
  by the Gate 1 ZipFS issue.
- Actual `Entity.move` collision behavior on 26.3 was verified by API/bytecode
  inspection, not by running a world.
- The per-axis "requested vs actual" heuristic should be validated against
  slabs/stairs/edges once a runtime exists; `MoverType.SELF` on a plain `Entity`
  does not step up blocks.
- Desired velocity is not persisted; this is intentional for Gate 3.
- No client-side prediction; clients rely on vanilla position tracking and
  interpolation.

## 8. Runtime validation procedure (after Gate 1)

1. `/summon swarmie:swarmie`
2. `/swarmie velocity @e[type=swarmie:swarmie,limit=1] 0.35 0 0` — expect movement at up
   to 0.35 blocks/tick, starting with a 0.04 acceleration step.
3. `/swarmie stop @e[type=swarmie:swarmie]` — expect gradual deceleration, not an
   instant stop.
4. Fly into a solid wall and a ceiling; verify the blocked axis stops while the
   other axes still slide.
5. Verify server/client positions agree and no client-side flight simulation is
   needed.
6. Save/reload; verify identity/position persistence and that no new command is
   required for the entity to exist.
