"""路径规划接口：``POST /api/v1/path/plan``（契约 4.2）。

职责边界：
* 本模块只做「请求模型 → 算法调用 → 响应模型」的转换与异常兜底；
* 真正的算法在 :mod:`app.algorithms.astar`；
* 规划失败（无路可走、起点在禁飞区等）返回 ``success=true, found=false``，HTTP 仍是 200；
* 只有算法内部抛异常时才返回 ``success=false``，同样**不返回 5xx**。
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter

from app.algorithms.astar import MSG_INTERNAL_ERROR, plan_path
from app.core.config import settings
from app.schemas.path import PathPlanRequest, PathPlanResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/path", tags=["path"])


@router.post("/plan", response_model=PathPlanResponse, summary="A* 路径规划")
def plan(request: PathPlanRequest) -> PathPlanResponse:
    """根据起终点与障碍物规划一条避障航线。

    :param request: 契约 4.2 定义的请求体
    :return: :class:`PathPlanResponse`
    """
    # requestId 可选：未传则服务端生成 uuid4 并回显，便于全链路追踪
    request_id = request.requestId or str(uuid.uuid4())

    try:
        result = plan_path(
            start_lat=request.start.lat,
            start_lon=request.start.lon,
            goal_lat=request.goal.lat,
            goal_lon=request.goal.lon,
            altitude_m=request.altitudeM,
            obstacles=request.obstacles,
            grid_size_m=request.gridSizeM,
            keep_out_margin_m=request.keepOutMarginM,
            max_search_nodes=request.maxSearchNodes,
            smooth=request.smooth,
            pad_cells=settings.bbox_pad_cells,
        )
    except Exception:  # noqa: BLE001 - 兜底：算法异常也要转成可读响应，不能 500
        logger.exception("路径规划内部异常 requestId=%s", request_id)
        return PathPlanResponse(
            success=False,
            requestId=request_id,
            algorithm="ASTAR",
            found=False,
            message=MSG_INTERNAL_ERROR,
            distanceM=0.0,
            waypoints=[],
            expandedNodes=0,
            elapsedMs=0.0,
        )

    return PathPlanResponse(
        success=True,
        requestId=request_id,
        algorithm=result.algorithm,
        found=result.found,
        message=result.message,
        distanceM=result.distance_m,
        waypoints=result.waypoints,
        expandedNodes=result.expanded_nodes,
        elapsedMs=result.elapsed_ms,
    )
