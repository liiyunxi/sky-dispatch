<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import type { AirspaceZone } from '@/api'
import { attachChinaBaseLayer } from './ChinaBaseLayer'
import { attachAdminBoundaries, type AdminBoundaryHandle } from './AdminBoundaryLayer'

/**
 * 空域地图拾取器。
 *
 * <p><b>为什么需要它</b>：原表单要求用户手工输入圆心经纬度与半径，等于让运营自己去查地图坐标，
 * 门槛高且极易填错（差一位小数就是几公里的偏差）。这里改成"在图上点一下就填好"：
 * <ul>
 *   <li><b>圆形</b>：点击地图设圆心，拖动圆心改位置，拖动圆边上的手柄改半径；</li>
 *   <li><b>多边形</b>：依次点击地图加顶点，顶点可拖动微调，支持撤销上一个 / 清空重画；</li>
 *   <li>已存在的空域以淡色画出来做参照，避免新建的空域与既有禁飞区重叠；</li>
 *   <li>底部实时显示鼠标处的经纬度，便于核对。</li>
 * </ul>
 *
 * <p><b>为什么必须铺真实地理底图</b>：划定空域的前提是知道"这块地在哪"。
 * 所以这里复用态势地图的 ChinaBaseLayer（海面 + 经纬网 + 主要城市名）与
 * AdminBoundaryLayer（省界 + 省/市/区县名），并把行政图层设为 <b>非交互</b>
 * （{@code interactive: false}）——否则点省面会被它抢去下钻，用户根本没法在地图上选点。
 *
 * <p>表单里的数字输入框<b>保留</b>（精细微调到小数点后 6 位），两边双向同步：
 * 图上动一下，表单数字立刻变；表单里改数字，图上也立刻跟着变。
 */

/** 与 AirspaceView 表单一致的多边形顶点结构（null = 该坐标未填） */
export interface PickerPoint {
  lat: number | null
  lon: number | null
}

const props = withDefaults(
  defineProps<{
    shapeType: 'CIRCLE' | 'POLYGON'
    centerLat: number
    centerLon: number
    radiusM: number
    points: PickerPoint[]
    /** 已有空域，作为参照底图 */
    zones?: AirspaceZone[]
    height?: string
  }>(),
  { zones: () => [], height: '320px' },
)

const emit = defineEmits<{
  (e: 'update:centerLat', v: number): void
  (e: 'update:centerLon', v: number): void
  (e: 'update:radiusM', v: number): void
  (e: 'update:points', v: PickerPoint[]): void
}>()

const mapEl = ref<HTMLElement | null>(null)
const cursorText = ref('')

let map: L.Map | null = null
/** 行政区参照图层的控制句柄（卸载时要销毁，避免遗留图层与监听） */
let adminHandle: AdminBoundaryHandle | null = null
/** 当前正在绘制的图形（圆心标记 / 圆 / 顶点 / 折线） */
let draftGroup: L.LayerGroup | null = null
/** 已有空域的参照图层 */
let referenceGroup: L.LayerGroup | null = null
let centerMarker: L.Marker | null = null
let radiusHandle: L.Marker | null = null
let circle: L.Circle | null = null
let polyline: L.Polyline | null = null
let vertexMarkers: L.Marker[] = []
/** 拖动标记时用来抑制"拖完又触发一次地图点击"的误判 */
let dragging = false

/** 上海的兜底视野：没有任何参照空域时用它，保证打开就有一片可点的地图 */
const FALLBACK_CENTER: [number, number] = [31.2304, 121.4737]

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6
}

// ---------------------------------------------------------------------------
// 参照空域
// ---------------------------------------------------------------------------

function buildReference(): void {
  if (!map || !referenceGroup) return
  referenceGroup.clearLayers()
  for (const zone of props.zones ?? []) {
    const color = zone.zoneType === 'NO_FLY' ? '#ff4d4f' : '#faad14'
    if (zone.shapeType === 'CIRCLE' && zone.center) {
      L.circle([zone.center.lat, zone.center.lon], {
        radius: zone.radiusM ?? 0,
        color,
        weight: 1,
        fillColor: color,
        fillOpacity: 0.08,
        interactive: false,
      })
        .bindTooltip(zone.name ?? zone.code ?? '', { sticky: true })
        .addTo(referenceGroup)
    } else if (zone.shapeType === 'POLYGON' && (zone.points?.length ?? 0) >= 3) {
      L.polygon(
        (zone.points ?? []).map((p) => [p.lat, p.lon] as [number, number]),
        { color, weight: 1, fillColor: color, fillOpacity: 0.08, interactive: false },
      )
        .bindTooltip(zone.name ?? zone.code ?? '', { sticky: true })
        .addTo(referenceGroup)
    }
  }
}

