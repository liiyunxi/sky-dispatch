<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import type { AirspaceZone, ChargingStation, DroneStatus, OrderPin, TaskStatus, ZoneType } from '@/api'
import { createDroneModel, disposeObject } from '@/components/drone/droneGeometry'
import { DRONE_STATUS_HEX } from '@/utils/format'

/**
 * 态势地图的立体（3D）视图。
 *
 * <p><b>它解决什么问题</b>：平面地图把"高度"这件事彻底压扁了 —— 只会告诉你在哪，
 * 不会告诉你"这架飞机正从 180m 的写字楼顶上飞过"。这里把同一批遥测数据放进一个
 * 真正的三维场景：地面 + 空域体块 + 挤出成实体的高楼 + 悬停在各自真实高度上的四旋翼，
 * 于是"绕楼、爬升、压着禁飞区顶上走"这些事一眼可见。
 *
 * <p><b>三个必须解释的换算</b>（否则用户会觉得比例不对）：
 * <ol>
 *   <li><b>水平方向 1 场景单位 = 1 米</b>，经纬度按等距圆柱投影折算成局部米制平面。</li>
 *   <li><b>垂直方向放大 {@link VERTICAL_EXAGGERATION} 倍</b>。一个 4km 宽的城市视野里，
 *       真实 120m 的飞行高度只占画面 3%，楼和飞机全都糊在地上；把高度与楼高<b>同比例</b>
 *       放大后，"楼顶之上"的层次关系才看得出来。幅度写在界面上，不做隐性欺骗。</li>
 *   <li><b>无人机按示意尺寸绘制</b>（{@link DRONE_SIZE_M}）。真机 1m 翼展在这个尺度下
 *       不足一个像素 —— 这与平面地图用固定大小的图标是同一个取舍。</li>
 * </ol>
 *
 * <p><b>为什么不自己算插值</b>：位置/姿态的平滑插值、姿态解算都已经在 DroneMap 的
 * 动画循环里做完了（那是唯一的数据真相）。本组件只负责"把最新位姿画出来"，
 * 通过 {@code updateFrames()} 被动接收，绝不重复注册定时器。
 */

/** 垂直放大倍数（见文件头说明） */
const VERTICAL_EXAGGERATION = 3
/**
 * 无人机示意翼展（米）。
 *
 * 真机 1m 翼展在"一座城市"的视野下不足一个像素，所以给一个示意尺寸。
 * 取值是按"城市级视野下的可读性"倒推的：视野 10km 时约 7 像素，配上下方的
 * 状态光环，既看得见又不至于糊住航线。
 */
const DRONE_SIZE_M = 55
/** 计划航路绘制高度（米）。航路点只带经纬度不带高度，用典型巡航高度示意 */
const ROUTE_ALT_M = 95
/** 每度纬度对应的米数 */
const M_PER_DEG_LAT = 111132
/** 地平线网格与地面的外扩倍数（相对视野跨度） */
const GROUND_SCALE = 2.4
/**
 * 视野中心漂移超过该比例（相对跨度）就换原点、把新区域的空域/航路加载进来。
 *
 * <p>没有这一步，立体视图就是"一块固定大小的板子"：往边上拖两下就只剩一张空白网格，
 * 用户会说"它不会动"。换原点后场景可以跟着视角无限延伸。
 *
 * <p>取 0.35 而不是更大：HUD 上的方向按钮每按一次平移 0.45 个跨度，
 * 按一下就该换到相邻区域、把新内容加载出来，不该让用户按三四次才看到反应。
 */
const RECENTER_DRIFT_RATIO = 0.35

/**
 * 城市基准海拔（米）。
 *
 * <p>真实 DEM 高程数据体积大、获取也麻烦，而这个仿真只需要表达
 * "不同地方的地面不在同一个高度"：上海几乎与海平面齐平、成都在 500m 的盆地里、
 * 山西高原上则有近千米。所以按<b>城市级基准海拔</b>整块抬升地形，
 * 足以让太原的立体视图看起来"架在高处"，而不必铺真实高程网格。
 *
 * <p>匹配规则：取最近的一个站点，超出 {@link ELEVATION_MATCH_RADIUS_KM} 则用默认海拔。
 */
const SITE_ELEVATIONS: Array<{ name: string; lat: number; lon: number; elevM: number }> = [
  { name: '上海', lat: 31.2304, lon: 121.4737, elevM: 4 },
  { name: '北京', lat: 39.9042, lon: 116.4074, elevM: 44 },
  { name: '深圳', lat: 22.5431, lon: 114.0579, elevM: 6 },
  { name: '成都', lat: 30.5728, lon: 104.0668, elevM: 500 },
  { name: '重庆', lat: 29.563, lon: 106.5516, elevM: 260 },
  { name: '武汉', lat: 30.5928, lon: 114.3055, elevM: 25 },
  { name: '西安', lat: 34.3416, lon: 108.9398, elevM: 400 },
  { name: '太原', lat: 37.8706, lon: 112.5489, elevM: 780 },
  { name: '呼和浩特', lat: 40.8426, lon: 111.7492, elevM: 1040 },
  { name: '兰州', lat: 36.0611, lon: 103.8343, elevM: 1520 },
  { name: '银川', lat: 38.4872, lon: 106.2309, elevM: 1110 },
  { name: '西宁', lat: 36.6171, lon: 101.7782, elevM: 2260 },
  { name: '昆明', lat: 24.8801, lon: 102.8329, elevM: 1890 },
  { name: '贵阳', lat: 26.647, lon: 106.6302, elevM: 1070 },
  { name: '拉萨', lat: 29.652, lon: 91.1721, elevM: 3650 },
  { name: '乌鲁木齐', lat: 43.8256, lon: 87.6168, elevM: 800 },
  { name: '哈尔滨', lat: 45.8038, lon: 126.5349, elevM: 150 },
  { name: '沈阳', lat: 41.8057, lon: 123.4315, elevM: 45 },
  { name: '郑州', lat: 34.7466, lon: 113.6254, elevM: 110 },
  { name: '杭州', lat: 30.2741, lon: 120.1551, elevM: 10 },
  { name: '南京', lat: 32.0603, lon: 118.7969, elevM: 20 },
  { name: '天津', lat: 39.3434, lon: 117.3616, elevM: 5 },
  { name: '广州', lat: 23.1291, lon: 113.2644, elevM: 21 },
  { name: '青岛', lat: 36.0671, lon: 120.3826, elevM: 20 },
]
/** 海拔匹配半径（公里）：超过它就认为不在已知站点附近 */
const ELEVATION_MATCH_RADIUS_KM = 220
/** 无法匹配时的默认海拔（米） */
const DEFAULT_ELEVATION_M = 50

