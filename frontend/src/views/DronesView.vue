<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import {
  AimOutlined,
  HistoryOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import { airspaceApi, droneApi, taskApi } from '@/api'
import type {
  AirspaceZone,
  DispatchTask,
  Drone,
  DroneCreateRequest,
  DroneFrame,
  DroneStatus,
} from '@/api'
import DroneModel3D from '@/components/drone/DroneModel3D.vue'
import DroneAnatomy from '@/components/drone/DroneAnatomy.vue'
import PointPickerMap from '@/components/map/PointPickerMap.vue'
import { useTelemetry } from '@/store/telemetry'
import {
  DRONE_STATUS_COLOR,
  DRONE_STATUS_HEX,
  DRONE_STATUS_TEXT,
  TASK_STATUS_COLOR,
  TASK_STATUS_TEXT,
  formatDateTime,
  formatDistance,
  formatDuration,
  formatEnergy,
  formatFromNow,
  formatPercent,
} from '@/utils/format'

/**
 * 机队战备指挥台。
 *
 * 原页面是一张台账表格：编号、状态、电量、累计里程……信息都对，但回答不了
 * "现在能派几架、谁快没电了、哪架最忙、这架飞机的履历如何"。
 *
 * 改造后分四层：
 *
 *  1. **战备态势**：战备率环形读数 + 七状态分布条 + 可点击状态筛选；
 *  2. **机型编队**：按机型聚合在册数 / 可派数 / 平均电量 / 载重与满电航程；
 *  3. **能力矩阵**：每架机一个点（累计任务 × 当前电量，点大小 = 载重），
 *     一眼看出"谁被用得很狠但电量还高、谁闲着却没电"；
 *  4. **机队卡片**：电量环、实时遥测（高度/速度/航向）、当前任务进度、
 *     累计战绩、距起降点距离，以及任务履历抽屉（可继续下钻到任务/订单链路）。
 *
 * 数据来源：台账走 REST（机型参数、累计战绩），状态/电量/位置优先用 WebSocket
 * 遥测帧覆盖，所以卡片是"活"的；台账每 10 秒静默刷新一次作为兜底。
 */

const router = useRouter()
const { drones: liveFrames } = useTelemetry()

const drones = ref<Drone[]>([])
const loading = ref(false)

/** 注册 / 整机解析 / 任务履历 */
const submitting = ref(false)
const modalOpen = ref(false)
const model3dOpen = ref(false)
const model3dDrone = ref<Drone | null>(null)
const detailTab = ref<'anatomy' | 'load'>('anatomy')

const historyOpen = ref(false)
const historyLoading = ref(false)
const historyDrone = ref<Drone | null>(null)
const historyTasks = ref<DispatchTask[]>([])

/** 筛选（全部在前端完成：机队规模小，且态势计数需要全量数据） */
const statusFilter = ref<DroneStatus | undefined>(undefined)
const modelFilter = ref<string | undefined>(undefined)
const keyword = ref('')
const page = ref(1)
const pageSize = ref(12)

/** 台账静默刷新：累计战绩与 currentTaskId 由后端定时更新 */
const autoRefresh = ref(true)
let refreshTimer: number | null = null

const STATUS_ORDER: DroneStatus[] = [
  'IDLE',
  'ASSIGNED',
  'IN_FLIGHT',
  'RETURNING',
  'CHARGING',
  'MAINTENANCE',
  'OFFLINE',
]

// ---------------------------------------------------------------------------
// 实时遥测覆盖
// ---------------------------------------------------------------------------

const liveById = computed(() => {
  const map = new Map<number, DroneFrame>()
  liveFrames.value.forEach((frame) => map.set(frame.droneId, frame))
  return map
})

function liveOf(drone: Drone): DroneFrame | undefined {
  return liveById.value.get(drone.id)
}

/** 状态以实时遥测为准（仿真每拍都会推），掉线时退回台账 */
function effectiveStatus(drone: Drone): DroneStatus {
  return liveOf(drone)?.status ?? drone.status
}

function effectiveBattery(drone: Drone): number {
  const value = liveOf(drone)?.batteryPct ?? drone.batteryPct ?? 0
  return Math.max(0, Math.min(100, Number(value)))
}

function effectiveLat(drone: Drone): number | undefined {
  return liveOf(drone)?.lat ?? drone.currentLat
}

function effectiveLon(drone: Drone): number | undefined {
  return liveOf(drone)?.lon ?? drone.currentLon
}

/** 距起降点的直线距离（公里）：回答"这架机飞出去多远了" */
function homeDistanceKm(drone: Drone): number | null {
  const lat = effectiveLat(drone)
  const lon = effectiveLon(drone)
  if (lat == null || lon == null || drone.homeLat == null || drone.homeLon == null) return null
  const earthRadius = 6371008.8
  const dLat = ((drone.homeLat - lat) * Math.PI) / 180
  const dLon = ((drone.homeLon - lon) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat * Math.PI) / 180) * Math.cos((drone.homeLat * Math.PI) / 180) * Math.sin(dLon / 2) ** 2
  return (2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)))) / 1000
}

// ---------------------------------------------------------------------------
// 战备态势
// ---------------------------------------------------------------------------

const statusCounts = computed<Record<DroneStatus, number>>(() => {
  const counts: Record<DroneStatus, number> = {
    IDLE: 0,
    ASSIGNED: 0,
    IN_FLIGHT: 0,
    RETURNING: 0,
    CHARGING: 0,
    MAINTENANCE: 0,
    OFFLINE: 0,
  }
  drones.value.forEach((drone) => {
    counts[effectiveStatus(drone)] += 1
  })
  return counts
})

/** 可立即派单：空闲且电量高于安全冗余（与后端 min-battery-reserve-pct 25% 对齐） */
const availableDrones = computed(() =>
  drones.value.filter((drone) => effectiveStatus(drone) === 'IDLE' && effectiveBattery(drone) >= 25),
)

const readiness = computed(() => {
  const total = drones.value.length
  if (!total) return 0
  return Math.round((availableDrones.value.length / total) * 100)
})

/** 环形进度：r=38 → 周长 ≈ 238.76 */
const READINESS_CIRCUMFERENCE = 2 * Math.PI * 38
const readinessDash = computed(() => `${(readiness.value / 100) * READINESS_CIRCUMFERENCE} ${READINESS_CIRCUMFERENCE}`)

const avgBattery = computed(() => {
  if (!drones.value.length) return 0
  const sum = drones.value.reduce((total, drone) => total + effectiveBattery(drone), 0)
  return sum / drones.value.length
})

const onlineCount = computed(
  () => drones.value.filter((drone) => effectiveStatus(drone) !== 'OFFLINE').length,
)

const lowBatteryDrones = computed(() =>
  drones.value.filter(
    (drone) =>
      effectiveBattery(drone) < 25 &&
      effectiveStatus(drone) !== 'CHARGING' &&
      effectiveStatus(drone) !== 'OFFLINE',
  ),
)

const activeMissions = computed(
  () =>
    drones.value.filter((drone) =>
      ['ASSIGNED', 'IN_FLIGHT', 'RETURNING', 'CHARGING'].includes(effectiveStatus(drone)),
    ).length,
)

function statusPercent(status: DroneStatus): number {
  const total = drones.value.length
  if (!total) return 0
  return (statusCounts.value[status] / total) * 100
}

interface FleetAlert {
  level: 'danger' | 'warning' | 'info'
  text: string
}

/** 调度建议：把"需要人处理的事"直接写在顶部，而不是让运营自己扫 26 张卡片 */
const fleetAlerts = computed<FleetAlert[]>(() => {
  const alerts: FleetAlert[] = []
  const low = lowBatteryDrones.value
  if (low.length) {
    const names = low.slice(0, 3).map((drone) => drone.code).join('、')
    alerts.push({
      level: 'warning',
      text: `${low.length} 架电量低于 25%：${names}${low.length > 3 ? ' 等' : ''}，建议安排返航补能`,
    })
  }
  const offline = drones.value.filter((drone) => effectiveStatus(drone) === 'OFFLINE')
  if (offline.length) {
    alerts.push({ level: 'info', text: `${offline.length} 架离线，不参与派单` })
  }
  const maintenance = drones.value.filter((drone) => effectiveStatus(drone) === 'MAINTENANCE')
  if (maintenance.length) {
    alerts.push({ level: 'info', text: `${maintenance.length} 架维护中，恢复后需手动改回空闲` })
  }
  if (!drones.value.length) {
    alerts.push({ level: 'info', text: '尚未注册任何无人机，点击右上角「注册无人机」开始' })
  } else if (!alerts.length) {
    alerts.push({
      level: 'info',
      text: `机队状态正常：${availableDrones.value.length} 架可立即派单，平均电量 ${avgBattery.value.toFixed(0)}%`,
    })
  }
  return alerts
})

