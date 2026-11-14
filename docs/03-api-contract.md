# sky-dispatch 接口契约（对外 API + 服务间 API + WebSocket）

> 本文件是 **Java 后端** 与 **Python 算法服务** 之间的唯一契约来源。任何一方改动接口，必须同步修改本文件。
> 版本：v1.0（MVP）

## 0. 端口与角色

| 服务 | 端口 | 职责 |
| --- | --- | --- |
| Java 后端（Spring Boot） | `8080` | 业务、调度、仿真引擎、持久化、WebSocket 推送 |
| Python 算法服务（FastAPI） | `8000` | A* 路径规划、冲突检测（纯计算，无状态） |
| PostgreSQL/PostGIS | `5432` | 持久化（MVP 可先用 H2 内存库跑演示） |

约定：**Java 单向调用 Python**（REST，同步阻塞），Python 不反向调用 Java，也不访问数据库。这样算法服务可以随意扩缩容和替换。

调用链：

```
HTTP Client → Java REST API → (DispatchService) → Python /path/plan
                            → (SimulationEngine, 1s/tick) → Python /conflict/check
                            → WebSocket /ws/telemetry → 前端/测试客户端
```

## 1. 统一响应包装（Java 对外 REST）

除 CSV 导出外，所有 Java 接口返回统一包装：

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "timestamp": "2026-01-01T10:00:00Z"
}
```

- `code = 0` 表示成功；非 0 见 `common/ErrorCode`。
- HTTP 状态码同时反映语义：`200` 成功、`400` 参数错误、`404` 不存在、`409` 业务冲突、`503` 算法服务不可用、`500` 其它。

## 2. Java 对外 REST API（前缀 `/api/v1`）

### 2.1 订单 Order

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/orders` | 创建订单 |
| GET | `/orders?status=&priority=&page=0&size=20` | 分页查询 |
| GET | `/orders/{id}` | 详情 |
| POST | `/orders/{id}/cancel` | 取消（仅 CREATED/QUEUED/ASSIGNED 可取消） |
| POST | `/orders/{id}/dispatch?strategy=NEAREST_AVAILABLE` | 对单个订单派单 |

创建请求：

```json
{
  "customerName": "张三",
  "customerPhone": "13800000000",
  "cargoName": "急救药品",
  "weightKg": 2.5,
  "priority": "URGENT",
  "pickup": { "lat": 31.2304, "lon": 121.4737, "address": "A 站点" },
  "dropoff": { "lat": 31.2401, "lon": 121.4902, "address": "B 医院" },
  "expectDeliveryAt": "2026-01-01T12:00:00Z"
}
```

响应 `data`：

```json
{
  "id": 1, "orderNo": "SO20260101001", "status": "CREATED",
  "priority": "URGENT", "weightKg": 2.5, "cargoName": "急救药品",
  "pickup": { "lat": 31.2304, "lon": 121.4737, "address": "A 站点" },
  "dropoff": { "lat": 31.2401, "lon": 121.4902, "address": "B 医院" },
  "assignedDroneId": null, "createdAt": "2026-01-01T10:00:00Z"
}
```

### 2.2 无人机 Drone

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/drones` | 注册无人机；`code` **可留空**，留空由后端自动编号（`SKY-DR-0001` 起顺延） |
| GET | `/drones?status=` | 列表（含型号参数） |
| GET | `/drones/{id}` | 详情 |
| PATCH | `/drones/{id}/status` | 手动改状态（充电/维护/下线） |

### 2.3 空域 Airspace

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/airspace/zones` | 新增禁飞/限制区；`code` 与 `name` **可留空**（编码按类型自动生成如 `NFZ-0003`，名称用「类型 + 编号」拼出），`severity` 留空时按类型取默认值（禁飞区 CRITICAL / 限飞区 HIGH / 走廊 LOW / 警示区 MEDIUM） |
| GET | `/airspace/zones` | 列表 |
| DELETE | `/airspace/zones/{id}` | 删除 |

圆形区域用 `shapeType=CIRCLE` + `centerLat/centerLon/radiusM`；多边形用 `shapeType=POLYGON` + `points:[{lat,lon}...]`。

