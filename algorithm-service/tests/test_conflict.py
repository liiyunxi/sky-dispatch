"""冲突检测测试：机-机命中/不命中、severity 分档、机-区、低电量、无冲突空数组。"""

from __future__ import annotations

import math

import pytest

from app.algorithms import geo
from app.algorithms.conflict import check_conflicts, severity_rank
from app.schemas.common import LatLon, Obstacle, ShapeType, ZoneType
from app.schemas.conflict import ConflictType, DroneState, Severity

#: 50m 水平 / 15m 垂直安全间隔，与契约示例一致
MIN_H = 50.0
MIN_V = 15.0
LOOKAHEAD = 60.0

#: 1 度经度对应的米数（纬度 31.23 处 ≈ 95196m），用来把米换算成经度增量
M_PER_DEG_LON = geo.METERS_PER_DEG_LON_EQUATOR * math.cos(math.radians(31.23))


def _drone(
    drone_id: int,
    lat: float,
    lon: float,
    heading: float,
    speed: float = 10.0,
    altitude: float = 80.0,
    battery: float = 90.0,
) -> DroneState:
    """构造一架无人机状态。"""
    return DroneState(
        droneId=drone_id,
        droneCode=f"UAV-{drone_id:03d}",
        lat=lat,
        lon=lon,
        altitudeM=altitude,
        speedMps=speed,
        headingDeg=heading,
        batteryPct=battery,
    )


def _check(drones: list[DroneState], zones: list[Obstacle] | None = None):
    """便捷调用冲突检测（固定阈值）。"""
    return check_conflicts(
        drones=drones,
        zones=zones or [],
        lookahead_seconds=LOOKAHEAD,
        min_horizontal_separation_m=MIN_H,
        min_vertical_separation_m=MIN_V,
    )


def _of_type(result, conflict_type: ConflictType) -> list:
    """筛出指定类型的冲突。"""
    return [c for c in result.conflicts if c.conflictType == conflict_type]


# --------------------------------------------------------------------------- #
# 1. 机-机冲突命中
# --------------------------------------------------------------------------- #
def test_drone_drone_head_on_conflict_detected() -> None:
    """两机相向飞行（各 10m/s，初始相距 600m，同高度）→ 应命中 DRONE_DRONE。"""
    a = _drone(3, 31.2300, 121.4700, heading=90.0)  # 向东飞
    b = _drone(5, 31.2300, 121.4700 + 600.0 / M_PER_DEG_LON, heading=270.0)  # 向西飞

    result = _check([a, b])
    conflicts = _of_type(result, ConflictType.DRONE_DRONE)

    assert result.conflict_count == 1
    assert len(conflicts) == 1

    conflict = conflicts[0]
    assert conflict.droneAId == 3 and conflict.droneBId == 5
    assert conflict.zoneId is None
    assert conflict.verticalDistanceM == pytest.approx(0.0)

    # 两机以 20m/s 接近，600m 初始间距 → 约 30s 后相遇；间距降到 <50m 应从第 28 秒开始
    assert conflict.timeToConflictS == pytest.approx(28.0, abs=1.0)
    # 最小间距出现在相遇时刻，接近 0
    assert conflict.horizontalDistanceM < 1.0
    # 接近率 ≈ 两机速度之和（都在连线方向上相向而行）
    assert conflict.closingSpeedMps == pytest.approx(20.0, abs=0.5)
    # 最小间距 0 < 0.5 × 50 → CRITICAL
    assert conflict.severity == Severity.CRITICAL
    # 中文描述包含两机编号与阈值
    assert "UAV-003" in conflict.message and "UAV-005" in conflict.message

    # 冲突位置应落在两机之间（经度介于两者之间）
    assert min(a.lon, b.lon) <= conflict.lon <= max(a.lon, b.lon)


def test_drone_drone_severity_high_band() -> None:
    """最小水平间距落在 [0.5, 0.8) × 阈值 → HIGH。

    构造：两机同向（都向东）同速飞行，**横向（正北方向）错开 30m**，
    纵向（航迹方向）完全对齐。两机平行飞行、间距恒定 30m（永远不会真正相撞），
    30 / 50 = 0.6 落在 [0.5, 0.8) 区间 → HIGH。
    """
    offset_m = 30.0
    a = _drone(3, 31.2300, 121.4700, heading=90.0, speed=10.0)
    b = _drone(5, 31.2300, 121.4700, heading=90.0, speed=10.0)
    b = b.model_copy(update={"lat": 31.2300 + offset_m / geo.METERS_PER_DEG_LAT})

    result = _check([a, b])
    conflicts = _of_type(result, ConflictType.DRONE_DRONE)

    assert len(conflicts) == 1
    conflict = conflicts[0]
    assert conflict.horizontalDistanceM == pytest.approx(offset_m, abs=0.5)
    assert MIN_H * 0.5 <= conflict.horizontalDistanceM < MIN_H * 0.8
    assert conflict.severity is Severity.HIGH
    # 平行同速飞行：间距不随时间变化，接近率为 0
    assert conflict.closingSpeedMps == pytest.approx(0.0, abs=0.1)


