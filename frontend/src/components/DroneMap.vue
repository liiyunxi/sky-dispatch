<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import L from 'leaflet'
import { message } from 'ant-design-vue'
import { FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons-vue'
import type { AirspaceZone, ChargingStation, DroneFrame, DroneStatus, OrderPin, TaskStatus } from '@/api'
import {
  CONFLICT_SEVERITY_TEXT,
  DRONE_STATUS_HEX,
  DRONE_STATUS_TEXT,
  ORDER_STATUS_TEXT,
  TASK_STATUS_TEXT,
  ZONE_TYPE_TEXT,
  formatPercent,
} from '@/utils/format'
import { createSimTileLayer } from './map/SimTileLayer'
import { attachChinaBaseLayer, CHINA_BOUNDS } from './map/ChinaBaseLayer'
import {
  attachAdminBoundaries,
  type AdminBoundaryHandle,
  type AdminRegion,
  type ProvinceColorMode,
} from './map/AdminBoundaryLayer'
import { applyDroneFrame, createDroneDivIcon, headingConeLatLngs, lerpAngle } from './map/droneIcon'
import Scene3DView from './map/Scene3DView.vue'

/**
 * 无人机态势地图。
 *
 * 底图是**两级结构**，叠在一起用：
 *
 *  1. **中国地图**（`map/ChinaBaseLayer.ts`）：真实的省级边界 + 海面 + 经纬网 + 主要城市标注，
 *     数据来自 `public/geo/china.json`。这是"我在看中国"的方位锚点，
 *     也回答了"地图必须是中国的"这个需求。
 *  2. **真实城市路网**（`map/SimTileLayer.ts`）：程序化生成的**环线 + 放射主干道 + 蜿蜒水系 +
 *     不规则次干道/支路**，而不是规则经纬网格。放大到 z ≥ 9 起叠加 ——
 *     省级边界在城市尺度上太粗糙，纯中国地图会让作业区看起来是空白。
 *
 * 两者用同一套冷色，叠加时不会出现"两张地图拼在一起"的割裂感；
 * 层级上中国地图在自建 pane(z=180)，路网瓦片在 tilePane(200)，空域/航路在 overlayPane(400)，
 * 无人机在 markerPane(600)，自下而上互不遮挡。
 *
 * 另外三件事让"仿真感"成立：**四旋翼模型**（按航向偏航、旋翼转速随速度变化、状态变色、
 * 低电量脉冲、载货货箱、俯仰/横滚姿态）、**位置与高度插值**（rAF 指数平滑，
 * 飞机沿航线滑行并爬升/下降，而不是每秒瞬移）、**航迹尾迹与航向锥**（看清它从哪来、往哪去）。
 *
 * **两种视图**：平面（Leaflet）与立体（`map/Scene3DView.vue`，Three.js）。两者共用同一份
 * 插值位姿 —— 插值只在 {@link startAnimation} 里算一次，立体视图通过 `updateFrames()` 被动接收，
 * 所以切视图不会出现"位置对不上"。切到立体时按当前平面视野框定范围，用户在地图上看的哪片区域，
 * 立起来就是哪片区域（不会把全国四个城市硬塞进一个场景）。
 *
 * 性能边界：DOM + SVG 图标在几十架规模下流畅；超过 `SMOOTH_MOTION_LIMIT` 架
 * 会自动关闭插值动画（降为按帧更新一次），避免低端机器掉帧。
 * 立体视图另有一套 ~30fps 的渲染循环，切回平面即停，不白烧 GPU。
 */

const props = withDefaults(
  defineProps<{
    drones: DroneFrame[]
    zones: AirspaceZone[]
    routes: Array<{ taskId: number; label: string; status: TaskStatus; points: [number, number][] }>
    /** 订单取货点 / 送货目的地标记（待派 / 在飞 / 最近已送达） */
    orderPins?: OrderPin[]
    /** 充电站：长航程任务里飞机"飞一段、补一段"，站点的位置决定运力半径 */
    stations?: ChargingStation[]
    selectedDroneId?: number | null
    /** 首帧数据到达时是否自动定位到作业区（默认开，让用户一进来就看得到飞机） */
    autoFocus?: boolean
    height?: string
  }>(),
  { selectedDroneId: null, orderPins: () => [], stations: () => [], autoFocus: true, height: '520px' },
)

const emit = defineEmits<{ (e: 'select-drone', droneId: number): void }>()

const mapEl = ref<HTMLElement | null>(null)
/** 地图外层容器：全屏 API 作用在它身上（只让地图全屏，而不是整个页面） */
const mapWrapEl = ref<HTMLElement | null>(null)
/** 当前是否处于全屏（用于切换按钮文案与图标） */
const isFullscreen = ref(false)

// ---------------------------------------------------------------------------
// 视图切换：平面（Leaflet）↔ 立体（Three.js）
// ---------------------------------------------------------------------------

/** 当前视图模式 */
const viewMode = ref<'2D' | '3D'>('2D')
/** 立体场景句柄 */
const scene3dRef = ref<InstanceType<typeof Scene3DView> | null>(null)
/** 立体视图的相机预设 */
const cameraPreset = ref<'oblique' | 'top' | 'close' | 'follow'>('oblique')
/** 立体视图左上角显示的城市名（"这是哪一片空域"） */
const scene3dAreaLabel = ref('')

/**
 * 视图模式变化后的收尾。
 *
 * <p><b>为什么不用 {@code switchView(mode)} 里加"值没变就返回"的守卫</b>：
 * {@code v-model:value} 会先把 viewMode 改掉，再抛 change 事件 —— 等回调进来时
 * "值已经变了"，任何基于"新旧对比"的守卫都会直接短路，导致立体场景永远框不定视野。
 * 所以这里只按目标模式做事，不做对比。
 */
function onViewModeChange(mode: '2D' | '3D'): void {
  if (mode === '3D') {
    // 视野必须在平面地图还"看得见"的时候取：容器一旦 display:none，
    // Leaflet 量到的尺寸是 0，getBounds() 会退化成零面积，跨度只剩几百米
    const bounds = map?.getBounds()
    // 等 DOM 把 3D 容器显示出来并量到真实尺寸后再框定
    void nextTick().then(() => frameScene3D(bounds))
    return
  }
  // 切回平面：容器刚从 display:none 恢复，Leaflet 必须重新量尺寸，否则灰边 / 图层错位
  void nextTick(() => {
    map?.invalidateSize()
    refreshCone()
  })
}

/**
 * 立体场景能有效表达的跨度上限（米）。
 *
 * <p>3D 场景是按真实米制搭的：把全国视野（上千公里）直接立起来，180m 的楼只有纸片厚，
 * 26 架飞机散落在几百万米的画布上 —— 画面就是一块空地。视野超过这个值就改立"最密集的作业区"。
 * 15km 大致是"一座城的核心区"，再大楼群就小到看不清了。
 */
const SCENE3D_MAX_SPAN_M = 15_000
/** 收敛到单个作业区时的最小跨度（米）：太贴近会看不到周边空域与航线全貌 */
const SCENE3D_MIN_SPAN_M = 3_000

/**
 * 挑一个"最值得立起来看"的作业区：机队最密集的那一簇。
 *
 * <p>与低缩放下的"作业区"汇总标记用同一套 0.5° 网格聚类，所以用户看到的
 * 那个"上海 11 架"标记，就是他点开立体视图后会看到的那片区域，认知一致。
 *
 * @return 该簇的中心、跨度与城市名；没有带坐标的飞机时返回 null
 */
function busiestCluster(): { lat: number; lon: number; spanM: number; label: string } | null {
  interface Cluster {
    lat: number
    lon: number
    count: number
    minLat: number
    maxLat: number
    minLon: number
    maxLon: number
  }
  const clusters = new Map<string, Cluster>()
  for (const drone of props.drones) {
    if (drone.lat === undefined || drone.lon === undefined) continue
    const key = `${Math.round(drone.lat / 0.5)}:${Math.round(drone.lon / 0.5)}`
    const found = clusters.get(key)
    if (found) {
      found.lat += drone.lat
      found.lon += drone.lon
      found.count += 1
      found.minLat = Math.min(found.minLat, drone.lat)
      found.maxLat = Math.max(found.maxLat, drone.lat)
      found.minLon = Math.min(found.minLon, drone.lon)
      found.maxLon = Math.max(found.maxLon, drone.lon)
    } else {
      clusters.set(key, {
        lat: drone.lat,
        lon: drone.lon,
        count: 1,
        minLat: drone.lat,
        maxLat: drone.lat,
        minLon: drone.lon,
        maxLon: drone.lon,
      })
    }
  }
  let best: Cluster | null = null
  clusters.forEach((cluster) => {
    if (!best || cluster.count > best.count) best = cluster
  })
  if (!best) return null

  const picked: Cluster = best
  const center = { lat: picked.lat / picked.count, lon: picked.lon / picked.count }
  const cornerA = L.latLng(picked.minLat, picked.minLon)
  const cornerB = L.latLng(picked.maxLat, picked.maxLon)
  // 机队只占城市里一小块时（都停在机巢附近）给一个最小跨度，否则场景会窄得像放大镜；
  // 上限与立体场景自身的天花板一致，避免算出一个立不起来的跨度
  const spanM = Math.min(
    SCENE3D_MAX_SPAN_M,
    Math.max(SCENE3D_MIN_SPAN_M, cornerA.distanceTo(cornerB) * 1.8),
  )
  return { lat: center.lat, lon: center.lon, spanM, label: cityNameOf(center.lat, center.lon) ?? '作业区' }
}

/**
 * 按给定视野框定立体场景。
 *
 * <p>两种情形：
 * <ul>
 *   <li><b>已经放大到城市尺度</b>（跨度 ≤ {@link SCENE3D_MAX_SPAN_M}）：原样立起来 ——
 *       用户在平面地图上看的是哪片区域，立起来就是哪片区域；</li>
 *   <li><b>还在全国视野</b>：收敛到机队最密集的作业区（{@link busiestCluster}）。
 *       全国跨度立起来只会是一块空地，楼和飞机全都看不见。</li>
 * </ul>
 *
 * @param bounds 切换前从平面地图取到的可见范围
 */
function frameScene3D(bounds?: L.LatLngBounds | null): void {
  const scene = scene3dRef.value
  if (!scene) return

  if (bounds && bounds.isValid()) {
    const spanM = bounds.getNorthWest().distanceTo(bounds.getSouthEast())
    if (spanM <= SCENE3D_MAX_SPAN_M) {
      const center = bounds.getCenter()
      scene3dAreaLabel.value = cityNameOf(center.lat, center.lng) ?? '当前视野'
      scene.frameArea(center.lat, center.lng, spanM)
      scene.setPreset(cameraPreset.value)
      return
    }
  }

  // 全国视野 / 视野不可用：收敛到机队最密集的作业区
  const cluster = busiestCluster()
  if (cluster) {
    scene3dAreaLabel.value = cluster.label
    scene.frameArea(cluster.lat, cluster.lon, cluster.spanM)
    scene.setPreset(cameraPreset.value)
    return
  }

  scene3dAreaLabel.value = '上海'
  scene.frameArea(31.2304, 121.4737, 6_000)
  scene.setPreset(cameraPreset.value)
}

/** 切换立体视图的相机预设（斜视 / 俯视 / 贴近 / 跟随） */
function changeCameraPreset(kind: 'oblique' | 'top' | 'close' | 'follow'): void {
  cameraPreset.value = kind
  scene3dRef.value?.setPreset(kind)
}

/** 立体视图里的"定位机队"：把相机收拢到当前场景内的机队 */
function focusFleet3D(): void {
  scene3dRef.value?.focusDrones()
}

/**
 * 立体视图的平移按钮：把视角挪到相邻区域。
 *
 * <p>为什么用按钮而不是只靠右键拖拽：拖拽是"隐藏手势"，用户根本不知道它能拖，
 * 于是整片立体视图看起来就是"死的、只能看一小片"。四个方向键把能力摆到明面上。
 */
function pan3D(direction: 'north' | 'south' | 'east' | 'west'): void {
  const step = PAN_STEP
  const moves: Record<'north' | 'south' | 'east' | 'west', [number, number]> = {
    // 数组是 (xRatio, zRatio)：北为 -z、东为 +x，与投影方向一致
    north: [0, -step],
    south: [0, step],
    east: [step, 0],
    west: [-step, 0],
  }
  const [dx, dz] = moves[direction]
  scene3dRef.value?.panBy(dx, dz)
}

/** 平移一步占视野跨度的比例（与 Scene3DView 的 PAN_STEP_RATIO 对齐：一次点击换一整屏） */
const PAN_STEP = 0.9

/** 立体视图的视野范围增减：看更广 / 看更细 */
function zoom3D(factor: number): void {
  scene3dRef.value?.zoomSpan(factor)
}

let map: L.Map | null = null
let zoneLayer: L.LayerGroup | null = null
let stationLayer: L.LayerGroup | null = null
let routeLayer: L.LayerGroup | null = null
let droneLayer: L.LayerGroup | null = null
let trailLayer: L.LayerGroup | null = null
let coneLayer: L.LayerGroup | null = null

/** droneId → Leaflet 标记 */
const droneMarkers = new Map<number, L.Marker>()
/** taskId → 航路折线 */
const routeLines = new Map<number, L.Polyline>()
/** droneId → 航迹折线 */
const trailLines = new Map<number, L.Polyline>()

/** 平滑插值状态：lat/lon/altitude 是"显示值"，target* 是"服务端最新值"；roll/pitch 是姿态解算结果 */
interface MotionState {
  lat: number
  lon: number
  altitude: number
  heading: number
  roll: number
  pitch: number
  rotorLevel: number
  targetLat: number
  targetLon: number
  targetAltitude: number
  targetHeading: number
  frame: DroneFrame
}
const motion = new Map<number, MotionState>()
/** droneId → 航迹点序列 */
const trails = new Map<number, Array<[number, number]>>()

let rafId: number | null = null
let lastFrameTs = 0

/** 超过这个数量就关闭插值动画，改为按帧直接更新（保帧率） */
const SMOOTH_MOTION_LIMIT = 80
/** 位置插值时间常数（秒）：越小越"贴"服务端位置，越容易看出跳变 */
const SMOOTH_TAU = 0.32
/** 姿态解算时间常数（秒）：比位置更"迟钝"一点，转弯与爬升的姿态才不会抖 */
const ATTITUDE_TAU = 0.5
/** 横滚上限（度）：真实无人机协调转弯通常不超过 30°，这里取制图可读的上限 */
const MAX_BANK_DEG = 30
/** 俯仰上限（度）：爬升/下降时的最大前倾角 */
const MAX_PITCH_DEG = 26
/** 旋翼转速的参考巡航速度（米/秒）：达到它就视为"全速转动" */
const ROTOR_REF_SPEED_MPS = 14
/** 航迹点最小间距（米）与最大点数 */
const TRAIL_MIN_GAP_M = 25
const TRAIL_MAX_POINTS = 90

/** 数值夹取（姿态与转速的公共工具） */
function clamp(value: number, min: number, max: number): number {
  if (value < min) return min
  return value > max ? max : value
}

function radToDeg(rad: number): number {
  return (rad * 180) / Math.PI
}

const cursorText = ref('')
const zoomLevel = ref(11)
/** 中国地图数据是否加载成功（失败时 HUD 会给出手动下载提示） */
const chinaMapReady = ref(true)

// ---- 行政区划：省级分色 + 省/市/区县下钻 ----
let adminHandle: AdminBoundaryHandle | null = null
const provinceOptions = ref<Array<{ value: number; label: string }>>([])
const colorMode = ref<ProvinceColorMode>('uniform')
/** 下拉选中的省 adcode（city 视图时仍显示所属省）；null = 全国 */
const selectedProvinceAdcode = ref<number | null>(null)
const adminRegion = ref<AdminRegion | null>(null)
const adminLoading = ref(false)
/** 行政区划的提示语：自动下钻拉不到下级边界时的原因（null = 无提示） */
const adminNotice = ref<string | null>(null)
/** 是否常驻显示行政区名称（省/市/区县） */
const showAdminLabels = ref(true)

/** 订单标记图层（取货站点 + 送货目的地） */
let orderLayer: L.LayerGroup | null = null

/** 首次拿到机队位置时自动定位作业区；用户手动拖拽后不再抢视角 */
let autoFocusDone = false
let userDraggedMap = false
/** 挂载后若数据尚未到达，用短轮询等待首帧位置（避免 watch 首帧时序竞争导致停在全国视角） */
let focusTimer: number | null = null
const FOCUS_MAX_TRIES = 20

/** 有坐标的无人机位置集合 */
function fleetPoints(): L.LatLng[] {
  return props.drones
    .filter((d) => d.lat !== undefined && d.lon !== undefined)
    .map((d) => L.latLng(d.lat as number, d.lon as number))
}

/** 机队位置的外接矩形；无坐标时返回 null */
function fleetBounds(): L.LatLngBounds | null {
  const points = fleetPoints()
  if (points.length === 0) return null
  return L.latLngBounds(points)
}

/** 兜底中心（没有任何机队坐标时用） */
const FALLBACK_CENTER: [number, number] = [31.2304, 121.4737]
/** 自动定位的缩放上限：机队集中在一个城市时放大到城市级，跨城市时自动收到全国 */
const AUTO_FOCUS_ZOOM = 11
/**
 * 判定"机队分散在多城"的跨度阈值（米）。
 *
 * <p>演示机队分布在京沪深蓉四城，全体包围盒横跨 1500km，fitBounds 只会落回全国视野（z5）：
 * 画面几乎没有变化，也永远到不了触发市/区县下钻的 z6，于是结论变成"地图没有市区县"。
 * 跨过这个阈值时改为收敛到最密集的作业区（见 {@link busiestCluster}）。
 */
const FLEET_MULTI_CITY_SPAN_M = 150_000

/**
 * 自动定位到作业区（无动画瞬间到位，避免标签页节流打断飞行动画）。
 *
 * <p>用 {@code fitBounds} 自适应，而不是写死缩放级别：机队都在一个城市时跨度小，
 * 会被放到城市级（z11）；机队分布在<b>全国多个城市</b>时跨度大，fitBounds 会自动
 * 收到全国视野。早期固定 z11 的写法在多城市演示下会停在一片空白，看不到任何飞机。
 */
function focusOperationArea(): void {
  if (!map) return
  const bounds = fleetBounds()
  if (bounds) {
    map.fitBounds(bounds.pad(0.25), { maxZoom: AUTO_FOCUS_ZOOM, animate: false })
  } else {
    map.setView(FALLBACK_CENTER, 12, { animate: false })
  }
  zoomLevel.value = map.getZoom()
  syncBaseLayerVisibility()
  syncOperationMarker()
}

/** 尝试一次自动定位；拿到位置并完成后返回 true（调用方据此停止轮询） */
function tryAutoFocus(): boolean {
  if (autoFocusDone || userDraggedMap || props.autoFocus === false) return true
  if (!props.drones.some((f) => f.lat !== undefined && f.lon !== undefined)) return false
  autoFocusDone = true
  focusOperationArea()
  return true
}

function stopFocusTimer(): void {
  if (focusTimer !== null) {
    window.clearInterval(focusTimer)
    focusTimer = null
  }
}

/** 真实城市路网图层：z ≥ STREET_LAYER_MIN_ZOOM 才叠加（省级边界在城市尺度上太粗糙） */
let streetLayer: L.GridLayer | null = null
/** 作业区汇总标记容器 */
let operationMarkerGroup: L.LayerGroup | null = null

/** 中国全览视野：默认就展示中国地图，而不是一上来只有上海市区 */
const CHINA_OVERVIEW = { center: L.latLng(34.5, 106.5), zoom: 4 }

// ---------------------------------------------------------------------------
// 初始化
// ---------------------------------------------------------------------------

function initMap() {
  if (!mapEl.value || map) return

  /*
   * 用局部常量持有地图实例，再赋给外层的 map 变量。
   * 原因：`map` 是可重新赋值的模块级变量（null | L.Map），
   * 在事件回调里 TS 无法保证它仍然非空，会报 "possibly null"；
   * 局部 const 的类型是确定的，回调里用起来干净。
   */
  const instance = L.map(mapEl.value, {
    center: CHINA_OVERVIEW.center,
    zoom: CHINA_OVERVIEW.zoom,
    // 允许缩到能看到整个中国，也能放大到街区
    minZoom: 3,
    maxZoom: 17,
    // 拖动范围限制在中国境内（含南海诸岛），避免用户漂到空白海域
    maxBounds: CHINA_BOUNDS.pad(0.1),
    maxBoundsViscosity: 0.75,
    zoomControl: false, // 下面单独放到右上角：左上角留给 HUD 读数，避免两者叠在一起
    preferCanvas: true, // 空域/航路/尾迹走 Canvas，SVG 只留给无人机图标
  })
  map = instance

  // 缩放按钮放右上角（左上角是 HUD、右下角是图例）
  L.control.zoom({ position: 'topright' }).addTo(instance)

  // 真实城市路网层先建好，按缩放级别动态挂载/卸载（省渲染）
  streetLayer = createSimTileLayer({ drawBackground: false })

  // 背景层（海面 / 经纬网 / 城市标注）先铺，省级行政区单独挂载以支持分色与下钻
  void attachChinaBaseLayer(instance)
  void attachAdminBoundaries(instance, {
    onSelectChange: (region) => {
      adminRegion.value = region
      // city 视图时下拉保持显示所属省；返回全国才清空
      if (region?.level === 'province') selectedProvinceAdcode.value = region.adcode
      else if (!region) selectedProvinceAdcode.value = null
    },
    // 自动下钻拉不到下级边界时，把原因写在面板上：
    // "放大后什么都没发生"和"这里确实没有更细的数据"必须能区分开
    onNotice: (text) => {
      adminNotice.value = text
    },
  }).then((handle) => {
    adminHandle = handle
    chinaMapReady.value = handle.ready
    provinceOptions.value = handle.provinces.map((p) => ({ value: p.adcode, label: p.name }))
    // 图层就绪后再按当前开关同步标签可见性（挂载瞬间的默认值也要生效）
    handle.setLabelsVisible(showAdminLabels.value)
    // 省级数据缺失时，用带底色的程序化瓦片兜底，避免陆地一片空白
    if (!handle.ready && streetLayer && !instance.hasLayer(streetLayer)) {
      streetLayer = createSimTileLayer({ drawBackground: true })
      streetLayer.addTo(instance)
    }
    syncBaseLayerVisibility()
  })

  // 比例尺：仿真平台没有比例尺就没有"距离感"
  L.control.scale({ imperial: false, metric: true, position: 'bottomleft' }).addTo(instance)

  trailLayer = L.layerGroup().addTo(instance)
  zoneLayer = L.layerGroup().addTo(instance)
  // 充电站垫在空域之下：站点是"底图级的运营资源"，不该盖住禁飞区/楼群这些风险信息
  stationLayer = L.layerGroup().addTo(instance)
  routeLayer = L.layerGroup().addTo(instance)
  coneLayer = L.layerGroup().addTo(instance)
  orderLayer = L.layerGroup().addTo(instance)
  droneLayer = L.layerGroup().addTo(instance)

  zoomLevel.value = instance.getZoom()
  instance.on('zoomend', () => {
    zoomLevel.value = instance.getZoom()
    syncBaseLayerVisibility()
    syncOperationMarker()
  })
  // 用户一旦手动拖过地图，首帧自动定位就不再抢视角，并停止等待轮询
  instance.on('dragstart', () => {
    userDraggedMap = true
    stopFocusTimer()
  })
  instance.on('mousemove', (event: L.LeafletMouseEvent) => {
    // 经纬度实时读数：让"这是一个地理仿真沙盘"变得可验证
    cursorText.value = `${event.latlng.lat.toFixed(5)}, ${event.latlng.lng.toFixed(5)}`
  })
  instance.on('mouseout', () => {
    cursorText.value = ''
  })

  rebuildZones()
  rebuildStations()
  updateRoutes()
  syncDrones()
  syncBaseLayerVisibility()
  startAnimation()

  // 立刻试一次（数据可能已随首帧到达），否则每 500ms 重试，最多 10 秒
  if (!tryAutoFocus()) {
    let tries = 0
    focusTimer = window.setInterval(() => {
      tries += 1
      if (tryAutoFocus() || tries >= FOCUS_MAX_TRIES) stopFocusTimer()
    }, 500)
  }
}

/**
 * 按缩放级别切换「真实城市路网」图层。
 *
 * z ≥ 9 就叠加：这一级虽然画不出支路，但**环线 + 放射主干道骨架**正是
 * "一眼看出这是一座真实城市"的关键；再往上逐级加密
 * （z≥11 次干道、z≥13 支路与街区）。低于 9（省级视角）时收起，
 * 只留中国地图的省级边界，避免糊成一团。
 */
const STREET_LAYER_MIN_ZOOM = 9

function syncBaseLayerVisibility() {
  if (!map || !streetLayer) return
  const zoom = map.getZoom()
  const shouldShow = zoom >= STREET_LAYER_MIN_ZOOM || !chinaMapReady.value
  if (shouldShow && !map.hasLayer(streetLayer)) {
    streetLayer.addTo(map)
  } else if (!shouldShow && map.hasLayer(streetLayer)) {
    map.removeLayer(streetLayer)
  }
}

/** 缩放到中国全览 */
function zoomToChina() {
  map?.flyTo(CHINA_OVERVIEW.center, CHINA_OVERVIEW.zoom, { duration: 0.6 })
}

/**
 * 全屏切换。
 *
 * <p>Leaflet 没有内置全屏控件，这里用浏览器 Fullscreen API 作用在<b>地图容器</b>上
 * （而不是整页），这样只放大地图本身，页面其它区域维持原样。退出方式：按钮 /
 * ESC / 浏览器自带的退出全屏。全屏切换后容器尺寸变了，必须 invalidateSize，
 * 否则 Leaflet 会以为还是旧尺寸、出现灰边或图层错位。
 */
async function toggleFullscreen(): Promise<void> {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else if (mapWrapEl.value) {
      await mapWrapEl.value.requestFullscreen()
    }
  } catch {
    message.warning('浏览器拒绝了全屏请求，请手动按 F11，或检查是否被浏览器策略拦截')
  }
}

