-- =============================================================================
-- V3 · 充电站 + 状态枚举补齐
--
--  1) 新增 charging_station 表（无人机中途补能的落脚点，见 BatteryRelayService）
--  2) dispatch_task.status 增加 CHARGING：长航程任务里无人机会自主改航到站补能，
--     停在桩上充电的这段时间就是一个新状态
--  3) airspace_zone.zone_type 增加 BUILDING：建筑障碍（高楼）与"禁飞区"语义不同，
--     它可以用 max_altitude_m 表达楼高，无人机飞过楼顶即可
--
--  说明：dev（H2）由 JPA 反推建表、不跑 Flyway，所以这份脚本只服务 postgres profile；
--  但两边的枚举取值必须一致，否则"dev 能跑、上真库就报约束冲突"。
-- =============================================================================

CREATE TABLE IF NOT EXISTS charging_station (
    id            BIGSERIAL     PRIMARY KEY,
    code          VARCHAR(32)   NOT NULL UNIQUE,   -- 站点编码，如 CS-SH-01
    name          VARCHAR(64)   NOT NULL,          -- 站点名称
    city          VARCHAR(32)   NOT NULL,          -- 所在城市（看板分组用）
    lat           DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon           DOUBLE PRECISION NOT NULL CHECK (lon BETWEEN -180 AND 180),
    charge_slots  INTEGER       NOT NULL DEFAULT 1 CHECK (charge_slots > 0),
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    remark        VARCHAR(200),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_charging_station_city ON charging_station (city);

COMMENT ON TABLE charging_station IS '充电站：无人机中途补能点，决定运力半径';

-- ---- 状态枚举补齐：先删旧约束再建新约束（PostgreSQL 不允许就地改 CHECK） ----
ALTER TABLE dispatch_task DROP CONSTRAINT IF EXISTS dispatch_task_status_check;
ALTER TABLE dispatch_task ADD CONSTRAINT dispatch_task_status_check
    CHECK (status IN ('PENDING', 'ASSIGNED', 'TAKEOFF', 'CRUISING',
                      'DELIVERING', 'RETURNING', 'CHARGING',
                      'COMPLETED', 'ABORTED', 'FAILED'));

ALTER TABLE airspace_zone DROP CONSTRAINT IF EXISTS airspace_zone_zone_type_check;
ALTER TABLE airspace_zone ADD CONSTRAINT airspace_zone_zone_type_check
    CHECK (zone_type IN ('NO_FLY', 'RESTRICTED', 'CORRIDOR', 'WARNING', 'BUILDING'));