/** 场景竖直方向的地面基准（米）。所有内容整体抬到该海拔上，表达"地势不在同一高度" */
let baseElevationM = 0

/** 一帧需要绘制的位姿（由 DroneMap 的插值循环喂进来） */
interface PoseFrame {
  droneId: number
  lat: number
  lon: number
  altitudeM: number
  headingDeg: number
  rotorLevel: number
  status: string
  batteryPct?: number
  /** 机型代码：不同型号的机臂数量与机身尺寸不同，按它选外形 */
  modelCode?: string
}

const props = withDefaults(
  defineProps<{
    zones: AirspaceZone[]
    routes: Array<{ taskId: number; label: string; status: TaskStatus; points: [number, number][] }>
    orderPins?: OrderPin[]
    /** 充电站：立体视图里画成"停机坪 + 灯柱"，一眼看出飞机中途能落哪儿补能 */
    stations?: ChargingStation[]
    selectedDroneId?: number | null
    /** 当前视野的城市名，用于左上角标出"这是哪一片空域" */
    areaLabel?: string
    height?: string
    /** 是否处于展示状态（切回平面视图时置 false，停掉渲染循环省电） */
    active?: boolean
  }>(),
  { orderPins: () => [], stations: () => [], selectedDroneId: null, areaLabel: '', height: '100%', active: false },
)

const emit = defineEmits<{
  (e: 'select-drone', droneId: number): void
  /**
   * 立体场景自己换了原点（用户平移走了 / 跟随的飞机飞远了）。
   *
   * <p>必须通知父组件：左上角的城市名是父组件算的，不通知的话会出现
   * "城市名还写着上海、下面的基准海拔已经变成成都"的互相矛盾。
   */
  (e: 'recenter', lat: number, lon: number): void
}>()

const hostEl = ref<HTMLElement | null>(null)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let rafId = 0
let lastTs = 0
let resizeObserver: ResizeObserver | null = null

/** 视野原点（经纬度），所有投影都以它为零点 */
let originLat = 31.2304
let originLon = 121.4737
/** 当前视野跨度（米），决定地面尺寸与相机距离 */
let spanM = 6000

/**
 * 世界根节点。
 *
 * <p>所有可见内容都挂在它下面，而不是直接挂到 {@code scene}：这样"整体抬到该城市的
 * 基准海拔"只需要改这一个节点的 y —— 否则每加一种元素（空域、建筑、航路、无人机、
 * 地面网格……）都要记得给它单独加海拔偏移，漏一处就穿模。
 */
let worldGroup: THREE.Group | null = null

/** 静态内容（地面 / 空域 / 航路 / 订单）随视野或数据变化整体重建 */
let groundGroup: THREE.Group | null = null
let zoneGroup: THREE.Group | null = null
let routeGroup: THREE.Group | null = null
let pinGroup: THREE.Group | null = null
/** 无人机（动态，每帧更新） */
let droneGroup: THREE.Group | null = null
/** 充电站（静态，随视野重建） */
let stationGroup: THREE.Group | null = null

interface DroneVisual {
  root: THREE.Group
  rotors: THREE.Group[]
  rotorDiscs: THREE.Mesh[]
  /** 地面状态光环：既是"投影点"也是状态色标识 */
  halo: THREE.Mesh
  /** 地面到机身的连线：直观表达"它现在有多高" */
  tether: THREE.Line
}
const droneVisuals = new Map<number, DroneVisual>()
/** 最新一帧数据（渲染循环每次读取） */
let latestPoses: PoseFrame[] = []
/** 旋翼累计转角 */
let rotorPhase = 0
/** 相机预设 */
type CameraPreset = 'oblique' | 'top' | 'close' | 'follow'
let preset: CameraPreset = 'oblique'
let presetApplied = false

const ready = ref(false)
const droneCount = ref(0)
/** HUD 上显示的地面基准海拔文案 */
const elevationLabel = ref('')
/** HUD 上显示的"现在看的是哪一片"（场景原点经纬度），让"视角移动了"这件事看得见 */
const centerLabel = ref('')

// ---------------------------------------------------------------------------
// 投影：经纬度 → 局部米制平面
// ---------------------------------------------------------------------------

function metersPerDegLon(lat: number): number {
  return M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180)
}

/** 经纬度 → 场景 (x, z)。北为 -z、东为 +x（与平面地图的视觉方向一致） */
function project(lat: number, lon: number): [number, number] {
  const x = (lon - originLon) * metersPerDegLon(originLat)
  const z = -(lat - originLat) * M_PER_DEG_LAT
  return [x, z]
}

/** 场景 (x, z) → 经纬度（{@link project} 的逆运算，换原点时要用） */
function unproject(x: number, z: number): [number, number] {
  return [originLat - z / M_PER_DEG_LAT, originLon + x / metersPerDegLon(originLat)]
}

/** 某点所在城市的基准海拔（米）：取最近的已知站点，超出匹配半径则用默认值 */
function elevationAt(lat: number, lon: number): { elevM: number; siteName: string | null } {
  let best: (typeof SITE_ELEVATIONS)[number] | null = null
  let bestKm = Number.POSITIVE_INFINITY
  for (const site of SITE_ELEVATIONS) {
    const dLatKm = (lat - site.lat) * 111
    const dLonKm = (lon - site.lon) * 111 * Math.cos((lat * Math.PI) / 180)
    const km = Math.hypot(dLatKm, dLonKm)
    if (km < bestKm) {
      bestKm = km
      best = site
    }
  }
  if (!best || bestKm > ELEVATION_MATCH_RADIUS_KM) return { elevM: DEFAULT_ELEVATION_M, siteName: null }
  return { elevM: best.elevM, siteName: best.name }
}

/**
 * 把地面整体抬到该城市的基准海拔。
 *
 * <p>抬高的是"世界根节点"（{@link worldGroup}），所以相机也要跟着抬，
 * 否则视角会突然对着地面下方 —— 换个城市（比如从上海到太原）就会立刻体现出来。
 */
