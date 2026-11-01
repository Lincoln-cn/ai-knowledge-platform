package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.platform.infrastructure.metrics.BusinessMetrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 上游网关客户端 —— 对 OpenAI 兼容端点做目录拉取 / 可用性校验 / 能力探测。
 *
 * <p>★ 职责边界（{@code 09 §3.13}）：本类只做<strong>平台侧</strong>的调用（问清单、探能力、验可用性），
 * <strong>不操纵上游实例</strong>（"载入显存"是网关/上游的事）。
 *
 * <p>★ 三条纪律（{@code 09 §3.13.5}）：
 *
 * <ol>
 *   <li>不依赖网关专有字段（{@code service_type} / {@code adapter}）
 *   <li>模型清单一律走 {@code GET /v1/models}，不在平台里维护第二份上游清单
 *   <li>能力探测自己实现，不依赖网关元数据
 * </ol>
 *
 * <p>★ 用 JDK 自带 {@link HttpClient} 而不是 Spring 的 {@code RestTemplate} / {@code WebClient}：
 * 这里的调用都是"问一下就回来"的短请求，不需要响应式、不需要拦截器链。 与 {@code ModelCapabilityProbeTest} 的探针手法同构。
 */
@Component
public class UpstreamGateway {

  private static final Logger log = LoggerFactory.getLogger(UpstreamGateway.class);

  /** POST 的默认超时。★ 业务侧要更短的（查询理解有预算）就显式传 —— 详见 {@link #chatCompletion}。 */
  private static final Duration DEFAULT_POST_TIMEOUT = Duration.ofSeconds(30);

  /** ★ 连接超时设短（3 秒）：load 的可用性校验不应该让管理员等 30 秒才看到"加载失败"。 请求超时另算（GET 10 秒 / POST 30 秒）。 */
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private final ObjectMapper json = new ObjectMapper();

  /** 默认上游 base-url（不含 /v1 之后的路径由各方法拼）。与 {@code spring.ai.openai.base-url} 同源。 */
  private final String defaultBaseUrl;

  /** 默认 API key（Ollama 不校验，但请求头要带）。 */
  private final String defaultApiKey;

  /** ★ 指标旁路（{@code #73}）：只记"这一次 HTTP 出去成功没有"，不记谁在调。 */
  private final BusinessMetrics metrics;

  @org.springframework.beans.factory.annotation.Autowired
  public UpstreamGateway(
      @Value("${spring.ai.openai.base-url:http://localhost:11434/v1}") String defaultBaseUrl,
      @Value("${spring.ai.openai.api-key:ollama-local}") String defaultApiKey,
      BusinessMetrics metrics) {
    this.defaultBaseUrl = trimTrailingSlash(defaultBaseUrl);
    this.defaultApiKey = defaultApiKey;
    this.metrics = metrics;
  }

  /**
   * 测试 / 无 Spring 上下文的构造器。
   *
   * <p>★ 它的指标落在一个不外导的 {@link BusinessMetrics#noop()} 上 —— 这些测试验的是 "路径、请求头、超时"，不是"指标有没有被导出"；后者由
   * {@code BusinessMetricsEndpointTest} 对着真实启动的应用验。两件事分开，才不会因为"测试里随手 new 了一个"而让指标读数失真。
   */
  public UpstreamGateway(String defaultBaseUrl, String defaultApiKey) {
    this(defaultBaseUrl, defaultApiKey, BusinessMetrics.noop());
  }

  // ────────────────────────── 目录 ──────────────────────────

  /** 上游模型清单里的一项（含能力元数据扩展字段）。 */
  public record UpstreamModel(String id, Integer contextWindow, Integer dimension, String tools) {}

  /**
   * 拉取上游模型清单（{@code GET /models}）。
   *
   * <p>★ 上游不可达时抛 {@link UpstreamUnreachableException}（带可读原因）， 调用方决定是降级成空列表还是报错 —— 不在这里静默吞掉。
   *
   * @param apiBase 为 null 时用默认上游
   */
  public List<UpstreamModel> listModels(String apiBase, String apiKey) {
    return listModels(apiBase, apiKey, AuthStyle.BEARER);
  }

