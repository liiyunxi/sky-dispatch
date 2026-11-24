<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { useRoute, useRouter } from 'vue-router'
import {
  AimOutlined,
  CopyOutlined,
  DownloadOutlined,
  LinkOutlined,
  ReloadOutlined,
  RocketOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'
import {
  reportApi,
  taskApi,
  type DispatchTask,
  type DroneFrame,
  type EventLogItem,
  type TaskStatus,
  type TaskStatusStats,
  type Waypoint,
} from '@/api'
import { useTelemetry } from '@/store/telemetry'
import {
  STRATEGY_TEXT,
  TASK_STATUS_COLOR,
  TASK_STATUS_TEXT,
  eventTypeText,
  formatDateTime,
  formatDistance,
  formatDuration,
  formatEnergy,
  formatPercent,
  formatRate,
} from '@/utils/format'

/**
 * 飞行任务指挥台。
 *
 * 任务页原来的问题是"只能翻表格"：任务号、状态、航程一堆数字，看不出任务飞到哪、
 * 电量还剩多少、异常卡在哪个阶段。改造后分三层：
 *
 *  1. **任务态势流水线**：待起飞 / 巡航投送 / 返航补能 / 已完成 / 异常，五段计数
 *     来自一次 GROUP BY 聚合，点任意阶段即可筛选。
 *  2. **任务卡片**：每张卡把「无人机 + 阶段轨 + 实时遥测 + 能耗 + 航程 + 耗时」
 *     放到一屏，执行中的任务随 WebSocket 遥测实时刷新进度。
 *  3. **任务下钻抽屉**：飞行剖面（高度/航程曲线）、事件时间线、航路点、任务参数，
 *     并支持"跟随实时"（5 秒刷新任务与事件）以及一键跳到订单全链路。
 *
 * 原能力（筛选、中止、航路点、事件、导出遥测/事件 CSV）全部保留。
 */

const router = useRouter()
const route = useRoute()
const { drones, kpi } = useTelemetry()

const loading = ref(false)
const statsLoading = ref(false)
const stats = ref<TaskStatusStats | null>(null)

const tasks = ref<DispatchTask[]>([])
const pagination = reactive({ current: 1, pageSize: 9, total: 0 })
const query = reactive<{ status?: TaskStatus; droneId: number | null }>({ droneId: null })

// ---------------------------------------------------------------------------
// 实时遥测
// ---------------------------------------------------------------------------

/** taskId → 最新遥测帧：卡片与抽屉都用它显示"此刻飞到哪里" */
const frameByTaskId = computed(() => {
  const map = new Map<number, DroneFrame>()
  drones.value.forEach((frame) => {
    if (frame.taskId) map.set(frame.taskId, frame)
  })
  return map
})

function liveOf(task: DispatchTask): DroneFrame | undefined {
  if (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'ABORTED') {
    return undefined
  }
  return frameByTaskId.value.get(task.id)
}

/** 无人机下拉：直接按机身编号选，比让用户填数据库 id 直观 */
const droneOptions = computed(() => {
  const map = new Map<number, string>()
  drones.value.forEach((frame) => map.set(frame.droneId, frame.droneCode))
  return Array.from(map.entries())
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label))
})

// ---------------------------------------------------------------------------
// 任务态势流水线
// ---------------------------------------------------------------------------

type StageKey = 'ready' | 'flying' | 'returning' | 'completed' | 'failed'

interface MissionStage {
  key: StageKey
  index: string
  label: string
  hint: string
  color: string
  statuses: TaskStatus[]
  count: number
}

const activeStage = ref<StageKey | undefined>(undefined)

const missionStages = computed<MissionStage[]>(() => {
  const by = stats.value?.byStatus ?? {}
  const sum = (statuses: TaskStatus[]) =>
    statuses.reduce((total, status) => total + Number(by[status] ?? 0), 0)
  return [
    {
      key: 'ready',
      index: '01',
      label: '待起飞',
      hint: '已派单 / 起飞中',
      color: '#f0a93b',
      statuses: ['PENDING', 'ASSIGNED', 'TAKEOFF'],
      count: sum(['PENDING', 'ASSIGNED', 'TAKEOFF']),
    },
    {
      key: 'flying',
      index: '02',
      label: '巡航投送',
      hint: '空中执行任务',
      color: '#38e8ff',
      statuses: ['CRUISING', 'DELIVERING'],
      count: sum(['CRUISING', 'DELIVERING']),
    },
    {
      key: 'returning',
      index: '03',
      label: '返航补能',
      hint: '返航 / 在站补能',
      color: '#b37feb',
      statuses: ['RETURNING', 'CHARGING'],
      count: sum(['RETURNING', 'CHARGING']),
    },
    {
      key: 'completed',
      index: '04',
      label: '已完成',
      hint: '正常交付',
      color: '#2fd6a5',
      statuses: ['COMPLETED'],
      count: sum(['COMPLETED']),
    },
    {
      key: 'failed',
      index: '05',
      label: '异常',
      hint: '失败 / 人工中止',
      color: '#ff6060',
      statuses: ['FAILED', 'ABORTED'],
      count: sum(['FAILED', 'ABORTED']),
    },
  ]
})

function stagePercent(stage: MissionStage): number {
  const total = Number(stats.value?.total ?? 0)
  if (!Number.isFinite(total) || total <= 0) return 0
  return Math.min(100, Math.round((stage.count / total) * 100))
}

function toggleStage(stage: MissionStage) {
  activeStage.value = activeStage.value === stage.key ? undefined : stage.key
  query.status = undefined
  pagination.current = 1
  void loadTasks()
}

function onStatusFilterChange() {
  activeStage.value = undefined
  pagination.current = 1
  void loadTasks()
}

/** 完成率：已完成 / 全部任务 */
const completionRate = computed(() => {
  const total = Number(stats.value?.total ?? 0)
  if (!total) return null
  return Number(stats.value?.byStatus?.['COMPLETED'] ?? 0) / total
})

// ---------------------------------------------------------------------------
// 查询（含自动刷新）
// ---------------------------------------------------------------------------

const autoRefresh = ref(true)
let refreshTimer: number | null = null

async function loadTasks(options: { silent?: boolean } = {}): Promise<void> {
  if (!options.silent) loading.value = true
  try {
    const stage = missionStages.value.find((item) => item.key === activeStage.value)
    const page = await taskApi.list({
      status: query.status,
      statuses: stage?.statuses.join(','),
      droneId: query.droneId ?? undefined,
      // ⚠️ 后端 page 从 0 开始：这里以前直接传 current，导致第 1 页永远看不到
      page: pagination.current - 1,
      size: pagination.pageSize,
    })
    tasks.value = page.content ?? []
    pagination.total = page.totalElements ?? 0
  } catch {
    // client.ts 已统一提示；保留上一次数据，避免自动刷新失败时页面被清空
  } finally {
    if (!options.silent) loading.value = false
  }
}

