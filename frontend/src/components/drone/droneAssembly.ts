import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import type { DroneDetailSpec } from './droneSpec'

/**
 * 零件级的无人机三维装配体。
 *
 * <p><b>和 {@code droneGeometry.ts} 的分工</b>：那个模块画的是"地图上一眼认出机型"的
 * 简化轮廓（一架飞机十几个网格，二十多架同屏也不卡）；这个模块画的是"贴到零件上
 * 看细节"的解剖模型 —— 单机约 40 类部件、200 多个网格，只有一架飞机独占屏幕时才用。
 *
 * <p><b>为什么每个零件都单独成组</b>：解剖视图要支持三件事，都要求零件是可独立操作的
 * 对象，而不是一坨合并网格：
 * <ul>
 *   <li><b>拆解</b>：沿各自方向平移，露出内部（{@link PartInstance.dir}/{@link PartInstance.dist}）；</li>
 *   <li><b>高亮</b>：点一个零件，只有它发光（所以材质必须按零件复制，不能共享）；</li>
 *   <li><b>透视</b>：壳体变半透明，看到里面的电池、飞控、电调（按零件改材质透明度）。</li>
 * </ul>
 *
 * <p>几何尺寸全部按真实米制建模，比例就是真机比例 —— 这也是"轴距 550 mm"
 * 这句话在画面上能被量出来的前提。
 */

const UP = new THREE.Vector3(0, 1, 0)

/** 一个零件实例：同一类零件可能有多份（四/六/八支机臂、两只舱门） */
export interface PartInstance {
  /** 部件 id，对应 droneParts.ts 里的 DronePart.id */
  partId: string
  /** 该零件的三维节点（位置即"拆解位移"，静止时为 0） */
  object: THREE.Object3D
  /** 拆解方向（世界坐标，已归一化）；零向量表示不参与拆解 */
  dir: THREE.Vector3
  /** 拆解距离（米） */
  dist: number
  /** 静止状态下的几何中心（root 局部坐标），用于相机聚焦与标注定位 */
  center: THREE.Vector3
  /** 包围球半径，用于决定聚焦时相机退多远 */
  radius: number
  /** 静止状态下的轴对齐包围盒（root 局部坐标）：用来算"整机取景"该退多远 */
  box: THREE.Box3
  /** 该零件独占的材质（高亮 / 透视直接改这些，不会影响别的零件） */
  materials: THREE.MeshStandardMaterial[]
}

export interface DroneAssembly {
  /** 整机根节点；调用方负责放到场景里的合适位置 */
  root: THREE.Group
  instances: PartInstance[]
  /** 部件 id → 实例列表 */
  byPart: Map<string, PartInstance[]>
  spec: DroneDetailSpec
  /** 透视模式下要变透明的"外壳"部件 */
  shellParts: string[]
  /** 螺旋桨（可选的自转动画用） */
  propellers: THREE.Object3D[]
}

// ---------------------------------------------------------------------------
// 几何 / 材质小工具
// ---------------------------------------------------------------------------

/** 圆角盒：直边在真实感渲染里非常"塑料"，所有面板统一带倒角 */
function box(w: number, h: number, d: number, r = 0.005): THREE.BufferGeometry {
  const radius = Math.max(0.0006, Math.min(r, Math.min(w, h, d) * 0.45))
  return new RoundedBoxGeometry(w, h, d, 2, radius)
}

function cyl(rt: number, rb: number, h: number, seg = 18): THREE.BufferGeometry {
  return new THREE.CylinderGeometry(rt, rb, h, seg)
}

/** 标准 PBR 材质；每次调用都是新实例（高亮要按零件独立改，不能共享） */
function std(
  color: number,
  opts: Partial<THREE.MeshStandardMaterialParameters> = {},
): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({ color, roughness: 0.45, metalness: 0.3, ...opts })
}

/** 两点之间的一根圆柱：支腿、天线、拉杆都用它，省掉一堆手算四元数 */
function strut(
  a: THREE.Vector3,
  b: THREE.Vector3,
  radius: number,
  material: THREE.Material,
  seg = 10,
): THREE.Mesh {
  const delta = new THREE.Vector3().subVectors(b, a)
  const len = Math.max(1e-4, delta.length())
  const mesh = new THREE.Mesh(cyl(radius, radius, len, seg), material)
  mesh.position.copy(a).add(b).multiplyScalar(0.5)
  mesh.quaternion.setFromUnitVectors(UP, delta.normalize())
  return mesh
}

/**
 * 一片桨叶：贝塞尔轮廓 + 展向扭转（washout）。
 *
 * <p>三件事决定它像不像真桨：桨根窄、中段宽、桨尖收窄并后掠；叶片有厚度；
 * 沿展向的安装角逐渐变小（桨尖螺距小，避免叶尖先失速）。
 * 少了扭转，远看就是一根"黑棍子" —— 这是程序化建模最容易露怯的地方。
 */
function bladeGeometry(radius: number, chord: number): THREE.BufferGeometry {
  const rootX = radius * 0.1
  const shape = new THREE.Shape()
  shape.moveTo(rootX, -chord * 0.24)
  shape.bezierCurveTo(radius * 0.3, -chord * 0.6, radius * 0.62, -chord * 0.56, radius * 0.9, -chord * 0.24)
  shape.quadraticCurveTo(radius * 0.99, -chord * 0.08, radius * 0.99, 0)
  shape.quadraticCurveTo(radius * 0.99, chord * 0.08, radius * 0.9, chord * 0.17)
  shape.bezierCurveTo(radius * 0.62, chord * 0.36, radius * 0.3, chord * 0.4, rootX, chord * 0.26)
  shape.closePath()

  const geo = new THREE.ExtrudeGeometry(shape, {
    depth: Math.max(0.0018, chord * 0.11),
    bevelEnabled: false,
    curveSegments: 14,
  })
  // Shape 在 XY 平面挤出到 Z：转到水平（厚度朝 Y），并给一个基础安装角（桨根约 17°）
  geo.rotateX(-Math.PI / 2 + 0.3)

  // 展向扭转：绕叶片长轴（X）逐点旋转。真桨的安装角沿展向递减，
  // 但始终保持正角度 —— 若减到负值，叶片会"翻面"，看起来像一根扭麻花。
  const pos = geo.attributes.position
  for (let i = 0; i < pos.count; i += 1) {
    const x = pos.getX(i)
    const t = Math.min(1, Math.max(0, (x - rootX) / (radius - rootX)))
    const twist = -0.16 * t
    const c = Math.cos(twist)
    const s = Math.sin(twist)
    const y = pos.getY(i)
    const z = pos.getZ(i)
    pos.setY(i, y * c - z * s)
    pos.setZ(i, y * s + z * c)
  }
  pos.needsUpdate = true
  geo.computeVertexNormals()
  return geo
}

/** 机身上的编号喷涂：用 canvas 生成贴图，避免为了一行字引入字体文件 */
function makeTailDecal(text: string, accent: number): THREE.CanvasTexture | null {
  if (typeof document === 'undefined') return null
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 128
  const ctx = canvas.getContext('2d')
  if (!ctx) return null

  const accentCss = `#${accent.toString(16).padStart(6, '0')}`
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  // 左侧色条 + 机身编号（横排，字号尽量撑满，远看才有识别度）
  ctx.fillStyle = accentCss
  ctx.fillRect(0, 26, 9, 76)
  ctx.fillStyle = '#1d232c'
  ctx.font = 'bold 84px "Segoe UI", "Microsoft YaHei", Arial, sans-serif'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, 26, 68)

  const tex = new THREE.CanvasTexture(canvas)
  tex.colorSpace = THREE.SRGBColorSpace
  tex.anisotropy = 4
  return tex
}

// ---------------------------------------------------------------------------
// 构建上下文
// ---------------------------------------------------------------------------

interface Ctx {
  spec: DroneDetailSpec
  root: THREE.Group
  instances: PartInstance[]
  byPart: Map<string, PartInstance[]>
  /** 全局尺寸系数：以 SKY-X4 为 1，用来缩放"与机型无关"的细节尺寸 */
  k: number
  /** 机身编号：喷在机身侧面 */
  decalText?: string
}

