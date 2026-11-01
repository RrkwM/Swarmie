# SDB v0 — Swarmie Device Bus

**Status:** draft ABI specification for a constrained experiment. Not implemented,
not a public API, and not PCI-compatible.

**Audience:** Swarmie runtime/DCPU implementers, Fabric device providers, and
player-driver authors.

**Normative language:** `MUST`, `MUST NOT`, `SHOULD`, and `MAY` describe the
first implementation target. “Guest” means DCPU code; “host” means the Swarmie
Entity/Runtime side.

---

## 1. Purpose

SDB is a PCI-inspired device bus for one robot:

```text
player program / external build
        ↓
DCPU instructions
        ↓
player driver
        ↓
SDB configuration + MMIO
        ↓
Swarmie host (Entity / Runtime / native Minecraft)
        ↓
bounded observation or action result
```

The bus must let a guest discover **what devices exist**, where their registers
are, what ABI they speak, and whether an operation is ready, busy, complete or
faulted. It must not tell the guest what a device’s readings mean.

The design rule is:

> Swarmie defines ports, timing, buffers, status and discovery.
> Device providers define device behavior.
> Player drivers define what device data means.

A crop sensor, an ore scanner, an energy meter and a topology mapper are all
just devices from the host’s point of view.

---

## 2. What is borrowed from PCI

| PCI concept | SDB v0 use |
|---|---|
| Root complex | `DeviceHost`, owned by the Swarmie Entity |
| Bus / device / function | One logical bus, fixed slots, one function in v0 |
| Configuration space | Small host-owned configuration header |
| Vendor/device ID | 16-bit numeric IDs resolved through a provider registry |
| BAR | Host-assigned MMIO window with base, length and flags |
| Capability list | Optional IRQ, buffer and reset capabilities |
| Command/status | Host-owned enable/reset; guest-readable ready/busy/fault |
| Enumeration | Scan fixed slots; absent slot reads `0xFFFF` |
| Interrupt pin/message | Dedicated IRQ status/mask/ack per function |
| Error status | Latched fault code separate from normal data |

The useful idea is separation of concerns:

```text
identity ≠ operation
configuration ≠ device registers
resource allocation ≠ device implementation
observation ≠ interpretation
```

### 2.1 What is not borrowed

SDB v0 MUST NOT implement:

- a 4 KiB PCI configuration space;
- 64-bit BARs;
- I/O BARs or x86 `0xCF8`/`0xCFC` configuration ports;
- PCI bridges, bus hierarchies or recursive enumeration;
- bus-master DMA;
- PCIe transactions, link training or topology;
- hot-plug;
- MSI-X;
- AER;
- power-management states;
- option ROMs;
- guest-writable BARs or configuration space;
- PCI class codes with semantic device categories.

In particular, there is no `Sensor` class code. A device is identified by
`vendor_id`, `device_id`, ABI and capabilities. The player driver supplies the
meaning.

---

## 3. Scope of v0

v0 is intentionally small:

- one bus, `bus 0`;
- up to 8 slots;
- one function per device;
- fixed deterministic slot addresses;
- no relocation after attach;
- no hot-plug;
- no DMA;
- no bridges;
- no multi-robot bus;
- no network;
- no persistent device configuration;
- no public Python/JSONL device API.

The first implementation is a test-gated experiment behind the existing DCPU
experiment gate. It must not become a player-facing stable ABI before the
questions in §22 are resolved.

---

## 4. Topology and addressing

```text
bus 0
  slot 0   motion (built-in)
  slot 1   clock/lifecycle (built-in)
  slot 2   block probe (first experiment)
  slot 3   counter mock (third-party parity test)
  slot 4–7 extension slots
  function 0 only
```

The logical address is:

```text
address = (slot & 0x07) | (function << 3)
```

`function` is reserved and MUST be `0` in v0. A provider that needs multiple
independent interfaces should expose one function with a capability/register
split, not multiple functions yet.

