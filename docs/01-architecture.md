# sky-dispatch 架构说明

> 低空经济无人机配送调度仿真平台 · 纯软件仿真，不涉及真实硬件

## 1. 一句话架构

**Java 管业务与仿真，Python 管算法，PostgreSQL 管数据，WebSocket 管实时态势。**

```
┌──────────────────────────────────────────────────────────────────────┐
│              Vue3 + Ant Design Vue 运营控制台  ·  :5173               │
│   实时看板 · 订单管理 · 任务管理 · 机队管理 · 空域管理                 │
│   Leaflet 态势地图  ·  ECharts 图表  ·  WebSocket 实时通道客户端       │
└───────────────┬──────────────────────────────────┬───────────────────┘
                │ REST（拉）                        │ WebSocket（推）
                │ 开发期经 Vite 代理 → 同源，无跨域   │
                ▼                                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Java 后端  Spring Boot 3 · :8080                  │
│                                                                      │
│   web/         REST 控制器（8 个）      ws/     WebSocket 推送        │
│   service/     业务服务（12 个）        dispatch/ 派单策略（可插拔）   │
│   engine/      仿真引擎（离散时间步进）  integration/ 算法服务客户端    │
│   domain/      实体 + 枚举              repository/ Spring Data JPA   │
│   event/       领域事件（提交后推送）    common/ 统一响应与异常         │
│   config/      配置绑定、跨域、WebSocket 注册                          │
└───────┬──────────────────────────────────────────┬───────────────────┘
        │ JDBC                                     │ REST（同步阻塞）
        ▼                                          ▼
┌───────────────────────┐              ┌───────────────────────────────┐
│ PostgreSQL / PostGIS  │              │ Python 算法服务 FastAPI :8000  │
│ :5432                 │              │  A* 路径规划 / 冲突检测        │
│ 12 张业务表 + 2 视图   │              │  纯计算、无状态、可横向扩容     │
└───────────────────────┘              └───────────────────────────────┘
```

> 除正式控制台外，仓库还保留了 `tools/dashboard.html`：一个零构建的备用调试页
> （原生 WebSocket + Leaflet CDN），在没有 Node 环境或只想快速确认后端时使用。

## 2. 为什么这样切分

| 决策 | 理由 |
| --- | --- |
| Java 负责业务与仿真 | 订单/任务/调度需要事务、状态机、并发控制，Spring 生态最成熟；长生命周期的定时步进用 `@Scheduled` 简单可靠 |
| Python 负责算法 | 算法迭代快、需要科学计算生态（后续接 OR-Tools / 强化学习），用 Python 更合适；且算法服务无状态、易替换、易压测 |
| 单向调用（Java → Python） | Python 不反向调用 Java、不访问数据库。这样算法服务可以被任意替换（换成 C++/Rust 服务也不影响 Java），也不会成为数据一致性的源头 |
| REST + WebSocket 并存 | REST 用于"要准确结果"的场景（查询、派单），WebSocket 用于"要实时流畅"的场景（位置刷新）。纯推送架构在首屏加载和断线重连时很别扭 |
| 算法可降级 | 算法服务挂了不能让整个平台瘫掉：冲突检测静默降级，路径规划明确报 503（绝不用直线偷偷穿过禁飞区） |

## 3. Java 后端包结构（交付物 2）

