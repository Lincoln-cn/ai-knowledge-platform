package cn.lincoln.aiplatform.platform.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 围栏的**不可变运行快照**：一次读库之后，匹配只碰这份对象。
 *
 * <h3>★ 为什么要有快照，而不是每次匹配都查库</h3>
 *
 * <p>匹配发生在**每一次**问答请求的最前面（{@code SafeGuardChatAdvisor}）。每条请求查两次表（分类 + 规则） 意味着围栏把整条链路的首字延迟又往前拖了一截 ——
 * 而它存在的目的是"更安全"，不是"更慢"。
 *
 * <h3>★ 为什么停用的分类与规则也装进来</h3>
 *
 * <p>配置页要显示"我停用过什么"（{@code includeDisabled} 那一套）。所以快照存**全量**，另用 {@link #patterns} 只装"真的参与匹配"的那部分 ——
 * 两个列表各回答一个问题，混成一个就要在页面里反推。
 *
 * @param settings 运行开关
 * @param categories 全部分类（含停用）
 * @param rules 全部规则（含停用）
 * @param patterns 参与匹配的规则（已归一化、已剔除停用分类与停用规则）
 */
public record GuardSnapshot(
    GuardSettings settings,
    List<GuardCategory> categories,
    List<GuardRule> rules,
    List<Pattern> patterns) {

  /**
   * 一条编译好的匹配规则。
   *
   * @param ruleId 规则主键
   * @param categoryId 分类主键
   * @param categoryCode 分类机器码
   * @param categoryName 分类展示名
   * @param kind 匹配类型
   * @param normalized 归一化后的内容（{@link GuardText#normalize}）
   * @param contentHashPrefix 归一化内容的哈希前 8 位
   * @param categoryRefusalText 分类专属话术（可空）
   */
  public record Pattern(
      UUID ruleId,
      UUID categoryId,
      String categoryCode,
      String categoryName,
      GuardRuleKind kind,
      String normalized,
      String contentHashPrefix,
      String categoryRefusalText) {}

  /**
   * 匹配一段用户输入。
   *
   * @param text 原始输入（{@code null} 视为不匹配）
   * @return 全部命中（按规则序；空列表 = 放行）
   */
  public List<GuardHit> match(String text) {
    if (text == null || text.isBlank() || patterns.isEmpty()) {
      return List.of();
    }
    // ★ 两种归一化口径各算一次，不是每条规则算一次 —— 规则有几百条时这是数量级差别。
    String keywordText = null;
    String questionText = null;
    List<GuardHit> hits = new ArrayList<>();
    for (Pattern pattern : patterns) {
      String normalized = pattern.normalized();
      if (normalized.isEmpty()) {
        // 空规则永远不命中（否则 `contains("")` 恒真 ⇒ 全平台都被拦）
        continue;
      }
      boolean matched;
      switch (pattern.kind()) {
        case KEYWORD -> {
          if (keywordText == null) {
            keywordText = GuardText.normalize(text, GuardRuleKind.KEYWORD);
          }
          matched = keywordText.contains(normalized);
        }
        case QUESTION -> {
          if (questionText == null) {
            questionText = GuardText.normalize(text, GuardRuleKind.QUESTION);
          }
          matched = questionText.contains(normalized);
        }
        default -> matched = false;
      }
      if (matched) {
        hits.add(
            new GuardHit(
                pattern.ruleId(),
                pattern.categoryId(),
                pattern.categoryCode(),
                pattern.categoryName(),
                pattern.kind(),
                pattern.contentHashPrefix(),
                pattern.categoryRefusalText()));
      }
    }
    return List.copyOf(hits);
  }

  /**
   * 该回哪句话。
   *
   * <p>★ 默认统一话术。只有 {@code categoryRefusalEnabled} 打开时才按分类取 —— 而且取不到就落回统一话术，
   * 绝不回空串（空串会让用户看到一片空白，比给一句拒答更像故障）。
   *
   * @param hits 本次命中
   * @return 拒答话术（非空）
   */
  public String refusalFor(List<GuardHit> hits) {
    if (settings.categoryRefusalEnabled()) {
      for (GuardHit hit : hits) {
        String text = hit.categoryRefusalText();
        if (text != null && !text.isBlank()) {
          return text;
        }
      }
    }
    return settings.defaultRefusalText();
  }
}
