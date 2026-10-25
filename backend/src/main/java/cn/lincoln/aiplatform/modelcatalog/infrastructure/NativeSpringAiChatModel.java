package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
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
 * 把一次上游对话包成 Spring AI 的 {@link ChatModel}，只给 {@code ChatClient} 的<b>非流式</b>调用用。
 *
 * <p>★ 为什么不直接用 {@code UpstreamGateway}：{@code #62} 的判据②要求<b>默认路径不经过网关</b>。网关留作
 * 显式兜底（以及流式路径），否则"兜底"会第二次悄悄变成"唯一实现"，而这正是这个 Issue 的成因。
 *
 * <p>★ 为什么不用 Spring AI 自带的 {@code OpenAiChatModel}：它 2.0.1 的构建器要求一个 OpenAI SDK 客户端， 而本仓的依赖树里没有对应的
 * okhttp 客户端实现；为了这一个类再引一套传输层，和"少一层"的目标相反。这里只实现 {@link ChatModel} 接口，advisor 链、结构化输出、日志这些 Spring AI
 * 能力全部照常可用。
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

  private Integer promptTokens;
  private Integer completionTokens;

  NativeSpringAiChatModel(
      String apiBase,
      String defaultBaseUrl,
      String apiKey,
      String baseModel,
      AuthStyle authStyle,
      Duration timeout) {
    String base =
        trimTrailingSlash(apiBase == null || apiBase.isBlank() ? defaultBaseUrl : apiBase);
    // 与网关同一口径：apiBase 已经是 .../v1 时不再拼一次
    this.chatUrl =
        base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    this.apiKey = apiKey;
    this.authStyle = authStyle == null ? AuthStyle.BEARER : authStyle;
    this.baseModel = baseModel;
    this.timeout = timeout;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    List<Map<String, String>> messages = new ArrayList<>();
    for (Message message : prompt.getInstructions()) {
      messages.add(Map.of("role", roleOf(message.getMessageType()), "content", message.getText()));
    }
    Map<String, Object> body =
        Map.of("model", baseModel, "messages", messages, "stream", false, "temperature", 0.2);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(chatUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8));
    if (authStyle.headerName() != null && apiKey != null && !apiKey.isBlank()) {
      request.header(authStyle.headerName(), authStyle.prefix() + apiKey);
    }

    HttpResponse<String> response;
    try {
      response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    throw new UnsupportedOperationException("非流式适配器不支持 stream()；流式仍由 UpstreamGateway 承担");
  }

  Integer promptTokens() {
    return promptTokens;
  }

  Integer completionTokens() {
    return completionTokens;
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
