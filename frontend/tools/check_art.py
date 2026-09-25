"""量 `src/assets/art/` 下每个位图**用起来会不会看得见**，以及形态对不对。

── 为什么需要它（被两次真实缺陷逼出来的）────────────────────────────────────

缺陷一（色场太淡）：`wash-cool-01` 第一版把色标不透明度压在 0.20–0.30，素材自身
  alpha 峰值只有 88/255。按 `--art-opacity-wash`（0.6）用上去，页头色场的开/关
  像素均差只有 **1.62/255** —— 肉眼看不出色场存在。
  ★ 教训：**`--art-opacity-*` 管的是"用多少"，管不了"素材里有多少"。**
    实测把上限从 0.6 抬到 1.0 只让均差 1.62 → 2.65，是无效手段 —— 要重画素材。

缺陷二（颗粒是纱不是颗粒）：`grain-cool-01` 第一版把 alpha 直接乘 0.5，
  **不透明覆盖率 100%** —— 它不是颗粒，是一层均匀的纱，叠上去只整体压暗。
  ★ 教训：颗粒的正确指标是**覆盖率低 + 对比度高**，不是 alpha 均值。

── 判据设计（这是本文件最要紧的部分）───────────────────────────────────────

同一个"alpha 均值"对四类素材含义完全不同，所以**不能用一个阈值**：

| 类 | 素材 | 真正要问的 | 判据 |
|---|---|---|---|
| `field` | wash / blob | 贴上去**改动了多少像素**（看得见吗） | 按声明的合成方式贴到纸/石墨上，**像素均差 ≥ 4/255** |
| `texture` | grain | 是**斑点**还是**纱** | 覆盖率 ∈ [8%, 45%] 且 alpha 标准差 ≥ 20 |
| `pattern` | hatch | 线**在不在** | alpha 峰值 ≥ 120 且覆盖率 ∈ [8%, 60%] |
| `line` | schematic | 线稿**干净吗** | alpha 峰值 ≥ 150 且覆盖率 ≤ 12% |

★ `field` 用**像素均差**而不是 alpha 统计，是因为只有前者能回答"看不看得见"。
★ 阈值不是猜的：`4/255` 来自实测对照 —— 1.62/255 肉眼看不见（缺陷一），
  53/255 明显可见（登录页色块），取两者之间偏保守的一档。

用法：
    python tools/check_art.py
"""

from __future__ import annotations

import base64
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "src" / "assets" / "art"
TOKENS = ROOT / "tokens.css"

# (类, 使用时的背景, 使用时的合成方式, 使用时的不透明度)
# 不透明度取自 tokens.css 的 --art-opacity-*，与实际用法一致 —— 判据必须复刻真实用法。
USAGE: dict[str, tuple[str, str, str, float]] = {
    "wash-cool-01":       ("field",   "paper",    "multiply",    0.60),
    "blob-cool-01":       ("field",   "graphite", "screen",      0.35),
    "grain-cool-01":      ("texture", "graphite", "source-over", 0.15),
    "hatch-cool-01":      ("pattern", "graphite", "source-over", 0.35),
    "schematic-empty-01": ("line",    "paper",    "source-over", 1.00),
    "schematic-empty-02": ("line",    "paper",    "source-over", 1.00),
}

MEASURE = """async ([dataUrl, bg, opacity, blend]) => {
  const img = new Image();
  img.src = dataUrl;
  await img.decode();
  const w = img.naturalWidth, h = img.naturalHeight;

  const mk = () => {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    return [c, c.getContext('2d', { willReadFrequently: true })];
  };

  // ① 素材自身的 alpha（用来判断"素材在哪"，以及形态统计）
  const [c0, g0] = mk();
  g0.clearRect(0, 0, w, h); g0.drawImage(img, 0, 0);
  const raw = g0.getImageData(0, 0, w, h).data;

  // ② 底：铺满背景色
  const [cA, a] = mk();
  a.fillStyle = bg; a.fillRect(0, 0, w, h);
  const base = a.getImageData(0, 0, w, h).data;

  // ③ 叠：按真实用法（合成方式 + 不透明度）
  const [cB, b] = mk();
  b.fillStyle = bg; b.fillRect(0, 0, w, h);
  b.globalAlpha = opacity;
  b.globalCompositeOperation = blend;
  b.drawImage(img, 0, 0);
  const over = b.getImageData(0, 0, w, h).data;

  // ④ 看得见吗：两个口径
  //    diffAll     —— 全图均差（对铺得满的素材合适）
  //    diffCovered —— **只在素材有料的地方**量（对"叠层英雄图"这类局部素材才公平）
  let dAll = 0, nAll = 0, dCov = 0, nCov = 0;
  for (let i = 0; i < over.length; i += 4) {
    const d = Math.abs(over[i] - base[i]) + Math.abs(over[i+1] - base[i+1]) + Math.abs(over[i+2] - base[i+2]);
    dAll += d; nAll += 3;
    if (raw[i+3] > 8) { dCov += d; nCov += 3; }
  }

  // ⑤ 形态：alpha 统计
  let aSum = 0, aMax = 0, cov = 0, tot = 0;
  const vals = [];
  for (let i = 3; i < raw.length; i += 4) {
    const v = raw[i];
    aSum += v; tot++;
    if (v > aMax) aMax = v;
    if (v > 8) cov++;
    vals.push(v);
  }
  const mean = aSum / tot;
  let sq = 0;
  for (const v of vals) sq += (v - mean) * (v - mean);

  return {
    w, h,
    diffAll: dAll / nAll,
    diffCovered: nCov ? dCov / nCov : 0,
    coverage: (cov / tot) * 100,
    aMax, aMean: mean, aStd: Math.sqrt(sq / tot),
  };
}"""