/** 收集一个零件下的全部材质（高亮/透视要逐材质改） */
function collectMaterials(root: THREE.Object3D): THREE.MeshStandardMaterial[] {
  const set = new Set<THREE.MeshStandardMaterial>()
  root.traverse((obj) => {
    if (!(obj instanceof THREE.Mesh)) return
    const mat = obj.material
    if (Array.isArray(mat)) mat.forEach((m) => set.add(m as THREE.MeshStandardMaterial))
    else set.add(mat as THREE.MeshStandardMaterial)
  })
  return [...set]
}

/**
 * 把一个零件挂到整机上，并登记它的拆解方向与几何中心。
 *
 * @param dir  拆解方向（不必归一化，内部会处理）
 * @param dist 拆解距离（米）
 */
function addPart(
  ctx: Ctx,
  partId: string,
  object: THREE.Object3D,
  dir: THREE.Vector3,
  dist: number,
): PartInstance {
  object.position.set(0, 0, 0)
  const bounds = new THREE.Box3().setFromObject(object)
  const center = bounds.getCenter(new THREE.Vector3())
  const radius = Math.max(0.01, bounds.getBoundingSphere(new THREE.Sphere()).radius)
  const materials = collectMaterials(object)
  const direction = dir.lengthSq() > 1e-8 ? dir.clone().normalize() : new THREE.Vector3()

  // 打标签：射线拾取命中任意子网格后，靠它一路上溯到所属零件
  object.userData.partId = partId

  ctx.root.add(object)
  const instance: PartInstance = {
    partId,
    object,
    dir: direction,
    dist,
    center,
    radius,
    box: bounds,
    materials,
  }
  ctx.instances.push(instance)
  const list = ctx.byPart.get(partId)
  if (list) list.push(instance)
  else ctx.byPart.set(partId, [instance])
  return instance
}

// ---------------------------------------------------------------------------
// 结构：壳体 / 承力板 / 起落架
// ---------------------------------------------------------------------------

/** 下壳体 + 上盖 + 涂装细节 */
function buildShell(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const H = spec.bodyH

  // ---- 下壳体：三种机型三种形态 ----
  const lower = new THREE.Group()
  if (spec.style === 'hex') {
    // 六边形机身：圆柱转 30°，再沿 Z 拉长，得到"长六边形"的经典物流机截面
    const body = new THREE.Mesh(cyl(W * 0.56, W * 0.5, H * 0.55, 6), std(spec.shell, { roughness: 0.4, metalness: 0.2 }))
    body.rotation.y = Math.PI / 6
    body.scale.z = (L / W) * 0.95
    body.position.y = H * 0.275
    lower.add(body)
  } else {
    const body = new THREE.Mesh(
      box(W, H * 0.55, L, 0.012 * k),
      std(spec.shell, { roughness: 0.4, metalness: 0.2 }),
    )
    body.position.y = H * 0.275
    lower.add(body)
  }

  // 上下壳之间的一圈深色"腰带"：既遮住分模缝，又让白色机身有个断开的地方。
  // 做成四根边框而不是一整块板 —— 爆炸图从上往下看时，整块板会像一块黑盖子，
  // 反而让人以为这是另一个零件。
  const beltT = 0.008 * k
  ;[
    [0, 1],
    [0, -1],
  ].forEach(([ox, oz]) => {
    const bar = new THREE.Mesh(
      box(W * 1.008, H * 0.055, beltT, 0.002),
      std(spec.graphite, { roughness: 0.45, metalness: 0.45 }),
    )
    bar.position.set(ox, H * 0.585, oz * (L * 0.5 - beltT * 0.5))
    lower.add(bar)
  })
  ;[-1, 1].forEach((side) => {
    const bar = new THREE.Mesh(
      box(beltT, H * 0.055, L * 1.002, 0.002),
      std(spec.graphite, { roughness: 0.45, metalness: 0.45 }),
    )
    bar.position.set(side * (W * 0.5 - beltT * 0.5), H * 0.585, 0)
    lower.add(bar)
  })

  // 机头下方的"下巴"：给云台让位，同时让侧面轮廓不是一块砖
  const chin = new THREE.Mesh(
    box(W * 0.66, H * 0.3, L * 0.16, 0.008 * k),
    std(spec.shell, { roughness: 0.42, metalness: 0.2 }),
  )
  chin.position.set(0, H * 0.16, L * 0.46)
  lower.add(chin)

  // 机头橡胶防撞条：真机上前脸最容易磕，这条黑色包边是"用过"的痕迹
  const bumper = new THREE.Mesh(
    box(W * 0.7, H * 0.07, 0.012 * k, 0.003),
    std(0x14171b, { roughness: 0.85, metalness: 0.05 }),
  )
  bumper.position.set(0, H * 0.15, L * 0.525)
  lower.add(bumper)

  // 腰线：型号色细条（左右各一条，避免在曲面上贴合）
  ;[-1, 1].forEach((side) => {
    const stripe = new THREE.Mesh(
      box(0.0016, H * 0.05, L * 0.7, 0.0006),
      std(spec.accent, { roughness: 0.35, metalness: 0.4 }),
    )
    stripe.position.set(side * (W * 0.5 + 0.0004), H * 0.4, -L * 0.02)
    lower.add(stripe)
  })

  // 侧面散热格栅：三组斜缝，暗示里面有电调和电池
  ;[-1, 1].forEach((side) => {
    for (let i = 0; i < 3; i += 1) {
      const slot = new THREE.Mesh(
        box(0.0016, H * 0.045, L * 0.045, 0.0008),
        std(0x11151a, { roughness: 0.85, metalness: 0.1 }),
      )
      slot.position.set(side * (W * 0.5 + 0.0002), H * 0.27, -L * 0.28 + i * L * 0.06)
      lower.add(slot)
    }
  })

  // 尾部电池仓门：一条缝 + 一个机械锁扣（电池从后面抽出来换）
  const doorSeam = new THREE.Mesh(box(W * 0.66, H * 0.42, 0.002), std(0x2a3038, { roughness: 0.6, metalness: 0.3 }))
  doorSeam.position.set(0, H * 0.26, -L * 0.5 - 0.0006)
  lower.add(doorSeam)
  const doorLatch = new THREE.Mesh(box(W * 0.1, H * 0.07, 0.006 * k, 0.002), std(spec.accent, { roughness: 0.4, metalness: 0.5 }))
  doorLatch.position.set(0, H * 0.44, -L * 0.5 - 0.004)
  lower.add(doorLatch)

  // 机身编号喷涂（两侧）
  const decal = makeTailDecal(ctx.decalText ?? spec.code, spec.accent)
  if (decal) {
    ;[-1, 1].forEach((side) => {
      const plane = new THREE.Mesh(
        new THREE.PlaneGeometry(L * 0.56, L * 0.14),
        new THREE.MeshStandardMaterial({
          map: decal,
          transparent: true,
          roughness: 0.55,
          metalness: 0.05,
          depthWrite: false,
          // 左侧贴图被镜像，必须双面渲染才不会被背面剔除
          side: THREE.DoubleSide,
        }),
      )
      plane.position.set(side * (W * 0.5 + 0.0012), H * 0.33, -L * 0.03)
      plane.rotation.y = side > 0 ? Math.PI / 2 : -Math.PI / 2
      // 左侧沿 -Z 排字，否则从外面看是反的
      plane.scale.x = side > 0 ? 1 : -1
      lower.add(plane)
    })
  }

  addPart(ctx, 'shell-bottom', lower, new THREE.Vector3(0, -1, 0), 0.3 * (spec.podDistance / 0.275))

  // ---- 上盖 ----
  const upper = new THREE.Group()
  if (spec.style === 'hex') {
    const cap = new THREE.Mesh(cyl(W * 0.5, W * 0.56, H * 0.36, 6), std(spec.panel, { roughness: 0.38, metalness: 0.2 }))
    cap.rotation.y = Math.PI / 6
    cap.scale.z = (L / W) * 0.95
    cap.position.y = H * 0.8
    upper.add(cap)
    // 圆顶：机身中部鼓起的雷达罩（压扁一点，别做成一个球）
    const dome = new THREE.Mesh(
      new THREE.SphereGeometry(W * 0.2, 20, 12, 0, Math.PI * 2, 0, Math.PI / 2),
      std(spec.panel, { roughness: 0.3, metalness: 0.25 }),
    )
    dome.scale.y = 0.5
    dome.position.y = H * 0.98
    upper.add(dome)
    // 服务舱盖
    const hatch = new THREE.Mesh(box(W * 0.3, 0.004, L * 0.22, 0.002), std(spec.shell, { roughness: 0.45, metalness: 0.2 }))
    hatch.position.set(0, H * 0.965, -L * 0.02)
    upper.add(hatch)
  } else if (spec.style === 'heavy') {
    // 重载机：装甲式平顶 + 两道加强梁 + 外露电池舱盖
    const cap = new THREE.Mesh(box(W * 0.99, H * 0.37, L * 0.99, 0.012 * k), std(spec.panel, { roughness: 0.45, metalness: 0.28 }))
    cap.position.y = H * 0.81
    upper.add(cap)
    ;[-1, 1].forEach((side) => {
      const beam = new THREE.Mesh(box(0.02 * k, H * 0.06, L * 0.82, 0.004), std(spec.graphite, { roughness: 0.5, metalness: 0.5 }))
      beam.position.set(side * W * 0.28, H * 1.0, 0)
      upper.add(beam)
    })
    // 顶部检修口
    const hatch = new THREE.Mesh(box(W * 0.36, 0.004, L * 0.2, 0.002), std(spec.shell, { roughness: 0.5, metalness: 0.25 }))
    hatch.position.set(0, H * 0.99, L * 0.16)
    upper.add(hatch)
  } else {
    const cap = new THREE.Mesh(box(W * 0.95, H * 0.36, L * 0.95, 0.012 * k), std(spec.panel, { roughness: 0.38, metalness: 0.2 }))
    cap.position.y = H * 0.8
    upper.add(cap)
    // 检修口：微微凸起的一块，带把手槽——上盖不该是一整块白板
    const hatch = new THREE.Mesh(box(W * 0.44, H * 0.045, L * 0.26, 0.006 * k), std(spec.panel, { roughness: 0.34, metalness: 0.24 }))
    hatch.position.set(0, H * 0.97, L * 0.12)
    upper.add(hatch)
    const grip = new THREE.Mesh(box(W * 0.16, 0.003, L * 0.02, 0.001), std(spec.graphite, { roughness: 0.5, metalness: 0.4 }))
    grip.position.set(0, H * 0.994, -L * 0.02)
    upper.add(grip)
    // 尾部排气格栅
    for (let i = 0; i < 4; i += 1) {
      const slot = new THREE.Mesh(box(W * 0.26, 0.0022, 0.004), std(0x11151a, { roughness: 0.85 }))
      slot.position.set(0, H * 0.96, -L * 0.22 - i * 0.009 * k)
      upper.add(slot)
    }
  }

  // 定位螺钉：四角各一颗（解剖视图里"能数出零件"就靠这种小东西）
  ;[-1, 1].forEach((sx) => {
    ;[-1, 1].forEach((sz) => {
      const screw = new THREE.Mesh(cyl(0.0032 * k, 0.0032 * k, 0.0018 * k, 10), std(0x9aa5b4, { roughness: 0.25, metalness: 0.95 }))
      screw.position.set(sx * W * 0.4, H * 0.975, sz * L * 0.4)
      upper.add(screw)
    })
  })

  addPart(ctx, 'shell-top', upper, new THREE.Vector3(0, 1, 0), 0.42 * (spec.podDistance / 0.275))

  // ---- 机腹快拆挂载板 ----
  const belly = new THREE.Group()
  const plate = new THREE.Mesh(box(W * 0.62, 0.008 * k, L * 0.46, 0.003), std(spec.graphite, { roughness: 0.5, metalness: 0.55 }))
  plate.position.y = -0.004
  belly.add(plate)
  ;[-1, 1].forEach((sx) => {
    ;[-1, 1].forEach((sz) => {
      const pin = new THREE.Mesh(cyl(0.0045 * k, 0.0045 * k, 0.006 * k, 10), std(0xc9a227, { roughness: 0.3, metalness: 0.9 }))
      pin.position.set(sx * W * 0.24, -0.008, sz * L * 0.19)
      belly.add(pin)
    })
  })
  addPart(ctx, 'belly-mount', belly, new THREE.Vector3(0, -1, 0), 0.44 * (spec.podDistance / 0.275))
}

