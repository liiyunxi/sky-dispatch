import L from 'leaflet'

/**
 * 中国地图静态背景（海面 / 经纬网 / 主要城市标注）。
 *
 * 省级陆地与省界由 `map/AdminBoundaryLayer.ts` 负责（支持分省着色与省→市→区县下钻），
 * 本模块只铺不参与交互的背景层，两者共用自建 pane 的 z 序。
 *
 * 数据来源：省级 GeoJSON `public/geo/china.json`（由 `scripts/fetch-china-geo.mjs` 下载，
 * 阿里云 DataV.GeoAtlas，含 35 个省级要素与各自中心点），在 AdminBoundaryLayer 中加载。
 *
 * 两个设计决定：
 *
 *  1. **独立 pane**：背景压在程序化路网之下、空域/航路/无人机之上。
 *     Leaflet 默认把 GridLayer 放在 tilePane(z=200)、矢量放在 overlayPane(z=400)，
 *     所以这里自建 `chinaBase`(z=180) 与 `cityLabel`(z=190) 两个 pane。
 *  2. **失败可降级**：背景层本身不依赖 GeoJSON，始终可渲染；省级数据缺失时
 *     调用方（DroneMap）会用程序化瓦片兜底并在 HUD 提示。
 */

/**
 * 中国大致范围，用于海面底色、地图边界限制与"全国视野"自适应。
 *
 * <p>南界取 15°N：DataV 的"南海诸岛"是独立要素（3~20°N），若把它算进来，全国视野会被拉低近 20%，
 * 大陆主体反而看不清；它本身适合作为陆地下方的角标存在，因此不参与 fit。
 */
export const CHINA_BOUNDS = L.latLngBounds(L.latLng(15.0, 70.0), L.latLng(55.0, 140.0))

/**
 * 城市标注：给底图提供方位参照。经纬度取市中心，够用。
 *
 * <p><b>为什么要覆盖全部省会</b>：早期只放了 18 个主要城市，用户会直接指出
 * "福州呢？"—— 省会是最基本的地理参照，缺一个就像地图少了一块。
 * 现在 34 个省会/直辖市/特别行政区齐全（含台北、香港、澳门），
 * 另外补几个计划单列市与重要地级市做密度补充。
 *
 * @param label 显示名（有简称用简称，省版面）
 * @param rank 重要度：0 = 省会/直辖市（全程显示），1 = 重要城市（放大后才显示）
 */
interface CityLabel {
  name: string
  lat: number
  lon: number
  rank: 0 | 1
}