function toggleStatus(status: DroneStatus): void {
  statusFilter.value = statusFilter.value === status ? undefined : status
}

function toggleModel(code: string): void {
  modelFilter.value = modelFilter.value === code ? undefined : code
}

// ---------------------------------------------------------------------------
// 机型编队
// ---------------------------------------------------------------------------

interface ModelGroup {
  code: string
  name: string
  count: number
  available: number
  avgBattery: number
  payloadKg: number
  cruiseMps: number
  maxFlightMin: number
  /** 满电理论航程（km） */
  rangeKm: number
}

const modelGroups = computed<ModelGroup[]>(() => {
  const map = new Map<string, Drone[]>()
  drones.value.forEach((drone) => {
    const list = map.get(drone.modelCode) ?? []
    list.push(drone)
    map.set(drone.modelCode, list)
  })
  return [...map.entries()]
    .map(([code, list]) => {
      const first = list[0]
      const energyPerKm = Number(first.energyPerKmWh ?? 0)
      const capacity = Number(first.batteryCapacityWh ?? 0)
      return {
        code,
        name: first.modelName ?? '',
        count: list.length,
        available: list.filter(
          (drone) => effectiveStatus(drone) === 'IDLE' && effectiveBattery(drone) >= 25,
        ).length,
        avgBattery: list.reduce((total, drone) => total + effectiveBattery(drone), 0) / list.length,
        payloadKg: Number(first.maxPayloadKg ?? 0),
        cruiseMps: Number(first.cruiseSpeedMps ?? 0),
        maxFlightMin: Number(first.maxFlightMin ?? 0),
        rangeKm: energyPerKm > 0 ? capacity / energyPerKm : 0,
      }
    })
    .sort((a, b) => b.count - a.count)
})

// ---------------------------------------------------------------------------
// 筛选 / 排序 / 分页
// ---------------------------------------------------------------------------

const filteredDrones = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const weight: Record<DroneStatus, number> = {
    IN_FLIGHT: 0,
    ASSIGNED: 1,
    RETURNING: 2,
    CHARGING: 3,
    IDLE: 4,
    MAINTENANCE: 5,
    OFFLINE: 6,
  }
  return drones.value
    .filter((drone) => {
      if (statusFilter.value && effectiveStatus(drone) !== statusFilter.value) return false
      if (modelFilter.value && drone.modelCode !== modelFilter.value) return false
      if (!kw) return true
      const live = liveOf(drone)
      return [drone.code, drone.modelCode, drone.modelName, live?.orderNo]
        .filter((value) => value != null)
        .some((value) => String(value).toLowerCase().includes(kw))
    })
    .sort((a, b) => {
      const diff = weight[effectiveStatus(a)] - weight[effectiveStatus(b)]
      return diff !== 0 ? diff : effectiveBattery(b) - effectiveBattery(a)
    })
})

const pagedDrones = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredDrones.value.slice(start, start + pageSize.value)
})

watch([statusFilter, modelFilter, keyword], () => {
  page.value = 1
})

function handlePageChange(next: number, size: number): void {
  page.value = next
  pageSize.value = size
}

// ---------------------------------------------------------------------------
// 能力矩阵（累计任务 × 当前电量）
// ---------------------------------------------------------------------------

interface MatrixPoint {
  x: number
  y: number
  r: number
  color: string
  code: string
  battery: number
  tasks: number
  statusText: string
}

const capabilityMatrix = computed(() => {
  const list = drones.value
  if (!list.length) return null
  const width = 680
  const height = 220
  const padL = 46
  const padR = 18
  const padT = 16
  const padB = 34
  const maxTasks = Math.max(4, ...list.map((drone) => Number(drone.totalTasks ?? 0)))
  const maxPayload = Math.max(1, ...list.map((drone) => Number(drone.maxPayloadKg ?? 0)))
  const totalTasks = list.reduce((sum, drone) => sum + Number(drone.totalTasks ?? 0), 0)
  const avgTasks = totalTasks / list.length
  const xOf = (tasks: number) => padL + (tasks / maxTasks) * (width - padL - padR)
  const yOf = (battery: number) => height - padB - (battery / 100) * (height - padT - padB)
  const points: MatrixPoint[] = list.map((drone) => {
    const battery = effectiveBattery(drone)
    const tasks = Number(drone.totalTasks ?? 0)
    const status = effectiveStatus(drone)
    return {
      x: xOf(tasks),
      y: yOf(battery),
      r: 3 + (Number(drone.maxPayloadKg ?? 0) / maxPayload) * 5,
      color: DRONE_STATUS_HEX[status],
      code: drone.code,
      battery,
      tasks,
      statusText: DRONE_STATUS_TEXT[status] ?? status,
    }
  })
  return {
    width,
    height,
    padL,
    padR,
    padT,
    padB,
    maxTasks,
    avgTasks,
    points,
    lowY: yOf(25),
    midY: yOf(50),
    axisY: height - padB,
    avgX: xOf(avgTasks),
    baseX: padL,
    endX: width - padR,
  }
})

// ---------------------------------------------------------------------------
// 卡片读数
// ---------------------------------------------------------------------------

type Tone = 'success' | 'active' | 'returning' | 'charging' | 'warning' | 'muted'

function statusTone(status: DroneStatus): Tone {
  switch (status) {
    case 'IDLE':
      return 'success'
    case 'ASSIGNED':
    case 'IN_FLIGHT':
      return 'active'
    case 'RETURNING':
      return 'returning'
    case 'CHARGING':
      return 'charging'
    case 'MAINTENANCE':
      return 'warning'
    default:
      return 'muted'
  }
}

/** 电量语义：<25 危险、<50 偏黄、其余正常 */
function batteryTone(drone: Drone): 'danger' | 'warning' | 'normal' {
  const value = effectiveBattery(drone)
  if (value < 25) return 'danger'
  if (value < 50) return 'warning'
  return 'normal'
}

/** 环形电量：r=31 → 周长 ≈ 194.78 */
const BATTERY_CIRCUMFERENCE = 2 * Math.PI * 31

function batteryDash(drone: Drone): string {
  const ratio = effectiveBattery(drone) / 100
  return `${ratio * BATTERY_CIRCUMFERENCE} ${BATTERY_CIRCUMFERENCE}`
}

function rangeOf(drone: Drone): number {
  const energyPerKm = Number(drone.energyPerKmWh ?? 0)
  if (energyPerKm <= 0) return 0
  return Number(drone.batteryCapacityWh ?? 0) / energyPerKm
}

/** 卡片底部的一行状态说明：把"这架机现在能不能用"讲清楚 */
function statusNote(drone: Drone): string {
  const status = effectiveStatus(drone)
  if (status === 'OFFLINE') return `离线 · 最后心跳 ${formatFromNow(drone.lastHeartbeatAt)}`
  if (status === 'MAINTENANCE') return '维护中 · 不参与派单'
  if (status === 'CHARGING') return '补能中 · 充满后需改回空闲'
  if (effectiveBattery(drone) < 25) return '低电量 · 建议返航补能'
  if (status === 'IDLE') return availableDrones.value.some((item) => item.id === drone.id)
    ? '可立即派单'
    : '空闲 · 电量低于派单冗余'
  const live = liveOf(drone)
  if (live?.taskId) return `执行中 · 任务 #${live.taskId}`
  return '执行中'
}

function progressOf(drone: Drone): number {
  const value = liveOf(drone)?.progressPct ?? 0
  return Math.max(0, Math.min(100, Number(value)))
}

// ---------------------------------------------------------------------------
// 台账加载
// ---------------------------------------------------------------------------

async function loadDrones(options: { silent?: boolean } = {}): Promise<void> {
  if (!options.silent) loading.value = true
  try {
    drones.value = await droneApi.list()
    // 筛选后当前页可能越界（例如注册/删除后）
    const maxPage = Math.max(1, Math.ceil(filteredDrones.value.length / pageSize.value))
    if (page.value > maxPage) page.value = maxPage
  } catch {
    // client.ts 已提示；保留上一次数据
  } finally {
    if (!options.silent) loading.value = false
  }
}