/** 中心承力板：碳板 + 加强肋 + 立柱 + 机臂根座 */
function buildCenterFrame(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const H = spec.bodyH
  const g = new THREE.Group()

  const plate = new THREE.Mesh(
    box(W * 0.92, 0.011 * k, L * 0.84, 0.003),
    std(0x191d22, { roughness: 0.55, metalness: 0.2 }),
  )
  plate.position.y = H * 0.585
  g.add(plate)

  // 纵向加强肋
  ;[-1, 1].forEach((side) => {
    const rib = new THREE.Mesh(box(0.006 * k, 0.01 * k, L * 0.7, 0.002), std(0x2a3038, { roughness: 0.5, metalness: 0.4 }))
    rib.position.set(side * W * 0.28, H * 0.585 + 0.008 * k, 0)
    g.add(rib)
  })

  // 四角立柱：把上盖的载荷传回承力板
  ;[-1, 1].forEach((sx) => {
    ;[-1, 1].forEach((sz) => {
      const post = new THREE.Mesh(cyl(0.0045 * k, 0.0045 * k, H * 0.34, 10), std(0x2a3038, { roughness: 0.4, metalness: 0.7 }))
      post.position.set(sx * W * 0.38, H * 0.7, sz * L * 0.38)
      g.add(post)
    })
  })

  // 机臂根座：与折叠铰链对接的金属接头
  for (let i = 0; i < spec.arms; i += 1) {
    const angle = Math.PI / spec.arms + (i * 2 * Math.PI) / spec.arms
    const mount = new THREE.Mesh(cyl(0.017 * k, 0.02 * k, 0.024 * k, 14), std(0x4b5563, { roughness: 0.35, metalness: 0.85 }))
    mount.position.set(Math.cos(angle) * W * 0.42, H * 0.585, Math.sin(angle) * W * 0.42)
    g.add(mount)
  }

  addPart(ctx, 'center-frame', g, new THREE.Vector3(), 0)
}

/** 起落架：两根滑橇 + 前后斜撑 + 前端上翘 + 橡胶脚垫 */
function buildLandingGear(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const y = -spec.gearHeight
  const half = spec.skidLength * 0.5
  const gearMat = () => std(0x1b1f25, { roughness: 0.5, metalness: 0.35 })

  ;[-1, 1].forEach((side) => {
    const g = new THREE.Group()
    const x = side * spec.skidSpread

    // 主管：略短于前后支腿的跨距，前端留出上翘段
    g.add(strut(new THREE.Vector3(x, y, -half * 0.9), new THREE.Vector3(x, y, half * 0.6), 0.0095 * k, gearMat(), 12))
    // 前端上翘：着陆时先接触地面的是这一段，能把冲击导成"滑"而不是"戳"
    g.add(strut(new THREE.Vector3(x, y, half * 0.6), new THREE.Vector3(x, y + 0.03 * k, half * 1.0), 0.0095 * k, gearMat(), 12))

    // 前后斜撑：从机腹斜向外下方撑到滑橇
    ;[-1, 1].forEach((end) => {
      const top = new THREE.Vector3(side * W * 0.34, -0.002, end * half * 0.66)
      const bottom = new THREE.Vector3(x, y + 0.004 * k, end * half * 0.6)
      g.add(strut(top, bottom, 0.0066 * k, gearMat()))
      // 撑杆与滑橇之间的连接夹
      const clampMesh = new THREE.Mesh(box(0.018 * k, 0.012 * k, 0.014 * k, 0.003), std(0x39424e, { roughness: 0.4, metalness: 0.7 }))
      clampMesh.position.set(x, y + 0.004 * k, end * half * 0.6)
      g.add(clampMesh)
    })

    // 橡胶脚垫：三处，落地时最先接触地面
    ;[-0.72, -0.1, 0.5].forEach((t) => {
      const pad = new THREE.Mesh(box(0.018 * k, 0.005 * k, 0.032 * k, 0.0018), std(0x0e1116, { roughness: 0.95, metalness: 0.05 }))
      pad.position.set(x, y - 0.0105 * k, t * half)
      g.add(pad)
    })

    // 端头封盖
    ;[-0.9, 1.0].forEach((t) => {
      const cap = new THREE.Mesh(new THREE.SphereGeometry(0.0095 * k, 10, 8), gearMat())
      cap.position.set(x, t > 0 ? y + 0.03 * k : y, t * half)
      g.add(cap)
    })

    addPart(ctx, 'skid', g, new THREE.Vector3(0, -1, 0), 0.52 * (spec.podDistance / 0.275))
  })
}

