<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import {
  ClockCircleOutlined,
  ClusterOutlined,
  EditOutlined,
  LinkOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import {
  infrastructureApi,
  type InfrastructureOverview,
  type InfrastructureStation,
  type InfrastructureVertiport,
  type StrategicSlot,
} from '@/api'
import { formatDuration } from '@/utils/format'

/**
 * 基础设施指挥台。
 *
 * <p>以前站点占用/排队只埋在 map tooltip 里，机巢容量只有全局配置，战略时隙完全不可见。
 * 这一页把三类资源放到同一个屏幕：
 *
 *  1. **补能网络**：每站桩位占用、排队长度、预计等待（按"还要排几轮 × 一次补能时长"估算）；
 *  2. **机巢容量**：每个起降点独立的坪位数与周转时间，可直接修改，修改后下一拍生效；
 *  3. **战略时隙**：哪条走廊、哪个高度层、哪个时隙被哪个任务/无人机占用（只读）。
 *
 * 页面每 3 秒静默刷新一次运行态，配置类操作（机巢坪位/周转）走 PATCH。
 */

const router = useRouter()

const overview = ref<InfrastructureOverview | null>(null)
const loading = ref(false)
const autoRefresh = ref(true)
let refreshTimer: number | null = null

const activeTab = ref<'stations' | 'vertiports' | 'slots'>('stations')

/** 补能网络筛选 */
const stationCity = ref<string | undefined>(undefined)
const stationKeyword = ref('')
const busyOnly = ref(false)
const stationPage = ref(1)
const stationPageSize = ref(9)

/** 机巢配置弹窗 */
const editOpen = ref(false)
const editing = ref<InfrastructureVertiport | null>(null)
const editSlots = ref(4)
const editTurnaround = ref(45)
const saving = ref(false)

const summary = computed(() => overview.value?.summary ?? null)
const slots = computed(() => overview.value?.slots.slots ?? [])

const simTimeText = computed(() =>
  overview.value ? formatDuration(overview.value.simTimeS) : '—',
)

const cities = computed(() => {
  const set = new Set((overview.value?.stations ?? []).map((station) => station.city))
  return [...set].sort()
})

function isBusy(station: InfrastructureStation): boolean {
  return station.occupancy >= station.chargeSlots || station.queueLength > 0
}

const filteredStations = computed(() => {
  const kw = stationKeyword.value.trim().toLowerCase()
  return (overview.value?.stations ?? []).filter((station) => {
    if (stationCity.value && station.city !== stationCity.value) return false
    if (busyOnly.value && !isBusy(station)) return false
    if (!kw) return true
    return [station.code, station.name, station.city]
      .filter((value) => value != null)
      .some((value) => String(value).toLowerCase().includes(kw))
  })
})

const pagedStations = computed(() => {
  const start = (stationPage.value - 1) * stationPageSize.value
  return filteredStations.value.slice(start, start + stationPageSize.value)
})

// ---------------------------------------------------------------------------
// 读数与格式化
// ---------------------------------------------------------------------------

type Tone = 'success' | 'active' | 'warning' | 'danger' | 'muted'

function stationStatus(station: InfrastructureStation): { text: string; tone: Tone } {
  if (station.enabled === false) return { text: '停用', tone: 'muted' }
  if (station.queueLength > 0) return { text: '排队中', tone: 'danger' }
  if (station.occupancy >= station.chargeSlots) return { text: '满位', tone: 'warning' }
  if (station.occupancy > 0) return { text: '补能中', tone: 'active' }
  return { text: '空闲', tone: 'success' }
}

function stationUtilization(station: InfrastructureStation): number {
  const slots = Math.max(1, station.chargeSlots)
  return Math.min(100, Math.round((station.occupancy / slots) * 100))
}

function waitText(station: InfrastructureStation): string {
  if (!station.estimatedWaitSeconds || station.estimatedWaitSeconds <= 0) return '可立即补能'
  return `约 ${formatDuration(station.estimatedWaitSeconds)}`
}

function vertiportStatus(vertiport: InfrastructureVertiport): { text: string; tone: Tone } {
  if (vertiport.nextFreeInSeconds > 0) return { text: '放行等待', tone: 'warning' }
  if (vertiport.busyDrones > 0) return { text: '运行中', tone: 'active' }
  return { text: '空闲', tone: 'success' }
}

function slotUsage(slot: StrategicSlot): number {
  const capacity = Math.max(1, slot.capacity)
  return Math.min(100, Math.round((slot.occupants.length / capacity) * 100))
}

/** 仿真秒 → mm:ss / h:mm:ss（时隙视图用） */
function simClock(seconds: number): string {
  const total = Math.max(0, Math.round(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(secs).padStart(2, '0')
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`
}

function goOrderTrace(orderId: number): void {
  if (!orderId) return
  void router.push({ path: '/orders', query: { traceOrderId: String(orderId) } })
}

// ---------------------------------------------------------------------------
// 加载 / 自动刷新
// ---------------------------------------------------------------------------

async function loadOverview(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    overview.value = await infrastructureApi.overview()
    const maxPage = Math.max(1, Math.ceil(filteredStations.value.length / stationPageSize.value))
    if (stationPage.value > maxPage) stationPage.value = maxPage
  } catch {
    /* client.ts 已提示；保留上一次数据 */
  } finally {
    if (!silent) loading.value = false
  }
}

function startTimer(): void {
  stopTimer()
  refreshTimer = window.setInterval(() => {
    if (autoRefresh.value) void loadOverview(true)
  }, 3000)
}

function stopTimer(): void {
  if (refreshTimer !== null) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
}

function handleStationPage(page: number, size: number): void {
  stationPage.value = page
  stationPageSize.value = size
}

// ---------------------------------------------------------------------------
// 机巢配置
// ---------------------------------------------------------------------------

function openEdit(vertiport: InfrastructureVertiport): void {
  editing.value = vertiport
  editSlots.value = vertiport.slots
  editTurnaround.value = vertiport.turnaroundSeconds
  editOpen.value = true
}

async function submitEdit(): Promise<void> {
  const target = editing.value
  if (!target) return
  if (editSlots.value < 1 || editSlots.value > 32) {
    message.warning('坪位数需在 1 ~ 32 之间')
    return
  }
  if (editTurnaround.value < 0 || editTurnaround.value > 600) {
    message.warning('周转时间需在 0 ~ 600 秒之间')
    return
  }
  saving.value = true
  try {
    await infrastructureApi.updateVertiport(target.id, {
      slots: editSlots.value,
      turnaroundSeconds: editTurnaround.value,
    })
    message.success(`${target.code} 已更新：${editSlots.value} 坪位 / 周转 ${editTurnaround.value}s`)
    editOpen.value = false
    await loadOverview(true)
  } catch {
    /* 已提示 */
  } finally {
    saving.value = false
  }
}

const slotColumns = [
  { title: '时隙', key: 'time', width: 160 },
  { title: '走廊扇区', dataIndex: 'sector', key: 'sector', width: 190 },
  { title: '高度层', dataIndex: 'layerM', key: 'layer', width: 90 },
  { title: '占用', key: 'usage', width: 150 },
  { title: '任务 / 无人机', key: 'occupants' },
]

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(() => {
  void loadOverview()
  startTimer()
})

onUnmounted(stopTimer)
</script>

<template>
  <div class="infra-view">
    <!-- ------------------------------ 基础设施指挥台 ------------------------------ -->
    <section class="infra">
      <div class="infra-head">
        <div>
          <div class="infra-title">
            <span class="infra-led" aria-hidden="true" />
            基础设施指挥台
            <span class="infra-live">LIVE</span>
          </div>
          <div class="infra-sub">
            补能桩位 · 机巢容量 · 战略时隙 · 仿真时间 {{ simTimeText }}
          </div>
        </div>
        <div class="infra-actions">
          <span class="auto-refresh">
            <a-switch v-model:checked="autoRefresh" size="small" />
            <span class="auto-refresh-label">3 秒刷新</span>
          </span>
          <a-button size="small" :loading="loading" @click="loadOverview()">
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
        </div>
      </div>

      <div class="infra-metrics">
        <div class="metric">
          <em>充电站</em>
          <strong>{{ summary?.stationCount ?? 0 }}<i>座</i></strong>
        </div>
        <div class="metric">
          <em>总桩位</em>
          <strong>{{ summary?.totalChargeSlots ?? 0 }}<i>个</i></strong>
        </div>
        <div class="metric">
          <em>补能中</em>
          <strong>{{ summary?.chargingDrones ?? 0 }}<i>架</i></strong>
        </div>
        <div class="metric">
          <em>排队</em>
          <strong :class="{ danger: (summary?.queuedDrones ?? 0) > 0 }">
            {{ summary?.queuedDrones ?? 0 }}<i>架</i>
          </strong>
        </div>
        <div class="metric">
          <em>最长等待</em>
          <strong class="small">
            {{ summary?.estimatedMaxWaitSeconds ? formatDuration(summary.estimatedMaxWaitSeconds) : '无' }}
          </strong>
        </div>
        <div class="metric">
          <em>机巢</em>
          <strong>{{ summary?.vertiportCount ?? 0 }}<i>座</i></strong>
        </div>
        <div class="metric">
          <em>总坪位</em>
          <strong>{{ summary?.totalPads ?? 0 }}<i>个</i></strong>
        </div>
        <div class="metric">
          <em>时隙占用</em>
          <strong :class="{ danger: (summary?.slotOccupancy ?? 0) > 0 }">
            {{ summary?.slotOccupancy ?? 0 }}<i>/ {{ summary?.slotCapacity ?? 0 }}</i>
          </strong>
        </div>
      </div>

      <div class="infra-note" :class="{ danger: (summary?.queuedDrones ?? 0) > 0 }">
        <WarningOutlined v-if="(summary?.queuedDrones ?? 0) > 0" />
        <ThunderboltOutlined v-else />
        <span v-if="(summary?.queuedDrones ?? 0) > 0">
          有 {{ summary?.queuedDrones }} 架在充电站排队，最长预计等待
          {{ formatDuration(summary?.estimatedMaxWaitSeconds ?? 0) }}；如需分流，可查看相邻站点的桩位占用。
        </span>
        <span v-else>
          补能网络当前无排队；{{ summary?.busyVertiports ?? 0 }} 座机巢处于放行/运行状态。
        </span>
      </div>
    </section>

    <!-- ------------------------------ 三类资源 ------------------------------ -->
    <a-tabs v-model:activeKey="activeTab" class="infra-tabs">
      <!-- 补能网络 -->
      <a-tab-pane key="stations" :tab="`补能网络 (${summary?.stationCount ?? 0})`">
        <a-card :bordered="false" size="small" class="toolbar-card">
          <a-space wrap>
            <a-select
              v-model:value="stationCity"
              class="filter"
              placeholder="全部城市"
              allow-clear
              :options="cities.map((city) => ({ value: city, label: city }))"
            />
            <a-input
              v-model:value="stationKeyword"
              class="filter"
              placeholder="搜索站点名称 / 编码"
              allow-clear
            />
            <span class="busy-toggle">
              <a-switch v-model:checked="busyOnly" size="small" />
              <span class="busy-label">只看繁忙 / 排队</span>
            </span>
            <a-tag class="count-tag">显示 {{ filteredStations.length }} 座</a-tag>
          </a-space>
        </a-card>

        <a-spin :spinning="loading">
          <div v-if="pagedStations.length" class="station-grid">
            <article
              v-for="station in pagedStations"
              :key="station.id"
              class="station-card"
              :class="`tone-${stationStatus(station).tone}`"
            >
              <header class="card-head">
                <div class="card-name">
                  <span class="card-sigil" aria-hidden="true" />
                  <span class="station-name">{{ station.name }}</span>
                </div>
                <a-tag :color="stationStatus(station).tone === 'success' ? 'success' : stationStatus(station).tone === 'danger' ? 'error' : stationStatus(station).tone === 'warning' ? 'warning' : 'processing'">
                  {{ stationStatus(station).text }}
                </a-tag>
              </header>

              <div class="card-sub text-mono">
                {{ station.code }}
                <span class="sub-city">{{ station.city }}</span>
              </div>

              <!-- 桩位小方块：点亮数量 = 正在充电的架数 -->
              <div class="pad-row">
                <span
                  v-for="index in Math.max(1, station.chargeSlots)"
                  :key="index"
                  class="pad"
                  :class="{ on: index <= station.occupancy }"
                />
                <span class="pad-text text-mono">
                  {{ station.occupancy }} / {{ station.chargeSlots }} 桩
                </span>
              </div>

              <div class="station-metrics">
                <div class="station-metric">
                  <em>排队</em>
                  <strong :class="{ danger: station.queueLength > 0 }">{{ station.queueLength }} 架</strong>
                </div>
                <div class="station-metric">
                  <em><ClockCircleOutlined /> 预计等待</em>
                  <strong :class="{ danger: station.queueLength > 0 }">{{ waitText(station) }}</strong>
                </div>
              </div>

              <div class="util-bar">
                <i :style="{ width: `${stationUtilization(station)}%` }" />
              </div>

              <footer class="card-foot">
                <span class="coords text-mono">
                  {{ Number(station.lat).toFixed(3) }}, {{ Number(station.lon).toFixed(3) }}
                </span>
                <span v-if="station.queueLength > 0" class="foot-warn">建议分流到相邻站点</span>
                <span v-else-if="station.remark" class="foot-remark" :title="station.remark">
                  {{ station.remark }}
                </span>
              </footer>
            </article>
          </div>
          <a-empty v-else class="infra-empty" description="当前筛选下没有充电站" />
        </a-spin>

        <div v-if="filteredStations.length > 0" class="pagination-bar">
          <a-pagination
            :current="stationPage"
            :page-size="stationPageSize"
            :total="filteredStations.length"
            :page-size-options="['6', '9', '12', '24']"
            show-size-changer
            :show-total="(total: number) => `共 ${total} 座充电站`"
            @change="handleStationPage"
          />
        </div>
      </a-tab-pane>

      <!-- 机巢容量 -->
      <a-tab-pane key="vertiports" :tab="`机巢容量 (${summary?.vertiportCount ?? 0})`">
        <div class="section-note">
          每个起降点可单独配置坪位数与地面周转时间；修改后立即影响后续放行节奏（不会清空已有任务）。
        </div>
        <div v-if="overview?.vertiports.length" class="vertiport-grid">
          <article
            v-for="vertiport in overview.vertiports"
            :key="vertiport.id"
            class="vertiport-card"
            :class="`tone-${vertiportStatus(vertiport).tone}`"
          >
            <header class="card-head">
              <div class="card-name">
                <span class="card-sigil" aria-hidden="true" />
                <span class="station-name">{{ vertiport.name }}</span>
              </div>
              <a-tag :color="vertiportStatus(vertiport).tone === 'success' ? 'success' : vertiportStatus(vertiport).tone === 'active' ? 'processing' : 'warning'">
                {{ vertiportStatus(vertiport).text }}
              </a-tag>
            </header>

            <div class="card-sub text-mono">
              {{ vertiport.code }}
              <span class="sub-city">{{ vertiport.siteKey }}</span>
            </div>

            <div class="vp-stats">
              <div><em>坪位</em><strong>{{ vertiport.slots }}</strong></div>
              <div><em>周转</em><strong>{{ Number(vertiport.turnaroundSeconds).toFixed(0) }}s</strong></div>
              <div><em>驻场</em><strong>{{ vertiport.basedDrones }}</strong></div>
              <div><em>执行中</em><strong>{{ vertiport.busyDrones }}</strong></div>
            </div>

            <div class="vp-foot">
              <span class="vp-next">
                <template v-if="vertiport.nextFreeInSeconds > 0">
                  下一批放行约 {{ formatDuration(vertiport.nextFreeInSeconds) }} 后
                </template>
                <template v-else>可立即放行</template>
                <span v-if="vertiport.turnaroundDrones > 0" class="vp-turnaround">
                  · 周转中 {{ vertiport.turnaroundDrones }} 架
                </span>
              </span>
              <a-button size="small" @click="openEdit(vertiport)">
                <template #icon><EditOutlined /></template>
                配置
              </a-button>
            </div>
          </article>
        </div>
        <a-empty v-else class="infra-empty" description="还没有机巢站点；机队注册后会自动建站" />
      </a-tab-pane>

      <!-- 战略时隙 -->
      <a-tab-pane key="slots" :tab="`战略时隙 (${slots.length})`">
        <div class="section-note">
          <template v-if="overview?.slots.enabled">
            战略层按「走廊扇区 + 高度层」预约为起飞分配时隙：每 {{ overview.slots.slotSeconds }}s 一个时隙，
            每个时隙最多 {{ overview.slots.departuresPerSlot }} 架。此视图只读。
          </template>
          <template v-else>
            战略时隙未启用（sky.strategic.enabled=false），任务不受时隙限制。
          </template>
        </div>

        <a-table
          :columns="slotColumns"
          :data-source="slots"
          row-key="key"
          size="small"
          :scroll="{ x: 980 }"
          :pagination="{ pageSize: 10, hideOnSinglePage: true }"
          :locale="{ emptyText: '当前没有被占用的战略时隙' }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'time'">
              <span class="text-mono slot-time">
                T+{{ simClock(record.startSimSec) }} → T+{{ simClock(record.endSimSec) }}
              </span>
            </template>

            <template v-else-if="column.key === 'sector'">
              <span class="text-mono">{{ record.sector }}</span>
            </template>

            <template v-else-if="column.key === 'layer'">
              <a-tag color="blue">{{ record.layerM }} m</a-tag>
            </template>

            <template v-else-if="column.key === 'usage'">
              <div class="slot-usage">
                <span class="slot-bar"><i :style="{ width: `${slotUsage(record)}%` }" /></span>
                <span class="text-mono">{{ record.occupants.length }} / {{ record.capacity }}</span>
              </div>
            </template>

            <template v-else-if="column.key === 'occupants'">
              <div class="occupant-list">
                <button
                  v-for="occupant in record.occupants"
                  :key="occupant.taskId"
                  type="button"
                  class="occupant-chip"
                  @click="goOrderTrace(occupant.orderId)"
                >
                  <span class="text-mono">{{ occupant.taskNo }}</span>
                  <span class="occupant-drone">{{ occupant.droneCode ?? `#${occupant.taskId}` }}</span>
                  <LinkOutlined />
                </button>
                <span v-if="!record.occupants.length" class="text-muted">—</span>
              </div>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <!-- ------------------------------ 机巢配置弹窗 ------------------------------ -->
    <a-modal
      v-model:open="editOpen"
      :title="`机巢配置 · ${editing?.code ?? ''}`"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="saving"
      @ok="submitEdit"
    >
      <div class="edit-body">
        <div class="edit-site text-mono">
          <ClusterOutlined />
          {{ editing?.siteKey }}
        </div>
        <a-form layout="vertical">
          <a-form-item label="坪位数（1 ~ 32）">
            <a-input-number v-model:value="editSlots" :min="1" :max="32" :step="1" style="width: 100%" />
            <div class="field-hint">坪位越多，同一站点可同时放行的架次越多（起飞间隔 = 起降占用时间 ÷ 坪位数）</div>
          </a-form-item>
          <a-form-item label="地面周转时间（秒，0 ~ 600）">
            <a-input-number v-model:value="editTurnaround" :min="0" :max="600" :step="5" style="width: 100%" />
            <div class="field-hint">降落后检查、装卸、换电所需时间；期间该机不能再次起飞</div>
          </a-form-item>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.infra-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* =============================================================================
   基础设施指挥台
   ============================================================================= */
.infra {
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

.infra::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 26%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  animation: infra-scan 8s linear infinite;
}

@keyframes infra-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(480%);
  }
}

.infra-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-bottom: 8px;
}

.infra-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: #e9fbff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.3);
}

