import L from 'leaflet'

/**
 * 程序化生成的「真实城市路网」图层（离线、自包含、确定性）。
 *
 * 为什么不用 OpenStreetMap 等在线瓦片？见 README 第 6 节：
 * 内网 / 断网 / 瓦片服务不可达时，在线底图就是一片空白，用户看到的就是「没有地图」。
 * 仿真平台必须自包含。
 *
 * 但「离线」不代表「假」。上一版把路网画成了**规则经纬网格**——每一格一样大、
 * 每条路一样直，一眼就是「草稿纸」而不是城市。这一版按真实城市的形态来生成：
 *
 *  1. **环形放射骨架**：3 条环线（内环 / 中环 / 外环）+ 13 条放射主干道，
 *     从城市中心向外辐射，带轻微椭圆、摆动与偏转 —— 这是上海最醒目的地貌特征，
 *     也是「一眼看出这是真实城市」的关键。
 *  2. **高速 / 高架**：环线与部分放射线用暖黄色（真实地图上高速/高架的惯例配色）区分层级。
 *  3. **蜿蜒水系**：黄浦江（宽 620m，南→北穿城后在陆家嘴东折）+ 苏州河 +
 *     两条运河 + 湖泊，用真实尺度的线宽绘制；道路遇到水面会**断开**（相当于桥梁之外不能过河）。
 *  4. **不规则次干道 / 支路**：不再是等距网格，而是「每条线按哈希抖动位置 + 偶发缺线」，
 *     于是街区大小天然参差、有断头路、有斜切 — 这才是城市肌理。
 *     三级路网（主干 / 次干 / 支路）与街区色块**全部由同一个基准格派生**，
 *     所以粗细路网互相咬合，不会出现「大路从街区中间横穿」的假象。
 *  5. **街区填充**：在基准格单元里填极浅的色块，路网像「切」出来的。
 *
 * 三个工程约束：
 *  • **确定性**：所有随机量都来自经纬度的稳定哈希，绝不用 `Math.random()`。
 *    瓦片会被反复创建（拖动、缩放），随机数会让同一块地皮不停变色，看起来像画面在闪。
 *  • **渐进细节**：z≥9 只见骨架路网，z≥11 加主干网格，z≥13 加次干道与街区，z≥15 加支路。
 *  • **离线**：不请求任何外部服务，同一经纬度永远渲染同一画面，便于截图与论文配图。
 */

const TILE_SIZE = 256

/** 地球周长的一半（Web Mercator 在 0 级时的世界像素宽度） */
const MERCATOR_WORLD = 2 * Math.PI * 6378137

/** 城市几何中心（上海人民广场附近）：所有环线与放射主干道都以它为原点 */
const CITY_LAT = 31.2304
const CITY_LON = 121.4737

/** 1 度纬度 / 赤道 1 度经度对应的米数（与后端 GeoUtils 取同一套近似值） */
const M_PER_DEG_LAT = 111_132.0
const M_PER_DEG_LON_EQUATOR = 111_320.0

/** 城市路网只画在这个经纬度框里；框外保持「省级边界 + 海面」的省级视角 */
const URBAN = { south: 31.0, north: 31.46, west: 121.14, east: 121.94 }

/**
 * 基准格距（度）：0.00125° 纬度 ≈ 139m。
 *
 * **这是整套路网的"原子"**：次干道、支路、街区色块全部由这个基准格按整数倍派生，
 * 于是粗细两级路网天然对齐（粗路的每一段都正好压在细路的某条格线上）。
 * 如果各级各用一套抖动，就会出现"粗路从街区中间横穿过去"的假象 ——
 * 真实城市的路网层级一定是互相咬合的。
 */
const LATTICE_STEP = 0.00125

/** 路网层级 = 基准格的整数倍：2 → 约 278m，4 → 约 556m */
const LEVEL_FINE = 1
const LEVEL_MEDIUM = 2
const LEVEL_COARSE = 4

/** 抖动幅度（相对基准格的比例）。太小还是网格，太大就不像城市了 */
const JITTER_RATIO = 0.55

function metersPerDegLon(lat: number): number {
  return M_PER_DEG_LON_EQUATOR * Math.cos((lat * Math.PI) / 180)
}

/** 以城市中心为原点，「向东 x 米 / 向北 y 米」→ 经纬度 */
function offsetLatLon(xM: number, yM: number): [number, number] {
  return [CITY_LAT + yM / M_PER_DEG_LAT, CITY_LON + xM / metersPerDegLon(CITY_LAT)]
}