/** 全屏状态变化：同步按钮文案并让 Leaflet 重新测量容器尺寸 */
function onFullscreenChange(): void {
  isFullscreen.value = document.fullscreenElement === mapWrapEl.value
  // 等浏览器完成布局再 invalidateSize，否则量到的还是旧尺寸
  window.setTimeout(() => map?.invalidateSize(), 120)
}

/**
 * 缩放到机队所在区域。
 *
 * <p>机队集中在同一城市时按包围盒自适应；<b>跨城市</b>时改为收敛到最密集的作业区，
 * 而不是把四个城市一起框进来 —— 后者等价于"什么都没发生"（停在 z5 全国视野），
 * 既看不到兵种分布，也永远触发不了市/区县下钻。
 */
function zoomToFleet() {
  if (!map) return
  const bounds = fleetBounds()
  if (!bounds) {
    map.flyTo(FALLBACK_CENTER, 12, { duration: 0.6 })
    return
  }
  const spanM = bounds.getNorthWest().distanceTo(bounds.getSouthEast())
  if (spanM > FLEET_MULTI_CITY_SPAN_M) {
    const cluster = busiestCluster()
    if (cluster) {
      map.flyTo([cluster.lat, cluster.lon], AUTO_FOCUS_ZOOM, { duration: 0.7 })
      message.info(`机队分布在多个城市，已定位到最密集的${cluster.label}作业区（点「全国」看全局）`)
      return
    }
  }
  map.flyToBounds(bounds.pad(0.3), { maxZoom: 14, duration: 0.6 })
}

