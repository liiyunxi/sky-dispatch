import { http } from './client'
import type {
  AirspaceZone,
  AlgorithmHealth,
  CandidatePreview,
  ChargingStation,
  ConflictView,
  DispatchDecision,
  DispatchResult,
  DispatchTask,
  DispatchStrategyType,
  Drone,
  DroneCreateRequest,
  DroneStatus,
  Kpi,
  EventLogItem,
  Order,
  OrderCreateRequest,
  OrderStatus,
  PageResponse,
  Priority,
  DroneFrame,
  SimulationRun,
  SimulationRuntimeState,
  Snapshot,
  TaskStatus,
  TickResult,
  Waypoint,
  ZoneCreateRequest,
} from './types'

/**
 * 后端接口清单（与 docs/03-api-contract.md 一一对应）。
 *
 * 全部集中在一个文件里，是为了让"前端到底调了哪些接口"一眼可见；
 * 拆成 8 个文件反而要来回翻。接口多了之后再按模块拆。
 */

// ---------------------------------------------------------------------------
// 看板
// ---------------------------------------------------------------------------
export const dashboardApi = {
  kpi: () => http.get<Kpi>('/dashboard/kpi', undefined, true),
  drones: () => http.get<DroneFrame[]>('/dashboard/drones', undefined, true),
  snapshot: () => http.get<Snapshot>('/dashboard/snapshot', undefined, true),
  conflicts: (status?: string) => http.get<ConflictView[]>('/dashboard/conflicts', { status }, true),
  resolveConflict: (id: number, resolution?: string) =>
    http.post<ConflictView>(`/dashboard/conflicts/${id}/resolve`, undefined, { resolution }),
  algorithmHealth: () => http.get<AlgorithmHealth>('/dashboard/algorithm-health', undefined, true),
}

// ---------------------------------------------------------------------------
// 订单
// ---------------------------------------------------------------------------
export const orderApi = {
  list: (params: { status?: OrderStatus; priority?: Priority; page?: number; size?: number }) =>
    http.get<PageResponse<Order>>('/orders', params),
  get: (id: number) => http.get<Order>(`/orders/${id}`),
  create: (body: OrderCreateRequest) => http.post<Order>('/orders', body),
  cancel: (id: number) => http.post<Order>(`/orders/${id}/cancel`),
  dispatch: (id: number, strategy?: string) =>
    http.post<DispatchResult>(`/orders/${id}/dispatch`, undefined, { strategy }),
}

// ---------------------------------------------------------------------------
// 无人机
// ---------------------------------------------------------------------------
export const droneApi = {
  list: (status?: DroneStatus) => http.get<Drone[]>('/drones', { status }),
  get: (id: number) => http.get<Drone>(`/drones/${id}`),
  register: (body: DroneCreateRequest) => http.post<Drone>('/drones', body),
  updateStatus: (id: number, status: DroneStatus) =>
    http.patch<Drone>(`/drones/${id}/status`, { status }),
}

// ---------------------------------------------------------------------------
// 空域
// ---------------------------------------------------------------------------
export const airspaceApi = {
  list: () => http.get<AirspaceZone[]>('/airspace/zones'),
  create: (body: ZoneCreateRequest) => http.post<AirspaceZone>('/airspace/zones', body),
  remove: (id: number) => http.delete<{ success: boolean; message: string }>(`/airspace/zones/${id}`),
}

// ---------------------------------------------------------------------------
// 充电站
// ---------------------------------------------------------------------------
export const stationApi = {
  /** 全部启用的充电站：画在态势地图与立体视图上，让运营看得见"中途能落哪儿" */
  list: () => http.get<ChargingStation[]>('/stations'),
}

// ---------------------------------------------------------------------------
// 调度
// ---------------------------------------------------------------------------
export const dispatchApi = {
  strategies: () => http.get<DispatchStrategyType[]>('/dispatch/strategies'),
  autoDispatch: (strategy?: string, limit = 5) =>
    http.post<DispatchResult[]>('/dispatch/auto', undefined, { strategy, limit }),
  decisions: (orderId: number) =>
    http.get<DispatchDecision[]>('/dispatch/decisions', { orderId }, true),
  /** 搜罗可承接订单的无人机（派单前预览，只返回入围候选） */
  candidates: (orderId: number, strategy?: string) =>
    http.get<CandidatePreview>('/dispatch/candidates', { orderId, strategy }, true),
}

// ---------------------------------------------------------------------------
// 任务
// ---------------------------------------------------------------------------
export const taskApi = {
  list: (params: { status?: TaskStatus; droneId?: number; page?: number; size?: number }) =>
    http.get<PageResponse<DispatchTask>>('/tasks', params),
  get: (id: number) => http.get<DispatchTask>(`/tasks/${id}`),
  waypoints: (id: number) => http.get<Waypoint[]>(`/tasks/${id}/waypoints`),
  events: (id: number) => http.get<EventLogItem[]>(`/tasks/${id}/events`),
  abort: (id: number, reason?: string) => http.post<DispatchTask>(`/tasks/${id}/abort`, { reason }),
}

// ---------------------------------------------------------------------------
// 仿真
// ---------------------------------------------------------------------------
export const simulationApi = {
  start: (body?: { name?: string; tickMs?: number; speedFactor?: number; randomSeed?: number }) =>
    http.post<SimulationRun>('/simulation/runs', body ?? {}),
  stop: (id: number) => http.post<SimulationRun>(`/simulation/runs/${id}/stop`),
  current: () => http.get<SimulationRun | null>('/simulation/runs/current', undefined, true),
  tick: () => http.post<TickResult>('/simulation/tick'),
  /** 运行时状态：引擎是否在推进、自动派单是否开启 */
  state: () => http.get<SimulationRuntimeState>('/simulation/state', undefined, true),
  /** 开关自动派单（关掉后由人工派单，便于观察"派单到底做了什么"） */
  setAutoDispatch: (enabled: boolean) =>
    http.post<SimulationRuntimeState>('/simulation/auto-dispatch', undefined, { enabled }),
  /** 暂停推进（手动步进仍可用） */
  pause: () => http.post<SimulationRuntimeState>('/simulation/pause'),
  /** 从暂停处继续（不重置计时；想从 0 开始请用 start 新建一轮） */
  resume: () => http.post<SimulationRuntimeState>('/simulation/resume'),
}

// ---------------------------------------------------------------------------
// 报表
// ---------------------------------------------------------------------------
export const reportApi = {
  telemetryCsv: (taskId: number) => http.download(`/reports/tasks/${taskId}/telemetry.csv`),
  eventsCsv: (taskId?: number) => http.download('/reports/events.csv', { taskId }),
  tasksCsv: () => http.download('/reports/tasks.csv'),
}

export * from './types'
