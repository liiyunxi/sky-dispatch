"""路径规划接口的数据模型（对应契约 4.2 `POST /api/v1/path/plan`）。

字段名与契约**逐字一致**（camelCase）。请求里的 ``requestId`` 可选，
未传时由服务端生成 uuid4 并在响应中回显。
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.common import LatLon, Obstacle


class PathPlanRequest(BaseModel):
    """A* 路径规划请求。"""

    model_config = ConfigDict(extra="ignore")

    requestId: str | None = Field(default=None, description="调用方请求号，未传则服务端生成 uuid4")
    start: LatLon = Field(description="起点")
    goal: LatLon = Field(description="终点")
    altitudeM: float = Field(default=80.0, description="飞行高度（米），决定哪些障碍物生效")
    cruiseSpeedMps: float = Field(default=12.0, gt=0.0, description="巡航速度（米/秒）")
    gridSizeM: float = Field(default=50.0, gt=0.0, description="栅格边长（米）")
    maxSearchNodes: int = Field(default=200_000, gt=0, description="最大搜索节点数")
    keepOutMarginM: float = Field(default=30.0, ge=0.0, description="障碍物膨胀安全间隔（米）")
    smooth: bool = Field(default=True, description="是否做视线法平滑")
    obstacles: list[Obstacle] = Field(default_factory=list, description="障碍物/禁飞区列表")


class WaypointModel(BaseModel):
    """航路点。``cumulativeDistanceM`` 为从起点累计的距离（米），首点为 0.0。"""

    model_config = ConfigDict(extra="ignore")

    seq: int = Field(ge=0, description="序号，从 0 开始")
    lat: float = Field(description="纬度")
    lon: float = Field(description="经度")
    altitudeM: float = Field(description="高度（米）")
    cumulativeDistanceM: float = Field(ge=0.0, description="累计距离（米）")


class PathPlanResponse(BaseModel):
    """A* 路径规划响应。

    规划失败**不返回 5xx**，而是 ``success=true, found=false`` + ``message``，
    方便 Java 侧降级处理；仅当算法内部抛异常时 ``success=false``。
    """

    model_config = ConfigDict(extra="ignore")

    success: bool = Field(description="服务是否正常处理了本次请求")
    requestId: str = Field(description="回显的请求号")
    algorithm: str = Field(default="ASTAR", description="使用的算法名")
    found: bool = Field(description="是否找到可行航线")
    message: str = Field(description="结果说明，失败时给出原因码（如 no_path_found）")
    distanceM: float = Field(default=0.0, ge=0.0, description="航线总距离（米）")
    waypoints: list[WaypointModel] = Field(default_factory=list, description="航路点列表")
    expandedNodes: int = Field(default=0, ge=0, description="A* 展开的节点数")
    elapsedMs: float = Field(default=0.0, ge=0.0, description="算法耗时（毫秒）")
