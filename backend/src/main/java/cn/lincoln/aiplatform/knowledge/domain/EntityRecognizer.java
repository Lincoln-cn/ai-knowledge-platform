package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;

/**
 * 实体识别端口（0.6.1）。
 *
 * <h2>为什么要一个接口而不是直接调某个库</h2>
 *
 * <p>用户 2026-11-11 拍板：「EntityRecognizer 这个接口，HanlpEntityRecognizer 和 OnnxNerRecognizer 各实现一份。先在 A
 * 上跑通判据，把 B 作为可替换实现留着； 哪天 A 的召回率不够了，换 B 只换一个 bean。」
 *
 * <p>所以这个接口的**契约要窄到两个实现都能诚实满足**：
 *
 * <ul>
 *   <li>只回答"这段文本里有哪些实体"与"它们之间有什么受控关系"；
 *   <li>**不回答"这个实体在库里对应哪一行"** —— 归并与消歧是别名表的事 （`kb_entity_alias` 的 `unique(kb_id,
 *       alias_name)`），不该压到识别器里；
 *   <li>**不做权限判断** —— 它拿到的文本已经是被允许读的。
 * </ul>
 *
 * <h2>为什么关系方法可以返回空</h2>
 *
 * <p>ONNX 的通用 NER 模型只给实体，不给关系。如果接口要求"必须给关系"，那 `OnnxNerRecognizer` 就只能编造或抛异常 —— 两种都会让调用方写特殊分支。
 *
 * <p>所以契约是：**关系可以由规则层补**（见 `EntityRelationExtractor`），识别器只
 * 负责它在行的那部分。返回空列表表示"我这一层抽不出关系"，不是"这段文本没有关系"。
 *
 * <p>★ 这条区别要写进 cada 实现的 javadoc，否则后来人会把"ONNX 没给关系"读成"这段 没有关系"，然后去调参数 —— 那是调不出来的。
 */
public interface EntityRecognizer {

  /**
   * 从一段正文里抽实体。
   *
   * <p>★ 只做**抽取**，不做消歧：同一个"财务部"在本文里出现三次，实现应当返回去重后 的三条（或一条 + 出现次数，看实现），归并是别名表的事。
   *
   * @param text 已解析、已剥离噪声的正文（不是原始 PDF 字节）
   * @return 按首次出现排序的实体；空文本返回空列表，不抛异常
   */
  default List<Recognized> recognize(String text) {
    return recognize(null, text);
  }

  /**
   * 从一段正文里抽实体，**按库取词表**（`#328`）。
   *
   * <p>★★ 为什么必须有 {@code kbId}：词表在 `kb_entity_dict` 里是 <b>per-库</b>的 （用户 2026-11-11
   * 要求「字典放到数据库里，不是必须要重启才能生效的」）。 不带库标识的实现只能吃一份全局默认表 —— 那样数据库里配的字典<b>一个字都不会生效</b>，
   * 而"没配字典"与"配了但不生效"读数完全一样（`#328`）。
   *
   * <p>默认实现委托 {@link #recognize(String)}：让"不吃库级词表"的实现（如 `OnnxNerRecognizer` 的模型自带标签）不必为了签名而写空方法。★
   * 但这意味着<b>调用方必须显式传 kbId</b>， 不许因为"默认实现能跑"就省掉 —— 抽取服务那边一定要把库 id 往下传。
   *
   * @param kbId 目标知识库；{@code null} 表示"不要库级词表，用实现自己的默认表"
   */
  default List<Recognized> recognize(java.util.UUID kbId, String text) {
    return recognize(text);
  }

  /**
   * 这个实现能给出的实体类型。
   *
   * <p>★ 用途是**诚实记账**：`OnnxNerRecognizer` 的模型通常只认 `PER/LOC/ORG`， 不认 `DOC`（书名号里的制度名）与
   * `TERM`（术语）。调用方据此决定还要不要挂 规则层去补那两类，而不是以为"识别器全都能给"。
   */
  List<String> supportedTypes();

  /** 实现的名字，用于日志与诊断（"这次是哪个识别器跑的"）。 */
  String name();

  /**
   * 抽出来的一个实体。
   *
   * @param type 取 {@code ORG|PERSON|DOC|TERM|OTHER}（与 `kb_entity.type` 对齐）
   * @param name 实体名（原文里的写法，不做归一 —— 归一是别名表的事）
   * @param confidence 实现自己给的置信度，0–1；规则实现给固定值（如 0.8）， 模型实现给 softmax 概率。★ **不许编**：拿不到就填 {@code 1.0} 并
   *     在实现注里写清"这个值不代表模型置信度"
   */
  record Recognized(String type, String name, double confidence) {}

  /**
   * 抽出来的一条受控关系。
   *
   * @param type 取 {@code BELONGS_TO|CONTAINS|PART_OF|RESPONSIBLE_FOR|DEFINES|WORKS_AT|REFERENCES}
   * @param from 头实体名（**必须在同一次 {@link #recognize} 的结果里**，否则调用方 无法溯源）
   * @param to 尾实体名（同上）
   */
  record Relation(String type, String from, String to) {}

  /**
   * 从一段正文里抽受控关系。
   *
   * <p>返回空列表有**两种**含义，调用方不能用它区分： ① 这个实现抽不出关系（见 `name()`）；② 这段确实没有关系。
   *
   * <p>要区分就去看 {@link #supportedTypes()} 与实现名 —— 这也是为什么接口里要 留 `name()`。
   */
  default List<Relation> relations(String text) {
    return List.of();
  }

  /**
   * 抽关系，**带上同一次 {@link #recognize} 的结果**（2026-11-28 加）。
   *
   * <p>★ 为什么要多这一个参数：{@link Relation} 的契约要求 {@code from}/{@code to} 必须在同一次 {@code recognize}
   * 的结果里。规则层自己抽不出真实人名（只认「张伟经理」那种职务词在名字后面的写法）， 于是「张三任信息中心主任」能认出"张三"却成不了边 —— 标记词加了、约束放了，读数还是 0，
   * 而且没有任何报错。
   *
   * <p>默认实现忽略 {@code entities} 并委托 {@link #relations(String)}：不吃实体集合的实现 行为不变，不需要为了签名写空方法。
   *
   * @param entities 同一次 {@link #recognize} 的返回；{@code null} 或空等价于不提供
   */
  default List<Relation> relations(String text, List<Recognized> entities) {
    return relations(text);
  }
}
