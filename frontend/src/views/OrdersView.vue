<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs, { type Dayjs } from 'dayjs'
import { useRoute } from 'vue-router'
import {
  AimOutlined,
  ClockCircleOutlined,
  CopyOutlined,
  DownloadOutlined,
  NodeIndexOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons-vue'
import {
  airspaceApi,
  dashboardApi,
  dispatchApi,
  orderApi,
  reportApi,
  type AirspaceZone,
  type CandidatePreview,
  type DispatchDecision,
  type DispatchTask,
  type DroneFrame,
  type EventLogItem,
  type Kpi,
  type Order,
  type OrderStatus,
  type OrderTrace,
  type PageResponse,
  type Priority,
} from '@/api'
import PointPickerMap from '@/components/map/PointPickerMap.vue'
import { useTelemetry } from '@/store/telemetry'
import {
  ORDER_STATUS_COLOR,
  ORDER_STATUS_TEXT,
  PRIORITY_COLOR,
  PRIORITY_TEXT,
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
  formatTime,
} from '@/utils/format'

/**
 * 订单管理 = 订单「调度控制台」。
 *
 * 这一页不再是一张平铺表格，而是三块信息：
 *
 *  1. **链路流水线**：待派 → 执行中 → 已送达 → 异常，四段计数来自实时 KPI，
 *     点任意一段即可筛选订单，运营一眼看出"积压卡在哪一段"。
 *  2. **订单卡片**：每单把「起终点 → 货物 → 执行无人机 → 实时进度 → SLA」压成一张卡，
 *     比横向滚动十几个字段的表格更快定位问题单。
 *  3. **链路追踪抽屉**：一次聚合请求拿到订单 / 任务 / 事件 / 派单决策，
 *     按时间线还原"下单→派单→起飞→送达（或异常）"的全过程，并给出可复制的链路号。
 *
 * 原有能力（新建、派单、可用机、决策明细、批量派单、导出、取消）全部保留。
 */

const loading = ref(false)
const submitting = ref(false)
const dispatchingId = ref<number | null>(null)

const orders = ref<Order[]>([])
const pagination = reactive({ current: 1, pageSize: 9, total: 0 })

const filterStatus = ref<OrderStatus | undefined>(undefined)
const filterPriority = ref<Priority | undefined>(undefined)

// ---------------------------------------------------------------------------
// 实时数据（复用全局遥测：KPI 计数 + 无人机帧）
// ---------------------------------------------------------------------------

const { kpi: liveKpi, drones } = useTelemetry()
const route = useRoute()

/** WebSocket 还没推 KPI 时用 REST 兜底，保证首屏流水线不留空白 */
const fallbackKpi = ref<Kpi | null>(null)
const kpi = computed(() => liveKpi.value ?? fallbackKpi.value)

/** droneId → 机身编号：卡片上显示编号比显示数据库 id 有用得多 */
const droneCodeById = computed(() => {
  const map = new Map<number, string>()
  drones.value.forEach((frame) => map.set(frame.droneId, frame.droneCode))
  return map
})

/** 订单号 → 最新遥测帧：用于卡片上的实时进度 / 电量 */
const frameByOrderNo = computed(() => {
  const map = new Map<string, DroneFrame>()
  drones.value.forEach((frame) => {
    if (frame.orderNo) map.set(frame.orderNo, frame)
  })
  return map
})

function liveFrameOf(order: Order): DroneFrame | undefined {
  // 只有执行中的订单才展示实时进度，避免已送达订单仍挂着一帧旧遥测
  if (order.status !== 'ASSIGNED' && order.status !== 'IN_FLIGHT') return undefined
  return frameByOrderNo.value.get(order.orderNo)
}

function droneLabel(order: Order): string {
  if (!order.assignedDroneId) return '未分配'
  const code = droneCodeById.value.get(order.assignedDroneId)
  return code ?? `#${order.assignedDroneId}`
}

// ---------------------------------------------------------------------------
// 链路流水线
// ---------------------------------------------------------------------------

type StageKey = 'pending' | 'inflight' | 'delivered' | 'failed'

interface PipelineStage {
  key: StageKey
  index: string
  label: string
  hint: string
  count: number
  color: string
  /** 该阶段对应的订单状态集合（后端按 statuses 过滤） */
  statuses: OrderStatus[]
}

/** 当前选中的流水线阶段；再点一次取消 */
const activeStage = ref<StageKey | undefined>(undefined)

const pipelineStages = computed<PipelineStage[]>(() => {
  const k = kpi.value
  return [
    {
      key: 'pending',
      index: '01',
      label: '待派单',
      hint: '等待分配运力',
      count: Number(k?.pendingOrders ?? 0),
      color: '#f0a93b',
      statuses: ['CREATED', 'PLANNING', 'QUEUED'],
    },
    {
      key: 'inflight',
      index: '02',
      label: '执行中',
      hint: '已派单 / 正在飞行',
      count: Number(k?.inFlightOrders ?? 0),
      color: '#38e8ff',
      statuses: ['ASSIGNED', 'IN_FLIGHT'],
    },
    {
      key: 'delivered',
      index: '03',
      label: '已送达',
      hint: '完成交付',
      count: Number(k?.deliveredOrders ?? 0),
      color: '#2fd6a5',
      statuses: ['DELIVERED'],
    },
    {
      key: 'failed',
      index: '04',
      label: '异常',
      hint: '失败 / 需要复盘',
      count: Number(k?.failedOrders ?? 0),
      color: '#ff6060',
      statuses: ['FAILED'],
    },
  ]
})

/** 阶段占比：让计数条有长度含义，而不是四个孤立数字 */
function stagePercent(stage: PipelineStage): number {
  const total = Number(kpi.value?.totalOrders ?? 0)
  if (!Number.isFinite(total) || total <= 0) return 0
  return Math.min(100, Math.round((stage.count / total) * 100))
}

/** 点阶段 = 按该组状态筛选；再点一次取消（与单个状态下拉互斥） */
function toggleStage(stage: PipelineStage) {
  activeStage.value = activeStage.value === stage.key ? undefined : stage.key
  filterStatus.value = undefined
  pagination.current = 1
  void loadOrders()
}

function onStatusFilterChange() {
  activeStage.value = undefined
  pagination.current = 1
  void loadOrders()
}

// ---------------------------------------------------------------------------
// 查询
// ---------------------------------------------------------------------------

async function loadOrders() {
  loading.value = true
  try {
    const stage = pipelineStages.value.find((item) => item.key === activeStage.value)
    const page: PageResponse<Order> = await orderApi.list({
      status: filterStatus.value,
      // Spring 会把逗号分隔的枚举串解析成 List<OrderStatus>
      statuses: stage?.statuses.join(','),
      priority: filterPriority.value,
      page: pagination.current - 1, // 后端 page 从 0 开始
      size: pagination.pageSize,
    })
    orders.value = page.content
    pagination.total = page.totalElements
  } catch {
    /* client.ts 已提示 */
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  pagination.current = 1
  void loadOrders()
}

function handlePageChange(page: number, pageSize: number) {
  pagination.current = page
  pagination.pageSize = pageSize
  void loadOrders()
}

// ---------------------------------------------------------------------------
// 卡片展示：距离 / SLA / 阶段轨
// ---------------------------------------------------------------------------

/** 直线距离（haversine）：下单时就能感知"这单有多远" */
function straightDistance(order?: Order | null): number | null {
  if (!order) return null
  const p = order.pickup
  const d = order.dropoff
  if (!p || !d) return null
  const earthRadius = 6371008.8
  const dLat = ((d.lat - p.lat) * Math.PI) / 180
  const dLon = ((d.lon - p.lon) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((p.lat * Math.PI) / 180) * Math.cos((d.lat * Math.PI) / 180) * Math.sin(dLon / 2) ** 2
  return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)))
}

type Tone = 'pending' | 'active' | 'success' | 'danger' | 'muted'

function statusTone(order: Order): Tone {
  switch (order.status) {
    case 'CREATED':
    case 'PLANNING':
    case 'QUEUED':
      return 'pending'
    case 'ASSIGNED':
    case 'IN_FLIGHT':
      return 'active'
    case 'DELIVERED':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'muted'
  }
}

interface SlaInfo {
  text: string
  tone: Tone
}

