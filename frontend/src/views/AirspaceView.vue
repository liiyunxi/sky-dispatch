<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  AimOutlined,
  MinusCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import { airspaceApi } from '@/api'
import type {
  AirspaceZone,
  ConflictSeverity,
  ConflictView,
  GeoDto,
  ZoneCreateRequest,
  ZoneShape,
  ZoneType,
} from '@/api'
import AirspacePickerMap from '@/components/map/AirspacePickerMap.vue'
import AirspaceOverviewMap from '@/components/map/AirspaceOverviewMap.vue'
import { useTelemetry } from '@/store/telemetry'
import {
  CONFLICT_SEVERITY_COLOR,
  CONFLICT_SEVERITY_TEXT,
  CONFLICT_TYPE_TEXT,
  ZONE_TYPE_COLOR,
  ZONE_TYPE_TEXT,
  formatFromNow,
} from '@/utils/format'

/**
 * 空域态势指挥台。
 *
 * 原页面是"地图 + 表格 + 新增弹窗"，能维护数据，但回答不了三个运营最关心的问题：
 *
 *  1. **空域在高度上怎么分布**——60~80m 是不是被禁飞区/限飞区叠满了？
 *  2. **现在有没有飞机在禁飞区里**——实时航迹与空域多边形叠加检查；
 *  3. **哪些空域正在产生冲突**——把未处理的 DRONE_ZONE 冲突直接放在页面上处理。
 *
 * 改造后：
 *  - 顶部态势条（总数/启用/禁飞/当前侵入/未处理冲突）+ 类型筛选；
 *  - 地图与「高度层占用剖面」并排，剖面用堆叠柱展示每个 20m 高度带里各类型空域的数量，
 *    并叠加当前在飞高度带；
 *  - 实时冲突面板，可直接解除；
 *  - 表格换成空域卡片：高度条、面积、形状、严重度、实时占用/侵入数量，
 *    点卡片即在地图上定位高亮。
 *
 * 原有的新增空域（地图拾取圆心/多边形）、删除、启用停用等能力全部保留。
 */

const { drones: liveFrames, conflicts, resolveConflict } = useTelemetry()

const ZONE_TYPES: ZoneType[] = ['NO_FLY', 'RESTRICTED', 'BUILDING', 'TERRAIN', 'CORRIDOR', 'WARNING']
const SEVERITIES: ConflictSeverity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

/** 与地图图层保持同一套颜色（红=禁飞、橙=限飞、紫=建筑、绿=走廊、黄=警示） */
const ZONE_HEX: Record<ZoneType, string> = {
  NO_FLY: '#ff4d4f',
  RESTRICTED: '#fa8c16',
  BUILDING: '#722ed1',
  TERRAIN: '#8c5a2b',
  CORRIDOR: '#52c41a',
  WARNING: '#faad14',
}

const SEVERITY_HEX: Record<string, string> = {
  LOW: '#5b7c99',
  MEDIUM: '#faad14',
  HIGH: '#fa8c16',
  CRITICAL: '#ff4d4f',
}

const loading = ref(false)
const submitting = ref(false)
const modalOpen = ref(false)
const zones = ref<AirspaceZone[]>([])

/** 卡片 / 地图联动选中的空域 id */
const selectedZoneId = ref<number | null>(null)
const overviewRef = ref<InstanceType<typeof AirspaceOverviewMap> | null>(null)

/** 客户端筛选：类型 + 关键字（空域是低频台账，不需要分页） */
const typeFilter = ref<ZoneType | undefined>(undefined)
const keyword = ref('')

const filteredZones = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return zones.value.filter((zone) => {
    if (typeFilter.value && zone.zoneType !== typeFilter.value) return false
    if (!kw) return true
    return [zone.code, zone.name, zone.remark]
      .filter((value) => value != null)
      .some((value) => String(value).toLowerCase().includes(kw))
  })
})

// ---------------------------------------------------------------------------
// 态势读数
// ---------------------------------------------------------------------------

const typeCounts = computed<Record<ZoneType, number>>(() => {
  const counts: Record<ZoneType, number> = {
    NO_FLY: 0,
    RESTRICTED: 0,
    BUILDING: 0,
    TERRAIN: 0,
    CORRIDOR: 0,
    WARNING: 0,
  }
  zones.value.forEach((zone) => {
    counts[zone.zoneType] += 1
  })
  return counts
})

const enabledZones = computed(() => zones.value.filter((zone) => zone.enabled !== false))

function toggleType(type: ZoneType): void {
  typeFilter.value = typeFilter.value === type ? undefined : type
}

// ---------------------------------------------------------------------------
// 实时占用：把当前无人机位置与启用中的空域做几何判断
// ---------------------------------------------------------------------------

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const earthRadius = 6371008.8
  const dLat = ((lat2 - lat1) * Math.PI) / 180
  const dLon = ((lon2 - lon1) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2
  return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)))
}

/** 射线法：判断点是否在多边形内（经纬度平面近似，城市尺度足够） */
function pointInPolygon(lat: number, lon: number, points: GeoDto[]): boolean {
  let inside = false
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const xi = points[i].lon
    const yi = points[i].lat
    const xj = points[j].lon
    const yj = points[j].lat
    const intersects = yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi || 1e-12) + xi
    if (intersects) inside = !inside
  }
  return inside
}

