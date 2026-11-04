package cn.lincoln.aiplatform.conversation.support;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * 查询理解（{@code #297}）把非流式的 {@link ChatPort} 引进了对话链路 —— 假模型口径随之扩展到它。
 *
 * <h3>★ 为什么必须桩 {@code ChatPort}（而不只是 {@code ChatStreamPort}）</h3>
 *
 * <p>真栈测试的口径是<strong>「假模型不打真上游」</strong>（见 {@code FakeChatStreamPorts} 的类注释）。本批之前 {@code ChatPort}
 * 在对话链路<strong>没有消费点</strong>，所以只桩 {@code ChatStreamPort} 就够了； {@code #297} 的查询理解每轮会调 {@code
 * ChatPort#complete}，不桩它就会真发 HTTP 到夹具模型的 {@code api_base}（{@code localhost:11434}）—— 实测那里没人正确应答， 白等
 * {@code queryUnderstandingBudgetMs}（5053ms）后才 D4 退回原句，<strong>把生成阶段的 idleTimeout 预算吃掉</strong>，
 * 于是正常路径变成「meta,refs,error(生成超时),done」（回归实录：ChatStreamHttpTest 4 红）。
 *
 * <h3>★ 两个方法的桩语义（缺一不可）</h3>
 *
 * <ul>
 *   <li>{@code pickChatModel} 给一个<strong>固定的假 id</strong>，不给 empty —— {@code
 *       ChatTurnWriter#pickModelId} 的 「另挑一个可用的」降级输入、以及一切「拿不到模型 ⇒ 400 当前没有可用的对话模型」的守卫都吃它；
 *   <li>{@code complete} 抛 {@link BusinessException}（= 上游不可用）—— 查询理解按契约把它吃掉并 D4 退回原始问题单路，
 *       检索串与不开这一层<strong>逐字相同</strong>（这些用例不测查询理解本身，它在 {@code
 *       cn.lincoln.aiplatform.retrieval.*Test} 里有自己的读数）。
 * </ul>
 */
public final class FakeChatPorts {

  private FakeChatPorts() {}

  /** 桩给的固定假模型 id（只是个标识；生成走 {@code FakeChatStreamPorts}，不看它）。 */
  public static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-00000000cafe");

  /** 把 {@code @MockitoBean ChatPort} 接成假模型语义（每个 {@code @BeforeEach} 调一次）。 */
  public static void wire(ChatPort chatPort) {
    Mockito.when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    Mockito.when(
            chatPort.complete(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "假模型不打真上游（测试桩）"));
  }
}