.infra-led {
  width: 6px;
  height: 6px;
  background: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  transform: rotate(45deg);
  animation: infra-led 1.8s ease-in-out infinite;
}

@keyframes infra-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.infra-live {
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

.infra-sub {
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.62);
}

.infra-actions {
  display: flex;
  align-items: center;
  gap: 10px;
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

.infra-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin-bottom: 8px;
}

.metric {
  display: flex;
  flex-direction: column;
  min-width: 58px;
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

.metric strong.small {
  font-size: 12px;
  font-weight: 500;
}

.metric strong.danger {
  color: #ffb4b4;
  text-shadow: 0 0 10px rgba(255, 96, 96, 0.5);
}

.infra-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 5px 9px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(159, 240, 255, 0.82);
  border: 1px solid rgba(56, 232, 255, 0.18);
  background: rgba(56, 232, 255, 0.05);
}

.infra-note.danger {
  color: #ffe0b0;
  border-color: rgba(240, 169, 59, 0.35);
  background: rgba(240, 169, 59, 0.08);
}

/* =============================================================================
   页签与工具条
   ============================================================================= */
.infra-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 10px;
}

.toolbar-card {
  margin-bottom: 10px;
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.filter {
  width: 220px;
}

.busy-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.busy-label {
  font-size: 12px;
  color: rgba(150, 199, 232, 0.68);
}

.count-tag {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
}

.section-note {
  margin-bottom: 10px;
  padding: 6px 9px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(150, 199, 232, 0.66);
  border-left: 2px solid rgba(56, 232, 255, 0.4);
  background: rgba(56, 232, 255, 0.04);
}

/* =============================================================================
   充电站卡片
   ============================================================================= */
.station-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 10px;
}