/** 演示城市参照点：给低缩放下的作业区标记一个可读的城市名 */
const DEMO_CITIES: Array<{ name: string; lat: number; lon: number }> = [
  { name: '上海', lat: 31.2304, lon: 121.4737 },
  { name: '北京', lat: 39.9087, lon: 116.4074 },
  { name: '深圳', lat: 22.5410, lon: 114.0579 },
  { name: '成都', lat: 30.6570, lon: 104.0657 },
]
/** 城市名的匹配半径（米）：超出则认为不属于任何已知城市，只显示"作业区" */
const CITY_MATCH_RADIUS_M = 120_000

function cityNameOf(lat: number, lon: number): string | null {
  let best: string | null = null
  let bestDistance = Number.POSITIVE_INFINITY
  for (const city of DEMO_CITIES) {
    const distance = L.latLng(lat, lon).distanceTo(L.latLng(city.lat, city.lon))
    if (distance < bestDistance) {
      bestDistance = distance
      best = city.name
    }
  }
  return bestDistance <= CITY_MATCH_RADIUS_M ? best : null
}

/**
 * 低缩放下的"作业区"汇总标记（按城市分组，每个城市一个）。
 *
 * <p>为什么需要它：全国视野下，一个城市的机队只占几个像素，如果没有提示，
 * 用户会以为"地图上没有飞机"。早期只画上海一个标记，机队扩到四个城市后就不够用了，
 * 这里改成按 0.5° 网格自动聚类，每个城市各出一个标记，点击即缩放到该城市机队。
 */
