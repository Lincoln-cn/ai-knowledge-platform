package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次评测运行：基线版 × 草稿正文 × 全部问题。ROADMAP §11.63.3。
 *
 * <p>★ {@code draftText} 存**整段正文**，不存版本号：评测常常发生在"还没保存"的那一版上， 而一旦保存它就变成一个新版本 ——
 * 存版本号只能记住"当时是哪一版"，存正文才能复现"当时测的到底是什么"。
 *
 * <p>★ {@code itemTotal} 在开始时定死：题中途被删不该让进度条失真（"跑到 8/10"和"跑到 8/12" 在用户眼里是两件事）。
 */
@Entity
@Table(name = "as_prompt_eval_run")
public class PromptEvalRun {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "set_id", nullable = false)
  private UUID setId;

  @Column(name = "base_version_no")
  private Integer baseVersionNo;

  @Column(name = "draft_text", nullable = false)
  private String draftText;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private PromptEvalStatus status = PromptEvalStatus.RUNNING;

  @Column(name = "item_total", nullable = false)
  private int itemTotal;

  @Column(name = "item_done", nullable = false)
  private int itemDone;

  @Column(name = "fail_reason", length = 500)
  private String failReason;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "started_by")
  private UUID startedBy;

  protected PromptEvalRun() {}

  public PromptEvalRun(
      UUID id,
      UUID setId,
      Integer baseVersionNo,
      String draftText,
      int itemTotal,
      Instant startedAt,
      UUID startedBy) {
    this.id = id;
    this.setId = setId;
    this.baseVersionNo = baseVersionNo;
    this.draftText = draftText;
    this.itemTotal = itemTotal;
    this.itemDone = 0;
    this.status = PromptEvalStatus.RUNNING;
    this.startedAt = startedAt;
    this.startedBy = startedBy;
  }

  /** 跑完一道题（两侧都写完了才算一道）。 */
  public void advance(int done) {
    this.itemDone = Math.min(done, itemTotal);
  }

  public void finish(Instant at) {
    this.status = PromptEvalStatus.DONE;
    this.itemDone = itemTotal;
    this.finishedAt = at;
  }

  public void fail(String reason, Instant at) {
    this.status = PromptEvalStatus.FAILED;
    this.failReason = reason;
    this.finishedAt = at;
  }

  public UUID id() {
    return id;
  }

  public UUID setId() {
    return setId;
  }

  public Integer baseVersionNo() {
    return baseVersionNo;
  }

  public String draftText() {
    return draftText;
  }

  public PromptEvalStatus status() {
    return status;
  }

  public int itemTotal() {
    return itemTotal;
  }

  public int itemDone() {
    return itemDone;
  }

  public String failReason() {
    return failReason;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }

  public UUID startedBy() {
    return startedBy;
  }
}
