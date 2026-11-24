/**
 * 与 Java 后端一致的 TypeScript 类型。
 *
 * ⚠️ 两个必须注意的点：
 *
 *  1. 后端 application.yml 里配了 `spring.jackson.default-property-inclusion: non_null`，
 *     也就是**值为 null 的字段不会出现在 JSON 里**，而不是出现为 null。
 *     所以这里的可空字段一律写成可选（`?`），页面里用 `?? '—'` 兜底显示。
 *
 *  2. 后端返回的是**统一包装** `{ code, message, data, timestamp }`。
 *     本文件只描述 `data` 的形状；解包在 `client.ts` 里统一做掉，
 *     页面拿到的就是纯净的业务数据。
 */

/** 后端统一响应包装 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  timestamp: string
}

/** 分页响应（对应后端 CommonDtos.PageResponse） */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** 经纬度 + 地址 */
export interface GeoDto {
  lat: number
  lon: number
  address?: string
}

// ---------------------------------------------------------------------------
// 枚举（与后端 domain/enums 一一对应）
// ---------------------------------------------------------------------------

export type DroneStatus =
  | 'IDLE'
  | 'ASSIGNED'
  | 'IN_FLIGHT'
  | 'RETURNING'
  | 'CHARGING'
  | 'MAINTENANCE'
  | 'OFFLINE'

export type OrderStatus =
  | 'CREATED'
  | 'PLANNING'
  | 'QUEUED'
  | 'ASSIGNED'
  | 'IN_FLIGHT'
  | 'DELIVERED'
  | 'FAILED'
  | 'CANCELLED'

export type TaskStatus =
  | 'PENDING'
  | 'ASSIGNED'
  | 'TAKEOFF'
  | 'CRUISING'
  | 'DELIVERING'
  | 'RETURNING'
  /** 在充电站补能中：长航程任务里飞机会自主改航到站充电，充满继续未完成的那一段 */
  | 'CHARGING'
  | 'COMPLETED'
  | 'ABORTED'
  | 'FAILED'

export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type ZoneType = 'NO_FLY' | 'RESTRICTED' | 'CORRIDOR' | 'WARNING' | 'BUILDING'
export type ZoneShape = 'CIRCLE' | 'POLYGON'
export type ConflictType = 'DRONE_DRONE' | 'DRONE_ZONE' | 'DEADLINE_RISK' | 'BATTERY_RISK'
export type ConflictSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type ConflictStatus = 'OPEN' | 'RESOLVED' | 'IGNORED'
export type EventSeverity = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
export type RunStatus = 'CREATED' | 'RUNNING' | 'PAUSED' | 'STOPPED' | 'FINISHED'
export type DispatchStrategyType = 'NEAREST_AVAILABLE' | 'BATTERY_FIRST' | 'LOAD_BALANCE'

// ---------------------------------------------------------------------------
// 订单
// ---------------------------------------------------------------------------

export interface Order {
  id: number
  orderNo: string
  customerName: string
  customerPhone?: string
  cargoName: string
  weightKg: number
  priority: Priority
  status: OrderStatus
  pickup: GeoDto
  dropoff: GeoDto
  expectDeliveryAt?: string
  assignedDroneId?: number
  failReason?: string
  remark?: string
  createdAt?: string
  updatedAt?: string
}

export interface OrderCreateRequest {
  customerName: string
  customerPhone?: string
  cargoName: string
  weightKg: number
  priority?: Priority
  pickup: GeoDto
  dropoff: GeoDto
  expectDeliveryAt?: string
  remark?: string
}

/**
 * 订单链路追踪：一次请求拿到「订单 + 任务 + 事件 + 派单决策」的同一时刻快照。
 *
 * ⚠️ 不要拆成多个请求：链路面板同时展示时间线、任务与决策，
 * 分开请求会出现"时间线和任务卡不是同一时刻"的错位。
 */
export interface OrderTrace {
  order: Order
  /** 该订单的全部执行任务，最新一次在前（重派会有多条） */
  tasks: DispatchTask[]
  /** 事件时间线，按时间升序 */
  events: EventLogItem[]
  /** 历次派单的候选评估，按名次升序 */
  decisions: DispatchDecision[]
}

// ---------------------------------------------------------------------------
// 无人机
// ---------------------------------------------------------------------------

export interface Drone {
  id: number
  code: string
  modelCode: string
  modelName: string
  maxPayloadKg: number
  cruiseSpeedMps: number
  maxFlightMin: number
  batteryCapacityWh: number
  energyPerKmWh: number
  status: DroneStatus
  batteryPct: number
  currentLat?: number
  currentLon?: number
  altitudeM?: number
  speedMps?: number
  headingDeg?: number
  homeLat?: number
  homeLon?: number
  currentTaskId?: number
  totalFlightMin?: number
  totalDistanceKm?: number
  totalTasks?: number
  lastHeartbeatAt?: string
}

