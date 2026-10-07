package cn.lincoln.aiplatform.platform.model;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 对话模型端口（跨模块 SPI，{@code 01 §二} 第 3 行：端口在我们这边）—— 第 12 周 · 0.4.0。
 *
 * <p>与 {@link EmbeddingPort} 同一套规矩：接口在 {@code platform}、实现在 {@code modelcatalog}，
 * 于是"哪个上游、密钥怎么解、超时多久"都不会漏到业务模块里。第一个消费者是检索的 <strong>查询理解</strong>（{@code 10
 * §3.1}），但它不是为这一处专用的：改写、多跳判定、 无引用时的自由回答都会走同一条路（{@code 10 §六} 的"模型调用总次数"要能数得清）。
 *
 * <h3>★ 为什么把"挑哪个模型"也放进这个端口</h3>
 *
 * <p>模型目录（哪些模型、什么类型、加载没有）住在 {@code modelcatalog}。检索要的不是"某个 id 的模型" ——它<strong>手上根本没有
 * id</strong>（应用绑定对话模型是第 15 周的事，{@code 10 §8.5}）。 所以选择规则必须留在拥有目录的那一侧，检索只问一句"给我一个能用的"：
 *
 * <ul>
 *   <li>{@link #pickChatModel()} 返回 <strong>empty</strong> 表示"没有可用的对话模型" —— ★
 *       这不是异常，而是<strong>一条正常的降级输入</strong>：查询理解整层不可用时， 检索必须退回原始问题单路（{@code 10 §6.3} 的
 *       D4）。把它做成异常，会让"没配模型" 和"模型坏了"在日志里长得一样。
 *   <li>什么时候有多个可用模型、按什么规则选，是目录那边的事（现在：按创建时间取第一个已加载的）。
 * </ul>
 *
 * <h3>★ 调用必须能被打断</h3>
 *
 * <p>{@link #complete} 收一个显式 {@code timeout}（而不是用固定的全局超时）：查询理解这一层 花掉的时间直接从"首字延迟"里扣（{@code 10
 * §6.1}）。上游抖动时，<strong>"准时放弃并降级" 比"等到结果但用户已经走了"更有价值</strong> —— 所以超时是调用方给的预算，不是适配器的常量。
 */
public interface ChatPort {

  /**
   * 挑一个可用的对话模型（{@code kind=CHAT} 且已加载）。
   *
   * @return 模型 id；<strong>空</strong>表示当前没有可用的对话模型（调用方据此降级，不要当错误处理）
   */
  Optional<UUID> pickChatModel();

  /**
   * 发一次对话补全，返回模型输出的<strong>原文</strong>。
   *
   * <p>★ 为什么返回原文而不是解析好的对象：解析、校验与降级都住在<strong>领域侧</strong> （{@code
   * QueryUnderstanding.parse}），因为它们要能脱离模型与网络被表驱动地测。 把解析放适配器里，等于让"模型输出不合法"变成一次依赖上游的测试。
   *
   * @param modelId 由 {@link #pickChatModel()} 给出的模型 id
   * @param prompt 完整提示词（含结构化输出的格式要求）
   * @param timeout 这次调用的时间预算（超过即放弃 ⇒ 调用方降级）
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 模型不存在 / 不是对话模型 /
   *     上游不可达或超时（都带可读原因与下一步）
   */
  String complete(UUID modelId, String prompt, Duration timeout);
}
