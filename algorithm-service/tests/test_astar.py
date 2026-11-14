"""A* 路径规划测试：直线、绕圆形/多边形禁飞区、起点在区内、围死、平滑合法性、高度过滤。"""

from __future__ import annotations

import pytest

from app.algorithms import geo
from app.algorithms.astar import (
    MSG_GOAL_INSIDE_OBSTACLE,
    MSG_GRID_TOO_LARGE,
    MSG_MAX_NODES,
    MSG_NO_PATH,
    MSG_OK,
    MSG_START_INSIDE_OBSTACLE,
    plan_path,
)
from app.schemas.common import LatLon, Obstacle, ShapeType
from tests.conftest import (
    GOAL,
    START,
    assert_path_respects_obstacles,
    path_length_m,
)

GRID_M = 50.0
MARGIN_M = 30.0
ALT_M = 80.0


def _plan(
    obstacles: list[Obstacle],
    start: LatLon = START,
    goal: LatLon = GOAL,
    altitude_m: float = ALT_M,
    keep_out_margin_m: float = MARGIN_M,
    smooth: bool = True,
    max_search_nodes: int = 200_000,
):
    """便捷调用：固定栅格尺寸，只暴露测试关心的参数。"""
    return plan_path(
        start_lat=start.lat,
        start_lon=start.lon,
        goal_lat=goal.lat,
        goal_lon=goal.lon,
        altitude_m=altitude_m,
        obstacles=obstacles,
        grid_size_m=GRID_M,
        keep_out_margin_m=keep_out_margin_m,
        max_search_nodes=max_search_nodes,
        smooth=smooth,
        pad_cells=5,
    )


def _waypoints_as_dicts(result) -> list[dict]:
    """把 WaypointModel 列表转成普通 dict，方便复用 conftest 的断言工具。"""
    return [wp.model_dump() for wp in result.waypoints]


# --------------------------------------------------------------------------- #
# 1. 直线路径（无障碍物）
# --------------------------------------------------------------------------- #
def test_straight_line_no_obstacles(straight_zones: list[Obstacle]) -> None:
    """无障碍物时应直接连通，且平滑后只剩起终点两个航路点。"""
    result = _plan(straight_zones)

    assert result.found is True
    assert result.message == MSG_OK
    assert len(result.waypoints) == 2

    first, last = result.waypoints[0], result.waypoints[-1]
    # 首尾航路点必须是原始起终点坐标（精确替换，不受栅格中心影响）
    assert first.lat == pytest.approx(START.lat, abs=1e-6)
    assert first.lon == pytest.approx(START.lon, abs=1e-6)
    assert last.lat == pytest.approx(GOAL.lat, abs=1e-6)
    assert last.lon == pytest.approx(GOAL.lon, abs=1e-6)

    # seq 从 0 开始连续
    assert [wp.seq for wp in result.waypoints] == [0, 1]
    # 首点累计距离 0，末点累计距离等于 distanceM
    assert first.cumulativeDistanceM == pytest.approx(0.0)
    assert last.cumulativeDistanceM == pytest.approx(result.distance_m, abs=0.01)

    # distanceM 与逐段 haversine 之和一致，且与理论直线距离一致
    assert path_length_m(_waypoints_as_dicts(result)) == pytest.approx(result.distance_m, abs=0.5)
    direct = geo.haversine_m(START.lat, START.lon, GOAL.lat, GOAL.lon)
    assert result.distance_m == pytest.approx(direct, rel=0.01)

    # 算法统计与耗时
    assert result.expanded_nodes > 0
    assert result.elapsed_ms >= 0.0
    assert result.algorithm == "ASTAR"


# --------------------------------------------------------------------------- #
# 2. 绕圆形禁飞区
# --------------------------------------------------------------------------- #
def test_avoid_circle_zone(circle_zones: list[Obstacle]) -> None:
    """直线穿过圆形禁飞区时必须绕行：距离变长、净空满足膨胀余量。"""
    direct = geo.haversine_m(START.lat, START.lon, GOAL.lat, GOAL.lon)
    result = _plan(circle_zones)

    assert result.found is True
    assert result.message == MSG_OK
    assert len(result.waypoints) >= 3, "绕过禁飞区不可能只有起终点两个航路点"
    assert result.distance_m > direct

    # 首尾仍是精确起终点
    assert result.waypoints[0].lat == pytest.approx(START.lat, abs=1e-6)
    assert result.waypoints[-1].lon == pytest.approx(GOAL.lon, abs=1e-6)

    # 平滑后整条航线仍与膨胀后的圆保持 ≥ keepOutMarginM 的净空
    assert_path_respects_obstacles(_waypoints_as_dicts(result), circle_zones, MARGIN_M, ALT_M)


