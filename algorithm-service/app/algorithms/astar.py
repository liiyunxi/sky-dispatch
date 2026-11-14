"""A* 路径规划（8 邻域 + 障碍膨胀 + 视线法平滑）。

算法要点（面向初学者）：

1. **代价**：直行邻居代价 1，对角邻居代价 √2（都再乘以 ``grid_size_m`` 得到米）。
2. **启发函数 h**：到终点的欧氏距离（栅格单位）。欧氏距离 ≤ 真实最短路径长度，
   因此是「可采纳（admissible）」的，能保证 A* 找到最优路径。
3. **开放列表**：用 ``heapq`` 最小堆实现，元素是 ``(f, tie, gx, gy)``；
   ``tie`` 是自增计数器，避免栅格坐标参与比较（元组比较会退化成索引比较，慢且无意义）。
4. **关闭列表**：用 ``g_score`` 字典 + 惰性删除（弹出时若 f 已过期就丢弃），
   比维护独立的 closed set 更简洁，也不会有重复入堆导致的问题。
5. **对角穿墙保护**：只有当两个正交邻居都可通行时，才允许走对角，
   否则航线会从障碍物（膨胀后）的尖角缝隙里「穿过去」，物理上不可行。
6. **平滑（两级）**：搜索得到的是阶梯状栅格路径，分两步变成"像真飞机飞的航线"。
   第一步**视线法（line-of-sight）**贪心跳点：从当前点出发，尽量跳到最远的、
   连线不穿过任何不可通行栅格的航路点 —— 把几十个栅格台阶压成几个拐点。
   第二步**转弯圆角化**：真实无人机不可能在拐点瞬时转向，它有最小转弯半径
   （协调转弯 r = v² / (g·tanφ)，15 m/s、约 30° 倾角下 r ≈ 40 m）。
   于是把每个拐点用一个与相邻两段相切的二次贝塞尔弧替换掉，
   让航线是"切进来的圆弧"而不是"折角"。
   **安全兜底**：每条弧都拿第一级同一张膨胀栅格逐段校验，一旦圆角会蹭到障碍，
   就退回原来的尖角 —— 宁可折一下，也不侵入禁飞区。
"""

from __future__ import annotations

import heapq
import math
import time
from dataclasses import dataclass, field

from app.algorithms import geo
from app.algorithms.grid import Grid, GridTooLargeError, build_grid
from app.schemas.common import Obstacle
from app.schemas.path import WaypointModel

#: 8 邻域偏移：(dx, dy, 基础代价)。代价 1 为直行，√2 为对角。
NEIGHBORS: tuple[tuple[int, int, float], ...] = (
    (1, 0, 1.0),
    (-1, 0, 1.0),
    (0, 1, 1.0),
    (0, -1, 1.0),
    (1, 1, math.sqrt(2.0)),
    (1, -1, math.sqrt(2.0)),
    (-1, 1, math.sqrt(2.0)),
    (-1, -1, math.sqrt(2.0)),
)

#: 失败原因码（会原样写进响应 message）
MSG_OK = "ok"
MSG_START_INSIDE_OBSTACLE = "start_inside_obstacle"
MSG_GOAL_INSIDE_OBSTACLE = "goal_inside_obstacle"
MSG_NO_PATH = "no_path_found"
MSG_MAX_NODES = "max_search_nodes_exceeded"
MSG_GRID_TOO_LARGE = "grid_too_large"
MSG_INTERNAL_ERROR = "internal_error"

#: 转弯圆角半径（米）。取四旋翼在 15 m/s、约 30° 倾角下的协调转弯半径：
#: r = v² / (g·tan φ) = 15² / (9.81 · tan 30°) ≈ 39.8 m，这里取 40 m。
#: 它只是期望值，实际会被"相邻航段长度的一半"夹住，保证圆角不会互相侵占。
TURN_RADIUS_M = 40.0

#: 每个转弯用几段直线逼近圆弧。6 段在 40 m 半径下弦高误差 < 0.2 m，
#: 肉眼已是光滑曲线，又不会把航路点数量撑爆。
CORNER_SAMPLES = 6

#: 去重阈值（米）：短于一粒米的相邻点没有意义，还会让后续逐段计算退化。
MIN_POINT_GAP_M = 0.01


@dataclass
class PathPlanResult:
    """路径规划内部结果（api 层再转成契约响应模型）。"""

    found: bool
    message: str
    distance_m: float = 0.0
    waypoints: list[WaypointModel] = field(default_factory=list)
    expanded_nodes: int = 0
    elapsed_ms: float = 0.0
    algorithm: str = "ASTAR"