function startAutoRefresh(): void {
  stopAutoRefresh()
  refreshTimer = window.setInterval(() => {
    if (autoRefresh.value) void loadDrones({ silent: true })
  }, 10_000)
}

function stopAutoRefresh(): void {
  if (refreshTimer !== null) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
}

// ---------------------------------------------------------------------------
// 注册
// ---------------------------------------------------------------------------

/** 注册弹窗可选型号：以后端返回过的型号为主，兜底内置常见型号 */
const FALLBACK_MODEL_CODES = ['SKY-X4', 'SKY-M8', 'SKY-H2']

const modelSpecs = computed(() => {
  const map = new Map<string, Drone>()
  for (const drone of drones.value) {
    if (drone.modelCode && !map.has(drone.modelCode)) map.set(drone.modelCode, drone)
  }
  return map
})

const selectedModelSpec = computed(() => modelSpecs.value.get(form.modelCode) ?? null)

/** 安全冗余（与后端 sky.dispatch.min-battery-reserve-pct 一致，默认 25%） */
const RESERVE_RATIO = 0.25

const rangeInfo = computed(() => {
  const spec = selectedModelSpec.value
  if (!spec || !spec.energyPerKmWh) return null
  const fullKm = Number(spec.batteryCapacityWh) / Number(spec.energyPerKmWh)
  const usableKm = fullKm * (1 - RESERVE_RATIO)
  return {
    fullKm,
    usableKm,
    roundTripKm: usableKm / 2,
    hoverMinutes: Number(spec.maxFlightMin),
  }
})

const modelCodeOptions = computed(() => {
  const codes = new Set<string>(FALLBACK_MODEL_CODES)
  drones.value.forEach((drone) => {
    if (drone.modelCode) codes.add(drone.modelCode)
  })
  return [...codes].map((code) => {
    const spec = modelSpecs.value.get(code)
    const label = spec
      ? `${code} · ${spec.modelName ?? ''}（载重 ${Number(spec.maxPayloadKg).toFixed(0)}kg）`
      : code
    return { value: code, label }
  })
})

const form = reactive<{
  code: string
  modelCode: string
  batteryPct: number | null
  homeLat: number | null
  homeLon: number | null
}>({
  code: '',
  modelCode: FALLBACK_MODEL_CODES[0],
  batteryPct: 100,
  homeLat: 31.2304,
  homeLon: 121.4737,
})

const statusOptions = STATUS_ORDER.map((status) => ({
  value: status,
  label: DRONE_STATUS_TEXT[status],
}))

function openModal(): void {
  form.code = ''
  form.modelCode = FALLBACK_MODEL_CODES[0]
  form.batteryPct = 100
  form.homeLat = 31.2304
  form.homeLon = 121.4737
  modalOpen.value = true
}

