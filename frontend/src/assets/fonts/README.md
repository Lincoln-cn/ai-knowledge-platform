# 自托管字体

自托管而非引 CDN —— 目标环境是国内企业内网，Google Fonts 通常不可达；
且离线冒烟（`build:mock` + 静态服务 4205）必须不依赖外网。

只取 **latin 子集**（每个 13–22 KB）。中文字形一律由本机 CJK 栈提供 ——
CJK 网络字体动辄 5–10 MB，且本机苹方 / HarmonyOS / 雅黑已经是渲染质量最好的那一档。

| 文件 | 用途 | 上游 | 许可 |
|---|---|---|---|
| `space-grotesk-latin-{500,600,700}-normal.woff2` | **display** —— 页面标题、品牌字、大数字 | `@fontsource/space-grotesk@5` | OFL-1.1 |
| `geist-latin-{400,500,600}-normal.woff2` | **body** —— 正文、界面文字 | `@fontsource/geist@5` | OFL-1.1 |
| `jetbrains-mono-latin-{400,500}-normal.woff2` | **mono** —— 数字/ID/版本/令牌、全大写小标签 | `@fontsource/jetbrains-mono@5` | OFL-1.1 |

三个面均为 **SIL Open Font License 1.1**（`package.json` 的 `license` 字段实读确认），
允许自托管、嵌入与再分发。

来源 CDN：`https://cdn.jsdelivr.net/npm/<pkg>/files/<file>`
（经 `http://127.0.0.1:7890` 代理抓取）

## 为什么要三个面而不是一个

- **display 与 body 必须是两个面** —— 单字体页是模板页的特征（`typography.md` § Bans）。
  但 Space Grotesk 做正文在中文环境里过窄，Geist 做标题又不够有骨架，
  所以按 Cobalt 配方分工。
- **Latin 面在前、CJK 面在后**，浏览器**逐字形回退**：
  拉丁与阿拉伯数字拿到 Geist / Space Grotesk / JetBrains Mono，
  汉字落到 `PingFang SC` → `HarmonyOS Sans SC` → `Microsoft YaHei`。
  这就是中文环境下做排印的正确姿势 —— 不是找"一个中文字体"，而是**分工**。

## 不引什么

- ❌ **Inter / Roboto / Open Sans** —— LLM 默认字体，`typography.md` 明令禁用（Cobalt 原配方里的 Inter 已被 Geist 替换）。
- ❌ **任何 CJK 网络字体** —— 体积与收益不成比例。