/** SLA 文案：已完单给结论，进行中给"还剩多久 / 超时多久" */
function slaOf(order: Order): SlaInfo {
  if (order.status === 'DELIVERED') {
    // 准时判定用实际送达时间（deliveredAt），而不是任务完成（含返航）时间
    if (order.deliveredAt && order.expectDeliveryAt) {
      const seconds = dayjs(order.deliveredAt).diff(dayjs(order.expectDeliveryAt), 'second')
      return seconds <= 0
        ? { text: `已送达（提前 ${formatDuration(-seconds)}）`, tone: 'success' }
        : { text: `已送达（超时 ${formatDuration(seconds)}）`, tone: 'danger' }
    }
    return {
      text: `完成交付 · ${formatDateTime(order.deliveredAt ?? order.updatedAt)}`,
      tone: 'success',
    }
  }
  if (order.status === 'FAILED') {
    return { text: order.failReason ? `失败：${order.failReason}` : '订单失败', tone: 'danger' }
  }
  if (order.status === 'CANCELLED') {
    return { text: '订单已取消', tone: 'muted' }
  }
  if (!order.expectDeliveryAt) {
    return { text: '未设置期望送达时间', tone: 'muted' }
  }
  const due = dayjs(order.expectDeliveryAt)
  const minutes = due.diff(dayjs(), 'minute')
  if (minutes < 0) {
    return { text: `已超时 ${formatDuration(Math.abs(minutes) * 60)}（期望 ${formatDateTime(order.expectDeliveryAt)}）`, tone: 'danger' }
  }
  if (minutes < 30) {
    return { text: `剩余 ${minutes} 分钟（期望 ${formatDateTime(order.expectDeliveryAt)}）`, tone: 'pending' }
  }
  return { text: `剩余 ${formatDuration(minutes * 60)}（期望 ${formatDateTime(order.expectDeliveryAt)}）`, tone: 'active' }
}

interface CardStep {
  label: string
  state: 'done' | 'active' | 'todo' | 'error'
}

/** 卡片上的四段阶段轨：下单 → 派单 → 起飞 → 交付 */
function cardSteps(order: Order): CardStep[] {
  const s = order.status
  if (s === 'FAILED') {
    return [
      { label: '下单', state: 'done' },
      { label: '派单', state: 'done' },
      { label: '异常', state: 'error' },
      { label: '交付', state: 'todo' },
    ]
  }
  if (s === 'CANCELLED') {
    return [
      { label: '下单', state: 'done' },
      { label: '派单', state: 'todo' },
      { label: '起飞', state: 'todo' },
      { label: '交付', state: 'todo' },
    ]
  }
  if (s === 'DELIVERED') {
    return [
      { label: '下单', state: 'done' },
      { label: '派单', state: 'done' },
      { label: '起飞', state: 'done' },
      { label: '交付', state: 'done' },
    ]
  }
  if (s === 'IN_FLIGHT') {
    return [
      { label: '下单', state: 'done' },
      { label: '派单', state: 'done' },
      { label: '飞行', state: 'active' },
      { label: '交付', state: 'todo' },
    ]
  }
  if (s === 'ASSIGNED') {
    return [
      { label: '下单', state: 'done' },
      { label: '派单', state: 'done' },
      { label: '起飞', state: 'active' },
      { label: '交付', state: 'todo' },
    ]
  }
  return [
    { label: '下单', state: 'done' },
    { label: '派单', state: 'active' },
    { label: '起飞', state: 'todo' },
    { label: '交付', state: 'todo' },
  ]
}

// ---------------------------------------------------------------------------
// 新建订单
// ---------------------------------------------------------------------------

const createOpen = ref(false)
// 这里用 any 而不是精确类型：Ant Design 的 FormInstance / 规则类型在模板里的推导很啰嗦，
// 用 any 换取可读性，代价是失去这部分类型检查 —— 对表单这种"结构简单、字段固定"的场景是划算的。
const formRef = ref<any>(null)

interface OrderForm {
  customerName: string
  customerPhone: string
  cargoName: string
  weightKg: number
  priority: Priority
  pickupAddress: string
  pickupLat: number
  pickupLon: number
  dropoffAddress: string
  dropoffLat: number
  dropoffLon: number
  expectDeliveryAt: Dayjs | null
  remark: string
}

function emptyForm(): OrderForm {
  return {
    customerName: '',
    customerPhone: '',
    cargoName: '',
    weightKg: 2,
    priority: 'NORMAL',
    pickupAddress: '人民广场站',
    pickupLat: 31.2304,
    pickupLon: 121.4737,
    dropoffAddress: '',
    dropoffLat: 31.24,
    dropoffLon: 121.49,
    expectDeliveryAt: dayjs().add(2, 'hour'),
    remark: '',
  }
}

const form = reactive<OrderForm>(emptyForm())

// ---------------------------------------------------------------------------
// 地图点选取货点 / 送货点
// ---------------------------------------------------------------------------

/** 当前正在地图上拾取哪个点 */
const activePointKey = ref<'pickup' | 'dropoff'>('pickup')

/** 拾取地图的标题 */
const activePointLabel = computed(() => (activePointKey.value === 'pickup' ? '取货点' : '送货点'))

/**
 * 当前点的经纬度双向绑定。
 *
 * <p>表单里取货点与送货点是两组独立字段，而地图一次只能拾取一个点，
 * 所以用一对可写计算属性把"当前激活的那个点"接到地图的 v-model 上 ——
 * 比写两套地图、或者在地图组件里塞 if/else 都干净。
 */
const activeLat = computed<number | null>({
  get: () => (activePointKey.value === 'pickup' ? form.pickupLat : form.dropoffLat),
  set: (value) => {
    if (value === null) return
    if (activePointKey.value === 'pickup') form.pickupLat = value
    else form.dropoffLat = value
  },
})
const activeLon = computed<number | null>({
  get: () => (activePointKey.value === 'pickup' ? form.pickupLon : form.dropoffLon),
  set: (value) => {
    if (value === null) return
    if (activePointKey.value === 'pickup') form.pickupLon = value
    else form.dropoffLon = value
  },
})

/** 另一个点画在地图上做参照（点与点之间离多远一眼可见） */
const otherPoints = computed(() =>
  activePointKey.value === 'pickup'
    ? [{ label: '送货点', lat: form.dropoffLat, lon: form.dropoffLon, color: '#52c41a' }]
    : [{ label: '取货点', lat: form.pickupLat, lon: form.pickupLon, color: '#1677ff' }],
)

/**
 * 空域参照层：把禁飞区 / 楼群画出来。
 *
 * <p>下单的人最需要知道的是"我要送到的那栋楼是不是在禁飞区里"——
 * 事后再从调度失败原因里反推，比下单时就看得到要绕得多。
 * 拉取失败静默降级（少一层参照，不影响下单）。
 */
const zones = ref<AirspaceZone[]>([])

const formRules: any = {
  customerName: [{ required: true, message: '请输入客户名称' }],
  cargoName: [{ required: true, message: '请输入货物名称' }],
  weightKg: [{ required: true, message: '请输入重量' }],
}

/** 直线距离预览：让下单的人立刻知道"这单有多远"，避免下出飞不到的单 */
const previewDistance = computed(() => {
  if (form.pickupLat == null || form.dropoffLat == null) return null
  const earthRadius = 6371008.8
  const dLat = ((form.dropoffLat - form.pickupLat) * Math.PI) / 180
  const dLon = ((form.dropoffLon - form.pickupLon) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((form.pickupLat * Math.PI) / 180) *
      Math.cos((form.dropoffLat * Math.PI) / 180) *
      Math.sin(dLon / 2) ** 2
  return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)))
})

function openCreate() {
  Object.assign(form, emptyForm())
  createOpen.value = true
}

