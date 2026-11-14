"""服务配置。

使用 pydantic-settings 从**环境变量**（可再叠加 `.env` 文件）读取配置，
环境变量统一加前缀 ``ALGO_``，例如：

    ALGO_PORT=8001
    ALGO_DEFAULT_GRID_SIZE_M=25
    ALGO_LOG_LEVEL=DEBUG

所有配置项都有合理默认值，因此不设置任何环境变量也能直接启动。
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """算法服务运行参数（环境变量前缀 ``ALGO_``）。"""

    model_config = SettingsConfigDict(
        env_prefix="ALGO_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---------- 基础信息 ----------
    app_name: str = "sky-dispatch-algorithm"
    version: str = "0.1.0"
    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"
    debug: bool = False

    # ---------- 路径规划（A*）默认值 ----------
    #: 栅格边长（米）。越小越精细，但搜索节点数按平方增长。
    default_grid_size_m: float = 50.0
    #: 障碍物膨胀（缓冲区）距离（米），保证航线与禁飞区之间留有安全间隔。
    default_keep_out_margin_m: float = 30.0
    #: 单次搜索允许展开的最大节点数，超过即判定失败，避免极端情况下卡死。
    default_max_search_nodes: int = 200_000
    #: 是否需要视线法平滑（true 会显著减少航路点数量）。
    default_smooth: bool = True
    #: 外接矩形向外预留的栅格数（至少 5 格），保证绕飞有腾挪空间。
    bbox_pad_cells: int = 5

    # ---------- 冲突检测默认值 ----------
    default_lookahead_seconds: float = 60.0
    default_min_horizontal_separation_m: float = 50.0
    default_min_vertical_separation_m: float = 15.0
    #: 低电量提示阈值（百分比）。
    battery_warn_pct: float = 20.0
    #: 低电量警告阈值（百分比），比提示阈值更严重一档。
    battery_critical_pct: float = 10.0
    #: 冲突外推的时间步长（秒）。1 秒足够，越小越精确但越慢。
    extrapolation_step_s: float = 1.0

    # ---------- CORS ----------
    cors_allow_origins: list[str] = ["*"]


@lru_cache
def get_settings() -> Settings:
    """返回进程内唯一的配置实例（带缓存，避免重复解析环境变量）。"""
    return Settings()


#: 模块级快捷引用，业务代码可直接 ``from app.core.config import settings``
settings = get_settings()
