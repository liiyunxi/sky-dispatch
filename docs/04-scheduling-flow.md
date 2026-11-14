# 最简调度流程：从创建订单到任务完成

> 这一篇是给"想快速理解系统在干什么"的人看的。全程只用一个订单做例子，
> 每一步都标明**谁做的、改了哪张表、对外发什么消息**。

## 0. 一张图看懂全过程

```
   运营/客户端              Java 后端                          Python 算法服务
        │                       │                                   │
   ①  POST /orders              │                                   │
        ├──────────────────────▶│ 写 delivery_order（CREATED）       │
        │◀────── orderNo ───────┤                                   │
        │                       │                                   │
   ②  自动/手动派单              │                                   │
        │                       │ 1. 硬条件筛候选（载重/电量/续航/距离）│
        │                       │ 2. 策略排序取第一名                 │
        │                       │──── POST /api/v1/path/plan ──────▶│ A* 规划
        │                       │◀─── waypoints + distanceM ────────┤
        │                       │ 3. 写 flight_route / flight_waypoint│
        │                       │ 4. 写 dispatch_task（ASSIGNED）     │
        │                       │ 5. 写 dispatch_decision（全部候选） │
        │◀── WS: EVENT 派单 ─────┤ 6. drone→ASSIGNED、order→ASSIGNED  │
        │                       │                                   │
   ③  仿真引擎每秒一拍            │                                   │
        │                       │ 推进位置、扣电量、更新进度           │
        │◀── WS: TELEMETRY ─────┤                                   │
        │                       │──── POST /api/v1/conflict/check ─▶│ 冲突检测
        │◀── WS: CONFLICT ──────┤◀─── conflicts ────────────────────┤
        │                       │ 到达送货点：order→DELIVERED         │
        │                       │ 返航回到机巢：task→COMPLETED        │
        │◀── WS: EVENT 完成 ─────┤ drone→IDLE、累计里程/电量入账        │
        │                       │                                   │
   ④  GET /reports/...csv       │                                   │
        ├──────────────────────▶│ 从 telemetry / event_log 导 CSV     │
```

## 1. 第一步：创建订单

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
        "customerName": "上海市第一人民医院",
        "cargoName": "急救血浆",
        "weightKg": 3.2,
        "priority": "URGENT",
        "pickup":  {"lat": 31.1900, "lon": 121.3000, "address": "虹桥配送站"},
        "dropoff": {"lat": 31.2480, "lon": 121.4520, "address": "市一医院"},
        "expectDeliveryAt": "2026-01-01T12:00:00Z"
      }'
```

发生了什么：

| 步骤 | 代码位置 | 结果 |
| --- | --- | --- |
| 生成订单号 | `OrderService.nextOrderNo()` | `SO202610030001` |
| 落库 | `delivery_order` | `status = CREATED` |
| 记账 | `EventService.orderCreated()` | `event_log` 插入一条，并（提交后）推送 WS `EVENT` |

此时订单在"待派队列"里。队列顺序由 `OrderService.findDispatchable()` 决定：
**先按优先级权重降序（URGENT > HIGH > NORMAL > LOW），再按创建时间升序**。
这条规则保证了急救单不会被普通单堵住。

## 2. 第二步：派单（调度的核心决策）

触发方式有两种：

- **手动**：`POST /api/v1/dispatch/orders/{id}?strategy=BATTERY_FIRST`
- **自动**：仿真引擎每 `autoDispatchIntervalMs`（默认 10 秒）扫一次待派队列

`DispatchService.dispatchOrder()` 里的判断顺序（**顺序不能乱**）：

### 2.1 硬条件筛选（决定"能不能飞"）

对机队里每一架飞机逐条检查，任何一条不满足就淘汰，并**把原因写下来**：

| 检查项 | 淘汰原因示例 |
| --- | --- |
| 状态必须是 `IDLE` | `状态为 IN_FLIGHT，非空闲` |
| 机型载重 ≥ 订单重量 | `载重不足（最大 5.0kg < 需求 8.5kg）` |
| 当前电量 ≥ 安全冗余（默认 25%） | `电量 18.0% 低于安全冗余 25.0%` |
| 到取货点距离 ≤ 搜索半径（默认 10km） | `距取货点 17119m 超出搜索半径 10000m` |
| **全链路可达（含补能）** | `续航不可达：取货后无法抵达送货点（载货需 855Wh，落地取货后最多可用 450Wh，且沿途没有可落脚的补能站）` |

> 最后一条是最容易写错的：无人机送完货还要飞回来。只按单程判断会派出飞不回来的飞机。
> 而且**有了充电桩之后，"电量不够"不再等于"飞不到"** —— 判断已升级为可达性分析，见下节。

### 2.1.1 可达性分析：不只是看电量

`ReachabilityService` 把一次任务看成一只能走补能节点的图：

```
起点 ──调机(空载)──▶ 取货点 ──载货──▶ 送货点 ──空载──▶ 机巢
  ↘                    ↘              ↘              ↗
    ─────────────── 充电站网络 ───────────────
