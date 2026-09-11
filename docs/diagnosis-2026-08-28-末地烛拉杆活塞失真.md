# 诊断报告 — 末地烛/拉杆/活塞 装置的 baseline 失真

- **日期**：2026-08-28
- **现场日志**：
  - `logs/2026-08-28_19-18-15-563_USE_ITEM_ON_BLOCK_at_-30_56_-74__Afferent_Neuron.txt`
  - `logs/2026-08-28_19-18-20-589_USE_ITEM_ON_BLOCK_at_-30_56_-74__Afferent_Neuron.txt`
- **装置（用户口述）**：拉杆驱动 → 红石线链 → 活塞；末地烛插在羊毛上对着活塞用来监测微时序
- **监测点**：羊毛放在红石粉下方（`-32, 56, -72`）；红石粉在中继器/拉杆链上的某一段（`minecraft:redstone_wire` 在 `-32, 56, -71`）
- **目标行为**：玩家拉一下拉杆 → 拉杆 setBlock 改自己状态 → `updateNeighborsAt` 通知邻居 → **红石粉**收到 NEIGHBOR_CHANGED → 红石粉自己 emit NEIGHBOR_CHANGED 通知**活塞** → 活塞 `neighborChanged` 调 `Level.blockEvent(活塞位置, piston, 1, 3)` → 入队 → **下一 tick** `ServerLevel.runBlockEvents` 执行 `piston.triggerEvent` → 活塞 setBlock 推出

## 关键症状（取自 19:18:20 那次 log）

| 维度 | 现实 | 模拟 baseline | 期望（一致） |
|---|---|---|---|
| `events` 流事件数 | 14 | 15 | 相等 |
| `events` 流中 NU | **14**（全在红石粉 `-32, 56, -71`） | **0** | 都有 |
| `events` 流中 BE | **0** | **15**（全在活塞 `-31, 56, -71`） | 都有 / 都没有 |
| `created events` 计数 | 15 | 15 | 相等 |
| `created ticks` 计数 | 0 | 0 | 相等 |
| `monitored roots` | 1 | — | — |
| `baseline match` | — | **DISTORTION** | MATCH |

> 第一次（19:18:15，开拉杆）只有 2 个 events 全是 NU 形式，第二次（19:18:20，关拉杆）才有完整 14/15 形态。下面只剖析第二次（完整形态）。

**两个完全不对称的位点**：

1. **模拟侧 NU 事件完全消失**（现实 14 个，模拟 0 个）
2. **模拟侧 15 个 BE 全数进了 `events` 流**（现实 0 个 BE 进 `events` 流，只进 `created events`）

## 根因 — 用 vanilla 源码（`_ref/`）逐条对账

### 根因 A：模拟侧 `FstestSimWorld.blockEvent` 覆写版的语义既不是基类也不是服务端

参考（`_ref/net/minecraft/world/level/Level.java:851-853`）：

```java
public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam) {
    this.getBlockState(pos).triggerEvent(this, pos, eventID, eventParam);   // 同步 triggerEvent
}
```

参考（`_ref/net/minecraft/server/level/ServerLevel.java:1250-1253, 1255-1285`）：

```java
@Override
public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam) {
    this.blockEvents.add(new BlockEventData(pos, block, eventID, eventParam));  // 仅入队
}

private void runBlockEvents() { /* 下一 tick 才执行 BlockState.triggerEvent */ }
```

`fstest/sim/FstestSimWorld.java:312-318` 的覆写版：

```java
@Override
public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam) {
    RecorderHub.onBlockEventCreate(this, pos, block, eventID, eventParam);
    this.blockEvents.add(new BlockEventData(pos.immutable(), block, eventID, eventParam));
}
```

既**不同步** `triggerEvent`，也**没有任何东西会消费** `this.blockEvents` 这个死 set（`FstestSimWorld` 没有 `runBlockEvents` 循环，因为 `FstestSimWorld extends Level` 而不是 `ServerLevel`）。结果：

