package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 评测集里的一道题。ROADMAP §11.63.3。
 *
 * <p>★ {@code ordinal} 是显式存的，不靠 id 或插入时间排序： 结果表要按题的原始顺序看 —— "第 3 题"在两次运行里必须是同一道题， 否则"上一次第 3
 * 题是更好的"这句话就无法追溯。
 */
@Entity
@Table(name = "as_prompt_eval_item")
public class PromptEvalItem {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "set_id", nullable = false)
  private UUID setId;

  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  @Column(name = "question", nullable = false, length = 1000)
  private String question;

  protected PromptEvalItem() {}

  public PromptEvalItem(UUID id, UUID setId, int ordinal, String question) {
    this.id = id;
    this.setId = setId;
    this.ordinal = ordinal;
    this.question = question;
  }

  public UUID id() {
    return id;
  }

  public UUID setId() {
    return setId;
  }

  public int ordinal() {
    return ordinal;
  }

  public String question() {
    return question;
  }
}
