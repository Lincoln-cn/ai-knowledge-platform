# Design — 知识助理平台

本文件是本 App 的**锁定设计系统**。每个页面在改样式之前先读它；页面之间**求同不求异** ——
结构化多样性只发生在组件层面，主题、字体、色、圆角、CTA 语气一律共用。
系统要长，就**改这份文件**（加 `## Variants` 或 `## Per-page allowance`），**不要在页面里局部覆盖**。

生成于 Hallmark（首次运行）。`tokens.css` 是本文件的机器可读镜像，`src/styles/index.css` 是运行时入口。

---

## Genre

`modern-minimal` —— 企业平台 / 后台 / B2B / 开发者工具这一档。

---

## Macrostructure family

| 页面类型 | 基线结构 | 允许的变量 |
|---|---|---|
| **应用页**（13 个业务模块 + 外壳内所有页） | **Workbench** —— 标题小而功能性，正文由**发丝线**分区，数据/界面本身是内容。没有营销节奏：没有 hero、没有两端 CTA 条、没有滚动入场。 | 页头（标题 + 元信息行 + 操作区）的三种排布；内容容器（表格 / 看板 / 表单 / 主从双栏）；空态与加载态 |
| **沉浸页**（登录、公开问答） | 同上，但**允许用掉本页唯一那一次「深色石墨拍子」**（Cobalt 签名 8） | 深色拍子出现在登录页左栏 / 公开页页头；一页只能有一个 |

**结构禁令**：不许出现居中大标题 hero、不许 `min-height: 100vh` 的居中单句、
不许三列等宽「图标在上-标题-两行文案」的功能格子、不许卡片套卡片、不许每个区块都滚动淡入。

---

## Theme — Cobalt

一个信号色。冷调工程白纸，靠**发丝线**分面，不靠投影。圆角紧、像用尺子画的。

```css
--color-paper:        oklch(97.4% 0.004 252);   /* 画布底 —— 冷调近白，不是 #fff */
--color-surface:      oklch(99.4% 0.002 252);   /* 卡面 / 浮层 —— 比画布亮一档，不是纯白 */
--color-sunken:       oklch(95.8% 0.005 252);   /* 凹槽：表头、输入框、代码块 */
--color-hover:        oklch(96.8% 0.006 252);   /* 行/项 hover */

--color-rule:         oklch(90.5% 0.008 252);   /* 主发丝线 —— 分面全靠它 */
--color-rule-2:       oklch(94.0% 0.006 252);   /* 内部次分隔线 */
--color-rule-strong:  oklch(85.0% 0.010 252);   /* 输入框边、需要更清楚的界线 */

--color-ink:          oklch(24.0% 0.020 258);   /* 标题 / 主文本。冷炭，不是 #000 */
--color-ink-2:        oklch(38.0% 0.017 257);   /* 正文 */
--color-muted:        oklch(56.0% 0.013 256);   /* 次要说明、标签 */
--color-faint:        oklch(68.0% 0.010 255);   /* 占位符、禁用 */

--color-accent:       oklch(58.0% 0.200 256);   /* 电光钴蓝 —— 唯一信号色 */
--color-accent-hover: oklch(52.0% 0.210 256);
--color-accent-ink:   oklch(99.0% 0.005 256);   /* 压在 accent 上的文字 */
--color-accent-soft:  oklch(96.0% 0.028 256);   /* 选中行底 —— 极浅钴 */
--color-accent-line:  oklch(88.0% 0.055 256);   /* 选中边 */

--color-focus:        oklch(58.0% 0.200 256);   /* 焦点环 = accent */

/* 状态语义 —— 只用于标签、圆点、行内文字，绝不做大面积填充 */
--color-ok:           oklch(55.0% 0.115 158);
--color-warn:         oklch(62.0% 0.130 72);
--color-danger:       oklch(55.0% 0.170 27);

/* 深色石墨拍子 —— 一页只许出现一次 */
--color-graphite:     oklch(22.0% 0.016 260);
--color-graphite-2:   oklch(27.0% 0.018 260);
--color-graphite-ink: oklch(93.0% 0.008 260);
```

**accent 用量上限：任一视口 ≤ 5%。** 只许出现在：选中态左竖条 / 输入焦点环 / 链接 hover 下划线 /
一个主按钮 / 状态点。其余全是 ink-on-cool-white。