async function submitRegister(): Promise<void> {
  if (!form.modelCode) {
    message.warning('请选择机型')
    return
  }
  const batteryPct = form.batteryPct ?? 100
  if (batteryPct < 0 || batteryPct > 100) {
    message.warning('初始电量需在 0 ~ 100 之间')
    return
  }
  const body: DroneCreateRequest = {
    code: form.code.trim() || undefined,
    modelCode: form.modelCode,
    batteryPct,
    home: { lat: form.homeLat ?? 31.2304, lon: form.homeLon ?? 121.4737 },
  }
  submitting.value = true
  try {
    const created = await droneApi.register(body)
    message.success(`无人机 ${created.code} 注册成功`)
    modalOpen.value = false
    await loadDrones()
  } catch {
    // 失败保持弹窗，便于修改后重试
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 状态变更（沿用"先选择、再确认"的安全交互）
// ---------------------------------------------------------------------------

const statusDraft = ref<Record<number, DroneStatus>>({})

function draftOf(drone: Drone): DroneStatus {
  return statusDraft.value[drone.id] ?? drone.status
}

function stageStatus(drone: Drone, value: DroneStatus): void {
  statusDraft.value = { ...statusDraft.value, [drone.id]: value }
}

function cancelStatusChange(drone: Drone): void {
  statusDraft.value = { ...statusDraft.value, [drone.id]: drone.status }
}

async function confirmStatusChange(drone: Drone): Promise<void> {
  const next = statusDraft.value[drone.id]
  if (next === undefined || next === drone.status) return
  try {
    await droneApi.updateStatus(drone.id, next)
    message.success(`${drone.code} 状态已更新为「${DRONE_STATUS_TEXT[next]}」`)
    await loadDrones({ silent: true })
  } catch {
    cancelStatusChange(drone)
  } finally {
    const rest = { ...statusDraft.value }
    delete rest[drone.id]
    statusDraft.value = rest
  }
}

// ---------------------------------------------------------------------------
// 整机解析
// ---------------------------------------------------------------------------

function openModel3d(drone: Drone): void {
  model3dDrone.value = drone
  detailTab.value = 'anatomy'
  model3dOpen.value = true
}

// ---------------------------------------------------------------------------
// 任务履历
// ---------------------------------------------------------------------------

async function openHistory(drone: Drone): Promise<void> {
  historyDrone.value = drone
  historyOpen.value = true
  historyLoading.value = true
  historyTasks.value = []
  try {
    const pageData = await taskApi.list({ droneId: drone.id, page: 0, size: 20 })
    historyTasks.value = pageData.content ?? []
  } catch {
    historyTasks.value = []
  } finally {
    historyLoading.value = false
  }
}

/** 在任务页按该机过滤：任务页会读取 query.droneId */
function gotoDroneTasks(drone: Drone | null): void {
  if (!drone) return
  void router.push({ path: '/tasks', query: { droneId: String(drone.id) } })
}

/** 从履历直接跳到该任务的订单链路 */
function gotoOrderTrace(task: DispatchTask): void {
  void router.push({ path: '/orders', query: { traceOrderId: String(task.orderId) } })
}

// ---------------------------------------------------------------------------
// 空域参照层（注册地图）
// ---------------------------------------------------------------------------

const zones = ref<AirspaceZone[]>([])

async function loadZones(): Promise<void> {
  try {
    zones.value = await airspaceApi.list()
  } catch {
    zones.value = []
  }
}

// ---------------------------------------------------------------------------
// 初始化
// ---------------------------------------------------------------------------

onMounted(() => {
  void loadDrones()
  void loadZones()
  startAutoRefresh()
})

onUnmounted(stopAutoRefresh)

/** 履历里显示"这架机的最后心跳"，用真实时间而不是仿真时间 */
function heartbeatText(drone: Drone): string {
  if (!drone.lastHeartbeatAt) return '—'
  return formatFromNow(drone.lastHeartbeatAt)
}

/** 距起降点距离的展示文本（避免在模板里对可空值做断言） */
function homeDistanceText(drone: Drone): string {
  const km = homeDistanceKm(drone)
  return km == null ? '—' : `${km.toFixed(1)} km`
}
</script>

<template>
  <div class="drones-view">
    <!-- ------------------------------ 机队战备态势 ------------------------------ -->
    <section class="fleet">
      <div class="fleet-head">
        <div class="fleet-title-block">
          <div class="fleet-title">
            <span class="fleet-led" aria-hidden="true" />
            机队战备指挥台
            <span class="fleet-live">LIVE</span>
          </div>
          <div class="fleet-sub">机体能力、实时遥测、战备率与任务履历 · 每架机都可下钻</div>
        </div>

        <div class="fleet-headline">
          <svg class="readiness-ring" viewBox="0 0 96 96" role="img" aria-label="机队战备率">
            <circle class="ring-track" cx="48" cy="48" r="38" />
            <circle
              class="ring-value"
              cx="48"
              cy="48"
              r="38"
              :stroke-dasharray="readinessDash"
              transform="rotate(-90 48 48)"
            />
            <text x="48" y="47" text-anchor="middle" class="ring-number">{{ readiness }}%</text>
            <text x="48" y="64" text-anchor="middle" class="ring-label">战备率</text>
          </svg>

          <div class="fleet-metrics">
            <div class="metric">
              <em>可立即派单</em>
              <strong>{{ availableDrones.length }}<i>/ {{ drones.length }}</i></strong>
            </div>
            <div class="metric">
              <em>在线</em>
              <strong>{{ onlineCount }}<i>/ {{ drones.length }}</i></strong>
            </div>
            <div class="metric">
              <em>平均电量</em>
              <strong>{{ avgBattery.toFixed(0) }}<i>%</i></strong>
            </div>
            <div class="metric">
              <em>执行中</em>
              <strong>{{ activeMissions }}<i>架</i></strong>
            </div>
            <div class="metric">
              <em>低电量</em>
              <strong :class="{ danger: lowBatteryDrones.length > 0 }">{{ lowBatteryDrones.length }}<i>架</i></strong>
            </div>
          </div>
        </div>
      </div>

      <!-- 七状态分布条 + 可点击筛选 -->
      <div class="status-bar" aria-hidden="true">
        <span
          v-for="status in STATUS_ORDER"
          :key="status"
          v-show="statusCounts[status] > 0"
          class="status-seg"
          :style="{ width: `${statusPercent(status)}%`, background: DRONE_STATUS_HEX[status] }"
        />
      </div>

      <div class="status-chips">
        <button
          type="button"
          class="status-chip"
          :class="{ active: !statusFilter }"
          @click="statusFilter = undefined"
        >
          全部 <b>{{ drones.length }}</b>
        </button>
        <button
          v-for="status in STATUS_ORDER"
          :key="status"
          type="button"
          class="status-chip"
          :class="{ active: statusFilter === status }"
          :style="{ '--chip-color': DRONE_STATUS_HEX[status] }"
          @click="toggleStatus(status)"
        >
          {{ DRONE_STATUS_TEXT[status] }} <b>{{ statusCounts[status] }}</b>
        </button>
      </div>

      <div class="fleet-lower">
        <!-- 机型编队 -->
        <div class="model-column">
          <div class="panel-label">机型编队</div>
          <button
            v-for="group in modelGroups"
            :key="group.code"
            type="button"
            class="model-card"
            :class="{ active: modelFilter === group.code }"
            @click="toggleModel(group.code)"
          >
            <span class="model-head">
              <span class="model-code text-mono">{{ group.code }}</span>
              <span class="model-name">{{ group.name }}</span>
              <span class="model-count">{{ group.count }} 架</span>
            </span>
            <span class="model-row">
              <span>可派 <b>{{ group.available }}</b> / {{ group.count }}</span>
              <span>平均电量 <b>{{ group.avgBattery.toFixed(0) }}%</b></span>
            </span>
            <span class="model-battery">
              <i :style="{ width: `${group.avgBattery}%` }" />
            </span>
            <span class="model-specs text-mono">
              载重 {{ group.payloadKg.toFixed(1) }}kg · 巡航 {{ group.cruiseMps.toFixed(0) }}m/s ·
              满电 {{ group.rangeKm.toFixed(0) }}km · 标称 {{ group.maxFlightMin }}min
            </span>
          </button>
          <div v-if="!modelGroups.length" class="panel-empty">暂无在册机型</div>
        </div>

        <!-- 能力矩阵 -->
        <div class="matrix-column">
          <div class="panel-label">
            机队能力矩阵
            <span class="panel-note">横轴累计任务 · 纵轴当前电量 · 点大小 = 最大载重</span>
          </div>
          <svg
            v-if="capabilityMatrix"
            class="matrix-svg"
            :viewBox="`0 0 ${capabilityMatrix.width} ${capabilityMatrix.height}`"
            role="img"
            aria-label="机队能力矩阵"
          >
            <!-- 低电量区 -->
            <rect
              :x="capabilityMatrix.padL"
              :y="capabilityMatrix.lowY"
              :width="capabilityMatrix.endX - capabilityMatrix.padL"
              :height="capabilityMatrix.axisY - capabilityMatrix.lowY"
              class="matrix-low-zone"
            />
            <text
              :x="capabilityMatrix.padL + 6"
              :y="capabilityMatrix.lowY - 5"
              class="matrix-zone-label"
            >
              低电量区 &lt; 25%
            </text>

            <!-- 坐标轴与参考线 -->
            <line
              :x1="capabilityMatrix.padL"
              :y1="capabilityMatrix.axisY"
              :x2="capabilityMatrix.endX"
              :y2="capabilityMatrix.axisY"
              class="matrix-axis"
            />
            <line
              :x1="capabilityMatrix.padL"
              :y1="capabilityMatrix.padT"
              :x2="capabilityMatrix.padL"
              :y2="capabilityMatrix.axisY"
              class="matrix-axis"
            />
            <line
              :x1="capabilityMatrix.padL"
              :y1="capabilityMatrix.midY"
              :x2="capabilityMatrix.endX"
              :y2="capabilityMatrix.midY"
              class="matrix-grid"
            />
            <line
              :x1="capabilityMatrix.avgX"
              :y1="capabilityMatrix.padT"
              :x2="capabilityMatrix.avgX"
              :y2="capabilityMatrix.axisY"
              class="matrix-grid dashed"
            />
            <text :x="capabilityMatrix.padL - 8" :y="capabilityMatrix.padT + 4" text-anchor="end" class="matrix-tick">
              100%
            </text>
            <text :x="capabilityMatrix.padL - 8" :y="capabilityMatrix.midY + 4" text-anchor="end" class="matrix-tick">
              50%
            </text>
            <text :x="capabilityMatrix.padL - 8" :y="capabilityMatrix.axisY + 4" text-anchor="end" class="matrix-tick">
              0%
            </text>
            <text :x="capabilityMatrix.padL" :y="capabilityMatrix.height - 10" class="matrix-tick">
              0
            </text>
            <text
              :x="capabilityMatrix.endX"
              :y="capabilityMatrix.height - 10"
              text-anchor="end"
              class="matrix-tick"
            >
              {{ capabilityMatrix.maxTasks }} 单
            </text>
            <text :x="capabilityMatrix.avgX + 4" :y="capabilityMatrix.padT + 10" class="matrix-tick">
              平均 {{ capabilityMatrix.avgTasks.toFixed(0) }} 单
            </text>

            <!-- 每架机一个点 -->
            <circle
              v-for="point in capabilityMatrix.points"
              :key="point.code"
              :cx="point.x"
              :cy="point.y"
              :r="point.r"
              :fill="point.color"
              class="matrix-dot"
            >
              <title>
                {{ point.code }} · {{ point.statusText }} · 电量 {{ point.battery.toFixed(0) }}% · 累计
                {{ point.tasks }} 单
              </title>
            </circle>
          </svg>
          <div v-else class="panel-empty">暂无遥测数据</div>
        </div>
      </div>

      <!-- 调度建议 -->
      <div class="fleet-alerts">
        <span class="alert-label">
          <ThunderboltOutlined />
          调度建议
        </span>
        <span
          v-for="(alert, index) in fleetAlerts"
          :key="index"
          class="alert-item"
          :class="`is-${alert.level}`"
        >
          <WarningOutlined v-if="alert.level !== 'info'" />
          {{ alert.text }}
        </span>
      </div>
    </section>

    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-input
          v-model:value="keyword"
          class="filter"
          placeholder="搜索机身编号 / 机型 / 订单号"
          allow-clear
        />
        <a-select
          v-model:value="modelFilter"
          class="filter-narrow"
          placeholder="全部机型"
          allow-clear
          :options="modelGroups.map((group) => ({ value: group.code, label: group.code }))"
        />
        <a-select
          v-model:value="statusFilter"
          class="filter-narrow"
          placeholder="全部状态"
          allow-clear
          :options="statusOptions"
        />
        <a-button :loading="loading" @click="loadDrones()">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <span class="auto-refresh">
          <a-switch v-model:checked="autoRefresh" size="small" />
          <span class="auto-refresh-label">10 秒同步台账</span>
        </span>
        <a-tag v-if="statusFilter || modelFilter || keyword" class="filter-tag">
          已筛选 {{ filteredDrones.length }} 架
        </a-tag>
      </a-space>

      <template #extra>
        <a-button type="primary" @click="openModal">
          <template #icon><PlusOutlined /></template>
          注册无人机
        </a-button>
      </template>
    </a-card>

    <!-- ------------------------------ 机队卡片网格 ------------------------------ -->
    <a-spin :spinning="loading">
      <div v-if="pagedDrones.length" class="drone-grid">
        <article
          v-for="drone in pagedDrones"
          :key="drone.id"
          class="drone-card"
          :class="`tone-${statusTone(effectiveStatus(drone))}`"
        >
          <header class="card-head">
            <div class="card-no">
              <span class="card-sigil" aria-hidden="true" />
              <span class="text-mono">{{ drone.code }}</span>
            </div>
            <div class="card-tags">
              <a-tag color="blue">{{ drone.modelCode }}</a-tag>
              <a-tag :color="DRONE_STATUS_COLOR[effectiveStatus(drone)]">
                {{ DRONE_STATUS_TEXT[effectiveStatus(drone)] ?? effectiveStatus(drone) }}
              </a-tag>
            </div>
          </header>

          <div class="card-body">
            <!-- 电量环 -->
            <div class="battery-dial" :class="`is-${batteryTone(drone)}`">
              <svg viewBox="0 0 80 80" aria-hidden="true">
                <circle class="dial-track" cx="40" cy="40" r="31" />
                <circle
                  class="dial-value"
                  cx="40"
                  cy="40"
                  r="31"
                  :stroke-dasharray="batteryDash(drone)"
                  transform="rotate(-90 40 40)"
                />
              </svg>
              <span class="dial-number">{{ effectiveBattery(drone).toFixed(0) }}<em>%</em></span>
              <span class="dial-label">电量</span>
            </div>

            <div class="drone-facts">
              <span class="fact-model">
                <em>机型</em>{{ drone.modelName ?? drone.modelCode }}
              </span>
              <span><em>最大载重</em>{{ Number(drone.maxPayloadKg).toFixed(1) }} kg</span>
              <span><em>巡航速度</em>{{ Number(drone.cruiseSpeedMps).toFixed(0) }} m/s</span>
              <span><em>标称续航</em>{{ drone.maxFlightMin ?? '—' }} min</span>
              <span><em>满电航程</em>{{ rangeOf(drone).toFixed(0) }} km</span>
              <span class="fact-home"><em>起降点</em>{{ Number(drone.homeLat).toFixed(4) }}, {{ Number(drone.homeLon).toFixed(4) }}</span>
            </div>
          </div>

          <!-- 实时遥测 -->
          <div v-if="liveOf(drone)" class="live-block">
            <div class="live-row text-mono">
              <span>高度 {{ Number(liveOf(drone)?.altitudeM ?? 0).toFixed(0) }} m</span>
              <span>速度 {{ Number(liveOf(drone)?.speedMps ?? 0).toFixed(1) }} m/s</span>
              <span class="heading-wrap">
                <i
                  class="heading-arrow"
                  :style="{ transform: `rotate(${Number(liveOf(drone)?.headingDeg ?? 0)}deg)` }"
                />
                {{ Number(liveOf(drone)?.headingDeg ?? 0).toFixed(0) }}°
              </span>
              <span class="live-pulse">遥测在线</span>
            </div>
            <div v-if="liveOf(drone)?.taskId" class="mission-line">
              <span class="mission-tag">
                <RocketOutlined />
                任务 #{{ liveOf(drone)?.taskId }}
              </span>
              <span class="text-mono">{{ liveOf(drone)?.orderNo ?? '—' }}</span>
              <span class="mission-progress">
                <i :style="{ width: `${progressOf(drone)}%` }" />
              </span>
              <span class="text-mono">{{ progressOf(drone).toFixed(0) }}%</span>
            </div>
            <div v-else class="mission-idle">当前空闲，等待派单</div>
          </div>
          <div v-else class="live-block is-offline text-mono">
            暂无实时遥测 · 最后心跳 {{ heartbeatText(drone) }}
          </div>

          <!-- 累计战绩 -->
          <div class="drone-totals">
            <div class="total-item">
              <em>累计任务</em>
              <strong>{{ drone.totalTasks ?? 0 }}<i>单</i></strong>
            </div>
            <div class="total-item">
              <em>累计航程</em>
              <strong>{{ formatDistance((drone.totalDistanceKm ?? 0) * 1000) }}</strong>
            </div>
            <div class="total-item">
              <em>累计飞行</em>
              <strong>{{ formatDuration((drone.totalFlightMin ?? 0) * 60) }}</strong>
            </div>
            <div class="total-item">
              <em>距起降点</em>
              <strong>{{ homeDistanceText(drone) }}</strong>
            </div>
          </div>

          <div class="status-note" :class="`is-${batteryTone(drone)}`">
            {{ statusNote(drone) }}
          </div>

          <footer class="card-actions">
            <a-button type="primary" size="small" @click="openHistory(drone)">
              <template #icon><HistoryOutlined /></template>
              任务记录
            </a-button>
            <a-button size="small" @click="openModel3d(drone)">
              <template #icon><AimOutlined /></template>
              整机解析
            </a-button>
            <a-popconfirm
              title="确认变更该无人机状态？变更后会立即生效。"
              ok-text="确认变更"
              cancel-text="取消"
              :disabled="draftOf(drone) === drone.status"
              @confirm="confirmStatusChange(drone)"
              @cancel="cancelStatusChange(drone)"
            >
              <a-select
                class="status-select"
                size="small"
                :value="draftOf(drone)"
                :options="statusOptions"
                :disabled="drone.status === 'OFFLINE'"
                @change="(value: unknown) => stageStatus(drone, value as DroneStatus)"
              />
            </a-popconfirm>
          </footer>
        </article>
      </div>

      <a-empty
        v-else
        class="drones-empty"
        description="当前筛选下没有无人机；可调整筛选或点击右上角「注册无人机」"
      />
    </a-spin>

    <div v-if="filteredDrones.length > 0" class="pagination-bar">
      <a-pagination
        :current="page"
        :page-size="pageSize"
        :total="filteredDrones.length"
        :page-size-options="['6', '12', '24', '48']"
        show-size-changer
        :show-total="(total: number) => `共 ${total} 架`"
        @change="handlePageChange"
      />
    </div>

    <!-- ------------------------------ 任务履历抽屉 ------------------------------ -->
    <a-drawer v-model:open="historyOpen" width="820" class="history-drawer">
      <template #title>
        <div class="history-title">
          <HistoryOutlined class="history-title-icon" />
          <span class="text-mono">{{ historyDrone?.code ?? '任务履历' }}</span>
          <span class="history-title-sub">
            {{ historyDrone?.modelCode }} · {{ historyDrone?.modelName }}
          </span>
        </div>
      </template>

      <a-spin :spinning="historyLoading">
        <template v-if="historyDrone">
          <div class="history-banner">
            <div class="history-banner-top">
              <a-tag :color="DRONE_STATUS_COLOR[effectiveStatus(historyDrone)]">
                {{ DRONE_STATUS_TEXT[effectiveStatus(historyDrone)] }}
              </a-tag>
              <span class="history-battery text-mono">
                电量 {{ effectiveBattery(historyDrone).toFixed(0) }}%
              </span>
              <span class="history-home text-mono">
                起降点 {{ Number(historyDrone.homeLat).toFixed(4) }}, {{ Number(historyDrone.homeLon).toFixed(4) }}
              </span>
            </div>
            <div class="history-totals">
              <div><em>累计任务</em><strong>{{ historyDrone.totalTasks ?? 0 }} 单</strong></div>
              <div>
                <em>累计航程</em>
                <strong>{{ formatDistance((historyDrone.totalDistanceKm ?? 0) * 1000) }}</strong>
              </div>
              <div>
                <em>累计飞行</em>
                <strong>{{ formatDuration((historyDrone.totalFlightMin ?? 0) * 60) }}</strong>
              </div>
              <div><em>最后心跳</em><strong>{{ heartbeatText(historyDrone) }}</strong></div>
            </div>
          </div>

          <a-empty v-if="!historyTasks.length" description="该机还没有执行记录" />
          <div v-else class="history-list">
            <div v-for="task in historyTasks" :key="task.id" class="history-item">
              <header class="history-item-head">
                <span class="text-mono history-task-no">{{ task.taskNo }}</span>
                <a-tag :color="TASK_STATUS_COLOR[task.status]">
                  {{ TASK_STATUS_TEXT[task.status] ?? task.status }}
                </a-tag>
                <button type="button" class="history-order" @click="gotoOrderTrace(task)">
                  <LinkOutlined />
                  <span class="text-mono">{{ task.orderNo ?? `订单 #${task.orderId}` }}</span>
                </button>
                <span class="history-time text-mono">{{ formatDateTime(task.assignedAt) }}</span>
              </header>
              <div class="history-facts">
                <span><em>航程</em>{{ formatDistance(task.actualDistanceM) }} / {{ formatDistance(task.plannedDistanceM) }}</span>
                <span><em>能耗</em>{{ formatEnergy(task.consumedEnergyWh) }} / {{ formatEnergy(task.plannedEnergyWh) }}</span>
                <span><em>耗时</em>{{ formatDuration(task.durationS) }}</span>
                <span>
                  <em>电量</em>{{ formatPercent(task.batteryStartPct, 0) }} → {{ formatPercent(task.batteryEndPct, 0) }}
                </span>
              </div>
              <div v-if="task.failReason" class="history-fail">{{ task.failReason }}</div>
            </div>
          </div>
        </template>
      </a-spin>

      <template #footer>
        <a-button type="primary" @click="gotoDroneTasks(historyDrone)">
          <template #icon><RocketOutlined /></template>
          在任务页查看该机全部任务
        </a-button>
      </template>
    </a-drawer>

    <!-- ------------------------------ 注册无人机 ------------------------------ -->
    <a-modal
      v-model:open="modalOpen"
      title="注册无人机"
      :width="720"
      ok-text="提交注册"
      cancel-text="取消"
      :confirm-loading="submitting"
      :mask-closable="false"
      @ok="submitRegister"
    >
      <a-form layout="vertical" class="register-form">
        <a-form-item label="机身编号（可留空）">
          <a-input v-model:value="form.code" placeholder="留空则自动生成，如 SKY-DR-0001" allow-clear />
          <div class="field-hint">留空由系统自动编号；需要沿用自有命名规范时才填写</div>
        </a-form-item>

        <a-form-item label="机型" required>
          <a-select v-model:value="form.modelCode" :options="modelCodeOptions" />
        </a-form-item>

        <a-form-item v-if="selectedModelSpec && rangeInfo" label="该机型能力（注册前先确认能覆盖多远）">
          <div class="model-spec">
            <div class="spec-row">
              <span class="spec-label">最大载重</span>
              <span class="spec-value">{{ Number(selectedModelSpec.maxPayloadKg).toFixed(1) }} kg</span>
            </div>
            <div class="spec-row">
              <span class="spec-label">巡航速度</span>
              <span class="spec-value">{{ Number(selectedModelSpec.cruiseSpeedMps).toFixed(1) }} m/s</span>
            </div>
            <div class="spec-row">
              <span class="spec-label">电池 / 能耗</span>
              <span class="spec-value">
                {{ Number(selectedModelSpec.batteryCapacityWh).toFixed(0) }} Wh /
                {{ Number(selectedModelSpec.energyPerKmWh).toFixed(0) }} Wh/km
              </span>
            </div>
            <div class="spec-row spec-row-key">
              <span class="spec-label">满电航程</span>
              <span class="spec-value">≈ {{ rangeInfo.fullKm.toFixed(0) }} km（单程，理论值）</span>
            </div>
            <div class="spec-row spec-row-key">
              <span class="spec-label">可用往返半径</span>
              <span class="spec-value">
                ≈ {{ rangeInfo.roundTripKm.toFixed(1) }} km
                <span class="spec-note">
                  （扣除 {{ (RESERVE_RATIO * 100).toFixed(0) }}% 安全冗余后往返可覆盖的单程距离；
                  超出这个半径的任务需要中途到充电站补能）
                </span>
              </span>
            </div>
            <div class="spec-row">
              <span class="spec-label">标称最长飞行</span>
              <span class="spec-value">{{ rangeInfo.hoverMinutes }} 分钟</span>
            </div>
          </div>
        </a-form-item>

        <a-form-item label="初始电量（%）">
          <a-input-number v-model:value="form.batteryPct" :min="0" :max="100" :step="1" class="num" />
        </a-form-item>

        <a-form-item label="起降点（Home）">
          <PointPickerMap
            v-model:lat="form.homeLat"
            v-model:lon="form.homeLon"
            label="起降点"
            :zones="zones"
            height="280px"
          />
          <div class="geo-row">
            <a-input-number
              v-model:value="form.homeLat"
              :precision="6"
              :step="0.000001"
              placeholder="纬度"
              class="num"
              addon-before="纬度"
            />
            <a-input-number
              v-model:value="form.homeLon"
              :precision="6"
              :step="0.000001"
              placeholder="经度"
              class="num"
              addon-before="经度"
            />
          </div>
          <div class="field-hint">地图点选为主，数字框用于精确微调；点到的位置最好落在机巢/起降场附近</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ------------------------------ 整机解析弹窗 ------------------------------ -->
    <a-modal
      v-model:open="model3dOpen"
      :title="`整机解析 · ${model3dDrone?.code ?? ''}`"
      :width="1240"
      :footer="null"
      centered
      destroy-on-close
    >
      <div class="detail-meta">
        <a-tag color="blue">{{ model3dDrone?.modelCode }}</a-tag>
        <span class="meta-name">{{ model3dDrone?.modelName ?? '—' }}</span>
        <i class="meta-sep" />
        <span>最大载重 <b>{{ Number(model3dDrone?.maxPayloadKg ?? 0).toFixed(1) }} kg</b></span>
        <i class="meta-sep" />
        <a-tag :color="DRONE_STATUS_COLOR[model3dDrone?.status as DroneStatus]">
          {{ DRONE_STATUS_TEXT[model3dDrone?.status as DroneStatus] ?? model3dDrone?.status }}
        </a-tag>
        <span>电量 <b>{{ formatPercent(model3dDrone?.batteryPct, 0) }}</b></span>
        <span class="meta-tail">机电参数与机型台账（drone_model）一致</span>
      </div>

      <a-tabs v-model:activeKey="detailTab" size="small" class="detail-tabs">
        <a-tab-pane key="anatomy" tab="整机结构图谱">
          <div class="anatomy-wrap">
            <DroneAnatomy :model-code="model3dDrone?.modelCode" :tail-number="model3dDrone?.code" />
          </div>
        </a-tab-pane>

        <a-tab-pane key="load" tab="装载过程演示">
          <DroneModel3D class="model3d-stage" :model-code="model3dDrone?.modelCode" />
          <a-alert
            type="info"
            show-icon
            class="model3d-alert"
            message="装载过程演示"
            description="演示无人机在取货点抓取货物并起飞的完整流程：待命悬停 → 垂直下降 → 抓取货物 → 载荷锁定 → 载货起飞。实际任务中，只有通过载重与往返电量校验的无人机才会被派来执行装载。"
          />
        </a-tab-pane>
      </a-tabs>
    </a-modal>
  </div>
</template>

<style scoped>
.drones-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.filter {
  width: 250px;
}

.filter-narrow {
  width: 150px;
}

.auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.auto-refresh-label {
  font-size: 12px;
  color: rgba(150, 199, 232, 0.68);
}

.filter-tag {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
}

/* =============================================================================
   机队战备态势
   ============================================================================= */
.fleet {
  position: relative;
  padding: 14px 16px 12px;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.18);
  border-radius: 8px;
  background:
    radial-gradient(680px 200px at 12% -30%, rgba(56, 232, 255, 0.14), transparent 70%),
    linear-gradient(180deg, rgba(13, 32, 50, 0.92), rgba(6, 17, 29, 0.92));
  box-shadow:
    inset 0 0 0 1px rgba(56, 232, 255, 0.04),
    0 10px 26px rgba(0, 0, 0, 0.4);
}

.fleet::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 26%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  animation: fleet-scan 8s linear infinite;
}