async function loadStats(options: { silent?: boolean } = {}): Promise<void> {
  if (!options.silent) statsLoading.value = true
  try {
    stats.value = await taskApi.stats()
  } catch {
    /* 统计失败不影响列表 */
  } finally {
    if (!options.silent) statsLoading.value = false
  }
}

function reloadAll(): void {
  void loadTasks()
  void loadStats()
}

function handleFilterChange(): void {
  pagination.current = 1
  void loadTasks()
}

function handlePageChange(page: number, pageSize: number): void {
  pagination.current = page
  pagination.pageSize = pageSize
  void loadTasks()
}

function startAutoRefresh(): void {
  stopAutoRefresh()
  refreshTimer = window.setInterval(() => {
    if (!autoRefresh.value) return
    void loadTasks({ silent: true })
    void loadStats({ silent: true })
  }, 5000)
}

function stopAutoRefresh(): void {
  if (refreshTimer !== null) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
}

// ---------------------------------------------------------------------------
// 卡片展示：阶段轨 / 进度 / 能耗 / 耗时
// ---------------------------------------------------------------------------

type Tone = 'pending' | 'active' | 'returning' | 'success' | 'danger' | 'muted'

function statusTone(status: TaskStatus): Tone {
  switch (status) {
    case 'PENDING':
    case 'ASSIGNED':
    case 'TAKEOFF':
      return 'pending'
    case 'CRUISING':
    case 'DELIVERING':
      return 'active'
    case 'RETURNING':
    case 'CHARGING':
      return 'returning'
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'muted'
  }
}

interface RailStep {
  label: string
  state: 'done' | 'active' | 'todo' | 'error'
}

const RAIL_LABELS = ['派单', '起飞', '巡航投送', '返航/完成']

/** 任务当前处在第几段（0~4，4 表示全部完成） */
function statusStep(status: TaskStatus): number {
  switch (status) {
    case 'PENDING':
    case 'ASSIGNED':
      return 0
    case 'TAKEOFF':
      return 1
    case 'CRUISING':
    case 'DELIVERING':
    case 'CHARGING':
      return 2
    case 'RETURNING':
      return 3
    case 'COMPLETED':
      return 4
    default:
      return 2
  }
}

/**
 * 阶段轨：失败/中止的任务把红色节点落在"实际卡住的那一段"。
 * 没起飞就中止落在起飞前，已经飞了一段则按进度落到巡航或返航。
 */
function missionRail(task: DispatchTask): RailStep[] {
  const terminalError = task.status === 'FAILED' || task.status === 'ABORTED'
  let current = statusStep(task.status)
  if (terminalError) {
    current = !task.startedAt ? 1 : Number(task.progressPct ?? 0) >= 70 ? 3 : 2
  }
  return RAIL_LABELS.map((label, index) => {
    if (task.status === 'COMPLETED') return { label, state: 'done' as const }
    if (index < current) return { label, state: 'done' as const }
    if (index === current) {
      return { label, state: terminalError ? ('error' as const) : ('active' as const) }
    }
    return { label, state: 'todo' as const }
  })
}

/** 进度优先取实时遥测，掉线时退回数据库里的进度 */
function progressOf(task: DispatchTask): number {
  const live = liveOf(task)?.progressPct
  const value = live ?? task.progressPct ?? 0
  return Math.max(0, Math.min(100, Number(value)))
}

/** 能耗占用比：实际 / 计划，用于卡片上的能耗条 */
function energyRatio(task: DispatchTask): number {
  const planned = Number(task.plannedEnergyWh ?? 0)
  const consumed = Number(task.consumedEnergyWh ?? 0)
  if (!planned) return 0
  return Math.max(0, Math.min(100, Math.round((consumed / planned) * 100)))
}

/** 已飞时长：进行中算到当前，已结束用任务记录的耗时 */
function elapsedText(task: DispatchTask): string {
  if (!task.startedAt) return '尚未起飞'
  if (task.finishedAt) return `耗时 ${formatDuration(task.durationS)}`
  const seconds = Math.max(0, dayjs().diff(dayjs(task.startedAt), 'second'))
  return `已飞 ${formatDuration(seconds)}`
}

/** 剩余航程估算：计划航程 × (1 - 进度) */
function remainingDistance(task: DispatchTask): number | null {
  const planned = Number(task.plannedDistanceM ?? 0)
  if (!planned) return null
  return planned * (1 - progressOf(task) / 100)
}

function strategyText(strategy?: string): string {
  if (!strategy) return '—'
  return STRATEGY_TEXT[strategy] ?? strategy
}

// ---------------------------------------------------------------------------
// 任务详情抽屉
// ---------------------------------------------------------------------------

const detailOpen = ref(false)
const detailLoading = ref(false)
const currentTask = ref<DispatchTask | null>(null)
const waypoints = ref<Waypoint[]>([])

/** 计划去程 ETA：取最后一个航路点的累计 ETA（与任务预计耗时同一口径）。 */
const plannedEtaSeconds = computed(() => {
  if (!waypoints.value.length) return null
  return Math.max(...waypoints.value.map((w) => Number(w.etaSeconds ?? 0)))
})
const events = ref<EventLogItem[]>([])
const exporting = ref<'telemetry' | 'events' | ''>('')
const detailTab = ref<'events' | 'waypoints' | 'params'>('events')
/** 跟随实时：任务执行中时每 5 秒刷新任务状态与事件 */
const followLive = ref(true)

let detailTimer: number | null = null

/** 抽屉内实时帧：与卡片共用同一份遥测 */
const detailFrame = computed(() => {
  const id = currentTask.value?.id
  return id ? frameByTaskId.value.get(id) : undefined
})

async function openDetail(task: DispatchTask): Promise<void> {
  currentTask.value = task
  detailOpen.value = true
  detailTab.value = 'events'
  waypoints.value = []
  events.value = []
  detailLoading.value = true
  try {
    // 两个接口互不依赖，并行请求缩短抽屉空白时间
    const [wp, ev] = await Promise.allSettled([taskApi.waypoints(task.id), taskApi.events(task.id)])
    if (wp.status === 'fulfilled') waypoints.value = wp.value ?? []
    if (ev.status === 'fulfilled') events.value = ev.value ?? []
  } finally {
    detailLoading.value = false
  }
  startDetailTimer()
}

/** 只刷新会变化的两块：任务状态 + 事件；航路点是静态的，不重复拉 */
async function refreshDetail(): Promise<void> {
  const id = currentTask.value?.id
  if (!id) return
  const [task, ev] = await Promise.allSettled([taskApi.get(id), taskApi.events(id)])
  if (task.status === 'fulfilled') currentTask.value = task.value
  if (ev.status === 'fulfilled') events.value = ev.value ?? []
}