function containsPoint(zone: AirspaceZone, lat: number, lon: number): boolean {
  if (zone.shapeType === 'CIRCLE' && zone.center) {
    return haversineMeters(lat, lon, zone.center.lat, zone.center.lon) <= Number(zone.radiusM ?? 0)
  }
  if (zone.shapeType === 'POLYGON' && (zone.points?.length ?? 0) >= 3) {
    return pointInPolygon(lat, lon, zone.points ?? [])
  }
  return false
}

/** 每个启用空域内当前有多少架无人机（只看有实时坐标的帧） */
const zoneOccupancy = computed(() => {
  const map = new Map<number, number>()
  const frames = liveFrames.value.filter((frame) => frame.lat != null && frame.lon != null)
  zones.value.forEach((zone) => {
    if (zone.enabled === false) {
      map.set(zone.id, 0)
      return
    }
    const count = frames.filter((frame) => containsPoint(zone, Number(frame.lat), Number(frame.lon))).length
    map.set(zone.id, count)
  })
  return map
})

/** 侵入禁飞区 / 限飞区的架次：这是需要立刻处理的运行风险 */
const intrusionCount = computed(() => {
  let total = 0
  zones.value.forEach((zone) => {
    if (zone.enabled === false) return
    if (zone.zoneType !== 'NO_FLY' && zone.zoneType !== 'RESTRICTED') return
    total += zoneOccupancy.value.get(zone.id) ?? 0
  })
  return total
})

/** 当前在飞高度带：用于在高度剖面上标出"飞机实际在哪一层" */
const flyingAltitude = computed(() => {
  const altitudes = liveFrames.value
    .filter((frame) => frame.altitudeM != null && Number(frame.altitudeM) > 0)
    .map((frame) => Number(frame.altitudeM))
  if (!altitudes.length) return null
  return { min: Math.min(...altitudes), max: Math.max(...altitudes) }
})

// ---------------------------------------------------------------------------
// 高度层占用剖面
// ---------------------------------------------------------------------------

const ALTITUDE_BAND_M = 20

const altitudeChart = computed(() => {
  const enabled = enabledZones.value
  if (!enabled.length) return null

  const topAltitude = Math.max(
    120,
    ...enabled.map((zone) => Number(zone.maxAltitudeM ?? 120)),
  )
  const bandCount = Math.max(1, Math.ceil(topAltitude / ALTITUDE_BAND_M))

  const bands = Array.from({ length: bandCount }, (_, index) => {
    const lo = index * ALTITUDE_BAND_M
    const hi = lo + ALTITUDE_BAND_M
    const counts: Record<ZoneType, number> = {
      NO_FLY: 0,
      RESTRICTED: 0,
      BUILDING: 0,
      TERRAIN: 0,
      CORRIDOR: 0,
      WARNING: 0,
    }
    enabled.forEach((zone) => {
      const min = Number(zone.minAltitudeM ?? 0)
      const max = Number(zone.maxAltitudeM ?? 120)
      if (min < hi && max > lo) counts[zone.zoneType] += 1
    })
    const total = ZONE_TYPES.reduce((sum, type) => sum + counts[type], 0)
    return { lo, hi, counts, total }
  })

  const width = 380
  const height = 210
  const padL = 10
  const padR = 10
  const padT = 20
  const padB = 24
  const innerW = width - padL - padR
  const chartH = height - padT - padB
  const barW = innerW / bandCount
  const gap = Math.min(5, barW * 0.22)
  const maxStack = Math.max(1, ...bands.map((band) => band.total))
  const unitH = chartH / maxStack

  const segments: Array<{ x: number; y: number; w: number; h: number; color: string }> = []
  const totals: Array<{ x: number; y: number; total: number }> = []
  bands.forEach((band, bandIndex) => {
    let stack = 0
    ZONE_TYPES.forEach((type) => {
      for (let n = 0; n < band.counts[type]; n++) {
        segments.push({
          x: padL + bandIndex * barW + gap / 2,
          y: padT + chartH - (stack + 1) * unitH,
          w: Math.max(2, barW - gap),
          h: Math.max(1, unitH - 1),
          color: ZONE_HEX[type],
        })
        stack += 1
      }
    })
    if (band.total > 0) {
      totals.push({
        x: padL + bandIndex * barW + barW / 2,
        y: padT + chartH - band.total * unitH - 4,
        total: band.total,
      })
    }
  })

  const ticks = bands
    .filter((_, index) => index % 3 === 0 || index === bands.length - 1)
    .map((band, index) => ({
      x: padL + (band.lo / (bandCount * ALTITUDE_BAND_M)) * innerW,
      label: `${band.lo}`,
      anchor: index === 0 ? 'start' : 'middle',
    }))

  // 当前在飞高度带：横向高亮一条竖带（x 轴就是高度）
  let flyingBand: { x: number; w: number; label: string } | null = null
  if (flyingAltitude.value) {
    const x1 = padL + (flyingAltitude.value.min / (bandCount * ALTITUDE_BAND_M)) * innerW
    const x2 = padL + (flyingAltitude.value.max / (bandCount * ALTITUDE_BAND_M)) * innerW
    flyingBand = {
      x: Math.max(padL, x1 - 2),
      w: Math.max(3, x2 - x1 + 4),
      label: `在飞 ${flyingAltitude.value.min.toFixed(0)}–${flyingAltitude.value.max.toFixed(0)}m`,
    }
  }

  const busiest = bands.reduce((best, band) => (band.total > best.total ? band : best), bands[0])

  return {
    width,
    height,
    padL,
    padR,
    padT,
    padB,
    chartH,
    innerW,
    bands,
    segments,
    totals,
    ticks,
    flyingBand,
    busiest,
    axisY: padT + chartH,
    endX: width - padR,
  }
})