// ---------------------------------------------------------------------------
// 草稿图形
// ---------------------------------------------------------------------------

const CENTER_ICON = L.divIcon({
  className: 'picker-icon-host',
  iconSize: [14, 14],
  iconAnchor: [7, 7],
  html: '<div class="picker-center"></div>',
})

const RADIUS_ICON = L.divIcon({
  className: 'picker-icon-host',
  iconSize: [14, 14],
  iconAnchor: [7, 7],
  html: '<div class="picker-radius"></div>',
})

const VERTEX_ICON = L.divIcon({
  className: 'picker-icon-host',
  iconSize: [12, 12],
  iconAnchor: [6, 6],
  html: '<div class="picker-vertex"></div>',
})

/** 取有效的多边形顶点（lat/lon 都填了的） */
function validPoints(): Array<{ lat: number; lon: number }> {
  return (props.points ?? [])
    .filter((p): p is { lat: number; lon: number } => p.lat !== null && p.lon !== null)
    .map((p) => ({ lat: p.lat, lon: p.lon }))
}

function rebuildDraft(): void {
  // 取本地引用：在下面的回调里 TS 无法保持对模块级变量的非空收窄
  const group = draftGroup
  if (!map || !group) return
  group.clearLayers()
  centerMarker = null
  radiusHandle = null
  circle = null
  polyline = null
  vertexMarkers = []

  if (props.shapeType === 'CIRCLE') {
    const center = L.latLng(props.centerLat, props.centerLon)
    const radius = Math.max(1, props.radiusM || 0)

    circle = L.circle(center, {
      radius,
      color: '#1677ff',
      weight: 2,
      fillColor: '#1677ff',
      fillOpacity: 0.16,
      interactive: false,
    }).addTo(group)

    // 圆心可拖：拖完把新坐标回写表单
    centerMarker = L.marker(center, { icon: CENTER_ICON, draggable: true, keyboard: false })
      .bindTooltip('拖动可移动圆心', { direction: 'top' })
      .addTo(group)
    centerMarker.on('dragstart', () => {
      dragging = true
    })
    centerMarker.on('drag', () => {
      const ll = centerMarker?.getLatLng()
      if (ll && circle) {
        circle.setLatLng(ll)
        radiusHandle?.setLatLng(radiusHandleLatLng(ll, radius))
      }
    })
    centerMarker.on('dragend', () => {
      const ll = centerMarker?.getLatLng()
      if (ll) {
        emit('update:centerLat', round6(ll.lat))
        emit('update:centerLon', round6(ll.lng))
      }
      window.setTimeout(() => {
        dragging = false
      }, 0)
    })

    // 半径手柄：放在圆心正东 radius 米处，拖动即改半径
    radiusHandle = L.marker(radiusHandleLatLng(center, radius), {
      icon: RADIUS_ICON,
      draggable: true,
      keyboard: false,
    })
      .bindTooltip('拖动可调整半径', { direction: 'top' })
      .addTo(group)
    radiusHandle.on('dragstart', () => {
      dragging = true
    })
    radiusHandle.on('drag', () => {
      const handle = radiusHandle?.getLatLng()
      const origin = centerMarker?.getLatLng()
      if (handle && origin && circle) {
        circle.setRadius(Math.max(1, origin.distanceTo(handle)))
      }
    })
    radiusHandle.on('dragend', () => {
      const handle = radiusHandle?.getLatLng()
      const origin = centerMarker?.getLatLng()
      if (handle && origin) {
        emit('update:radiusM', Math.round(Math.max(1, origin.distanceTo(handle))))
      }
      window.setTimeout(() => {
        dragging = false
      }, 0)
    })
    return
  }

  // 多边形：折线 + 可拖顶点
  const pts = validPoints()
  const latlngs = pts.map((p) => [p.lat, p.lon] as [number, number])

  if (latlngs.length >= 2) {
    // 先画折线（不闭合）：闭合要等顶点够 3 个才有意义
    polyline = L.polyline(latlngs, {
      color: '#1677ff',
      weight: 2,
      dashArray: '6 4',
      fillColor: '#1677ff',
      fillOpacity: latlngs.length >= 3 ? 0.14 : 0,
    }).addTo(group)
  }

  latlngs.forEach((ll, index) => {
    const marker = L.marker(ll, { icon: VERTEX_ICON, draggable: true, keyboard: false })
      .bindTooltip(`顶点 ${index + 1}（可拖动）`, { direction: 'top' })
      .addTo(group)
    marker.on('dragstart', () => {
      dragging = true
    })
    marker.on('drag', () => {
      const next = latlngs.map((p, i) => (i === index ? [marker.getLatLng().lat, marker.getLatLng().lng] as [number, number] : p))
      if (polyline) {
        polyline.setLatLngs(next)
      }
    })
    marker.on('dragend', () => {
      const llMoved = marker.getLatLng()
      const updated = (props.points ?? []).map((p, i) =>
        i === index
          ? { lat: round6(llMoved.lat), lon: round6(llMoved.lng) }
          : { lat: p.lat, lon: p.lon },
      )
      emit('update:points', updated)
      window.setTimeout(() => {
        dragging = false
      }, 0)
    })
    vertexMarkers.push(marker)
  })
}