```

- **节点**：起点、取货点、送货点、机巢，以及"跳得到"的充电站（先用单段最大航程过滤）。
- **边权**：航段能耗 = 巡航能耗（载重只作用于"取货点 → 送货点"）+ 一次起降悬停。
- **补能**：落到充电站可把电量补到 `relayResumePct`（默认 90%），再扣安全冗余，
  所以补能后可用 `(90 - 25)% = 65%` 的电池容量；与飞行中自主补能的口径完全一致。
- **搜索**：状态 `(已补能次数, 阶段, 节点)`，在最多 3 次补能的预算内做"能量最大化"松弛，
  取<b>补能次数最少</b>的可行方案。

输出一句话结论直接进入决策明细：

| 结论 | 展示 |
| --- | --- |
| 直飞够电 | `直飞可达：全程 6.2km，预计耗电 320Wh（最紧一段余量 210Wh）` |
| 需要补能 | `需经停 2 次补能（虹桥补能站 → 虹桥补能站）：全程 19.4km，预计耗电 908Wh` |
| 确实飞不到 | `续航不可达：…（写明卡在调机段 / 载货段 / 返航段，以及差多少 Wh）` |

前端「可承接本单的无人机」里，"续航方案"列用绿色 `直飞可达` / 橙色 `经停 N 站` 区分，
悬停可看完整方案；顶部还会统计"直飞 X 架 / 需补能 Y 架"。

> 演示补单也用同一套判据：只有"可达且确实需要经停"的远单才会被造出来，
> 保证演示里一定看得到自主补能，又不会造出一张永远派不出去的死单。

### 2.2 策略排序（决定"想派谁"）

通过的候选交给策略排序，取第一名：

| 策略 | 主指标 | 适合场景 | 代价 |
| --- | --- | --- | --- |
| `NEAREST_AVAILABLE` | 到取货点距离 | 订单密集的城区，追求响应速度 | 近的飞机连轴转，机队使用不均 |
| `BATTERY_FIRST` | 剩余电量（越高越优先） | 长航程、重载、偏远任务 | 满电飞机可能离取货点很远 |
| `LOAD_BALANCE` | 当前在途任务数 | 机队规模大、看重设备寿命 | 可能选到较远的飞机，牺牲单次时效 |

并列时按各自声明的次序规则兜底（例如"距离相同则电量高者优先"），保证结果**可复现**。

### 2.3 路径规划（交给 Python）

Java 把空域转成障碍物列表，连同起终点一起发给算法服务：

```json
{
  "start": {"lat": 31.2304, "lon": 121.4737},
  "goal":  {"lat": 31.2050, "lon": 121.5990},
  "altitudeM": 80.0,
  "gridSizeM": 50.0,
  "keepOutMarginM": 30.0,
  "obstacles": [
    {"id": "Z-NF-001", "shape": "CIRCLE", "center": {"lat": 31.1979, "lon": 121.3363},
     "radiusM": 2500.0, "minAltitudeM": 0.0, "maxAltitudeM": 300.0},
    {"id": "Z-RS-001", "shape": "POLYGON", "points": [ ... ], "minAltitudeM": 0.0, "maxAltitudeM": 60.0}
  ]
}
```

三种返回情况的处理方式（**这是本节最值得记住的设计**）：

| 情况 | 处理 | 为什么 |
| --- | --- | --- |
| `found = true` | 落库航路，继续派单 | 正常路径 |
| `found = false`（无可行航路 / 起终点在禁飞区） | 抛 2002，派单失败 | 确实飞不了，不能硬派 |
| 算法服务连不上/超时 | 抛 2001，接口返回 503 | **绝不退化成直线**——那等于让飞机穿禁飞区 |
| `sky.algorithm.enabled=false` | 退化为两点直线 | 仅用于纯 Java 离线开发，日志会告警 |

### 2.4 落库与联动

一个派单动作会写/改 **5 张表**，它们必须在**同一个事务**里：

```
flight_route       ← 新增（航路头：距离、预计耗时、预计能耗）
flight_waypoint    ← 新增 N 条（航路点）
dispatch_task      ← 新增（ASSIGNED，任务号 ST…）
drone              ← 更新（status=ASSIGNED，current_task_id=任务 id）
delivery_order     ← 更新（status=ASSIGNED，assigned_drone_id）
dispatch_decision  ← 新增"全部候选"的得分与淘汰原因（独立事务提交，见下）
```

> **为什么 `dispatch_decision` 用独立事务？**
> 派单失败时（比如没有任何无人机扛得动 99kg），`dispatchOrder` 会抛业务异常、事务回滚。
> 如果决策记录写在同一个事务里，它就跟着被回滚掉了——而"为什么没有可用无人机"
> 恰恰是运营最需要看到的。所以 `DispatchDecisionRecorder` 用 `REQUIRES_NEW` 独立提交。
> 这是本项目里最容易忽略、也最能体现工程经验的一处设计。

## 3. 第三步：仿真推进（任务执行）

仿真引擎每 `tickMs`（默认 1000ms）推进一拍，每拍推进 `tickMs/1000 × speedFactor`（默认 4）仿真秒。
`SimulationTickService.tick()` 对一个任务做的事：

```
ASSIGNED ──起飞──▶ TAKEOFF ──▶ CRUISING ──抵达──▶ DELIVERING
  （扣一次起降悬停能耗）        （按去程载货扣电）    （卸货、订单→DELIVERED）
                                                      │
                                                      ▼
                    COMPLETED ◀──回到机巢── RETURNING
                  （任务入账、飞机 IDLE、          （按回程空载扣电）
                    累计里程/时长更新）
