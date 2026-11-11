package cn.lincoln.aiplatform.knowledge.application;

import java.util.List;
import java.util.UUID;

/**
 * 实体合并的三档策略。
 *
 * <p>★ 三档的划分（用户 2026-11-10 拍板，落点 {@code §11.27} 与 {@code §11.35}）：
 *
 * <ol>
 *   <li><b>强键相同</b>（{@code code} 相等）⇒ <b>自动归并</b>。强键是「这个实体在业务系统里的唯一标识」， 两条记录共用一个 {@code code}
 *       就是同一个东西 —— 没有判断余地。
 *   <li><b>属性高度重叠</b> ⇒ 只生成候选、<b>等人确认</b>。会改权限归属，不能自动。
 *   <li><b>只有名字相似</b> ⇒ <b>只标疑似，不合</b>。「财务部」与「财务资产部」是不是同一个，没有数据能判定。
 * </ol>
 *
 * <p>★★ <b>第一档发生在写入之前，不是事后清理</b>。这是 2026-11-11 订正的设计：
 *
 * <p>{@code kb_entity} 上有 {@code unique(kb_id, code) where code is not null}，所以「库里有两行同 code」
 * 这个状态**根本不可能出现** —— 我最初写的「扫全库找同 code 的组再合并」在健康的库上永远扫不到东西， 等于空转。唯一约束是对的，错的是把合并想成"事后"。
 * 正确的形状是：拿到一个实体先按 {@code code} 问库里有没有， 有就<b>不插新行</b>、只记一条别名。
 *
 * <p>★★ <b>合并 = 加别名 + 不插新行，永远不删原行</b>。理由三条：合并要能拆 · {@code kb_entity_mention} 与 {@code kb_relation}
 * 引用的是实体 id（删行等于让这些边失效）· 审计要能回答「谁在什么时候把 A 并进了 B」。
 */
public interface EntityMergeService {

  /**
   * 第一档：按强键决定「这次该插一行」还是「并进已有的那行」。
   *
   * <p>调用方（导入 / 抽取写入方）拿到的实体可能带 {@code code}（结构化导入）或不带（文档抽取）。
   *
   * <ul>
   *   <li>{@code code} 为空 ⇒ 返回 {@link Decision#insert()}，走正常插入（后两档管）。
   *   <li>{@code code} 有值、库里已有同 code 的行 ⇒ 返回 {@link Decision#mergeInto(UUID)}，调用方<b>不该插新行</b>， 并应把
   *       {@code name} 记成那行的别名。
   *   <li>{@code code} 有值、库里没有 ⇒ 返回 {@link Decision#insert()}。
   * </ul>
   *
   * @param kbId 库标识（{@code null} ⇒ 直接 insert，不查库）
   * @param code 强键（{@code null} 或空 ⇒ 直接 insert）
   * @return 决定
   */
  Decision resolve(UUID kbId, String code);

  /**
   * 把 {@code aliases} 记为 {@code entityId} 的别名（第一档归并时调用）。
   *
   * <p>★ 幂等：`unique(kb_id, alias_name)` 兜住，返回值只数<b>真正新增</b>的行。
   *
   * @return 实际新增的别名行数
   */
  int recordAliases(UUID kbId, UUID entityId, List<String> aliases);

  /**
   * 第二档：属性重叠的候选（<b>不自动执行</b>，供管理界面等人确认）。
   *
   * <p>{@code reason} 写清为什么认为它们可能相同（共有的属性名与值）—— 确认的人需要依据， 只给一个分数等于让他盲选。
   */
  List<Candidate> candidatesByAttributes(UUID kbId);

  /** 一次 {@link #resolve} 的决定。 */
  record Decision(boolean insertNew, UUID mergeInto, String matchedCode) {

    public static Decision insert() {
      return new Decision(true, null, null);
    }

    public static Decision mergeInto(UUID entityId, String code) {
      return new Decision(false, entityId, code);
    }
  }

  /**
   * 一条待确认的合并候选。
   *
   * @param keepId 建议保留的实体（较早创建的那个，界面允许改）
   * @param mergeId 建议并入的实体
   * @param reason 人可读的依据（例如「共同属性 department=财务」）
   */
  record Candidate(UUID keepId, String keepName, UUID mergeId, String mergeName, String reason) {}
}
