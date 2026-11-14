<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined, AimOutlined } from '@ant-design/icons-vue'
import { droneApi, airspaceApi } from '@/api'
import type { AirspaceZone, Drone, DroneCreateRequest, DroneStatus } from '@/api'
import DroneModel3D from '@/components/drone/DroneModel3D.vue'
import DroneAnatomy from '@/components/drone/DroneAnatomy.vue'
import PointPickerMap from '@/components/map/PointPickerMap.vue'
import {
  DRONE_STATUS_COLOR,
  DRONE_STATUS_TEXT,
  formatDistance,
  formatDuration,
  formatPercent,
} from '@/utils/format'

/**
 * 机队管理：服务端按状态过滤（机队规模通常不大，但状态过滤走服务端可与后端缓存口径一致）。
 */

type TableColumn = {
  title: string
  dataIndex?: string
  key?: string
  width?: number
  align?: 'left' | 'center' | 'right'
}

/** 注册弹窗可选型号：以后端返回过的型号为主，兜底内置常见型号，避免列表为空时无法注册 */
const FALLBACK_MODEL_CODES = ['SKY-X4', 'SKY-M8', 'SKY-H2']

/**
 * 机型能力（从机队列表里已有的同型号飞机上取）。
 *
 * <p><b>为什么注册时必须看到"能飞多远"</b>：型号名字（SKY-X4）本身不说明任何能力，
 * 运维选了型号却不知道这架机能覆盖多少公里，就只能靠试错 —— 而无人机买来是要
 * 按任务半径配置的。所以把电池容量与每公里能耗折算成"满电航程 / 可用往返航程"，
 * 直接标在型号选择下方。
 *
 * <p>数据来自 {@code droneApi.list()} 已经返回的型号字段（响应里本就带机型能力），
 * 不必再加一个接口。
 */
const modelSpecs = computed(() => {
  const map = new Map<string, Drone>()
  for (const drone of drones.value) {
    if (drone.modelCode && !map.has(drone.modelCode)) map.set(drone.modelCode, drone)
  }
  return map
})

/** 当前选中机型的说明卡；没有该型号的在册数据时返回 null（只在兜底型号下出现） */
const selectedModelSpec = computed(() => modelSpecs.value.get(form.modelCode) ?? null)

/**
 * 由机型参数折算航程（公里）。
 *
 * <p>电池容量 ÷ 每公里能耗 = 满电理论航程；实际可用的是"扣掉安全冗余后的电量"，
 * 而往返任务还要除以 2 —— 三个数都给出来，运维才知道"这架机能接多远的单"。
 */
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

/** 安全冗余（与后端 sky.dispatch.min-battery-reserve-pct 一致，默认 25%） */
const RESERVE_RATIO = 0.25

const loading = ref(false)
const submitting = ref(false)
const modalOpen = ref(false)
/** 清空筛选（allowClear）时为 undefined，表示"全部状态" */
const statusFilter = ref<DroneStatus>()
const drones = ref<Drone[]>([])
/** 行内状态下拉的待确认值：key = 无人机 id，值为待确认的新状态 */
const statusDraft = ref<Record<number, DroneStatus>>({})

/**
 * 空域数据：注册弹窗的地图要把禁飞区/楼群画出来。
 *
 * <p>起降点是无人机的基地，落在禁飞区里等于"这架机永远飞不起来" ——
 * 让运营在点选时看得见这些区域，比事后被调度器拒单要友好得多。
 * 拉取失败不影响注册流程（地图只是少一层参照），所以静默降级。
 */
const zones = ref<AirspaceZone[]>([])

// 3D 建模弹窗
const model3dOpen = ref(false)
const model3dDrone = ref<Drone | null>(null)
/** 弹窗内的页签：结构图谱（默认，回答"里面有什么"）/ 装载演示（回答"它怎么干活"） */
const detailTab = ref<'anatomy' | 'load'>('anatomy')

function openModel3d(record: Drone): void {
  model3dDrone.value = record
  detailTab.value = 'anatomy'
  model3dOpen.value = true
}

const statusOptions = (Object.keys(DRONE_STATUS_TEXT) as DroneStatus[]).map((s) => ({
  value: s,
  label: DRONE_STATUS_TEXT[s],
}))

/** 按后端返回值反推可选型号，保证注册表单里只会出现真实存在的型号 */
const modelCodeOptions = computed(() => {
  const codes = new Set<string>(FALLBACK_MODEL_CODES)
  drones.value.forEach((d) => {
    if (d.modelCode) codes.add(d.modelCode)
  })
  return [...codes].map((c) => {
    const spec = modelSpecs.value.get(c)
    // 带上载重：光看型号代码分不清哪架能拉重货
    const label = spec ? `${c} · ${spec.modelName ?? ''}（载重 ${Number(spec.maxPayloadKg).toFixed(0)}kg）` : c
    return { value: c, label }
  })
})

