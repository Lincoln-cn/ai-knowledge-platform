import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import gsap from 'gsap'
import { DrawSVGPlugin } from 'gsap/DrawSVGPlugin'

/**
 * 登录页左栏的**自绘拓扑**（knowledge graph / blueprint topology）。
 *
 * <h3>为什么把"云"换掉</h3>
 * 使用者的评价一路是：「让登录有视觉先感受效果」→「只有打开的一瞬间，能不能像屏保一样一直动」
 * →「**这个动画太丑了**」→「**只是一个云 太丑了**」。
 * 前三轮我都在调**参数**（幅度、路径、时长、层数），但根因在**素材**：
 * 一片 `blob` 云不管怎么动，都是"一张贴图在动" —— 它和这个产品的语言没有任何关系。
 *
 * ⇒ 换成**让它自己画出一张图**。三条理由：
 * <ol>
 *   <li>**隐喻正确**：这是知识助理平台，图谱就是它的内容本身；云只是装饰。</li>
 *   <li>**视觉语言同源**：本系统的底色就是"工程图纸 + 发丝线 + 蓝图网格"
 *       （页头那条 hatch 就是网格）。线条自己生长是同一套语言的动态版。</li>
 *   <li>**动画即形态**：不是"一个东西在位移"，而是**线在生长** ——
 *       眼睛读的是"图在被构建"，这正是知识入库这件事的隐喻。</li>
 * </ol>
 *
 * <h3>用的是 GSAP 官方 demo 的那一招</h3>
 * [Draw a path](https://demos.gsap.com/demo/draw-a-path)：`gsap.from('.draw-me', {drawSVG: 0})`
 * —— `DrawSVGPlugin` 通过 `stroke-dashoffset` / `stroke-dasharray` 揭示描边，
 * 一行代码就能让任意 `<path>` 自己画出来。（该插件此前是会员专属，2025 起随 GSAP 全部免费。）
 *
 * <h3>★ 一处刻意偏离"只动 transform / opacity"</h3>
 * `drawSVG` 驱动的是 `stroke-dashoffset`，属于**绘制层**而不是合成层 —— 与本工程
 * `design.md § Motion` 的"只动 transform 与 opacity"不一致。这里接受它，因为
 * **"线条生长"这个效果本身没有 transform 等价物**。边界：路径数量有限（18 条）、
 * 单轮动画时长长、且降动效下**完全不动**（见下）。这是一处**有理由的例外**，不是疏忽。
 *
 * <h3>★ 降动效：把图画完就停</h3>
 * 与 `useSlateAurora` 同一条纪律：GSAP 走 rAF + 内联样式，**不受 CSS 的
 * `@media (prefers-reduced-motion: reduce)` 约束**，所以必须在 JS 里判
 * （`motionAllowed()`：同时读系统设置与 `?motion=on` 开关）。
 * 不允许时**不空着、也不动**：把整张图画满后静止 —— 静态的图本身也是一张好看的图。
 */

/// 节点坐标。**铺满整栏**（viewBox 672 × 900），**文字区不放节点** ——
/// 方块落在字旁边会像污点。但**边允许穿过文字带**：标题是 93% 明度的亮字、
/// 线只有 ~48%，线穿过去几乎不损可读性，而场因此是**连续**的
/// （否则密集之后那个「洞」会变得很显眼）。实测 83 条里只有 4 条穿过文字带。
///
/// 生成方式：**8×10 抖动网格 + 文字区排除 + 每点接 3 个近邻 + 10 条跨栏主干**，
/// 再由脚本统计度数（最小 1 / 最大 7 / 平均 3.8）。手搓 44 个点做不到既均匀又不呆板。
export const TOPOLOGY_NODES: Array<[number, number]> = [
  [381, 37], [482, 46], [526, 55], [621, 43], [202, 135], [317, 148],
  [369, 130], [650, 131], [32, 215], [208, 208], [650, 248], [22, 317],
  [111, 324], [396, 321], [438, 334], [567, 313], [623, 309], [361, 408],
  [475, 382], [524, 417], [281, 480], [391, 488], [458, 499], [635, 495],
  [40, 561], [308, 586], [482, 603], [626, 570], [40, 678], [205, 670],
  [310, 672], [360, 667], [33, 783], [122, 776], [204, 767], [314, 783],
  [397, 760], [449, 771], [544, 780], [628, 758], [56, 832], [393, 863],
  [441, 840], [537, 837]
]