export interface DroneCreateRequest {
  /** 机身编号，可留空 —— 留空时后端自动生成（SKY-DR-0001 起顺延） */
  code?: string
  modelCode: string
  batteryPct?: number
  home: GeoDto
}

// ---------------------------------------------------------------------------
// 空域
// ---------------------------------------------------------------------------

export interface AirspaceZone {
  id: number
  code: string
  name: string
  zoneType: ZoneType
  shapeType: ZoneShape
  center?: GeoDto
  radiusM?: number
  points?: GeoDto[]
  minAltitudeM?: number
  maxAltitudeM?: number
  severity?: ConflictSeverity
  enabled?: boolean
  effectiveFrom?: string
  effectiveTo?: string
  remark?: string
}

/**
 * 充电站：无人机中途补能的落脚点。
 *
 * 长航程订单光靠机巢是飞不完的，飞机可以"飞一段、补一段"，
 * 这个类型就是地图上要画出来的那些可落站点。
 */
export interface ChargingStation {
  id: number
  code: string
  name: string
  /** 所在城市（看板分组用） */
  city: string
  lat: number
  lon: number
  /** 可同时补能的桩位数 */
  chargeSlots: number
  enabled?: boolean
  remark?: string
}

export interface ZoneCreateRequest {
  /** 区域编码，可留空 —— 留空时按区域类型自动生成（NFZ-0003 等） */
  code?: string
  /** 区域名称，可留空 —— 留空时用「类型中文名 + 编号」自动拼一个 */
  name?: string
  zoneType: ZoneType
  shapeType: ZoneShape
  center?: GeoDto
  radiusM?: number
  points?: GeoDto[]
  minAltitudeM?: number
  maxAltitudeM?: number
  /** 严重度，可留空 —— 留空时按区域类型取默认值（禁飞区最严，走廊最松） */
  severity?: ConflictSeverity
  enabled?: boolean
  remark?: string
}

// ---------------------------------------------------------------------------
// 任务与航路
// ---------------------------------------------------------------------------

export interface DispatchTask {
  id: number
  taskNo: string
  orderId: number
  orderNo?: string
  droneId: number
  droneCode?: string
  routeId?: number
  strategy: string
  status: TaskStatus
  progressPct?: number
  currentWaypointSeq?: number
  plannedDistanceM?: number
  actualDistanceM?: number
  plannedEnergyWh?: number
  consumedEnergyWh?: number
  batteryStartPct?: number
  batteryEndPct?: number
  assignedAt?: string
  startedAt?: string
  finishedAt?: string
  durationS?: number
  failReason?: string
}

export interface TaskStatusStats {
  /** 任务总数 */
  total: number
  /** 占用无人机的活动任务数 */
  active: number
  /** 各状态任务数，key 为 TaskStatus 枚举名（后端补齐全部状态，缺失为 0） */
  byStatus: Record<string, number>
}

export interface Waypoint {
  seq: number
  lat: number
  lon: number
  altitudeM: number
  cumulativeDistanceM: number
  etaSeconds?: number
}

export interface EventLogItem {
  id: number
  taskId?: number
  orderId?: number
  droneId?: number
  eventType: string
  severity: EventSeverity
  message: string
  payload?: string
  simTimeS?: number
  ts: string
}

export interface DispatchDecision {
  id: number
  orderId: number
  droneId: number
  droneCode?: string
  strategy: string
  score?: number
  rankNo?: number
  selected?: boolean
  rejectedReason?: string
  detail?: string
  createdAt?: string
}

export interface DispatchResult {
  task?: DispatchTask
  strategy: string
  routePlanned: boolean
  planElapsedMs: number
  message: string
}

/** 派单前「搜罗可用无人机」预览：只返回通过硬条件的入围候选，不含被淘汰的。 */
export interface CandidatePreview {
  orderId: number
  orderNo: string
  strategy: string
  /** 本次共评估的无人机总数（含被淘汰的） */
  evaluatedTotal: number
  qualifiedCount: number
  rejectedCount: number
  /** 入围者中无需补能、直飞即可完成的架数 */
  directCount: number
  /** 入围者中必须中途补能才能完成的架数 */
  relayCount: number
  qualified: QualifiedDrone[]
}

/** 通过全部硬条件（载重 / 可达性 / 半径 / 空闲）的入围候选 */
export interface QualifiedDrone {
  droneId: number
  droneCode: string
  modelCode: string
  modelName: string
  maxPayloadKg: number
  /** 当前位置到取货点的距离（米） */
  distanceToPickupM: number
  batteryPct: number
  /** 扣除安全冗余后的可用电量 */
  usableEnergyWh: number
  /** 完整任务（含补能绕行）的实际飞行能耗；直飞时即调机 + 去程载货 + 回程 */
  missionEnergyWh: number
  /** 中途补能次数；0 表示直飞可达 */
  chargingStops: number
  /** 可达性结论（一句话），可直接展示 */
  reachability: string
  /** 途经补能站名称，按飞行顺序；空表示直飞 */
  chargingStopNames: string[]
  activeTaskCount: number
  score: number
  rank: number
}

