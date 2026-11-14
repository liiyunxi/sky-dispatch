<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import { FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { AirspaceZone } from '@/api'
import { attachChinaBaseLayer, CHINA_BOUNDS } from './ChinaBaseLayer'
import { attachAdminBoundaries, type AdminBoundaryHandle } from './AdminBoundaryLayer'
import { CONFLICT_SEVERITY_TEXT, ZONE_TYPE_TEXT } from '@/utils/format'

/**
 * 空域总览地图（常驻在空域管理页，不藏进弹窗）。
 *
 * <p><b>为什么需要它</b>：空域数据只有表格的话，运营根本不知道"这些区分布在哪儿、
 * 有没有互相重叠、新增的这个区会不会盖住某条航路"。所以把全部空域直接铺在真实地理底图上，
 * 并把表格行与地图图形联动：<b>点表格行 → 地图定位并高亮该区；点地图上的区 → 表格该行也选中</b>。
 *
 * <p>底图复用态势地图的 ChinaBaseLayer（海面 + 经纬网 + 主要城市名）与
 * AdminBoundaryLayer（省界 + 省/市/区县名，可点击下钻），保证与监控页看到的是同一张地图，
 * 不会出现"两个页面地图长得不一样"的割裂感。
 */

const props = withDefaults(
  defineProps<{
    zones: AirspaceZone[]
    /** 当前高亮的空域 id（来自表格选中行），null 表示不高亮 */
    highlightId?: number | null
    height?: string
  }>(),
  { highlightId: null, height: '360px' },
)

const emit = defineEmits<{
  (e: 'select', zone: AirspaceZone): void
}>()

/** 全国默认视野：没有空域数据时也要能看清自己在哪 */
const CHINA_VIEW: { center: [number, number]; zoom: number } = { center: [34.5, 106.5], zoom: 4 }

const mapEl = ref<HTMLElement | null>(null)
/** 全屏容器：全屏的是整块地图（含浮层按钮），而不是只把 canvas 拉大 */
const wrapEl = ref<HTMLElement | null>(null)
const isFullscreen = ref(false)

let map: L.Map | null = null
let zoneLayer: L.LayerGroup | null = null
let adminHandle: AdminBoundaryHandle | null = null
/** 空域 id → 地图图形，用于高亮与定位（圆形/多边形都支持 getBounds，便于"定位到该区"） */
const zoneLayers = new Map<number, L.Circle | L.Polygon>()

/** 按区域类型着色（与监控页 DroneMap 的 ZONE_STYLE 保持一致，含建筑障碍） */
const ZONE_STYLE: Record<string, L.PathOptions> = {
  NO_FLY: { color: '#ff4d4f', weight: 2, fillColor: '#ff4d4f', fillOpacity: 0.13 },
  RESTRICTED: { color: '#fa8c16', weight: 2, fillColor: '#fa8c16', fillOpacity: 0.1 },
  BUILDING: { color: '#722ed1', weight: 2, dashArray: '3 3', fillColor: '#722ed1', fillOpacity: 0.14 },
  CORRIDOR: { color: '#52c41a', weight: 2, dashArray: '6 4', fillColor: '#52c41a', fillOpacity: 0.06 },
  WARNING: { color: '#faad14', weight: 2, dashArray: '4 4', fillColor: '#faad14', fillOpacity: 0.06 },
}

function styleOf(zone: AirspaceZone): L.PathOptions {
  return ZONE_STYLE[zone.zoneType] ?? ZONE_STYLE.WARNING
}

function popupHtml(zone: AirspaceZone): string {
  const severity = zone.severity ? CONFLICT_SEVERITY_TEXT[zone.severity] ?? zone.severity : '—'
  return (
    `<b>${zone.name ?? zone.code}</b><br/>` +
    `类型：${ZONE_TYPE_TEXT[zone.zoneType] ?? zone.zoneType}<br/>` +
    `编码：${zone.code}<br/>` +
    `生效高度：${zone.minAltitudeM ?? 0} ~ ${zone.maxAltitudeM ?? 120} m<br/>` +
    `严重度：${severity}` +
    (zone.enabled === false ? '<br/>状态：已停用' : '')
  )
}

/** 重建空域图形；已停用的区用虚线弱化，提示"当前不参与路径规划" */
function rebuildZones(): void {
  if (!zoneLayer) return
  zoneLayer.clearLayers()
  zoneLayers.clear()

  for (const zone of props.zones ?? []) {
    const style = styleOf(zone)
    const disabled = zone.enabled === false
    const pathStyle: L.PathOptions = disabled
      ? { ...style, weight: 1, dashArray: '2 4', opacity: 0.45, fillOpacity: 0.04 }
      : style

    let path: L.Circle | L.Polygon | null = null
    if (zone.shapeType === 'CIRCLE' && zone.center) {
      path = L.circle([zone.center.lat, zone.center.lon], {
        ...pathStyle,
        radius: Number(zone.radiusM ?? 0),
      })
    } else if (zone.shapeType === 'POLYGON' && (zone.points?.length ?? 0) >= 3) {
      path = L.polygon(
        (zone.points ?? []).map((p) => [p.lat, p.lon] as [number, number]),
        pathStyle,
      )
    }
    if (!path) continue

    path.bindPopup(popupHtml(zone))
    path.bindTooltip(zone.name ?? zone.code, { sticky: true, direction: 'top', opacity: 0.92 })
    path.on('click', () => emit('select', zone))
    path.addTo(zoneLayer)
    zoneLayers.set(zone.id, path)
  }
  applyHighlight()
}

/** 高亮当前选中的区，其余淡化，让"表格选中的那条"在地图上立刻可辨 */
function applyHighlight(): void {
  const target = props.highlightId ?? null
  zoneLayers.forEach((path, id) => {
    const zone = (props.zones ?? []).find((z) => z.id === id)
    if (!zone) return
    const base = styleOf(zone)
    if (target === null) {
      path.setStyle(
        zone.enabled === false
          ? { ...base, weight: 1, dashArray: '2 4', opacity: 0.45, fillOpacity: 0.04 }
          : base,
      )
      path.bringToBack()
    } else if (id === target) {
      path.setStyle({ ...base, weight: 3.5, fillOpacity: Math.min(0.45, (base.fillOpacity ?? 0.1) * 2.6), opacity: 1 })
      path.bringToFront()
    } else {
      path.setStyle({ ...base, weight: 1, opacity: 0.32, fillOpacity: 0.03 })
    }
  })
}

function focusZone(id: number): void {
  const path = zoneLayers.get(id)
  if (!map || !path) return
  const bounds = path.getBounds()
  if (!bounds.isValid()) return
  map.flyToBounds(bounds.pad(0.35), { duration: 0.5, maxZoom: 13 })
}

function resetView(): void {
  fitCountry(true)
}

/**
 * 把整个中国恰好铺满容器。
 *
 * <p>以前写死 zoom 4：卡片只有 360px 高时，中国的包围盒约 797×438，南北会被上下各切掉一条，
 * 用户只能自己缩到 z2 才看全 —— 于是就成了"空域管理的地图放不大 / 图太小"。
 * 现在按容器实际尺寸自适应（配合 zoomSnap 0.25 支持 1/4 级缩放）。
 */
function fitCountry(animate = false): void {
  if (!map) return
  map.fitBounds(CHINA_BOUNDS, {
    padding: [8, 8],
    animate,
    duration: animate ? 0.5 : 0,
  })
}

/** 全屏 / 退出全屏：卡片里的 360px 地图看不清空域分布与区县边界 */
async function toggleFullscreen(): Promise<void> {
  if (!wrapEl.value) return
  if (document.fullscreenElement === wrapEl.value) {
    await document.exitFullscreen().catch(() => undefined)
    return
  }
  try {
    await wrapEl.value.requestFullscreen()
  } catch {
    message.warning('浏览器拒绝了全屏请求，请手动按 F11，或检查是否被浏览器策略拦截')
  }
}

function onFullscreenChange(): void {
  isFullscreen.value = document.fullscreenElement === wrapEl.value
  // 等浏览器完成布局再重算，否则量到的还是切换前的尺寸
  window.setTimeout(() => map?.invalidateSize(), 120)
}

let resizeObserver: ResizeObserver | null = null

onMounted(async () => {
  if (!mapEl.value) return
  map = L.map(mapEl.value, {
    center: CHINA_VIEW.center,
    zoom: CHINA_VIEW.zoom,
    zoomControl: true,
    attributionControl: false,
    // 卡片很扁（宽高比约 3.5:1）：整数级要么裁掉南北、要么浪费一半横向空间，这里允许 1/4 级
    zoomSnap: 0.25,
    minZoom: 2,
  })
  map.getContainer().style.background = '#07131f'

  void attachChinaBaseLayer(map)
  // 行政区图层保持可交互：空域总览时"点省面下钻看区县"是有用的参照动作
  adminHandle = await attachAdminBoundaries(map, { interactive: true })

  zoneLayer = L.layerGroup().addTo(map)
  rebuildZones()

  // 容器尺寸变化（窗口缩放 / 全屏切换）后必须让 Leaflet 重新测量，
  // 否则会沿用旧尺寸的瓦片与偏移，表现得像"地图卡住了、怎么放大都没反应"
  if (typeof ResizeObserver !== 'undefined' && mapEl.value) {
    resizeObserver = new ResizeObserver(() => map?.invalidateSize())
    resizeObserver.observe(mapEl.value)
  }
  document.addEventListener('fullscreenchange', onFullscreenChange)

  const bounds = L.latLngBounds([])
  zoneLayers.forEach((path) => bounds.extend(path.getBounds()))
  const span = bounds.isValid() ? bounds.getNorthWest().distanceTo(bounds.getSouthEast()) : 0
  // 空域集中在同一城市才整体 fit；散布全国时铺满全国视野，避免缩放到无法辨认
  if (bounds.isValid() && span <= 300_000) {
    map.fitBounds(bounds.pad(0.25), { maxZoom: 12 })
  } else {
    fitCountry()
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  resizeObserver?.disconnect()
  resizeObserver = null
  if (document.fullscreenElement === wrapEl.value) {
    void document.exitFullscreen().catch(() => undefined)
  }
  isFullscreen.value = false
  adminHandle?.destroy()
  adminHandle = null
  zoneLayers.clear()
  zoneLayer = null
  map?.remove()
  map = null
})

watch(() => props.zones, () => rebuildZones(), { deep: true })
watch(() => props.highlightId, () => applyHighlight())

defineExpose({
  focusZone: (id: number) => focusZone(id),
  resetView,
  toggleFullscreen,
})
</script>

<template>
  <div ref="wrapEl" class="overview">
    <div ref="mapEl" class="overview-canvas" :style="{ height }"></div>
    <button
      type="button"
      class="overview-fs"
      :title="isFullscreen ? '退出全屏' : '全屏查看地图'"
      @click="toggleFullscreen"
    >
      <FullscreenExitOutlined v-if="isFullscreen" />
      <FullscreenOutlined v-else />
    </button>
    <div class="overview-hint">
      点地图上的色块可选中该空域；点省面可下钻查看区县，确认"这块地在哪"后再划定
    </div>
  </div>
</template>

<style scoped>
.overview {
  position: relative;
  border-radius: 6px;
  overflow: hidden;
}

.overview-canvas {
  width: 100%;
}

/* 全屏入口：卡片高度有限，看不清空域分布与区县边界时一键铺满屏幕 */
.overview-fs {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 500;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: rgba(7, 20, 34, 0.9);
  box-shadow:
    0 0 0 1px rgba(34, 211, 238, 0.22),
    0 6px 16px rgba(0, 0, 0, 0.45);
  color: #8fd0f5;
  font-size: 15px;
  cursor: pointer;
}

.overview-fs:hover {
  background: rgba(34, 211, 238, 0.16);
  color: #7ff0ff;
}

/* 全屏时撑满屏幕：canvas 的 height 是行内样式，必须 !important 才能覆盖 */
.overview:fullscreen {
  border-radius: 0;
  background: #07131f;
}

.overview:fullscreen .overview-canvas {
  height: 100vh !important;
}

.overview:fullscreen .overview-hint {
  left: 16px;
  bottom: 16px;
}

.overview-hint {
  position: absolute;
  left: 8px;
  bottom: 8px;
  z-index: 500;
  padding: 4px 9px;
  background: rgba(7, 20, 34, 0.9);
  border-radius: 4px;
  box-shadow: 0 0 0 1px rgba(34, 211, 238, 0.18);
  font-size: 12px;
  color: rgba(190, 216, 240, 0.78);
  pointer-events: none;
}
</style>