There are no bridges. All devices are on the root bus.

### 4.1 Slot ownership

The host assigns slots at attach time. v0 uses deterministic built-in slots:

| Slot | Function | BAR0 base | BAR0 length (words) |
|---|---|---:|---:|
| 0 | `swarmie:motion` | `0x9000` | 7 |
| 1 | `swarmie:clock` | `0x9100` | 5 |
| 2 | `swarmie:block_probe` | `0x9200` | 32 |
| 3 | `example:counter` | `0x9300` | 8 |
| 4–7 | extension | `0x9000 + slot * 0x100` | provider-declared |

The built-in motion and clock windows keep their existing addresses so the
current DCPU fixture remains valid.

---

## 5. Memory map

The 64K word address space is divided as follows for v0:

```text
0x0000–0x7FFF   guest RAM
0x8000–0x8007   SDB control, configuration and IRQ control
0x8008–0x8FFF   reserved (MUST NOT silently fall through to RAM)
0x9000–0x90FF   slot 0 BAR (motion)
0x9100–0x91FF   slot 1 BAR (clock)
0x9200–0x92FF   slot 2 BAR (block probe)
0x9300–0x93FF   slot 3 BAR (counter mock)
0x9400–0x97FF   slots 4–7 BARs
0x9800–0xFFFF   RAM unless reserved by a future provider
```

All addresses and lengths are in **16-bit words**, not bytes.

An access to a reserved window that has no attached function MUST NOT fall
through to RAM or to another device. v0 must latch a bus/config fault and expose
it through the bus status (see §19). The final CPU-visible behavior is an open
question because the current DCPU subset has no bus-fault instruction.

---

## 6. Configuration access

PCI uses I/O ports and configuration cycles. SDB uses a tiny MMIO index/data
interface because the DCPU has no I/O address space.

### 6.1 Bus registers

```text
0x8000  BUS_SELECT     write: slot in bits 0..2, function in bits 3..7
0x8001  CONFIG_ADDR    write: configuration word offset (0..31)
0x8002  CONFIG_DATA    read: selected configuration word
0x8003  BUS_STATUS     read: slot count and bus state
0x8004  IRQ_PENDING    read: per-vector pending bitmap
0x8005  IRQ_ENABLE     read/write: 1 = interrupt enabled
0x8006  IRQ_ACK        write-one-to-clear pending bits
0x8007  reserved       read 0
```

`BUS_SELECT` and `CONFIG_ADDR` are writable by the guest. They only select a
configuration word; they do not access device registers.

`CONFIG_DATA` is **read-only to the guest**. A guest write:

- MUST NOT modify configuration;
- MUST NOT modify BARs or IRQ routing;
- SHOULD latch `CONFIG_ERROR` in `BUS_STATUS`.

`IRQ_ENABLE` and `IRQ_ACK` are the only writable IRQ-control registers. They
change interrupt delivery/status only; they never change configuration or BARs.

`BUS_STATUS` layout:

```text
bits 0..7   slot count (1..8)
bit  8      CONFIG_READY
bit  9      CONFIG_ERROR
bits 10..15 reserved (read 0)
```

### 6.2 Configuration reads

For a valid slot and offset `0..31`, `CONFIG_DATA` returns the selected word.
For an absent slot, `VENDOR_ID` reads `0xFFFF` and all other words read `0`.
For an out-of-range offset, `CONFIG_DATA` reads `0xFFFF`; the host SHOULD set
`CONFIG_ERROR`.

Enumeration must have no side effects and must not load chunks or touch the
world.

---

## 7. Configuration header

v0 uses a 32-word (64-byte) header. Reserved words read `0` and ignore writes.