/** 圆心正东 radius 米处的坐标（半径手柄位置） */
function radiusHandleLatLng(center: L.LatLng, radius: number): L.LatLng {
  const metersPerDegreeLat = 111320
  const metersPerDegreeLon = metersPerDegreeLat * Math.cos((center.lat * Math.PI) / 180)
  return L.latLng(center.lat, center.lng + radius / Math.max(1, metersPerDegreeLon))
}

// ---------------------------------------------------------------------------
// 地图点击
// ---------------------------------------------------------------------------

function onMapClick(e: L.LeafletMouseEvent): void {
  if (dragging) return
  if (props.shapeType === 'CIRCLE') {
    emit('update:centerLat', round6(e.latlng.lat))
    emit('update:centerLon', round6(e.latlng.lng))
    return
  }
  // 多边形：追加顶点。显式声明为 PickerPoint[]，因为下面还要补 null 占位行
  const next: PickerPoint[] = validPoints().map((p) => ({ lat: p.lat, lon: p.lon }))
  next.push({ lat: round6(e.latlng.lat), lon: round6(e.latlng.lng) })
  // 末尾补两个空占位，保持"至少 3 个顶点"的表单骨架不被破坏
  while (next.length < 3) {
    next.push({ lat: null, lon: null })
  }
  emit('update:points', next)
}

