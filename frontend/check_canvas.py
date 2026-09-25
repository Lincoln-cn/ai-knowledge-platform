"""编排画布几何探针 —— 量「一页装下」与 fitView 缩放。

为什么单独写：`shot_workflow.py` 的"被切"判据在**截图之后**读 bounding_box，
而 `canvas.screenshot()` 会滚动页面 ⇒ 两套坐标空间混用。
本脚本在**同一快照内**把节点坐标换算成**相对画布**的逻辑坐标，无歧义。

用法：python _probe_canvas.py
"""

from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280"

JS = """
() => {
  // fitView 的缩放在某个带 matrix 的元素上
  let scale = null;
  document.querySelectorAll('*').forEach((el) => {
    const t = getComputedStyle(el).transform;
    if (t && t.startsWith('matrix')) {
      const m = t.match(/matrix\\(([^)]+)\\)/);
      if (m) {
        const v = m[1].split(',').map(Number);
        if (Math.abs(v[0] - 1) > 0.001) scale = +v[0].toFixed(3);
      }
    }
  });
  if (!scale) return 'no scale found';

  const canvas = document.querySelector('.workflow-main').getBoundingClientRect();
  const rs = [...document.querySelectorAll('.workflow-node-container')].map((n) =>
    n.getBoundingClientRect()
  );
  const minL = Math.min(...rs.map((r) => r.left));
  const minT = Math.min(...rs.map((r) => r.top));

  // 全部换算回**逻辑坐标**（除以缩放），再相对左上角归一
  const L = rs.map((r) => ({
    l: Math.round((r.left - minL) / scale),
    t: Math.round((r.top - minT) / scale),
    w: Math.round(r.width / scale),
    h: Math.round(r.height / scale),
  }));

  // 列（rank）归并
  const cols = [];
  L.slice().sort((a, b) => a.l - b.l).forEach((x) => {
    if (!cols.length || x.l - cols[cols.length - 1].l > 10) cols.push({ l: x.l, w: x.w, n: 1 });
    else cols[cols.length - 1].n++;
  });
  const gaps = [];
  for (let i = 1; i < cols.length; i++) gaps.push(cols[i].l - (cols[i - 1].l + cols[i - 1].w));

  const spanX = Math.round(Math.max(...L.map((x) => x.l + x.w)));
  const spanY = Math.round(Math.max(...L.map((x) => x.t + x.h)) - Math.min(...L.map((x) => x.t)));
  const canvasLogicalW = Math.round(canvas.width / scale);
  const canvasLogicalH = Math.round(canvas.height / scale);

  // 被切出画布的节点（同一快照内、相对画布）
  const clipped = rs.filter(
    (r) =>
      r.top < canvas.top - 1 ||
      r.bottom > canvas.bottom + 1 ||
      r.left < canvas.left - 1 ||
      r.right > canvas.right + 1
  ).length;

  let overlap = 0;
  for (let i = 0; i < rs.length; i++)
    for (let j = i + 1; j < rs.length; j++) {
      const a = rs[i], b = rs[j];
      if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) overlap++;
    }

  const body = document.querySelector('.node-body');

  // 节点名是否换行 —— 窄卡里断词比截断难读，且两行标题会把卡高顶起来、压低缩放
  const titles = [...document.querySelectorAll('.workflow-node-container h4')].map((h) => {
    const cs = getComputedStyle(h);
    const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.3;
    return { text: (h.textContent || '').trim().slice(0, 12), lines: Math.round(h.scrollHeight / lh) };
  });
  const wrapped = titles.filter((t) => t.lines > 1);
  const truncated = [...document.querySelectorAll('.workflow-node-container h4')].filter(
    (h) => h.scrollWidth > h.clientWidth + 1
  ).length;

  return {
    scale,
    nodeCount: rs.length,
    cardW: L[0].w,
    cardH: L[0].h,
    cols: cols.length,
    colNodes: cols.map((c) => c.n),
    gaps,
    spanX,
    spanY,
    scaleNeededX: +(canvas.width / scale / spanX).toFixed(3),
    scaleNeededY: +(canvas.height / scale / spanY).toFixed(3),
    clipped,
    overlap,
    bodyScrolls: body ? body.scrollHeight > body.clientHeight + 1 : null,
    titleWrapped: wrapped.length,
    wrappedTexts: wrapped.map((t) => `${t.text}(${t.lines}行)`),
    titleTruncated: truncated,
  };
}
"""


def main() -> int:
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 900})
        errs: list[str] = []
        page.on("pageerror", lambda e: errs.append(str(e)))

        def on_console(m) -> None:
            if m.type != "error":
                return
            url = (m.location or {}).get("url", "") if isinstance(m.location, dict) else ""
            if "favicon" not in url:
                errs.append(f"{m.text} @ {url}")

        page.on("console", on_console)

        page.goto(f"{BASE}/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1800)
        page.goto(f"{BASE}/#/assistants/as5/workflow", wait_until="networkidle")
        page.wait_for_timeout(5500)

        r = page.evaluate(JS)
        if isinstance(r, str):
            print("✗ " + r)
            b.close()
            return 1

        print("── 画布几何（逻辑坐标） ──")
        print(f"  fitView 缩放        {r['scale']}   ← 目标 ≥ 0.45")
        print(f"  节点数              {r['nodeCount']}")
        print(f"  卡尺寸              {r['cardW']} × {r['cardH']}")
        print(f"  列数                {r['cols']}  各列节点 {r['colNodes']}")
        print(f"  列间距              {r['gaps']}")
        print(f"  逻辑跨度            X {r['spanX']}  Y {r['spanY']}")
        print(f"  各维所需缩放        X {r['scaleNeededX']}  Y {r['scaleNeededY']}   ← 小的那个是约束")
        print(f"  重叠对数            {r['overlap']}（应 0）")
        print(f"  被切出画布节点      {r['clipped']}（应 0）")
        print(f"  卡内可滚动          {r['bodyScrolls']}")
        print(f"  节点名换行          {r['titleWrapped']} 个（应 0）{r['wrappedTexts'] if r['wrappedTexts'] else ''}")
        print(f"  节点名被截断        {r['titleTruncated']} 个")
        print(f"  控制台错误          {errs if errs else 0}")
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
