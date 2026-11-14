"""地理几何工具（纯标准库，不依赖 numpy / shapely）。

本模块解决三个问题：

1. **经纬度 → 局部平面米制坐标**
   无人机配送场景的航线只有几公里，可以忽略地球曲率，用「等距圆柱投影」
   把经纬度线性映射成以某个原点（通常是航线起点）为中心的 (x, y) 米。
   1 度纬度 ≈ 111132 m；1 度经度 ≈ 111320 * cos(lat) m。
   几公里范围内该近似误差在米级以下，完全够用。

2. **地表距离**
   用 haversine 公式算两点球面大圆距离（地球半径取 6371008.8 m，IUGG 平均半径）。

3. **几何判定**
   点在多边形内（射线法）、点到线段最短距离、线段与圆是否相交、方位角。

约定：所有角度单位为「度」，距离单位为「米」。
"""

from __future__ import annotations

import math

from app.schemas.common import LatLon, PolygonPoints

#: 地球平均半径（米），与 PostGIS / Java 侧保持一致的取值
EARTH_RADIUS_M = 6_371_008.8

#: 1 度纬度对应的米数（WGS84 平均值）
METERS_PER_DEG_LAT = 111_132.0

#: 赤道上 1 度经度对应的米数
METERS_PER_DEG_LON_EQUATOR = 111_320.0


# --------------------------------------------------------------------------- #
# 1. 投影：经纬度 <-> 局部米制平面
# --------------------------------------------------------------------------- #
def local_scale_lon(lat_ref: float) -> float:
    """返回参考纬度处「1 度经度」对应的米数。

    :param lat_ref: 参考纬度（度）
    """
    return METERS_PER_DEG_LON_EQUATOR * math.cos(math.radians(lat_ref))


def latlon_to_local(lat: float, lon: float, origin_lat: float, origin_lon: float) -> tuple[float, float]:
    """经纬度 → 以 ``origin`` 为原点的局部平面米制坐标。

    正变换：x 向东为正，y 向北为正。

    :param lat: 待转换点纬度（度）
    :param lon: 待转换点经度（度）
    :param origin_lat: 投影原点纬度（度）
    :param origin_lon: 投影原点经度（度）
    :return: ``(x_m, y_m)`` 局部平面坐标（米）
    """
    x_m = (lon - origin_lon) * local_scale_lon(origin_lat)
    y_m = (lat - origin_lat) * METERS_PER_DEG_LAT
    return x_m, y_m


def local_to_latlon(x_m: float, y_m: float, origin_lat: float, origin_lon: float) -> tuple[float, float]:
    """局部平面米制坐标 → 经纬度（``latlon_to_local`` 的逆变换）。

    :return: ``(lat, lon)``，单位为度
    """
    lat = origin_lat + y_m / METERS_PER_DEG_LAT
    lon = origin_lon + x_m / local_scale_lon(origin_lat)
    return lat, lon


# --------------------------------------------------------------------------- #
# 2. 距离与方位
# --------------------------------------------------------------------------- #
def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """用 haversine 公式计算两点间地表大圆距离（米）。

    公式：
        a = sin²(Δφ/2) + cosφ1 · cosφ2 · sin²(Δλ/2)
        d = 2R · atan2(√a, √(1−a))
    相比「平面勾股」在几十公里尺度上更准，且对极地/跨经度也稳定。
    """
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)

    a = math.sin(d_phi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2.0) ** 2
    a = min(1.0, max(0.0, a))  # 防浮点误差导致 sqrt 负数
    return 2.0 * EARTH_RADIUS_M * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """计算从点 1 指向点 2 的初始方位角（度，正北为 0，顺时针增大）。

    返回区间 ``[0, 360)``。
    """
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_lambda = math.radians(lon2 - lon1)

    y = math.sin(d_lambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lambda)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def heading_to_unit_vector(heading_deg: float) -> tuple[float, float]:
    """把航向角转成局部平面上的单位方向向量 (x 向东, y 向北)。

    正北 = 0° 对应 ``(0, 1)``，正东 = 90° 对应 ``(1, 0)``。
    """
    rad = math.radians(heading_deg)
    return math.sin(rad), math.cos(rad)


# --------------------------------------------------------------------------- #
# 3. 点在多边形内（射线法）
# --------------------------------------------------------------------------- #
def point_in_polygon(lat: float, lon: float, polygon: PolygonPoints) -> bool:
    """射线法判断点是否在多边形内部（边界上的点视为「内」，返回 True）。

    原理：从待测点向 +x 方向发一条水平射线，统计它与多边形边的交点个数；
    奇数个说明点在多边形内部，偶数个在外部。
    """
    pts = polygon.points
    n = len(pts)
    if n < 3:
        return False

    inside = False
    j = n - 1
    for i in range(n):
        lat_i, lon_i = pts[i].lat, pts[i].lon
        lat_j, lon_j = pts[j].lat, pts[j].lon

        # 判断水平射线是否跨越第 j→i 条边
        crosses = (lat_i > lat) != (lat_j > lat)
        if crosses:
            # 求该边与射线所在纬线的交点经度
            t = (lat - lat_i) / (lat_j - lat_i)
            lon_cross = lon_i + t * (lon_j - lon_i)
            if lon < lon_cross:
                inside = not inside
        j = i
    return inside


def polygon_bbox(polygon: PolygonPoints) -> tuple[float, float, float, float]:
    """返回多边形的经纬度外接矩形 ``(min_lat, min_lon, max_lat, max_lon)``。

    空点集返回全 0，由调用方处理。
    """
    if not polygon.points:
        return 0.0, 0.0, 0.0, 0.0
    lats = [p.lat for p in polygon.points]
    lons = [p.lon for p in polygon.points]
    return min(lats), min(lons), max(lats), max(lons)


# --------------------------------------------------------------------------- #
# 4. 点到线段 / 线段与圆
# --------------------------------------------------------------------------- #
def point_segment_distance(
    px: float,
    py: float,
    ax: float,
    ay: float,
    bx: float,
    by: float,
) -> float:
    """点 P 到线段 AB 的最短距离（二维平面，单位与输入一致）。

    做法：把 P 投影到直线 AB 上，若投影参数 t 落在 [0,1] 内，最短点就是垂足；
    否则最短点为离得较近的那个端点。
    """
    abx, aby = bx - ax, by - ay
    length_sq = abx * abx + aby * aby
    if length_sq <= 1e-12:
        # A、B 重合，退化成点-点距离
        return math.hypot(px - ax, py - ay)

    t = ((px - ax) * abx + (py - ay) * aby) / length_sq
    t = min(1.0, max(0.0, t))
    cx, cy = ax + t * abx, ay + t * aby
    return math.hypot(px - cx, py - cy)


def segment_intersects_circle(
    ax: float,
    ay: float,
    bx: float,
    by: float,
    center: tuple[float, float],
    radius: float,
) -> bool:
    """判断线段 AB 是否与圆（圆心 center，半径 radius）相交或落入圆内。

    等价于：A、B 到圆心的最近距离 ≤ radius。用「点到线段最短距离」一次判定即可，
    无需解二次方程，也不会有数值退化问题。
    """
    cx, cy = center
    return point_segment_distance(cx, cy, ax, ay, bx, by) <= radius


def min_distance_between_latlon(points_a: list[LatLon], points_b: list[LatLon]) -> float:
    """两组经纬度点之间的最小 haversine 距离（米），任一为空返回 ``inf``。"""
    best = math.inf
    for pa in points_a:
        for pb in points_b:
            best = min(best, haversine_m(pa.lat, pa.lon, pb.lat, pb.lon))
    return best
