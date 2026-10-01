package cn.lincoln.aiplatform.knowledge.domain;

import java.util.Map;
import java.util.Set;

/**
 * 文档加工状态（契约 {@code DocumentStatus}，{@code frontend/src/api/ingestion.ts}）。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code 14} 册 §2.1）： 标识符 = 线上值 =
 * 库存值三者同名。展示文案（「待解析 / 分段中 / …」）只出现在前端 label 映射里。
 *
 * <p>★ 七态对应真实加工链（{@code 02 §2.2} 状态机）： 解析/分段 → 向量化 → 成功，两段各有失败态。 <strong>初值是 {@link
 * #PENDING_PARSE}</strong>（未解析那一态）—— 不是「成功」， 否则界面会把还没加工的文档显示成可检索。
 *
 * <p>★ <strong>{@link #ALLOWED_INCOMING} 是「哪些状态能转到这个状态」的唯一真源</strong>（{@code 03 §3.2}
 * 的流水线）：所有状态变更都必须过 {@link #requireLegal}。理由与模型状态机（{@code ModelStatus}）同一条 ——
 * 「加载失败直接标成功」「跳过分段直接成功」这类<strong>跳步</strong>不会报错，只会让界面把一个其实没加工的文档显示成可用； 靠调用方自觉是不够的。
 *
 * <p>★ 一处自觉的取舍：{@link #SEGMENTING}（分段中）在当前实现里<strong>不可被外部观测</strong>—— 解析与分块是同步的，
 * 两个转移发生在同一事务内，外部只看得到最终态。它仍然保留在矩阵里、也确实被走过（{@code Document#recordParseSuccess}），
 * 因为等解析/分块改成异步流水线时（{@code 03 §3.2} 的形态）它就是在途态，届时不必改状态机。 想确认「这一步真的走过了」的，看 {@code
 * DocumentStateMachineTest} 的轨迹断言。
 */
public enum DocumentStatus {
  /** 待解析（初值）。★ 没有任何状态能转回它 —— 它是真正的起点。 */
  PENDING_PARSE,
  /** 分段中。 */
  SEGMENTING,
  /** 待向量化。 */
  PENDING_EMBEDDING,
  /** 向量化中。 */
  EMBEDDING,
  /** 成功。 */
  SUCCEEDED,
  /** 分段失败。 */
  SEGMENT_FAILED,
  /** 向量化失败。 */
  EMBEDDING_FAILED;

  /**
   * 合法转移表：<strong>目标状态 → 允许的来源状态集合</strong>。
   *
   * <p>为什么按「目标 → 来源」而不是「来源 → 目标」：每个行为（解析成功、开始向量化、标记完成…）都要问 「我现在这个状态允许做这件事吗」，目标态是行为自己知道的、来源态是运行时读出来的
   * —— 按目标查一次命中。
   *
   * <p>★ 逐条都能对应到一个真实流程或一条纪律：
   *
   * <ul>
   *   <li>{@code PENDING_PARSE} 的来源是空集 —— 起点不许被转回（否则「成功后回退到未解析」会擦掉加工结果）
   *   <li>{@code SEGMENT_FAILED} 可由 {@code PENDING_PARSE} / {@code SEGMENTING} / {@code
   *       PENDING_EMBEDDING} 进入：解析或分块任一步失败；{@code PENDING_EMBEDDING} 也允许，因为重新分块失败是它的真实形态
   *   <li>重试：{@code SEGMENT_FAILED → SEGMENTING}（重新分段）、{@code EMBEDDING_FAILED →
   *       PENDING_EMBEDDING}（重新排队向量化）
   *   <li>{@code SUCCEEDED → PENDING_EMBEDDING}：分段被编辑或删除后向量就旧了（{@code SEG-I7}）
   *   <li>★ {@code PENDING_EMBEDDING} <strong>可以是自己的来源</strong>：解析成功后紧接着回填分段结果 （{@code
   *       DocumentService} 的「建档即解析 + 入库即分块」就是这条路径），状态不变但那几个字段要更新。
   *       分块本身是<strong>幂等</strong>的（先删后写，{@code 03 §3.3}）⇒ 回填必须能重复执行， 不能因为「已经待向量化」而失败
   * </ul>
   *
   * <p>★ 被刻意排除的转移：{@code PENDING_PARSE → SUCCEEDED}（跳过分段与向量化）、 {@code SEGMENT_FAILED →
   * SUCCEEDED}（失败原因还没处理就当成功）、{@code EMBEDDING → SEGMENT_FAILED}（向量化阶段不该退回去改分段结构）。
   */
  private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED_INCOMING =
      Map.of(
          PENDING_PARSE, Set.of(),
          SEGMENTING, Set.of(PENDING_PARSE, SEGMENT_FAILED, PENDING_EMBEDDING),
          PENDING_EMBEDDING,
              Set.of(PENDING_PARSE, SEGMENTING, PENDING_EMBEDDING, EMBEDDING_FAILED, SUCCEEDED),
          EMBEDDING, Set.of(PENDING_EMBEDDING),
          SUCCEEDED, Set.of(EMBEDDING),
          SEGMENT_FAILED, Set.of(PENDING_PARSE, SEGMENTING, PENDING_EMBEDDING),
          EMBEDDING_FAILED, Set.of(EMBEDDING));

  /** 允许转到本状态的来源状态集合（只读）。 */
  public Set<DocumentStatus> allowedSources() {
    return ALLOWED_INCOMING.get(this);
  }

  /** {@code from → this} 是否合法。 */
  public boolean canMoveFrom(DocumentStatus from) {
    return allowedSources().contains(from);
  }

  /**
   * 校验一次转移是否合法，非法则抛<strong>可读异常</strong>（带 from / to 与「状态未改变」）。
   *
   * <p>消息里必须出现两个状态名：排障时要一眼看出「谁想从哪儿跳到哪儿」，而不是只知道失败。
   *
   * @param from 当前状态
   * @param to 目标状态
   * @param action 正在做的事（中文，进异常消息）
   * @throws IllegalStateException 非法转移
   */
  public static void requireLegal(DocumentStatus from, DocumentStatus to, String action) {
    if (!to.canMoveFrom(from)) {
      throw new IllegalStateException(
          "不能"
              + action
              + "：当前状态是「"
              + from
              + "」，不允许转到「"
              + to
              + "」（允许的来源状态："
              + to.allowedSources()
              + "，状态未改变）");
    }
  }

  /**
   * 校验当前状态<b>是</b>某个状态 —— 给<strong>不改变状态的就地更新</strong>用（例如「向量化中回写进度」）。
   *
   * <p>★ 为什么不复用 {@link #requireLegal}：就地更新的目标状态就是当前状态，硬套转移矩阵会变成 「自己转自己」而被误判为非法。两件事本来就不一样 ——
   * 一个是「状态要变」，一个是「在这个状态下才准做」。
   *
   * @param current 当前状态
   * @param expected 要求的状态
   * @param action 正在做的事（中文，进异常消息）
   * @throws IllegalStateException 当前状态不是要求的状态
   */
  public static void requireInState(
      DocumentStatus current, DocumentStatus expected, String action) {
    if (current != expected) {
      throw new IllegalStateException(
          "不能" + action + "：当前状态是「" + current + "」，该动作只允许在「" + expected + "」（状态未改变）");
    }
  }
}
