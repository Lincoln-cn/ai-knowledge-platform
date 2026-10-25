package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
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
 * <h3>★ 与流式路径的边界（不要读成"全部切完了"）</h3>
 *
 * <p>只覆盖<strong>非流式</strong>（一次性补全 / 结构化补全）。流式仍走 {@code UpstreamGateway}：那里由 {@code
 * ChatStreamHttpTest} 一类真栈用例钉着"refs 先于 delta、done 恰好一次、超时可中断"，一次性换掉 传输层的做法不是本 Issue 要求的，也不该在一个提交里做。
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

  public SpringAiChatAdapter(
      List<Advisor> advisors,
      @Value("${app.chat.advisors.enabled:true}") boolean enabled,
      @Value("${spring.ai.openai.base-url:http://localhost:11434/v1}") String defaultBaseUrl) {
    this.advisors = advisors.stream().sorted(Comparator.comparingInt(Advisor::getOrder)).toList();
    this.enabled = enabled;
    this.defaultBaseUrl = defaultBaseUrl;
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
    return new NativeSpringAiChatModel(
        target.apiBase(), defaultBaseUrl, target.apiKey(), baseModel, target.authStyle(), timeout);
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