function onMouseMove(e: L.LeafletMouseEvent): void {
  cursorText.value = `${e.latlng.lat.toFixed(5)}, ${e.latlng.lng.toFixed(5)}`
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(async () => {
  if (!mapEl.value) return
  map = L.map(mapEl.value, {
    center: FALLBACK_CENTER,
    zoom: 12,
    zoomControl: true,
    attributionControl: false,
  })

  // ---- 真实地理底图：让用户看清"这块地到底在哪个省/市" ----
  // 海面 + 经纬网 + 主要城市名（纯背景，不参与交互）
  void attachChinaBaseLayer(map)
  // 省界 + 省/市/区县常驻名称；interactive=false 保证点击不被省面抢走（点击要留给"选点"）
  adminHandle = await attachAdminBoundaries(map, { interactive: false })
  if (!adminHandle.ready) {
    // 省级 GeoJSON 缺失时至少给一块深色底，避免地图闪白无从下手
    map.getContainer().style.background = '#07131f'
  }

  referenceGroup = L.layerGroup().addTo(map)
  draftGroup = L.layerGroup().addTo(map)

  map.on('click', onMapClick)
  map.on('mousemove', onMouseMove)

  buildReference()
  rebuildDraft()
  focusToReference()

  // 弹窗有开合动画，容器尺寸要等动画结束才稳定
  await nextTick()
  window.setTimeout(() => map?.invalidateSize(), 220)
})

onBeforeUnmount(() => {
  map?.off('click', onMapClick)
  map?.off('mousemove', onMouseMove)
  adminHandle?.destroy()
  adminHandle = null
  map?.remove()
  map = null
  draftGroup = null
  referenceGroup = null
  centerMarker = null
  radiusHandle = null
  circle = null
  polyline = null
  vertexMarkers = []
})

/** 参照空域跨度超过该值（米）就认为"分布在全国"，不再整体 fitBounds */
const SPREAD_LIMIT_M = 300_000

/**
 * 把视野收敛到参照空域范围。
 *
 * <p>空域集中在同一个城市时整体 fitBounds；但空域<b>分布在全国四个城市</b>时，
 * 整体 fitBounds 会把视野拉到国土尺度 —— 在那种缩放下点击地图，一个像素就是几十公里，
 * 根本点不准。所以跨度太大时退回"城市级可操作视野"（默认上海），
 * 参照空域仍画在图上，用户平移即可找到目标城市。
 */
function focusToReference(): void {
  if (!map) return
  const bounds = L.latLngBounds([])
  for (const zone of props.zones ?? []) {
    if (zone.shapeType === 'CIRCLE' && zone.center) {
      bounds.extend(L.latLng(zone.center.lat, zone.center.lon))
    } else if (zone.shapeType === 'POLYGON') {
      for (const p of zone.points ?? []) {
        bounds.extend(L.latLng(p.lat, p.lon))
      }
    }
  }
  if (!bounds.isValid()) {
    map.setView(FALLBACK_CENTER, 12)
    return
  }
  const spanMeters = bounds.getNorthWest().distanceTo(bounds.getSouthEast())
  if (spanMeters <= SPREAD_LIMIT_M) {
    map.fitBounds(bounds.pad(0.25), { maxZoom: 13 })
  } else {
    map.setView(FALLBACK_CENTER, 12)
  }
}

/** 供父组件调用的"回到参照视野" */
defineExpose({
  focusToReference: () => focusToReference(),
})

watch(() => props.zones, () => {
  buildReference()
  focusToReference()
})
watch(() => props.shapeType, () => rebuildDraft())
watch(
  () => [props.centerLat, props.centerLon, props.radiusM],
  () => rebuildDraft(),
)
watch(() => props.points, () => rebuildDraft(), { deep: true })
</script>

<template>
  <div class="picker">
    <div ref="mapEl" class="picker-canvas" :style="{ height }"></div>
    <div class="picker-tip">
      <template v-if="shapeType === 'CIRCLE'">
        在图上<b>点一下设圆心</b>，拖动圆心改位置、拖动圆边手柄改半径（也可用右侧数字精确微调）
      </template>
      <template v-else>
        依次<b>点击地图添加多边形顶点</b>，顶点可拖动微调；至少 3 个顶点
      </template>
    </div>
    <div class="picker-readout">
      <span class="picker-readout-label">光标</span>
      <span class="text-mono">{{ cursorText || '—' }}</span>
    </div>
  </div>
</template>

<style scoped>
.picker {
  position: relative;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}

.picker-canvas {
  width: 100%;
}

.picker-tip {
  position: absolute;
  left: 8px;
  top: 8px;
  z-index: 500;
  max-width: calc(100% - 60px);
  padding: 5px 9px;
  background: rgba(7, 20, 34, 0.9);
  border-radius: 4px;
  box-shadow: 0 0 0 1px rgba(34, 211, 238, 0.18);
  font-size: 12px;
  line-height: 1.5;
  color: rgba(200, 224, 245, 0.85);
  pointer-events: none;
}

.picker-readout {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 500;
  display: flex;
  gap: 6px;
  padding: 3px 8px;
  background: rgba(7, 20, 34, 0.9);
  border-radius: 4px;
  box-shadow: 0 0 0 1px rgba(34, 211, 238, 0.18);
  font-size: 12px;
  pointer-events: none;
}

.picker-readout-label {
  color: rgba(180, 208, 232, 0.6);
}

/* 拾取手柄：动态注入的 HTML，需要 :deep 才能命中 */
.picker :deep(.picker-icon-host) {
  background: transparent;
  border: none;
}

.picker :deep(.picker-center) {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #1677ff;
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
  cursor: move;
}

.picker :deep(.picker-radius) {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 3px solid #1677ff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.28);
  cursor: ew-resize;
}

.picker :deep(.picker-vertex) {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  background: #1677ff;
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
  cursor: move;
}
</style>