```
com.skydispatch
├── SkyDispatchApplication           启动类（@EnableScheduling）
│
├── common/                          与业务无关的公共设施
│   ├── ApiResponse                  统一响应包装 {code,message,data,timestamp}
│   ├── ErrorCode                    错误码枚举（1001 参数 / 2001 算法服务 ...）
│   ├── BusinessException            业务异常（可预期错误）
│   ├── GlobalExceptionHandler       全局异常翻译（@RestControllerAdvice）
│   ├── GeoPoint                     经纬度 record
│   └── GeoUtils                     haversine / 方位角 / 折线插值 / 点在多边形内
│
├── config/                          配置
│   ├── SkyProperties                sky.* 配置绑定（record + @DefaultValue）
│   ├── RestClientConfig             调用算法服务的 RestClient（含超时）
│   └── WebSocketConfig              注册 /ws/telemetry
│
├── domain/
│   ├── entity/                      12 个 JPA 实体（对应 12 张表）
│   └── enums/                       13 个枚举（状态机与类型）
│
├── repository/                      12 个 Spring Data JPA 仓储
│
├── dto/                             对外契约（record 容器，按聚合分组）
│   ├── CommonDtos                   GeoDto / PageResponse / OperationResult
│   ├── OrderDtos / DroneDtos / AirspaceDtos / TaskDtos / SimulationDtos
│   ├── DashboardDtos                Kpi / DroneFrame / Snapshot / ConflictView
│   └── algorithm/AlgorithmDtos      与 Python 服务的请求/响应模型
│
├── dispatch/                        派单策略（开闭原则实践）
│   ├── DispatchStrategy             接口：type / score / comparator
│   ├── DroneCandidate               候选上下文对象（距离、负载、续航）
│   ├── NearestAvailableStrategy     最近可用
│   ├── BatteryFirstStrategy         电量优先
│   ├── LoadBalanceStrategy          负载均衡
│   └── DispatchStrategyFactory      按类型取策略（自动收集所有实现）
│
├── engine/                          仿真引擎
│   ├── SimulationEngine             定时器 + 并发保护 + 事务外广播
│   ├── SimulationTickService        一拍工作单元（@Transactional 边界）
│   ├── TickOutcome                  一拍结果
│   ├── SimulationService            运行生命周期（run 的创建/停止/时钟推进）
│   └── EnergyModel                  电量消耗模型（巡航 + 载重惩罚 + 起降悬停）
│
├── service/                         业务服务
│   ├── OrderService                 下单/查询/取消 + 订单状态机
│   ├── DroneService                 机队台账与状态
│   ├── AirspaceService              空域 CRUD + 空域→障碍物转换（WKT 互转）
│   ├── RouteService                 航线规划（组装请求→调算法→落库）
│   ├── DispatchService              派单决策（硬条件筛选 + 策略排序 + 决策留痕）
│   ├── TaskService                  任务查询/航路点/中止
│   ├── TelemetryService             遥测落库（降采样）
│   ├── ConflictService              冲突检测（调算法 + 指纹去重）
│   ├── DashboardService             指标聚合
│   ├── EventService                 事件记账（唯一写 event_log 的地方）
│   └── ReportService                CSV 导出（BOM + 转义）
│
├── event/                           领域事件
│   ├── SkyEvent                     事件载体
│   └── EventBroadcaster             @TransactionalEventListener(AFTER_COMMIT) → WebSocket
│
├── integration/
│   └── AlgorithmServiceClient       Python 服务客户端（可降级）
│
├── ws/                              实时推送
│   ├── WsMessage                    消息信封 {type,ts,payload}
│   ├── TelemetryBroadcaster         会话管理与广播（慢客户端隔离）
│   └── TelemetryWebSocketHandler    /ws/telemetry 端点
│
├── web/                             8 个 REST 控制器
│   ├── OrderController / DroneController / AirspaceController
│   ├── DispatchController / TaskController / SimulationController
│   ├── DashboardController / ReportController
│
└── bootstrap/
    └── DemoDataSeeder               演示数据（dev profile 一键可跑）
```

### 分层依赖方向

```
web ──▶ service ──▶ repository ──▶ entity
         │  ▲
         │  └── dispatch（策略）· engine（仿真）· integration（外部服务）
         └──▶ event ──▶ ws
```

规则：**上层可以依赖下层，下层绝不反向依赖上层**。
`engine` 与 `service` 平级协作；`ws` 只被 `event` 和 `engine` 调用，自己不依赖业务。

## 4. 关键设计决策

### 4.1 每一拍是一个事务，推送在事务提交之后

仿真引擎拆成两个 Bean 不是过度设计，而是被 Spring 的代理机制逼出来的：

- `SimulationEngine`（`@Component`）：定时器、并发锁、广播、自动派单；
- `SimulationTickService`（`@Service`）：`@Transactional` 的单拍工作单元。

同一个类里 A 方法调自己的 B 方法时，B 上的 `@Transactional` **不会生效**。把单拍逻辑独立成 Bean，才能保证"一拍 = 一个事务"，且推送发生在提交之后——否则事务回滚时前端会收到从未存在过的位置。

### 4.2 硬条件与软条件分离