def plan_path(
    start_lat: float,
    start_lon: float,
    goal_lat: float,
    goal_lon: float,
    altitude_m: float,
    obstacles: list[Obstacle],
    grid_size_m: float,
    keep_out_margin_m: float,
    max_search_nodes: int,
    smooth: bool,
    pad_cells: int = 5,
) -> PathPlanResult:
    """A* 路径规划主入口。

    所有失败情况都通过返回值表达（``found=False`` + ``message``），**不抛异常**，
    这样 HTTP 层始终可以返回 200，方便调用方降级处理。

    :param start_lat: 起点纬度
    :param start_lon: 起点经度
    :param goal_lat: 终点纬度
    :param goal_lon: 终点经度
    :param altitude_m: 飞行高度（米），决定哪些障碍物生效
    :param obstacles: 障碍物/禁飞区列表
    :param grid_size_m: 栅格边长（米）
    :param keep_out_margin_m: 障碍物膨胀安全间隔（米）
    :param max_search_nodes: 最大展开节点数，超过即失败
    :param smooth: 是否做视线法平滑
    :param pad_cells: 外接矩形预留栅格数
    :return: :class:`PathPlanResult`
    """
    started = time.perf_counter()

    # ---------- 1. 构建栅格（含障碍膨胀、高度过滤、航线走廊过滤） ----------
    try:
        grid = build_grid(
            start_lat=start_lat,
            start_lon=start_lon,
            goal_lat=goal_lat,
            goal_lon=goal_lon,
            altitude_m=altitude_m,
            obstacles=obstacles,
            cell_m=grid_size_m,
            keep_out_margin_m=keep_out_margin_m,
            pad_cells=pad_cells,
        )
    except GridTooLargeError:
        # 病态输入（超长航线 + 极小栅格）不允许把服务内存吃光：
        # 与其它失败一样以 found=False 返回，由调用方降级，绝不抛到 HTTP 层
        return _failure(MSG_GRID_TOO_LARGE, started)

    start_cell = grid.spec.latlon_to_cell(start_lat, start_lon)
    goal_cell = grid.spec.latlon_to_cell(goal_lat, goal_lon)

    # ---------- 2. 起终点合法性检查 ----------
    if not grid.is_free(*start_cell):
        return _failure(MSG_START_INSIDE_OBSTACLE, started)
    if not grid.is_free(*goal_cell):
        return _failure(MSG_GOAL_INSIDE_OBSTACLE, started)

    # ---------- 3. A* 搜索（栅格索引空间） ----------
    cell_path, expanded, failure = _astar(grid, start_cell, goal_cell, max_search_nodes)
    if failure is not None:
        return _failure(failure, started, expanded)

    # ---------- 4. 栅格路径 → 米制航路点，并把首尾换成精确坐标 ----------
    points_m = [grid.spec.cell_center_m(cx, cy) for cx, cy in cell_path]
    points_m[0] = geo.latlon_to_local(start_lat, start_lon, grid.spec.origin_lat, grid.spec.origin_lon)
    points_m[-1] = geo.latlon_to_local(goal_lat, goal_lon, grid.spec.origin_lat, grid.spec.origin_lon)

    # ---------- 5. 平滑：视线法压拐点 → 转弯圆角化（越障校验内置） ----------
    if smooth and len(points_m) > 2:
        points_m = _smooth(grid, points_m)
        points_m = _round_corners(grid, points_m)

    # ---------- 6. 组装航路点（累计距离用 haversine，单位米） ----------
    latlons = [
        geo.local_to_latlon(x_m, y_m, grid.spec.origin_lat, grid.spec.origin_lon) for x_m, y_m in points_m
    ]
    waypoints = _build_waypoints(latlons, altitude_m)
    total = waypoints[-1].cumulativeDistanceM if waypoints else 0.0

    return PathPlanResult(
        found=True,
        message=MSG_OK,
        distance_m=total,
        waypoints=waypoints,
        expanded_nodes=expanded,
        elapsed_ms=_elapsed_ms(started),
    )


