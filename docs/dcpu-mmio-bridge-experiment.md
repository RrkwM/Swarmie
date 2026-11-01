# DCPU-16 / MMIO Entity Bridge Spike

> Status: **architecture spike / limited subset experiment**, not a complete DCPU
> emulator and not a public API commitment.
>
> Branch: `main` after merging `feature/api-v1-state-motion` and
> `experiment/dcpu-mmio-bridge`
> Date: 2026-11-02

## 1. Question

Can a guest program running inside a Swarmie robot emit one language-neutral
robot intent through MMIO, have the existing server-authoritative Runtime
execute it, and receive completion/fault in MMIO plus a guest interrupt?

The answer for this narrow fixture is **yes**. The experiment does not claim
that DCPU replaces JSONL/Python, does not add a network protocol, and does not
implement inventory, interaction, entity or sensor peripherals.

## 2. Architecture

```text
DCPU guest program
  (SET / IAS / RFI, 16-bit words)
          │
          │ MMIO reads/writes
          ▼
BasicMemoryBus
  ├─ 0x9000..0x9006  MotionDevice  ── MotionPort (pure Java)
  └─ 0x9100..0x9104  ClockDevice
          │
          ▼
RuntimeMotionPort  (Minecraft bridge, no duplicated world behavior)
          │
          ▼
SwarmieRuntime.submitTimedFlight / status / stop
          │
          ▼
SwarmieEntity.tick / native move / collision / server tick
          │
          ▼
MMIO status + one guest interrupt
```

The pure-Java DCPU package (`dev.swarmie.dcpu`) has no `net.minecraft.*` or
Fabric imports. Only `dev.swarmie.dcpu.bridge` touches the Entity/Runtime.

## 3. DCPU variant and supported subset

The implementation follows **DCPU-16 version 1.7** word/operand/interrupt
conventions, but only a deliberately small subset is implemented:

| Instruction | Opcode | Status |
|---|---:|---|
| `SET b, a` | basic `0x01` | supported |
| `IAS a` | special `0x0a` | supported |
| `RFI` | special `0x0b` | supported |
| everything else | — | explicit `FAULTED` (`0xfffe` for unsupported basic opcode, `0xfffd` for unsupported special) |

Supported operand modes: registers `A..J`, `[register]`, `[register + next
word]`, `SP`, `PC`, `EX`, `[next word]`, next-word literal, and the `0x20..0x3f`
literal source mode. Stack push/pop operand modes are not implemented. Literal
destinations fail silently per 1.7.

Machine state:

- 65,536 16-bit words of RAM, MMIO windows overlaid;
- registers A/B/C/X/Y/Z/I/J, PC, SP, EX, IA;
- SP starts at `0xffff`;
- interrupt queue (bounded at 256; overflow faults);
- `RUNNING`, `HALTED`, `FAULTED`;
- `run(cycleBudget)` executes at most the budget and returns cycles used, state,
  PC and fault code. One fetched instruction costs one cycle; interrupt
  dispatch also costs one cycle. This is an **instruction-budget subset**, not
  a complete hardware cycle-accurate timing model.

Unsupported opcodes do not silently no-op; they halt execution with `FAULTED`.

## 4. MMIO v0

### Clock/status — read-only

| Address | Meaning |
|---:|---|
| `0x9100` | server tick, low 16 bits |
| `0x9101` | server tick, high 16 bits |
| `0x9102` | VM status: 0 running, 1 halted, 2 faulted |
| `0x9103` | pending guest interrupt count |
| `0x9104` | capability/version word = 1 |

### Motion experiment

| Address | Meaning |
|---:|---|
| `0x9000` | CMD: 1 = timed flight, 2 = stop, 3 = acknowledge terminal |
| `0x9001` | STAT: 0 READY, 1 BUSY, 2 SUCCEEDED, 3 FAILED, 4 FAULTED |
| `0x9002` | ERR: 0 none, 1 invalid command, 2 invalid argument, 3 busy, 4 terminal not acknowledged, 5 Runtime rejected, 6 Runtime fault |
| `0x9003` | duration ticks, 1..6000 |
| `0x9004` | vx in signed thousandths of a block/tick |
| `0x9005` | vy in signed thousandths of a block/tick |
| `0x9006` | vz in signed thousandths of a block/tick |

`|vx|,|vy|,|vz| <= 350` (0.35 blocks/tick). Only one command may be BUSY.
Terminal status is latched; `CMD=3` acknowledges it and returns READY. One
interrupt is queued per terminal/fault transition; it is not duplicated on
polling.

The first command is a bounded timed flight, deliberately not a complete
Turtle-style movement vocabulary. A native collision is **not** mapped to a
specific hardware fault in this spike; collision behavior remains the existing
Runtime/entity behavior.

## 5. Internal seam and control authority

`MotionPort` is a pure-Java numeric interface (`submitTimedFlight`,
`status`, `stop`). `RuntimeMotionPort` projects MMIO values into the existing
Contract `CommandSpec.TimedFlight` and calls `SwarmieRuntime`; there is no
socket, no loopback JSONL, no duplicate movement implementation.

The experiment claims a **guest motion owner** in `AdapterSession` before
attachment. While the guest owns motion:

- JSONL `set_velocity`/`clear_velocity` is rejected with `MOTION_CHANNEL_BUSY`;
- JSONL `TIMED_FLIGHT`/`STOP` is rejected with `MOTION_CHANNEL_BUSY`;
- hand actions are not the motion channel and are not claimed by this spike.

The guest claim checks that no velocity setpoint or legacy motion is already
active. On entity unload/removal/cleanup, the controller stops the Runtime and
releases the claim.

## 6. Entity activation and gate

Activation requires **all** of:

