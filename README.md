# Fengshui Tester (fstest)

A [Fabric](https://fabricmc.net/) / [Carpet](https://github.com/gnembon/fabric-carpet) extension mod that tests redstone contraptions for **directionality** (do rotation/mirror change behaviour?) and **positionality** (does translation change behaviour?), by replaying accepted player operations inside an isolated simulated space and diffing micro-timing style event streams against the real world.

中文说明见下方 [风水测试器（中文）](#风水测试器中文)。

## How it works

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
| `/fstest range <unlimited\|r>` | Snapshot radius cap (r 1..128). Default `unlimited` (= effective 48 in v1). |
| `/fstest pstrategy <uniform\|chunkborder\|hashdelta>` | Offset sampling for P/PD. `hashdelta` is planned for v2. |
| `/fstest updates <on\|off>` | Record block-update dispatch events (`NU`) or leave them out of the streams. Default `on`. |
| `/fstest duplications <on\|off>` | Record creation attempts that were rejected as duplicates (the `dup` entries) or leave only the successful ones. Default `on`. |
| `/fstest targets add <color> <x> <y> <z>` | Mark a block position directly (see below). Re-adding the same position recolours it. |
| `/fstest targets remove <x> <y> <z>` | Remove one target. |
| `/fstest targets remove color <color>` | Remove every target of that colour. |
| `/fstest targets clear` | Remove all targets. |
| `/fstest targets query` | List the manually registered targets only (placed wool is not listed). |
| `/fstest query` | Show current configuration. |

A subcommand invoked without its required arguments prints that subcommand's own usage line instead of Brigadier's generic tree help; bare `/fstest` prints a one-line index of the subcommands.

Settings are sticky in memory; they fall back to the initial values (and the target list is emptied) after a restart (persistence is tracked in [`docs/v2-todo.md`](docs/v2-todo.md)).

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

1. **Player chat** — multi-line coloured report. The header is gold, the per-outcome labels are yellow, the summary is grey, all-match is green, baseline distortion is dark red. A dark-grey line at the end shows the path to the on-disk log.
2. **Server console / `logs/latest.log`** — the same lines, prefixed with `[fstest]`. INFO for normal runs, WARN for baseline distortion, ERROR for analysis crashes. This is the persistent human record; the chat line scrolls away.
3. **Per-analysis text file under `<run-dir>/fstest-logs/`** — the full record. Filenames look like `2026-08-26_19-45-12-345_USE_ITEM_ON_BLOCK_at_3_64_-2_Player1.txt`. The file holds, in order:
   - a header with timestamp, player, operation, anchor, dimension, game time, the full config snapshot (mode, colour filter, counts, range, strategy, `updates`, `duplications`, registered-target count), the real-stream event count and the raw tick/block-event creation-attempt counts with how many of them made it into the stream (attempts include duplicates even when `duplications` is off), and the baseline self-check result;
   - the **real stream** of events (deduplicated, with `×N` suffixes on consecutive identical `pos | signature` lines);
   - the **baseline** stream (identity transform) and its diff vs the real stream;
   - **every simulation run** in the configured order — in D modes the identity baseline is the first entry, followed by the D set (per non-identity symmetry × `count_d`), then the P set (`count_p` runs), then the PD set (`count_pd` runs) — each with its event list, a one-line replay diagnostic (roots applied, setBlock calls, neighbour-update dispatches, tick/event creation attempts, the sampled offset, and - when non-empty - `sideEffectCtxMismatch N @ x y z ...`, which lists the removals whose side-effect replay landed in a different cascade context than reality's, so their relative timing is not guaranteed), and its diff vs the real stream. A diff whose unmatched lines are identical as a multiset is labelled `(order change only)` instead of being shown as extras/missing;
   - the **aggregated summary**: total simulations, count that matched exactly, count of distinct outcomes, and for each outcome `=== Nx [label(s)] ===` (with an `(order change only ...)` note when applicable) followed by the sample `+`/`-` lines.

While the analysis runs, chat and console get a grey start line (`N replay(s) planned`) and then progress every `max(10%, 50)` replays; the final report follows.

`run-dir` is the working directory of the Minecraft JVM (e.g. `.minecraft/` for a client, the server root for dedicated). v2 will add a JSONL variant and `/fstest` commands to list/grep previous runs.

### Reading the report

- `+` lines: the simulation produced something reality did not (sim extra).
- `-` lines: reality produced something the simulation missed (sim missing).
- **The same line appearing in both `+` and `-` is an ORDER change**, not a missing/extra event: the same update was received (or the same block event created) at a different point of the cascade. Vanilla's redstone wire evaluator notifies its surroundings while iterating a `HashSet` of positions, so the delivery order legitimately depends on the device's absolute coordinates — a different transform (D) or offset (P) samples a different order. This is real position/orientation-dependent behaviour, exactly what the tester measures; the 0° baseline matching proves the simulator itself reproduces it faithfully. When the unmatched lines are identical as a multiset, the tester now says so directly: the log labels the diff `(order change only)` and chat prints an `(order change only - the same events in a different order)` note, so it is never mistaken for an extra/missing event.
- **Different event counts between a run and reality** (e.g. 14 piston block-event creations vs 15) are the same phenomenon one step further: the wire cascade unrolls differently at the sampled coordinates, producing a different number of power-change rounds and therefore a different number of notifications. Real physics, not a simulator fault.
- Runs are aggregated by distinct outcome: e.g. `50x [P#3]:` followed by that outcome's diff lines; runs matching reality exactly are counted in the summary line.

For directionality testing, any non-empty diff means the contraption is **not** symmetric under that transform. For positionality, diffs indicate position-hash / chunk-border sensitivity (use `chunkborder` strategy to probe borders deliberately).

#### Monitoring troubleshooting

- "No monitor subscribed under the selected wool colour near the operation; place wool next to your redstone components or register a /fstest targets marker." → the colour filter excludes your wool/target (`/fstest color all` or match the colour); or there is no wool in the component's subscription position (see the table) and no `/fstest targets` marker on the component; or it is too far from the operation anchor (use `/fstest range unlimited` to take a bigger snapshot). If you registered targets, run `/fstest targets query`: a target annotated "(excluded by the current color filter)" is being filtered out.
- "Simulator distortion detected! ... baseline differences ..." → your contraption uses something the v1 simulator does not cover (light-sensitive components, entities, explosions, piston move-shape effects on rails/fences, etc.). The per-file log under `fstest-logs/` will show which `+`/`-` lines the baseline disagrees on — that is the first thing to fix.

## v1 scope & limitations

- Single version build (MC 1.21.11); Stonecutter multi-version expansion (1.19.4 / 1.21.10 / 26.x) is the next step.
- Lighting is approximated with neutral constants (sky 15, block 0): light-sensitive components (daylight sensors etc.) produce results for reference only.
- Entities, explosions, and block drops are out of the simulation window; operations relying on them will surface as baseline distortion warnings.
- Removal side effects (`affectNeighborsAfterRemoval`, e.g. redstone wire announcing its power change when broken, or when a trapdoor invalidates its support) cannot be *called* inside the simulated space (the hook's signature demands a `ServerLevel`). Instead the dispatches the real hook performs are captured per removal and re-issued in the simulated space **at the same point of its setBlock flow where vanilla would run the hook** - so the relative order and cascade context match, and the simulation's own neighbour cascades take over from there. This covers removals performed directly by the operation and removals produced inside a neighbour cascade alike. Residual: a hook that mutates blocks instead of only dispatching would not be reproduced; among 1.21.11's redstone components (wire, rails, torches, diodes, levers, buttons, plates, observers, pistons) the removal hooks only dispatch.
- Block-entity removal side effects (`BlockEntity#preRemoveSideEffects`) are deliberately not simulated. Its default implementation drops container contents (spawning item entities); overrides drop campfire / lectern / jukebox / shulker box / furnace contents, scream from a removed sculk shrieker (game events), or finalize a moving piston (`PistonMovingBlockEntity#finalTick`). Entities, item drops and game events are out of scope per the plan, and none of these emits the recorded event kinds, so the diff is unaffected today - listed here so that a future test depending on them is not mistaken for a simulator bug.
- Scheduled ticks are recorded but not executed inside the operation's synchronous window - matching vanilla semantics, where queued ticks only run in later tick phases.
- The simulated space deliberately ignores the dimension's build height (per the plan: "the custom virtual world ignores world height limits"): a vertical P offset may place the captured region's edges outside `[minY, maxY]`, and those blocks stay stored and readable. Enforcing the build height there would silently turn supporting ground (or the end rod / wool above) into void air and produce a spurious run with zero events.
- `hashdelta` strategy, JSONL machine output, configuration persistence (including targets), exact light copying, per-marker grouped output, a clickable log path and regression tests are v2 items; the prioritized backlog is [`docs/v2-todo.md`](docs/v2-todo.md).
- A per-analysis text log is written to `<run-dir>/fstest-logs/` (see [Output destinations](#output-destinations)); JSONL is planned for v2.

## Building

```bash
# JDK 21 required
./gradlew compileJava     # or ./gradlew build for the remapped jar
```

The project resolves fabric-carpet through Jitpack mirrors; see `build.gradle`.

## License

LGPL-3.0-only. The collection layer re-implements concepts from [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition)'s microTiming logger under the same license; no source code of that mod is included or linked.

---

# 风水测试器（中文）

一个 Fabric/Carpet 扩展 Mod，用于测试红石装置的**方向性**（旋转/镜像后行为是否一致）与**位置性**（平移后行为是否一致）：把被服务端受理的玩家操作放进隔离的模拟空间重放，再用微时序风格的事件流与现实记录做聚合 diff。

## 工作原理

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
| `/fstest range <unlimited\|r>` | 快照半径上限（r 取 1..128）。默认 `unlimited`（v1 实际按 48 生效）。 |
| `/fstest pstrategy <uniform\|chunkborder\|hashdelta>` | P/PD 的偏移采样策略；`hashdelta` 属 v2 规划。 |
| `/fstest updates <on\|off>` | 是否记录方块更新派发事件（`NU`）。默认 `on`。 |
| `/fstest duplications <on\|off>` | 是否记录因重复被拒的创建尝试（`dup` 条目）。默认 `on`。 |
| `/fstest targets add <颜色> <x> <y> <z>` | 直接标记一个方块位置（见下）。对同一位置重复执行会改色。 |
| `/fstest targets remove <x> <y> <z>` | 移除单个目标。 |
| `/fstest targets remove color <颜色>` | 移除该颜色的全部目标。 |
| `/fstest targets clear` | 移除全部目标。 |
| `/fstest targets query` | 只列出手动注册的目标（放置的羊毛不会出现在这里）。 |
| `/fstest query` | 查询当前全部配置。 |

子命令缺少必需参数时会输出该子命令自己的用法行，而不是 Brigadier 的通用命令树提示；裸 `/fstest` 给出一行子命令索引。

配置粘性保存在内存；重启后回落初始值（目标列表一并清空），持久化登记在 [`docs/v2-todo.md`](docs/v2-todo.md)。

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

1. **玩家聊天栏** — 多行带色报告：标题金色、单次结果标签黄色、汇总行灰色、全部一致时绿色、基线失真时深红色；末尾会有一行深灰显示本次落盘文件的绝对路径。
2. **服务端控制台 / `logs/latest.log`** — 同样的内容加 `[fstest]` 前缀；正常 INFO，基线失真 WARN，分析崩溃 ERROR。这是持久的人眼记录（聊天行会滚走）。
3. **`<运行目录>/fstest-logs/` 下的一次性文本文件** — 完整存档。文件名形如 `2026-08-26_19-45-12-345_USE_ITEM_ON_BLOCK_at_3_64_-2_Player1.txt`。文件按顺序包含：
   - 头信息：时间戳、玩家、操作类型、锚点坐标、维度、游戏时间、完整配置快照（模式、颜色筛选、次数、范围、策略、`updates`、`duplications`、目标数）、现实流事件数、计划刻/方块事件的**创建尝试**总数及其中**进入事件流的条数**（尝试数含重复项，即使 `duplications` 为 off）以及基线自检结果；
   - **现实流**（去重后输出，相同 `坐标 | 签名` 折叠为 `×N` 计数行）；
   - **基线**（identity 变换）事件流及其与现实的 diff；
   - **每一次模拟** 的事件流及其与现实的 diff：D 类模式下第一条即 identity 基线，其后是 D 集（每个非 identity 对称 × `count_d`），再 P 集（`count_p` 次），再 PD 集（`count_pd` 次）；每轮附一行重放诊断（生效根操作数、setBlock 次数、邻居更新派发数、计划刻/方块事件创建尝试数、采样偏移；非空时还有 `sideEffectCtxMismatch N @ x y z …`——列出那些副作用重放落在了与真实不同级联上下文里的移除坐标，其相对时序不再有保证）；
   - 若某轮 diff 的未匹配行作为多重集完全相同，则该轮 diff 标注为 `(order change only)`，不再当作增删；
   - **聚合汇总**：模拟总数、与现实完全一致的次数、不同结果的种类数，每种结果以 `=== Nx [标签] ===`（必要时附 `(order change only …)` 说明）形式给出样本 `+`/`-` 行。

测试进行期间，聊天栏与控制台会先给一行灰色开始提示（`计划重放 N 轮`），之后每完成 `max(10%, 50)` 轮给一次进度，最后输出完整报告。

`运行目录` 是 Minecraft 启动时 JVM 的当前工作目录（客户端一般是 `.minecraft/`，专用服务器就是服务端根目录）。v2 会增加 JSONL 变体和 `/fstest` 子命令来浏览历史结果。

## 输出解读

- `+` 行：模拟产生了现实中没有的内容；
- `-` 行：现实中发生了而模拟缺失的内容；
- 同一行同时出现在 `+` 和 `-` 里代表**纯顺序差异**（事件相同、先后不同），不是增删；当未匹配行作为多重集完全相同时，日志会直接标注 `(order change only)`，聊天里也会给一行 `（纯顺序差异——事件相同、顺序不同）`；
- 结果按差异内容聚合计数，如 `50 次 [P#3]:` 后跟该结果的差异明细；与现实完全一致的次数显示在汇总行。

方向性测试中任何非空 diff 都说明装置在该变换下不对称；位置性测试中的 diff 说明存在位置哈希或区块边界敏感（可用 `chunkborder` 策略专门探测边界效应）。

### 监测排错

- "No monitor subscribed under the selected wool colour near the operation; place wool next to your redstone components or register a /fstest targets marker." → 颜色筛选器把羊毛/目标过滤掉了（`/fstest color all` 或换成对应色）；或组件订阅位置没放羊毛（见上表）、组件上也没注册 target；或距离操作点太远（用 `/fstest range unlimited` 扩大快照半径）。若已注册目标，执行 `/fstest targets query`：被标注"（被当前颜色筛选排除）"的就是被筛掉的。
- "Simulator distortion detected! ... baseline differences ..." → 装置里有 v1 模拟器没覆盖到的东西（光敏元件、依赖实体的部分、爆炸、铁轨/栅栏的活塞搬移形状等）。看 `fstest-logs/` 里对应文件，基线 diff 行就是排查起点。

## 版本与限制

- 当前为 v1 单版本构建（MC 1.21.11）；后续经 Stonecutter 扩展 1.19.4 / 1.21.10 / 26.x 并建立黄金回放回归用例。
- 光照以中性常量近似（天空 15 / 方块 0），光敏元件结果仅供参考（v2 精确复制光照）。
- 实体、爆炸、掉落物不在模拟窗口内；依赖它们的操作会以基线失真告警呈现。
- 移除侧副作用（`affectNeighborsAfterRemoval`，例如红石线被拆、或活板门使其支撑失效时广播自身功率变化）**无法在模拟空间里直接调用**（该钩子签名要求 `ServerLevel`）。改为按"每次移除"捕获真实钩子发出的派发，并在模拟空间里**于其 setBlock 流程中 vanilla 运行该钩子的同一位置**重新发出——相对顺序与级联上下文因此保持一致，之后由模拟自身的邻居级联接管。操作直接造成的移除与邻居级联内部产生的移除都适用。残留：若某钩子不只是派发、还会改动方块状态，则无法复现；1.21.11 的红石元件（红石线、铁轨、火把、二极管、拉杆、按钮、压力板、观察者、活塞）的移除钩子都只做派发。
- 方块实体的移除副作用（`BlockEntity#preRemoveSideEffects`）**有意不模拟**。其默认实现是掉落容器内容（生成掉落物实体）；覆写还包括营火/讲台/唱片机/潜影盒/熔炉掉落内容、被拆的幽匿尖啸体尖叫（game event）、以及活塞移动方块的收尾（`PistonMovingBlockEntity#finalTick`）。实体、掉落物与 game event 按规划均在窗口外，且它们都不产生被记录的事件类型，所以当前不影响 diff；写在这里是为了将来若有测试依赖它们，不会被误当成模拟器 bug。
- 操作窗口内计划刻只记录不执行（与原版同步处理语义一致）；创建尝试会作为事件参与对比。重放无法复现的“操作处理器直接排定的计划刻”（如按钮的弹起）会回填进模拟；方块事件创建**不回填**——模拟必须从重放的更新中自然产生它们，缺了就是真实的失真信号。
- 模拟空间按规范有意**无视世界高度限制**：垂直 P 偏移可能把快照区域边缘放到 `[minY, maxY]` 之外，这些方块仍然保留且可读。若在这里强制建造高度，线下方的支撑方块或活塞上方的末地烛/羊毛会静默变成虚空空气，导致该轮零事件的假失真。
- v1 已落地每分析一次的纯文本日志（`fstest-logs/`）；v2 计划增加 JSONL 变体、`/fstest` 历史浏览命令、配置持久化（含 targets）、精确光照、`hashdelta`、按标记分组输出、可点击日志路径与回归测试——按优先级整理在 [`docs/v2-todo.md`](docs/v2-todo.md)。

## 构建

需要 JDK 21：

```bash
./gradlew compileJava   # 或 ./gradlew build 产出可安装 jar
```

## 许可

LGPL-3.0-only。采集层的逻辑模型复刻自 [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition) 的微时序监测器（相同协议保持兼容），未包含也未链接其任何源代码。