const columns: TableColumn[] = [
  { title: '机身编号', dataIndex: 'code', key: 'code', width: 140 },
  { title: '型号', key: 'model', width: 190 },
  { title: '状态', key: 'status', width: 120 },
  { title: '电量', key: 'battery', width: 170 },
  { title: '当前位置', key: 'position', width: 200 },
  { title: '当前任务', dataIndex: 'currentTaskId', key: 'currentTaskId', width: 100 },
  { title: '累计任务', dataIndex: 'totalTasks', key: 'totalTasks', width: 100 },
  { title: '累计航程', key: 'distance', width: 120 },
  { title: '累计飞行', key: 'flightMin', width: 120 },
  { title: '操作', key: 'action', width: 280, align: 'left' },
]

/** a-input-number 允许清空，清空后 v-model 回填 null，所以数字字段显式带上 null */
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

/** 进度条状态：低电量要让运营一眼看到风险，所以 <25% 直接标红 */
function batteryStatus(pct?: number): 'exception' | 'active' | 'success' {
  const value = pct ?? 0
  if (value < 25) return 'exception'
  if (value < 50) return 'active'
  return 'success'
}

function positionText(record: Drone): string {
  if (record.currentLat === undefined || record.currentLon === undefined) return '—'
  return `${record.currentLat.toFixed(5)}, ${record.currentLon.toFixed(5)}`
}

async function loadDrones(): Promise<void> {
  loading.value = true
  try {
    drones.value = await droneApi.list(statusFilter.value ?? undefined)
  } catch {
    // client.ts 已统一弹出错误提示，这里只负责收尾（列表保持上一次内容）
  } finally {
    loading.value = false
  }
}

function handleStatusFilterChange(): void {
  void loadDrones()
}

function openModal(): void {
  // 每次打开都回到默认值，避免上次的输入串到本次注册
  form.code = ''
  form.modelCode = FALLBACK_MODEL_CODES[0]
  form.batteryPct = 100
  form.homeLat = 31.2304
  form.homeLon = 121.4737
  modalOpen.value = true
}

async function submitRegister(): Promise<void> {
  // 机身编号可以不填：留空由后端自动生成（SKY-DR-xxxx），编号本来就是系统的事
  if (!form.modelCode) {
    message.warning('请选择机型')
    return
  }
  // 前端先做一次范围校验，省一次无效请求；清空后为 null，按"未填"处理并回落默认值
  const batteryPct = form.batteryPct ?? 100
  if (batteryPct < 0 || batteryPct > 100) {
    message.warning('初始电量需在 0 ~ 100 之间')
    return
  }

  const body: DroneCreateRequest = {
    // 留空就不传，让后端走自动编号；用户填了就按用户的来
    code: form.code.trim() || undefined,
    modelCode: form.modelCode,
    batteryPct,
    home: { lat: form.homeLat ?? 31.2304, lon: form.homeLon ?? 121.4737 },
  }

  submitting.value = true
  try {
    const created = await droneApi.register(body)
    // 用后端返回的编号提示：自动生成时用户才知道"这架机叫什么"
    message.success(`无人机 ${created.code} 注册成功`)
    modalOpen.value = false
    await loadDrones()
  } catch {
    // 失败时保持弹窗打开，便于运维修改后重试
  } finally {
    submitting.value = false
  }
}

function draftOf(record: Drone): DroneStatus {
  return statusDraft.value[record.id] ?? record.status
}

/** 仅记录待确认状态：真正的变更是运维在确认气泡里点"确认变更"后才提交的 */
function stageStatus(record: Drone, value: DroneStatus): void {
  statusDraft.value = { ...statusDraft.value, [record.id]: value }
}

function cancelStatusChange(record: Drone): void {
  // 取消后把下拉框回退到后端当前值，避免界面显示成"已变更"
  statusDraft.value = { ...statusDraft.value, [record.id]: record.status }
}

async function confirmStatusChange(record: Drone): Promise<void> {
  const next = statusDraft.value[record.id]
  if (next === undefined || next === record.status) return
  try {
    await droneApi.updateStatus(record.id, next)
    message.success(`${record.code} 状态已更新为「${DRONE_STATUS_TEXT[next]}」`)
    await loadDrones()
  } catch {
    // 失败则回退下拉框，保证界面与后端一致
    cancelStatusChange(record)
  } finally {
    const rest = { ...statusDraft.value }
    delete rest[record.id]
    statusDraft.value = rest
  }
}

onMounted(() => {
  void loadDrones()
  void loadZones()
})

/** 空域只用于弹窗地图的参照层，失败静默（不打断注册） */
async function loadZones(): Promise<void> {
  try {
    zones.value = await airspaceApi.list()
  } catch {
    zones.value = []
  }
}
</script>

