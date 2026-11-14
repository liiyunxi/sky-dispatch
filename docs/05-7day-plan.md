# 7 天开发任务清单

> 目标：**第 7 天可以完整演示「下单 → 自动派单 → 绕障规划 → 仿真飞行 → 冲突告警 → 送达返航 → 导出报告」**。
>
> 本清单假设 1～2 人开发。括号里是**当前仓库已经完成的**部分，可以直接跳过、把时间投到后面的任务上。

## 总览

| 天 | 主题 | 交付物 | 验收标准 |
| --- | --- | --- | --- |
| D1 | 骨架与数据 | 建表 SQL、Spring Boot 骨架、FastAPI 骨架 | 两个服务都能启动，`/actuator/health` 与 `/health` 返回正常 |
| D2 | 订单与机队 | 订单/无人机/空域 CRUD | 能用 curl 创建订单、注册无人机、画禁飞区 |
| D3 | 算法服务 | A* 路径规划 + 冲突检测 + 单测 | 绕障路径正确，单元测试全绿 |
| D4 | 调度模块 | 三种派单策略 + 决策留痕 | 换策略得到不同派单结果，可查淘汰原因 |
| D5 | 仿真引擎 | 离散步进 + 电量消耗 + 状态机 | 任务能从 ASSIGNED 走到 COMPLETED |
| D6 | 实时看板 | WebSocket 推送 + 地图 + 指标 | 浏览器能看到飞机移动与电量下降 |
| D7 | 回放报告与联调 | CSV 导出 + 事件时间线 + 演示脚本 | 一条命令跑通全链路，输出报告 |

---

## D1（已完成）骨架与数据建模

**任务**

- [x] 设计表结构，写 `db/migration/V1__init_schema.sql`（12 张表 + 2 视图）
- [x] 写演示数据 `V2__seed_data.sql`
- [x] Maven 工程 + Spring Boot 启动类 + 统一响应/异常
- [x] FastAPI 工程 + uv 依赖管理 + `/health`
- [x] `dev`（H2）与 `postgres` 双 profile

**验收**

```bash
cd backend && mvn spring-boot:run           # 起来后 8080/actuator/health = UP
cd algorithm-service && uv run uvicorn app.main:app --port 8000   # 8000/health = ok
```

**容易卡住的地方**

- JDK 版本：Spring Boot 3 需要 JDK 17+，本项目按 21 编译（JDK 23 也能编，用 `release=21`）。
- Maven 下载慢：配阿里云镜像（见 `.build/maven-settings.xml` 示例）。
- Python 版本：`requires-python >= 3.12`，别用 3.9。

---

## D2（已完成）订单 / 无人机 / 空域

**任务**

- [x] 三个实体的 JPA 映射 + 仓储
- [x] 订单状态机（`OrderService.requireTransitionAllowed`）
- [x] 空域 WKT 互转（`AirspaceService.toWkt / parsePolygonWkt`）
- [x] 三组 REST 接口 + Swagger UI
- [x] 全局异常 → 统一错误码

**验收**

```bash
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerName":"张三","cargoName":"药品","weightKg":2.5,"priority":"URGENT",
       "pickup":{"lat":31.2304,"lon":121.4737},"dropoff":{"lat":31.248,"lon":121.452}}'

curl localhost:8080/api/v1/drones
curl localhost:8080/api/v1/airspace/zones
```

**容易卡住的地方**

- 返回 DTO 时访问懒加载关联 → `LazyInitializationException`。要么方法上加 `@Transactional`，要么用 `join fetch`。
- 校验注解写在类型参数上（`List<@Valid X>`）会导致**解析期**报错，进而让 Lombok 完全不工作，报出一堆"找不到 builder()"的假象错误。

---

## D3（已完成）算法服务：A* 与冲突检测

**任务**

- [x] 经纬度 ↔ 局部米制平面坐标转换
- [x] 栅格离散 + 障碍物膨胀（`keepOutMarginM`）
- [x] A*（8 邻域、对角代价 √2、`heapq`）
- [x] 视线法平滑（`smooth=true`）
- [x] 高度区间判定（高度不在区间内的障碍不生效）
- [x] 冲突检测（机-机外推 / 机-区 / 低电量）
- [x] pytest 覆盖：直线、绕圆、绕多边形、起终点在区内、无路可走、平滑后仍合法
- [x] `docs/03-api-contract.md` 固化接口契约

**验收**