.station-card,
.vertiport-card {
  --card-tone: #38e8ff;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 11px 13px;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.16);
  border-left: 3px solid var(--card-tone);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(13, 32, 50, 0.9), rgba(7, 19, 32, 0.9));
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.38);
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.station-card:hover,
.vertiport-card:hover {
  transform: translateY(-2px);
  border-color: rgba(56, 232, 255, 0.42);
}

.station-card.tone-success,
.vertiport-card.tone-success {
  --card-tone: #2fd6a5;
}
.station-card.tone-active,
.vertiport-card.tone-active {
  --card-tone: #38e8ff;
}
.station-card.tone-warning,
.vertiport-card.tone-warning {
  --card-tone: #f0a93b;
}
.station-card.tone-danger {
  --card-tone: #ff6060;
}
.station-card.tone-muted {
  --card-tone: #6b8299;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-name {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.card-sigil {
  flex: 0 0 auto;
  width: 5px;
  height: 5px;
  background: var(--card-tone);
  box-shadow: 0 0 7px var(--card-tone);
  transform: rotate(45deg);
}

.station-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
  color: #e3f7ff;
}

.card-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.55);
}

.sub-city {
  padding-left: 10px;
  border-left: 1px solid rgba(56, 232, 255, 0.14);
}

/* 桩位方块 */
.pad-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.pad {
  width: 12px;
  height: 12px;
  background: rgba(56, 232, 255, 0.08);
  border: 1px solid rgba(56, 232, 255, 0.2);
  transition: background-color 0.25s ease, box-shadow 0.25s ease;
}