def test_drone_drone_medium_severity() -> None:
    """最小水平间距落在 [0.8, 1.0) × 阈值 → MEDIUM。

    构造：两机同向（都向东）缓慢追赶，A 速度 12m/s、B 速度 9.4m/s，
    接近率仅 2.6m/s，初始纵向间距 200m（同高 80m）：
    * 间距线性缩小：d(t) = 200 − 2.6t，全程单调递减；
    * 第 58 秒降到 50m，首次突破阈值；
    * 60 秒窗口末端 d = 200 − 2.6 × 60 = 44m 是整个窗口的最小值，
      44 / 50 = 0.88 落在 [0.8, 1.0) 区间 → MEDIUM（始终没有接近到 0.8 倍以内）。
    """
    a = _drone(7, 31.2300, 121.4700, heading=90.0, speed=12.0)
    b = _drone(8, 31.2300, 121.4700 + 200.0 / M_PER_DEG_LON, heading=90.0, speed=9.4)

    result = _check([a, b])
    conflicts = _of_type(result, ConflictType.DRONE_DRONE)

    assert len(conflicts) == 1
    conflict = conflicts[0]
    # 首次突破阈值发生在约 58s（= (200 - 50) / 2.6）
    assert conflict.timeToConflictS == pytest.approx(58.0, abs=1.0)
    assert MIN_H * 0.8 <= conflict.horizontalDistanceM < MIN_H
    assert conflict.horizontalDistanceM == pytest.approx(44.0, abs=1.0)
    assert conflict.severity is Severity.MEDIUM
    # 同向缓慢追赶：接近率 = 速度差，远小于速度之和
    assert conflict.closingSpeedMps == pytest.approx(2.6, abs=0.5)


def test_drone_drone_no_conflict_when_vertically_separated() -> None:
    """水平间距很小但垂直间隔足够 → 不算机-机冲突。"""
    a = _drone(3, 31.2300, 121.4700, heading=90.0, altitude=80.0)
    b = _drone(5, 31.2300, 121.4700 + 600.0 / M_PER_DEG_LON, heading=270.0, altitude=120.0)

    result = _check([a, b])
    assert _of_type(result, ConflictType.DRONE_DRONE) == []


def test_drone_drone_no_conflict_when_far_apart() -> None:
    """两机背向飞行、且水平间距始终大于阈值 → 无冲突。"""
    a = _drone(1, 31.2300, 121.4700, heading=270.0)
    b = _drone(2, 31.2300, 121.4700 + 600.0 / M_PER_DEG_LON, heading=90.0)

    result = _check([a, b])
    assert _of_type(result, ConflictType.DRONE_DRONE) == []


# --------------------------------------------------------------------------- #
# 2. 机-区冲突
# --------------------------------------------------------------------------- #
def _no_fly_circle(radius_m: float = 200.0, offset_m: float = 300.0) -> Obstacle:
    """在无人机正东 ``offset_m`` 处放一个圆形禁飞区。"""
    lat, lon = geo.local_to_latlon(offset_m, 0.0, 31.2300, 121.4700)
    return Obstacle(
        id="Z001",
        name="禁飞区-虹桥",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=lat, lon=lon),
        radiusM=radius_m,
        minAltitudeM=0.0,
        maxAltitudeM=120.0,
        zoneType=ZoneType.NO_FLY,
    )


def test_drone_zone_future_intrusion_detected() -> None:
    """无人机向东飞，300m 外有 200m 半径圆形禁飞区 → 应命中 DRONE_ZONE。"""
    drone = _drone(3, 31.2300, 121.4700, heading=90.0, speed=10.0)
    result = _check([drone], [_no_fly_circle()])
    conflicts = _of_type(result, ConflictType.DRONE_ZONE)

    assert len(conflicts) == 1
    conflict = conflicts[0]
    assert conflict.droneAId == 3
    assert conflict.droneBId is None
    assert conflict.zoneId == "Z001"
    # 距边界 100m，10m/s → 约 10s 后进入
    assert conflict.timeToConflictS == pytest.approx(10.0, abs=1.5)
    assert conflict.severity is Severity.CRITICAL  # NO_FLY
    assert "禁飞区" in conflict.message


def test_drone_zone_immediate_intrusion_not_reported() -> None:
    """无人机当前位置已在禁飞区内 → 属于既成事实，不再产出 TTC=0 的预测提醒。"""
    drone = _drone(9, 31.2300, 121.4700, heading=90.0, speed=0.0)
    zone = Obstacle(
        id="Z_NOW",
        name="禁飞区-当前",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=31.2300, lon=121.4700),
        radiusM=150.0,
        minAltitudeM=0.0,
        maxAltitudeM=120.0,
        zoneType=ZoneType.NO_FLY,
    )
    result = _check([drone], [zone])
    conflicts = _of_type(result, ConflictType.DRONE_ZONE)

    assert conflicts == []


