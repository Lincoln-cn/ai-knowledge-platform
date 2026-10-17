package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatStreamPort} 的<strong>兜底</strong>实现：用 {@link ChatPort#complete} 一次性拿到全文，再按块送出。
 *
 * <h3>★★ 它存在的唯一理由：端口已经接进链路，而"真流式实现"属另一批</h3>
 *
 * <p>本批（第 17 周 · 0.4.5）的任务是把 <strong>SSE 真链路</strong>接起来，判据全部落在 "事件序列 / 恰好一次 done / refs 先于 delta /
 * 落库终态"上 —— 它们与"字是分批到还是一次到"无关， 所以测试注入的是<strong>逐块吐字的假实现</strong>（那才谈得上"生成中途"）。
 *
 * <p>而<strong>生产</strong>上不能没有兜底：{@code ChatService} 依赖 {@link ChatStreamPort}， 没有实现 bean
 * 时应用起不来（那会让所有 {@code @SpringBootTest} 一起红，而它们与本批无关）。 所以这里给一个"形状对、只是不逐字"的实现：
 *
 * <ul>
 *   <li><strong>一次上游调用</strong>（{@code ChatPort.complete}），拿到全文；
 *   <li>按固定大小切块、逐块交给回调 —— 于是 {@code meta → refs → delta*} 的<strong>顺序</strong>、 落库 flush
 *       与终态回写全部照常工作；
 *   <li>★ <strong>代价写清楚</strong>：首字延迟等于整段生成时间（B4 ≤10s 在有真流式之前没有意义）， 且"逐字"是切出来的、不是模型吐的。真实现（{@code
 *       stream: true}）随"接真模型"那一批落。
 * </ul>
 *
 * <p>★★ <strong>为什么不用 {@code @ConditionalOnMissingBean}（本批实测踩到过）</strong>：写在
 * <strong>组件类</strong>上时它的判定时机不对 —— 实测这个 bean <strong>根本没被注册</strong>， 于是<strong>所有</strong>
 * {@code @SpringBootTest} 一起红在 {@code No qualifying bean of type 'ChatStreamPort'
 * available}（而报错看起来像"忘了写实现"）。 官方文档也写明：那个注解只在 {@code @Bean} 方法（自动配置类）上可靠。
 *
 * <p>⇒ 直接登记为普通组件：生产上它是<strong>唯一</strong>的实现（真流式实现随那一批再加）； 测试里用 {@code @Primary} 的假实现覆盖它 ——
 * 于是"测试用假的、生产用真的"仍然由容器装配决定。
 */
@Component
public class ChatPortFallbackStreamAdapter implements ChatStreamPort {

  private static final Logger log = LoggerFactory.getLogger(ChatPortFallbackStreamAdapter.class);

  /** 切块大小（字符）—— 与前端 mock 的吐字节拍同量级，只为了让"逐块"这件事真的发生。 */
  private static final int CHUNK_CHARS = 24;

  private final ChatPort chat;

  public ChatPortFallbackStreamAdapter(ChatPort chat) {
    this.chat = chat;
  }

  @Override
  public void stream(UUID modelId, String prompt, Duration timeout, Callback callback) {
    String text;
    try {
      text = chat.complete(modelId, prompt, timeout);
    } catch (RuntimeException failure) {
      // ★ 上游失败是"这一路调用失败"，不是"整个请求 500"：交回回调，由链路收成 FAILED
      callback.onEnd(End.FAILED, failure.getMessage());
      return;
    }
    emitInChunks(text, callback);
  }

  /**
   * 覆盖端口的默认实现（`#168`）：把历史按**消息数组**发给上游，而不是拼成一段文本。
   *
   * <p>★ 切块与收尾走同一个 {@link #emitInChunks} —— 两份实现若各写一遍， "单轮走一条路、多轮走另一条路"迟早会各自长歪。
   */
  @Override
  public void streamConversation(
      UUID modelId,
      java.util.List<cn.lincoln.aiplatform.platform.model.ChatMessage> messages,
      Duration timeout,
      Callback callback) {
    String text;
    try {
      text = chat.completeConversation(modelId, messages, timeout);
    } catch (RuntimeException failure) {
      callback.onEnd(End.FAILED, failure.getMessage());
      return;
    }
    emitInChunks(text, callback);
  }

  /**
   * 把上游一次性返回的整段文本按 {@link #CHUNK_CHARS} 切块送出。
   *
   * @param text 上游返回（可能为 {@code null}/空 —— 那是"生成了 0 个字的答案"）
   * @param callback 回调；★ 它可能抛（用户停止 / 闲置超时 / 连接已断）⇒ 让异常传出去并停止继续吐
   */
  private void emitInChunks(String text, Callback callback) {
    if (text == null || text.isEmpty()) {
      // 空回答：一个 delta 都不发（装配器拒绝空增量），直接以 COMPLETE 收 ——
      // 终态仍是 GENERATED，这正是"生成了 0 个字的答案"
      callback.onEnd(End.COMPLETE, null);
      return;
    }
    log.debug("兜底流式实现：上游一次性返回 {} 字，按 {} 字切块送出（真流式实现属'接真模型'那一批）", text.length(), CHUNK_CHARS);
    for (int at = 0; at < text.length(); at += CHUNK_CHARS) {
      callback.onChunk(text.substring(at, Math.min(text.length(), at + CHUNK_CHARS)));
    }
    callback.onEnd(End.COMPLETE, null);
  }
}