// ---------------------------------------------------------------------------
// 实时冲突
// ---------------------------------------------------------------------------

const severityWeight: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }

const liveConflicts = computed<ConflictView[]>(() =>
  [...conflicts.value]
    .sort((a, b) => (severityWeight[a.severity] ?? 9) - (severityWeight[b.severity] ?? 9))
    .slice(0, 6),
)

async function handleResolve(conflict: ConflictView): Promise<void> {
  try {
    await resolveConflict(conflict.id, '空域页人工解除')
    message.success('冲突已解除')
  } catch {
    /* client.ts 已提示 */
  }
}

/** 冲突关联了空域时，点一下直接定位到该空域 */
function focusConflict(conflict: ConflictView): void {
  if (conflict.zoneId == null) return
  selectedZoneId.value = conflict.zoneId
  overviewRef.value?.focusZone(conflict.zoneId)
}

// ---------------------------------------------------------------------------
// 空域卡片读数
// ---------------------------------------------------------------------------

const altitudeDomain = computed(() =>
  Math.max(120, ...zones.value.map((zone) => Number(zone.maxAltitudeM ?? 120))),
)

function altitudeBandStyle(zone: AirspaceZone): Record<string, string> {
  const domain = altitudeDomain.value
  const min = Math.max(0, Number(zone.minAltitudeM ?? 0))
  const max = Math.min(domain, Number(zone.maxAltitudeM ?? domain))
  return {
    left: `${(min / domain) * 100}%`,
    width: `${Math.max(2, ((max - min) / domain) * 100)}%`,
    background: ZONE_HEX[zone.zoneType],
  }
}

/** 圆形面积 πr²；多边形用经纬度投影后的鞋带公式 */
function zoneAreaKm2(zone: AirspaceZone): number {
  if (zone.shapeType === 'CIRCLE') {
    const radius = Number(zone.radiusM ?? 0)
    return (Math.PI * radius * radius) / 1_000_000
  }
  const points = zone.points ?? []
  if (points.length < 3) return 0
  const metersPerDegLat = 110540
  const metersPerDegLon = 111320 * Math.cos((points[0].lat * Math.PI) / 180)
  let area = 0
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const xi = points[i].lon * metersPerDegLon
    const yi = points[i].lat * metersPerDegLat
    const xj = points[j].lon * metersPerDegLon
    const yj = points[j].lat * metersPerDegLat
    area += xi * yj - xj * yi
  }
  return Math.abs(area / 2) / 1_000_000
}

function areaText(zone: AirspaceZone): string {
  const area = zoneAreaKm2(zone)
  if (area <= 0) return '—'
  return area < 10 ? `${area.toFixed(2)} km²` : `${area.toFixed(1)} km²`
}

function shapeText(zone: AirspaceZone): string {
  if (zone.shapeType === 'CIRCLE') {
    return `圆形 · 半径 ${Number(zone.radiusM ?? 0).toFixed(0)} m`
  }
  return `多边形 · ${zone.points?.length ?? 0} 个顶点`
}

/** 仿真时间窗：空表示长期有效。 */
function windowText(zone: AirspaceZone): string {
  if (zone.activeFromSimSec == null && zone.activeToSimSec == null) return '长期有效'
  return `仿真 ${zone.activeFromSimSec ?? 0} ~ ${zone.activeToSimSec ?? '长期'} s`
}

function occupancyText(zone: AirspaceZone): string {
  if (zone.enabled === false) return '已停用'
  const count = zoneOccupancy.value.get(zone.id) ?? 0
  if (count <= 0) return '空域空闲'
  if (zone.zoneType === 'NO_FLY' || zone.zoneType === 'RESTRICTED') return `侵入 ${count} 架`
  return `占用 ${count} 架`
}

function occupancyTone(zone: AirspaceZone): string {
  if (zone.enabled === false) return 'muted'
  const count = zoneOccupancy.value.get(zone.id) ?? 0
  if (count <= 0) return 'idle'
  return zone.zoneType === 'NO_FLY' || zone.zoneType === 'RESTRICTED' ? 'danger' : 'active'
}

/** 点卡片：选中并让地图定位过去 */
function selectZone(zone: AirspaceZone): void {
  selectedZoneId.value = zone.id
  overviewRef.value?.focusZone(zone.id)
}