function applyElevation(lat: number, lon: number): void {
  const { elevM, siteName } = elevationAt(lat, lon)
  const delta = elevM * VERTICAL_EXAGGERATION - baseElevationM * VERTICAL_EXAGGERATION
  baseElevationM = elevM
  elevationLabel.value = siteName ? `基准海拔 ≈ ${elevM} m（${siteName}）` : `基准海拔 ≈ ${elevM} m（估算）`
  if (worldGroup) worldGroup.position.y = baseElevationM * VERTICAL_EXAGGERATION
  if (delta !== 0 && camera && controls) {
    camera.position.y += delta
    controls.target.y += delta
    controls.update()
  }
}

/**
 * 换原点：把场景内容换成新区域，同时保持画面不跳。
 *
 * <p>怎么做才不跳：内容在新坐标系里整体平移了 {@code -target}，所以相机与轨道中心
 * 也平移同样的量，画面看起来完全连续；随后 {@code rebuildStatic()} 按新原点重新算出
 * 空域 / 航路 / 订单的坐标，{@code updateFrames} 重画无人机。
 *
 * @param newLat 新原点的纬度
 * @param newLon 新原点的经度
 */
function recenterTo(newLat: number, newLon: number): void {
  if (!camera || !controls) return
  const dx = -controls.target.x
  const dz = -controls.target.z
  originLat = newLat
  originLon = newLon
  camera.position.x += dx
  camera.position.z += dz
  controls.target.x += dx
  controls.target.z += dz
  controls.update()
  applyElevation(newLat, newLon)
  rebuildStatic()
  if (latestPoses.length > 0) updateFrames(latestPoses)
  updateCenterLabel()
  emit('recenter', newLat, newLon)
}

/**
 * 把"当前视野中心在哪"写进 HUD。
 *
 * <p>为什么要显示经纬度：立体视图移动时画面是"平移着过去"的，
 * 用户很难仅凭画面判断自己到底动了没有。一个会变的坐标读数，
 * 比"看起来好像没动"可靠得多 —— 也是调试这类空间交互时唯一能对齐的依据。
 *
 * <p><b>读数取的是相机当前对准的地面点，不是换原点的位置</b>：换原点只在漂移超过
 * 0.35 个跨度时才发生，若读数绑在原点上的话，按住 WASD 连续飞的前一秒钟读数纹丝不动，
 * 用户会以为"键盘没生效"。所以按渲染循环逐帧折算，只有文字真的变了才写回 ref
 * （避免每帧触发一次组件重渲染）。
 */
function updateCenterLabel(): void {
  if (!controls) return
  const [lat, lon] = unproject(controls.target.x, controls.target.z)
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return
  const text = `视野中心 ${lat.toFixed(4)}, ${lon.toFixed(4)}`
  if (text !== centerLabel.value) centerLabel.value = text
}

/** 视锥剔除用的包围盒（米） */
function zoneBounds(zone: AirspaceZone): [number, number, number, number] | null {
  if (zone.shapeType === 'CIRCLE' && zone.center) {
    const [cx, cz] = project(zone.center.lat, zone.center.lon)
    const r = Number(zone.radiusM ?? 0)
    return [cx - r, cz - r, cx + r, cz + r]
  }
  const points = zone.points ?? []
  if (points.length < 3) return null
  let minX = Infinity
  let minZ = Infinity
  let maxX = -Infinity
  let maxZ = -Infinity
  for (const p of points) {
    const [x, z] = project(p.lat, p.lon)
    minX = Math.min(minX, x)
    maxX = Math.max(maxX, x)
    minZ = Math.min(minZ, z)
    maxZ = Math.max(maxZ, z)
  }
  return [minX, minZ, maxX, maxZ]
}

/** 视野半边长（米）：超出它的空域不画。
 *
 * <p>取值比"可见范围"大 1.5 倍：航路绕飞的那个禁飞区往往就压在画面边缘外一点，
 * 如果严格按可见范围剔除，用户会看到"航路莫名其妙拐了个弯"却找不到原因。
 */
function viewRadius(): number {
  return spanM * 1.5
}

function inView(box: [number, number, number, number]): boolean {
  const r = viewRadius()
  return box[0] < r && box[2] > -r && box[1] < r && box[3] > -r
}

// ---------------------------------------------------------------------------
// 静态内容
// ---------------------------------------------------------------------------

const ZONE_COLOR: Record<ZoneType, number> = {
  NO_FLY: 0xff4d4f,
  RESTRICTED: 0xfa8c16,
  BUILDING: 0x64748b,
  CORRIDOR: 0x52c41a,
  WARNING: 0xfaad14,
}

/** 空域体块：建筑障碍画成实心灰块（可绕可越），禁飞/限飞等画成半透明彩色体块（不可进的空层） */
function buildZoneMesh(zone: AirspaceZone): THREE.Object3D | null {
  const minAlt = Number(zone.minAltitudeM ?? 0)
  const maxAlt = Number(zone.maxAltitudeM ?? 120)
  const heightM = Math.max(2, (maxAlt - minAlt) * VERTICAL_EXAGGERATION)
  const baseY = minAlt * VERTICAL_EXAGGERATION
  const isBuilding = zone.zoneType === 'BUILDING'
  const color = ZONE_COLOR[zone.zoneType] ?? 0xfaad14

  const group = new THREE.Group()

  if (zone.shapeType === 'CIRCLE' && zone.center) {
    const [cx, cz] = project(zone.center.lat, zone.center.lon)
    const r = Math.max(30, Number(zone.radiusM ?? 0))
    const mesh = new THREE.Mesh(
      new THREE.CylinderGeometry(r, r, heightM, 40, 1, false),
      new THREE.MeshStandardMaterial({
        color,
        roughness: isBuilding ? 0.85 : 0.6,
        metalness: 0.05,
        transparent: !isBuilding,
        opacity: isBuilding ? 1 : 0.15,
        side: THREE.DoubleSide,
        depthWrite: isBuilding,
      }),
    )
    mesh.position.set(cx, baseY + heightM / 2, cz)
    group.add(mesh)
    return group
  }

  const points = zone.points ?? []
  if (points.length < 3) return null
  const shape = new THREE.Shape(
    points.map((p) => {
      const [x, z] = project(p.lat, p.lon)
      // Shape 在 XY 平面里构造，稍后绕 X 轴转 -90° 摊到 XZ 地面：y 先取 -z，转完正好回来
      return new THREE.Vector2(x, -z)
    }),
  )
  const geometry = new THREE.ExtrudeGeometry(shape, { depth: heightM, bevelEnabled: false })
  geometry.rotateX(-Math.PI / 2)

  const mesh = new THREE.Mesh(
    geometry,
    new THREE.MeshStandardMaterial({
      color,
      roughness: isBuilding ? 0.85 : 0.6,
      metalness: 0.05,
      transparent: !isBuilding,
      opacity: isBuilding ? 1 : 0.15,
      side: THREE.DoubleSide,
      depthWrite: isBuilding,
    }),
  )
  mesh.position.y = baseY
  group.add(mesh)

  // 建筑加描边：挤出的盒子没有边框会糊成一团，看不清楼群的分界
  if (isBuilding) {
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(geometry, 30),
      new THREE.LineBasicMaterial({ color: 0x334155, transparent: true, opacity: 0.5 }),
    )
    edges.position.y = baseY
    group.add(edges)
  }
  return group
}