// ---------------------------------------------------------------------------
// 动力：机臂 / 电机 / 桨 / 电调 / 电池 / 配电板 / 线束
// ---------------------------------------------------------------------------

/** 一支机臂及其末端的电机、桨、电调（各自是独立零件，拆解方向不同） */
function buildArmUnit(ctx: Ctx, index: number): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const H = spec.bodyH
  const r0 = W * 0.42
  const r1 = spec.podDistance
  const yArm = H * 0.585
  const angle = Math.PI / spec.arms + (index * 2 * Math.PI) / spec.arms
  const radial = new THREE.Vector3(Math.cos(angle), 0, Math.sin(angle))
  const K = spec.podDistance / 0.275

  // 局部 +X 指向机臂外侧：把整组绕 Y 转 -angle 即可对齐径向
  const rot = -angle

  // ---- 机臂：铰链 + 碳管 + 锁扣 + 端头 + 走线 ----
  const arm = new THREE.Group()
  arm.rotation.y = rot

  const hinge = new THREE.Mesh(cyl(0.015 * k, 0.015 * k, 0.03 * k, 14), std(0x39424e, { roughness: 0.35, metalness: 0.85 }))
  hinge.position.set(r0, yArm, 0)
  arm.add(hinge)
  // 铰链叉耳（上下两片）
  ;[-1, 1].forEach((side) => {
    const ear = new THREE.Mesh(box(0.03 * k, 0.006 * k, 0.026 * k, 0.002), std(0x2a3038, { roughness: 0.45, metalness: 0.7 }))
    ear.position.set(r0 + 0.012 * k, yArm + side * 0.011 * k, 0)
    arm.add(ear)
  })

  const tubeLen = Math.max(0.04, r1 - r0 - 0.012)
  const tube = new THREE.Mesh(cyl(0.011 * k, 0.0135 * k, tubeLen, 16), std(0x1b1f25, { roughness: 0.5, metalness: 0.25 }))
  tube.rotation.z = Math.PI / 2
  tube.position.set(r0 + tubeLen * 0.5, yArm, 0)
  arm.add(tube)
  // 碳管上的加强环（两道）
  ;[0.3, 0.72].forEach((t) => {
    const ring = new THREE.Mesh(cyl(0.0145 * k, 0.0145 * k, 0.005 * k, 16), std(spec.accent, { roughness: 0.4, metalness: 0.4 }))
    ring.rotation.z = Math.PI / 2
    ring.position.set(r0 + tubeLen * t, yArm, 0)
    arm.add(ring)
  })

  // 折叠锁扣：展开后压下的机械卡扣（型号色的那一小块）
  const latch = new THREE.Mesh(box(0.026 * k, 0.009 * k, 0.02 * k, 0.003), std(spec.accent, { roughness: 0.35, metalness: 0.45 }))
  latch.position.set(r0 + 0.03 * k, yArm + 0.016 * k, 0)
  arm.add(latch)

  // 电机座端头
  const endCap = new THREE.Mesh(box(0.036 * k, 0.014 * k, 0.036 * k, 0.005), std(0x2a3038, { roughness: 0.4, metalness: 0.7 }))
  endCap.position.set(r1 - 0.004, yArm, 0)
  arm.add(endCap)

  // 沿臂走的动力线（两根细线，贴在管下方）
  ;[-0.006, 0.006].forEach((offset) => {
    const wire = new THREE.Mesh(cyl(0.0022 * k, 0.0022 * k, tubeLen * 0.96, 8), std(0x0d1013, { roughness: 0.9, metalness: 0.1 }))
    wire.rotation.z = Math.PI / 2
    wire.position.set(r0 + tubeLen * 0.5, yArm - 0.014 * k, offset * k)
    arm.add(wire)
  })

  addPart(ctx, 'arm', arm, new THREE.Vector3(radial.x, 0.02, radial.z), 0.2 * K)

  // ---- 电机 ----
  const motorH = Math.max(0.032, H * 0.42)
  const motor = new THREE.Group()
  motor.rotation.y = rot
  const motorY = yArm + 0.007

  const statorBase = new THREE.Mesh(cyl(0.014 * k, 0.016 * k, 0.008 * k, 16), std(0x2a3038, { roughness: 0.4, metalness: 0.8 }))
  statorBase.position.set(r1 - 0.004, motorY, 0)
  motor.add(statorBase)

  const stator = new THREE.Mesh(cyl(0.016 * k, 0.016 * k, motorH * 0.5, 18), std(0x39424e, { roughness: 0.35, metalness: 0.8 }))
  stator.position.set(r1 - 0.004, motorY + motorH * 0.3, 0)
  motor.add(stator)

  // 外转子钟形壳（带散热环）
  const bell = new THREE.Mesh(cyl(0.0175 * k, 0.019 * k, motorH * 0.55, 20), std(0xb6bfcc, { roughness: 0.22, metalness: 0.95 }))
  bell.position.set(r1 - 0.004, motorY + motorH * 0.62, 0)
  motor.add(bell)

  const bellRing = new THREE.Mesh(new THREE.TorusGeometry(0.0186 * k, 0.0016 * k, 8, 24), std(0x6b7482, { roughness: 0.3, metalness: 0.9 }))
  bellRing.rotation.x = Math.PI / 2
  bellRing.position.set(r1 - 0.004, motorY + motorH * 0.56, 0)
  motor.add(bellRing)

  const motorCap = new THREE.Mesh(cyl(0.013 * k, 0.0175 * k, 0.004 * k, 20), std(0x2a3038, { roughness: 0.35, metalness: 0.6 }))
  motorCap.position.set(r1 - 0.004, motorY + motorH * 0.9, 0)
  motor.add(motorCap)

  addPart(ctx, 'motor', motor, new THREE.Vector3(radial.x, 0.5, radial.z), 0.34 * K)

  // ---- 螺旋桨（两片桨叶 + 桨夹 + 整流帽）----
  const prop = new THREE.Group()
  prop.rotation.y = rot + index * 0.4
  const propY = motorY + motorH * 0.94
  const bladeGeo = bladeGeometry(spec.propRadius, spec.propChord)
  // 桨叶是复合材料：比机身更深、更哑光，带一点纤维感的高光
  const bladeMat = () => std(0x1c2026, { roughness: 0.34, metalness: 0.28 })

  const hub = new THREE.Mesh(cyl(0.0115 * k, 0.0135 * k, 0.01 * k, 18), std(0x2a3038, { roughness: 0.3, metalness: 0.8 }))
  hub.position.set(r1 - 0.004, propY, 0)
  prop.add(hub)
  // 整流帽：盖住桨夹螺栓，视觉上把两片桨"收"到一起
  const spinner = new THREE.Mesh(
    new THREE.SphereGeometry(0.0115 * k, 18, 10, 0, Math.PI * 2, 0, Math.PI / 2),
    std(0x2a3038, { roughness: 0.28, metalness: 0.85 }),
  )
  spinner.position.set(r1 - 0.004, propY + 0.005 * k, 0)
  prop.add(spinner)

  ;[0, Math.PI].forEach((yaw) => {
    const blade = new THREE.Mesh(bladeGeo, bladeMat())
    blade.position.set(r1 - 0.004, propY, 0)
    blade.rotation.y = yaw
    prop.add(blade)
    // 桨夹：真实折叠桨靠它把叶片压紧在桨毂上
    const clampMesh = new THREE.Mesh(box(0.016 * k, 0.009 * k, 0.02 * k, 0.003), std(0x39424e, { roughness: 0.35, metalness: 0.8 }))
    clampMesh.position.set(r1 - 0.004 + Math.cos(yaw) * 0.013 * k, propY - 0.001 * k, Math.sin(yaw) * 0.013 * k)
    clampMesh.rotation.y = -yaw
    prop.add(clampMesh)
  })

  addPart(ctx, 'prop', prop, new THREE.Vector3(radial.x, 0.9, radial.z), 0.44 * K)

  // ---- 电调：贴在机臂下方，靠近机身（散热最好的一段）----
  const esc = new THREE.Group()
  esc.rotation.y = rot
  const escX = r0 + (r1 - r0) * 0.34
  const escBoard = new THREE.Mesh(box(0.04 * k, 0.007 * k, 0.019 * k, 0.002), std(0x1b4a39, { roughness: 0.55, metalness: 0.15 }))
  escBoard.position.set(escX, yArm - 0.02 * k, 0)
  esc.add(escBoard)
  // 散热片
  const heatsink = new THREE.Mesh(box(0.022 * k, 0.005 * k, 0.015 * k, 0.001), std(0x9aa5b4, { roughness: 0.3, metalness: 0.95 }))
  heatsink.position.set(escX, yArm - 0.016 * k, 0)
  esc.add(heatsink)
  // 两颗电容
  ;[-0.012, 0.012].forEach((offset) => {
    const cap = new THREE.Mesh(cyl(0.0035 * k, 0.0035 * k, 0.009 * k, 10), std(0x2f3a34, { roughness: 0.6, metalness: 0.2 }))
    cap.position.set(escX + offset * k, yArm - 0.028 * k, 0)
    esc.add(cap)
  })
  // 三相线
  ;[-0.005, 0, 0.005].forEach((offset) => {
    const wire = new THREE.Mesh(cyl(0.0016 * k, 0.0016 * k, (r1 - escX) * 0.9, 6), std(0x8b3a2f, { roughness: 0.7 }))
    wire.rotation.z = Math.PI / 2
    wire.position.set((escX + r1) * 0.5, yArm - 0.024 * k, offset * k)
    esc.add(wire)
  })

  addPart(ctx, 'esc', esc, new THREE.Vector3(radial.x, -0.24, radial.z), 0.26 * K)
}