/** 点地图色块：只反选卡片，不重复 fly（地图已经在用户眼前） */
function onZoneSelect(zone: AirspaceZone): void {
  selectedZoneId.value = zone.id
}

// ---------------------------------------------------------------------------
// 新建 / 删除
// ---------------------------------------------------------------------------

interface ZoneFormPoint {
  lat: number | null
  lon: number | null
}

function emptyPoint(): ZoneFormPoint {
  return { lat: null, lon: null }
}

const form = ref<{
  code: string
  name: string
  zoneType: ZoneType
  shapeType: ZoneShape
  centerLat: number
  centerLon: number
  radiusM: number
  minAltitudeM: number
  maxAltitudeM: number
  severity: ConflictSeverity | null
  enabled: boolean
  /** 仿真时间窗（秒）；空表示长期有效 */
  activeFromSimSec: number | null
  activeToSimSec: number | null
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
  activeFromSimSec: null,
  activeToSimSec: null,
  remark: '',
})

/** 多边形顶点：默认 3 行，符合"面至少 3 个顶点"的行业习惯 */
const points = ref<ZoneFormPoint[]>([emptyPoint(), emptyPoint(), emptyPoint()])

const zoneTypeOptions = ZONE_TYPES.map((type) => ({ value: type, label: ZONE_TYPE_TEXT[type] }))
const severityOptions = SEVERITIES.map((severity) => ({
  value: severity,
  label: CONFLICT_SEVERITY_TEXT[severity],
}))

