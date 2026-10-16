# 第三方声明

本仓库自身以 **[Apache-2.0](LICENSE)** 发布（`Copyright 2026 Lincoln`）。

下面是随本仓库使用的第三方组件的许可。**表中每一条都是从各发布包自己的
`package.json` 的 `license` 字段实读得出**，不是按印象推断；版本是实际安装的版本，
声明范围见 [`frontend/package.json`](frontend/package.json)，锁定版本见 `package-lock.json`。

## 运行时依赖

| 包 | 版本 | 用途 | 许可 |
|---|---|---|---|
| `vue` | 3.5.x | 框架 | MIT |
| `vue-router` | 4.6.x | 路由 | MIT |
| `pinia` | 2.3.x | 状态管理 | MIT |
| `element-plus` | 2.14.x | 组件库 | MIT |
| `@element-plus/icons-vue` | 2.3.x | 图标 | MIT |
| `axios` | 1.20.x | HTTP | MIT |
| `dayjs` | 1.11.x | 日期 | MIT |
| `lodash-es` | 4.18.x | 工具（ESM 版） | MIT |
| `mitt` | 3.0.x | 事件总线 | MIT |
| `@antv/layout` | 0.3.x | 画布自动布局 | MIT |
| `echarts` | 5.6.x | 图表 | **Apache-2.0** |
| `zrender` | 5.6.x | `echarts` 的渲染引擎（传递依赖，此处列出以履行署名） | **BSD-3-Clause** |
| `@logicflow/core` | 1.2.x | 编排画布内核 | **Apache-2.0** |
| `@logicflow/extension` | 1.2.x | 编排画布扩展 | **Apache-2.0** |
| `gsap` | 3.15.x | 登录页多层光场的动效（沉浸页装饰件的运动；**无任何依赖**） | ⚠️ **Standard "No Charge" License —— 非 OSI 许可，见下** |

### ⚠️ `gsap` —— 本仓库唯一一条非 OSI 许可的运行时依赖

`gsap` 的 `package.json` 里 `license` 字段**不是** SPDX 标识，而是一句话：

```
Standard 'no charge' license: https://gsap.com/standard-license.
```

许可原文见 <https://gsap.com/community/standard-license/>（生效 2025-04-30，最后修改 2025-05-30）。
实读要点（**逐条对着原文抄，不是按印象**）：

- **免费**：「Can I really use GSAP in commercial projects without paying anything? **Yes, really!**
  Commercial usage is covered under the standard license.」—— 含此前「会员专属」的插件（SplitText、MorphSVG 等）。
- **允许**：在「任何网站、Web 应用或数字界面」上使用（`Permitted Uses`）。
- **禁止**：用于「让用户无代码构建动画、与 Webflow 的可视化动画能力竞争」的工具（`Prohibited Uses`）；
  反向工程以制造竞品；**移除或修改 GSAP 产品上的专有声明或品牌**。
- **归属**：全部知识产权属 Webflow，本许可**不转移任何所有权**。
- **终止与修订**：Webflow 可在你违约时终止许可；并**保留随时修改条款的权利**（继续使用即视为接受）。

**本仓库的使用情形**：一个自托管的企业级 RAG 平台（知识库 / 应用编排 / 权限），
**不是**可视化动画构建器 ⇒ 属于 `Permitted Uses`，不触碰任何 `Prohibited Use`。
本条声明本身就是"不移除专有声明"那一款的履行方式。

> ★ 把这条记在这里的原因不只是合规：它是**整张表里唯一一条可以被对方单方面修改的许可**。
> 对本仓库"Apache-2.0、准备开源"的定位来说，这是一个**显式的、被记录下来的取舍**，
> 不是被忽略的细节。若要回到"全是 OSI 许可"的依赖面，替代品是 MIT 的 `motion`
> （见 `frontend/README.md` 的依赖白名单注记）。


## 构建期依赖

| 包 | 版本 | 用途 | 许可 |
|---|---|---|---|
| `vite` | 5.4.x | 构建 | MIT |
| `@vitejs/plugin-vue` | 5.2.x | Vue 单文件组件支持 | MIT |
| `vue-tsc` | 2.2.x | 类型检查 | MIT |
| `typescript` | 5.6.x | 语言 | **Apache-2.0** |
| `sass` | 1.105.x | 样式预编译 | MIT |
| `@types/node` | 22.20.x | 类型定义 | MIT |
| `@types/lodash-es` | 4.17.x | 类型定义 | MIT |

## 字体

字体文件**自托管**于 [`frontend/src/assets/fonts/`](frontend/src/assets/fonts)（只取 latin 子集），
不引外部 CDN。三个字面均以 **SIL Open Font License 1.1** 发布，允许自托管、嵌入与再分发。

| 文件 | 用途 | 上游 |
|---|---|---|
| `space-grotesk-latin-{500,600,700}-normal.woff2` | display（标题、品牌字、大数字） | `@fontsource/space-grotesk` · OFL-1.1 |
| `geist-latin-{400,500,600}-normal.woff2` | body（正文、界面文字） | `@fontsource/geist` · OFL-1.1 |
| `jetbrains-mono-latin-{400,500}-normal.woff2` | mono（数字、ID、版本、令牌） | `@fontsource/jetbrains-mono` · OFL-1.1 |

中文字形**不引网络字体**，一律由访客本机的 CJK 字体栈提供（`PingFang SC` →
`HarmonyOS Sans SC` → `Microsoft YaHei` → `Source Han Sans`），因此不涉及字体许可。

## 后端依赖（★ 待生成，M0）

后端的依赖清单**尚未生成** —— 后端工程还没开工（`backend/` 目前只有 `README.md`）。
M0 会加 **`license-maven-plugin`**，从 `pom.xml` 实际解析出依赖树并生成本节，规则与上表一致：
**只列直接依赖 + 实际列入署名的传递依赖，且每条从发布包自己的许可字段实读，不按印象推断。**

开工前已知的许可要点（选型阶段核实过，见 [`docs/系统设计方案/09`](docs/系统设计方案/09-中间件与-Spring-模块选型.md) §10.1）：

| 类 | 许可 |
|---|---|
| Spring Boot / Framework / Security / Modulith / **Spring AI** | Apache-2.0 |
| PostgreSQL JDBC 驱动 · Flyway · HikariCP | Apache-2.0 |
| **Apache Tika**（`tika-core`）· **Apache PDFBox** · **Apache POI** | Apache-2.0 |
| **commonmark-java**（由 `tika-core` 传递引入） | **BSD-2-Clause** |
| **JGraphT**（V1.0 编排引擎才用） | ★ **LGPL-2.1-or-later OR EPL-2.0 双选 ⇒ 取 EPL-2.0**（避开 LGPL） |
| Caffeine · Resilience4j · Testcontainers | Apache-2.0 / MIT |

> 一条纪律：**引入任何新依赖时先看许可，再看体积**。上表里 `commonmark` 是 `tika-core` 带进来的（BSD-2-Clause），
> 而 `JGraphT` 是**双许可**——这类都要在引入当期就记进本节，不要留到发布前补。

## 说明

- 上表只列**直接依赖**与实际列入署名的传递依赖。其余传递依赖各自随包携带许可，
  完整清单可用 `npm ls --all` 或查 `package-lock.json` 复核。
- 本仓库**不使用**任何商业授权的框架或模板产物，界面层只依赖上表中的宽松许可组件。
- 各依赖的完整许可原文随其发布包分发（`node_modules/<包>/LICENSE*`），此处不再转载。