def test_smoothing_keeps_path_legal_and_shortens_it(circle_zones: list[Obstacle]) -> None:
    """平滑前后都必须合法，且平滑后的航路点数不多于平滑前、距离不长于平滑前。"""
    raw = _plan(circle_zones, smooth=False)
    smooth = _plan(circle_zones, smooth=True)

    assert raw.found is True and smooth.found is True
    assert_path_respects_obstacles(_waypoints_as_dicts(raw), circle_zones, MARGIN_M, ALT_M)
    assert_path_respects_obstacles(_waypoints_as_dicts(smooth), circle_zones, MARGIN_M, ALT_M)

    assert len(smooth.waypoints) <= len(raw.waypoints)
    assert smooth.distance_m <= raw.distance_m + 0.5


# --------------------------------------------------------------------------- #
# 3. 绕多边形禁飞区
# --------------------------------------------------------------------------- #
def test_avoid_polygon_zone(polygon_zones: list[Obstacle]) -> None:
    """直线穿过矩形（多边形）禁飞区时必须绕行，且航线不侵入膨胀多边形。"""
    direct = geo.haversine_m(START.lat, START.lon, GOAL.lat, GOAL.lon)
    result = _plan(polygon_zones)

    assert result.found is True
    assert result.message == MSG_OK
    assert len(result.waypoints) >= 3
    assert result.distance_m > direct
    assert_path_respects_obstacles(_waypoints_as_dicts(result), polygon_zones, MARGIN_M, ALT_M)


# --------------------------------------------------------------------------- #
# 4. 起点 / 终点落在禁飞区内
# --------------------------------------------------------------------------- #
def test_start_inside_obstacle_returns_not_found() -> None:
    """起点在圆形禁飞区内部 → found=false, message=start_inside_obstacle。"""
    zone = Obstacle(
        id="Z_IN",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=START.lat, lon=START.lon),
        radiusM=200.0,
        minAltitudeM=0.0,
        maxAltitudeM=120.0,
    )
    result = _plan([zone])

    assert result.found is False
    assert result.message == MSG_START_INSIDE_OBSTACLE
    assert result.waypoints == []
    assert result.distance_m == 0.0


def test_goal_inside_obstacle_returns_not_found() -> None:
    """终点在禁飞区内部 → found=false, message=goal_inside_obstacle。"""
    zone = Obstacle(
        id="Z_GOAL",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=GOAL.lat, lon=GOAL.lon),
        radiusM=200.0,
        minAltitudeM=0.0,
        maxAltitudeM=120.0,
    )
    result = _plan([zone])

    assert result.found is False
    assert result.message == MSG_GOAL_INSIDE_OBSTACLE
    assert result.waypoints == []


# --------------------------------------------------------------------------- #
# 5. 完全被围死 → no_path_found
# --------------------------------------------------------------------------- #
def test_start_surrounded_returns_no_path(ring_zones: list[Obstacle]) -> None:
    """起点被环形禁飞区完全围住 → found=false, message=no_path_found（HTTP 仍是 200）。"""
    result = _plan(ring_zones)

    assert result.found is False
    assert result.message == MSG_NO_PATH
    assert result.waypoints == []
    assert result.distance_m == 0.0
    assert result.expanded_nodes > 0


def test_max_search_nodes_exceeded(ring_zones: list[Obstacle]) -> None:
    """把 maxSearchNodes 压到 1 → 立刻判定失败并给出 max_search_nodes_exceeded。"""
    result = _plan(ring_zones, max_search_nodes=1)

    assert result.found is False
    assert result.message == MSG_MAX_NODES
    assert result.expanded_nodes > 1


# --------------------------------------------------------------------------- #
# 6. 高度过滤（低空三维空域的关键简化）
# --------------------------------------------------------------------------- #
def test_obstacle_ignored_when_altitude_out_of_range(circle_zones: list[Obstacle]) -> None:
    """飞行高度 200m 高于禁飞区上限 120m → 障碍物不生效，可以直线穿过。"""
    result = _plan(circle_zones, altitude_m=200.0)

    assert result.found is True
    assert len(result.waypoints) == 2, "障碍物不生效时应为直线"
    direct = geo.haversine_m(START.lat, START.lon, GOAL.lat, GOAL.lon)
    assert result.distance_m == pytest.approx(direct, rel=0.01)