function resetForm(): void {
  form.value = {
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
    activeFromSimSec: null,
    activeToSimSec: null,
    remark: '',
  }
  points.value = [emptyPoint(), emptyPoint(), emptyPoint()]
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

function clearPoints(): void {
  points.value = [emptyPoint(), emptyPoint(), emptyPoint()]
}

function undoLastPoint(): void {
  const lastFilled = points.value.map((point) => point.lat !== null || point.lon !== null).lastIndexOf(true)
  if (lastFilled >= 0) {
    points.value[lastFilled] = emptyPoint()
  }
}

async function submitZone(): Promise<void> {
  const current = form.value
  if (current.shapeType === 'CIRCLE' && !(current.radiusM > 0)) {
    message.warning('圆形空域半径必须大于 0')
    return
  }

  const validPoints =
    current.shapeType === 'POLYGON'
      ? points.value.filter((point) => point.lat !== null || point.lon !== null)
      : []
  if (current.shapeType === 'POLYGON') {
    if (validPoints.length < 3) {
      message.warning('多边形空域至少需要 3 个顶点')
      return
    }
    if (validPoints.some((point) => point.lat === null || point.lon === null)) {
      message.warning('每个顶点都必须同时填写纬度和经度')
      return
    }
  }

  if (current.maxAltitudeM < current.minAltitudeM) {
    message.warning('高度上限不能低于高度下限')
    return
  }
  if (
    current.activeFromSimSec != null &&
    current.activeToSimSec != null &&
    current.activeToSimSec <= current.activeFromSimSec
  ) {
    message.warning('仿真时间窗的结束时刻必须大于开始时刻')
    return
  }

  const body: ZoneCreateRequest = {
    code: current.code.trim() || undefined,
    name: current.name.trim() || undefined,
    zoneType: current.zoneType,
    shapeType: current.shapeType,
    minAltitudeM: current.minAltitudeM,
    maxAltitudeM: current.maxAltitudeM,
    severity: current.severity ?? undefined,
    enabled: current.enabled,
    activeFromSimSec: current.activeFromSimSec ?? undefined,
    activeToSimSec: current.activeToSimSec ?? undefined,
    remark: current.remark.trim() || undefined,
  }
  if (current.shapeType === 'CIRCLE') {
    body.center = { lat: current.centerLat, lon: current.centerLon }
    body.radiusM = current.radiusM
  } else {
    body.points = validPoints.map((point) => ({
      lat: point.lat as number,
      lon: point.lon as number,
    }))
  }

  submitting.value = true
  try {
    const created = await airspaceApi.create(body)
    message.success(`空域「${created.name}」（${created.code}）新增成功`)
    modalOpen.value = false
    resetForm()
    await loadZones()
    selectedZoneId.value = created.id ?? null
  } catch {
    // 失败保持弹窗内容，便于修正后重试
  } finally {
    submitting.value = false
  }
}

async function removeZone(zone: AirspaceZone): Promise<void> {
  try {
    await airspaceApi.remove(zone.id)
    message.success(`空域「${zone.name ?? zone.code}」已删除`)
    await loadZones()
  } catch {
    /* 已提示 */
  }
}

async function loadZones(): Promise<void> {
  loading.value = true
  try {
    zones.value = await airspaceApi.list()
    if (selectedZoneId.value !== null && !zones.value.some((zone) => zone.id === selectedZoneId.value)) {
      selectedZoneId.value = null
    }
  } catch {
    /* client.ts 已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadZones()
})
</script>

<template>
  <div class="airspace-view">
    <!-- ------------------------------ 空域态势指挥台 ------------------------------ -->
    <section class="airspace">
      <div class="airspace-head">
        <div>
          <div class="airspace-title">
            <span class="airspace-led" aria-hidden="true" />
            空域态势指挥台
            <span class="airspace-live">LIVE</span>
          </div>
          <div class="airspace-sub">禁飞 / 限飞 / 走廊按高度分层 · 与实时航迹叠加检查 · 冲突可直接解除</div>
        </div>
        <div class="airspace-metrics">
          <div class="metric">
            <em>空域总数</em>
            <strong>{{ zones.length }}<i>个</i></strong>
          </div>
          <div class="metric">
            <em>启用中</em>
            <strong>{{ enabledZones.length }}<i>个</i></strong>
          </div>
          <div class="metric">
            <em>禁飞区</em>
            <strong :class="{ danger: typeCounts.NO_FLY > 0 }">{{ typeCounts.NO_FLY }}<i>个</i></strong>
          </div>
          <div class="metric">
            <em>当前侵入</em>
            <strong :class="{ danger: intrusionCount > 0 }">{{ intrusionCount }}<i>架</i></strong>
          </div>
          <div class="metric">
            <em>未处理冲突</em>
            <strong :class="{ danger: conflicts.length > 0 }">{{ conflicts.length }}<i>条</i></strong>
          </div>
        </div>
      </div>

      <!-- 类型筛选 -->
      <div class="type-chips">
        <button
          type="button"
          class="type-chip"
          :class="{ active: !typeFilter }"
          @click="typeFilter = undefined"
        >
          全部 <b>{{ zones.length }}</b>
        </button>
        <button
          v-for="type in ZONE_TYPES"
          :key="type"
          type="button"
          class="type-chip"
          :class="{ active: typeFilter === type }"
          :style="{ '--chip-color': ZONE_HEX[type] }"
          @click="toggleType(type)"
        >
          {{ ZONE_TYPE_TEXT[type] }} <b>{{ typeCounts[type] }}</b>
        </button>
      </div>

      <div class="risk-note">
        <WarningOutlined />
        <span>
          禁飞区与限飞区会立即参与路径规划：新增或删除后，后续所有派单都会把启用中的空域作为障碍物传给算法服务。
        </span>
      </div>

      <div class="command-grid">
        <!-- 地图 -->
        <a-card :bordered="false" size="small" class="map-card">
          <template #title>
            空域分布总览
            <span class="map-card-sub">点色块选中、点省面下钻</span>
          </template>
          <template #extra>
            <a-button size="small" @click="overviewRef?.resetView()">全国视野</a-button>
          </template>
          <AirspaceOverviewMap
            ref="overviewRef"
            :zones="filteredZones"
            :highlight-id="selectedZoneId"
            height="400px"
            @select="onZoneSelect"
          />
        </a-card>

        <!-- 高度层剖面 + 实时冲突 -->
        <div class="side-column">
          <div class="side-card">
            <div class="side-head">
              <span class="side-title">高度层占用剖面</span>
              <span class="side-note">每 20m 一档 · 堆叠为该高度覆盖的空域数</span>
            </div>
            <svg
              v-if="altitudeChart"
              class="altitude-svg"
              :viewBox="`0 0 ${altitudeChart.width} ${altitudeChart.height}`"
              role="img"
              aria-label="高度层空域占用剖面"
            >
              <!-- 当前在飞高度带 -->
              <rect
                v-if="altitudeChart.flyingBand"
                :x="altitudeChart.flyingBand.x"
                :y="altitudeChart.padT"
                :width="altitudeChart.flyingBand.w"
                :height="altitudeChart.chartH"
                class="alt-flying-band"
              />
              <text
                v-if="altitudeChart.flyingBand"
                :x="altitudeChart.flyingBand.x + altitudeChart.flyingBand.w / 2"
                :y="altitudeChart.padT - 7"
                text-anchor="middle"
                class="alt-flying-label"
              >
                {{ altitudeChart.flyingBand.label }}
              </text>

              <!-- 堆叠柱 -->
              <rect
                v-for="(segment, index) in altitudeChart.segments"
                :key="`seg-${index}`"
                :x="segment.x"
                :y="segment.y"
                :width="segment.w"
                :height="segment.h"
                :fill="segment.color"
                class="alt-segment"
              />

              <!-- 每档总数 -->
              <text
                v-for="(total, index) in altitudeChart.totals"
                :key="`total-${index}`"
                :x="total.x"
                :y="total.y"
                text-anchor="middle"
                class="alt-total"
              >
                {{ total.total }}
              </text>

              <!-- 坐标轴 -->
              <line
                :x1="altitudeChart.padL"
                :y1="altitudeChart.axisY"
                :x2="altitudeChart.endX"
                :y2="altitudeChart.axisY"
                class="alt-axis"
              />
              <text
                v-for="(tick, index) in altitudeChart.ticks"
                :key="`tick-${index}`"
                :x="tick.x"
                :y="altitudeChart.axisY + 14"
                :text-anchor="tick.anchor"
                class="alt-tick"
              >
                {{ tick.label }}m
              </text>
            </svg>
            <div v-else class="side-empty">暂无启用中的空域</div>

            <div v-if="altitudeChart" class="altitude-foot">
              <span>
                最拥堵：<b>{{ altitudeChart.busiest.lo }}–{{ altitudeChart.busiest.hi }}m</b>
                （{{ altitudeChart.busiest.total }} 个空域重叠）
              </span>
              <span v-if="flyingAltitude" class="altitude-live text-mono">
                在飞 {{ flyingAltitude.min.toFixed(0) }}–{{ flyingAltitude.max.toFixed(0) }}m
              </span>
            </div>
          </div>

          <div class="side-card">
            <div class="side-head">
              <span class="side-title">实时冲突</span>
              <span class="side-note">{{ conflicts.length }} 条未处理</span>
            </div>
            <div v-if="liveConflicts.length" class="conflict-list">
              <button
                v-for="conflict in liveConflicts"
                :key="conflict.id"
                type="button"
                class="conflict-item"
                @click="focusConflict(conflict)"
              >
                <span
                  class="conflict-dot"
                  :style="{ background: SEVERITY_HEX[conflict.severity] ?? '#5b7c99' }"
                />
                <div class="conflict-body">
                  <div class="conflict-head">
                    <span class="conflict-type">{{ CONFLICT_TYPE_TEXT[conflict.conflictType] ?? conflict.conflictType }}</span>
                    <a-tag :color="CONFLICT_SEVERITY_COLOR[conflict.severity]">
                      {{ CONFLICT_SEVERITY_TEXT[conflict.severity] }}
                    </a-tag>
                    <span class="conflict-time">{{ formatFromNow(conflict.detectedAt) }}</span>
                  </div>
                  <div class="conflict-msg">{{ conflict.message ?? '—' }}</div>
                </div>
                <a-button size="small" type="link" @click.stop="handleResolve(conflict)">解除</a-button>
              </button>
            </div>
            <div v-else class="side-empty">当前空域态势正常，无未处理冲突</div>
          </div>
        </div>
      </div>
    </section>

    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-input
          v-model:value="keyword"
          class="filter"
          placeholder="搜索空域名称 / 编码 / 备注"
          allow-clear
        />
        <a-button :loading="loading" @click="loadZones">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-tag class="count-tag">显示 {{ filteredZones.length }} / {{ zones.length }} 个空域</a-tag>
        <a-button v-if="typeFilter || keyword" type="link" size="small" @click="typeFilter = undefined; keyword = ''">
          清除筛选
        </a-button>
      </a-space>

      <template #extra>
        <a-button type="primary" @click="openModal">
          <template #icon><PlusOutlined /></template>
          新增空域
        </a-button>
      </template>
    </a-card>

    <!-- ------------------------------ 空域卡片 ------------------------------ -->
    <a-spin :spinning="loading">
      <div v-if="filteredZones.length" class="zone-grid">
        <article
          v-for="zone in filteredZones"
          :key="zone.id"
          class="zone-card"
          :class="[`type-${zone.zoneType.toLowerCase()}`, { selected: selectedZoneId === zone.id, disabled: zone.enabled === false }]"
          @click="selectZone(zone)"
        >
          <header class="zone-head">
            <div class="zone-name">
              <span class="zone-sigil" :style="{ background: ZONE_HEX[zone.zoneType] }" />
              <span class="zone-title">{{ zone.name ?? zone.code }}</span>
            </div>
            <div class="zone-tags">
              <a-tag :color="ZONE_TYPE_COLOR[zone.zoneType]">{{ ZONE_TYPE_TEXT[zone.zoneType] }}</a-tag>
              <a-tag :color="zone.enabled === false ? 'default' : 'success'">
                {{ zone.enabled === false ? '停用' : '启用' }}
              </a-tag>
            </div>
          </header>

          <div class="zone-sub text-mono">
            {{ zone.code }}
            <span class="zone-shape">{{ shapeText(zone) }}</span>
            <span class="zone-area">{{ areaText(zone) }}</span>
          </div>

          <!-- 高度条：在全局高度域里的实际位置 -->
          <div class="zone-altitude">
            <div class="alt-track">
              <i :style="altitudeBandStyle(zone)" />
            </div>
            <span class="alt-text text-mono">
              {{ Number(zone.minAltitudeM ?? 0).toFixed(0) }}–{{ Number(zone.maxAltitudeM ?? 120).toFixed(0) }} m
            </span>
          </div>

          <div class="zone-facts">
            <span>
              <em>严重度</em>
              <a-tag
                v-if="zone.severity"
                :color="CONFLICT_SEVERITY_COLOR[zone.severity]"
                class="inline-tag"
              >
                {{ CONFLICT_SEVERITY_TEXT[zone.severity] }}
              </a-tag>
              <span v-else class="muted">按类型默认</span>
            </span>
            <span class="zone-remark" :title="zone.remark">
              <em>时间窗</em>{{ windowText(zone) }}
            </span>
            <span class="zone-remark" :title="zone.remark">
              <em>备注</em>{{ zone.remark || '—' }}
            </span>
          </div>

          <div class="zone-foot">
            <span class="occupancy-chip" :class="`is-${occupancyTone(zone)}`">
              {{ occupancyText(zone) }}
            </span>
            <a-button type="link" size="small" @click.stop="selectZone(zone)">
              <template #icon><AimOutlined /></template>
              定位
            </a-button>
            <a-popconfirm
              title="确认删除该空域？删除后不再参与路径规划。"
              ok-text="确认删除"
              cancel-text="取消"
              @confirm="removeZone(zone)"
            >
              <a-button type="link" size="small" danger @click.stop>删除</a-button>
            </a-popconfirm>
          </div>
        </article>
      </div>

      <a-empty
        v-else
        class="zones-empty"
        description="当前筛选下没有空域；可调整筛选或点击右上角「新增空域」"
      />
    </a-spin>

    <!-- ------------------------------ 新增空域弹窗 ------------------------------ -->
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
              <span class="point-count">
                已填 {{ points.filter((point) => point.lat !== null && point.lon !== null).length }} 个（至少 3 个）
              </span>
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
              <a-button type="text" danger @click="removePoint(index)">
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

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="仿真生效开始（s）">
              <a-input-number
                v-model:value="form.activeFromSimSec"
                :min="0"
                :step="60"
                placeholder="留空 = 从仿真开始生效"
                class="num"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="仿真生效结束（s）">
              <a-input-number
                v-model:value="form.activeToSimSec"
                :min="0"
                :step="60"
                placeholder="留空 = 长期有效"
                class="num"
              />
              <div class="field-hint">临时管制按仿真秒开关：到点自动生效/解除，规划与冲突检测同步</div>
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
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card,
.map-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.filter {
  width: 260px;
}

.count-tag {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
}

/* =============================================================================
   空域态势指挥台
   ============================================================================= */
.airspace {
  position: relative;
  padding: 14px 16px 14px;
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

.airspace::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 26%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  animation: airspace-scan 8s linear infinite;
}

@keyframes airspace-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(480%);
  }
}

.airspace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-bottom: 8px;
}