  /** 带认证方式的重载：JAiRouter 用 {@code X-API-Key}，OpenAI 兼容用 Bearer。 */
  public List<UpstreamModel> listModels(String apiBase, String apiKey, AuthStyle authStyle) {
    String body = get(resolve(apiBase, "/models"), apiKey, authStyle);
    List<UpstreamModel> result = new ArrayList<>();
    try {
      JsonNode data = json.readTree(body).path("data");
      if (data.isArray()) {
        for (JsonNode item : data) {
          result.add(
              new UpstreamModel(
                  item.path("id").asText(null),
                  // 兼容 OpenAI / Ollama 的两种命名：context_window（我们扩展的）与 context_length（Ollama）
                  firstInt(item, "context_window", "context_length"),
                  firstInt(item, "dimension"),
                  firstText(item, "tools")));
        }
      }
    } catch (JacksonException e) {
      throw new UpstreamUnreachableException("上游返回的模型清单不是合法 JSON：" + e.getMessage(), e);
    }
    return result;
  }

  /** 该基础模型是否在上游清单里（load 的可用性校验）。 */
  public boolean isModelAvailable(String baseModel, String apiBase, String apiKey) {
    return isModelAvailable(baseModel, apiBase, apiKey, AuthStyle.BEARER);
  }

  public boolean isModelAvailable(
      String baseModel, String apiBase, String apiKey, AuthStyle authStyle) {
    return listModels(apiBase, apiKey, authStyle).stream().anyMatch(m -> baseModel.equals(m.id()));
  }

  // ────────────────────────── 向量维度 ──────────────────────────

  /**
   * 调 embeddings，读返回向量长度。
   *
   * <p>★ 维度必须<strong>实测</strong>（从返回向量的长度读），不能从元数据猜 —— 维度与向量表绑定， 猜错会导致"入库时 768 维、检索时 1024
   * 维"这类只能在检索时才暴露的静默错位。
   */
  public int probeEmbeddingDimension(String model, String apiBase, String apiKey) {
    return probeEmbeddingDimension(model, apiBase, apiKey, AuthStyle.BEARER);
  }

  public int probeEmbeddingDimension(
      String model, String apiBase, String apiKey, AuthStyle authStyle) {
    String payload = "{\"model\":\"" + escape(model) + "\",\"input\":\"dimension-probe\"}";
    String body = post(resolve(apiBase, "/embeddings"), payload, apiKey, authStyle);
    try {
      JsonNode embedding = json.readTree(body).path("data").path(0).path("embedding");
      if (!embedding.isArray() || embedding.isEmpty()) {
        throw new UpstreamUnreachableException(
            "上游返回的 embeddings 响应里没有向量数组 —— 无法测定维度。响应片段：" + abbreviate(body));
      }
      return embedding.size();
    } catch (JacksonException e) {
      throw new UpstreamUnreachableException("上游返回的 embeddings 响应不是合法 JSON：" + e.getMessage(), e);
    }
  }

  // ────────────────────────── 向量化 ──────────────────────────

  /**
   * 把一批文本向量化（第 8 周 · 0.3.0 入库链用）。
   *
   * <p>★ <strong>顺序必须严格对齐</strong>：返回的第 i 个向量必须对应入参的第 i 段文本。 向量与文本错配是最难发现的一类事故 —— 检索能返回结果，只是返回的是
   * <em>别的内容</em>的邻居，而且没有任何报错。所以这里：① 要求条数与入参一致；② 按响应里的 {@code index} 归位（不是按数组顺序想当然）； ③ 缺哪一条就报哪一条。
   *
   * @param model 上游模型名（{@code baseModel}）
   * @param texts 待向量化的文本（顺序即结果顺序）
   * @return 与 {@code texts} 一一对应的向量
   * @throws UpstreamUnreachableException 上游不可达、响应不合规、或条数/index 对不上
   */
  public List<float[]> embed(String model, List<String> texts, String apiBase, String apiKey) {
    return embed(model, texts, apiBase, apiKey, AuthStyle.BEARER);
  }