/** 智能电池：本体 + 提手 + 电量灯 + 端子（重载机额外挂两侧电池包） */
function buildBattery(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const H = spec.bodyH
  const g = new THREE.Group()

  const bw = W * 0.62
  const bh = H * 0.34
  const bl = L * 0.56
  const cy = H * 0.27

  const body = new THREE.Mesh(box(bw, bh, bl, 0.008 * k), std(0x262c34, { roughness: 0.65, metalness: 0.15 }))
  body.position.set(0, cy, -L * 0.06)
  g.add(body)

  // 型号色标签带
  const label = new THREE.Mesh(box(bw * 0.99, bh * 0.3, bl * 0.5, 0.003), std(spec.accent, { roughness: 0.5, metalness: 0.2 }))
  label.position.set(0, cy + bh * 0.05, -L * 0.06)
  g.add(label)

  // 电芯分格（4 条深缝）
  for (let i = 0; i < 4; i += 1) {
    const groove = new THREE.Mesh(box(bw * 0.9, 0.0016, 0.0035), std(0x0f1319, { roughness: 0.9 }))
    groove.position.set(0, cy + bh * 0.5 + 0.0008, -L * 0.06 + (i - 1.5) * bl * 0.2)
    g.add(groove)
  }

  // 提手（电池要能单手拎出来换）
  const handle = new THREE.Mesh(box(bw * 0.34, 0.006 * k, 0.02 * k, 0.003), std(0x39424e, { roughness: 0.5, metalness: 0.3 }))
  handle.position.set(0, cy + bh * 0.56, -L * 0.06)
  g.add(handle)
  ;[-1, 1].forEach((side) => {
    const post = new THREE.Mesh(cyl(0.003 * k, 0.003 * k, 0.012 * k, 8), std(0x39424e, { roughness: 0.5, metalness: 0.3 }))
    post.position.set(side * bw * 0.15, cy + bh * 0.52, -L * 0.06)
    g.add(post)
  })

  // 电量指示灯（4 格，这里画成常亮绿）
  for (let i = 0; i < 4; i += 1) {
    const led = new THREE.Mesh(
      box(bw * 0.12, 0.003 * k, 0.003 * k, 0.001),
      std(0x22c55e, { emissive: 0x22c55e, emissiveIntensity: 1.6, roughness: 0.4 }),
    )
    led.position.set(-bw * 0.3 + i * bw * 0.13, cy + bh * 0.18, -L * 0.06 + bl * 0.5)
    g.add(led)
  }

  // 输出端子（金色触点）
  ;[-1, 1].forEach((side) => {
    const terminal = new THREE.Mesh(cyl(0.004 * k, 0.004 * k, 0.006 * k, 10), std(0xc9a227, { roughness: 0.25, metalness: 0.95 }))
    terminal.rotation.x = Math.PI / 2
    terminal.position.set(side * bw * 0.14, cy, -L * 0.06 - bl * 0.5)
    g.add(terminal)
  })

  // 重载机：两侧外挂电池包（八轴电耗大，机内塞不下）
  if (spec.style === 'heavy') {
    ;[-1, 1].forEach((side) => {
      const pack = new THREE.Mesh(box(W * 0.16, H * 0.5, L * 0.42, 0.008 * k), std(0x262c34, { roughness: 0.6, metalness: 0.2 }))
      pack.position.set(side * W * 0.55, H * 0.34, -L * 0.05)
      g.add(pack)
      const band = new THREE.Mesh(box(W * 0.162, H * 0.08, L * 0.44, 0.002), std(spec.accent, { roughness: 0.45 }))
      band.position.set(side * W * 0.55, H * 0.34, -L * 0.05)
      g.add(band)
    })
  }

  addPart(ctx, 'battery', g, new THREE.Vector3(0, -1, 0), 0.16 * (spec.podDistance / 0.275))
}

/** 电源分配板：板 + 电容 + XT90 + 采样电阻 */
function buildPdb(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const H = spec.bodyH
  const g = new THREE.Group()
  const y = H * 0.615

  const board = new THREE.Mesh(box(W * 0.5, 0.005 * k, L * 0.3, 0.002), std(0x1b4a39, { roughness: 0.6, metalness: 0.12 }))
  board.position.set(0, y, -L * 0.04)
  g.add(board)

  // 大电容 ×2
  ;[-1, 1].forEach((side) => {
    const cap = new THREE.Mesh(cyl(0.006 * k, 0.006 * k, 0.016 * k, 12), std(0x2f3a34, { roughness: 0.55, metalness: 0.25 }))
    cap.rotation.z = 0
    cap.position.set(side * W * 0.12, y + 0.008 * k, -L * 0.04)
    g.add(cap)
  })

  // 电流采样电阻 + 主输出插头
  const shunt = new THREE.Mesh(box(W * 0.1, 0.003 * k, 0.01 * k, 0.001), std(0x8993a1, { roughness: 0.35, metalness: 0.9 }))
  shunt.position.set(W * 0.06, y + 0.004 * k, -L * 0.12)
  g.add(shunt)

  const plug = new THREE.Mesh(box(0.016 * k, 0.012 * k, 0.012 * k, 0.002), std(0xd9a521, { roughness: 0.5, metalness: 0.2 }))
  plug.position.set(-W * 0.16, y + 0.006 * k, -L * 0.12)
  g.add(plug)

  addPart(ctx, 'pdb', g, new THREE.Vector3(0, 1, 0), 0.12 * (spec.podDistance / 0.275))
}

/** 动力线束：电池 → 配电板的一段弯曲线，用 Tube 沿曲线挤出 */
function buildHarness(ctx: Ctx): void {
  const { spec, k } = ctx
  const L = spec.bodyL
  const H = spec.bodyH
  const g = new THREE.Group()

  const curve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(0, H * 0.32, -L * 0.33),
    new THREE.Vector3(0.01 * k, H * 0.45, -L * 0.2),
    new THREE.Vector3(0.005 * k, H * 0.585, -L * 0.12),
    new THREE.Vector3(-0.008 * k, H * 0.62, -L * 0.06),
  ])
  const tube = new THREE.Mesh(
    new THREE.TubeGeometry(curve, 24, 0.0038 * k, 8, false),
    std(0x12161b, { roughness: 0.85, metalness: 0.05 }),
  )
  g.add(tube)

  // 分叉到左右电调的两根
  ;[-1, 1].forEach((side) => {
    const branch = new THREE.CatmullRomCurve3([
      new THREE.Vector3(0, H * 0.585, -L * 0.1),
      new THREE.Vector3(side * spec.bodyW * 0.2, H * 0.6, -L * 0.02),
      new THREE.Vector3(side * spec.bodyW * 0.36, H * 0.585, 0),
    ])
    const b = new THREE.Mesh(
      new THREE.TubeGeometry(branch, 16, 0.0026 * k, 8, false),
      std(0x8b3a2f, { roughness: 0.75, metalness: 0.08 }),
    )
    g.add(b)
  })

  addPart(ctx, 'cable', g, new THREE.Vector3(0, 0.3, 0), 0.05)
}

