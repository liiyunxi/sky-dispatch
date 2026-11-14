<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs, { type Dayjs } from 'dayjs'
import { DownloadOutlined, PlusOutlined, ReloadOutlined, SendOutlined } from '@ant-design/icons-vue'
import {
  airspaceApi,
  dispatchApi,
  orderApi,
  reportApi,
  type AirspaceZone,
  type CandidatePreview,
  type DispatchDecision,
  type Order,
  type OrderStatus,
  type PageResponse,
  type Priority,
} from '@/api'
import PointPickerMap from '@/components/map/PointPickerMap.vue'
import {
  ORDER_STATUS_COLOR,
  ORDER_STATUS_TEXT,
  PRIORITY_COLOR,
  PRIORITY_TEXT,
  STRATEGY_TEXT,
  formatDateTime,
  formatDistance,
} from '@/utils/format'

/**
 * 订单管理。
 *
 * 这是"人机协作"最密集的一页，三个交互值得说明：
 *
 *  1. **派单要选策略**：同一批订单换个策略会派给不同无人机，
 *     所以"派单"不是一个按钮，而是"按钮 + 策略下拉"。默认策略由后端配置决定。
 *  2. **决策可解释**：点「决策明细」能看到每架候选机的得分、排名和淘汰原因。
 *     这是把"调度黑盒"变透明的关键，也是排障时唯一有用的信息。
 *  3. **失败不是 bug**：订单派单失败（比如往返续航不够）会在决策明细里写清原因，
 *     页面不做"红色报错"处理，而是引导运营去看原因。
 */

const loading = ref(false)
const submitting = ref(false)
const dispatchingId = ref<number | null>(null)

const orders = ref<Order[]>([])
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })

const filterStatus = ref<OrderStatus | undefined>(undefined)
const filterPriority = ref<Priority | undefined>(undefined)

// ---------------------------------------------------------------------------
// 查询
// ---------------------------------------------------------------------------

