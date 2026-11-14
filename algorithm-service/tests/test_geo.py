"""``app.algorithms.geo`` 的单元测试：投影、距离、方位角、点在多边形内、线段与圆相交。"""

from __future__ import annotations

import math

import pytest

from app.algorithms import geo
from app.schemas.common import LatLon, PolygonPoints

# 参考点：上海虹桥机场附近
LAT0, LON0 = 31.2300, 121.4700


# --------------------------------------------------------------------------- #
# 投影：正反变换
# --------------------------------------------------------------------------- #
def test_latlon_to_local_origin_is_zero() -> None:
    """原点投影到 (0, 0)。"""
    x, y = geo.latlon_to_local(LAT0, LON0, LAT0, LON0)
    assert x == pytest.approx(0.0, abs=1e-9)
    assert y == pytest.approx(0.0, abs=1e-9)


def test_latlon_to_local_direction_and_scale() -> None:
    """向北为正 y、向东为正 x，且尺度与「1 度 ≈ 111132m / 95196m」一致。"""
    _, y_north = geo.latlon_to_local(LAT0 + 0.01, LON0, LAT0, LON0)
    assert y_north == pytest.approx(1111.32, rel=1e-6)

    x_east, _ = geo.latlon_to_local(LAT0, LON0 + 0.01, LAT0, LON0)
    expected = 0.01 * geo.METERS_PER_DEG_LON_EQUATOR * math.cos(math.radians(LAT0))
    assert x_east == pytest.approx(expected, rel=1e-9)
    assert x_east > 0

    # 反向：向南为负、向西为负
    _, y_south = geo.latlon_to_local(LAT0 - 0.01, LON0, LAT0, LON0)
    x_west, _ = geo.latlon_to_local(LAT0, LON0 - 0.01, LAT0, LON0)
    assert y_south < 0 and x_west < 0


def test_local_to_latlon_is_inverse() -> None:
    """``local_to_latlon`` 是 ``latlon_to_local`` 的逆变换（往返误差 < 1e-9 度）。"""
    lat, lon = 31.2401, 121.4902
    x, y = geo.latlon_to_local(lat, lon, LAT0, LON0)
    back_lat, back_lon = geo.local_to_latlon(x, y, LAT0, LON0)
    assert back_lat == pytest.approx(lat, abs=1e-9)
    assert back_lon == pytest.approx(lon, abs=1e-9)


# --------------------------------------------------------------------------- #
# haversine 距离
# --------------------------------------------------------------------------- #
def test_haversine_zero_and_symmetry() -> None:
    """同点距离为 0，且距离具有对称性。"""
    assert geo.haversine_m(LAT0, LON0, LAT0, LON0) == pytest.approx(0.0, abs=1e-6)
    d1 = geo.haversine_m(31.2304, 121.4737, 31.2401, 121.4902)
    d2 = geo.haversine_m(31.2401, 121.4902, 31.2304, 121.4737)
    assert d1 == pytest.approx(d2, rel=1e-12)


def test_haversine_matches_known_meridian_distance() -> None:
    """沿同一条经线走 0.01 度纬度 ≈ 1111.9m（球面值，误差 < 1m）。"""
    d = geo.haversine_m(LAT0, LON0, LAT0 + 0.01, LON0)
    assert d == pytest.approx(1111.95, abs=1.0)


def test_haversine_one_degree_equator() -> None:
    """赤道上 1 度经度 ≈ 111194.9m（2πR/360）。"""
    d = geo.haversine_m(0.0, 0.0, 0.0, 1.0)
    assert d == pytest.approx(2 * math.pi * geo.EARTH_RADIUS_M / 360.0, rel=1e-6)


# --------------------------------------------------------------------------- #
# 方位角
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    ("lat2", "lon2", "expected"),
    [
        (LAT0 + 0.01, LON0, 0.0),  # 正北
        (LAT0, LON0 + 0.01, 90.0),  # 正东
        (LAT0 - 0.01, LON0, 180.0),  # 正南
        (LAT0, LON0 - 0.01, 270.0),  # 正西
    ],
)
def test_bearing_deg_cardinal_directions(lat2: float, lon2: float, expected: float) -> None:
    """四个正方向的方位角。

    容差取 0.01 度：正北/正南是精确值，而正东/正西用的是**大圆初始方位角**公式，
    在 1km 量级上会与 90°/270° 有约 0.0026° 的固有偏差（球面几何的正常现象）。
    """
    assert geo.bearing_deg(LAT0, LON0, lat2, lon2) == pytest.approx(expected, abs=0.01)


def test_bearing_deg_range_and_heading_vector() -> None:
    """方位角始终落在 [0, 360)，且航向单位向量满足 sin/cos 关系。"""
    bearing = geo.bearing_deg(31.2304, 121.4737, 31.2200, 121.4600)
    assert 0.0 <= bearing < 360.0

    ux, uy = geo.heading_to_unit_vector(90.0)
    assert ux == pytest.approx(1.0, abs=1e-12)
    assert uy == pytest.approx(0.0, abs=1e-12)
    ux, uy = geo.heading_to_unit_vector(0.0)
    assert ux == pytest.approx(0.0, abs=1e-12)
    assert uy == pytest.approx(1.0, abs=1e-12)