function rebuildStatic(): void {
  const world = worldGroup
  if (!scene || !world) return

  if (groundGroup) {
    world.remove(groundGroup)
    disposeObject(groundGroup)
  }
  if (zoneGroup) {
    world.remove(zoneGroup)
    disposeObject(zoneGroup)
  }
  if (routeGroup) {
    world.remove(routeGroup)
    disposeObject(routeGroup)
  }
  if (pinGroup) {
    world.remove(pinGroup)
    disposeObject(pinGroup)
  }
  if (stationGroup) {
    world.remove(stationGroup)
    disposeObject(stationGroup)
  }

  // ---- 地面 + 网格 ----
  groundGroup = new THREE.Group()
  const groundSize = Math.max(4000, spanM * GROUND_SCALE)
  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(groundSize, groundSize),
    // 深色地面：与平面地图的深色底图统一，3D 里的彩色航路/告警才够跳
    new THREE.MeshStandardMaterial({ color: 0x0d1e2f, roughness: 0.95 }),
  )
  ground.rotation.x = -Math.PI / 2
  groundGroup.add(ground)

  const grid = new THREE.GridHelper(groundSize, 40, 0x1f4d6b, 0x13324a)
  grid.position.y = 0.4
  groundGroup.add(grid)

  // 原点十字：给"我现在看在哪儿"一个参照
  const axis = new THREE.LineSegments(
    new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-groundSize / 2, 0.6, 0),
      new THREE.Vector3(groundSize / 2, 0.6, 0),
    ]),
    new THREE.LineBasicMaterial({ color: 0x94a3b8, transparent: true, opacity: 0.45 }),
  )
  groundGroup.add(axis)
  world.add(groundGroup)

  // ---- 空域体块 ----
  zoneGroup = new THREE.Group()
  props.zones.forEach((zone) => {
    const box = zoneBounds(zone)
    if (!box || !inView(box)) return
    const mesh = buildZoneMesh(zone)
    if (mesh) zoneGroup!.add(mesh)
  })
  world.add(zoneGroup)

  // ---- 计划航路（抬到巡航高度，与地面拉开层次）----
  routeGroup = new THREE.Group()
  props.routes.forEach((route) => {
    if (route.points.length < 2) return
    const pts = route.points.map(([lat, lon]) => {
      const [x, z] = project(lat, lon)
      return new THREE.Vector3(x, ROUTE_ALT_M * VERTICAL_EXAGGERATION, z)
    })
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(pts),
      new THREE.LineBasicMaterial({ color: 0x1677ff, transparent: true, opacity: 0.75 }),
    )
    routeGroup!.add(line)
  })
  world.add(routeGroup)

  // ---- 订单信标：取货点蓝色矮台、目的地按状态着色的光柱 ----
  pinGroup = new THREE.Group()
  ;(props.orderPins ?? []).forEach((pin) => {
    const [px, pz] = project(pin.pickupLat, pin.pickupLon)
    const base = new THREE.Mesh(
      new THREE.CylinderGeometry(55, 55, 6, 20),
      new THREE.MeshStandardMaterial({ color: 0x1677ff, roughness: 0.7 }),
    )
    base.position.set(px, 3, pz)
    pinGroup!.add(base)

    const [dx, dz] = project(pin.dropoffLat, pin.dropoffLon)
    const color =
      pin.status === 'DELIVERED'
        ? 0x52c41a
        : pin.status === 'IN_FLIGHT' || pin.status === 'ASSIGNED'
          ? 0xff4d4f
          : pin.status === 'CANCELLED' || pin.status === 'FAILED'
            ? 0x8c8c8c
            : 0xfa8c16
    const pillar = new THREE.Mesh(
      new THREE.CylinderGeometry(9, 9, 90, 12),
      new THREE.MeshStandardMaterial({ color, transparent: true, opacity: 0.85 }),
    )
    pillar.position.set(dx, 45, dz)
    pinGroup!.add(pillar)

    const cap = new THREE.Mesh(
      new THREE.SphereGeometry(26, 18, 14),
      new THREE.MeshStandardMaterial({ color, roughness: 0.35, metalness: 0.2 }),
    )
    cap.position.set(dx, 96, dz)
    pinGroup!.add(cap)
  })
  world.add(pinGroup)

  // ---- 充电站：停机坪圆盘 + 灯柱，远端可见（长航程任务的中继点） ----
  stationGroup = new THREE.Group()
  const stationRadius = Math.max(120, DRONE_SIZE_M * 2.6)
  for (const station of props.stations ?? []) {
    const [sx, sz] = project(station.lat, station.lon)
    if (Math.abs(sx) > viewRadius() || Math.abs(sz) > viewRadius()) continue

    const pad = new THREE.Mesh(
      new THREE.CylinderGeometry(stationRadius, stationRadius, 8, 28),
      new THREE.MeshStandardMaterial({ color: 0xf0f5ff, roughness: 0.8 }),
    )
    pad.position.set(sx, 4, sz)
    stationGroup.add(pad)

    const ring = new THREE.Mesh(
      new THREE.RingGeometry(stationRadius * 0.72, stationRadius, 28),
      new THREE.MeshBasicMaterial({ color: 0x1677ff, transparent: true, opacity: 0.5, side: THREE.DoubleSide }),
    )
    ring.rotation.x = -Math.PI / 2
    ring.position.set(sx, 9, sz)
    stationGroup.add(ring)

    // 灯柱：站点在远处也要看得见，所以给一根比无人机更高的细柱
    const mast = new THREE.Mesh(
      new THREE.CylinderGeometry(10, 10, stationRadius * 1.5, 10),
      new THREE.MeshStandardMaterial({ color: 0x1677ff, roughness: 0.5, metalness: 0.3 }),
    )
    mast.position.set(sx, stationRadius * 0.75, sz)
    stationGroup.add(mast)

    const beacon = new THREE.Mesh(
      new THREE.SphereGeometry(18, 16, 12),
      new THREE.MeshBasicMaterial({ color: 0x69b1ff }),
    )
    beacon.position.set(sx, stationRadius * 1.5, sz)
    stationGroup.add(beacon)
  }
  world.add(stationGroup)
}