- 现实侧：`ServerLevel.blockEvent` 入队 → 下一 tick 的 `runBlockEvents` → `piston.triggerEvent` → **piston setBlock 推出** → 推出又触发新一轮 NUs → **红石中继器**充能 → 链继续
- 模拟侧：BE 入了一个永远没人读的 set → **piston 永远不会推出** → 推出触发的整条 NU 链（红石中继器/比较器重布）**永远不会发生**

### 根因 B：模拟侧 `FstestSimWorld.setBlockInternal` 是手写版，onPlace 多调一次，affectNeighborsAfterRemoval 跳过

参考（`_ref/Level.java:229-275`）`Level.setBlock` 本身**只**调 `levelChunk.setBlockState(pos, state, flags)`，**不**直接调 `onPlace` / `affectNeighborsAfterRemoval`——这俩是 **`LevelChunk.setBlockState` 内**调的（`_ref/LevelChunk.java:307-348`）：

```java
// LevelChunk.setBlockState L307-348
if (bl3 && blockState.hasBlockEntity() && !state.shouldChangedStateKeepBlockEntity(blockState)) {
    if (!this.level.isClientSide() && bl5) {
        BlockEntity blockEntity = this.level.getBlockEntity(pos);
        if (blockEntity != null) blockEntity.preRemoveSideEffects(pos, blockState);
    }
    this.removeBlockEntity(pos);
}
if ((bl3 || block instanceof BaseRailBlock) && this.level instanceof ServerLevel serverLevel && ((flags & 1) != 0 || bl4)) {
    blockState.affectNeighborsAfterRemoval(serverLevel, pos, bl4);   // <-- vanilla 在这里调
}
if (!this.level.isClientSide() && (flags & 512) == 0) {
    state.onPlace(this.level, pos, blockState, bl4);                  // <-- vanilla 在这里调
}
if (state.hasBlockEntity()) { /* create/update BE */ }
```

`fstest/sim/FstestSimWorld.java:208-310` 的 `setBlockInternal` 自己手写了这一切：
- L244-250：明确注释成"v1 limitation 跳过 `affectNeighborsAfterRemoval`"——**少**一连串 NUs（铁轨/栅栏的搬移形状 NUs）
- L256：`state.onPlace(this, pos, old, movingByPiston);` — **多**调一次 onPlace（和 chunk 调过的路径**不重合**也没问题，但意味着模拟 setBlock 内部 onPlace 路径里的 `blockEvent` 调用 = vanilla 路径 + 多一次 onPlace 路径的 BE 调用 = 多一倍 `blockEvent` 调度）

具体影响：onPlace 路径里调 `Level.blockEvent` 取决于方块类型；红石粉/中继器/比较器/活塞的 onPlace 都**可能**调 blockEvent。模拟侧"多调一次"让 BE 调度比现实多一截。

### 根因 C：`FstestSimWorld` 不是 `ServerLevel`，没有 `runBlockEvents`，所以即便 BE 想执行也没机会

`FstestSimWorld extends Level`（不是 `ServerLevel`）。基类 `Level.blockEvent` 是同步 `triggerEvent`（见根因 A 第一段）。模拟**没有覆写**这个同步行为（它把 `blockEvent` 覆写成只记录+加 set），所以**模拟侧任何"想立即执行 BE"的代码路径都不存在**。

现实侧 `ServerLevel.blockEvent` 入队不执行，要等下一 tick `runBlockEvents`。**两边 BE 行为都是"延迟/不同步执行"**，但**模拟侧连"被调度出来"这层记录都和现实不一致**——因为 onPlace 多调一次、覆写版加了死代码 set。

### 根因 D：`FstestSimWorld` 模拟侧 `NeighborUpdater` 链没产生 NU 事件（baseline 流 0 个 NU 的根因）

`FstestSimWorld` 构造里 `super(...)` 调 `Level` 构造，初始化 `this.neighborUpdater = new CollectingNeighborUpdater(this, maxChainedNeighborUpdates)`（`_ref/Level.java:149`）。`FstestSimWorld` 自己覆写 `updateNeighborsAt` / `neighborChanged` 委托给 `this.neighborUpdater`（`fstest/sim/FstestSimWorld.java:324-352`）。