| Word | Field | Meaning |
|---:|---|---|
| `0x00` | `VENDOR_ID` | numeric vendor ID; `0xFFFF` means absent |
| `0x01` | `DEVICE_ID` | numeric device ID within vendor namespace |
| `0x02` | `ABI` | high byte major, low byte minor |
| `0x03` | `STATUS` | device state bits |
| `0x04` | `COMMAND` | host-only enable/reset |
| `0x05` | `CAPABILITY_PTR` | word offset of capability list; `0` = none |
| `0x06` | `HEADER` | low byte header type, high byte function count |
| `0x07` | reserved | must read 0 |
| `0x08` | `BAR0_BASE` | first MMIO base address (word address) |
| `0x09` | `BAR0_LENGTH` | length in words |
| `0x0A` | `BAR0_FLAGS` | memory/read-only/async/buffered/entity-scoped/IRQ_CAPABLE |
| `0x0B` | `IRQ_VECTOR` | dedicated interrupt vector; `0` = none |
| `0x0C` | `IRQ_STATUS` | pending bit for this function (read-only) |
| `0x0D` | reserved | must read 0 |
| `0x0E` | reserved | must read 0 |
| `0x0F` | `FAULT` | latched generic fault code |
| `0x10–0x1F` | reserved | must read 0 |

No 64-bit fields exist in v0. Values wider than 16 bits are split into low/high
words by the relevant capability or BAR definition.

`HEADER` is `0x0001` in v0: low byte header type `0` (single-function device)
and high byte function count `1`. Other values are reserved.

### 7.1 Identity

`VENDOR_ID` and `DEVICE_ID` are 16-bit numeric IDs. They are assigned by the
host-side provider registry from stable namespaced names, for example:

```text
swarmie:motion        -> vendor 0x0001, device 0x0001
swarmie:clock         -> vendor 0x0001, device 0x0002
swarmie:block_probe   -> vendor 0x0001, device 0x0003
example:counter       -> vendor 0x0002, device 0x0001
```

The namespaced string is provisioning/documentation metadata. It is not packed
into the 16-bit configuration header in v0. A future `CAP_NAME` may expose a
bounded read-only string descriptor.

Numeric IDs MUST be stable for a given provider and ABI. Hashing a namespaced
string into an ID without collision handling is not acceptable.

### 7.2 Status

`STATUS` is read-only to the guest:

```text
bit 0  READY
bit 1  BUSY
bit 2  FAULT
bit 3  IRQ_PENDING
bit 4  CONFIG_ERROR
bit 5  UNAVAILABLE
bits 6..15 reserved
```

Rules:

- `READY` means the function can accept a new request.
- `BUSY` means exactly one request is outstanding.
- `FAULT` is latched until host reset or explicit device ack.
- `IRQ_PENDING` mirrors `IRQ_STATUS != 0`.
- `CONFIG_ERROR` means the host or guest made an invalid configuration access.
- `UNAVAILABLE` means the device is detached/unavailable; the guest must not
  interpret its registers as valid data.

`BUSY` and `COMPLETE` are not combined into one boolean. A transfer can be
complete but invalid or stale.

### 7.3 Command

`COMMAND` is **host-only** in v0. Guest writes are ignored and latch
`CONFIG_ERROR`.

```text
bit 0  ENABLE
bit 1  RESET
bits 2..15 reserved
```

There is no bus-master enable bit. SDB v0 has no DMA.

### 7.4 BAR0

`BAR0_BASE` and `BAR0_LENGTH` are read-only to the guest. The host assigns them
during attach after validating:

- base and length are non-zero for a present function;
- `base + length` does not overflow 16 bits;
- the window does not overlap any other BAR, the bus window or reserved space;
- `base` is aligned to at least 16 words, and preferably to `length`.

`BAR0_FLAGS`:

```text
bit 0  MEMORY (must be 1 in v0)
bit 1  READ_ONLY
bit 2  ASYNC
bit 3  BUFFERED
bit 4  ENTITY_SCOPED
bit 5  IRQ_CAPABLE
bits 6..15 reserved
```

There is no I/O BAR, no prefetchable BAR, no 64-bit BAR and no write-1-to-size
probing. The provider declares the required length; the host assigns the base.