def resolve_bg(page, tokens_text: str, which: str) -> str:
    """把 tokens.css 里的 oklch 值经探针元素解析成 rgb() —— canvas 不认 oklch。"""
    name = "--color-paper" if which == "paper" else "--color-graphite"
    m = re.search(rf"{name}:\s*([^;]+);", tokens_text)
    if not m:
        raise ValueError(f"{name} 不在 tokens.css 里")
    return page.evaluate(
        """(val) => {
            const p = document.createElement('span');
            p.style.color = val;
            document.body.appendChild(p);
            const c = getComputedStyle(p).color;
            p.remove();
            return c;
        }""",
        m.group(1).strip(),
    )


def judge(kind: str, m: dict) -> list[str]:
    f: list[str] = []
    if kind == "field":
        # 只在"素材有料的地方"量 —— 叠层英雄图只覆盖约 1/4 画面，
        # 用全图均差会把它判成"看不见"，那是指标的问题，不是素材的问题。
        if m["diffCovered"] < 8.0:
            f.append(f"有料处均差 {m['diffCovered']:.2f}/255 < 8（看不见）")
        if m["coverage"] < 18.0:
            f.append(f"覆盖率 {m['coverage']:.1f}% < 18%（铺得太小，衬不住）")
    elif kind == "texture":
        if not (8.0 <= m["coverage"] <= 45.0):
            f.append(f"覆盖率 {m['coverage']:.1f}% 不在 8–45%（不是斑点，是纱或没有）")
        if m["aStd"] < 20:
            f.append(f"alpha 标准差 {m['aStd']:.1f} < 20（没有对比 = 没有纹路）")
    elif kind == "pattern":
        if m["aMax"] < 120:
            f.append(f"alpha 峰值 {m['aMax']} < 120（线太淡）")
        if not (8.0 <= m["coverage"] <= 60.0):
            f.append(f"覆盖率 {m['coverage']:.1f}% 不在 8–60%")
    elif kind == "line":
        if m["aMax"] < 150:
            f.append(f"alpha 峰值 {m['aMax']} < 150（线太淡）")
        if m["coverage"] > 12.0:
            f.append(f"覆盖率 {m['coverage']:.1f}% > 12%（线稿不该铺满）")
    return f


def main() -> int:
    files = sorted(p for p in ART.glob("*") if p.suffix in (".webp", ".png"))
    if not files:
        print(f"{ART} 下没有位图")
        return 1
    tokens_text = TOKENS.read_text(encoding="utf-8")

    print(f"{'文件':<26} {'类':<9} {'有料处均差':>10} {'全图均差':>9} {'覆盖率':>8} {'alpha均':>7} {'std':>6} {'峰值':>5}  判定")
    print("-" * 108)

    bad = 0
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        page = browser.new_page()
        page.set_content("<body></body>")
        bgs = {k: resolve_bg(page, tokens_text, k) for k in ("paper", "graphite")}

        for f in files:
            kind, bg_name, blend, opacity = USAGE.get(f.stem, ("line", "paper", "source-over", 1.0))
            data = base64.b64encode(f.read_bytes()).decode()
            ext = "webp" if f.suffix == ".webp" else "png"
            m = page.evaluate(MEASURE, [f"data:image/{ext};base64,{data}", bgs[bg_name], opacity, blend])
            fails = judge(kind, m)
            if fails:
                bad += 1
            verdict = "✓" if not fails else "★ " + "；".join(fails)
            print(f"{f.name:<26} {kind:<9} {m['diffCovered']:>10.2f} {m['diffAll']:>9.2f} "
                  f"{m['coverage']:>7.1f}% {m['aMean']:>7.1f} {m['aStd']:>6.1f} {m['aMax']:>5}  {verdict}")
        browser.close()

    print()
    if bad:
        print(f"★ {bad} 个素材不合格 —— 重画源 SVG；**不要**抬 --art-opacity-* 上限（实测无效）")
        return 1
    print("✓ 全部素材：用得上、看得见、形态对")
    return 0


if __name__ == "__main__":
    sys.exit(main())
