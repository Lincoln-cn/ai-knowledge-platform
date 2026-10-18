# 本地 ONNX 向量模型（`models/`）

> ★ **这个目录里，只有 `README.md` 进 git。** 模型文件一律不进（体积、许可证随模型而异）。
> 规则在根 `.gitignore`（`models/*` + `!models/README.md`），与 `corpus/` 同一个模式。

---

## 一、为什么需要它

向量化现在依赖**外部进程**（Ollama / 桩，`http://localhost:11434`）。带来一个具体的开发痛点：
**只起后端时，文档解析完会永远停在 `PENDING_EMBEDDING`**，检索因此永远 0 命中 ——
而健康检查一直是 `UP`（"进程活着"与"向量化可用"是两件事）。

本地 ONNX 让向量化**在同一个 JVM 里跑**：

| 场景 | 之前 | 之后 |
|---|---|---|
| 开发：解析 → 入库 → 检索 | 要 8080 + 5280 + **11434** | **8080 + 5280** |
| 桩（`openai_stub.py`） | 假扮 chat **和** embeddings | 只需假扮 **chat** |
| 密级 | `LOCAL_INFERENCE` 已算不出网 | 连 HTTP 都没有，**更强** |

## 二、当前默认模型：`multilingual-e5-small`（中英双语，int8 量化）

> ★ 2026-10-18 起默认改为它（依据 = 第八节的实测：命中 10/12 vs 6/12）。
> 下面第三节起关于 `bge-small-zh-v1.5` 的记录**保留** —— 它是可选模型（22.9MB / 中文单语 /
> 体积敏感场景），换模型的方式与代价见第八节。


| 项 | 值 |
|---|---|
| 文件 | `models/bge-small-zh-v1.5/model_quantized.onnx`（**23,448.1 KB**，sha256 `15B717C382BCB518…`） |
| tokenizer | `models/bge-small-zh-v1.5/tokenizer.json`（428.8 KB，sha256 `48CEA5D44424912A…`） |
| 维度 | **512** ⚠️ 与 `V16` 的 `vector(768)` **不同维**（影响见下） |
| 窗口 | 512 token ⇒ 分段上限 **400 字** |

### ★★ 维度从 768 变成 512 —— 这不是问题，是设计内的

`V16` 那张表是 `vector(768)`，而 `vector(N)` 是**列类型**（一张表一个维度）。
但 `vectorindex` 模块（`#5` 修过）**按维度路由 + 按需建表**：

```
VectorIndexPort.tableFor(dimension) → vec_embedding_<N>
JdbcVectorIndex.ensureTable(dimension) → create table if not exists …
```

⇒ 512 维模型的数据会落到 `vec_embedding_512`，与 768 的互不影响。
★ 这条正好是 `#5` 那个设计的**第一次真实使用**；如果它不成立，这里就是第一次红。

> ⚠️ **维度还没有实测确认**：`512` 来自模型规格，不是本机跑出来的。
> 接上之后必须有一条断言 **"产出向量长度 == 512"** —— 维度错了不会报错，
> 只会让写入失败或写进错表。

## 三、从哪里下的（★ 不是 HuggingFace）

**HuggingFace 与 hf-mirror 在本机都不可达**（`curl` exit 28 连接超时），ModelScope 上的
`BAAI/bge-base-zh-v1.5` **没有 ONNX**（只有 config + tokenizer）。

最终来源：**Maven Central 上的 LangChain4j 打包工件**（模型 + tokenizer 都在 jar 里）：

```
dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15-q:1.22.0-beta32
  └─ bge-small-zh-v1.5-q.onnx              23.4 MB
  └─ bge-small-zh-v1.5-q-tokenizer.json    428.8 KB
```

本机解出后的实测大小与 sha256（前 16 位）：

```
model_quantized.onnx  22.9 MB  15B717C382BCB518…
tokenizer.json         0.42 MB  48CEA5D44424912A…
```

> ★★ **一处如实说明**：这两个哈希是**本机算的**，**没有**第三方公布的校验值可比对
> （HF 的 LFS oid 拿不到，因为 HF 不可达）。⇒ 完整性靠"Maven Central 的 jar 本身可信 +
> 解压无错 + 文件大小与 jar 内条目一致"。这比之前那次「下载中断留下半截文件」强得多，
> 但**不等于**"与上游权重逐字节一致"。

## 四、★★ 上一次踩过的坑（记在这里，别再犯）

第一次尝试是 HF 的 `Xenova/bge-base-zh-v1.5` 量化版（目标 **102,868,746** 字节，sha256 `b665f3bb…`）。
`Invoke-WebRequest` 下到 94,731,896 字节时中断，**但半截文件留在了磁盘上**。

不比对 sha256 的话，会得到一个"能加载、但向量不可信"的模型 —— 而后续**没有任何读数能发现**
（向量照常产出，只是错的）。

⇒ 判据：**下完必比对 size + sha256**；拿不到上游校验值时，至少比对"文件大小 == jar 内条目大小"。

## 五、怎么复现