/// 边（节点下标对）。密度由「每点 3 近邻」+「10 条主干」决定 ——
/// 主干那几条是刻意的：只连近邻会让整栏读成「若干座孤岛」，跨栏长边才让它像**一张**图。
export const TOPOLOGY_EDGES: Array<[number, number]> = [
  [0, 1], [0, 5], [0, 6], [1, 2], [1, 3], [1, 6], [2, 3], [2, 7],
  [3, 7], [3, 13], [4, 5], [4, 6], [4, 9], [5, 6], [5, 9], [7, 10],
  [7, 23], [8, 9], [8, 11], [8, 12], [9, 12], [10, 15], [10, 16], [11, 12],
  [13, 14], [13, 17], [13, 18], [14, 17], [14, 18], [14, 26], [15, 16], [15, 19],
  [16, 19], [17, 20], [17, 21], [18, 19], [19, 22], [19, 23], [20, 21], [20, 25],
  [21, 22], [21, 26], [22, 23], [22, 26], [22, 27], [22, 30], [23, 27], [24, 28],
  [25, 29], [25, 30], [25, 31], [26, 27], [26, 31], [28, 32], [28, 33], [28, 40],
  [29, 30], [29, 34], [30, 31], [30, 35], [31, 36], [32, 33], [32, 40], [33, 34],
  [33, 40], [34, 35], [34, 37], [34, 40], [35, 36], [36, 37], [36, 41], [36, 42],
  [37, 38], [37, 39], [37, 41], [37, 42], [37, 43], [38, 39], [38, 43], [39, 43],
  [40, 41], [41, 42], [42, 43]
]

/** 一条边的路径：微弓的二次贝塞尔（直线网格太"硬"，微弓读起来像拓扑图） */
export function edgePath(a: [number, number], b: [number, number]): string {
  const [x1, y1] = a
  const [x2, y2] = b
  const bow = 0.08
  const cx = (x1 + x2) / 2 - (y2 - y1) * bow
  const cy = (y1 + y2) / 2 + (x2 - x1) * bow
  return `M ${x1} ${y1} Q ${cx} ${cy} ${x2} ${y2}`
}

/// 装饰层的色阶（定义在 `tokens.css`，蓝族五档）。**每轮重新随机分配** ⇒
/// 画面在变的不只是位置，还有颜色。
/// ★ `--color-accent` **不进随机池**：它留给"信号"与枢纽节点，守住 accent ≤ 5% 视口的预算。
const TONES = ['--art-wire-1', '--art-wire-2', '--art-wire-3', '--art-wire-4', '--art-wire-5']

/// 度数 ≥ 此值的节点算"枢纽"，取 accent 而不是随机色阶 —— 让颜色带一点含义，
/// 而不是纯彩纸屑。
const HUB_DEGREE = 5

/// 整组色相漂移的幅度与周期（度）。±10° 左右足够"看得出在变"，又不会让钴蓝离开蓝族。
const HUE_SWING = 9
const HUE_PERIOD = 48

/** 一轮的三段：画线 → 信号游走 → 溶解。**总长约 34.5s**，之后重新洗牌再来一轮。 */
const DRAW = 8
const HOLD = 22
const DISSOLVE = 4.5

gsap.registerPlugin(DrawSVGPlugin)