function startDetailTimer(): void {
  clearDetailTimer()
  detailTimer = window.setInterval(() => {
    if (detailOpen.value && followLive.value) void refreshDetail()
  }, 5000)
}

function clearDetailTimer(): void {
  if (detailTimer !== null) {
    window.clearInterval(detailTimer)
    detailTimer = null
  }
}

function closeDetail(): void {
  detailOpen.value = false
  clearDetailTimer()
}

async function abortTask(task: DispatchTask): Promise<void> {
  try {
    await taskApi.abort(task.id, '运营人工中止')
    message.success(`任务 ${task.taskNo} 已中止`)
    await Promise.all([loadTasks(), loadStats()])
    if (currentTask.value?.id === task.id) await refreshDetail()
  } catch {
    // 中止失败（例如任务已完成）时刷新列表，拿回后端的最新状态
    await Promise.all([loadTasks(), loadStats()])
  }
}

async function exportTelemetry(): Promise<void> {
  const task = currentTask.value
  if (!task) return
  exporting.value = 'telemetry'
  try {
    await reportApi.telemetryCsv(task.id)
  } catch {
    /* 下载失败已由 client.ts 提示 */
  } finally {
    exporting.value = ''
  }
}

async function exportEvents(): Promise<void> {
  const task = currentTask.value
  if (!task) return
  exporting.value = 'events'
  try {
    await reportApi.eventsCsv(task.id)
  } catch {
    /* 下载失败已由 client.ts 提示 */
  } finally {
    exporting.value = ''
  }
}

/** 从任务跳到订单全链路：订单页会读 query 并自动打开追踪抽屉 */
function openOrderTrace(task: DispatchTask): void {
  void router.push({ path: '/orders', query: { traceOrderId: String(task.orderId) } })
}

async function copyTaskNo(): Promise<void> {
  const text = currentTask.value?.taskNo
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    message.success(`已复制任务号 ${text}`)
  } catch {
    message.info(`任务号：${text}`)
  }
}

// ---------------------------------------------------------------------------
// 飞行剖面：由航路点画「高度 × 累计航程」曲线
// ---------------------------------------------------------------------------

interface ProfilePoint {
  x: number
  y: number
  seq: number
}

const profile = computed(() => {
  const list = waypoints.value
  if (!list.length) return null
  const width = 640
  const height = 160
  const padX = 26
  const padTop = 22
  const padBottom = 30
  const maxDist = Math.max(...list.map((w) => Number(w.cumulativeDistanceM ?? 0)), 1)
  const maxAlt = Math.max(...list.map((w) => Number(w.altitudeM ?? 0)), 10)
  const points: ProfilePoint[] = list.map((w) => ({
    x: padX + (Number(w.cumulativeDistanceM ?? 0) / maxDist) * (width - padX * 2),
    y: height - padBottom - (Number(w.altitudeM ?? 0) / maxAlt) * (height - padTop - padBottom),
    seq: w.seq,
  }))
  const line = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
  const area = `${padX},${height - padBottom} ${line} ${width - padX},${height - padBottom}`
  return {
    width,
    height,
    padX,
    padBottom,
    maxAlt,
    maxDist,
    points,
    line,
    area,
    currentSeq: currentTask.value?.currentWaypointSeq,
    baseY: height - padBottom,
  }
})

/** 事件圆点颜色：与严重度语义一致 */
const SEVERITY_HEX: Record<string, string> = {
  DEBUG: '#5b7c99',
  INFO: '#38e8ff',
  WARN: '#f0a93b',
  ERROR: '#ff6060',
}

function severityColor(severity: string): string {
  return SEVERITY_HEX[severity] ?? '#5b7c99'
}

function prettyJson(raw?: string): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

const waypointColumns = [
  { title: '序号', dataIndex: 'seq', key: 'seq', width: 64 },
  { title: '纬度', dataIndex: 'lat', key: 'lat', width: 110 },
  { title: '经度', dataIndex: 'lon', key: 'lon', width: 110 },
  { title: '高度', dataIndex: 'altitudeM', key: 'altitudeM', width: 86 },
  { title: '累计航程', key: 'cumulativeDistanceM', width: 100 },
  { title: '预计到达', key: 'etaSeconds', width: 100 },
]

// ---------------------------------------------------------------------------
// 初始化
// ---------------------------------------------------------------------------

onMounted(() => {
  // 从机队页「在任务页查看该机全部任务」跳转过来：带上无人机筛选
  const raw = route.query.droneId
  const droneId = Number(Array.isArray(raw) ? raw[0] : raw)
  if (Number.isFinite(droneId) && droneId > 0) {
    query.droneId = droneId
    pagination.current = 1
  }
  void loadTasks()
  void loadStats()
  startAutoRefresh()
})

onUnmounted(() => {
  stopAutoRefresh()
  clearDetailTimer()
})
</script>