async function submitCreate() {
  try {
    await formRef.value?.validate()
  } catch {
    return // 校验失败时 Ant Design 已在字段下方标红
  }
  submitting.value = true
  try {
    const created = await orderApi.create({
      customerName: form.customerName,
      customerPhone: form.customerPhone || undefined,
      cargoName: form.cargoName,
      weightKg: form.weightKg,
      priority: form.priority,
      pickup: { lat: form.pickupLat, lon: form.pickupLon, address: form.pickupAddress || undefined },
      dropoff: { lat: form.dropoffLat, lon: form.dropoffLon, address: form.dropoffAddress || undefined },
      expectDeliveryAt: form.expectDeliveryAt ? form.expectDeliveryAt.toISOString() : undefined,
      remark: form.remark || undefined,
    })
    message.success(`订单 ${created.orderNo} 已创建`)
    createOpen.value = false
    resetAndLoad()
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 派单 / 取消
// ---------------------------------------------------------------------------

const strategyOptions = Object.entries(STRATEGY_TEXT).map(([value, label]) => ({ value, label }))
/** 手动派单与批量派单共用同一策略 */
const preferredStrategy = ref<string>('NEAREST_AVAILABLE')

async function handleDispatch(order: Order) {
  dispatchingId.value = order.id
  try {
    const result = await orderApi.dispatch(order.id, preferredStrategy.value)
    message.success(`订单 ${order.orderNo}：${result.message}`)
    resetAndLoad()
  } catch {
    // 常见失败：载重不足 / 续航不够 / 距离超半径 / 无可行航路。
    // 前端给两条路：看可用机（事前筛选）或决策明细（事后原因）。
    message.info('可点击「可用机」查看当前能承接的无人机，或在「决策明细」里查看淘汰原因')
  } finally {
    dispatchingId.value = null
  }
}

async function handleCancel(order: Order) {
  try {
    await orderApi.cancel(order.id)
    message.success(`订单 ${order.orderNo} 已取消`)
    resetAndLoad()
  } catch {
    /* 已提示 */
  }
}

// ---------------------------------------------------------------------------
// 决策明细抽屉
// ---------------------------------------------------------------------------

const decisionOpen = ref(false)
const decisionLoading = ref(false)
const decisions = ref<DispatchDecision[]>([])
const decisionOrder = ref<Order | null>(null)

async function openDecisions(order: Order) {
  decisionOrder.value = order
  decisionOpen.value = true
  decisionLoading.value = true
  try {
    decisions.value = await dispatchApi.decisions(order.id)
  } catch {
    decisions.value = []
  } finally {
    decisionLoading.value = false
  }
}

/** 把 detail 里的 JSON 字符串格式化后再展示，方便直接读 */
function prettyDetail(detail?: string): string {
  if (!detail) return '（无分项数据）'
  try {
    return JSON.stringify(JSON.parse(detail), null, 2)
  } catch {
    return detail
  }
}

const decisionColumns = [
  { title: '排名', dataIndex: 'rankNo', key: 'rankNo', width: 70 },
  { title: '无人机', dataIndex: 'droneCode', key: 'droneCode', width: 110 },
  { title: '得分', dataIndex: 'score', key: 'score', width: 90 },
  { title: '结果', dataIndex: 'selected', key: 'selected', width: 90 },
  { title: '淘汰原因 / 说明', dataIndex: 'rejectedReason', key: 'rejectedReason' },
]

/** 入围候选（通过全部硬条件、有排名的） */
const passedDecisions = computed(() => decisions.value.filter((d) => d.rankNo != null))
/** 被淘汰的候选：收进折叠面板，不与入围者混排 */
const rejectedDecisions = computed(() => decisions.value.filter((d) => d.rankNo == null))

// ---------------------------------------------------------------------------
// 可用无人机预览（派单前"搜罗"）
// ---------------------------------------------------------------------------

const candidatesOpen = ref(false)
const candidatesLoading = ref(false)
const candidatesPreview = ref<CandidatePreview | null>(null)
const candidatesOrder = ref<Order | null>(null)

async function openCandidates(order: Order) {
  candidatesOrder.value = order
  candidatesOpen.value = true
  candidatesLoading.value = true
  try {
    candidatesPreview.value = await dispatchApi.candidates(order.id, preferredStrategy.value)
  } catch {
    candidatesPreview.value = null
  } finally {
    candidatesLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 批量派单与导出
// ---------------------------------------------------------------------------

const batchLimit = ref(5)
const batchLoading = ref(false)

async function handleBatchDispatch() {
  batchLoading.value = true
  try {
    const results = await dispatchApi.autoDispatch(preferredStrategy.value, batchLimit.value)
    const succeeded = results.filter((item) => item.task).length
    message.success(`批量派单完成：尝试 ${results.length} 单，成功 ${succeeded} 单`)
    resetAndLoad()
  } catch {
    /* 已提示 */
  } finally {
    batchLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 链路追踪抽屉
// ---------------------------------------------------------------------------

const traceOpen = ref(false)
const traceLoading = ref(false)
const trace = ref<OrderTrace | null>(null)
const traceOrder = ref<Order | null>(null)
const traceAutoRefresh = ref(true)
const traceTab = ref<'timeline' | 'tasks' | 'decisions'>('timeline')

/** 链路号：对外沟通时用它定位一整条链路（订单号 + TRACE 前缀） */
const traceId = computed(() => (traceOrder.value ? `TRACE-${traceOrder.value.orderNo}` : '—'))
/** 抽屉头部信息优先用聚合接口返回的最新快照，避免列表数据过期 */
const traceOrderView = computed<Order | null>(() => trace.value?.order ?? traceOrder.value)
const traceEvents = computed<EventLogItem[]>(() => trace.value?.events ?? [])
const traceTasks = computed<DispatchTask[]>(() => trace.value?.tasks ?? [])
/**
 * 决策列表：入围者按名次排在前面，被淘汰的沉底。
 *
 * <p>数据库对 rankNo 为 NULL 的排序在不同库上不一致（H2 默认 NULL 在前），
 * 直接展示会让"选中/入围"沉到 26 条候选的最后，链路面板第一屏全是淘汰原因。
 */
const traceDecisions = computed<DispatchDecision[]>(() => {
  const list = [...(trace.value?.decisions ?? [])]
  return list.sort((a, b) => {
    const rankA = a.rankNo ?? Number.MAX_SAFE_INTEGER
    const rankB = b.rankNo ?? Number.MAX_SAFE_INTEGER
    return rankA === rankB ? a.id - b.id : rankA - rankB
  })
})
/** 最新一次执行任务（重派场景下 id 最大的一条） */
const traceLatestTask = computed<DispatchTask | undefined>(() => traceTasks.value[0])

let traceTimer: number | null = null

function clearTraceTimer() {
  if (traceTimer !== null) {
    window.clearInterval(traceTimer)
    traceTimer = null
  }
}

/** 抽屉打开期间每 5 秒同步一次；失败静默，避免网络抖动时弹窗刷屏 */
function startTraceTimer() {
  clearTraceTimer()
  traceTimer = window.setInterval(() => {
    if (traceOpen.value && traceAutoRefresh.value) {
      void loadTrace({ silent: true })
    }
  }, 5000)
}

async function loadTrace(options: { silent?: boolean; spinner?: boolean } = {}) {
  const id = traceOrder.value?.id
  if (!id) return
  if (options.spinner) traceLoading.value = true
  try {
    trace.value = await orderApi.trace(id, options.silent)
  } catch {
    if (!options.silent) trace.value = null
  } finally {
    if (options.spinner) traceLoading.value = false
  }
}

function openTrace(order: Order) {
  traceOrder.value = order
  trace.value = null
  traceTab.value = 'timeline'
  traceOpen.value = true
  void loadTrace({ spinner: true })
  startTraceTimer()
}

function manualRefreshTrace() {
  void loadTrace({ spinner: true })
}

async function copyTraceId() {
  const text = traceId.value
  try {
    await navigator.clipboard.writeText(text)
    message.success(`已复制链路号 ${text}`)
  } catch {
    // 非 HTTPS / 浏览器不允许剪贴板时退化为可见提示，用户可手动复制
    message.info(`链路号：${text}`)
  }
}

/** 链路阶段轨：下单 → 派单 → 起飞 → 交付（异常单最后一节标红） */
interface TraceStage {
  key: string
  label: string
  time?: string
  state: 'done' | 'active' | 'pending' | 'error'
}

const traceStages = computed<TraceStage[]>(() => {
  const order = traceOrderView.value
  if (!order) return []
  const task = traceLatestTask.value
  const failed = order.status === 'FAILED'
  const cancelled = order.status === 'CANCELLED'
  const delivered = order.status === 'DELIVERED'
  return [
    { key: 'created', label: '下单', time: order.createdAt, state: 'done' },
    {
      key: 'assigned',
      label: '派单',
      time: task?.assignedAt,
      state: task ? 'done' : failed || cancelled ? 'error' : 'active',
    },
    {
      key: 'started',
      label: '起飞',
      time: task?.startedAt,
      state: task?.startedAt ? 'done' : task ? 'active' : 'pending',
    },
    {
      key: 'finished',
      label: failed ? '异常终止' : '完成交付',
      time: failed ? task?.finishedAt : delivered ? task?.finishedAt ?? order.updatedAt : undefined,
      state: failed ? 'error' : delivered ? 'done' : 'pending',
    },
  ]
})

/** 链路总耗时：下单 → 任务结束（未完单则算到当前） */
const traceElapsed = computed(() => {
  const order = traceOrderView.value
  if (!order?.createdAt) return '—'
  const end = traceLatestTask.value?.finishedAt ?? order.updatedAt
  const seconds = dayjs(end).diff(dayjs(order.createdAt), 'second')
  return seconds >= 0 ? formatDuration(seconds) : '—'
})

/** 事件圆点颜色：与严重度语义一致，时间线扫一眼就知道哪里出过问题 */
const SEVERITY_HEX: Record<string, string> = {
  DEBUG: '#5b7c99',
  INFO: '#38e8ff',
  WARN: '#f0a93b',
  ERROR: '#ff6060',
}

function severityColor(severity: string): string {
  return SEVERITY_HEX[severity] ?? '#5b7c99'
}

/** 把事件 payload 的 JSON 字符串格式化（不是 JSON 就原样展示） */
function prettyJson(raw?: string): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

onUnmounted(clearTraceTimer)

// ---------------------------------------------------------------------------
// 初始化
// ---------------------------------------------------------------------------

onMounted(() => {
  void loadOrders()
  void loadZones()
  void loadFallbackKpi()
  void openTraceFromRoute()
})

/**
 * 从任务页「订单链路」跳转过来时自动打开对应订单的追踪抽屉。
 *
 * <p>任务页知道 orderId，但用户关心的是整条订单链路（下单→派单→任务→事件）；
 * 直接用 trace 接口拿快照，省掉"先查订单再查链路"的两次请求。
 */
async function openTraceFromRoute(): Promise<void> {
  const raw = route.query.traceOrderId
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  if (!Number.isFinite(id) || id <= 0) return
  try {
    const snapshot = await orderApi.trace(id)
    trace.value = snapshot
    traceOrder.value = snapshot.order
    traceTab.value = 'timeline'
    traceOpen.value = true
    startTraceTimer()
  } catch {
    /* 已提示 */
  }
}

/** WebSocket 尚未推 KPI 时的兜底：只拉一次，之后由实时通道接管 */
async function loadFallbackKpi(): Promise<void> {
  try {
    fallbackKpi.value = await dashboardApi.kpi()
  } catch {
    fallbackKpi.value = null
  }
}

/** 空域只作为下单地图的参照层，失败静默（不打断下单流程） */
async function loadZones(): Promise<void> {
  try {
    zones.value = await airspaceApi.list()
  } catch {
    zones.value = []
  }
}
</script>

<template>
  <div class="orders-view">
    <!-- ------------------------------ 链路流水线 ------------------------------ -->
    <section class="pipeline">
      <div class="pipeline-head">
        <div>
          <div class="pipeline-title">
            <span class="pipeline-led" aria-hidden="true" />
            订单链路流水线
            <span class="pipeline-live">LIVE</span>
          </div>
          <div class="pipeline-sub">从下单、派单、起飞到交付 · 每个节点都可追踪 · 点击阶段筛选</div>
        </div>
        <div class="pipeline-meta">
          <span>总订单 <strong>{{ kpi?.totalOrders ?? 0 }}</strong></span>
          <span>准时率 <strong>{{ formatRate(kpi?.onTimeRate) }}</strong></span>
          <span>平均配送 <strong>{{ formatDuration(kpi?.avgDeliverySeconds) }}</strong></span>
          <a-button
            v-if="activeStage"
            type="link"
            size="small"
            class="pipeline-clear"
            @click="activeStage = undefined; resetAndLoad()"
          >
            清除阶段筛选
          </a-button>
        </div>
      </div>

      <div class="pipeline-track">
        <button
          v-for="stage in pipelineStages"
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
            {{ stage.count }}<em>单</em>
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
          v-model:value="filterStatus"
          placeholder="全部状态"
          style="width: 130px"
          allow-clear
          :options="Object.entries(ORDER_STATUS_TEXT).map(([value, label]) => ({ value, label }))"
          @change="onStatusFilterChange"
        />
        <a-select
          v-model:value="filterPriority"
          placeholder="全部优先级"
          style="width: 130px"
          allow-clear
          :options="Object.entries(PRIORITY_TEXT).map(([value, label]) => ({ value, label }))"
          @change="resetAndLoad"
        />
        <a-button :loading="loading" @click="resetAndLoad">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>

        <a-divider type="vertical" />

        <a-select v-model:value="preferredStrategy" style="width: 210px" :options="strategyOptions" />
        <a-input-number v-model:value="batchLimit" :min="1" :max="50" style="width: 80px" />
        <a-button type="primary" :loading="batchLoading" @click="handleBatchDispatch">
          批量派单
        </a-button>
      </a-space>

      <template #extra>
        <a-space>
          <a-button @click="reportApi.tasksCsv()">
            <template #icon><DownloadOutlined /></template>
            导出任务 CSV
          </a-button>
          <a-button type="primary" @click="openCreate">
            <template #icon><PlusOutlined /></template>
            新建订单
          </a-button>
        </a-space>
      </template>
    </a-card>

    <!-- ------------------------------ 订单卡片网格 ------------------------------ -->
    <a-spin :spinning="loading">
      <div v-if="orders.length" class="order-grid">
        <article
          v-for="order in orders"
          :key="order.id"
          class="order-card"
          :class="`tone-${statusTone(order)}`"
        >
          <header class="card-head">
            <div class="card-no">
              <span class="card-sigil" aria-hidden="true" />
              <span class="text-mono">{{ order.orderNo }}</span>
            </div>
            <div class="card-tags">
              <a-tag :color="PRIORITY_COLOR[order.priority]">
                {{ PRIORITY_TEXT[order.priority] ?? order.priority }}
              </a-tag>
              <a-tag :color="ORDER_STATUS_COLOR[order.status]">
                {{ ORDER_STATUS_TEXT[order.status] ?? order.status }}
              </a-tag>
            </div>
          </header>

          <!-- 路线示意：起点 → 终点，中间标直线距离 -->
          <div class="route-graph">
            <div class="route-axis" aria-hidden="true">
              <span class="route-node is-start" />
              <span class="route-track"><span class="route-flow" /></span>
              <span class="route-node is-end" />
            </div>
            <div class="route-points">
              <div class="route-point">
                <span class="point-badge">取</span>
                <div class="point-body">
                  <span class="point-addr">{{ order.pickup?.address ?? '未填写取货地址' }}</span>
                  <span class="point-geo text-mono">
                    {{ Number(order.pickup?.lat).toFixed(4) }}, {{ Number(order.pickup?.lon).toFixed(4) }}
                  </span>
                </div>
              </div>
              <div class="route-distance">
                <span class="text-mono">{{ formatDistance(straightDistance(order)) }}</span>
                <em>直线</em>
              </div>
              <div class="route-point">
                <span class="point-badge is-drop">送</span>
                <div class="point-body">
                  <span class="point-addr">{{ order.dropoff?.address ?? '未填写送货地址' }}</span>
                  <span class="point-geo text-mono">
                    {{ Number(order.dropoff?.lat).toFixed(4) }}, {{ Number(order.dropoff?.lon).toFixed(4) }}
                  </span>
                </div>
              </div>
            </div>
          </div>

          <div class="card-facts">
            <span><em>客户</em>{{ order.customerName }}</span>
            <span><em>货物</em>{{ order.cargoName }}</span>
            <span><em>重量</em>{{ Number(order.weightKg).toFixed(2) }} kg</span>
            <span><em>无人机</em>{{ droneLabel(order) }}</span>
          </div>

          <!-- 实时进度：仅执行中的订单展示，让卡片"活"起来 -->
          <div v-if="liveFrameOf(order)" class="live-strip">
            <div class="live-progress">
              <span class="live-label">实时进度</span>
              <span class="live-bar">
                <i :style="{ width: `${Number(liveFrameOf(order)?.progressPct ?? 0)}%` }" />
              </span>
              <span class="live-value text-mono">
                {{ Number(liveFrameOf(order)?.progressPct ?? 0).toFixed(0) }}%
              </span>
            </div>
            <div class="live-meta text-mono">
              <span>电量 {{ Number(liveFrameOf(order)?.batteryPct ?? 0).toFixed(0) }}%</span>
              <span>高度 {{ Number(liveFrameOf(order)?.altitudeM ?? 0).toFixed(0) }} m</span>
              <span>速度 {{ Number(liveFrameOf(order)?.speedMps ?? 0).toFixed(1) }} m/s</span>
            </div>
          </div>

          <!-- 阶段轨：卡片上直接看出这单走到哪一步 -->
          <div class="card-rail">
            <span
              v-for="step in cardSteps(order)"
              :key="step.label"
              class="rail-step"
              :class="`is-${step.state}`"
            >
              <i class="rail-dot" />
              <span class="rail-label">{{ step.label }}</span>
            </span>
          </div>

          <div class="card-sla" :class="`is-${slaOf(order).tone}`">
            <ClockCircleOutlined />
            <span class="sla-text">{{ slaOf(order).text }}</span>
          </div>

          <footer class="card-actions">
            <a-button type="primary" size="small" class="trace-btn" @click="openTrace(order)">
              <template #icon><NodeIndexOutlined /></template>
              链路追踪
            </a-button>
            <a-button
              size="small"
              :loading="dispatchingId === order.id"
              :disabled="!['CREATED', 'QUEUED', 'PLANNING'].includes(order.status)"
              @click="handleDispatch(order)"
            >
              <template #icon><SendOutlined /></template>
              派单
            </a-button>
            <a-button
              size="small"
              :loading="candidatesLoading && candidatesOrder?.id === order.id"
              :disabled="!['CREATED', 'QUEUED', 'PLANNING'].includes(order.status)"
              @click="openCandidates(order)"
            >
              可用机
            </a-button>
            <a-button size="small" @click="openDecisions(order)">决策</a-button>
            <a-popconfirm
              title="确认取消该订单？"
              ok-text="取消订单"
              cancel-text="再想想"
              :disabled="!['CREATED', 'PLANNING', 'QUEUED', 'ASSIGNED'].includes(order.status)"
              @confirm="handleCancel(order)"
            >
              <a-button
                size="small"
                danger
                :disabled="!['CREATED', 'PLANNING', 'QUEUED', 'ASSIGNED'].includes(order.status)"
              >
                取消
              </a-button>
            </a-popconfirm>
          </footer>
        </article>
      </div>

      <a-empty
        v-else
        class="orders-empty"
        description="当前筛选下暂无订单，点击右上角「新建订单」开始"
      />
    </a-spin>

    <div v-if="pagination.total > 0" class="pagination-bar">
      <a-pagination
        :current="pagination.current"
        :page-size="pagination.pageSize"
        :total="pagination.total"
        :page-size-options="['6', '9', '12', '24']"
        show-size-changer
        :show-total="(total: number) => `共 ${total} 单`"
        @change="handlePageChange"
      />
    </div>

    <!-- ------------------------------ 新建订单弹窗 ------------------------------ -->
    <a-modal
      v-model:open="createOpen"
      title="新建配送订单"
      width="880px"
      :confirm-loading="submitting"
      ok-text="创建订单"
      cancel-text="取消"
      @ok="submitCreate"
    >
      <a-form ref="formRef" :model="form" :rules="formRules" layout="vertical" class="order-form">
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="客户名称" name="customerName">
              <a-input v-model:value="form.customerName" placeholder="如：上海市第一人民医院" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="联系电话" name="customerPhone">
              <a-input v-model:value="form.customerPhone" placeholder="选填" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="货物名称" name="cargoName">
              <a-input v-model:value="form.cargoName" placeholder="如：急救血浆" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="重量（kg）" name="weightKg">
              <a-input-number v-model:value="form.weightKg" :min="0.01" :step="0.5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="优先级" name="priority">
              <a-select
                v-model:value="form.priority"
                :options="Object.entries(PRIORITY_TEXT).map(([value, label]) => ({ value, label }))"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-divider orientation="left" class="form-divider">取货点 / 送货点（在地图上点选）</a-divider>
        <a-row :gutter="16">
          <!-- 左列：地址 + 坐标（精确微调） -->
          <a-col :span="9">
            <a-form-item label="取货点地址" name="pickupAddress">
              <a-input v-model:value="form.pickupAddress" placeholder="如：人民广场站" />
            </a-form-item>
            <a-form-item label="取货点坐标（可精确微调）" name="pickupLat">
              <div class="geo-row">
                <a-input-number
                  v-model:value="form.pickupLat"
                  :precision="6"
                  class="num"
                  addon-before="纬"
                />
                <a-input-number
                  v-model:value="form.pickupLon"
                  :precision="6"
                  class="num"
                  addon-before="经"
                />
              </div>
            </a-form-item>

            <a-form-item label="送货点地址" name="dropoffAddress">
              <a-input v-model:value="form.dropoffAddress" placeholder="如：市一医院" />
            </a-form-item>
            <a-form-item label="送货点坐标（可精确微调）" name="dropoffLat">
              <div class="geo-row">
                <a-input-number
                  v-model:value="form.dropoffLat"
                  :precision="6"
                  class="num"
                  addon-before="纬"
                />
                <a-input-number
                  v-model:value="form.dropoffLon"
                  :precision="6"
                  class="num"
                  addon-before="经"
                />
              </div>
            </a-form-item>
          </a-col>

          <!-- 右列：一张地图 + 切换"正在选哪个点" -->
          <a-col :span="15">
            <a-form-item>
              <div class="pick-switch">
                <span class="pick-switch-label">正在拾取</span>
                <!-- 这张地图一次只拾取一个点，所以先明确"现在点哪儿"，避免"点了一下却改错点" -->
                <a-radio-group v-model:value="activePointKey" size="small" button-style="solid">
                  <a-radio-button value="pickup">取货点</a-radio-button>
                  <a-radio-button value="dropoff">送货点</a-radio-button>
                </a-radio-group>
                <span class="pick-switch-hint">另一个点以绿色/蓝色圆点画在图上做参照</span>
              </div>
              <PointPickerMap
                v-model:lat="activeLat"
                v-model:lon="activeLon"
                :label="activePointLabel"
                :others="otherPoints"
                :zones="zones"
                height="320px"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-alert
          v-if="previewDistance !== null"
          type="info"
          show-icon
          class="distance-alert"
          :message="`起终点直线距离约 ${formatDistance(previewDistance)}（无人机需往返，实际航程约为其 2 倍以上）`"
        />

        <a-row :gutter="12" class="mt-16">
          <a-col :span="12">
            <a-form-item label="期望送达时间" name="expectDeliveryAt">
              <a-date-picker
                v-model:value="form.expectDeliveryAt"
                show-time
                format="YYYY-MM-DD HH:mm"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="备注" name="remark">
              <a-input v-model:value="form.remark" placeholder="选填，如：需防震" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
    </a-modal>

    <!-- ------------------------------ 链路追踪抽屉 ------------------------------ -->
    <a-drawer
      v-model:open="traceOpen"
      width="900"
      class="trace-drawer"
      @close="clearTraceTimer"
    >
      <template #title>
        <div class="trace-title">
          <NodeIndexOutlined class="trace-title-icon" />
          <span class="text-mono">{{ traceId }}</span>
          <a-button type="text" size="small" class="copy-btn" @click="copyTraceId">
            <template #icon><CopyOutlined /></template>
          </a-button>
          <span class="trace-title-sub">订单全链路追踪</span>
        </div>
      </template>

      <template #extra>
        <a-space size="small">
          <span class="trace-auto-label">自动刷新</span>
          <a-switch v-model:checked="traceAutoRefresh" size="small" />
          <a-button size="small" :loading="traceLoading" @click="manualRefreshTrace">
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
        </a-space>
      </template>

      <a-spin :spinning="traceLoading">
        <template v-if="trace">
          <!-- 订单摘要 -->
          <div class="trace-banner">
            <div class="trace-banner-top">
              <span class="trace-order-no text-mono">{{ traceOrderView?.orderNo }}</span>
              <a-tag v-if="traceOrderView" :color="ORDER_STATUS_COLOR[traceOrderView.status]">
                {{ ORDER_STATUS_TEXT[traceOrderView.status] ?? traceOrderView.status }}
              </a-tag>
              <a-tag v-if="traceOrderView" :color="PRIORITY_COLOR[traceOrderView.priority]">
                {{ PRIORITY_TEXT[traceOrderView.priority] ?? traceOrderView.priority }}
              </a-tag>
              <span v-if="traceOrderView?.failReason" class="trace-fail-flag">
                {{ traceOrderView.failReason }}
              </span>
            </div>

            <div class="trace-route">
              <span class="trace-route-point">
                <AimOutlined />
                {{ traceOrderView?.pickup?.address ?? '未填写取货地址' }}
              </span>
              <span class="trace-route-arrow">→</span>
              <span class="trace-route-point">
                <AimOutlined />
                {{ traceOrderView?.dropoff?.address ?? '未填写送货地址' }}
              </span>
              <span class="trace-route-distance text-mono">
                {{ formatDistance(straightDistance(traceOrderView)) }}
              </span>
            </div>

            <div class="trace-facts">
              <span><em>客户</em>{{ traceOrderView?.customerName ?? '—' }}</span>
              <span>
                <em>货物</em>{{ traceOrderView?.cargoName ?? '—' }} · {{ Number(traceOrderView?.weightKg ?? 0).toFixed(2) }} kg
              </span>
              <span><em>期望送达</em>{{ formatDateTime(traceOrderView?.expectDeliveryAt) }}</span>
              <span><em>实际送达</em>{{ formatDateTime(traceOrderView?.deliveredAt) }}</span>
              <span><em>执行无人机</em>{{ traceLatestTask?.droneCode ?? (traceOrderView ? droneLabel(traceOrderView) : '—') }}</span>
            </div>
          </div>

          <!-- 链路阶段轨 -->
          <div class="trace-rail">
            <div
              v-for="stage in traceStages"
              :key="stage.key"
              class="trace-node"
              :class="`is-${stage.state}`"
            >
              <span class="trace-node-dot" />
              <span class="trace-node-label">{{ stage.label }}</span>
              <span class="trace-node-time text-mono">
                {{ stage.time ? formatTime(stage.time) : '—' }}
              </span>
            </div>
          </div>

          <!-- 链路读数 -->
          <div class="trace-summary">
            <div class="summary-item">
              <em>链路总耗时</em>
              <strong>{{ traceElapsed }}</strong>
            </div>
            <div class="summary-item">
              <em>执行任务</em>
              <strong>{{ traceTasks.length }}</strong>
            </div>
            <div class="summary-item">
              <em>事件节点</em>
              <strong>{{ traceEvents.length }}</strong>
            </div>
            <div class="summary-item">
              <em>派单候选</em>
              <strong>{{ traceDecisions.length }}</strong>
            </div>
            <div class="summary-item">
              <em>最新任务</em>
              <strong class="text-mono small">{{ traceLatestTask?.taskNo ?? '尚未派单' }}</strong>
            </div>
          </div>

          <!-- 时间线 / 任务 / 决策 -->
          <a-tabs v-model:activeKey="traceTab" class="trace-tabs">
            <a-tab-pane key="timeline" :tab="`事件时间线 (${traceEvents.length})`">
              <ul v-if="traceEvents.length" class="timeline">
                <li
                  v-for="event in traceEvents"
                  :key="event.id"
                  class="timeline-item"
                  :style="{ '--event-color': severityColor(event.severity) }"
                >
                  <span class="timeline-dot" />
                  <div class="timeline-body">
                    <div class="timeline-head">
                      <span class="timeline-type">{{ eventTypeText(event.eventType) }}</span>
                      <span class="timeline-time text-mono">{{ formatTime(event.ts) }}</span>
                      <span v-if="event.simTimeS != null" class="timeline-sim text-mono">
                        T+{{ formatDuration(Number(event.simTimeS)) }}
                      </span>
                      <a-tag
                        v-if="event.severity !== 'INFO'"
                        :color="event.severity === 'ERROR' ? 'error' : event.severity === 'WARN' ? 'warning' : 'default'"
                        class="timeline-sev"
                      >
                        {{ event.severity }}
                      </a-tag>
                    </div>
                    <div class="timeline-msg">{{ event.message }}</div>
                    <div v-if="event.taskId || event.droneId" class="timeline-refs text-mono">
                      <span v-if="event.taskId">任务 #{{ event.taskId }}</span>
                      <span v-if="event.droneId">无人机 #{{ event.droneId }}</span>
                    </div>
                    <details v-if="event.payload" class="timeline-payload">
                      <summary>原始数据</summary>
                      <pre>{{ prettyJson(event.payload) }}</pre>
                    </details>
                  </div>
                </li>
              </ul>
              <a-empty v-else description="该订单还没有事件记录（下单后由派单与仿真引擎产生）" />
            </a-tab-pane>

            <a-tab-pane key="tasks" :tab="`任务执行 (${traceTasks.length})`">
              <div v-if="traceTasks.length" class="task-list">
                <div v-for="task in traceTasks" :key="task.id" class="task-card">
                  <div class="task-head">
                    <span class="text-mono">{{ task.taskNo }}</span>
                    <a-tag :color="TASK_STATUS_COLOR[task.status]">
                      {{ TASK_STATUS_TEXT[task.status] ?? task.status }}
                    </a-tag>
                    <span class="task-drone text-mono">{{ task.droneCode ?? `#${task.droneId}` }}</span>
                    <span class="task-strategy">
                      {{ STRATEGY_TEXT[task.strategy] ?? task.strategy }}
                    </span>
                  </div>
                  <a-progress
                    :percent="Number(task.progressPct ?? 0)"
                    size="small"
                    :show-info="false"
                    class="task-progress"
                  />
                  <div class="task-grid">
                    <span><em>计划里程</em>{{ formatDistance(task.plannedDistanceM) }}</span>
                    <span><em>实际里程</em>{{ formatDistance(task.actualDistanceM) }}</span>
                    <span><em>计划能耗</em>{{ formatEnergy(task.plannedEnergyWh) }}</span>
                    <span><em>实际能耗</em>{{ formatEnergy(task.consumedEnergyWh) }}</span>
                    <span>
                      <em>电量</em>{{ formatPercent(task.batteryStartPct) }} → {{ formatPercent(task.batteryEndPct) }}
                    </span>
                    <span><em>执行耗时</em>{{ formatDuration(task.durationS) }}</span>
                    <span><em>派发时间</em>{{ formatDateTime(task.assignedAt) }}</span>
                    <span><em>起飞时间</em>{{ formatDateTime(task.startedAt) }}</span>
                    <span><em>结束时间</em>{{ formatDateTime(task.finishedAt) }}</span>
                  </div>
                  <a-alert
                    v-if="task.failReason"
                    type="error"
                    show-icon
                    class="task-fail"
                    :message="task.failReason"
                  />
                </div>
              </div>
              <a-empty v-else description="该订单还没有执行任务（可在卡片上点击「派单」）" />
            </a-tab-pane>

            <a-tab-pane key="decisions" :tab="`派单决策 (${traceDecisions.length})`">
              <div v-if="traceDecisions.length" class="decision-list">
                <div
                  v-for="item in traceDecisions"
                  :key="item.id"
                  class="decision-row"
                  :class="{ 'is-selected': item.selected }"
                >
                  <span class="decision-rank">
                    {{ item.rankNo != null ? `#${item.rankNo}` : '—' }}
                  </span>
                  <span class="decision-drone text-mono">{{ item.droneCode ?? `#${item.droneId}` }}</span>
                  <span class="decision-score text-mono">
                    {{ item.score != null ? Number(item.score).toFixed(2) : '—' }}
                  </span>
                  <a-tag v-if="item.selected" color="success">选中</a-tag>
                  <a-tag v-else-if="item.rankNo != null" color="warning">入围</a-tag>
                  <a-tag v-else>淘汰</a-tag>
                  <span class="decision-reason">
                    {{ item.rejectedReason ?? '通过全部硬条件（载重 / 电量 / 往返续航 / 半径）' }}
                  </span>
                </div>
              </div>
              <a-empty v-else description="该订单还没有派单决策记录" />
            </a-tab-pane>
          </a-tabs>
        </template>

        <a-empty v-else description="暂无链路数据，请点击刷新重试" />
      </a-spin>
    </a-drawer>

    <!-- ------------------------------ 决策明细抽屉 ------------------------------ -->
    <a-drawer v-model:open="decisionOpen" title="调度决策明细" width="760">
      <a-alert
        type="info"
        show-icon
        class="mb-16"
        message="这张表回答了「为什么派这架无人机」"
        description="每次派单都会评估全部无人机：通过硬条件（载重、电量、往返续航、搜索半径）的进入排名，未通过的写明淘汰原因。得分越小越优先。"
      />

      <a-descriptions :column="2" size="small" bordered class="mb-16">
        <a-descriptions-item label="订单号">{{ decisionOrder?.orderNo ?? '—' }}</a-descriptions-item>
        <a-descriptions-item label="货物">
          {{ decisionOrder?.cargoName ?? '—' }} / {{ decisionOrder?.weightKg ?? '—' }} kg
        </a-descriptions-item>
        <a-descriptions-item label="优先级">
          <a-tag v-if="decisionOrder" :color="PRIORITY_COLOR[decisionOrder.priority]">
            {{ PRIORITY_TEXT[decisionOrder.priority] }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="策略">
          {{ decisions[0] ? (STRATEGY_TEXT[decisions[0].strategy] ?? decisions[0].strategy) : '—' }}
        </a-descriptions-item>
      </a-descriptions>

      <a-table
        :columns="decisionColumns"
        :data-source="passedDecisions"
        :loading="decisionLoading"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'rankNo'">
            <a-tag v-if="record.rankNo" color="blue">{{ record.rankNo }}</a-tag>
            <span v-else class="text-muted">—</span>
          </template>

          <template v-else-if="column.key === 'droneCode'">
            <span class="text-mono">{{ record.droneCode ?? `#${record.droneId}` }}</span>
          </template>

          <template v-else-if="column.key === 'score'">
            {{ record.score !== undefined && record.score !== null ? Number(record.score).toFixed(2) : '—' }}
          </template>

          <template v-else-if="column.key === 'selected'">
            <a-tag v-if="record.selected" color="success">已选中</a-tag>
            <a-tag v-else color="warning">未入选</a-tag>
          </template>

          <template v-else-if="column.key === 'rejectedReason'">
            <span class="text-muted">通过全部硬条件（载重 / 电量 / 往返续航 / 半径）</span>
          </template>
        </template>

        <template #expandedRowRender="{ record }">
          <pre class="detail-json">{{ prettyDetail(record.detail) }}</pre>
        </template>

        <template #emptyText>
          <a-empty description="该订单还没有派单记录" />
        </template>
      </a-table>

      <!-- 被淘汰的候选：默认收起，不与入围者混排 -->
      <a-collapse v-if="rejectedDecisions.length" class="rejected-collapse">
        <a-collapse-panel
          :header="`被淘汰的候选（${rejectedDecisions.length} 架，不参与派单）`"
        >
          <ul class="rejected-list">
            <li v-for="item in rejectedDecisions" :key="item.id">
              <span class="text-mono">{{ item.droneCode ?? `#${item.droneId}` }}</span>
              <span class="reject-reason">{{ item.rejectedReason ?? '未知原因' }}</span>
            </li>
          </ul>
        </a-collapse-panel>
      </a-collapse>
    </a-drawer>

    <!-- ------------------------------ 可用无人机预览抽屉 ------------------------------ -->
    <a-drawer
      v-model:open="candidatesOpen"
      :title="`可承接本单的无人机 · ${candidatesOrder?.orderNo ?? ''}`"
      width="760"
    >
      <a-alert
        v-if="candidatesPreview"
        :type="candidatesPreview.qualifiedCount > 0 ? 'success' : 'warning'"
        show-icon
        class="mb-16"
        :message="
          candidatesPreview.qualifiedCount > 0
            ? `搜罗到 ${candidatesPreview.qualifiedCount} 架可用无人机（直飞 ${candidatesPreview.directCount} 架 / 需补能 ${candidatesPreview.relayCount} 架，筛选自 ${candidatesPreview.evaluatedTotal} 架）`
            : `全部 ${candidatesPreview.evaluatedTotal} 架无人机都无法承接本单`
        "
        :description="
          candidatesPreview.qualifiedCount > 0
            ? '入围不只看电量：系统会在「调机 → 取货 → 送达 → 返航」的完整航路上搜索可落脚的补能站（最多经停 3 站）。直飞够电的标绿色，需要中途补能的标橙色，悬停可看经停明细。'
            : '常见原因：货物超过机型最大载重、电量低于安全冗余、或者即使把沿途补能站都用上也飞不完全程。可在「决策明细」里查看每架被淘汰的具体原因。'
        "
      />

      <a-table
        :columns="[
          { title: '排名', dataIndex: 'rank', key: 'rank', width: 64 },
          { title: '无人机', dataIndex: 'droneCode', key: 'droneCode', width: 110 },
          { title: '机型', key: 'model', width: 150 },
          { title: '最大载重', dataIndex: 'maxPayloadKg', key: 'payload', width: 90 },
          { title: '距取货点', dataIndex: 'distanceToPickupM', key: 'distance', width: 100 },
          { title: '电量', dataIndex: 'batteryPct', key: 'battery', width: 80 },
          { title: '续航方案', key: 'reachability', width: 150 },
          { title: '在途任务', dataIndex: 'activeTaskCount', key: 'tasks', width: 80 },
          { title: '得分', dataIndex: 'score', key: 'score', width: 80 },
        ]"
        :data-source="candidatesPreview?.qualified ?? []"
        :loading="candidatesLoading"
        :pagination="false"
        row-key="droneId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'rank'">
            <a-tag :color="record.rank === 1 ? 'green' : 'blue'">{{ record.rank }}</a-tag>
          </template>

          <template v-else-if="column.key === 'droneCode'">
            <span class="text-mono">{{ record.droneCode }}</span>
          </template>

          <template v-else-if="column.key === 'model'">
            <div>{{ record.modelCode }}</div>
            <div class="text-muted sub-line">{{ record.modelName }}</div>
          </template>

          <template v-else-if="column.key === 'payload'">
            {{ Number(record.maxPayloadKg).toFixed(1) }} kg
          </template>

          <template v-else-if="column.key === 'distance'">
            {{ formatDistance(record.distanceToPickupM) }}
          </template>

          <template v-else-if="column.key === 'battery'">
            {{ Number(record.batteryPct).toFixed(0) }}%
          </template>

          <template v-else-if="column.key === 'reachability'">
            <a-tooltip
              :title="`${record.reachability}｜可用电量 ${Number(record.usableEnergyWh).toFixed(0)}Wh，全程耗电 ${Number(record.missionEnergyWh).toFixed(0)}Wh`"
            >
              <div class="reach-cell">
                <a-tag v-if="!record.chargingStops" color="green">直飞可达</a-tag>
                <a-tag v-else color="orange">经停 {{ record.chargingStops }} 站</a-tag>
                <span v-if="record.chargingStopNames?.length" class="reach-stops">
                  {{ record.chargingStopNames.join(' → ') }}
                </span>
              </div>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'score'">
            {{ Number(record.score).toFixed(2) }}
          </template>
        </template>

        <template #emptyText>
          <a-empty description="没有满足条件的无人机" />
        </template>
      </a-table>
    </a-drawer>
  </div>
</template>

<style scoped>
.orders-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

/* =============================================================================
   链路流水线
   ============================================================================= */
.pipeline {
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

/* 顶部扫描线：和顶栏同款，暗示这是一块在线读数板 */
.pipeline::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 26%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  animation: pipeline-scan 8s linear infinite;
}

@keyframes pipeline-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(480%);
  }
}

.pipeline-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 12px;
}

.pipeline-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: #e9fbff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.3);
}

/* 标题前的呼吸指示灯 */
.pipeline-led {
  width: 6px;
  height: 6px;
  background: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  transform: rotate(45deg);
  animation: pipeline-led 1.8s ease-in-out infinite;
}

@keyframes pipeline-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.pipeline-live {
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

.pipeline-sub {
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.62);
}

.pipeline-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 12px;
  color: rgba(150, 199, 232, 0.72);
}

.pipeline-meta strong {
  margin-left: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 14px;
  color: #d8f7ff;
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.35);
}