const CITY_LABELS: CityLabel[] = [
  // ---- 省会 / 直辖市 / 特别行政区（rank 0）----
  { name: '北京', lat: 39.9042, lon: 116.4074, rank: 0 },
  { name: '天津', lat: 39.3434, lon: 117.3616, rank: 0 },
  { name: '石家庄', lat: 38.0428, lon: 114.5149, rank: 0 },
  { name: '太原', lat: 37.8706, lon: 112.5489, rank: 0 },
  { name: '呼和浩特', lat: 40.8426, lon: 111.7492, rank: 0 },
  { name: '沈阳', lat: 41.8057, lon: 123.4315, rank: 0 },
  { name: '长春', lat: 43.8171, lon: 125.3235, rank: 0 },
  { name: '哈尔滨', lat: 45.8038, lon: 126.5349, rank: 0 },
  { name: '上海', lat: 31.2304, lon: 121.4737, rank: 0 },
  { name: '南京', lat: 32.0603, lon: 118.7969, rank: 0 },
  { name: '杭州', lat: 30.2741, lon: 120.1551, rank: 0 },
  { name: '合肥', lat: 31.8206, lon: 117.2272, rank: 0 },
  { name: '福州', lat: 26.0745, lon: 119.2965, rank: 0 },
  { name: '南昌', lat: 28.682, lon: 115.8579, rank: 0 },
  { name: '济南', lat: 36.6512, lon: 117.1201, rank: 0 },
  { name: '郑州', lat: 34.7466, lon: 113.6254, rank: 0 },
  { name: '武汉', lat: 30.5928, lon: 114.3055, rank: 0 },
  { name: '长沙', lat: 28.2282, lon: 112.9388, rank: 0 },
  { name: '广州', lat: 23.1291, lon: 113.2644, rank: 0 },
  { name: '南宁', lat: 22.817, lon: 108.3665, rank: 0 },
  { name: '海口', lat: 20.0442, lon: 110.1999, rank: 0 },
  { name: '重庆', lat: 29.563, lon: 106.5516, rank: 0 },
  { name: '成都', lat: 30.5728, lon: 104.0668, rank: 0 },
  { name: '贵阳', lat: 26.647, lon: 106.6302, rank: 0 },
  { name: '昆明', lat: 24.8801, lon: 102.8329, rank: 0 },
  { name: '拉萨', lat: 29.652, lon: 91.1721, rank: 0 },
  { name: '西安', lat: 34.3416, lon: 108.9398, rank: 0 },
  { name: '兰州', lat: 36.0611, lon: 103.8343, rank: 0 },
  { name: '西宁', lat: 36.6171, lon: 101.7782, rank: 0 },
  { name: '银川', lat: 38.4872, lon: 106.2309, rank: 0 },
  { name: '乌鲁木齐', lat: 43.8256, lon: 87.6168, rank: 0 },
  { name: '台北', lat: 25.033, lon: 121.5654, rank: 0 },
  { name: '香港', lat: 22.3193, lon: 114.1694, rank: 0 },
  { name: '澳门', lat: 22.1987, lon: 113.5439, rank: 0 },

  // ---- 计划单列市 / 重要节点城市（rank 1：放大后才显示，避免低缩放糊成一片）----
  { name: '深圳', lat: 22.5431, lon: 114.0579, rank: 1 },
  { name: '青岛', lat: 36.0671, lon: 120.3826, rank: 1 },
  { name: '大连', lat: 38.914, lon: 121.6147, rank: 1 },
  { name: '宁波', lat: 29.8683, lon: 121.544, rank: 1 },
  { name: '厦门', lat: 24.4798, lon: 118.0894, rank: 1 },
  { name: '苏州', lat: 31.2989, lon: 120.5853, rank: 1 },
  { name: '无锡', lat: 31.4912, lon: 120.3119, rank: 1 },
  { name: '温州', lat: 27.9938, lon: 120.6994, rank: 1 },
  { name: '东莞', lat: 23.0207, lon: 113.7518, rank: 1 },
  { name: '佛山', lat: 23.0215, lon: 113.1214, rank: 1 },
  { name: '珠海', lat: 22.2707, lon: 113.5767, rank: 1 },
  { name: '汕头', lat: 23.3535, lon: 116.682, rank: 1 },
  { name: '徐州', lat: 34.2058, lon: 117.2841, rank: 1 },
  { name: '洛阳', lat: 34.6197, lon: 112.454, rank: 1 },
  { name: '唐山', lat: 39.6304, lon: 118.1802, rank: 1 },
  { name: '烟台', lat: 37.4638, lon: 121.4479, rank: 1 },
  { name: '潍坊', lat: 36.7069, lon: 119.1618, rank: 1 },
  { name: '南通', lat: 31.9802, lon: 120.8943, rank: 1 },
  { name: '常州', lat: 31.8107, lon: 119.9742, rank: 1 },
  { name: '嘉兴', lat: 30.7522, lon: 120.75, rank: 1 },
  { name: '泉州', lat: 24.8741, lon: 118.6757, rank: 1 },
  { name: '台中', lat: 24.1477, lon: 120.6736, rank: 1 },
  { name: '高雄', lat: 22.6273, lon: 120.3014, rank: 1 },
]

/** 底图配色：深色科技风（深海蓝底 + 略亮的陆地 + 冷青省界），与整体暗色主题一致 */
const STYLE = {
  sea: '#04101d',
  land: '#0a1a2a',
  landHover: '#0e2437',
  provinceBorder: '#1b425c',
  graticule: '#12324a',
}

/**
 * 把中国底图挂到地图上。
 *
 * @returns 数据加载成功返回 true；失败返回 false（调用方据此提示用户）
 */
