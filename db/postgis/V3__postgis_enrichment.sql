-- =============================================================================
--  sky-dispatch · PostGIS 增强（可选）
--
--  什么时候需要：当你要做"附近有哪些无人机""航线是否穿越禁飞区""最近起降点"
--  这类空间查询时，用 PostGIS 的 geometry + GIST 索引比在应用层算距离快几个数量级。
--
--  用不用都行：核心表 V1 不依赖 PostGIS，纯 PostgreSQL 也能跑完整 Demo。
--  本文件独立于 Flyway 迁移（放在 db/postgis/，不参与自动迁移），需要时手工执行：
--      psql -h localhost -U sky -d sky_dispatch -f db/postgis/V3__postgis_enrichment.sql
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------------------
-- 1. 给 drone / delivery_order 增加点几何列（SRID 4326 = WGS84 经纬度）
-- ---------------------------------------------------------------------------
ALTER TABLE drone
    ADD COLUMN IF NOT EXISTS current_geom geometry(Point, 4326),
    ADD COLUMN IF NOT EXISTS home_geom    geometry(Point, 4326);

ALTER TABLE delivery_order
    ADD COLUMN IF NOT EXISTS pickup_geom  geometry(Point, 4326),
    ADD COLUMN IF NOT EXISTS dropoff_geom geometry(Point, 4326);

ALTER TABLE airspace_zone
    ADD COLUMN IF NOT EXISTS geom geometry(Geometry, 4326);

ALTER TABLE flight_route
    ADD COLUMN IF NOT EXISTS path_geom geometry(LineString, 4326);

-- ---------------------------------------------------------------------------
-- 2. 触发器：把经纬度 / WKT 同步成 geometry，应用层不用关心
--    ST_MakePoint 的参数顺序是 (经度, 纬度)，很容易写反，务必注意。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_sync_drone_geom() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.current_lat IS NOT NULL AND NEW.current_lon IS NOT NULL THEN
        NEW.current_geom := ST_SetSRID(ST_MakePoint(NEW.current_lon, NEW.current_lat), 4326);
    END IF;
    IF NEW.home_lat IS NOT NULL AND NEW.home_lon IS NOT NULL THEN
        NEW.home_geom := ST_SetSRID(ST_MakePoint(NEW.home_lon, NEW.home_lat), 4326);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_drone_geom ON drone;
CREATE TRIGGER trg_drone_geom
    BEFORE INSERT OR UPDATE OF current_lat, current_lon, home_lat, home_lon ON drone
    FOR EACH ROW EXECUTE FUNCTION fn_sync_drone_geom();

CREATE OR REPLACE FUNCTION fn_sync_order_geom() RETURNS TRIGGER AS $$
BEGIN
    NEW.pickup_geom  := ST_SetSRID(ST_MakePoint(NEW.pickup_lon, NEW.pickup_lat), 4326);
    NEW.dropoff_geom := ST_SetSRID(ST_MakePoint(NEW.dropoff_lon, NEW.dropoff_lat), 4326);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_order_geom ON delivery_order;
CREATE TRIGGER trg_order_geom
    BEFORE INSERT OR UPDATE OF pickup_lat, pickup_lon, dropoff_lat, dropoff_lon ON delivery_order
    FOR EACH ROW EXECUTE FUNCTION fn_sync_order_geom();

CREATE OR REPLACE FUNCTION fn_sync_zone_geom() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.shape_type = 'CIRCLE' THEN
        NEW.geom := ST_SetSRID(ST_Buffer(ST_MakePoint(NEW.center_lon, NEW.center_lat)::geography,
                                         NEW.radius_m)::geometry, 4326);
    ELSIF NEW.shape_type = 'POLYGON' AND NEW.polygon_wkt IS NOT NULL THEN
        NEW.geom := ST_SetSRID(ST_GeomFromText(NEW.polygon_wkt), 4326);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_zone_geom ON airspace_zone;
CREATE TRIGGER trg_zone_geom
    BEFORE INSERT OR UPDATE ON airspace_zone
    FOR EACH ROW EXECUTE FUNCTION fn_sync_zone_geom();

