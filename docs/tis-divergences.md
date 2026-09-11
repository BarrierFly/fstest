# fstest 与 TIS 微时序（microTiming）的差异清单

- **基准**：Carpet-TIS-Addition **1.80.0** @ Minecraft **1.21.11**（工作区 `Carpet-TIS-Addition/` 源码）
- **对比对象**：fstest **0.1.14** 的采集层（`fstest/record`、`fstest/mixin`）
- **范围**：只列采集层语义差异；fstest 特有的部分（模拟空间、变换重放、diff 输出）不在 TIS 中存在，不属"差异"。
- 每条标注：**[有意简化]**（v1 范围决策，README/规划已声明）、**[待对齐]**（将来应对齐的缺口）或 **[已对齐]**（注明对齐版本）。

## 一、已对齐的关键语义（作为基准，非差异）

| 语义 | 双方一致的行为 |
|---|---|
| NU（邻居更新）事件 | **发送方语义**：事件属于"发出派发的方块"，挂在派发源坐标；挂点同为 `ServerLevel.updateNeighborsAt(3参)` / `updateNeighborsAtExceptFromFacing` / `neighborChanged`（简单与全形式）（fstest：`ServerLevelDispatchMixin`；TIS：`WorldMixins.BlockUpdateMixin` / `SingleBlockUpdateMixin` / `SingleBlockUpdate2Mixin`） |
| NU 子类型 | 事件签名携带 TIS 的 `BlockUpdateType` 子集：`BLOCK_UPDATE`（BU）/ `BLOCK_UPDATE_EXCEPT`（BU_EXCEPT，附带 except 方向）/ `SINGLE_BLOCK_UPDATE`（SINGLE）；fstest `UpdateKind` 与之同名对应 |
| 比较器输出更新 | `Level.updateNeighbourForOutputSignal` 作为 `COMPARATOR_UPDATE` 事件记录（fstest：`LevelComparatorUpdateMixin`；TIS：`WorldMixins.ComparatorUpdateMixin`）；其后对比较器的全形式 `neighborChanged` 另记一条 `SINGLE_BLOCK_UPDATE`，与 TIS 的两条事件一致 |
| 创建成功/去重标志 | 计划刻与方块事件的创建尝试都带 success：由"队列尺寸是否增长"判定（计划刻看 `LevelChunkTicks` 容器条目数，方块事件看 `ServerLevel.blockEvents`），成功=新条目被接受，失败=等值条目已在队列中被静默去重；与 TIS 的 `onScheduleTileTickEvent(..., success)` / `onScheduleBlockEvent(..., success)` 判定方式相同，且 success 参与事件签名（成功/失败是两条不同事件） |
| NU 订阅 | 仅末地烛规则（羊毛上的末地烛指向该方块），即 TIS `blockUpdateColorGetter`（`MicroTimingUtil.java` `getEndRodWoolColor`）；元件羊毛规则不适用于 NU。另有 `/fstest targets` 直接标注方块位置（见差异 #7/#8） |
| BC/计划刻/BE 订阅 | 元件羊毛规则优先、末地烛规则兜底，即 TIS `defaultColorGetter` 前两级（`MicroTimingUtil.java` `getWoolColor` → `getEndRodWoolColor`）；`/fstest targets` 目标位置直接订阅、跳过羊毛规则 |
| 末地烛羊毛几何 | 羊毛在末地烛指向反方向一格（"正后方"） |
| 服务端单事件 | 服务端 updater 为 Collecting（非 instant），TIS 走 `onScheduleBlockUpdate` 单事件分支；fstest 同为单事件 |

## 二、采集层差异

### 1. 比较器输出更新 [已对齐 0.1.9]
`LevelComparatorUpdateMixin`（`@Mixin(Level.class)`）在 `updateNeighbourForOutputSignal` HEAD 记录 `COMPARATOR_UPDATE` 事件；因挂在 `Level` 上，模拟空间继承同一实现，两侧天然对称。全形式 `neighborChanged` 自 0.1.9 起也按 TIS 记为 `SINGLE_BLOCK_UPDATE`（此前只作重放捕获）。
**备注**：TIS 在 instant 模式下会输出 ACTION_START/ACTION_END 两条；fstest 服务端语义下始终单事件，与 TIS 的服务端分支一致。

### 2. 派发类型细分 [已对齐 0.1.9]
新增 `fstest.record.UpdateKind`（`BLOCK_UPDATE` / `BLOCK_UPDATE_EXCEPT` / `COMPARATOR_UPDATE` / `SINGLE_BLOCK_UPDATE`，与 TIS `BlockUpdateType` 同名），`FstEvent.NeighborUpdate` 签名变为 `NU|<KIND>[|except:<方向>]|<方块>`，EXCEPT 变体携带被打点方向且参与逆变换 canonicalize。
**未覆盖**：TIS 的 `STATE_UPDATE` / `SINGLE_STATE_UPDATE`（形状更新）仍不采集——形状更新不属于本测试器 diff 的微时序事件（见差异 #6 的窗口语义）。

