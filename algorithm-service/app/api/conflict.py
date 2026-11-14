"""冲突检测接口：``POST /api/v1/conflict/check``（契约 4.3）。

同样遵循「算法内部异常不返回 5xx」的原则：异常时返回
``success=false`` + ``conflicts=[]`` + ``conflictCount=0``。
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter

from app.algorithms.conflict import check_conflicts
from app.core.config import settings
from app.schemas.conflict import ConflictCheckRequest, ConflictCheckResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/conflict", tags=["conflict"])


@router.post("/check", response_model=ConflictCheckResponse, summary="冲突检测")
def check(request: ConflictCheckRequest) -> ConflictCheckResponse:
    """对一批无人机状态做机-机、机-区、低电量三类冲突检测。

    :param request: 契约 4.3 定义的请求体
    :return: :class:`ConflictCheckResponse`
    """
    request_id = request.requestId or str(uuid.uuid4())

    try:
        result = check_conflicts(
            drones=request.drones,
            zones=request.zones,
            lookahead_seconds=request.lookaheadSeconds,
            min_horizontal_separation_m=request.minHorizontalSeparationM,
            min_vertical_separation_m=request.minVerticalSeparationM,
            step_s=settings.extrapolation_step_s,
            battery_warn_pct=settings.battery_warn_pct,
            battery_critical_pct=settings.battery_critical_pct,
        )
    except Exception:  # noqa: BLE001 - 兜底：异常也要转成可解析的响应
        logger.exception("冲突检测内部异常 requestId=%s", request_id)
        return ConflictCheckResponse(
            success=False,
            requestId=request_id,
            conflictCount=0,
            conflicts=[],
            message="internal_error",
            elapsedMs=0.0,
        )

    return ConflictCheckResponse(
        success=True,
        requestId=request_id,
        conflictCount=result.conflict_count,
        conflicts=result.conflicts,
        message=result.message,
        elapsedMs=result.elapsed_ms,
    )
