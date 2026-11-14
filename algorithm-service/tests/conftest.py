"""测试包的公共夹具与断言辅助函数。

约定：所有测试统一使用「上海虹桥附近」的一组固定经纬度，便于手工心算距离。
运行方式（在 algorithm-service 目录下）：

    .\\.venv\\Scripts\\python.exe -m pytest -q
"""

from __future__ import annotations

import math
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.algorithms import geo
from app.main import app
from app.schemas.common import LatLon, Obstacle, ShapeType, ZoneType

# --------------------------------------------------------------------------- #
# 常用坐标常量（纬度 31.23 附近：1 度纬度 ≈ 111132m，1 度经度 ≈ 95196m）
# --------------------------------------------------------------------------- #
START = LatLon(lat=31.2300, lon=121.4700)
GOAL = LatLon(lat=31.2300, lon=121.4900)  # 正东约 1904m

#: 覆盖在 START→GOAL 直线正中间的圆形禁飞区
CIRCLE_ZONE = Obstacle(
    id="Z001",
    name="禁飞区-虹桥",
    shape=ShapeType.CIRCLE,
    center=LatLon(lat=31.2300, lon=121.4800),
    radiusM=500.0,
    minAltitudeM=0.0,
    maxAltitudeM=120.0,
    zoneType=ZoneType.NO_FLY,
)

#: 覆盖在 START→GOAL 直线正中间的矩形禁飞区（经纬度近似矩形）
RECT_ZONE = Obstacle(
    id="Z002",
    name="限飞多边形",
    shape=ShapeType.POLYGON,
    points=[
        LatLon(lat=31.22510, lon=121.47510),
        LatLon(lat=31.22510, lon=121.48490),
        LatLon(lat=31.23490, lon=121.48490),
        LatLon(lat=31.23490, lon=121.47510),
    ],
    minAltitudeM=0.0,
    maxAltitudeM=150.0,
    zoneType=ZoneType.RESTRICTED,
)

#: 把起终点「围死」的环形禁飞区：4 个矩形拼成一个只在
#: lat [31.22950, 31.23050] × lon [121.46950, 121.47050]（约 111m × 95m）留孔的环。
#: 起点 (31.2300, 121.4700) 正好在孔中央，距离四个矩形的边界都超过 44m，
#: 因此「起点不在障碍物内」，但任何方向都在几十米内被完全封死 → 应返回 no_path_found。
RING_ZONES: list[Obstacle] = [
    Obstacle(  # 北侧横条（同时封住西/东上角）
        id="R_TOP",
        shape=ShapeType.POLYGON,
        points=[
            LatLon(lat=31.23050, lon=121.46900),
            LatLon(lat=31.23050, lon=121.47100),
            LatLon(lat=31.24000, lon=121.47100),
            LatLon(lat=31.24000, lon=121.46900),
        ],
        minAltitudeM=0.0,
        maxAltitudeM=200.0,
    ),
    Obstacle(  # 南侧横条（同时封住西/东下角）
        id="R_BOTTOM",
        shape=ShapeType.POLYGON,
        points=[
            LatLon(lat=31.22000, lon=121.46900),
            LatLon(lat=31.22000, lon=121.47100),
            LatLon(lat=31.22950, lon=121.47100),
            LatLon(lat=31.22950, lon=121.46900),
        ],
        minAltitudeM=0.0,
        maxAltitudeM=200.0,
    ),
    Obstacle(  # 西侧竖条
        id="R_LEFT",
        shape=ShapeType.POLYGON,
        points=[
            LatLon(lat=31.22900, lon=121.46800),
            LatLon(lat=31.22900, lon=121.46950),
            LatLon(lat=31.23100, lon=121.46950),
            LatLon(lat=31.23100, lon=121.46800),
        ],
        minAltitudeM=0.0,
        maxAltitudeM=200.0,
    ),
    Obstacle(  # 东侧竖条
        id="R_RIGHT",
        shape=ShapeType.POLYGON,
        points=[
            LatLon(lat=31.22900, lon=121.47050),
            LatLon(lat=31.22900, lon=121.47200),
            LatLon(lat=31.23100, lon=121.47200),
            LatLon(lat=31.23100, lon=121.47050),
        ],
        minAltitudeM=0.0,
        maxAltitudeM=200.0,
    ),
]

#: 契约 4.2 的示例请求体，可直接被接口测试复用
SAMPLE_PLAN_BODY: dict[str, Any] = {
    "requestId": "test-request-0001",
    "start": {"lat": 31.2304, "lon": 121.4737},
    "goal": {"lat": 31.2401, "lon": 121.4902},
    "altitudeM": 80.0,
    "cruiseSpeedMps": 12.0,
    "gridSizeM": 50.0,
    "maxSearchNodes": 200000,
    "keepOutMarginM": 30.0,
    "smooth": True,
    "obstacles": [
        {
            "id": "Z001",
            "name": "禁飞区-虹桥",
            "shape": "CIRCLE",
            "center": {"lat": 31.2350, "lon": 121.4800},
            "radiusM": 600.0,
            "minAltitudeM": 0.0,
            "maxAltitudeM": 120.0,
        }
    ],
}


