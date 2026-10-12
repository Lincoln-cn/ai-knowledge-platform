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
   * <p>★ 为什么这一路返回原文而不是解析好的对象：它的调用点用的是<strong>简单分隔</strong>输出（查询理解按换行给多条查询， {@code 11 §2.2.1} 照抄官方
   * {@code MultiQueryExpander} 的做法），分隔与校验住在<strong>领域侧</strong>（{@code
   * QueryUnderstanding.parse}），因为它们要能脱离模型与网络被表驱动地测。 要读"几个固定字段"的调用走 {@link #completeAs}，别在这里自己剥围栏。
   *
   * @param modelId 由 {@link #pickChatModel()} 给出的模型 id
   * @param prompt 完整提示词（这一路不承诺 JSON —— 调用方按简单分隔读输出）
   * @param timeout 这次调用的时间预算（超过即放弃 ⇒ 调用方降级）
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 模型不存在 / 不是对话模型 /
   *     上游不可达或超时（都带可读原因与下一步）
   */
  String complete(UUID modelId, String prompt, Duration timeout);

  /**
   * 发一次对话补全，并把输出解析成 {@code type} 的实例 —— ★ <strong>格式说明与解析都交给官方转换器</strong> （适配器里用 {@code
   * org.springframework.ai.converter.BeanOutputConverter}：{@code getFormat()} 生成 "只输出 JSON"的指令与
   * schema，{@code convert(...)} 解析响应）。
   *
   * <p>★ 为什么要这个出口：{@code 11 §2.7} 的口径是"实现结构化输出时先看官方，不要又写一层"。自研解析必须处理 围栏、字段别名、前后废话 ——
   * 那些正是官方转换器已经在做的事，自己再写一遍只多一份要维护的容错， 而且"看起来在读 JSON、实际读的是我们猜的那几个形状"。
   * <strong>领域侧只留"什么算可用"这类判定规则</strong>（例如多跳判定：{@code sufficient} 字段缺失 不等于 {@code false}）。
   *
   * <p>★ 解析失败按一次失败抛出，不返回 null：调用方据此降级（多跳的承诺是"判定坏了也照常用已有结果回答"）， 而"静默给一个默认值"会让多跳永远不触发、且没有任何地方报错。
   *
   * @param modelId 由 {@link #pickChatModel()} 给出的模型 id
   * @param prompt 提示词（<strong>不要自己写"请返回 json"</strong> —— 格式说明由转换器追加）
   * @param type 期望的产出类型（转换器按它生成 schema）
   * @param timeout 这次调用的时间预算（超过即放弃 ⇒ 调用方降级）
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 模型不存在 / 不是对话模型 / 上游不可达或超时 /
   *     <strong>输出不符合 schema</strong>
   */
  <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout);
}
