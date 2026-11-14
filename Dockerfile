# =============================================================================
#  sky-dispatch 生产镜像（Render 单 Web 服务）
#
#  构建产物同时包含：
#    - Vue 前端（frontend/dist → classpath:/static）
#    - Spring Boot 后端 + Flyway 迁移脚本
#  这样前端 /api、/ws 与页面同源，不需要额外 Nginx/CORS 配置。
#
#  构建上下文必须是仓库根目录（render.yaml 里 dockerContext: .）。
# =============================================================================

# ---------- 1. 前端构建 ----------
FROM node:20-alpine AS web
WORKDIR /web
# 先复制整个前端目录：postinstall 需要 scripts/fetch-china-geo.mjs，
# 且 public/geo 下的地图数据在生产构建时需要就位（缺失时前端会降级为模拟底图）
COPY frontend/ ./
RUN npm ci --no-audit --no-fund
RUN npm run build

# ---------- 2. 后端构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./pom.xml
RUN mvn -q -B -DskipTests dependency:go-offline
COPY backend/src ./src
# Flyway 脚本在仓库根目录 db/migration，postgres profile 从 classpath:db/migration 读取
COPY db/migration ./src/main/resources/db/migration
# 前端产物进入 Spring Boot 静态资源目录
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# ---------- 3. 运行时 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# 免费实例内存有限：堆上限按容器内存的 70% 计算，避免 OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"
COPY --from=build /build/target/*.jar /app/app.jar
EXPOSE 8080
# Render 通过 PORT 注入监听端口，必须显式传给 Spring Boot
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