<template>
  <div class="tasks-view">
    <!-- ------------------------------ 任务态势流水线 ------------------------------ -->
    <section class="mission">
      <div class="mission-head">
        <div>
          <div class="mission-title">
            <span class="mission-led" aria-hidden="true" />
            飞行任务指挥台
            <span class="mission-live">LIVE</span>
            <a-spin v-if="statsLoading" size="small" class="mission-spin" />
          </div>
          <div class="mission-sub">从派单、起飞、巡航投送到返航 · 每架机的执行链路可下钻</div>
        </div>
        <div class="mission-meta">
          <span>总任务 <strong>{{ stats?.total ?? 0 }}</strong></span>
          <span>活动任务 <strong>{{ stats?.active ?? 0 }}</strong></span>
          <span>完成率 <strong>{{ completionRate == null ? '—' : formatRate(completionRate, 0) }}</strong></span>
          <span>在飞无人机 <strong>{{ kpi?.activeDrones ?? 0 }} / {{ kpi?.totalDrones ?? 0 }}</strong></span>
        </div>
      </div>

      <div class="mission-track">
        <button
          v-for="stage in missionStages"
          :key="stage.key"
          type="button"
          class="stage-card"
          :class="{ active: activeStage === stage.key }"
          :style="{ '--stage-color': stage.color }"
          @click="toggleStage(stage)"
        >
          <span class="stage-top">
            <span class="stage-index">{{ stage.index }}</span>
            <span class="stage-label">{{ stage.label }}</span>
          </span>
          <span class="stage-value">
            {{ stage.count }}<em>条</em>
          </span>
          <span class="stage-bar">
            <i :style="{ width: `${stagePercent(stage)}%` }" />
          </span>
          <span class="stage-hint">{{ stage.hint }} · {{ stagePercent(stage) }}%</span>
        </button>
      </div>
    </section>

    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-select
          v-model:value="query.status"
          class="filter"
          placeholder="全部任务状态"
          allow-clear
          :options="(Object.keys(TASK_STATUS_TEXT) as TaskStatus[]).map((s) => ({ value: s, label: TASK_STATUS_TEXT[s] }))"
          @change="onStatusFilterChange"
        />
        <a-select
          v-model:value="query.droneId"
          class="filter filter-narrow"
          placeholder="全部无人机"
          allow-clear
          show-search
          option-filter-prop="label"
          :options="droneOptions"
          @change="handleFilterChange"
        />
        <a-button :loading="loading" @click="reloadAll">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <span class="auto-refresh">
          <a-switch v-model:checked="autoRefresh" size="small" />
          <span class="auto-refresh-label">5 秒自动刷新</span>
        </span>
        <a-button v-if="activeStage" type="link" size="small" @click="activeStage = undefined; reloadAll()">
          清除阶段筛选
        </a-button>
      </a-space>

      <template #extra>
        <a-button @click="reportApi.tasksCsv()">
          <template #icon><DownloadOutlined /></template>
          导出任务 CSV
        </a-button>
      </template>
    </a-card>

    <!-- ------------------------------ 任务卡片网格 ------------------------------ -->
    <a-spin :spinning="loading">
      <div v-if="tasks.length" class="task-grid">
        <article
          v-for="task in tasks"
          :key="task.id"
          class="task-card"
          :class="`tone-${statusTone(task.status)}`"
        >
          <header class="card-head">
            <div class="card-no">
              <span class="card-sigil" aria-hidden="true" />
              <span class="text-mono">{{ task.taskNo }}</span>
            </div>
            <div class="card-tags">
              <a-tag :color="TASK_STATUS_COLOR[task.status]">
                {{ TASK_STATUS_TEXT[task.status] ?? task.status }}
              </a-tag>
              <a-tag v-if="task.status === 'CHARGING'" color="gold">补能中</a-tag>
            </div>
          </header>

          <!-- 订单号可直接跳到订单全链路 -->
          <button type="button" class="order-link" @click="openOrderTrace(task)">
            <LinkOutlined />
            <span class="text-mono">{{ task.orderNo ?? `订单 #${task.orderId}` }}</span>
            <span class="order-link-hint">查看订单链路</span>
          </button>

          <div class="drone-strip">
            <span class="drone-code text-mono">
              <RocketOutlined />
              {{ task.droneCode ?? `#${task.droneId}` }}
            </span>
            <span class="drone-strategy">{{ strategyText(task.strategy) }}</span>
            <span class="drone-time text-mono">{{ elapsedText(task) }}</span>
          </div>

          <!-- 阶段轨 -->
          <div class="mission-rail">
            <span
              v-for="step in missionRail(task)"
              :key="step.label"
              class="rail-step"
              :class="`is-${step.state}`"
            >
              <i class="rail-dot" />
              <span class="rail-label">{{ step.label }}</span>
            </span>
          </div>

          <!-- 进度：实时遥测优先 -->
          <div class="progress-block">
            <div class="progress-head">
              <span>执行进度</span>
              <span class="text-mono">{{ progressOf(task).toFixed(0) }}%</span>
            </div>
            <div class="progress-bar">
              <i :style="{ width: `${progressOf(task)}%` }" />
              <span v-if="task.currentWaypointSeq" class="progress-wp text-mono">
                WP {{ task.currentWaypointSeq }}
              </span>
            </div>
          </div>

          <!-- 地面等待：战略时隙/机巢周转时，明确告诉用户"在等什么、等到什么时候" -->
          <div v-if="task.status === 'ASSIGNED' && task.holdReason" class="ground-hold-strip text-mono">
            <span class="hold-dot" />
            地面等待：{{ task.holdReason }}
            <span v-if="task.holdUntilS != null">（至 {{ Number(task.holdUntilS).toFixed(0) }}s）</span>
          </div>

          <!-- 实时遥测条 -->
          <div v-if="liveOf(task)" class="live-strip text-mono">
            <span><ThunderboltOutlined /> 电量 {{ Number(liveOf(task)?.batteryPct ?? 0).toFixed(0) }}%</span>
            <span>高度 {{ Number(liveOf(task)?.altitudeM ?? 0).toFixed(0) }} m</span>
            <span>速度 {{ Number(liveOf(task)?.speedMps ?? 0).toFixed(1) }} m/s</span>
            <span class="live-pulse">遥测在线</span>
          </div>
          <div v-else class="live-strip is-offline text-mono">
            <span>{{ task.status === 'COMPLETED' ? '任务已完成' : '暂无实时遥测' }}</span>
          </div>

          <!-- 能耗与航程读数 -->
          <div class="readouts">
            <div class="readout">
              <span class="readout-label">能耗</span>
              <span class="readout-value text-mono">
                {{ formatEnergy(task.consumedEnergyWh) }} / {{ formatEnergy(task.plannedEnergyWh) }}
              </span>
              <span class="readout-bar">
                <i :style="{ width: `${energyRatio(task)}%` }" />
              </span>
            </div>
            <div class="readout">
              <span class="readout-label">航程</span>
              <span class="readout-value text-mono">
                {{ formatDistance(task.actualDistanceM) }} / {{ formatDistance(task.plannedDistanceM) }}
              </span>
              <span class="readout-sub">
                剩余约 {{ formatDistance(remainingDistance(task)) }}
              </span>
            </div>
            <div class="readout">
              <span class="readout-label">电量</span>
              <span class="readout-value text-mono">
                {{ formatPercent(task.batteryStartPct, 0) }} → {{ formatPercent(task.batteryEndPct, 0) }}
              </span>
              <span class="readout-sub">派发 {{ formatDateTime(task.assignedAt) }}</span>
            </div>
          </div>

          <div v-if="task.failReason" class="fail-reason" :title="task.failReason">
            {{ task.failReason }}
          </div>

          <footer class="card-actions">
            <a-button type="primary" size="small" class="detail-btn" @click="openDetail(task)">
              <template #icon><AimOutlined /></template>
              任务详情
            </a-button>
            <a-button size="small" @click="openOrderTrace(task)">
              <template #icon><LinkOutlined /></template>
              订单链路
            </a-button>
            <a-popconfirm
              title="确认中止该任务？中止后无人机将返航。"
              ok-text="确认中止"
              cancel-text="取消"
              :disabled="['COMPLETED', 'ABORTED', 'FAILED'].includes(task.status)"
              @confirm="abortTask(task)"
            >
              <a-button
                size="small"
                danger
                :disabled="['COMPLETED', 'ABORTED', 'FAILED'].includes(task.status)"
              >
                中止
              </a-button>
            </a-popconfirm>
          </footer>
        </article>
      </div>

      <a-empty
        v-else
        class="tasks-empty"
        description="当前筛选下暂无任务；任务由订单派单生成"
      />
    </a-spin>

    <div v-if="pagination.total > 0" class="pagination-bar">
      <a-pagination
        :current="pagination.current"
        :page-size="pagination.pageSize"
        :total="pagination.total"
        :page-size-options="['6', '9', '12', '24']"
        show-size-changer
        :show-total="(total: number) => `共 ${total} 条任务`"
        @change="handlePageChange"
      />
    </div>

    <!-- ------------------------------ 任务详情抽屉 ------------------------------ -->
    <a-drawer
      v-model:open="detailOpen"
      width="920"
      class="task-drawer"
      :body-style="{ paddingBottom: '20px' }"
      @close="closeDetail"
    >
      <template #title>
        <div class="detail-title">
          <RocketOutlined class="detail-title-icon" />
          <span class="text-mono">{{ currentTask?.taskNo ?? '任务详情' }}</span>
          <a-button type="text" size="small" class="copy-btn" @click="copyTaskNo">
            <template #icon><CopyOutlined /></template>
          </a-button>
          <span class="detail-title-sub">飞行任务链路</span>
        </div>
      </template>

      <template #extra>
        <a-space size="small">
          <span class="follow-label">跟随实时</span>
          <a-switch v-model:checked="followLive" size="small" />
          <a-button size="small" :loading="detailLoading" @click="refreshDetail">
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
        </a-space>
      </template>

      <a-spin :spinning="detailLoading">
        <template v-if="currentTask">
          <!-- 摘要 -->
          <div class="detail-banner">
            <div class="detail-banner-top">
              <span class="detail-order text-mono">{{ currentTask.orderNo ?? `订单 #${currentTask.orderId}` }}</span>
              <a-tag :color="TASK_STATUS_COLOR[currentTask.status]">
                {{ TASK_STATUS_TEXT[currentTask.status] ?? currentTask.status }}
              </a-tag>
              <span class="detail-drone text-mono">
                <RocketOutlined />
                {{ currentTask.droneCode ?? `#${currentTask.droneId}` }}
              </span>
              <span class="detail-strategy">{{ strategyText(currentTask.strategy) }}</span>
            </div>
            <div v-if="currentTask.failReason" class="detail-fail">{{ currentTask.failReason }}</div>
          </div>

          <!-- 阶段轨 -->
          <div class="detail-rail">
            <div
              v-for="step in missionRail(currentTask)"
              :key="step.label"
              class="detail-node"
              :class="`is-${step.state}`"
            >
              <span class="detail-node-dot" />
              <span class="detail-node-label">{{ step.label }}</span>
            </div>
          </div>

          <!-- 实时遥测 -->
          <div v-if="detailFrame" class="detail-live text-mono">
            <span class="detail-live-pulse">实时</span>
            <span>电量 {{ Number(detailFrame.batteryPct ?? 0).toFixed(0) }}%</span>
            <span>高度 {{ Number(detailFrame.altitudeM ?? 0).toFixed(0) }} m</span>
            <span>速度 {{ Number(detailFrame.speedMps ?? 0).toFixed(1) }} m/s</span>
            <span>航路点 #{{ detailFrame.waypointSeq ?? '—' }}</span>
            <span>
              {{ Number(detailFrame.lat ?? 0).toFixed(5) }}, {{ Number(detailFrame.lon ?? 0).toFixed(5) }}
            </span>
          </div>

          <!-- 读数条 -->
          <div class="detail-summary">
            <div class="summary-item">
              <em>执行进度</em>
              <strong>{{ progressOf(currentTask).toFixed(0) }}%</strong>
            </div>
            <div class="summary-item">
              <em>实际 / 计划航程</em>
              <strong class="small text-mono">
                {{ formatDistance(currentTask.actualDistanceM) }} / {{ formatDistance(currentTask.plannedDistanceM) }}
              </strong>
            </div>
            <div class="summary-item">
              <em>实际 / 计划能耗</em>
              <strong class="small text-mono">
                {{ formatEnergy(currentTask.consumedEnergyWh) }} / {{ formatEnergy(currentTask.plannedEnergyWh) }}
              </strong>
            </div>
            <div class="summary-item">
              <em>电量起止</em>
              <strong class="small text-mono">
                {{ formatPercent(currentTask.batteryStartPct, 0) }} → {{ formatPercent(currentTask.batteryEndPct, 0) }}
              </strong>
            </div>
            <div class="summary-item">
              <em>执行耗时</em>
              <strong class="small text-mono">{{ elapsedText(currentTask) }}</strong>
            </div>
          </div>

          <!-- 飞行剖面 -->
          <div class="profile-card">
            <div class="profile-head">
              <span class="profile-title">飞行剖面</span>
              <span class="profile-note">
                高度 × 累计航程 · {{ waypoints.length }} 个航路点
              </span>
            </div>
            <svg
              v-if="profile"
              class="profile-svg"
              :viewBox="`0 0 ${profile.width} ${profile.height}`"
              role="img"
              aria-label="飞行高度剖面"
            >
              <defs>
                <linearGradient id="profile-area" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="rgba(56, 232, 255, 0.32)" />
                  <stop offset="100%" stop-color="rgba(56, 232, 255, 0)" />
                </linearGradient>
                <linearGradient id="profile-line" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0%" stop-color="#1677ff" />
                  <stop offset="100%" stop-color="#38e8ff" />
                </linearGradient>
              </defs>
              <line
                class="profile-base"
                :x1="profile.padX"
                :y1="profile.baseY"
                :x2="profile.width - profile.padX"
                :y2="profile.baseY"
              />
              <polygon :points="profile.area" fill="url(#profile-area)" />
              <polyline
                :points="profile.line"
                fill="none"
                stroke="url(#profile-line)"
                stroke-width="2"
              />
              <circle
                v-for="point in profile.points"
                :key="point.seq"
                :cx="point.x"
                :cy="point.y"
                :r="point.seq === profile.currentSeq ? 5 : 2.4"
                class="profile-dot"
                :class="{ current: point.seq === profile.currentSeq }"
              />
              <text x="8" y="14" class="profile-label">{{ Math.round(profile.maxAlt) }} m</text>
              <text x="8" :y="profile.height - 8" class="profile-label">0 m</text>
              <text
                :x="profile.width - 12"
                :y="profile.height - 8"
                text-anchor="end"
                class="profile-label"
              >
                {{ formatDistance(profile.maxDist) }}
              </text>
            </svg>
            <a-empty v-else description="该任务没有航路点数据" />
          </div>

          <!-- 事件 / 航路点 / 参数 -->
          <a-tabs v-model:activeKey="detailTab" class="detail-tabs">
            <a-tab-pane key="events" :tab="`事件时间线 (${events.length})`">
              <ul v-if="events.length" class="timeline">
                <li
                  v-for="event in events"
                  :key="event.id"
                  class="timeline-item"
                  :style="{ '--event-color': severityColor(event.severity) }"
                >
                  <span class="timeline-dot" />
                  <div class="timeline-body">
                    <div class="timeline-head">
                      <span class="timeline-type">{{ eventTypeText(event.eventType) }}</span>
                      <span class="timeline-time text-mono">{{ formatDateTime(event.ts) }}</span>
                      <span v-if="event.simTimeS != null" class="timeline-sim text-mono">
                        T+{{ formatDuration(Number(event.simTimeS)) }}
                      </span>
                    </div>
                    <div class="timeline-msg">{{ event.message }}</div>
                    <details v-if="event.payload" class="timeline-payload">
                      <summary>原始数据</summary>
                      <pre>{{ prettyJson(event.payload) }}</pre>
                    </details>
                  </div>
                </li>
              </ul>
              <a-empty v-else description="该任务还没有事件记录" />
            </a-tab-pane>

            <a-tab-pane key="waypoints" :tab="`航路点 (${waypoints.length})`">
              <a-table
                row-key="seq"
                :columns="waypointColumns"
                :data-source="waypoints"
                size="small"
                :pagination="false"
                :scroll="{ y: 280 }"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'seq'">
                    <a-tag :color="record.seq === currentTask.currentWaypointSeq ? 'blue' : 'default'">
                      {{ record.seq }}
                    </a-tag>
                  </template>
                  <template v-else-if="column.key === 'lat'">
                    {{ Number(record.lat).toFixed(5) }}
                  </template>
                  <template v-else-if="column.key === 'lon'">
                    {{ Number(record.lon).toFixed(5) }}
                  </template>
                  <template v-else-if="column.key === 'altitudeM'">
                    {{ Number(record.altitudeM).toFixed(0) }} m
                  </template>
                  <template v-else-if="column.key === 'cumulativeDistanceM'">
                    {{ formatDistance(record.cumulativeDistanceM) }}
                  </template>
                  <template v-else-if="column.key === 'etaSeconds'">
                    {{ formatDuration(record.etaSeconds) }}
                  </template>
                </template>
              </a-table>
            </a-tab-pane>

            <a-tab-pane key="params" tab="任务参数">
              <a-descriptions bordered :column="2" size="small">
                <a-descriptions-item label="任务号">{{ currentTask.taskNo }}</a-descriptions-item>
                <a-descriptions-item label="订单号">{{ currentTask.orderNo ?? '—' }}</a-descriptions-item>
                <a-descriptions-item label="无人机">
                  {{ currentTask.droneCode ?? `#${currentTask.droneId}` }}
                </a-descriptions-item>
                <a-descriptions-item label="派单策略">{{ strategyText(currentTask.strategy) }}</a-descriptions-item>
                <a-descriptions-item label="航路 ID">{{ currentTask.routeId ?? '—' }}</a-descriptions-item>
                <a-descriptions-item label="当前航路点">{{ currentTask.currentWaypointSeq ?? '—' }}</a-descriptions-item>
                <a-descriptions-item v-if="currentTask.holdReason" label="地面等待">
                  {{ currentTask.holdReason }}
                  <span v-if="currentTask.holdUntilS != null">
                    （至 {{ Number(currentTask.holdUntilS).toFixed(0) }}s）
                  </span>
                </a-descriptions-item>
                <a-descriptions-item label="派发时间">{{ formatDateTime(currentTask.assignedAt) }}</a-descriptions-item>
                <a-descriptions-item label="起飞时间">{{ formatDateTime(currentTask.startedAt) }}</a-descriptions-item>
                <a-descriptions-item label="结束时间">{{ formatDateTime(currentTask.finishedAt) }}</a-descriptions-item>
                <a-descriptions-item label="计划去程 ETA">
                  {{ plannedEtaSeconds == null ? '—' : formatDuration(plannedEtaSeconds) }}
                </a-descriptions-item>
                <a-descriptions-item label="执行耗时（含返航）">
                  {{ formatDuration(currentTask.durationS) }}
                </a-descriptions-item>
                <a-descriptions-item label="失败原因" :span="2">
                  {{ currentTask.failReason ?? '—' }}
                </a-descriptions-item>
              </a-descriptions>
            </a-tab-pane>
          </a-tabs>
        </template>
      </a-spin>

      <template #footer>
        <a-space wrap>
          <a-button type="primary" @click="currentTask && openOrderTrace(currentTask)">
            <template #icon><LinkOutlined /></template>
            查看订单全链路
          </a-button>
          <a-button :loading="exporting === 'telemetry'" @click="exportTelemetry">
            <template #icon><DownloadOutlined /></template>
            导出遥测 CSV
          </a-button>
          <a-button :loading="exporting === 'events'" @click="exportEvents">
            <template #icon><DownloadOutlined /></template>
            导出事件 CSV
          </a-button>
        </a-space>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.tasks-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.filter {
  width: 220px;
}

