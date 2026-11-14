<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { MinusCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { airspaceApi } from '@/api'
import type { AirspaceZone, ConflictSeverity, ZoneCreateRequest, ZoneShape, ZoneType } from '@/api'
import AirspacePickerMap from '@/components/map/AirspacePickerMap.vue'
import AirspaceOverviewMap from '@/components/map/AirspaceOverviewMap.vue'
import {
  CONFLICT_SEVERITY_COLOR,
  CONFLICT_SEVERITY_TEXT,
  ZONE_TYPE_COLOR,
  ZONE_TYPE_TEXT,
} from '@/utils/format'

/**
 * 空域管理：禁飞/限飞区会作为障碍物传给算法服务，所以这里是"改动即影响路径规划"的高危入口，
 * 页面顶部常驻提示，删除与新增都要求二次确认或显式校验。
 */

type TableColumn = {
  title: string
  dataIndex?: string
  key?: string
  width?: number
  align?: 'left' | 'center' | 'right'
  /** 列固定。操作列固定在右侧，横向滚动时始终可见 */
  fixed?: 'left' | 'right'
}

/** 表单里的多边形顶点：a-input-number 清空时会回填 null，所以用 null 表示"未填" */
interface ZoneFormPoint {
  lat: number | null
  lon: number | null
}

/** 新建一行空白顶点 */
function emptyPoint(): ZoneFormPoint {
  return { lat: null, lon: null }
}

const ZONE_TYPES: ZoneType[] = ['NO_FLY', 'RESTRICTED', 'BUILDING', 'CORRIDOR', 'WARNING']
const SEVERITIES: ConflictSeverity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

const loading = ref(false)
const submitting = ref(false)
const modalOpen = ref(false)
const zones = ref<AirspaceZone[]>([])

/** 表格选中行 ↔ 地图高亮联动的当前空域 id */
const selectedZoneId = ref<number | null>(null)
const overviewRef = ref<InstanceType<typeof AirspaceOverviewMap> | null>(null)

/** 点表格行即选中：地图定位并高亮该空域 */
function onRowClick(record: AirspaceZone): void {
  selectedZoneId.value = record.id
  overviewRef.value?.focusZone(record.id)
}

/** 点地图上的空域即反选表格行 */
function onZoneSelect(zone: AirspaceZone): void {
  selectedZoneId.value = zone.id
}

/** a-table 自定义行：整行可点，光标变手型 */
function customRow(record: AirspaceZone): Record<string, unknown> {
  return {
    onClick: () => onRowClick(record),
    style: { cursor: 'pointer' },
  }
}

const zoneTypeOptions = ZONE_TYPES.map((t) => ({ value: t, label: ZONE_TYPE_TEXT[t] }))
const severityOptions = SEVERITIES.map((s) => ({ value: s, label: CONFLICT_SEVERITY_TEXT[s] }))

const columns: TableColumn[] = [
  { title: '编码', dataIndex: 'code', key: 'code', width: 150 },
  { title: '名称', dataIndex: 'name', key: 'name', width: 180 },
  { title: '类型', key: 'zoneType', width: 110 },
  { title: '形状', key: 'shape', width: 180 },
  { title: '高度区间', key: 'altitude', width: 140 },
  { title: '严重度', key: 'severity', width: 100 },
  { title: '启用', key: 'enabled', width: 90 },
  { title: '备注', key: 'remark', width: 220 },
  { title: '操作', key: 'action', width: 90, fixed: 'right' as const },
]

const form = reactive<{
  code: string
  name: string
  zoneType: ZoneType
  shapeType: ZoneShape
  centerLat: number
  centerLon: number
  radiusM: number
  minAltitudeM: number
  maxAltitudeM: number
  /** null = 不指定，交给后端按区域类型取默认值（禁飞区最严、走廊最松） */
  severity: ConflictSeverity | null
  enabled: boolean
  remark: string
}>({
  code: '',
  name: '',
  zoneType: 'NO_FLY',
  shapeType: 'CIRCLE',
  centerLat: 31.2304,
  centerLon: 121.4737,
  radiusM: 1000,
  minAltitudeM: 0,
  maxAltitudeM: 120,
  severity: null,
  enabled: true,
  remark: '',
})

/** 多边形顶点：默认给 3 行，符合"面至少要 3 个顶点"的行业习惯 */
const points = ref<ZoneFormPoint[]>([emptyPoint(), emptyPoint(), emptyPoint()])

function shapeText(record: AirspaceZone): string {
  if (record.shapeType === 'CIRCLE') {
    return `圆形 半径 ${record.radiusM ?? '—'} m`
  }
  return `多边形 ${record.points?.length ?? 0} 个顶点`
}

function altitudeText(record: AirspaceZone): string {
  const min = record.minAltitudeM ?? 0
  const max = record.maxAltitudeM ?? '—'
  return `${min} ~ ${max} m`
}

function resetForm(): void {
  form.code = ''
  form.name = ''
  form.zoneType = 'NO_FLY'
  form.shapeType = 'CIRCLE'
  form.centerLat = 31.2304
  form.centerLon = 121.4737
  form.radiusM = 1000
  form.minAltitudeM = 0
  form.maxAltitudeM = 120
  form.severity = null
  form.enabled = true
  form.remark = ''
  points.value = [emptyPoint(), emptyPoint(), emptyPoint()]
}

async function loadZones(): Promise<void> {
  loading.value = true
  try {
    zones.value = await airspaceApi.list()
    // 删除后原来选中的行已不存在，联动高亮要一并清掉，避免"高亮了一个不存在的区"
    if (selectedZoneId.value !== null && !zones.value.some((z) => z.id === selectedZoneId.value)) {
      selectedZoneId.value = null
    }
  } catch {
    // client.ts 已统一提示错误，这里只收尾
  } finally {
    loading.value = false
  }
}

function openModal(): void {
  resetForm()
  modalOpen.value = true
}

function addPoint(): void {
  points.value.push(emptyPoint())
}

function removePoint(index: number): void {
  points.value.splice(index, 1)
}

/** 多边形：清空全部顶点，从头在图上重画 */
function clearPoints(): void {
  points.value = [emptyPoint(), emptyPoint(), emptyPoint()]
}

/** 多边形：撤销最后一个已填顶点 */
function undoLastPoint(): void {
  const lastFilled = points.value.map((p) => p.lat !== null || p.lon !== null).lastIndexOf(true)
  if (lastFilled >= 0) {
    points.value[lastFilled] = emptyPoint()
  }
}

async function submitZone(): Promise<void> {
  // 编码与名称都可以不填：留空由后端自动生成（编码按类型派生前缀，名称用「类型 + 编号」拼）
  if (form.shapeType === 'CIRCLE' && !(form.radiusM > 0)) {
    message.warning('圆形空域半径必须大于 0')
    return
  }

  // 过滤掉完全空白的行，再校验有效顶点：既允许用户中途留空，也不放过"少填一个坐标"的错
  const validPoints =
    form.shapeType === 'POLYGON' ? points.value.filter((p) => p.lat !== null || p.lon !== null) : []
  if (form.shapeType === 'POLYGON') {
    if (validPoints.length < 3) {
      message.warning('多边形空域至少需要 3 个顶点')
      return
    }
    if (validPoints.some((p) => p.lat === null || p.lon === null)) {
      message.warning('每个顶点都必须同时填写纬度和经度')
      return
    }
  }

  if (form.maxAltitudeM < form.minAltitudeM) {
    message.warning('高度上限不能低于高度下限')
    return
  }

  const body: ZoneCreateRequest = {
    // 留空就不传，让后端走自动编号与默认名称
    code: form.code.trim() || undefined,
    name: form.name.trim() || undefined,
    zoneType: form.zoneType,
    shapeType: form.shapeType,
    minAltitudeM: form.minAltitudeM,
    maxAltitudeM: form.maxAltitudeM,
    // null = 不指定，由后端按区域类型给默认严重度
    severity: form.severity ?? undefined,
    enabled: form.enabled,
    remark: form.remark.trim() || undefined,
  }
  if (form.shapeType === 'CIRCLE') {
    body.center = { lat: form.centerLat, lon: form.centerLon }
    body.radiusM = form.radiusM
  } else {
    // 上面已校验非空，这里的断言是安全的
    body.points = validPoints.map((p) => ({ lat: p.lat as number, lon: p.lon as number }))
  }

  submitting.value = true
  try {
    // 用后端返回的编码/名称提示：自动生成时用户才知道"这个区叫什么"
    const created = await airspaceApi.create(body)
    message.success(`空域「${created.name}」（${created.code}）新增成功`)
    modalOpen.value = false
    resetForm()
    await loadZones()
    // 新增完顺手选中它：用户回到列表就能看到"刚画的那个区"在地图上的位置
    selectedZoneId.value = created.id ?? null
  } catch {
    // 失败保持弹窗内容，便于修正后重试
  } finally {
    submitting.value = false
  }
}

async function removeZone(record: AirspaceZone): Promise<void> {
  try {
    await airspaceApi.remove(record.id)
    message.success(`空域「${record.name ?? record.code}」已删除`)
    await loadZones()
  } catch {
    // 删除失败已由 client.ts 提示
  }
}

onMounted(() => {
  void loadZones()
})
</script>

<template>
  <div class="airspace-view">
    <!-- 空域改动会立即影响路径规划，所以这条提示常驻而不是做成一次性 toast -->
    <a-alert
      type="info"
      show-icon
      banner
      message="禁飞区与限飞区会立即参与路径规划"
      description="新增或删除空域后，后续所有派单的路径规划都会把启用中的空域作为障碍物传给算法服务，请谨慎操作。"
    />

    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-button :loading="loading" @click="loadZones">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <span class="text-muted">共 {{ zones.length }} 个空域</span>
      </a-space>

      <template #extra>
        <a-button type="primary" @click="openModal">
          <template #icon><PlusOutlined /></template>
          新增空域
        </a-button>
      </template>
    </a-card>

    <!-- ------------------------------ 空域分布总览地图 ------------------------------ -->
    <!-- 表格只能看出"有几个区"，看不出"区在哪、有没有重叠"，所以把地图常驻在页面上 -->
    <a-card :bordered="false" size="small" class="map-card">
      <template #title>
        空域分布总览
        <span class="map-card-sub">点色块选中、点省面下钻，确认位置后再划定</span>
      </template>
      <template #extra>
        <a-button size="small" @click="overviewRef?.resetView()">全国视野</a-button>
      </template>
      <AirspaceOverviewMap
        ref="overviewRef"
        :zones="zones"
        :highlight-id="selectedZoneId"
        height="420px"
        @select="onZoneSelect"
      />
    </a-card>

    <!-- ------------------------------ 空域表格 ------------------------------ -->
    <a-card :bordered="false" size="small" class="table-card">
      <a-table
        row-key="id"
        :columns="columns"
        :data-source="zones"
        :loading="loading"
        :custom-row="customRow"
        :row-class-name="(record: AirspaceZone) => (record.id === selectedZoneId ? 'row-selected' : '')"
        :pagination="{ pageSize: 10, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 个空域` }"
        :scroll="{ x: 1400 }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'code'">
            <span class="text-mono">{{ record.code ?? '—' }}</span>
          </template>

          <template v-else-if="column.key === 'name'">
            {{ record.name ?? '—' }}
          </template>

          <template v-else-if="column.key === 'zoneType'">
            <a-tag :color="ZONE_TYPE_COLOR[record.zoneType as ZoneType] ?? 'default'">
              {{ ZONE_TYPE_TEXT[record.zoneType as ZoneType] ?? record.zoneType }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'shape'">
            {{ shapeText(record) }}
          </template>

          <template v-else-if="column.key === 'altitude'">
            {{ altitudeText(record) }}
          </template>

          <template v-else-if="column.key === 'severity'">
            <a-tag v-if="record.severity" :color="CONFLICT_SEVERITY_COLOR[record.severity as ConflictSeverity] ?? 'default'">
              {{ CONFLICT_SEVERITY_TEXT[record.severity as ConflictSeverity] }}
            </a-tag>
            <span v-else>—</span>
          </template>

          <template v-else-if="column.key === 'enabled'">
            <a-tag :color="record.enabled ? 'success' : 'default'">
              {{ record.enabled ? '启用' : '停用' }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'remark'">
            {{ record.remark ?? '—' }}
          </template>

          <template v-else-if="column.key === 'action'">
            <a-popconfirm
              title="确认删除该空域？删除后不再参与路径规划。"
              ok-text="确认删除"
              cancel-text="取消"
              @confirm="removeZone(record)"
            >
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:open="modalOpen"
      title="新增空域"
      :width="760"
      ok-text="提交"
      cancel-text="取消"
      :confirm-loading="submitting"
      :mask-closable="false"
      @ok="submitZone"
    >
      <a-form layout="vertical" class="zone-form">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="空域编码（可留空）">
              <a-input v-model:value="form.code" placeholder="留空则自动生成，如 NFZ-0001" allow-clear />
              <div class="field-hint">前缀按类型派生：禁飞区 NFZ / 限飞区 RFA / 走廊 CRD / 警示区 WRN</div>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="空域名称（可留空）">
              <a-input v-model:value="form.name" placeholder="留空则自动命名，如「禁飞区 NFZ-0001」" allow-clear />
              <div class="field-hint">只在需要表达业务含义（如「虹桥机场禁飞区」）时才填写</div>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="空域类型">
              <a-select v-model:value="form.zoneType" :options="zoneTypeOptions" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="形状">
              <a-radio-group v-model:value="form.shapeType">
                <a-radio-button value="CIRCLE">圆形</a-radio-button>
                <a-radio-button value="POLYGON">多边形</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>

        <!-- 地图拾取：点一下就把圆心 / 顶点填好，不必手工查经纬度 -->
        <a-form-item :label="form.shapeType === 'CIRCLE' ? '地图拾取圆心（点击定位）' : '地图拾取顶点（依次点击）'">
          <AirspacePickerMap
            v-model:center-lat="form.centerLat"
            v-model:center-lon="form.centerLon"
            v-model:radius-m="form.radiusM"
            v-model:points="points"
            :shape-type="form.shapeType"
            :zones="zones"
            height="300px"
          />
        </a-form-item>

        <template v-if="form.shapeType === 'CIRCLE'">
          <a-row :gutter="16">
            <a-col :span="8">
              <a-form-item label="圆心纬度（精确微调）">
                <a-input-number v-model:value="form.centerLat" :precision="6" :step="0.000001" class="num" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="圆心经度（精确微调）">
                <a-input-number v-model:value="form.centerLon" :precision="6" :step="0.000001" class="num" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="半径（m）">
                <a-input-number v-model:value="form.radiusM" :min="1" :step="100" class="num" />
              </a-form-item>
            </a-col>
          </a-row>
        </template>

        <template v-else>
          <a-form-item label="多边形顶点（图上点选，或在此精确微调）">
            <div class="point-tools">
              <a-button size="small" @click="undoLastPoint">撤销上一个顶点</a-button>
              <a-button size="small" @click="clearPoints">清空重画</a-button>
              <span class="point-count">已填 {{ points.filter((p) => p.lat !== null && p.lon !== null).length }} 个（至少 3 个）</span>
            </div>
            <div v-for="(point, index) in points" :key="index" class="point-row">
              <span class="point-index">#{{ index + 1 }}</span>
              <a-input-number
                v-model:value="point.lat"
                :precision="6"
                :step="0.000001"
                placeholder="纬度"
                class="num"
              />
              <a-input-number
                v-model:value="point.lon"
                :precision="6"
                :step="0.000001"
                placeholder="经度"
                class="num"
              />
              <a-button
                type="text"
                danger
                @click="removePoint(index)"
              >
                <template #icon><MinusCircleOutlined /></template>
                删除
              </a-button>
            </div>
            <a-button type="dashed" block @click="addPoint">
              <template #icon><PlusOutlined /></template>
              添加空白顶点
            </a-button>
          </a-form-item>
        </template>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="高度下限（m）">
              <a-input-number v-model:value="form.minAltitudeM" :min="0" :step="10" class="num" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="高度上限（m）">
              <a-input-number v-model:value="form.maxAltitudeM" :min="0" :step="10" class="num" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="严重度">
              <!-- 允许清空 = 交给后端按类型给默认值；类型一变，默认严重度也跟着变 -->
              <a-select
                v-model:value="form.severity"
                :options="severityOptions"
                allow-clear
                placeholder="按类型自动"
              />
              <div class="field-hint">清空则按类型取默认值：禁飞区严重、走廊宽松</div>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="是否启用">
          <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="停用" />
        </a-form-item>

        <a-form-item label="备注">
          <a-textarea v-model:value="form.remark" :rows="2" placeholder="用于说明空域来源、生效条件等" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.airspace-view {
  /* 外层 .app-content 已给内边距，这里只负责卡片之间的间距 */
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card,
.map-card,
.table-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.map-card-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
  color: rgba(180, 208, 232, 0.55);
}

/* 表格选中行：与地图上高亮的空域是同一条，给一层青色底做呼应 */
:deep(.ant-table-tbody > tr.row-selected > td) {
  background: rgba(34, 211, 238, 0.12);
}

.zone-form {
  margin-top: 8px;
}

.num {
  width: 100%;
}

.point-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.point-index {
  width: 32px;
  color: rgba(180, 208, 232, 0.55);
  font-size: 12px;
}

/* 多边形顶点工具条：撤销 / 清空 / 已填计数 */
.point-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.point-count {
  color: rgba(180, 208, 232, 0.55);
  font-size: 12px;
}

/* 弹窗里有地图与长表单，加高滚动区，避免地图被压扁 */
:deep(.ant-modal-body) {
  max-height: 72vh;
  overflow-y: auto;
}
</style>