```

每一拍的具体动作：

1. **推进位置**：先按**飞行剖面**算出本拍的水平速度，再 `已飞距离 += 本拍速度 × 每拍秒数`，
   用 `GeoUtils.pointAtDistance(航路折线, 已飞距离)` 求当前位置——这样位置连续、进度百分比可算。
   剖面让"已经飞了多远"同时决定**速度**（起飞加速 → 巡航 → 减速降落）与**高度**
   （爬升过渡 → 巡航高度 → 下降过渡），所以看板上能看到飞机爬上去、平飞、再降下来，
   而不是一直贴在天花板上（参数见 `sky.flight.*`）；
2. **更新航向**：用当前位置与上一拍的方位角，地图上的箭头才会转；
3. **扣电量**：按本拍实际飞过的距离算能耗（`EnergyModel.cruiseEnergyWh`），
   而不是一次扣完——这样看板上的电量曲线是斜的，能直观对比机型能耗差异；
4. **到达判定**：距终点小于 0.5m 就算到达（浮点容差，否则永远差一点点）；
5. **送达**：`order → DELIVERED`，卸货，扣一次降落悬停能耗，速度与高度归零；
6. **返航**：沿去程反向飞回取货点（MVP 简化：航路只规划了取送两点），
   回程同样走一遍完整的爬升 → 巡航 → 下降剖面，只是载荷按空载计能耗；
7. **完成**：飞机 `IDLE`、位置归位到机巢、`total_tasks/total_distance_km/total_flight_min` 入账；
8. **电量兜底**：任何时刻电量低于安全冗余，任务立即 `FAILED`，飞机转 `MAINTENANCE`
   （真实系统里这叫"强制返航"，绝不会让飞机硬飞）。

### 2.5 长航程：无人机自主落到充电站补能（`BatteryRelayService`）

机巢只在起点，所以"往返续航不够"的长单以前会被派单硬条件直接判死 ——
演示数据里那单 10.5km 的"虹桥→中山公园"就是这样，26 架机没有一架接得了，
订单永远躺在待派列表里。充电站把这件事变成可执行：

| 环节 | 在哪里 | 做什么 |
| --- | --- | --- |
| 派单放行 | `DispatchService.filterCandidates` → `ReachabilityService` | 在"任务节点 + 充电站"图上搜索完整方案：直飞够电直接放行；直飞不够但补能可行也<b>放行</b>，并把"经停几次、经停哪些站"写进决策明细（见 2.1.1） |
| 自主判断 | `BatteryRelayService.scanAndRelay` | 每一拍扫在飞任务：剩余航程能耗 &gt; 可用电量 × `relaySafetyFactor`（默认 1.15）→ 自己改航到<b>最近且这点电确实飞得到</b>的站 |
| 到场补能 | `beginCharging` / `advanceCharging` | 落桩（任务转 `CHARGING`、飞机转 `DRONE.CHARGING`），按 `rechargeRatePctPerSimSec` 回充到 `relayResumePct`（默认 90%） |
| 恢复续飞 | `advanceCharging` | 航路重建成"充电站 → 还没飞完的那一段终点"（送货点或机巢），航段标记交回 `RerouteRegistry`，任务回到 `CRUISING`/`RETURNING` |

**补能站从哪来**：`DemoDataSeeder` 按<b>省会清单</b>在全国 35 个省/自治区/直辖市/港澳台各布 1 座，
再在四个演示城市（上海、北京、深圳、成都）的进出走廊上各加密 2 座，共 40 座。
补能网络是公共资源 —— 覆盖到哪，运力半径就到哪，不该按城市或运营商切分；
演示里只布四城的话，地图上就只剩四个城市的 ⚡ 标记。
选站时只保留**单段最大航程能跳到**的站（先按"到某个任务节点的距离"过滤）：
几百公里外的站放进图里也只是一次永远走不通的松弛。

三个必须说清的边界：

- **改航不改任务语义**：去补能的路上，那条航路的终点是充电站而不是送货点，
  所以抵达时要<b>拦下"送达"逻辑</b>（否则订单会被提前标成 DELIVERED，货其实还在机上）；
  补完能重建航路后才真正去送货。
- **补能期间不做电量兜底**：不然"自己判断该补能"会被低电量保护提前掐断、永远落不了站。
  选站时已经保证"这点电飞得到"，所以不会出现落到一半没电的情况。
- **开关隔离**：`sky.dispatch.relay-charging-enabled`（dev 打开、生产默认关）。
  真机能不能自主改航要过适航与空管评估，不是软件开关说了算。

### 冲突检测穿插其中

每 `conflictCheckEveryTicks`（默认 5 拍）调一次 Python 的 `/api/v1/conflict/check`：

- **机-机**：按当前航向与速度外推 60 秒，最小间距低于阈值就告警（严重度按间距分档）；
- **机-区**：位置或外推点落入禁飞区水平范围、且高度落在该区生效高度区间 → 告急；
- **低电量**：电量过低提示。

**去重**很关键：同一对飞机 60 秒内只记一次，否则 10 分钟就能产生上万条重复记录。

## 4. 第四步：完成与归档

任务完成后系统会自动留下三份可追溯的数据：

| 数据 | 位置 | 用途 |
| --- | --- | --- |
| 遥测轨迹 | `telemetry` | 逐点经纬度/电量/进度，可导 CSV 画轨迹图 |
| 事件时间线 | `event_log` | 回放与故障归因（`GET /tasks/{id}/events`） |
| 决策链 | `dispatch_decision` | 复盘"为什么派这架"（`GET /dispatch/decisions?orderId=`） |

导出：

```bash
curl -o telemetry.csv http://localhost:8080/api/v1/reports/tasks/1/telemetry.csv
curl -o events.csv    "http://localhost:8080/api/v1/reports/events.csv?taskId=1"
curl -o tasks.csv     http://localhost:8080/api/v1/reports/tasks.csv
```

## 5. 亲手跑一遍（5 分钟）

```bash
# 终端 1：算法服务
cd algorithm-service
uv run uvicorn app.main:app --port 8000