@keyframes fleet-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(480%);
  }
}

.fleet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px 20px;
  margin-bottom: 10px;
}

.fleet-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: #e9fbff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.3);
}

.fleet-led {
  width: 6px;
  height: 6px;
  background: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  transform: rotate(45deg);
  animation: fleet-led 1.8s ease-in-out infinite;
}

@keyframes fleet-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.fleet-live {
  padding: 0 6px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #8bffdc;
  border: 1px solid rgba(47, 214, 165, 0.5);
  background: rgba(47, 214, 165, 0.1);
  clip-path: polygon(4px 0, 100% 0, 100% calc(100% - 4px), calc(100% - 4px) 100%, 0 100%, 0 4px);
}

.fleet-sub {
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.62);
}

.fleet-headline {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}

/* 战备率环 */
.readiness-ring {
  width: 88px;
  height: 88px;
  flex: 0 0 auto;
}

.ring-track {
  fill: none;
  stroke: rgba(56, 232, 255, 0.12);
  stroke-width: 6;
}

.ring-value {
  fill: none;
  stroke: #38e8ff;
  stroke-width: 6;
  stroke-linecap: round;
  filter: drop-shadow(0 0 6px rgba(56, 232, 255, 0.8));
  transition: stroke-dasharray 0.5s ease;
}

