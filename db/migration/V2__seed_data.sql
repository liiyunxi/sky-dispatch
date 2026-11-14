-- =============================================================================
--  sky-dispatch · V2__seed_data.sql
--  演示用基础数据（可重复执行，靠唯一键 ON CONFLICT 保证幂等）
--
--  坐标中心：上海虹桥—浦东一带，方便在地图上直观看到效果。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 无人机型号
--    参数必须自洽：battery_capacity_wh ≈ energy_per_km_wh × 巡航速度 × 续航分钟 / 60
--      SKY-X4: 42 Wh/km × (15 m/s × 35 min = 31.5km) ≈ 1323 → 1300 Wh
--      SKY-M8: 58 Wh/km × (18 m/s × 45 min = 48.6km) ≈ 2819 → 2800 Wh
--      SKY-H2: 95 Wh/km × (12 m/s × 30 min = 21.6km) ≈ 2052 → 2000 Wh
--    如果这三者互相矛盾（例如电池装不下标称的续航），仿真里的续航校验就会
--    和"标称参数"打架，运营看到的机型能力表也就不可信了。
-- ---------------------------------------------------------------------------
INSERT INTO drone_model (code, name, max_payload_kg, cruise_speed_mps, max_flight_min,
                         battery_capacity_wh, energy_per_km_wh, hover_power_w, max_altitude_m, remark)
VALUES ('SKY-X4', '天巡 X4 轻量配送机', 5.00, 15.00, 35, 1300.00, 42.000, 900.00, 120, '小件高频，主力机型'),
       ('SKY-M8', '天巡 M8 中型配送机', 15.00, 18.00, 45, 2800.00, 58.000, 1600.00, 150, '中件长航程'),
       ('SKY-H2', '天巡 H2 重载配送机', 30.00, 12.00, 30, 2000.00, 95.000, 2800.00, 180, '重载，速度慢')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 无人机（8 架，初始停放在 3 个起降点）
--    站点坐标必须在禁飞区之外：虹桥站距虹桥禁飞区圆心约 3.6km（半径 2.5km），
--    浦东站距浦东禁飞区圆心约 3.9km（半径 3km）。否则起点就落在禁飞区里，
--    路径规划会直接返回 start_inside_obstacle。
-- ---------------------------------------------------------------------------
INSERT INTO drone (code, model_id, status, battery_pct, current_lat, current_lon, altitude_m,
                   home_lat, home_lon, total_flight_min, total_distance_km, total_tasks)
SELECT v.code,
       (SELECT id FROM drone_model WHERE code = v.model_code),
       'IDLE',
       v.battery_pct,
       v.lat,
       v.lon,
       0,
       v.lat,
       v.lon,
       0,
       0,
       0
FROM (VALUES ('UAV-001', 'SKY-X4', 100.00, 31.1900::double precision, 121.3000::double precision),
             ('UAV-002', 'SKY-X4', 96.50, 31.1900, 121.3000),
             ('UAV-003', 'SKY-X4', 88.00, 31.1900, 121.3000),
             ('UAV-004', 'SKY-M8', 100.00, 31.2304, 121.4737),
             ('UAV-005', 'SKY-M8', 74.20, 31.2304, 121.4737),
             ('UAV-006', 'SKY-M8', 55.00, 31.2304, 121.4737),
             -- 人民广场是主力站点，多留几架空闲运力：
             -- 否则演示时"点派单没反应"的真实原因往往只是"附近飞机全在飞"
             ('UAV-009', 'SKY-M8', 100.00, 31.2304, 121.4737),
             ('UAV-010', 'SKY-M8', 92.00, 31.2304, 121.4737),
             ('UAV-011', 'SKY-M8', 85.00, 31.2304, 121.4737),
             ('UAV-007', 'SKY-H2', 100.00, 31.1700, 121.7800),
             ('UAV-008', 'SKY-H2', 62.30, 31.1700, 121.7800)) AS v (code, model_code, battery_pct, lat, lon)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 空域（1 个禁飞区、1 个限飞区、1 个航路走廊、1 个临时管制区）