### 3. 计划刻去重结果 [已对齐 0.1.9]
真实侧 `LevelTicksScheduleMixin` 在 `LevelTicks.schedule` HEAD/RETURN 比较目标区块容器 `LevelChunkTicks` 的条目数，增量即 success；模拟侧 `SimTickQueue.schedule` 直接取去重集合 `add` 的布尔结果（此前命中去重会提前 return，连事件都不记录——作为不对称 bug 一并修复）。事件签名形如 `ST+<delay>|<priority>|<block>|ok|dup`。

### 4. 方块事件（BE）去重结果 [已对齐 0.1.9]
真实侧 `ServerLevelBlockEventMixin` 改为 HEAD 记队列大小、RETURN 比较 `ServerLevel.blockEvents` 尺寸（1.21.11 的 `blockEvent` 返回 void，只能如此判定，与 TIS 相同）；模拟侧 `FstestSimWorld.blockEvent` 取 `LinkedHashSet.add` 的布尔结果。事件签名形如 `BE|<type>,<data>|<block>|ok|dup`。

### 5. BC 事件粒度粗于 TIS [有意简化，可升级]
TIS 区分 `BlockStateChangeEvent`（同方块属性变更：逐 property 差异列表 + setBlock 返回值）与 `BlockReplaceEvent`（方块替换），并携带 returnValue。fstest 统一为 `BlockChange(old → new, flags)`，不区分两类、无返回值、无属性级拆分。
**影响**：fstest 日志信息量足够 diff，但不如 TIS 便于人读属性级变化。

### 6. BE/计划刻的执行事件不记录 [有意简化——规划 §五]
TIS 记录 `ExecuteBlockEventEvent`（`doBlockEvent` HEAD/RETURN，含 returnValue/failInfo）与 `ExecuteTileTickEvent`。fstest 按规划第五节"创建即记录、本轮一律不执行"，且执行发生在采集窗口（操作同步处理过程）之外。
**影响**：fstest 不输出执行侧事件；对齐窗口语义后（如 v2 多 tick 模拟）需重新评估。

### 7. 订阅 fallback 与 microTimingTarget [有意简化]
TIS `defaultColorGetter` 兜底链：wool → end-rod → **microTimingTarget**（`IN_RANGE`：更新半径内有玩家即记录；`ALL`：全部记录；`MARKER_ONLY`）。fstest 以 wool 颜色过滤（`/fstest color`）为主，另可用 `/fstest targets add <颜色> <方块坐标>` 直接在**元件方块位置**注册目标：该位置同时订阅元件通道（BC/计划刻/BE）与更新通道（该方块自身的派发），完全不查羊毛几何；但无 IN_RANGE/ALL/MARKER_ONLY 模式。
**影响**：未放羊毛/末地烛、也未注册 target 的位置在 TIS 的 IN_RANGE/ALL 下有记录，在 fstest 中静默。

### 8. marker 系统未实现 [有意简化]
TIS 有 `MicroTimingMarkerManager`（带 `REGULAR` / `END_ROD` 类型区分的 marker，`MARKER_ONLY` 模式下作为主订阅源）。fstest 的 `/fstest targets` 是"方块位置→颜色"的直接订阅，等价于同时给该位置挂上元件通道与更新通道，但没有 TIS marker 的类型区分、命名与移动/生命周期语义，两者不等价。

### 9. 区块就绪检查缺失 [待对齐（仅真实侧健壮性）]
TIS `MicroTimingUtil.isPositionAvailable`（ticking future ready）先于订阅判定。fstest 未检查（真实侧窗口内区块必然加载；模拟空间无区块概念）。
**影响**：目前无实际差异，仅记录设计差异。

### 10. 事件树/作用域模型 [有意简化]
TIS 事件为 Phase → Queue → Update 树（作用域节点 push/pop，含剪枝）。fstest 为平铺事件流（seq 排序 + 写日志时按键去重折叠 ×N）。
**影响**：fstest 无法表达"某次 setBlock 触发的更新子树"的从属关系；diff 依赖顺序对齐（LCS）。

### 11. 其余 TIS 事件类型未实现 [有意简化——v1 范围]
活塞推动结构解析（`onPistonComputePushStructureEvent`）、实体/玩家 tick、漏斗传输、振动系统、随机 tick 等 Phase 事件，fstest 均无。

### 12. 客户端渲染 [有意简化]
TIS 有客户端 box+text 渲染与红石石板；fstest 仅输出聊天摘要 + 文本日志（`fstest-logs/`）。