// ---------------------------------------------------------------------------
// 无人机
// ---------------------------------------------------------------------------

function createDroneVisual(modelCode?: string): DroneVisual {
  // 每架飞机独立造几何体：这样能直接拿到它的旋翼句柄（clone 出来的树里没有）。
  // 机型不同 → 机臂数量/机身尺寸不同，所以必须按 modelCode 造，不能共用一套。
  const parts = createDroneModel(modelCode)
  const bodyScale = parts.spec.sceneScale

  const halo = new THREE.Mesh(
    new THREE.RingGeometry(DRONE_SIZE_M * 0.7, DRONE_SIZE_M * 1.05, 32),
    new THREE.MeshBasicMaterial({
      color: 0x1677ff,
      transparent: true,
      opacity: 0.55,
      side: THREE.DoubleSide,
      depthWrite: false,
    }),
  )
  halo.rotation.x = -Math.PI / 2

  const tether = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]),
    new THREE.LineBasicMaterial({ color: 0x94a3b8, transparent: true, opacity: 0.35 }),
  )

  const root = new THREE.Group()
  // 机身按型号缩放：重载机真实尺寸更大，缩一点才不至于把画面撑爆
  parts.group.scale.setScalar(bodyScale)
  root.add(parts.group)

  return { root, rotors: parts.rotors, rotorDiscs: parts.rotorDiscs, halo, tether }
}

/** 把一架飞机从场景里摘掉并释放它的几何体/材质（机身 + 地面光环 + 高度投影线） */
function disposeDroneVisual(droneId: number, visual: DroneVisual): void {
  droneGroup?.remove(visual.root)
  droneGroup?.remove(visual.halo)
  droneGroup?.remove(visual.tether)
  disposeObject(visual.root)
  visual.halo.geometry.dispose()
  ;(visual.halo.material as THREE.Material).dispose()
  visual.tether.geometry.dispose()
  ;(visual.tether.material as THREE.Material).dispose()
  droneVisuals.delete(droneId)
}

/** 把最新一帧位姿画出来（由 DroneMap 的插值循环每帧调用） */
function updateFrames(frames: PoseFrame[]): void {
  // 必须存副本：调用方（DroneMap）复用一个数组并每帧清空，
  // 直接存引用会拿到空数组，换视野时就无法"用上一份位姿先画一遍"
  latestPoses = frames.slice()
  if (!scene) return

  if (!droneGroup) {
    droneGroup = new THREE.Group()
    // 挂在世界根节点下：无人机的高度（含地面海拔基准）由根节点统一抬升
    worldGroup?.add(droneGroup)
  }

  const seen = new Set<number>()
  for (const frame of frames) {
    if (!Number.isFinite(frame.lat) || !Number.isFinite(frame.lon)) continue
    seen.add(frame.droneId)

    let visual = droneVisuals.get(frame.droneId)
    if (!visual) {
      visual = createDroneVisual(frame.modelCode)
      visual.root.userData.droneId = frame.droneId
      droneGroup.add(visual.root)
      droneGroup.add(visual.halo)
      droneGroup.add(visual.tether)
      droneVisuals.set(frame.droneId, visual)
    }

    const [x, z] = project(frame.lat, frame.lon)
    const y = Math.max(1, frame.altitudeM) * VERTICAL_EXAGGERATION
    const headingRad = ((frame.headingDeg ?? 0) * Math.PI) / 180
    // 模型机头朝 +Z（南），航向 0° 指北（-Z），所以绕 Y 旋转 π - heading
    const selected = props.selectedDroneId === frame.droneId
    const scale = selected ? 1.35 : 1

    visual.root.position.set(x, y, z)
    visual.root.rotation.y = Math.PI - headingRad
    visual.root.scale.setScalar(scale)

    visual.halo.position.set(x, 1.2, z)
    const haloMat = visual.halo.material as THREE.MeshBasicMaterial
    haloMat.color.set(DRONE_STATUS_HEX[(frame.status as DroneStatus) ?? 'IDLE'] ?? '#1677ff')
    // 低电量闪一闪：与平面地图的红色脉冲圈语义一致
    const lowBattery = (frame.batteryPct ?? 100) < 20
    haloMat.opacity = lowBattery ? 0.35 + Math.abs(Math.sin(rotorPhase * 1.6)) * 0.5 : selected ? 0.85 : 0.5
    visual.halo.scale.setScalar(selected ? 1.35 : 1)

    const positions = visual.tether.geometry.attributes.position as THREE.BufferAttribute
    positions.setXYZ(0, x, 0, z)
    positions.setXYZ(1, x, y, z)
    positions.needsUpdate = true
    const tetherMat = visual.tether.material as THREE.LineBasicMaterial
    tetherMat.color.set(haloMat.color)
    tetherMat.opacity = selected ? 0.8 : 0.3

    // 旋翼转速随水平速度提高；旋翼模糊盘同步变浓
    const speed = 0.35 + Math.max(0, Math.min(1, frame.rotorLevel)) * 1.2
    visual.rotors.forEach((rotor, index) => {
      rotor.rotation.y = rotorPhase * speed * (index % 2 === 0 ? 1 : -1)
    })
    visual.rotorDiscs.forEach((disc) => {
      ;(disc.material as THREE.MeshBasicMaterial).opacity = 0.05 + frame.rotorLevel * 0.22
    })
  }

  // 清理已经消失的飞机
  droneVisuals.forEach((visual, droneId) => {
    if (seen.has(droneId)) return
    disposeDroneVisual(droneId, visual)
  })

  // 只统计"画在scene里"的飞机：数据里 26 架分布在四个城市，
  // 但立体场景一次只立一座城，报全量会让用户以为自己看漏了十几架
  const radius = viewRadius()
  let visible = 0
  droneVisuals.forEach((visual) => {
    const { x, z } = visual.root.position
    if (Math.abs(x) <= radius && Math.abs(z) <= radius) visible += 1
  })
  droneCount.value = visible
}

