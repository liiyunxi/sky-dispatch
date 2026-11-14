# sky-dispatch 算法服务（algorithm-service）

低空无人机配送调度仿真平台的**算法服务**：用 FastAPI 提供 A* 路径规划与冲突检测能力。

- **无状态纯计算**：不访问数据库，也不反向调用 Java 后端。
- **纯标准库算法**：A*、栅格离散化、几何判定全部用 Python 标准库实现，**不引入 numpy / shapely / networkx**。
- **接口契约**：请求/响应字段名与 `docs/03-api-contract.md` **第 4 节逐字一致**（camelCase）。
- **失败不 5xx**：规划无路可走、起点在禁飞区等都属于**业务结果**，返回 HTTP 200 + `found=false` + 原因码，便于 Java 侧降级。

---

## 1. 快速开始

Windows PowerShell：

```powershell
# uv 默认缓存目录可能在沙箱里不可写，先把缓存指到工作区内
$env:UV_CACHE_DIR='D:\work-project\sky-dispatch\.uv-cache'

uv venv --python 'D:\uv\python\cpython-3.13.15-windows-x86_64-none\python.exe'
uv sync

# 运行测试
.\.venv\Scripts\python.exe -m pytest -q

# 启动服务
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
```

启动后：

| 地址 | 说明 |
| --- | --- |
| `http://127.0.0.1:8000/health` | 健康检查 |
| `http://127.0.0.1:8000/` | 服务简介与接口清单 |
| `http://127.0.0.1:8000/docs` | Swagger UI（可交互调试） |
| `http://127.0.0.1:8000/openapi.json` | OpenAPI 结构 |

---

## 2. 接口

### 2.1 `GET /health`

```json
{ "status": "ok", "service": "sky-dispatch-algorithm", "version": "0.1.0", "python": "3.13.15" }
```

### 2.2 `POST /api/v1/path/plan` —— A* 路径规划

请求（`requestId` 可选，未传则服务端生成 uuid4 并回显）：

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

响应：

```json
{
  "success": true,
  "requestId": "b1f2...",
  "algorithm": "ASTAR",
  "found": true,
  "message": "ok",
  "distanceM": 2380.5,
  "waypoints": [
    { "seq": 0, "lat": 31.2304, "lon": 121.4737, "altitudeM": 80.0, "cumulativeDistanceM": 0.0 }
  ],
  "expandedNodes": 812,
  "elapsedMs": 12.3
}
```

`message` 取值：

| message | 含义 |
| --- | --- |
| `ok` | 规划成功 |
| `start_inside_obstacle` | 起点落在禁飞区内（`found=false`） |
| `goal_inside_obstacle` | 终点落在禁飞区内（`found=false`） |
| `no_path_found` | 被障碍物完全围死，无可行航线（`found=false`） |
| `max_search_nodes_exceeded` | 展开节点数超过 `maxSearchNodes`（`found=false`） |
| `internal_error` | 算法内部异常（`success=false`） |

### 2.3 `POST /api/v1/conflict/check` —— 冲突检测

三类冲突：

| conflictType | 判定 | severity |
| --- | --- | --- |
| `DRONE_DRONE` | 按航向与速度匀速外推（1s 步长、覆盖 `lookaheadSeconds`），最小水平间距 < `minHorizontalSeparationM` **且** 最小垂直间距 < `minVerticalSeparationM` | <0.5×阈值 → `CRITICAL`；<0.8×阈值 → `HIGH`；否则 `MEDIUM` |
| `DRONE_ZONE` | 当前位置或外推点落入区域水平范围，且高度在 `[minAltitudeM, maxAltitudeM]` 内 | `NO_FLY` → `CRITICAL`；`RESTRICTED` → `HIGH`；其它 → `MEDIUM` |
| `BATTERY_RISK` | `batteryPct` < 20 → `LOW`；< 10 → `MEDIUM` | 同左 |

响应字段（严格遵循契约）：

```json
{
  "success": true,
  "requestId": "b1f2...",
  "conflictCount": 1,
  "conflicts": [
    { "conflictType": "DRONE_DRONE", "severity": "HIGH",
      "droneAId": 3, "droneBId": 5, "zoneId": null,
      "horizontalDistanceM": 31.2, "verticalDistanceM": 0.0,
      "closingSpeedMps": 11.4, "timeToConflictS": 12.5,
      "lat": 31.2349, "lon": 121.4805,
      "message": "UAV-003 与 UAV-005 预计 12.5s 后水平间距 31m，低于 50m 安全阈值（垂直间距 0m，接近率 11.4m/s）" }
  ],
  "message": "ok",
  "elapsedMs": 1.2
}
```

无冲突时 `conflicts` 为空数组、`conflictCount` 为 0。

> 契约 4.3 未定义 `requestId` / `message` / `elapsedMs`，本服务额外返回，
> 均为**追加字段**，不影响既有字段名与语义。

---

## 3. 算法说明

### 3.1 坐标系（`app/algorithms/geo.py`）

- **等距圆柱投影**：以航线起点为原点，把经纬度线性映射成局部米制平面坐标：
  - `x = (lon − lon₀) × 111320 × cos(lat₀)`
  - `y = (lat − lat₀) × 111132`

  几公里范围内误差可忽略，且提供 `local_to_latlon` 逆变换。
- `haversine_m`：球面大圆距离，地球半径 `6371008.8 m`（累计里程、间距判定用）。
- `bearing_deg`：大圆初始方位角。
- `point_in_polygon`：射线法。
- `segment_intersects_circle`：点到线段最短距离 ≤ 半径。

### 3.2 A*（`app/algorithms/grid.py` + `astar.py`）