<template>
  <div class="drones-view">
    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-select
          v-model:value="statusFilter"
          class="filter"
          placeholder="全部状态"
          allow-clear
          :options="statusOptions"
          @change="handleStatusFilterChange"
        />
        <a-button :loading="loading" @click="loadDrones">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </a-space>

      <template #extra>
        <a-button type="primary" @click="openModal">
          <template #icon><PlusOutlined /></template>
          注册无人机
        </a-button>
      </template>
    </a-card>

    <!-- ------------------------------ 机队表格 ------------------------------ -->
    <a-card :bordered="false" size="small" class="table-card">
      <a-table
        row-key="id"
        :columns="columns"
        :data-source="drones"
        :loading="loading"
        :pagination="{ pageSize: 10, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 架` }"
        :scroll="{ x: 1500 }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'model'">
            <div class="model-cell">
              <span class="model-code">{{ record.modelCode }}</span>
              <span class="model-name">{{ record.modelName ?? '—' }}</span>
            </div>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag :color="DRONE_STATUS_COLOR[record.status as DroneStatus]">
              {{ DRONE_STATUS_TEXT[record.status as DroneStatus] ?? record.status }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'battery'">
            <a-progress
              :percent="Number(record.batteryPct ?? 0)"
              :status="batteryStatus(record.batteryPct)"
              size="small"
            />
            <span class="battery-text">{{ formatPercent(record.batteryPct, 0) }}</span>
          </template>

          <template v-else-if="column.key === 'position'">
            <span class="text-mono">{{ positionText(record) }}</span>
          </template>

          <template v-else-if="column.key === 'currentTaskId'">
            {{ record.currentTaskId ?? '—' }}
          </template>

          <template v-else-if="column.key === 'totalTasks'">
            {{ record.totalTasks ?? 0 }}
          </template>

          <template v-else-if="column.key === 'distance'">
            {{ formatDistance((record.totalDistanceKm ?? 0) * 1000) }}
          </template>

          <template v-else-if="column.key === 'flightMin'">
            {{ formatDuration((record.totalFlightMin ?? 0) * 60) }}
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space size="small">
              <a-button type="link" size="small" @click="openModel3d(record)">
                <template #icon><AimOutlined /></template>
                整机解析
              </a-button>
              <a-popconfirm
              title="确认变更该无人机状态？变更后会立即生效。"
              ok-text="确认变更"
              cancel-text="取消"
              :disabled="draftOf(record) === record.status"
              @confirm="confirmStatusChange(record)"
              @cancel="cancelStatusChange(record)"
            >
              <a-select
                class="status-select"
                size="small"
                :value="draftOf(record)"
                :options="statusOptions"
                :disabled="record.status === 'OFFLINE'"
                @change="(value: unknown) => stageStatus(record, value as DroneStatus)"
              />
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

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
        <!-- 编号不再是必填项：系统唯一、合规范、注册后不改，这三件事都轮不到人来操心 -->
        <a-form-item label="机身编号（可留空）">
          <a-input v-model:value="form.code" placeholder="留空则自动生成，如 SKY-DR-0001" allow-clear />
          <div class="field-hint">留空由系统自动编号；需要沿用自有命名规范时才填写</div>
        </a-form-item>

        <a-form-item label="机型" required>
          <a-select v-model:value="form.modelCode" :options="modelCodeOptions" />
        </a-form-item>

        <!-- 机型能力：回答"这架机能飞多远"。不显示航程，注册就成了盲选 -->
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
          <!-- 地图拾取：点一下就把经纬度填好，不必去查坐标；禁飞区/楼群画成参照，避免把基地放进禁区 -->
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
    <!--
      为什么从窄抽屉换成宽弹窗：结构图谱是左右分栏（三维 + 部件清单），
      720px 的抽屉塞不下"看细节"和"读说明"两件事，一行说明会被挤成三行。
    -->
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
          <!-- 传机型与编号：几何尺寸、机臂数量随型号变化，机身侧面喷的就是这架机的编号 -->
          <div class="anatomy-wrap">
            <DroneAnatomy
              :model-code="model3dDrone?.modelCode"
              :tail-number="model3dDrone?.code"
            />
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
  /* 外层 .app-content 已给内边距，这里只负责卡片之间的间距 */
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card,
.table-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.filter {
  width: 200px;
}

.model-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.model-code {
  font-weight: 600;
}

.model-name {
  color: rgba(180, 208, 232, 0.55);
  font-size: 12px;
}

.battery-text {
  font-size: 12px;
  color: rgba(190, 216, 240, 0.78);
}

.status-select {
  width: 100%;
}

.register-form {
  margin-top: 8px;
}

.geo-row {
  display: flex;
  gap: 12px;
  /* 拾取地图在数字框上方，留一点间距，别让两个控件贴在一起 */
  margin-top: 8px;
}

/* 机型能力卡：把"能飞多远"讲清楚，所以关键两行加底色强调 */
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

/* ---------------- 整机解析弹窗 ---------------- */
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

/* 分隔条比"竖线字符"更稳：不随字体行高跳动 */
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

/* 三维视口必须有确定高度（canvas 依赖容器尺寸），同时不撑破小屏 */
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
</style>
