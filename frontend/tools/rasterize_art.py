"""把 `src/assets/art/src/*.svg` 栅格化成 `src/assets/art/*`（PNG 或 WebP）。

为什么要这一步（而不是直接用 SVG）：
  design.md 的 Art layer 要求**位图**。但本项目有两条硬纪律 ——
  ① 资源自托管，不许引外部 CDN（字体就是因此自托管的）；
  ② 离线冒烟（build:mock + 静态服务）必须不依赖外网。
  所以位图只能**本地生成**：源用**手工 SVG**（可版本化、可审阅、可重生成），
  成品用**已装的 Playwright** 栅格化 —— 不引入任何新的图像库或网络依赖。

为什么一部分资产是 WebP 而不是 PNG：
  柔渐变与噪声用 PNG 压不住 —— 实测 blob 1200×1200 的 PNG 是 **608 KB**，颗粒 256×256 是 158 KB。
  Chromium 的 `Page.captureScreenshot` 支持 **WebP（含 alpha）**，只是 Playwright 的
  `page.screenshot()` 没把它暴露出来 ⇒ 这里**直接走 CDP**。线稿类仍是 PNG（锐利且本来就小）。

用法：
    python tools/rasterize_art.py            # 全量重建
    python tools/rasterize_art.py wash       # 只重建名字含 "wash" 的

幂等：同一份 SVG 每次产出一致（Chromium 的滤镜是确定性的，seed 写在 SVG 里）。
"""

from __future__ import annotations

import base64
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "assets" / "art" / "src"
OUT = ROOT / "src" / "assets" / "art"

# name → (输出倍率, 格式, 质量)。未列入的默认 (1, "png", None)。
# 倍率：线稿要 2x 才不糊；柔和的色场 1x 就够（体积差 4 倍）。
SPEC: dict[str, tuple[int, str, int | None]] = {
    "blob-cool-01": (1, "webp", 84),
    "wash-cool-01": (1, "webp", 70),
    "grain-cool-01": (1, "webp", 92),
    "hatch-cool-01": (2, "png", None),
    "schematic-empty-01": (2, "png", None),
    "schematic-empty-02": (2, "png", None),
}

DEFAULT = (1, "png", None)

WRAPPER = """<!doctype html><meta charset="utf-8">
<style>html,body{{margin:0;padding:0;background:transparent;overflow:hidden}}
svg{{display:block}}</style>
{svg}
"""


def size_of(svg_text: str) -> tuple[int, int]:
    """从根 svg 的 width/height（或 viewBox）取像素尺寸。"""
    head = svg_text[: svg_text.index(">") + 1]
    w = re.search(r'\bwidth="(\d+)"', head)
    h = re.search(r'\bheight="(\d+)"', head)
    if w and h:
        return int(w.group(1)), int(h.group(1))
    vb = re.search(r'viewBox="([\d.\s-]+)"', head)
    if vb:
        parts = vb.group(1).split()
        return int(float(parts[2])), int(float(parts[3]))
    raise ValueError("根 svg 既没有 width/height 也没有 viewBox")


def shoot(page, path: Path, fmt: str, quality: int | None) -> None:
    """PNG 走 Playwright 的 omit_background；WebP 走 CDP（并显式把默认底色设成全透明）。"""
    if fmt == "png":
        page.screenshot(path=str(path), omit_background=True)
        return
    cdp = page.context.new_cdp_session(page)
    # 不设这个，CDP 截图会带上一层白底，透明通道就没了
    cdp.send("Emulation.setDefaultBackgroundColorOverride",
             {"color": {"r": 0, "g": 0, "b": 0, "a": 0}})
    res = cdp.send("Page.captureScreenshot", {"format": "webp", "quality": quality or 85})
    path.write_bytes(base64.b64decode(res["data"]))
    cdp.detach()


def main() -> int:
    if not SRC.is_dir():
        print(f"源目录不存在：{SRC}")
        return 1

    needle = sys.argv[1] if len(sys.argv) > 1 else ""
    svgs = sorted(p for p in SRC.glob("*.svg") if needle in p.name)
    if not svgs:
        print(f"没有匹配的 SVG（过滤词 {needle!r}）")
        return 1

    OUT.mkdir(parents=True, exist_ok=True)
    rows: list[tuple[str, str, int, str, int]] = []
    total = 0

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        for svg_path in svgs:
            text = svg_path.read_text(encoding="utf-8")
            w, h = size_of(text)
            scale, fmt, quality = SPEC.get(svg_path.stem, DEFAULT)

            page = browser.new_page(
                viewport={"width": w, "height": h},
                device_scale_factor=scale,
            )
            page.set_content(WRAPPER.format(svg=text))
            out = OUT / f"{svg_path.stem}.{fmt}"
            shoot(page, out, fmt, quality)
            page.close()

            # 换了格式就把另一种残留删掉，避免两个同名文件并存
            stale = OUT / f"{svg_path.stem}.{'png' if fmt == 'webp' else 'webp'}"
            if stale.exists():
                stale.unlink()

            kb = out.stat().st_size // 1024
            total += out.stat().st_size
            rows.append((out.name, f"{w * scale}x{h * scale}", scale, fmt, kb))

        browser.close()

    print(f"{'文件':<26} {'尺寸':>12} {'倍率':>5} {'格式':>6} {'体积':>8}")
    print("-" * 64)
    for name, dim, scale, fmt, kb in rows:
        print(f"{name:<26} {dim:>12} {f'{scale}x':>5} {fmt:>6} {f'{kb}KB':>8}")

    print(f"\n合计 {total / 1024:.0f} KB / {len(rows)} 个文件")

    # ── 体积闸门（design.md § Art layer 定的口径）──
    cap, per_file = 1200, 200
    bad = False
    over = [n for n, _, _, _, kb in rows if kb > per_file]
    if over:
        print(f"★ 单文件超过 {per_file} KB：{', '.join(over)}")
        bad = True
    if total / 1024 > cap:
        print(f"★ 全目录超过 {cap} KB")
        bad = True
    if bad:
        return 1
    print(f"✓ 体积达标（单文件 ≤{per_file}KB · 全目录 ≤{cap}KB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