.pipeline-clear {
  padding: 0;
  font-size: 12px;
}

/* 四个阶段横向排布，中间用连接线串起来 */
.pipeline-track {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.pipeline-track::before {
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
  padding: 9px 12px 10px;
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

/* 左侧色条表达阶段语义 */
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
  font-size: 26px;
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
   订单卡片网格
   ============================================================================= */
.order-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.order-card {
  --card-tone: #38e8ff;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 12px;
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

.order-card:hover {
  transform: translateY(-2px);
  border-color: rgba(56, 232, 255, 0.42);
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.46),
    0 0 16px rgba(56, 232, 255, 0.14);
}

/* 右上角格栅：与 KPI 卡同一套仪表质感 */
.order-card::after {
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

.order-card.tone-pending {
  --card-tone: #f0a93b;
}
.order-card.tone-active {
  --card-tone: #38e8ff;
}
.order-card.tone-success {
  --card-tone: #2fd6a5;
}
.order-card.tone-danger {
  --card-tone: #ff6060;
}
.order-card.tone-muted {
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

/* ---------------- 路线示意 ---------------- */
.route-graph {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid rgba(56, 232, 255, 0.1);
  background: rgba(6, 18, 31, 0.55);
}

.route-axis {
  position: relative;
  display: flex;
  align-items: center;
  height: 12px;
  padding: 0 4px;
}

.route-node {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #0c1b2c;
  border: 1.5px solid #38e8ff;
  box-shadow: 0 0 7px rgba(56, 232, 255, 0.8);
}

.route-node.is-end {
  border-color: #2fd6a5;
  box-shadow: 0 0 7px rgba(47, 214, 165, 0.8);
}

.route-track {
  position: relative;
  flex: 1 1 auto;
  height: 1px;
  margin: 0 2px;
  overflow: hidden;
  background: repeating-linear-gradient(
    90deg,
    rgba(56, 232, 255, 0.4) 0 5px,
    transparent 5px 10px
  );
}

/* 沿航线流动的光点：静默表达"链路是通的" */
.route-flow {
  position: absolute;
  top: -1px;
  left: 0;
  width: 26%;
  height: 3px;
  background: linear-gradient(90deg, transparent, rgba(159, 240, 255, 0.95), transparent);
  animation: route-flow 2.8s linear infinite;
}

@keyframes route-flow {
  from {
    transform: translateX(-120%);
  }
  to {
    transform: translateX(420%);
  }
}

.route-points {
  display: flex;
  align-items: center;
  gap: 8px;
}

.route-point {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  min-width: 0;
  flex: 1 1 0;
}

.point-badge {
  flex: 0 0 auto;
  width: 17px;
  height: 17px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  line-height: 1;
  color: #071a2a;
  background: #38e8ff;
  clip-path: polygon(4px 0, 100% 0, 100% calc(100% - 4px), calc(100% - 4px) 100%, 0 100%, 0 4px);
}

.point-badge.is-drop {
  background: #2fd6a5;
}

.point-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.point-addr {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.4;
  color: rgba(216, 240, 255, 0.9);
}

.point-geo {
  font-size: 10px;
  line-height: 1.3;
  color: rgba(150, 199, 232, 0.52);
}

.route-distance {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 6px;
  border-left: 1px solid rgba(56, 232, 255, 0.12);
  border-right: 1px solid rgba(56, 232, 255, 0.12);
}

.route-distance .text-mono {
  font-size: 11px;
  color: #9ff0ff;
}

.route-distance em {
  font-size: 10px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.5);
}

/* ---------------- 事实行 ---------------- */
.card-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  line-height: 1.5;
  color: rgba(216, 240, 255, 0.85);
}

.card-facts em {
  margin-right: 4px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.55);
}

/* ---------------- 实时进度 ---------------- */
.live-strip {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 9px;
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(56, 232, 255, 0.05);
}

.live-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.live-label {
  flex: 0 0 auto;
  font-size: 11px;
  color: rgba(150, 199, 232, 0.72);
}

.live-bar {
  position: relative;
  flex: 1 1 auto;
  height: 4px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.12);
}

