-- =============================================================================
--  sky-dispatch · 低空经济无人机配送调度仿真平台
--  V1__init_schema.sql  核心表结构（PostgreSQL 14+）
--
--  设计说明（给初学者）：
--   1. 本文件不依赖 PostGIS，用普通 PostgreSQL 即可跑通，方便本地演示；
--      PostGIS 的地理增强（geometry 列 + GIST 索引 + 空间函数）见 db/postgis/。
--   2. 枚举一律用 VARCHAR + CHECK 约束，而不是 PostgreSQL 原生 ENUM 类型。
--      原因：原生 ENUM 增删值需要 ALTER TYPE，且 Hibernate/JPA 映射麻烦。
--   3. 经纬度用 DOUBLE PRECISION 存储，纬度范围 [-90,90]、经度 [-180,180]。
--      需要空间计算时再叠加 PostGIS 的 geometry 列，两者通过触发器保持同步。
--   4. 所有金额/距离/电量都用 NUMERIC，避免浮点误差；只有经纬度用 DOUBLE。
--   5. 每张业务表都有 created_at / updated_at，updated_at 由触发器自动维护。
--   6. 主键统一 BIGSERIAL（对应 JPA 的 GenerationType.IDENTITY）。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0. 触发器函数：自动维护 updated_at
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ---------------------------------------------------------------------------
-- 1. drone_model 无人机型号（字典表）
--    把"型号能力"和"单机状态"分开，是低空运营系统的基本建模方式：
--    换机型不需要改无人机记录，只是换个 model_id。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS drone_model (
    id                  BIGSERIAL     PRIMARY KEY,
    code                VARCHAR(32)   NOT NULL UNIQUE,               -- 型号编码，如 SKY-X4
    name                VARCHAR(64)   NOT NULL,                      -- 型号名称
    max_payload_kg      NUMERIC(6, 2) NOT NULL CHECK (max_payload_kg > 0),      -- 最大载重(kg)
    cruise_speed_mps    NUMERIC(6, 2) NOT NULL CHECK (cruise_speed_mps > 0),    -- 巡航速度(m/s)
    max_flight_min      INTEGER       NOT NULL CHECK (max_flight_min > 0),      -- 满电最长飞行(分钟)
    battery_capacity_wh NUMERIC(8, 2) NOT NULL CHECK (battery_capacity_wh > 0), -- 电池容量(Wh)
    energy_per_km_wh    NUMERIC(6, 3) NOT NULL CHECK (energy_per_km_wh > 0),    -- 巡航能耗(Wh/km)
    hover_power_w       NUMERIC(7, 2) NOT NULL DEFAULT 0,                       -- 悬停功率(W)
    max_altitude_m      INTEGER       NOT NULL DEFAULT 120,                     -- 最大升限(m)
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    remark              VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 2. drone 无人机（单机台账 + 实时状态）
--    status 的取值与 Java 枚举 DroneStatus 一一对应。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS drone (
    id                BIGSERIAL        PRIMARY KEY,
    code              VARCHAR(32)      NOT NULL UNIQUE,              -- 机身编号，如 UAV-003
    model_id          BIGINT           NOT NULL REFERENCES drone_model (id),
    status            VARCHAR(20)      NOT NULL DEFAULT 'IDLE'
        CHECK (status IN ('IDLE', 'ASSIGNED', 'IN_FLIGHT', 'RETURNING',
                          'CHARGING', 'MAINTENANCE', 'OFFLINE')),
    battery_pct       NUMERIC(5, 2)    NOT NULL DEFAULT 100
        CHECK (battery_pct BETWEEN 0 AND 100),                       -- 剩余电量百分比
    current_lat       DOUBLE PRECISION CHECK (current_lat BETWEEN -90 AND 90),
    current_lon       DOUBLE PRECISION CHECK (current_lon BETWEEN -180 AND 180),
    altitude_m        NUMERIC(7, 2)    NOT NULL DEFAULT 0,
    heading_deg       NUMERIC(5, 2)    CHECK (heading_deg BETWEEN 0 AND 360),
    speed_mps         NUMERIC(6, 2)    NOT NULL DEFAULT 0,
    home_lat          DOUBLE PRECISION,                              -- 起降点（机巢）
    home_lon          DOUBLE PRECISION,
    current_task_id   BIGINT,                                        -- 外键在文件末尾补（循环依赖）
    total_flight_min  NUMERIC(10, 2)   NOT NULL DEFAULT 0,           -- 累计飞行时长
    total_distance_km NUMERIC(12, 3)   NOT NULL DEFAULT 0,           -- 累计航程
    total_tasks       INTEGER          NOT NULL DEFAULT 0,           -- 累计完成任务数
    last_heartbeat_at TIMESTAMPTZ,
    version           BIGINT           NOT NULL DEFAULT 0,           -- JPA 乐观锁 @Version
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_drone_status ON drone (status);
CREATE INDEX IF NOT EXISTS idx_drone_model ON drone (model_id);

-- ---------------------------------------------------------------------------
-- 3. delivery_order 配送订单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS delivery_order (
    id                 BIGSERIAL        PRIMARY KEY,
    order_no           VARCHAR(40)      NOT NULL UNIQUE,             -- 业务单号，如 SO20260101001
    customer_name      VARCHAR(64)      NOT NULL,
    customer_phone     VARCHAR(32),
    cargo_name         VARCHAR(128)     NOT NULL,                    -- 货物名称
    weight_kg          NUMERIC(6, 2)    NOT NULL CHECK (weight_kg > 0),
    priority           VARCHAR(10)      NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status             VARCHAR(20)      NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'PLANNING', 'QUEUED', 'ASSIGNED',
                          'IN_FLIGHT', 'DELIVERED', 'FAILED', 'CANCELLED')),
    pickup_address     VARCHAR(255),
    pickup_lat         DOUBLE PRECISION  NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lon         DOUBLE PRECISION  NOT NULL CHECK (pickup_lon BETWEEN -180 AND 180),
    dropoff_address    VARCHAR(255),
    dropoff_lat        DOUBLE PRECISION  NOT NULL CHECK (dropoff_lat BETWEEN -90 AND 90),
    dropoff_lon        DOUBLE PRECISION  NOT NULL CHECK (dropoff_lon BETWEEN -180 AND 180),
    expect_delivery_at TIMESTAMPTZ,                                  -- 期望送达时间（准时率统计用）
    assigned_drone_id  BIGINT REFERENCES drone (id),
    fail_reason        VARCHAR(255),
    remark             VARCHAR(500),
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_status ON delivery_order (status);
CREATE INDEX IF NOT EXISTS idx_order_created ON delivery_order (created_at DESC);
-- 派单排序常用：先按优先级权重，再按创建时间（在服务层用 CASE 表达式实现权重）

-- ---------------------------------------------------------------------------
-- 4. airspace_zone 空域（禁飞区 / 限飞区 / 航路走廊）
--    MVP 用"中心点+半径"表示圆、用 WKT 文本表示多边形，避免强制依赖 PostGIS。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS airspace_zone (
    id             BIGSERIAL        PRIMARY KEY,
    code           VARCHAR(32)      NOT NULL UNIQUE,
    name           VARCHAR(128)     NOT NULL,
    zone_type      VARCHAR(16)      NOT NULL
        CHECK (zone_type IN ('NO_FLY', 'RESTRICTED', 'CORRIDOR', 'WARNING')),
    shape_type     VARCHAR(16)      NOT NULL
        CHECK (shape_type IN ('CIRCLE', 'POLYGON')),
    center_lat     DOUBLE PRECISION,                                 -- CIRCLE 用
    center_lon     DOUBLE PRECISION,
    radius_m       NUMERIC(10, 2),                                   -- CIRCLE 用
    polygon_wkt    VARCHAR(8000),                                    -- POLYGON 用，如 POLYGON((lon lat, ...))
    min_altitude_m NUMERIC(7, 2)    NOT NULL DEFAULT 0,              -- 生效高度下限
    max_altitude_m NUMERIC(7, 2)    NOT NULL DEFAULT 120,            -- 生效高度上限
    severity       VARCHAR(10)      NOT NULL DEFAULT 'HIGH'
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    effective_from TIMESTAMPTZ,                                      -- 临时空域管制时段
    effective_to   TIMESTAMPTZ,
    enabled        BOOLEAN          NOT NULL DEFAULT TRUE,
    remark         VARCHAR(255),
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_zone_shape CHECK (
        (shape_type = 'CIRCLE'  AND center_lat IS NOT NULL AND center_lon IS NOT NULL AND radius_m IS NOT NULL)
     OR (shape_type = 'POLYGON' AND polygon_wkt IS NOT NULL)
    ),
    CONSTRAINT ck_zone_altitude CHECK (max_altitude_m > min_altitude_m)
);

CREATE INDEX IF NOT EXISTS idx_zone_enabled ON airspace_zone (enabled, zone_type);

-- ---------------------------------------------------------------------------
-- 5. flight_route 航路（一次路径规划的结果头）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flight_route (
    id              BIGSERIAL        PRIMARY KEY,
    order_id        BIGINT           NOT NULL REFERENCES delivery_order (id),
    drone_id        BIGINT           REFERENCES drone (id),
    algorithm       VARCHAR(32)      NOT NULL DEFAULT 'ASTAR',
    status          VARCHAR(16)      NOT NULL DEFAULT 'PLANNED'
        CHECK (status IN ('PLANNED', 'ACTIVE', 'FINISHED', 'FAILED')),
    distance_m      NUMERIC(12, 2)   NOT NULL DEFAULT 0,             -- 航路总长度
    est_duration_s  INTEGER          NOT NULL DEFAULT 0,             -- 预计耗时
    est_energy_wh   NUMERIC(10, 3)   NOT NULL DEFAULT 0,             -- 预计能耗
    waypoint_count  INTEGER          NOT NULL DEFAULT 0,
    grid_size_m     NUMERIC(8, 2),                                   -- 算法栅格分辨率
    plan_elapsed_ms INTEGER,                                         -- 算法耗时（性能观测）
    raw_response    VARCHAR(8000),                                   -- 算法服务原始 JSON，排错用
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_route_order ON flight_route (order_id);
CREATE INDEX IF NOT EXISTS idx_route_drone ON flight_route (drone_id);

-- ---------------------------------------------------------------------------
-- 6. flight_waypoint 航路点（航路的明细，有序）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flight_waypoint (
    id                    BIGSERIAL        PRIMARY KEY,
    route_id              BIGINT           NOT NULL
        REFERENCES flight_route (id) ON DELETE CASCADE,
    seq                   INTEGER          NOT NULL,                 -- 从 0 开始
    lat                   DOUBLE PRECISION NOT NULL,
    lon                   DOUBLE PRECISION NOT NULL,
    altitude_m            NUMERIC(7, 2)    NOT NULL DEFAULT 80,
    cumulative_distance_m NUMERIC(12, 2)   NOT NULL DEFAULT 0,       -- 到起点的累计距离
    eta_seconds           INTEGER,                                   -- 预计到达时刻（相对起飞）
    UNIQUE (route_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_waypoint_route ON flight_waypoint (route_id, seq);

-- ---------------------------------------------------------------------------
-- 7. dispatch_task 配送任务（订单 × 无人机 × 航路的执行实例）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dispatch_task (
    id                 BIGSERIAL        PRIMARY KEY,
    task_no            VARCHAR(40)      NOT NULL UNIQUE,             -- 任务号，如 ST20260101001
    order_id           BIGINT           NOT NULL REFERENCES delivery_order (id),
    drone_id           BIGINT           NOT NULL REFERENCES drone (id),
    route_id           BIGINT           REFERENCES flight_route (id),
    strategy           VARCHAR(32)      NOT NULL DEFAULT 'NEAREST_AVAILABLE',
    status             VARCHAR(20)      NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ASSIGNED', 'TAKEOFF', 'CRUISING',
                          'DELIVERING', 'RETURNING', 'COMPLETED', 'ABORTED', 'FAILED')),
    progress_pct       NUMERIC(5, 2)    NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
    current_waypoint_seq INTEGER        NOT NULL DEFAULT 0,
    planned_distance_m NUMERIC(12, 2)   NOT NULL DEFAULT 0,
    actual_distance_m  NUMERIC(12, 2)   NOT NULL DEFAULT 0,
    planned_energy_wh  NUMERIC(10, 3)   NOT NULL DEFAULT 0,
    consumed_energy_wh NUMERIC(10, 3)   NOT NULL DEFAULT 0,
    battery_start_pct  NUMERIC(5, 2),
    battery_end_pct    NUMERIC(5, 2),
    assigned_at        TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    duration_s         INTEGER,
    fail_reason        VARCHAR(255),
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_status ON dispatch_task (status);
CREATE INDEX IF NOT EXISTS idx_task_drone ON dispatch_task (drone_id, status);
CREATE INDEX IF NOT EXISTS idx_task_order ON dispatch_task (order_id);

-- 循环外键：drone.current_task_id → dispatch_task.id
ALTER TABLE drone
    DROP CONSTRAINT IF EXISTS fk_drone_current_task;
ALTER TABLE drone
    ADD CONSTRAINT fk_drone_current_task
    FOREIGN KEY (current_task_id) REFERENCES dispatch_task (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 8. telemetry 遥测（时序数据，写入最频繁）
--    注意：这张表会长得很快。生产环境建议：
--      - 按月做 RANGE 分区（PARTITION BY RANGE (ts)），见文件末尾注释；
--      - 或直接写入 TimescaleDB / ClickHouse。
--    MVP 先用单表 + BRIN 索引，足够跑百万级。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS telemetry (
    id           BIGSERIAL        PRIMARY KEY,
    run_id       BIGINT,                                             -- 所属仿真运行
    task_id      BIGINT,
    drone_id     BIGINT           NOT NULL,
    ts           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    sim_time_s   NUMERIC(14, 2),                                     -- 仿真时间（秒）
    step_index   BIGINT,                                             -- 第几个 tick
    lat          DOUBLE PRECISION,
    lon          DOUBLE PRECISION,
    altitude_m   NUMERIC(7, 2),
    speed_mps    NUMERIC(6, 2),
    heading_deg  NUMERIC(5, 2),
    battery_pct  NUMERIC(5, 2),
    progress_pct NUMERIC(5, 2)
);

CREATE INDEX IF NOT EXISTS idx_telemetry_drone_ts ON telemetry (drone_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_telemetry_task ON telemetry (task_id);
CREATE INDEX IF NOT EXISTS idx_telemetry_run ON telemetry (run_id);
-- BRIN 索引体积极小，适合只按时间范围扫描的追加型时序表
CREATE INDEX IF NOT EXISTS idx_telemetry_ts_brin ON telemetry USING BRIN (ts);

-- ---------------------------------------------------------------------------
-- 9. conflict_event 冲突事件（无人机互相冲突 / 闯入禁飞区 / 电量与超时风险）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conflict_event (
    id                    BIGSERIAL      PRIMARY KEY,
    run_id                BIGINT,
    task_id               BIGINT,
    drone_a_id            BIGINT         NOT NULL,
    drone_b_id            BIGINT,                                    -- 机-机冲突时才有
    zone_id               BIGINT,                                    -- 机-区冲突时才有
    conflict_type         VARCHAR(20)    NOT NULL
        CHECK (conflict_type IN ('DRONE_DRONE', 'DRONE_ZONE', 'DEADLINE_RISK', 'BATTERY_RISK')),
    severity              VARCHAR(10)    NOT NULL DEFAULT 'MEDIUM'
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status                VARCHAR(16)    NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    horizontal_distance_m NUMERIC(10, 2),
    vertical_distance_m   NUMERIC(10, 2),
    closing_speed_mps     NUMERIC(8, 2),
    time_to_conflict_s    NUMERIC(10, 2),
    lat                   DOUBLE PRECISION,
    lon                   DOUBLE PRECISION,
    detected_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    resolution            VARCHAR(255),
    resolved_at           TIMESTAMPTZ,
    detail                VARCHAR(8000)
);

CREATE INDEX IF NOT EXISTS idx_conflict_status ON conflict_event (status, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_conflict_drone ON conflict_event (drone_a_id);

-- ---------------------------------------------------------------------------
-- 10. simulation_run 仿真运行（一次"沙盘推演"）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS simulation_run (
    id           BIGSERIAL        PRIMARY KEY,
    name         VARCHAR(128)     NOT NULL,
    status       VARCHAR(16)      NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'STOPPED', 'FINISHED')),
    tick_ms      INTEGER          NOT NULL DEFAULT 1000 CHECK (tick_ms > 0),   -- 每 tick 的真实毫秒
    speed_factor NUMERIC(6, 2)    NOT NULL DEFAULT 1 CHECK (speed_factor > 0), -- 仿真倍速
    random_seed  BIGINT,
    sim_time_s   NUMERIC(14, 2)   NOT NULL DEFAULT 0,                -- 已推进的仿真秒数
    tick_count   BIGINT           NOT NULL DEFAULT 0,
    config       VARCHAR(8000),                                      -- 运行参数 JSON
    started_at   TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 11. event_log 事件日志（时间线 / 回放 / 审计）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_log (
    id         BIGSERIAL      PRIMARY KEY,
    run_id     BIGINT,
    task_id    BIGINT,
    order_id   BIGINT,
    drone_id   BIGINT,
    event_type VARCHAR(32)    NOT NULL,      -- ORDER_CREATED / TASK_ASSIGNED / CONFLICT_DETECTED ...
    severity   VARCHAR(10)    NOT NULL DEFAULT 'INFO'
        CHECK (severity IN ('DEBUG', 'INFO', 'WARN', 'ERROR')),
    message    VARCHAR(500)   NOT NULL,
    payload    VARCHAR(8000),                -- 附加 JSON，回放时还原上下文
    sim_time_s NUMERIC(14, 2),
    ts         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_event_ts ON event_log (ts DESC);
CREATE INDEX IF NOT EXISTS idx_event_task ON event_log (task_id, ts);
CREATE INDEX IF NOT EXISTS idx_event_type ON event_log (event_type);

-- ---------------------------------------------------------------------------
-- 12. dispatch_decision 调度决策明细（"为什么选了这架无人机"）
--     把评分过程落库，是调度系统可解释、可复盘的关键。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dispatch_decision (
    id              BIGSERIAL      PRIMARY KEY,
    order_id        BIGINT         NOT NULL,                        -- 不设外键，允许订单删除后留痕
    run_id          BIGINT,
    strategy        VARCHAR(32)    NOT NULL,
    drone_id        BIGINT,
    score           NUMERIC(12, 4),                                 -- 综合得分，越小越优先
    rank_no         INTEGER,
    selected        BOOLEAN        NOT NULL DEFAULT FALSE,
    rejected_reason VARCHAR(255),                                   -- 载重不足 / 电量不够 / 忙碌 ...
    detail          VARCHAR(8000),                                  -- 各因子分项 JSON
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_decision_order ON dispatch_decision (order_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 13. updated_at 自动维护触发器（逐表挂载）
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['drone_model', 'drone', 'delivery_order', 'airspace_zone',
                             'flight_route', 'dispatch_task', 'simulation_run']
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%1$s_updated_at ON %1$s', t);
        EXECUTE format(
            'CREATE TRIGGER trg_%1$s_updated_at BEFORE UPDATE ON %1$s
             FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at()', t);
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- 14. 视图：给看板和报表用的宽表
-- ---------------------------------------------------------------------------
-- 14.1 无人机实时快照
CREATE OR REPLACE VIEW v_drone_live AS
SELECT d.id            AS drone_id,
       d.code          AS drone_code,
       m.code          AS model_code,
       m.name          AS model_name,
       m.max_payload_kg,
       m.cruise_speed_mps,
       d.status,
       d.battery_pct,
       d.current_lat,
       d.current_lon,
       d.altitude_m,
       d.speed_mps,
       d.heading_deg,
       d.current_task_id,
       t.task_no,
       t.status        AS task_status,
       t.progress_pct,
       o.order_no,
       o.priority,
       d.last_heartbeat_at
FROM drone d
         JOIN drone_model m ON m.id = d.model_id
         LEFT JOIN dispatch_task t ON t.id = d.current_task_id
         LEFT JOIN delivery_order o ON o.id = t.order_id;

-- 14.2 看板 KPI（单行结果，前端一次查询即可）
CREATE OR REPLACE VIEW v_dashboard_kpi AS
SELECT (SELECT count(*) FROM delivery_order)                                        AS total_orders,
       (SELECT count(*) FROM delivery_order WHERE status IN ('CREATED', 'PLANNING', 'QUEUED')) AS pending_orders,
       (SELECT count(*) FROM delivery_order WHERE status IN ('ASSIGNED', 'IN_FLIGHT'))          AS in_flight_orders,
       (SELECT count(*) FROM delivery_order WHERE status = 'DELIVERED')               AS delivered_orders,
       (SELECT count(*) FROM delivery_order WHERE status = 'FAILED')                  AS failed_orders,
       (SELECT count(*) FROM dispatch_task)                                           AS total_tasks,
       (SELECT count(*) FROM dispatch_task WHERE status = 'COMPLETED')                AS completed_tasks,
       (SELECT count(*) FROM drone WHERE status IN ('ASSIGNED', 'IN_FLIGHT', 'RETURNING'))     AS active_drones,
       (SELECT count(*) FROM drone)                                                   AS total_drones,
       (SELECT round(avg(battery_pct), 2) FROM drone)                                 AS avg_battery_pct,
       (SELECT round(avg(EXTRACT(EPOCH FROM (finished_at - started_at)))::numeric, 2)
        FROM dispatch_task WHERE status = 'COMPLETED')                                AS avg_delivery_seconds,
       (SELECT round(
                       count(*) FILTER (WHERE o.expect_delivery_at IS NULL OR t.finished_at <= o.expect_delivery_at)::numeric
                           / NULLIF(count(*), 0), 4)
        FROM dispatch_task t
                 JOIN delivery_order o ON o.id = t.order_id
        WHERE t.status = 'COMPLETED')                                                 AS on_time_rate,
       (SELECT count(*) FROM conflict_event WHERE status = 'OPEN')                     AS open_conflicts;

-- ---------------------------------------------------------------------------
-- 15. 生产环境可选：telemetry 按月分区（MVP 不启用，保留作演进参考）
-- ---------------------------------------------------------------------------
-- CREATE TABLE telemetry_202601 PARTITION OF telemetry
--     FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