```bash
cd algorithm-service
uv sync && .venv/Scripts/python -m pytest -q     # 全部通过
```

**容易卡住的地方**

- 起终点落在障碍物里必须先判出来，否则 A* 会从"封闭区域"里找不到路，报成笼统的"无路可走"。
- 平滑后一定要**重新校验**路径不穿障碍，否则会把绕障结果平滑回障碍里。
- 累计距离必须单调递增，且末点距离等于 `distanceM`，否则 Java 侧进度百分比会跳变。

---

## D4（已完成）调度模块

**任务**

- [x] `DispatchStrategy` 接口 + 三种策略 + 工厂
- [x] 硬条件筛选（状态/载重/电量/搜索半径/往返续航）
- [x] 决策明细落库（得分、排名、淘汰原因）——**独立事务提交**
- [x] 批量派单按「优先级 → 创建时间」排序，且 `limit` 限制**成功数**而非尝试数
- [x] 派单失败原因写入决策表

**验收**

```bash
# 同一订单分别用三种策略派单，对比选中的无人机
curl -X POST 'localhost:8080/api/v1/dispatch/orders/1?strategy=NEAREST_AVAILABLE'
curl -X POST 'localhost:8080/api/v1/dispatch/orders/2?strategy=BATTERY_FIRST'
curl -X POST 'localhost:8080/api/v1/dispatch/orders/3?strategy=LOAD_BALANCE'
curl 'localhost:8080/api/v1/dispatch/decisions?orderId=1'
```

**容易卡住的地方**

- **自调用让事务失效**：同一个类里 A 方法调自己的 B 方法，B 的 `@Transactional` 不生效。批量派单必须放独立 Bean。
- **失败时审计丢失**：派单失败会回滚，审计记录要用 `REQUIRES_NEW` 单独提交。
- **订单饥饿**：如果批次上限限制的是"尝试数"，几个永远失败的订单会饿死后面的订单。
- **只算单程续航**：必须算往返，否则会派出飞不回来的飞机。

---

## D5（已完成）仿真引擎

**任务**

- [x] 仿真运行生命周期（`simulation_run`）
- [x] 每拍推进：位置、航向、速度、高度
- [x] 电量模型（巡航 + 载重惩罚 + 起降悬停）
- [x] 任务状态机：ASSIGNED → TAKEOFF → CRUISING → DELIVERING → RETURNING → COMPLETED
- [x] 电量低于安全冗余时强制中止
- [x] 遥测降采样落库
- [x] 手动步进接口 `POST /simulation/tick`

**验收**

```bash
curl -X POST localhost:8080/api/v1/simulation/tick   # 连打几次
curl localhost:8080/api/v1/dashboard/drones          # 位置在变、电量在降
curl 'localhost:8080/api/v1/tasks?size=5'
```

**容易卡住的地方**

- `fixedRate` 会堆积任务，仿真必须用 `fixedDelay`。
- 手动 tick 和定时 tick 会打架，要用 `AtomicBoolean` 加锁。
- 推送必须在事务提交之后，否则回滚时前端会收到"从未发生"的位置。
- 到达判定要留浮点容差（0.5m），否则永远差一点点。

---

## D6（已完成）实时看板

**后端（推送通道）**

- [x] 原生 WebSocket `/ws/telemetry`（`SNAPSHOT` / `TELEMETRY` / `EVENT` / `CONFLICT` / `KPI`）
- [x] 慢客户端隔离 + 连接并发保护
- [x] REST 兜底：`/dashboard/kpi`、`/dashboard/drones`、`/dashboard/snapshot`
- [x] 跨域放行（`WebConfig`，用于 `pnpm preview` 或直接 fetch 调试）

**前端（Vue3 + Ant Design Vue 4 运营控制台）**