// ---------------------------------------------------------------------------
// 看板
// ---------------------------------------------------------------------------

export interface Kpi {
  totalOrders: number
  pendingOrders: number
  inFlightOrders: number
  deliveredOrders: number
  failedOrders: number
  totalTasks: number
  completedTasks: number
  activeDrones: number
  totalDrones: number
  avgBatteryPct: number
  avgDeliverySeconds?: number
  onTimeRate?: number
  openConflicts: number
  simTimeS: number
  tickCount: number
  runStatus: string
  onlineClients: number
}

/** 单架无人机的一帧遥测（与 WebSocket 推送的字段一致） */
export interface DroneFrame {
  droneId: number
  droneCode: string
  /** 机型代码（SKY-X4 / SKY-M8 / SKY-H2）：前端按型号画不同的机身外形 */
  modelCode?: string
  taskId?: number
  orderNo?: string
  status: DroneStatus
  lat?: number
  lon?: number
  altitudeM?: number
  speedMps?: number
  headingDeg?: number
  batteryPct?: number
  progressPct?: number
  waypointSeq?: number
  ts: string
}

export interface ConflictView {
  id: number
  conflictType: ConflictType
  severity: ConflictSeverity
  status: ConflictStatus
  droneAId?: number
  droneBId?: number
  zoneId?: number
  horizontalDistanceM?: number
  verticalDistanceM?: number
  closingSpeedMps?: number
  timeToConflictS?: number
  lat?: number
  lon?: number
  detectedAt?: string
  message?: string
}

/** 地图用的订单地理标记（取货点 / 送货目的地） */
export interface OrderPin {
  orderId: number
  orderNo: string
  status: OrderStatus
  weightKg: number
  pickupLat: number
  pickupLon: number
  dropoffLat: number
  dropoffLon: number
  assignedDroneId?: number | null
}

/**
 * 当前风场读数（HUD 展示用）。
 *
 * 后端模型是确定性的（由仿真时间推导），所以这里只传瞬时风速与来向，
 * 前端不需要自己算风，避免两套物理模型对不上。
 */
export interface WindView {
  enabled: boolean
  /** 瞬时风速（含阵风，米/秒） */
  speedMps: number
  /** 风的来向（度，正北 0、顺时针） */
  directionDeg: number
}

export interface Snapshot {
  simTimeS: number
  runStatus: string
  tickCount: number
  drones: DroneFrame[]
  kpi: Kpi
  openConflicts: ConflictView[]
  /** 未完结订单 + 最近已送达订单的地理标记（老后端可能不返回，做缺省兜底） */
  orders?: OrderPin[]
  /** 当前风场（老后端不返回，做缺省兜底） */
  wind?: WindView
}

// ---------------------------------------------------------------------------
// 仿真
// ---------------------------------------------------------------------------

export interface SimulationRun {
  id: number
  name: string
  status: RunStatus
  tickMs: number
  speedFactor: number
  simTimeS: number
  tickCount: number
  startedAt?: string
  endedAt?: string
}

export interface TickResult {
  stepIndex: number
  simTimeS: number
  movedDrones: number
  completedTasks: number
  newConflicts: number
  message: string
}

/**
 * 仿真运行时状态。
 *
 * `engineActive` 对应界面上的「暂停 / 继续」：false 表示定时推进已停，
 * 但手动「步进一拍」仍然可用（逐拍观察状态机）。
 * `autoDispatchEnabled` 对应「自动派单」开关：关掉后派单完全由人控制。
 */
export interface SimulationRuntimeState {
  engineActive: boolean
  autoDispatchEnabled: boolean
  tickMs: number
  speedFactor: number
  runId?: number
  runStatus?: string
  simTimeS?: number
  tickCount?: number
}

// ---------------------------------------------------------------------------
// WebSocket 消息
// ---------------------------------------------------------------------------

export type WsMessageType = 'SNAPSHOT' | 'TELEMETRY' | 'EVENT' | 'CONFLICT' | 'KPI' | 'PONG'

export interface TelemetryPayload {
  simTimeS: number
  tickCount: number
  drones: DroneFrame[]
  /** 当前风场（随遥测一起推送，老后端不返回时做缺省兜底） */
  wind?: WindView
}

/** 领域事件（后端 event/SkyEvent 的 JSON 形态） */
export interface SkyEvent {
  eventType: string
  severity: EventSeverity
  message: string
  runId?: number
  taskId?: number
  orderId?: number
  droneId?: number
  simTimeS?: number
  payload?: string
  ts: string
}

export interface WsMessage {
  type: WsMessageType
  ts: string
  payload: Snapshot | TelemetryPayload | SkyEvent | ConflictView | Kpi | null
}

/** 算法服务健康状态 */
export interface AlgorithmHealth {
  baseUrl: string
  /** 后端是否启用算法服务（false = 离线降级模式，走直线航路，仍可正常派单） */
  enabled: boolean
  available: boolean
  detail?: { status: string; service: string; version: string; python: string } | null
}
