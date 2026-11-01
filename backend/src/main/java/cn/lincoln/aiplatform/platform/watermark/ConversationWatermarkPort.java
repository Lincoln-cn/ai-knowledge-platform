package cn.lincoln.aiplatform.platform.watermark;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.UUID;

/**
 * <strong>会话密级水位的补偿端口</strong>（{@code #247} 判据 ①）—— 让"文档升密"这件事能传导到引用过它的会话。
 *
 * <h3>为什么需要一个端口</h3>
 *
 * <p>会话的 {@code max_classification} 是<strong>单调水位</strong>：它的唯一生产写入点是"引用集合落库的同一个事务"（{@code
 * ChatTurnWriter}，见 {@code 13 §2.2}）。那条路径建立在"引用的密级只会在检索那一刻被读一次"的假设上 —— 文档密级<b>入库时就定死</b>，
 * 所以没人想过它会变。
 *
 * <p>{@code #247} 把那个假设拆掉了：文档密级有了变更入口之后，<b>历史会话的水位会停在旧值上</b>。方向必须是
 * <strong>抬高</strong>，因为水位低于它引用的内容密级，就违反 {@code 13 §2.2} 的那条不变量（"会话密级 = 其引用集合的最大密级"）。
 *
 * <p>★ <strong>接口放 {@code platform}、实现放 {@code conversation}</strong>：与 {@code OrgDirectoryPort} /
 * {@code AccessScopePort} 同一条铁律（{@code 08 §4.4.2}）。knowledge 模块不许直接依赖 {@code conversation.domain}
 * —— 那条依赖会被 {@code ModuleBoundaryTest} 判红。
 *
 * <h3>★ 只给一个方法</h3>
 *
 * <p>没有"降水位"：会话密级是单调的（{@code #108}：库侧 UPDATE 的 {@code where} 里只有严格低于它的那些档）。文档<b>降密</b>时
 * <strong>什么都不用做</strong> —— 水位停在旧的高档位，是安全的严格方向，也是"历史回答曾经引用了什么"的如实记录。
 */
public interface ConversationWatermarkPort {

  /**
   * 把<strong>引用过这篇文档</strong>的所有会话的水位抬到 {@code refsMax}（只升不降、相等幂等）。
   *
   * <p>★ 实现必须走一条 UPDATE 一族、且在调用方事务里（{@code REQUIRED}）：文档密级的变更与水位补偿要么一起生效，要么一起不生效 ——
   * 半个结果就是"文档已经不可见，而会话还按低密级筛"，这类中间态没有任何人能看到。
   *
   * @param documentId 文档 id
   * @param refsMax 新的密级（调用方保证它是一次<b>升密</b>；降密不必调用）
   * @return 真正被抬高的会话数（{@code > 0} 说明这篇文档确实被引用过）。★ 返回计数是为了让调用点能把它写进日志 —— 一个恒为 0
   *     的补偿和"没有补偿"在日志上看起来一样，而它们是两件不同的事：前者可能是引用快照的字段名写错。
   */
  int raiseForDocument(UUID documentId, ClassificationLevel refsMax);
}
