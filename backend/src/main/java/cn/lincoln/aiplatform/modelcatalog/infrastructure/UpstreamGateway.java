package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

  /** ★ 连接超时设短（3 秒）：load 的可用性校验不应该让管理员等 30 秒才看到"加载失败"。 请求超时另算（GET 10 秒 / POST 30 秒）。 */
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private final ObjectMapper json = new ObjectMapper();

  /** 默认上游 base-url（不含 /v1 之后的路径由各方法拼）。与 {@code spring.ai.openai.base-url} 同源。 */
  private final String defaultBaseUrl;

  /** 默认 API key（Ollama 不校验，但请求头要带）。 */
  private final String defaultApiKey;

  public UpstreamGateway(
      @Value("${spring.ai.openai.base-url:http://localhost:11434/v1}") String defaultBaseUrl,
      @Value("${spring.ai.openai.api-key:ollama-local}") String defaultApiKey) {
    this.defaultBaseUrl = trimTrailingSlash(defaultBaseUrl);
    this.defaultApiKey = defaultApiKey;
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
    String body = get(resolve(apiBase, "/models"), apiKey);
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
    } catch (IOException e) {
      throw new UpstreamUnreachableException("上游返回的模型清单不是合法 JSON：" + e.getMessage(), e);
    }
    return result;
  }

  /** 该基础模型是否在上游清单里（load 的可用性校验）。 */
  public boolean isModelAvailable(String baseModel, String apiBase, String apiKey) {
    return listModels(apiBase, apiKey).stream().anyMatch(m -> baseModel.equals(m.id()));
  }

  // ────────────────────────── 向量维度 ──────────────────────────

  /**
   * 调 embeddings，读返回向量长度。
   *
   * <p>★ 维度必须<strong>实测</strong>（从返回向量的长度读），不能从元数据猜 —— 维度与向量表绑定， 猜错会导致"入库时 768 维、检索时 1024
   * 维"这类只能在检索时才暴露的静默错位。
   */
  public int probeEmbeddingDimension(String model, String apiBase, String apiKey) {
    String payload = "{\"model\":\"" + escape(model) + "\",\"input\":\"dimension-probe\"}";
    String body = post(resolve(apiBase, "/embeddings"), payload, apiKey);
    try {
      JsonNode embedding = json.readTree(body).path("data").path(0).path("embedding");
      if (!embedding.isArray() || embedding.isEmpty()) {
        throw new UpstreamUnreachableException(
            "上游返回的 embeddings 响应里没有向量数组 —— 无法测定维度。响应片段：" + abbreviate(body));
      }
      return embedding.size();
    } catch (IOException e) {
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
    if (texts == null || texts.isEmpty()) {
      return List.of();
    }
    StringBuilder input = new StringBuilder("[");
    for (int i = 0; i < texts.size(); i++) {
      if (i > 0) {
        input.append(',');
      }
      input.append('"').append(escape(texts.get(i))).append('"');
    }
    input.append(']');
    String payload = "{\"model\":\"" + escape(model) + "\",\"input\":" + input + "}";

    String body = post(resolve(apiBase, "/embeddings"), payload, apiKey);
    try {
      JsonNode data = json.readTree(body).path("data");
      if (!data.isArray() || data.size() != texts.size()) {
        throw new UpstreamUnreachableException(
            "上游返回的向量条数（"
                + data.size()
                + "）与请求的文本数（"
                + texts.size()
                + "）不一致 —— 顺序无法保证，拒绝入库。响应片段："
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
    } catch (IOException e) {
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

  // ────────────────────────── 思考三态 ──────────────────────────

  /**
   * 发一次 chat 请求，返回响应体原文。
   *
   * @param reasoningEffort 为 null 表示不带 {@code reasoning_effort} 字段
   */
  public String chat(String model, String reasoningEffort, String apiBase, String apiKey) {
    String effort =
        reasoningEffort == null ? "" : ",\"reasoning_effort\":\"" + escape(reasoningEffort) + "\"";
    String payload =
        "{\"model\":\""
            + escape(model)
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"1+1=?只答数字\"}]"
            + effort
            + "}";
    return post(resolve(apiBase, "/chat/completions"), payload, apiKey);
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

  private String effectiveKey(String apiKey) {
    return (apiKey == null || apiKey.isBlank()) ? defaultApiKey : apiKey;
  }

  private String get(String url, String apiKey) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + effectiveKey(apiKey))
              .GET()
              .build();
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
      return response.body();
    } catch (UpstreamUnreachableException e) {
      throw e;
    } catch (IOException e) {
      throw new UpstreamUnreachableException(
          "网关不可达：连接 " + url + " 失败（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnreachableException("网关请求被中断：" + url, e);
    }
  }

  private String post(String url, String payload, String apiKey) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + effectiveKey(apiKey))
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
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
      return response.body();
    } catch (UpstreamUnreachableException e) {
      throw e;
    } catch (IOException e) {
      throw new UpstreamUnreachableException(
          "网关不可达：连接 " + url + " 失败（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）",
          e);
    } catch (InterruptedException e) {
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
