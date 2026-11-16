package cn.lincoln.aiplatform.platform.guard;

import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 安全围栏的业务层（校验 + 审计接线）。
 *
 * <p>存储细节全部在 {@link GuardStore}；这里只做三件事：<strong>入参校验</strong>、<strong>审计留痕</strong>、
 * 以及给对话链路一个"判定一次"的入口（{@link #inspect(String)}）。
 *
 * <h3>★ 为什么"关掉围栏"要单独记一条审计</h3>
 *
 * <p>见 {@link AuditAction#GUARD_SETTINGS_CHANGE}：关掉它让全平台问答在无检查状态下运行，是这一族里最危险的一次改动。
 *
 * <h3>★ 为什么命中只记哈希、不记词面</h3>
 *
 * <p>词表是可被绕过的清单，把它抄进审计表等于给"能读审计的人"发了一份副本；同时用户原话属内容， 审计不该顺手存正文。要的内容是"哪一类、哪一条、被拦了几次"，哈希前 8 位就够聚合。
 */
@Service
public class GuardService {

  /** 分类机器码的形状：大写字母开头，大写字母/数字/下划线，最长 32。 */
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

  private static final int MAX_NAME_LENGTH = 32;

  private static final int MAX_RULE_LENGTH = 200;

  private static final int MAX_NOTE_LENGTH = 200;

  private final GuardStore store;

  private final AuditLogPort auditLog;

  public GuardService(GuardStore store, AuditLogPort auditLog) {
    this.store = store;
    this.auditLog = auditLog;
  }

  /** 当前生效的配置（运行开关）。 */
  public GuardSettings settings() {
    return store.current().settings();
  }

  /** 全部分类（含停用的：维护页要能看到自己停过什么）。 */
  public List<GuardCategory> categories() {
    return store.current().categories();
  }

  /**
   * 全部规则（维护页用）。
   *
   * @param categoryId 只取某个分类；{@code null} = 全部
   */
  public List<GuardRule> rules(UUID categoryId) {
    List<GuardRule> all = store.current().rules();
    if (categoryId == null) {
      return all;
    }
    return all.stream().filter(rule -> rule.categoryId().equals(categoryId)).toList();
  }

  /**
   * 改运行开关。
   *
   * @param settings 新值（会走 {@link GuardSettings#checked()}）
   * @param operator 操作人
   * @return 落库后的值
   */
  public GuardSettings updateSettings(GuardSettings settings, UUID operator) {
    GuardSettings before = store.current().settings();
    GuardSettings after = store.saveSettings(settings, operator);
    auditLog.record(
        AuditAction.GUARD_SETTINGS_CHANGE,
        "GUARD_SETTINGS",
        "singleton",
        operator,
        Map.of(
            "inputEnabledBefore", before.inputEnabled(),
            "inputEnabledAfter", after.inputEnabled(),
            "advisorsEnabledBefore", before.advisorsEnabled(),
            "advisorsEnabledAfter", after.advisorsEnabled(),
            "defaultRefusalTextBefore", before.defaultRefusalText(),
            "defaultRefusalTextAfter", after.defaultRefusalText()));
    return after;
  }

  /**
   * 新增或修改分类。
   *
   * @param id 主键；{@code null} = 新增
   * @param code 机器码（新增必填；修改时传原值，改了也不生效）
   * @param name 展示名
   * @param sortOrder 排序；{@code null} = 排在末尾
   * @param enabled 是否启用；{@code null} = 保持原值（新增时 = true）
   * @param refusalText 分类专属话术；{@code null} = 用统一话术
   * @param operator 操作人
   * @return 落库后的分类
   */
  public GuardCategory saveCategory(
      UUID id,
      String code,
      String name,
      Integer sortOrder,
      Boolean enabled,
      String refusalText,
      UUID operator) {
    GuardCategory existing =
        id == null
            ? null
            : store.current().categories().stream()
                .filter(category -> category.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分类不存在：" + id));
    String finalizedCode = existing == null ? requireCode(code) : existing.code();
    String finalizedName = requireName(name);
    boolean finalizedEnabled = enabled == null ? (existing == null || existing.enabled()) : enabled;
    int finalizedSort = sortOrder == null ? nextSortOrder(existing) : sortOrder;
    String finalizedRefusal = checkedRefusal(refusalText);
    GuardCategory saved =
        store.saveCategory(
            id, finalizedCode, finalizedName, finalizedSort, finalizedEnabled, finalizedRefusal);
    auditLog.record(
        AuditAction.GUARD_RULE_CHANGE,
        "GUARD_CATEGORY",
        saved.id().toString(),
        operator,
        Map.of(
            "op",
            existing == null ? "create" : "update",
            "code",
            saved.code(),
            "enabled",
            saved.enabled(),
            "hasRefusalText",
            finalizedRefusal != null));
    return saved;
  }

  /**
   * 停用分类。★ 内置分类也允许停用（运营口径优先），但不允许删 —— 历史审计里的 {@code code} 还要有显示名。
   *
   * @param id 分类
   * @param operator 操作人
   * @return 停用后的分类
   */
  public GuardCategory disableCategory(UUID id, UUID operator) {
    GuardCategory before =
        store.current().categories().stream()
            .filter(category -> category.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("分类不存在：" + id));
    GuardCategory saved = store.disableCategory(id);
    auditLog.record(
        AuditAction.GUARD_RULE_CHANGE,
        "GUARD_CATEGORY",
        id.toString(),
        operator,
        Map.of("op", "disable", "code", before.code()));
    return saved;
  }

  /**
   * 新增或修改一条规则。
   *
   * @param id 主键；{@code null} = 新增
   * @param categoryId 分类（必填）
   * @param kind 类型；{@code null} = 保持原值（新增时必填）
   * @param content 原始写法
   * @param enabled 是否启用；{@code null} = 保持原值（新增时 = true）
   * @param note 备注
   * @param operator 操作人
   * @return 落库后的规则
   */
  public GuardRule saveRule(
      UUID id,
      UUID categoryId,
      GuardRuleKind kind,
      String content,
      Boolean enabled,
      String note,
      UUID operator) {
    GuardRule existing =
        id == null
            ? null
            : store.current().rules().stream()
                .filter(rule -> rule.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("规则不存在：" + id));
    UUID finalizedCategory =
        categoryId == null ? (existing == null ? null : existing.categoryId()) : categoryId;
    if (finalizedCategory == null) {
      throw new IllegalArgumentException("必须指定分类");
    }
    boolean categoryExists =
        store.current().categories().stream()
            .anyMatch(category -> category.id().equals(finalizedCategory));
    if (!categoryExists) {
      throw new IllegalArgumentException("分类不存在：" + finalizedCategory);
    }
    GuardRuleKind finalizedKind = kind == null ? (existing == null ? null : existing.kind()) : kind;
    if (finalizedKind == null) {
      throw new IllegalArgumentException("必须指定规则类型（KEYWORD / QUESTION）");
    }
    String finalizedContent = requireContent(content);
    boolean finalizedEnabled = enabled == null ? (existing == null || existing.enabled()) : enabled;
    // ★ 同分类 + 同类型 + 归一化后内容相同 = 同一条规则：库上有唯一约束兜着，但撞约束抛的是
    //   DataIntegrityViolationException ⇒ 界面读到「服务内部错误」，用户不知道是自己重复了。
    //   在落库之前先判一次，把话说清楚。★ 不能拿 GuardSnapshot.patterns() 判：那一列在
    //   「输入检查关掉」时是空的，会让这条校验在关围栏的部署上失效。
    String normalized = GuardText.normalize(finalizedContent, finalizedKind);
    boolean duplicated =
        store.current().rules().stream()
            .anyMatch(
                rule ->
                    rule.categoryId().equals(finalizedCategory)
                        && rule.kind() == finalizedKind
                        && GuardText.normalize(rule.content(), rule.kind()).equals(normalized)
                        && (id == null || !rule.id().equals(id)));
    if (duplicated) {
      throw new IllegalArgumentException(
          "这个分类下已经有同一条规则了（" + finalizedKind.label() + "归一化后内容相同，换个分类或改写内容）");
    }
    GuardRule saved =
        store.saveRule(
            id,
            finalizedCategory,
            finalizedKind,
            finalizedContent,
            finalizedEnabled,
            checkedNote(note),
            operator);
    // ★ 记哈希而不是记词：词表是可绕过清单，抄进审计等于发了份副本
    auditLog.record(
        AuditAction.GUARD_RULE_CHANGE,
        "GUARD_RULE",
        saved.id().toString(),
        operator,
        Map.of(
            "op", existing == null ? "create" : "update",
            "category", saved.categoryCode(),
            "kind", saved.kind().name(),
            "enabled", saved.enabled(),
            "hash", GuardText.hashPrefix(GuardText.normalize(saved.content(), saved.kind()))));
    return saved;
  }

  /**
   * 删除一条规则。★ 允许真删：错加的词留在表里即使停用，"这条词存在过"也会变成永久记录。
   *
   * @param id 规则
   * @param operator 操作人
   * @return 是否删到了
   */
  public boolean deleteRule(UUID id, UUID operator) {
    GuardRule existing =
        store.current().rules().stream()
            .filter(rule -> rule.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("规则不存在：" + id));
    boolean deleted = store.deleteRule(id);
    if (deleted) {
      auditLog.record(
          AuditAction.GUARD_RULE_CHANGE,
          "GUARD_RULE",
          id.toString(),
          operator,
          Map.of(
              "op", "delete",
              "category", existing.categoryCode(),
              "kind", existing.kind().name(),
              "hash",
                  GuardText.hashPrefix(GuardText.normalize(existing.content(), existing.kind()))));
    }
    return deleted;
  }

  /**
   * 对话链路的判定入口（{@code SafeGuardChatAdvisor} 调它）。
   *
   * @param text 用户输入
   * @return 拒绝话术；{@code null} = 放行
   */
  public String inspect(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    GuardSnapshot snapshot = store.current();
    if (!snapshot.settings().inputEnabled() || !snapshot.settings().advisorsEnabled()) {
      return null;
    }
    List<GuardHit> hits = snapshot.match(text);
    if (hits.isEmpty()) {
      return null;
    }
    GuardHit first = hits.get(0);
    // ★ 谁问的 / 在哪个会话里问的：拦截点（Spring AI 的 advisor 链）手上只有 Prompt，
    //   这两样由上游 ChatService 放进 GuardCallContext。
    GuardCallContext.Carrier carrier = GuardCallContext.current();
    // ★★ 没有随行上下文 ⇒ 这次拦截发生在链路的**内部步骤**上（今天就是检索前的
    //   「查询理解」：它把用户原话再送给一次模型改写，所以用户说了敏感词它也会命中）。
    //   那种调用不记审计，理由有两条：
    //   ① 同一次提问会因此记两条（一次内部改写 + 一次真正的回答），而「这个月拦了多少次」
    //      这个数只有一个正确答案 —— 实测过：3 次提问在列表里成了 4 行；
    //   ② 它回答不了"谁问的"（内部调用本来就没有登录人），留着只是一行无主记录。
    //   ★ 判定仍然生效（拒答照旧），只是不留痕 —— 留痕归"真正回答用户的那一次"。
    //   ★ 推论：任何新增的**用户入口**都必须在入口处 set 上下文，否则它会被当成内部步骤。
    boolean auditable = carrier != null;
    if (auditable) {
      Map<String, Object> detail = new java.util.LinkedHashMap<>();
      detail.put("category", first.categoryCode());
      detail.put("kind", first.kind().name());
      detail.put("hash", first.contentHashPrefix());
      if (carrier.conversationId() != null) {
        // ★ 记会话 id 而不是记原话：原话已经在消息表里（有访问控制、有留存期），
        //   复制进审计等于多一份删不掉的副本 —— 而审计是长期留存的。
        detail.put("conversation", carrier.conversationId().toString());
      }
      auditLog.record(
          AuditAction.GUARD_BLOCK,
          "GUARD_RULE",
          first.ruleId().toString(),
          carrier.actorId(),
          detail);
    }
    // ★ 不把命中数交给调用方：命中几条本身也是信息（"这句话踩了 3 条"比"踩了 1 条"更能试探词表形状）
    return snapshot.refusalFor(hits);
  }

  /**
   * 拦截读数：汇总 + 当前页明细。
   *
   * @param days 窗口天数；{@code 0} 表示不限时间（"全部"）。其余夹在 1–90
   * @param page 页码，从 1 开始
   * @param size 每页条数，夹在 1–{@value GuardHitPage#MAX_SIZE}
   */
  public GuardHitPage hits(int days, int page, int size) {
    int window = days <= 0 ? 0 : Math.max(1, Math.min(days, 90));
    int pageSize = size <= 0 ? GuardHitPage.DEFAULT_SIZE : Math.min(size, GuardHitPage.MAX_SIZE);
    int currentPage = Math.max(1, page);
    List<GuardHitCount> byCategory = store.hitCounts(window);
    long total = store.hitTotal(window);
    List<GuardHitDetail> recent = store.hitDetails(window, pageSize, (currentPage - 1) * pageSize);
    return new GuardHitPage(window, total, byCategory, recent, currentPage, pageSize);
  }

  private String requireCode(String code) {
    String trimmed = code == null ? "" : code.trim();
    if (!CODE.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(
          "分类机器码只能是大写字母开头的 A-Z / 0-9 / 下划线，最长 32 位（收到：" + code + "）");
    }
    boolean taken =
        store.current().categories().stream().anyMatch(category -> category.code().equals(trimmed));
    if (taken) {
      throw new IllegalArgumentException("分类机器码已存在：" + trimmed);
    }
    return trimmed;
  }

  private String requireName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("分类名不能为空");
    }
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("分类名最长 " + MAX_NAME_LENGTH + " 字");
    }
    return trimmed;
  }

  private String requireContent(String content) {
    String trimmed = content == null ? "" : content.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("规则内容不能为空");
    }
    if (trimmed.length() > MAX_RULE_LENGTH) {
      throw new IllegalArgumentException("规则内容最长 " + MAX_RULE_LENGTH + " 字");
    }
    return trimmed;
  }

  private String checkedRefusal(String refusalText) {
    if (refusalText == null || refusalText.isBlank()) {
      return null;
    }
    String trimmed = refusalText.trim();
    if (trimmed.length() > GuardSettings.MAX_REFUSAL_LENGTH) {
      throw new IllegalArgumentException("分类话术最长 " + GuardSettings.MAX_REFUSAL_LENGTH + " 字");
    }
    return trimmed;
  }

  private String checkedNote(String note) {
    if (note == null || note.isBlank()) {
      return null;
    }
    String trimmed = note.trim();
    if (trimmed.length() > MAX_NOTE_LENGTH) {
      throw new IllegalArgumentException("备注最长 " + MAX_NOTE_LENGTH + " 字");
    }
    return trimmed;
  }

  private int nextSortOrder(GuardCategory existing) {
    if (existing != null) {
      return existing.sortOrder();
    }
    return store.current().categories().stream().mapToInt(GuardCategory::sortOrder).max().orElse(0)
        + 10;
  }
}
