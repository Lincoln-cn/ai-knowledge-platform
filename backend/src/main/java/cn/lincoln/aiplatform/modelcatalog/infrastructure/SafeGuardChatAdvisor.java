package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.guard.GuardService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 官方 {@link SafeGuardAdvisor} 的扩展（{@code 13 §12.1} 拍板的口径：扩展，不自研）。
 *
 * <p>★ 为什么是"扩展"而不是另写一套拦截：官方 advisor 已经站在调用链上，另写一套会出现两道语义不同的网 —— 一道拦输入、一道拦输出，最后没人说得清哪一道在生效。
 *
 * <h3>★ 为什么必须重写 {@code adviseCall} / {@code adviseStream}</h3>
 *
 * <p>官方的 {@code SafeGuardAdvisor} 把词表、话术、order 三个字段全设为 {@code private final}， 判定逻辑写在 {@code
 * adviseCall} 里、{@code createFailureResponse} 还是 {@code private} ——
 * 没有"换一个词表"的钩子。它只做<strong>一串词</strong>的 {@code contains} 与一句统一话术，
 * 拿不到分类、也做不了"敏感问题集"。所以这里保留继承关系（链上仍然只有一道网）， 但判定与拒答交给 {@link GuardService}：<b>分类 → 归一化 → 命中 →
 * 分类话术</b>。
 *
 * <p>★ 词表来自库里（{@link GuardService} 背后的 {@code pf_guard_rule}），不来自 yml： 把词表写死在配置里会让"哪些话不能问"变成改配置 +
 * 重启才能变的事，而它是运营口径。 {@code CHAT_GUARD_SENSITIVE_WORDS} 仍有效，但只在规则表为空时播种一次（{@code ROADMAP §11.53.5}）。
 */
@Component
public class SafeGuardChatAdvisor extends SafeGuardAdvisor {

  /** 拒绝话术的兜底（与 {@code pf_guard_settings.default_refusal_text} 默认值一字不差）。 */
  public static final String REFUSAL = "抱歉，这个问题不在可回答范围内。";

  private final GuardService guard;

  /**
   * @param guard 判定与话术的真源。父类的词表传空列表：判定已被本类完全接管， 留着它只会让"父类还会不会再拦一次"变成一个要读源码才能回答的问题
   */
  public SafeGuardChatAdvisor(GuardService guard) {
    super(List.of(), REFUSAL, 100);
    this.guard = guard;
  }

  @Override
  public ChatClientResponse adviseCall(
      ChatClientRequest request, CallAdvisorChain callAdvisorChain) {
    String refusal = guard.inspect(userText(request));
    if (refusal != null) {
      return refuse(refusal);
    }
    return callAdvisorChain.nextCall(request);
  }

  /**
   * ★ 判定写在 {@link Flux#defer} 里：{@code ChatClient} 装配时就会调到这里， 写在方法体外等于"装配一次就判一次"（同 {@code
   * AdvisorChainTrace} 的理由）。
   */
  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain streamAdvisorChain) {
    return Flux.defer(
        () -> {
          String refusal = guard.inspect(userText(request));
          if (refusal != null) {
            return Flux.just(refuse(refusal));
          }
          return streamAdvisorChain.nextStream(request);
        });
  }

  @Override
  public String getName() {
    return "safeguard-chat";
  }

  /**
   * 只取 <b>USER</b> 角色的正文。
   *
   * <p>★ 不取 {@code prompt().getContents()}：那会把 system prompt 一起算进来，而检索拼进去的 上下文里可能正当地出现"暴力""政治"之类的字眼
   * —— 那会把一次正常问答拦成拒答，且现象极难解释 （用户问的是 A，被拦的理由在文档 B 里）。
   */
  private static String userText(ChatClientRequest request) {
    StringBuilder text = new StringBuilder();
    for (Message message : request.prompt().getInstructions()) {
      if (message.getMessageType() == MessageType.USER) {
        String part = message.getText();
        if (part != null && !part.isEmpty()) {
          text.append(part).append('\n');
        }
      }
    }
    return text.toString();
  }

  private static ChatClientResponse refuse(String refusal) {
    return new ChatClientResponse(
        new ChatResponse(List.of(new Generation(new AssistantMessage(refusal)))), Map.of());
  }
}
