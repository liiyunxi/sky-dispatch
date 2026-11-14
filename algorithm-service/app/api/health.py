"""健康检查接口。

Java 后端启动时可用它探活；返回体字段与契约 4.1 节逐字一致。
"""

from __future__ import annotations

import platform

from fastapi import APIRouter

from app.core.config import settings

router = APIRouter(tags=["health"])


@router.get("/health", summary="健康检查")
def health() -> dict[str, str]:
    """返回服务状态与运行环境信息。

    :return: ``{"status": "ok", "service": ..., "version": ..., "python": ...}``
    """
    return {
        "status": "ok",
        "service": settings.app_name,
        "version": settings.version,
        "python": platform.python_version(),
    }