```powershell
$v  = '1.22.0-beta32'
$jar = "https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-embeddings-bge-small-zh-v15-q/$v/langchain4j-embeddings-bge-small-zh-v15-q-$v.jar"
curl.exe -sS -L -o "$env:TEMP\bge-zh.jar" $jar
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead("$env:TEMP\bge-zh.jar")
# 解出 bge-small-zh-v1.5-q.onnx           → models/bge-small-zh-v1.5/model_quantized.onnx
#     bge-small-zh-v1.5-q-tokenizer.json → models/bge-small-zh-v1.5/tokenizer.json
```

## 六、许可

`bge-small-zh-v1.5` 由 BAAI 发布，模型卡标注 **MIT**；本目录用的是 LangChain4j 的 ONNX 转换件。
★ 仅本地/内部使用；随产品分发前需再核一次模型卡与转换件的许可条款。

## 七、实现选型（已定，未接线）

- 用 **官方 Spring AI** 的 `org.springframework.ai.transformers.TransformersEmbeddingModel`
  （`spring-ai-transformers:2.0.1`，与本仓 Spring AI 同版本；Maven Central 可达）。
  ⇒ 不引入第二个 AI 框架；模型文件走磁盘路径（`modelUri` / `tokenizerUri`）。
- ★ 官方 ONNX 生命周期的两条事实（写进实现）：
  **一个 JVM 只能有一个 `OrtEnvironment`**，且它的 `close()` 自 ORT 1.11 起是 **no-op**
  ⇒ 「卸载」只能发生在 `OrtSession` 层。
- ★ **不做**多个本地模型热切换：本地只留一个，多模型交给服务侧（HTTP）。

## 八、模型对比：中英双语的代价（2026-10-18 实测）

用户问的是「能不能中英双语、体量再小一点、更新一点」。**结论先说：双语能，体量小不了 —— 两者在现成 ONNX 里不可兼得。**

### 候选与体积（`hf-mirror` 实测 content-length + 本地下载核对）

| 模型 | 发布 | 参数 | 维度 | int8 体积 | 中 | 英 |
|---|---|---|---|---|---|---|
| `bge-small-zh-v1.5`（当前默认） | 2023-09 | 24M | 512 | **22.9 MB** | 强 | **弱** |
| `multilingual-e5-small` | 2024-02 | 118M | 384 | **112.8 MB** | 强 | 强 |
| `paraphrase-multilingual-MiniLM-L12-v2` | 2020 | 118M | 384 | **112.8 MB** | 中 | 中 |
| `granite-embedding-107m-multilingual` | 2024-12 | 107M | 384 | 无 ONNX 镜像（要本地转换） | 强 | 强 |
| `bge-m3` | 2024-01 | 568M | 1024 | ~570 MB | 最强 | 最强 |

★★ **为什么小不下来**：多语模型最小也是 ~118M 参数，而词表 ~25 万 × 384 维的
embedding 矩阵在 Xenova 的量化件里**仍然是 fp32**（约 100 MB 里的大头）⇒ int8 也只压到 112.8 MB。
`bge-small-zh` 之所以只有 22.9 MB，是因为它**中文单语 + 小词表**。

### ★★ 实测：拿同一套 16 份语料 + 12 条问答对，只换模型

同一条命令（`scripts/parse_corpus.py`，`HYBRID` / `threshold=0` / `topN=5`），
只改 `CORPUS_MODEL_NAME` / `CORPUS_MODEL_DIMENSION` 与后端那三个 `app.embedding.local.*`：

| 模型 | 命中率 | 英文文档（`gpl-3.0` 3 条 + `full-markdown` 3 条） |
|---|---|---|
| `bge-small-zh-v1.5`（22.9 MB） | **6/12（50%）** | **1/6** |
| `multilingual-e5-small`（112.8 MB） | **10/12（83%）** | **5/6** |

★ 16/16 文档在两次实验里都 `SUCCEEDED`（384 维落到 `vec_embedding_384`，路由与建表按维度走，照常）。
★ 还剩 2 条失败（`q-005` 刘易斯文本 · `q-012` markdown 示例），与语言无关，属检索策略层面。

### ★ 一处**没有做**的事，会影响读数

`multilingual-e5` 系列**建议给输入加前缀**：`query: …`（问题）与 `passage: …`（文档）。
上面 10/12 是**完全没加前缀**的结果 —— 因为 `EmbeddingPort.embed(modelId, texts)` 今天
**不区分"问题"与"文档"**，加前缀需要动接口语义（`RetrievalService` 与入库侧各传一种角色）。
⇒ **加上前缀可能更高**，但那是一次真实的接口改造，不在本次范围内。

### 怎么切换（三个配置 + 一次重灌）

1. 把模型放到 `models/<name>/`（两个文件：`model_quantized.onnx` + `tokenizer.json`）；
2. 后端三项：

```
app.embedding.local.model-path=models/multilingual-e5-small/model_quantized.onnx
app.embedding.local.tokenizer-path=models/multilingual-e5-small/tokenizer.json
app.embedding.local.model-name=multilingual-e5-small
```

3. 在模型目录里**登记同名 `baseModel`**（路由按名字分派！名字对不上就会去连上游 11434），
   维度填 **384**；
4. ★ **已有知识库的向量不能复用**：不同模型 = 不同向量空间 ⇒ 必须重新向量化
   （`vec_embedding_384` 是新表，旧 512 表不会被读）。这也是"一个库不许中途换模型"那条口径的由来。