function syncOperationMarker() {
  if (!map) return
  if (!operationMarkerGroup) {
    operationMarkerGroup = L.layerGroup().addTo(map)
  }
  // 取本地引用：下面在 forEach 回调里要用，TS 无法跨回调保持非空收窄
  const group = operationMarkerGroup
  const zoom = map.getZoom()

  // 放大到城市尺度后，飞机图标本身已经看得清，汇总标记反而碍事
  if (zoom >= 9) {
    group.clearLayers()
    return
  }

  // 按 0.5°（≈55km）网格聚类：同一城市的飞机落进同一个格子
  const clusters = new Map<string, { lat: number; lon: number; total: number; airborne: number }>()
  for (const drone of props.drones) {
    if (drone.lat === undefined || drone.lon === undefined) continue
    const key = `${Math.round(drone.lat / 0.5)}:${Math.round(drone.lon / 0.5)}`
    const cluster = clusters.get(key) ?? { lat: 0, lon: 0, total: 0, airborne: 0 }
    cluster.lat += drone.lat
    cluster.lon += drone.lon
    cluster.total += 1
    if (drone.status !== 'IDLE' && drone.status !== 'OFFLINE') {
      cluster.airborne += 1
    }
    clusters.set(key, cluster)
  }

  group.clearLayers()
  clusters.forEach((cluster) => {
    const lat = cluster.lat / cluster.total
    const lon = cluster.lon / cluster.total
    const city = cityNameOf(lat, lon)
    const html = `
    <div class="operation-badge">
      <span class="operation-dot"></span>
      作业区${city ? ` · ${city}` : ''}
      <span class="operation-count">${cluster.total} 架 / 在飞 ${cluster.airborne}</span>
      <span class="operation-hint">点击放大</span>
    </div>`
    L.marker([lat, lon], {
      icon: L.divIcon({ html, className: 'operation-badge-host', iconSize: [0, 0], iconAnchor: [-8, 10] }),
      zIndexOffset: -50,
      keyboard: false,
    })
      // 点击直接缩放到该城市的机队，而不是全国机队，省一次手动缩放
      .on('click', () => {
        const points = props.drones
          .filter(
            (d) =>
              d.lat !== undefined &&
              d.lon !== undefined &&
              Math.round((d.lat as number) / 0.5) === Math.round(lat / 0.5) &&
              Math.round((d.lon as number) / 0.5) === Math.round(lon / 0.5),
          )
          .map((d) => L.latLng(d.lat as number, d.lon as number))
        if (points.length > 0) {
          map?.flyToBounds(L.latLngBounds(points).pad(0.35), { maxZoom: 13, duration: 0.6 })
        }
      })
      .addTo(group)
  })
}

// ---------------------------------------------------------------------------
// 行政区划：分色模式、省份选择与返回
// ---------------------------------------------------------------------------

// 分色模式切换即时同步到行政区图层（单色 / 分省着色）
watch(colorMode, (mode) => adminHandle?.setColorMode(mode))
// 名称标签开关同步
watch(showAdminLabels, (visible) => adminHandle?.setLabelsVisible(visible))

/** a-select 清空时值为 undefined，统一转成 null（返回全国） */
function onProvincePickFromSelect(adcode?: number): Promise<void> {
  return onProvincePick(typeof adcode === 'number' ? adcode : null)
}

async function onProvincePick(adcode: number | null): Promise<void> {
  if (!adminHandle) return
  adminLoading.value = true
  try {
    await adminHandle.selectProvince(adcode)
  } catch {
    // 港澳台等暂无下级边界的区域：省本身仍会高亮，这里提示数据边界即可
    message.info('该区域暂无在线的市/县边界数据（数据源：阿里云 DataV.GeoAtlas）')
  } finally {
    adminLoading.value = false
  }
}