v0 does not relocate a BAR after attach. A guest cannot change it.

### 7.5 Capabilities

`CAPABILITY_PTR` points to a bounded capability list in configuration space:

```text
word +0  cap_id
word +1  next_offset (0 terminates)
word +2.. data
```

The host MUST validate:

- all offsets are within `0..31`;
- no cycles;
- at most 8 capabilities;
- a capability's data length stays inside the header.

v0 capability IDs:

```text
1  CAP_IRQ      IRQ fields are valid (common fields in the header)
2  CAP_BUFFER   bounded result index/data window
3  CAP_RESET    device-specific reset register (optional)
```

Capability data layouts:

```text
CAP_IRQ:
  no data; IRQ_VECTOR and IRQ_STATUS are in the common header.

CAP_BUFFER:
  +0  INDEX_OFFSET   BAR word offset of the result index register
  +1  DATA_OFFSET    BAR word offset of the result data register
  +2  LENGTH         maximum words in the result window
  +3  COUNT_OFFSET   BAR word offset of the valid word count
  +4  0             terminator

CAP_RESET:
  +0  RESET_OFFSET   BAR word offset of the reset register
  +1  0             terminator
```

Offsets are relative to BAR0. A result window MUST be read-only to the guest;
the guest writes only the index register and reads data/count.

A capability describes access mechanics, not sensor semantics. There is no
`CAP_SENSOR`, `CAP_CROP` or `CAP_ORE`.

### 7.6 IRQ fields

v0 uses one dedicated IRQ line per function. No sharing and no MSI-X.

```text
IRQ_VECTOR   nonzero = vector assigned by host
IRQ_STATUS   pending bit for this function (read-only)
IRQ_PENDING  bus-wide pending bitmap (0x8004)
IRQ_ENABLE   bus-wide enable bitmap (0x8005, 1 = enabled)
IRQ_ACK      bus-wide write-one-to-clear bitmap (0x8006)
```

Rules:

- `IRQ_VECTOR` is assigned by the host and is normally `slot + 1`;
- `IRQ_ENABLE` resets to `0` on attach; the driver must enable the vectors it
  handles;
- a device sets at most one pending bit per completion/fault transition;
- repeated polling does not re-queue an interrupt;
- `IRQ_PENDING` is the bus-wide view; `IRQ_STATUS` is the per-function view;
- a pending interrupt is queued to the CPU only when its `IRQ_ENABLE` bit is 1;
- `IRQ_ACK` clears the bus/status pending bit but does **not** release a device
  terminal state; the guest must still write the device-specific ACK register
  (for example motion `CMD=3` or block-probe `CMD=2`);
- `IRQ_ACK` reads as `0`; `IRQ_PENDING` and `IRQ_STATUS` are read-only;
- detach clears pending bits and makes the function unavailable;
- a masked/disabled interrupt remains pending but does not queue to the CPU;
- no interrupt storm: coalesce repeated terminal events until ack.

### 7.7 Fault

`FAULT` is a latched generic code:

```text
0  none
1  invalid configuration access
2  BAR/window violation
3  unsupported command
4  invalid argument
5  busy
6  rejected by Swarmie permissions/lifecycle
7  host execution fault
8  detached/unavailable
9  truncated result
```

Device-specific error detail belongs in the BAR, not in the generic header.
A fault MUST NOT be silently converted into zero data or an empty result.

---

## 8. Enumeration

A guest driver discovers devices as follows:

```text
1. Read BUS_STATUS.
2. slot_count = BUS_STATUS & 0xFF.
3. For slot in 0..slot_count-1:
     BUS_SELECT = slot
     CONFIG_ADDR = VENDOR_ID
     vendor = CONFIG_DATA
     if vendor == 0xFFFF: continue
     read DEVICE_ID, ABI, STATUS, CAPABILITY_PTR, BAR0_*
     if device matches the driver: initialize and use it
```