async function loadOrders() {
  loading.value = true
  try {
    const page: PageResponse<Order> = await orderApi.list({
      status: filterStatus.value,
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

function handleTableChange(page: { current?: number; pageSize?: number }) {
  pagination.current = page.current ?? 1
  pagination.pageSize = page.pageSize ?? 10
  void loadOrders()
}

function resetAndLoad() {
  pagination.current = 1
  void loadOrders()
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
/** 默认策略取自后端配置，这里只作为 UI 初始值 */
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

const batchStrategy = ref<string>('NEAREST_AVAILABLE')
const batchLimit = ref(5)
const batchLoading = ref(false)

async function handleBatchDispatch() {
  batchLoading.value = true
  try {
    const results = await dispatchApi.autoDispatch(batchStrategy.value, batchLimit.value)
    const succeeded = results.filter((item) => item.task).length
    message.success(`批量派单完成：尝试 ${results.length} 单，成功 ${succeeded} 单`)
    resetAndLoad()
  } catch {
    /* 已提示 */
  } finally {
    batchLoading.value = false
  }
}

onMounted(() => {
  void loadOrders()
  void loadZones()
})

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
    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-select
          v-model:value="filterStatus"
          placeholder="全部状态"
          style="width: 130px"
          allow-clear
          :options="Object.entries(ORDER_STATUS_TEXT).map(([value, label]) => ({ value, label }))"
          @change="resetAndLoad"
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

    <!-- ------------------------------ 订单表格 ------------------------------ -->
    <a-card :bordered="false" size="small" class="table-card">
      <a-table
        :columns="[
          { title: '订单号', dataIndex: 'orderNo', key: 'orderNo', width: 150, fixed: 'left' },
          { title: '客户 / 货物', dataIndex: 'customerName', key: 'customer', width: 180 },
          { title: '重量', dataIndex: 'weightKg', key: 'weightKg', width: 80 },
          { title: '优先级', dataIndex: 'priority', key: 'priority', width: 88 },
          { title: '状态', dataIndex: 'status', key: 'status', width: 96 },
          { title: '取货点', dataIndex: 'pickup', key: 'pickup', width: 170 },
          { title: '送货点', dataIndex: 'dropoff', key: 'dropoff', width: 170 },
          { title: '直线距离', key: 'distance', width: 100 },
          { title: '期望送达', dataIndex: 'expectDeliveryAt', key: 'expectDeliveryAt', width: 160 },
          { title: '执行无人机', dataIndex: 'assignedDroneId', key: 'assignedDroneId', width: 100 },
          { title: '操作', key: 'action', width: 280, fixed: 'right' },
        ]"
        :data-source="orders"
        :loading="loading"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: true,
          showTotal: (t: number) => `共 ${t} 单`,
        }"
        :scroll="{ x: 1560 }"
        row-key="id"
        size="small"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orderNo'">
            <span class="text-mono">{{ record.orderNo }}</span>
          </template>

          <template v-else-if="column.key === 'customer'">
            <div>{{ record.customerName }}</div>
            <div class="text-muted sub-line">{{ record.cargoName }}</div>
          </template>

          <template v-else-if="column.key === 'weightKg'">
            {{ Number(record.weightKg).toFixed(2) }} kg
          </template>

          <template v-else-if="column.key === 'priority'">
            <a-tag :color="PRIORITY_COLOR[record.priority as keyof typeof PRIORITY_COLOR]">
              {{ PRIORITY_TEXT[record.priority as keyof typeof PRIORITY_TEXT] ?? record.priority }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag :color="ORDER_STATUS_COLOR[record.status as keyof typeof ORDER_STATUS_COLOR]">
              {{ ORDER_STATUS_TEXT[record.status as keyof typeof ORDER_STATUS_TEXT] ?? record.status }}
            </a-tag>
            <a-tooltip v-if="record.failReason" :title="record.failReason">
              <span class="fail-dot">!</span>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'pickup'">
            <div class="addr">{{ record.pickup?.address ?? '—' }}</div>
            <div class="text-muted sub-line text-mono">
              {{ Number(record.pickup?.lat).toFixed(4) }}, {{ Number(record.pickup?.lon).toFixed(4) }}
            </div>
          </template>

          <template v-else-if="column.key === 'dropoff'">
            <div class="addr">{{ record.dropoff?.address ?? '—' }}</div>
            <div class="text-muted sub-line text-mono">
              {{ Number(record.dropoff?.lat).toFixed(4) }}, {{ Number(record.dropoff?.lon).toFixed(4) }}
            </div>
          </template>

          <template v-else-if="column.key === 'distance'">
            <!-- 用 haversine 在前端算一遍直线距离：让运营对"这单有多远"有直观感受 -->
            {{
              formatDistance(
                (() => {
                  const p = record.pickup
                  const d = record.dropoff
                  if (!p || !d) return null
                  const R = 6371008.8
                  const dLat = ((d.lat - p.lat) * Math.PI) / 180
                  const dLon = ((d.lon - p.lon) * Math.PI) / 180
                  const a =
                    Math.sin(dLat / 2) ** 2 +
                    Math.cos((p.lat * Math.PI) / 180) * Math.cos((d.lat * Math.PI) / 180) * Math.sin(dLon / 2) ** 2
                  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)))
                })(),
              )
            }}
          </template>

          <template v-else-if="column.key === 'expectDeliveryAt'">
            {{ formatDateTime(record.expectDeliveryAt) }}
          </template>

          <template v-else-if="column.key === 'assignedDroneId'">
            <a-tag v-if="record.assignedDroneId" color="blue">#{{ record.assignedDroneId }}</a-tag>
            <span v-else class="text-muted">—</span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space size="small">
              <a-button
                type="link"
                size="small"
                :loading="dispatchingId === record.id"
                :disabled="!['CREATED', 'QUEUED', 'PLANNING'].includes(record.status)"
                @click="handleDispatch(record)"
              >
                <template #icon><SendOutlined /></template>
                派单
              </a-button>
              <a-button
                type="link"
                size="small"
                :loading="candidatesLoading && candidatesOrder?.id === record.id"
                :disabled="!['CREATED', 'QUEUED', 'PLANNING'].includes(record.status)"
                @click="openCandidates(record)"
              >
                可用机
              </a-button>
              <a-button type="link" size="small" @click="openDecisions(record)">决策明细</a-button>
              <a-popconfirm
                title="确认取消该订单？"
                ok-text="取消订单"
                cancel-text="再想想"
                :disabled="!['CREATED', 'PLANNING', 'QUEUED', 'ASSIGNED'].includes(record.status)"
                @confirm="handleCancel(record)"
              >
                <a-button
                  type="link"
                  size="small"
                  danger
                  :disabled="!['CREATED', 'PLANNING', 'QUEUED', 'ASSIGNED'].includes(record.status)"
                >
                  取消
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="暂无订单，点击右上角「新建订单」开始" />
        </template>
      </a-table>
    </a-card>

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

.toolbar-card,
.table-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.sub-line {
  font-size: 12px;
  line-height: 1.5;
}

.addr {
  line-height: 1.5;
}

.fail-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-left: 2px;
  border-radius: 50%;
  background: #ff4d4f;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  cursor: help;
  vertical-align: middle;
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
</style>