### 13. 输出过滤开关（fstest 特有）[非语义差异]
`/fstest updates <on|off>` 与 `/fstest duplications <on|off>` 只在**写入事件流**处过滤：`updates off` 丢掉 `NU` 派发事件，`duplications off` 丢掉 success=false 的 `ST`/`BE` 条目。两侧（真实/模拟）用同一开关，采集、尝试与重放捕获不受影响——重放所需的派发仍在 `RecorderHub` 里被捕获，重复创建仍照常尝试，计划刻核对也照常执行。TIS 无对应开关，属 fstest 的观测范围配置，不影响语义保真。

## 三、模拟器保真度差异（与现实的差异，影响 diff 可比性）

这些不是"与 TIS 的差异"，但同样限制结果解读，随文档一并记录：

1. **`affectNeighborsAfterRemoval` 不直接调用，改由"就地重放捕获的派发"重建**：vanilla 该方法签名要求 `ServerLevel`（`BlockBehaviour.BlockStateBase.affectNeighborsAfterRemoval(ServerLevel, BlockPos, boolean)`），无区块的模拟世界无法充当；也不能让 `instanceof ServerLevel` 通过（继承 ServerLevel 会接上真实存档与区块生成，违反隔离；无构造实例化则字段为空、任何未覆写调用都会 NPE）。
   - 实现：真实侧在 `LevelChunk.setBlockState` 的调用点开"移除窗口"（`LevelChunkRemovalWindowMixin`），把钩子自身的派发按移除位置收集为 `RemovalSideEffect`；`ReplayEngine` 把它们变换进模拟空间，`FstestSimWorld` 在自身 setBlock 流程中 vanilla 运行该钩子的同一位置（存状态与 BE 拆除之后、`onPlace` 之前）原序重发，之后交给模拟自身的级联。
   - 时机等价性依据：1.19+ 的更新链由 `CollectingNeighborUpdater` 手工栈控制——`addAndRun` 在 `count>0` 时只把条目放进 `addedThisLayer`，等当前 `runNext` 返回后按层压栈执行；`count==0` 时则立即执行并排空整条级联。因此在"同一次调用序列、同一调用点、同样的 count>0 与否"下重发，派发进入手工栈的位置与相对顺序都与真实一致。捕获时记录入口忙碌状态（`RemovalSideEffect.cascadeBusy`），模拟重发时比对自身 `count>0`，不一致则计数并在该轮 stats 中显示 `sideEffectCtxMismatch`（意味着该移除在模拟中的级联上下文已先偏离，时序不再保证）。
   - 窗口同时记录入口处的级联状态：入口空闲（操作直接移除）只捕钩子自身派发，其后代派发由模拟再生；入口忙碌（级联内移除，如活板门使红石线失效：`updateShape` → `updateOrDestroy` → `destroyBlock`）则捕该 setBlock 深度上的全部派发——级联忙碌时 `CollectingNeighborUpdater.addAndRun` 只入队不执行，窗口期内不会有后代派发混入。
   - 残留：若某方块移除钩子不只派发、还改动方块状态，则无法复现（1.21.11 的红石元件均为纯派发，已核对）。
2. **`preRemoveSideEffects` 有意不模拟**：签名不要求 `ServerLevel`（`BlockEntity#preRemoveSideEffects(BlockPos, BlockState)`），模拟本可调用，但其默认/覆写行为是掉落容器内容（生成实体）、幽匿尖啸体尖叫（game event）、活塞移动方块收尾（`PistonMovingBlockEntity.finalTick`）等——实体/掉落物/game event 按规划 §七均在窗口外，且都不产生被记录的事件类型。当前不影响 diff，仅记录在案。
3. **光照为常量 stub**：天空光 15 / 无 BlockLight，光敏元件结果仅供参考。
4. **创建不执行**：窗口内的计划刻/方块事件只记录不执行（规划 §五）；方块事件执行（活塞推动等）发生在现实的下一 tick，本就在采集窗口之外。
5. **无实体、无粒子/音效发包、gameEvent 抑制、统计不写入**（隔离要求，规划 §七）。
6. **方块实体冻结快照只读不 tick**（比较器读容器等行为依赖冻结 NBT）。

## 四、建议的后续对齐顺序

1. ~~派发子类型 + except 方向进事件签名（差异 #2）~~ —— 0.1.9 已对齐。
2. ~~计划刻/BE 的去重标注（差异 #3、#4）~~ —— 0.1.9 已对齐。
3. ~~比较器输出更新（差异 #1）~~ —— 0.1.9 已对齐。
4. BC 属性级拆分与 returnValue（差异 #5）：`BlockStateChangeEvent` / `BlockReplaceEvent` 分离 + setBlock 返回值，纯信息量提升，不影响 diff。
5. 形状更新事件（`STATE_UPDATE` / `SINGLE_STATE_UPDATE`）：需先确认它在方向性/位置性判定中是否真有价值，再决定是否纳入。
6. 订阅 fallback（差异 #7）与 marker 系统（差异 #8）：属于 TIS 的观测范围配置，非语义保真问题，优先级最低。
