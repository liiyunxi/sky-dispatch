#!/usr/bin/env node
/**
 * 前端 API 契约检查（OpenAPI → TS 防漂移）。
 *
 * <p>背景：前端的 `src/api/types.ts` 是手写的。后端加字段时前端不会报错，
 * 只有等到某个页面真的用到它才发现"类型里没有 / 字段名拼错"。
 * 完全改成 OpenAPI 生成 TS 会带来一次大范围重构，收益与风险不成比例；
 * 这里取一个折中：**只校验前端真正依赖的关键字段与关键路径**。
 *
 * <p>用法：
 *   node scripts/check-api-contract.mjs
 *   npm run check:api
 *
 * <p>环境变量：
 *   API_BASE  后端地址，默认 http://localhost:8080
 *
 * <p>退出码：0 = 契约一致；1 = 有缺失（输出会指出是哪个 schema/路径）。
 */

const base = (process.env.API_BASE ?? 'http://localhost:8080').replace(/\/+$/, '')
const docUrl = `${base}/v3/api-docs`

/** 前端页面直接调用、不能悄悄消失的关键路径 */
const REQUIRED_PATHS = [
  { path: '/api/v1/dashboard/kpi', method: 'get' },
  { path: '/api/v1/dashboard/drones', method: 'get' },
  { path: '/api/v1/dashboard/snapshot', method: 'get' },
  { path: '/api/v1/orders', method: 'get' },
  { path: '/api/v1/orders/{id}/trace', method: 'get' },
  { path: '/api/v1/tasks', method: 'get' },
  { path: '/api/v1/drones', method: 'get' },
  { path: '/api/v1/airspace/zones', method: 'get' },
  { path: '/api/v1/airspace/zones', method: 'post' },
  { path: '/api/v1/airspace/zones/{id}', method: 'put' },
  { path: '/api/v1/airspace/zones/{id}', method: 'delete' },
  { path: '/api/v1/stations', method: 'get' },
  { path: '/api/v1/infrastructure', method: 'get' },
  { path: '/api/v1/simulation/perf', method: 'get' },
]

/**
 * 关键 schema 校验。
 *
 * <p>不按 schema 名匹配，而按"特征字段组合"识别：springdoc 对多个 `Response`
 * record 会重名/加后缀，按名字查非常脆；按字段特征查（如无人机帧必然有
 * droneId + droneCode + batteryPct）则与命名无关。
 */
const SCHEMA_CHECKS = [
  {
    name: '遥测帧 DroneFrame',
    signature: (has) => has('droneId') && has('droneCode') && has('batteryPct') && has('altitudeM'),
    signatureText: 'droneId + droneCode + batteryPct + altitudeM',
    // 遥测质量标记：前端弹窗按这些字段判断"失联 / 丢包 / 漂移"
    required: ['lat', 'lon', 'commLost', 'stale', 'gpsDrift', 'observedAt', 'delayed'],
  },
  {
    name: '订单 Order',
    signature: (has) => has('orderNo') && has('cargoName') && has('expectDeliveryAt'),
    signatureText: 'orderNo + cargoName + expectDeliveryAt',
    // SLA 时序图与准时率口径依赖 deliveredAt
    required: ['deliveredAt', 'pickup', 'dropoff', 'status'],
  },
  {
    name: '任务 DispatchTask',
    signature: (has) => has('taskNo') && has('plannedDistanceM') && has('progressPct'),
    signatureText: 'taskNo + plannedDistanceM + progressPct',
    // 地面等待（战略时隙/机巢周转）与能耗口径
    required: ['holdUntilS', 'holdReason', 'plannedEnergyWh', 'consumedEnergyWh', 'durationS'],
  },
  {
    name: '空域 AirspaceZone',
    signature: (has) => has('zoneType') && has('shapeType') && has('maxAltitudeM'),
    signatureText: 'zoneType + shapeType + maxAltitudeM',
    required: ['code', 'activeFromSimSec', 'activeToSimSec', 'enabled', 'points'],
  },
  {
    name: '充电站 ChargingStation',
    signature: (has) => has('chargeSlots') && has('city') && has('queueLength'),
    signatureText: 'chargeSlots + city + queueLength',
    required: ['occupancy', 'queueLength', 'lat', 'lon'],
  },
  {
    name: '航路点 Waypoint',
    signature: (has) => has('seq') && has('cumulativeDistanceM') && has('altitudeM'),
    signatureText: 'seq + cumulativeDistanceM + altitudeM',
    required: ['etaSeconds', 'lat', 'lon'],
  },
]

let doc
try {
  const response = await fetch(docUrl)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  doc = await response.json()
} catch (error) {
  console.error(`[契约检查] 无法获取 OpenAPI 文档：${docUrl}`)
  console.error(`             原因：${error instanceof Error ? error.message : String(error)}`)
  console.error('             请先启动后端（8080），或用 API_BASE 指定地址。')
  process.exit(1)
}

const failures = []

// ---- 1. 路径检查 ----
for (const item of REQUIRED_PATHS) {
  if (!doc.paths?.[item.path]?.[item.method]) {
    failures.push(`缺少接口：${item.method.toUpperCase()} ${item.path}`)
  }
}

// ---- 2. schema 字段检查 ----
const schemas = doc.components?.schemas ?? {}
for (const check of SCHEMA_CHECKS) {
  const matched = Object.entries(schemas).filter(([, schema]) => {
    const properties = schema?.properties ?? {}
    return check.signature((field) => field in properties)
  })
  if (matched.length === 0) {
    failures.push(`${check.name}：未找到含特征字段（${check.signatureText}）的 schema`)
    continue
  }
  for (const [schemaName, schema] of matched) {
    const fields = Object.keys(schema?.properties ?? {})
    const missing = check.required.filter((field) => !fields.includes(field))
    if (missing.length > 0) {
      failures.push(`${check.name}（${schemaName}）缺少字段：${missing.join(', ')}`)
    }
  }
}

if (failures.length > 0) {
  console.error(`[契约检查] 发现 ${failures.length} 处前后端契约漂移：`)
  failures.forEach((message) => console.error(`  - ${message}`))
  console.error('\n如果这是有意的后端变更，请同步更新 frontend/src/api/types.ts 与本脚本。')
  process.exit(1)
}

console.log(
  `[契约检查] 通过：${REQUIRED_PATHS.length} 条关键路径、${SCHEMA_CHECKS.length} 组关键 schema 与前端类型一致（${docUrl}）`,
)