`FstestSimWorld.setBlockInternal` 的通知阶段 L292-299：

```java
if ((flags & 1) != 0) {
    this.updateNeighborsAt(pos, old.getBlock());
    if (state.hasAnalogOutputSignal()) {
        this.updateNeighbourForOutputSignal(pos, state.getBlock());
    }
}
```

这条路径**应该**会走 `CollectingNeighborUpdater.addAndRun` → `runUpdates` → `MultiNeighborUpdate.runNext` → `NeighborUpdater.executeUpdate` → 调 `blockState.neighborChanged(...)` → 调 `Level.blockEvent`（被 `FstestSimWorld.blockEvent` 覆写版记 BE）。

但 baseline events 流里 0 个 NU——意味着 `NeighborUpdaterEntryMixins` 的 mixin（在 `CollectingNeighborUpdater$FullNeighborUpdate.runNext` / `MultiNeighborUpdate.runNext` 等）**在 `FstestSimWorld` 这条 NU 链上**没捕到任何调用。两个可能：

- (D1) 模拟侧 `updateNeighborsAt` 被调了，`runNext` 也被调了，但**`isSubscribed(pos)` 全部返回 false** → `RecorderHub.onNeighborUpdate` 不进 `session.record(...)` → events 流没 NU。**但 15 个 BE 进了 events 流**意味着这 15 个 BE 位置**确实被 isSubscribed 判定 true**——所以"全 false"和"15 个 true"矛盾
- (D2) 模拟侧 `updateNeighborsAt` 在 setBlock 链里**根本**没被调用过——**完全没进 NU 链**——但 `state.onPlace(this, pos, ...)` 路径（多调的那次 onPlace）调了 `Level.blockEvent` 15 次，每次都进 baseline events 流（因为 caller 传的 pos 是在订阅位附近）。**这条能解释 baseline 0 NU + 15 BE 同时存在**

我倾向 (D2)，但还**没**在游戏内跑诊断输出确认。最快的确认方式见下文"下一步"。

### 根因 E：现实侧 14 个 NU 在红石粉位置全数进 events 流，0 个 BE 进 events 流——这部分行为是对的

- 现实 `ServerLevel.blockEvent` HEAD 被 mixin 钩到 → 15 次 `onBlockEventCreate` → `created events: 15` 正确
- `isSubscribed(活塞位置)` 现实侧 = false（活塞位置不是 wool，订阅位 = 朝向反方向的红石粉位置，红石粉也不是 wool）→ 15 个 BE 都不进 events 流 → 现实 events 流 0 个 BE ✓
- 红石粉位置 `isSubscribed = true`（下方是红羊毛）→ NU 全在红石粉位置 → events 流 14 个 NU（去重成 `×8 east + ×8 north`） ✓

**现实侧行为正确。** 失真完全来自模拟侧。

## 上一轮我哪些诊断是错的

| 错的话 | 错在哪 |
|---|---|
| "末地烛 use 路径 / 同步脉冲设备是已知缺口" | 末地烛是**监测器**不是被监测对象；用户操作的是**拉杆**——我的脑回路直接错位 |
| "拉杆 use 路径触发同步级联，v1 模拟器无法复现" | 拉杆 use 路径在现实是**入队** BE，**不**是同步级联；同步级联是 `setBlock → updateNeighborsAt` 链。这条链**模拟侧应当能复现**（baseline 显示 0 NU 是模拟 setBlock 链的 bug，不是设计缺口）|
| "v1 模拟器没复现 BE 调度后执行，所以不可比" | 这是用"已知缺口"逃避修 bug；BE 调度后**应当**在 `FstestSimWorld` 上同步执行（基类 `Level.blockEvent` 行为就是同步），把它覆写成只记录+加 set 是覆写错了 |
| "`BlockEventCreate` 区分调度 vs 执行" | 不可行——`Level.blockEvent` public 方法在 `ServerLevel` 上既是调度入口（外部代码调）也是执行入口（`runBlockEvents` 内部调 `Level.blockEvent(pos, block, paramA, paramB)` 调 triggerEvent），mixin 层面无法区分 |

