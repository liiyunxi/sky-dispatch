# 数据库设计与使用说明

> 对应文件：`db/migration/V1__init_schema.sql`（表结构）、`db/migration/V2__seed_data.sql`（演示数据）、
> `db/postgis/V3__postgis_enrichment.sql`（可选空间增强）

## 1. 表清单与职责

| # | 表 | 职责 | 关键设计 |
| --- | --- | --- | --- |
| 1 | `drone_model` | 机型能力字典 | 把"机型能力"与"单机状态"分开，换机型只改 `model_id` |
| 2 | `drone` | 无人机台账 + 实时状态 | `version` 乐观锁；`current_task_id` 反向指向任务（循环外键，建表后补） |
| 3 | `delivery_order` | 配送订单 | 起终点各存"地址文本 + 经纬度"，地址给人看、经纬度给算法算 |
| 4 | `airspace_zone` | 禁飞区/限飞区/走廊 | 圆用 `center+radius_m`，多边形用 `polygon_wkt`，高度区间限定生效范围 |
| 5 | `flight_route` | 航路头（一次规划的产物） | 存 `raw_response`，算法服务返回什么就留什么，便于排错 |
| 6 | `flight_waypoint` | 航路点明细 | `(route_id, seq)` 唯一，`ON DELETE CASCADE` |
| 7 | `dispatch_task` | 执行实例（订单×无人机×航路） | 一个订单可有多条任务（失败可重派），取"最新一条"为当前执行 |
| 8 | `telemetry` | 时序遥测 | BRIN 索引 + 分区演进方案，写入最频繁 |
| 9 | `conflict_event` | 冲突事件 | 机-机 / 机-区 / 超时 / 低电量四类，带严重度与处理状态 |
| 10 | `simulation_run` | 仿真运行 | 把一次沙盘推演的所有数据串到同一个 runId 上 |
| 11 | `event_log` | 事件时间线 | 回放与审计的唯一来源，`payload` 存上下文 JSON |
| 12 | `dispatch_decision` | 调度决策明细 | 得分/排名/淘汰原因，回答"为什么选它" |

### 实体关系

```
drone_model 1──n drone
                  │
delivery_order 1──n dispatch_task n──1 drone
       │                 │
       │                 └──1 flight_route 1──n flight_waypoint
       │
       └──1──n flight_route

airspace_zone ──(按高度/形状参与规划)──▶ conflict_event
simulation_run 1──n telemetry / event_log / conflict_event
```

## 2. 几个刻意的设计决定

### 2.1 枚举用 VARCHAR + CHECK，不用 PostgreSQL 原生 ENUM

```sql
status VARCHAR(20) NOT NULL DEFAULT 'IDLE'
    CHECK (status IN ('IDLE','ASSIGNED','IN_FLIGHT', ...))
```

原因有三条：

1. 原生 ENUM 加一个值要 `ALTER TYPE ... ADD VALUE`，而且在老版本里不能在事务里执行；
2. Hibernate/JPA 映射原生 ENUM 很别扭，要自己写 `UserType`；
3. `VARCHAR + CHECK` 的约束强度足够，且任何工具（Navicat、DataGrip、psql）都能直接改。

**代价**：改枚举值需要 `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT`，所以每个枚举值都一次性定全。

### 2.2 核心表不依赖 PostGIS

几何信息用两种方式表达：

- 圆：`center_lat / center_lon / radius_m` 三个数值列；
- 多边形：`polygon_wkt` 文本（标准 WKT，`POLYGON((lon lat, ...))`）。

好处是纯 PostgreSQL 甚至 H2 都能跑通整套演示，`db/postgis/V3__postgis_enrichment.sql`
再按需叠加真正的空间能力：`geometry(Point,4326)` 列 + GIST 索引 + 同步触发器 +
`fn_nearby_idle_drones()`、`fn_route_blocked_zones()` 两个空间查询函数。

需要空间索引时再上，属于"演进"而不是"前置依赖"。

### 2.3 遥测表按时间追加，索引也按追加优化

```sql
CREATE INDEX idx_telemetry_drone_ts ON telemetry (drone_id, ts DESC);  -- 查单机轨迹
CREATE INDEX idx_telemetry_ts_brin  ON telemetry USING BRIN (ts);      -- 按时间范围扫
```