- [x] Vue3 + Vite + TypeScript 工程，`@` 别名、路由懒加载、`a-config-provider` 中文本地化
- [x] 全局实时状态 `store/telemetry.ts`：WebSocket 连接、指数退避重连、连续失败自动切 REST 轮询兜底
- [x] **实时看板**：8 张 KPI 卡片（`a-statistic` + 语义色条）、Leaflet 态势地图（空域/航路/无人机三层，与表格双向联动）、订单状态环形图 + 机队电量分布柱状图（ECharts 按需引入）、机队实时表格、冲突告警（可人工解除）与事件流
- [x] **订单管理**：筛选 + 服务端分页、新建订单（含直线距离实时提示）、按策略派单、**决策明细抽屉**（全部候选的得分/排名/淘汰原因）、批量派单、取消
- [x] **任务管理**：任务列表（进度条、计划/实际航程与能耗）、详情抽屉（字段 + 航路点表 + 事件时间线）、导出遥测/事件 CSV、中止任务
- [x] **机队管理**：机型能力、电量进度条、位置、累计里程/飞行时长、注册新机、状态调整
- [x] **空域管理**：禁飞区/限飞区/走廊增删，圆形与多边形（支持动态增删顶点）
- [x] 顶部状态条：实时通道健康度 + 仿真拍数/时长 + **算法服务在线状态**（离线时首页弹红色横幅）

**验收**

```bash
cd frontend
pnpm type-check     # vue-tsc 零错误（覆盖全部 .ts 与 .vue 模板）
pnpm build          # vite build 成功产出 dist/
pnpm dev            # 打开 http://localhost:5173
```

**容易卡住的地方**

- Vite 代理必须显式写 `ws: true`，否则 WebSocket 握手会失败（表现为"页面有数据但飞机不动"）。
- Leaflet 容器必须有**确定高度**，否则地图塌陷成 0 高什么都看不见；容器尺寸变化后要调 `map.invalidateSize()`。
- 地图每秒刷新，**千万不要"清空图层再重建"**，要按 id 缓存标记对象做增量更新；
  用 `preferCanvas: true` 走 Canvas 渲染。
- ECharts 实例必须在 `onUnmounted` 里 `dispose()`，否则切换页面会累积实例与监听器。
- 后端配了 `non_null` 序列化，**null 字段不会出现在 JSON 里**，前端所有可空字段都要 `?? '—'` 兜底。
- 别写死 `localhost:8080`，用 `location.host` 拼地址，才能同时适配开发代理与生产 Nginx。

---

## D7（已完成）回放、报告与联调

**任务**

- [x] 事件时间线接口与导出（`/tasks/{id}/events`、`/reports/events.csv`）
- [x] 遥测导出（`/reports/tasks/{id}/telemetry.csv`，UTF-8 BOM 防 Excel 乱码）
- [x] 任务清单导出（`/reports/tasks.csv`）
- [x] 端到端冒烟脚本 `tools/smoke-test.ps1`
- [x] 后端测试 23 项（含完整业务流程集成测试）
- [x] 看板最简版 + 文档五篇

**待做（若要更完整）**

- [ ] 回放功能：按时间轴重播某次任务（前端读遥测数据或按 `sim_time_s` 拉历史）
- [ ] 空域地图绘制：集成 Leaflet.Draw，支持在地图上拖拽画禁飞区、编辑已有区域
- [ ] 登录与权限：按角色控制派单与空域编辑
- [ ] 演示 PPT / 录屏脚本
- [ ] 压测：把无人机数量调到 200 架，观察 tick 耗时、地图帧率与算法服务 P99

**验收**

```bash
pwsh -File tools/smoke-test.ps1     # 全绿退出码 0
cd backend && mvn test              # 23 项测试通过
```

---

## 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 算法服务成为单点 | 派单全部 503 | 冲突检测静默降级；路径规划明确报错；预留 `sky.algorithm.enabled=false` 的直线降级开关（仅开发用） |
| 所有飞机同高度 | 机-机冲突偏多 | D6/D7 引入高度层分配；MVP 阶段冲突只告警不阻塞 |
| 派单事务里发 HTTP 调用 | 长事务占用连接 | 已记入演进路线 P0：拆成"选候选 / 事务外规划 / 落库确认"三段 |
| 演示数据距离超出机队能力 | 部分订单派单失败 | 这是**真实约束下的正确行为**；决策表里能看到每条淘汰原因。演示时优先展示能送的单，或调大 `search-radius-m` |
| H2 与 PostgreSQL 行为差异 | 上线才发现问题 | 核心 SQL 只用通用语法；PostGIS 增强独立成脚本；上线前用 `postgres` profile 跑一遍 |

## 每日节奏建议

- **上午**：写代码 + 写测试（先写测试更省时间）
- **下午前半**：curl / Swagger 手工验证并截图存档（演示素材就是这么攒出来的）
- **下午后半**：更新文档与 `docs/`，把当天踩的坑补进"容易卡住的地方"
- **收尾**：`mvn test` + `pytest` 必须全绿再提交