async function onAdminBack(): Promise<void> {
  adminLoading.value = true
  try {
    await adminHandle?.goBack()
  } finally {
    adminLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 订单地理标记：取货站点（聚合）+ 送货目的地（按状态着色）
// ---------------------------------------------------------------------------

const ACTIVE_ORDER_STATUS = new Set(['ASSIGNED', 'IN_FLIGHT'])
type PinVisual = 'pending' | 'active' | 'delivered'

function orderVisual(status: string): PinVisual {
  if (status === 'DELIVERED') return 'delivered'
  if (ACTIVE_ORDER_STATUS.has(status)) return 'active'
  return 'pending'
}

function buildStationIcon(count: number): L.DivIcon {
  return L.divIcon({
    className: 'order-pin-host',
    iconSize: [26, 26],
    iconAnchor: [13, 13],
    html: `<div class="order-pin station"><span>取</span><i>${count}</i></div>`,
  })
}

function buildDropoffIcon(visual: PinVisual): L.DivIcon {
  const text = visual === 'delivered' ? '达' : '送'
  return L.divIcon({
    className: 'order-pin-host',
    iconSize: [24, 30],
    iconAnchor: [12, 26],
    html: `<div class="order-pin drop drop--${visual}"><span class="teardrop"></span><b>${text}</b></div>`,
  })
}

function rebuildOrderPins(): void {
  if (!orderLayer) return
  orderLayer.clearLayers()
  const pins = props.orderPins ?? []

  // 同一取货坐标的订单聚合为一个站点标记，避免同站点多单叠成一团
  const stations = new Map<string, { lat: number; lon: number; count: number }>()
  for (const pin of pins) {
    const key = `${pin.pickupLat.toFixed(3)},${pin.pickupLon.toFixed(3)}`
    const station = stations.get(key) ?? { lat: pin.pickupLat, lon: pin.pickupLon, count: 0 }
    station.count += 1
    stations.set(key, station)
  }
  stations.forEach((station) => {
    L.marker([station.lat, station.lon], {
      icon: buildStationIcon(station.count),
      zIndexOffset: -300,
      keyboard: false,
    })
      .bindPopup(`<b>取货起降点</b><br/>关联订单 ${station.count} 单`)
      .addTo(orderLayer!)
  })

  // 每个订单一个目的地：待派(橙) / 在飞(红脉冲) / 已送达(绿)，直观看出"飞到哪、到没到"
  for (const pin of pins) {
    const visual = orderVisual(pin.status)
    const statusText = ORDER_STATUS_TEXT[pin.status as keyof typeof ORDER_STATUS_TEXT] ?? pin.status
    L.marker([pin.dropoffLat, pin.dropoffLon], {
      icon: buildDropoffIcon(visual),
      zIndexOffset: visual === 'active' ? -100 : -200,
      keyboard: false,
    })
      .bindPopup(
        `<b>目的地 · ${pin.orderNo}</b><br/>` +
          `重量：${Number(pin.weightKg).toFixed(2)} kg<br/>` +
          `状态：${statusText}` +
          (pin.assignedDroneId ? `<br/>执行无人机：#${pin.assignedDroneId}` : ''),
      )
      .addTo(orderLayer)
  }
}

// ---------------------------------------------------------------------------
// 空域层
// ---------------------------------------------------------------------------

const ZONE_STYLE: Record<string, L.PathOptions> = {
  NO_FLY: { color: '#ff4d4f', weight: 2, fillColor: '#ff4d4f', fillOpacity: 0.13 },
  RESTRICTED: { color: '#fa8c16', weight: 2, fillColor: '#fa8c16', fillOpacity: 0.1 },
  // 建筑障碍（高楼）：紫色虚线，与"禁飞/限飞"的红橙拉开，一眼看出它是可绕可越的实体障碍
  BUILDING: { color: '#722ed1', weight: 2, dashArray: '3 3', fillColor: '#722ed1', fillOpacity: 0.14 },
  CORRIDOR: { color: '#52c41a', weight: 2, dashArray: '6 4', fillColor: '#52c41a', fillOpacity: 0.06 },
  WARNING: { color: '#faad14', weight: 2, dashArray: '4 4', fillColor: '#faad14', fillOpacity: 0.06 },
}

// ---------------------------------------------------------------------------
// 充电站层
// ---------------------------------------------------------------------------

/** 充电站图标：圆形底盘 + 闪电符号，与无人机/订单标记不会混淆 */
function buildChargingIcon(): L.DivIcon {
  return L.divIcon({
    className: 'station-icon-host',
    iconSize: [20, 20],
    iconAnchor: [10, 10],
    html: '<div class="station-pin"><span>⚡</span></div>',
  })
}

/**
 * 画充电站。
 *
 * <p>为什么要在地图上常驻：站点的位置直接决定"运力半径" ——
 * 长航程订单能不能接，取决于中途有没有站可落。运营看板上看不到站，
 * 就无法理解"这单为什么被派给了那架机"。
 */
function rebuildStations(): void {
  if (!stationLayer) return
  stationLayer.clearLayers()
  for (const station of props.stations ?? []) {
    if (station.lat === undefined || station.lon === undefined) continue
    L.marker([station.lat, station.lon], {
      icon: buildChargingIcon(),
      zIndexOffset: -150,
      keyboard: false,
    })
      .bindPopup(
        `<b>${station.name}</b><br/>` +
          `编码：${station.code}<br/>` +
          `城市：${station.city}<br/>` +
          `桩位：${station.chargeSlots} 个` +
          (station.remark ? `<br/>备注：${station.remark}` : ''),
      )
      .addTo(stationLayer)
  }
}

function rebuildZones() {
  if (!zoneLayer) return
  zoneLayer.clearLayers()
  props.zones.forEach((zone) => {
    const style = ZONE_STYLE[zone.zoneType] ?? ZONE_STYLE.WARNING
    const popupHtml = `
      <b>${zone.name}</b><br/>
      类型：${ZONE_TYPE_TEXT[zone.zoneType] ?? zone.zoneType}<br/>
      编码：${zone.code}<br/>
      生效高度：${zone.minAltitudeM ?? 0} ~ ${zone.maxAltitudeM ?? 120} m<br/>
      严重度：${zone.severity ? (CONFLICT_SEVERITY_TEXT[zone.severity] ?? zone.severity) : '—'}
      ${zone.remark ? `<br/>备注：${zone.remark}` : ''}
    `

    if (zone.shapeType === 'CIRCLE' && zone.center) {
      L.circle([zone.center.lat, zone.center.lon], { ...style, radius: Number(zone.radiusM ?? 0) })
        .bindPopup(popupHtml)
        .addTo(zoneLayer!)
      return
    }
    if (zone.shapeType === 'POLYGON' && zone.points && zone.points.length >= 3) {
      L.polygon(zone.points.map((p) => [p.lat, p.lon] as [number, number]), style)
        .bindPopup(popupHtml)
        .addTo(zoneLayer!)
    }
  })
}

// ---------------------------------------------------------------------------
// 航路层
// ---------------------------------------------------------------------------

const ROUTE_COLOR: Partial<Record<TaskStatus, string>> = {
  ASSIGNED: '#1677ff',
  TAKEOFF: '#13c2c2',
  CRUISING: '#1677ff',
  DELIVERING: '#722ed1',
  RETURNING: '#fa8c16',
}

function updateRoutes() {
  if (!routeLayer) return
  const validIds = new Set<number>()

  props.routes.forEach((route) => {
    validIds.add(route.taskId)
    const color = ROUTE_COLOR[route.status] ?? '#1677ff'
    const latlngs = route.points.map((p) => L.latLng(p[0], p[1]))
    const existing = routeLines.get(route.taskId)

    if (existing) {
      existing.setStyle({ color })
      existing.setLatLngs(latlngs)
    } else {
      const line = L.polyline(latlngs, {
        color,
        weight: 2.4,
        opacity: 0.8,
        // 后端航线已做转弯圆角化，这里再用 round 线帽/线接头把折点抹平，
        // 于是地图上看到的是一条"飞出来的弧线"而不是折线。
        lineJoin: 'round',
        lineCap: 'round',
        // 不做抽稀：抽稀会把刚圆出来的弧重新切成折角
        smoothFactor: 0.5,
        dashArray: route.status === 'RETURNING' ? '5 5' : undefined,
      })
        .bindPopup(`<b>${route.label}</b><br/>状态：${TASK_STATUS_TEXT[route.status] ?? route.status}`)
        .addTo(routeLayer!)
      routeLines.set(route.taskId, line)
    }
  })

  routeLines.forEach((line, taskId) => {
    if (!validIds.has(taskId)) {
      routeLayer!.removeLayer(line)
      routeLines.delete(taskId)
    }
  })
}

// ---------------------------------------------------------------------------
// 无人机层：增量同步（新增 / 更新 / 删除）
// ---------------------------------------------------------------------------

function buildPopup(frame: DroneFrame): string {
  return `
    <b>${frame.droneCode}</b><br/>
    状态：${DRONE_STATUS_TEXT[frame.status] ?? frame.status}<br/>
    电量：${formatPercent(frame.batteryPct ?? undefined)}<br/>
    高度：${(frame.altitudeM ?? 0).toFixed(0)} m &nbsp; 速度：${(frame.speedMps ?? 0).toFixed(1)} m/s<br/>
    航向：${(frame.headingDeg ?? 0).toFixed(0)}°
    ${frame.orderNo ? `<br/>订单：${frame.orderNo}` : ''}
    ${frame.progressPct !== undefined ? `<br/>进度：${frame.progressPct.toFixed(0)}%` : ''}
    ${frame.taskId ? `<br/>任务 ID：${frame.taskId}` : ''}
  `
}

function syncDrones() {
  if (!droneLayer) return
  const validIds = new Set<number>()

  props.drones.forEach((frame) => {
    if (frame.lat === undefined || frame.lon === undefined) return
    validIds.add(frame.droneId)

    const existingState = motion.get(frame.droneId)
    if (existingState) {
      // 已有：只更新"目标值"，显示位置/高度交给动画循环插值过去
      existingState.targetLat = frame.lat
      existingState.targetLon = frame.lon
      existingState.targetAltitude = frame.altitudeM ?? 0
      existingState.targetHeading = frame.headingDeg ?? existingState.targetHeading
      existingState.frame = frame
    } else {
      // 新增：显示值直接落在目标上（首帧不该从 (0,0) 飞过来）
      const altitude = frame.altitudeM ?? 0
      motion.set(frame.droneId, {
        lat: frame.lat,
        lon: frame.lon,
        altitude,
        heading: frame.headingDeg ?? 0,
        roll: 0,
        pitch: 0,
        rotorLevel: 0.15,
        targetLat: frame.lat,
        targetLon: frame.lon,
        targetAltitude: altitude,
        targetHeading: frame.headingDeg ?? 0,
        frame,
      })
    }

    const marker = droneMarkers.get(frame.droneId)
    if (!marker) {
      const created = L.marker([frame.lat, frame.lon], {
        icon: createDroneDivIcon(),
        // 无人机图标比默认标记大，抬高层级避免被空域填充色盖住
        zIndexOffset: 500,
        keyboard: false,
      })
        .bindPopup(buildPopup(frame))
        .addTo(droneLayer!)

      created.on('click', () => emit('select-drone', frame.droneId))
      droneMarkers.set(frame.droneId, created)
      pushTrailPoint(frame.droneId, frame.lat, frame.lon, true)
    } else {
      marker.setPopupContent(buildPopup(frame))
    }
  })

  // 清理已消失的无人机（连同它的航迹）
  droneMarkers.forEach((marker, droneId) => {
    if (!validIds.has(droneId)) {
      droneLayer!.removeLayer(marker)
      droneMarkers.delete(droneId)
      motion.delete(droneId)
      trails.delete(droneId)
      const trail = trailLines.get(droneId)
      if (trail) {
        trailLayer?.removeLayer(trail)
        trailLines.delete(droneId)
      }
    }
  })

  // 注意：这里**不再**自动 fitBounds 到机队。
  // 默认视野是中国全览（这是明确的产品需求：地图要能看到中国），
  // 机队只占上海的几个像素，靠"作业区"标记 + 「定位机队」按钮来引导，而不是抢走视野。
  syncOperationMarker()
}

// ---------------------------------------------------------------------------
// 航迹尾迹
// ---------------------------------------------------------------------------

/** 两点间近似距离（米）：只用来决定"要不要记一个点"，精度要求很低 */
function roughDistanceM(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const dLat = (bLat - aLat) * 111320
  const dLon = (bLon - aLon) * 111320 * Math.cos((aLat * Math.PI) / 180)
  return Math.sqrt(dLat * dLat + dLon * dLon)
}

function pushTrailPoint(droneId: number, lat: number, lon: number, force = false) {
  let points = trails.get(droneId)
  if (!points) {
    points = []
    trails.set(droneId, points)
  }
  const last = points[points.length - 1]
  if (!force && last && roughDistanceM(last[0], last[1], lat, lon) < TRAIL_MIN_GAP_M) return

  points.push([lat, lon])
  // 只保留最近一段：航迹是"近期轨迹"，不是完整历史（完整历史用遥测 CSV 回放）
  if (points.length > TRAIL_MAX_POINTS) points.shift()

  const status = (motion.get(droneId)?.frame.status ?? 'IDLE') as DroneStatus
  const color = DRONE_STATUS_HEX[status] ?? '#1677ff'
  const existing = trailLines.get(droneId)
  if (existing) {
    existing.setLatLngs(points.map((p) => L.latLng(p[0], p[1])))
    existing.setStyle({ color })
  } else if (points.length >= 2 && trailLayer) {
    trailLines.set(
      droneId,
      L.polyline(
        points.map((p) => L.latLng(p[0], p[1])),
        // 点线让尾迹与"计划航路"（实线）一眼区分开
        { color, weight: 2, opacity: 0.4, dashArray: '1 4' },
      ).addTo(trailLayer),
    )
  }
}

// ---------------------------------------------------------------------------
// 插值动画
// ---------------------------------------------------------------------------

function startAnimation() {
  if (rafId !== null) return
  const step = (ts: number) => {
    rafId = requestAnimationFrame(step)

    // 限到约 30fps：数据每秒才更新一次，60fps 没有收益，只会白烧 CPU
    if (ts - lastFrameTs < 33) return
    const dt = Math.min((ts - lastFrameTs) / 1000, 0.2)
    lastFrameTs = ts

    // 数量太多时不做插值：直接贴到目标，牺牲平滑换帧率
    const smooth = motion.size <= SMOOTH_MOTION_LIMIT
    const k = smooth ? 1 - Math.exp(-dt / SMOOTH_TAU) : 1
    // 姿态比位置更"迟钝"：转弯结束后机身要自然回正，而不是瞬间弹平
    const attitudeK = 1 - Math.exp(-dt / ATTITUDE_TAU)

    motion.forEach((state, droneId) => {
      const prevAltitude = state.altitude
      const prevHeading = state.heading

      state.lat += (state.targetLat - state.lat) * k
      state.lon += (state.targetLon - state.lon) * k
      state.altitude += (state.targetAltitude - state.altitude) * k
      state.heading = lerpAngle(state.heading, state.targetHeading, k)

      /*
       * 姿态解算：服务端只给"在哪、多快、多高"，姿态是渲染层从时序变化里反推的。
       *  - 垂直速度 → 俯仰：爬升抬头、下降低头（flight-path angle）
       *  - 转弯角速度 → 横滚：协调转弯 tan φ = v·ω / g，转弯必须压坡度
       *  - 水平速度 → 旋翼转速：飞得越快转速越高
       * 只在开启插值时解算，否则逐帧跳变会算出巨大的瞬时导数、姿态乱甩。
       */
      const groundSpeed = state.frame.speedMps ?? 0
      if (smooth && dt > 0) {
        const verticalSpeed = (state.altitude - prevAltitude) / dt
        const turnDegPerSec = (((state.heading - prevHeading + 540) % 360) - 180) / dt
        const turnRadPerSec = (turnDegPerSec * Math.PI) / 180

        const targetRoll = clamp(radToDeg(Math.atan((groundSpeed * turnRadPerSec) / 9.81)), -MAX_BANK_DEG, MAX_BANK_DEG)
        const targetPitch = clamp(radToDeg(Math.atan2(verticalSpeed, Math.max(groundSpeed, 1))), -MAX_PITCH_DEG, MAX_PITCH_DEG)
        state.roll += (targetRoll - state.roll) * attitudeK
        state.pitch += (targetPitch - state.pitch) * attitudeK
      }

      const targetRotor = clamp(groundSpeed / ROTOR_REF_SPEED_MPS, 0.12, 1)
      state.rotorLevel += (targetRotor - state.rotorLevel) * attitudeK

      const marker = droneMarkers.get(droneId)
      if (!marker) return

      // 立体视图：把插值后的位姿交给 3D 场景。
      // 这里**不重算**位置与姿态 —— 插值只在上面做一次，3D 只是换一种画法；
      // 若两边各插一次值，切视图时同一架飞机会出现在两个位置上。
      if (viewMode.value === '3D') {
        poses3d.push({
          droneId,
          lat: state.lat,
          lon: state.lon,
          altitudeM: state.altitude,
          headingDeg: state.heading,
          rotorLevel: state.rotorLevel,
          status: state.frame.status,
          batteryPct: state.frame.batteryPct,
          // 机型决定外形（四/六/八旋翼），必须传下去，否则立体视图里所有飞机长得一样
          modelCode: state.frame.modelCode,
        })
        return
      }

      marker.setLatLng([state.lat, state.lon])

      const host = marker.getElement()
      if (host) {
        applyDroneFrame(host, state.frame, state.heading, {
          pitchDeg: state.pitch,
          rollDeg: state.roll,
          rotorLevel: state.rotorLevel,
          altitudeM: state.altitude,
        })
      }
    })

    // 位姿整体交付一次，而不是每架飞机各自触发一次场景遍历
    if (viewMode.value === '3D') scene3dRef.value?.updateFrames(poses3d)
    poses3d.length = 0
  }
  rafId = requestAnimationFrame(step)
}

/**
 * 每帧收集的立体视图位姿。
 *
 * 复用一个数组并每帧清空（而不是每帧新建）：26 架 × 30fps 的新对象会带来没必要的 GC 抖动。
 */
const poses3d: Array<{
  droneId: number
  lat: number
  lon: number
  altitudeM: number
  headingDeg: number
  rotorLevel: number
  status: DroneStatus
  batteryPct?: number
  modelCode?: string
}> = []

function stopAnimation() {
  if (rafId !== null) {
    cancelAnimationFrame(rafId)
    rafId = null
  }
}

// ---------------------------------------------------------------------------
// 选中联动：航向锥 + 视野跟随
// ---------------------------------------------------------------------------

function refreshCone(): void {
  const droneId = props.selectedDroneId
  if (!droneId || !coneLayer) return
  const state = motion.get(droneId)
  if (!state) return

  // 航向锥长度随速度变化：飞得快的飞机"看得更远"，视觉上更容易理解
  const coneLength = Math.max(300, (state.frame.speedMps ?? 0) * 60)
  const color = DRONE_STATUS_HEX[state.frame.status as DroneStatus] ?? '#1677ff'

  coneLayer.clearLayers()
  coneLayer.addLayer(
    L.polygon(headingConeLatLngs(state.lat, state.lon, state.heading, coneLength), {
      color,
      weight: 1,
      fillColor: color,
      fillOpacity: 0.18,
      interactive: false,
    }),
  )
}

watch(
  () => props.selectedDroneId,
  (droneId) => {
    // 先清掉上一个选中态
    droneMarkers.forEach((marker, id) => {
      const host = marker.getElement()
      if (host) host.classList.toggle('is-selected', id === droneId)
    })
    coneLayer?.clearLayers()

    if (!droneId || !map) return
    const state = motion.get(droneId)
    if (!state) return

    // 立体视图下"定位"交给 3D 场景的「跟随」预设，不再去动被隐藏的 Leaflet 地图
    if (viewMode.value === '3D') {
      scene3dRef.value?.updateFrames(poses3d)
      return
    }

    map.panTo([state.lat, state.lon], { animate: true, duration: 0.4 })
    droneMarkers.get(droneId)?.openPopup()
    refreshCone()
  },
)

/** 选中机的航向锥要跟着飞机走，否则一松手就"掉队"了 */
// （与下面的 drones 监听合并成一个，避免同一数据源注册两个 watcher 造成困惑）

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(() => {
  initMap()
  // 订单标记可能在组件挂载前就已随快照到达（如切页再切回），挂载后主动画一次
  rebuildOrderPins()
  window.addEventListener('resize', handleResize)
  document.addEventListener('fullscreenchange', onFullscreenChange)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  // 组件卸载时若仍处于全屏，主动退出，避免留下"整块地图盖住页面"的残留状态
  if (document.fullscreenElement === mapWrapEl.value) {
    void document.exitFullscreen().catch(() => undefined)
  }
  isFullscreen.value = false
  stopAnimation()
  stopFocusTimer()
  adminHandle?.destroy()
  adminHandle = null
  // 必须销毁地图实例：只清 DOM 会残留事件监听与动画帧，多次切换页面后内存暴涨
  map?.remove()
  map = null
  droneMarkers.clear()
  routeLines.clear()
  trailLines.clear()
  motion.clear()
  trails.clear()
})

let resizeTimer: number | null = null
function handleResize() {
  if (resizeTimer !== null) window.clearTimeout(resizeTimer)
  resizeTimer = window.setTimeout(() => {
    map?.invalidateSize()
    resizeTimer = null
  }, 200)
}

watch(
  () => props.drones,
  (frames) => {
    syncDrones()
    // 每个服务端帧记一个航迹点（1 秒一次，比在动画循环里记要省得多）
    frames.forEach((frame) => {
      if (frame.lat !== undefined && frame.lon !== undefined) {
        pushTrailPoint(frame.droneId, frame.lat, frame.lon)
      }
    })
    // 选中的那架飞机在动，航向锥也要跟着刷新
    if (props.selectedDroneId) refreshCone()

    // 首帧有位置数据时自动定位到作业区（用户拖过地图就不抢视角），
    // 与挂载轮询互为兜底，autoFocusDone 保证只定位一次
    if (!autoFocusDone && !userDraggedMap && props.autoFocus !== false) {
      if (frames.some((f) => f.lat !== undefined && f.lon !== undefined)) {
        autoFocusDone = true
        stopFocusTimer()
        focusOperationArea()
      }
    }
  },
)
watch(() => props.routes, updateRoutes)
watch(() => props.zones, () => rebuildZones())
watch(() => props.stations, () => rebuildStations())
watch(() => props.orderPins, () => rebuildOrderPins(), { deep: false })

/** 图例数据：把枚举字典转成数组，避免在模板里 v-for 对象导致 key 被推断成 string */
const legendItems = computed(() =>
  (Object.keys(DRONE_STATUS_TEXT) as DroneStatus[]).map((status) => ({
    status,
    text: DRONE_STATUS_TEXT[status],
    color: DRONE_STATUS_HEX[status],
  })),
)
</script>

<template>
  <div ref="mapWrapEl" class="map-wrap" :style="{ height }">
    <!-- 平面视图（Leaflet）：切到立体时用 v-show 隐藏而不是销毁 —— 重新建图要重载
         省级 GeoJSON 与瓦片，来回切两次就能感到明显卡顿 -->
    <div v-show="viewMode === '2D'" ref="mapEl" class="map-canvas"></div>

    <!-- 立体视图（Three.js）：按当前平面视野框定的三维场景。
         同样用 v-show 保留场景与相机状态，active=false 时停掉渲染循环省电 -->
    <Scene3DView
      v-show="viewMode === '3D'"
      ref="scene3dRef"
      :zones="zones"
      :routes="routes"
      :order-pins="orderPins"
      :stations="stations"
      :selected-drone-id="selectedDroneId"
      :area-label="scene3dAreaLabel"
      :active="viewMode === '3D'"
      @select-drone="(id: number) => emit('select-drone', id)"
      @recenter="(lat: number, lon: number) => (scene3dAreaLabel = cityNameOf(lat, lon) ?? '当前视野')"
    />

    <!-- 左上角 HUD：视图切换、底图来源与实时读数，并提供快捷视角 -->
    <div class="map-hud">
      <!-- 视图切换：平面 / 立体。立体视图才能看清"飞机在楼顶之上还是楼缝里" -->
      <div class="hud-view-switch">
        <a-radio-group v-model:value="viewMode" size="small" button-style="solid" @change="onViewModeChange(viewMode)">
          <a-radio-button value="2D">平面</a-radio-button>
          <a-radio-button value="3D">立体</a-radio-button>
        </a-radio-group>
        <template v-if="viewMode === '3D'">
          <a-radio-group
            v-model:value="cameraPreset"
            size="small"
            button-style="solid"
            class="hud-preset"
            @change="changeCameraPreset(cameraPreset)"
          >
            <a-radio-button value="oblique">斜视</a-radio-button>
            <a-radio-button value="top">俯视</a-radio-button>
            <a-radio-button value="close">贴近</a-radio-button>
            <a-radio-button value="follow">跟随选中</a-radio-button>
          </a-radio-group>
          <a-button size="small" type="link" @click="focusFleet3D">聚焦机队</a-button>
          <!-- 范围：解决"只能看到一小片"；平移：解决"看不到别的区域"。
               两个都做成按钮而不是手势 —— 立体视图里没有任何手势提示，
               用户不会去猜"滚轮能缩放、右键能平移" -->
          <span class="hud-pan-pad">
            <a-button size="small" @click="zoom3D(0.6)">范围 +</a-button>
            <a-button size="small" @click="zoom3D(1.7)">范围 -</a-button>
          </span>
          <span class="hud-pan-pad">
            <a-button size="small" @click="pan3D('north')">北</a-button>
            <a-button size="small" @click="pan3D('south')">南</a-button>
            <a-button size="small" @click="pan3D('west')">西</a-button>
            <a-button size="small" @click="pan3D('east')">东</a-button>
          </span>
        </template>
      </div>
      <div class="hud-badge">
        {{ viewMode === '3D' ? '立体视图 · 高度已放大 3×' : chinaMapReady ? '中国地图 · 省级边界' : '模拟地图 · 程序化生成' }}
      </div>
      <!-- 缩放/光标是平面地图的读数，立体视图下没有意义，切走 -->
      <div v-if="viewMode === '2D'" class="hud-line">
        <span class="text-muted">缩放</span> z{{ zoomLevel }}
        <span class="hud-sep">|</span>
        <span class="text-muted">光标</span>
        <span class="text-mono">{{ cursorText || '—' }}</span>
      </div>
      <div class="hud-actions">
        <template v-if="viewMode === '2D'">
          <a-button size="small" type="link" @click="zoomToChina">全览中国</a-button>
          <a-button size="small" type="link" @click="zoomToFleet">定位机队</a-button>
        </template>
        <!-- 全屏：只放大地图容器本身，ESC 或再次点击即可退出（两种视图都适用） -->
        <a-button size="small" type="link" @click="toggleFullscreen">
          <template #icon>
            <FullscreenExitOutlined v-if="isFullscreen" />
            <FullscreenOutlined v-else />
          </template>
          {{ isFullscreen ? '退出全屏' : '全屏' }}
        </a-button>
      </div>
      <div v-if="viewMode === '2D' && !chinaMapReady" class="hud-warn">
        未找到 public/geo/china.json，已退化为程序化底图。
        在 frontend 目录执行 <code>node scripts/fetch-china-geo.mjs</code> 即可恢复中国地图。
      </div>
    </div>

    <!-- 右上角：行政区划控制（分色 + 省下钻到市/区县）。立体视图下没有下钻语义，切走 -->
    <div v-if="viewMode === '2D'" class="admin-panel">
      <div class="admin-row">
        <span class="admin-title">行政区划</span>
        <a-radio-group
          v-model:value="colorMode"
          size="small"
          button-style="solid"
        >
          <a-radio-button value="uniform">单色</a-radio-button>
          <a-radio-button value="palette">分省着色</a-radio-button>
        </a-radio-group>
      </div>
      <div class="admin-row">
        <span class="admin-title">显示名称</span>
        <a-switch v-model:checked="showAdminLabels" size="small" checked-children="开" un-checked-children="关" />
        <span class="admin-sub">省 / 市 / 区县名常驻标注</span>
      </div>
      <div class="admin-row">
        <a-select
          :value="selectedProvinceAdcode"
          :options="provinceOptions"
          :loading="adminLoading"
          show-search
          allow-clear
          option-filter-prop="label"
          style="width: 218px"
          placeholder="选择省份，高亮其市 / 区县"
          @change="onProvincePickFromSelect"
        />
      </div>
      <div v-if="adminNotice" class="admin-notice">{{ adminNotice }}</div>
      <div v-if="adminRegion" class="admin-crumb">
        <a-button size="small" type="link" class="admin-back" @click="void onAdminBack()">
          返回{{ adminRegion.level === 'city' ? '省份' : '全国' }}
        </a-button>
        <span class="admin-current">{{ adminRegion.name }}</span>
        <span class="admin-crumb-hint">
          {{ adminRegion.level === 'city' ? '区/县边界' : '下辖市/区县已高亮（点地级市继续看区县）' }}
        </span>
      </div>
      <div v-else class="admin-hint">放大到省级尺度会自动加载该省的市 / 区县边界（需联网加载）</div>
    </div>

    <!-- 图例 -->
    <div class="map-legend">
      <div class="legend-title">机队状态</div>
      <div v-for="item in legendItems" :key="item.status" class="legend-row">
        <span class="legend-dot" :style="{ background: item.color }"></span>
        <span>{{ item.text }}</span>
      </div>
      <div class="legend-title legend-title-gap">空域</div>
      <div class="legend-row">
        <span class="legend-box" style="border-color: #ff4d4f; background: rgba(255, 77, 79, 0.15)"></span>
        <span>禁飞区</span>
      </div>
      <div class="legend-row">
        <span class="legend-box" style="border-color: #fa8c16; background: rgba(250, 140, 22, 0.13)"></span>
        <span>限飞区</span>
      </div>
      <div class="legend-row">
        <span class="legend-box" style="border-color: #722ed1; background: rgba(114, 46, 209, 0.15)"></span>
        <span>建筑障碍</span>
      </div>
      <div class="legend-row">
        <span class="legend-station">⚡</span>
        <span>充电站（中途补能点）</span>
      </div>
      <template v-if="viewMode === '3D'">
        <div class="legend-title legend-title-gap">立体视图</div>
        <div class="legend-row">
          <span class="legend-line legend-line-route"></span>
          <span>计划航路（巡航高度）</span>
        </div>
        <div class="legend-row">
          <span class="legend-line legend-line-tether"></span>
          <span>高度投影线</span>
        </div>
        <div class="legend-row">
          <span class="legend-dot" style="background: #fa8c16"></span>
          <span>订单目的地信标</span>
        </div>
      </template>
      <template v-else>
        <div class="legend-title legend-title-gap">航迹</div>
        <div class="legend-row">
          <span class="legend-line legend-line-route"></span>
          <span>计划航路</span>
        </div>
        <div class="legend-row">
          <span class="legend-line legend-line-trail"></span>
          <span>已飞尾迹</span>
        </div>
      </template>
    </div>

    <div v-if="drones.length === 0" class="map-empty">
      <a-spin size="small" />
      <span>等待遥测数据…（请确认后端与算法服务已启动）</span>
    </div>
  </div>
</template>

<style scoped>
.map-wrap {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  /* 与深色底图一致，避免瓦片未加载时闪出浅灰 */
  background: #07131f;
}

.map-canvas {
  width: 100%;
  height: 100%;
}

.map-hud {
  position: absolute;
  left: 12px;
  top: 12px;
  z-index: 400;
  padding: 8px 10px;
  /* 深色玻璃面板 + 青色细描边：悬浮在地图之上仍然清晰可读 */
  background: rgba(7, 20, 34, 0.88);
  border: 1px solid rgba(34, 211, 238, 0.18);
  border-radius: 6px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(6px);
  font-size: 12px;
  line-height: 1.7;
  pointer-events: none;
}

.hud-badge {
  font-weight: 600;
  color: #4fc3ff;
}

/* HUD 整体不接收鼠标事件（否则会挡住地图拖拽），但按钮必须能点，
   所以单独把这一层重新打开 pointer-events */
.hud-actions {
  pointer-events: auto;
  margin-top: 2px;
}

/* 视图切换与相机预设：这是 HUD 里最常用的控件，放最上面一行 */
.hud-view-switch {
  pointer-events: auto;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 4px;
}

.hud-view-switch :deep(.ant-radio-button-wrapper) {
  height: 22px;
  line-height: 20px;
  padding: 0 8px;
  font-size: 12px;
}

.hud-preset {
  margin-left: 2px;
}

/* 立体视图的方向平移按钮：四个小键排在一起，一眼看出"能往四周看" */
.hud-pan-pad {
  display: inline-flex;
  gap: 3px;
  margin-left: 4px;
}

.hud-pan-pad :deep(.ant-btn) {
  padding: 0 6px;
  height: 20px;
  font-size: 12px;
}

.hud-actions :deep(.ant-btn) {
  padding: 0 6px;
  height: 20px;
  font-size: 12px;
}

.hud-warn {
  margin-top: 4px;
  max-width: 320px;
  padding: 4px 6px;
  border-radius: 4px;
  background: rgba(250, 173, 20, 0.12);
  border: 1px solid rgba(250, 173, 20, 0.38);
  color: #f0b45a;
  font-size: 11px;
  line-height: 1.5;
  white-space: normal;
}

.hud-warn code {
  background: rgba(255, 255, 255, 0.08);
  padding: 0 3px;
  border-radius: 2px;
}

.hud-sep {
  margin: 0 6px;
  color: rgba(255, 255, 255, 0.18);
}

.map-legend {
  position: absolute;
  right: 12px;
  bottom: 12px;
  z-index: 400;
  padding: 10px 12px;
  background: rgba(7, 20, 34, 0.9);
  border: 1px solid rgba(34, 211, 238, 0.18);
  border-radius: 6px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(6px);
  font-size: 12px;
  line-height: 1.8;
}

.legend-title {
  font-weight: 600;
  color: #cfe6fb;
  margin-bottom: 2px;
}

.legend-title-gap {
  margin-top: 8px;
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 6px;
  color: rgba(190, 216, 240, 0.78);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1.5px solid #0a1a28;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.55);
}

.legend-box {
  width: 12px;
  height: 10px;
  border: 1.5px solid;
  border-radius: 2px;
}

.legend-line {
  width: 18px;
  height: 0;
  border-top-width: 2px;
  border-top-style: solid;
}

.legend-line-route {
  border-top-color: #1677ff;
}

.legend-line-trail {
  border-top-color: #8c8c8c;
  border-top-style: dotted;
}

/* 立体视图里的"地面 → 机身"投影线：浅灰半透明，与航路的蓝色实线区分开 */
.legend-line-tether {
  border-top-color: #94a3b8;
  border-top-style: dashed;
}

/* 图例里的充电站符号，与地图上的站标一致 */
.legend-station {
  display: inline-block;
  width: 14px;
  height: 14px;
  line-height: 14px;
  text-align: center;
  border-radius: 50%;
  background: #0d2740;
  border: 1px solid #38c6ff;
  color: #38c6ff;
  font-size: 9px;
}

/* 地图上的充电站标记：圆形底盘 + 闪电，与无人机/订单标记不会混淆 */
.map-wrap :deep(.station-icon-host) {
  background: transparent;
  border: none;
}

.map-wrap :deep(.station-pin) {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #0d2740;
  border: 2px solid #38c6ff;
  box-shadow: 0 0 8px rgba(56, 198, 255, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  line-height: 1;
  color: #38c6ff;
}

.map-empty {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: rgba(7, 20, 34, 0.92);
  border: 1px solid rgba(34, 211, 238, 0.18);
  border-radius: 6px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.45);
  font-size: 13px;
  color: rgba(200, 224, 245, 0.8);
  z-index: 401;
  pointer-events: none;
}

/* ------------------------------ 行政区划面板 ------------------------------ */
.admin-panel {
  position: absolute;
  top: 80px; /* 让开右上角的缩放控件 */
  right: 12px;
  /* 必须高于右下角的"机队状态"图例：面板与图例都贴在右边，容器不够高时下半截会被图例压住，
     而"正在加载/暂无下级数据"这两句提示恰好长在面板底部 —— 被压住就等于问题又"看不见"了 */
  z-index: 430;
  /* 内容最多时（面包屑 + 提示）也不会长到图例的区域内，超出内部滚动 */
  max-height: calc(100% - 260px);
  overflow-y: auto;
  width: 248px;
  padding: 8px 10px;
  background: rgba(7, 20, 34, 0.92);
  border: 1px solid rgba(34, 211, 238, 0.18);
  border-radius: 6px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(6px);
  font-size: 12px;
  pointer-events: auto;
}

.admin-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.admin-title {
  font-weight: 600;
  color: rgba(190, 216, 240, 0.9);
  white-space: nowrap;
}

.admin-crumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  padding-top: 6px;
  border-top: 1px dashed #1d3a55;
}

.admin-back {
  padding: 0 4px;
  height: 20px;
  font-size: 12px;
}

.admin-current {
  font-weight: 600;
  color: #4fc3ff;
}

.admin-crumb-hint {
  flex-basis: 100%;
  color: rgba(180, 208, 232, 0.55);
  line-height: 1.5;
}

.admin-hint {
  color: rgba(180, 208, 232, 0.55);
  line-height: 1.5;
}

/* 自动下钻失败的说明：用警示色，否则用户只会得出"放大也看不到市区县"的结论 */
.admin-notice {
  flex-basis: 100%;
  margin-top: 2px;
  padding-top: 6px;
  border-top: 1px dashed rgba(250, 173, 20, 0.38);
  color: #f0b45a;
  line-height: 1.5;
}

.admin-sub {
  color: rgba(180, 208, 232, 0.55);
  font-size: 11px;
}

/* 行政区划名称标签的样式在 styles/global.css：这份标签每张地图都会插入，
   写在组件 scoped 样式里的话，其它地图上的标签就没有层级与描边 */

/* ------------------------------ 全屏 ------------------------------ */
/* 内联 height 会盖过浏览器 :fullscreen 的默认尺寸，必须用 !important 覆盖，
   否则全屏后地图仍只有原高度、周围留黑边 */
.map-wrap:fullscreen {
  height: 100% !important;
  width: 100% !important;
  border-radius: 0;
}

.map-wrap:fullscreen .map-canvas {
  border-radius: 0;
}

/* ------------------------------ 订单地理标记（动态注入，需 :deep） ------------------------------ */
.map-wrap :deep(.order-pin-host) {
  background: transparent;
  border: none;
}

.map-wrap :deep(.order-pin) {
  position: relative;
  width: 100%;
  height: 100%;
}

/* 取货起降点：蓝色方块 + 关联订单角标 */
.map-wrap :deep(.order-pin.station) {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: #1677ff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
}

.map-wrap :deep(.order-pin.station i) {
  position: absolute;
  top: -8px;
  right: -10px;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 8px;
  background: #fff;
  color: #1677ff;
  border: 1px solid #1677ff;
  font-style: normal;
  font-size: 10px;
  line-height: 13px;
  text-align: center;
}

/* 送货目的地：水滴形图钉，文字随状态区分 */
.map-wrap :deep(.order-pin.drop) {
  width: 24px;
  height: 30px;
}

/* 水滴本体（旋转 -45° 的圆形，尖角朝下） */
.map-wrap :deep(.order-pin.drop .teardrop) {
  position: absolute;
  left: 1px;
  top: 0;
  width: 22px;
  height: 22px;
  border-radius: 50% 50% 50% 0;
  transform: rotate(-45deg);
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
}

/* 文字反向旋转回正，叠在水滴中心 */
.map-wrap :deep(.order-pin.drop b) {
  position: absolute;
  left: 0;
  top: 1px;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}

.map-wrap :deep(.drop--pending .teardrop) {
  background: #fa8c16;
}

.map-wrap :deep(.drop--delivered .teardrop) {
  background: #52c41a;
}

.map-wrap :deep(.drop--active .teardrop) {
  background: #ff4d4f;
}

/* 在飞目的地的脉冲波纹，提示"飞机正在飞向这里" */
.map-wrap :deep(.drop--active)::after {
  content: '';
  position: absolute;
  left: 2px;
  top: 1px;
  width: 22px;
  height: 22px;
  border-radius: 50% 50% 50% 0;
  transform: rotate(-45deg) scale(0.7);
  border: 2px solid #ff4d4f;
  animation: drop-pulse 1.5s ease-out infinite;
  pointer-events: none;
}

@keyframes drop-pulse {
  0% {
    opacity: 0.85;
    transform: rotate(-45deg) scale(0.7);
  }
  100% {
    opacity: 0;
    transform: rotate(-45deg) scale(1.9);
  }
}
</style>
