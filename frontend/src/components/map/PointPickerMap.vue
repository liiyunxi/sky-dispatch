<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import type { AirspaceZone } from '@/api'
import { attachChinaBaseLayer } from './ChinaBaseLayer'
import { attachAdminBoundaries, type AdminBoundaryHandle } from './AdminBoundaryLayer'

/**
 * 单点地图拾取器（无人机起降点 / 订单取货点 / 送货点）。
 *
 * <p><b>为什么需要它</b>：让用户手填"纬度 31.230400、经度 121.473700"是不现实的 ——
 * 差一位小数就是几公里的偏差，而且没人知道那串数字到底在城市的哪一边。
 * 这里改成"在图上点一下就填好"，并且铺上真实地理底图（省界 + 省/市/区县名 + 城市点位），
 * 用户点之前就知道自己点在哪。
 *
 * <p><b>与 {@code AirspacePickerMap} 的分工</b>：那个是"画范围"的编辑器（圆心 + 半径 +
 * 多边形顶点 + 撤销重画），本组件只做一件事 —— <b>选一个点</b>。所以没有草稿图形、
 * 没有手柄、没有半径，API 也只有 lat/lon 两个双向绑定。
 *
 * <p><b>为什么参照点（{@link ReferencePoint}）不接收点击</b>：地图点击的语义在这里是
 * "把当前点放到这儿"。如果参照标记也抢点击，用户点一下想切换、结果反而把坐标改掉了 ——
 * 这类"点哪改哪"的歧义在表单里非常容易出错，所以参照点一律 {@code interactive: false}，
 * 切换当前点交给表单上的选择器。
 */

/**
 * 参照点：已经确定的另一个点（例如订单里"不是当前正在拾取"的那个）。
 *
 * <p>只在本组件内部使用（`<script setup>` 不允许对外导出模块成员），
 * 调用方按结构传值即可。
 */
interface ReferencePoint {
  label: string
  lat: number
  lon: number
  /** 标记颜色，缺省为橙色 */
  color?: string
}

const props = withDefaults(
  defineProps<{
    /** 当前点的纬度；null 表示还没选 */
    lat: number | null
    lon: number | null
    /** 当前点叫什么（显示在提示与标记气泡里） */
    label?: string
    /** 其他已确定的点，仅作参照 */
    others?: ReferencePoint[]
    /** 已有空域：画成淡色参照，避免把起降点/收货点放进禁飞区或楼里 */
    zones?: AirspaceZone[]
    height?: string
  }>(),
  { label: '当前点', others: () => [], zones: () => [], height: '300px' },
)

const emit = defineEmits<{
  (e: 'update:lat', v: number): void
  (e: 'update:lon', v: number): void
}>()

const mapEl = ref<HTMLElement | null>(null)
const cursorText = ref('')

let map: L.Map | null = null
let adminHandle: AdminBoundaryHandle | null = null
/** 参照图层：已有空域 + 其他点 */
let referenceGroup: L.LayerGroup | null = null
/** 当前点标记（可拖动微调） */
let activeMarker: L.Marker | null = null
/** 拖动期间抑制"拖完顺手触发一次地图点击"的误判 */
let dragging = false

/** 兜底视野（上海）：没有任何参照时也要有一片可点的地图 */
const FALLBACK_CENTER: [number, number] = [31.2304, 121.4737]

const ACTIVE_ICON = L.divIcon({
  className: 'point-icon-host',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
  html: '<div class="point-pin"></div>',
})

function otherIcon(color: string): L.DivIcon {
  return L.divIcon({
    className: 'point-icon-host',
    iconSize: [14, 14],
    iconAnchor: [7, 7],
    html: `<div class="point-ref" style="background:${color}"></div>`,
  })
}

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6
}

/** 按空域类型取参照色（与态势地图的空域配色一致，认知统一） */
function zoneColor(zone: AirspaceZone): string {
  if (zone.zoneType === 'NO_FLY') return '#ff4d4f'
  if (zone.zoneType === 'RESTRICTED') return '#fa8c16'
  if (zone.zoneType === 'BUILDING') return '#722ed1'
  if (zone.zoneType === 'CORRIDOR') return '#52c41a'
  return '#faad14'
}

// ---------------------------------------------------------------------------
// 图层
// ---------------------------------------------------------------------------

function buildReference(): void {
  if (!map || !referenceGroup) return
  referenceGroup.clearLayers()

  // 已有空域：淡色铺底，让用户看清"这一片是禁飞区 / 楼群"
  for (const zone of props.zones ?? []) {
    const color = zoneColor(zone)
    const style: L.PathOptions = {
      color,
      weight: 1,
      fillColor: color,
      fillOpacity: 0.1,
      interactive: false,
    }
    if (zone.shapeType === 'CIRCLE' && zone.center) {
      L.circle([zone.center.lat, zone.center.lon], { ...style, radius: Number(zone.radiusM ?? 0) }).addTo(
        referenceGroup,
      )
    } else if (zone.shapeType === 'POLYGON' && (zone.points?.length ?? 0) >= 3) {
      L.polygon(
        (zone.points ?? []).map((p) => [p.lat, p.lon] as [number, number]),
        style,
      ).addTo(referenceGroup)
    }
  }

  // 其他点：纯参照，不接收点击（见文件头说明）
  for (const point of props.others ?? []) {
    L.marker([point.lat, point.lon], {
      icon: otherIcon(point.color ?? '#fa8c16'),
      interactive: false,
      keyboard: false,
    })
      .bindTooltip(point.label, { direction: 'top', opacity: 0.92 })
      .addTo(referenceGroup)
  }
}