.live-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #1677ff, #38e8ff);
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.8);
  transition: width 0.5s linear;
}

.live-value {
  flex: 0 0 auto;
  font-size: 11px;
  color: #9ff0ff;
}

.live-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 3px 12px;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.6);
}

/* ---------------- 阶段轨 ---------------- */
.card-rail {
  display: flex;
  align-items: center;
  gap: 0;
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

/* 节点之间的连线：由每个节点的 ::before 画在左侧 */
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

/* ---------------- SLA ---------------- */
.card-sla {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(150, 199, 232, 0.66);
}

.card-sla .sla-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-sla.is-success {
  color: rgba(47, 214, 165, 0.9);
}
.card-sla.is-pending {
  color: rgba(240, 169, 59, 0.95);
}
.card-sla.is-danger {
  color: #ff9a9a;
}

/* ---------------- 操作区 ---------------- */
.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid rgba(56, 232, 255, 0.1);
}

/* 链路追踪是这张卡的主操作，给一点呼吸感 */
.card-actions .trace-btn {
  font-weight: 600;
  letter-spacing: 0.4px;
}

.orders-empty {
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
   链路追踪抽屉
   ============================================================================= */
.trace-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.trace-title-icon {
  color: #38e8ff;
  filter: drop-shadow(0 0 6px rgba(56, 232, 255, 0.8));
}

.trace-title .text-mono {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #eafcff;
}

.copy-btn {
  color: rgba(150, 199, 232, 0.7);
}

.trace-title-sub {
  margin-left: 4px;
  padding-left: 8px;
  border-left: 1px solid rgba(56, 232, 255, 0.25);
  font-size: 12px;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.6);
}