# --------------------------------------------------------------------------- #
# A* 核心
# --------------------------------------------------------------------------- #
def _astar(
    grid: Grid,
    start_cell: tuple[int, int],
    goal_cell: tuple[int, int],
    max_search_nodes: int,
) -> tuple[list[tuple[int, int]], int, str | None]:
    """在栅格上跑 A*。

    :return: ``(栅格路径, 展开节点数, 失败原因码)``；成功时失败原因为 ``None``
    """
    cell_m = grid.spec.cell_m
    gx_goal, gy_goal = goal_cell

    # 开放列表：最小堆，按 f 值排序
    open_heap: list[tuple[float, int, int, int]] = []
    tie = 0
    g_score: dict[tuple[int, int], float] = {start_cell: 0.0}
    came_from: dict[tuple[int, int], tuple[int, int]] = {}

    start_h = _heuristic(start_cell, goal_cell)
    heapq.heappush(open_heap, (start_h, tie, start_cell[0], start_cell[1]))

    expanded = 0
    closed: set[tuple[int, int]] = set()

    while open_heap:
        f_current, _, cx, cy = heapq.heappop(open_heap)
        current = (cx, cy)

        # 惰性删除：堆里可能残留同一节点的旧记录
        if current in closed:
            continue
        closed.add(current)
        expanded += 1

        if current == goal_cell:
            return _reconstruct(came_from, current), expanded, None

        # 展开节点数超过上限 → 判定失败，避免极端场景把服务卡死
        if expanded > max_search_nodes:
            return [], expanded, MSG_MAX_NODES

        current_g = g_score[current]
        for dx, dy, base_cost in NEIGHBORS:
            nx, ny = cx + dx, cy + dy
            if not grid.is_free(nx, ny):
                continue
            # 对角移动禁止「穿尖角」：两个正交邻居都必须可通行
            if dx != 0 and dy != 0 and not (grid.is_free(cx + dx, cy) and grid.is_free(cx, cy + dy)):
                continue

            neighbor = (nx, ny)
            if neighbor in closed:
                continue

            tentative_g = current_g + base_cost * cell_m
            if tentative_g < g_score.get(neighbor, math.inf):
                g_score[neighbor] = tentative_g
                came_from[neighbor] = current
                h = _heuristic(neighbor, goal_cell)
                tie += 1
                heapq.heappush(open_heap, (tentative_g + h, tie, nx, ny))

    # 堆空仍未到达终点：确实没有可行路径
    return [], expanded, MSG_NO_PATH


def _heuristic(cell: tuple[int, int], goal: tuple[int, int]) -> float:
    """启发函数：到终点的欧氏距离（栅格单位），可采纳且比曼哈顿距离更紧。"""
    return math.hypot(cell[0] - goal[0], cell[1] - goal[1])


def _reconstruct(
    came_from: dict[tuple[int, int], tuple[int, int]],
    current: tuple[int, int],
) -> list[tuple[int, int]]:
    """从 came_from 回溯出从起点到终点的栅格路径。"""
    path = [current]
    while current in came_from:
        current = came_from[current]
        path.append(current)
    path.reverse()
    return path