### 2.4 调度 Dispatch

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/dispatch/orders/{orderId}?strategy=NEAREST_AVAILABLE` | 单订单派单，返回任务 |
| POST | `/dispatch/auto?strategy=BATTERY_FIRST&limit=10` | 批量派单（按优先级 + 创建时间排序） |
| GET | `/dispatch/decisions?orderId=` | 调度打分明细（可解释性，事后留痕，含被淘汰候选） |
| GET | `/dispatch/candidates?orderId=&strategy=` | 派单前"搜罗可用无人机"预览：只返回通过硬条件（载重 / 电量 / 往返续航 / 半径 / 空闲）的入围候选，按策略排序；`evaluatedTotal` 为评估总数，`rejectedCount` 为被淘汰数 |

`strategy` 取值：`NEAREST_AVAILABLE` | `BATTERY_FIRST` | `LOAD_BALANCE`。

### 2.5 任务 Task

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/tasks?status=&droneId=&page=&size=` | 分页查询 |
| GET | `/tasks/{id}` | 详情（含航路摘要） |
| GET | `/tasks/{id}/waypoints` | 航路点列表 |
| POST | `/tasks/{id}/abort` | 中止任务 |
| GET | `/tasks/{id}/events` | 事件时间线（回放用） |

### 2.6 仿真 Simulation

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/simulation/runs` | 启动/复用仿真运行 |
| POST | `/simulation/runs/{id}/stop` | 停止 |
| GET | `/simulation/runs/{id}` | 运行状态 |
| GET | `/simulation/runs/current` | 当前运行 |
| POST | `/simulation/tick` | 手动步进一拍（便于测试与教学） |

### 2.7 看板与报告

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/dashboard/kpi` | 核心指标 |
| GET | `/dashboard/drones` | 在线无人机快照（地图用） |
| GET | `/dashboard/conflicts?status=OPEN` | 未处理冲突 |
| GET | `/reports/tasks/{id}/telemetry.csv` | 导出遥测 CSV |
| GET | `/reports/events.csv?taskId=` | 导出事件 CSV |
| GET | `/reports/tasks.csv` | 导出任务清单 CSV |

`/dashboard/kpi` 返回：

```json
{
  "totalOrders": 42, "pendingOrders": 5, "inFlightOrders": 3, "deliveredOrders": 34,
  "failedOrders": 0, "totalTasks": 40, "completedTasks": 33,
  "activeDrones": 6, "totalDrones": 8, "avgBatteryPct": 71.5,
  "avgDeliverySeconds": 486.2, "onTimeRate": 0.94, "openConflicts": 1,
  "simTimeS": 1234.0, "tickCount": 1234, "runStatus": "RUNNING"
}
```

## 3. WebSocket 遥测推送

- 地址：`ws://localhost:8080/ws/telemetry`（原生 WebSocket，**非 STOMP**，方便浏览器直接 `new WebSocket(...)` 测试）
- 服务端 → 客户端为纯文本 JSON 帧，统一结构：

```json
{ "type": "TELEMETRY", "ts": "2026-01-01T10:00:01Z", "payload": { } }
```

`type` 取值与 `payload`：

| type | 触发时机 | payload |
| --- | --- | --- |
| `SNAPSHOT` | 客户端连接成功时立即推送一次全量快照 | `{ "simTimeS": 12.0, "drones": [TelemetryFrame...], "kpi": {...} }` |
| `TELEMETRY` | 每个仿真 tick | `{ "simTimeS": 13.0, "drones": [TelemetryFrame...] }` |
| `EVENT` | 订单/任务状态变化 | `{ "eventType": "TASK_COMPLETED", "taskId": 7, "message": "..." }` |
| `CONFLICT` | 冲突检测发现新冲突 | `{ "conflictType": "DRONE_DRONE", "severity": "HIGH", ... }` |
| `KPI` | 每 5 个 tick 汇总一次 | 同 `/dashboard/kpi` |

`TelemetryFrame`（Java 与前端共用的最小字段集）：

```json
{
  "droneId": 3, "droneCode": "UAV-003", "taskId": 7, "orderNo": "SO20260101001",
  "status": "IN_FLIGHT", "lat": 31.2345, "lon": 121.4801, "altitudeM": 80.0,
  "speedMps": 12.0, "headingDeg": 45.2, "batteryPct": 72.4,
  "progressPct": 38.0, "waypointSeq": 5, "ts": "2026-01-01T10:00:01Z"
}
```

客户端 → 服务端只接受一种控制帧（可选实现）：`{"type":"PING"}` → 服务端回 `{"type":"PONG"}`。

## 4. Python 算法服务 API（前缀 `/api/v1`）

