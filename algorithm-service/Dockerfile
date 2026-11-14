# =============================================================================
#  Python 算法服务镜像（Render Web 服务）
#
#  - 使用 uv.lock 锁定依赖，构建可复现
#  - 不安装 dev 依赖（pytest/httpx）
#  - 监听 Render 注入的 PORT（默认 8000）
# =============================================================================

FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev

COPY app ./app
ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
