"""冲突检测：机-机外推、机-禁飞区、低电量。

三类冲突（与契约 4.3 规则表一致）：

1. ``DRONE_DRONE``（机-机）
   两机各自按 ``heading_deg`` + ``speed_mps`` 做匀速直线外推（1 秒一个采样点，
   覆盖 ``lookahead_seconds``），逐时刻比较水平间距与垂直间距。
   当**同时**满足「最小水平间距 < minHorizontalSeparationM」和
   「最小垂直间距 < minVerticalSeparationM」时判为冲突。
   严重度分档看最小水平间距相对阈值的位置：
   小于 0.5 倍 → CRITICAL，小于 0.8 倍 → HIGH，否则 MEDIUM。

2. ``DRONE_ZONE``（机-区）
   无人机当前位置或任一外推点落在禁飞/限制区的水平范围内，
   且飞行高度落在区域的 [minAltitudeM, maxAltitudeM] 内 → 冲突。
   severity：NO_FLY → CRITICAL，RESTRICTED → HIGH，其它 → MEDIUM。

3. ``BATTERY_RISK``（低电量）
   ``battery_pct`` < 20 → LOW；< 10 → MEDIUM。

坐标处理：外推在「以无人机当前位置为原点」的局部米制平面上做（几公里内精度足够），
算完再逆变换回经纬度，避免在经纬度上做矢量运算。
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field

from app.algorithms import geo
from app.schemas.common import Obstacle
from app.schemas.conflict import ConflictModel, ConflictType, DroneState, Severity

#: 严重度排序，便于取「更严重者」或排序展示
_SEVERITY_RANK: dict[Severity, int] = {
    Severity.LOW: 0,
    Severity.MEDIUM: 1,
    Severity.HIGH: 2,
    Severity.CRITICAL: 3,
}


@dataclass
class ConflictCheckResult:
    """冲突检测内部结果（api 层再转成契约响应模型）。"""

    conflicts: list[ConflictModel] = field(default_factory=list)
    message: str = "ok"
    elapsed_ms: float = 0.0

    @property
    def conflict_count(self) -> int:
        """冲突条数。"""
        return len(self.conflicts)


def check_conflicts(
    drones: list[DroneState],
    zones: list[Obstacle],
    lookahead_seconds: float,
    min_horizontal_separation_m: float,
    min_vertical_separation_m: float,
    step_s: float = 1.0,
    battery_warn_pct: float = 20.0,
    battery_critical_pct: float = 10.0,
) -> ConflictCheckResult:
    """执行全部三类冲突检测。

    :param drones: 无人机状态列表
    :param zones: 禁飞/限制区列表
    :param lookahead_seconds: 外推时长（秒）
    :param min_horizontal_separation_m: 最小水平安全间隔（米）
    :param min_vertical_separation_m: 最小垂直安全间隔（米）
    :param step_s: 外推时间步长（秒）
    :param battery_warn_pct: 低电量提示阈值（%）
    :param battery_critical_pct: 低电量警告阈值（%）
    :return: :class:`ConflictCheckResult`；无冲突时 ``conflicts`` 为空列表
    """
    started = time.perf_counter()
    conflicts: list[ConflictModel] = []
    step = step_s if step_s > 0 else 1.0

    # 机-机：两两组合
    for i in range(len(drones)):
        for j in range(i + 1, len(drones)):
            conflict = _detect_drone_drone(
                drones[i], drones[j], lookahead_seconds, min_horizontal_separation_m,
                min_vertical_separation_m, step,
            )
            if conflict is not None:
                conflicts.append(conflict)

    # 机-区：每架无人机对每个区域
    for drone in drones:
        for zone in zones:
            conflict = _detect_drone_zone(drone, zone, lookahead_seconds, step)
            if conflict is not None:
                conflicts.append(conflict)

    # 低电量
    for drone in drones:
        conflict = _detect_battery_risk(drone, battery_warn_pct, battery_critical_pct)
        if conflict is not None:
            conflicts.append(conflict)

    return ConflictCheckResult(
        conflicts=conflicts,
        message="ok",
        elapsed_ms=round((time.perf_counter() - started) * 1000.0, 2),
    )


# --------------------------------------------------------------------------- #
# 1. 机-机冲突
# --------------------------------------------------------------------------- #
def _detect_drone_drone(
    a: DroneState,
    b: DroneState,
    lookahead_seconds: float,
    min_horizontal_m: float,
    min_vertical_m: float,
    step_s: float,
) -> ConflictModel | None:
    """机-机外推检测。

    外推模型（局部平面，以 A 当前位置为原点）：

        x(t) = x0 + speed · sin(heading) · t
        y(t) = y0 + speed · cos(heading) · t
        z(t) = z0   （MVP 假设高度保持不变）

    逐时刻求水平间距（平面欧氏距离）与垂直间距（|Δz|），
    记录最小值、首次同时突破两个阈值的时刻，以及接近率。
    """
    origin_lat, origin_lon = a.lat, a.lon
    ax, ay = 0.0, 0.0
    bx, by = geo.latlon_to_local(b.lat, b.lon, origin_lat, origin_lon)

    # 速度矢量（米/秒）
    a_vx, a_vy = _velocity(a)
    b_vx, b_vy = _velocity(b)

    steps = max(1, int(math.ceil(lookahead_seconds / step_s)))

    min_horizontal = math.inf
    min_horizontal_t = 0.0
    min_vertical = abs(a.altitudeM - b.altitudeM)
    time_to_conflict: float | None = None

    for k in range(steps + 1):
        t = min(k * step_s, lookahead_seconds)
        a_tx, a_ty = ax + a_vx * t, ay + a_vy * t
        b_tx, b_ty = bx + b_vx * t, by + b_vy * t

        horizontal = math.hypot(a_tx - b_tx, a_ty - b_ty)
        if horizontal < min_horizontal:
            min_horizontal = horizontal
            min_horizontal_t = t
        # 高度恒定，垂直间距在所有时刻相同
        vertical = abs(a.altitudeM - b.altitudeM)
        min_vertical = min(min_vertical, vertical)

        # 首次同时突破两个阈值 → 冲突开始时刻
        if time_to_conflict is None and horizontal < min_horizontal_m and vertical < min_vertical_m:
            time_to_conflict = t

    # 两个条件必须同时满足才算冲突
    if not (min_horizontal < min_horizontal_m and min_vertical < min_vertical_m):
        return None

    severity = _separation_severity(min_horizontal, min_horizontal_m)
    closing_speed = _closing_speed(a, b)

    # 冲突位置：取最小水平间距时刻两机连线的中点
    t = min_horizontal_t
    mid_x = (ax + a_vx * t + bx + b_vx * t) / 2.0
    mid_y = (ay + a_vy * t + by + b_vy * t) / 2.0
    lat, lon = geo.local_to_latlon(mid_x, mid_y, origin_lat, origin_lon)

    message = (
        f"{a.label()} 与 {b.label()} 预计 {t:.1f}s 后水平间距 {min_horizontal:.0f}m，"
        f"低于 {min_horizontal_m:.0f}m 安全阈值（垂直间距 {min_vertical:.0f}m，"
        f"接近率 {closing_speed:.1f}m/s）"
    )

    return ConflictModel(
        conflictType=ConflictType.DRONE_DRONE,
        severity=severity,
        droneAId=a.droneId,
        droneBId=b.droneId,
        zoneId=None,
        horizontalDistanceM=round(min_horizontal, 2),
        verticalDistanceM=round(min_vertical, 2),
        closingSpeedMps=round(closing_speed, 2),
        timeToConflictS=round(time_to_conflict, 2) if time_to_conflict is not None else None,
        lat=round(lat, 7),
        lon=round(lon, 7),
        message=message,
    )


def _velocity(drone: DroneState) -> tuple[float, float]:
    """把航向角 + 速度转成局部平面速度矢量（x 向东、y 向北，米/秒）。"""
    ux, uy = geo.heading_to_unit_vector(drone.headingDeg)
    return ux * drone.speedMps, uy * drone.speedMps


def _separation_severity(horizontal_m: float, threshold_m: float) -> Severity:
    """按最小水平间距相对阈值的比例分档：<0.5 → CRITICAL，<0.8 → HIGH，否则 MEDIUM。"""
    ratio = horizontal_m / threshold_m if threshold_m > 0 else 1.0
    if ratio < 0.5:
        return Severity.CRITICAL
    if ratio < 0.8:
        return Severity.HIGH
    return Severity.MEDIUM


def _closing_speed(a: DroneState, b: DroneState) -> float:
    """两机速度矢量在「连线方向」上的接近率（米/秒）。

    连线的单位向量取 A→B 方向；把两机速度都投影到该方向后，
    ``接近率 = (vA - vB) · 单位向量``：
    为正说明 A 在向 B 靠近（或 B 反向远离），数值越大越危险。
    """
    distance = geo.haversine_m(a.lat, a.lon, b.lat, b.lon)
    if distance < 1e-6:
        # 位置重合，无法定义连线方向，退化用两机速率之和
        return a.speedMps + b.speedMps

    # 在局部平面里算连线单位向量
    bx, by = geo.latlon_to_local(b.lat, b.lon, a.lat, a.lon)
    norm = math.hypot(bx, by)
    ux, uy = bx / norm, by / norm

    a_vx, a_vy = _velocity(a)
    b_vx, b_vy = _velocity(b)
    return (a_vx - b_vx) * ux + (a_vy - b_vy) * uy


# --------------------------------------------------------------------------- #
# 2. 机-区冲突
# --------------------------------------------------------------------------- #
def _detect_drone_zone(
    drone: DroneState,
    zone: Obstacle,
    lookahead_seconds: float,
    step_s: float,
) -> ConflictModel | None:
    """机-区检测：当前位置或外推点进入区域水平范围，且高度落在区域高度区间内。"""
    # 高度不在区域生效区间 → 该区域对本机不生效（例如从禁飞区上方飞过）
    if not zone.is_active_at(drone.altitudeM):
        return None

    # 当前位置已在区内属于既成事实，不再产出 timeToConflictS=0 的"预测"冲突；
    # 否则前端会反复出现"预计 0.0s 后触发"这种没有预警意义的提醒。
    if zone.contains_point(drone.lat, drone.lon):
        return None

    # 再做匀速外推
    vx, vy = _velocity(drone)
    steps = max(1, int(math.ceil(lookahead_seconds / step_s)))

    for k in range(1, steps + 1):
        t = min(k * step_s, lookahead_seconds)
        x_m, y_m = vx * t, vy * t
        lat, lon = geo.local_to_latlon(x_m, y_m, drone.lat, drone.lon)
        if zone.contains_point(lat, lon):
            return _zone_conflict(drone, zone, time_to_conflict=t, lat=lat, lon=lon)

    return None


def _zone_conflict(
    drone: DroneState,
    zone: Obstacle,
    time_to_conflict: float,
    lat: float,
    lon: float,
) -> ConflictModel:
    """构造机-区冲突记录，severity 由区域类型决定。"""
    severity = _zone_severity(zone)

    if time_to_conflict <= 0.0:
        message = (
            f"{drone.label()} 当前位于{_zone_type_label(zone)}「{zone.label()}」范围内，"
            f"高度 {drone.altitudeM:.0f}m，需立即避让"
        )
    else:
        message = (
            f"{drone.label()} 预计 {time_to_conflict:.1f}s 后进入{_zone_type_label(zone)}"
            f"「{zone.label()}」，高度 {drone.altitudeM:.0f}m"
        )

    return ConflictModel(
        conflictType=ConflictType.DRONE_ZONE,
        severity=severity,
        droneAId=drone.droneId,
        droneBId=None,
        zoneId=zone.id,
        horizontalDistanceM=0.0,
        verticalDistanceM=0.0,
        closingSpeedMps=round(drone.speedMps, 2),
        timeToConflictS=round(time_to_conflict, 2),
        lat=round(lat, 7),
        lon=round(lon, 7),
        message=message,
    )


def _zone_severity(zone: Obstacle) -> Severity:
    """区域类型 → severity：禁飞区 CRITICAL，限制区 HIGH，其它 MEDIUM。"""
    zone_type = str(getattr(zone, "zoneType", "") or "").upper()
    if zone_type == "NO_FLY":
        return Severity.CRITICAL
    if zone_type == "RESTRICTED":
        return Severity.HIGH
    return Severity.MEDIUM


def _zone_type_label(zone: Obstacle) -> str:
    """区域类型的中文标签（消息文案用）：禁飞区 / 限制区 / 管制区。"""
    zone_type = str(getattr(zone, "zoneType", "") or "").upper()
    return {"NO_FLY": "禁飞区", "RESTRICTED": "限制区"}.get(zone_type, "管制区")


# --------------------------------------------------------------------------- #
# 3. 低电量
# --------------------------------------------------------------------------- #
def _detect_battery_risk(
    drone: DroneState,
    warn_pct: float,
    critical_pct: float,
) -> ConflictModel | None:
    """低电量检测：< warn_pct → LOW；< critical_pct → MEDIUM。"""
    if drone.batteryPct >= warn_pct:
        return None

    severity = Severity.MEDIUM if drone.batteryPct < critical_pct else Severity.LOW
    if severity is Severity.LOW:
        message = (
            f"{drone.label()} 电量 {drone.batteryPct:.1f}%，低于警戒阈值 {warn_pct:.0f}%，请安排返航或就近补能"
        )
    else:
        message = (
            f"{drone.label()} 电量仅 {drone.batteryPct:.1f}%，低于 {critical_pct:.0f}%，"
            f"存在迫降风险，建议立即中止任务返航"
        )

    return ConflictModel(
        conflictType=ConflictType.BATTERY_RISK,
        severity=severity,
        droneAId=drone.droneId,
        droneBId=None,
        zoneId=None,
        horizontalDistanceM=0.0,
        verticalDistanceM=0.0,
        closingSpeedMps=0.0,
        timeToConflictS=None,
        lat=round(drone.lat, 7),
        lon=round(drone.lon, 7),
        message=message,
    )


def severity_rank(severity: Severity) -> int:
    """返回 severity 的数值等级（越大越严重），供排序/展示使用。"""
    return _SEVERITY_RANK.get(severity, 0)