  public List<float[]> embed(
      String model, List<String> texts, String apiBase, String apiKey, AuthStyle authStyle) {
    if (texts == null || texts.isEmpty()) {
      return List.of();
    }
    // ★ 用 Jackson 生成 JSON，**不手拼字符串**。
    //   手拼的转义（只处理 \\ 与 "）在遇到换行/制表符时会发出**非法 JSON**，
    //   而分段的 search_text 里正好含换行（前缀与正文的分隔符）—— 真事故：
    //   非法请求被上游按 400 拒（或被桩服务当成空请求）⇒ 表现为"怎么只回了 1 条向量"。
    // ★ 用 writeValueAsString 而不是 valueToTree(…).toString()：后者的输出格式由 JsonNode 决定，
    //   而这里要的是**确定的合法 JSON**（Jackson 3 下没有再对 toString() 的格式作承诺）
    String payload = json.writeValueAsString(Map.of("model", model, "input", texts));

    String body = post(resolve(apiBase, "/embeddings"), payload, apiKey, authStyle);
    try {
      JsonNode data = json.readTree(body).path("data");
      if (!data.isArray() || data.size() != texts.size()) {
        throw new UpstreamUnreachableException(
            "上游返回的向量条数（"
                + data.size()
                + "）与请求的文本数（"
                + texts.size()
                + "）不一致 —— 顺序无法保证，拒绝入库。请求片段："
                + abbreviate(payload)
                + "；响应片段："
                + abbreviate(body));
      }
      float[][] ordered = new float[texts.size()][];
      int fallbackIndex = 0;
      for (JsonNode item : data) {
        int index = item.hasNonNull("index") ? item.get("index").asInt() : fallbackIndex;
        if (index < 0 || index >= texts.size() || ordered[index] != null) {
          throw new UpstreamUnreachableException(
              "上游返回的向量 index 异常：" + index + "（本批共 " + texts.size() + " 条）");
        }
        ordered[index] = toFloats(item.path("embedding"), body);
        fallbackIndex = index + 1;
      }
      for (int i = 0; i < ordered.length; i++) {
        if (ordered[i] == null) {
          throw new UpstreamUnreachableException("上游未返回第 " + i + " 条文本的向量");
        }
      }
      return List.of(ordered);
    } catch (JacksonException e) {
      throw new UpstreamUnreachableException("上游返回的 embeddings 响应不是合法 JSON：" + e.getMessage(), e);
    }
  }

  private static float[] toFloats(JsonNode array, String body) {
    if (!array.isArray() || array.isEmpty()) {
      throw new UpstreamUnreachableException("上游返回的向量为空或不是数组 —— 无法入库。响应片段：" + abbreviate(body));
    }
    float[] vector = new float[array.size()];
    for (int i = 0; i < array.size(); i++) {
      vector[i] = (float) array.get(i).asDouble();
    }
    return vector;
  }

  // ────────────────────────── 对话补全（第 12 周 · 0.4.0） ──────────────────────────

  /**
   * 发一次对话补全并**取出模型输出的正文**（{@code choices[0].message.content}）。
   *
   * <p>★ 与上面那个 {@link #chat} 的区别：那是**能力探针**（提示词写死"1+1"，还要看响应体里有没有 {@code
   * reasoning_content}），所以它必须拿到<strong>响应体原文</strong>；这里是给业务用的补全调用， 要的是"模型说了什么"。
   *
   * <p>★ {@code timeout} 由调用方给：查询理解这一层的时间直接从首字延迟里扣（{@code 10 §6.1}）， 所以"准时放弃"是它的功能，不是它的容错。
   */
  public String chatCompletion(
      String model, String apiBase, String apiKey, String prompt, Duration timeout) {
    return chatCompletion(model, apiBase, apiKey, prompt, timeout, AuthStyle.BEARER);
  }

  public String chatCompletion(
      String model,
      String apiBase,
      String apiKey,
      String prompt,
      Duration timeout,
      AuthStyle authStyle) {
    return chatCompletion(
        model,
        apiBase,
        apiKey,
        List.of(Map.of("role", "user", "content", prompt)),
        timeout,
        authStyle);
  }

