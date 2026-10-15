package cn.lincoln.aiplatform.conversation.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 「等库里那条助手消息正文非空」—— ★★ 全仓<strong>唯一</strong>的那把等待（Issue {@code #125} 的收尾）。
 *
 * <h3>★★ 为什么等的必须是"正文非空"，不是"状态是 {@code GENERATING}"</h3>
 *
 * <p>助手消息是<strong>先落库、再开流</strong>的（{@code ChatTurnWriter#prepare}）：状态 {@code GENERATING}
 * 那一行在<strong>第一个 chunk 之前</strong>就已经存在（那正是 {@code A2}「刷新不重置」要的形状）。于是"看到 {@code
 * GENERATING}"与"模型已经吐过第一个字"是 <strong>两件事</strong>：
 *
 * <ul>
 *   <li>拿前者当后者 ⇒ 停止信号可能赶在 {@code Run#onChunk} 第一次跑之前置上 ⇒ 一个 chunk 都没进装配器 ⇒ 终态确实还是 {@code
 *       STOPPED}，但正文是<strong>空串</strong> ⇒ {@code A5}「停止<strong>保留已生成内容</strong>」那条断言红，
 *       而红的样子像"停止丢了内容"，真实原因是<strong>等待条件选错了观测点</strong>；
 *   <li>★ 它是 ~50% 偶发而不是恒红（信号先到还是第一个 chunk 先到取决于两条线程的调度，{@code #125} 实测 4 次运行绿/红/红/绿）⇒
 *       <strong>单次绿不足以证明它好了</strong>。
 * </ul>
 *
 * <h3>★★ 为什么只有这一处实现（{@code #125} 收尾的全部意义）</h3>
 *
 * <p>本类出现之前，{@code ChatStreamHttpTest} 与 {@code ConversationStopEndpointTest}
 * 各自写了一把<strong>等价但独立</strong>的等待。 两把同口径的等待并存的下场已经发生过一次：{@code #119} 的新用例把 {@code #125}
 * 的老洞<strong>原样抄了一遍</strong>。 ⇒ 所以本类不是"再抽一个工具类"，而是把那条纪律变成<strong>结构事实</strong>：判据只有一处可改， 而"又抄了一把"由
 * {@code GeneratedTextWaitSingleSourceTest} 当场判红。
 *
 * <p>★ <strong>判据那一段逐字没改</strong>：仍然是"{@code m.role = 'ASSISTANT'} 且正文非空"（原两处的逐字语义）。
 * 唯一收紧的是<strong>定位方式</strong> —— 取原 {@code ConversationStopEndpointTest} 那把的<strong>按会话</strong>定位，
 * 而不是原 {@code ChatStreamHttpTest} 那把的"按用户取最新一条"：会话是更窄、也更确定的观测范围 （于是那一条用例改成自己建一个会话，把会话 id 交给这里）。
 *
 * <p>★ 它只做这一件小事、只被测试用（<strong>不是</strong>生产代码）：超时即 {@link AssertionError} —— 生成没起来 / flush
 * 没发生都会被读成一次红的失败，而不是被继续等下去。
 */
public final class AssistantGeneratedTextWait {

  /** 轮询粒度（毫秒）：只影响"多快被发现"，不影响"等的是什么"。 */
  private static final long POLL_INTERVAL_MS = 20L;

  private AssistantGeneratedTextWait() {}

  /**
   * 等某个会话里出现一条<strong>正文非空</strong>的助手消息，返回它的 id。
   *
   * <p>★ 为什么从库里等、而不是"从响应的流里读 {@code meta} 拿 messageId"（本批实测踩到过）： 本机实测客户端在整条流结束之前可能读不到任何一帧，
   * 而助手消息<strong>先于第一个字节就落库</strong> ⇒ 库里那一行是更早、也更确定的观测点。
   *
   * <p>★ 判据逐字：助手角色那一行、且<strong>正文非空</strong>（{@code content} 与空串比较 —— 那句 SQL 就在下面，
   * 它也是全仓唯一的一处），取该会话里最新的一条。 正文只增不减（{@code Message#appendText} 只追加、停止不清空），所以它一旦非空，停止之后也仍然非空。
   *
   * @param jdbc 真栈测试自己的 {@link JdbcTemplate}
   * @param conversationId 会话 id —— ★ 按<strong>会话</strong>定位，不按用户定位（同一个用户可以有多个会话）
   * @param timeoutMs 上限（超时即断言失败 —— 不藏缺陷）
   * @return 那条已经吐过字的助手消息 id
   */
  public static UUID await(JdbcTemplate jdbc, UUID conversationId, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
    while (System.nanoTime() < deadline) {
      List<String> found =
          jdbc.queryForList(
              "select m.id::text from cv_message m where m.conversation_id = ?::uuid"
                  + " and m.role = 'ASSISTANT' and m.content <> '' order by m.created_at desc"
                  + " limit 1",
              String.class,
              conversationId);
      if (!found.isEmpty()) {
        return UUID.fromString(found.getFirst());
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    throw new AssertionError(
        "在 "
            + timeoutMs
            + "ms 内没有等到正文非空的助手消息 —— "
            + "★ 等待条件必须是「库里正文非空」（= 真的吐过字），不是「状态 GENERATING」："
            + "助手行在第一个 chunk 之前就已经是 GENERATING 了（#125）");
  }
}
