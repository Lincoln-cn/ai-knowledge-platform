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
    // ★ 操作人传 null：advisor 层拿不到登录主体（ChatClientRequest 里没有它）。
    //   "谁问的"由会话侧的消息表承担，这里只回答"围栏拦了什么"。
    auditLog.record(
        AuditAction.GUARD_BLOCK,
        "GUARD_RULE",
        first.ruleId().toString(),
        null,
        Map.of(
            "category", first.categoryCode(),
            "kind", first.kind().name(),
            "hash", first.contentHashPrefix()));
    // ★ 不把命中数交给调用方：命中几条本身也是信息（"这句话踩了 3 条"比"踩了 1 条"更能试探词表形状）
    return snapshot.refusalFor(hits);
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
