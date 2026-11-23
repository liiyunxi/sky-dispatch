import { computed, ref } from 'vue'
import { getToken } from '@/api/token'
import {
  airspaceApi,
  stationApi,
  dashboardApi,
  simulationApi,
  taskApi,
  type AirspaceZone,
  type ChargingStation,
  type ConflictView,
  type DroneFrame,
  type Kpi,
  type OrderPin,
  type RunStatus,
  type SimulationRun,
  type SimulationRuntimeState,
  type SkyEvent,
  type TaskStatus,
  type TickResult,
  type WsMessage,
} from '@/api'

/**
 * 全局实时状态（一个模块级单例，所有页面共享同一份数据）。
 *
 * 为什么不用 Pinia？因为这里的"状态"本质上是**一份服务端推送的镜像**：
 * 没有复杂的 action 编排、没有本地持久化需求，模块级 `ref` + 几个函数就够了，
 * 少一个依赖、少一层心智负担。等状态真正复杂起来（多标签页、离线队列、乐观更新）
 * 再迁到 Pinia，迁移成本也很低——调用方用的都是同一组 API。
 *
 * 三条数据通道，优先级从高到低：
 *  1. WebSocket 实时推送（TELEMETRY / EVENT / CONFLICT / KPI）
 *  2. WebSocket 首帧 SNAPSHOT（连接建立时后端主动推的全量快照）
 *  3. REST 轮询兜底（WebSocket 连不上时自动开启，保证页面不会"死"在那里）
 */

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

/** 核心指标 */
const kpi = ref<Kpi | null>(null)
/** 全部无人机的最新一帧遥测 */
const drones = ref<DroneFrame[]>([])
/** 未处理冲突（最多保留 50 条） */
const conflicts = ref<ConflictView[]>([])
/** 事件流（最多保留 120 条，最新的在最前） */
const events = ref<SkyEvent[]>([])
/** 空域（不常变，按需加载） */
const zones = ref<AirspaceZone[]>([])
/** 充电站（不常变，按需加载）：地图上标出"飞机中途能落哪儿补能" */
const stations = ref<ChargingStation[]>([])
/** 在飞航路，用于地图画线：{ taskId, label, status, points } */
const routes = ref<Array<{ taskId: number; label: string; status: TaskStatus; points: [number, number][] }>>([])
/** 订单地理标记：取货点 + 送货目的地（含待派 / 在飞 / 最近已送达） */
const orderPins = ref<OrderPin[]>([])
/** 当前仿真运行 */
const currentRun = ref<SimulationRun | null>(null)
/** 算法服务是否可用（null = 还没探测过） */
const algorithmAvailable = ref<boolean | null>(null)
/** 后端是否启用算法服务（null = 还没探测过；false 表示离线降级模式，派单仍可用） */
const algorithmEnabled = ref<boolean | null>(null)

const connected = ref(false)
const wsState = ref<'closed' | 'connecting' | 'open'>('closed')
const usingPolling = ref(false)
const lastMessageAt = ref<string | null>(null)
const simTimeS = ref(0)
const tickCount = ref(0)
const runStatus = ref<RunStatus | 'STOPPED'>('STOPPED')

/** 供页面显示"数据是否新鲜" */
export const dataFreshness = computed(() => lastMessageAt.value)

// ---------------------------------------------------------------------------
// WebSocket
// ---------------------------------------------------------------------------

const MAX_EVENTS = 120
const MAX_CONFLICTS = 50
/** 同时画太多航路会拖慢地图，超过就只画前 N 条 */
const MAX_ROUTES_ON_MAP = 12

let socket: WebSocket | null = null
let reconnectAttempts = 0
let reconnectTimer: number | null = null
let pollTimer: number | null = null

/**
 * WebSocket 地址用当前页面的 host 拼，而不是写死 localhost:8080。
 *
 * 好处：开发时走 Vite 的 /ws 代理（见 vite.config.ts），生产走 Nginx 的 /ws 转发，
 * 前端代码一行都不用改，也不会出现"部署到服务器后连的是用户自己的 localhost"这种经典问题。
 */
function buildWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // 浏览器 WebSocket 不能自定义请求头，鉴权 token 通过查询参数传给握手拦截器
  const token = getToken()
  const query = token ? `?token=${encodeURIComponent(token)}` : ''
  return `${protocol}//${window.location.host}/ws/telemetry${query}`
}