// ---------------------------------------------------------------------------
// 航电：飞控 / IMU / GNSS / 天线 / 雷达 / 下视 / 航行灯 / 风扇
// ---------------------------------------------------------------------------

function buildAvionics(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const H = spec.bodyH

  // ---- 飞控 ----
  const fc = new THREE.Group()
  const fcY = H * 0.655
  const board = new THREE.Mesh(box(W * 0.44, 0.005 * k, L * 0.28, 0.002), std(0x1b4a39, { roughness: 0.6, metalness: 0.12 }))
  board.position.set(0, fcY, 0)
  fc.add(board)
  // 支撑柱
  ;[-1, 1].forEach((sx) => {
    ;[-1, 1].forEach((sz) => {
      const post = new THREE.Mesh(cyl(0.0032 * k, 0.0032 * k, H * 0.06, 8), std(0x39424e, { roughness: 0.4, metalness: 0.7 }))
      post.position.set(sx * W * 0.19, fcY - H * 0.035, sz * L * 0.12)
      fc.add(post)
    })
  })
  // 屏蔽罩 + 主控芯片 + 排针
  const shield = new THREE.Mesh(box(W * 0.2, 0.006 * k, L * 0.12, 0.002), std(0x9aa5b4, { roughness: 0.3, metalness: 0.9 }))
  shield.position.set(-W * 0.1, fcY + 0.005 * k, L * 0.02)
  fc.add(shield)
  const chip = new THREE.Mesh(box(W * 0.1, 0.004 * k, L * 0.06, 0.001), std(0x22272e, { roughness: 0.5, metalness: 0.4 }))
  chip.position.set(W * 0.1, fcY + 0.005 * k, -L * 0.02)
  fc.add(chip)
  ;[-1, 1].forEach((side) => {
    const header = new THREE.Mesh(box(W * 0.04, 0.008 * k, L * 0.03, 0.001), std(0xe8ecef, { roughness: 0.6, metalness: 0.1 }))
    header.position.set(side * W * 0.17, fcY + 0.004 * k, -L * 0.1)
    fc.add(header)
  })
  addPart(ctx, 'fc', fc, new THREE.Vector3(0, 1, 0), 0.22 * (spec.podDistance / 0.275))

  // ---- IMU：减震球 + 金属壳 ----
  const imu = new THREE.Group()
  const imuY = fcY + 0.012 * k
  const imuBody = new THREE.Mesh(box(0.018 * k, 0.01 * k, 0.018 * k, 0.002), std(0x39424e, { roughness: 0.3, metalness: 0.9 }))
  imuBody.position.set(0, imuY, L * 0.06)
  imu.add(imuBody)
  ;[-1, 1].forEach((sx) => {
    ;[-1, 1].forEach((sz) => {
      const ball = new THREE.Mesh(new THREE.SphereGeometry(0.0028 * k, 8, 6), std(0x101317, { roughness: 0.9 }))
      ball.position.set(sx * 0.007 * k, imuY - 0.006 * k, L * 0.06 + sz * 0.007 * k)
      imu.add(ball)
    })
  })
  addPart(ctx, 'imu', imu, new THREE.Vector3(0, 1, 0), 0.28 * (spec.podDistance / 0.275))

  // ---- GNSS：桅杆 + 蘑菇头天线 + 地平面 ----
  const gnss = new THREE.Group()
  const mastBase = H * 0.99
  const mast = new THREE.Mesh(cyl(0.0032 * k, 0.0032 * k, 0.028 * k, 8), std(0x39424e, { roughness: 0.4, metalness: 0.7 }))
  mast.position.set(0, mastBase + 0.014 * k, L * 0.02)
  gnss.add(mast)
  const ground = new THREE.Mesh(cyl(0.02 * k, 0.02 * k, 0.002 * k, 24), std(0x2a3038, { roughness: 0.45, metalness: 0.6 }))
  ground.position.set(0, mastBase + 0.002 * k, L * 0.02)
  gnss.add(ground)
  const puck = new THREE.Mesh(cyl(0.016 * k, 0.019 * k, 0.0045 * k, 24), std(0xe9eef5, { roughness: 0.35, metalness: 0.25 }))
  puck.position.set(0, mastBase + 0.03 * k, L * 0.02)
  gnss.add(puck)
  addPart(ctx, 'gnss', gnss, new THREE.Vector3(0, 1, 0.15), 0.62 * (spec.podDistance / 0.275))

  // ---- 天线 ×2：向后倾斜的鞭状天线 ----
  for (let i = 0; i < 2; i += 1) {
    const side = i === 0 ? -1 : 1
    const g = new THREE.Group()
    const base = new THREE.Vector3(side * W * 0.3, H * 0.99, -L * 0.34)
    const tip = new THREE.Vector3(side * W * 0.36, H * 1.14, -L * 0.52)
    g.add(strut(base, tip, 0.0026 * k, std(0x1b1f25, { roughness: 0.6, metalness: 0.2 }), 8))
    const joint = new THREE.Mesh(cyl(0.006 * k, 0.006 * k, 0.012 * k, 10), std(0x39424e, { roughness: 0.4, metalness: 0.7 }))
    joint.position.copy(base)
    g.add(joint)
    const tipBall = new THREE.Mesh(new THREE.SphereGeometry(0.0035 * k, 8, 6), std(0x1b1f25, { roughness: 0.6 }))
    tipBall.position.copy(tip)
    g.add(tipBall)
    addPart(ctx, 'antenna', g, new THREE.Vector3(side * 0.3, 0.6, -0.6), 0.4 * (spec.podDistance / 0.275))
  }

  // ---- 前向毫米波雷达 ----
  const radar = new THREE.Group()
  const radarY = H * 0.5
  const housing = new THREE.Mesh(box(W * 0.3, H * 0.22, 0.012 * k, 0.003), std(0x2a3038, { roughness: 0.35, metalness: 0.7 }))
  housing.position.set(0, radarY, L * 0.5 + 0.004)
  radar.add(housing)
  const radome = new THREE.Mesh(box(W * 0.24, H * 0.15, 0.004, 0.002), std(0x11151a, { roughness: 0.25, metalness: 0.3 }))
  radome.position.set(0, radarY, L * 0.5 + 0.011)
  radar.add(radome)
  addPart(ctx, 'radar', radar, new THREE.Vector3(0, 0.2, 1), 0.38 * (spec.podDistance / 0.275))

  // ---- 下视感知：双目 + ToF ----
  const down = new THREE.Group()
  ;[-1, 1].forEach((side) => {
    const lens = new THREE.Mesh(cyl(0.009 * k, 0.009 * k, 0.008 * k, 14), std(0x22272e, { roughness: 0.4, metalness: 0.6 }))
    lens.position.set(side * W * 0.12, -0.006, L * 0.2)
    down.add(lens)
    const glass = new THREE.Mesh(cyl(0.0062 * k, 0.0062 * k, 0.0016 * k, 14), std(0x1b2430, { roughness: 0.05, metalness: 0.9 }))
    glass.position.set(side * W * 0.12, -0.0105, L * 0.2)
    down.add(glass)
  })
  const tof = new THREE.Mesh(cyl(0.004 * k, 0.004 * k, 0.006 * k, 10), std(0x2f3a34, { roughness: 0.5 }))
  tof.position.set(0, -0.006, L * 0.2)
  down.add(tof)
  addPart(ctx, 'downward-sensor', down, new THREE.Vector3(0, -0.6, 0.8), 0.3 * (spec.podDistance / 0.275))

  // ---- 航行灯：左红 / 右绿 / 尾白 ----
  const lights: Array<[number, number, number, number]> = [
    [-W * 0.49, H * 0.45, L * 0.16, 0xff3b30],
    [W * 0.49, H * 0.45, L * 0.16, 0x22c55e],
    [0, H * 0.42, -L * 0.5, 0xf8fafc],
  ]
  lights.forEach(([x, y, z, color]) => {
    const g = new THREE.Group()
    const bulb = new THREE.Mesh(
      new THREE.SphereGeometry(0.0055 * k, 12, 8),
      std(color, { emissive: color, emissiveIntensity: 2.2, roughness: 0.2, metalness: 0.1 }),
    )
    bulb.position.set(x, y, z)
    g.add(bulb)
    const base = new THREE.Mesh(cyl(0.006 * k, 0.006 * k, 0.004 * k, 10), std(0x2a3038, { roughness: 0.5, metalness: 0.6 }))
    base.rotation.z = Math.PI / 2
    base.position.set(x * 0.94, y, z)
    g.add(base)
    addPart(ctx, 'lights', g, new THREE.Vector3(x !== 0 ? Math.sign(x) : 0, 0.15, -0.2), 0.22 * (spec.podDistance / 0.275))
  })

  // ---- 散热风扇：机身右侧的排风口（电池仓强制风冷）----
  const fan = new THREE.Group()
  const fanX = W * 0.485
  const fanY = H * 0.3
  const fanZ = -L * 0.2
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.019 * k, 0.003 * k, 8, 24), std(0x2a3038, { roughness: 0.4, metalness: 0.7 }))
  ring.rotation.y = Math.PI / 2
  ring.position.set(fanX, fanY, fanZ)
  fan.add(ring)
  for (let i = 0; i < 6; i += 1) {
    // 每片桨叶用一个绕 X 转过的 holder 定位，保证叶片沿径向排布
    const holder = new THREE.Group()
    holder.position.set(fanX, fanY, fanZ)
    holder.rotation.x = (i * Math.PI) / 3
    const blade = new THREE.Mesh(box(0.0035 * k, 0.006 * k, 0.015 * k, 0.001), std(0x39424e, { roughness: 0.45, metalness: 0.4 }))
    blade.position.set(0, 0, 0.009 * k)
    blade.rotation.x = 0.55
    holder.add(blade)
    fan.add(holder)
  }
  const fanHub = new THREE.Mesh(cyl(0.0045 * k, 0.0045 * k, 0.005 * k, 12), std(0x1b1f25, { roughness: 0.5 }))
  fanHub.rotation.z = Math.PI / 2
  fanHub.position.set(fanX + 0.001, fanY, fanZ)
  fan.add(fanHub)
  addPart(ctx, 'fan', fan, new THREE.Vector3(1, 0.1, -0.15), 0.4 * (spec.podDistance / 0.275))
}

