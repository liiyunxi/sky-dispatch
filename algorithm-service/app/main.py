"""FastAPI 应用入口。

包含：CORS 中间件、路由注册、422 校验错误的统一响应体、启动日志。

本地启动：
    .\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --port 8000

接口清单（前缀 ``/api/v1``）见根路径 ``GET /``。
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api import conflict as conflict_api
from app.api import health as health_api
from app.api import path as path_api
from app.core.config import settings

#: 日志在「模块导入时」就配置好，确保 lifespan / 启动日志能被正确格式化输出
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """应用生命周期：启动时打印监听地址与接口清单，关闭时打印退出日志。"""
    logger.info("=" * 68)
    logger.info("%s v%s 启动中……", settings.app_name, settings.version)
    logger.info("监听地址: http://%s:%d", settings.host, settings.port)
    logger.info("接口文档: http://127.0.0.1:%d/docs", settings.port)
    logger.info("接口清单: http://127.0.0.1:%d/", settings.port)
    logger.info("已注册接口:")
    for method, route, _ in _routes():
        logger.info("  %-6s %s", method, route)
    logger.info("=" * 68)
    yield
    logger.info("%s 已停止", settings.app_name)


def _routes() -> list[tuple[str, str, str]]:
    """返回已注册的 ``(HTTP 方法, 路径, 中文说明)`` 列表。

    实现说明：FastAPI 0.142 起，``include_router`` 注册进来的是包装对象
    （``_IncludedRouter``），它没有 ``path`` / ``methods`` 属性，
    直接遍历 ``app.routes`` 会漏掉业务接口。
    因此这里改为读取 OpenAPI 结构（它按路径汇总了真实可访问的接口），
    并过滤掉 FastAPI 自带的文档路由。

    :return: 按路径排序的接口清单
    """
    builtin = {"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"}
    schema = app.openapi()
    items: list[tuple[str, str, str]] = []

    for path, operations in schema.get("paths", {}).items():
        if path in builtin:
            continue
        for method in operations:
            items.append((method.upper(), path, _describe(path)))

    return sorted(items, key=lambda item: (item[1], item[0]))


app = FastAPI(
    title="sky-dispatch 算法服务",
    description="低空无人机配送调度仿真平台 —— A* 路径规划与冲突检测（无状态纯计算服务）",
    version=settings.version,
    lifespan=lifespan,
)

# CORS：允许所有来源，方便前端调试页面直接调用
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allow_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 路由注册
app.include_router(health_api.router)
app.include_router(path_api.router)
app.include_router(conflict_api.router)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    """把 FastAPI 默认的 422 校验错误改成更易读的响应体（HTTP 状态码仍是 422）。"""
    details = [
        {
            "loc": ".".join(str(part) for part in error.get("loc", [])),
            "msg": error.get("msg", ""),
            "type": error.get("type", ""),
        }
        for error in exc.errors()
    ]
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "message": "参数校验失败",
            "detail": details,
        },
    )


@app.get("/", tags=["meta"], summary="服务简介与接口清单")
def index() -> dict[str, object]:
    """返回服务简介、契约版本与全部可用接口，方便人工自检。"""
    return {
        "service": settings.app_name,
        "version": settings.version,
        "description": "低空无人机配送调度仿真平台算法服务：A* 路径规划 + 冲突检测（无状态）",
        "contract": "docs/03-api-contract.md 第 4 节",
        "endpoints": [
            {"method": method, "path": path, "description": description}
            for method, path, description in _routes()
        ],
    }


def _describe(path: str) -> str:
    """给接口路径配一句中文说明。"""
    mapping = {
        "/": "服务简介与接口清单",
        "/health": "健康检查",
        "/api/v1/path/plan": "A* 路径规划（8 邻域 + 障碍膨胀 + 视线法平滑）",
        "/api/v1/conflict/check": "冲突检测（机-机 / 机-禁飞区 / 低电量）",
    }
    return mapping.get(path, "")


if __name__ == "__main__":  # pragma: no cover - 便于 `python -m app.main` 直接启动
    import uvicorn

    uvicorn.run("app.main:app", host=settings.host, port=settings.port, log_level=settings.log_level.lower())
