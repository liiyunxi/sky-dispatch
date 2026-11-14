"""冲突检测接口的数据模型（对应契约 4.3 `POST /api/v1/conflict/check`）。

字段名与契约**逐字一致**（camelCase）。冲突类型有三种：
``DRONE_DRONE``（机-机）、``DRONE_ZONE``（机-空域）、``BATTERY_RISK``（低电量）。
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.common import Obstacle


class ConflictType(StrEnum):
    """冲突类型。"""

    DRONE_DRONE = "DRONE_DRONE"
    DRONE_ZONE = "DRONE_ZONE"
    BATTERY_RISK = "BATTERY_RISK"


class Severity(StrEnum):
    """冲突严重程度，由低到高。"""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class DroneState(BaseModel):
    """参与冲突检测的无人机状态快照。"""

    model_config = ConfigDict(extra="ignore")

    droneId: int = Field(description="无人机主键")
    droneCode: str | None = Field(default=None, description="无人机编号，如 UAV-003")
    lat: float = Field(ge=-90.0, le=90.0, description="当前纬度")
    lon: float = Field(ge=-180.0, le=180.0, description="当前经度")
    altitudeM: float = Field(default=80.0, description="当前高度（米）")
    speedMps: float = Field(default=0.0, ge=0.0, description="地速（米/秒）")
    headingDeg: float = Field(default=0.0, description="航向角（度，正北为 0，顺时针）")
    batteryPct: float = Field(default=100.0, ge=0.0, le=100.0, description="电量百分比")

    def label(self) -> str:
        """返回消息里使用的无人机标识。"""
        return self.droneCode or f"UAV-{self.droneId}"


class ConflictCheckRequest(BaseModel):
    """冲突检测请求。"""

    model_config = ConfigDict(extra="ignore")

    requestId: str | None = Field(default=None, description="调用方请求号，未传则服务端生成 uuid4")
    checkTime: datetime | None = Field(default=None, description="检测时刻（ISO8601，MVP 仅回显/日志用）")
    lookaheadSeconds: float = Field(default=60.0, gt=0.0, description="外推时长（秒）")
    minHorizontalSeparationM: float = Field(default=50.0, gt=0.0, description="最小水平安全间隔（米）")
    minVerticalSeparationM: float = Field(default=15.0, gt=0.0, description="最小垂直安全间隔（米）")
    drones: list[DroneState] = Field(default_factory=list, description="无人机状态列表")
    zones: list[Obstacle] = Field(default_factory=list, description="禁飞/限制区列表")


class ConflictModel(BaseModel):
    """单条冲突记录，字段与契约 4.3 响应对应。"""

    model_config = ConfigDict(extra="ignore")

    conflictType: ConflictType = Field(description="冲突类型")
    severity: Severity = Field(description="严重程度")
    droneAId: int | None = Field(default=None, description="无人机 A 主键")
    droneBId: int | None = Field(default=None, description="无人机 B 主键（机-机冲突才有）")
    zoneId: str | None = Field(default=None, description="空域区域编号（机-区冲突才有）")
    horizontalDistanceM: float = Field(default=0.0, ge=0.0, description="最小水平间距（米）")
    verticalDistanceM: float = Field(default=0.0, ge=0.0, description="最小垂直间距（米）")
    closingSpeedMps: float = Field(default=0.0, description="接近率（米/秒），越大越危险")
    timeToConflictS: float | None = Field(default=None, ge=0.0, description="首次突破阈值的时刻（秒）")
    lat: float = Field(description="冲突位置纬度")
    lon: float = Field(description="冲突位置经度")
    message: str = Field(description="中文描述，便于直接展示与落库")


class ConflictCheckResponse(BaseModel):
    """冲突检测响应。无冲突时 ``conflicts`` 为空数组、``conflictCount`` 为 0。"""

    model_config = ConfigDict(extra="ignore")

    success: bool = Field(description="服务是否正常处理了本次请求")
    requestId: str = Field(description="回显的请求号")
    conflictCount: int = Field(default=0, ge=0, description="冲突条数")
    conflicts: list[ConflictModel] = Field(default_factory=list, description="冲突明细")
    message: str = Field(default="ok", description="结果说明")
    elapsedMs: float = Field(default=0.0, ge=0.0, description="算法耗时（毫秒）")