export async function attachChinaBaseLayer(map: L.Map): Promise<boolean> {
  // ---- 自建 pane，保证图层压叠顺序正确 ----
  if (!map.getPane('chinaBase')) {
    const pane = map.createPane('chinaBase')
    // 低于 tilePane(200)：程序化的街区细节瓦片才能盖在中国底图之上
    pane.style.zIndex = '180'
  }
  if (!map.getPane('cityLabel')) {
    const pane = map.createPane('cityLabel')
    pane.style.zIndex = '190'
    // 城市名不需要接收鼠标事件，否则会挡住下方地图的拖拽
    pane.style.pointerEvents = 'none'
  }

  // ---- 海面底色：一块覆盖中国范围的大矩形，让陆地"浮"在海面上 ----
  L.rectangle(CHINA_BOUNDS, {
    pane: 'chinaBase',
    stroke: false,
    fillColor: STYLE.sea,
    fillOpacity: 1,
    interactive: false,
  }).addTo(map)

  // ---- 经纬网：每 5° 一条，给地图以"地理坐标系"的质感 ----
  const graticule: L.Polyline[] = []
  for (let lat = 15; lat <= 55; lat += 5) {
    graticule.push(
      L.polyline([[lat, 70], [lat, 140]], {
        pane: 'chinaBase',
        color: STYLE.graticule,
        weight: 0.6,
        opacity: 0.55,
        dashArray: '3 5',
        interactive: false,
      }),
    )
  }
  for (let lon = 70; lon <= 140; lon += 5) {
    graticule.push(
      L.polyline([[15, lon], [55, lon]], {
        pane: 'chinaBase',
        color: STYLE.graticule,
        weight: 0.6,
        opacity: 0.55,
        dashArray: '3 5',
        interactive: false,
      }),
    )
  }
  const graticuleLayer = L.layerGroup(graticule).addTo(map)

  // 省级陆地与省界由 map/AdminBoundaryLayer.ts 负责（支持分色与省/市/区县下钻），
  // 这里只铺海面、经纬网与城市标注等静态背景，避免同一份 GeoJSON 重复渲染。
  // ---- 城市标注：分两档，按缩放决定显示到哪一档 ----
  // 低缩放（全国）只标省会，否则几十个城市名会糊在一起、互相压叠；
  // 放大到省级尺度才补上重要地级市。
  const labelRank: Record<number, CityLabel[]> = {
    0: CITY_LABELS.filter((c) => c.rank === 0),
    1: CITY_LABELS,
  }
  const layers: Array<{ zoom: number; layer: L.LayerGroup }> = []
  for (const zoom of [0, 1]) {
    const markers = labelRank[zoom].map((city) =>
      L.marker([city.lat, city.lon], {
        pane: 'cityLabel',
        interactive: false,
        keyboard: false,
        icon: L.divIcon({
          html: `<div class="city-label${city.rank === 0 ? ' city-label-capital' : ''}"><span class="city-dot"></span>${city.name}</div>`,
          className: 'city-label-host',
          iconSize: [0, 0],
          iconAnchor: [-2, 8],
        }),
      }),
    )
    layers.push({ zoom, layer: L.layerGroup(markers) })
  }

  // 缩放太小时城市名会糊成一片 —— 反而更难读，所以两档分别控制显隐
  const syncCityVisibility = () => {
    const zoom = map.getZoom()
    layers.forEach(({ zoom: rank, layer }) => {
      const shouldShow = zoom >= 4 && (rank === 0 ? zoom < 7 : zoom >= 7)
      const has = map.hasLayer(layer)
      if (shouldShow && !has) layer.addTo(map)
      else if (!shouldShow && has) map.removeLayer(layer)
    })
    // 经纬网在放大到城市尺度后没有意义，反而干扰，z>=11 隐藏
    if (zoom >= 11) {
      if (map.hasLayer(graticuleLayer)) map.removeLayer(graticuleLayer)
    } else if (!map.hasLayer(graticuleLayer)) {
      graticuleLayer.addTo(map)
    }
  }
  syncCityVisibility()
  map.on('zoomend', syncCityVisibility)

  return true
}

/**
 * 让中国底图与程序化图层视觉协调的辅助说明（供调用方参考）：
 *  - 程序化的街区瓦片在 z>=12 才叠加，正好覆盖省级边界变得过于粗糙的尺度；
 *  - 两者配色都取自同一套冷色，因此叠加时不会出现"两张地图拼在一起"的割裂感。
 */
export const CHINA_BASE_STYLE = STYLE
