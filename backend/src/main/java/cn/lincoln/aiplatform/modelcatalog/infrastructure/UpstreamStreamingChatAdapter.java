package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * ★★ 真流式实现：把上游（Ollama / OpenAI 兼容）逐 token 的正文**立刻**转给 {@link ChatStreamPort}。
 *
 * <h3>它替掉了什么</h3>
 *
 * <p>在此之前生产上只有 {@code ChatPortFallbackStreamAdapter}：它调 {@link ChatModelAdapter#complete}
 * 拿**整段**答案，再按 24 字切块、按 400ms 节奏回放。 那是「假流式」——
 *
 * <ul>
 *   <li><b>首字节 = 全量生成时间</b>（实测 60~90 秒）：用户在界面上看到的是 「转 90 秒，然后一次性出现全文」，而不是逐字冒出来；
 *   <li>回放节奏 400ms/块只是为了**让已生成的内容读起来像在打字**，总时长反而更长。
 * </ul>
 *
 * <p>用户的原话是这个设计的判据：「不应该是下游 ollama 出什么？直接 SSE 先发？」 —— 对本类来说完全成立：上游给什么就转发什么，不攒、不切、不睡。
 *
 * <h3>★ 为什么它能标 {@code @Primary} 而不会让旧实现失效</h3>
 *
 * <p>旧实现仍是 Spring 组件（只是不再是注入首选），保留它的理由是： 它证明了「上游只支持一次性返回」时链路仍然可用；而本类在流式不可用时 **显式报失败**（{@link
 * ChatStreamPort.End#FAILED}），不静默退回 —— "悄悄退回假流式"会让用户以为真流式坏了。
 *
 * <h3>★★ 终态与落库不在这里</h3>
 *
 * <p>本类只负责 <b>onChunk</b> 与"把终态告诉端口"；落库、`done` 帧、引用、停止信号 全部由 {@code ChatService}
 * 的既有收尾路径处理（那一处是"恰好一次 done"的唯一闸门）。
 */
@Primary
@Component
public class UpstreamStreamingChatAdapter implements ChatStreamPort {

  private static final Logger log = LoggerFactory.getLogger(UpstreamStreamingChatAdapter.class);

  private final ChatModelAdapter chat;

  public UpstreamStreamingChatAdapter(ChatModelAdapter chat) {
    this.chat = chat;
  }

  @Override
  public void stream(UUID modelId, String prompt, Duration timeout, Callback callback) {
    stream(modelId, prompt, false, timeout, callback);
  }

  @Override
  public void stream(
      UUID modelId, String prompt, boolean thinking, Duration timeout, Callback callback) {
    streamConversation(
        modelId,
        List.of(new ChatMessage(ChatMessage.Role.USER, prompt)),
        thinking,
        timeout,
        callback);
  }

  @Override
  public void streamConversation(
      UUID modelId, List<ChatMessage> messages, Duration timeout, Callback callback) {
    streamConversation(modelId, messages, false, timeout, callback);
  }

  @Override
  public void streamConversation(
      UUID modelId,
      List<ChatMessage> messages,
      boolean thinking,
      Duration timeout,
      Callback callback) {
    try {
      boolean streamed =
          chat.streamChat(
              modelId, messages, thinking, timeout, callback::onChunk, callback::onUsage);
      if (!streamed) {
        // ★ 上游 200 但一个 data 帧都没有：这是"它不支持 stream:true"或"回了空"。
        //   明确报失败而不是当成功收尾 —— 否则界面会显示"生成了 0 个字的答案"。
        log.warn("上游没有返回任何流式 data 帧（model={}）—— 该上游可能不支持 stream:true", modelId);
        callback.onEnd(End.FAILED, "上游没有返回任何流式内容（该模型可能不支持 stream:true）");
        return;
      }
      callback.onEnd(End.COMPLETE, null);
    } catch (RuntimeException failure) {
      // ★ 上游不可达 / 中断（用户停止时 callback.onChunk 会抛）——都按"这一路失败"交出，
      //   由 ChatService 按既有路径收成 FAILED / STOPPED。
      callback.onEnd(End.FAILED, failure.getMessage());
    }
  }
}