/** 动效是否允许：系统设置与使用者的显式开关取"或"（与 `lib/motion.ts` 同一口径）。 */
export function motionAllowed(): boolean {
  if (typeof document === 'undefined' || typeof window.matchMedia !== 'function') return false
  if (document.documentElement.dataset.motion === 'on') return true
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function useSlateTopology(svgRef: Ref<SVGSVGElement | null>): void {
  let cycle: gsap.core.Timeline | undefined
  let hueDrift: gsap.core.Tween | undefined
  let stopped = false

  /** 整组颜色的**连续漂移**：一次 `hue-rotate` 作用在一个 `<g>` 上。
   * ★ 为什么不给 127 个元素逐个补间 `stroke`：那是 127 处颜色插值 + 每帧重绘；
   *   一次分组 filter 就把同一件事做完（而且中性灰不受 hue-rotate 影响 ——
   *   变的是彩色那部分，"底"保持中性）。
   * ★ 幅度只有 ±9°：足够"看得出在变"，又不会让钴蓝漂出蓝族。 */
  const startHueDrift = () => {
    const field = svgRef.value?.querySelector<SVGGElement>('.topology-field')
    if (!field) return
    const spin = { deg: -HUE_SWING }
    hueDrift = gsap.to(spin, {
      deg: HUE_SWING,
      duration: HUE_PERIOD,
      ease: 'sine.inOut',
      repeat: -1,
      yoyo: true,
      onUpdate: () => field.style.setProperty('filter', `hue-rotate(${spin.deg.toFixed(2)}deg)`)
    })
  }

  /** 跑一轮，跑完自己再起一轮（每轮重新洗牌 ⇒ 永不重复） */
  const runCycle = () => {
    const root = svgRef.value
    if (!root || stopped) return

    cycle?.kill()
    hueDrift?.kill()
    hueDrift = undefined
    // 复位色相，避免"改系统设置后重建"把上一次的旋转叠上去
    svgRef.value?.querySelector('.topology-field')?.removeAttribute('style')

    const wires = gsap.utils.toArray<SVGPathElement>('.wire', root)
    const pulses = gsap.utils.toArray<SVGPathElement>('.pulse', root)
    const nodes = gsap.utils.toArray<SVGGElement>('.node', root)
    if (!wires.length) return

    // ── 颜色：每轮重新洗牌（与"生长顺序"一起洗）──
    //   ★ 放在 motionAllowed() 之前：颜色是**静态属性**，降动效下也该有颜色，
    //     只有"漂移"才是动效。
    const degree = new Map<number, number>()
    for (const [a, b] of TOPOLOGY_EDGES) {
      degree.set(a, (degree.get(a) ?? 0) + 1)
      degree.set(b, (degree.get(b) ?? 0) + 1)
    }
    for (const wire of wires) {
      // ⚠️ 这里**不能**写成 `gsap.utils.random(TONES, true)` —— 第二个参数 `true` 的含义是
      // "返回一个可复用函数"，于是插进字符串就成了 `var(function(){…})`：自定义属性变垃圾值、
      // `var()` 解析失败、**静默回退到兜底色**。实测症状 = 83 条线全是同一个颜色，
      // 不报错也不 console 红（探针靠"数不同颜色的种数"才抓到它）。
      wire.style.setProperty('--wire-tone', `var(${gsap.utils.random(TONES)})`)
    }
    nodes.forEach((node, index) => {
      const tone =
        (degree.get(index) ?? 0) >= HUB_DEGREE
          ? 'var(--color-accent)'
          : `var(${gsap.utils.random(TONES)})`
      node.style.setProperty('--node-tone', tone)
    })

    // ── 降动效：把图画满就停（不空着、也不动）──
    if (!motionAllowed()) {
      gsap.set(wires, { drawSVG: '0% 100%' })
      gsap.set(pulses, { opacity: 0 })
      gsap.set(nodes, { opacity: 1 })
      return
    }

    startHueDrift()

    // 每轮重新洗牌的三件事：线的生长顺序 · 哪几条线走信号 · 哪个节点先亮
    const drawOrder = gsap.utils.shuffle(wires.map((_, i) => i))
    const stroke = DRAW / drawOrder.length

    const tl = gsap.timeline({
      onComplete: () => {
        if (!stopped) runCycle()
      }
    })

    tl.set(wires, { drawSVG: '0% 0%' }, 0)
      .set(pulses, { opacity: 0 }, 0)
      .set(nodes, { opacity: 0, scale: 1, transformOrigin: 'center center' }, 0)

    // ① 画线：按洗过的顺序错峰生长
    drawOrder.forEach((wireIndex, step) => {
      tl.fromTo(
        wires[wireIndex],
        { drawSVG: '0% 0%' },
        { drawSVG: '0% 100%', duration: stroke * 2.4, ease: 'power2.out' },
        step * stroke
      )
    })

    // ② 节点：在它**第一条边画到**的时候亮起 —— 顺序因此是"可读"的（图在被搭起来）
    const firstTouch = new Map<number, number>()
    drawOrder.forEach((wireIndex, step) => {
      for (const nodeIndex of TOPOLOGY_EDGES[wireIndex]) {
        if (!firstTouch.has(nodeIndex)) firstTouch.set(nodeIndex, step * stroke + stroke * 1.2)
      }
    })
    for (const [nodeIndex, at] of firstTouch) {
      const node = nodes[nodeIndex]
      if (!node) continue
      tl.fromTo(
        node,
        { opacity: 0, scale: 0.4 },
        { opacity: 1, scale: 1, duration: 0.5, ease: 'back.out(2)' },
        at
      )
    }

    // ③ 信号游走：一条细的钴蓝亮段沿边扫过；扫到末端时**终点节点打一下点**
    const holdStart = DRAW + 0.4
    // 密度上去后信号也要跟上（14 → 24），否则大片区域整轮都轮不到一次扫过
    const pulseOrder = gsap.utils.shuffle(wires.map((_, i) => i)).slice(0, 24)
    const gap = HOLD / pulseOrder.length
    pulseOrder.forEach((wireIndex, k) => {
      const at = holdStart + gap * k
      const target = TOPOLOGY_EDGES[wireIndex][1]
      tl.fromTo(
        pulses[wireIndex],
        { drawSVG: '0% 6%', opacity: 0.9 },
        { drawSVG: '94% 100%', duration: 1.15, ease: 'power1.inOut' },
        at
      )
        .to(pulses[wireIndex], { opacity: 0, duration: 0.3 }, at + 1.15)
        .fromTo(
          nodes[target],
          { scale: 1, opacity: 1 },
          { scale: 2.4, opacity: 0.15, duration: 0.55, ease: 'power2.out' },
          at + 0.75
        )
        .to(nodes[target], { scale: 1, opacity: 1, duration: 0.45, ease: 'power2.inOut' }, at + 1.3)
    })

    // ④ 溶解：线从末端往回抹掉、节点随机散掉 —— 然后 onComplete 起新的一轮
    const dissolveAt = DRAW + HOLD
    tl.to(
      wires,
      {
        drawSVG: '0% 0%',
        duration: DISSOLVE,
        ease: 'power2.in',
        stagger: { amount: 0.9, from: 'random' }
      },
      dissolveAt
    ).to(
      nodes,
      { opacity: 0, scale: 0.6, duration: DISSOLVE * 0.7, stagger: { amount: 0.7, from: 'random' } },
      dissolveAt + 0.6
    )

    cycle = tl
  }

  onMounted(() => {
    stopped = false
    runCycle()
    // 系统设置可能在页面开着时被改 ⇒ 重建（与 useSlateAurora 同一条纪律）
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    mq.addEventListener('change', runCycle)
    onBeforeUnmount(() => mq.removeEventListener('change', runCycle))
  })

  onBeforeUnmount(() => {
    stopped = true
    cycle?.kill()
    hueDrift?.kill()
  })
}