  /**
   * 带**角色边界**的多轮对话（`#168`）：`history` 里每条各自成条，不拼接。
   *
   * <p>★ 为什么单独一个入口、而不是把历史拼进 {@code prompt}：拼进去就丢了 role 边界， 模型分不清"用户上一轮问的"与"参考资料里写的"——那正是反向注入（{@code
   * #84}）的形状。
   *
   * @param history 已按上游协议成形的消息（{@code {"role": …, "content": …}}）， <strong>最后一条必须是本轮用户问题</strong>
   */
  public String chatCompletion(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout) {
    return chatCompletion(model, apiBase, apiKey, history, timeout, AuthStyle.BEARER);
  }

  public String chatCompletion(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout,
      AuthStyle authStyle) {
    return chatCompletionWithUsage(model, apiBase, apiKey, history, timeout, authStyle).content();
  }

  /**
   * 一次性对话补全，并把上游 {@code usage} 一并带回来（{@code #199}）。
   *
   * <p>★ 与流式路径同一口径：上游没给 {@code usage} 时两个分量都是 {@code null}，表示 <strong>没有读数</strong>，不是
   * 0。兜底流式适配器据此决定是否调用 {@code onUsage}。
   */
  public ChatResult chatCompletionWithUsage(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout,
      AuthStyle authStyle) {
    // ★★ 用序列化器拼请求体，**不要**手拼字符串 —— 真栈闸门实测抓到过：
    //   查询理解的提示词是**多行**的，而 escape() 只处理了反斜杠与引号，换行原样留在 JSON
    //   字符串里 ⇒ 上游按"请求体不是合法 JSON"直接 400。那是最难查的一类错：
    //   本地单测全绿，因为夹具里的提示词恰好是单行。
    //   映射成 Map 再序列化，控制字符的转义交给序列化器。
    Map<String, Object> payload = Map.of("model", model, "messages", history);
    String body =
        post(resolve(apiBase, "/chat/completions"), writeJson(payload), apiKey, authStyle, timeout);
    try {
      JsonNode root = json.readTree(body);
      JsonNode content = root.path("choices").path(0).path("message").path("content");
      if (!content.isTextual()) {
        // ★ 明确拒绝而不是返回空串："上游回了 200 但没有正文"与"模型答了个空"是两件事，
        //   而下游（解析）会把两者都看成"解析失败"—— 分开报才知道该找谁
        throw new UpstreamUnreachableException(
            "上游响应里没有 choices[0].message.content。响应片段：" + abbreviate(body));
      }
      JsonNode usage = root.path("usage");
      if (!usage.isObject()) {
        return new ChatResult(content.asText(), null, null);
      }
      JsonNode promptTokens = usage.path("prompt_tokens");
      JsonNode completionTokens = usage.path("completion_tokens");
      if (!promptTokens.isIntegralNumber() || !completionTokens.isIntegralNumber()) {
        return new ChatResult(content.asText(), null, null);
      }
      int promptCount = promptTokens.asInt();
      int completionCount = completionTokens.asInt();
      if (promptCount < 0 || completionCount < 0) {
        return new ChatResult(content.asText(), null, null);
      }
      return new ChatResult(content.asText(), promptCount, completionCount);
    } catch (JacksonException e) {
      throw new UpstreamUnreachableException("上游返回的对话响应不是合法 JSON：" + e.getMessage(), e);
    }
  }

  /** 一次性补全的正文与用量；两个用量分量同时为 {@code null} 表示上游没上报。 */
  public record ChatResult(String content, Integer promptTokens, Integer completionTokens) {

    public ChatResult {
      if ((promptTokens == null) != (completionTokens == null)) {
        throw new IllegalArgumentException("promptTokens 与 completionTokens 必须同时有值或同时为空");
      }
      if (promptTokens != null && (promptTokens < 0 || completionTokens < 0)) {
        throw new IllegalArgumentException(
            "token 数不能为负：prompt=" + promptTokens + ", completion=" + completionTokens);
      }
    }

    public boolean hasUsage() {
      return promptTokens != null;
    }
  }