--    注意 WKT 的顺序是 (经度 纬度)
-- ---------------------------------------------------------------------------
INSERT INTO airspace_zone (code, name, zone_type, shape_type, center_lat, center_lon, radius_m,
                           polygon_wkt, min_altitude_m, max_altitude_m, severity, remark)
VALUES ('Z-NF-001', '虹桥机场净空禁飞区', 'NO_FLY', 'CIRCLE', 31.1979, 121.3363, 2500.00,
        NULL, 0, 300, 'CRITICAL', '半径 2.5km 内禁止无人机活动'),
       ('Z-NF-002', '浦东机场净空禁飞区', 'NO_FLY', 'CIRCLE', 31.1443, 121.8083, 3000.00,
        NULL, 0, 300, 'CRITICAL', '半径 3km 内禁止无人机活动'),
       ('Z-RS-001', '陆家嘴限飞区', 'RESTRICTED', 'POLYGON',
        NULL, NULL, NULL,
        'POLYGON((121.4900 31.2300, 121.5150 31.2300, 121.5150 31.2500, 121.4900 31.2500, 121.4900 31.2300))',
        0, 60, 'HIGH', '限高 60m，需报备'),
       ('Z-CR-001', '中环低空物流走廊', 'CORRIDOR', 'POLYGON',
        NULL, NULL, NULL,
        'POLYGON((121.3800 31.2100, 121.4600 31.2100, 121.4600 31.2250, 121.3800 31.2250, 121.3800 31.2100))',
        60, 120, 'LOW', '推荐航路，限高 120m')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 演示订单（覆盖不同优先级 / 重量 / 状态）
-- ---------------------------------------------------------------------------
INSERT INTO delivery_order (order_no, customer_name, customer_phone, cargo_name, weight_kg, priority, status,
                            pickup_address, pickup_lat, pickup_lon,
                            dropoff_address, dropoff_lat, dropoff_lon,
                            expect_delivery_at, remark)
VALUES ('SO20260101001', '上海市第一人民医院', '021-60000001', '急救血浆', 3.20, 'URGENT', 'CREATED',
        '虹桥配送站', 31.1900, 121.3000, '市一医院', 31.2480, 121.4520, now() + interval '30 minutes', '高优先级急救'),
       ('SO20260101002', '王先生', '13800000002', '生鲜包裹', 1.80, 'NORMAL', 'CREATED',
        '虹桥配送站', 31.1900, 121.3000, '中山公园', 31.2200, 121.4200, now() + interval '2 hours', NULL),
       ('SO20260101003', '京东仓储', '021-60000003', '电子元件', 8.50, 'HIGH', 'CREATED',
        '人民广场站', 31.2304, 121.4737, '张江高科技园', 31.2050, 121.5990, now() + interval '90 minutes', '需防震'),
       ('SO20260101004', '李女士', '13800000004', '文件快递', 0.40, 'LOW', 'CREATED',
        '人民广场站', 31.2304, 121.4737, '静安寺', 31.2230, 121.4450, now() + interval '4 hours', NULL),
       ('SO20260101005', '浦东配送站', '021-60000005', '医疗耗材', 12.00, 'HIGH', 'CREATED',
        '张江配送站', 31.2050, 121.5990, '浦东配送站', 31.1700, 121.7800, now() + interval '3 hours', '重载测试'),
       ('SO20260101006', '陈先生', '13800000006', '午餐外卖', 2.10, 'NORMAL', 'CREATED',
        '人民广场站', 31.2304, 121.4737, '陆家嘴环路', 31.2390, 121.5000, now() + interval '45 minutes', '穿越限飞区，测试绕行')
ON CONFLICT (order_no) DO NOTHING;
