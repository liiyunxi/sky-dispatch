"""HTTP 接口端到端测试（FastAPI TestClient），同时校验契约字段名。

覆盖：``GET /``、``GET /health``、``POST /api/v1/path/plan``、``POST /api/v1/conflict/check``、
CORS 响应头、参数校验 422。
"""

from __future__ import annotations

import math

from fastapi.testclient import TestClient

from tests.conftest import SAMPLE_PLAN_BODY

M_PER_DEG_LON = 95_196.0  # 纬度 31.23 附近，1 度经度 ≈ 95196m


# --------------------------------------------------------------------------- #
# GET / 与 GET /health
# --------------------------------------------------------------------------- #
def test_index_lists_endpoints(client: TestClient) -> None:
    """根路径返回服务简介与接口清单。"""
    response = client.get("/")
    assert response.status_code == 200

    body = response.json()
    assert body["service"] == "sky-dispatch-algorithm"
    assert body["version"] == "0.1.0"

    paths = {item["path"] for item in body["endpoints"]}
    assert {"/health", "/api/v1/path/plan", "/api/v1/conflict/check"} <= paths


def test_health_contract_fields(client: TestClient) -> None:
    """健康检查字段与契约 4.1 逐字一致。"""
    response = client.get("/health")
    assert response.status_code == 200

    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "sky-dispatch-algorithm"
    assert body["version"] == "0.1.0"
    # python 字段应是 "3.13.x" 这类真实版本号
    assert body["python"].count(".") == 2
    assert set(body.keys()) == {"status", "service", "version", "python"}


# --------------------------------------------------------------------------- #
# POST /api/v1/path/plan
# --------------------------------------------------------------------------- #
def test_path_plan_success_and_contract_fields(client: TestClient) -> None:
    """契约示例请求：应返回 found=true 且字段名完全符合契约 4.2。"""
    response = client.post("/api/v1/path/plan", json=SAMPLE_PLAN_BODY)
    assert response.status_code == 200

    body = response.json()
    assert set(body.keys()) == {
        "success",
        "requestId",
        "algorithm",
        "found",
        "message",
        "distanceM",
        "waypoints",
        "expandedNodes",
        "elapsedMs",
    }
    assert body["success"] is True
    assert body["found"] is True
    assert body["requestId"] == "test-request-0001"  # 回显
    assert body["algorithm"] == "ASTAR"
    assert body["message"] == "ok"
    assert body["distanceM"] > 0
    assert body["expandedNodes"] > 0
    assert body["elapsedMs"] >= 0

    waypoints = body["waypoints"]
    assert len(waypoints) >= 2
    assert set(waypoints[0].keys()) == {"seq", "lat", "lon", "altitudeM", "cumulativeDistanceM"}
    assert waypoints[0]["seq"] == 0
    assert waypoints[0]["cumulativeDistanceM"] == 0.0
    assert waypoints[-1]["cumulativeDistanceM"] == body["distanceM"]
    assert [wp["seq"] for wp in waypoints] == list(range(len(waypoints)))
    assert all(wp["altitudeM"] == 80.0 for wp in waypoints)

    # 首尾必须是原始起终点
    assert waypoints[0]["lat"] == SAMPLE_PLAN_BODY["start"]["lat"]
    assert waypoints[0]["lon"] == SAMPLE_PLAN_BODY["start"]["lon"]
    assert waypoints[-1]["lat"] == SAMPLE_PLAN_BODY["goal"]["lat"]
    assert waypoints[-1]["lon"] == SAMPLE_PLAN_BODY["goal"]["lon"]


def test_path_plan_generates_request_id_when_absent(client: TestClient) -> None:
    """未传 requestId 时服务端生成 uuid4 并回显。"""
    payload = dict(SAMPLE_PLAN_BODY)
    payload.pop("requestId")

    body = client.post("/api/v1/path/plan", json=payload).json()
    assert isinstance(body["requestId"], str)
    assert len(body["requestId"]) == 36  # uuid4 标准长度
    assert body["requestId"].count("-") == 4