.airspace-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: #e9fbff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.3);
}

.airspace-led {
  width: 6px;
  height: 6px;
  background: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  transform: rotate(45deg);
  animation: airspace-led 1.8s ease-in-out infinite;
}

@keyframes airspace-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.airspace-live {
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

.airspace-sub {
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.62);
}

.airspace-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
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

.metric strong.danger {
  color: #ffb4b4;
  text-shadow: 0 0 10px rgba(255, 96, 96, 0.5);
}

/* 类型筛选 */
.type-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.type-chip {
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

.type-chip::before {
  content: '';
  width: 4px;
  height: 4px;
  background: var(--chip-color);
  box-shadow: 0 0 6px var(--chip-color);
  transform: rotate(45deg);
}

.type-chip b {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 600;
  color: #dffbff;
}

.type-chip:hover {
  border-color: rgba(56, 232, 255, 0.42);
  color: #7ff0ff;
}

.type-chip.active {
  border-color: var(--chip-color);
  background: color-mix(in srgb, var(--chip-color) 14%, transparent);
  box-shadow: 0 0 12px color-mix(in srgb, var(--chip-color) 30%, transparent);
}

.risk-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 10px;
  padding: 5px 9px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(255, 224, 176, 0.85);
  border: 1px solid rgba(240, 169, 59, 0.28);
  background: rgba(240, 169, 59, 0.06);
}