1. **栅格离散化**：先按「起点 + 终点 + 生效（高度区间命中）且**落在航线走廊内**的障碍物
   （膨胀后）」求外接矩形，向外留 `pad ≥ 5` 格余量，再按 `gridSizeM`（默认 50m）切格。
   走廊半宽 `ROUTE_CORRIDOR_M = 2000m`：演示/生产空域是全国分布的（上海、北京、深圳、成都……），
   若不做这层过滤，外接矩形会被拉到国土尺度，栅格数达上亿级（实测把服务内存吃到 7GB 后卡死）；
   远离航线的障碍物不可能影响本段航路，过滤掉既安全又必需。
   同时有 `MAX_GRID_CELLS = 2_000_000` 的规模守卫：超过即返回 `grid_too_large` 失败，
   绝不分配超大数组。
2. **障碍膨胀**：按 `keepOutMarginM`（默认 30m）膨胀，覆盖到的栅格标记为不可通行。
3. **高度过滤（低空三维空域的关键简化）**：若 `altitudeM` 不落在障碍物的
   `[minAltitudeM, maxAltitudeM]` 内，该障碍物**对本次规划完全不生效**
   —— 这就是无人机可以「从禁飞区上方飞过」的建模方式。
4. **搜索**：8 邻域，直行代价 1、对角代价 √2（乘 `gridSizeM` 得米）；
   启发函数用欧氏距离（栅格单位，可采纳 → 保证最优）；
   `heapq` 实现开放列表，`g_score` / `came_from` 字典回溯路径；
   统计 `expandedNodes`，超过 `maxSearchNodes`（默认 200000）判失败。
5. **对角防穿角**：只有两个正交邻居都可通行时才允许走对角。
6. **收尾**：首尾航路点精确替换为原始起终点坐标（不受栅格中心影响）；
   `smooth=true` 时做**视线法（line-of-sight）贪心平滑**，
   碰撞判定复用同一张 blocked 栅格 → 平滑后航线**一定不会穿越膨胀障碍**。

### 3.3 冲突检测（`app/algorithms/conflict.py`）

- 机-机在局部平面做匀速外推，逐秒求水平/垂直间距最小值，
  `timeToConflictS` = 首次**同时**突破两个阈值的时刻，
  `closingSpeedMps` = 两机速度矢量在连线方向上的接近率。
- 机-区先判当前位置（`timeToConflictS = 0`），再判外推点。
- 全部 `message` 均为中文，可直接展示或落库。

---

## 4. 配置（环境变量）

用 `pydantic-settings` 读取，前缀 `ALGO_`，也可写进 `.env`：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ALGO_HOST` | `0.0.0.0` | 监听地址 |
| `ALGO_PORT` | `8000` | 监听端口 |
| `ALGO_LOG_LEVEL` | `INFO` | 日志级别 |
| `ALGO_DEFAULT_GRID_SIZE_M` | `50.0` | 默认栅格边长（米） |
| `ALGO_DEFAULT_KEEP_OUT_MARGIN_M` | `30.0` | 默认膨胀安全间隔（米） |
| `ALGO_DEFAULT_MAX_SEARCH_NODES` | `200000` | 默认最大展开节点数 |
| `ALGO_DEFAULT_SMOOTH` | `true` | 默认是否平滑 |
| `ALGO_BBOX_PAD_CELLS` | `5` | 外接矩形额外预留栅格数（下限 5） |
| `ALGO_DEFAULT_LOOKAHEAD_SECONDS` | `60.0` | 冲突外推时长（秒） |
| `ALGO_DEFAULT_MIN_HORIZONTAL_SEPARATION_M` | `50.0` | 最小水平安全间隔（米） |
| `ALGO_DEFAULT_MIN_VERTICAL_SEPARATION_M` | `15.0` | 最小垂直安全间隔（米） |
| `ALGO_BATTERY_WARN_PCT` | `20.0` | 低电量提示阈值（%） |
| `ALGO_BATTERY_CRITICAL_PCT` | `10.0` | 低电量警告阈值（%） |
| `ALGO_EXTRAPOLATION_STEP_S` | `1.0` | 冲突外推步长（秒） |

> 说明：请求体里传了 `gridSizeM` / `maxSearchNodes` / `keepOutMarginM` / `smooth`
> 时以**请求值**为准，环境变量只作为默认值来源。

---

## 5. 目录结构

```
algorithm-service/
├─ pyproject.toml            # 依赖与 pytest 配置（虚拟工程，无 [build-system]）
├─ README.md
├─ .python-version           # 3.13
├─ app/
│  ├─ main.py                # FastAPI 实例、CORS、路由注册、启动日志
│  ├─ core/config.py         # pydantic-settings 配置
│  ├─ schemas/               # common / path / conflict 数据模型（camelCase）
│  ├─ algorithms/            # geo / grid / astar / conflict
│  └─ api/                   # health / path / conflict 路由
└─ tests/                    # pytest 用例（几何、A*、冲突、API 端到端）
```

---

## 6. 测试覆盖

`tests/` 覆盖：

- **几何**：投影正反变换、haversine、方位角、射线法、点到线段/线段与圆。
- **A***：直线可达、绕圆形禁飞区、绕多边形禁飞区、起点/终点在禁飞区内、
  被围死 `no_path_found`、`max_search_nodes_exceeded`、
  平滑后仍不穿越膨胀障碍、高度过滤（区间外不生效）、膨胀余量影响、累计距离单调。
- **冲突**：机-机命中与不命中、三种 severity 分档、垂直间隔豁免、
  机-区（圆/多边形/当前侵入/外推侵入/RESTRICTED）、低电量阈值、
  无冲突返回空数组。
- **API**：`GET /`、`GET /health`、两个 POST 接口的端到端调用与字段名校验、
  失败仍返回 200、参数校验 422、CORS 预检。

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```