.pad.on {
  background: #38e8ff;
  border-color: #38e8ff;
  box-shadow: 0 0 7px rgba(56, 232, 255, 0.85);
}

.station-card.tone-danger .pad.on {
  background: #ff6060;
  border-color: #ff6060;
  box-shadow: 0 0 7px rgba(255, 96, 96, 0.85);
}

.pad-text {
  margin-left: 6px;
  font-size: 10px;
  color: rgba(159, 240, 255, 0.82);
}

.station-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.station-metric {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.station-metric em {
  font-size: 10px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.5);
}

.station-metric strong {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  color: rgba(216, 240, 255, 0.92);
}

.station-metric strong.danger {
  color: #ff9a9a;
}

.util-bar {
  height: 3px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.1);
}

.util-bar i {
  display: block;
  height: 100%;
  background: var(--card-tone);
  box-shadow: 0 0 8px var(--card-tone);
  transition: width 0.4s ease;
}

.card-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.45);
}

.coords {
  flex: 0 0 auto;
  font-size: 10px;
}

.foot-warn {
  margin-left: auto;
  color: #ffb4b4;
}

.foot-remark {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* =============================================================================
   机巢卡片
   ============================================================================= */
.vertiport-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 10px;
}

.vp-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  padding: 5px 0;
  border-top: 1px solid rgba(56, 232, 255, 0.08);
  border-bottom: 1px solid rgba(56, 232, 255, 0.08);
}