### 4.1 `GET /health`

```json
{ "status": "ok", "service": "sky-dispatch-algorithm", "version": "0.1.0", "python": "3.13.15" }
```

### 4.2 `POST /api/v1/path/plan` —— A* 路径规划

请求：

```json
{
  "requestId": "b1f2...",
  "start": { "lat": 31.2304, "lon": 121.4737 },
  "goal":  { "lat": 31.2401, "lon": 121.4902 },
  "altitudeM": 80.0,
  "cruiseSpeedMps": 12.0,
  "gridSizeM": 50.0,
  "maxSearchNodes": 200000,
  "keepOutMarginM": 30.0,
  "smooth": true,
  "obstacles": [
    { "id": "Z001", "name": "禁飞区-虹桥", "shape": "CIRCLE",
      "center": { "lat": 31.2350, "lon": 121.4800 }, "radiusM": 600.0,
      "minAltitudeM": 0.0, "maxAltitudeM": 120.0 },
    { "id": "Z002", "name": "限飞多边形", "shape": "POLYGON",
      "points": [ { "lat": 31.24, "lon": 121.49 }, { "lat": 31.25, "lon": 121.50 }, { "lat": 31.24, "lon": 121.51 } ],
      "minAltitudeM": 0.0, "maxAltitudeM": 150.0 }
  ]
}
```

响应（**规划失败不报 5xx**，而是 `found=false`，便于 Java 侧降级处理）：

```json
{
  "success": true,
  "requestId": "b1f2...",
  "algorithm": "ASTAR",
  "found": true,
  "message": "ok",
  "distanceM": 2380.5,
  "waypoints": [
    { "seq": 0, "lat": 31.2304, "lon": 121.4737, "altitudeM": 80.0, "cumulativeDistanceM": 0.0 },
    { "seq": 1, "lat": 31.2312, "lon": 121.4750, "altitudeM": 80.0, "cumulativeDistanceM": 168.3 }
  ],
  "expandedNodes": 812,
  "elapsedMs": 12.3
}
```

### 4.3 `POST /api/v1/conflict/check` —— 冲突检测

请求：

```json
{
  "checkTime": "2026-01-01T10:00:00Z",
  "lookaheadSeconds": 60.0,
  "minHorizontalSeparationM": 50.0,
  "minVerticalSeparationM": 15.0,
  "drones": [
    { "droneId": 3, "droneCode": "UAV-003", "lat": 31.2345, "lon": 121.4801,
      "altitudeM": 80.0, "speedMps": 12.0, "headingDeg": 45.0, "batteryPct": 72.4 }
  ],
  "zones": [ { "id": "Z001", "shape": "CIRCLE", "center": {"lat":0,"lon":0}, "radiusM": 600.0,
               "minAltitudeM": 0.0, "maxAltitudeM": 120.0 } ]
}
```

响应：

```json
{
  "success": true,
  "conflictCount": 1,
  "conflicts": [
    { "conflictType": "DRONE_DRONE", "severity": "HIGH",
      "droneAId": 3, "droneBId": 5, "zoneId": null,
      "horizontalDistanceM": 31.2, "verticalDistanceM": 0.0,
      "closingSpeedMps": 11.4, "timeToConflictS": 12.5,
      "lat": 31.2349, "lon": 121.4805,
      "message": "UAV-003 与 UAV-005 预计 12.5s 后水平间距 31m < 50m" }
  ]
}
```

规则（MVP）：

| 类型 | 判定 |
| --- | --- |
| `DRONE_DRONE` | 匀速外推 `lookaheadSeconds`，若最小水平间距 < `minHorizontalSeparationM` 且垂直间距 < `minVerticalSeparationM` → 冲突；severity 按最小间距分档 |
| `DRONE_ZONE` | 无人机位置或外推点落在禁飞区水平范围内，且高度落在 `[minAltitudeM, maxAltitudeM]` → 冲突 |
| `BATTERY_RISK` | `batteryPct` 低于阈值（默认 20%）→ 提示级冲突 |

## 5. 错误码（Java）

| code | 含义 |
| --- | --- |
| 0 | 成功 |
| 1001 | 参数校验失败 |
| 1002 | 资源不存在 |
| 1003 | 状态不允许该操作 |
| 1004 | 无可用无人机 |
| 2001 | 算法服务不可用 |
| 2002 | 路径规划失败（无可行路径） |
| 5000 | 系统内部错误 |