/** 当前点标记：位置随 props 变化，也支持拖动微调 */
function renderActive(): void {
  if (!map) return
  const hasPoint = props.lat !== null && props.lon !== null
  if (!hasPoint) {
    if (activeMarker) {
      map.removeLayer(activeMarker)
      activeMarker = null
    }
    return
  }
  const latlng = L.latLng(props.lat as number, props.lon as number)
  if (!activeMarker) {
    activeMarker = L.marker(latlng, {
      icon: ACTIVE_ICON,
      draggable: true,
      keyboard: false,
      zIndexOffset: 400,
    }).addTo(map)
    activeMarker.bindTooltip(props.label, { direction: 'top', offset: [0, -10] })
    activeMarker.on('dragstart', () => {
      dragging = true
    })
    activeMarker.on('dragend', () => {
      const moved = activeMarker?.getLatLng()
      if (moved) {
        emit('update:lat', round6(moved.lat))
        emit('update:lon', round6(moved.lng))
      }
      window.setTimeout(() => {
        dragging = false
      }, 0)
    })
  } else {
    activeMarker.setLatLng(latlng)
    activeMarker.getTooltip()?.setContent(props.label)
  }
}

function onMapClick(e: L.LeafletMouseEvent): void {
  if (dragging) return
  emit('update:lat', round6(e.latlng.lat))
  emit('update:lon', round6(e.latlng.lng))
}

function onMouseMove(e: L.LeafletMouseEvent): void {
  cursorText.value = `${e.latlng.lat.toFixed(5)}, ${e.latlng.lng.toFixed(5)}`
}

/**
 * 把所有相关点收进视野（点太少时退回城市级缩放，不然会怼到几米）。
 *
 * <p>空域分布在全国时不做整体 fitBounds：那会把视野拉到国土尺度、根本点不准。
 */
function focusAll(): void {
  if (!map) return
  const bounds = L.latLngBounds([])
  if (props.lat !== null && props.lon !== null) bounds.extend(L.latLng(props.lat, props.lon))
  for (const point of props.others ?? []) bounds.extend(L.latLng(point.lat, point.lon))
  if (!bounds.isValid()) {
    map.setView(FALLBACK_CENTER, 12)
    return
  }
  const spanM = bounds.getNorthWest().distanceTo(bounds.getSouthEast())
  if (spanM > 300_000) {
    map.setView(FALLBACK_CENTER, 12)
    return
  }
  map.fitBounds(bounds.pad(0.35), { maxZoom: 13 })
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

  // 真实地理底图：点之前先看清"这块地在哪个省 / 市"
  void attachChinaBaseLayer(map)
  adminHandle = await attachAdminBoundaries(map, { interactive: false })
  if (!adminHandle.ready) {
    map.getContainer().style.background = '#07131f'
  }

  referenceGroup = L.layerGroup().addTo(map)
  buildReference()
  renderActive()
  focusAll()

  map.on('click', onMapClick)
  map.on('mousemove', onMouseMove)

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
  referenceGroup = null
  activeMarker = null
})

watch(() => [props.lat, props.lon], () => renderActive())
watch(() => props.label, () => renderActive())
watch(() => [props.zones, props.others], () => buildReference(), { deep: true })

defineExpose({ focusAll: () => focusAll() })
</script>

<template>
  <div class="point-picker">
    <div ref="mapEl" class="point-canvas" :style="{ height }"></div>
    <div class="point-tip">
      点击地图即可设定<b>{{ label }}</b>；拖动标记可微调（也可用右侧数字精确输入）
    </div>
    <div class="point-readout">
      <span class="point-readout-label">光标</span>
      <span class="text-mono">{{ cursorText || '—' }}</span>
    </div>
  </div>
</template>

<style scoped>
.point-picker {
  position: relative;
  border: 1px solid #1d3a55;
  border-radius: 6px;
  overflow: hidden;
}

.point-canvas {
  width: 100%;
}

.point-tip {
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

.point-readout {
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

.point-readout-label {
  color: rgba(180, 208, 232, 0.6);
}

/* 手柄是动态注入的 HTML，必须 :deep 才能命中 */
.point-picker :deep(.point-icon-host) {
  background: transparent;
  border: none;
}

/* 当前点：蓝色十字准星，拖起来像"瞄准" */
.point-picker :deep(.point-pin) {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #1677ff;
  border: 3px solid #fff;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.4);
  cursor: move;
}

/* 参照点：同色系小圆点，一眼区分"已定的另一个点" */
.point-picker :deep(.point-ref) {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
}
</style>
