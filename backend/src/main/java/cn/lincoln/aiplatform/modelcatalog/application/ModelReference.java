package cn.lincoln.aiplatform.modelcatalog.application;

import java.util.List;
import java.util.UUID;

/**
 * 模型 / 服务商的<strong>引用面</strong>读数 —— 「删除它之前会弄坏什么」的一份可读清单（{@code #300}）。
 *
 * <p>★ 为什么要有这个类型，而不是在 {@code delete()} 里写 {@code if (count > 0) throw}：一句 "还有 N 个引用" 只够拦住误删，
 * <strong>不够让人决定"要不要强制删"</strong>。用户要看到的是"哪三个应用、哪两个库、多少篇文档、哪个平台默认键"，否则他只能盲选。
 * 所以引用面是<strong>数据</strong>（{@code 409} 的 {@code data} 字段），不是一句提示语。
 *
 * <h3>★ 五类引用与它们的真实存放位置（{@code #300} 核实后的形状）</h3>
 *
 * <ul>
 *   <li>{@code as_assistant.model_id} ⇒ {@link #apps()}
 *   <li>{@code kb_knowledge_base.embedding_model_id} ⇒ {@link #knowledgeBases()}
 *   <li>★ 文档<strong>没有</strong> {@code embedding_model_id} 列 —— 文档跟着库用同一个向量模型。所以 {@link
 *       #documents()} 读的是「这些库下面还有多少篇未删文档」：删掉向量模型 ⇒ 这些文档检索不回来。 把 issue 里写的「{@code
 *       kb_document.embedding_model_id}」按字面去查会直接失败（列不存在），这里按真实形状实现。
 *   <li>{@code cv_chat_settings.default_model_id} ⇒ {@link #defaultKeys()}（issue 写的 {@code
 *       platform_settings.chat.model} 在库里不存在；"平台默认向量模型"那一格至今未落地，见 {@code RetrievalScope.java}
 *       的注释，故不会出现 embedding 默认键）
 *   <li>{@code ai_model.provider_id} ⇒ 只在服务商侧有意义：{@link #models()} / {@link #modelNames()}
 * </ul>
 *
 * <p>★ <strong>刻意不计入</strong> {@code cv_message.model_id}：它是历史消息的"这条答案由哪个模型产出"的存档，不是活的依赖 —— 拿它拦删除，
 * 一个被用过一次的模型就永远删不掉了。删除后它变成历史记录里的一个旧 id，不影响任何在线路径。
 *
 * <p>★ 软删的行不计入：{@code deleted_at is not null} 的应用/库/文档已经不参与任何在线路径，把它们算成"引用"会让守卫变成 "永远删不掉"。
 *
 * @param apps 引用它的应用（id + 名称），按名称排序，最多 {@code 20} 条（够界面展示，也够人判断；超出走计数）
 * @param knowledgeBases 引用它的知识库（id + 名称），同样封顶
 * @param documents 这些知识库下<strong>未删</strong>文档的总数（文档跟着库用同一个向量模型，见类注释）
 * @param defaultKeys 平台默认键的可读名（如 {@code cv_chat_settings.default_model_id}）
 * @param models 服务商侧：绑定在它下面的模型数（模型侧恒为 0）
 * @param modelNames 服务商侧：这些模型的名字（封顶），方便直接看出"删掉的是哪几个"
 */
public record ModelReference(
    List<Named> apps,
    List<Named> knowledgeBases,
    long documents,
    List<String> defaultKeys,
    long models,
    List<String> modelNames) {

  /** 一条具名引用：id 让界面能跳过去，name 让界面能直接显示。 */
  public record Named(UUID id, String name) {}

  /** 空引用面：没有任何引用。 */
  public static final ModelReference NONE =
      new ModelReference(List.of(), List.of(), 0, List.of(), 0, List.of());

  /** 展示用的名字上限：超出只报总数，不把响应体撑大。 */
  public static final int MAX_NAMES = 20;

  /** 有没有任何引用（{@code delete} 的守卫就看它）。 */
  public boolean any() {
    return !apps.isEmpty()
        || !knowledgeBases.isEmpty()
        || documents > 0
        || !defaultKeys.isEmpty()
        || models > 0;
  }

  /**
   * 引用总数：应用数 + 库数 + 文档数 + 默认键数 + 模型数。
   *
   * <p>★ 它是一个<strong>给人看的量级</strong>，不是"受影响对象"的精确定义（文档与库有父子关系，但两者都各自会被弄坏）。 界面上的「N 处引用」用它，{@code
   * 409} 的明细看各字段。
   */
  public long total() {
    return apps.size() + knowledgeBases.size() + documents + defaultKeys.size() + models;
  }

  /**
   * 一句话摘要（进 {@code message}，让人不展开 JSON 也知道坏了什么）。
   *
   * <p>例：{@code 3 个应用、2 个知识库（共 12 篇文档）、平台默认键 cv_chat_settings.default_model_id}。
   */
  public String summary() {
    String downstream = downstreamSummary();
    if (models > 0) {
      return downstream.isEmpty() ? models + " 个模型" : downstream + "、" + models + " 个模型";
    }
    return downstream.isEmpty() ? "无引用" : downstream;
  }

  /**
   * 只讲<strong>模型之外</strong>的下游引用（应用 / 知识库 / 文档 / 平台默认键）。
   *
   * <p>★ 为什么单拎一个方法：服务商侧的 {@code models} 是"它自己下面的模型数"， 不是"被谁引用" —— 把它拼进 "仍被引用：…1 个模型"
   * 会读成"有个模型引用了它"，那是错的意思。
   */
  public String downstreamSummary() {
    StringBuilder text = new StringBuilder();
    if (!apps.isEmpty()) {
      text.append(apps.size()).append(" 个应用");
    }
    if (!knowledgeBases.isEmpty()) {
      if (text.length() > 0) {
        text.append("、");
      }
      text.append(knowledgeBases.size()).append(" 个知识库");
      if (documents > 0) {
        text.append("（共 ").append(documents).append(" 篇文档）");
      }
    } else if (documents > 0) {
      // 理论上 documents 只会在有库时 > 0；真出现就明确说出来，不要静默丢掉这个数
      if (text.length() > 0) {
        text.append("、");
      }
      text.append(documents).append(" 篇文档");
    }
    if (!defaultKeys.isEmpty()) {
      if (text.length() > 0) {
        text.append("、");
      }
      text.append("平台默认键 ").append(String.join(" / ", defaultKeys));
    }
    return text.toString();
  }
}