  // ────────────────────────── 思考三态 ──────────────────────────

  /**
   * ★★ 真流式：把上游 SSE 的每个 delta 立刻交给调用方（而不是等整段返回再切块）。
   *
   * <h3>为什么必须用它（实测）</h3>
   *
   * <p>{@link #chatCompletion} 是「一次性返回」的：CPU 上 qwen2.5:1.5b 生成 400 字要 <b>60~90 秒</b>，这期间下游一个字节都拿不到
   * ⇒ 前端只能"转 90 秒，然后一次性出现全文" （用户原话：「回复是一次性展示的，并没有打字机效果」）。 而 Ollama 本身是逐 token 吐的 —— 中间那一层把它攒起来了。
   *
   * <h3>★ 实现上的两个关键选择</h3>
   *
   * <ol>
   *   <li><b>用 {@code BodyHandlers.ofInputStream()} 而不是 {@code ofLines()}</b>： {@code ofLines()}
   *       要等响应体<b>读完</b>才返回 {@code Stream}，那等于又攒了一次； 只有拿到 InputStream 才能"读到一行就交出去"。
   *   <li><b>逐行解析 {@code data:} 帧、忽略 {@code [DONE]}</b>：这是 OpenAI 兼容协议 （Ollama 的 {@code
   *       /v1/chat/completions} 同样遵守），delta 在 {@code choices[0].delta.content}。
   * </ol>
   *
   * @param onDelta 每收到一段正文就回调一次（★ 它可能抛异常来中断，例如用户点了停止）
   * @return 是否真的走了流式（{@code false} = 上游不支持，调用方应退回一次性路径）
   */
  public boolean streamChatCompletion(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout,
      java.util.function.Consumer<String> onDelta) {
    return streamChatCompletion(
        model, apiBase, apiKey, history, timeout, onDelta, AuthStyle.BEARER);
  }

  public boolean streamChatCompletion(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout,
      java.util.function.Consumer<String> onDelta,
      AuthStyle authStyle) {
    return streamChatCompletion(model, apiBase, apiKey, history, timeout, onDelta, authStyle, null);
  }

  /**
   * 带思考参数的流式补全（{@code #185}）。
   *
   * @param reasoningEffort {@code null} = 请求体里<strong>不出现</strong>该字段；由模型能力映射决定（见 {@code
   *     ChatModelAdapter#streamChat}）
   */
  public boolean streamChatCompletion(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout,
      java.util.function.Consumer<String> onDelta,
      AuthStyle authStyle,
      String reasoningEffort) {
    return streamChatCompletion(
        model, apiBase, apiKey, history, timeout, onDelta, authStyle, reasoningEffort, null);
  }