// ---------------------------------------------------------------------------
// 任务载荷：云台 / 货舱 / 锁钩 / 舱门
// ---------------------------------------------------------------------------

function buildPayload(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const K = spec.podDistance / 0.275

  // ---- 三轴云台相机 ----
  const gimbal = new THREE.Group()
  const gz = L * 0.42
  const gy = -0.004
  // 云台随机型放大，但比机身小一档：真机云台是"挂件"，不该抢机身的分量
  const gScale = W / 0.26
  gimbal.scale.setScalar(gScale)

  const armMat = () => std(0x2a3038, { roughness: 0.35, metalness: 0.85 })
  const yaw = new THREE.Mesh(cyl(0.016, 0.018, 0.012, 16), armMat())
  yaw.position.set(0, gy - 0.008, gz)
  gimbal.add(yaw)

  // 航向臂（连到横滚电机）
  const yoke = new THREE.Mesh(box(0.012, 0.026, 0.012, 0.003), armMat())
  yoke.position.set(0, gy - 0.026, gz)
  gimbal.add(yoke)

  const roll = new THREE.Mesh(cyl(0.012, 0.012, 0.03, 14), armMat())
  roll.rotation.z = Math.PI / 2
  roll.position.set(0, gy - 0.042, gz)
  gimbal.add(roll)

  // 相机本体
  const camBody = new THREE.Mesh(box(0.058, 0.046, 0.05, 0.008), std(0x22272e, { roughness: 0.42, metalness: 0.5 }))
  camBody.position.set(0, gy - 0.072, gz + 0.004)
  gimbal.add(camBody)

  // 镜头筒 + 前组镜片 + 遮光罩
  const barrel = new THREE.Mesh(cyl(0.017, 0.019, 0.02, 20), std(0x181c22, { roughness: 0.35, metalness: 0.7 }))
  barrel.rotation.x = Math.PI / 2
  barrel.position.set(0, gy - 0.072, gz + 0.034)
  gimbal.add(barrel)

  const lensGlass = new THREE.Mesh(
    cyl(0.0145, 0.0145, 0.002, 20),
    std(0x2b4d80, { roughness: 0.04, metalness: 0.6, emissive: 0x0b1b33, emissiveIntensity: 0.6 }),
  )
  lensGlass.rotation.x = Math.PI / 2
  lensGlass.position.set(0, gy - 0.072, gz + 0.0445)
  gimbal.add(lensGlass)

  const hood = new THREE.Mesh(new THREE.TorusGeometry(0.018, 0.0022, 8, 20), std(0x181c22, { roughness: 0.4, metalness: 0.6 }))
  hood.position.set(0, gy - 0.072, gz + 0.045)
  gimbal.add(hood)

  // 红外镜头（小孔）
  const ir = new THREE.Mesh(cyl(0.005, 0.005, 0.008, 12), std(0x0f1319, { roughness: 0.3, metalness: 0.5 }))
  ir.rotation.x = Math.PI / 2
  ir.position.set(0.018, gy - 0.088, gz + 0.03)
  gimbal.add(ir)

  addPart(ctx, 'gimbal', gimbal, new THREE.Vector3(0, -0.35, 1), 0.36 * K)

  // ---- 货舱 ----
  const bay = new THREE.Group()
  const bayZ = -L * 0.12
  const bayW = W * 0.58
  const bayH = spec.gearHeight * 0.62
  const bayL = L * 0.36
  // 挂载板厚度占掉了 0 平面，货舱从 -0.014 往下挂，避免与挂载板共面闪烁
  const bayTop = -0.014
  const bayBottom = bayTop - bayH

  // 顶板 + 底框 + 四根立柱：做成"框架"而不是实心盒，拆解时能看见里面的锁钩
  const topPlate = new THREE.Mesh(box(bayW, 0.008 * k, bayL, 0.003), std(0x2a3038, { roughness: 0.5, metalness: 0.5 }))
  topPlate.position.set(0, bayTop, bayZ)
  bay.add(topPlate)
  ;[-1, 1].forEach((sx) => {
    ;[-1, 1].forEach((sz) => {
      const post = new THREE.Mesh(cyl(0.0038 * k, 0.0038 * k, bayH, 8), std(0x39424e, { roughness: 0.45, metalness: 0.6 }))
      post.position.set(sx * bayW * 0.46, bayTop - bayH * 0.5, bayZ + sz * bayL * 0.46)
      bay.add(post)
    })
  })
  ;[-1, 1].forEach((sz) => {
    const rail = new THREE.Mesh(box(bayW, 0.005 * k, 0.005 * k, 0.002), std(0x39424e, { roughness: 0.45, metalness: 0.6 }))
    rail.position.set(0, bayBottom, bayZ + sz * bayL * 0.46)
    bay.add(rail)
  })
  // 侧板（半透明蜂窝板，能隐约看到里面的包裹）
  ;[-1, 1].forEach((sx) => {
    const side = new THREE.Mesh(
      box(0.0025, bayH * 0.82, bayL * 0.92, 0.001),
      new THREE.MeshStandardMaterial({
        color: 0xd8dee7,
        roughness: 0.45,
        metalness: 0.1,
        transparent: true,
        opacity: 0.55,
      }),
    )
    side.position.set(sx * bayW * 0.46, bayTop - bayH * 0.5, bayZ)
    bay.add(side)
  })
  // 型号色角标
  ;[-1, 1].forEach((sx) => {
    const tag = new THREE.Mesh(box(0.0026, bayH * 0.16, bayL * 0.3, 0.001), std(spec.accent, { roughness: 0.45 }))
    tag.position.set(sx * bayW * 0.465, bayTop - bayH * 0.2, bayZ + bayL * 0.26)
    bay.add(tag)
  })

  addPart(ctx, 'cargo-hold', bay, new THREE.Vector3(0, -0.5, -0.5), 0.3 * K)

  // ---- 电磁锁钩：伺服 + 挂钩 + 状态灯 ----
  const hook = new THREE.Group()
  const servo = new THREE.Mesh(box(0.024 * k, 0.016 * k, 0.018 * k, 0.003), std(0x22272e, { roughness: 0.45, metalness: 0.6 }))
  servo.position.set(bayW * 0.3, bayTop - 0.014 * k, bayZ)
  hook.add(servo)
  const hookArm = new THREE.Mesh(box(0.008 * k, 0.022 * k, 0.008 * k, 0.002), std(0x9aa5b4, { roughness: 0.3, metalness: 0.95 }))
  hookArm.position.set(bayW * 0.3, bayTop - 0.03 * k, bayZ)
  hook.add(hookArm)
  const claw = new THREE.Mesh(new THREE.TorusGeometry(0.008 * k, 0.0022 * k, 8, 16, Math.PI * 1.4), std(0x9aa5b4, { roughness: 0.3, metalness: 0.95 }))
  claw.rotation.x = Math.PI / 2
  claw.position.set(bayW * 0.3, bayTop - 0.041 * k, bayZ)
  hook.add(claw)
  const statusLed = new THREE.Mesh(
    new THREE.SphereGeometry(0.0028 * k, 8, 6),
    std(0x22c55e, { emissive: 0x22c55e, emissiveIntensity: 2, roughness: 0.3 }),
  )
  statusLed.position.set(bayW * 0.3, bayTop - 0.008 * k, bayZ + 0.012)
  hook.add(statusLed)
  addPart(ctx, 'lock-hook', hook, new THREE.Vector3(0, -0.7, -0.5), 0.36 * K)

  // ---- 舱门 ×2：底部对开 ----
  ;[-1, 1].forEach((side) => {
    const door = new THREE.Group()
    const flap = new THREE.Mesh(box(bayW * 0.48, 0.004 * k, bayL * 0.94, 0.0015), std(0xd8dee7, { roughness: 0.5, metalness: 0.15 }))
    flap.position.set(side * bayW * 0.24, bayBottom - 0.004, bayZ)
    door.add(flap)
    // 铰链
    const hinge = new THREE.Mesh(cyl(0.003 * k, 0.003 * k, bayL * 0.7, 8), std(0x39424e, { roughness: 0.4, metalness: 0.7 }))
    hinge.rotation.x = Math.PI / 2
    hinge.position.set(side * bayW * 0.46, bayBottom - 0.004, bayZ)
    door.add(hinge)
    addPart(ctx, 'cargo-door', door, new THREE.Vector3(side * 0.35, -0.85, -0.35), 0.44 * K)
  })
}