**禁**：纯 `#000` / 纯 `#fff` · 灰阶零彩度（每个中性色都带 0.004–0.020 的冷色彩度）·
紫→蓝/青渐变 · 渐变文字 · 毛玻璃（`backdrop-filter` 只许出现在顶栏一处）·
彩色左条 callout · 投影堆叠（深度靠**线**和**色阶**，不靠阴影）。

**与 Element Plus 的关系**：`--el-*` 由 `src/styles/element-plus.css` 单向映射到上面这些令牌。
**页面不许直接写 `--el-*` 或 `--el-color-primary`** —— 那等于绕开这套系统。

---

## Typography

**2+1 三面封顶**：display + body + mono。第四个字族就是 slop。

```css
--font-display: 'Space Grotesk', var(--font-cjk);
--font-body:    'Geist', var(--font-cjk);
--font-mono:    'JetBrains Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace;
--font-cjk:     'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', 'Source Han Sans SC',
                'Noto Sans CJK SC', sans-serif;
```

**中文排印专项纪律（本项目最重要的四条，照搬英文配方会出错）：**

1. **正文字号保底 14px**（`--text-sm`）。当前工程有 18 处 11px、53 处 12px —— 11px 一律抬到 12px，正文一律 14px。中文在 11px 已丢笔画。
2. **行高分两档，不许一刀切。** `--leading-body: 1.75` 给**阅读性文本**（markdown、消息体、说明段落、`.notice`，用全局 `.prose`）—— 汉字是满高字形、没有升降部做缓冲，行距不够就是一片黑。`--leading-ui: 1.6` 给**界面**（表单、表格、标签、按钮、密集列表）—— 那类内容是"扫"不是"读"。★ 一刀切 1.75 是错的：实测把编排画布的 9 个节点卡各撑到 1641px 总高，整图超出可视区、2 个节点被切掉。
3. **中文标题字距必须为 0。** 拉丁 display 的 `-0.02em ~ -0.04em` 会让汉字**笔画相撞**。负字距只许加在纯拉丁/数字元素上。
4. **不要合成斜体。** 中文没有真斜体，`font-style: italic` 会得到机械倾斜的糊字。强调一律用**字重 / accent 色 / 下划线**。

**字号阶梯**（14px 基准，display 段用 1.25 比率）：

| 令牌 | 值 | 用途 |
|---|---|---|
| `--text-2xs` | 12px | 元信息、密集表格、mono 小标签（中文下限） |
| `--text-xs` | 13px | 次要正文、表格单元格 |
| `--text-sm` | 14px | **正文 / 界面默认** |
| `--text-md` | 16px | 强调正文、卡片标题 |
| `--text-lg` | 20px | 区块标题 |
| `--text-xl` | 24px | 页面标题 |
| `--text-2xl` | 32px | 品牌字、登录页主标题 |
| `--text-3xl` | 40px | 仅登录页深色拍子 |

**单页最多用 5 档。** 层级不够时用字重和颜色补，不要再加字号。

**字重**：正文 400 一档；标题与正文对比 **≥ 300**（正文 400 ⇒ 标题 600/700，不要 500）。
display 用 Space Grotesk **600**，正文用 Geist **400**，强调用 Geist **500**。

**两处 outlier 位（mono 只许占这两处）**：① 全大写小标签（页头元信息、表头、状态、kbd 提示），`letter-spacing: 0.08em`；② 数字列 / ID / 版本号 / 接口路径。
**数字列必须 `font-variant-numeric: tabular-nums`。**

**标点**：中文用「」，英文用 “ ”，破折号 ——，省略号 …；不写 `--` 与 `...`。

---

## Spacing

4pt 刻度，九档，**按角色命名**。页面只许用 `var(--space-*)`，不许写裸数值。

```css
--space-3xs: 2px;   --space-2xs: 4px;   --space-xs: 8px;
--space-sm: 12px;   --space-md: 16px;   --space-lg: 24px;
--space-xl: 40px;   --space-2xl: 64px;  --space-3xl: 96px;
```