def test_obstacle_active_when_altitude_in_range(circle_zones: list[Obstacle]) -> None:
    """飞行高度 80m 落在禁飞区区间内 → 障碍物生效，必须绕行。"""
    result = _plan(circle_zones, altitude_m=80.0)
    assert result.found is True
    assert len(result.waypoints) >= 3


# --------------------------------------------------------------------------- #
# 7. 其他边界情形
# --------------------------------------------------------------------------- #
def test_keep_out_margin_grows_detour(circle_zones: list[Obstacle]) -> None:
    """膨胀余量越大，绕行越远（且始终保持更大净空）。"""
    small = _plan(circle_zones, keep_out_margin_m=0.0)
    large = _plan(circle_zones, keep_out_margin_m=150.0)

    assert small.found is True and large.found is True
    assert large.distance_m >= small.distance_m
    assert_path_respects_obstacles(_waypoints_as_dicts(large), circle_zones, 150.0, ALT_M)


def test_same_start_and_goal_returns_single_point() -> None:
    """起终点重合时返回单点航线，距离为 0。"""
    result = _plan([], start=START, goal=START)

    assert result.found is True
    assert len(result.waypoints) == 1
    assert result.distance_m == pytest.approx(0.0)


def test_cumulative_distance_is_monotonic(circle_zones: list[Obstacle]) -> None:
    """累计距离必须单调不减，且末点等于 distanceM。"""
    result = _plan(circle_zones)
    cumulative = [wp.cumulativeDistanceM for wp in result.waypoints]

    assert cumulative[0] == pytest.approx(0.0)
    assert all(b >= a for a, b in zip(cumulative, cumulative[1:]))
    assert cumulative[-1] == pytest.approx(result.distance_m, abs=0.01)


# --------------------------------------------------------------------------- #
# 8. 航线走廊过滤（防"全国障碍物把栅格撑爆"的回归测试）
# --------------------------------------------------------------------------- #
def test_far_away_obstacle_is_filtered_out() -> None:
    """远离航线的障碍物必须被走廊过滤：既不引起绕飞，也不能把栅格撑到国土尺度。

    回归背景：演示数据同时存在上海 / 北京 / 深圳 / 成都 四个城市的空域，
    而栅格外接矩形原本是「起点 + 终点 + 所有生效障碍物」的并集。不过滤时外接矩形
    会被拉到国土尺度，在 50m 栅格下高达数亿格 —— 实测把算法服务内存吃到 7GB 后卡死。
    """
    far_zone = Obstacle(
        id="FAR-001",
        name="北京远郊禁飞区",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=39.9042, lon=116.4074),  # 距上海约 1060km
        radiusM=3000.0,
        minAltitudeM=0.0,
        maxAltitudeM=500.0,
    )
    result = _plan([far_zone])

    assert result.found is True
    assert len(result.waypoints) == 2, "远离航线的障碍物不应该引起绕飞"
    direct = geo.haversine_m(START.lat, START.lon, GOAL.lat, GOAL.lon)
    assert result.distance_m == pytest.approx(direct, rel=0.01)


def test_obstacle_inside_corridor_still_avoided() -> None:
    """走廊内的障碍物（只是偏离直线 400m）仍必须绕飞 —— 防止过滤过狠误删真障碍。"""
    near_zone = Obstacle(
        id="NEAR-001",
        name="航线旁建筑群",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=31.2336, lon=121.4800),  # 航线正北 400m，膨胀后仍压住航线
        radiusM=500.0,
        minAltitudeM=0.0,
        maxAltitudeM=120.0,
    )
    result = _plan([near_zone])

    assert result.found is True
    assert len(result.waypoints) >= 3, "压住航线的障碍物必须绕行"
    assert_path_respects_obstacles(_waypoints_as_dicts(result), [near_zone], MARGIN_M, ALT_M)


def test_too_large_grid_fails_instead_of_oom() -> None:
    """病态输入（千公里级航线 + 5m 极小栅格）应返回 grid_too_large，而不是吃光内存。"""
    far = LatLon(lat=39.9042, lon=116.4074)
    result = plan_path(
        start_lat=START.lat,
        start_lon=START.lon,
        goal_lat=far.lat,
        goal_lon=far.lon,
        altitude_m=ALT_M,
        obstacles=[],
        grid_size_m=5.0,
        keep_out_margin_m=MARGIN_M,
        max_search_nodes=200_000,
        smooth=True,
        pad_cells=5,
    )

    assert result.found is False
    assert result.message == MSG_GRID_TOO_LARGE