Enumeration is deterministic and side-effect free. There is no hot-plug event
in v0; a device appears on attach and disappears on detach/unload.

A guest driver MUST NOT assume:

- that slots are contiguous;
- that a device's BAR is at a fixed address;
- that a provider's numeric ID has a particular meaning outside its declared
  ABI;
- that a missing device is an empty result.

---

## 9. Lifecycle

Host lifecycle:

```text
REGISTERED -> ATTACHED -> READY -> BUSY -> READY
                    \-> UNAVAILABLE
                    \-> FAULT
```

| State | Guest-visible behavior |
|---|---|
| `ATTACHED` | configuration may be readable; not yet ready |
| `READY` | command accepted; `STATUS.READY=1` |
| `BUSY` | one request outstanding; new request rejected with BUSY |
| `FAULT` | terminal status latched; IRQ queued once; ack/reset required |
| `DETACHED` | empty slot or `UNAVAILABLE`; no valid data |
| `UNAVAILABLE` | device exists in registry but cannot run |

Rules:

- attach happens when the robot becomes loaded;
- detach happens on unload/removal/cleanup;
- an in-flight request on detach becomes `UNAVAILABLE`/faulted, never silently
  successful;
- configuration and device state do not persist across unload/reload in v0;
- device providers are registered at server start, not hot-plugged;
- no device may retain a robot/world reference after detach.

The host must be able to stop a device even if the guest is in an infinite loop.

---

## 10. Device operation

### 10.1 Request/result protocol

A device BAR defines its own registers. The common pattern is:

```text
1. Guest writes arguments.
2. Guest writes CMD=START (or a device-specific command word).
3. Device validates; if invalid, terminal FAULT with error.
4. Device sets BUSY.
5. Host completes work on the server thread.
6. Device latches COMPLETE/FAULT, result words, flags and freshness.
7. Device queues one interrupt.
8. Guest polls or handles the interrupt.
9. Guest reads result and writes ACK.
10. Device clears terminal state and returns to READY.
```

v0 allows at most **one outstanding request per function**. A second request while
BUSY is rejected with error `BUSY`; it must not overwrite the pending request or
the previous result.

### 10.2 Result flags

Device BARs SHOULD expose:

```text
VALID       result represents a successful observation
STALE       result is from an older tick than the request/current target
TRUNCATED   result exceeded the bounded buffer
COVERAGE    what was actually observed
FRESHNESS   server tick when observed
```

`COMPLETE` alone does not mean `VALID`. A completed operation may be invalid,
stale or truncated.

### 10.3 No false absence

The bus must never turn:

```text
device unavailable
chunk unloaded
target out of bounds
result truncated
device fault
```

into:

```text
air
empty list
zero
no result
```

Every one of those conditions needs an explicit status/error that a player
driver can branch on.

---

## 11. Provider registration

A provider is server-side Java/Fabric code registered at startup:

```java
interface DeviceProvider {
    String namespaceId();
    int vendorId();
    int abiMajor();
    List<DeviceFunction> create(DeviceHost host);
}
```

The real SPI is not frozen; this is the intended shape.

Rules:

- a provider declares identity, ABI, BAR requirement(s), capabilities and
  resource cost;
- the host assigns slots and BARs; providers do not choose arbitrary addresses;
- built-in devices use the same descriptor/status/IRQ model;
- a provider cannot bypass the Entity, Runtime, FakePlayer, chunk, range,
  world-border or server-permission checks;
- a provider cannot execute Minecraft work off the server thread;
- provider code is trusted server code; v0 does not load untrusted device code;
- a failed provider attach must not corrupt existing devices or the DCPU.

A provider is not a `Sensor`. It is a device function factory.

The host SPI must keep the pure-Java DCPU package free of Minecraft/Fabric
imports. Adapters live in the bridge package.

---

## 12. Guest driver registration

There is no server-side registration of sensor meanings.

A player driver is part of the guest program/firmware:

```text
1. Enumerate devices.
2. Match vendor/device/ABI.
3. Read BAR0.
4. Initialize the device if required.
5. Submit requests.
6. Poll or handle IRQ.
7. Interpret raw results according to the player's own semantics.
```

For example, a driver may know:

```text
example:soil_probe@1 BAR+4 == moisture raw
0x0037 means 55%
```

The host does not know that mapping.

A provisioning manifest may declare a dependency:

```json
{"requires": ["example:soil_probe@1"]}
```

This is for installation/diagnostics. It must not be the runtime source of
sensor semantics.

---

## 13. Built-in devices

### 13.1 Motion

```text
ID:       swarmie:motion@1
Vendor:   0x0001, device 0x0001
BAR0:     0x9000, length 7
Flags:    MEMORY | ASYNC | IRQ_CAPABLE
Registers: existing motion device
```

The existing `0x9000..0x9006` layout remains:

```text
0x9000 CMD
0x9001 STAT
0x9002 ERR
0x9003 duration
0x9004 vx
0x9005 vy
0x9006 vz
```

This is the first built-in actuator function and also the model for terminal
status + one IRQ + ACK.

### 13.2 Clock/lifecycle

```text
ID:       swarmie:clock@1
Vendor:   0x0001, device 0x0002
BAR0:     0x9100, length 5
Flags:    MEMORY | READ_ONLY
Registers: existing clock device
```

### 13.3 Block probe

```text
ID:       swarmie:block_probe@1
Vendor:   0x0001, device 0x0003
ABI:      1.0
BAR0:     0x9200, length 32
Flags:    MEMORY | ASYNC | IRQ_CAPABLE | ENTITY_SCOPED
```

BAR layout:

| Offset | Register | Meaning |
|---:|---|---|
| `+0x00` | `CMD` | 1 = start probe, 2 = ack terminal |
| `+0x01` | `STATUS` | READY/BUSY/COMPLETE/FAULT |
| `+0x02` | `FLAGS` | VALID/STALE/TRUNCATED |
| `+0x03` | `ERR` | device-specific error |
| `+0x04` | `X_LO` | signed world X low word |
| `+0x05` | `X_HI` | signed world X high word |
| `+0x06` | `Y_LO` | signed world Y low word |
| `+0x07` | `Y_HI` | signed world Y high word |
| `+0x08` | `Z_LO` | signed world Z low word |
| `+0x09` | `Z_HI` | signed world Z high word |
| `+0x0A` | `BLOCK_STATE_LO` | raw block state ID low word |
| `+0x0B` | `BLOCK_STATE_HI` | raw block state ID high word |
| `+0x0C` | `COVERAGE` | 1 = one block observed; 0 = none |
| `+0x0D` | `FRESHNESS_LO` | server tick low word |
| `+0x0E` | `FRESHNESS_HI` | server tick high word |
| `+0x0F` | `REQUEST_ID` | guest correlation word |
| `+0x10` | `RESULT_INDEX` | reserved for buffered properties |
| `+0x11` | `RESULT_DATA` | reserved for buffered properties |
| `+0x12..` | reserved | read 0 |

Status:

```text
0 READY
1 BUSY
2 COMPLETE
3 FAULT
```

Flags:

```text
bit 0 VALID
bit 1 STALE
bit 2 TRUNCATED
```

Error:

```text
0 none
1 invalid target
2 out of bounds
3 unloaded chunk
4 busy
5 rejected by Swarmie
6 host execution fault
7 unsupported
```

The block state is a raw 32-bit numeric ID in v0. It does **not** encode item or
property semantics; a player driver maps it. Property buffers are reserved for
a later ABI.

The device must not force-load chunks. `UNLOADED`, `OUT_OF_BOUNDS`, invalid
targets and truncation remain explicit results; they are never air.

### 13.4 Counter mock

```text
ID:       example:counter@1
Vendor:   0x0002, device 0x0001
ABI:      1.0
BAR0:     0x9300, length 8
Flags:    MEMORY | ASYNC | IRQ_CAPABLE
```

