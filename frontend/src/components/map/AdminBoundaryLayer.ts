import L from 'leaflet'

/**
 * 行政区划图层：省级可控分色 + 省 → 市 → 区/县逐级下钻高亮。
 *
 * 数据策略（与项目"省级边界不入库、按需下载"的既有做法一致）：
 *  - 省级：本地 `public/geo/china.json`（DataV 100000_full，含 adcode/name/center）；
 *  - 市 / 区县：按选中区域的 **adcode** 在线请求 DataV
 *    `https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json`，
 *    内存缓存，切换省份不重复请求。用 adcode 而非中文名绑定，避免
 *    "海南州 / 海南省 / 三亚"这类同名歧义。
 *
 * 层级（pane）：省面 chinaBase(180) → 下钻面 adminDistrict(186) → 城市标注 cityLabel(190)，
 * 均压在程序化路网 tilePane(200) 之下、业务 overlay(400)/无人机(600) 之下，互不遮挡。
 *
 * 交互：点省面或用下拉选择 → 其他省变暗、该省下的市/区县整体高亮并缩放过去；
 * 普通省份下钻先看到地级市，点地级市再看区/县；直辖市（京沪津渝）直接是区/县。
 */

export type ProvinceColorMode = 'uniform' | 'palette'

export interface ProvinceOption {
  adcode: number
  name: string
  center?: [number, number]
}

/** 当前所处的行政区划层级 */
export type AdminLevel = 'country' | 'province' | 'city'

export interface AdminRegion {
  adcode: number
  name: string
  level: AdminLevel
}

export interface AdminBoundaryOptions {
  /** 选中某个省/市（点击地图要素或下拉框都会触发） */
  onSelectChange?: (region: AdminRegion | null) => void
  /**
   * 是否允许"点击省面即下钻"。
   *
   * <p>默认为 true（态势地图的行为）。空域拾取器里必须设为 false：
   * 那里的点击语义是"在这一点划定区域"，若省面还会抢走点击去下钻，用户根本没法选点。
   * 关掉交互后本图层只作地理参照（省界 + 省名 + 市/区县名），视觉完全一致。
   */
  interactive?: boolean
  /**
   * 自动下钻的"状态说明"（成功传 null，失败传原因）。
   *
   * <p><b>为什么要把失败讲出来</b>：下级边界是按 adcode 在线拉的，港澳台等区划没有
   * `_full.json`、网络不通时也会失败。以前失败是**静默**的 —— 用户放大后什么都没发生，
   * 只能得出"这个地图坏了 / 放大也看不到市区县"的结论，而这恰恰是他最想知道的边界。
   * 把原因交给调用方展示，至少让人知道"是这里确实没有更细的数据"，而不是以为功能没做。
   */
  onNotice?: (text: string | null) => void
}

export interface AdminBoundaryHandle {
  /** 省级下拉选项（按名称排序） */
  provinces: ProvinceOption[]
  /** 当前层级与区域；null 表示全国视图 */
  readonly current: AdminRegion | null
  /** 省级数据是否加载成功（失败时地图只剩海面背景） */
  ready: boolean
  setColorMode(mode: ProvinceColorMode): void
  /** 名称标签总开关（省/市/区县名常驻显示） */
  setLabelsVisible(visible: boolean): void
  /** 选择省份（null = 返回全国） */
  selectProvince(adcode: number | null): Promise<void>
  /** 返回上一级：city → province → country */
  goBack(): Promise<void>
  destroy(): void
}

const DATAV_BASE = 'https://geo.datav.aliyun.com/areas_v3/bound'
/** 下钻面/边的高亮配色（该区域内的所有市/区县统一高亮，与未选省份拉开对比） */
const HIGHLIGHT_FILL = '#1b5d94'
const HIGHLIGHT_FILL_STRONG = '#2f8fd6'
const HIGHLIGHT_BORDER = '#38c6ff'
/** 分省模式调色板（深色底上取低明度、可辨色相的 HSL，避免与红橙告警冲突） */
const PALETTE_SAT = 46
const PALETTE_LIGHT = 24
const PALETTE_BORDER_LIGHT = 46
/** 未选中省份在下钻视图中的淡化透明度 */
const DIMMED_PROVINCE_OPACITY = 0.18

/**
 * 标签密度控制的缩放阈值与名称长度上限。
 *
 * <p>全国视图（z &lt; 5）下 34 个省级名称常驻会互相压叠，所以只标短名；
 * 放大到 z ≥ 5 再补全。见 {@code refreshLabels} 的说明。
 */
