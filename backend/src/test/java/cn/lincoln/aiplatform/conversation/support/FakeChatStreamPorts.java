package cn.lincoln.aiplatform.conversation.support;

import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import java.time.Duration;
import java.util.UUID;

/**
 * 测试用的<strong>假模型</strong>（第 17 周 · 0.4.5）。
 *
 * <h3>★★ 为什么必须有一个假实现（而不用 {@code tools/openai_stub.py}）</h3>
 *
 * <p>本批的判据全部落在"<strong>链路的形状</strong>"上：事件序列、恰好一次 {@code done}、 {@code refs} 先于首个 {@code
 * delta}、四条终止路径各自的落库终态。这些东西需要的是 <strong>能被脚本化的吐字节拍</strong>（第 1 块等多久、每块之间等多久），
 * 而一个真模型（或一个桩服务）给不了这种确定性 —— 于是"测试偶发地红"会被当成"链路有问题"， 而真实原因是模型这次答得快。★
 * 项目纪律：<strong>假模型就用测试里的假实现，不起长驻服务</strong>。
 *
 * <h3>★★ 三条纪律，每条都对应一个真实现也会有的义务</h3>
 *
 * <ol>
 *   <li><strong>回调抛异常必须让 {@code stream} 抛出并停止吐字</strong> —— 那是"用户停止 / 超时 /
 *       连接断开"唯一的取消机制。吞掉它等于"用户停了、模型还在烧 token"，且不报错；
 *   <li><strong>{@code onEnd} 恰好调一次</strong> —— 调用方那台"恰好一次 done"的闸门是照它写的；
 *   <li><strong>不在"已经决定收尾"之后继续回调</strong>（本类靠"回调抛了就退出"实现）。
 * </ol>
 */
public final class FakeChatStreamPorts {

  /** 每块之间的默认等待（毫秒）—— 足够让"生成中途落库"这条被观测到。 */
  public static final long DEFAULT_CHUNK_DELAY_MS = 40L;

  private FakeChatStreamPorts() {}

  /**
   * 一段确定性的回答（★ 不含换行与引号，方便测试里直接断言原始帧文本）。
   *
   * <p>长度 &gt; {@code 03 §1.4} 的 200 字阈值的一小半，于是"按字阈值 flush"在测试里够不着、 "按 500ms 间隔 flush"够得着 ——
   * 两种节奏各自有自己的用例。
   */
  public static final String ANSWER =
      "差旅住宿标准为每人每天500元，超出部分由个人承担。报销时需附酒店发票与行程单，" + "并在系统中填写出差事由与起止日期。跨月出差的费用应在次月十日前提交，逾期需部门负责人审批。";

  /** 一段很长的回答（约 600 字，来自 {@link #ANSWER} 重复）—— 见 {@link #longAnswerStreaming}。 */
  public static final String LONG_ANSWER = repeat(ANSWER, 7);

  private static String repeat(String unit, int times) {
    StringBuilder text = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      text.append(unit);
    }
    return text.toString();
  }

  /**
   * 逐块吐字、中间有停顿的假模型。
   *
   * @param firstChunkDelayMs 第一块之前的停顿（毫秒）—— ★ 心跳用例靠它制造"无业务帧的间隙"
   * @param chunkDelayMs 块与块之间的停顿（毫秒）
   * @param chunkChars 每块的字符数
   * @return 假实现
   */
  public static ChatStreamPort streaming(
      long firstChunkDelayMs, long chunkDelayMs, int chunkChars) {
    return new Scripted(firstChunkDelayMs, chunkDelayMs, chunkChars, ANSWER);
  }

  /**
   * 逐块吐<strong>很长</strong>的答案、中间有停顿的假模型 —— 给"生成中途"那类读数用。
   *
   * <p>★ 为什么需要它：短答案在 8 个块之内就写完了，于是"读 N 条事件然后查库"这种采样
   * 很可能读到的是<strong>收尾之后</strong>的状态（本机实测就那样发生了）。长答案把生成过程拉到 十几秒的量级，"中途"这个时刻才是稳定可采样的。
   *
   * @param chunkDelayMs 块与块之间的停顿（毫秒）
   * @param chunkChars 每块的字符数
   * @return 假实现
   */
  public static ChatStreamPort longAnswerStreaming(long chunkDelayMs, int chunkChars) {
    return new Scripted(0, chunkDelayMs, chunkChars, LONG_ANSWER);
  }

  /** 逐块吐字、默认节奏的假模型。 */
  public static ChatStreamPort streaming() {
    return streaming(0, DEFAULT_CHUNK_DELAY_MS, 12);
  }

  /** 一个块都不吐、直接以失败收尾（模拟上游报错或上游超时）。 */
  public static ChatStreamPort failing(String detail) {
    return (modelId, prompt, timeout, callback) ->
        callback.onEnd(ChatStreamPort.End.FAILED, detail);
  }

  /** 一次吐完、没有停顿（"全文一次性到"的极端情况）。 */
  public static ChatStreamPort oneShot() {
    return (modelId, prompt, timeout, callback) -> {
      callback.onChunk(ANSWER);
      callback.onEnd(ChatStreamPort.End.COMPLETE, null);
    };
  }

  /** 脚本化实现：先等 {@code firstChunkDelayMs}，再按 {@code chunkChars} 逐块吐，块间等 {@code chunkDelayMs}。 */
  private record Scripted(long firstChunkDelayMs, long chunkDelayMs, int chunkChars, String answer)
      implements ChatStreamPort {

    @Override
    public void stream(UUID modelId, String prompt, Duration timeout, Callback callback) {
      sleep(firstChunkDelayMs);
      for (int at = 0; at < answer.length(); at += chunkChars) {
        // ★ 回调抛了就让它传出去，并立刻停止（见类注释第 1 条）
        callback.onChunk(answer.substring(at, Math.min(answer.length(), at + chunkChars)));
        sleep(chunkDelayMs);
      }
      callback.onEnd(End.COMPLETE, null);
    }
  }

  /** 不吞中断：被打断时恢复中断位并抛出（假模型也要遵守"取消要能生效"）。 */
  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("假模型在吐字间隙被打断", interrupted);
    }
  }
}