Suggested registers:

```text
+0 CMD      1 = increment asynchronously, 2 = ack
+1 STATUS   READY/BUSY/COMPLETE/FAULT
+2 VALUE    current counter
+3 ERR      error
+4 FLAGS    VALID/STALE
```

This device proves that a third-party provider can use the same ABI without
adding world semantics. It is the parity test for built-in vs extension devices.

---

## 14. Permissions, resources and safety

Device providers are trusted server-side extensions, but their runtime behavior
is constrained:

- no direct Minecraft access from the pure DCPU core;
- all world work goes through the host/Entity/Runtime bridge;
- no chunk loading;
- no bypassing interaction range, world border, permissions or lifecycle;
- no DMA;
- no mutation unless the function is explicitly an actuator;
- one outstanding request per function;
- bounded result size;
- per-tick and per-request resource budgets;
- no unbounded retry or interrupt storm;
- detach must release world references and stop interrupts.

The first block probe is read-only. It does not expand Swarmie's world features.

---

## 15. Scheduling and ordering

Every robot CPU runs only during its owning Entity's server tick.

Proposed order:

```text
entity server tick
  -> service attached device completions
  -> run DCPU for at most N cycles
  -> run existing Swarmie Runtime work
  -> publish state/events
```

Consequences:

- a completion latched before the CPU run is visible to the guest in that tick;
- a request submitted by the guest is serviced on the server thread, not
  asynchronously from another thread;
- device work must not block the server tick indefinitely;
- an infinite guest loop consumes only its cycle budget;
- one device cannot starve another robot's CPU.

The exact ordering must be documented and tested; this section is the target,
not a claim that all phases already exist.

---

## 16. Bus errors

Current `BasicMemoryBus` falls back to RAM for unclaimed addresses. SDB MUST NOT
do that for reserved windows.

v0 requirements:

- overlapping BARs are rejected at attach;
- access inside a reserved but unmapped window latches a bus fault;
- access outside a function's BAR cannot read another device;
- an invalid configuration address sets `CONFIG_ERROR`;
- a fault is visible to the guest through status/fault, not silently ignored;
- the CPU-level response to a bus fault is an open question until the DCPU has
  a defined bus-fault model.

This is a deliberate hardening step over the current fixed-window bus.

---

## 17. Test matrix

### Unit

- empty slot reads `0xFFFF` and has no side effects;
- config offsets outside `0..31` fault;
- guest writes to `CONFIG_DATA`/`COMMAND`/BAR do not change host state;
- overlapping BARs are rejected;
- BAR base/length overflow is rejected;
- capability list bounds/cycles are rejected;
- unknown/unsupported commands fault;
- IRQ mask/ack/coalescing;
- detach clears pending IRQ and makes the function unavailable;
- reserved-window access does not fall through to RAM.

### DCPU

- enumerate a bus with built-in and mock devices;
- match vendor/device/ABI;
- read BAR base/length/flags;
- submit an async request, observe BUSY, receive one IRQ;
- read VALID/STALE/TRUNCATED and a result;
- ack and return to READY;
- a second request while BUSY is rejected;
- missing device is distinguishable from zero result.

### Real server

- loaded block probe returns a valid raw state and freshness;
- unloaded chunk returns `UNLOADED`, not air;
- out-of-bounds returns `OUT_OF_BOUNDS`;
- device unavailable/fault does not look like an empty world;
- no remote Python path bypasses the device bus;
- cleanup stops the CPU, detaches devices and releases ownership.

### Acceptance

The experiment is successful only if:

```text
1. A built-in device and a third-party mock device
   enumerate through the same ABI.
2. A DCPU player-driver fixture can discover, request,
   receive IRQ, read a bounded result and ack.
3. Unknown/absent/unloaded/truncated results are explicit.
4. Device work goes through the existing Swarmie execution path.
5. No new crop/ore/entity sensor semantics are added to the host.
```

---