调度里最容易出事故的做法，是把"能不能飞"和"想不想飞"混在一个评分函数里。
本平台强制分离：

- **硬条件**（载重、电量冗余、续航、搜索半径、空闲状态）→ `DispatchService.filterCandidates`，不满足直接淘汰并**记录原因**；
- **软条件**（更近、更满电、更闲）→ `DispatchStrategy`，只负责排序。

于是换策略时安全边界不会跟着变。

### 4.3 决策可解释

每次派单都会把**全部候选**（含被淘汰的）写进 `dispatch_decision`：
得分、排名、是否选中、淘汰原因、分项指标 JSON。
这既是排障手段，也是教学素材——能直接回答"为什么这次派了 UAV-005 而不是更近的 UAV-002"。

### 4.4 冲突去重

仿真每 5 拍检测一次冲突，若不去重，一对飞机 10 分钟就能产生上万条重复记录。
`ConflictService` 用"类型 + 机对 + 区域"的指纹做 60 秒窗口去重。
生产环境应把这个指纹移到 Redis 以支持多实例。

## 5. 数据流：一次派单经过哪些组件

```
POST /api/v1/orders/{id}/dispatch
  └─ OrderController
      └─ DispatchService（@Transactional）
          ├─ OrderService.require            读订单，校验状态
          ├─ DispatchStrategyFactory.get      选策略
          ├─ filterCandidates                 硬条件筛选 + 组装 DroneCandidate
          ├─ DispatchStrategy.comparator      排序取第一名
          ├─ RouteService.planRoute
          │    ├─ AirspaceService.activeObstacleSpecs   空域 → 障碍物
          │    ├─ AlgorithmServiceClient.plan  ──HTTP──▶ Python /api/v1/path/plan
          │    ├─ EnergyModel                 估时、估能耗
          │    └─ FlightRouteRepository       落库 route + waypoints
          ├─ DispatchTaskRepository           建任务
          ├─ DroneRepository / OrderService   更新飞机与订单状态
          ├─ DispatchDecisionRepository       落库决策链
          └─ EventService.record ──▶ SkyEvent ──▶（提交后）WebSocket EVENT 帧
```

## 6. 部署形态

| 组件 | 端口 | 启动方式 | 依赖 |
| --- | --- | --- | --- |
| 前端控制台 | 5173 | `cd frontend && pnpm dev` | Node 20+（代理到 8080，无需 CORS） |
| Java 后端（dev） | 8080 | `mvn spring-boot:run` | 无（H2 内存库） |
| Java 后端（prod） | 8080 | `mvn spring-boot:run -Dspring-boot.run.profiles=postgres` | PostgreSQL 14+ |
| Python 算法服务 | 8000 | `uv run uvicorn app.main:app --port 8000` | 无 |
| PostgreSQL/PostGIS | 5432 | `docker compose up -d` | Docker（可选） |

算法服务与 Java 后端可以部署在不同机器，只需改 `sky.algorithm.base-url`。
前端与后端也可以分机部署：生产用 Nginx 托管 `frontend/dist` 并把 `/api`、`/ws` 转发到后端
（配置示例见 [`07-frontend.md`](07-frontend.md) 第 6 节），这样仍然不需要开 CORS。

## 7. 演进路线（MVP 之后）

| 优先级 | 事项 | 说明 |
| --- | --- | --- |
| P0 | 派单事务拆分 | 把"算法服务 HTTP 调用"移出数据库事务，避免长事务占用连接 |
| P0 | 冲突消解 | 现在只**检测**冲突，不**解决**。需要加高度层分配 / 速度调节 / 等待 |
| P1 | 订单号改序列 | 当前 `当日计数+1` 在并发下会撞号，改用 `CREATE SEQUENCE` |
| P1 | 并行沙盘 | 同时跑多套策略做 A/B 对比，需要把引擎状态从单例改成"每 run 一个上下文" |
| P1 | 多高度层 | 现在所有飞机都在 80m，冲突会很频繁；引入高度层是关键 |
| P2 | 前端工程化 | Vue3 + ECharts 正式看板，替换 `tools/dashboard.html` |
| P2 | 时效性优化 | 引入"超时风险"评分，把准时率纳入派单目标 |
| P2 | 观测性 | Prometheus 指标 + 派单耗时分布 + 算法服务 P99 延迟 |