.trace-auto-label {
  font-size: 12px;
  color: rgba(150, 199, 232, 0.68);
}

/* ---------------- 摘要 ---------------- */
.trace-banner {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid rgba(56, 232, 255, 0.2);
  background:
    radial-gradient(520px 160px at 8% -40%, rgba(56, 232, 255, 0.14), transparent 70%),
    linear-gradient(180deg, rgba(14, 35, 55, 0.9), rgba(7, 19, 32, 0.9));
  clip-path: polygon(12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%, 0 12px);
}

.trace-banner-top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.trace-order-no {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.6px;
  color: #f0fdff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.4);
}

.trace-fail-flag {
  padding: 1px 8px;
  font-size: 11px;
  color: #ffb4b4;
  border: 1px solid rgba(255, 96, 96, 0.4);
  background: rgba(255, 96, 96, 0.1);
}

.trace-route {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: rgba(216, 240, 255, 0.86);
}

.trace-route-point {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.trace-route-arrow {
  color: rgba(56, 232, 255, 0.7);
}

.trace-route-distance {
  margin-left: 2px;
  padding: 0 6px;
  font-size: 11px;
  color: #9ff0ff;
  border-left: 1px solid rgba(56, 232, 255, 0.2);
}

.trace-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  font-size: 12px;
  color: rgba(216, 240, 255, 0.82);
}

