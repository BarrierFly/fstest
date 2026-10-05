# Fengshui Tester (fstest)

A [Fabric](https://fabricmc.net/) / [Carpet](https://github.com/gnembon/fabric-carpet) extension mod that tests redstone contraptions for **directionality** (do rotation/mirror change behaviour?) and **positionality** (does translation change behaviour?), by replaying accepted player operations inside an isolated simulated space and diffing micro-timing style event streams against the real world.

中文说明见下方 [风水测试器（中文）](#风水测试器中文)。

## How it works

**What triggers a simulation.** Only player-initiated operations do — placing, breaking and using blocks reach the capture hooks through `ServerPlayerGameMode`; block updates caused by redstone, dispensers or any other entity never do. In instant mode an operation triggers when it changed a block *and* affected a monitored block synchronously, because that instant stream is all the instant engine compares. In **timed mode** the trigger is wider: with a named area scoped, any player operation targeting a block inside that area triggers, as long as the area holds at least one monitored block. That is deliberate — an operation whose effect only materialises a few ticks later (pressing a button, placing a component that feeds a delay) records nothing in the instant window, and the simulated ticks are precisely what reveals it. Operations outside the scoped area are ignored outright.

```
player operation accepted (place/break/use/tool/bucket)
        │  ServerPlayerGameMode mixins wrap the whole synchronous processing
        ▼
real-world recording  ── shared collection layer (Recorder) ──┐
   setBlock transitions, scheduled tick / block event         │ same code
   creations, neighbour-update dispatches at marked             ▼
   components                                          FstEvent stream
        ▼                                                     ▼
RegionSnapshot (blocks + frozen BE NBT + queues + env) ──> simulated space (chunkless virtual Level)
        │                                        replayed under each transform:
        │                                        D = 4 rotations x flip/no-flip
        │                                        P = uniform / chunkborder offsets
        │                                        PD = random symmetry + offset per run
        ▼
aggregated diff report:  "50x (+... -...)   20x (...)   ..."  (baseline self-checked)
```

Key properties:

- **Isolation**: the simulation uses its own seeded random sequence (seeded best-effort from the captured real random state), copies game time/weather/difficulty/game rules, never sends packets, never writes statistics, and can never touch the real world.
- **Window semantics**: pre-existing scheduled ticks and block events are copied into the simulation *query-only* (never executed inside the operation window); creation attempts made during the operation are recorded as events on both sides. Handler-side scheduled ticks (e.g. a button's release) that the replay cannot regenerate are backfilled into the simulation; block-event creations are never backfilled — the simulation must produce them from the replayed updates, and any it misses is reported as a genuine divergence. `/fstest updates` and `/fstest duplications` filter which of these events reach the streams, symmetrically on both sides, without changing what is attempted or replayed.
- **Baseline self-check**: an identity-transform run must reproduce reality exactly; if not, the simulator is distorted and comparison output is suppressed with a warning instead.

## Requirements

- Minecraft **1.21.11** (Fabric), fabric-loader ≥ 0.19.3 required (the mod declares that as a hard dependency)
- [fabric-carpet](https://modrinth.com/mod/carpet) ≥ 1.4.193

## Commands (`/fstest`, permission level 2)

| Command | Effect |
|---|---|
| `/fstest mode <none\|directionality\|positionality\|both>` | `directionality` accepts aliases `directional/direction/D/d`; `positionality` accepts `positional/position/P/p`; `both` accepts `DP/PD` (case-insensitive). Default `none`. |
| `/fstest color <all\|dye color>` | Only monitor markers whose colour matches this filter. Default `all`. |
| `/fstest count <d\|p\|pd> <n>` | Simulation counts per mode (d default 1, p/pd default 100). Independent settings. |
| `/fstest range <unlimited\|r>` | Snapshot radius cap (r 1..128). Default `unlimited` (= effective 48 in v1). **Set this deliberately — see [Snapshot radius](#snapshot-radius-and-performance-read-this-first).** |
| `/fstest pstrategy <uniform\|chunkborder\|hashdelta>` | Offset sampling for P/PD. `hashdelta` is planned for v2. |
| `/fstest updates <on\|off>` | Record block-update dispatch events (`NU`) or leave them out of the streams. Default `on`. |
| `/fstest duplications <on\|off>` | Record creation attempts that were rejected as duplicates (the `dup` entries) or leave only the successful ones. Default `on`. |
| `/fstest sim <instant\|timed>` | **Simulation mode** (default `instant`). Instant simulates only the operation's synchronous window (v1 behaviour). Timed additionally *executes* `simticks` game ticks in the simulated space after the operation replay. The two modes are exclusive - exactly one is active. See [Timed mode](#timed-mode-multi-tick-simulation). |
| `/fstest simticks <n>` | Timed-mode simulation duration, 1..100 game ticks (default 10). |
| `/fstest scope <unlimited\|r\|area-name>` | The snapshot/simulation **scope**: a number usable as a radius is treated as the radius (r 1..128, default `unlimited` = effective 48); a name selects a registered test area. `/fstest range` is kept as a deprecated alias. |
| `/fstest mtrarea add <name> <pos1> <pos2>` | Register a named axis-aligned **test area** (each side ≤ 256 blocks). fstest resolves area names only against its own registry - the MicroTimingReplay mod's profiles are a separate thing entirely. |
| `/fstest mtrarea remove <name>` / `list` / `clear` | Remove one area / list all (annotating the currently scoped one) / remove all. |
| *(visualization)* | Registered areas are drawn in world space once per client tick: the scoped one as a green box with a faint fill, the others as grey outlines (skipped when far from the camera). Single-player only — the selection is not replicated to clients of a remote server. The renderer announces itself in the log once per session (`selection overlay renderer active; N area(s)...`), which is the quickest way to tell a missing overlay from a missing one. |
| `/fstest targets add <color> <x> <y> <z>` | Mark a block position directly (see below). Re-adding the same position recolours it. |
| `/fstest targets remove <x> <y> <z>` | Remove one target. |
| `/fstest targets remove color <color>` | Remove every target of that colour. |
| `/fstest targets clear` | Remove all targets. |
| `/fstest targets query` | List the manually registered targets only (placed wool is not listed). |
| `/fstest query` | Show current configuration. |

A subcommand invoked without its required arguments prints that subcommand's own usage line instead of Brigadier's generic tree help; bare `/fstest` prints a one-line index of the subcommands.

Settings are sticky and **persisted** to `<config>/fstest.json` (including the target list, the test areas and the scope selection), written after every change and reloaded on server start. A corrupt or unparseable file falls back to the defaults with a server-log warning; unknown keys are ignored.

### Timed mode (multi-tick simulation)

`/fstest sim timed` extends the analysis beyond the operation's instant window - inspired by hotpad100c's MicroTimingReplay ("MTR") and its tick-count-driven recording, but implemented natively: the simulated space runs inside **a second, real `MinecraftServer`** (the same construction Ryan100C's simulatica uses - genuine void dimensions with a real chunk pipeline in a scratch world that is never saved). **The real world is never advanced and nothing is recorded from it beyond the instant window.**

- Each simulated run still replays the captured operation first (identical to instant mode), and then *executes* `simticks` game ticks in that real ServerLevel, mirroring vanilla's phase order: time advance → scheduled block ticks → scheduled fluid ticks → block events → block entity ticking. Because the simulated world IS a `ServerLevel`, **every vanilla block behaviour executes natively**: all scheduled-tick consumers (repeaters, comparators, buttons, observers, redstone torches, pressure plates, pistons, dispensers, and everything else), fluid ticks (water/lava flow), block events (piston actions), block-entity ticking (moving pistons, hoppers, sculk sensors) and removal hooks (`affectNeighborsAfterRemoval`).
- Still deliberately out of scope (per the plan): **chunk ticks incl. random ticks** (they are never simulated), entities (none exist in the simulated world - block drops, falling blocks and anything entity-driven are refused), weather, raids and explosions' entity effects.
- The **baseline self-check** still gates the output: the identity run's instant-window events must match the real recording exactly, or the simulator is reported as distorted. (The multi-tick part has no reality counterpart, so it is checked only against the baseline simulation - see below.)
- Because reality is not recorded beyond the instant window, the diff **reference becomes the baseline simulation itself** (identity transform, 0 offset): every transformed D/P/PD run's full multi-tick stream is compared against the baseline run's. "Matched" in the report means "matched the baseline simulation".
- The scope (`/fstest scope`, radius or named area) bounds what is captured and simulated; in timed mode it should cover the device, its cascades *and* every monitored marker - markers outside the scope cannot produce simulated events and will trip the baseline check.
- Costs: the first timed-mode analysis boots the simulation server (a datapack load - expect a one-off pause of a few seconds). Per run: chunk force-load + promotion, a raw copy-in of the region, the replay and `simticks` ticks of real simulation, then a reset. Lower `/fstest count` values and a tight scope are strongly recommended.
- **Where the time actually goes.** The simulation space is a real `ServerLevel`, so a run needs its region's chunks loaded and lit. `directionality` runs reuse one location and pay that cost once; every `positionality`/`pd` run samples a fresh offset millions of blocks away, so it pays it again. That per-run chunk acquisition is the dominant cost of timed mode and is not amortised — expect roughly a second per P/PD run, and prefer `directionality` (or the instant engine) when you need many runs. Each run logs its own breakdown: `timed run <label> took <ms> (promote <ms>, release <ms>, ...)`, so the split between chunk promotion and the simulated ticks is visible per run.
- Caveats: the void world's lighting (sky light 15, no real shadows) and biome (the_void) can diverge from reality for light-/biome-sensitive components - such runs surface as baseline distortion, which is the honest answer.

### Snapshot radius and performance (read this first)

> [!IMPORTANT]
> **Set `/fstest range` to a sensible value before testing. The default (`unlimited` = radius 48 in v1) captures a 97×97×97 box and is very heavy — with the default counts (208 replay runs) the server will visibly stall.**

Why: the snapshot reads the **whole cube** of half-width `range` around the operation anchor (roughly `(2r+1)³` block reads), and every stored non-air block is then re-transformed and re-written once per replay run. The number of stored blocks — and therefore the cost of *every* run — grows with `r³`, and all runs execute **synchronously on the server thread**, so the whole analysis lands in a single tick. `/fstest range` and `/fstest count` are the two knobs that decide whether this takes milliseconds or seconds.

Guidance:

- **Set the radius just large enough to cover the contraption plus the extent its cascades travel** — for a small redstone device, `range 4`–`16` is usually plenty (e.g. `/fstest range 8`). Do not reach for `unlimited` unless you really need a large region.
- **Lower the counts while iterating** (`/fstest count p 20`, `/fstest count pd 20`); raise them only for a final confirmation run.
- **Too small a radius is also wrong**: anything outside the box reads as void air, so a cascade that leaves the region diverges from reality and trips the baseline self-check ("simulator distortion"). If that happens, increase the radius rather than ignoring it.
- `unlimited` does **not** mean unbounded or cheap — in v1 it is simply the fixed default of 48 (capped at `MAX_RANGE = 128`); both extremes are expensive.
- Chat/console progress lines tell you how far along a long run is, but they do not reduce the stall — only a smaller radius/count does.

### Selecting which blocks to monitor (wool markers)

The collection logic follows Carpet TIS Addition's *microTiming* subscription rules. A redstone component is "monitored" when the block at its subscription position is a **wool block whose colour passes the current `color` filter**. Default filter is `all`, so any of the 16 wool colours count. To split work, set `/fstest color <name>` (e.g. `red`, `light_blue`, `lime`) and only subscriptions of that colour are recorded; every other colour is silently ignored.

Place wool at the subscription position for the component type:

| Component | Place wool… |
|---|---|
| observer, end rod, piston, moving piston | on the block **behind its facing** (i.e. in the direction opposite to where the component points) |
| button, lever | **behind its attach facing** |
| wall redstone torch, tripwire hook | **behind its horizontal facing** |
| rail, detector/activator/powered rail, repeater, comparator, ground redstone torch, redstone dust, pressure plate | **directly underneath** the component |

You can also declare a monitor without placing a block: `/fstest targets add <color> <x> <y> <z>` registers a *manual target* at the given **block position**. A target marks that block directly and bypasses the wool geometry entirely: the marked block is subscribed for block-state changes, scheduled-tick and block-event creations, and its own outgoing block-update dispatches. So you can watch a component with no wool (and no end rod) anywhere — handy when wool would disturb the contraption, when the subscription position is inaccessible, or when you just want to point at the component itself. At a targeted position the wool rules are not consulted at all (the target overrides them; a target whose colour the filter excludes leaves the position unsubscribed). Targets are subject to the same `color` filter as placed wool (`/fstest targets query` annotates any that the filter currently excludes), and they are listed only by `targets query`; placed wool is resolved from the world and never appears there.

For each monitored position, the recorder captures (in execution order): block-state transitions (`old → new` with flags), scheduled-tick creation attempts (`delay | priority | block` plus an `ok`/`dup` success flag), block-event creations (`piston actions` etc., also flagged `ok`/`dup`), and block-update dispatches (the block notifying its neighbours).

The `ok`/`dup` flag mirrors Carpet TIS Addition: it reports whether the attempt actually queued a new entry (`ok`) or was silently deduplicated because an equivalent entry was already pending (`dup`) — for a scheduled tick an identical type+position, for a block event an identical `(pos, block, paramA, paramB)`. The two are distinct events in the stream, so a piston that tries to schedule its action twice in one window shows one `ok` and one `dup`. `/fstest duplications off` drops the `dup` entries from both the real and simulated streams (they are still attempted internally, and the replay's tick reconciliation still runs), so a diff can be read without duplication noise; `/fstest updates off` likewise drops every `NU` dispatch entry while keeping the dispatches themselves captured for replay.

Block-update dispatches use TIS's *block-update* semantics, which differ from the other event kinds in two ways: they are **sender-side** — the event belongs to the block that DISPATCHES the updates, and a block merely receiving updates is never logged — and they are gated **only** by the end-rod rule, or directly by a `/fstest targets` marker on the dispatching block. Each dispatch also carries its TIS subtype: `BU` (`updateNeighborsAt`, all six neighbours), `BU_EXCEPT` (same but one side skipped, which is named in the signature), `SINGLE` (a direct single-position `neighborChanged`), and `COMPARATOR` (the analog-output notifier, which only reaches comparators). To watch a block's outgoing updates (e.g. a piston notifying neighbours, or redstone wire fanning out), plant an end rod on wool so it **points at that block** (wool directly behind the rod), or `/fstest targets add` on the block itself; the dispatches then appear in the log under the **dispatching block's** coordinates. The component table above subscribes a position to block changes / tick & block-event creations, but *not* to neighbour updates. Placing an unrelated block next to a watched piston therefore produces no NU records: the placed block's dispatches are unsubscribed and the piston's receipt is not an event.

Tip: if your contraption has no `+`/`-` lines in the report, no marker at all was placed or registered in the right position — check the [Monitoring troubleshooting](#monitoring-troubleshooting) entry below.

### Output destinations

Every accepted analysis produces output in **three places, simultaneously**:

1. **Player chat** — multi-line coloured report. The header is gold, the per-outcome labels are yellow, the summary is grey, all-match is green, baseline distortion is dark red. The dark-grey line at the end shows the path to the on-disk log and is **clickable** (clicking it pre-fills a `say <path>` command — vanilla's packet codec rejects the `open_file` click action, so the file cannot be opened straight from chat). Difference lines are grouped under their monitored position (`@ x y z`).
2. **Server console / `logs/latest.log`** — the same lines, prefixed with `[fstest]`. INFO for normal runs, WARN for baseline distortion, ERROR for analysis crashes. This is the persistent human record; the chat line scrolls away.
3. **Per-analysis text file under `<run-dir>/fstest-logs/`** — the full record. Filenames look like `2026-08-26_19-45-12-345_USE_ITEM_ON_BLOCK_at_3_64_-2_Player1.txt`. The file holds, in order:
   - a header with timestamp, player, operation, anchor, dimension, game time, the full config snapshot (mode, colour filter, counts, range, strategy, `updates`, `duplications`, MTR ticks, test area, registered-target count), the real-stream event count and the raw tick/block-event creation-attempt counts with how many of them made it into the stream (attempts include duplicates even when `duplications` is off), the baseline self-check result, and the total analysis wall-clock time (each run's stats line carries its own);
   - the **real stream** of events (deduplicated, with `×N` suffixes on consecutive identical `pos | signature` lines);
   - the **baseline** stream (identity transform) and its diff vs the real stream; in timed mode the baseline is split per simulated tick (`BASELINE sim tick #n` sections) and its diff vs the real stream covers only the instant-window part (the baseline self-check);
   - **every simulation run** in the configured order — the three modes are alternatives, so `directionality` runs the D set only (the identity baseline is reused as the first entry, followed by per non-identity symmetry × `count_d`), `positionality` runs the P set only (`count_p` runs), and `both` (aliases `DP`/`PD`) runs the PD set only (`count_pd` runs, each drawing a random symmetry and a random offset) — each with its event list, a one-line replay diagnostic (roots applied, setBlock calls, neighbour-update dispatches, tick/event creation attempts, the sampled offset, and - when non-empty - `sideEffectCtxMismatch N @ x y z ...`, which lists the removals whose side-effect replay landed in a different cascade context than reality's, so their relative timing is not guaranteed), and its diff vs the real stream. A diff whose unmatched lines are identical as a multiset is labelled `(order change only)` instead of being shown as extras/missing;
   - the **aggregated summary**: total simulations, count that matched the reference (reality, or the baseline simulation in timed mode), count of distinct outcomes, and for each outcome `=== Nx [label(s)] ===` (with an `(order change only ...)` note when applicable) followed by the sample `+`/`-` lines grouped by monitored position.

While the analysis runs, chat and console get a grey start line (`N replay(s) planned`) and then progress every `max(10%, 50)` replays; the final report follows.

`run-dir` is the working directory of the Minecraft JVM (e.g. `.minecraft/` for a client, the server root for dedicated). A JSONL variant and `/fstest` commands to list/grep previous runs are planned for v2.

### Reading the report

- `+` lines: the simulation produced something reality did not (sim extra).
- `-` lines: reality produced something the simulation missed (sim missing).
- **The same line appearing in both `+` and `-` is an ORDER change**, not a missing/extra event: the same update was received (or the same block event created) at a different point of the cascade. Vanilla's redstone wire evaluator notifies its surroundings while iterating a `HashSet` of positions, so the delivery order legitimately depends on the device's absolute coordinates — a different transform (D) or offset (P) samples a different order. This is real position/orientation-dependent behaviour, exactly what the tester measures; the 0° baseline matching proves the simulator itself reproduces it faithfully. When the unmatched lines are identical as a multiset, the tester now says so directly: the log labels the diff `(order change only)` and chat prints an `(order change only - the same events in a different order)` note, so it is never mistaken for an extra/missing event.
- **Different event counts between a run and reality** (e.g. 14 piston block-event creations vs 15) are the same phenomenon one step further: the wire cascade unrolls differently at the sampled coordinates, producing a different number of power-change rounds and therefore a different number of notifications. Real physics, not a simulator fault.
- Runs are aggregated by distinct outcome: e.g. `50x [P#3]:` followed by that outcome's diff lines; runs matching the reference exactly are counted in the summary line. In timed mode the reference is the baseline simulation (see [Timed mode](#timed-mode-multi-tick-simulation)).

For directionality testing, any non-empty diff means the contraption is **not** symmetric under that transform. For positionality, diffs indicate position-hash / chunk-border sensitivity (use `chunkborder` strategy to probe borders deliberately).

#### Monitoring troubleshooting

- "No monitor subscribed under the selected wool colour near the operation; place wool next to your redstone components or register a /fstest targets marker." → the colour filter excludes your wool/target (`/fstest color all` or match the colour); or there is no wool in the component's subscription position (see the table) and no `/fstest targets` marker on the component; or it is outside the snapshot radius (raise `/fstest range`, but keep it as small as the device allows — see [Snapshot radius and performance](#snapshot-radius-and-performance-read-this-first)). If you registered targets, run `/fstest targets query`: a target annotated "(excluded by the current color filter)" is being filtered out.
- "Simulator distortion detected! ... baseline differences ..." → your contraption uses something the v1 simulator does not cover (light-sensitive components, entities, explosions, piston move-shape effects on rails/fences, etc.). The per-file log under `fstest-logs/` will show which `+`/`-` lines the baseline disagrees on — that is the first thing to fix.

## 0.4.1 changes

**Fixed**
- **Timed mode triggered on operations outside the scoped area.** The 0.4.0 scope gate only ran for operations that carry a hit position. The client falls back to an in-air item use whenever the server answers the block interaction with `PASS`, and that path has no hit position — so it skipped the gate entirely. Filling water on a stair is the common case: a stair is neither water-replaceable nor a liquid container, so the bucket asks for the block next to the clicked face, and when that one is blocked too the whole interaction returns `PASS`, the client re-sends the click as a plain item use, and the bucket then places water wherever it likes and triggers a simulation. The gate now judges the position the operation is anchored at (the clicked block, or the player for an in-air use), so every entry point is covered. Operations outside the selection are ignored again, as documented. Note that a player standing *inside* the selection who places a block *outside* it still triggers — the gate judges the operation, not the resulting block.

## 0.4.0 changes

**Added**
- **Selection overlay** — registered test areas are drawn in world space: the scoped one as a green box with a faint fill, the others as grey outlines. Single-player only (the selection is not replicated to clients of a remote server).

**Changed**
- **`both`/`pd` runs the PD set only.** It previously ran the D set, the P set *and* the PD set, so enabling it tripled the work. The three test modes are now alternatives.
- **Wider trigger in timed mode.** With a named area scoped, any player operation inside it triggers a simulation, as long as the area holds at least one monitored block. Previously an operation had to affect a monitored block *synchronously*, which excluded anything whose effect only appears a few ticks later (buttons, components feeding a delay) — precisely what timed mode exists to test. Operations outside the scoped area are ignored. Instant mode is unchanged. The baseline self-check is skipped when reality recorded no instant events, since there is nothing to reproduce against.

**Fixed**
- Timed mode exhausted the heap on large P/PD offsets: it force-loaded the axis-aligned union of the previous run's box (to be cleared) and the new one, which for dimension-wide offsets is billions of chunks. Only the new box is loaded now, and a box above 1024 chunks is refused outright.
- Timed runs no longer accumulate chunks: a distant box is released as soon as it is cleared, and the release actually unloads (unloading only runs in the chunk map's tick, which the old release path never called), so memory is reclaimed during and after a batch.
- Per-run cost: the promotion pump handed the server's task loop a 50 ms budget and re-granted it on every attempt, so the budget multiplied by the attempt count and dominated each run. The budget is now 2 ms, and the ticking margin is one chunk instead of two (9 chunks per box instead of 25).
- `/fstest mtrarea add` always stored the second corner as typed, collapsing the selection to a single block whenever that corner was the smaller one. Both corners are now read from the original arguments, and coinciding corners are rejected.
- `/fstest mtrarea add` printed its raw template with `%s` placeholders (four placeholders, three arguments).
- The full-log chat line used an `open_file` click event, which the packet codec rejects on the network — the whole `system_chat` packet failed to encode and the message never arrived.

## v1 scope & limitations

- **Performance / snapshot radius**: the snapshot reads the whole `(2·range+1)³` cube around the anchor, and every replay run re-writes its non-air blocks — cost grows with `range³` and is multiplied by the number of runs, all **synchronously on the server thread**. Always set `/fstest range` to just cover the contraption and its cascades, and lower the counts while iterating; the default `unlimited` (= 48) with default counts will stall the server. See [Snapshot radius and performance](#snapshot-radius-and-performance-read-this-first).
- Single version build (MC 1.21.11); Stonecutter multi-version expansion (1.19.4 / 1.21.10 / 26.x) is the next step.
- Instant mode: lighting is approximated with neutral constants (sky 15, block 0). Timed mode: a real void world (sky light 15, no real shadows, biome the_void). Either way, light-sensitive components (daylight sensors etc.) produce results for reference only - a divergence shows up as baseline distortion.
- Entities, explosions, and block drops are out of the simulation window; operations relying on them will surface as baseline distortion warnings.
- Removal side effects (`affectNeighborsAfterRemoval`, e.g. redstone wire announcing its power change when broken, or when a trapdoor invalidates its support) cannot be *called* inside the simulated space (the hook's signature demands a `ServerLevel`). Instead the dispatches the real hook performs are captured per removal and re-issued in the simulated space **at the same point of its setBlock flow where vanilla would run the hook** - so the relative order and cascade context match, and the simulation's own neighbour cascades take over from there. This covers removals performed directly by the operation and removals produced inside a neighbour cascade alike. Residual: a hook that mutates blocks instead of only dispatching would not be reproduced; among 1.21.11's redstone components (wire, rails, torches, diodes, levers, buttons, plates, observers, pistons) the removal hooks only dispatch.
- Block-entity removal side effects (`BlockEntity#preRemoveSideEffects`) are deliberately not simulated. Its default implementation drops container contents (spawning item entities); overrides drop campfire / lectern / jukebox / shulker box / furnace contents, scream from a removed sculk shrieker (game events), or finalize a moving piston (`PistonMovingBlockEntity#finalTick`). Entities, item drops and game events are out of scope per the plan, and none of these emits the recorded event kinds, so the diff is unaffected today - listed here so that a future test depending on them is not mistaken for a simulator bug.
- Scheduled ticks are recorded but not executed inside the operation's synchronous window - matching vanilla semantics, where queued ticks only run in later tick phases.
- The simulated space deliberately ignores the dimension's build height (per the plan: "the custom virtual world ignores world height limits"): a vertical P offset may place the captured region's edges outside `[minY, maxY]`, and those blocks stay stored and readable. Enforcing the build height there would silently turn supporting ground (or the end rod / wool above) into void air and produce a spurious run with zero events.
- Configuration persistence (including targets, the test areas and the scope selection), per-marker grouped diff output, the clickable log path (as a command suggestion), per-run/total analysis timing and the world-space selection overlay (0.4.0) are implemented. `hashdelta` strategy, JSONL machine output, exact light copying and regression tests remain v2 items (see `docs/v2-todo.md` for the difficulties involved).
- A per-analysis text log is written to `<run-dir>/fstest-logs/` (see [Output destinations](#output-destinations)); the chat log path is clickable. JSONL is planned for v2.

## Building

```bash
# JDK 21 required
./gradlew compileJava     # or ./gradlew build for the remapped jar
```

The project resolves fabric-carpet through Jitpack mirrors; see `build.gradle`.

## Acknowledgements

fstest was developed with reference to the following open-source projects. None of their source code is bundled in the built jar.

- **[Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition)** — by TISUnion, maintained by Fallen_Breath — **LGPL-3.0**. The collection layer re-implements its *microTiming* subscription rules and event semantics (wool / end-rod markers, block-update subtypes, creation success flags, comparator updates).
- **[fabric-carpet](https://github.com/gnembon/fabric-carpet)** — by gnembon — **MIT**. fstest is a Carpet extension and follows Carpet's extension API, mixin conventions and command-permission level (2, the same as `/log`).
- **[MicroTimingReplay (MTIR)](https://github.com/hotpad100c/microtimingreplay)** — by Ryan100C (hotpad100c) — **MIT**. Consulted as a reference for recording and step-by-step replaying of micro-timing events.
- **[simulatica](https://github.com/hotpad100c/simulatica)** — by Ryan100C (hotpad100c) — **MIT**. Consulted as a reference for the isolated in-world simulation approach.
- **[Ticker](https://github.com/hotpad100c/ticker)** — by Ryan100C (hotpad100c) — **CC0-1.0**. Consulted as a reference for command-driven injection of block events, scheduled ticks and world/game events.

The development reference checkouts (`Carpet-TIS-Addition/`, `fabric-carpet/`, `microtimingreplay/`, `simulatica/`, `ticker/`) are not part of this repository.

## License

LGPL-3.0-only. The collection layer re-implements concepts from [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition)'s microTiming logger under the same license; no source code of that mod is included or linked.

---

# 风水测试器（中文）

一个 Fabric/Carpet 扩展 Mod，用于测试红石装置的**方向性**（旋转/镜像后行为是否一致）与**位置性**（平移后行为是否一致）：把被服务端受理的玩家操作放进隔离的模拟空间重放，再用微时序风格的事件流与现实记录做聚合 diff。

## 工作原理

**什么会触发一次模拟。** 只有玩家主动操作会——放置、破坏、对目标方块使用物品/工具/桶，这些都经由 `ServerPlayerGameMode` 进入采集钩子；红石级联、发射器或任何实体引起的方块更新都不会。瞬时模式下，操作必须**同步地**影响到被监视方块才会触发，因为瞬时引擎比对的只有那一个瞬时窗口的流。**定时模式**的触发条件更宽：设定了命名选区时，玩家对选区内任意方块的操作都会触发，只要该选区里至少有一个被监视方块。这是刻意的——按按钮、放置一个接入延迟的元件这类操作，效果要过几个刻才显现，瞬时窗口里什么也录不到，而跑模拟刻恰恰就是为了把这种延迟效果暴露出来。选区之外的操作直接忽略。

1. 玩家操作（放置/破坏/对方块使用物品/工具改形/桶装放流体）在服务端受理后，其**整个同步处理过程**被包进一次采集会话；
2. 真实世界与模拟空间共用同一套采集层（复刻自 TIS Addition 微时序逻辑，LGPL 兼容）：记录订阅位置上的方块状态变化、计划刻/方块事件创建尝试、邻居更新派发等事件，按真实执行顺序排列；
3. 以操作点为中心做**操作前**快照（方块状态 + 冻结的方块实体 NBT + 计划刻/方块事件队列 + 时间/天气/难度/游戏规则；在本次操作的第一个根 setBlock 应用前的瞬间惰性采集，因此重放根操作时模拟空间才会真正发生变更），在无区块的虚拟世界里按变换重建：
   - D（方向性）：四个旋转 × 有/无翻转，共八种正交对称；方块朝向属性随 Rotation/Mirror 语义同步变换；
   - P（位置性）：均匀随机平移（水平 ±2^23 且自动夹紧到合法坐标），或区块边界策略（16 的倍数加小抖动）；
   - PD：每轮随机选一种对称再叠加一次 P 偏移；
   - 模拟产生的事件在参与 diff 前会做逆变换映射回现实坐标（位置/朝向/方块状态同步还原），使各轮结果可与现实流直接逐条对比。
4. 模拟使用独立种子化随机数（尽力取自现实捕获的随机状态），严禁推进真实世界的共享随机序列；不发送任何数据包、不写统计；
5. 先跑一轮"无变换 0°"基线自检——基线与现实不一致即视为模拟器失真，报错并抑制对比输出。

## 命令（`/fstest`，权限等级 2）

| 命令 | 作用 |
|---|---|
| `/fstest mode <none\|directionality\|positionality\|both>` | `directionality` 可简写 `directional/direction/D/d`；`positionality` 可简写 `positional/position/P/p`；`both` 可简写 `DP/PD`（大小写均可）。默认 `none`。 |
| `/fstest color <all\|羊毛颜色>` | 只监测颜色匹配该筛选器的标记。默认 `all`。 |
| `/fstest count <d\|p\|pd> <n>` | 各模式的模拟次数（d 默认 1，p/pd 默认 100），三档互不共用。 |
| `/fstest range <unlimited\|r>` | 快照半径上限（r 取 1..128）。默认 `unlimited`（v1 实际按 48 生效）。**务必主动设置——见[快照半径与性能](#快照半径与性能先看这里)。** |
| `/fstest pstrategy <uniform\|chunkborder\|hashdelta>` | P/PD 的偏移采样策略；`hashdelta` 属 v2 规划。 |
| `/fstest updates <on\|off>` | 是否记录方块更新派发事件（`NU`）。默认 `on`。 |
| `/fstest duplications <on\|off>` | 是否记录因重复被拒的创建尝试（`dup` 条目）。默认 `on`。 |
| `/fstest sim <instant\|timed>` | **模拟模式**（默认 `instant`）。瞬时只模拟操作的同步处理窗口（v1 行为）；定时在重放操作之后再*执行* `simticks` 个游戏刻。两种模式互斥——同一时间只激活一种。见[定时模式](#定时模式多刻模拟)。 |
| `/fstest simticks <n>` | 定时模式的模拟时长，1..100 游戏刻（默认 10）。 |
| `/fstest scope <unlimited\|r\|选区名>` | 快照/模拟**范围**：能当半径用的数字按半径处理（r 取 1..128，默认 `unlimited` = 实际 48）；名字则选取已注册的测试选区。`/fstest range` 保留为弃用别名。 |
| `/fstest mtrarea add <名称> <坐标1> <坐标2>` | 注册一个命名的长方体**测试选区**（每条边 ≤ 256 格）。fstest 只在**自己的**选区注册表里解析名字——MicroTimingReplay mod 的 profile 选区是完全独立的东西，互不读取。 |
| `/fstest mtrarea remove <名称>` / `list` / `clear` | 移除一个选区 / 列出全部（标注当前范围内者）/ 清空。 |
| *（可视化）* | 已注册的选区每个客户端刻画一次：当前范围内的那个是绿色线框加淡填充，其余是灰色线框（离镜头太远的会跳过）。仅在单人游戏内生效（远程服务器不会把选区同步给客户端）。渲染器每局会在日志里自报一次（`selection overlay renderer active; N area(s)...`），用来最快区分「没渲染出来」和「数据没有」。 |
| `/fstest targets add <颜色> <x> <y> <z>` | 直接标记一个方块位置（见下）。对同一位置重复执行会改色。 |
| `/fstest targets remove <x> <y> <z>` | 移除单个目标。 |
| `/fstest targets remove color <颜色>` | 移除该颜色的全部目标。 |
| `/fstest targets clear` | 移除全部目标。 |
| `/fstest targets query` | 只列出手动注册的目标（放置的羊毛不会出现在这里）。 |
| `/fstest query` | 查询当前全部配置。 |

子命令缺少必需参数时会输出该子命令自己的用法行，而不是 Brigadier 的通用命令树提示；裸 `/fstest` 给出一行子命令索引。

配置粘性保存，并**持久化**到 `<config>/fstest.json`（含目标列表、测试选区与范围选择）：每次修改后写回，服务器启动时读回。文件损坏或无法解析时回落默认值并在服务端日志告警；未知键会被忽略。

### 定时模式（多刻模拟）

`/fstest sim timed` 把分析范围扩展到操作瞬时窗口之外——灵感来自 hotpad100c 的 MicroTimingReplay（"MTR"）与其以 tick 数驱动的录制，但为 fstest 原生实现：模拟空间运行在**第二个真实的 `MinecraftServer`** 里（与 Ryan100C 的 simulatica 同一构造方式——真实空虚维度、真实区块管线、从不落盘的 scratch 世界）。**绝不推进真实世界，也不在瞬时窗口之外录制现实。**

- 每轮模拟仍先重放捕获到的操作（与瞬时模式一致），随后在那个真 ServerLevel 里*执行* `simticks` 个游戏刻，按原版阶段顺序：时间推进 → 计划刻（方块）→ 计划刻（流体）→ 方块事件 → 方块实体运算。因为模拟世界本身就是一个 `ServerLevel`，**所有原版方块行为都原生执行**：全部计划刻消费方（中继器、比较器、按钮、观察者、红石火把、压力板、活塞、发射器……）、流体计划刻（水/岩浆流动）、方块事件（活塞动作）、方块实体运算（移动活塞、漏斗、幽匿感测体）以及移除钩子（`affectNeighborsAfterRemoval`）。
- 仍然有意排除（按规划）：**区块刻（含随机刻）**——从不模拟；实体——模拟世界里不存在任何实体（掉落物、下落方块及一切依赖实体的行为会被拒绝）；天气、袭击与爆炸的实体伤害。
- **基线自检**仍然把守输出：identity 轮的瞬时窗口事件必须与现实记录完全一致，否则报模拟器失真。（多刻部分没有现实对照，只在各模拟轮次与基线模拟之间比较——见下。）
- 由于现实侧没有瞬时窗口之外的记录，diff 的**参照改为基线模拟本身**（identity 变换、0 偏移）：每个 D/P/PD 变换轮的完整多刻事件流与基线轮对比。报告中的"完全一致"指"与基线模拟一致"。
- 范围（`/fstest scope`，半径或命名选区）界定被捕获与模拟的区域；定时模式下应覆盖装置、其级联波及范围*以及*全部监测标记——范围外的标记无法产生模拟事件，会触发基线失真。
- **时间花在哪里。** 模拟空间是真实的 `ServerLevel`，所以每一轮都要把该区域的区块加载并照亮。`directionality` 各轮复用同一位置，这笔开销只付一次；而每一轮 `positionality`/`pd` 都会采到几百万格之外的新偏移，于是再付一次。这份逐轮的区块获取是定时模式的主要开销，且无法摊薄——按每轮 P/PD 大约一秒来预期，需要跑很多轮时请改用 `directionality`（或瞬时引擎）。每轮都会打印自己的耗时拆分：`timed run <标签> took <毫秒> (promote <毫秒>, release <毫秒>, ...)`，区块升级与模拟刻各占多少一眼可见。
- 成本：第一次定时模式分析要引导模拟服务器（一次数据包加载——预计一次性停顿数秒）。每轮：区块强制加载与提升、区域的原始拷入、重放、`simticks` 刻的真实模拟，然后重置。强烈建议调小 `/fstest count` 并收紧范围。
- 已知边界：空虚世界的光照（天空光 15、无真实阴影）与生物群系（the_void）可能与现实不同，光敏/群系敏感的装置会以基线失真呈现——这就是诚实的答案。

### 快照半径与性能（先看这里）

> [!IMPORTANT]
> **测试前务必把 `/fstest range` 设成合理值。默认的 `unlimited`（v1 实际半径 48）会快照 97×97×97 的立方体，非常重——配合默认次数（208 轮重放）服务端会明显卡顿。**

原因：快照会读取锚点周围半边长 `range` 的**整个立方体**（约 `(2r+1)³` 次读方块），其中每个非空气方块又要在**每一轮重放**里重新变换、重新写入。方块数量（也就是每轮的成本）随 `r³` 增长，且所有轮次都**同步跑在服务端线程**上，整次分析挤在一个游戏刻里。`/fstest range` 和 `/fstest count` 决定了这是几毫秒还是几秒。

建议：

- **半径设为刚好覆盖装置及其级联波及范围**——小型红石装置通常 `range 4`~`16` 足够（例如 `/fstest range 8`）。除非确实需要大范围，不要用 `unlimited`。
- **边调边试时把次数调小**（`/fstest count p 20`、`/fstest count pd 20`），只在最终确认时再调大。
- **半径也不能太小**：盒子之外的方块会被读成虚空空气，级联一旦跑出范围就会与现实不符，触发基线自检的"模拟器失真"告警。遇到这种情况要调大半径，而不是忽略它。
- `unlimited` 并不代表"无限"或"便宜"——v1 里它就是固定的 48（上限 `MAX_RANGE = 128`），两端都贵。
- 聊天/控制台的进度提示只能告诉你跑到哪了，**不会减少卡顿**；真正有用的是更小的半径和次数。

## 监测方式（羊毛标记）

监测逻辑与 TIS 微时序完全一致：把**羊毛**放在元件旁即可订阅它的事件；哪个方块位置算"旁"由元件类型决定。默认 `/fstest color all`（任意羊毛色都算订阅），可改为指定颜色把工作分片。

| 元件 | 把羊毛放在… |
|---|---|
| 观察者、末地烛、活塞、活塞臂 | 它的**朝向后侧**那一格 |
| 按钮、拉杆 | 它的**附着面反方向**那一格 |
| 墙红石火把、绊线钩 | 它的**水平朝向反方向**那一格 |
| 铁轨/动力/激活/探测/导轨、中继器、比较器、地面红石火把、红石粉、压力板 | 它的**正下方**那一格 |

也可以不放方块、直接用命令声明监测点：`/fstest targets add <颜色> <x> <y> <z>` 在给定**方块位置**注册一个**手动目标**。目标直接标记该方块、完全不走羊毛几何：被标记的方块会订阅自身的方块状态变化、计划刻/方块事件创建，以及它自己对外派发的方块更新。因此可以在完全没有羊毛（也没有末地烛）的情况下监测任意元件——当放羊毛会干扰装置、订阅位置难以触及、或就是想直接指着元件本身时尤其好用。目标位置上不再套用羊毛规则（目标是覆盖而非回退；颜色被筛选器排除的目标会让该位置保持未订阅）。目标与放置的羊毛受同一个 `color` 筛选器约束（`/fstest targets query` 会给被筛掉的条目标注说明），且只有 `targets query` 会列出手动目标；放置的羊毛由世界解析，不会出现在该列表里。

每次成功订阅，采集层按真实执行顺序记录：方块状态变化（含 flags）、计划刻创建尝试（delay/priority/block + `ok`/`dup` 成功标志）、方块事件创建（活塞动作等，同样带 `ok`/`dup`）、方块更新派发（该方块对邻居的通知 + TIS 子类型标签）。

`ok`/`dup` 与 TIS 一致：表示这次尝试是否真的入队（`ok`），还是因为队列里已有等值条目而被静默去重（`dup`）——计划刻看"type+坐标"是否已在队列，方块事件看 `(坐标, 方块, paramA, paramB)` 四元组是否已存在。两者在事件流里是不同的两条，因此活塞在同一窗口内两次尝试排入动作会显示一条 `ok` 与一条 `dup`。`/fstest duplications off` 会从现实与模拟两条流里都去掉 `dup` 条目（内部仍照常尝试，重放的计划刻核对也照常执行），便于在无重复噪声的情况下读 diff；`/fstest updates off` 同理去掉所有 `NU` 派发条目，但派发本身仍会被捕获用于重放。

注意：**方块更新派发的语义与上表不同**，与 TIS 微时序一致——它是**发送方事件**：记录"被末地烛指向的方块**发出了**更新"，而某方块**收到**更新不会被记录。每条派发还带 TIS 子类型：`BU`（`updateNeighborsAt`，六向全发）、`BU_EXCEPT`（同一派发但跳过某一面，方向写进签名）、`SINGLE`（针对单个坐标的 `neighborChanged` 直发）、`COMPARATOR`（模拟输出变更通知，只及比较器）。想观测某个方块（如活塞、红石线）对外派发的更新，就把末地烛种在羊毛上、**指向那个方块**（羊毛在末地烛正后方，即指向的反方向一格），或者直接对该方块执行 `/fstest targets add`；派发事件会挂在**派发方方块**的坐标下。上表的元件规则只订阅方块变更/计划刻/方块事件创建，不订阅邻居更新；`/fstest targets` 目标则两条通道都订阅。因此在没有红石信号时于活塞旁放置普通方块不会产生任何 NU 记录（放置方块的派发未被订阅，活塞的"接收"不是事件）。

## 输出位置

每一次被受理的分析都会**同时**输出到三个地方：

1. **玩家聊天栏** — 多行带色报告：标题金色、单次结果标签黄色、汇总行灰色、全部一致时绿色、基线失真时深红色；末尾的深灰行显示本次落盘文件的绝对路径，且**可点击**（点击会填入 `say <路径>` 命令——原版网络层禁止 `open_file` 点击动作，无法直接在聊天栏里打开文件）。差异行会按监测位置分组（`@ x y z` 小节）。
2. **服务端控制台 / `logs/latest.log`** — 同样的内容加 `[fstest]` 前缀；正常 INFO，基线失真 WARN，分析崩溃 ERROR。这是持久的人眼记录（聊天行会滚走）。
3. **`<运行目录>/fstest-logs/` 下的一次性文本文件** — 完整存档。文件名形如 `2026-08-26_19-45-12-345_USE_ITEM_ON_BLOCK_at_3_64_-2_Player1.txt`。文件按顺序包含：
   - 头信息：时间戳、玩家、操作类型、锚点坐标、维度、游戏时间、完整配置快照（模式、颜色筛选、次数、范围、策略、`updates`、`duplications`、MTR 刻数、测试选区、目标数）、现实流事件数、计划刻/方块事件的**创建尝试**总数及其中**进入事件流的条数**（尝试数含重复项，即使 `duplications` 为 off）、基线自检结果，以及整次分析的总耗时（每轮 stats 另有单轮耗时）；
   - **现实流**（去重后输出，相同 `坐标 | 签名` 折叠为 `×N` 计数行）；
   - **基线**（identity 变换）事件流及其与现实的 diff；定时模式下基线按模拟刻分节（`BASELINE sim tick #n`），其与现实的 diff 只覆盖瞬时窗口部分（即基线自检）；
   - **每一次模拟** 的事件流及其与现实的 diff：D 类模式下第一条即 identity 基线，其后是 D 集（每个非 identity 对称 × `count_d`），再 P 集（`count_p` 次），再 PD 集（`count_pd` 次）；每轮附一行重放诊断（生效根操作数、setBlock 次数、邻居更新派发数、计划刻/方块事件创建尝试数、采样偏移；非空时还有 `sideEffectCtxMismatch N @ x y z …`——列出那些副作用重放落在了与真实不同级联上下文里的移除坐标，其相对时序不再有保证）；
   - 若某轮 diff 的未匹配行作为多重集完全相同，则该轮 diff 标注为 `(order change only)`，不再当作增删；
   - **聚合汇总**：模拟总数、与参照（现实流；定时模式下为基线模拟流）完全一致的次数、不同结果的种类数，每种结果以 `=== Nx [标签] ===`（必要时附 `(order change only …)` 说明）形式给出按监测位置分组的样本 `+`/`-` 行。

测试进行期间，聊天栏与控制台会先给一行灰色开始提示（`计划重放 N 轮`），之后每完成 `max(10%, 50)` 轮给一次进度，最后输出完整报告。

`运行目录` 是 Minecraft 启动时 JVM 的当前工作目录（客户端一般是 `.minecraft/`，专用服务器就是服务端根目录）。JSONL 变体与浏览历史结果的 `/fstest` 子命令属 v2 规划。

## 输出解读

- `+` 行：模拟产生了现实中没有的内容；
- `-` 行：现实中发生了而模拟缺失的内容；
- 同一行同时出现在 `+` 和 `-` 里代表**纯顺序差异**（事件相同、先后不同），不是增删；当未匹配行作为多重集完全相同时，日志会直接标注 `(order change only)`，聊天里也会给一行 `（纯顺序差异——事件相同、顺序不同）`；
- 结果按差异内容聚合计数，如 `50 次 [P#3]:` 后跟该结果的差异明细；与参照完全一致的次数显示在汇总行（定时模式下参照为基线模拟，见[定时模式](#定时模式多刻模拟)）。

方向性测试中任何非空 diff 都说明装置在该变换下不对称；位置性测试中的 diff 说明存在位置哈希或区块边界敏感（可用 `chunkborder` 策略专门探测边界效应）。

### 监测排错

- "No monitor subscribed under the selected wool colour near the operation; place wool next to your redstone components or register a /fstest targets marker." → 颜色筛选器把羊毛/目标过滤掉了（`/fstest color all` 或换成对应色）；或组件订阅位置没放羊毛（见上表）、组件上也没注册 target；或在快照半径之外（调大 `/fstest range`，但保持装置所需的最小值——见[快照半径与性能](#快照半径与性能先看这里)）。若已注册目标，执行 `/fstest targets query`：被标注"（被当前颜色筛选排除）"的就是被筛掉的。
- "Simulator distortion detected! ... baseline differences ..." → 装置里有 v1 模拟器没覆盖到的东西（光敏元件、依赖实体的部分、爆炸、铁轨/栅栏的活塞搬移形状等）。看 `fstest-logs/` 里对应文件，基线 diff 行就是排查起点。

## 0.4.1 变更

**修复**
- **定时模式会因选区外的操作而触发**。0.4.0 的选区门禁只对「带命中位置」的操作生效。客户端在服务端对某次方块交互返回 `PASS` 时，会兜底改发一次「空中使用物品」，而这条路径没有命中位置，于是整个绕过了门禁。给台阶充水就是最常见的触发方式：楼梯既不能被水替换、也不是液体容器，水桶于是去问被点击面的相邻方块，若那个格子也被挡住，整次交互就返回 `PASS`，客户端把这次点击改发成普通使用物品，水桶随即在任何位置放下水并触发模拟。现在门禁改为判定操作所锚定的位置（点到的方块；空中使用则取玩家自身位置），所有入口都被覆盖，选区外的操作重新被忽略，与文档一致。仍需注意：玩家**站在**选区内、但把方块放在选区**之外**时依然会触发——门禁判定的是这次操作，而不是它最终落下的方块。

## 0.4.0 变更

**新增**
- **选区可视化** —— 已注册的测试选区直接画在世界里：当前范围内的那个是绿色线框加淡填充，其余是灰色线框。仅在单人游戏内生效（远程服务器不会把选区同步给客户端）。

**变更**
- **`both`/`pd` 只跑 PD 组**。此前会连跑 D 组、P 组和 PD 组，一开就是三倍的工作量。三种测试模式现在是互斥的。
- **定时模式的触发条件放宽**。设定了命名选区时，玩家对选区内任意方块的操作都会触发模拟，只要选区内至少有一个被监视方块。此前要求操作**同步地**影响到被监视方块，这把「效果要过几个刻才显现」的操作（按钮、接入延迟的元件）全排除在外——而那恰恰是定时模式存在的意义。选区之外的操作直接忽略。瞬时模式不变。现实侧没有录到瞬时事件时会跳过基线自检（没有可比对的流），各轮仍与基线的多刻流比对。

**修复**
- 定时模式在大 P/PD 偏移下会耗尽堆内存：它会去强载「上一轮待清空的盒子」与「新盒子」的轴对齐并集，在全维度范围的偏移下那是几十亿个区块。现在只加载新盒子，超过 1024 区块的区域直接拒绝。
- 定时各轮不再累积区块：远处盒子清空后立即释放，且释放真的会卸载（卸载只发生在区块映射的 tick 里，而旧的释放路径从不调用它），内存因而能在批次进行中和结束后被回收。
- 单轮开销：promotion 泵动每次迭代都给服务端任务循环重新发放一次 50ms 预算，该预算被迭代次数乘放大后主导了每轮耗时。预算改为 2ms，ticking 余量从 2 个区块降为 1 个区块，每箱 9 块（原 25）。
- `/fstest mtrarea add` 总是把第二个角点存成你输入的值，当那个角点恰好是较小角时，选区会塌成一格。现在两个角都从原始参数读取，两角重合则直接拒绝。
- `/fstest mtrarea add` 打印的是带 `%s` 占位符的原始模板（4 个占位符，只传了 3 个参数）。
- 聊天里的完整日志路径行原先使用 `open_file` 点击动作，网络层的封包编解码会拒绝它——整个 `system_chat` 包编码失败，消息根本发不出去。

## 版本与限制

- **性能 / 快照半径**：快照会读取锚点周围 `(2·range+1)³` 的整个立方体，每一轮重放都要重写其中的非空气方块——成本随 `range³` 增长，再乘以轮数，且**同步跑在服务端线程**上。务必把 `/fstest range` 设为刚好覆盖装置及其级联范围，并先调小次数再测试；默认的 `unlimited`（=48）配合默认次数会卡服。详见[快照半径与性能](#快照半径与性能先看这里)。
- 当前为 v1 单版本构建（MC 1.21.11）；后续经 Stonecutter 扩展 1.19.4 / 1.21.10 / 26.x 并建立黄金回放回归用例。
- 瞬时模式：光照以中性常量近似（天空 15 / 方块 0）。定时模式：真实的空虚世界（天空光 15、无真实阴影、生物群系 the_void）。两种模式下光敏元件（阳光探测器等）结果都仅供参考——分歧会以基线失真呈现。
- 实体、爆炸、掉落物不在模拟窗口内；依赖它们的操作会以基线失真告警呈现。
- 移除侧副作用（`affectNeighborsAfterRemoval`，例如红石线被拆、或活板门使其支撑失效时广播自身功率变化）**无法在模拟空间里直接调用**（该钩子签名要求 `ServerLevel`）。改为按"每次移除"捕获真实钩子发出的派发，并在模拟空间里**于其 setBlock 流程中 vanilla 运行该钩子的同一位置**重新发出——相对顺序与级联上下文因此保持一致，之后由模拟自身的邻居级联接管。操作直接造成的移除与邻居级联内部产生的移除都适用。残留：若某钩子不只是派发、还会改动方块状态，则无法复现；1.21.11 的红石元件（红石线、铁轨、火把、二极管、拉杆、按钮、压力板、观察者、活塞）的移除钩子都只做派发。
- 方块实体的移除副作用（`BlockEntity#preRemoveSideEffects`）**有意不模拟**。其默认实现是掉落容器内容（生成掉落物实体）；覆写还包括营火/讲台/唱片机/潜影盒/熔炉掉落内容、被拆的幽匿尖啸体尖叫（game event）、以及活塞移动方块的收尾（`PistonMovingBlockEntity#finalTick`）。实体、掉落物与 game event 按规划均在窗口外，且它们都不产生被记录的事件类型，所以当前不影响 diff；写在这里是为了将来若有测试依赖它们，不会被误当成模拟器 bug。
- 操作窗口内计划刻只记录不执行（与原版同步处理语义一致）；创建尝试会作为事件参与对比。重放无法复现的“操作处理器直接排定的计划刻”（如按钮的弹起）会回填进模拟；方块事件创建**不回填**——模拟必须从重放的更新中自然产生它们，缺了就是真实的失真信号。
- 模拟空间按规范有意**无视世界高度限制**：垂直 P 偏移可能把快照区域边缘放到 `[minY, maxY]` 之外，这些方块仍然保留且可读。若在这里强制建造高度，线下方的支撑方块或活塞上方的末地烛/羊毛会静默变成虚空空气，导致该轮零事件的假失真。
- 配置持久化（含 targets、测试选区与范围选择）、按标记分组输出、可点击日志路径、每轮/整次分析耗时统计，以及世界内选区可视化均已落地（可视化见 0.4.0）；JSONL 机器输出、精确光照、`hashdelta` 与回归测试仍是 v2 规划。

## 构建

需要 JDK 21：

```bash
./gradlew compileJava   # 或 ./gradlew build 产出可安装 jar
```

## 致谢

fstest 的开发全程参考了以下开源项目；构建出的 jar 未打包其中任何源代码。

- **[Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition)** — 作者 TISUnion，主要维护者 Fallen_Breath — **LGPL-3.0**。采集层复刻其*微时序*（microTiming）的订阅规则与事件语义（羊毛/末地烛标记、方块更新子类型、创建成功标志、比较器更新）。
- **[fabric-carpet](https://github.com/gnembon/fabric-carpet)** — 作者 gnembon — **MIT**。fstest 本身是 Carpet 扩展，沿用其扩展 API、mixin 约定与命令权限等级（2，与 `/log` 一致）。
- **[MicroTimingReplay (MTIR)](https://github.com/hotpad100c/microtimingreplay)** — 作者 Ryan100C（hotpad100c）— **MIT**。参考其微时序事件的记录与逐步回放设计。
- **[simulatica](https://github.com/hotpad100c/simulatica)** — 作者 Ryan100C（hotpad100c）— **MIT**。参考其隔离式世界内模拟的思路。
- **[Ticker](https://github.com/hotpad100c/ticker)** — 作者 Ryan100C（hotpad100c）— **CC0-1.0**。参考其用命令注入方块事件、计划刻与世界/游戏事件的调试工具。

开发时使用的参考仓库（`Carpet-TIS-Addition/`、`fabric-carpet/`、`microtimingreplay/`、`simulatica/`、`ticker/`）不属于本仓库。

## 许可

LGPL-3.0-only。采集层的逻辑模型复刻自 [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition) 的微时序监测器（相同协议保持兼容），未包含也未链接其任何源代码。
