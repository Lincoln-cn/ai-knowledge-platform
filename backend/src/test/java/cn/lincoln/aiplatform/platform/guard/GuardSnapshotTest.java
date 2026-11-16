package cn.lincoln.aiplatform.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 快照的匹配与拒答话术（{@link GuardSnapshot}）。
 *
 * <h3>★ 这里钉的三条，正是 {@code ROADMAP §11.53.7} 的前两条</h3>
 *
 * <ol>
 *   <li>某一类命中 ⇒ 拒答，并且<b>说得清是哪一类命中的</b>（审计要记分类）；
 *   <li>敏感问题集换了标点、空格、全角之后<b>仍然命中</b>（否则这份清单形同虚设）。
 * </ol>
 *
 * <p>第三条"关掉某一类之后同一句话放行"在 {@code GuardServiceTest} 里 —— 它要过库， 而这里是纯内存的匹配口径。
 */
class GuardSnapshotTest {

  private static final UUID POLITICS = UUID.randomUUID();
  private static final UUID VIOLENCE = UUID.randomUUID();

  private static final UUID KEYWORD_RULE = UUID.randomUUID();
  private static final UUID QUESTION_RULE = UUID.randomUUID();

  private static GuardSettings settings(boolean categoryRefusalEnabled) {
    return new GuardSettings(true, "统一话术", true, categoryRefusalEnabled, Instant.now());
  }

  private static GuardSnapshot.Pattern keyword(String content) {
    return pattern(KEYWORD_RULE, POLITICS, "政治", GuardRuleKind.KEYWORD, content, "政治专用话术");
  }

  private static GuardSnapshot.Pattern question(String content) {
    return pattern(QUESTION_RULE, VIOLENCE, "暴力", GuardRuleKind.QUESTION, content, null);
  }

  private static GuardSnapshot.Pattern pattern(
      UUID ruleId,
      UUID categoryId,
      String categoryName,
      GuardRuleKind kind,
      String content,
      String refusalText) {
    String normalized = GuardText.normalize(content, kind);
    return new GuardSnapshot.Pattern(
        ruleId,
        categoryId,
        categoryId == POLITICS ? "POLITICS" : "VIOLENCE",
        categoryName,
        kind,
        normalized,
        GuardText.hashPrefix(normalized),
        refusalText);
  }

  private static GuardSnapshot snapshot(List<GuardSnapshot.Pattern> patterns) {
    return new GuardSnapshot(settings(false), List.of(), List.of(), patterns);
  }

  @Test
  @DisplayName("关键词命中：包含即命中，命中里带着分类与哈希前缀")
  void keywordHitCarriesCategoryAndHash() {
    GuardSnapshot snapshot = snapshot(List.of(keyword("绝密")));

    List<GuardHit> hits = snapshot.match("请把绝密材料发我");

    assertThat(hits).hasSize(1);
    GuardHit hit = hits.get(0);
    assertThat(hit.categoryCode()).isEqualTo("POLITICS");
    assertThat(hit.categoryName()).isEqualTo("政治");
    assertThat(hit.kind()).isEqualTo(GuardRuleKind.KEYWORD);
    assertThat(hit.ruleId()).isEqualTo(KEYWORD_RULE);
    assertThat(hit.contentHashPrefix()).hasSize(8);
  }

  @Test
  @DisplayName("★ 敏感问题集：换了标点、空格与全角之后仍然命中")
  void questionHitSurvivesPunctuationAndWidth() {
    GuardSnapshot snapshot = snapshot(List.of(question("这份材料能发给我吗")));

    assertThat(snapshot.match("这份材料能发给我吗？")).hasSize(1);
    assertThat(snapshot.match("这份材料能发给我吗?")).hasSize(1);
    assertThat(snapshot.match("这份 材料　能发给我吗！")).hasSize(1);
  }

  @Test
  @DisplayName("不命中的输入一律放行（空列表 = 通过）")
  void benignInputIsNotMatched() {
    GuardSnapshot snapshot = snapshot(List.of(keyword("绝密"), question("这份材料能发给我吗")));

    assertThat(snapshot.match("差旅住宿标准是什么？")).isEmpty();
    assertThat(snapshot.match(null)).isEmpty();
    assertThat(snapshot.match("   ")).isEmpty();
  }

  @Test
  @DisplayName("★ 归一化后为空的规则永不命中（否则 contains(\"\") 会拦住全平台）")
  void emptyNormalizedPatternNeverMatches() {
    GuardSnapshot.Pattern empty =
        new GuardSnapshot.Pattern(
            UUID.randomUUID(),
            POLITICS,
            "POLITICS",
            "政治",
            GuardRuleKind.KEYWORD,
            "",
            "00000000",
            null);
    GuardSnapshot snapshot = snapshot(List.of(empty));

    assertThat(snapshot.match("任何一句话")).isEmpty();
  }

  @Test
  @DisplayName("没有规则时一律放行（空规则表 ≠ 全拦）")
  void emptyPatternListPassesEverything() {
    assertThat(snapshot(List.of()).match("任何一句话")).isEmpty();
  }

  @Test
  @DisplayName("★ 默认回统一话术；只有开关打开时才按分类取，且取不到就回落")
  void refusalTextFollowsTheCategorySwitch() {
    GuardSnapshot unified = snapshot(List.of(keyword("绝密")));
    assertThat(unified.refusalFor(unified.match("绝密"))).isEqualTo("统一话术");

    GuardSnapshot perCategory =
        new GuardSnapshot(
            settings(true), List.of(), List.of(), List.of(keyword("绝密"), question("测试问题")));
    assertThat(perCategory.refusalFor(perCategory.match("绝密"))).isEqualTo("政治专用话术");

    // 问题集那条没有分类话术（refusalText = null）⇒ 回落到统一话术，绝不回空串
    assertThat(perCategory.refusalFor(perCategory.match("测试问题"))).isEqualTo("统一话术");
  }
}