def test_drone_zone_restricted_severity_is_high() -> None:
    """RESTRICTED 类型区域 → HIGH。"""
    zone = _no_fly_circle()
    zone = zone.model_copy(update={"zoneType": ZoneType.RESTRICTED})
    drone = _drone(4, 31.2300, 121.4700, heading=90.0, speed=10.0)

    result = _check([drone], [zone])
    conflicts = _of_type(result, ConflictType.DRONE_ZONE)
    assert len(conflicts) == 1
    assert conflicts[0].severity is Severity.HIGH


def test_drone_zone_polygon_detected() -> None:
    """多边形（矩形）禁飞区同样能命中。"""
    zone = Obstacle(
        id="Z002",
        name="限飞多边形",
        shape=ShapeType.POLYGON,
        points=[
            LatLon(lat=31.22500, lon=121.47500),
            LatLon(lat=31.22500, lon=121.48500),
            LatLon(lat=31.23500, lon=121.48500),
            LatLon(lat=31.23500, lon=121.47500),
        ],
        minAltitudeM=0.0,
        maxAltitudeM=150.0,
        zoneType=ZoneType.NO_FLY,
    )
    drone = _drone(6, 31.2300, 121.4700, heading=90.0, speed=10.0)

    result = _check([drone], [zone])
    conflicts = _of_type(result, ConflictType.DRONE_ZONE)
    assert len(conflicts) == 1
    assert conflicts[0].zoneId == "Z002"


def test_drone_zone_ignored_when_altitude_out_of_range() -> None:
    """飞行高度高于区域上限 → 区域不生效，无冲突。"""
    drone = _drone(3, 31.2300, 121.4700, heading=90.0, speed=10.0, altitude=200.0)
    result = _check([drone], [_no_fly_circle()])

    assert _of_type(result, ConflictType.DRONE_ZONE) == []


def test_drone_zone_not_reached_within_lookahead() -> None:
    """禁飞区在 60s 外推范围之外 → 无冲突。"""
    drone = _drone(3, 31.2300, 121.4700, heading=90.0, speed=10.0)
    # 3000m 外：60s 内最多飞 600m，够不到
    result = _check([drone], [_no_fly_circle(radius_m=200.0, offset_m=3000.0)])

    assert _of_type(result, ConflictType.DRONE_ZONE) == []


# --------------------------------------------------------------------------- #
# 3. 低电量
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    ("battery", "expected_severity", "should_report"),
    [
        (95.0, None, False),
        (20.0, None, False),  # 正好等于阈值不算（严格小于）
        (15.0, Severity.LOW, True),
        (10.0, Severity.LOW, True),
        (9.0, Severity.MEDIUM, True),
        (3.0, Severity.MEDIUM, True),
    ],
)
def test_battery_risk_thresholds(battery: float, expected_severity, should_report: bool) -> None:
    """< 20% → LOW；< 10% → MEDIUM；≥ 20% 不报。"""
    drone = _drone(11, 31.2300, 121.4700, heading=90.0, speed=0.0, battery=battery)
    result = _check([drone])
    conflicts = _of_type(result, ConflictType.BATTERY_RISK)

    if not should_report:
        assert conflicts == []
        return

    assert len(conflicts) == 1
    assert conflicts[0].severity is expected_severity
    assert conflicts[0].droneAId == 11
    assert "电量" in conflicts[0].message


# --------------------------------------------------------------------------- #
# 4. 无冲突 / 空输入
# --------------------------------------------------------------------------- #
def test_no_conflict_returns_empty_list() -> None:
    """两机相距很远、电量充足、无区域 → conflicts 为空数组、conflictCount 为 0。"""
    a = _drone(1, 31.2300, 121.4700, heading=0.0, battery=90.0)
    b = _drone(2, 31.2500, 121.5100, heading=180.0, battery=88.0)

    result = _check([a, b], [])
    assert result.conflicts == []
    assert result.conflict_count == 0
    assert result.message == "ok"


def test_empty_drones_returns_no_conflict() -> None:
    """空输入不应抛异常。"""
    result = _check([], [_no_fly_circle()])
    assert result.conflicts == []
    assert result.conflict_count == 0


def test_multiple_conflict_types_in_one_batch() -> None:
    """一批数据里可以同时出现三种类型冲突。"""
    a = _drone(3, 31.2300, 121.4700, heading=90.0, battery=8.0)  # 低电量 MEDIUM
    b = _drone(5, 31.2300, 121.4700 + 600.0 / M_PER_DEG_LON, heading=270.0, battery=90.0)

    result = _check([a, b], [_no_fly_circle()])
    types = {c.conflictType for c in result.conflicts}

    assert ConflictType.DRONE_DRONE in types
    assert ConflictType.DRONE_ZONE in types
    assert ConflictType.BATTERY_RISK in types
    assert result.conflict_count == len(result.conflicts)


def test_severity_rank_ordering() -> None:
    """severity 等级排序可用于「取最严重者」。"""
    assert severity_rank(Severity.CRITICAL) > severity_rank(Severity.HIGH)
    assert severity_rank(Severity.HIGH) > severity_rank(Severity.MEDIUM)
    assert severity_rank(Severity.MEDIUM) > severity_rank(Severity.LOW)