B 树索引适合"按 key 精确查"，BRIN 索引体积极小（几十 KB 就能覆盖上亿行），
适合"只按时间范围顺序扫"的时序表。生产环境再按 `ts` 做 RANGE 分区（脚本末尾留了模板）。

### 2.4 `updated_at` 由触发器维护，不靠应用层

```sql
CREATE TRIGGER trg_drone_updated_at BEFORE UPDATE ON drone
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
```

这样即使有人直接用 psql 改数据、或以后换了别的服务写入，`updated_at` 也不会失真。

### 2.5 审计表不设外键

`dispatch_decision.order_id`、`conflict_event.drone_a_id` 等**故意不加外键**：
它们是"历史事实"，即使订单被归档、无人机被删除，也要保留下来。
外键在这里会变成"为了让审计记录活着而不敢删业务数据"的枷锁。

## 3. 视图

| 视图 | 用途 |
| --- | --- |
| `v_drone_live` | 无人机 + 机型 + 当前任务 + 订单的宽表，看板地图一次查完 |
| `v_dashboard_kpi` | 单行 KPI（订单/任务/机队/准时率/冲突数），备选方案 |

注意：Java 侧的 `DashboardService` 目前**在应用层计算 KPI**，没有直接用这个视图。
两个方案并存是刻意的——MVP 阶段指标口径常变，Java 改一行就能上线；
数据量变大后再切到视图或物化视图（`REFRESH MATERIALIZED VIEW CONCURRENTLY`）。

## 4. 迁移工作流（Flyway）

```
db/migration/V1__init_schema.sql     ← 表结构
db/migration/V2__seed_data.sql       ← 演示数据（幂等，可重复执行）
```

- 脚本位置是**仓库顶层的 `db/migration`**（数据库资产不进 Java 源码树）；
- `backend/pom.xml` 用 `maven-resources-plugin` 把该目录打进 `classpath:db/migration`，
  所以 **SQL 只有一份**，构建时复制，不做源码拷贝；
- `postgres` profile 才会启用 Flyway，`dev` profile 用 H2 + `ddl-auto: create-drop`；
- 新增变更请**追加** `V3__xxx.sql`，不要改动已执行过的 V1/V2（校验和会变，Flyway 会拒绝启动）。

### 本地起库

```bash
docker compose up -d          # 启动 PostGIS（见仓库根 docker-compose.yml）
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

没有 Docker 时手工执行：

```sql
CREATE ROLE sky LOGIN PASSWORD 'sky123456';
CREATE DATABASE sky_dispatch OWNER sky;
\c sky_dispatch
-- 可选：CREATE EXTENSION IF NOT EXISTS postgis;
```

## 5. 常见运维 SQL

```sql
-- 当前在飞的飞机与任务
SELECT d.code, d.status, d.battery_pct, t.task_no, t.progress_pct, o.order_no
FROM drone d
         LEFT JOIN dispatch_task t ON t.id = d.current_task_id
         LEFT JOIN delivery_order o ON o.id = t.order_id
WHERE d.status <> 'IDLE';

-- 准时率与平均配送时长
SELECT count(*) FILTER (WHERE t.finished_at <= o.expect_delivery_at)::numeric
           / NULLIF(count(*), 0)                       AS on_time_rate,
       round(avg(EXTRACT(EPOCH FROM (t.finished_at - t.started_at))))  AS avg_seconds
FROM dispatch_task t JOIN delivery_order o ON o.id = t.order_id
WHERE t.status = 'COMPLETED';

-- 未处理冲突（按严重度）
SELECT severity, conflict_type, count(*)
FROM conflict_event WHERE status = 'OPEN'
GROUP BY severity, conflict_type ORDER BY 3 DESC;

-- 某订单的调度决策链（复盘"为什么派这架"）
SELECT drone_id, score, rank_no, selected, rejected_reason
FROM dispatch_decision WHERE order_id = 1 ORDER BY rank_no NULLS LAST;

-- 遥测数据量趋势（决定何时上分区）
SELECT date_trunc('hour', ts) AS hour, count(*) FROM telemetry GROUP BY 1 ORDER BY 1 DESC LIMIT 24;
```