# --------------------------------------------------------------------------- #
# 点在多边形内（射线法）
# --------------------------------------------------------------------------- #
def _square() -> PolygonPoints:
    """构造一个边长 0.01 度的正方形（中心在参考点）。"""
    return PolygonPoints(
        points=[
            LatLon(lat=LAT0 - 0.005, lon=LON0 - 0.005),
            LatLon(lat=LAT0 - 0.005, lon=LON0 + 0.005),
            LatLon(lat=LAT0 + 0.005, lon=LON0 + 0.005),
            LatLon(lat=LAT0 + 0.005, lon=LON0 - 0.005),
        ]
    )


def test_point_in_polygon_inside_and_outside() -> None:
    """中心点在内部；四周远点在外部。"""
    square = _square()
    assert geo.point_in_polygon(LAT0, LON0, square) is True
    assert geo.point_in_polygon(LAT0, LON0 + 0.02, square) is False
    assert geo.point_in_polygon(LAT0 + 0.02, LON0, square) is False
    assert geo.point_in_polygon(LAT0, LON0 - 0.02, square) is False


def test_point_in_polygon_degenerate_returns_false() -> None:
    """少于 3 个顶点的多边形视为不包含任何点。"""
    degenerate = PolygonPoints.model_construct(
        points=[LatLon(lat=LAT0, lon=LON0), LatLon(lat=LAT0 + 1, lon=LON0 + 1)]
    )
    assert geo.point_in_polygon(LAT0, LON0, degenerate) is False


def test_polygon_bbox() -> None:
    """外接矩形正确反映顶点范围。"""
    min_lat, min_lon, max_lat, max_lon = geo.polygon_bbox(_square())
    assert min_lat == pytest.approx(LAT0 - 0.005)
    assert max_lat == pytest.approx(LAT0 + 0.005)
    assert min_lon == pytest.approx(LON0 - 0.005)
    assert max_lon == pytest.approx(LON0 + 0.005)


# --------------------------------------------------------------------------- #
# 点到线段 / 线段与圆
# --------------------------------------------------------------------------- #
def test_point_segment_distance_perpendicular_and_endpoint() -> None:
    """垂足在段内取垂距；垂足在段外取端点距离。"""
    # P(0,5) 到线段 (0,0)-(10,0) 的垂距为 5
    assert geo.point_segment_distance(0.0, 5.0, 0.0, 0.0, 10.0, 0.0) == pytest.approx(5.0)
    # P(20,0) 垂足在段外，距离为到最近端点 (10,0) 的距离
    assert geo.point_segment_distance(20.0, 0.0, 0.0, 0.0, 10.0, 0.0) == pytest.approx(10.0)
    # 退化成点
    assert geo.point_segment_distance(3.0, 4.0, 0.0, 0.0, 0.0, 0.0) == pytest.approx(5.0)


def test_segment_intersects_circle_cases() -> None:
    """横穿、相切外侧、完全在圆外、端点落在圆内四种情形。"""
    center = (5.0, 0.0)
    # 线段穿过圆心
    assert geo.segment_intersects_circle(0.0, 0.0, 10.0, 0.0, center, 1.0) is True
    # 端点落在圆内
    assert geo.segment_intersects_circle(5.0, 0.0, 20.0, 0.0, center, 1.0) is True
    # 圆正好与线段相切（距离 = 半径）
    assert geo.segment_intersects_circle(0.0, 1.0, 10.0, 1.0, center, 1.0) is True
    # 圆在线段上方 2m，半径 1m → 不相交
    assert geo.segment_intersects_circle(0.0, 2.0, 10.0, 2.0, center, 1.0) is False


def test_obstacle_contains_point_circle_and_polygon() -> None:
    """障碍物模型自带的包含判定（含膨胀余量）。"""
    from app.schemas.common import Obstacle, ShapeType

    circle = Obstacle(
        id="C1",
        shape=ShapeType.CIRCLE,
        center=LatLon(lat=LAT0, lon=LON0),
        radiusM=500.0,
        minAltitudeM=0.0,
        maxAltitudeM=120.0,
    )
    # 正东约 300m 处仍在圆内
    lat, lon = geo.local_to_latlon(300.0, 0.0, LAT0, LON0)
    assert circle.contains_point(lat, lon) is True
    # 加上 30m 膨胀后，超出 500m 边界 10m 的点也算命中
    lat2, lon2 = geo.local_to_latlon(510.0, 0.0, LAT0, LON0)
    assert circle.contains_point(lat2, lon2) is False
    assert circle.contains_point(lat2, lon2, margin_m=30.0) is True
