"""下载并校验本项目用到的**全部** ONNX 模型。

## 为什么是一个脚本管全部

模型加起来上 GB，**不能进 git**（`models/*` 被忽略），但**必须在发布包里** ——
离线部署环境没有外网，也不该让运维去三个不同的 HuggingFace 仓库各找一遍、
再猜"tokenizer 该放哪个子目录"。

所以所有 ONNX 模型统一走这一条路：

    开发时     python tools/fetch_onnx_models.py                → 补齐缺的
    打包时     python tools/fetch_onnx_models.py --pack <dir>    → 补齐 + 拷进发布包 models/
    离线部署   把发布包里的 models/ 整个拷到目标机的仓库根即可

★ `--pack` 不是"再下一遍"：本地已有的直接拷，只有缺的才下载。

## 清单

| 名字 | 用途 | 仓库 | 体积 |
| --- | --- | --- | --- |
| `ner` | 中文实体识别（0.6 起） | `TangoBeeAkto/bert-chinese-ner-onnx` | 388MB |
| `reranker` | cross-encoder 重排（0.5 起） | `BAAI/bge-reranker-base` | 1061MB |
| `bge-small-zh` | 备选向量模型 | `BAAI/bge-small-zh-v1.5` | 23MB |
| `e5-small` | 默认向量模型（已在用） | `intfloat/multilingual-e5-small` | 113MB |

## ★★ 两个真实的坑（都是我自己踩的，写在这里免得下一个人再踩）

**1. 字节数必须精确到 1 字节。**
第一版凭 `[math]::Round($_.Length/1KB,0)` 的输出抄了近似值（`config.json` 写成 2171，
实际 2121）⇒ 校验失败 ⇒ 脚本把 388MB 的 `model.onnx` 删掉重下了半小时。
现在清单里没有数字的（`None`）**跳过大小校验**，只核"存在且非空"；
有数字的用 `--print-sizes` 现量后填进去。

**2. 大小不符时不再删除。**
"不符就删了重下"是 destructive 的：清单错一次，本地一份好文件就没了。
现在不符只报告，要不要重下由人 `--force` 决定。

## 校验

- 字节数（清单里有的）
- `--smoke`：真加载一次模型跑一个最小输入。**字节数对不等于文件能用** ——
  加载失败、算子不支持、标签表错位都可能发生。

★ 不核 sha256：仓库里没有维护模型的哈希来源，自己算一个只能证明"文件和上次一样"，
证明不了"文件是对的"。
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS_ROOT = ROOT / "models"

#: 每个模型：仓库、目标子目录、要拿的文件（`None` 的大小 = 不校验）。
#: 大小用 `--print-sizes` 现量。只有 ner 与 reranker 量过精确值。
CATALOG = {
    "ner": {
        "repo": "TangoBeeAkto/bert-chinese-ner-onnx",
        "dir": "bert-chinese-ner-onnx",
        "why": "中文实体识别（0.6 起，替代 HanLP 的 NER）",
        "files": {
            "model.onnx": 407_063_645,
            "tokenizer.json": 439_223,
            "config.json": 2_121,
            "vocab.txt": 109_540,
        },
    },
    "reranker": {
        "repo": "BAAI/bge-reranker-base",
        "dir": "bge-reranker-base",
        "why": "cross-encoder 重排（0.5 起，`app.retrieval.rerank.enabled`）",
        "files": {
            "onnx/model.onnx": 1_112_459_588,
            "tokenizer.json": 17_098_107,
        },
    },
    "bge-small-zh": {
        "repo": "BAAI/bge-small-zh-v1.5",
        "dir": "bge-small-zh-v1.5",
        "why": "备选向量模型（`LocalOnnxEmbedding` 可加载）",
        "files": {
            "onnx/model_quantized.onnx": 24_010_842,
            "tokenizer.json": 439_125,
        },
    },
    "e5-small": {
        "repo": "intfloat/multilingual-e5-small",
        "dir": "multilingual-e5-small",
        "why": "默认向量模型",
        "files": {
            "onnx/model_quantized.onnx": 118_308_185,
            "tokenizer.json": 17_082_730,
            "config.json": 658,
        },
    },
}

#: 仓库里的路径带子目录、本地的文件名不带 —— 落盘时取 basename，
#: 因为 `LocalOnnxEmbedding` / `OnnxRerankerAdapter` 都按 `model.onnx` 这个固定名找。
STRIP_DIR = True


def human(n: int) -> str:
    return f"{n / 1024 / 1024:.1f}MB" if n > 1024 * 1024 else f"{n / 1024:.0f}KB"


def download(url: str, dest: Path, segments: int = 8) -> None:
    """分段并发下载。服务器不支持 Range 时自动退回单线程。

    ★★ 真实的坑（2026-11-11，代价是重下两次）：**不能用 HEAD 的 `Content-Length`
    当作总长度**。hf-mirror 对 HEAD 返回的值与真实文件不符（它给 388MB，
    真实文件 366MB），按它切段就会**多切出一段落在文件末尾之外**，
    那一段拿到的是 416 或空内容，拼起来比真实文件短 40MB，
    而分段下载本身"成功"了 —— 只有最后的大小校验能发现。

    正解：先发一个 `Range: bytes=0-0` 的 GET，从响应头 `Content-Range: bytes 0-0/366282945`
    里取斜杠后面的数 —— 那是服务器自己对资源大小的陈述，比 HEAD 可信。
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    total = probe_total(url)
    ranged = total is not None

    if not ranged or segments <= 1:
        print(f"    单线程（{human(total) if total else '未知大小'}）")
        urllib.request.urlretrieve(url, dest)
        return

    chunk = total // segments
    parts = dest.with_suffix(dest.suffix + ".parts")
    shutil.rmtree(parts, ignore_errors=True)
    parts.mkdir(exist_ok=True)

    def fetch(i: int) -> Path:
        start = i * chunk
        end = total - 1 if i == segments - 1 else (i + 1) * chunk - 1
        part = parts / f"{i:02d}"
        expected = end - start + 1
        if part.exists() and part.stat().st_size == expected:
            return part
        req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
        with urllib.request.urlopen(req, timeout=300) as resp, part.open("wb") as out:
            shutil.copyfileobj(resp, out, 1 << 20)
        got = part.stat().st_size
        if got != expected:
            # 不静默接受：少一段就是坏文件，宁可整批失败也不要拼出一个短 40MB 的模型
            sys.exit(f"✗ 第 {i} 段：期望 {expected} 字节，实得 {got}")
        return part

    print(f"    分段 {human(total)} × {segments}")
    with ThreadPoolExecutor(max_workers=segments) as pool:
        got = list(pool.map(fetch, range(segments)))

    with dest.open("wb") as out:
        for part in sorted(got):
            with part.open("rb") as src:
                shutil.copyfileobj(src, out, 1 << 20)
    shutil.rmtree(parts, ignore_errors=True)

    final = dest.stat().st_size
    if final != total:
        sys.exit(f"✗ 拼完是 {final} 字节，服务器说是 {total} —— 中间有段没下全")