.trace-facts em {
  margin-right: 4px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.55);
}

/* ---------------- 阶段轨 ---------------- */
.trace-rail {
  display: flex;
  align-items: flex-start;
  padding: 4px 2px 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid rgba(56, 232, 255, 0.1);
}

.trace-node {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

/* 节点连线 */
.trace-node::before {
  content: '';
  position: absolute;
  top: 5px;
  right: 50%;
  width: 100%;
  height: 1px;
  background: rgba(56, 232, 255, 0.18);
}

.trace-node:first-child::before {
  display: none;
}

.trace-node.is-done::before,
.trace-node.is-active::before,
.trace-node.is-error::before {
  background: rgba(56, 232, 255, 0.55);
}

.trace-node-dot {
  position: relative;
  z-index: 1;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #0a1a2b;
  border: 1px solid rgba(56, 232, 255, 0.3);
}

.trace-node.is-done .trace-node-dot {
  background: #2fd6a5;
  border-color: #2fd6a5;
  box-shadow: 0 0 8px rgba(47, 214, 165, 0.85);
}

.trace-node.is-active .trace-node-dot {
  background: #38e8ff;
  border-color: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  animation: rail-pulse 1.4s ease-out infinite;
}

.trace-node.is-error .trace-node-dot {
  background: #ff6060;
  border-color: #ff6060;
  box-shadow: 0 0 10px rgba(255, 96, 96, 0.9);
}

.trace-node-label {
  font-size: 11px;
  color: rgba(216, 240, 255, 0.86);
}

.trace-node.is-pending .trace-node-label {
  color: rgba(150, 199, 232, 0.45);
}

.trace-node-time {
  font-size: 10px;
  color: rgba(150, 199, 232, 0.5);
}

/* ---------------- 读数条 ---------------- */
.trace-summary {
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

/* ---------------- 时间线 ---------------- */
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

/* 竖线：除最后一条外都画 */
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

.timeline-sev {
  margin-inline-end: 0;
  transform: scale(0.85);
  transform-origin: left center;
}

.timeline-msg {
  font-size: 12px;
  line-height: 1.6;
  color: rgba(216, 240, 255, 0.82);
  word-break: break-word;
}

.timeline-refs {
  display: flex;
  gap: 10px;
  margin-top: 2px;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.45);
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

/* ---------------- 任务卡 ---------------- */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-card {
  padding: 10px 12px;
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: linear-gradient(180deg, rgba(13, 32, 50, 0.8), rgba(7, 19, 32, 0.8));
}

.task-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
}

.task-head .text-mono {
  font-size: 12px;
  font-weight: 600;
  color: #d9f4ff;
}

.task-drone {
  color: #9ff0ff !important;
}

.task-strategy {
  margin-left: auto;
  font-size: 11px;
  color: rgba(150, 199, 232, 0.62);
}

.task-progress {
  margin-bottom: 6px;
}

.task-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px 12px;
  font-size: 12px;
  color: rgba(216, 240, 255, 0.82);
}