1. `FabricLoader.getInstance().isDevelopmentEnvironment()` is true;
2. `config/swarmie-dcpu-experiment.json` exists with `enabled=true`;
3. the robot is spawned by the existing test-only file harness; there is no
   Brigadier command or JSONL operation.

The harness itself now also enforces the development environment. The DCPU
experiment does not expose a UUID control path and does not attach to ordinary
loaded robots.

The VM runs only from `SwarmieEntity.tick()` on the server side, with a fixed
128-cycle budget per entity tick. It does not tick unloaded robots or force
chunk loading.

## 7. Fixture program

`DcpuFixtureProgram` is a hand-assembled image, not an assembler:

1. set IA to the handler via `IAS`;
2. write duration 20 and vx=50 to MMIO;
3. write CMD=1;
4. poll STAT and clock/VM status into RAM;
5. handler reads STAT, acknowledges via CMD=3, writes `0x1234` to RAM
   `0x1004`, and executes `RFI`.

Guest RAM markers:

| Address | Meaning |
|---:|---|
| `0x1000` | last polled motion status |
| `0x1001` | server tick low |
| `0x1002` | VM status |
| `0x1003` | terminal motion status |
| `0x1004` | handler marker (`0x1234`) |

## 8. Evidence

### Pure Java and integration tests

- `Dcpu16Test`: word/register/memory, SET operand modes, exact cycle budget,
  unsupported-opcode fault, IAS/RFI interrupt/PC restoration, literal/stack
  boundaries.
- `MotionDeviceTest`: BUSY/terminal latch, one interrupt, acknowledgement,
  invalid argument fault, synthetic Runtime failure, stop/invalid command.
- `DcpuFixtureProgramTest`: hand-assembled fixture writes MMIO, polls clock,
  receives terminal interrupt, handler marker, gate truth table.
- `DcpuRuntimeBridgeTest`: guest MMIO command reaches a real `SwarmieRuntime`
  submission, the Runtime transition supplies terminal status, and the device
  handler completes.
- `ExperimentGate`: development + config are both required.

### Real integrated server

Final run, epoch `7279924691838937940`, robot
`02c93e2c-270e-4d0d-a775-6af2cbe4d1fb`:

```text
spawn: position (-24.5, 72.0, 1.5), serverTick 289
attach: budget=128, program=0

serverTick 290:
  cycles=128, vm=RUNNING, pc=17,
  motionStatus=BUSY, commandId=1, interrupts=0,
  ram1000=1 (polled BUSY)

serverTick 314:
  DCPU terminal: motionStatus=2 (SUCCEEDED), error=0,
                 commandId=1, interrupts=1
  after CPU: vm=RUNNING, pc=27, motionStatus=READY (ack),
             ram1003=2, ram1004=0x1234

robot state after: (-23.4, 72.0, 1.5), velocity (0,0,0)
remote setpoint: MOTION_CHANNEL_BUSY
remote TIMED_FLIGHT: MOTION_CHANNEL_BUSY
```

The Entity moved 1.1 blocks from the guest command, stopped with zero velocity,
and the guest handler observed terminal status 2 and acknowledged it. The
terminal interrupt was delivered and handled exactly once.

Cleanup and disabled-gate evidence:

```text
cleanup: DCPU experiment stopped
         vm=HALTED, motionStatus=READY, commandId=-1, interrupts=0
         ram1003=2, ram1004=0x1234
disabled config startup:
         development=true, configured=false
         "DCPU experiment disabled (development=true, configured=false)"
```

The disabled-test confirms the config file alone is not sufficient; the real
Fabric development-environment gate and explicit config are both required.

### Evidence classification

| Layer | Evidence |
|---|---|
| Pure Java | DCPU core, MMIO, fault/cycle/interrupt unit tests |
| Runtime integration | `DcpuRuntimeBridgeTest` against existing `SwarmieRuntime` |
| Real Minecraft | integrated-server epoch/robot run above; actual Entity state, movement, stop, ownership rejection and cleanup |
| Unverified | native-collision-to-MMIO fault mapping, unloading during active guest command, multiple guest instances, persistence |

Test totals at this commit: main **75**, contract **12**, Python SDK **66**, audit
**33**; all passed. These are separate from the real-server evidence.

## 9. Why this is only an experiment

No assembler, disassembler, cartridge, NBT RAM persistence, UI, network/NIC,
inventory/interaction peripheral, hardware enumeration, or multi-robot
communication is implemented. The CPU program is a fixed hand-assembled image.
The DCPU is not a replacement for JSONL/Python; those remain developer tooling.

Before persistent programs or general peripherals:

- define cartridge/program persistence and a stable load address;
- define device IDs and hardware enumeration (`HWN/HWQ/HWI`) if required;
- define lifecycle semantics for unload/reload and active guest commands;
- define collision/range fault mapping or explicitly keep it non-hardware;
- define a control-authority protocol among guest, JSONL and hand actions;
- add real fault-path and repeated-command scenarios;
- decide whether an assembler/toolchain belongs in the product.

The next architecture step is specified separately: the PCI-inspired
[SDB v0 device bus](device-bus-v0.md) defines device identity, configuration,
BARs, capabilities, IRQ and lifecycle. It is a draft; no device-bus code or
public API exists yet.

## 10. Reproduction

Fixture config (default-off; development environment required):

```text
run/config/swarmie-dcpu-experiment.json  {"enabled":true}
run/config/swarmie-lifecycle-test.json   {"enabled":true}
run/config/swarmie-transport.json        JSONL config
```

Then use the test harness `spawn`; the spawned test robot is attached. The
public SDK can read its state and must receive `MOTION_CHANNEL_BUSY` for a
remote setpoint or legacy motion command. Cleanup discards the test robot,
releases the guest claim and stops the Runtime. Remove all test configs after
the run.