def probe_total(url: str) -> int | None:
    """从 `Range: bytes=0-0` 的 `Content-Range` 里取真实总长度。拿不到就返回 None（走单线程）。"""
    req = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            content_range = resp.headers.get("Content-Range") or ""
    except Exception:  # noqa: BLE001 - 拿不到总长度就走单线程，不在这里崩
        return None
    if "/" not in content_range:
        return None
    tail = content_range.rsplit("/", 1)[-1].strip()
    return int(tail) if tail.isdigit() else None


def ensure_one(key: str, spec: dict, endpoint: str, segments: int, force: bool) -> list[str]:
    dest_root = MODELS_ROOT / spec["dir"]
    base = f"{endpoint.rstrip('/')}/{spec['repo']}/resolve/main"
    notes = []
    for remote, size in spec["files"].items():
        local_name = Path(remote).name if STRIP_DIR else remote
        target = dest_root / local_name

        if target.exists() and target.stat().st_size > 0 and not force:
            actual = target.stat().st_size
            if size is not None and actual != size:
                notes.append(f"⚠ {local_name}：实得 {actual}，清单写 {size}（不删，要重下加 --force）")
            else:
                notes.append(f"✓ {local_name}（{human(actual)}）")
            continue

        if target.exists():
            print(f"  --force 重下 {local_name}")
            target.unlink()
        expected = human(size) if size else "未知大小"
        print(f"  下载 {local_name}（{expected}）")
        download(f"{base}/{remote}", target, segments)
        actual = target.stat().st_size
        if actual == 0:
            sys.exit(f"✗ {local_name} 下成了空文件")
        if size is not None and actual != size:
            sys.exit(f"✗ {local_name} 字节数不对：期望 {size}，实得 {actual}（可能下到了错误页）")
        notes.append(f"↓ {local_name}（{human(actual)}）")
    del key
    return notes