/* 地图 + 侧栏 */
.command-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(320px, 0.9fr);
  gap: 12px;
  align-items: start;
}

.map-card {
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.35);
}

.map-card-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
  color: rgba(180, 208, 232, 0.55);
}

.side-column {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.side-card {
  padding: 9px 11px 10px;
  border: 1px solid rgba(56, 232, 255, 0.14);
  background: linear-gradient(180deg, rgba(10, 26, 43, 0.75), rgba(6, 17, 29, 0.75));
}

.side-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.side-title {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #d9f6ff;
}

.side-note {
  font-size: 10px;
  color: rgba(150, 199, 232, 0.5);
}

.side-empty {
  padding: 16px 4px;
  text-align: center;
  font-size: 11px;
  color: rgba(150, 199, 232, 0.45);
}

/* 高度剖面 */
.altitude-svg {
  display: block;
  width: 100%;
  height: 190px;
}

.alt-segment {
  opacity: 0.85;
  transition: opacity 0.18s ease;
}

.alt-segment:hover {
  opacity: 1;
}

.alt-flying-band {
  fill: rgba(56, 232, 255, 0.14);
  stroke: rgba(56, 232, 255, 0.5);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.alt-flying-label {
  fill: #9ff0ff;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 9px;
}

.alt-total {
  fill: rgba(216, 240, 255, 0.72);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 9px;
}

.alt-axis {
  stroke: rgba(56, 232, 255, 0.25);
  stroke-width: 1;
}

.alt-tick {
  fill: rgba(150, 199, 232, 0.45);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 9px;
}

.altitude-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin-top: 4px;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.55);
}

.altitude-foot b {
  color: #ffe0b0;
}

.altitude-live {
  color: #9ff0ff;
}

/* 实时冲突 */
.conflict-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 180px;
  overflow-y: auto;
}