**节奏**：同级兄弟用 `gap`，不用成串 `margin`。页内边距 `--space-lg`，
区块之间 `--space-xl`，卡片内边距 `--space-md`～`--space-lg`。
不要「卡片内边距 = 区块间距 = 页面边距」那种扁平节奏。

---

## Motion

**只做微交互。应用页零滚动入场**（这是 Cobalt 的立场，也是反模式清单里的点名条目）。

```css
--ease-out:    cubic-bezier(0.16, 1, 0.3, 1);     /* 进入 */
--ease-in:     cubic-bezier(0.7, 0, 0.84, 0);     /* 离开 */
--ease-in-out: cubic-bezier(0.65, 0, 0.35, 1);    /* 状态切换 */
--dur-micro: 120ms;   /* 按下、勾选、颜色变化 */
--dur-short: 220ms;   /* hover、下拉、提示 */
--dur-long:  420ms;   /* 抽屉、弹窗、登录页那一次入场 */
```

- **只动 `transform` 与 `opacity`。** 不动 `width/height/top/left/margin/padding`。
- 禁用浏览器默认 `ease`、禁用 bounce/overshoot/elastic、禁用视差、禁用无限循环。
- **焦点环瞬间出现，不做过渡**（过渡会让键盘用户在最开始那 200ms 看不到指示）。
- `@media (prefers-reduced-motion: reduce)` 下所有位移塌缩成 ≤150ms 透明度交叉淡入 —— 全工程必须有这一条。

---

## Microinteractions stance

- **静默成功**（silent success）：用户能看见结果的动作不弹成功提示。提示只留给失败、异步且结果不可见、以及用户需要留痕的确认。
- 可逆动作（删一行）**不做确认弹窗**，做乐观删除 + 可撤销；不可逆动作才用弹窗。
- 提示的 hover 延迟 800ms、focus 延迟 0ms（意图不同，时序不同）。
- 必须给**全部 8 态**样式：default · hover · `:focus-visible` · `:active` · disabled · loading · error · success。

---

## CTA voice

- **主按钮**：实心 accent 填充，圆角 `--radius`（6px），高度 32px（紧凑）/ 36px（表单）。**不是胶囊。** 文案是动词 + 目的：「发布」「保存配置」「开始问答」，不要「提交」「确定」这种无信息量的。
- **次按钮**：1px `--color-rule-strong` 描边，透明底，文字 `--color-ink-2`。
- **文字按钮**：accent 色文字，无边框；hover 出下划线（accent 用量算在这里）。
- 一行里**最多一个**主按钮。

---

## Per-page allowance

- **应用页禁止 enrichment** —— 功能本身承担页面，不许插装饰插画 / 空态插画 / 背景纹理 / 背景图案。
- **空态**只许用排版 + 一条发丝线，不许插画。
- **沉浸页**（登录、公开问答）允许一次深色石墨拍子，允许克制的层次感，但同样不许插画与背景图案。
- **编排画布**是唯一豁免「零装饰」的区域 —— 它需要节点卡、连线、网格背景（那些是功能件，不是装饰）。

---

## What pages MUST share

字标与图标口径 · 上面这套色（含 accent 的用量纪律）· 三个字族与字号阶梯 ·
圆角三档（6/8/999）· 按钮形状与内边距节奏 · 页头节奏（标题 + 元信息行 + 操作区）·
发丝线语言 · 动效令牌。

## What pages MAY differ on

页头三要素的排布 · 内容容器形态（表格 / 看板 / 表单 / 主从双栏）· 空态与加载态 ·
深浅拍子的位置（沉浸页）· 编排画布内部的节点观感。

---

## Exports

`tokens.css`（工程根，Hallmark 令牌的可移植镜像）。本项目非 Tailwind / 非 shadcn，不产出另两种格式。

---

## Provenance

- 系统建立：Hallmark `redesign`（多页流程），2026-09-25。
- 改造前的实测问题（驱动这套系统的四条）：**同屏三种蓝**（`#2f6fed` 应用令牌 / `#409eff` Element Plus 默认 / `#3370ff` 编排模块孤儿覆盖）· **无字号阶梯**（12px×53、13px×35、11px×18，正文 14px 而页面标题只有 15–17px）· **间距手抄值**（20/14/11/18/7/5/9）· **零 `:focus-visible`、零 `prefers-reduced-motion`**。