const LABEL_DENSE_ZOOM = 5
const LABEL_SHORT_NAME_MAX = 5

/**
 * 全国视野的标签避让参数：11px 加粗汉字的单字宽约等于字号，行高取 14px。
 *
 * <p>34 个省名挤在约 470px 宽的中国轮廓里，即使换成简称实测仍有 19 对矩形相交
 * （用户原话"省份的字体都占到一块"）。真实地图渲染器的做法是"宁缺勿叠"：
 * 按区域面积从大到小依次占位，与已放标签相交的先不画，放大（z ≥ 5）后自动补回。
 */
const LABEL_CHAR_PX = 11
const LABEL_LINE_PX = 14
const LABEL_GAP_PX = 2

/**
 * 自动下钻的缩放阈值：放大到省级尺度时，自动加载视野所在省的市/区县边界。
 *
 * <p><b>为什么是 6 而不是 7</b>：全国默认视野停在 z4，用户"放大"两格才到 z6。
 * 阈值定在 7 时，实测滚 4 格仍停在 z6（Leaflet 的缩放动画会吃掉相邻两次滚轮中的一次），
 * 屏幕上什么都不出来 —— 这正是用户反馈的"放大看不到市区县"。
 * z6 的可见宽度约 500km，已经是一座省的范围，在这里开始下钻与直觉一致。
 */
const AUTO_DRILL_ZOOM = 6

/**
 * 视野内该省的覆盖比例下限：低于它就认为"只是擦到了省界一角"，不触发自动下钻。
 *
 * <p>用于中心点落在海面/湖面时的兜底判断 —— 沿海城市放大后视野中心常常是一片水，
 * 只看中心点会得出"这里不属于任何省"，于是"放大到深圳却看不到区县"。
 */
const VIEW_COVERAGE_MIN = 0.35

interface ProvinceShape {
  adcode: number
  name: string
  center?: [number, number]
  layer: L.GeoJSON
  /** 原始要素，用于计算名称标签锚点 */
  feature: GeoJSON.Feature
}

/** adcode → GeoJSON 的请求缓存（省份/城市边界只拉一次） */
const geoCache = new Map<number, GeoJSON.FeatureCollection>()
let chinaGeoPromise: Promise<GeoJSON.FeatureCollection> | null = null

/** 加载全国省级 GeoJSON（模块级共享，避免与底图重复请求） */
function loadChinaGeo(): Promise<GeoJSON.FeatureCollection> {
  if (!chinaGeoPromise) {
    chinaGeoPromise = fetch('/geo/china.json').then(async (resp) => {
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      const geo = (await resp.json()) as GeoJSON.FeatureCollection
      if (!geo || !Array.isArray(geo.features)) throw new Error('不是合法的 FeatureCollection')
      return geo
    })
  }
  return chinaGeoPromise
}

/** 按 adcode 拉取下级边界（市 / 区县），带缓存与明确报错 */
async function loadChildrenGeo(adcode: number): Promise<GeoJSON.FeatureCollection> {
  const cached = geoCache.get(adcode)
  if (cached) return cached
  const resp = await fetch(`${DATAV_BASE}/${adcode}_full.json`)
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  const geo = (await resp.json()) as GeoJSON.FeatureCollection
  if (!geo || !Array.isArray(geo.features) || geo.features.length === 0) {
    throw new Error('该区域暂无下级边界数据')
  }
  geoCache.set(adcode, geo)
  return geo
}

/** 稳定的分省颜色：同一省份在任何时候颜色都不变（按 adcode 排序索引取色相） */
function provinceHue(adcode: number, orderedAdcodes: number[]): number {
  const index = orderedAdcodes.indexOf(adcode)
  return Math.round((index * 137.508) % 360) // 黄金角散布，相邻省份色差大
}

/** 取要素的全部外环（Polygon 一个环；MultiPolygon 每个子多边形的首环）。 */
function outerRings(geometry: GeoJSON.Geometry | null | undefined): number[][][] {
  if (!geometry) return []
  if (geometry.type === 'Polygon') {
    return [(geometry.coordinates as number[][][])[0] as number[][]]
  }
  if (geometry.type === 'MultiPolygon') {
    return (geometry.coordinates as number[][][][]).map((poly) => poly[0] as number[][])
  }
  return []
}

/**
 * 射线法判断一个经纬度点是否落在要素的某个外环内。
 *
 * <p>只用<b>外环</b>（不含内环/孔洞）：自动下钻只需要粗判"地图中心在哪个省"，
 * 用外环已足够且更快——沿海省的孔洞极少，判错的概率远低于它带来的复杂度。
 */