# 终端 2：Java 后端（dev profile，H2 内存库，自动灌演示数据）
cd backend
mvn spring-boot:run

# 终端 3：浏览器打开最简看板，或用脚本跑完整链路
#   tools/dashboard.html
pwsh -File tools/smoke-test.ps1
```

打开 `tools/dashboard.html` 就能看到：8 架无人机在地图上移动、电量下降、
任务完成后返航归巢，以及右侧的指标与冲突告警。

## 6. 驾驶舱：暂停与自动派单（这一版新增）

早期版本有一个**真 bug**：`SimulationEngine.runOnce()` 每拍都调 `ensureRunning()`，
查不到 RUNNING 的运行就立刻新建一个 —— 于是「停止仿真」按钮**完全无效**，
点下去 1 秒后引擎又自己跑起来。连带的问题是「立即批量派单」看起来没反应：
后台每 10 秒也在自动派单，而待派的又恰好是飞不到的订单。

现在把"跑仿真"和"派单"拆成两个独立的运行时开关（内存态，见 `SimulationRuntimeState`）：

| 开关 | 接口 | 语义 |
| --- | --- | --- |
| **引擎推进** `engineActive` | `POST /simulation/pause`、`/simulation/resume` | 暂停后定时推进**真的停下**，不会再自己复活；但 `POST /simulation/tick` 手动步进仍可用，可以逐拍观察状态机 |
| **自动派单** `autoDispatchEnabled` | `POST /simulation/auto-dispatch?enabled=` | 关闭后引擎不再扫单，订单只排队等待，派给谁完全由人决定 |

`GET /simulation/state` 一次返回全部状态，界面顶部控制条就是它的视图。

> **为什么 `engineActive` 的初值不取 `sky.simulation.enabled`？**
> 两者语义不同：配置项表示"这个进程挂不挂定时器"（集成测试就是关掉它、改用手动步进保证确定性），
> 而 `engineActive` 表示"用户是否暂停"。如果让前者决定后者，
> "关闭定时器"会连带把手动步进也禁掉。

**推荐的手动演示流程**：

```bash
# 1) 暂停推进，关掉自动派单 —— 世界静止，订单静静排队
curl -X POST localhost:8080/api/v1/simulation/pause
curl -X POST 'localhost:8080/api/v1/simulation/auto-dispatch?enabled=false'