.ring-number {
  fill: #eafcff;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 19px;
  font-weight: 700;
}

.ring-label {
  fill: rgba(150, 199, 232, 0.65);
  font-size: 10px;
  letter-spacing: 0.5px;
}

.fleet-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
}

.metric {
  display: flex;
  flex-direction: column;
  min-width: 62px;
}

.metric em {
  font-size: 10px;
  font-style: normal;
  letter-spacing: 0.3px;
  color: rgba(150, 199, 232, 0.55);
}

.metric strong {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 17px;
  font-weight: 600;
  color: #d8f7ff;
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.35);
  font-variant-numeric: tabular-nums;
}

.metric strong i {
  margin-left: 2px;
  font-size: 10px;
  font-style: normal;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.55);
  text-shadow: none;
}

.metric strong.danger {
  color: #ffb4b4;
  text-shadow: 0 0 10px rgba(255, 96, 96, 0.5);
}

/* 状态分布条 */
.status-bar {
  display: flex;
  width: 100%;
  height: 5px;
  margin: 4px 0 8px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.06);
}

.status-seg {
  height: 100%;
  box-shadow: 0 0 8px currentColor;
  transition: width 0.45s ease;
}

.status-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.status-chip {
  --chip-color: #38e8ff;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  font-size: 11px;
  cursor: pointer;
  color: rgba(216, 240, 255, 0.82);
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(8, 21, 35, 0.55);
  transition: border-color 0.18s ease, color 0.18s ease, background-color 0.18s ease;
}