.vp-stats div {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.vp-stats em {
  font-size: 9px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.48);
}

.vp-stats strong {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
  color: rgba(216, 240, 255, 0.92);
}

.vp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.vp-next {
  font-size: 10px;
  color: rgba(150, 199, 232, 0.6);
}

.vp-turnaround {
  color: #ffe0b0;
}

/* =============================================================================
   战略时隙
   ============================================================================= */
.slot-time {
  font-size: 11px;
  color: #9ff0ff;
}

.slot-usage {
  display: flex;
  align-items: center;
  gap: 8px;
}

.slot-bar {
  position: relative;
  flex: 1 1 auto;
  height: 4px;
  min-width: 60px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.1);
}

.slot-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #1677ff, #38e8ff);
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.8);
}

.slot-usage .text-mono {
  flex: 0 0 auto;
  font-size: 10px;
  color: rgba(159, 240, 255, 0.8);
}

.occupant-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.occupant-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 6px;
  font-size: 10px;
  white-space: nowrap;
  cursor: pointer;
  color: #9fd9f5;
  border: 1px solid rgba(56, 232, 255, 0.18);
  background: rgba(56, 232, 255, 0.05);
  transition: border-color 0.18s ease, color 0.18s ease;
}

.occupant-chip .text-mono {
  white-space: nowrap;
}

.occupant-chip:hover {
  color: #7ff0ff;
  border-color: rgba(56, 232, 255, 0.45);
}

.occupant-drone {
  color: rgba(150, 199, 232, 0.6);
}

.infra-empty {
  padding: 50px 0;
  border: 1px dashed rgba(56, 232, 255, 0.18);
  background: rgba(7, 19, 32, 0.4);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 8px 2px 0;
}

/* 编辑弹窗 */
.edit-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.edit-site {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 9px;
  font-size: 11px;
  color: #9ff0ff;
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(56, 232, 255, 0.05);
}

.field-hint {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(150, 199, 232, 0.5);
}

@media (max-width: 900px) {
  .vp-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .infra::after,
  .infra-led {
    animation: none;
  }
}
</style>