// ---------------------------------------------------------------------------
// 安全：降落伞 / 蜂鸣器
// ---------------------------------------------------------------------------

function buildSafety(ctx: Ctx): void {
  const { spec, k } = ctx
  const W = spec.bodyW
  const L = spec.bodyL
  const H = spec.bodyH

  // ---- 应急降落伞舱 ----
  const chute = new THREE.Group()
  const cz = -L * 0.26
  const cy = H * 0.99
  // 舱体做扁：真机的伞舱是嵌在上盖里的一个浅圆盘，不是顶在头上的"罐子"
  const tube = new THREE.Mesh(cyl(W * 0.17, W * 0.175, 0.014 * k, 24), std(0xd8dee7, { roughness: 0.45, metalness: 0.15 }))
  tube.position.set(0, cy + 0.007 * k, cz)
  chute.add(tube)
  const lid = new THREE.Mesh(cyl(W * 0.172, W * 0.172, 0.004 * k, 24), std(spec.panel, { roughness: 0.4, metalness: 0.25 }))
  lid.position.set(0, cy + 0.016 * k, cz)
  chute.add(lid)
  // 拉发手柄（红色）：伞舱边上那个"一拉就开"的红色拉环，也是航前检查的确认点
  const handle = new THREE.Mesh(box(0.024 * k, 0.004 * k, 0.007 * k, 0.0016), std(0xd93b3b, { roughness: 0.5 }))
  handle.position.set(W * 0.175, cy + 0.018 * k, cz)
  chute.add(handle)
  // 固定环
  const ring = new THREE.Mesh(new THREE.TorusGeometry(W * 0.176, 0.0022 * k, 8, 28), std(0x39424e, { roughness: 0.4, metalness: 0.7 }))
  ring.rotation.x = Math.PI / 2
  ring.position.set(0, cy + 0.016 * k, cz)
  chute.add(ring)
  addPart(ctx, 'parachute', chute, new THREE.Vector3(0, 1, -0.25), 0.52 * (spec.podDistance / 0.275))

  // ---- 蜂鸣器 + 频闪 ----
  const buzzer = new THREE.Group()
  const bz = -L * 0.44
  const body = new THREE.Mesh(cyl(0.008 * k, 0.008 * k, 0.008 * k, 12), std(0x11151a, { roughness: 0.7 }))
  body.rotation.x = Math.PI / 2
  body.position.set(0, H * 0.2, bz)
  buzzer.add(body)
  const strobe = new THREE.Mesh(
    new THREE.SphereGeometry(0.0045 * k, 10, 8),
    std(0xff3b30, { emissive: 0xff3b30, emissiveIntensity: 1.8, roughness: 0.25 }),
  )
  strobe.position.set(0, H * 0.2, bz - 0.006 * k)
  buzzer.add(strobe)
  addPart(ctx, 'buzzer', buzzer, new THREE.Vector3(0, 0.4, -1), 0.3 * (spec.podDistance / 0.275))
}

// ---------------------------------------------------------------------------
// 对外入口
// ---------------------------------------------------------------------------

/**
 * 造一架"解剖级"的无人机。
 *
 * @param spec     机型详细规格（droneSpec.ts）
 * @param tailNumber 机身编号，喷在机身侧面；不传则喷型号代码
 */
export function buildDroneAssembly(
  spec: DroneDetailSpec,
  tailNumber?: string,
): DroneAssembly {
  const root = new THREE.Group()
  root.name = `drone-${spec.code}`

  const ctx: Ctx = {
    spec,
    root,
    instances: [],
    byPart: new Map(),
    k: 1,
    decalText: tailNumber,
  }
  // 细节尺寸系数：小零件（螺钉、线径、端子）随机型放大，否则重载机上的螺钉会细如发丝
  ctx.k = Math.min(2.2, Math.max(0.75, spec.bodyW / 0.21))

  buildShell(ctx)
  buildCenterFrame(ctx)
  buildLandingGear(ctx)
  for (let i = 0; i < spec.arms; i += 1) buildArmUnit(ctx, i)
  buildBattery(ctx)
  buildPdb(ctx)
  buildHarness(ctx)
  buildAvionics(ctx)
  buildPayload(ctx)
  buildSafety(ctx)

  root.traverse((obj) => {
    if (obj instanceof THREE.Mesh) {
      obj.castShadow = true
      obj.receiveShadow = true
    }
  })

  const propellers = (ctx.byPart.get('prop') ?? []).map((i) => i.object)

  return {
    root,
    instances: ctx.instances,
    byPart: ctx.byPart,
    spec,
    shellParts: ['shell-top', 'shell-bottom'],
    propellers,
  }
}

/** 释放整机占用的 GPU 资源（几何体 / 材质 / 贴图），切页后显存不会越用越多 */
export function disposeAssembly(assembly: DroneAssembly | null): void {
  if (!assembly) return
  assembly.root.traverse((obj) => {
    if (!(obj instanceof THREE.Mesh)) return
    obj.geometry.dispose()
    const mats = Array.isArray(obj.material) ? obj.material : [obj.material]
    mats.forEach((m) => {
      const anyMat = m as THREE.MeshStandardMaterial
      anyMat.map?.dispose()
      m.dispose()
    })
  })
}