# --------------------------------------------------------------------------- #
# 夹具
# --------------------------------------------------------------------------- #
@pytest.fixture(scope="session")
def client() -> TestClient:
    """FastAPI 测试客户端（进程内直连，无需真实启动服务）。"""
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def straight_zones() -> list[Obstacle]:
    """空障碍物列表：用于「直线可达」场景。"""
    return []


@pytest.fixture
def circle_zones() -> list[Obstacle]:
    """单个圆形禁飞区。"""
    return [CIRCLE_ZONE]


@pytest.fixture
def polygon_zones() -> list[Obstacle]:
    """单个矩形（多边形）禁飞区。"""
    return [RECT_ZONE]


@pytest.fixture
def ring_zones() -> list[Obstacle]:
    """包围起终点的环形禁飞区。"""
    return list(RING_ZONES)


# --------------------------------------------------------------------------- #
# 断言辅助函数
# --------------------------------------------------------------------------- #
def local_xy(lat: float, lon: float, origin: LatLon = START) -> tuple[float, float]:
    """经纬度 → 以 ``origin`` 为原点的局部米制坐标。"""
    return geo.latlon_to_local(lat, lon, origin.lat, origin.lon)


def segment_clearance_to_obstacle_m(
    lat1: float,
    lon1: float,
    lat2: float,
    lon2: float,
    obstacle: Obstacle,
    origin: LatLon = START,
) -> float:
    """航段到**圆形障碍物边界**的最小净空（米，>0 表示在区域外）。

    在局部平面上把航段与圆心都化成米制坐标，再用「点到线段最短距离」判定。
    """
    assert obstacle.shape == ShapeType.CIRCLE
    assert obstacle.center is not None and obstacle.radiusM is not None

    ax, ay = local_xy(lat1, lon1, origin)
    bx, by = local_xy(lat2, lon2, origin)
    cx, cy = local_xy(obstacle.center.lat, obstacle.center.lon, origin)
    distance = geo.point_segment_distance(cx, cy, ax, ay, bx, by)
    return distance - obstacle.radiusM


def assert_path_respects_obstacles(
    waypoints: list[dict[str, Any]],
    obstacles: list[Obstacle],
    margin_m: float,
    altitude_m: float = 80.0,
) -> None:
    """断言整条航线（所有航段）与膨胀后的障碍物都不相交。

    :param waypoints: 响应里的 waypoints 列表（含 lat / lon）
    :param obstacles: 障碍物列表
    :param margin_m: 膨胀安全间隔（米）
    :param altitude_m: 飞行高度，用于跳过不生效的障碍物
    """
    for obstacle in obstacles:
        if not obstacle.is_active_at(altitude_m):
            continue
        for prev, cur in zip(waypoints, waypoints[1:]):
            if obstacle.shape == ShapeType.CIRCLE:
                clearance = segment_clearance_to_obstacle_m(
                    prev["lat"], prev["lon"], cur["lat"], cur["lon"], obstacle
                )
                assert clearance >= margin_m - 1.0, (
                    f"航段 {prev['lat']},{prev['lon']} -> {cur['lat']},{cur['lon']} "
                    f"侵入膨胀障碍 {obstacle.id}，净空仅 {clearance:.1f}m < {margin_m:.1f}m"
                )
            else:
                assert _segment_outside_polygon(prev, cur, obstacle, margin_m, altitude_m), (
                    f"航段 {prev['lat']},{prev['lon']} -> {cur['lat']},{cur['lon']} "
                    f"侵入膨胀多边形 {obstacle.id}"
                )


def _segment_outside_polygon(
    prev: dict[str, Any],
    cur: dict[str, Any],
    obstacle: Obstacle,
    margin_m: float,
    altitude_m: float,
) -> bool:
    """沿航段密集采样，检查采样点是否都在膨胀多边形之外。"""
    samples = 200
    for i in range(samples + 1):
        t = i / samples
        lat = prev["lat"] + (cur["lat"] - prev["lat"]) * t
        lon = prev["lon"] + (cur["lon"] - prev["lon"]) * t
        if obstacle.contains_point(lat, lon, margin_m):
            return False
    return True


def path_length_m(waypoints: list[dict[str, Any]]) -> float:
    """按 haversine 累加航路点距离（米）。"""
    total = 0.0
    for prev, cur in zip(waypoints, waypoints[1:]):
        total += geo.haversine_m(prev["lat"], prev["lon"], cur["lat"], cur["lon"])
    return total


def approx_m(value: float, expected: float, rel: float = 0.02) -> bool:
    """粗略比较两个米数（默认 2% 相对误差），用于结果自检。"""
    return math.isclose(value, expected, rel_tol=rel)
