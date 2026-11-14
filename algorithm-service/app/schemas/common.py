"""公共数据模型：经纬度点、障碍物/空域区域。

**字段命名约定（全项目一致）**：请求与响应模型一律直接使用契约里的 camelCase
字段名（如 ``radiusM``、``minAltitudeM``），不使用 alias 生成器。
这样 Python 属性名与 JSON 字段名逐字一致，排查契约问题时不需要做心智映射。

障碍物/区域同时支持两种形状（与契约 2.3 节一致）：

* ``CIRCLE``：``center`` + ``radiusM``
* ``POLYGON``：``points``（至少 3 个点）

高度维度是低空三维空域的关键简化：只有飞行高度落在
``[minAltitudeM, maxAltitudeM]`` 内时，该区域才对本条航线生效。
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, model_validator

# 说明：几何计算统一实现在 app.algorithms.geo，本模块只放数据模型。
# geo.py 反过来导入本模块的 LatLon / PolygonPoints —— 方向是
# schemas -> algorithms 无依赖、algorithms -> schemas 单向导入，不会循环。
from app.algorithms import geo


class ShapeType(StrEnum):
    """空域区域形状枚举。"""

    CIRCLE = "CIRCLE"
    POLYGON = "POLYGON"


class ZoneType(StrEnum):
    """空域区域类型枚举（决定冲突 severity 分档）。"""

    NO_FLY = "NO_FLY"
    RESTRICTED = "RESTRICTED"
    OTHER = "OTHER"


class LatLon(BaseModel):
    """经纬度坐标点（度）。"""

    model_config = ConfigDict(extra="ignore")

    lat: float = Field(ge=-90.0, le=90.0, description="纬度")
    lon: float = Field(ge=-180.0, le=180.0, description="经度")


class PolygonPoints(BaseModel):
    """多边形顶点集合（至少 3 个点）。"""

    model_config = ConfigDict(extra="ignore")

    points: list[LatLon] = Field(min_length=3, description="多边形顶点，顺序闭合")

    def bbox(self) -> tuple[float, float, float, float]:
        """返回 ``(min_lat, min_lon, max_lat, max_lon)`` 外接矩形。"""
        return geo.polygon_bbox(self)

    def centroid(self) -> LatLon:
        """返回顶点算术平均中心（够用即可，不追求面积质心）。"""
        n = len(self.points)
        return LatLon(
            lat=sum(p.lat for p in self.points) / n,
            lon=sum(p.lon for p in self.points) / n,
        )

    def bounding_radius_m(self) -> float:
        """返回能覆盖整个多边形的圆半径（米），以中心为圆心。

        用于快速构造「膨胀多边形」的栅格外接矩形，避免全图扫描。
        """
        center = self.centroid()
        return max(
            geo.haversine_m(center.lat, center.lon, p.lat, p.lon) for p in self.points
        )


class Obstacle(BaseModel):
    """障碍物 / 空域区域。

    既是 ``/path/plan`` 请求中的 ``obstacles`` 元素，
    也是 ``/conflict/check`` 请求中的 ``zones`` 元素（契约里两者字段同构）。
    """

    model_config = ConfigDict(extra="ignore")

    id: str | None = Field(default=None, description="区域编号，如 Z001")
    name: str | None = Field(default=None, description="区域名称，如 禁飞区-虹桥")
    shape: ShapeType = Field(default=ShapeType.CIRCLE, description="形状：CIRCLE 或 POLYGON")
    zoneType: ZoneType = Field(default=ZoneType.NO_FLY, description="区域类型：NO_FLY / RESTRICTED / OTHER")

    # CIRCLE 专用
    center: LatLon | None = Field(default=None, description="圆心（shape=CIRCLE）")
    radiusM: float | None = Field(default=None, gt=0.0, description="半径（米，shape=CIRCLE）")

    # POLYGON 专用
    points: list[LatLon] | None = Field(default=None, description="多边形顶点（shape=POLYGON，至少 3 个）")

    minAltitudeM: float = Field(default=0.0, description="区域生效的最低高度（米）")
    maxAltitudeM: float = Field(default=9999.0, description="区域生效的最高高度（米）")

    @model_validator(mode="after")
    def _check_shape_payload(self) -> "Obstacle":
        """按形状校验必要字段，尽量给出可读的错误信息（FastAPI 会转成 422）。"""
        if self.shape == ShapeType.CIRCLE:
            if self.center is None or self.radiusM is None:
                raise ValueError("shape=CIRCLE 时必须同时提供 center 和 radiusM")
        elif self.shape == ShapeType.POLYGON:
            if not self.points or len(self.points) < 3:
                raise ValueError("shape=POLYGON 时 points 至少需要 3 个顶点")
        if self.maxAltitudeM < self.minAltitudeM:
            raise ValueError("maxAltitudeM 不能小于 minAltitudeM")
        return self

    # ---------------- 便捷方法 ---------------- #

    def is_active_at(self, altitude_m: float) -> bool:
        """给定飞行高度，该区域是否生效（高度落在 [min, max] 闭区间内）。"""
        return self.minAltitudeM <= altitude_m <= self.maxAltitudeM

    def label(self) -> str:
        """返回用于日志与提示消息的可读标签。"""
        return self.name or self.id or "未命名区域"

    def polygon(self) -> PolygonPoints | None:
        """返回多边形对象；非多边形区域返回 ``None``。"""
        if self.shape != ShapeType.POLYGON or not self.points:
            return None
        return PolygonPoints(points=self.points)

    def contains_point(self, lat: float, lon: float, margin_m: float = 0.0) -> bool:
        """点到区域**几何边界**的包含判定（不含高度判断）。

        * 圆：球面距离 ≤ ``radiusM + margin_m``
        * 多边形：点在多边形内；``margin_m > 0`` 时再叠加「点到边最短距离」外扩近似
        """
        if self.shape == ShapeType.CIRCLE:
            # 校验器保证 center / radiusM 一定存在
            assert self.center is not None and self.radiusM is not None
            return geo.haversine_m(lat, lon, self.center.lat, self.center.lon) <= self.radiusM + margin_m

        poly = self.polygon()
        if poly is None:
            return False
        if geo.point_in_polygon(lat, lon, poly):
            return True
        if margin_m <= 0.0:
            return False
        return _distance_to_polygon_boundary_m(lat, lon, poly) <= margin_m


def _distance_to_polygon_boundary_m(lat: float, lon: float, poly: PolygonPoints) -> float:
    """点（经纬度）到多边形边界的最短距离（米，本地平面近似）。

    只服务于「膨胀判定」，米级误差可以接受。
    """
    center = poly.centroid()
    px, py = geo.latlon_to_local(lat, lon, center.lat, center.lon)
    pts = poly.points
    n = len(pts)
    best = float("inf")
    for i in range(n):
        a = pts[i]
        b = pts[(i + 1) % n]
        ax, ay = geo.latlon_to_local(a.lat, a.lon, center.lat, center.lon)
        bx, by = geo.latlon_to_local(b.lat, b.lon, center.lat, center.lon)
        best = min(best, geo.point_segment_distance(px, py, ax, ay, bx, by))
    return best
