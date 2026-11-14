"""栅格离散化：把经纬度空域映射成统一的整数栅格。

思路（栅格法 A* 的经典做法）：

1. 以**起点**为原点做局部等距圆柱投影，把经纬度变成米制平面坐标 (x, y)；
2. 求「起点 + 终点 + 所有生效障碍物（按 keep-out 膨胀后）」的外接矩形；
3. 外接矩形再向外留 ``pad_cells`` 个栅格（默认 5 格）的余量，
   保证绕飞时有腾挪空间、不会因为贴边而搜不到路；
4. 按 ``grid_size_m`` 把矩形切成 ``nx × ny`` 的栅格，
   栅格中心点的米制坐标 ↔ 栅格索引可以互相转换；
5. 把每个生效障碍物膨胀 ``keep_out_margin_m`` 后，覆盖到的栅格标记为不可通行。

**高度维度的处理**：低空三维空域简化为「分层 2D」。若飞行高度不在障碍物的
``[minAltitudeM, maxAltitudeM]`` 区间内，该障碍物对本次规划**完全不生效**，
既不膨胀也不阻挡 —— 这正是无人机可以「从禁飞区上方飞过」的建模方式。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from app.algorithms import geo
from app.schemas.common import Obstacle, ShapeType

#: 不可通行标记
BLOCKED = True
#: 可通行标记
FREE = False

#: 航线走廊半宽（米）：只有与「起点→终点 连线 ± 该宽度」相交的障碍物才参与本次规划。
#:
#: **为什么必须过滤**：生产/演示的空域是<b>全国分布</b>的（上海、北京、深圳、成都……），
#: 而栅格的外接矩形是「起点 + 终点 + 所有生效障碍物」的并集。若把全国障碍物都算进去，
#: 外接矩形会被拉到国土尺度，在 120 m 栅格下 nx×ny 高达上亿格 —— 实测直接把进程内存
#: 吃到 7 GB 后卡死。而远离航线段几百公里外的障碍物**不可能影响本段航路**，
#: 过滤掉既不影响正确性，又能把栅格限制在城市尺度（几千~几万格）。
#:
#: 取 2 km 是留足余量：绕飞一个 1.5 km 半径的禁飞区，航线最多偏离直线约 1.5 km，
#: 仍然落在走廊内；而走廊外的障碍物本来就不在可达范围内。
ROUTE_CORRIDOR_M = 2000.0

#: 栅格总数安全上限。走廊过滤后正常航线只有几千~几十万格；
#: 一旦超过该值说明输入病态（超长航线 + 极小栅格），宁可判失败也不要吃光内存。
MAX_GRID_CELLS = 2_000_000


class GridTooLargeError(RuntimeError):
    """栅格规模超过安全上限，拒绝分配（由调用方转成"规划失败"而非崩溃）。"""


def _boxes_overlap(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> bool:
    """两个轴对齐矩形是否相交（闭区间，``(min_x, min_y, max_x, max_y)``）。"""
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


@dataclass(frozen=True)
class GridSpec:
    """栅格几何描述（全部为米 / 栅格数）。"""

    #: 局部米制平面原点对应的经纬度（= 航线起点）
    origin_lat: float
    origin_lon: float
    #: 栅格边长（米）
    cell_m: float
    #: 栅格列数（x 方向）与行数（y 方向）
    nx: int
    ny: int
    #: 栅格坐标 (0,0) 中心点在局部平面中的米制坐标
    origin_x_m: float
    origin_y_m: float

    def cell_center_m(self, cx: int, cy: int) -> tuple[float, float]:
        """栅格索引 → 栅格中心的局部米制坐标。"""
        return self.origin_x_m + (cx + 0.5) * self.cell_m, self.origin_y_m + (cy + 0.5) * self.cell_m

    def cell_center_latlon(self, cx: int, cy: int) -> tuple[float, float]:
        """栅格索引 → 栅格中心的经纬度。"""
        x_m, y_m = self.cell_center_m(cx, cy)
        return geo.local_to_latlon(x_m, y_m, self.origin_lat, self.origin_lon)

    def point_to_cell(self, x_m: float, y_m: float) -> tuple[int, int]:
        """局部米制点 → 栅格索引（超出范围时夹取到边界，避免越界崩溃）。"""
        cx = int(math.floor((x_m - self.origin_x_m) / self.cell_m))
        cy = int(math.floor((y_m - self.origin_y_m) / self.cell_m))
        return min(self.nx - 1, max(0, cx)), min(self.ny - 1, max(0, cy))

    def latlon_to_cell(self, lat: float, lon: float) -> tuple[int, int]:
        """经纬度 → 栅格索引。"""
        x_m, y_m = geo.latlon_to_local(lat, lon, self.origin_lat, self.origin_lon)
        return self.point_to_cell(x_m, y_m)

    def in_bounds(self, cx: int, cy: int) -> bool:
        """栅格索引是否在网格范围内。"""
        return 0 <= cx < self.nx and 0 <= cy < self.ny

    def cell_count(self) -> int:
        """栅格总数。"""
        return self.nx * self.ny


@dataclass
class Grid:
    """栅格地图：几何信息 + 不可通行标记 + 生效障碍物列表。"""

    spec: GridSpec
    #: blocked[cy][cx] = True 表示不可通行
    blocked: list[list[bool]] = field(default_factory=list)
    #: 本次规划中真正生效（高度区间覆盖飞行高度）的障碍物
    active_obstacles: list[Obstacle] = field(default_factory=list)

    # ---------------- 查询 ---------------- #

    def is_free(self, cx: int, cy: int) -> bool:
        """该栅格是否可通行（越界视为不可通行）。"""
        if not self.spec.in_bounds(cx, cy):
            return False
        return not self.blocked[cy][cx]

    def is_free_point(self, x_m: float, y_m: float) -> bool:
        """局部米制点所在的栅格是否可通行。"""
        cx, cy = self.spec.point_to_cell(x_m, y_m)
        return self.is_free(cx, cy)

    def is_free_latlon(self, lat: float, lon: float) -> bool:
        """经纬度点所在的栅格是否可通行。"""
        cx, cy = self.spec.latlon_to_cell(lat, lon)
        return self.is_free(cx, cy)

    # ---------------- 碰撞检测（供平滑与测试复用） ---------------- #

    def path_is_clear(self, ax: float, ay: float, bx: float, by: float) -> bool:
        """判断局部米制线段 AB 是否**完全落在可通行栅格内**。

        采用「超覆盖（supercover）」采样：沿线段以 ``cell_m / 4`` 的步长推进，
        额外把两个端点附近也采样到，因此线段经过的每一个栅格都会被检查到，
        不会出现「斜穿障碍物角」或「掠过细长障碍物」的漏检。
        与 A* 使用同一套 blocked 栅格，所以平滑后的航线一定不会穿越膨胀障碍。

        :param ax: 起点 x（米）
        :param ay: 起点 y（米）
        :param bx: 终点 x（米）
        :param by: 终点 y（米）
        """
        step = self.spec.cell_m / 4.0
        distance = math.hypot(bx - ax, by - ay)
        steps = max(1, int(math.ceil(distance / step)))

        for i in range(steps + 1):
            t = i / steps
            x_m = ax + (bx - ax) * t
            y_m = ay + (by - ay) * t
            if not self.is_free_point(x_m, y_m):
                return False
        return True

    def path_is_clear_latlon(self, start_lat: float, start_lon: float, end_lat: float, end_lon: float) -> bool:
        """经纬度版本的 :meth:`path_is_clear`。"""
        ax, ay = geo.latlon_to_local(start_lat, start_lon, self.spec.origin_lat, self.spec.origin_lon)
        bx, by = geo.latlon_to_local(end_lat, end_lon, self.spec.origin_lat, self.spec.origin_lon)
        return self.path_is_clear(ax, ay, bx, by)

    # ---------------- 统计 ---------------- #

    def blocked_count(self) -> int:
        """不可通行栅格数量。"""
        return sum(1 for row in self.blocked for cell in row if cell)


def build_grid(
    start_lat: float,
    start_lon: float,
    goal_lat: float,
    goal_lon: float,
    altitude_m: float,
    obstacles: list[Obstacle],
    cell_m: float,
    keep_out_margin_m: float,
    pad_cells: int = 5,
    max_cells: int = MAX_GRID_CELLS,
) -> Grid:
    """构建栅格地图（外接矩形 + 膨胀障碍物）。

    :param start_lat: 起点纬度
    :param start_lon: 起点经度
    :param goal_lat: 终点纬度
    :param goal_lon: 终点经度
    :param altitude_m: 飞行高度（决定哪些障碍物生效）
    :param obstacles: 全部障碍物（未生效的、以及远离航线的会被过滤掉）
    :param cell_m: 栅格边长（米）
    :param keep_out_margin_m: 障碍物膨胀距离（米）
    :param pad_cells: 外接矩形向外预留的栅格数，至少 5 格
    :param max_cells: 栅格总数上限，超过即抛 :class:`GridTooLargeError`
    :return: 构建好的 :class:`Grid`
    :raises GridTooLargeError: 栅格规模超过 ``max_cells``（避免病态输入吃光内存）
    """
    pad = max(5, pad_cells)

    # 1) 只有高度区间覆盖飞行高度的障碍物才参与本次规划
    active = [o for o in obstacles if o.is_active_at(altitude_m)]

    gx, gy = geo.latlon_to_local(goal_lat, goal_lon, start_lat, start_lon)

    # 2) 再筛掉"离本段航线太远"的障碍物（见 ROUTE_CORRIDOR_M 的说明）。
    #    用「航线外接矩形 ± 走廊宽」与「障碍物膨胀后外接矩形」做矩形相交判定：
    #    比逐段求点到线段距离简单得多，代价是斜向航线会多留一点余量 —— 无害。
    corridor = max(ROUTE_CORRIDOR_M, keep_out_margin_m)
    route_box = (
        min(0.0, gx) - corridor,
        min(0.0, gy) - corridor,
        max(0.0, gx) + corridor,
        max(0.0, gy) + corridor,
    )
    obstacle_boxes: list[tuple[float, float, float, float]] = []
    nearby: list[Obstacle] = []
    for obstacle in active:
        box = _inflated_bbox_m(obstacle, start_lat, start_lon, keep_out_margin_m)
        if _boxes_overlap(box, route_box):
            nearby.append(obstacle)
            obstacle_boxes.append(box)
    active = nearby

    # 3) 外接矩形 = 起点 + 终点 + 上述障碍物的并集，再留 pad 格余量
    xs: list[float] = [0.0, gx]  # 起点自身
    ys: list[float] = [0.0, gy]
    for box in obstacle_boxes:
        xs.extend([box[0], box[2]])
        ys.extend([box[1], box[3]])

    min_x, max_x = min(xs) - pad * cell_m, max(xs) + pad * cell_m
    min_y, max_y = min(ys) - pad * cell_m, max(ys) + pad * cell_m

    nx = max(1, int(math.ceil((max_x - min_x) / cell_m)))
    ny = max(1, int(math.ceil((max_y - min_y) / cell_m)))

    # 4) 规模守卫：正常航线只有几千~几十万格；超过上限说明输入病态，直接拒绝分配
    if nx * ny > max_cells:
        raise GridTooLargeError(
            f"栅格规模过大：{nx}×{ny}={nx * ny} 格，超过上限 {max_cells}（请调大 gridSizeM）"
        )

    spec = GridSpec(
        origin_lat=start_lat,
        origin_lon=start_lon,
        cell_m=cell_m,
        nx=nx,
        ny=ny,
        origin_x_m=min_x,
        origin_y_m=min_y,
    )

    # 5) 标记膨胀障碍覆盖的栅格
    blocked = [[FREE] * nx for _ in range(ny)]
    for obstacle in active:
        mark_obstacle_cells(blocked, spec, obstacle, keep_out_margin_m)

    return Grid(spec=spec, blocked=blocked, active_obstacles=active)


# --------------------------------------------------------------------------- #
# 内部工具函数
# --------------------------------------------------------------------------- #
def _inflated_bbox_m(
    obstacle: Obstacle,
    origin_lat: float,
    origin_lon: float,
    margin_m: float,
) -> tuple[float, float, float, float]:
    """返回障碍物「几何 + 膨胀余量」在局部米制平面上的外接矩形。

    :return: ``(min_x, min_y, max_x, max_y)``，单位为米
    """
    if obstacle.shape == ShapeType.CIRCLE:
        assert obstacle.center is not None and obstacle.radiusM is not None
        cx, cy = geo.latlon_to_local(obstacle.center.lat, obstacle.center.lon, origin_lat, origin_lon)
        r = obstacle.radiusM + margin_m
        return cx - r, cy - r, cx + r, cy + r

    poly = obstacle.polygon()
    assert poly is not None
    center = poly.centroid()
    ccx, ccy = geo.latlon_to_local(center.lat, center.lon, origin_lat, origin_lon)
    radius = poly.bounding_radius_m() + margin_m
    return ccx - radius, ccy - radius, ccx + radius, ccy + radius


def mark_obstacle_cells(
    blocked: list[list[bool]],
    spec: GridSpec,
    obstacle: Obstacle,
    margin_m: float,
) -> None:
    """把「膨胀 margin_m 后的障碍物」覆盖到的栅格标记为不可通行（对外可直接调用）。

    :param blocked: ``blocked[cy][cx]`` 二维标记数组，就地修改
    :param spec: 栅格几何
    :param obstacle: 障碍物
    :param margin_m: 膨胀距离（米）
    """
    box = _inflated_bbox_m(obstacle, spec.origin_lat, spec.origin_lon, margin_m)
    min_cx, min_cy = spec.point_to_cell(box[0], box[1])
    max_cx, max_cy = spec.point_to_cell(box[2], box[3])
    max_cx = min(max_cx, spec.nx - 1)
    max_cy = min(max_cy, spec.ny - 1)
    min_cx = max(0, min_cx)
    min_cy = max(0, min_cy)

    for cy in range(min_cy, max_cy + 1):
        for cx in range(min_cx, max_cx + 1):
            if blocked[cy][cx]:
                continue
            lat, lon = spec.cell_center_latlon(cx, cy)
            if obstacle.contains_point(lat, lon, margin_m):
                blocked[cy][cx] = BLOCKED