function pointInFeature(lat: number, lon: number, feature: GeoJSON.Feature): boolean {
  for (const ring of outerRings(feature.geometry)) {
    let inside = false
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const xi = ring[i][1]
      const yi = ring[i][0]
      const xj = ring[j][1]
      const yj = ring[j][0]
      // 半开区间判定：严格处理顶点与水平边，避免边界点被重复计入
      const crosses = xi > lat !== xj > lat
      if (crosses && lon < ((yj - yi) * (lat - xi)) / (xj - xi) + yi) inside = !inside
    }
    if (inside) return true
  }
  return false
}

/**
 * DataV 的 {@code properties.center} 是 {@code [经度, 纬度]}，而 Leaflet 的 LatLng 是
 * {@code [纬度, 经度]}。直接透传会把纬度写成 116°（Mercator 投影直接 NaN），
 * 所有名称标签会叠到地图左上角外面，表现为"只有色块、没有任何省/市/区县名字"。
 */
function centerToLatLng(center: unknown): [number, number] | null {
  if (!Array.isArray(center) || typeof center[0] !== 'number' || typeof center[1] !== 'number') {
    return null
  }
  return [center[1], center[0]]
}

/**
 * 计算区域名称标签的锚点。
 *
 * <p>优先用 GeoJSON 自带的 {@code properties.center}；缺失时取"包围盒面积最大的那个外环"
 * 的包围盒中心。为什么不取全部坐标的算术平均？因为沿海 / 群岛型行政区（舟山、三沙等）
 * 的顶点会被远海小岛拉偏，标签会飘到海面上。
 */
function labelAnchor(feature: GeoJSON.Feature): [number, number] | null {
  const props = feature.properties as { center?: unknown } | null
  const fromCenter = centerToLatLng(props?.center)
  if (fromCenter) return fromCenter
  const rings = outerRings(feature.geometry)
  if (rings.length === 0) return null

  let bestRing: number[][] | null = null
  let bestArea = -1
  for (const ring of rings) {
    let minLat = Number.POSITIVE_INFINITY
    let maxLat = Number.NEGATIVE_INFINITY
    let minLon = Number.POSITIVE_INFINITY
    let maxLon = Number.NEGATIVE_INFINITY
    for (const coord of ring) {
      const lon = coord[0]
      const lat = coord[1]
      if (lat < minLat) minLat = lat
      if (lat > maxLat) maxLat = lat
      if (lon < minLon) minLon = lon
      if (lon > maxLon) maxLon = lon
    }
    const area = (maxLat - minLat) * (maxLon - minLon)
    if (area > bestArea) {
      bestArea = area
      bestRing = ring
    }
  }
  if (!bestRing) return null

  let minLat = Number.POSITIVE_INFINITY
  let maxLat = Number.NEGATIVE_INFINITY
  let minLon = Number.POSITIVE_INFINITY
  let maxLon = Number.NEGATIVE_INFINITY
  for (const coord of bestRing) {
    if (coord[1] < minLat) minLat = coord[1]
    if (coord[1] > maxLat) maxLat = coord[1]
    if (coord[0] < minLon) minLon = coord[0]
    if (coord[0] > maxLon) maxLon = coord[0]
  }
  return [(minLat + maxLat) / 2, (minLon + maxLon) / 2]
}

/**
 * 要素的"代表性尺寸"：取各外环包围盒长边的最大值（单位：度）。
 *
 * <p>只用于全国视野下给标签排占位优先级 —— 新疆/西藏/内蒙古这类大省先占位，
 * 小省（如上海、澳门）在窄屏上可以等放大后再显示。
 */
function featureSpan(feature: GeoJSON.Feature): number {
  let span = 0
  for (const ring of outerRings(feature.geometry)) {
    let minLat = Number.POSITIVE_INFINITY
    let maxLat = Number.NEGATIVE_INFINITY
    let minLon = Number.POSITIVE_INFINITY
    let maxLon = Number.NEGATIVE_INFINITY
    for (const coord of ring) {
      if (coord[1] < minLat) minLat = coord[1]
      if (coord[1] > maxLat) maxLat = coord[1]
      if (coord[0] < minLon) minLon = coord[0]
      if (coord[0] > maxLon) maxLon = coord[0]
    }
    span = Math.max(span, maxLat - minLat, maxLon - minLon)
  }
  return span
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) =>
    ch === '&' ? '&amp;' : ch === '<' ? '&lt;' : ch === '>' ? '&gt;' : ch === '"' ? '&quot;' : '&#39;',
  )
}