-- 回填历史数据
UPDATE drone SET current_geom = ST_SetSRID(ST_MakePoint(current_lon, current_lat), 4326)
WHERE current_lat IS NOT NULL AND current_geom IS NULL;
UPDATE airspace_zone SET geom = CASE
    WHEN shape_type = 'CIRCLE'
        THEN ST_SetSRID(ST_Buffer(ST_MakePoint(center_lon, center_lat)::geography, radius_m)::geometry, 4326)
    ELSE ST_SetSRID(ST_GeomFromText(polygon_wkt), 4326) END
WHERE geom IS NULL;

-- ---------------------------------------------------------------------------
-- 3. GIST 空间索引：让 ST_DWithin / ST_Intersects 走索引而不是全表扫描
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_drone_current_geom ON drone USING GIST (current_geom);
CREATE INDEX IF NOT EXISTS idx_drone_home_geom    ON drone USING GIST (home_geom);
CREATE INDEX IF NOT EXISTS idx_order_pickup_geom  ON delivery_order USING GIST (pickup_geom);
CREATE INDEX IF NOT EXISTS idx_order_dropoff_geom ON delivery_order USING GIST (dropoff_geom);
CREATE INDEX IF NOT EXISTS idx_zone_geom          ON airspace_zone USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_route_path_geom    ON flight_route USING GIST (path_geom);

-- ---------------------------------------------------------------------------
-- 4. 常用空间查询函数（供 Java 通过 native query 调用）
-- ---------------------------------------------------------------------------

-- 4.1 找出距离某点 radius_m 米内、且载重/电量满足条件的空闲无人机
--     返回按距离升序排列的候选集，可直接喂给调度模块做初筛
CREATE OR REPLACE FUNCTION fn_nearby_idle_drones(
    p_lat DOUBLE PRECISION,
    p_lon DOUBLE PRECISION,
    p_radius_m DOUBLE PRECISION DEFAULT 5000,
    p_min_payload_kg NUMERIC DEFAULT 0,
    p_min_battery_pct NUMERIC DEFAULT 30
)
    RETURNS TABLE (
                      drone_id BIGINT,
                      drone_code VARCHAR(32),
                      distance_m DOUBLE PRECISION,
                      battery_pct NUMERIC,
                      max_payload_kg NUMERIC
                  )
AS $$
BEGIN
    RETURN QUERY
        SELECT d.id,
               d.code,
               ST_Distance(d.current_geom::geography, ST_SetSRID(ST_MakePoint(p_lon, p_lat), 4326)::geography) AS dist,
               d.battery_pct,
               m.max_payload_kg
        FROM drone d
                 JOIN drone_model m ON m.id = d.model_id
        WHERE d.status = 'IDLE'
          AND d.current_geom IS NOT NULL
          AND d.battery_pct >= p_min_battery_pct
          AND m.max_payload_kg >= p_min_payload_kg
          AND ST_DWithin(d.current_geom::geography, ST_SetSRID(ST_MakePoint(p_lon, p_lat), 4326)::geography,
                         p_radius_m)
        ORDER BY dist;
END;
$$ LANGUAGE plpgsql STABLE;

-- 4.2 判断一条航线是否穿越禁飞区（返回穿越的禁飞区列表）
CREATE OR REPLACE FUNCTION fn_route_blocked_zones(p_route_id BIGINT)
    RETURNS TABLE (zone_id BIGINT, zone_code VARCHAR(32), zone_name VARCHAR(128))
AS $$
BEGIN
    RETURN QUERY
        SELECT z.id, z.code, z.name
        FROM flight_route r
                 JOIN airspace_zone z
                      ON z.enabled AND z.zone_type = 'NO_FLY' AND z.geom IS NOT NULL
                          AND ST_Intersects(r.path_geom, z.geom)
        WHERE r.id = p_route_id
          AND r.path_geom IS NOT NULL;
END;
$$ LANGUAGE plpgsql STABLE;