function handleMessage(raw: string): void {
  let msg: WsMessage
  try {
    msg = JSON.parse(raw) as WsMessage
  } catch {
    return
  }
  lastMessageAt.value = new Date().toISOString()

  switch (msg.type) {
    case 'SNAPSHOT': {
      const payload = msg.payload as {
        simTimeS: number
        runStatus: string
        tickCount: number
        drones: DroneFrame[]
        kpi: Kpi
        openConflicts: ConflictView[]
        orders?: OrderPin[]
      }
      drones.value = payload.drones ?? []
      kpi.value = payload.kpi ?? null
      conflicts.value = (payload.openConflicts ?? []).slice(0, MAX_CONFLICTS)
      orderPins.value = payload.orders ?? []
      simTimeS.value = payload.simTimeS ?? 0
      tickCount.value = payload.tickCount ?? 0
      runStatus.value = (payload.runStatus as RunStatus) ?? 'STOPPED'
      break
    }
    case 'TELEMETRY': {
      const payload = msg.payload as { simTimeS: number; tickCount: number; drones: DroneFrame[] }
      drones.value = payload.drones ?? []
      simTimeS.value = payload.simTimeS ?? 0
      tickCount.value = payload.tickCount ?? 0
      break
    }
    case 'KPI': {
      kpi.value = msg.payload as Kpi
      break
    }
    case 'CONFLICT': {
      const conflict = msg.payload as ConflictView
      if (!conflict || conflicts.value.some((item) => item.id === conflict.id)) return
      conflicts.value = [conflict, ...conflicts.value].slice(0, MAX_CONFLICTS)
      break
    }
    case 'EVENT': {
      const event = msg.payload as SkyEvent
      if (!event) return
      events.value = [event, ...events.value].slice(0, MAX_EVENTS)
      break
    }
    default:
      // PONG 等心跳消息不需要处理
      break
  }
}

function scheduleReconnect(): void {
  if (reconnectTimer !== null) return
  // 指数退避，上限 15 秒：既不会把后端打爆，也不会让用户等太久
  const delay = Math.min(1000 * 2 ** reconnectAttempts, 15_000)
  reconnectAttempts += 1
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connect()
  }, delay)

  // 连续失败 3 次就认为 WebSocket 走不通（比如被代理拦了），改用 REST 轮询兜底
  if (reconnectAttempts >= 3) {
    startPolling()
  }
}

/** 建立连接（幂等：已连接或正在连接时直接返回） */
export function connect(): void {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return
  }
  wsState.value = 'connecting'
  try {
    socket = new WebSocket(buildWsUrl())
  } catch {
    wsState.value = 'closed'
    scheduleReconnect()
    return
  }

  socket.onopen = () => {
    wsState.value = 'open'
    connected.value = true
    reconnectAttempts = 0
    stopPolling()
  }
  socket.onmessage = (event) => handleMessage(String(event.data))
  socket.onerror = () => {
    // 出错后浏览器一定会触发 onclose，重连逻辑统一放在那里，避免重复排程
  }
  socket.onclose = () => {
    wsState.value = 'closed'
    connected.value = false
    socket = null
    scheduleReconnect()
  }
}

export function disconnect(): void {
  if (reconnectTimer !== null) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  stopPolling()
  if (socket) {
    // 主动关闭前把 onclose 摘掉，否则会触发一次多余的重连
    socket.onclose = null
    socket.close()
    socket = null
  }
  connected.value = false
  wsState.value = 'closed'
}

// ---------------------------------------------------------------------------
// REST 兜底轮询
// ---------------------------------------------------------------------------

function startPolling(): void {
  if (pollTimer !== null) return
  usingPolling.value = true
  pollTimer = window.setInterval(() => {
    void refreshSnapshot()
  }, 3000)
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  usingPolling.value = false
}

// ---------------------------------------------------------------------------
// REST 数据加载
// ---------------------------------------------------------------------------

/** 拉一次全量快照（首屏、断线重连、手动刷新都用它） */
export async function refreshSnapshot(): Promise<void> {
  const snapshot = await dashboardApi.snapshot()
  drones.value = snapshot.drones ?? []
  kpi.value = snapshot.kpi ?? null
  conflicts.value = (snapshot.openConflicts ?? []).slice(0, MAX_CONFLICTS)
  // 订单标记不走每秒推送，随快照低频刷新（首帧 + 轮询/定时）即可
  orderPins.value = snapshot.orders ?? []
  simTimeS.value = snapshot.simTimeS ?? 0
  tickCount.value = snapshot.tickCount ?? 0
  runStatus.value = (snapshot.runStatus as RunStatus) ?? 'STOPPED'
  lastMessageAt.value = new Date().toISOString()
}

export async function loadZones(): Promise<void> {
  zones.value = await airspaceApi.list()
}

/** 加载充电站：站点是低频台账，进页面拉一次即可 */
export async function loadStations(): Promise<void> {
  try {
    stations.value = await stationApi.list()
  } catch {
    // 站点只是地图上的一层参照，拉不到不能影响看板主流程
    stations.value = []
  }
}