.filter-narrow {
  width: 170px;
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

/* =============================================================================
   任务态势流水线
   ============================================================================= */
.mission {
  position: relative;
  padding: 14px 16px 16px;
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

.mission::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 26%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  animation: mission-scan 8s linear infinite;
}

@keyframes mission-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(480%);
  }
}

.mission-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 12px;
}

.mission-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: #e9fbff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.3);
}

.mission-led {
  width: 6px;
  height: 6px;
  background: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  transform: rotate(45deg);
  animation: mission-led 1.8s ease-in-out infinite;
}

@keyframes mission-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.mission-live {
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

.mission-spin {
  margin-left: 2px;
}

.mission-sub {
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.62);
}

.mission-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 12px;
  color: rgba(150, 199, 232, 0.72);
}

.mission-meta strong {
  margin-left: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 14px;
  color: #d8f7ff;
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.35);
}

.mission-track {
  position: relative;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.mission-track::before {
  content: '';
  position: absolute;
  left: 2%;
  right: 2%;
  top: 50%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(56, 232, 255, 0.35) 12%,
    rgba(56, 232, 255, 0.35) 88%,
    transparent
  );
}

.stage-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 9px 11px 10px;
  text-align: left;
  cursor: pointer;
  border: 1px solid rgba(56, 232, 255, 0.16);
  border-radius: 0;
  background: linear-gradient(180deg, rgba(18, 42, 64, 0.72), rgba(7, 19, 32, 0.72));
  clip-path: polygon(9px 0, 100% 0, 100% calc(100% - 9px), calc(100% - 9px) 100%, 0 100%, 0 9px);
  box-shadow: inset 0 0 20px rgba(56, 232, 255, 0.03);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.15s ease;
}