.task-grid em {
  display: inline-block;
  min-width: 62px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.55);
}

.task-fail {
  margin-top: 8px;
}

/* ---------------- 决策列表 ---------------- */
.decision-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.decision-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border: 1px solid rgba(56, 232, 255, 0.1);
  background: rgba(8, 21, 35, 0.55);
  font-size: 12px;
  color: rgba(216, 240, 255, 0.82);
}

.decision-row.is-selected {
  border-color: rgba(47, 214, 165, 0.45);
  background: rgba(47, 214, 165, 0.07);
  box-shadow: inset 0 0 18px rgba(47, 214, 165, 0.08);
}

.decision-rank {
  flex: 0 0 auto;
  width: 30px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: #9ff0ff;
}

.decision-drone {
  flex: 0 0 auto;
  min-width: 78px;
  font-weight: 600;
  color: #d9f4ff;
}

.decision-score {
  flex: 0 0 auto;
  min-width: 52px;
  color: rgba(159, 240, 255, 0.8);
}

.decision-reason {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(150, 199, 232, 0.66);
}

/* =============================================================================
   原有表单 / 抽屉辅助样式
   ============================================================================= */
.sub-line {
  font-size: 12px;
  line-height: 1.5;
}

.order-form {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 4px;
}

.form-divider {
  margin: 4px 0 8px;
  font-size: 13px;
  color: rgba(190, 216, 240, 0.78);
}

/* 两个坐标数字框并排：宽度均分，不再各占一整列 */
.geo-row {
  display: flex;
  gap: 8px;
}

.num {
  width: 100%;
}

/* 拾取地图上方的"正在拾取哪个点"切换条 */
.pick-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.pick-switch-label {
  font-size: 12px;
  color: rgba(180, 208, 232, 0.55);
}

.pick-switch-hint {
  font-size: 12px;
  color: rgba(180, 208, 232, 0.38);
}

.distance-alert {
  margin-bottom: 4px;
}

/* 续航方案：标签 + 经停站名，站名过长时省略（完整内容看 tooltip） */
.reach-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  line-height: 1.4;
}

.reach-stops {
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: rgba(180, 208, 232, 0.55);
}

.reject-reason {
  color: #ff7875;
}

.detail-json {
  margin: 0;
  padding: 8px 10px;
  background: #0b1a2c;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  max-height: 200px;
  overflow: auto;
}

.rejected-collapse {
  margin-top: 12px;
}

.rejected-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rejected-list li {
  display: flex;
  gap: 10px;
  font-size: 12px;
  line-height: 1.5;
}

/* 窄屏：流水线从四列降为两列，卡片单列 */
@media (max-width: 900px) {
  .pipeline-track {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .pipeline-track::before {
    display: none;
  }

  .trace-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .pipeline::after,
  .pipeline-led,
  .route-flow,
  .rail-step.is-active .rail-dot,
  .trace-node.is-active .trace-node-dot {
    animation: none;
  }
}
</style>
