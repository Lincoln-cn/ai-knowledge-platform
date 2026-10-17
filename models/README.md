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

## 二、当前用的模型：`bge-small-zh-v1.5`（int8 量化）

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