  /**
   * 带用量回调的流式补全（{@code #142}）。
   *
   * <p>★ 兼容 OpenAI 的流式实现只在最后一个块里给 {@code usage}（需要请求体带 {@code stream_options.include_usage=true}）。
   * 这里不攒正文，只在读到 {@code usage} 时把两个分量交给回调；装配器负责把多段用量相加。
   *
   * @param onUsage {@code null} = 调用方不关心用量；非 null 时每个带 {@code usage} 的帧回调一次
   */
  public boolean streamChatCompletion(
      String model,
      String apiBase,
      String apiKey,
      List<Map<String, String>> history,
      Duration timeout,
      java.util.function.Consumer<String> onDelta,
      AuthStyle authStyle,
      String reasoningEffort,
      java.util.function.BiConsumer<Integer, Integer> onUsage) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("model", model);
    payload.put("messages", history);
    payload.put("stream", true);
    // ★ OpenAI 只有显式要求才在流末尾回 usage；不带这个字段时 done.tokens 仍会是 0（#142）。
    payload.put("stream_options", Map.of("include_usage", true));
    if (reasoningEffort != null) {
      // ★ 只在能力允许时才带：ALWAYS_ON / NONE 不带，避免把"关不掉"伪装成"已关闭"
      payload.put("reasoning_effort", reasoningEffort);
    }
    String url = resolve(apiBase, "/chat/completions");
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));
    // ★ 认证必须走同一处：这里曾内联拼头，绕过了 effectiveKey ⇒ 默认 Bearer 上游在“没配 key”时
    //   发送不带 Authorization 的请求，而 chatCompletion 会回落到配置里的默认 key。两条对话路径
    //   对同一份配置表现不同，症状是“流式 401 / 非流式正常”。
    applyAuth(builder, apiKey, authStyle);
    HttpRequest request = builder.build();
    try {
      HttpResponse<java.io.InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() / 100 != 2) {
        String text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        throw new UpstreamUnreachableException(
            "上游返回 HTTP " + response.statusCode() + "：连接 " + url + " 失败。响应片段：" + abbreviate(text));
      }
      boolean[] sawFrame = {false};
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) {
            continue; // 空行、事件名、注释帧一律跳过
          }
          String data = line.substring(5).trim();
          if (data.isEmpty() || "[DONE]".equals(data)) {
            continue;
          }
          sawFrame[0] = true;
          JsonNode chunk = json.readTree(data);
          JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
          if (delta.isTextual() && !delta.asText().isEmpty()) {
            onDelta.accept(delta.asText());
          }
          JsonNode usage = chunk.path("usage");
          if (onUsage != null && usage.isObject()) {
            JsonNode prompt = usage.path("prompt_tokens");
            JsonNode completion = usage.path("completion_tokens");
            if (prompt.isNumber() && completion.isNumber()) {
              onUsage.accept(prompt.asInt(), completion.asInt());
            }
          }
        }
      }
      boolean streamed = sawFrame[0];
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.OK);
      return streamed;
    } catch (UpstreamUnreachableException rethrow) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      throw rethrow;
    } catch (java.io.IOException | InterruptedException | JacksonException failure) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new UpstreamUnreachableException(
          "上游流式调用失败：" + failure.getMessage() + "（连接 " + url + "）", failure);
    }
  }

  /**
   * 发一次 chat 请求，返回响应体原文。
   *
   * @param reasoningEffort 为 null 表示不带 {@code reasoning_effort} 字段
   */
  public String chat(String model, String reasoningEffort, String apiBase, String apiKey) {
    return chat(model, reasoningEffort, apiBase, apiKey, AuthStyle.BEARER);
  }

  public String chat(
      String model, String reasoningEffort, String apiBase, String apiKey, AuthStyle authStyle) {
    String effort =
        reasoningEffort == null ? "" : ",\"reasoning_effort\":\"" + escape(reasoningEffort) + "\"";
    String payload =
        "{\"model\":\""
            + escape(model)
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"1+1=?只答数字\"}]"
            + effort
            + "}";
    return post(resolve(apiBase, "/chat/completions"), payload, apiKey, authStyle);
  }

  /** 响应体里是否出现了思考内容（OpenAI 兼容协议里的 {@code reasoning_content} / {@code reasoning}）。 */
  public static boolean sawReasoning(String responseBody) {
    return responseBody != null
        && (responseBody.contains("reasoning_content") || responseBody.contains("\"reasoning\""));
  }

  // ────────────────────────── 内部 ──────────────────────────

  private String resolve(String apiBase, String path) {
    String base =
        (apiBase == null || apiBase.isBlank()) ? defaultBaseUrl : trimTrailingSlash(apiBase);
    return base + path;
  }

  private String effectiveKey(String apiKey, AuthStyle authStyle) {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey;
    }
    // ★ 只有默认的 Bearer 上游（Ollama / JAiRouter 的 JWT 面）才回落到配置里的默认 key。
    //   X-API-Key / NONE 上回落会把一个“没配 key”的服务商悄悄拿默认 key 去试，症状是 401。
    return authStyle == AuthStyle.BEARER ? defaultApiKey : null;
  }

  private void applyAuth(HttpRequest.Builder builder, String apiKey, AuthStyle authStyle) {
    // ★ 拼头与校验同址（`#221`）：两条对话路径 + 向量化 + 探活都经过这里，任何一处内联拼头都会
    //   绕开校验，症状是“某条路径填了中文密钥就 500，而另一条路径报得很清楚”。
    AuthHeader.apply(builder, authStyle, effectiveKey(apiKey, authStyle));
  }

  private String get(String url, String apiKey) {
    return get(url, apiKey, AuthStyle.BEARER);
  }

  private String get(String url, String apiKey, AuthStyle authStyle) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
      applyAuth(builder, apiKey, authStyle);
      HttpRequest request = builder.GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new UpstreamUnreachableException(
            "上游返回 HTTP "
                + response.statusCode()
                + "：连接 "
                + url
                + " 失败。响应片段："
                + abbreviate(response.body()));
      }
      String body = response.body();
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.OK);
      return body;
    } catch (UpstreamUnreachableException e) {
      // ★ 记账放在**唯一出口**（get / post / streamChatCompletion 三处），而不是每个业务方法各记一次：
      //   散在业务方法上时，新加一条调用路径就会漏记，而"漏记"的表现是分母偏小 ——
      //   不可达率被**算高**，看起来像上游更糟。这类偏差不会让人去查指标本身。
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      throw e;
    } catch (IOException e) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      throw new UpstreamUnreachableException(
          "网关不可达：连接 " + url + " 失败（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）",
          e);
    } catch (InterruptedException e) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      Thread.currentThread().interrupt();
      throw new UpstreamUnreachableException("网关请求被中断：" + url, e);
    }
  }

  private String post(String url, String payload, String apiKey) {
    return post(url, payload, apiKey, AuthStyle.BEARER, DEFAULT_POST_TIMEOUT);
  }

  private String post(String url, String payload, String apiKey, AuthStyle authStyle) {
    return post(url, payload, apiKey, authStyle, DEFAULT_POST_TIMEOUT);
  }

  private String post(
      String url, String payload, String apiKey, AuthStyle authStyle, Duration timeout) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(timeout)
              .header("Content-Type", "application/json");
      applyAuth(builder, apiKey, authStyle);
      HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new UpstreamUnreachableException(
            "上游返回 HTTP "
                + response.statusCode()
                + "：连接 "
                + url
                + " 失败。响应片段："
                + abbreviate(response.body()));
      }
      String body = response.body();
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.OK);
      return body;
    } catch (UpstreamUnreachableException e) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      throw e;
    } catch (IOException e) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      throw new UpstreamUnreachableException(
          "网关不可达：连接 " + url + " 失败（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）",
          e);
    } catch (InterruptedException e) {
      metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
      Thread.currentThread().interrupt();
      throw new UpstreamUnreachableException("网关请求被中断：" + url, e);
    }
  }

  private static String trimTrailingSlash(String url) {
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static Integer firstInt(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.path(name);
      if (value.isInt() || value.isLong()) {
        return value.asInt();
      }
    }
    return null;
  }

  private static String firstText(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.path(name);
      if (value.isTextual()) {
        return value.asText();
      }
    }
    return null;
  }

  private static String escape(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** 把一个 Map 序列化成请求体。★ 提示词是多行的，控制字符必须由序列化器转义（见 {@link #chatCompletion}）。 */
  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException e) {
      // ★ Jackson 3 起异常是**非受检**的（`JacksonException extends RuntimeException`），
      //   但这个 catch 仍然要留：它是"构造请求体失败"这条读数的唯一出口，
      //   去掉就变成"序列化异常直接冒到调用方"，而调用方按的是 {@link UpstreamUnreachableException} 那条语义。
      // 到不了：Map / List / String 一定能序列化。留着是免得调用方处理一个虚构的分支
      throw new UpstreamUnreachableException("构造请求体失败：" + e.getMessage(), e);
    }
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 200 ? body : body.substring(0, 200) + "…";
  }

  /**
   * 上游不可达 / 响应不合法。
   *
   * <p>★ 它的 message 必须是<strong>能照着排查的那句话</strong>（如「网关不可达：连接 http://… 超时」）—— 这正是 {@code
   * Model.markFailed} 对失败原因的要求。
   */
  public static class UpstreamUnreachableException extends RuntimeException {
    public UpstreamUnreachableException(String message) {
      super(message);
    }

    public UpstreamUnreachableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