.stage-card:hover {
  border-color: color-mix(in srgb, var(--stage-color) 55%, transparent);
  transform: translateY(-1px);
  box-shadow: inset 0 0 24px color-mix(in srgb, var(--stage-color) 10%, transparent);
}

.stage-card.active {
  border-color: var(--stage-color);
  box-shadow:
    inset 0 0 26px color-mix(in srgb, var(--stage-color) 16%, transparent),
    0 0 14px color-mix(in srgb, var(--stage-color) 28%, transparent);
}

.stage-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 2px;
  background: var(--stage-color);
  box-shadow: 0 0 8px var(--stage-color);
  opacity: 0.75;
}

.stage-top {
  display: flex;
  align-items: center;
  gap: 7px;
}

.stage-index {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 10px;
  color: color-mix(in srgb, var(--stage-color) 80%, #fff 20%);
  opacity: 0.8;
}

.stage-label {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: rgba(216, 240, 255, 0.92);
}

.stage-value {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.15;
  color: #eafcff;
  text-shadow: 0 0 14px color-mix(in srgb, var(--stage-color) 45%, transparent);
  font-variant-numeric: tabular-nums;
}

.stage-value em {
  margin-left: 4px;
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.65);
  text-shadow: none;
}

.stage-bar {
  display: block;
  width: 100%;
  height: 3px;
  margin-top: 2px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.08);
}

