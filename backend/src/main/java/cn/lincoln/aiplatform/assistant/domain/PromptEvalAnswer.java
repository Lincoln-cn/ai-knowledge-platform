package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次运行里，某道题的某一侧产出与评分。ROADMAP §11.63.3。
 *
 * <p>两侧（{@link EvalSide#SAVED} / {@link EvalSide#DRAFT}）各一条，({@code run_id}, {@code item_id},
 * {@code side}) 唯一 —— 同一个问题不允许出现两条"基线答案"，否则谁是基线就说不清了。
 *
 * <p>★ {@code passCount} / {@code failCount} 是**计数**不是布尔：一条设定要求"必须标出第几条"， 答案可能标了 3 处、漏了 1
 * 处。布尔读数会把这些差别抹平。
 *
 * <p>★ 评分是"后加上去的"：跑完只产出答案，人再逐题评（可改、可清空）。 所以 {@code verdict} 允许为 null —— 它表示"还没评"，不是"差不多"。
 */
@Entity
@Table(name = "as_prompt_eval_answer")
public class PromptEvalAnswer {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "item_id", nullable = false)
  private UUID itemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "side", nullable = false, length = 8)
  private EvalSide side;

  @Column(name = "answer", nullable = false)
  private String answer = "";

  @Column(name = "elapsed_ms")
  private Integer elapsedMs;

  @Column(name = "pass_count", nullable = false)
  private int passCount;

  @Column(name = "fail_count", nullable = false)
  private int failCount;

  /**
   * 每条断言的判定快照（JSON 数组，见 {@link PromptAssertions}）。
   *
   * <p>★ 存快照而不是每次按当前定义重算：断言定义可以随时改，改了不该改写历史运行的读数 —— 要看新定义的结果就重跑一次（与 {@code draft_text} 整段存正文同一条理由）。
   *
   * <p>★ 空数组 = 跑的时候这道题没设断言。这时 {@code passCount}/{@code failCount} 都是 0， 但**不许显示成「0 通过 0 失败」**——
   * 那会把"没测"读成"测了且都好"。
   */
  @Column(name = "assertion_results", nullable = false)
  private String assertionResults = "[]";

  @Enumerated(EnumType.STRING)
  @Column(name = "verdict", length = 8)
  private EvalVerdict verdict;

  @Column(name = "note", length = 500)
  private String note;

  @Column(name = "scored_by")
  private UUID scoredBy;

  @Column(name = "scored_at")
  private Instant scoredAt;

  protected PromptEvalAnswer() {}

  public PromptEvalAnswer(UUID id, UUID runId, UUID itemId, EvalSide side) {
    this.id = id;
    this.runId = runId;
    this.itemId = itemId;
    this.side = side;
  }

  public void recordAnswer(String answer, Integer elapsedMs) {
    this.answer = answer == null ? "" : answer;
    this.elapsedMs = elapsedMs;
  }

  /**
   * 记录第 1 层机械断言的判定结果。
   *
   * <p>★ 计数由结果算出来（不单独传）：两处各传一次，迟早会出现"结果是 3 条、计数写 2"的错位， 而错位以后读数就没有意义了。
   *
   * @param results 每条断言的判定；空列表表示这道题跑的时候没设断言
   */
  public void recordAssertions(List<PromptAssertions.Result> results) {
    List<PromptAssertions.Result> safe = results == null ? List.of() : results;
    this.assertionResults = PromptAssertions.writeResults(safe);
    this.passCount = PromptAssertions.passedCount(safe);
    this.failCount = PromptAssertions.failedCount(safe);
  }

  public List<PromptAssertions.Result> assertionResults() {
    return PromptAssertions.parseResults(assertionResults);
  }

  /** 人工评一档（{@code note} 可空）。 */
  public void rate(EvalVerdict verdict, String note, UUID by, Instant at) {
    this.verdict = verdict;
    this.note = note;
    this.scoredBy = by;
    this.scoredAt = at;
  }

  /** 清空评价（改主意、或误点）。 */
  public void clearVerdict() {
    this.verdict = null;
    this.note = null;
    this.scoredBy = null;
    this.scoredAt = null;
  }

  public UUID id() {
    return id;
  }

  public UUID runId() {
    return runId;
  }

  public UUID itemId() {
    return itemId;
  }

  public EvalSide side() {
    return side;
  }

  public String answer() {
    return answer;
  }

  public Integer elapsedMs() {
    return elapsedMs;
  }

  public int passCount() {
    return passCount;
  }

  public int failCount() {
    return failCount;
  }

  public EvalVerdict verdict() {
    return verdict;
  }

  public String note() {
    return note;
  }

  public UUID scoredBy() {
    return scoredBy;
  }

  public Instant scoredAt() {
    return scoredAt;
  }
}