/**
 * 常驻名称标签图标。
 *
 * <p>用 iconSize/anchor 全 0 的 divIcon，再靠 CSS {@code transform: translate(-50%,-50%)}
 * 把文字精确居中到锚点；这样文字长短变化时中心不会漂移，也不会像固定尺寸那样截断。
 */
function createLabelIcon(text: string, level: 'province' | 'city' | 'district', dense = false): L.DivIcon {
  return L.divIcon({
    className: 'admin-label-host',
    iconSize: [0, 0],
    iconAnchor: [0, 0],
    html: `<span class="admin-label admin-label--${level}${dense ? ' admin-label--dense' : ''}">${escapeHtml(text)}</span>`,
  })
}

/**
 * 全国视图下的省级简称：去掉"省 / 市 / 自治区 / 特别行政区"和民族前缀。
 *
 * <p>全国视野里中国只有 ~470px 宽，"内蒙古自治区 / 新疆维吾尔自治区"这类全名必然压到邻居，
 * 用户原话是"省份的字体都占到一块"。缩到 2~3 个字（内蒙古、新疆、北京）后才能各就各位。
 */
function shortProvinceName(name: string): string {
  return name.replace(/(壮族|回族|维吾尔)?(自治区|特别行政区|省|市)$/, '')
}

/**
 * 挂载行政区划图层。
 * @returns 成功返回控制句柄；省级数据缺失时 ready=false（调用方提示并保留背景）
 */
