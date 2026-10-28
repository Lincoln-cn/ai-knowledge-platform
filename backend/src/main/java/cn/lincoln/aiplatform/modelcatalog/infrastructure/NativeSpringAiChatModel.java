package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把一次上游对话包成 Spring AI 的 {@link ChatModel}，给 {@code ChatClient} 的<b>非流式与流式</b>两种调用用。
 *
 * <p>★ 为什么不直接用 {@code UpstreamGateway}：{@code #62} 的判据②要求<b>默认路径不经过网关</b>。网关留作
 * 显式兜底（以及流式路径），否则"兜底"会第二次悄悄变成"唯一实现"，而这正是这个 Issue 的成因。
 *
 * <p>★ 为什么不用 Spring AI 自带的 {@code OpenAiChatModel}：它 2.0.1 的构建器要求一个 OpenAI SDK 客户端， 而本仓的依赖树里没有对应的
 * okhttp 客户端实现；为了这一个类再引一套传输层，和"少一层"的目标相反。这里只实现 {@link ChatModel} 接口，advisor 链、结构化输出、日志这些 Spring AI
 * 能力全部照常可用。
 *
 * <h3>★ 流式（{@code #62} 剩下的那一半）</h3>
 *
 * <p>{@link ChatModel} 继承 {@code StreamingChatModel}，所以"能流式"从来不是适配器缺的东西；缺的只是 {@link #stream} 这套 SSE
 * 读法。 它和 {@code UpstreamGateway.streamChatCompletion} 逐条同口径（{@code
 * stream_options.include_usage}、跳过空 delta、 容忍 {@code choices} 为空的 usage 帧、非 2xx 抛 {@link
 * UpstreamGateway.UpstreamUnreachableException}）， <b>但读法不同</b>：网关是"读一行、回调一次"，这里是"拉一帧、产出一个 {@link
 * ChatResponse}" —— 因为 Spring AI 的流是 {@code Flux}，而 {@code ChatClient} 之上还挂着 advisor
 * 链（安全闸门正是靠它才能在流式上生效）。
 *
 * <p>★ 为什么是<b>拉</b>而不是"起一个线程往里推"：{@code DefaultStreamResponseSpec} 在 2.0.1 里没有任何 {@code
 * Schedulers}/{@code publishOn}，于是"同步拉"与"异步推"对调用方的可见行为一样，而前者少一条线程、也少一处 背压口径要维护。 消费侧用 {@code
 * toIterable()} 迭代，首帧在消费线程上读，块与块之间不攒。
 */
final class NativeSpringAiChatModel implements ChatModel {

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private final ObjectMapper json = new ObjectMapper();

  private final String chatUrl;
  private final String apiKey;
  private final AuthStyle authStyle;
  private final String baseModel;
  private final Duration timeout;

  /** 能力映射出来的 {@code reasoning_effort}；{@code null} = 这个模型不该带这个字段（{@code #185}）。 */
  private final String reasoningEffort;

  private Integer promptTokens;
  private Integer completionTokens;

  /** 当前这条流的上游连接（供消费者放弃订阅时关掉）；非流式调用永远为 {@code null}。 */
  private volatile SseFrames openStream;

  NativeSpringAiChatModel(
      String apiBase,
      String defaultBaseUrl,
      String apiKey,
      String baseModel,
      AuthStyle authStyle,
      Duration timeout) {
    this(apiBase, defaultBaseUrl, apiKey, baseModel, authStyle, timeout, null);
  }

  NativeSpringAiChatModel(
      String apiBase,
      String defaultBaseUrl,
      String apiKey,
      String baseModel,
      AuthStyle authStyle,
      Duration timeout,
      String reasoningEffort) {
    String base =
        trimTrailingSlash(apiBase == null || apiBase.isBlank() ? defaultBaseUrl : apiBase);
    // 与网关同一口径：apiBase 已经是 .../v1 时不再拼一次
    this.chatUrl =
        base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    this.apiKey = apiKey;
    this.authStyle = authStyle == null ? AuthStyle.BEARER : authStyle;
    this.baseModel = baseModel;
    this.timeout = timeout;
    this.reasoningEffort = reasoningEffort;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    HttpRequest request = requestBuilder(prompt, false).build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UpstreamGateway.UpstreamUnreachableException("连接上游失败：" + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamGateway.UpstreamUnreachableException("调用上游被中断", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new UpstreamGateway.UpstreamUnreachableException(
          "上游返回 HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
    }

    JsonNode root = json.readTree(response.body());
    JsonNode content = root.path("choices").path(0).path("message").path("content");
    if (content.isMissingNode() || content.isNull() || content.asText().isEmpty()) {
      throw new UpstreamGateway.UpstreamUnreachableException(
          "上游没有返回正文：" + abbreviate(response.body()));
    }
    JsonNode usage = root.path("usage");
    if (usage.has("prompt_tokens") && usage.has("completion_tokens")) {
      promptTokens = usage.path("prompt_tokens").asInt();
      completionTokens = usage.path("completion_tokens").asInt();
    }
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content.asText()))));
  }

  /**
   * ★ 真流式： subscrib 时才连上游（{@code Flux.defer}），每拉到一帧正文就产出一个 {@link ChatResponse}。
   *
   * <p>{@code Flux.defer} 不是风格问题：装配 {@code ChatClient} 时就会走到这里，如果连接动作写在方法体里， 那么"没订阅"也会打一次上游请求 ——
   * 判据②（默认路径不许乱碰上游）会因此变成一件只在"没人调用"时成立的事。
   */
  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(
        () -> {
          SseFrames frames = openStream(prompt);
          return Flux.fromIterable(frames).doFinally(signal -> frames.close());
        });
  }

  /** 供消费者放弃订阅时立刻释放上游连接（幂等；{@code doFinally} 也会走这里）。 */
  void closeStream() {
    SseFrames frames = openStream;
    if (frames != null) {
      frames.close();
    }
  }

  Integer promptTokens() {
    return promptTokens;
  }

  Integer completionTokens() {
    return completionTokens;
  }

  /** 连上游并交出可迭代的帧读法；非 2xx 在<b>订阅时</b>就抛出，与网关同一句话术。 */
  private SseFrames openStream(Prompt prompt) {
    HttpRequest request = requestBuilder(prompt, true).build();
    HttpResponse<InputStream> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new UpstreamGateway.UpstreamUnreachableException("连接上游失败：" + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamGateway.UpstreamUnreachableException("调用上游被中断", e);
    }
    if (response.statusCode() / 100 != 2) {
      String text;
      try {
        text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        text = "";
      }
      throw new UpstreamGateway.UpstreamUnreachableException(
          "上游返回 HTTP " + response.statusCode() + "：" + abbreviate(text));
    }
    SseFrames frames = new SseFrames(response.body());
    this.openStream = frames;
    return frames;
  }

  private HttpRequest.Builder requestBuilder(Prompt prompt, boolean stream) {
    Map<String, Object> body = payload(prompt, stream);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(chatUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8));
    if (stream) {
      builder.header("Accept", "text/event-stream");
    }
    if (authStyle.headerName() != null && apiKey != null && !apiKey.isBlank()) {
      builder.header(authStyle.headerName(), authStyle.prefix() + apiKey);
    }
    return builder;
  }

  /**
   * 一次对话的请求体。两条路径共用，差别只有 {@code stream} 与它带出来的 {@code stream_options}。
   *
   * <p>★ 用 {@link LinkedHashMap} 而不是 {@code Map.of}：{@code Map.of} 的迭代顺序不定，而请求体是
   * "上游看到什么"的记录，桩与真模型都按字段名读，但排障时人按顺序读。
   *
   * <p>★ {@code reasoning_effort} 只在非 {@code null} 时出现（{@code #185}）：{@code ALWAYS_ON} 与 {@code
   * NONE} 都不发 —— 把 {@code none} 发给一个始终思考的模型只会得到一个被忽略的字段，却让日志看起来"已经关了"。
   */
  private Map<String, Object> payload(Prompt prompt, boolean stream) {
    List<Map<String, String>> messages = new ArrayList<>();
    for (Message message : prompt.getInstructions()) {
      messages.add(Map.of("role", roleOf(message.getMessageType()), "content", message.getText()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", baseModel);
    body.put("messages", messages);
    body.put("stream", stream);
    if (stream) {
      // ★ 只有显式要求，OpenAI 兼容上游才在流末尾回 usage（#142）；不带这个字段时用量会恒为 0
      body.put("stream_options", Map.of("include_usage", true));
    }
    body.put("temperature", 0.2);
    if (reasoningEffort != null) {
      body.put("reasoning_effort", reasoningEffort);
    }
    return body;
  }

  /**
   * 一条 SSE 流的帧读法：{@link #iterator()} 每次 {@code hasNext()} 最多读一帧，块与块之间不攒。
   *
   * <p>★ 它是 {@link Iterable} 而不是 {@code List}：{@code Flux.fromIterable} 对 {@code Iterable}
   * 是<b>拉</b>语义 —— 订阅方要一个才读一个，读的时候上游才继续吐。攒成 {@code List} 会把"真流式"退化回"等全部返回再切块"。
   *
   * <p>★ usage 帧（{@code choices} 为空）只落用量、不产出元素：它不是正文，交出去会让上层多出一次空 delta。
   */
  private final class SseFrames implements Iterable<ChatResponse>, AutoCloseable {

    private final BufferedReader reader;
    private boolean closed;

    SseFrames(InputStream body) {
      this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    }

    @Override
    public Iterator<ChatResponse> iterator() {
      return new Iterator<>() {
        private ChatResponse next;
        private boolean done;

        @Override
        public boolean hasNext() {
          if (next != null) {
            return true;
          }
          if (done) {
            return false;
          }
          next = readNext();
          if (next == null) {
            done = true;
          }
          return next != null;
        }

        @Override
        public ChatResponse next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          ChatResponse value = next;
          next = null;
          return value;
        }
      };
    }

    /** 拉一帧；{@code [DONE]} 与流结束都返回 {@code null}（正文帧之间没有"空元素"这种东西）。 */
    private ChatResponse readNext() {
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) {
            continue; // 空行、事件名、注释帧一律跳过
          }
          String data = line.substring(5).trim();
          if (data.isEmpty()) {
            continue;
          }
          if ("[DONE]".equals(data)) {
            close();
            return null;
          }
          JsonNode chunk = json.readTree(data);
          JsonNode usage = chunk.path("usage");
          if (usage.isObject()) {
            JsonNode prompt = usage.path("prompt_tokens");
            JsonNode completion = usage.path("completion_tokens");
            if (prompt.isNumber() && completion.isNumber()) {
              promptTokens = prompt.asInt();
              completionTokens = completion.asInt();
            }
          }
          JsonNode content = chunk.path("choices").path(0).path("delta").path("content");
          if (content.isTextual() && !content.asText().isEmpty()) {
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage(content.asText()))));
          }
        }
        close();
        return null;
      } catch (IOException e) {
        close();
        throw new UpstreamGateway.UpstreamUnreachableException(
            "上游流式调用失败：" + e.getMessage() + "（连接 " + chatUrl + "）", e);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        reader.close();
      } catch (IOException ignored) {
        // 关连接失败没有可做的事：调用方已经在退出路径上，这里再抛只会盖住真正的原因
      }
    }
  }

  private String write(Map<String, Object> body) {
    try {
      return json.writeValueAsString(body);
    } catch (RuntimeException e) {
      throw new IllegalStateException("拼对话请求体失败：" + e.getMessage(), e);
    }
  }

  private static String roleOf(MessageType type) {
    return switch (type) {
      case USER -> "user";
      case ASSISTANT -> "assistant";
      case SYSTEM -> "system";
      case TOOL -> "tool";
    };
  }

  private static String trimTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String abbreviate(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
  }
}