def test_path_plan_failure_still_http_200(client: TestClient) -> None:
    """起点落在禁飞区内 → HTTP 200 + found=false + 原因码，绝不 5xx。"""
    payload = {
        "start": {"lat": 31.2300, "lon": 121.4700},
        "goal": {"lat": 31.2300, "lon": 121.4900},
        "altitudeM": 80.0,
        "obstacles": [
            {
                "id": "Z_IN",
                "shape": "CIRCLE",
                "center": {"lat": 31.2300, "lon": 121.4700},
                "radiusM": 300.0,
                "minAltitudeM": 0.0,
                "maxAltitudeM": 120.0,
            }
        ],
    }
    response = client.post("/api/v1/path/plan", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["found"] is False
    assert body["message"] == "start_inside_obstacle"
    assert body["waypoints"] == []
    assert body["distanceM"] == 0.0


def test_path_plan_no_path_returns_200(client: TestClient, ring_zones) -> None:
    """被围死 → HTTP 200 + found=false + no_path_found（算法失败不报 5xx）。"""
    payload = {
        "start": {"lat": 31.2300, "lon": 121.4700},
        "goal": {"lat": 31.2300, "lon": 121.4900},
        "altitudeM": 80.0,
        "gridSizeM": 50.0,
        "obstacles": [zone.model_dump(mode="json") for zone in ring_zones],
    }
    response = client.post("/api/v1/path/plan", json=payload)

    assert response.status_code == 200
    assert response.json()["message"] == "no_path_found"


def test_path_plan_validation_error_422(client: TestClient) -> None:
    """缺少必填字段 goal → 422 参数校验失败。"""
    response = client.post("/api/v1/path/plan", json={"start": {"lat": 31.23, "lon": 121.47}})

    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert "参数校验失败" in body["message"]
    assert isinstance(body["detail"], list)


def test_path_plan_invalid_latitude_422(client: TestClient) -> None:
    """纬度超出 [-90, 90] → 422。"""
    response = client.post(
        "/api/v1/path/plan",
        json={"start": {"lat": 999.0, "lon": 121.47}, "goal": {"lat": 31.24, "lon": 121.49}},
    )
    assert response.status_code == 422


def test_path_plan_invalid_circle_obstacle_422(client: TestClient) -> None:
    """shape=CIRCLE 但缺 radiusM → 422（模型校验器给出中文原因）。"""
    response = client.post(
        "/api/v1/path/plan",
        json={
            "start": {"lat": 31.2300, "lon": 121.4700},
            "goal": {"lat": 31.2300, "lon": 121.4900},
            "obstacles": [{"id": "Z", "shape": "CIRCLE", "center": {"lat": 31.23, "lon": 121.48}}],
        },
    )
    assert response.status_code == 422


# --------------------------------------------------------------------------- #
# POST /api/v1/conflict/check
# --------------------------------------------------------------------------- #
def _drone_payload(drone_id: int, lat: float, lon: float, heading: float, battery: float = 90.0) -> dict:
    """构造契约 4.3 里的 drones 元素。"""
    return {
        "droneId": drone_id,
        "droneCode": f"UAV-{drone_id:03d}",
        "lat": lat,
        "lon": lon,
        "altitudeM": 80.0,
        "speedMps": 12.0,
        "headingDeg": heading,
        "batteryPct": battery,
    }


def test_conflict_check_detects_drone_drone(client: TestClient) -> None:
    """两机相向飞行 → 命中 DRONE_DRONE，字段名与契约 4.3 逐字一致。"""
    payload = {
        "checkTime": "2026-01-01T10:00:00Z",
        "lookaheadSeconds": 60.0,
        "minHorizontalSeparationM": 50.0,
        "minVerticalSeparationM": 15.0,
        "drones": [
            _drone_payload(3, 31.2345, 121.4801, heading=90.0),
            _drone_payload(5, 31.2345, 121.4801 + 1000.0 / M_PER_DEG_LON, heading=270.0),
        ],
        "zones": [],
    }
    response = client.post("/api/v1/conflict/check", json=payload)
    assert response.status_code == 200

    body = response.json()
    assert set(body.keys()) == {"success", "requestId", "conflictCount", "conflicts", "message", "elapsedMs"}
    assert body["success"] is True
    assert body["conflictCount"] == 1
    assert body["message"] == "ok"

    conflict = body["conflicts"][0]
    assert set(conflict.keys()) == {
        "conflictType",
        "severity",
        "droneAId",
        "droneBId",
        "zoneId",
        "horizontalDistanceM",
        "verticalDistanceM",
        "closingSpeedMps",
        "timeToConflictS",
        "lat",
        "lon",
        "message",
    }
    assert conflict["conflictType"] == "DRONE_DRONE"
    assert conflict["droneAId"] == 3 and conflict["droneBId"] == 5
    assert conflict["zoneId"] is None
    # 两机同高度，垂直间距 0，水平最小间距接近 0 → CRITICAL
    assert conflict["verticalDistanceM"] == 0.0
    assert conflict["severity"] == "CRITICAL"
    assert conflict["closingSpeedMps"] > 0
    assert conflict["message"]  # 中文描述非空


def test_conflict_check_detects_zone_and_battery(client: TestClient) -> None:
    """机-区（NO_FLY → CRITICAL）与低电量（<20% → LOW）都能被检出。"""
    payload = {
        "lookaheadSeconds": 60.0,
        "minHorizontalSeparationM": 50.0,
        "minVerticalSeparationM": 15.0,
        "drones": [_drone_payload(7, 31.2300, 121.4700, heading=90.0, battery=15.0)],
        "zones": [
            {
                "id": "Z001",
                "name": "禁飞区-虹桥",
                "shape": "CIRCLE",
                "center": {"lat": 31.2300, "lon": 121.4700 + 300.0 / M_PER_DEG_LON},
                "radiusM": 200.0,
                "minAltitudeM": 0.0,
                "maxAltitudeM": 120.0,
                "zoneType": "NO_FLY",
            }
        ],
    }
    body = client.post("/api/v1/conflict/check", json=payload).json()

    assert body["conflictCount"] == 2
    types = {c["conflictType"] for c in body["conflicts"]}
    assert types == {"DRONE_ZONE", "BATTERY_RISK"}

    zone_conflict = next(c for c in body["conflicts"] if c["conflictType"] == "DRONE_ZONE")
    assert zone_conflict["severity"] == "CRITICAL"
    assert zone_conflict["zoneId"] == "Z001"
    assert zone_conflict["droneAId"] == 7
    assert zone_conflict["timeToConflictS"] is not None
    assert math.isclose(zone_conflict["timeToConflictS"], 100.0 / 12.0, abs_tol=1.0)

    battery_conflict = next(c for c in body["conflicts"] if c["conflictType"] == "BATTERY_RISK")
    assert battery_conflict["severity"] == "LOW"


def test_conflict_check_no_conflict_returns_empty(client: TestClient) -> None:
    """无任何冲突时 conflicts 为空数组、conflictCount 为 0。"""
    payload = {
        "lookaheadSeconds": 60.0,
        "minHorizontalSeparationM": 50.0,
        "minVerticalSeparationM": 15.0,
        "drones": [
            _drone_payload(1, 31.2300, 121.4700, heading=0.0, battery=95.0),
            _drone_payload(2, 31.2600, 121.5300, heading=180.0, battery=88.0),
        ],
        "zones": [],
    }
    body = client.post("/api/v1/conflict/check", json=payload).json()

    assert body["success"] is True
    assert body["conflictCount"] == 0
    assert body["conflicts"] == []


def test_conflict_check_generates_request_id(client: TestClient) -> None:
    """未传 requestId → 服务端生成 uuid4。"""
    body = client.post(
        "/api/v1/conflict/check",
        json={"drones": [], "zones": []},
    ).json()

    assert len(body["requestId"]) == 36
    assert body["conflictCount"] == 0


def test_conflict_check_validation_error_422(client: TestClient) -> None:
    """drones 元素缺 droneId → 422。"""
    response = client.post(
        "/api/v1/conflict/check",
        json={"drones": [{"lat": 31.23, "lon": 121.47}], "zones": []},
    )
    assert response.status_code == 422


# --------------------------------------------------------------------------- #
# CORS
# --------------------------------------------------------------------------- #
def test_cors_preflight_allows_any_origin(client: TestClient) -> None:
    """CORS 预检请求应放行任意来源（方便前端调试页面直接调用）。"""
    response = client.options(
        "/api/v1/path/plan",
        headers={
            "Origin": "http://localhost:5173",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "*"