## 应做的修复（按优先级）

### Fix 1（必修，v1 内）：`FstestSimWorld.blockEvent` 改回基类行为

```java
// FstestSimWorld.blockEvent 应该：让现实路径的"同步 triggerEvent"在模拟侧也成立
@Override
public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam) {
    RecorderHub.onBlockEventCreate(this, pos, block, eventID, eventParam);  // 保留事件记录
    // 转基类 Level.blockEvent 的行为 = 同步 triggerEvent
    BlockState state = this.getBlockState(pos);
    if (state.is(block)) {
        state.triggerEvent(this, pos, eventID, eventParam);
    }
}
```

副作用：模拟侧 BE 会**同步 triggerEvent**——`piston.triggerEvent` 立刻调 setBlock 推出 → 推出又触发新一轮 NUs → 整条链在模拟里**自然展开**。

**接受这个副作用的代价**：现实 BE 是入队到下一 tick 才执行，模拟 BE 是同步执行——**两边 BE 节拍差一 tick**——但 BE 之后的"setBlock 推出 → NUs"是同步的，这一段**两端一致**。

**别在 fix 里做的事**：
- ❌ 在 `FstestSimWorld.blockEvent` 里模仿 `ServerLevel` 维护一个入队 set + 写一个 `runBlockEvents` 同步版本——这是过设计，v1 走基类同步 triggerEvent 就够
- ❌ `FstestSimWorld.setBlockInternal` L256 删除 `state.onPlace(...)` —— 现实 `levelChunk.setBlockState` L327 也调 onPlace，**手写版保留这行是合理的**

### Fix 2（必修，v1 内）：`FstestSimWorld.setBlockInternal` 实现 `affectNeighborsAfterRemoval` 的基础版

L244-250 当前是空注释。补上 `oldState.affectNeighborsAfterRemoval(this, pos, movingByPiston)`（在 ServerLevel 上是 `serverLevel`，在模拟侧传 `this`）—— 但**模拟侧** `affectNeighborsAfterRemoval` 会调 `this.updateNeighborsAt` 和 `this.updateNeighbourForOutputSignal`——这些**已经**覆写委托给 `this.neighborUpdater`——**应该**会触发 NU 链——但需要在 `affectNeighborsAfterRemoval` 实际执行前**先确认 rootChange 已经记录**（防止 setBlock 链深处再产生 rootChange）。

```java
// 在 FstestSimWorld.setBlockInternal L244-250 处
if ((differentBlock || old.getBlock() instanceof BaseRailBlock)
        && ((flags & 1) != 0 || movingByPiston)) {
    oldState.affectNeighborsAfterRemoval(this, pos, movingByPiston);
}
```

### Fix 3（建议，v1 内可选）：报告层把 `created events` 和 `events` 流 BE 的不一致透明化

`ReportFormatter` 当前已经同时输出 `created events: 15`（双方都有）和 events 流 diff（15 vs 0）——这本身已经是透明化的。**不需要额外改动**——只是要在 README 限制章里说清"现实 BE 是入队到下一 tick 执行，模拟 BE 是同步执行；events 流只反映入队时刻的订阅命中，BE 之后的 setBlock 推出 → NUs 链是同步展开的，两端一致"。

## 下一步 — 验证手段

我倾向**先动手**（Fix 1 + Fix 2），rebuild 一版让用户换 jar 跑一次：
- 期望：baseline 流里出现 14 个 NU 在红石粉位置（和现实一致）+ 0 个 BE 进 events 流（因为 fix 后 `isSubscribed(活塞位置)` 仍为 false）→ baseline 应当 MATCH
- 如果还失真：再针对 Fix 1/2 之外的位置加诊断输出

或者用户希望**先加诊断**——在 `RecorderHub.beforeSetBlock` / `enterSetBlock` / `NeighborUpdaterEntryMixins.runNext` 入口加 `LOGGER.info`，看模拟侧 setBlock 链每一层到底走了什么——这个能确认根因 D 是 D1 还是 D2。

**用户决定**：先动手还是先诊断？