## 18. Non-goals

- complete PCI or PCIe compatibility;
- bridges or multi-bus topology;
- hot-plug;
- DMA;
- network-attached devices;
- plugin marketplace;
- persistent device state;
- arbitrary third-party code loading;
- production MMIO address map;
- full DCPU `HWN`/`HWQ`/`HWI`;
- a stable public device API;
- crop, ore, entity, energy or mod-specific sensor semantics in the core;
- a `scan() -> List<Block>` API;
- bypassing server authority.

---

## 19. Open decisions

These must be resolved before implementation freezes:

1. **Name/ID mapping:** numeric IDs plus registry, or a bounded `CAP_NAME`
   string descriptor?
2. **Block state payload:** raw 32-bit state ID only, or a bounded property
   buffer?
3. **BAR assignment:** fixed slots forever, or host relocation within reserved
   windows?
4. **IRQ policy:** dedicated line only, or shared line bitmap later?
5. **Configuration writes:** confirm guest writes are ignored and latched as
   `CONFIG_ERROR`; confirm no guest reset path.
6. **Bus fault:** how does DCPU observe an MMIO fault without a new opcode?
7. **Provider trust:** only statically registered server-side providers in v0?
8. **Block probe range:** reuse existing observation permissions, or impose a
   device-specific range?
9. **HWN/HWQ/HWI:** when, if ever, do we add standard DCPU hardware enumeration
   on top of the MMIO config mechanism?
10. **Persistence:** do device IDs/BARs need to survive reload? v0 says no.

---

## 20. Implementation phases

### Phase 0 — this document

Freeze the v0 vocabulary and boundaries. No device code.

### Phase 1 — bus and configuration

- `DeviceDescriptor`/`DeviceFunction` host model;
- config header access;
- slot allocation;
- BAR overlap/alignment checks;
- capability list validation;
- host-only config writes;
- mock `example:counter@1` and config tests.

### Phase 2 — DCPU discovery

- guest config reader;
- enumerate slots;
- match IDs/ABI;
- read BAR and status;
- no HWN/HWQ/HWI yet.

### Phase 3 — block probe

- built-in `swarmie:block_probe@1`;
- async request/result;
- explicit unloaded/out-of-bounds/truncated;
- one IRQ and ack;
- real server evidence.

### Phase 4 — parity and limits

- third-party mock provider through the same ABI;
- resource budgets;
- detach/cleanup;
- documentation of unverified cases.

---

## 21. Relationship to the existing DCPU spike

The current experiment has:

- a pure-Java DCPU subset (`SET`, `IAS`, `RFI`);
- fixed motion and clock MMIO windows;
- no general device registry;
- no HWN/HWQ/HWI;
- `BasicMemoryBus` without overlap rejection;
- `MmioDevice` with only read/write/tick.

SDB v0 does not require replacing the existing motion/clock devices. It wraps
them as built-in functions with configuration descriptors and adds a bus above
them.

The first implementation should be gated by the same development-environment
and explicit-config rules as the DCPU experiment. It is not a release feature
and has no Python/JSONL operation.

---

## 22. Design summary

```text
                  ┌────────────────────────────┐
                  │ Player driver / firmware   │
                  │ interprets device data     │
                  └─────────────┬──────────────┘
                                │ SDB config + MMIO
                  ┌─────────────▼──────────────┐
                  │ SDB v0                     │
                  │ identity · BAR · caps      │
                  │ status · IRQ · fault       │
                  └─────────────┬──────────────┘
                                │ bounded host call
                  ┌─────────────▼──────────────┐
                  │ Swarmie Entity / Runtime   │
                  │ authority · range · tick   │
                  └─────────────┬──────────────┘
                                │
                  ┌─────────────▼──────────────┐
                  │ Minecraft / Fabric world   │
                  └────────────────────────────┘
```

The bus is not the intelligence layer.

It is the contract that lets player-authored drivers own the meaning of devices
without letting them own the world.