.stage-bar i {
  display: block;
  height: 100%;
  background: var(--stage-color);
  box-shadow: 0 0 8px var(--stage-color);
  transition: width 0.45s ease;
}

.stage-hint {
  font-size: 11px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.58);
}

/* =============================================================================
   任务卡片
   ============================================================================= */
.task-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.task-card {
  --card-tone: #38e8ff;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 9px;
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

.task-card:hover {
  transform: translateY(-2px);
  border-color: rgba(56, 232, 255, 0.42);
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.46),
    0 0 16px rgba(56, 232, 255, 0.14);
}

.task-card::after {
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

.task-card.tone-pending {
  --card-tone: #f0a93b;
}
.task-card.tone-active {
  --card-tone: #38e8ff;
}
.task-card.tone-returning {
  --card-tone: #b37feb;
}
.task-card.tone-success {
  --card-tone: #2fd6a5;
}
.task-card.tone-danger {
  --card-tone: #ff6060;
}
.task-card.tone-muted {
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

/* 订单跳转条 */
.order-link {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 8px;
  text-align: left;
  cursor: pointer;
  color: #9fd9f5;
  border: 1px solid rgba(56, 232, 255, 0.12);
  background: rgba(6, 18, 31, 0.55);
  transition: border-color 0.18s ease, color 0.18s ease;
}

.order-link:hover {
  border-color: rgba(56, 232, 255, 0.4);
  color: #7ff0ff;
}

.order-link .text-mono {
  font-size: 11px;
}

.order-link-hint {
  margin-left: auto;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.45);
}

.drone-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 12px;
  color: rgba(216, 240, 255, 0.86);
}

.drone-code {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 600;
  color: #cfeaff;
}

.drone-strategy {
  color: rgba(150, 199, 232, 0.62);
}

.drone-time {
  margin-left: auto;
  color: rgba(150, 199, 232, 0.55);
}

/* 阶段轨 */
.mission-rail {
  display: flex;
  align-items: center;
  padding: 2px 0;
}

.rail-step {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

.rail-step::before {
  content: '';
  position: absolute;
  top: 4px;
  right: 50%;
  width: 100%;
  height: 1px;
  background: rgba(56, 232, 255, 0.15);
}

.rail-step:first-child::before {
  display: none;
}

.rail-step.is-done::before,
.rail-step.is-active::before,
.rail-step.is-error::before {
  background: rgba(56, 232, 255, 0.5);
}

.rail-dot {
  position: relative;
  z-index: 1;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #0a1a2b;
  border: 1px solid rgba(56, 232, 255, 0.28);
}

.rail-step.is-done .rail-dot {
  background: #2fd6a5;
  border-color: #2fd6a5;
  box-shadow: 0 0 6px rgba(47, 214, 165, 0.8);
}

.rail-step.is-active .rail-dot {
  background: #38e8ff;
  border-color: #38e8ff;
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.95);
  animation: rail-pulse 1.4s ease-out infinite;
}

.rail-step.is-error .rail-dot {
  background: #ff6060;
  border-color: #ff6060;
  box-shadow: 0 0 8px rgba(255, 96, 96, 0.9);
}

@keyframes rail-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(56, 232, 255, 0.55);
  }
  100% {
    box-shadow: 0 0 0 7px rgba(56, 232, 255, 0);
  }
}

.rail-label {
  font-size: 10px;
  line-height: 1.2;
  color: rgba(150, 199, 232, 0.5);
}

.rail-step.is-done .rail-label {
  color: rgba(47, 214, 165, 0.85);
}

.rail-step.is-active .rail-label {
  color: #9ff0ff;
}

.rail-step.is-error .rail-label {
  color: #ff9a9a;
}

/* 进度条 */
.progress-block {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.progress-head {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: rgba(150, 199, 232, 0.68);
}

.progress-head .text-mono {
  color: #9ff0ff;
}

.progress-bar {
  position: relative;
  height: 5px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.1);
}

.progress-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #1677ff, #38e8ff);
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.8);
  transition: width 0.5s linear;
}

.progress-wp {
  position: absolute;
  right: 0;
  top: -1px;
  padding: 0 4px;
  font-size: 9px;
  line-height: 7px;
  color: #9ff0ff;
  background: #0a1a2b;
}

/* 地面等待（战略时隙/机巢周转） */
.ground-hold-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 6px;
  padding: 5px 8px;
  font-size: 10px;
  color: #ffd591;
  border: 1px solid rgba(250, 173, 20, 0.34);
  background: rgba(250, 173, 20, 0.08);
}

.hold-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #faad14;
  box-shadow: 0 0 6px rgba(250, 173, 20, 0.8);
}

/* 实时遥测 */
.live-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px 12px;
  padding: 5px 8px;
  font-size: 10px;
  color: rgba(159, 240, 255, 0.82);
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(56, 232, 255, 0.05);
}

.live-strip.is-offline {
  color: rgba(150, 199, 232, 0.42);
  border-color: rgba(150, 199, 232, 0.1);
  background: rgba(150, 199, 232, 0.03);
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

/* 读数区 */
.readouts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.readout {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.readout-label {
  font-size: 10px;
  letter-spacing: 0.4px;
  color: rgba(150, 199, 232, 0.5);
}

.readout-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: rgba(216, 240, 255, 0.88);
}

.readout-bar {
  display: block;
  height: 2px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.1);
}

.readout-bar i {
  display: block;
  height: 100%;
  background: #f0a93b;
  box-shadow: 0 0 6px rgba(240, 169, 59, 0.7);
}

.readout-sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.45);
}

.fail-reason {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 4px 8px;
  font-size: 11px;
  color: #ffb4b4;
  border: 1px solid rgba(255, 96, 96, 0.3);
  background: rgba(255, 96, 96, 0.08);
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid rgba(56, 232, 255, 0.1);
}

.card-actions .detail-btn {
  font-weight: 600;
  letter-spacing: 0.4px;
}

