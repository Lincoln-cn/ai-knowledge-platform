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

## 说明

- 上表只列**直接依赖**与实际列入署名的传递依赖。其余传递依赖各自随包携带许可，
  完整清单可用 `npm ls --all` 或查 `package-lock.json` 复核。
- 本仓库**不使用**任何商业授权的框架或模板产物，界面层只依赖上表中的宽松许可组件。
- 各依赖的完整许可原文随其发布包分发（`node_modules/<包>/LICENSE*`），此处不再转载。