export async function attachAdminBoundaries(
  map: L.Map,
  options: AdminBoundaryOptions = {},
): Promise<AdminBoundaryHandle> {
  if (!map.getPane('adminDistrict')) {
    const pane = map.createPane('adminDistrict')
    // 必须高于程序化路网 tilePane(200)：下钻到省/市/区县时视野通常已到 z9~z11，
    // 路网瓦片会在该缩放级出现；若下钻面压在瓦片之下（曾用 z=186），区县会被整块盖住，
    // 表现为"明明联网、数据也加载了却看不到区县"。同时低于业务 overlayPane(400)，
    // 保证空域/航路/无人机标记仍在最上层。
    pane.style.zIndex = '250'
  }
  if (!map.getPane('adminLabel')) {
    const pane = map.createPane('adminLabel')
    // 名称标签要压在所有面之上（含路网瓦片），但仍低于业务 overlayPane(400)
    pane.style.zIndex = '260'
    // 标签只是文字，绝不能拦住地图拖拽 / 面点击
    pane.style.pointerEvents = 'none'
  }

  /** 下钻区域（市/区县）要素容器，每次切换整体替换 */
  const districtGroup = L.layerGroup().addTo(map)
  /** 常驻名称标签容器（省/市/区县名），随视图层级重建 */
  const labelGroup = L.layerGroup().addTo(map)
  /** 名称标签总开关 */
  let showLabels = true

  /**
   * 是否允许交互（点击省面下钻 / hover 高亮）。
   *
   * <p>空域拾取器把地图的点击语义留给了"在这一点划定区域"，因此必须传 false，
   * 让本图层退化为纯地理参照（省界 + 名称），不再抢占鼠标事件。
   */
  const interactive = options.interactive !== false

  let colorMode: ProvinceColorMode = 'uniform'
  let current: AdminRegion | null = null
  /** 下钻栈顶区域的父级（city 视图时指向所属省，用于返回） */
  let parentProvince: AdminRegion | null = null
  let shapes: ProvinceShape[] = []
  let orderedAdcodes: number[] = []
  /** 当前下钻渲染的市/区县要素（标签重建时要用） */
  let districtFeatures: GeoJSON.Feature[] = []

  // ---- 加载并渲染省面 ----
  let geo: GeoJSON.FeatureCollection
  try {
    geo = await loadChinaGeo()
  } catch (error) {
    console.warn('[admin-map] 省级边界加载失败，地图仅显示背景层。', error)
    return {
      provinces: [],
      current: null,
      ready: false,
      setColorMode: () => undefined,
      setLabelsVisible: () => undefined,
      selectProvince: async () => undefined,
      goBack: async () => undefined,
      destroy: () => undefined,
    }
  }

  shapes = geo.features
    .filter((f) => f.properties && typeof f.properties.adcode === 'number')
    .map((feature) => {
      const p = feature.properties as {
        adcode: number
        name: string
        center?: [number, number]
      }
      const layer = L.geoJSON(feature, {
        pane: 'chinaBase',
        // 非交互模式（空域拾取器）下整层不接收鼠标事件：
        // 省面若仍是 interactive，点击会被它吃掉，用户就没法在地图上点选坐标了
        interactive,
        style: () => ({ weight: 1, fillOpacity: 1 }),
      }).addTo(map)
      if (interactive) {
        layer.bindTooltip(p.name, { sticky: true, direction: 'top', opacity: 0.92 })
      }
      // 统一存成 [纬度, 经度]，后续标签锚点、对外选项都不用再关心数据源顺序
      return { adcode: p.adcode, name: p.name, center: centerToLatLng(p.center) ?? undefined, layer, feature }
    })
  orderedAdcodes = shapes.map((s) => s.adcode).sort((a, b) => a - b)

  const provinces: ProvinceOption[] = shapes
    .map((s) => ({ adcode: s.adcode, name: s.name, center: s.center }))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh'))

  if (interactive) {
    shapes.forEach((shape) => {
      shape.layer.on('click', () => {
        void selectProvince(shape.adcode)
      })
      // hover 提亮（全国视图下才有意义；下钻后省面已淡化，不改）
      shape.layer.on('mouseover', () => {
        if (current) return
        shape.layer.setStyle({ fillOpacity: 0.85, weight: 1.6 })
      })
      shape.layer.on('mouseout', () => {
        if (current) return
        applyProvinceStyle(shape)
      })
    })
  }

  applyAllProvinceStyles()
  // 初始即为全国视图：先把省名标出来（否则用户只看得到色块、看不到名字）
  refreshLabels()

  // 缩放 / 平移结束时都要跟一次视野：
  //  ① 重建名称标签 —— 放大后要把低缩放时让位掉的省级长名补回来（见 refreshLabels 的密度控制）；
  //  ② 尝试自动下钻 / 跟随视野换省 / 缩回全国时自动退出。
  // 拖动只会触发 moveend 而**不会**触发 zoomend，只监听 zoomend 的话，
  // "放大后把地图拖到我要看的城市"这条最常见的操作路径就完全不会生效。
  function onZoomEnd(): void {
    if (autoDrilled && map.getZoom() < AUTO_DRILL_ZOOM) {
      resetToCountry(false)
      return
    }
    refreshLabels()
    void autoDrillIfNeeded()
  }
  function onMoveEnd(): void {
    void followViewOnMove()
    void autoDrillIfNeeded()
  }
  map.on('zoomend', onZoomEnd)
  map.on('moveend', onMoveEnd)

  // ------------------------------------------------------------------
  // 样式
  // ------------------------------------------------------------------

  /** 单个省在当前模式下的标准样式 */
  function provinceStyle(adcode: number): L.PathOptions {
    if (colorMode === 'palette') {
      const hue = provinceHue(adcode, orderedAdcodes)
      return {
        color: `hsl(${hue}, ${PALETTE_SAT + 8}%, ${PALETTE_BORDER_LIGHT}%)`,
        fillColor: `hsl(${hue}, ${PALETTE_SAT}%, ${PALETTE_LIGHT}%)`,
        weight: 1,
        fillOpacity: 1,
      }
    }
    return {
      color: '#1e4a68',
      fillColor: '#0c2135',
      weight: 1,
      fillOpacity: 1,
    }
  }

  function applyProvinceStyle(shape: ProvinceShape): void {
    shape.layer.setStyle(provinceStyle(shape.adcode))
  }

  function applyAllProvinceStyles(): void {
    shapes.forEach(applyProvinceStyle)
  }

  /** 下钻后：选中省保持原色，其余省淡化 */
  function dimProvinces(selectedAdcode: number | null): void {
    shapes.forEach((shape) => {
      applyProvinceStyle(shape)
      if (selectedAdcode !== null && shape.adcode !== selectedAdcode) {
        shape.layer.setStyle({ fillOpacity: DIMMED_PROVINCE_OPACITY, color: '#0f2a3f' })
      }
    })
  }

  // ------------------------------------------------------------------
  // 常驻名称标签（省 / 市 / 区县名）
  // ------------------------------------------------------------------

  /**
   * 重建名称标签。
   *
   * <p>按当前层级决定标什么：全国视图标省名，下钻视图标市/区县名。
   * 这是"看得见行政区名字"的关键——之前只有 hover tooltip，鼠标不悬停就完全看不到名字，
   * 用户会以为"区县名没有显示"。
   *
   * <p><b>低缩放必须做密度控制</b>：全国视图下 34 个省级名称全是常驻标签，
   * 缩到 z=4 时它们会挤成一团、互相压叠（用户反馈"省份的字体都占到一块"）。
   * 所以按缩放分档：
   * <ul>
   *   <li>z &lt; 5：只标<b>名字短</b>的省级区划（北京、上海、山西…），长名字（内蒙古自治区、
   *       新疆维吾尔自治区）在这个尺度下必然压到邻居，先让位给省界色块；</li>
   *   <li>5 ≤ z &lt; 7：全部省级名称；</li>
   *   <li>z ≥ 7：下钻视图，标市/区县名（并按其名长度决定字号）。</li>
   * </ul>
   * 这不是"少标几个就完事"——标签重叠的根因是<b>可用的屏幕面积</b>，
   * 所以在低缩放下主动让出空间，放大后再补全，是唯一可读的做法。
   */
  function refreshLabels(): void {
    labelGroup.clearLayers()
    if (!showLabels) return
    const zoom = map?.getZoom() ?? 4

    if (current === null) {
      const dense = zoom < LABEL_DENSE_ZOOM
      // 低缩放：大省先占位，和已放标签相交的先略过（宁缺勿叠），放大后自动补回
      const ordered = dense
        ? [...shapes].sort((a, b) => featureSpan(b.feature) - featureSpan(a.feature))
        : shapes
      const placed: Array<{ l: number; t: number; r: number; b: number }> = []
      ordered.forEach((shape) => {
        // 低缩放用简称（北京 / 内蒙古 / 新疆）+ 小一号字，避免省名互相压叠
        const text = dense ? shortProvinceName(shape.name) : shape.name
        if (dense && text.length > LABEL_SHORT_NAME_MAX) return
        const anchor = labelAnchor(shape.feature) ?? shape.center
        if (!anchor) return
        if (dense) {
          const point = map.latLngToContainerPoint(anchor)
          const halfW = (text.length * LABEL_CHAR_PX) / 2
          const rect = {
            l: point.x - halfW,
            t: point.y - LABEL_LINE_PX / 2,
            r: point.x + halfW,
            b: point.y + LABEL_LINE_PX / 2,
          }
          const overlaps = placed.some(
            (o) =>
              rect.l < o.r + LABEL_GAP_PX &&
              rect.r > o.l - LABEL_GAP_PX &&
              rect.t < o.b + LABEL_GAP_PX &&
              rect.b > o.t - LABEL_GAP_PX,
          )
          if (overlaps) return
          placed.push(rect)
        }
        L.marker(anchor, {
          pane: 'adminLabel',
          icon: createLabelIcon(text, 'province', dense),
          interactive: false,
          keyboard: false,
        }).addTo(labelGroup)
      })
      return
    }

    districtFeatures.forEach((feature) => {
      const props = feature.properties as { name?: string; level?: string } | null
      const name = props?.name
      if (!name) return
      const anchor = labelAnchor(feature)
      if (!anchor) return
      // DataV 的 level 取值：province / city / district；直辖市的"区"也是 district
      const level = props?.level === 'district' ? 'district' : 'city'
      L.marker(anchor, {
        pane: 'adminLabel',
        icon: createLabelIcon(name, level),
        interactive: false,
        keyboard: false,
      }).addTo(labelGroup)
    })
  }

  function setLabelsVisible(visible: boolean): void {
    showLabels = visible
    refreshLabels()
  }

  // ------------------------------------------------------------------
  // 下钻渲染
  // ------------------------------------------------------------------

  /** 把一组市/区县要素渲染到下钻层，整体高亮 */
  function renderDistricts(children: GeoJSON.FeatureCollection, maxZoom: number, fly = true): void {
    districtGroup.clearLayers()
    districtFeatures = children.features ?? []
    L.geoJSON(children, {
      pane: 'adminDistrict',
      style: () => ({
        color: HIGHLIGHT_BORDER,
        weight: 1.4,
        fillColor: HIGHLIGHT_FILL,
        fillOpacity: 0.62,
      }),
      onEachFeature: (feature, layer) => {
        const p = feature.properties as { name?: string; level?: string; adcode?: number }
        const path = layer as L.Path
        const tip =
          p.level === 'city' ? `${p.name ?? ''}（点击查看区/县）` : (p.name ?? '')
        layer.bindTooltip(tip, { sticky: true, direction: 'top', opacity: 0.95 })
        path.on('mouseover', () => path.setStyle({ fillColor: HIGHLIGHT_FILL_STRONG, weight: 2 }))
        path.on('mouseout', () => path.setStyle({ fillColor: HIGHLIGHT_FILL, weight: 1.4 }))
        // 普通省份先看到地级市，点地级市继续下钻到区/县
        if (p.level === 'city' && typeof p.adcode === 'number') {
          path.on('click', () => {
            void drillIntoCity(p.adcode as number, p.name ?? String(p.adcode))
          })
        }
      },
    }).addTo(districtGroup)

    if (fly) {
      const bounds = L.geoJSON(children).getBounds()
      if (bounds.isValid()) {
        map.flyToBounds(bounds.pad(0.08), { duration: 0.55, maxZoom })
      }
    }
    refreshLabels()
  }

  /**
   * 自动下钻：缩放到省级尺度（z ≥ 7）时，把"地图中心所在省"的市/区县边界自动加载出来。
   *
   * <p><b>为什么需要它</b>：用户的原话是"无法展示出省的市区县" —— 手动点省面才能下钻，
   * 而"我放大到福建了，就该看到福州和它的区县"是所有人对地图的默认预期。
   * 这里只<b>加载与渲染</b>，不调用 {@code flyToBounds}：自动飞视野会打断用户正在做的缩放。
   *
   * <p>拉不到下级数据（港澳台等 DataV 没有 `_full.json` 的区划）时不清空已有内容、
   * 不打断操作，但会通过 {@code onNotice} 说明原因 —— 静默失败只会让人以为功能没做。
   */
  let autoDrilling = false
  /** 当前下钻是"自动跟随视野"造成的（true）还是用户主动点的（false）；只有前者会被自动收回 */
  let autoDrilled = false

  /**
   * 判断"当前视野属于哪个省"。
   *
   * <p>先看视野中心落在哪个省面里（最精确）；中心点落在海面、湖面或省界外时，
   * 退化为"与视野重叠面积占视野比例最大的那个省"，并要求覆盖比例 ≥ {@link VIEW_COVERAGE_MIN}。
   * 只用中心点是不行的：沿海城市（上海、深圳、厦门、台北）放大后中心常常正在水面上，
   * 用户看到的就是"放大到深圳，区县却一个都没出来"。
   */
  function provinceAtView(): ProvinceShape | null {
    if (!map) return null
    const center = map.getCenter()
    const byCenter = shapes.find((shape) => pointInFeature(center.lat, center.lng, shape.feature))
    if (byCenter) return byCenter

    const viewBounds = map.getBounds()
    const viewArea =
      Math.max(1e-9, viewBounds.getNorth() - viewBounds.getSouth()) *
      Math.max(1e-9, viewBounds.getEast() - viewBounds.getWest())
    let best: ProvinceShape | null = null
    let bestCoverage = VIEW_COVERAGE_MIN
    for (const shape of shapes) {
      const bounds = shape.layer.getBounds()
      if (!bounds.isValid() || !viewBounds.intersects(bounds)) continue
      const south = Math.max(bounds.getSouth(), viewBounds.getSouth())
      const north = Math.min(bounds.getNorth(), viewBounds.getNorth())
      const west = Math.max(bounds.getWest(), viewBounds.getWest())
      const east = Math.min(bounds.getEast(), viewBounds.getEast())
      const coverage = ((north - south) * (east - west)) / viewArea
      if (coverage > bestCoverage) {
        bestCoverage = coverage
        best = shape
      }
    }
    return best
  }

  async function autoDrillIfNeeded(): Promise<void> {
    if (!map || current !== null || autoDrilling) return
    if (map.getZoom() < AUTO_DRILL_ZOOM) return
    const province = provinceAtView()
    if (!province) return

    autoDrilling = true
    // 下级边界要联网拉（首次没有缓存），慢的时候要一秒多；先给一句"正在加载"，
    // 否则用户放大后盯着空白的地图，只会得出"放大也看不到市区县"的结论
    options.onNotice?.(`正在加载「${province.name}」的市/区县边界…`)
    try {
      const children = await loadChildrenGeo(province.adcode)
      parentProvince = { adcode: province.adcode, name: province.name, level: 'province' }
      current = parentProvince
      autoDrilled = true
      dimProvinces(province.adcode)
      renderDistricts(children, 12, false)
      options.onNotice?.(null)
      options.onSelectChange?.(parentProvince)
    } catch {
      // 暂无下级边界数据（港澳台等）或网络不可达：保持全国视图，但要把原因说出来
      options.onNotice?.(`「${province.name}」暂无下级的市/区县边界数据，可改用下拉框查看其他省份`)
    } finally {
      autoDrilling = false
    }
    // 拉取期间用户可能又把视野推远了（连续滚轮会让中间几级 zoomend 因"正在加载"而跳过），
    // 补一次跟随，避免"下钻到的省"和"眼前的省"对不上；同省或无下钻态时它自己会直接返回
    void followViewOnMove()
  }

  /**
   * 视野平移结束后的跟随：用户把地图拖到另一个省，边界应当跟着换。
   *
   * <p><b>为什么不能只看 zoomend</b>：拖动只触发 moveend，不触发 zoomend。用户的实际动作几乎
   * 都是"先放大、再把地图拖到我要看的城市"，只在 zoomend 里判断的话，他拖到福州之后什么都
   * 不会发生 —— 这正是"放大看不到市区县"最典型的一种复现路径。
   *
   * <p>只跟随**自动**下钻（{@code autoDrilled}）：用户通过下拉框/点击主动选的省份不被改动，
   * 否则他刚选定一个省、手一滑就把选择弄丢了。落在海里/省外时保持现状，不清空。
   */
  async function followViewOnMove(): Promise<void> {
    if (!map || !autoDrilled || autoDrilling) return
    if (map.getZoom() < AUTO_DRILL_ZOOM) return
    const province = provinceAtView()
    if (!province || province.adcode === current?.adcode) return
    // 用"重新自动下钻"的方式切换：先清空当前下钻态，再走同一套逻辑
    current = null
    parentProvince = null
    autoDrilled = false
    districtGroup.clearLayers()
    districtFeatures = []
    applyAllProvinceStyles()
    await autoDrillIfNeeded()
  }

  /** 回到全国视图（不飞视野的静默版本，供自动收回与对外动作共用） */
  function resetToCountry(fly: boolean): void {
    current = null
    parentProvince = null
    autoDrilled = false
    districtGroup.clearLayers()
    districtFeatures = []
    applyAllProvinceStyles()
    refreshLabels()
    options.onNotice?.(null)
    // 必须通知父组件：下拉框与"返回全国"面包屑都在父组件里，只清图层不通知的话，
    // 缩回全国后会出现"图上已经是全国、面板还写着甘肃省 + 返回全国"的错位
    options.onSelectChange?.(null)
    if (fly) map.flyTo([34.5, 106.5], 4, { duration: 0.55 })
  }

  async function drillIntoCity(cityAdcode: number, cityName: string): Promise<void> {
    try {
      const children = await loadChildrenGeo(cityAdcode)
      const region: AdminRegion = { adcode: cityAdcode, name: cityName, level: 'city' }
      current = region
      autoDrilled = false
      // 区/县尺度更小，允许放到更大缩放级
      renderDistricts(children, 12)
      options.onSelectChange?.(region)
    } catch (error) {
      console.warn('[admin-map] 区/县边界加载失败', error)
      throw error
    }
  }

  // ------------------------------------------------------------------
  // 对外动作
  // ------------------------------------------------------------------

  async function selectProvince(adcode: number | null): Promise<void> {
    // 返回全国（resetToCountry 内部已通知父组件）
    if (adcode === null) {
      resetToCountry(true)
      return
    }

    const shape = shapes.find((s) => s.adcode === adcode)
    if (!shape) return
    try {
      const children = await loadChildrenGeo(adcode)
      parentProvince = { adcode, name: shape.name, level: 'province' }
      current = parentProvince
      autoDrilled = false
      dimProvinces(adcode)
      // 省级下钻（直辖市直接是区/县，普通省是地级市）：放到 10~11 级看清边界
      renderDistricts(children, 11)
      options.onNotice?.(null)
      options.onSelectChange?.(parentProvince)
    } catch (error) {
      // 港澳台等暂无下级数据的区域：选中省并提示，不打断操作
      dimProvinces(adcode)
      districtGroup.clearLayers()
      districtFeatures = []
      current = { adcode, name: shape.name, level: 'province' }
      autoDrilled = false
      refreshLabels()
      options.onSelectChange?.(current)
      throw error
    }
  }

  async function goBack(): Promise<void> {
    if (current?.level === 'city' && parentProvince) {
      await selectProvince(parentProvince.adcode)
    } else {
      await selectProvince(null)
    }
  }

  function setColorMode(mode: ProvinceColorMode): void {
    colorMode = mode
    if (current) dimProvinces(current.level === 'city' ? parentProvince?.adcode ?? null : current.adcode)
    else applyAllProvinceStyles()
  }

  function destroy(): void {
    map.off('zoomend', onZoomEnd)
    map.off('moveend', onMoveEnd)
    districtGroup.clearLayers()
    labelGroup.clearLayers()
    map.removeLayer(districtGroup)
    map.removeLayer(labelGroup)
    shapes.forEach((s) => map.removeLayer(s.layer))
    shapes = []
    districtFeatures = []
  }

  return {
    provinces,
    get current() {
      return current
    },
    ready: true,
    setColorMode,
    setLabelsVisible,
    selectProvince,
    goBack,
    destroy,
  }
}