.tasks-empty {
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
   任务详情抽屉
   ============================================================================= */
.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.detail-title-icon {
  color: #38e8ff;
  filter: drop-shadow(0 0 6px rgba(56, 232, 255, 0.8));
}

.detail-title .text-mono {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #eafcff;
}

.copy-btn {
  color: rgba(150, 199, 232, 0.7);
}

.detail-title-sub {
  margin-left: 4px;
  padding-left: 8px;
  border-left: 1px solid rgba(56, 232, 255, 0.25);
  font-size: 12px;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.6);
}

.follow-label {
  font-size: 12px;
  color: rgba(150, 199, 232, 0.68);
}

.detail-banner {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid rgba(56, 232, 255, 0.2);
  background:
    radial-gradient(520px 160px at 8% -40%, rgba(56, 232, 255, 0.14), transparent 70%),
    linear-gradient(180deg, rgba(14, 35, 55, 0.9), rgba(7, 19, 32, 0.9));
  clip-path: polygon(12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%, 0 12px);
}

.detail-banner-top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-order {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #f0fdff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.4);
}

.detail-drone {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #9ff0ff;
}

.detail-strategy {
  margin-left: auto;
  font-size: 11px;
  color: rgba(150, 199, 232, 0.62);
}

.detail-fail {
  padding: 5px 8px;
  font-size: 12px;
  color: #ffb4b4;
  border: 1px solid rgba(255, 96, 96, 0.3);
  background: rgba(255, 96, 96, 0.08);
}

.detail-rail {
  display: flex;
  align-items: flex-start;
  padding: 4px 2px 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid rgba(56, 232, 255, 0.1);
}

.detail-node {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

.detail-node::before {
  content: '';
  position: absolute;
  top: 5px;
  right: 50%;
  width: 100%;
  height: 1px;
  background: rgba(56, 232, 255, 0.18);
}

.detail-node:first-child::before {
  display: none;
}

.detail-node.is-done::before,
.detail-node.is-active::before,
.detail-node.is-error::before {
  background: rgba(56, 232, 255, 0.55);
}

.detail-node-dot {
  position: relative;
  z-index: 1;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #0a1a2b;
  border: 1px solid rgba(56, 232, 255, 0.3);
}

.detail-node.is-done .detail-node-dot {
  background: #2fd6a5;
  border-color: #2fd6a5;
  box-shadow: 0 0 8px rgba(47, 214, 165, 0.85);
}

.detail-node.is-active .detail-node-dot {
  background: #38e8ff;
  border-color: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  animation: rail-pulse 1.4s ease-out infinite;
}

.detail-node.is-error .detail-node-dot {
  background: #ff6060;
  border-color: #ff6060;
  box-shadow: 0 0 10px rgba(255, 96, 96, 0.9);
}

.detail-node-label {
  font-size: 11px;
  color: rgba(216, 240, 255, 0.86);
}

.detail-node.is-todo .detail-node-label {
  color: rgba(150, 199, 232, 0.45);
}

.detail-live {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 14px;
  padding: 7px 10px;
  margin-bottom: 12px;
  font-size: 11px;
  color: rgba(159, 240, 255, 0.85);
  border: 1px solid rgba(56, 232, 255, 0.2);
  background: rgba(56, 232, 255, 0.06);
}

.detail-live-pulse {
  color: #2fd6a5;
}

.detail-live-pulse::before {
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

.detail-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 10px;
  border: 1px solid rgba(56, 232, 255, 0.12);
  background: rgba(8, 21, 35, 0.6);
}

.summary-item em {
  font-size: 11px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.58);
}

.summary-item strong {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 15px;
  font-weight: 600;
  color: #dffbff;
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.3);
}

.summary-item strong.small {
  font-size: 12px;
  font-weight: 500;
}

/* 飞行剖面 */
.profile-card {
  padding: 10px 12px 4px;
  margin-bottom: 14px;
  border: 1px solid rgba(56, 232, 255, 0.14);
  background: linear-gradient(180deg, rgba(10, 26, 43, 0.75), rgba(6, 17, 29, 0.75));
}

.profile-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.profile-title {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #d9f6ff;
}

.profile-note {
  font-size: 11px;
  color: rgba(150, 199, 232, 0.55);
}

.profile-svg {
  display: block;
  width: 100%;
  height: 160px;
}

.profile-base {
  stroke: rgba(56, 232, 255, 0.2);
  stroke-width: 1;
  stroke-dasharray: 4 4;
}

.profile-dot {
  fill: #0a1a2b;
  stroke: #38e8ff;
  stroke-width: 1.5;
}

.profile-dot.current {
  fill: #38e8ff;
  stroke: #eafcff;
  stroke-width: 2;
  filter: drop-shadow(0 0 6px rgba(56, 232, 255, 0.95));
}

.profile-label {
  fill: rgba(150, 199, 232, 0.6);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 10px;
}

/* 时间线 */
.timeline {
  list-style: none;
  margin: 0;
  padding: 0 0 0 6px;
}

.timeline-item {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 0 0 16px 0;
}

.timeline-item::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 14px;
  bottom: -2px;
  width: 1px;
  background: linear-gradient(180deg, rgba(56, 232, 255, 0.32), rgba(56, 232, 255, 0.08));
}

.timeline-item:last-child::before {
  display: none;
}

.timeline-dot {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  width: 11px;
  height: 11px;
  margin-top: 3px;
  border-radius: 50%;
  background: var(--event-color);
  box-shadow: 0 0 9px var(--event-color);
}

.timeline-body {
  flex: 1 1 auto;
  min-width: 0;
}

.timeline-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin-bottom: 2px;
}

.timeline-type {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: #d9f6ff;
}

.timeline-time {
  font-size: 11px;
  color: rgba(150, 199, 232, 0.55);
}

.timeline-sim {
  font-size: 10px;
  padding: 0 5px;
  color: rgba(159, 240, 255, 0.75);
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(56, 232, 255, 0.05);
}

.timeline-msg {
  font-size: 12px;
  line-height: 1.6;
  color: rgba(216, 240, 255, 0.82);
  word-break: break-word;
}

.timeline-payload {
  margin-top: 4px;
  font-size: 11px;
  color: rgba(150, 199, 232, 0.6);
}

.timeline-payload summary {
  cursor: pointer;
  color: rgba(79, 195, 255, 0.8);
}

.timeline-payload pre {
  margin: 4px 0 0;
  padding: 6px 8px;
  max-height: 160px;
  overflow: auto;
  border-radius: 4px;
  background: #0b1a2c;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(216, 240, 255, 0.78);
}

@media (max-width: 900px) {
  .mission-track {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mission-track::before {
    display: none;
  }

  .readouts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .mission::after,
  .mission-led,
  .rail-step.is-active .rail-dot,
  .detail-node.is-active .detail-node-dot,
  .live-pulse::before,
  .detail-live-pulse::before {
    animation: none;
  }
}
</style>