// ---------------------------------------------------------------------------
// 相机
// ---------------------------------------------------------------------------

function applyPreset(): void {
  if (!camera || !controls) return
  const distance = Math.max(900, Math.min(spanM * 1.1, 14000))
  // 相机高度要加上地面基准海拔：不加的话，成都/太原这类高海拔城市会"相机埋在地面下"
  const base = baseElevationM * VERTICAL_EXAGGERATION
  if (preset === 'top') {
    camera.position.set(0, base + distance * 1.35, 0.01)
  } else if (preset === 'close') {
    // 贴近：低角度近距离，看得清单架飞机的旋翼与它脚下的楼顶
    camera.position.set(distance * 0.17, base + distance * 0.13, distance * 0.22)
  } else {
    // 斜视：约 40° 俯角，城市与楼群层次最清楚
    camera.position.set(distance * 0.52, base + distance * 0.6, distance * 0.72)
  }
  controls.target.set(0, base + 120, 0)
  controls.update()
  presetApplied = true
}

function setPreset(kind: CameraPreset): void {
  preset = kind
  if (kind === 'follow') return
  applyPreset()
}

/**
 * 平移视角到相邻区域（给 HUD 的方向按钮与 WASD 用）。
 *
 * <p>为什么要有按钮/键盘而不只靠右键拖拽：右键拖拽是 OrbitControls 的默认手势，
 * 但"能拖动"这件事对用户来说完全不可见 —— 他只会觉得"这东西是死的、只能看一小片"。
 *
 * <p>按钮每次点击走一个固定步长（见 DroneMap 的 {@code PAN_STEP}），
 * 键盘则是长按后由渲染循环按时间逐帧累加（见 {@link flyByPressedKeys}）。
 *
 * @param xRatio 东西方向位移（+ 为东），单位为"视野跨度的比例"
 * @param zRatio 南北方向位移（+ 为南），单位为"视野跨度的比例"
 */
function panBy(xRatio: number, zRatio: number): void {
  if (!camera || !controls) return
  const dx = spanM * xRatio
  const dz = spanM * zRatio
  camera.position.x += dx
  camera.position.z += dz
  controls.target.x += dx
  controls.target.z += dz
  controls.update()
}

/** 跨度上下限（米）：太小看不到周边空域，太大楼与飞机都成了点 */
const MIN_SPAN_M = 3_000
const MAX_SPAN_M = 24_000

/**
 * 调整视野范围（"看更广 / 看更细"）。
 *
 * <p>与平移互补：平移解决"看不到别的区域"，范围解决"只能看到一小片"。
 * 两个都做成按钮而不是手势，是因为立体视图没有平面地图那样的缩放手势语义
 * （滚轮在 OrbitControls 里是"推拉相机"，不是"改变可见范围"）。
 */
function zoomSpan(factor: number): void {
  const next = Math.max(MIN_SPAN_M, Math.min(MAX_SPAN_M, spanM * factor))
  if (Math.abs(next - spanM) < 1) return
  frameArea(originLat, originLon, next)
}

/**
 * 立体视图里的"定位机队"：把相机收拢到当前场景内的飞机。
 *
 * <p>与平面地图的「定位机队」是同一个意图。这里不重新框定整个视野（那会丢掉
 * 用户已经调好的观察方向），只把轨道中心搬到机队质心、并按机队尺度收紧距离，
 * 观察方向（相机相对目标的向量）保持不变，操作不会"突然天旋地转"。
 */
function focusDrones(): void {
  if (!camera || !controls || droneVisuals.size === 0) return
  const box = new THREE.Box3()
  droneVisuals.forEach((visual) => box.expandByPoint(visual.root.position))
  if (box.isEmpty()) return

  const center = box.getCenter(new THREE.Vector3())
  // 机队位置是世界根节点的局部坐标，转成世界坐标要把地面基准海拔加回来，
  // 否则在高海拔城市（成都 / 太原）会把相机对到地下去
  center.y += baseElevationM * VERTICAL_EXAGGERATION
  const size = box.getSize(new THREE.Vector3())
  const radius = Math.max(400, size.length() * 0.6)
  const direction = camera.position.clone().sub(controls.target).normalize()
  controls.target.copy(center)
  camera.position.copy(center).addScaledVector(direction, radius * 1.7)
  controls.update()
}

/**
 * 按当前平面地图视野框定立体场景。
 *
 * @param centerLat 视野中心纬度
 * @param centerLon 视野中心经度
 * @param nextSpanM 视野跨度（米），决定地面尺寸与相机距离
 */
function frameArea(centerLat: number, centerLon: number, nextSpanM: number): void {
  originLat = centerLat
  originLon = centerLon
  // 跨度上限见 MAX_SPAN_M：一条跨城航路就有 12km，立起来时楼与飞机全成了点，
  // 所以宁可只立"一座城的核心区"，用户想看得更广可以自己按「范围 +」
  spanM = Math.max(MIN_SPAN_M, Math.min(nextSpanM, MAX_SPAN_M))
  presetApplied = false
  // 换城市时地面基准海拔随之变化（上海 4m / 成都 500m / 太原 780m）：
  // 先把海拔与相机同步好，再重建静态内容与无人机，避免出现"先建完再抬升"的穿模
  applyElevation(centerLat, centerLon)
  // 视野换了原点，已有飞机的坐标全部失效，整体清掉等下一帧重建
  droneVisuals.forEach((visual, droneId) => disposeDroneVisual(droneId, visual))
  droneCount.value = 0
  syncCameraScale()
  rebuildStatic()
  applyPreset()
  updateCenterLabel()
  // 视角变了，等下一帧数据到来前先把已有位姿重画一遍，避免出现"空场"
  if (latestPoses.length > 0) updateFrames(latestPoses)
}

/**
 * 跨度变化后同步相机与雾效的量级。
 *
 * <p>这三样都跟"场景有多大"绑死，而跨度会随视野变化，建场景时算一次是不够的：
 * 否则要么远处的东西被裁掉（far 太小）、要么滚轮能一路缩到场景外（maxDistance 太大）、
 * 要么雾把整座城糊掉（fog 是按旧跨度算的）。
 */
