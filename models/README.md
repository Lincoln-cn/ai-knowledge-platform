# 本地 ONNX 向量模型（`models/`）

> ★ **这个目录里，只有 `README.md` 进 git。** 模型文件一律不进（体积大、许可证随模型而异）。
> 规则在根 `.gitignore`（`models/*` + `!models/README.md`），与 `corpus/` 同一个模式。

---

## 一、为什么需要它

向量化现在依赖**外部进程**（Ollama / 桩，`http://localhost:11434`）。这带来一个具体的开发痛点：
**只起后端时，文档解析完会永远停在 `PENDING_EMBEDDING`**，检索因此永远 0 命中 ——
而健康检查一直是 `UP`（"进程活着"与"向量化可用"是两件事，见下）。

本地 ONNX 让向量化**在同一个 JVM 里跑**：

| 场景 | 之前 | 之后 |
|---|---|---|
| 开发：解析 → 入库 → 检索 | 要 8080 + 5280 + **11434** | **8080 + 5280** |
| 桩（`openai_stub.py`） | 假扮 chat **和** embeddings | 只需假扮 **chat** |
| 密级 | `LOCAL_INFERENCE` 已算不出网 | 连 HTTP 都没有，**更强** |

## 二、选了哪个模型，以及**为什么**是它

**`Xenova/bge-base-zh-v1.5`**（BAAI `bge-base-zh-v1.5` 的 ONNX 转换版）

选它的**唯一硬理由**是维度与窗口，而这两条都能在 `config.json` 里**本地核实**（不是抄网上的说法）：

```json
"hidden_size": 768,                 // ⇒ 768 维，正好对上现有 knowledge_vec_embedding.vector(768)
"max_position_embeddings": 512,     // ⇒ 512 token 窗口
"architectures": ["BertModel"],
"model_type": "bert"
```

★ `vector(N)` 是**列类型**，一张表只能装一个维度（`V16` 的注释写死了这条）。
选 768 维模型 = **不用改表、不用新迁移**。这是"能不能低成本落地"的分水岭。

## 三、★★ 512 token 窗口 ⇒ **分段上限强制 400 字**

这是本次设计里最容易出错的一条，已写进设计册
[`03-关键机制设计.md`](../../docs/系统设计方案/03-关键机制设计.md) 的「单分段字符上限」一节。

- 分段的**字数上限**与模型的 **token 窗口**是两回事，不能混用；
- BERT 系中文分词器里 **1 个汉字常常就是 1 个 token**，还要额外留 `[CLS]` / `[SEP]`；
- 所以 `512` 窗口的安全字数约 **400**：`floor(512 × 0.78) = 399`；
- ★ 超出的部分**不参与向量**，检索命中率下降，**但不报错**——属于静默劣化。

⇒ 口径：`实际字数上限 = min(配置回落值, 模型安全字数预算)`。
配置里写 1200，**本地 ONNX 也必须被压到 400**（不许"模型窗口覆盖配置"）。

## 四、文件清单与完整性

| 文件 | 大小（字节） | 来源 |
|---|---|---|
| `tokenizer.json` | 439,090 | `resolve/main/tokenizer.json` |
| `config.json` | 913 | `resolve/main/config.json` |
| `model_quantized.onnx` | **102,868,746** | `resolve/main/onnx/model_quantized.onnx` |

### sha256（**必须核对**，HuggingFace 公布的 LFS oid）

```
model_quantized.onnx  b665f3bba56c3119bc76ba131ebcc544d720a7408cb11581bdf354aaa0198d43
```

核对命令：

```powershell
(Get-FileHash models/bge-base-zh-v1.5/model_quantized.onnx -Algorithm SHA256).Hash.ToLower()
```

> ★★ **实测踩过一次**：用 `Invoke-WebRequest` 下这个 98MB 文件时，传输中断（`unexpected EOF`），
> 但**文件留在了磁盘上**（94,731,896 字节）。如果不比对 sha256，就会得到一个"能打开、但推理结果不可信"的模型 ——
> 而这在后续是**没有任何读数能发现**的（向量照样产出，只是错的）。
> ⇒ **判据：下完必比对 size + sha256，不比对不算下完。**

## 五、怎么下（可复现）

```powershell
$base = 'https://huggingface.co/Xenova/bge-base-zh-v1.5/resolve/main'
curl.exe -L --retry 8 --retry-delay 3 --retry-all-errors -C - `
  --output models/bge-base-zh-v1.5/model_quantized.onnx `
  "$base/onnx/model_quantized.onnx"
# 再按上一节核对 sha256
```

★ 用 `curl -C -`（断点续传）而不是 `Invoke-WebRequest`：后者中断后**不会**续传，只会留半截文件。

## 六、许可

`bge-base-zh-v1.5` 由 BAAI 发布，模型卡标注 **MIT**。
★ 仅本地/内部使用；随产品分发前需再核一次模型卡的许可条款（本仓不把模型放入 git 就是为了这件事留出决定空间）。

## 七、还没做的事（避免误解）

- **没有**接入任何 Java 代码 —— 本目录只放模型文件；
- **没有**验证过推理输出（本机无 ONNX runtime 之前）；
- 接入方案（`InProcessOnnxEmbeddingAdapter` + `OrtSession` 生命周期）见对应 Issue。

★ 特别提醒 ONNX 生命周期的两条官方事实（会写进实现）：
**一个 JVM 只能有一个 `OrtEnvironment`**，且它的 `close()` 自 ORT 1.11 起是 **no-op**
⇒ 「卸载」只能发生在 `OrtSession` 层，`OrtEnvironment` 与进程同生共死。