/** 加载"在飞任务"的航路折线，用于地图上画出飞行轨迹 */
export async function loadRoutes(): Promise<void> {
  const activeStatuses: TaskStatus[] = ['ASSIGNED', 'TAKEOFF', 'CRUISING', 'DELIVERING', 'RETURNING']
  const pages = await Promise.all(
    activeStatuses.map((status) => taskApi.list({ status, size: 50 })),
  )
  // 同一架飞机只保留一条航路，避免状态切换瞬间出现重复线条
  const seen = new Set<number>()
  const tasks = pages
    .flatMap((page) => page.content)
    .filter((task) => {
      if (seen.has(task.id)) return false
      seen.add(task.id)
      return true
    })
    .slice(0, MAX_ROUTES_ON_MAP)

  const loaded = await Promise.all(
    tasks.map(async (task) => {
      try {
        const waypoints = await taskApi.waypoints(task.id)
        const points = waypoints.map((wp) => [wp.lat, wp.lon] as [number, number])
        return {
          taskId: task.id,
          label: `${task.droneCode ?? '未知机'} · ${task.orderNo ?? ''}`.trim(),
          status: task.status,
          points,
        }
      } catch {
        // 单条航路失败不影响其它航线展示
        return null
      }
    }),
  )
  routes.value = loaded.filter((item): item is NonNullable<typeof item> => item !== null && item.points.length >= 2)
}

export async function loadConflicts(): Promise<void> {
  conflicts.value = (await dashboardApi.conflicts('OPEN')).slice(0, MAX_CONFLICTS)
}

export async function resolveConflict(id: number, resolution?: string): Promise<void> {
  await dashboardApi.resolveConflict(id, resolution)
  conflicts.value = conflicts.value.filter((item) => item.id !== id)
}

export async function refreshRun(): Promise<void> {
  const run = await simulationApi.current()
  currentRun.value = run
  if (run) {
    runStatus.value = run.status
    simTimeS.value = run.simTimeS
    tickCount.value = run.tickCount
  }
}

export async function probeAlgorithm(): Promise<void> {
  try {
    const health = await dashboardApi.algorithmHealth()
    algorithmEnabled.value = health.enabled
    algorithmAvailable.value = health.available
  } catch {
    algorithmEnabled.value = null
    algorithmAvailable.value = false
  }
}

// ---------------------------------------------------------------------------
// 仿真控制
// ---------------------------------------------------------------------------

/** 运行时状态：引擎是否在推进、自动派单是否开启 */
const runtimeState = ref<SimulationRuntimeState | null>(null)

export async function loadRuntimeState(): Promise<void> {
  try {
    runtimeState.value = await simulationApi.state()
  } catch {
    // 状态查询失败不影响其它功能，静默处理（state 请求已标为 silent，不会弹提示）
  }
}

/**
 * 开关自动派单。
 *
 * 关掉它，订单就只会安静地排在队列里 —— 演示/教学时这样才能看清
 * "派单"这个动作到底做了什么，而不是和后台定时任务抢活干。
 */
export async function setAutoDispatch(enabled: boolean): Promise<void> {
  runtimeState.value = await simulationApi.setAutoDispatch(enabled)
}

/** 暂停推进（运行记录保留，手动「步进一拍」仍可用） */
export async function pauseRun(): Promise<void> {
  runtimeState.value = await simulationApi.pause()
  await refreshRun()
}

/** 从暂停处继续，不重置拍数与仿真时间 */
export async function resumeRun(): Promise<void> {
  runtimeState.value = await simulationApi.resume()
  await refreshRun()
}

/** 新建一轮运行（计时从 0 开始） */
export async function startRun(payload?: {
  name?: string
  tickMs?: number
  speedFactor?: number
}): Promise<SimulationRun> {
  const run = await simulationApi.start(payload)
  currentRun.value = run
  runStatus.value = run.status
  await loadRuntimeState()
  return run
}

/** 结束运行（不可继续，需要新建一轮或调用 resume） */
export async function stopRun(): Promise<void> {
  if (!currentRun.value) {
    await refreshRun()
  }
  if (currentRun.value) {
    const run = await simulationApi.stop(currentRun.value.id)
    currentRun.value = run
    runStatus.value = run.status
  }
  await loadRuntimeState()
}

/** 手动步进一拍（教学演示用：不依赖定时器也能看到状态推进） */
export async function manualTick(): Promise<TickResult> {
  const result = await simulationApi.tick()
  // 步进后立刻刷新一次，避免界面还停在上一拍
  await Promise.all([refreshSnapshot(), loadRoutes()])
  return result
}

// ---------------------------------------------------------------------------
// 对外暴露
// ---------------------------------------------------------------------------

export function useTelemetry() {
  return {
    // 状态
    kpi,
    drones,
    conflicts,
    events,
    zones,
    stations,
    routes,
    orderPins,
    currentRun,
    runtimeState,
    algorithmAvailable,
    algorithmEnabled,
    connected,
    wsState,
    usingPolling,
    lastMessageAt,
    simTimeS,
    tickCount,
    runStatus,
    // 动作
    connect,
    disconnect,
    refreshSnapshot,
    loadZones,
    loadStations,
    loadRoutes,
    loadConflicts,
    resolveConflict,
    refreshRun,
    loadRuntimeState,
    setAutoDispatch,
    pauseRun,
    resumeRun,
    probeAlgorithm,
    startRun,
    stopRun,
    manualTick,
  }
}