.status-chip::before {
  content: '';
  width: 4px;
  height: 4px;
  background: var(--chip-color);
  box-shadow: 0 0 6px var(--chip-color);
  transform: rotate(45deg);
}

.status-chip b {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 600;
  color: #dffbff;
}

.status-chip:hover {
  border-color: rgba(56, 232, 255, 0.42);
  color: #7ff0ff;
}

.status-chip.active {
  border-color: var(--chip-color);
  background: color-mix(in srgb, var(--chip-color) 14%, transparent);
  box-shadow: 0 0 12px color-mix(in srgb, var(--chip-color) 30%, transparent);
}

/* 机型编队 + 能力矩阵 */
.fleet-lower {
  display: grid;
  grid-template-columns: minmax(260px, 0.82fr) minmax(0, 1.35fr);
  gap: 16px;
  align-items: start;
}

.panel-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: rgba(216, 240, 255, 0.9);
}

.panel-label::before {
  content: '';
  width: 3px;
  height: 11px;
  background: #38e8ff;
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.9);
}

.panel-note {
  font-size: 10px;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.45);
}

.model-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.model-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 7px 10px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  border: 1px solid rgba(56, 232, 255, 0.12);
  background: rgba(8, 21, 35, 0.55);
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.model-card:hover {
  border-color: rgba(56, 232, 255, 0.4);
}

.model-card.active {
  border-color: #38e8ff;
  background: rgba(56, 232, 255, 0.08);
  box-shadow: inset 0 0 18px rgba(56, 232, 255, 0.08);
}

.model-head {
  display: flex;
  align-items: center;
  gap: 7px;
}

.model-code {
  font-size: 12px;
  font-weight: 600;
  color: #d9f4ff;
}

.model-name {
  font-size: 11px;
  color: rgba(150, 199, 232, 0.6);
}

.model-count {
  margin-left: auto;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  color: rgba(159, 240, 255, 0.8);
}

.model-row {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.5);
}

.model-row b {
  color: #cfeaff;
}

.model-battery {
  display: block;
  height: 2px;
  margin: 2px 0 1px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.1);
}

.model-battery i {
  display: block;
  height: 100%;
  background: #2fd6a5;
  box-shadow: 0 0 6px rgba(47, 214, 165, 0.7);
}

.model-specs {
  font-size: 10px;
  color: rgba(150, 199, 232, 0.42);
}

.panel-empty {
  padding: 20px;
  text-align: center;
  font-size: 12px;
  color: rgba(150, 199, 232, 0.45);
  border: 1px dashed rgba(56, 232, 255, 0.14);
}

/* 能力矩阵 SVG */
.matrix-svg {
  display: block;
  width: 100%;
  height: 220px;
}

.matrix-axis {
  stroke: rgba(56, 232, 255, 0.25);
  stroke-width: 1;
}

.matrix-grid {
  stroke: rgba(56, 232, 255, 0.12);
  stroke-width: 1;
  stroke-dasharray: 3 4;
}

.matrix-low-zone {
  fill: rgba(255, 96, 96, 0.06);
}

.matrix-zone-label {
  fill: rgba(255, 154, 154, 0.55);
  font-size: 9px;
}

.matrix-tick {
  fill: rgba(150, 199, 232, 0.45);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 9px;
}

.matrix-dot {
  opacity: 0.85;
  stroke: rgba(234, 252, 255, 0.35);
  stroke-width: 0.8;
  transition: opacity 0.18s ease;
}

.matrix-dot:hover {
  opacity: 1;
  stroke: #eafcff;
  stroke-width: 1.6;
}

/* 调度建议 */
.fleet-alerts {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px 12px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid rgba(56, 232, 255, 0.1);
  font-size: 11px;
}

.alert-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: rgba(150, 199, 232, 0.6);
}

.alert-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  color: rgba(216, 240, 255, 0.82);
  border: 1px solid rgba(56, 232, 255, 0.14);
  background: rgba(56, 232, 255, 0.04);
}

.alert-item.is-warning {
  color: #ffe0b0;
  border-color: rgba(240, 169, 59, 0.35);
  background: rgba(240, 169, 59, 0.08);
}

.alert-item.is-danger {
  color: #ffc2c2;
  border-color: rgba(255, 96, 96, 0.35);
  background: rgba(255, 96, 96, 0.08);
}

/* =============================================================================
   机队卡片
   ============================================================================= */
.drone-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.drone-card {
  --card-tone: #38e8ff;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.16);
  border-left: 3px solid var(--card-tone);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(13, 32, 50, 0.9), rgba(7, 19, 32, 0.9));
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.38);
  transition:
    transform 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.drone-card:hover {
  transform: translateY(-2px);
  border-color: rgba(56, 232, 255, 0.42);
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.46),
    0 0 16px rgba(56, 232, 255, 0.14);
}

.drone-card::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 54px;
  height: 54px;
  pointer-events: none;
  background: repeating-linear-gradient(
    135deg,
    rgba(56, 232, 255, 0.18) 0 1px,
    transparent 1px 6px
  );
  mask-image: linear-gradient(225deg, #000, transparent 72%);
  opacity: 0.5;
}

.drone-card.tone-success {
  --card-tone: #2fd6a5;
}
.drone-card.tone-active {
  --card-tone: #38e8ff;
}
.drone-card.tone-returning {
  --card-tone: #fa8c16;
}
.drone-card.tone-charging {
  --card-tone: #13c2c2;
}
.drone-card.tone-warning {
  --card-tone: #f0a93b;
}
.drone-card.tone-muted {
  --card-tone: #6b8299;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-no {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  font-size: 13px;
  color: #d9f4ff;
}

.card-sigil {
  flex: 0 0 auto;
  width: 5px;
  height: 5px;
  background: var(--card-tone);
  box-shadow: 0 0 7px var(--card-tone);
  transform: rotate(45deg);
}

.card-no .text-mono {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.card-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
}

.card-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 电量环 */
.battery-dial {
  position: relative;
  flex: 0 0 auto;
  width: 80px;
  height: 80px;
}

.battery-dial svg {
  display: block;
  width: 80px;
  height: 80px;
}

.dial-track {
  fill: none;
  stroke: rgba(56, 232, 255, 0.1);
  stroke-width: 6;
}

.dial-value {
  fill: none;
  stroke: #2fd6a5;
  stroke-width: 6;
  stroke-linecap: round;
  filter: drop-shadow(0 0 5px rgba(47, 214, 165, 0.7));
  transition: stroke-dasharray 0.5s linear;
}

.battery-dial.is-warning .dial-value {
  stroke: #f0a93b;
  filter: drop-shadow(0 0 5px rgba(240, 169, 59, 0.7));
}