// ---------------------------------------------------------------------------
// 稳定哈希（替代随机数）
// ---------------------------------------------------------------------------

/** 一维稳定哈希 → [0,1) */
function hash1(n: number): number {
  let h = Math.imul(n ^ 0x9e3779b9, 0x85ebca6b)
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

/** 二维稳定哈希 → [0,1)（街区配色用） */
function hash2(x: number, y: number): number {
  let h = Math.imul(x, 374761393) + Math.imul(y, 668265263)
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

// ---------------------------------------------------------------------------
// 城市骨架：环线 + 放射主干道
// ---------------------------------------------------------------------------

interface RingSpec {
  radiusM: number
  aspect: number
  rotationDeg: number
  wobble: number
  phase: number
  expressway: boolean
}

/** 环线：越靠外的环越「扁」且越宽，模拟真实城市的放射不均衡 */
const RINGS: RingSpec[] = [
  { radiusM: 4200, aspect: 1.05, rotationDeg: 20, wobble: 0.045, phase: 0.6, expressway: true },
  { radiusM: 9200, aspect: 1.1, rotationDeg: 6, wobble: 0.038, phase: 2.1, expressway: true },
  { radiusM: 16500, aspect: 1.16, rotationDeg: -8, wobble: 0.032, phase: 3.4, expressway: true },
  { radiusM: 27500, aspect: 1.08, rotationDeg: 3, wobble: 0.028, phase: 5.0, expressway: false },
]

interface RadialSpec {
  bearingDeg: number
  innerM: number
  lenM: number
  bendM: number
  bendPhase: number
  driftDeg: number
  expressway: boolean
}

/** 放射主干道：方位角均匀铺开但长短不一、摆动幅度不一，避免「均匀发散」的机械感 */
const RADIALS: RadialSpec[] = [
  { bearingDeg: 6, innerM: 1500, lenM: 46000, bendM: 900, bendPhase: 0.4, driftDeg: 7, expressway: true },
  { bearingDeg: 34, innerM: 1600, lenM: 38000, bendM: 700, bendPhase: 1.3, driftDeg: -5, expressway: false },
  { bearingDeg: 62, innerM: 1400, lenM: 34000, bendM: 800, bendPhase: 2.0, driftDeg: 6, expressway: true },
  { bearingDeg: 88, innerM: 1700, lenM: 36000, bendM: 650, bendPhase: 2.9, driftDeg: -4, expressway: false },
  { bearingDeg: 118, innerM: 1500, lenM: 42000, bendM: 900, bendPhase: 3.6, driftDeg: 5, expressway: true },
  { bearingDeg: 146, innerM: 1600, lenM: 33000, bendM: 600, bendPhase: 4.2, driftDeg: -6, expressway: false },
  { bearingDeg: 172, innerM: 1500, lenM: 30000, bendM: 700, bendPhase: 5.1, driftDeg: 4, expressway: false },
  { bearingDeg: 200, innerM: 1500, lenM: 34000, bendM: 800, bendPhase: 0.9, driftDeg: -5, expressway: false },
  { bearingDeg: 228, innerM: 1600, lenM: 40000, bendM: 900, bendPhase: 1.8, driftDeg: 6, expressway: true },
  { bearingDeg: 256, innerM: 1400, lenM: 36000, bendM: 650, bendPhase: 2.6, driftDeg: -4, expressway: false },
  { bearingDeg: 286, innerM: 1500, lenM: 38000, bendM: 700, bendPhase: 3.3, driftDeg: 5, expressway: false },
  { bearingDeg: 316, innerM: 1500, lenM: 32000, bendM: 600, bendPhase: 4.6, driftDeg: -6, expressway: false },
  { bearingDeg: 340, innerM: 1600, lenM: 36000, bendM: 750, bendPhase: 5.6, driftDeg: 4, expressway: false },
]

interface Arterial {
  points: Array<[number, number]>
  expressway: boolean
}

let arterialCache: Arterial[] | null = null

/**
 * 生成环线与放射主干道（惰性缓存：整张图只算一次）。
 * 所有摆动都是固定相位的正弦，因此结果稳定可复现。
 */
function buildArterials(): Arterial[] {
  if (arterialCache) {
    return arterialCache
  }
  const roads: Arterial[] = []

  RINGS.forEach((ring) => {
    const points: Array<[number, number]> = []
    const steps = 120
    const rot = (ring.rotationDeg * Math.PI) / 180
    for (let i = 0; i <= steps; i++) {
      const t = (i / steps) * Math.PI * 2
      const wobble =
        1 + ring.wobble * Math.sin(3 * t + ring.phase) + ring.wobble * 0.55 * Math.sin(5 * t + ring.phase * 1.7)
      const r = ring.radiusM * wobble
      const x = Math.cos(t) * r * ring.aspect
      const y = Math.sin(t) * r
      points.push(offsetLatLon(x * Math.cos(rot) - y * Math.sin(rot), x * Math.sin(rot) + y * Math.cos(rot)))
    }
    roads.push({ points, expressway: ring.expressway })
  })

  RADIALS.forEach((spec) => {
    const points: Array<[number, number]> = []
    const steps = 48
    for (let i = 0; i <= steps; i++) {
      const s = i / steps
      const r = spec.innerM + (spec.lenM - spec.innerM) * s
      // 侧向摆动：让主干道走「弧线」而不是笔直射线
      const lateral = spec.bendM * Math.sin(s * Math.PI * (0.9 + spec.bendPhase * 0.1))
      const bearing = spec.bearingDeg + spec.driftDeg * s
      const dir = (bearing * Math.PI) / 180
      const x = Math.sin(dir) * r + Math.cos(dir) * lateral
      const y = Math.cos(dir) * r - Math.sin(dir) * lateral
      points.push(offsetLatLon(x, y))
    }
    roads.push({ points, expressway: spec.expressway })
  })

  arterialCache = roads
  return roads
}

// ---------------------------------------------------------------------------
// 水系与绿地
// ---------------------------------------------------------------------------

interface Waterway {
  points: Array<[number, number]>
  widthM: number
}

/** 河流 / 运河：按真实尺度的线宽绘制（黄浦江约 600m，苏州河约 90m） */
const WATERWAYS: Waterway[] = [
  // 黄浦江：自南向北穿过市区，在陆家嘴一带东折，最后汇入长江口
  {
    widthM: 620,
    points: [
      [30.98, 121.53], [31.06, 121.505], [31.13, 121.492], [31.18, 121.487], [31.215, 121.492],
      [31.245, 121.503], [31.27, 121.512], [31.3, 121.505], [31.345, 121.512], [31.39, 121.53],
      [31.44, 121.56], [31.5, 121.62],
    ],
  },
  // 苏州河：自西向东在外滩汇入黄浦江
  {
    widthM: 95,
    points: [
      [31.246, 121.478], [31.249, 121.44], [31.253, 121.408], [31.257, 121.372],
      [31.262, 121.335], [31.268, 121.3],
    ],
  },
  // 川杨河：向东南出城
  { widthM: 70, points: [[31.2, 121.49], [31.175, 121.55], [31.155, 121.61], [31.14, 121.67]] },
  // 蕰藻浜：向北
  { widthM: 80, points: [[31.3, 121.44], [31.33, 121.47], [31.37, 121.5], [31.41, 121.53]] },
]

/** 湖泊：经纬度矩形（示意形状，够用） */
const LAKES: Array<{ name: string; rect: [number, number, number, number] }> = [
  { name: '淀山湖', rect: [31.05, 120.92, 31.2, 121.08] },
  { name: '滴水湖', rect: [30.87, 121.9, 30.91, 121.96] },
]

/** 绿地公园：矩形（南纬, 西经, 北纬, 东经） */
const PARKS: Array<{ name: string; rect: [number, number, number, number] }> = [
  { name: '中央公园', rect: [31.218, 121.425, 31.232, 121.452] },
  { name: '滨江绿地', rect: [31.238, 121.505, 31.248, 121.525] },
  { name: '世纪公园', rect: [31.205, 121.545, 31.222, 121.565] },
  { name: '西郊苗圃', rect: [31.178, 121.318, 31.196, 121.352] },
  { name: '东滩湿地', rect: [31.132, 121.752, 31.158, 121.792] },
  { name: '共青森林公园', rect: [31.305, 121.535, 31.32, 121.56] },
]

/**
 * 机场：画一条跑道 + 一条滑行道，用来解释「为什么这里有禁飞区」。
 * 跑道方向与真实机场大致一致，纯示意。
 */
const AIRPORTS: Array<{ name: string; center: [number, number]; bearingDeg: number; runwayM: number }> = [
  { name: '虹桥机场', center: [31.1979, 121.3363], bearingDeg: 176, runwayM: 3400 },
  { name: '浦东机场', center: [31.1443, 121.8083], bearingDeg: 167, runwayM: 4000 },
]

/** 街区填充色：按格子哈希在几个深色阶里选，形成暗色底图上的「城市肌理」 */
const BLOCK_TONES = ['#0c1c2c', '#0a1826', '#0e2133', '#091522', '#0b1a29']

/** 水面 / 绿地包络盒：瓦片不与它相交时，路网就不用做「打洞」判定（性能） */
let hazardBox: { south: number; north: number; west: number; east: number } | null = null

function getHazardBox() {
  if (hazardBox) {
    return hazardBox
  }
  let south = Number.POSITIVE_INFINITY
  let north = Number.NEGATIVE_INFINITY
  let west = Number.POSITIVE_INFINITY
  let east = Number.NEGATIVE_INFINITY
  const consider = ([lat, lon]: [number, number]) => {
    south = Math.min(south, lat)
    north = Math.max(north, lat)
    west = Math.min(west, lon)
    east = Math.max(east, lon)
  }
  WATERWAYS.forEach((w) => w.points.forEach(consider))
  LAKES.forEach((l) => {
    consider([l.rect[0], l.rect[1]])
    consider([l.rect[2], l.rect[3]])
  })
  PARKS.forEach((p) => {
    consider([p.rect[0], p.rect[1]])
    consider([p.rect[2], p.rect[3]])
  })
  hazardBox = { south, north, west, east }
  return hazardBox
}

/** 点到线段的近似距离（米）。几公里尺度下用局部等距投影足够准。 */
function segmentDistanceM(
  pLat: number, pLon: number,
  aLat: number, aLon: number,
  bLat: number, bLon: number,
): number {
  const scale = metersPerDegLon(pLat)
  const px = (pLon - aLon) * scale
  const py = (pLat - aLat) * M_PER_DEG_LAT
  const bx = (bLon - aLon) * scale
  const by = (bLat - aLat) * M_PER_DEG_LAT
  const len2 = bx * bx + by * by
  if (len2 <= 1e-9) {
    return Math.hypot(px, py)
  }
  let t = (px * bx + py * by) / len2
  t = t < 0 ? 0 : t > 1 ? 1 : t
  return Math.hypot(px - t * bx, py - t * by)
}

function isWater(lat: number, lon: number): boolean {
  for (const lake of LAKES) {
    if (lat >= lake.rect[0] && lat <= lake.rect[2] && lon >= lake.rect[1] && lon <= lake.rect[3]) {
      return true
    }
  }
  for (const waterway of WATERWAYS) {
    const half = waterway.widthM / 2
    for (let i = 1; i < waterway.points.length; i++) {
      const a = waterway.points[i - 1]
      const b = waterway.points[i]
      if (segmentDistanceM(lat, lon, a[0], a[1], b[0], b[1]) <= half) {
        return true
      }
    }
  }
  return false
}

function isPark(lat: number, lon: number): boolean {
  for (const park of PARKS) {
    if (lat >= park.rect[0] && lat <= park.rect[2] && lon >= park.rect[1] && lon <= park.rect[3]) {
      return true
    }
  }
  return false
}

/** 道路不该画到水面或绿地里面 */
function isBlockedGround(lat: number, lon: number): boolean {
  return isWater(lat, lon) || isPark(lat, lon)
}

// ---------------------------------------------------------------------------
// 抖动基准格：不规则次干道 / 支路 / 街区（三级共用同一套格线）
// ---------------------------------------------------------------------------

/** 第 i 条基准纬线的纬度：不再等距，街区大小自然参差 */
function latticeLat(i: number): number {
  return i * LATTICE_STEP + (hash1(i * 3.71 + 1.1) - 0.5) * LATTICE_STEP * JITTER_RATIO
}

/** 第 j 条基准经线的经度 */
function latticeLon(j: number): number {
  return j * LATTICE_STEP + (hash1(j * 5.17 + 2.3) - 0.5) * LATTICE_STEP * JITTER_RATIO
}

/**
 * 该基准格线是否「缺一条」。
 *
 * 同一个函数对粗细两级是一致的（粗线用的是同一个 i），所以不会出现
 * "细路在、粗路的位置却是空的"这种矛盾；约 12% 的缺线率制造出
 * 断头路与大小不一的街区 —— 这正是真实城市肌理最容易被忽略的特征。
 */
function latMissing(i: number): boolean {
  return hash1(i * 7.13 + 4.9) > 0.88
}

function lonMissing(j: number): boolean {
  return hash1(j * 9.31 + 6.1) > 0.88
}

// ---------------------------------------------------------------------------
// 投影与绘制工具
// ---------------------------------------------------------------------------

/** 给定缩放级别，1 像素代表多少米（Web Mercator 近似） */
function metersPerPixel(zoom: number, lat: number): number {
  return (MERCATOR_WORLD * Math.cos((lat * Math.PI) / 180)) / (TILE_SIZE * 2 ** zoom)
}

/** 把经纬度数组投影成当前瓦片内的画布坐标 */
function projectToTile(
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  points: Array<[number, number]>,
): Array<[number, number]> {
  return points.map(([lat, lon]) => {
    const p = map.project(L.latLng(lat, lon), zoom)
    return [p.x - originX, p.y - originY]
  })
}

/** 画一条折线（世界坐标 → 画布坐标） */
function strokePolyline(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  points: Array<[number, number]>,
  style: { color: string; width: number; dash?: number[]; lineCap?: CanvasLineCap },
) {
  const projected = projectToTile(map, zoom, originX, originY, points)
  if (projected.length < 2) {
    return
  }
  ctx.save()
  ctx.strokeStyle = style.color
  ctx.lineWidth = style.width
  ctx.lineCap = style.lineCap ?? 'round'
  ctx.lineJoin = 'round'
  if (style.dash) {
    ctx.setLineDash(style.dash)
  }
  ctx.beginPath()
  ctx.moveTo(projected[0][0], projected[0][1])
  for (let i = 1; i < projected.length; i++) {
    ctx.lineTo(projected[i][0], projected[i][1])
  }
  ctx.stroke()
  ctx.restore()
}

/** 画一个经纬度矩形（绿地 / 湖泊） */
function fillGeoRect(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  rect: [number, number, number, number],
  color: string,
) {
  const [south, west, north, east] = rect
  const nw = map.project(L.latLng(north, west), zoom)
  const se = map.project(L.latLng(south, east), zoom)
  ctx.fillStyle = color
  ctx.fillRect(nw.x - originX, nw.y - originY, se.x - nw.x, se.y - nw.y)
}

/** 道路样式：文字说明见文件头。宽度按真实尺度换算，并做「制图夸张」以便小比例尺可见。 */
const ROAD_STYLE: Record<
  'expressway' | 'arterial' | 'secondary' | 'minor',
  { casing: string; core: string; widthM: number; minPx: number; maxPx: number }
> = {
  expressway: { casing: '#6b4d1e', core: '#d8a447', widthM: 70, minPx: 1.8, maxPx: 7 },
  // 主干道用「更深的描边 + 偏亮的冷灰路面」：暗色陆地底上，
  // 路面必须比底图亮一档才立得住，描边则负责把相邻街区切开。
  arterial: { casing: '#071420', core: '#2f4f68', widthM: 45, minPx: 1.1, maxPx: 5 },
  secondary: { casing: '#06111c', core: '#27435a', widthM: 26, minPx: 0.9, maxPx: 3.2 },
  minor: { casing: '#050f18', core: '#1f374a', widthM: 14, minPx: 0.65, maxPx: 2.2 },
}

/** 按样式画一条「有描边」的道路：先画稍宽的描边，再画本色 —— 浅底上才立得住 */
function strokeRoad(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  points: Array<[number, number]>,
  kind: keyof typeof ROAD_STYLE,
) {
  const mpp = metersPerPixel(zoom, points[0][0])
  const style = ROAD_STYLE[kind]
  const core = Math.min(style.maxPx, Math.max(style.minPx, style.widthM / mpp))
  strokePolyline(ctx, map, zoom, originX, originY, points, { color: style.casing, width: core + 1.4 })
  strokePolyline(ctx, map, zoom, originX, originY, points, { color: style.core, width: core })
}

// ---------------------------------------------------------------------------
// 瓦片绘制
// ---------------------------------------------------------------------------

/** 画主干骨架（环线 + 放射），按层级用不同粗细与配色 */
function drawArterials(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  bounds: L.LatLngBounds,
) {
  buildArterials().forEach((road) => {
    // 粗筛：折线的外接矩形与瓦片不相交就跳过，省掉上万次投影
    let south = Number.POSITIVE_INFINITY
    let north = Number.NEGATIVE_INFINITY
    let west = Number.POSITIVE_INFINITY
    let east = Number.NEGATIVE_INFINITY
    road.points.forEach(([lat, lon]) => {
      south = Math.min(south, lat)
      north = Math.max(north, lat)
      west = Math.min(west, lon)
      east = Math.max(east, lon)
    })
    if (
      bounds.getNorth() < south || bounds.getSouth() > north ||
      bounds.getEast() < west || bounds.getWest() > east
    ) {
      return
    }
    strokeRoad(ctx, map, zoom, originX, originY, road.points, road.expressway ? 'expressway' : 'arterial')
  })
}

/**
 * 按某一层级画不规则网格（次干道 / 支路 / 主干道）。
 *
 * `ratio` 是相对基准格的倍数，三级的格线位置由同一套 `latticeLat/latticeLon` 给出，
 * 因此**层级之间天然咬合**：粗路的每一段都正好压在细路的格线上。
 *
 * 关键点在 `strokeMasked`：一条线遇到水面或绿地就**断开**，
 * 而不是硬穿过去 —— 这是「路网有逻辑」最直观的体现。
 */
function drawLatticeGrid(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  bounds: L.LatLngBounds,
  ratio: number,
  kind: 'arterial' | 'secondary' | 'minor',
) {
  const south = Math.max(bounds.getSouth(), URBAN.south)
  const north = Math.min(bounds.getNorth(), URBAN.north)
  const west = Math.max(bounds.getWest(), URBAN.west)
  const east = Math.min(bounds.getEast(), URBAN.east)
  if (south >= north || west >= east) {
    return
  }

  // 水面/绿地包络盒与瓦片不相交时，整块瓦片都不用做打洞判定
  const box = getHazardBox()
  const hazard =
    bounds.getNorth() >= box.south && bounds.getSouth() <= box.north &&
    bounds.getEast() >= box.west && bounds.getWest() <= box.east
  const blocked = hazard ? isBlockedGround : null

  const spacing = LATTICE_STEP * ratio
  // 每条线按 spacing/4 采样：既够密以识别 600m 宽的江面，又不至于每块瓦片算上万次
  const substeps = 4

  // 纬线（东西向）
  for (let i = Math.floor(south / spacing) * ratio; i <= Math.ceil(north / spacing) * ratio; i += ratio) {
    if (latMissing(i)) {
      continue
    }
    const lat = latticeLat(i)
    if (lat < URBAN.south || lat > URBAN.north) {
      continue
    }
    const n = Math.max(2, Math.ceil((east - west) / (spacing / substeps)))
    const samples: Array<[number, number]> = []
    for (let s = 0; s <= n; s++) {
      samples.push([lat, west + ((east - west) * s) / n])
    }
    strokeMasked(ctx, map, zoom, originX, originY, samples, blocked, kind)
  }

  // 经线（南北向）
  for (let j = Math.floor(west / spacing) * ratio; j <= Math.ceil(east / spacing) * ratio; j += ratio) {
    if (lonMissing(j)) {
      continue
    }
    const lon = latticeLon(j)
    if (lon < URBAN.west || lon > URBAN.east) {
      continue
    }
    const n = Math.max(2, Math.ceil((north - south) / (spacing / substeps)))
    const samples: Array<[number, number]> = []
    for (let s = 0; s <= n; s++) {
      samples.push([south + ((north - south) * s) / n, lon])
    }
    strokeMasked(ctx, map, zoom, originX, originY, samples, blocked, kind)
  }
}

/**
 * 沿一条（共线）采样线画道路，遇到 blocked 的点就断开。
 * 每段连续区间用「首点→末点」两个点画即可（采样点本身共线）。
 */
function strokeMasked(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  samples: Array<[number, number]>,
  blocked: ((lat: number, lon: number) => boolean) | null,
  kind: 'arterial' | 'secondary' | 'minor',
) {
  let runStart: [number, number] | null = null
  let lastFree: [number, number] | null = null

  const flush = () => {
    if (runStart && lastFree && (lastFree[0] !== runStart[0] || lastFree[1] !== runStart[1])) {
      strokeRoad(ctx, map, zoom, originX, originY, [runStart, lastFree], kind)
    }
    runStart = null
    lastFree = null
  }

  for (const point of samples) {
    if (blocked && blocked(point[0], point[1])) {
      flush()
      continue
    }
    if (!runStart) {
      runStart = point
    }
    lastFree = point
  }
  flush()
}

/**
 * 街区肌理：在基准格单元里填极浅的色块。
 *
 * 单元边界直接用与路网**同一套** latticeLat / latticeLon（同一个 ratio），
 * 所以色块与道路严丝合缝，粗路也一定会落在色块边界上。
 */
function drawBlocks(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  bounds: L.LatLngBounds,
  ratio: number,
) {
  const south = Math.max(bounds.getSouth(), URBAN.south)
  const north = Math.min(bounds.getNorth(), URBAN.north)
  const west = Math.max(bounds.getWest(), URBAN.west)
  const east = Math.min(bounds.getEast(), URBAN.east)
  if (south >= north || west >= east) {
    return
  }

  const spacing = LATTICE_STEP * ratio

  for (let i = Math.floor(south / spacing) * ratio; i <= Math.ceil(north / spacing) * ratio; i += ratio) {
    const latSouth = latticeLat(i)
    const latNorth = latticeLat(i + ratio)
    if (latNorth < south || latSouth > north) {
      continue
    }
    for (let j = Math.floor(west / spacing) * ratio; j <= Math.ceil(east / spacing) * ratio; j += ratio) {
      const lonWest = latticeLon(j)
      const lonEast = latticeLon(j + ratio)
      if (lonEast < west || lonWest > east) {
        continue
      }
      const centerLat = (latSouth + latNorth) / 2
      const centerLon = (lonWest + lonEast) / 2
      if (isBlockedGround(centerLat, centerLon)) {
        continue
      }
      const tone = BLOCK_TONES[Math.floor(hash2(i, j) * BLOCK_TONES.length)]
      const nw = map.project(L.latLng(latNorth, lonWest), zoom)
      const se = map.project(L.latLng(latSouth, lonEast), zoom)
      const w = se.x - nw.x
      const h = se.y - nw.y
      if (w <= 1.4 || h <= 1.4) {
        continue
      }
      ctx.fillStyle = tone
      // 内缩 1px 留出「路缝」，视觉上街区与道路自然分离
      ctx.fillRect(nw.x - originX + 1, nw.y - originY + 1, w - 2, h - 2)
    }
  }
}

/** 水系：先描一层深一点的边，再填浅色水面，看起来像一条真正的河 */
function drawWaterways(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  bounds: L.LatLngBounds,
) {
  const box = getHazardBox()
  if (
    bounds.getNorth() < box.south || bounds.getSouth() > box.north ||
    bounds.getEast() < box.west || bounds.getWest() > box.east
  ) {
    return
  }

  LAKES.forEach((lake) => {
    const [south, west, north, east] = lake.rect
    if (!bounds.intersects(L.latLngBounds(L.latLng(south, west), L.latLng(north, east)))) {
      return
    }
    fillGeoRect(ctx, map, zoom, originX, originY, lake.rect, '#0a2c44')
  })

  WATERWAYS.forEach((waterway) => {
    const mpp = metersPerPixel(zoom, waterway.points[0][0])
    const width = Math.max(2.5, waterway.widthM / mpp)
    strokePolyline(ctx, map, zoom, originX, originY, waterway.points, { color: '#062033', width: width + 1.6 })
    strokePolyline(ctx, map, zoom, originX, originY, waterway.points, { color: '#12506f', width })
  })
}

/** 画机场跑道与滑行道 */
function drawAirport(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  zoom: number,
  originX: number,
  originY: number,
  airport: (typeof AIRPORTS)[number],
) {
  const mpp = metersPerPixel(zoom, airport.center[0])
  const halfM = airport.runwayM / 2

  // 把「以中心为原点、沿 bearing 方向的 ±halfM」换算成经纬度
  const rad = (airport.bearingDeg * Math.PI) / 180
  const dLat = (Math.cos(rad) * halfM) / M_PER_DEG_LAT
  const dLon = (Math.sin(rad) * halfM) / metersPerDegLon(airport.center[0])
  const runway: Array<[number, number]> = [
    [airport.center[0] - dLat, airport.center[1] - dLon],
    [airport.center[0] + dLat, airport.center[1] + dLon],
  ]

  // 跑道本体：中灰实线 + 浅色中线
  strokePolyline(ctx, map, zoom, originX, originY, runway, { color: '#3d556b', width: Math.max(3, 60 / mpp) })
  strokePolyline(ctx, map, zoom, originX, originY, runway, {
    color: '#9fc6e4',
    width: Math.max(1, 3 / mpp),
    dash: [Math.max(3, 120 / mpp), Math.max(4, 90 / mpp)],
  })

  // 滑行道：与跑道平行、向南偏移约 500m
  const offsetLat = -500 / M_PER_DEG_LAT
  strokePolyline(
    ctx, map, zoom, originX, originY,
    runway.map(([lat, lon]) => [lat + offsetLat, lon] as [number, number]),
    { color: '#2b4257', width: Math.max(1, 18 / mpp) },
  )
}

/** 画出单块瓦片 */
function drawTile(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  coords: L.Coords,
  drawBackground: boolean,
): void {
  const size = TILE_SIZE
  const originX = coords.x * size
  const originY = coords.y * size
  const zoom = coords.z

  const nw = map.unproject([originX, originY], zoom)
  const se = map.unproject([originX + size, originY + size], zoom)
  const bounds = L.latLngBounds(nw, se)

  // 1) 底色。
  //    单独使用时画底色；叠在「中国地图」之上时必须留空（transparent），
  //    否则会把下面的省级边界与海面整块盖住。
  if (drawBackground) {
    ctx.fillStyle = '#07131f'
    ctx.fillRect(0, 0, size, size)
  }

  // 2) 绿地（最底层）
  PARKS.forEach((park) => {
    if (!bounds.intersects(L.latLngBounds(L.latLng(park.rect[0], park.rect[1]), L.latLng(park.rect[2], park.rect[3])))) {
      return
    }
    fillGeoRect(ctx, map, zoom, originX, originY, park.rect, '#102a24')
  })

  // 3) 街区肌理（z≥13 才有意义，更低时一格不到 1px，纯噪声）。
  //    与下面那一级路网用同一个 ratio，色块才会刚好卡在道路之间。
  if (zoom >= 15) {
    drawBlocks(ctx, map, zoom, originX, originY, bounds, LEVEL_FINE)
  } else if (zoom >= 13) {
    drawBlocks(ctx, map, zoom, originX, originY, bounds, LEVEL_MEDIUM)
  }

  // 4) 水系：压住街区，让「江穿城而过」的观感成立
  drawWaterways(ctx, map, zoom, originX, originY, bounds)

  // 5) 路网：三级渐进。细级先画（在下），粗级后画（在上），自然形成粗细层次。
  if (zoom >= 15) {
    drawLatticeGrid(ctx, map, zoom, originX, originY, bounds, LEVEL_FINE, 'minor')
  }
  if (zoom >= 13) {
    drawLatticeGrid(ctx, map, zoom, originX, originY, bounds, LEVEL_MEDIUM, 'secondary')
  }
  if (zoom >= 11) {
    drawLatticeGrid(ctx, map, zoom, originX, originY, bounds, LEVEL_COARSE, 'arterial')
  }

  // 6) 主干骨架（环线 + 放射）：最后画，永远压在最上面，保证城市结构始终清晰
  drawArterials(ctx, map, zoom, originX, originY, bounds)

  // 7) 机场（在骨架之上，跑道要看得见）
  AIRPORTS.forEach((airport) => {
    if (!bounds.contains(L.latLng(airport.center[0], airport.center[1]))) {
      return
    }
    drawAirport(ctx, map, zoom, originX, originY, airport)
  })
}

/**
 * 创建「真实城市路网」图层。
 *
 * 两种用法：
 *  - 单独作为底图：`createSimTileLayer()`（浅底 + 真实路网），完全离线自包含；
 *  - 叠加在中国地图之上：`createSimTileLayer({ drawBackground: false })`，
 *    给作业区补上城市级细节 —— 省级边界在街区尺度上太粗糙。
 *
 * 用 `L.GridLayer.extend` 是为了复用 Leaflet 的瓦片调度（按需创建、离开视野回收），
 * 我们只需要实现「怎么画一块瓦片」。
 */
export function createSimTileLayer(options: { drawBackground?: boolean } = {}): L.GridLayer {
  const drawBackground = options.drawBackground ?? true

  const SimLayer = L.GridLayer.extend({
    createTile(this: L.GridLayer, coords: L.Coords): HTMLElement {
      const canvas = document.createElement('canvas')
      canvas.width = TILE_SIZE
      canvas.height = TILE_SIZE
      const ctx = canvas.getContext('2d')
      // 拿不到 2d 上下文（极端情况）时退回纯色，不能让整个地图挂掉
      if (!ctx) {
        return canvas
      }

      // `_map` 是 Leaflet 内部字段，在 createTile 阶段一定已经挂载
      const map = (this as unknown as { _map: L.Map })._map
      try {
        drawTile(ctx, map, coords, drawBackground)
      } catch {
        if (drawBackground) {
          ctx.fillStyle = '#07131f'
          ctx.fillRect(0, 0, TILE_SIZE, TILE_SIZE)
        }
      }
      return canvas
    },
  })

  return new (SimLayer as unknown as new (options?: L.GridLayerOptions) => L.GridLayer)({
    // 关闭坐标环绕，避免在东西边界重复绘制（我们的作业区远小于半个地球）
    noWrap: true,
    attribution: '底图：程序化生成（离线，无外部瓦片依赖）',
  })
}
