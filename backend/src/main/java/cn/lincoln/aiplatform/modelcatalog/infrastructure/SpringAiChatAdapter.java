package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 非流式对话的 Spring AI 实现（{@code 03 §475} 的"Spring AI 实现、原生适配器仅作兜底"）。
 *
 * <h3>★ 它到底换掉了什么</h3>
 *
 * <p>不是换传输（传输仍是同一条 OpenAI 兼容的 HTTP），而是<b>让调用经过 {@code ChatClient} 与 advisor 链</b>： 安全闸门（{@link
 * SafeGuardChatAdvisor}）、可观测探针（{@link AdvisorChainTrace}）、 以后的结构化输出校验都挂在这条链上。此前它们一个都挂不上 —— 生产路径直接调
 * {@code UpstreamGateway}， 于是 {@code 13 §12.1} 的拍板只停留在设计册里。
 *
 * <h3>★ 覆盖范围：非流式与流式<b>两半</b>都在这里</h3>
 *
 * <p>非流式（一次性补全 / 结构化补全）与流式（{@link #streamConversation}）都经过 {@code ChatClient}，因此两半都在 advisor
 * 链上：安全闸门对"整段回答"和"逐块回答"是同一张网。★ 流式那一半曾经是空的（{@code NativeSpringAiChatModel.stream()} 直接抛 {@code
 * UnsupportedOperationException}，生产流式仍走 {@code UpstreamGateway}），于是 {@code 13 §12.1} 的拍板在流式上不生效 ——
 * 这正是 {@code #62} 的后半段。
 *
 * <h3>★ 兜底</h3>
 *
 * <p>{@code app.chat.advisors.enabled=false} 时本类直接返回"不启用"，由 {@code ChatModelAdapter} 退回
 * 网关。这条开关是<b>显式</b>的，不是"Spring AI 调不通就自动回落"—— 自动回落会让"挂上了"与"没挂上"在日志里 长得一样。
 */
@Component
public class SpringAiChatAdapter {

  private final List<Advisor> advisors;
  private final boolean enabled;
  private final String defaultBaseUrl;
  private final String defaultApiKey;

  @Autowired
  public SpringAiChatAdapter(
      List<Advisor> advisors,
      @Value("${app.chat.advisors.enabled:true}") boolean enabled,
      @Value("${spring.ai.openai.base-url:http://localhost:11434/v1}") String defaultBaseUrl,
      @Value("${spring.ai.openai.api-key:ollama-local}") String defaultApiKey) {
    this.advisors = advisors.stream().sorted(Comparator.comparingInt(Advisor::getOrder)).toList();
    this.enabled = enabled;
    this.defaultBaseUrl = defaultBaseUrl;
    this.defaultApiKey = defaultApiKey;
  }

  /** 测试用重载：默认 key 取与网关相同的默认值（{@code ollama-local}），避免每条用例都要想一遍它。 */
  public SpringAiChatAdapter(List<Advisor> advisors, boolean enabled, String defaultBaseUrl) {
    this(advisors, enabled, defaultBaseUrl, "ollama-local");
  }

  /** 供测试/显式兜底路径使用：不启用的适配器。 */
  public static SpringAiChatAdapter disabled() {
    return new SpringAiChatAdapter(List.of(), false, "http://localhost");
  }

  public boolean enabled() {
    return enabled;
  }

  /** 一次性补全（单条 prompt）。 */
  public String complete(
      ResolvedUpstream target, String baseModel, String prompt, Duration timeout) {
    return callWith(modelOf(target, baseModel, timeout), List.of(new UserMessage(prompt)));
  }

  /** 带角色边界的多轮补全。 */
  public String completeConversation(
      ResolvedUpstream target, String baseModel, List<ChatMessage> messages, Duration timeout) {
    return callWith(modelOf(target, baseModel, timeout), springMessages(messages));
  }

  /** 带正文与用量的补全：上游没给 usage 时两个分量都是 null（不伪造 0）。 */
  public ChatPort.Completion completeWithUsage(
      ResolvedUpstream target, String baseModel, List<ChatMessage> messages, Duration timeout) {
    NativeSpringAiChatModel model = modelOf(target, baseModel, timeout);
    String text = callWith(model, springMessages(messages));
    return new ChatPort.Completion(text, model.promptTokens(), model.completionTokens());
  }

  /**
   * ★★ 真流式（{@code #62} 的后半段）：逐块交给调用方，而<b>那条链照样在</b>。
   *
   * <p>为什么不能像以前那样直接调网关：流式路径上挂不上 advisor 链，于是"敏感词不进上游"这条拍板只管非流式 —— 同一个应用里
   * 两种提问方式的安全边界不一样，是最不该有的那种不一致。
   *
   * <p>★ 返回"是否至少收到过一块正文"：200 但零正文的情况（上游不支持 {@code stream:true}）必须报失败， 否则上层会把它收成一条"成功但零字"的助手消息。
   *
   * <p>★ 只把 {@link UpstreamGateway.UpstreamUnreachableException} 包成业务失败：调用方在 {@code onDelta} 里抛的异常
   * （典型是用户点停止时的 {@code StopUpstream}）必须<strong>原样穿透</strong>，它代表"正常终止"，不是"上游坏了"。
   *
   * <p>★★ 订阅方式是有讲究的（{@code #244}）：必须用无界请求的 {@code blockLast()}，<b>不能</b>用 {@code toIterable()} ——
   * 后者按批预取，会把若干帧先读完再逐帧交出来，真栈上就表现成"答案在生成结束时一起到"。 这条曾经真实发生过：{@code ChatStreamHttpTest}
   * 与端口层都逐帧，只有这里在攒。判据见 {@code SpringAiStreamDeltaTimingTest}（进程内 300ms 一帧的小上游，首末帧跨度必须 &gt; 600ms）。
   *
   * @param reasoningEffort {@code null} = 这个模型不该带 {@code reasoning_effort}（{@code #185}）
   * @param onUsage {@code null} = 调用方不关心用量；上游没给 usage 时一次都不回调（不伪造 0）
   */
  public boolean streamConversation(
      ResolvedUpstream target,
      String baseModel,
      List<ChatMessage> messages,
      String reasoningEffort,
      Duration timeout,
      Consumer<String> onDelta,
      BiConsumer<Integer, Integer> onUsage) {
    NativeSpringAiChatModel model = modelOf(target, baseModel, timeout, reasoningEffort);
    int[] chunkCount = {0};
    try {
      // ★ 不要用 toIterable() 迭代（#244）：它按批预取 —— 先把一批帧读完，再逐帧交出来，
      // 真栈上就表现成"整段答案在生成结束的一瞬间一起到"。blockLast() 用无界请求订阅，
      // 每一帧在 doOnNext 里立刻交给调用方。
      clientOf(model).prompt().messages(springMessages(messages)).stream()
          .chatResponse()
          .doOnNext(
              response -> {
                String text = textOf(response);
                if (text == null || text.isEmpty()) {
                  return;
                }
                chunkCount[0]++;
                onDelta.accept(text);
              })
          .blockLast();
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调用对话模型失败：" + e.getMessage(),
          "请确认对话模型服务可用（地址与密钥）；检索会退回原始问题继续，不会因此失败");
    } finally {
      // 消费方抛异常（停止 / 断开）也要把上游连接放掉，否则一次"停止"会留下一条读到超时的连接
      model.closeStream();
    }
    if (onUsage != null && model.promptTokens() != null && model.completionTokens() != null) {
      onUsage.accept(model.promptTokens(), model.completionTokens());
    }
    return chunkCount[0] > 0;
  }

  /** 从一帧里取正文；usage 帧产不出元素（那一层已经滤掉），这里只防空。 */
  private static String textOf(ChatResponse response) {
    if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
      return null;
    }
    Generation generation = response.getResults().get(0);
    AssistantMessage output = generation == null ? null : generation.getOutput();
    return output == null ? null : output.getText();
  }

  /** 结构化输出：格式说明与解析交给官方 {@link BeanOutputConverter}（经 {@code ChatClient.entity}）。 */
  public <T> T completeAs(
      ResolvedUpstream target, String baseModel, String prompt, Class<T> type, Duration timeout) {
    NativeSpringAiChatModel model = modelOf(target, baseModel, timeout);
    try {
      T value = clientOf(model).prompt().user(prompt).call().entity(type);
      if (value == null) {
        throw new BusinessException(
            ErrorCode.UPSTREAM_UNAVAILABLE,
            "模型输出不符合 " + type.getSimpleName() + " 的结构：转换器产出了空对象",
            "上游模型没有按 schema 返回；本次调用按失败处理，调用方会用已有结果继续");
      }
      return value;
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "模型输出不符合 " + type.getSimpleName() + " 的结构：" + e.getMessage(),
          "上游模型没有按 schema 返回；本次调用按失败处理，调用方会用已有结果继续");
    }
  }

  private String callWith(NativeSpringAiChatModel model, List<Message> messages) {
    try {
      String content = clientOf(model).prompt().messages(messages).call().content();
      if (content == null) {
        throw new UpstreamGateway.UpstreamUnreachableException("上游没有返回正文");
      }
      return content;
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调用对话模型失败：" + e.getMessage(),
          "请确认对话模型服务可用（地址与密钥）；检索会退回原始问题继续，不会因此失败");
    }
  }

  private ChatClient clientOf(NativeSpringAiChatModel model) {
    return ChatClient.builder(model).defaultAdvisors(advisors.toArray(new Advisor[0])).build();
  }

  private NativeSpringAiChatModel modelOf(
      ResolvedUpstream target, String baseModel, Duration timeout) {
    return modelOf(target, baseModel, timeout, null);
  }

  private NativeSpringAiChatModel modelOf(
      ResolvedUpstream target, String baseModel, Duration timeout, String reasoningEffort) {
    return new NativeSpringAiChatModel(
        target.apiBase(),
        defaultBaseUrl,
        keyOf(target),
        baseModel,
        target.authStyle(),
        timeout,
        reasoningEffort);
  }

  /**
   * 认证 key：服务商没配 key 时，Bearer 上游回落到配置里的默认 key。
   *
   * <p>★ 与 {@code UpstreamGateway.effectiveKey} 逐字同一条规则（含"只有 Bearer 才回落"这一半）：流式从网关切到 {@code
   * ChatClient} 之后，这条规则若不跟过来，症状就是"非流式正常、流式 401"—— 而它看起来像 key 填错了。
   */
  private String keyOf(ResolvedUpstream target) {
    String key = target.apiKey();
    if (key != null && !key.isBlank()) {
      return key;
    }
    AuthStyle style = target.authStyle() == null ? AuthStyle.BEARER : target.authStyle();
    // ★ X-API-Key / NONE 上回落会把一个"没配 key"的服务商悄悄拿默认 key 去试，症状是 401
    return style == AuthStyle.BEARER ? defaultApiKey : null;
  }

  private static List<Message> springMessages(List<ChatMessage> messages) {
    List<Message> result = new ArrayList<>(messages.size());
    for (ChatMessage message : messages) {
      result.add(
          switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
          });
    }
    return result;
  }
}