.battery-dial.is-danger .dial-value {
  stroke: #ff6060;
  filter: drop-shadow(0 0 6px rgba(255, 96, 96, 0.85));
  animation: dial-pulse 1.6s ease-in-out infinite;
}

@keyframes dial-pulse {
  0%,
  100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
}

.dial-number {
  position: absolute;
  left: 0;
  right: 0;
  top: 27px;
  text-align: center;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 17px;
  font-weight: 700;
  color: #eafcff;
  text-shadow: 0 0 12px rgba(56, 232, 255, 0.35);
}

.dial-number em {
  font-size: 9px;
  font-style: normal;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.6);
}

.battery-dial.is-danger .dial-number {
  color: #ffc2c2;
}

.dial-label {
  position: absolute;
  left: 0;
  right: 0;
  top: 49px;
  text-align: center;
  font-size: 9px;
  letter-spacing: 0.5px;
  color: rgba(150, 199, 232, 0.5);
}

.drone-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 10px;
  min-width: 0;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(216, 240, 255, 0.86);
}

.drone-facts em {
  display: inline-block;
  min-width: 46px;
  margin-right: 4px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.5);
}

.fact-model {
  grid-column: span 2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fact-home {
  grid-column: span 2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 实时遥测 */
.live-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 5px 8px;
  font-size: 10px;
  color: rgba(159, 240, 255, 0.82);
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(56, 232, 255, 0.05);
}

.live-block.is-offline {
  color: rgba(150, 199, 232, 0.42);
  border-color: rgba(150, 199, 232, 0.1);
  background: rgba(150, 199, 232, 0.03);
}

.live-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 3px 12px;
}

.live-pulse {
  margin-left: auto;
  color: #2fd6a5;
}

.live-pulse::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 4px;
  margin-right: 4px;
  border-radius: 50%;
  background: #2fd6a5;
  box-shadow: 0 0 6px rgba(47, 214, 165, 0.9);
  animation: live-blink 1.6s ease-in-out infinite;
}

@keyframes live-blink {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

/* 航向小箭头：随遥测旋转，像仪表上的航向指示 */
.heading-wrap {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.heading-arrow {
  display: inline-block;
  width: 0;
  height: 0;
  border-left: 3px solid transparent;
  border-right: 3px solid transparent;
  border-bottom: 7px solid #9ff0ff;
  transform-origin: 50% 60%;
  filter: drop-shadow(0 0 4px rgba(56, 232, 255, 0.7));
  transition: transform 0.4s linear;
}

.mission-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
}

.mission-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0 5px;
  color: #9ff0ff;
  border: 1px solid rgba(56, 232, 255, 0.2);
}

.mission-progress {
  position: relative;
  flex: 1 1 auto;
  height: 3px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.12);
}

.mission-progress i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #1677ff, #38e8ff);
  box-shadow: 0 0 6px rgba(56, 232, 255, 0.8);
  transition: width 0.5s linear;
}

.mission-idle {
  font-size: 10px;
  color: rgba(150, 199, 232, 0.45);
}

/* 累计战绩 */
.drone-totals {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  padding: 6px 0;
  border-top: 1px solid rgba(56, 232, 255, 0.08);
  border-bottom: 1px solid rgba(56, 232, 255, 0.08);
}

.total-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.total-item em {
  font-size: 9px;
  font-style: normal;
  letter-spacing: 0.3px;
  color: rgba(150, 199, 232, 0.48);
}

.total-item strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  color: rgba(216, 240, 255, 0.92);
}

.total-item strong i {
  margin-left: 1px;
  font-size: 9px;
  font-style: normal;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.5);
}

.status-note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.6);
}

.status-note.is-warning {
  color: rgba(240, 169, 59, 0.9);
}

.status-note.is-danger {
  color: #ff9a9a;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: auto;
  padding-top: 7px;
  border-top: 1px solid rgba(56, 232, 255, 0.1);
}

.status-select {
  width: 116px;
}

.drones-empty {
  padding: 60px 0;
  border: 1px dashed rgba(56, 232, 255, 0.18);
  background: rgba(7, 19, 32, 0.4);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 2px 4px 8px;
}

/* =============================================================================
   任务履历抽屉
   ============================================================================= */
.history-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.history-title-icon {
  color: #38e8ff;
  filter: drop-shadow(0 0 6px rgba(56, 232, 255, 0.8));
}

.history-title .text-mono {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #eafcff;
}

.history-title-sub {
  margin-left: 4px;
  padding-left: 8px;
  border-left: 1px solid rgba(56, 232, 255, 0.25);
  font-size: 12px;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.6);
}

.history-banner {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
  border: 1px solid rgba(56, 232, 255, 0.18);
  background:
    radial-gradient(480px 140px at 8% -40%, rgba(56, 232, 255, 0.12), transparent 70%),
    linear-gradient(180deg, rgba(14, 35, 55, 0.9), rgba(7, 19, 32, 0.9));
  clip-path: polygon(10px 0, 100% 0, 100% calc(100% - 10px), calc(100% - 10px) 100%, 0 100%, 0 10px);
}

.history-banner-top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: rgba(216, 240, 255, 0.84);
}

.history-battery {
  color: #9ff0ff;
}

.history-home {
  margin-left: auto;
  color: rgba(150, 199, 232, 0.55);
}

.history-totals {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.history-totals div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 8px;
  border: 1px solid rgba(56, 232, 255, 0.1);
  background: rgba(8, 21, 35, 0.55);
}

.history-totals em {
  font-size: 10px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.55);
}

.history-totals strong {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  color: #dffbff;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-item {
  padding: 9px 11px;
  border: 1px solid rgba(56, 232, 255, 0.12);
  background: rgba(8, 21, 35, 0.55);
}

.history-item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 5px;
}

.history-task-no {
  font-size: 12px;
  font-weight: 600;
  color: #d9f4ff;
}

.history-order {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  font-size: 11px;
  cursor: pointer;
  color: #9fd9f5;
  border: 0;
  background: transparent;
}

.history-order:hover {
  color: #7ff0ff;
}

.history-time {
  margin-left: auto;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.45);
}

.history-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 3px 14px;
  font-size: 11px;
  color: rgba(216, 240, 255, 0.8);
}

.history-facts em {
  margin-right: 3px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.5);
}

.history-fail {
  margin-top: 5px;
  padding: 3px 7px;
  font-size: 11px;
  color: #ffb4b4;
  border: 1px solid rgba(255, 96, 96, 0.3);
  background: rgba(255, 96, 96, 0.08);
}

/* =============================================================================
   注册 / 整机解析（沿用原样式）
   ============================================================================= */
.register-form {
  margin-top: 8px;
}

.geo-row {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

.model-spec {
  padding: 8px 10px;
  border-radius: 6px;
  background: #0c1b2c;
  border: 1px solid #1d3a55;
  font-size: 12px;
  line-height: 1.9;
}

.spec-row {
  display: flex;
  gap: 8px;
}

.spec-row-key {
  background: rgba(34, 211, 238, 0.1);
  margin: 0 -10px;
  padding: 0 10px;
  font-weight: 600;
  color: #4fc3ff;
}

.spec-label {
  flex: 0 0 96px;
  color: rgba(180, 208, 232, 0.55);
}

.spec-row-key .spec-label {
  color: #4fc3ff;
}

.spec-value {
  color: #d8e8fb;
}

.spec-note {
  font-weight: 400;
  color: rgba(180, 208, 232, 0.55);
}

.num {
  width: 100%;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: -4px 0 0;
  font-size: 12px;
  color: rgba(190, 216, 240, 0.78);
}

.detail-meta b {
  font-weight: 600;
  color: #d8e8fb;
}

.meta-name {
  font-weight: 600;
  color: #d8e8fb;
}

.meta-sep {
  width: 1px;
  height: 12px;
  background: #1d3a55;
}

.meta-tail {
  margin-left: auto;
  font-size: 11px;
  color: rgba(180, 208, 232, 0.38);
}

.detail-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 8px;
}

.anatomy-wrap {
  height: min(620px, calc(100vh - 330px));
  min-height: 440px;
}

.model3d-stage {
  height: 460px;
  margin-bottom: 12px;
}

.model3d-alert {
  border-radius: 8px;
}

@media (max-width: 1000px) {
  .fleet-lower {
    grid-template-columns: 1fr;
  }

  .drone-totals {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .history-totals {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .fleet::after,
  .fleet-led,
  .battery-dial.is-danger .dial-value,
  .live-pulse::before {
    animation: none;
  }
}
</style>