# --------------------------------------------------------------------------- #
# 平滑
# --------------------------------------------------------------------------- #
def _smooth(grid: Grid, points_m: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """第一级平滑：视线法（line-of-sight）捷径，把栅格台阶压成少量拐点。

    贪心策略：从当前锚点出发，向后找**最远**的、与锚点连线不穿过任何不可通行
    栅格的点，把它作为下一个航路点；重复直到终点。

    由于碰撞判定复用 A* 的同一张 blocked 栅格，平滑后的航线
    **一定不会穿越膨胀障碍物**，只是拐点更少、更接近直线。

    注意：这一级只减少拐点，航线仍是折线（有尖角）。真正的"会转弯"
    由 :func:`_round_corners` 在第二级完成。
    """
    result: list[tuple[float, float]] = [points_m[0]]
    anchor = 0
    n = len(points_m)

    while anchor < n - 1:
        farthest = anchor + 1
        # 从最远处往回找，第一个「连得通」的就是最远可达点
        for candidate in range(n - 1, anchor, -1):
            ax, ay = points_m[anchor]
            bx, by = points_m[candidate]
            if grid.path_is_clear(ax, ay, bx, by):
                farthest = candidate
                break
        result.append(points_m[farthest])
        anchor = farthest

    return result


# --------------------------------------------------------------------------- #
# 转弯圆角化（让航线像真飞机飞出来的弧线）
# --------------------------------------------------------------------------- #
def _round_corners(
    grid: Grid,
    points_m: list[tuple[float, float]],
    radius_m: float = TURN_RADIUS_M,
    samples: int = CORNER_SAMPLES,
) -> list[tuple[float, float]]:
    """把折线航路的每个拐点换成一段相切圆弧（用二次贝塞尔采样逼近）。

    做法（逐拐点）：
      1. 取拐点 P 与前后两点 A、B，沿 PA、PB 各退 ``cut`` 米得到切点 P1、P2；
         ``cut`` 同时受半径与"相邻段长度的一半"限制，这样相邻两个圆角不会重叠。
      2. 以 P 为控制点、P1→P2 为端点的二次贝塞尔，就是在 P 处与两段相切的圆弧近似。
      3. 用 A* 同一张膨胀栅格逐段校验这条弧；不通过就保留原尖角。

    :param grid: A* 使用的栅格（含膨胀障碍标记）
    :param points_m: 局部米制折线（已做过视线法平滑）
    :param radius_m: 期望转弯半径（米）
    :param samples: 每段弧的采样段数
    :return: 圆角化后的折线（首尾点保持不变）
    """
    pts = _dedupe(points_m)
    if len(pts) < 3:
        # 两点直线或单点，没有拐角可圆
        return pts

    result: list[tuple[float, float]] = [pts[0]]

    for i in range(1, len(pts) - 1):
        a, p, b = pts[i - 1], pts[i], pts[i + 1]
        la = math.hypot(p[0] - a[0], p[1] - a[1])
        lb = math.hypot(b[0] - p[0], b[1] - p[1])
        if la <= MIN_POINT_GAP_M or lb <= MIN_POINT_GAP_M:
            continue

        cut = min(radius_m, la * 0.5, lb * 0.5)
        if cut < 1.0:
            # 拐角两段太短，圆角没有意义，保留尖角
            result.append(p)
            continue

        p1 = (p[0] + (a[0] - p[0]) * (cut / la), p[1] + (a[1] - p[1]) * (cut / la))
        p2 = (p[0] + (b[0] - p[0]) * (cut / lb), p[1] + (b[1] - p[1]) * (cut / lb))
        curve = _quadratic_bezier(p1, p, p2, samples)

        if _curve_is_clear(grid, curve):
            result.extend(curve)
        else:
            # 圆角会侵入膨胀障碍 → 安全优先，退回尖角
            result.append(p)

    result.append(pts[-1])
    return _dedupe(result)


def _quadratic_bezier(
    p1: tuple[float, float],
    control: tuple[float, float],
    p2: tuple[float, float],
    samples: int,
) -> list[tuple[float, float]]:
    """二次贝塞尔曲线的等参数采样（``samples`` 段 → ``samples + 1`` 个点）。"""
    out: list[tuple[float, float]] = []
    for i in range(samples + 1):
        t = i / samples
        mt = 1.0 - t
        x = mt * mt * p1[0] + 2.0 * mt * t * control[0] + t * t * p2[0]
        y = mt * mt * p1[1] + 2.0 * mt * t * control[1] + t * t * p2[1]
        out.append((x, y))
    return out


def _curve_is_clear(grid: Grid, curve: list[tuple[float, float]]) -> bool:
    """圆弧采样点是否全程落在可通行栅格内（复用 A* 的膨胀栅格）。"""
    for i in range(len(curve) - 1):
        ax, ay = curve[i]
        bx, by = curve[i + 1]
        if not grid.path_is_clear(ax, ay, bx, by):
            return False
    return True


def _dedupe(points_m: list[tuple[float, float]], min_gap_m: float = MIN_POINT_GAP_M) -> list[tuple[float, float]]:
    """去掉相邻的重复点。

    圆角采样与切点拼接时很容易产生相距几厘米的点；这类点会让
    "累积距离""进度百分比"等按段计算的地方白白多做一次除法，
    极端情况下还会退化成零长度段。统一在最后清理一遍。
    """
    out: list[tuple[float, float]] = []
    for point in points_m:
        if out and math.hypot(point[0] - out[-1][0], point[1] - out[-1][1]) < min_gap_m:
            continue
        out.append(point)
    return out


# --------------------------------------------------------------------------- #
# 结果组装
# --------------------------------------------------------------------------- #
def _build_waypoints(latlons: list[tuple[float, float]], altitude_m: float) -> list[WaypointModel]:
    """把经纬度序列转成航路点模型，累计距离从 0.0 开始逐段累加。"""
    waypoints: list[WaypointModel] = []
    cumulative = 0.0

    for seq, (lat, lon) in enumerate(latlons):
        if seq > 0:
            prev_lat, prev_lon = latlons[seq - 1]
            cumulative += geo.haversine_m(prev_lat, prev_lon, lat, lon)
        waypoints.append(
            WaypointModel(
                seq=seq,
                lat=round(lat, 7),
                lon=round(lon, 7),
                altitudeM=altitude_m,
                cumulativeDistanceM=round(cumulative, 2),
            )
        )
    return waypoints


def _failure(
    message: str,
    started: float,
    expanded: int = 0,
) -> PathPlanResult:
    """构造失败结果（HTTP 仍返回 200）。"""
    return PathPlanResult(
        found=False,
        message=message,
        distance_m=0.0,
        waypoints=[],
        expanded_nodes=expanded,
        elapsed_ms=_elapsed_ms(started),
    )


def _elapsed_ms(started: float) -> float:
    """耗时（毫秒），保留两位小数便于阅读。"""
    return round((time.perf_counter() - started) * 1000.0, 2)