function syncCameraScale(): void {
  if (camera) {
    camera.far = spanM * 10
    camera.near = Math.max(1, spanM * 0.002)
    camera.updateProjectionMatrix()
  }
  if (controls) {
    controls.maxDistance = spanM * 5
    // 缩放下限跟着跨度走：跨到 12km 时不该还能怼到楼里去
    controls.minDistance = Math.max(30, spanM * 0.02)
  }
  if (scene) {
    scene.fog = new THREE.Fog(0x07131f, spanM * 0.8, spanM * 2.6)
  }
}

// ---------------------------------------------------------------------------
// 键盘移动（WASD 长按连续飞行）
// ---------------------------------------------------------------------------

/** WASD → 平移方向：W 向北、S 向南、A 向西、D 向东 */
const KEY_PAN: Record<string, [number, number]> = {
  w: [0, -1],
  s: [0, 1],
  a: [-1, 0],
  d: [1, 0],
}

/**
 * 按住方向键时每秒平移的跨度比例。
 *
 * <p><b>为什么按"按住"而不是"每按一下走一步"</b>：方向键是会被操作系统自动重复触发的，
 * 若每次 keydown 都按按钮那样的步长（0.9 跨度）走，长按一秒就会窜出去十几个视野，
 * 完全失控。所以改成：keydown 只登记"这个方向被按住"，真正的位移由渲染循环每帧按
 * <b>时间</b>累加（与显示器刷新率无关）。0.35 ≈ 长按 3 秒穿过一整屏，符合"驾驶舱"的手感。
 */
const KEY_FLY_SPAN_PER_SEC = 0.35

/** 当前被按住的键（松开会移除；窗口失焦时整体清空，避免按键"卡住"） */
const pressedKeys = new Set<string>()

/**
 * 键盘"飞行"：WASD 平移视角，Q/E 升降范围。
 *
 * <p><b>为什么值得做</b>：立体视图本质上是个"驾驶舱"，而 WASD 是所有人对三维场景的
 * 默认操作直觉。只给按钮的话，用户得一次次点；给了键盘才能在场景里连续"飞"。
 *
 * <p>两条必须遵守的边界：
 * <ol>
 *   <li><b>只在立体视图激活时生效</b>（{@code props.active}）——切回平面视图后
 *       按键不该再偷偷动 3D 场景；</li>
 *   <li><b>绝不抢输入框的按键</b>：焦点在 input/textarea/select/contenteditable 上时直接返回，
 *       否则用户在别的页面输入 "w" 会把地图挪走。带 Ctrl/Alt/Meta 的组合键也放过
 *       （那是浏览器/系统快捷键）。</li>
 * </ol>
 */
function onKeyDown(event: KeyboardEvent): void {
  if (event.ctrlKey || event.altKey || event.metaKey) return
  const target = event.target as HTMLElement | null
  const tag = target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) return

  const key = event.key.toLowerCase()
  if (KEY_PAN[key]) {
    if (!props.active) return
    event.preventDefault()
    pressedKeys.add(key)
    return
  }
  if (!props.active) return
  // Q/E 是"离散一步"，按住连发也是可接受的（范围有上下限，不会被无限推出去）
  if (key === 'q') {
    event.preventDefault()
    zoomSpan(0.7)
  } else if (key === 'e') {
    event.preventDefault()
    zoomSpan(1.45)
  }
}

function onKeyUp(event: KeyboardEvent): void {
  pressedKeys.delete(event.key.toLowerCase())
}

/** 窗口失焦 / 切到后台时清空按住的键：否则回来会发现视角自己在飞 */
function clearPressedKeys(): void {
  pressedKeys.clear()
}

/** 按当前按住的方向飞一小段（在渲染循环里调用，位移与时间成正比） */
function flyByPressedKeys(dt: number): void {
  if (pressedKeys.size === 0) return
  let x = 0
  let z = 0
  pressedKeys.forEach((key) => {
    const pan = KEY_PAN[key]
    if (!pan) return
    x += pan[0]
    z += pan[1]
  })
  // 同时按住相反方向（W+S）时互相抵消，不动
  if (x === 0 && z === 0) return
  panBy(x * KEY_FLY_SPAN_PER_SEC * dt, z * KEY_FLY_SPAN_PER_SEC * dt)
}

// ---------------------------------------------------------------------------
// 点击选中：射线拾取最近的无人机
// ---------------------------------------------------------------------------

const raycaster = new THREE.Raycaster()

function onCanvasClick(event: MouseEvent): void {
  if (!camera || !renderer) return
  const rect = renderer.domElement.getBoundingClientRect()
  raycaster.setFromCamera(
    new THREE.Vector2(
      ((event.clientX - rect.left) / rect.width) * 2 - 1,
      -((event.clientY - rect.top) / rect.height) * 2 + 1,
    ),
    camera,
  )
  const roots = Array.from(droneVisuals.values()).map((v) => v.root)
  const hits = raycaster.intersectObjects(roots, true)
  for (const hit of hits) {
    let node: THREE.Object3D | null = hit.object
    while (node && node.userData.droneId === undefined) node = node.parent
    if (node?.userData.droneId !== undefined) {
      emit('select-drone', node.userData.droneId as number)
      return
    }
  }
}

// ---------------------------------------------------------------------------
// 渲染循环
// ---------------------------------------------------------------------------

function resize(): void {
  if (!hostEl.value || !renderer || !camera) return
  const w = hostEl.value.clientWidth
  const h = hostEl.value.clientHeight
  if (w <= 0 || h <= 0) return
  // 必须让 setSize 同时更新 canvas 的 CSS 尺寸（第三个参数保持默认 true）：
  // 高分屏下内部分辨率是 CSS 尺寸的 devicePixelRatio 倍，不写样式的话
  // canvas 会以"内部像素数"作为 CSS 尺寸显示，直接撑破容器、画面被裁掉一角
  renderer.setSize(w, h)
  camera.aspect = w / h
  camera.updateProjectionMatrix()
}