# 2) 下几单
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' -d '{...}'

# 3) 派单，然后看决策明细：谁被选中、谁被淘汰、为什么
curl -X POST 'localhost:8080/api/v1/dispatch/auto?limit=5'
curl 'localhost:8080/api/v1/dispatch/decisions?orderId=1'

# 4) 一拍一拍地看它起飞、巡航、送达、返航
curl -X POST localhost:8080/api/v1/simulation/tick
```

## 7. 需要知道的"预期现象"

用默认演示数据跑，会看到**部分订单派单失败**，这是**正确行为**而不是 bug：

| 订单 | 航程 | 结果 | 原因 |
| --- | --- | --- | --- |
| 虹桥配送站 → 市一医院 | 15.9km | 失败 | 附近只有 X4，往返能耗 1466Wh > 可用 975Wh |
| 虹桥 → 中山公园 | 11.9km | 失败 | 往返能耗 1075Wh > 可用 975Wh |
| 人民广场站 → 张江 | 12.2km | 成功 | M8 载重与电量都够 |
| 人民广场站 → 静安寺 | 2.5km | 成功 | 短途单 |
| 人民广场站 → 陆家嘴 | 4.0km | 成功 | 限飞区限高 60m，80m 巡航不构成障碍 |
| 张江 → 浦东配送站 | 17.7km | 失败 | 载重 12kg 时往返能耗远超可用电量（远机还要加调机段） |

想全部送出去，可以调大 `sky.dispatch.search-radius-m`、调小安全冗余
`min-battery-reserve-pct`，或者给站点补几架飞机。
**每个失败原因都写在 `dispatch_decision.rejected_reason` 里**，可以直接查到。

**演示建议**：默认在人民广场站放了 6 架 M8，就是为了留出空闲运力。
否则"点派单没反应"的真实原因往往只是"这个站点附近的飞机恰好全在飞" ——
真遇到这种情况，看板上的「立即批量派单」结果弹窗会逐单写明原因。

## 8. 已知简化（MVP 取舍）

| 简化 | 影响 | 演进方向 |
| --- | --- | --- |
| 返航终点是取货点，不重新规划回机巢的独立航路 | 回程距离与去程相同，与实际有偏差 | 规划"取货点→机巢"的第二段航路 |
| 调机段（当前位置→取货点）计入能耗，但不画进航路、也不逐拍飞行 | 任务详情里的"航程"只含取送段，而"能耗"含调机段，两者口径不同 | 把无人机当前位置作为 A* 起点、取货点作为途经点 |
| 所有飞机同高度（默认 80m）巡航 | 机-机冲突会偏多 | 引入高度层分配，把冲突消解掉（现在只检测不消解） |
| 冲突只检测、不消解 | 告警多但不影响任务推进 | 加"速度调节 / 高度调整 / 等待"三种消解动作 |
| 单机单任务 | 不支持一架飞机连续接单 | 引入任务队列与接力派单 |
| 订单号用"当日计数+1" | 并发下单可能撞号 | 改数据库序列 |