def smoke(key: str) -> None:
    """真加载一次。字节数对不等于文件能用。"""
    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError as exc:  # pragma: no cover - 只在缺依赖的机器上走
        print(f"  跳过冒烟：{exc}")
        return

    spec = CATALOG[key]
    root = MODELS_ROOT / spec["dir"]
    onnx_files = list(root.glob("*.onnx"))
    if not onnx_files:
        sys.exit(f"✗ {key}：目录里没有 .onnx")
    session = ort.InferenceSession(str(onnx_files[0]))

    if key == "ner":
        from tokenizers import Tokenizer

        tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
        labels = [
            v["label"] if isinstance(v, dict) else v
            for v in json.loads((root / "config.json").read_text(encoding="utf-8"))["id2label"].values()
        ]
        if not any(x.startswith("B-") and x[2:] in ("organization", "company", "government")
                   for x in labels):
            sys.exit(f"✗ 标签里没有机构类：{sorted(set(labels))}")
        encoded = tokenizer.encode("本制度由财务资产部负责解释。")
        feeds = {
            "input_ids": np.array([encoded.ids], dtype=np.int64),
            "attention_mask": np.array([encoded.attention_mask], dtype=np.int64),
        }
        if "token_type_ids" in {i.name for i in session.get_inputs()}:
            feeds["token_type_ids"] = np.zeros_like(feeds["input_ids"])
        logits = session.run(None, feeds)[0][0]
        spans, current = [], []
        for i, _ in enumerate(encoded.ids):
            label = labels[int(np.argmax(logits[i]))]
            piece = encoded.tokens[i].replace("##", "")
            if label.startswith("B-"):
                if current:
                    spans.append("".join(current))
                current = [piece] if label[2:] in ("organization", "company", "government") else []
            elif label.startswith("I-") and current:
                current.append(piece)
            else:
                if current:
                    spans.append("".join(current))
                current = []
        if current:
            spans.append("".join(current))
        print(f"  冒烟：抽到 {spans}")

    elif key == "reranker":
        import numpy as np

        length = 32
        feeds = {
            "input_ids": np.ones((1, length), dtype=np.int64),
            "attention_mask": np.ones((1, length), dtype=np.int64),
        }
        names = {i.name for i in session.get_inputs()}
        if "token_type_ids" in names:
            feeds["token_type_ids"] = np.zeros((1, length), dtype=np.int64)
        out = session.run(None, feeds)[0]
        print(f"  冒烟：输出形状 {getattr(out, 'shape', None)}")

    else:
        import numpy as np

        feeds = {
            "input_ids": np.ones((1, 16), dtype=np.int64),
            "attention_mask": np.ones((1, 16), dtype=np.int64),
        }
        names = {i.name for i in session.get_inputs()}
        if "token_type_ids" in names:
            feeds["token_type_ids"] = np.zeros((1, 16), dtype=np.int64)
        out = session.run(None, feeds)[0]
        print(f"  冒烟：输出形状 {getattr(out, 'shape', None)}")


def print_sizes() -> None:
    """量本地现有文件的大小，供填进 CATALOG。"""
    for key, spec in CATALOG.items():
        root = MODELS_ROOT / spec["dir"]
        print(f"{key}:（{spec['repo']}）")
        for remote in spec["files"]:
            local = root / (Path(remote).name if STRIP_DIR else remote)
            size = local.stat().st_size if local.exists() else None
            flag = "" if size else "  ← 本地没有，无法量"
            print(f'    "{remote}": {size},{flag}')


def main() -> int:
    parser = argparse.ArgumentParser(description="下载/校验本项目的 ONNX 模型")
    parser.add_argument("--only", help="逗号分隔，只处理这些：" + ",".join(CATALOG))
    parser.add_argument("--endpoint", default="https://hf-mirror.com",
                        help="镜像站；主站是 https://huggingface.co")
    parser.add_argument("--segments", type=int, default=8, help="分段并发数；1 = 单线程")
    parser.add_argument("--force", action="store_true", help="已存在的也重下")
    parser.add_argument("--smoke", action="store_true", help="下完真加载一次")
    parser.add_argument("--pack", type=Path, help="发布包目录：拷到 <pack>/models/")
    parser.add_argument("--print-sizes", action="store_true", help="量本地大小，供填 CATALOG")
    args = parser.parse_args()

    if args.print_sizes:
        print_sizes()
        return 0

    keys = [k.strip() for k in args.only.split(",")] if args.only else list(CATALOG)
    unknown = [k for k in keys if k not in CATALOG]
    if unknown:
        sys.exit(f"未知模型：{unknown}（可选 {sorted(CATALOG)}）")

    for key in keys:
        spec = CATALOG[key]
        print(f"\n[{key}] {spec['why']}")
        for note in ensure_one(key, spec, args.endpoint, args.segments, args.force):
            print(f"  {note}")
        if args.smoke:
            smoke(key)

    if args.pack:
        target = args.pack / "models"
        for key in keys:
            src = MODELS_ROOT / CATALOG[key]["dir"]
            dst = target / CATALOG[key]["dir"]
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
            size = sum(f.stat().st_size for f in dst.rglob("*") if f.is_file())
            print(f"已拷进发布包：{dst}（{human(size)}）")
        print("\n离线部署时把 <发布包>/models/ 整个拷到目标机的仓库根即可。")

    return 0


if __name__ == "__main__":
    sys.exit(main())