function tick(ts: number): void {
  rafId = requestAnimationFrame(tick)
  if (!props.active) return
  // 与平面地图一样限到 ~30fps：数据每秒才更新一次，60fps 只是白烧 GPU
  if (ts - lastTs < 33) return
  const dt = Math.min((ts - lastTs) / 1000, 0.2)
  lastTs = ts
  rotorPhase += dt * 16

  if (!presetApplied) applyPreset()

  // 键盘"飞行"：按住 WASD 时每帧走一小段（与时间成正比，与刷新率无关）
  flyByPressedKeys(dt)

  // 「跟随选中无人机」：把相机目标缓慢贴过去，飞机飞出画面也能跟上
  if (preset === 'follow' && controls) {
    const selected = props.selectedDroneId ? droneVisuals.get(props.selectedDroneId) : null
    if (selected) controls.target.lerp(selected.root.position, 0.12)
  }

  // 视角中心漂出去了（用户平移，或跟随的飞机飞远了）→ 换原点、把新区域的空域与航路加载进来。
  // 没有这一步，立体视图就是一块固定大小的板子，往边上拖两下就只剩空白网格。
  if (controls && Math.hypot(controls.target.x, controls.target.z) > spanM * RECENTER_DRIFT_RATIO) {
    const [lat, lon] = unproject(controls.target.x, controls.target.z)
    recenterTo(lat, lon)
  }

  controls?.update()
  // HUD 的视野中心读数逐帧跟随（只有文字变了才写回，见 updateCenterLabel）
  updateCenterLabel()
  if (renderer && scene && camera) renderer.render(scene, camera)
}

onMounted(() => {
  if (!hostEl.value) return

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x07131f)

  camera = new THREE.PerspectiveCamera(45, 1, 1, spanM * 10)
  camera.position.set(3000, 3400, 4200)

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  hostEl.value.appendChild(renderer.domElement)
  renderer.domElement.addEventListener('click', onCanvasClick)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  // 不让相机钻到地面以下：地下视角只会看到一片空
  controls.maxPolarAngle = Math.PI / 2 - 0.04

  // 所有可见内容都挂在这个根节点下（见 worldGroup 的说明）
  worldGroup = new THREE.Group()
  scene.add(worldGroup)

  scene.add(new THREE.HemisphereLight(0xdfefff, 0x0a2036, 0.95))
  const sun = new THREE.DirectionalLight(0xffffff, 1.5)
  sun.position.set(spanM * 0.6, spanM * 0.9, spanM * 0.4)
  scene.add(sun)
  const fill = new THREE.DirectionalLight(0xffffff, 0.35)
  fill.position.set(-spanM * 0.5, spanM * 0.4, -spanM * 0.5)
  scene.add(fill)

  // 相机量级（far / 雾 / 缩放范围）统一由 syncCameraScale 按当前跨度给出
  syncCameraScale()
  applyElevation(originLat, originLon)
  rebuildStatic()
  applyPreset()

  // 键盘在"窗口级"监听：立体视图不是输入控件，用户不会先去点它再按键。
  // 靠 onKeyDown 里的白名单（不吃输入框按键）来保证不干扰页面其它输入。
  // keyup 与失焦都要收：只登记按住的方向键，松开/切走就必须停。
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
  window.addEventListener('blur', clearPressedKeys)

  resize()
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(hostEl.value)
  ready.value = true
  tick(performance.now())
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
  window.removeEventListener('blur', clearPressedKeys)
  pressedKeys.clear()
  resizeObserver?.disconnect()
  resizeObserver = null
  cancelAnimationFrame(rafId)
  renderer?.domElement.removeEventListener('click', onCanvasClick)
  controls?.dispose()
  droneVisuals.clear()
  if (scene) disposeObject(scene)
  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null
  scene = null
  camera = null
})

// 数据变化就重建静态内容（空域/航路/订单/充电站都是低频更新）
watch(() => [props.zones, props.routes, props.orderPins, props.stations], () => {
  if (ready.value) rebuildStatic()
}, { deep: true })

watch(() => props.active, (active) => {
  if (!active) return
  // 从隐藏切回来时容器尺寸变化不一定会触发 ResizeObserver，等 DOM 显示完再量一次
  lastTs = performance.now()
  void nextTick(() => resize())
})

defineExpose({
  frameArea: (lat: number, lon: number, span: number) => frameArea(lat, lon, span),
  updateFrames: (frames: PoseFrame[]) => updateFrames(frames),
  setPreset: (kind: CameraPreset) => setPreset(kind),
  focusDrones: () => focusDrones(),
  panBy: (xRatio: number, zRatio: number) => panBy(xRatio, zRatio),
  zoomSpan: (factor: number) => zoomSpan(factor),
})
</script>

<template>
  <div class="scene3d">
    <div ref="hostEl" class="scene3d-canvas" :style="{ height }"></div>

    <div class="scene3d-hud">
      <div class="scene3d-title">{{ areaLabel || '立体视图' }}</div>
      <div class="scene3d-meta">
        画面内 {{ droneCount }} 架 · 高度与楼高已放大 {{ VERTICAL_EXAGGERATION }}× 便于观察
      </div>
      <div v-if="elevationLabel" class="scene3d-meta">{{ elevationLabel }}</div>
      <div v-if="centerLabel" class="scene3d-meta">{{ centerLabel }}</div>
      <div class="scene3d-meta scene3d-meta-sub">
        <span class="scene3d-key">W</span><span class="scene3d-key">A</span><span class="scene3d-key">S</span><span class="scene3d-key">D</span> 按住移动 ·
        <span class="scene3d-key">Q</span><span class="scene3d-key">E</span> 改范围 · 拖拽旋转 · 滚轮推拉 · 点击飞机可选中
      </div>
    </div>
  </div>
</template>

<style scoped>
.scene3d {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #07131f;
}

.scene3d-canvas {
  width: 100%;
  height: 100%;
}

.scene3d-hud {
  position: absolute;
  right: 12px;
  top: 12px;
  z-index: 400;
  padding: 7px 10px;
  border-radius: 6px;
  background: rgba(7, 20, 34, 0.9);
  box-shadow:
    0 0 0 1px rgba(34, 211, 238, 0.18),
    0 6px 18px rgba(0, 0, 0, 0.45);
  font-size: 12px;
  line-height: 1.7;
  pointer-events: none;
}

.scene3d-title {
  font-weight: 600;
  color: #4fc3ff;
}

.scene3d-meta {
  color: rgba(200, 224, 245, 0.72);
}

.scene3d-meta-sub {
  color: rgba(180, 208, 232, 0.5);
}

/* 键位提示里的单键小方块：让"这些字母是可按的键"一眼可辨，而不是正文里的字 */
.scene3d-key {
  display: inline-block;
  min-width: 14px;
  margin-right: 1px;
  padding: 0 3px;
  border: 1px solid rgba(34, 211, 238, 0.28);
  border-radius: 3px;
  background: rgba(34, 211, 238, 0.1);
  font-weight: 600;
  text-align: center;
  color: #9fe6ff;
}
</style>