.conflict-item {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  width: 100%;
  padding: 5px 7px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  border: 1px solid rgba(56, 232, 255, 0.1);
  background: rgba(8, 21, 35, 0.55);
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.conflict-item:hover {
  border-color: rgba(56, 232, 255, 0.35);
  background: rgba(56, 232, 255, 0.05);
}

.conflict-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  margin-top: 5px;
  border-radius: 50%;
  box-shadow: 0 0 7px currentColor;
}

.conflict-body {
  flex: 1 1 auto;
  min-width: 0;
}

.conflict-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 6px;
}

.conflict-type {
  font-size: 11px;
  font-weight: 600;
  color: #d9f6ff;
}

.conflict-time {
  margin-left: auto;
  font-size: 9px;
  color: rgba(150, 199, 232, 0.42);
}

.conflict-msg {
  margin-top: 1px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  font-size: 10px;
  line-height: 1.5;
  color: rgba(216, 240, 255, 0.72);
}

/* =============================================================================
   空域卡片
   ============================================================================= */
.zone-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.zone-card {
  --zone-tone: #38e8ff;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  cursor: pointer;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.16);
  border-left: 3px solid var(--zone-tone);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(13, 32, 50, 0.9), rgba(7, 19, 32, 0.9));
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.38);
  transition:
    transform 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.zone-card:hover {
  transform: translateY(-2px);
  border-color: rgba(56, 232, 255, 0.42);
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.46),
    0 0 16px rgba(56, 232, 255, 0.14);
}

.zone-card.selected {
  border-color: #38e8ff;
  box-shadow:
    inset 0 0 22px rgba(56, 232, 255, 0.08),
    0 0 18px rgba(56, 232, 255, 0.24);
}

.zone-card.disabled {
  opacity: 0.62;
}

.zone-card.type-no_fly {
  --zone-tone: #ff4d4f;
}
.zone-card.type-restricted {
  --zone-tone: #fa8c16;
}
.zone-card.type-building {
  --zone-tone: #722ed1;
}
.zone-card.type-corridor {
  --zone-tone: #52c41a;
}
.zone-card.type-warning {
  --zone-tone: #faad14;
}

.zone-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.zone-name {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.zone-sigil {
  flex: 0 0 auto;
  width: 5px;
  height: 5px;
  box-shadow: 0 0 7px currentColor;
  transform: rotate(45deg);
}

.zone-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
  color: #e3f7ff;
}

.zone-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
}

.zone-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 3px 10px;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.55);
}

.zone-shape,
.zone-area {
  padding-left: 10px;
  border-left: 1px solid rgba(56, 232, 255, 0.14);
}

/* 高度条 */
.zone-altitude {
  display: flex;
  align-items: center;
  gap: 8px;
}

.alt-track {
  position: relative;
  flex: 1 1 auto;
  height: 6px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.08);
}

.alt-track i {
  position: absolute;
  top: 0;
  bottom: 0;
  opacity: 0.8;
  box-shadow: 0 0 8px currentColor;
}

.alt-text {
  flex: 0 0 auto;
  font-size: 10px;
  color: rgba(159, 240, 255, 0.82);
}

.zone-facts {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11px;
  color: rgba(216, 240, 255, 0.82);
}

.zone-facts em {
  margin-right: 4px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.5);
}

.inline-tag {
  margin-inline-end: 0;
  transform: scale(0.9);
  transform-origin: left center;
}

.muted {
  color: rgba(150, 199, 232, 0.45);
}

.zone-remark {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(150, 199, 232, 0.66);
}

.zone-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: auto;
  padding-top: 7px;
  border-top: 1px solid rgba(56, 232, 255, 0.1);
}

.occupancy-chip {
  margin-right: auto;
  padding: 1px 7px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.55);
  border: 1px solid rgba(150, 199, 232, 0.16);
  background: rgba(150, 199, 232, 0.04);
}

.occupancy-chip.is-idle {
  color: rgba(47, 214, 165, 0.85);
  border-color: rgba(47, 214, 165, 0.25);
  background: rgba(47, 214, 165, 0.06);
}

.occupancy-chip.is-active {
  color: #9ff0ff;
  border-color: rgba(56, 232, 255, 0.3);
  background: rgba(56, 232, 255, 0.08);
}

.occupancy-chip.is-danger {
  color: #ffb4b4;
  border-color: rgba(255, 96, 96, 0.4);
  background: rgba(255, 96, 96, 0.1);
  box-shadow: 0 0 10px rgba(255, 96, 96, 0.15);
}

.zones-empty {
  padding: 60px 0;
  border: 1px dashed rgba(56, 232, 255, 0.18);
  background: rgba(7, 19, 32, 0.4);
}

/* =============================================================================
   新增空域弹窗（沿用原样式）
   ============================================================================= */
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

@media (max-width: 1100px) {
  .command-grid {
    grid-template-columns: 1fr;
  }

  .side-column {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .side-column {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .airspace::after,
  .airspace-led {
    animation: none;
  }
}

:deep(.ant-modal-body) {
  max-height: 72vh;
  overflow-y: auto;
}
</style>
